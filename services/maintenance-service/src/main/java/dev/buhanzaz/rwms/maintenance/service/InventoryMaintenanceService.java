package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSource;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperation;
import dev.buhanzaz.rwms.maintenance.domain.InventoryRepairSourceOperationId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventStore;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceProjectionSnapshotFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.CatalogNodeRepository;
import dev.buhanzaz.rwms.maintenance.repository.CatalogVersionRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryRepairSourceOperationRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/** Exact-scope Stage 7 boundary. Inventory can freeze and publish, but cannot lease or sync tasks. */
@Service
public class InventoryMaintenanceService {
  private static final Set<String> REPAIR_QUEUE_SOURCES = Set.of(
      "FREE", "WAREHOUSE", "OWN_NEEDS", "AFTER_RENT");
  private static final UUID INVENTORY_ACTOR = UUID.nameUUIDFromBytes(
      "inventory-service".getBytes(StandardCharsets.UTF_8));

  private final CatalogVersionRepository catalogs;
  private final CatalogNodeRepository catalogNodes;
  private final InventoryRepairSourceOperationRepository sourceOperations;
  private final InventoryRepairSourceRepository sources;
  private final RentalItemFactProjectionRepository rentalItems;
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceRepairRepository repairs;
  private final RepairStageRepository repairStages;
  private final MaintenanceEventStore events;
  private final MaintenanceEventFactFactory eventFacts;
  private final MaintenanceProjectionSnapshotFactory projectionSnapshots;
  private final InventoryRepairReconciliationWriter reconciliations;
  private final MaintenanceReconciliationStore ownerProofs;
  private final InventoryRepairSourceOperationRegistrar sourceRegistrar;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;

  public InventoryMaintenanceService(
      CatalogVersionRepository catalogs,
      CatalogNodeRepository catalogNodes,
      InventoryRepairSourceOperationRepository sourceOperations,
      InventoryRepairSourceRepository sources,
      RentalItemFactProjectionRepository rentalItems,
      MediaFactProjectionRepository mediaFacts,
      MaintenanceRepairRepository repairs,
      RepairStageRepository repairStages,
      MaintenanceEventStore events,
      MaintenanceEventFactFactory eventFacts,
      MaintenanceProjectionSnapshotFactory projectionSnapshots,
      InventoryRepairReconciliationWriter reconciliations,
      MaintenanceReconciliationStore ownerProofs,
      InventoryRepairSourceOperationRegistrar sourceRegistrar,
      MaintenanceDependencyGateway dependencies,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper) {
    this.catalogs = catalogs;
    this.catalogNodes = catalogNodes;
    this.sourceOperations = sourceOperations;
    this.sources = sources;
    this.rentalItems = rentalItems;
    this.mediaFacts = mediaFacts;
    this.repairs = repairs;
    this.repairStages = repairStages;
    this.events = events;
    this.eventFacts = eventFacts;
    this.projectionSnapshots = projectionSnapshots;
    this.reconciliations = reconciliations;
    this.ownerProofs = ownerProofs;
    this.sourceRegistrar = sourceRegistrar;
    this.dependencies = dependencies;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
  }

  @Transactional
  public FreezeResult freeze(FreezeInventoryPlanRequest request) {
    if (request.sourceRevision() < 1) {
      throw invalid("Inventory source revision must be at least one");
    }
    String requestFingerprint = frozenHash(request);
    InventoryRepairSource replay = sources
        .findByInventoryIdAndFindingId(request.inventoryId(), request.findingId())
        .orElse(null);
    if (replay != null) {
      if (!requestFingerprint.equals(replay.getPlanRequestSha256())) {
        throw conflict("Inventory source is already bound to a different frozen plan request");
      }
      return new FreezeResult(frozenResponse(replay), true);
    }

    CatalogVersion catalog =
        catalogs
            .findFirstByStateOrderByActivatedAtDescCreatedAtDesc(CatalogVersionState.ACTIVE)
            .orElseThrow(() -> invalid("Global maintenance catalog has no active version"));
    Map<UUID, CatalogNode> nodes = catalogNodes
        .findAllByCatalogVersionIdOrderByNameAscIdAsc(catalog.getId()).stream()
        .collect(Collectors.toMap(CatalogNode::getId, Function.identity()));
    FrozenInventoryPlanSnapshot snapshot = freezeSnapshot(request, catalog, nodes);
    String fingerprint = frozenHash(snapshot);

    InventoryRepairSourceOperationId operationId =
        new InventoryRepairSourceOperationId(request.inventoryId(), request.findingId());
    String canonicalRequestFingerprint = requestFingerprint;
    registerConcurrentSafe(
        () -> sourceRegistrar.register(operationId, canonicalRequestFingerprint));
    InventoryRepairSourceOperation operation = sourceOperations.findByIdForUpdate(operationId)
        .orElseThrow(() -> new IllegalStateException(
            "Inventory repair source operation registration failed"));
    if (!operation.getRequestSha256().equals(requestFingerprint)) {
      throw conflict("Inventory source is already bound to a different frozen plan request");
    }
    InventoryRepairSource existing = sources
        .findBySourceForUpdate(request.inventoryId(), request.findingId())
        .orElse(null);
    if (existing != null) {
      if (!requestFingerprint.equals(existing.getPlanRequestSha256())) {
        throw conflict("Inventory source is already bound to a different frozen plan request");
      }
      return new FreezeResult(frozenResponse(existing), true);
    }
    InventoryRepairSource saved = sources.saveAndFlush(InventoryRepairSource.freeze(
        request.inventoryId(), request.findingId(), request.sourceRevision(), request.warehouseId(),
        catalog.getId(), requestFingerprint, fingerprint, write(snapshot),
        write(sourceMedia(snapshot))));
    return new FreezeResult(frozenResponse(saved), false);
  }

