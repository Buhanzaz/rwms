package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersion;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.stream.Collectors;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Validates immutable inventory repair plans and converts their frozen routing, media and line
 * evidence into maintenance repair stages.
 *
 * <p>The component has no source-operation or repair ownership. It can ask task-board for a
 * routing preflight, but leaves transaction/retry control to the freeze and upsert use cases.
 */
@Component
final class InventoryMaintenancePlanValidation {
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceDependencyGateway dependencies;
  private final ObjectMapper mapper;

  InventoryMaintenancePlanValidation(
      MediaFactProjectionRepository mediaFacts,
      MaintenanceDependencyGateway dependencies,
      ObjectMapper mapper) {
    this.mediaFacts = mediaFacts;
    this.dependencies = dependencies;
    this.mapper = mapper;
  }

  FrozenInventoryPlanSnapshot freezeSnapshot(
      FreezeInventoryPlanRequest request,
      CatalogVersion catalog,
      Map<UUID, CatalogNode> nodes,
      Map<UUID, List<UUID>> incomingLinks,
      List<InventoryPlanStageSnapshot> routingPreflightStages) {
    validateMedia(request.findingId(), request.warehouseId(), request.mediaReferences());
    validateCoverMediaSelection(request.mediaReferences(), request.coverMediaId());
    if (request.priority() < 1 || request.priority() > 5) {
      throw invalid("Inventory repair priority must be between 1 and 5");
    }
    List<InventoryPlanLineSnapshot> lines = new ArrayList<>();
    for (InventoryPlanLineInput input : request.lines()) {
      validateMedia(request.findingId(), request.warehouseId(), input.mediaReferences());
      String quantity = quantity(input.quantity());
      if (input.aggregationKind() == InventoryPlanLineKind.CATALOG) {
        if (input.catalogNodeId() == null
            || input.routingCatalogNodeId() != null
            || input.description() != null
            || input.type() != null
            || input.unit() != null
            || input.unitPriceMinor() != null
            || input.normativeMinutes() != null) {
          throw invalid("CATALOG line accepts only catalogNodeId and quantity evidence");
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
            quantity, node.getPriceMinor(), normativeMinutes(node.getDurationMinutes()),
            routing(node, nodes, incomingLinks),
            "WORK".equals(node.getNodeType()) ? normalize(input.groupComment()) : null,
            List.copyOf(input.mediaReferences()),
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
            || input.routingCatalogNodeId() == null
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
        CatalogNode routingNode = activeNode(nodes, input.routingCatalogNodeId());
        RoutingSnapshot routing = routing(routingNode, nodes, incomingLinks);
        if (routing == null) {
          throw invalid("MANUAL line routing catalog node has no routing snapshot");
        }
        lines.add(new InventoryPlanLineSnapshot(
            InventoryPlanLineKind.MANUAL, null, null, null, input.type(), description,
            description.toLowerCase(Locale.forLanguageTag("ru-RU")), unit, quantity,
            input.unitPriceMinor(), minutes, routing,
            input.type() == InventoryPlanLineType.WORK ? normalize(input.groupComment()) : null,
            List.copyOf(input.mediaReferences()), false, null));
      }
    }

    validateWorkLineMediaIsolation(lines);
    validateAggregateLimits(lines);

    List<InventoryPlanStageSnapshot> stages = request.mode() == InventoryPlanMode.AUTO
        ? autoStages(request, catalog.getId(), nodes, incomingLinks, lines)
        : manualStages(request, catalog.getId(), nodes, incomingLinks);
    if (request.mode() == InventoryPlanMode.MANUAL) {
      requireManualLineRoutesMatchSelectedStages(lines, stages);
    }
    if (!request.isLogisticsPlanningValid()) {
      throw invalid(
          "Inventory logistics planning mode and date are inconsistent");
    }
    if (!stages.equals(routingPreflightStages)) {
      throw RemotePreflightRequired.routing(request.warehouseId(), stages);
    }
    FrozenInventoryPlanSnapshot snapshot = new FrozenInventoryPlanSnapshot(
        catalog.getId(), request.mode(), List.copyOf(lines), List.copyOf(stages),
        request.movementToRepair(),
        List.copyOf(request.mediaReferences()), request.priority(), request.coverMediaId(),
        request.logisticsPlanningMode(), request.logisticsScheduledDate());
    if (sourceMedia(snapshot).size() > 100) {
      throw invalid("Inventory plan cannot reference more than 100 media objects");
    }
    return snapshot;
  }

  void requireWarehouseRoutingReady(
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
                    MaintenanceDependencyGateway.RoutingQueueSnapshot::queueDefinitionId,
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

  void validateSnapshotMedia(
      UUID findingId, UUID warehouseId, FrozenInventoryPlanSnapshot snapshot) {
    validateMedia(findingId, warehouseId, snapshot.mediaReferences());
    validateCoverMediaSelection(snapshot.mediaReferences(), snapshot.coverMediaId());
    snapshot.lines().forEach(line -> validateMedia(findingId, warehouseId, line.mediaReferences()));
    validateWorkLineMediaIsolation(snapshot.lines());
  }

  List<RepairStage> inventoryRepairStages(
      UUID repairId,
      UUID inventoryId,
      UUID findingId,
      FrozenInventoryPlanSnapshot snapshot) {
    List<InventoryRepairLine> inventoryLines = inventoryRepairLines(
        inventoryId, findingId, snapshot);
    if (inventoryLines.stream().anyMatch(line -> line.snapshot().routing() == null)) {
      throw invalid(
          "Every inventory repair line must have an explicit global queue definition");
    }
    return inventoryRepairStages(repairId, snapshot, inventoryLines);
  }

  private static void requireManualLineRoutesMatchSelectedStages(
      List<InventoryPlanLineSnapshot> lines,
      List<InventoryPlanStageSnapshot> stages) {
    for (InventoryPlanLineSnapshot line : lines) {
      if (line.routing() == null) {
        throw invalid("Every manual inventory line requires a frozen routing snapshot");
      }
      boolean selected = stages.stream().anyMatch(stage -> sameRoute(line.routing(), stage.routing()));
      if (!selected) {
        throw invalid("Every inventory line route must match a selected manual repair-work stage");
      }
    }
  }

  private static boolean sameRoute(RoutingSnapshot first, RoutingSnapshot second) {
    return first != null
        && second != null
        && first.queueId().equals(second.queueId())
        && first.queueType().equals(second.queueType());
  }

  private List<InventoryPlanStageSnapshot> autoStages(
      FreezeInventoryPlanRequest request,
      UUID catalogVersionId,
      Map<UUID, CatalogNode> nodes,
      Map<UUID, List<UUID>> incomingLinks,
      List<InventoryPlanLineSnapshot> lines) {
    if (!request.plan().isEmpty()) {
      throw invalid("AUTO inventory plan derives every repair-work stage from its lines");
    }
    List<InventoryPlanStageSnapshot> result = new ArrayList<>();
    int order = 0;
    List<InventoryPlanLineSnapshot> stageLines = lines.stream()
        .filter(line -> line.type() == InventoryPlanLineType.WORK)
        .toList();
    if (stageLines.isEmpty()) {
      // A historical material-only inventory plan remains executable, but a plan with work
      // always has one stage per work line, even when several works share one queue.
      stageLines = lines;
    }
    for (InventoryPlanLineSnapshot line : stageLines) {
      if (line.catalogNodeId() == null || line.routing() == null) {
        throw invalid("AUTO inventory line has no existing catalog routing");
      }
      CatalogNode node = activeNode(nodes, line.catalogNodeId());
      result.add(stage(
          catalogVersionId,
          node,
          nodes,
          incomingLinks,
          RepairStageKind.REPAIR_WORK,
          order++));
    }
    if (result.isEmpty()) {
      throw invalid("Inventory repair plan requires at least one routed work stage");
    }
    return result;
  }

  private List<InventoryPlanStageSnapshot> manualStages(
      FreezeInventoryPlanRequest request,
      UUID catalogVersionId,
      Map<UUID, CatalogNode> nodes,
      Map<UUID, List<UUID>> incomingLinks) {
    if (request.plan().isEmpty()) throw invalid("MANUAL inventory plan requires ordered stages");
    List<InventoryPlanStageSnapshot> result = new ArrayList<>();
    for (int index = 0; index < request.plan().size(); index++) {
      InventoryPlanStageSelection selection = request.plan().get(index);
      if (selection.order() != index) throw invalid("MANUAL plan order must be contiguous");
      result.add(stage(catalogVersionId, nodes, incomingLinks, selection, index));
    }
    if (result.isEmpty()) {
      throw invalid("MANUAL inventory plan requires a repair-work stage");
    }
    return result;
  }

  private InventoryPlanStageSnapshot stage(
      UUID catalogVersionId,
      Map<UUID, CatalogNode> nodes,
      Map<UUID, List<UUID>> incomingLinks,
      InventoryPlanStageSelection selection,
      int normalizedOrder) {
    if (selection.order() != normalizedOrder) throw invalid("Inventory plan order is not canonical");
    return stage(
        catalogVersionId,
        activeNode(nodes, selection.catalogNodeId()),
        nodes,
        incomingLinks,
        selection.kind(),
        normalizedOrder);
  }

  private InventoryPlanStageSnapshot stage(
      UUID catalogVersionId,
      CatalogNode node,
      Map<UUID, CatalogNode> nodes,
      Map<UUID, List<UUID>> incomingLinks,
      RepairStageKind kind,
      int order) {
    if (kind != RepairStageKind.REPAIR_WORK) {
      throw invalid("Inventory plan stages can contain repair work only");
    }
    if (!("WORK".equals(node.getNodeType()) || "MATERIAL".equals(node.getNodeType()))) {
      throw invalid("REPAIR_WORK must reference active WORK or MATERIAL catalog routing");
    }
    RoutingSnapshot routing = routing(node, nodes, incomingLinks);
    if (routing == null) throw invalid("Inventory plan stage catalog node has no routing snapshot");
    UUID stageId = UUID.nameUUIDFromBytes(
        (catalogVersionId + ":" + node.getId() + ":" + kind + ":" + order)
            .getBytes(StandardCharsets.UTF_8));
    return new InventoryPlanStageSnapshot(
        stageId, node.getId(), node.getName(), kind, order, routing, node.getDurationMinutes());
  }

  private static void validateWorkLineMediaIsolation(
      List<InventoryPlanLineSnapshot> lines) {
    Set<UUID> assigned = new HashSet<>();
    for (InventoryPlanLineSnapshot line : lines) {
      if (line.type() != InventoryPlanLineType.WORK
          && !line.mediaReferences().isEmpty()) {
        throw invalid("Inventory photos can only be assigned to work lines");
      }
      if (line.type() != InventoryPlanLineType.WORK) continue;
      for (MediaReferenceInput reference : line.mediaReferences()) {
        if (!assigned.add(reference.mediaId())) {
          throw invalid("One inventory photo cannot be assigned to multiple work lines");
        }
      }
    }
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

  static List<MediaReferenceInput> sourceMedia(FrozenInventoryPlanSnapshot snapshot) {
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

  private static CatalogNode activeNode(Map<UUID, CatalogNode> nodes, UUID id) {
    CatalogNode node = nodes.get(id);
    if (node == null || !node.isActive()) {
      throw invalid("Inventory plan references an unknown or inactive catalog node");
    }
    return node;
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

  private static RoutingSnapshot routing(
      CatalogNode node,
      Map<UUID, CatalogNode> nodes,
      Map<UUID, List<UUID>> incomingLinks) {
    return CatalogRoutingResolver.snapshot(
        CatalogRoutingResolver.resolve(
            node.getId(),
            nodes,
            CatalogNode::getParentNodeId,
            InventoryMaintenancePlanValidation::directRouteValue,
            incomingLinks));
  }

  private static CatalogRoutingResolver.Route directRouteValue(CatalogNode node) {
    return node.getRoutingQueueId() == null
        ? null
        : new CatalogRoutingResolver.Route(
            node.getRoutingQueueId(), node.getRoutingQueueName(), node.getRoutingQueueType());
  }

  private List<RepairStage> inventoryRepairStages(
      UUID repairId,
      FrozenInventoryPlanSnapshot snapshot,
      List<InventoryRepairLine> lines) {
    List<InventoryStageAllocation> allocations = new ArrayList<>(snapshot.stages().stream()
        .map(InventoryStageAllocation::new)
        .toList());
    Set<Integer> allocated = new HashSet<>();

    // Preserve the user's selected work sequence. The same catalog work can be selected more
    // than once, so each stage consumes exactly one matching work line in source order.
    for (InventoryStageAllocation allocation : allocations) {
      InventoryRepairLine primary = firstAvailable(
          lines,
          allocated,
          candidate -> candidate.isWork()
              && matchesCatalogNode(candidate, allocation.stage()));
      if (primary == null) {
        primary = firstAvailable(
            lines,
            allocated,
            candidate -> matchesCatalogNode(candidate, allocation.stage()));
      }
      if (primary == null) {
        primary = firstAvailable(
            lines,
            allocated,
            candidate -> candidate.isWork() && matchesRoute(candidate, allocation.stage()));
      }
      if (primary != null) {
        allocation.add(primary);
        allocated.add(primary.sourceIndex());
      }
    }

    // Older frozen plans may have grouped several works under one queue stage. Keep those facts
    // executable, but never copy an unassigned line into every stage of the same queue.
    for (InventoryRepairLine line : lines) {
      if (!line.isWork() || allocated.contains(line.sourceIndex())) continue;
      InventoryStageAllocation target = routeStageFor(allocations, line);
      if (target == null) throw invalid("Inventory line has no selected repair-work route");
      target.add(line);
      allocated.add(line.sourceIndex());
    }

    // Materials are shared estimate positions. They are attached once to the closest matching
    // work stage, rather than duplicated into every stage that happens to use the same queue.
    for (InventoryRepairLine line : lines) {
      if (!line.isMaterial() || allocated.contains(line.sourceIndex())) continue;
      InventoryStageAllocation target = directCatalogStageFor(allocations, line);
      if (target == null) target = routeStageFor(allocations, line);
      if (target == null) throw invalid("Inventory line has no selected repair-work route");
      target.add(line);
      allocated.add(line.sourceIndex());
    }

    if (allocated.size() != lines.size()) {
      throw invalid("Inventory repair plan did not allocate every line exactly once");
    }

    return allocations.stream()
        .map(
            allocation -> {
              List<EstimateLineResponse> workLines = allocation.lines().stream()
                  .filter(line -> line.response().lineType() == EstimateLineType.WORK)
                  .map(InventoryRepairLine::response)
                  .toList();
              List<EstimateLineResponse> materialLines = allocation.lines().stream()
                  .filter(line -> line.response().lineType() == EstimateLineType.MATERIAL)
                  .map(InventoryRepairLine::response)
                  .toList();
              InventoryPlanStageSnapshot stage = allocation.stage();
              return new RepairStage(
                  stage.id(),
                  repairId,
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
  }

  private static InventoryRepairLine firstAvailable(
      List<InventoryRepairLine> lines,
      Set<Integer> allocated,
      Predicate<InventoryRepairLine> predicate) {
    return lines.stream()
        .filter(line -> !allocated.contains(line.sourceIndex()))
        .filter(predicate)
        .findFirst()
        .orElse(null);
  }

  private static boolean matchesCatalogNode(
      InventoryRepairLine line, InventoryPlanStageSnapshot stage) {
    return line.snapshot().catalogNodeId() != null
        && line.snapshot().catalogNodeId().equals(stage.catalogNodeId());
  }

  private static boolean matchesRoute(
      InventoryRepairLine line, InventoryPlanStageSnapshot stage) {
    return line.snapshot().routing() != null
        && line.snapshot().routing().queueId().equals(stage.routing().queueId());
  }

  private static InventoryStageAllocation directCatalogStageFor(
      List<InventoryStageAllocation> allocations, InventoryRepairLine line) {
    return allocations.stream()
        .filter(allocation -> matchesCatalogNode(line, allocation.stage()))
        .filter(allocation -> matchesRoute(line, allocation.stage()))
        .findFirst()
        .orElse(null);
  }

  private static InventoryStageAllocation routeStageFor(
      List<InventoryStageAllocation> allocations, InventoryRepairLine line) {
    List<InventoryStageAllocation> matching = allocations.stream()
        .filter(allocation -> matchesRoute(line, allocation.stage()))
        .toList();
    if (matching.isEmpty()) return null;
    return matching.stream()
        .filter(allocation -> allocation.lastSourceIndex() < line.sourceIndex())
        .max(Comparator.comparingInt(InventoryStageAllocation::lastSourceIndex))
        .orElse(matching.getFirst());
  }

  private static List<InventoryRepairLine> inventoryRepairLines(
      UUID inventoryId,
      UUID findingId,
      FrozenInventoryPlanSnapshot snapshot) {
    List<InventoryRepairLine> result = new ArrayList<>();
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
          line.type() == InventoryPlanLineType.WORK ? line.groupComment() : null,
          line.mediaReferences());
      result.add(new InventoryRepairLine(index, line, response));
    }
    return result;
  }

  /**
   * Couples a frozen inventory line with its API projection while preserving source order for
   * deterministic stage allocation and repair materialization.
   */
  private record InventoryRepairLine(
      int sourceIndex,
      InventoryPlanLineSnapshot snapshot,
      EstimateLineResponse response) {
    boolean isWork() {
      return response.lineType() == EstimateLineType.WORK;
    }

    boolean isMaterial() {
      return response.lineType() == EstimateLineType.MATERIAL;
    }
  }

  /**
   * Mutable, method-local planning bucket that assigns validated repair lines to exactly one
   * frozen route stage before immutable domain stages are created.
   */
  private static final class InventoryStageAllocation {
    private final InventoryPlanStageSnapshot stage;
    private final List<InventoryRepairLine> lines = new ArrayList<>();

    private InventoryStageAllocation(InventoryPlanStageSnapshot stage) {
      this.stage = stage;
    }

    private InventoryPlanStageSnapshot stage() {
      return stage;
    }

    private List<InventoryRepairLine> lines() {
      return lines;
    }

    private void add(InventoryRepairLine line) {
      lines.add(line);
    }

    private int lastSourceIndex() {
      return lines.stream()
          .mapToInt(InventoryRepairLine::sourceIndex)
          .max()
          .orElse(Integer.MIN_VALUE);
    }
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

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory maintenance value cannot be serialized", exception);
    }
  }

  /**
   * Signals that a local prepare transaction must roll back before a remote prerequisite is
   * checked. The retry retains no caller locks and repeats all local state checks afterward.
   */
  static final class RemotePreflightRequired extends RuntimeException {
    private final Kind kind;
    private final UUID warehouseId;
    private final List<InventoryPlanStageSnapshot> stages;

    private RemotePreflightRequired(
        Kind kind,
        UUID warehouseId,
        List<InventoryPlanStageSnapshot> stages) {
      super(null, null, false, false);
      this.kind = kind;
      this.warehouseId = warehouseId;
      this.stages = stages;
    }

    static RemotePreflightRequired incoming(UUID warehouseId) {
      return new RemotePreflightRequired(Kind.INCOMING, warehouseId, List.of());
    }

    static RemotePreflightRequired routing(
        UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
      return new RemotePreflightRequired(Kind.ROUTING, warehouseId, List.copyOf(stages));
    }

    Kind kind() {
      return kind;
    }

    UUID warehouseId() {
      return warehouseId;
    }

    List<InventoryPlanStageSnapshot> stages() {
      return stages;
    }

    /**
     * Identifies which remote warehouse or task-board prerequisite must run outside local locks.
     */
    enum Kind { INCOMING, ROUTING }
  }

  static MaintenanceValidationException invalid(String detail) {
    return new MaintenanceValidationException("MAINTENANCE_VALIDATION_FAILED", detail);
  }
}
