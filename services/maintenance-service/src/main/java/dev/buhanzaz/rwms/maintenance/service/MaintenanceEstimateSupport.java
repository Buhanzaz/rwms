package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateLine;
import dev.buhanzaz.rwms.maintenance.domain.EstimatePlanStage;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.mapper.CatalogFurnitureReferenceMapper;
import dev.buhanzaz.rwms.maintenance.repository.CatalogLinkRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Provides canonical estimate-line, repair-plan and routing validation mechanics. */
@Service
final class MaintenanceEstimateSupport {
  private final CatalogVersionRepository catalogVersions;
  private final CatalogNodeRepository catalogNodes;
  private final CatalogLinkRepository catalogLinks;
  private final MaintenanceEstimateRepository estimates;
  private final RepairStageRepository repairStages;
  private final CatalogFurnitureReferenceMapper catalogFurnitureMapper;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceCatalogModelSupport catalogModelSupport;
  private final MaintenanceCommandSupport commandSupport;
  private final MaintenanceEstimateModelSupport estimateModelSupport;
  private final MaintenanceEstimateFurnitureSupport furnitureSupport;

  MaintenanceEstimateSupport(
      CatalogVersionRepository catalogVersions,
      CatalogNodeRepository catalogNodes,
      CatalogLinkRepository catalogLinks,
      MaintenanceEstimateRepository estimates,
      RepairStageRepository repairStages,
      CatalogFurnitureReferenceMapper catalogFurnitureMapper,
      MaintenanceDependencyGateway dependencies,
      MaintenanceCatalogModelSupport catalogModelSupport,
      MaintenanceCommandSupport commandSupport,
      MaintenanceEstimateModelSupport estimateModelSupport,
      MaintenanceEstimateFurnitureSupport furnitureSupport) {
    this.catalogVersions = catalogVersions;
    this.catalogNodes = catalogNodes;
    this.catalogLinks = catalogLinks;
    this.estimates = estimates;
    this.repairStages = repairStages;
    this.catalogFurnitureMapper = catalogFurnitureMapper;
    this.dependencies = dependencies;
    this.catalogModelSupport = catalogModelSupport;
    this.commandSupport = commandSupport;
    this.estimateModelSupport = estimateModelSupport;
    this.furnitureSupport = furnitureSupport;
  }

  protected List<PlanStageInput> storedPlanInputs(List<EstimatePlanStage> estimatePlan) {
    return estimatePlan.stream()
        .map(value -> new PlanStageInput(
            value.getId(), value.getStageKind(), value.getStageNo(),
            new RoutingSnapshot(value.getRoutingQueueId(), value.getRoutingQueueName(),
                value.getRoutingQueueType()),
            commandSupport.readList(value.getIncludedLineIds(), UUID.class),
            value.getPrimaryLineId(),
            value.getGroupComment(),
            value.getTaskDeadline()))
        .toList();
  }

  protected FurnitureAccountingMode resolveFurnitureAccounting(
      EstimateCompletionPreflight preflight, CompleteEstimateRequest request) {
    return furnitureSupport.resolve(
        preflight.rentalItemId(),
        preflight.warehouseId(),
        !preflight.furniture().isEmpty(),
        request.allowsUnaccountedFurniture());
  }