  @Transactional(readOnly = true)
  public InventoryRepairSnapshotsResponse repairSnapshots(
      InventoryRepairSnapshotRequest request) {
    List<UUID> assetIds = request.assetIds().stream().sorted().toList();
    List<MaintenanceRepair> selected =
        repairs.findAllByRentalItemIdInOrderByRentalItemIdAscIdAsc(assetIds).stream()
            .sorted(Comparator.comparing(MaintenanceRepair::getRentalItemId)
                .thenComparing(MaintenanceRepair::getId))
            .toList();
    List<UUID> repairIds = selected.stream().map(MaintenanceRepair::getId).toList();
    Map<UUID, List<RepairStage>> stagesByRepair = new LinkedHashMap<>();
    if (!repairIds.isEmpty()) {
      for (RepairStage stage :
          repairStages.findAllByRepairIdInOrderByRepairIdAscStageNoAscIdAsc(repairIds)) {
        stagesByRepair.computeIfAbsent(stage.getRepairId(), ignored -> new ArrayList<>()).add(stage);
      }
      stagesByRepair.values().forEach(stages -> stages.sort(
          Comparator.comparingInt(RepairStage::getStageNo).thenComparing(RepairStage::getId)));
    }

    Map<UUID, List<InventoryRepairFact>> repairsByAsset = new LinkedHashMap<>();
    for (MaintenanceRepair repair : selected) {
      List<RepairStage> stages = stagesByRepair.getOrDefault(repair.getId(), List.of());
      InventoryRepairFact fact = new InventoryRepairFact(
          repair.getId(),
          repair.getRootRepairId() == null ? repair.getId() : repair.getRootRepairId(),
          repair.getOrigin(),
          repair.getKind(),
          repair.getExecutionState(),
          repair.getAcceptanceState(),
          hash(inventoryPlanSnapshot(stages)));
      repairsByAsset
          .computeIfAbsent(repair.getRentalItemId(), ignored -> new ArrayList<>())
          .add(fact);
    }
    List<InventoryRepairSnapshot> assets = assetIds.stream()
        .map(assetId -> new InventoryRepairSnapshot(
            assetId, List.copyOf(repairsByAsset.getOrDefault(assetId, List.of()))))
        .toList();
    return new InventoryRepairSnapshotsResponse(assets);
  }

  @Transactional
  public UpsertResult upsert(
      UUID inventoryId, UUID findingId, UpsertInventoryRepairRequest request) {
    InventoryRepairSource source = sources.findBySourceForUpdate(inventoryId, findingId)
        .orElseThrow(() -> new MaintenanceNotFoundException("Frozen inventory plan not found"));
    requireHistoricalSource(source, request);
    String sourceFingerprint = sourceFingerprint(inventoryId, findingId, request);
    if (source.getRepairId() != null) {
      if (!sourceFingerprint.equals(source.getSourceFingerprint())
          || !request.rentalItemId().equals(source.getRentalItemId())
          || !request.rentalItemVersion().equals(source.getRentalItemVersionSnapshot())) {
        throw conflict("Inventory source is already bound to different immutable repair input");
      }
      MaintenanceRepair existing = repairs.findById(source.getRepairId())
          .orElseThrow(() -> conflict("Inventory source points to a missing repair"));
      return result(existing, source, true);
    }

    RentalItemFactProjection rentalItem = rentalItems.findById(request.rentalItemId())
        .orElseThrow(() -> new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "Current rental-item fact is unavailable"));
    if (!request.warehouseId().equals(rentalItem.getWarehouseId())
        || request.rentalItemVersion() != rentalItem.getAggregateVersion()) {
      throw conflict("Current rental-item warehouse/version differs from the frozen source");
    }
    if (!REPAIR_QUEUE_SOURCES.contains(rentalItem.getAssetStatus())) {
      throw conflict("Current rental-item status is unsafe for maintenance queueing");
    }
    validateSnapshotMedia(findingId, request.warehouseId(), request.snapshot());
    requireWarehouseRoutingReady(
        request.warehouseId(), request.snapshot().stages());

