package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Validates immutable inventory publication snapshots before they are materialized into
 * maintenance targets.
 *
 * <p>The raw schema-versioned JSON remains the fingerprinted source fact. Schema adaptation only
 * produces the executable in-memory representation after that fingerprint has been verified.
 */
@Component
final class InventoryPublicationPlanValidation {
  private final MediaFactProjectionRepository mediaFacts;
  private final MaintenanceDependencyGateway dependencies;
  private final MaintenanceJsonbCanonicalizer canonicalizer;
  private final ObjectMapper mapper;

  InventoryPublicationPlanValidation(
      MediaFactProjectionRepository mediaFacts,
      MaintenanceDependencyGateway dependencies,
      MaintenanceJsonbCanonicalizer canonicalizer,
      ObjectMapper mapper) {
    this.mediaFacts = mediaFacts;
    this.dependencies = dependencies;
    this.canonicalizer = canonicalizer;
    this.mapper = mapper;
  }

  InventoryPublicationValidatedPlan validatePublication(
      UUID warehouseId, InventoryPublicationFindingInput finding) {
    if (warehouseId == null || finding == null) {
      throw invalid("Inventory publication warehouse and finding are required");
    }
    if (!finding.movementToRepair() && finding.movementScheduledDate() != null) {
      throw invalid("Inbound movement date must be absent when movement to repair is disabled");
    }
    if (finding.snapshot() == null || !finding.snapshot().isObject()) {
      throw invalid("Inventory publication snapshot must be a JSON object");
    }
    String rawFingerprint = canonicalizer.sha256(finding.snapshot());
    if (!rawFingerprint.equals(finding.planFingerprintSha256())) {
      throw conflict("Inventory publication plan fingerprint does not match the raw frozen snapshot");
    }
    FrozenInventoryPlanSnapshot snapshot = adaptSnapshot(
        finding.snapshotSchemaVersion(), finding.snapshot());
    validateSnapshot(snapshot);
    List<MediaReferenceInput> sourceMedia = sourceMedia(snapshot);
    if (sourceMedia.size() > 100 || !sourceMedia.equals(finding.media())) {
      throw conflict("Inventory publication media must equal the deterministic frozen plan media union");
    }
    validateEvidenceMedia(finding.findingId(), warehouseId, sourceMedia);
    return new InventoryPublicationValidatedPlan(
        snapshot, sourceMedia, write(finding.snapshot()), write(finding.media()));
  }