  protected void validateEstimateRouting(
      List<CatalogNodeSnapshot> lineSnapshots,
      List<PlanStageInput> planInputs) {
    List<RoutingSnapshot> catalogRouting =
        lineSnapshots.stream()
            .filter(snapshot -> snapshot.nodeType() == CatalogNodeType.WORK)
            .map(CatalogNodeSnapshot::routing)
            .toList();
    if (catalogRouting.contains(null)) {
      throw MaintenanceCommandSupport.invalid("Every estimate work must inherit or define a catalog queue");
    }
    Set<RoutingIdentity> expected =
        catalogRouting.stream()
            .map(MaintenanceEstimateSupport::routingIdentity)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<RoutingIdentity> actual =
        planInputs.stream()
            .filter(stage -> stage.kind() == dev.buhanzaz.rwms.maintenance.domain.RepairStageKind.REPAIR_WORK)
            .map(stage -> routingIdentity(stage.routing()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (!actual.containsAll(expected)) {
      throw MaintenanceCommandSupport.invalid(
          "Catalog repair-work stages must use queue identities inherited from the global catalog");
    }
  }

  protected static RoutingIdentity routingIdentity(RoutingSnapshot routing) {
    if (routing == null) {
      throw new IllegalArgumentException("Routing snapshot is required");
    }
    return new RoutingIdentity(routing.queueId());
  }

  protected CatalogNodeSnapshot canonicalCatalogSnapshot(
      MaintenanceEstimate estimate, CatalogNodeSnapshot submitted) {
    if (submitted == null) return null;
    if (!estimate.getCatalogVersionId().equals(submitted.catalogVersionId())) {
      throw MaintenanceCommandSupport.invalid("Estimate line must use the catalog version captured by the estimate");
    }
    return canonicalCatalogSnapshot(
        estimate.getWarehouseId(), estimate.getCatalogVersionId(), submitted);
  }

  protected CatalogNodeSnapshot canonicalCatalogSnapshot(
      UUID warehouseId,
      UUID catalogVersionId,
      CatalogNodeSnapshot submitted) {
    if (submitted == null) return null;
    if (!catalogVersionId.equals(submitted.catalogVersionId())) {
      throw MaintenanceCommandSupport.invalid("Repair line must use one canonical catalog version");
    }
    CatalogVersion version = catalogVersions.findById(catalogVersionId)
        .orElseThrow(() -> MaintenanceCommandSupport.invalid("Estimate catalog version is unavailable"));
    Objects.requireNonNull(warehouseId, "Estimate warehouse is required");
    if (version.getState() == CatalogVersionState.DRAFT) {
      throw MaintenanceCommandSupport.invalid("Estimate lines cannot use a draft catalog version");
    }
    CatalogNode node = catalogNodes.findByCatalogVersionIdAndId(
            catalogVersionId, submitted.nodeId())
        .orElseThrow(() -> MaintenanceCommandSupport.invalid("Estimate catalog node is unavailable"));
    List<CatalogNode> versionNodes =
        catalogNodes.findAllByCatalogVersionIdOrderByNameAscIdAsc(node.getCatalogVersionId());
    Map<UUID, CatalogNode> nodesById =
        versionNodes.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    CatalogNode::getId, value -> value));
    Map<UUID, List<UUID>> incomingLinks = CatalogRoutingResolver.incoming(
        catalogLinks.findAllByCatalogVersionIdOrderBySortOrderAscIdAsc(catalogVersionId));
    if (!node.isActive() || !node.isIncludeInEstimate()) {
      throw MaintenanceCommandSupport.invalid("Estimate catalog node is not active for estimates");
    }
    CatalogNodeType type = CatalogNodeType.valueOf(node.getNodeType());
    if (type != CatalogNodeType.WORK
        && type != CatalogNodeType.MATERIAL
        && type != CatalogNodeType.OPTION) {
      throw MaintenanceCommandSupport.invalid("Catalog node type cannot be added to an estimate");
    }
    if (type == CatalogNodeType.MATERIAL && node.getFurnitureEquipmentId() == null) {
      if (belongsToFurnitureTree(node, nodesById)) {
        throw MaintenanceCommandSupport.invalid(
            "Furniture material must be linked to additional equipment before use in an estimate");
      }
    }
    return new CatalogNodeSnapshot(
        node.getCatalogVersionId(),
        node.getId(),
        type,
        node.getName(),
        node.getUnit(),
        commandSupport.money(node.getPriceMinor()),
        node.getDurationMinutes(),
        CatalogRoutingResolver.snapshot(
            CatalogRoutingResolver.resolve(
                node.getId(),
                nodesById,
                CatalogNode::getParentNodeId,
                MaintenanceCatalogSupport::directRouteValue,
                incomingLinks)),
        node.getFurnitureEquipmentId() == null
            ? null
            : catalogFurnitureMapper.toReference(node),
        node.isForcesCapitalRepair(),
        node.getCharacteristicId() == null
            ? null
            : new CabinCharacteristicReference(
                node.getCharacteristicId(), node.getCharacteristicName()));
  }

  protected static EstimateLineType canonicalLineType(
      CatalogNodeSnapshot catalogSnapshot, EstimateLineType submitted) {
    if (submitted == null) {
      throw MaintenanceCommandSupport.invalid("Estimate line type is required");
    }
    if (catalogSnapshot == null) {
      return submitted;
    }
    EstimateLineType canonical = catalogSnapshot.nodeType() == CatalogNodeType.WORK
        ? EstimateLineType.WORK
        : EstimateLineType.MATERIAL;
    if (submitted != canonical) {
      throw MaintenanceCommandSupport.invalid("Estimate line type must match its active catalog position");
    }
    return canonical;
  }