    MaintenanceRepair draft = MaintenanceRepair.primary(
        request.warehouseId(), request.rentalItemId(), request.rentalItemVersion(), null,
        RepairOrigin.INVENTORY, request.dispatchDate(), "Инвентаризация", inventoryActorJson());
    draft.selectPriority(request.snapshot().priority());
    draft.selectMovementToShipment(
        request.snapshot().moveFromRepairRequired());
    draft.selectLogisticsPlanning(
        request.snapshot().logisticsPlanningMode(),
        request.snapshot().logisticsScheduledDate());
    draft.replaceCoverMediaId(request.snapshot().coverMediaId());
    MaintenanceRepair repair = repairs.saveAndFlush(draft);
    Map<UUID, List<EstimateLineResponse>> linesByQueue =
        inventoryRepairLines(inventoryId, findingId, request.snapshot());
    List<EstimateLineResponse> unrouted =
        linesByQueue.remove(new UUID(0, 0));
    if (unrouted != null && !unrouted.isEmpty()) {
      throw invalid(
          "Every inventory repair line must have an explicit global queue definition");
    }
    List<RepairStage> stages = request.snapshot().stages().stream()
        .map(
            stage -> {
              List<EstimateLineResponse> stageLines =
                  stage.kind() == RepairStageKind.REPAIR_WORK
                      ? linesByQueue.getOrDefault(stage.routing().queueId(), List.of())
                      : List.of();
              List<EstimateLineResponse> workLines = stageLines.stream()
                  .filter(line -> line.lineType() == EstimateLineType.WORK)
                  .toList();
              List<EstimateLineResponse> materialLines = stageLines.stream()
                  .filter(line -> line.lineType() == EstimateLineType.MATERIAL)
                  .toList();
              return new RepairStage(
                  stage.id(),
                  repair.getId(),
                  stage.order(),
                  stage.kind(),
                  stage.routing().queueId(),
                  stage.routing().queueName(),
                  stage.routing().queueType(),
                  write(workLines),
                  write(materialLines),
                  workLines.isEmpty() ? null : workLines.getFirst().id(),
                  "",
                  null);
            })
        .toList();
    Set<UUID> stageQueues = request.snapshot().stages().stream()
        .filter(stage -> stage.kind() == RepairStageKind.REPAIR_WORK)
        .map(stage -> stage.routing().queueId())
        .collect(Collectors.toSet());
    if (linesByQueue.entrySet().stream()
        .anyMatch(entry -> !entry.getValue().isEmpty() && !stageQueues.contains(entry.getKey()))) {
      throw invalid("Inventory line has no selected repair-work route");
    }
    repairStages.saveAllAndFlush(stages);
    source.bindRepair(
        repair.getId(), request.rentalItemId(), request.rentalItemVersion(), sourceFingerprint);
    InventoryRepairSource bound = sources.saveAndFlush(source);