  void requireWarehouseRoutingReady(UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
    Map<UUID, MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
        new LinkedHashMap<>();
    for (InventoryPlanStageSnapshot stage : stages) {
      String type = stage.routing().queueType().trim().toUpperCase(java.util.Locale.ROOT);
      MaintenanceDependencyGateway.RoutingQueueRequirement requirement =
          new MaintenanceDependencyGateway.RoutingQueueRequirement(stage.routing().queueId(), type);
      MaintenanceDependencyGateway.RoutingQueueRequirement previous =
          requirements.putIfAbsent(requirement.queueDefinitionId(), requirement);
      if (previous != null && !previous.equals(requirement)) {
        throw invalid("One inventory queue definition has conflicting routing snapshots");
      }
    }
    if (requirements.isEmpty()) {
      throw invalid("Inventory repair plan requires at least one queue definition");
    }
    MaintenanceDependencyGateway.RoutingPreflight preflight =
        dependencies.preflightMaintenanceRouting(warehouseId, List.copyOf(requirements.values()));
    if (preflight == null || !warehouseId.equals(preflight.warehouseId())) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board omitted warehouse routing truth for the inventory repair");
    }
    if (!preflight.ready()) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_ROUTING_INVALID",
          "A required global queue is not connected to this warehouse");
    }
    Map<UUID, String> resolved = preflight.queues().stream().collect(
        Collectors.toMap(
            MaintenanceDependencyGateway.RoutingQueueSnapshot::queueDefinitionId,
            queue -> queue.type().trim().toUpperCase(java.util.Locale.ROOT)));
    boolean complete = resolved.size() == requirements.size()
        && requirements.entrySet().stream().allMatch(
            entry -> entry.getValue().type().equals(resolved.get(entry.getKey())));
    if (!complete) {
      throw new MaintenanceDependencyException(
          HttpStatus.SERVICE_UNAVAILABLE,
          "Task-board returned incomplete warehouse routing truth for the inventory repair");
    }
  }

  String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory publication value cannot be serialized", exception);
    }
  }

  private FrozenInventoryPlanSnapshot adaptSnapshot(int schemaVersion, JsonNode raw) {
    if (schemaVersion != 1 && schemaVersion != 2) {
      throw invalid("Inventory publication snapshot schema version must be 1 or 2");
    }
    ObjectNode executable = ((ObjectNode) raw).deepCopy();
    JsonNode legacyOutbound = executable.get("movementToShipment");
    if (schemaVersion == 1) {
      if (legacyOutbound != null && !legacyOutbound.isBoolean()) {
        throw invalid("Inventory snapshot schema version 1 has an invalid movementToShipment marker");
      }
      executable.remove("movementToShipment");
    } else if (legacyOutbound != null) {
      throw invalid("Inventory snapshot schema version 2 must not contain movementToShipment");
    }
    if (schemaVersion == 2) {
      adaptLegacyManualLineRouting(executable);
    }
    try {
      return mapper.treeToValue(executable, FrozenInventoryPlanSnapshot.class);
    } catch (JacksonException exception) {
      throw invalid("Inventory publication snapshot is not a valid frozen maintenance plan");
    }
  }

  /**
   * Supplies one deterministic executable route for schema-v2 manual lines after raw snapshot
   * fingerprint verification.
   */
  private void adaptLegacyManualLineRouting(ObjectNode executable) {
    JsonNode rawLines = executable.get("lines");
    if (rawLines == null || !rawLines.isArray()) {
      return;
    }
    List<ObjectNode> missingRoutes = new ArrayList<>();
    for (JsonNode rawLine : rawLines) {
      if (rawLine.isObject()
          && "MANUAL".equals(rawLine.path("aggregationKind").asText())
          && rawLine.has("routing")
          && rawLine.get("routing").isNull()) {
        missingRoutes.add((ObjectNode) rawLine);
      }
    }
    if (missingRoutes.isEmpty()) {
      return;
    }

    JsonNode rawStages = executable.get("stages");
    if (rawStages == null || !rawStages.isArray()) {
      throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
    }
    Set<RoutingSnapshot> routes = new LinkedHashSet<>();
    for (JsonNode rawStage : rawStages) {
      if (!rawStage.isObject()
          || !RepairStageKind.REPAIR_WORK.name().equals(rawStage.path("kind").asText())) {
        throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
      }
      try {
        RoutingSnapshot routing = mapper.treeToValue(rawStage.get("routing"), RoutingSnapshot.class);
        if (routing == null) {
          throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
        }
        routes.add(routing);
      } catch (JacksonException | IllegalArgumentException exception) {
        throw invalid("Inventory publication legacy manual routing has no valid selected stage route");
      }
    }
    if (routes.size() != 1) {
      throw invalid("Inventory publication legacy manual routing is ambiguous");
    }
    JsonNode routing = mapper.valueToTree(routes.iterator().next());
    for (ObjectNode line : missingRoutes) {
      line.set("routing", routing.deepCopy());
    }
  }

  private void validateSnapshot(FrozenInventoryPlanSnapshot snapshot) {
    if (snapshot.catalogVersionId() == null
        || snapshot.lines() == null
        || snapshot.lines().isEmpty()
        || snapshot.stages() == null
        || snapshot.stages().isEmpty()
        || snapshot.mediaReferences() == null
        || snapshot.priority() == null
        || snapshot.priority() < 1
        || snapshot.priority() > 5
        || !snapshot.isLogisticsPlanningValid()) {
      throw invalid("Inventory publication frozen plan is invalid");
    }
    if (snapshot.lines().stream().noneMatch(line -> line.type() == InventoryPlanLineType.WORK)) {
      throw invalid("Completed inventory publication requires a nonempty frozen work plan");
    }
    validateCover(snapshot.mediaReferences(), snapshot.coverMediaId());
    validateWorkLineMediaIsolation(snapshot.lines());
    for (InventoryPlanStageSnapshot stage : snapshot.stages()) {
      if (stage.id() == null
          || stage.routing() == null
          || stage.kind() != RepairStageKind.REPAIR_WORK) {
        throw invalid("Inventory publication stage is incomplete");
      }
    }
    for (InventoryPlanLineSnapshot line : snapshot.lines()) {
      if (line.mediaReferences() == null) {
        throw invalid("Inventory publication line media are required");
      }
      if (line.routing() == null) {
        throw invalid("Every inventory plan line requires a frozen routing snapshot");
      }
      if (snapshot.stages().stream()
          .noneMatch(stage -> sameRoute(line.routing(), stage.routing()))) {
        throw invalid("Every inventory line route must match a selected repair-work stage");
      }
    }
  }

  private void validateEvidenceMedia(
      UUID findingId, UUID warehouseId, List<MediaReferenceInput> references) {
    Set<UUID> unique = new HashSet<>();
    for (MediaReferenceInput reference : references) {
      if (reference == null || reference.mediaId() == null || reference.generation() == null
          || !unique.add(reference.mediaId())) {
        throw invalid("Inventory publication media references must be unique and complete");
      }
      MediaFactProjection fact = mediaFacts.findById(reference.mediaId()).orElseThrow(
          () -> new MaintenanceValidationException(
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

  private static void validateWorkLineMediaIsolation(List<InventoryPlanLineSnapshot> lines) {
    Set<UUID> assigned = new HashSet<>();
    for (InventoryPlanLineSnapshot line : lines) {
      if (line.type() != InventoryPlanLineType.WORK && !line.mediaReferences().isEmpty()) {
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

  private static void validateCover(List<MediaReferenceInput> aggregate, UUID coverMediaId) {
    if (aggregate.isEmpty()) {
      if (coverMediaId != null) {
        throw invalid("Inventory cover photo must be null when aggregate media is empty");
      }
      return;
    }
    if (coverMediaId == null
        || aggregate.stream().noneMatch(reference -> coverMediaId.equals(reference.mediaId()))) {
      throw invalid("Inventory cover photo must reference aggregate media");
    }
  }

  private static boolean sameRoute(RoutingSnapshot first, RoutingSnapshot second) {
    return first.queueId().equals(second.queueId())
        && first.queueType().equals(second.queueType());
  }

  static MaintenanceValidationException invalid(String detail) {
    return new MaintenanceValidationException("MAINTENANCE_VALIDATION_FAILED", detail);
  }

  static MaintenanceConflictException conflict(String detail) {
    return new MaintenanceConflictException("MAINTENANCE_IDEMPOTENCY_CONFLICT", detail);
  }

  /**
   * Aborts local publication preparation so a remote prerequisite can be evaluated without
   * retaining database locks; the caller then repeats all local validation before applying work.
   */
  static final class RemotePreflightRequired extends RuntimeException {
    private final RemotePreflightKind kind;
    private final UUID warehouseId;
    private final List<InventoryPlanStageSnapshot> stages;

    private RemotePreflightRequired(
        RemotePreflightKind kind, UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
      super(null, null, false, false);
      this.kind = kind;
      this.warehouseId = warehouseId;
      this.stages = stages;
    }

    static RemotePreflightRequired incoming(UUID warehouseId) {
      return new RemotePreflightRequired(RemotePreflightKind.INCOMING, warehouseId, List.of());
    }

    static RemotePreflightRequired routing(
        UUID warehouseId, List<InventoryPlanStageSnapshot> stages) {
      return new RemotePreflightRequired(RemotePreflightKind.ROUTING, warehouseId, List.copyOf(stages));
    }

    RemotePreflightKind kind() {
      return kind;
    }

    UUID warehouseId() {
      return warehouseId;
    }

    List<InventoryPlanStageSnapshot> stages() {
      return stages;
    }
  }

  /** Selects the owner-side prerequisite required before a publication plan may be materialized. */
  enum RemotePreflightKind {
    INCOMING,
    ROUTING
  }
}

/** Immutable executable view plus raw JSON retained by a published source row. */
record InventoryPublicationValidatedPlan(
    FrozenInventoryPlanSnapshot snapshot,
    List<MediaReferenceInput> sourceMedia,
    String rawSnapshot,
    String rawMedia) {}