  protected static String canonicalLineUnit(CatalogNodeSnapshot catalogSnapshot, String submitted) {
    if (catalogSnapshot != null) {
      return catalogSnapshot.unit();
    }
    if (submitted == null || submitted.isBlank()) {
      throw MaintenanceCommandSupport.invalid("Custom estimate line unit is required");
    }
    String normalized = submitted.trim();
    if (normalized.length() > 32) {
      throw MaintenanceCommandSupport.invalid("Custom estimate line unit is too long");
    }
    return normalized;
  }

  protected List<FurnitureQuantity> furnitureQuantities(MaintenanceRepair repair) {
    if (repair.getEstimateId() != null) {
      return estimateFurnitureQuantities(estimateModelSupport.requireEstimate(repair.getEstimateId()));
    }
    Map<UUID, FurnitureQuantity> quantities = new HashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      java.util.stream.Stream.concat(
              commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
              commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
          .forEach(
              line ->
                  addFurnitureQuantity(
                      quantities, line.catalogSnapshot(), new BigDecimal(line.quantity())));
    }
    return orderedFurnitureQuantities(quantities);
  }

  protected List<FurnitureQuantity> estimateFurnitureQuantities(
      MaintenanceEstimate estimate) {
    Map<UUID, FurnitureQuantity> losses = new HashMap<>();
    for (EstimateLine line : estimateModelSupport.currentLines(estimate)) {
      if (line.getCatalogSnapshot() == null) continue;
      CatalogNodeSnapshot storedSnapshot = commandSupport.read(
          line.getCatalogSnapshot(), CatalogNodeSnapshot.class);
      CatalogNodeSnapshot canonicalSnapshot = canonicalCatalogSnapshot(estimate, storedSnapshot);
      addFurnitureQuantity(losses, canonicalSnapshot, line.getQuantity());
    }
    return orderedFurnitureQuantities(losses);
  }

  protected List<FurnitureQuantity> estimateFurnitureQuantities(
      MaintenanceEstimate estimate, List<EstimateLineInput> inputs) {
    Map<UUID, FurnitureQuantity> losses = new HashMap<>();
    for (EstimateLineInput input : inputs) {
      CatalogNodeSnapshot snapshot = canonicalCatalogSnapshot(
          estimate, input.catalogSnapshot());
      addFurnitureQuantity(losses, snapshot, new BigDecimal(input.quantity()));
    }
    return orderedFurnitureQuantities(losses);
  }

  protected static void addFurnitureQuantity(
      Map<UUID, FurnitureQuantity> losses,
      CatalogNodeSnapshot snapshot,
      BigDecimal quantity) {
    if (snapshot == null || snapshot.furnitureEquipment() == null || quantity.signum() == 0) {
      return;
    }
    final long wholeQuantity;
    try {
      wholeQuantity = quantity.longValueExact();
    } catch (ArithmeticException exception) {
      throw MaintenanceCommandSupport.invalid("Furniture quantity must be a whole number within the supported range");
    }
    FurnitureEquipmentReference equipment = snapshot.furnitureEquipment();
    FurnitureQuantity previous = losses.get(equipment.equipmentId());
    final long aggregateQuantity;
    try {
      aggregateQuantity = Math.addExact(previous == null ? 0 : previous.quantity(), wholeQuantity);
    } catch (ArithmeticException exception) {
      throw MaintenanceCommandSupport.invalid("Furniture quantity must be a whole number within the supported range");
    }
    losses.put(
        equipment.equipmentId(),
        new FurnitureQuantity(equipment.equipmentId(), aggregateQuantity));
  }

  protected static List<FurnitureQuantity> orderedFurnitureQuantities(
      Map<UUID, FurnitureQuantity> losses) {
    return losses.values().stream()
        .sorted(Comparator.comparing(value -> value.equipmentId().toString()))
        .toList();
  }