    Map<String, Object> snapshot = projectionSnapshots.repair(repair);
    events.initialize(
        MaintenanceAggregateType.REPAIR,
        repair.getId(),
        repair.getVersion(),
        MaintenanceEventType.REPAIR_CREATED,
        snapshot,
        eventFacts.repairPayload(MaintenanceEventType.REPAIR_CREATED, repair, stages),
        snapshot);
    ownerProofs.enqueueMediaOwnerProof(
        "MAINTENANCE_REPAIR",
        repair.getId(),
        repair.getWarehouseId(),
        repair.getId(),
        repair.getVersion(),
        true);
    reconciliations.enqueue(
        repair.getId(), stableKey("inventory-queue-repair", inventoryId, findingId));
    return result(repair, bound, false);
  }

  private FrozenInventoryPlanSnapshot freezeSnapshot(
      FreezeInventoryPlanRequest request,
      CatalogVersion catalog,
      Map<UUID, CatalogNode> nodes) {
    validateMedia(request.findingId(), request.warehouseId(), request.mediaReferences());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    if (request.priority() < 1 || request.priority() > 5) {
      throw invalid("Inventory repair priority must be between 1 and 5");
    }
    Set<UUID> lineIds = new HashSet<>();
    List<InventoryPlanLineSnapshot> lines = new ArrayList<>();
    for (InventoryPlanLineInput input : request.lines()) {
      validateMedia(request.findingId(), request.warehouseId(), input.mediaReferences());
      String quantity = quantity(input.quantity());
      if (input.aggregationKind() == InventoryPlanLineKind.CATALOG) {
        if (input.catalogNodeId() == null
            || input.description() != null
            || input.type() != null
            || input.unit() != null
            || input.unitPriceMinor() != null
            || input.normativeMinutes() != null) {
          throw invalid("CATALOG line accepts only catalogNodeId and quantity evidence");
        }
        if (!lineIds.add(input.catalogNodeId())) {
          throw invalid("Inventory plan contains a duplicate catalog line");
        }
        CatalogNode node = activeNode(nodes, input.catalogNodeId());
        if (!node.isIncludeInEstimate()
            || !("WORK".equals(node.getNodeType()) || "MATERIAL".equals(node.getNodeType()))) {
          throw invalid("Inventory line must reference active estimate work or material");
        }
        if ("MATERIAL".equals(node.getNodeType()) && belongsToFurnitureTree(node, nodes)) {
          throw invalid("Furniture materials can only be used through an estimate");
        }
        if (node.getPriceMinor() == null) {
          throw invalid("Inventory catalog line has no immutable price");
        }
        String unit = normalize(node.getUnit());
        if (unit == null) {
          throw invalid("Inventory catalog line has no immutable unit");
        }
        validateMinorProduct(quantity, node.getPriceMinor());
        lines.add(new InventoryPlanLineSnapshot(
            InventoryPlanLineKind.CATALOG, catalog.getId(), node.getId(), node.getName(),
            InventoryPlanLineType.valueOf(node.getNodeType()), node.getName(), null, unit,
            quantity, node.getPriceMinor(), normativeMinutes(node.getDurationMinutes()), routing(node),
            normalize(input.groupComment()), List.copyOf(input.mediaReferences()),
            node.isForcesCapitalRepair(),
            node.getCharacteristicId() == null
                ? null
                : new CabinCharacteristicReference(
                    node.getCharacteristicId(), node.getCharacteristicName())));
      } else {
        if (request.mode() != InventoryPlanMode.MANUAL) {
          throw invalid("MANUAL lines require MANUAL inventory plan mode");
        }
        if (input.catalogNodeId() != null
            || input.description() == null
            || input.description().isBlank()
            || input.type() == null
            || input.unit() == null
            || input.unit().isBlank()
            || input.unitPriceMinor() == null
            || input.normativeMinutes() == null) {
          throw invalid("MANUAL line requires description, type, unit, price and normative minutes");
        }
        String description = normalizedText(input.description(), 1000, "description");
        String unit = normalizedText(input.unit(), 32, "unit");
        String minutes = normativeMinutes(input.normativeMinutes());
        validateMinorProduct(quantity, input.unitPriceMinor());
        lines.add(new InventoryPlanLineSnapshot(
            InventoryPlanLineKind.MANUAL, null, null, null, input.type(), description,
            description.toLowerCase(Locale.forLanguageTag("ru-RU")), unit, quantity,
            input.unitPriceMinor(), minutes, null, normalize(input.groupComment()),
            List.copyOf(input.mediaReferences()), false, null));
      }
    }

    validateAggregateLimits(lines);

    List<InventoryPlanStageSnapshot> stages = request.mode() == InventoryPlanMode.AUTO
        ? autoStages(request, catalog.getId(), nodes, lines)
        : manualStages(request, catalog.getId(), nodes);
    boolean moveTo = stages.stream().anyMatch(value -> value.kind() == RepairStageKind.MOVE_TO_REPAIR);
    boolean moveFrom = stages.stream().anyMatch(value -> value.kind() == RepairStageKind.MOVE_FROM_REPAIR);
    if (moveTo != moveFrom) {
      throw invalid("Movement plan requires both move-to and move-from stages");
    }
    if (!request.isLogisticsPlanningValid()) {
      throw invalid(
          "Inventory logistics planning mode and date are inconsistent");
    }
    requireWarehouseRoutingReady(request.warehouseId(), stages);
    FrozenInventoryPlanSnapshot snapshot = new FrozenInventoryPlanSnapshot(
        catalog.getId(), request.mode(), List.copyOf(lines), List.copyOf(stages), moveTo, moveFrom,
        List.copyOf(request.mediaReferences()), request.priority(), request.coverMediaId(),
        request.logisticsPlanningMode(), request.logisticsScheduledDate());
    if (sourceMedia(snapshot).size() > 100) {
      throw invalid("Inventory plan cannot reference more than 100 media objects");
    }
    return snapshot;
  }

  private void requireWarehouseRoutingReady(
      UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    for (InventoryPlanStageSnapshot stage : stages) {
      String type = stage.routing().queueType().trim().toUpperCase(Locale.ROOT);
      MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
          new MaintenanceDependencyGateway.RoutingQueueRequirement(
              stage.routing().queueId(), type);
      MaintenanceDependencyGateway.RoutingQueueRequirement previous =
          requirements.putIfAbsent(requirement.queueDefinitionId(), requirement);
      if (previous != null && !previous.equals(requirement)) {
        throw invalid(
            "One inventory queue definition has conflicting routing snapshots");
      }
    }
    if (requirements.isEmpty()) {
      throw invalid("Inventory repair plan requires at least one queue definition");
    }
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(
            warehouseId, List.copyOf(requirements.values()));
    if (preflight == null || !warehouseId.equals(preflight.warehouseId())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted warehouse routing truth for the inventory repair");
    }
    if (!preflight.ready()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_ROUTING_INVALID",
          "A required global queue is not connected to this warehouse");
    }
    Map<UUID, String> resolved =
        preflight.queues().stream()
            .collect(
                Collectors.toMap(
                    MaintenanceDependencyGateway.RoutingQueueSnapshot::
                        queueDefinitionId,
                    queue -> queue.type().trim().toUpperCase(Locale.ROOT)));
    boolean complete =
        resolved.size() == requirements.size()
            && requirements.entrySet().stream()
                .allMatch(
                    entry ->
                        entry.getValue().type().equals(
                            resolved.get(entry.getKey())));
    if (!complete) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned incomplete warehouse routing truth for the inventory repair");
    }
  }

  private List<InventoryPlanStageSnapshot> autoStages(
      FreezeInventoryPlanRequest request,
      UUID catalogVersionId,
      Map<UUID, CatalogNode> nodes,
      List<InventoryPlanLineSnapshot> lines) {
    List<InventoryPlanStageSelection> movement = request.plan();
    if (movement.stream().anyMatch(value -> value.kind() == RepairStageKind.REPAIR_WORK)) {
      throw invalid("AUTO plan derives repair-work stages; only movement nodes may be selected");
    }
    List<InventoryPlanStageSnapshot> result = new ArrayList<>();
    InventoryPlanStageSelection moveTo = single(movement, RepairStageKind.MOVE_TO_REPAIR);
    InventoryPlanStageSelection moveFrom = single(movement, RepairStageKind.MOVE_FROM_REPAIR);
    if ((moveTo == null) != (moveFrom == null)) {
      throw invalid("AUTO movement requires both route endpoints");
    }
    int order = 0;
    if (moveTo != null) result.add(stage(catalogVersionId, nodes, moveTo, order++));
    Set<UUID> repairQueues = new HashSet<>();
    for (InventoryPlanLineSnapshot line : lines) {
      if (line.catalogNodeId() == null || line.routing() == null) {
        throw invalid("AUTO inventory line has no existing catalog routing");
      }
      CatalogNode node = activeNode(nodes, line.catalogNodeId());
      if (!repairQueues.add(line.routing().queueId())) continue;
      result.add(stage(
          catalogVersionId,
          node,
          RepairStageKind.REPAIR_WORK,
          order++));
    }
    if (moveFrom != null) result.add(stage(catalogVersionId, nodes, moveFrom, order));
    if (result.stream().noneMatch(value -> value.kind() == RepairStageKind.REPAIR_WORK)) {
      throw invalid("Inventory repair plan requires at least one routed work stage");
    }
    return result;
  }

  private List<InventoryPlanStageSnapshot> manualStages(
      FreezeInventoryPlanRequest request,
      UUID catalogVersionId,
      Map<UUID, CatalogNode> nodes) {
    if (request.plan().isEmpty()) throw invalid("MANUAL inventory plan requires ordered stages");
    List<InventoryPlanStageSnapshot> result = new ArrayList<>();
    Set<UUID> stageNodes = new HashSet<>();
    for (int index = 0; index < request.plan().size(); index++) {
      InventoryPlanStageSelection selection = request.plan().get(index);
      if (selection.order() != index) throw invalid("MANUAL plan order must be contiguous");
      if (!stageNodes.add(selection.catalogNodeId())) {
        throw invalid("MANUAL plan cannot repeat a catalog node");
      }
      result.add(stage(catalogVersionId, nodes, selection, index));
    }
    if (result.stream().noneMatch(value -> value.kind() == RepairStageKind.REPAIR_WORK)) {
      throw invalid("MANUAL inventory plan requires a repair-work stage");
    }
    return result;
  }

  private InventoryPlanStageSnapshot stage(
      UUID catalogVersionId,
      Map<UUID, CatalogNode> nodes,
      InventoryPlanStageSelection selection,
      int normalizedOrder) {
    if (selection.order() != normalizedOrder) throw invalid("Inventory plan order is not canonical");
    return stage(
        catalogVersionId, activeNode(nodes, selection.catalogNodeId()), selection.kind(), normalizedOrder);
  }

  private InventoryPlanStageSnapshot stage(
      UUID catalogVersionId, CatalogNode node, RepairStageKind kind, int order) {
    if (kind == RepairStageKind.REPAIR_WORK
        && !("WORK".equals(node.getNodeType()) || "MATERIAL".equals(node.getNodeType()))) {
      throw invalid("REPAIR_WORK must reference active WORK or MATERIAL catalog routing");
    }
    if (kind != RepairStageKind.REPAIR_WORK && !"LOCATION".equals(node.getNodeType())) {
      throw invalid("Movement stages must reference active LOCATION catalog nodes");
    }
    RoutingSnapshot routing = routing(node);
    if (routing == null) throw invalid("Inventory plan stage catalog node has no routing snapshot");
    UUID stageId = UUID.nameUUIDFromBytes(
        (catalogVersionId + ":" + node.getId() + ":" + kind + ":" + order)
            .getBytes(StandardCharsets.UTF_8));
    return new InventoryPlanStageSnapshot(
        stageId, node.getId(), node.getName(), kind, order, routing, node.getDurationMinutes());
  }

  private void requireHistoricalSource(
      InventoryRepairSource source, UpsertInventoryRepairRequest request) {
    if (!request.warehouseId().equals(source.getWarehouseId())
        || request.sourceRevision() != source.getSourceRevision()
        || !request.planFingerprint().equals(source.getPlanFingerprint())) {
      throw conflict("Inventory publication does not match its frozen source identity");
    }
    ObjectNode requestedSnapshot = mapper.valueToTree(request.snapshot());
    String exactSnapshotFingerprint = frozenHash(requestedSnapshot);
    if (!exactSnapshotFingerprint.equals(source.getPlanFingerprint())
        || !sameJson(write(requestedSnapshot), source.getPlanSnapshot())) {
      throw conflict("Inventory publication changed the historical maintenance plan snapshot");
    }
  }

  private void validateSnapshotMedia(
      UUID findingId, UUID warehouseId, FrozenInventoryPlanSnapshot snapshot) {
    validateMedia(findingId, warehouseId, snapshot.mediaReferences());
    validateCoverMediaSelection(snapshot.mediaReferences(), snapshot.coverMediaId());
    snapshot.lines().forEach(line -> validateMedia(findingId, warehouseId, line.mediaReferences()));
  }

  private static void validateCoverMediaSelection(
      List<MediaReferenceInput> mediaReferences, UUID coverMediaId) {
    if (mediaReferences == null || mediaReferences.isEmpty()) {
      if (coverMediaId != null) {
        throw invalid("Inventory cover photo must be null when aggregate media is empty");
      }
      return;
    }
    if (coverMediaId == null) {
      throw invalid("Inventory plan with photos requires a cover photo");
    }
    if (mediaReferences.stream()
        .noneMatch(reference -> coverMediaId.equals(reference.mediaId()))) {
      throw invalid("Inventory cover photo must reference aggregate media");
    }
  }

  private static List<MediaReferenceInput> sourceMedia(FrozenInventoryPlanSnapshot snapshot) {
    Map<UUID, MediaReferenceInput> values = new LinkedHashMap<>();
    List<MediaReferenceInput> all = new ArrayList<>(snapshot.mediaReferences());
    snapshot.lines().forEach(line -> all.addAll(line.mediaReferences()));
    for (MediaReferenceInput reference : all) {
      MediaReferenceInput previous = values.putIfAbsent(reference.mediaId(), reference);
      if (previous != null && !previous.generation().equals(reference.generation())) {
        throw invalid("One inventory media identity cannot reference multiple generations");
      }
    }
    return List.copyOf(values.values());
  }

  private void validateMedia(
      UUID findingId, UUID warehouseId, List<MediaReferenceInput> requested) {
    Set<UUID> unique = new HashSet<>();
    for (MediaReferenceInput reference : requested) {
      if (!unique.add(reference.mediaId())) throw invalid("Duplicate inventory media reference");
      MediaFactProjection fact = mediaFacts.findById(reference.mediaId())
          .orElseThrow(() -> new MaintenanceValidationException(
              "MAINTENANCE_MEDIA_NOT_READY", "Inventory media fact is not known"));
      if (fact.getGeneration() != reference.generation()
          || !"READY".equals(fact.getMediaStatus())
          || !"INVENTORY_FINDING".equals(fact.getOwnerType())
          || !findingId.equals(fact.getOwnerId())
          || !warehouseId.equals(fact.getWarehouseId())) {
        throw new MaintenanceValidationException(
            "MAINTENANCE_MEDIA_NOT_READY",
            "Inventory media owner, generation, warehouse or status does not match");
      }
    }
  }

  private FrozenInventoryPlanResponse frozenResponse(InventoryRepairSource source) {
    return new FrozenInventoryPlanResponse(
        source.getWarehouseId(), source.getInventoryId(), source.getFindingId(),
        source.getSourceRevision(), read(source.getPlanSnapshot(), FrozenInventoryPlanSnapshot.class),
        source.getPlanFingerprint());
  }

  private UpsertResult result(
      MaintenanceRepair repair, InventoryRepairSource source, boolean replayed) {
    InventorySourceReference reference = new InventorySourceReference(
        source.getInventoryId(), source.getFindingId(), source.getSourceRevision(),
        source.getPlanFingerprint(), source.getSourceFingerprint());
    DeliverySnapshot delivery = new DeliverySnapshot(
        DeliveryState.valueOf(repair.getDeliveryState()), repair.getDeliveryAttempts(),
        repair.getDeliveryUpdatedAt());
    return new UpsertResult(repair.getId(), reference, delivery, replayed);
  }

  private String sourceFingerprint(
      UUID inventoryId, UUID findingId, UpsertInventoryRepairRequest request) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("inventoryId", inventoryId);
    value.put("findingId", findingId);
    value.put("sourceRevision", request.sourceRevision());
    value.put("warehouseId", request.warehouseId());
    value.put("rentalItemId", request.rentalItemId());
    value.put("rentalItemVersion", request.rentalItemVersion());
    value.put("dispatchDate", request.dispatchDate());
    value.put("planFingerprint", request.planFingerprint());
    value.put("snapshot", request.snapshot());
    return frozenHash(value);
  }

  private CatalogNode activeNode(Map<UUID, CatalogNode> nodes, UUID id) {
    CatalogNode node = nodes.get(id);
    if (node == null || !node.isActive()) {
      throw invalid("Inventory plan references an unknown or inactive catalog node");
    }
    return node;
  }

  private static InventoryPlanStageSelection single(
      List<InventoryPlanStageSelection> values, RepairStageKind kind) {
    List<InventoryPlanStageSelection> matches = values.stream()
        .filter(value -> value.kind() == kind)
        .toList();
    if (matches.size() > 1) throw invalid("AUTO movement stage may appear only once");
    return matches.isEmpty() ? null : matches.getFirst();
  }

  private static boolean belongsToFurnitureTree(
      CatalogNode node, Map<UUID, CatalogNode> nodes) {
    Set<UUID> visited = new HashSet<>();
    CatalogNode current = node;
    while (current != null && visited.add(current.getId())) {
      if (current.isFurnitureCategory()) {
        return true;
      }
      current = current.getParentNodeId() == null
          ? null
          : nodes.get(current.getParentNodeId());
    }
    return false;
  }

  private static RoutingSnapshot routing(CatalogNode node) {
    return node.getRoutingQueueId() == null
        ? null
        : new RoutingSnapshot(
            node.getRoutingQueueId(), node.getRoutingQueueName(), node.getRoutingQueueType());
  }

  private Map<UUID, List<EstimateLineResponse>> inventoryRepairLines(
      UUID inventoryId,
      UUID findingId,
      FrozenInventoryPlanSnapshot snapshot) {
    Map<UUID, List<EstimateLineResponse>> result = new LinkedHashMap<>();
    UUID unrouted = new UUID(0, 0);
    for (int index = 0; index < snapshot.lines().size(); index++) {
      InventoryPlanLineSnapshot line = snapshot.lines().get(index);
      UUID lineId = UUID.nameUUIDFromBytes(
          (inventoryId + ":" + findingId + ":line:" + index)
              .getBytes(StandardCharsets.UTF_8));
      BigDecimal quantity = new BigDecimal(line.quantity());
      String quantityText = quantity.stripTrailingZeros().toPlainString();
      long totalMinor =
          quantity.multiply(BigDecimal.valueOf(line.unitPriceMinor()))
              .setScale(0, RoundingMode.HALF_UP)
              .longValueExact();
      int duration = new BigDecimal(line.normativeMinutes())
          .setScale(0, RoundingMode.CEILING)
          .intValueExact();
      CatalogNodeSnapshot catalogSnapshot =
          line.aggregationKind() == InventoryPlanLineKind.CATALOG
              ? new CatalogNodeSnapshot(
                  line.catalogVersionId(),
                  line.catalogNodeId(),
                  line.type() == InventoryPlanLineType.WORK
                      ? CatalogNodeType.WORK
                      : CatalogNodeType.MATERIAL,
                  line.catalogNodeName(),
                  line.unit(),
                  moneyFromMinor(line.unitPriceMinor()),
                  duration,
                  line.routing(),
                  null,
                  line.forcesCapitalRepair(),
                  line.characteristic())
              : null;
      EstimateLineResponse response = new EstimateLineResponse(
          lineId,
          catalogSnapshot,
          line.type() == InventoryPlanLineType.WORK
              ? EstimateLineType.WORK
              : EstimateLineType.MATERIAL,
          line.description(),
          line.unit(),
          quantityText,
          moneyFromMinor(line.unitPriceMinor()),
          moneyFromMinor(totalMinor),
          duration,
          line.groupComment(),
          line.mediaReferences());
      UUID queueId = line.routing() == null ? unrouted : line.routing().queueId();
      result.computeIfAbsent(queueId, ignored -> new ArrayList<>()).add(response);
    }
    return result;
  }

  private static String moneyFromMinor(long value) {
    return BigDecimal.valueOf(value, 2).setScale(2).toPlainString();
  }

  private static String quantity(String value) {
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value);
    } catch (NumberFormatException exception) {
      throw invalid("Inventory quantity is invalid");
    }
    if (parsed.signum() <= 0
        || parsed.scale() > 3
        || parsed.precision() - parsed.scale() > 14) {
      throw invalid("Inventory quantity is invalid");
    }
    return parsed.setScale(6, RoundingMode.UNNECESSARY).toPlainString();
  }

  private static String normativeMinutes(int value) {
    return BigDecimal.valueOf(value).setScale(3).toPlainString();
  }

  private static String normativeMinutes(String value) {
    BigDecimal parsed;
    try {
      parsed = new BigDecimal(value);
    } catch (NumberFormatException exception) {
      throw invalid("Inventory normative minutes are invalid");
    }
    if (parsed.signum() < 0 || parsed.scale() > 3) {
      throw invalid("Inventory normative minutes are invalid");
    }
    try {
      parsed.setScale(3, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    } catch (ArithmeticException exception) {
      throw invalid("Inventory normative minutes overflow");
    }
    return parsed.setScale(3, RoundingMode.UNNECESSARY).toPlainString();
  }

  private static void validateMinorProduct(String quantity, long unitPriceMinor) {
    if (unitPriceMinor < 0) throw invalid("Inventory unit price must be nonnegative");
    BigDecimal product = new BigDecimal(quantity).multiply(BigDecimal.valueOf(unitPriceMinor));
    if (product.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
      throw invalid("Inventory line minor-unit product overflow");
    }
  }

  private static void validateAggregateLimits(List<InventoryPlanLineSnapshot> lines) {
    BigDecimal work = BigDecimal.ZERO;
    BigDecimal material = BigDecimal.ZERO;
    BigDecimal minutes = BigDecimal.ZERO;
    for (InventoryPlanLineSnapshot line : lines) {
      BigDecimal value = new BigDecimal(line.quantity())
          .multiply(BigDecimal.valueOf(line.unitPriceMinor()));
      if (line.type() == InventoryPlanLineType.WORK) work = work.add(value);
      else material = material.add(value);
      minutes = minutes.add(new BigDecimal(line.normativeMinutes()));
    }
    for (BigDecimal value : List.of(work, material, work.add(material))) {
      if (value.compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) > 0) {
        throw invalid("Inventory aggregate minor-unit total overflow");
      }
    }
    try {
      minutes.setScale(3, RoundingMode.UNNECESSARY).unscaledValue().longValueExact();
    } catch (ArithmeticException exception) {
      throw invalid("Inventory normative minutes total overflow");
    }
  }

  private static String normalizedText(String value, int maximum, String field) {
    String normalized = normalize(value);
    if (normalized == null || normalized.length() > maximum) {
      throw invalid("Inventory manual " + field + " is invalid");
    }
    return normalized;
  }

  private static String normalize(String value) {
    if (value == null || value.isBlank()) return null;
    return value.trim().replaceAll("\\s+", " ");
  }

  private String inventoryActorJson() {
    return write(Map.of(
        "subjectId", INVENTORY_ACTOR.toString(),
        "principalType", "SERVICE"));
  }

  private List<Map<String, Object>> inventoryPlanSnapshot(List<RepairStage> stages) {
    return stages.stream().map(stage -> {
      Map<String, Object> routing = new LinkedHashMap<>();
      routing.put("queueId", stage.getRoutingQueueId());
      routing.put("queueName", stage.getRoutingQueueName());
      routing.put("queueType", stage.getRoutingQueueType());

      Map<String, Object> value = new LinkedHashMap<>();
      value.put("kind", stage.getStageKind().name());
      value.put("order", stage.getStageNo());
      value.put("routing", routing);
      value.put("workLines", semanticJson(stage.getWorkLines()));
      value.put("materialLines", semanticJson(stage.getMaterialLines()));
      value.put("primaryLineId", stage.getPrimaryLineId());
      value.put("groupComment", stage.getGroupComment());
      return value;
    }).toList();
  }

  private Object semanticJson(String value) {
    try {
      return mapper.readValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored repair plan content is invalid", exception);
    }
  }

  private String hash(Object value) {
    try {
      String canonical = mapper.writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
      return MaintenanceChecksum.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException(
          "Inventory maintenance value cannot be canonicalized", exception);
    }
  }

  private String frozenHash(Object value) {
    return canonicalizer.sha256(value);
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory maintenance value cannot be serialized", exception);
    }
  }

  private <T> T read(String value, Class<T> type) {
    try {
      return mapper.readValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory maintenance snapshot is invalid", exception);
    }
  }

  private boolean sameJson(String first, String second) {
    try {
      return mapper.readTree(first).equals(mapper.readTree(second));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory maintenance snapshot is invalid", exception);
    }
  }

  private static void registerConcurrentSafe(Runnable registration) {
    try {
      registration.run();
    } catch (DataIntegrityViolationException ignored) {
      // Another transaction registered the immutable source key; lock and validate it below.
    }
  }

  private static UUID stableKey(String operation, UUID inventoryId, UUID findingId) {
    return UUID.nameUUIDFromBytes(
        (operation + ":" + inventoryId + ":" + findingId).getBytes(StandardCharsets.UTF_8));
  }

  private static MaintenanceValidationException invalid(String detail) {
    return new MaintenanceValidationException("MAINTENANCE_VALIDATION_FAILED", detail);
  }

  private static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("MAINTENANCE_IDEMPOTENCY_CONFLICT", detail);
  }

  public record UpsertResult(
      UUID repairId,
      InventorySourceReference source,
      DeliverySnapshot delivery,
      boolean replayed) {}

  public record FreezeResult(FrozenInventoryPlanResponse response, boolean replayed) {}
}