  protected Map<UUID, String> furnitureEquipmentNames(MaintenanceRepair repair) {
    Map<UUID, String> names = new HashMap<>();
    for (RepairStage stage : repairStages.findAllByRepairIdOrderByStageNo(repair.getId())) {
      java.util.stream.Stream.concat(
              commandSupport.readList(stage.getWorkLines(), EstimateLineResponse.class).stream(),
              commandSupport.readList(stage.getMaterialLines(), EstimateLineResponse.class).stream())
          .map(EstimateLineResponse::catalogSnapshot)
          .filter(Objects::nonNull)
          .map(CatalogNodeSnapshot::furnitureEquipment)
          .filter(Objects::nonNull)
          .forEach(
              equipment -> {
                String previous = names.putIfAbsent(
                    equipment.equipmentId(), equipment.equipmentName());
                if (previous != null && !previous.equals(equipment.equipmentName())) {
                  throw new IllegalStateException(
                      "Stored repair furniture has conflicting equipment names");
                }
              });
    }
    return Map.copyOf(names);
  }

  protected List<EstimateLineResponse> canonicalRepairLines(
      UUID warehouseId, List<EstimateLineInput> inputs) {
    if (inputs == null) throw MaintenanceCommandSupport.invalid("Repair lines are required");
    if (inputs.isEmpty()) return List.of();
    CatalogVersion active = catalogModelSupport.requireActiveCatalog(warehouseId);
    List<EstimateLineResponse> result = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    for (EstimateLineInput input : inputs) {
      if (input == null || input.id() == null || !ids.add(input.id())) {
        throw MaintenanceCommandSupport.invalid("Repair line IDs must be present and unique");
      }
      CatalogNodeSnapshot snapshot =
          input.catalogSnapshot() == null
              ? null
              : canonicalCatalogSnapshot(
                  warehouseId, active.getId(), input.catalogSnapshot());
      EstimateLineType lineType = canonicalLineType(snapshot, input.lineType());
      String unit = canonicalLineUnit(snapshot, input.unit());
      BigDecimal quantity;
      try {
        quantity = new BigDecimal(input.quantity());
      } catch (NumberFormatException exception) {
        throw MaintenanceCommandSupport.invalid("Repair line quantity is invalid");
      }
      long unitPriceMinor = commandSupport.moneyToMinor(input.unitPrice());
      int normativeMinutes =
          estimateLineNormativeMinutes(snapshot, lineType, input.normativeMinutes());
      result.add(
          new EstimateLineResponse(
              input.id(),
              snapshot,
              lineType,
              input.description(),
              unit,
              commandSupport.quantity(quantity),
              commandSupport.money(unitPriceMinor),
              commandSupport.money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              normativeMinutes,
              workLineComment(lineType, input.comment()),
              List.copyOf(input.mediaReferences())));
    }
    validateWorkLineMediaIsolation(result);
    return List.copyOf(result);
  }

  protected static void validateWorkLineMediaIsolation(
      List<EstimateLineResponse> lines) {
    Set<UUID> assigned = new HashSet<>();
    for (EstimateLineResponse line : lines) {
      if (line.lineType() != EstimateLineType.WORK
          && !line.mediaReferences().isEmpty()) {
        throw MaintenanceCommandSupport.invalid("Photos can only be assigned to work lines");
      }
      if (line.lineType() != EstimateLineType.WORK) continue;
      for (MediaReferenceInput reference : line.mediaReferences()) {
        if (!assigned.add(reference.mediaId())) {
          throw MaintenanceCommandSupport.invalid("One photo cannot be assigned to multiple work lines");
        }
      }
    }
  }

  protected static String workLineComment(
      EstimateLineType lineType, String comment) {
    if (lineType != EstimateLineType.WORK || comment == null || comment.isBlank()) {
      return null;
    }
    return comment.trim();
  }

  protected List<EstimateLineResponse> canonicalEstimateLines(
      MaintenanceEstimate estimate, List<EstimateLineInput> inputs) {
    if (inputs == null) throw MaintenanceCommandSupport.invalid("Estimate lines are required");
    List<EstimateLineResponse> result = new ArrayList<>();
    Set<UUID> ids = new HashSet<>();
    for (EstimateLineInput input : inputs) {
      if (input == null || input.id() == null || !ids.add(input.id())) {
        throw MaintenanceCommandSupport.invalid("Estimate line IDs must be present and unique");
      }
      CatalogNodeSnapshot snapshot =
          canonicalCatalogSnapshot(estimate, input.catalogSnapshot());
      EstimateLineType lineType = canonicalLineType(snapshot, input.lineType());
      String unit = canonicalLineUnit(snapshot, input.unit());
      BigDecimal quantity;
      try {
        quantity = new BigDecimal(input.quantity());
      } catch (NumberFormatException exception) {
        throw MaintenanceCommandSupport.invalid("Estimate line quantity is invalid");
      }
      long unitPriceMinor = commandSupport.moneyToMinor(input.unitPrice());
      int normativeMinutes =
          estimateLineNormativeMinutes(snapshot, lineType, input.normativeMinutes());
      result.add(
          new EstimateLineResponse(
              input.id(),
              snapshot,
              lineType,
              input.description(),
              unit,
              commandSupport.quantity(quantity),
              commandSupport.money(unitPriceMinor),
              commandSupport.money(quantity.multiply(BigDecimal.valueOf(unitPriceMinor))),
              normativeMinutes,
              workLineComment(lineType, input.comment()),
              List.copyOf(input.mediaReferences())));
    }
    return List.copyOf(result);
  }

  protected void validatePlanContent(
      List<EstimateLineResponse> lines, List<PlanStageInput> plan) {
    Map<UUID, EstimateLineResponse> lineById =
        lines.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    EstimateLineResponse::id,
                    value -> value,
                    (left, right) -> {
                      throw MaintenanceCommandSupport.invalid("Repair line IDs must be unique");
                    },
                    LinkedHashMap::new));
    Set<UUID> assigned = new HashSet<>();
    for (PlanStageInput stage : plan) {
      if (stage.includedLineIds() == null || stage.groupComment() == null) {
        throw MaintenanceCommandSupport.invalid("Repair plan stage content is required");
      }
      Set<UUID> stageIds = new HashSet<>();
      for (UUID lineId : stage.includedLineIds()) {
        EstimateLineResponse line = lineById.get(lineId);
        if (line == null) throw MaintenanceCommandSupport.invalid("Repair plan references an unavailable catalog line");
        if (!stageIds.add(lineId) || !assigned.add(lineId)) {
          throw MaintenanceCommandSupport.invalid("Each repair line can belong to only one stage");
        }
        if (line.catalogSnapshot() == null && isWorkLine(line)) {
          requireCustomWorkRouting(stage.routing());
        } else if (isWorkLine(line)
            && !routingIdentity(stage.routing())
                .equals(routingIdentity(line.catalogSnapshot().routing()))) {
          throw MaintenanceCommandSupport.invalid("Repair stage queue must match its catalog work queue");
        }
      }
      if (stage.primaryLineId() != null) {
        EstimateLineResponse primary = lineById.get(stage.primaryLineId());
        if (primary == null
            || !stageIds.contains(stage.primaryLineId())
            || !isWorkLine(primary)) {
          throw MaintenanceCommandSupport.invalid("Primary stage line must be an included work");
        }
      }
      if (stage.primaryLineId() == null
          && stageIds.stream().map(lineById::get).anyMatch(this::isWorkLine)) {
        throw MaintenanceCommandSupport.invalid("A repair-work stage containing work requires a primary work line");
      }
    }
    if (!assigned.equals(lineById.keySet())) {
      throw MaintenanceCommandSupport.invalid("Every repair line must belong to exactly one repair-work stage");
    }
  }

  protected List<PlanStageInput> resolvePlanContent(
      List<EstimateLineResponse> lines, List<PlanStageInput> plan) {
    if (lines.isEmpty()
        || plan.stream().anyMatch(stage -> !stage.includedLineIds().isEmpty())) {
      return plan;
    }
    Map<RoutingIdentity, List<PlanStageInput>> stagesByRoute =
        plan.stream()
        .collect(
                java.util.stream.Collectors.groupingBy(
                    stage -> routingIdentity(stage.routing()),
                    LinkedHashMap::new,
                    java.util.stream.Collectors.toList()));
    Map<UUID, List<UUID>> included = new LinkedHashMap<>();
    for (PlanStageInput stage : plan) included.put(stage.id(), new ArrayList<>());
    for (EstimateLineResponse line : lines) {
      if (line.catalogSnapshot() == null) {
        throw MaintenanceCommandSupport.invalid("Custom repair lines require explicit stage grouping and routing");
      }
      if (line.catalogSnapshot().routing() == null) {
        throw MaintenanceCommandSupport.invalid("Every catalog repair line must inherit a queue from the catalog builder");
      }
      List<PlanStageInput> candidates =
          stagesByRoute.get(routingIdentity(line.catalogSnapshot().routing()));
      if (candidates == null || candidates.size() != 1) {
        throw MaintenanceCommandSupport.invalid(
            "Catalog routing must identify exactly one repair-work stage; submit explicit line grouping");
      }
      included.get(candidates.getFirst().id()).add(line.id());
    }
    Map<UUID, EstimateLineResponse> lineById =
        lines.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    EstimateLineResponse::id, value -> value));
    return plan.stream()
        .map(
            stage -> {
              List<UUID> stageLineIds = List.copyOf(included.get(stage.id()));
              UUID primaryLineId =
                  stageLineIds.stream()
                      .filter(lineId -> isWorkLine(lineById.get(lineId)))
                      .findFirst()
                      .orElse(null);
              return new PlanStageInput(
                  stage.id(),
                  stage.kind(),
                  stage.order(),
                  stage.routing(),
                  stageLineIds,
                  primaryLineId,
                  stage.groupComment(),
                  stage.taskDeadline());
            })
        .toList();
  }

  protected boolean isWorkLine(EstimateLineResponse line) {
    return line.lineType() == EstimateLineType.WORK;
  }

  protected static void requireCustomWorkRouting(RoutingSnapshot routing) {
    String queueType = routing.queueType().trim().toUpperCase(Locale.ROOT);
    if (!"REPAIR".equals(queueType) && !"HOLDING".equals(queueType)) {
      throw MaintenanceCommandSupport.invalid(
          "Custom repair works can only use a repair or holding queue");
    }
  }

  protected void validateCustomRoutingStructure(
      List<EstimateLineResponse> lines, List<PlanStageInput> plan) {
    Set<UUID> customLineIds =
        lines.stream()
            .filter(line -> line.catalogSnapshot() == null)
            .map(EstimateLineResponse::id)
            .collect(java.util.stream.Collectors.toSet());
    List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        customRoutingRequirements(plan);
    if (requirements.isEmpty() && !customLineIds.isEmpty()) {
      throw MaintenanceCommandSupport.invalid("Every custom repair line requires a selected queue");
    }
  }

  protected void requireCustomRoutingReady(UUID warehouseId, List<PlanStageInput> plan) {
    commandSupport.requireNoCallerTransaction("validate task-board routing");
    List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        customRoutingRequirements(plan);
    if (requirements.isEmpty()) return;
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(
            warehouseId, requirements);
    if (preflight == null || !preflight.ready()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_ROUTING_INVALID",
          "A required global queue is not connected to this warehouse");
    }
  }

  protected static List<MaintenanceDependencyGateway.RoutingQueueRequirement>
      customRoutingRequirements(List<PlanStageInput> plan) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    for (PlanStageInput stage : plan) {
      String queueType = stage.routing().queueType().trim().toUpperCase(Locale.ROOT);
      requireCustomWorkRouting(stage.routing());
      MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
          new MaintenanceDependencyGateway.RoutingQueueRequirement(
              stage.routing().queueId(), queueType);
      MaintenanceDependencyGateway.RoutingQueueRequirement previous =
          requirements.putIfAbsent(requirement.queueDefinitionId(), requirement);
      if (previous != null && !previous.equals(requirement)) {
        throw MaintenanceCommandSupport.invalid("One custom-line queue ID has conflicting routing snapshots");
      }
    }
    return List.copyOf(requirements.values());
  }

  protected static boolean belongsToFurnitureTree(
      UUID nodeId,
      Set<UUID> furnitureRoots,
      Map<UUID, CatalogNodeInput> nodesById) {
    Set<UUID> visited = new HashSet<>();
    CatalogNodeInput current = nodesById.get(nodeId);
    while (current != null && visited.add(current.id())) {
      if (furnitureRoots.contains(current.id())) return true;
      current = current.parentNodeId() == null ? null : nodesById.get(current.parentNodeId());
    }
    return false;
  }

  protected static boolean belongsToFurnitureTree(
      CatalogNode node, Map<UUID, CatalogNode> nodesById) {
    Set<UUID> visited = new HashSet<>();
    CatalogNode current = node;
    while (current != null && visited.add(current.getId())) {
      if (current.isFurnitureCategory()) return true;
      current = current.getParentNodeId() == null
          ? null
          : nodesById.get(current.getParentNodeId());
    }
    return false;
  }

  protected void validateEstimatePlan(
      List<EstimateLineInput> lines, List<PlanStageInput> plan) {
    if (lines == null || plan == null) throw new IllegalArgumentException("Estimate lines and plan are required");
    validatePlan(plan, lines.isEmpty());
    if (lines.isEmpty() && !plan.isEmpty()) {
      throw MaintenanceCommandSupport.invalid("An empty estimate cannot contain repair stages");
    }
    if (!lines.isEmpty() && plan.isEmpty()) {
      throw MaintenanceCommandSupport.invalid("A non-empty estimate requires a repair plan");
    }
  }

  protected void validatePlan(List<PlanStageInput> plan, boolean allowEmpty) {
    if (plan == null || (!allowEmpty && plan.isEmpty())) {
      throw MaintenanceCommandSupport.invalid("A repair plan requires at least one stage");
    }
    Set<UUID> ids = new HashSet<>();
    Set<Integer> orders = new HashSet<>();
    Set<OffsetDateTime> deadlines = new HashSet<>();
    for (int index = 0; index < plan.size(); index++) {
      PlanStageInput stage = plan.get(index);
      if (stage == null || stage.id() == null || stage.routing() == null) {
        throw MaintenanceCommandSupport.invalid("Every plan stage requires identity and routing");
      }
      if (stage.kind() != RepairStageKind.REPAIR_WORK) {
        throw MaintenanceCommandSupport.invalid("Repair plans can contain REPAIR_WORK stages only");
      }
      if (!ids.add(stage.id())) throw MaintenanceCommandSupport.invalid("Plan stage IDs must be unique");
      if (!orders.add(stage.order())) throw MaintenanceCommandSupport.invalid("Plan stage order must be unique");
      if (stage.order() != index) {
        throw MaintenanceCommandSupport.invalid("Plan stage order must be contiguous and match submitted order");
      }
      if (stage.taskDeadline() != null) deadlines.add(stage.taskDeadline());
    }
    if (deadlines.size() > 1) {
      throw MaintenanceCommandSupport.invalid("Repair plan stage deadlines must be absent or one identical timestamp");
    }
  }

  protected static int taskWorkDurationMinutes(CatalogNodeSnapshot catalog) {
    Integer duration = catalog.durationMinutes();
    if (duration == null || duration < 1) {
      throw new IllegalStateException(
          "Stored catalog work has no positive planned duration");
    }
    return duration;
  }

  protected static int estimateLineNormativeMinutes(
      CatalogNodeSnapshot catalog, EstimateLineType lineType, Integer submittedMinutes) {
    if (catalog != null) {
      if (catalog.nodeType().name().equals(lineType.name())) {
        return lineType == EstimateLineType.MATERIAL
            ? 0
            : taskWorkDurationMinutes(catalog);
      }
      throw MaintenanceCommandSupport.invalid("Catalog snapshot type does not match the estimate line");
    }
    if (lineType == EstimateLineType.MATERIAL) return 0;
    if (submittedMinutes == null || submittedMinutes < 1 || submittedMinutes > 525600) {
      throw MaintenanceCommandSupport.invalid("Custom repair work requires execution time in minutes");
    }
    return submittedMinutes;
  }

  protected static BigDecimal taskLineQuantity(EstimateLineResponse line) {
    if (line == null || line.quantity() == null) {
      throw new IllegalStateException("Worker task line quantity is missing");
    }
    try {
      BigDecimal quantity = new BigDecimal(line.quantity());
      if (quantity.signum() <= 0 || quantity.scale() > 3) {
        throw new IllegalStateException("Worker task line quantity is invalid");
      }
      double snapshotQuantity = quantity.doubleValue();
      if (!Double.isFinite(snapshotQuantity)) {
        throw new IllegalStateException("Worker task line quantity exceeds the supported range");
      }
      return quantity;
    } catch (NumberFormatException exception) {
      throw new IllegalStateException("Worker task line quantity is invalid", exception);
    }
  }

  protected static String taskLineName(EstimateLineResponse line) {
    if (line == null || line.description() == null || line.description().isBlank()) {
      throw new IllegalStateException("Worker task line name is missing");
    }
    return line.description().trim();
  }

  protected static String taskLineComment(String comment) {
    return comment == null || comment.isBlank() ? null : comment.trim();
  }
}
