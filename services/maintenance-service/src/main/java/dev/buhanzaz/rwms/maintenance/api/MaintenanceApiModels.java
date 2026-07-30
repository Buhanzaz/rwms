package dev.buhanzaz.rwms.maintenance.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.InvalidNullException;

/** Canonical Stage 6 HTTP wire records. Component names mirror maintenance-service.yaml. */
public final class MaintenanceApiModels {
  private MaintenanceApiModels() {}

  public enum ActorType { USER, SERVICE }
  public enum CatalogNodeType { CATEGORY, SUBCATEGORY, WORK, MATERIAL, LOCATION, OPTION }
  public enum CatalogLinkType { DEPENDENCY, FOLLOW_UP }
  public enum CatalogLinkAnchor { TOP, BOTTOM }
  public enum DeliveryState { PENDING, RETRY_PENDING, DELIVERED, QUARANTINED }
  public enum LeaseReconciliationState {
    NOT_ACQUIRED, ACTIVE, RELEASED, RECONCILIATION_REQUIRED
  }
  public enum GenerationState { PENDING_GENERATION, GENERATED, FAILED }
  public enum InventoryPlanMode { AUTO, MANUAL }
  public enum InventoryPlanLineKind { CATALOG, MANUAL }
  public enum InventoryPlanLineType { WORK, MATERIAL }
  public enum EstimateLineType { WORK, MATERIAL }
  public enum ReworkLineDisposition { ADDED, REPEAT }

  public record ActorSnapshot(String actorId, ActorType actorType) {}
  public record LogisticsEquipmentShortage(
      @NotNull UUID equipmentId,
      @NotNull @Min(1) Long missingQuantity) {}
  public record UpsertLogisticsReturnShortageRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull @Min(0) Long rentalItemVersion,
      @NotNull LocalDate dispatchDate,
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> mediaReferences,
      @NotNull @Size(min = 1, max = 100) List<@Valid LogisticsEquipmentShortage> shortages) {}
  public record LogisticsReturnShortageResponse(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
      List<LogisticsEquipmentShortage> shortages,
      String snapshotSha256,
      OffsetDateTime receivedAt) {}
  public record TransferRepairRequest(
      @NotNull UUID rentalItemId,
      @NotNull UUID sourceWarehouseId,
      @NotNull UUID targetWarehouseId) {
    public TransferRepairRequest {
      if (sourceWarehouseId != null && sourceWarehouseId.equals(targetWarehouseId)) {
        throw new IllegalArgumentException(
            "Transfer source and target warehouses must be different");
      }
    }
  }
  public record PrepareTransferRepairResponse(
      UUID activeRepairId,
      Long activeRepairVersion,
      @NotBlank @Pattern(regexp = "^(FREE|REPAIR)$") String assetStatus) {}
  public record TransferRepairArrivalPreflightResponse(
      UUID activeRepairId,
      boolean priorityRequired,
      boolean movementToShipmentAvailable,
      @NotNull List<UUID> missingQueueDefinitionIds) {}
  public record CompleteTransferRepairRequest(
      @NotNull UUID rentalItemId,
      @NotNull UUID sourceWarehouseId,
      @NotNull UUID targetWarehouseId,
      @JsonProperty(required = true) @Min(1) @Max(5) Integer priority,
      boolean movementToShipment) {
    public CompleteTransferRepairRequest {
      if (sourceWarehouseId != null && sourceWarehouseId.equals(targetWarehouseId)) {
        throw new IllegalArgumentException(
            "Transfer source and target warehouses must be different");
      }
    }
  }
  public record CompleteTransferRepairResponse(
      UUID activeRepairId,
      Long repairVersion,
      @NotNull UUID warehouseId) {}
  public record MediaReferenceInput(@NotNull UUID mediaId, @NotNull @Min(0) Long generation) {}
  /** Catalog commands identify a task-board queue only by its UUID and declared type. */
  public record CatalogRoutingInput(
      @NotNull UUID queueId,
      @NotBlank @Size(max = 64) String queueType) {}
  /** Canonical task-board routing snapshot. Name and type are display/validation snapshots. */
  public record RoutingSnapshot(
      @NotNull UUID queueId,
      @NotBlank @Size(max = 255) String queueName,
      @NotBlank @Size(max = 64) String queueType) {
    public RoutingSnapshot {
      if (queueId == null
          || queueName == null
          || queueName.isBlank()
          || queueName.length() > 255
          || queueType == null
          || queueType.isBlank()
          || queueType.length() > 64) {
        throw new IllegalArgumentException(
            "Canonical queue routing snapshot is incomplete");
      }
      queueName = queueName.trim();
      queueType = queueType.trim().toUpperCase(java.util.Locale.ROOT);
    }
  }
  public record FurnitureEquipmentReference(
      @NotNull UUID equipmentId,
      @NotBlank @Size(max = 255) String equipmentName) {}
  public record CabinCharacteristicReference(
      @NotNull UUID characteristicId,
      @NotBlank @Size(max = 255) String characteristicName) {}

  public record CatalogNodeInput(
      @NotNull UUID id,
      @NotNull CatalogNodeType nodeType,
      @NotBlank @Size(max = 255) String name,
      @NotNull @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) Boolean active,
      UUID parentNodeId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean furnitureCategory,
      @Valid FurnitureEquipmentReference furnitureEquipment,
      @Size(max = 32) String unit,
      @JsonProperty(required = true)
          @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{2})$") String unitPrice,
      @NotNull @Min(0) @Max(525600) Integer durationMinutes,
      @JsonProperty(defaultValue = "true")
          @JsonDeserialize(using = DefaultTrueBooleanDeserializer.class)
          Boolean includeInEstimate,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean commonItem,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean showInMainMenu,
      Integer canvasX,
      Integer canvasY,
      @JsonProperty(required = true) @Valid CatalogRoutingInput routing,
      @Size(max = 2000) String comment,
      @Pattern(regexp = "^#[0-9A-Fa-f]{6}$") String displayColor,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forcesCapitalRepair,
      UUID characteristicId) {
    public CatalogNodeInput {
      if (active == null) throw new IllegalArgumentException("active is required");
      includeInEstimate = includeInEstimate == null ? Boolean.TRUE : includeInEstimate;
      commonItem = commonItem == null ? Boolean.FALSE : commonItem;
      showInMainMenu = showInMainMenu == null ? Boolean.FALSE : showInMainMenu;
      furnitureCategory = furnitureCategory == null ? Boolean.FALSE : furnitureCategory;
      forcesCapitalRepair =
          forcesCapitalRepair == null ? Boolean.FALSE : forcesCapitalRepair;
      displayColor = displayColor == null
          ? null
          : displayColor.toUpperCase(java.util.Locale.ROOT);
    }

  }

  /** Distinguishes an omitted optional flag from an explicitly invalid JSON null. */
  public abstract static class DefaultingBooleanDeserializer extends ValueDeserializer<Boolean> {
    private final boolean absentValue;

    protected DefaultingBooleanDeserializer(boolean absentValue) {
      this.absentValue = absentValue;
    }

    @Override
    public Boolean deserialize(JsonParser parser, DeserializationContext context) {
      return parser.getBooleanValue();
    }

    @Override
    public Boolean getAbsentValue(DeserializationContext context) {
      return absentValue;
    }

    @Override
    public Boolean getNullValue(DeserializationContext context) {
      throw InvalidNullException.from(
          context,
          PropertyName.construct(context.getParser().currentName()),
          context.constructType(Boolean.class));
    }
  }

  public static final class DefaultTrueBooleanDeserializer
      extends DefaultingBooleanDeserializer {
    public DefaultTrueBooleanDeserializer() {
      super(true);
    }
  }

  public static final class DefaultFalseBooleanDeserializer
      extends DefaultingBooleanDeserializer {
    public DefaultFalseBooleanDeserializer() {
      super(false);
    }
  }

  public record CatalogLinkInput(
      @NotNull UUID id,
      @NotNull UUID fromNodeId,
      @NotNull UUID toNodeId,
      @NotNull CatalogLinkType linkType,
      CatalogLinkAnchor sourceAnchor,
      CatalogLinkAnchor targetAnchor,
      @Min(0) int sortOrder) {}
  public record ImportCatalogRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceSha256,
      @NotNull @Size(max = 10000) List<@Valid CatalogNodeInput> nodes,
      @NotNull @Size(max = 20000) List<@Valid CatalogLinkInput> links) {}
  public record CreateCatalogRequest(@NotNull UUID warehouseId) {}
  public record ReplaceCatalogNodesRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 10000) List<@Valid CatalogNodeInput> nodes) {}
  public record ReplaceCatalogLinksRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 20000) List<@Valid CatalogLinkInput> links) {}
  public record ChangeCatalogRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 10000) List<@Valid CatalogNodeInput> nodes,
      @NotNull @Size(max = 20000) List<@Valid CatalogLinkInput> links) {}
  public record VersionCommand(@NotNull @Min(0) Long expectedVersion) {}
  public record CompleteEstimateRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) @Max(5) Integer priority) {
    public CompleteEstimateRequest(Long expectedVersion) {
      this(expectedVersion, 3);
    }
  }
  public record QueueRepairRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) @Max(5) Integer priority) {}

  public record CatalogCounts(int nodes, int links) {}
  public record CatalogValidationReport(
      boolean valid, int errorCount, int warningCount, String reportSha256) {}
  public record CatalogRoutingSyncSnapshot(
      DeliveryState state,
      int registrationsRequired,
      int registrationsConfirmed,
      int cleanupRequired,
      int cleanupConfirmed,
      int attempts,
      OffsetDateTime updatedAt) {}
  public record CatalogVersionResponse(
      UUID id,
      UUID warehouseId,
      long version,
      CatalogVersionState lifecycle,
      String sourceSha256,
      CatalogCounts counts,
      CatalogValidationReport validation,
      OffsetDateTime createdAt,
      OffsetDateTime activatedAt,
      CatalogRoutingSyncSnapshot routingSync) {}
  public record CatalogNodeResponse(
      UUID id,
      UUID catalogVersionId,
      CatalogNodeType nodeType,
      String name,
      boolean active,
      UUID parentNodeId,
      boolean furnitureCategory,
      FurnitureEquipmentReference furnitureEquipment,
      String unit,
      String unitPrice,
      int durationMinutes,
      boolean includeInEstimate,
      boolean commonItem,
      boolean showInMainMenu,
      Integer canvasX,
      Integer canvasY,
      RoutingSnapshot routing,
      String comment,
      String displayColor,
      boolean forcesCapitalRepair,
      CabinCharacteristicReference characteristic) {}
  public record CatalogLinkResponse(
      UUID id,
      UUID catalogVersionId,
      UUID fromNodeId,
      UUID toNodeId,
      CatalogLinkType linkType,
      CatalogLinkAnchor sourceAnchor,
      CatalogLinkAnchor targetAnchor,
      int sortOrder) {}

  public record CatalogNodeSnapshot(
      @NotNull UUID catalogVersionId,
      @NotNull UUID nodeId,
      @NotNull CatalogNodeType nodeType,
      @NotBlank @Size(max = 255) String name,
      @JsonProperty(required = true) @Size(max = 32) String unit,
      @JsonProperty(required = true)
          @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{2})$") String unitPrice,
      @NotNull @Min(0) Integer durationMinutes,
      @JsonProperty(required = true) @Valid RoutingSnapshot routing,
      @JsonProperty(required = true) @Valid FurnitureEquipmentReference furnitureEquipment,
      boolean forcesCapitalRepair,
      @JsonProperty(required = true) @Valid CabinCharacteristicReference characteristic) {
    public CatalogNodeSnapshot {
      if (catalogVersionId == null
          || nodeId == null
          || (nodeType != CatalogNodeType.WORK
              && nodeType != CatalogNodeType.MATERIAL)
          || name == null
          || name.isBlank()
          || unit == null
          || unit.isBlank()
          || unitPrice == null
          || durationMinutes == null) {
        throw new IllegalArgumentException(
            "Canonical catalog line snapshot is incomplete");
      }
      if ((nodeType == CatalogNodeType.WORK && durationMinutes < 1)
          || (nodeType == CatalogNodeType.MATERIAL && durationMinutes != 0)
          || (forcesCapitalRepair && nodeType != CatalogNodeType.WORK)
          || (characteristic != null
              && nodeType != CatalogNodeType.MATERIAL)) {
        throw new IllegalArgumentException(
            "Canonical catalog line snapshot violates its type invariants");
      }
    }

  }
  public record EstimateLineInput(
      @NotNull UUID id,
      @JsonProperty(required = true) @Valid CatalogNodeSnapshot catalogSnapshot,
      @NotNull @JsonProperty(required = true) EstimateLineType lineType,
      @NotBlank @Size(max = 1000) String description,
      @JsonProperty(required = true) @Size(max = 32) String unit,
      @NotBlank @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$") String quantity,
      @NotBlank @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{2})$") String unitPrice,
      @Min(0) @Max(525600) Integer normativeMinutes,
      @Size(max = 2000) String comment,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {
  }
  public record PlanStageInput(
      @NotNull UUID id,
      @NotNull RepairStageKind kind,
      @NotNull @Min(0) Integer order,
      @NotNull @Valid RoutingSnapshot routing,
      @NotNull @Size(max = 2000) List<@NotNull UUID> includedLineIds,
      @JsonProperty(required = true) UUID primaryLineId,
      @NotNull @Size(max = 2000) String groupComment,
      OffsetDateTime taskDeadline) {
    public PlanStageInput(
        UUID id,
        RepairStageKind kind,
        Integer order,
        RoutingSnapshot routing,
        OffsetDateTime taskDeadline) {
      this(id, kind, order, routing, List.of(), null, "", taskDeadline);
    }
  }
  public record CreateEstimateRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull LocalDate dispatchDate,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId) {
    public CreateEstimateRequest(
        UUID warehouseId,
        UUID rentalItemId,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(
          warehouseId,
          rentalItemId,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          null);
    }
  }
  public record UpdateEstimateRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull LocalDate dispatchDate,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId) {
    public UpdateEstimateRequest(
        Long expectedVersion,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(
          expectedVersion,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          null);
    }
  }
  public record AmendEstimateRequest(
      @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @Min(0) Long expectedLinkedRepairVersion,
      @NotNull LocalDate dispatchDate,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId) {
    public AmendEstimateRequest(
        Long expectedVersion,
        Long expectedLinkedRepairVersion,
        LocalDate dispatchDate,
        String reason,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(
          expectedVersion,
          expectedLinkedRepairVersion,
          dispatchDate,
          reason,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          null);
    }
  }

  public record EstimateLineResponse(
      UUID id,
      CatalogNodeSnapshot catalogSnapshot,
      @JsonProperty(required = true) EstimateLineType lineType,
      String description,
      @JsonProperty(required = true) @Size(max = 32) String unit,
      String quantity,
      String unitPrice,
      String lineTotal,
      int normativeMinutes,
      String comment,
      List<MediaReferenceInput> mediaReferences,
      ReworkLineDisposition disposition,
      UUID sourceRepairId,
      UUID sourceLineId,
      UUID lineageRootLineId) {
    public EstimateLineResponse {
      if (id == null
          || lineType == null
          || description == null
          || description.isBlank()
          || unit == null
          || unit.isBlank()
          || quantity == null
          || unitPrice == null
          || lineTotal == null
          || mediaReferences == null) {
        throw new IllegalArgumentException(
            "Canonical estimate line snapshot is incomplete");
      }
      if ((lineType == EstimateLineType.WORK && normativeMinutes < 1)
          || (lineType == EstimateLineType.MATERIAL && normativeMinutes != 0)) {
        throw new IllegalArgumentException(
            "Canonical estimate line duration is inconsistent with its type");
      }
      if (catalogSnapshot != null
          && ((lineType == EstimateLineType.WORK
                  && catalogSnapshot.nodeType() != CatalogNodeType.WORK)
              || (lineType == EstimateLineType.MATERIAL
                  && catalogSnapshot.nodeType() != CatalogNodeType.MATERIAL))) {
        throw new IllegalArgumentException(
            "Estimate line type differs from its catalog snapshot");
      }
      mediaReferences = List.copyOf(mediaReferences);
    }

    public EstimateLineResponse(
        UUID id,
        CatalogNodeSnapshot catalogSnapshot,
        EstimateLineType lineType,
        String description,
        String unit,
        String quantity,
        String unitPrice,
        String lineTotal,
        Integer normativeMinutes,
        String comment,
        List<MediaReferenceInput> mediaReferences) {
      this(
          id,
          catalogSnapshot,
          lineType,
          description,
          unit,
          quantity,
          unitPrice,
          lineTotal,
          java.util.Objects.requireNonNull(
              normativeMinutes, "normativeMinutes is required"),
          comment,
          mediaReferences,
          null,
          null,
          null,
          null);
    }
  }
  public record EstimateRevisionResponse(
      int revision,
      LocalDate dispatchDate,
      String sourceParty,
      List<EstimateLineResponse> lines,
      List<PlanStageInput> plan,
      String total,
      String reason,
      OffsetDateTime recordedAt) {}
  public record EstimateResponse(
      UUID id,
      UUID warehouseId,
      UUID rentalItemId,
      long version,
      EstimateState lifecycle,
      int currentRevision,
      List<EstimateRevisionResponse> revisions,
      UUID repairId,
      List<MediaReferenceInput> mediaReferences,
      UUID coverMediaId,
      OffsetDateTime createdAt,
      OffsetDateTime completedAt,
      ActorSnapshot actor) {
    public EstimateResponse(
        UUID id,
        UUID warehouseId,
        UUID rentalItemId,
        long version,
        EstimateState lifecycle,
        int currentRevision,
        List<EstimateRevisionResponse> revisions,
        UUID repairId,
        List<MediaReferenceInput> mediaReferences,
        OffsetDateTime createdAt,
        OffsetDateTime completedAt,
        ActorSnapshot actor) {
      this(
          id,
          warehouseId,
          rentalItemId,
          version,
          lifecycle,
          currentRevision,
          revisions,
          repairId,
          mediaReferences,
          null,
          createdAt,
          completedAt,
          actor);
    }
  }

  public record DeliverySnapshot(DeliveryState state, int attempts, OffsetDateTime updatedAt) {}
  public record LeaseSnapshot(
      UUID leaseId,
      Long fencingToken,
      OffsetDateTime expiresAt,
      LeaseReconciliationState reconciliationState) {}
  public record TaskSyncSnapshot(
      UUID externalTaskId,
      Long taskBoardRegistrationVersion,
      GenerationState generationState,
      DeliverySnapshot delivery) {}
  public enum TaskEvidenceState {
    READY,
    REVIEW_REQUIRED
  }
  public record TaskEvidenceResponse(
      UUID evidenceId,
      UUID entryId,
      UUID workerId,
      UUID workerGroupId,
      UUID mediaId,
      long mediaGeneration,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      TaskEvidenceState state) {}
  public record RepairWorkerEvidenceResponse(
      UUID evidenceId,
      UUID repairId,
      UUID stageId,
      int stageIndex,
      UUID entryId,
      UUID taskId,
      int routeIndex,
      UUID workerId,
      UUID workerGroupId,
      UUID mediaId,
      long mediaGeneration,
      OffsetDateTime capturedAt,
      OffsetDateTime recordedAt,
      TaskEvidenceState state) {}
  public record RepairStageResponse(
      UUID id,
      RepairStageKind kind,
      int order,
      RepairStageState state,
      RoutingSnapshot routing,
      List<EstimateLineResponse> workLines,
      List<EstimateLineResponse> materialLines,
      UUID primaryLineId,
      String groupComment,
      List<TaskEvidenceResponse> evidence,
      OffsetDateTime taskDeadline,
      TaskSyncSnapshot taskSync,
      OffsetDateTime completedAt) {
    public RepairStageResponse(
        UUID id,
        RepairStageKind kind,
        int order,
        RepairStageState state,
        RoutingSnapshot routing,
        OffsetDateTime taskDeadline,
        TaskSyncSnapshot taskSync,
        OffsetDateTime completedAt) {
      this(
          id,
          kind,
          order,
          state,
          routing,
          List.of(),
          List.of(),
          null,
          "",
          List.of(),
          taskDeadline,
          taskSync,
          completedAt);
    }
  }
  public record RepairPlanResponse(
      UUID repairId, long repairVersion, List<RepairStageResponse> stages) {}
  public record InventorySourceReference(
      UUID inventoryId,
      UUID findingId,
      @Min(1) long sourceRevision,
      String planFingerprint,
      String sourceFingerprint) {}
  public record RepairComplexitySnapshot(
      @NotNull RepairComplexity type,
      @NotBlank String name,
      @Pattern(regexp = "^#[0-9A-F]{6}$") String color,
      @NotBlank
          @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$")
          String plannedMinutes,
      boolean forcedCapital) {}
  public record RepairResponse(
      UUID id,
      UUID rootRepairId,
      UUID sourceRepairId,
      UUID estimateId,
      UUID warehouseId,
      UUID rentalItemId,
      RepairOrigin origin,
      RepairKind kind,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      long version,
      LocalDate dispatchDate,
      int priority,
      String sourceParty,
      RepairPlanResponse plan,
      InventorySourceReference inventorySource,
      LeaseSnapshot lease,
      List<MediaReferenceInput> mediaReferences,
      UUID coverMediaId,
      RepairComplexitySnapshot complexity,
      boolean movementToShipment,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      ActorSnapshot actor) {
  }

  public record InventoryPlanLineInput(
      @NotNull InventoryPlanLineKind aggregationKind,
      @JsonProperty(required = true) UUID catalogNodeId,
      @JsonProperty(required = true) @Size(max = 1000) String description,
      @JsonProperty(required = true) InventoryPlanLineType type,
      @JsonProperty(required = true) @Size(max = 32) String unit,
      @NotBlank
          @Pattern(regexp = "^(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,3})?$")
          String quantity,
      @JsonProperty(required = true) @Min(0) Long unitPriceMinor,
      @JsonProperty(required = true)
          @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$")
          String normativeMinutes,
      @Size(max = 2000) String groupComment,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record InventoryPlanStageSelection(
      @NotNull UUID catalogNodeId,
      @NotNull RepairStageKind kind,
      @NotNull @Min(0) Integer order) {}
  public record FreezeInventoryPlanRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID inventoryId,
      @NotNull UUID findingId,
      @NotNull @Min(1) Long sourceRevision,
      @NotNull InventoryPlanMode mode,
      @NotEmpty @Size(max = 2000) List<@Valid InventoryPlanLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid InventoryPlanStageSelection> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) UUID coverMediaId) {}
  public record InventoryPlanLineSnapshot(
      @NotNull InventoryPlanLineKind aggregationKind,
      @JsonProperty(required = true) UUID catalogVersionId,
      @JsonProperty(required = true) UUID catalogNodeId,
      @JsonProperty(required = true) String catalogNodeName,
      @NotNull InventoryPlanLineType type,
      @NotBlank String description,
      @JsonProperty(required = true) String normalizedDescription,
      @JsonProperty(required = true) String unit,
      @NotBlank String quantity,
      @Min(0) long unitPriceMinor,
      @NotBlank String normativeMinutes,
      @JsonProperty(required = true) @Valid RoutingSnapshot routing,
      @JsonProperty(required = true) String groupComment,
      @NotNull List<@Valid MediaReferenceInput> mediaReferences,
      boolean forcesCapitalRepair,
      @JsonProperty(required = true) @Valid CabinCharacteristicReference characteristic) {}
  public record InventoryPlanStageSnapshot(
      @NotNull UUID id,
      @NotNull UUID catalogNodeId,
      @NotBlank String catalogNodeName,
      @NotNull RepairStageKind kind,
      @Min(0) int order,
      @NotNull @Valid RoutingSnapshot routing,
      @Min(0) int normativeDurationMinutes) {}
  public record FrozenInventoryPlanSnapshot(
      @NotNull UUID catalogVersionId,
      @NotNull InventoryPlanMode mode,
      @NotEmpty List<@Valid InventoryPlanLineSnapshot> lines,
      @NotEmpty List<@Valid InventoryPlanStageSnapshot> stages,
      boolean moveToRepairRequired,
      boolean moveFromRepairRequired,
      @NotNull List<@Valid MediaReferenceInput> mediaReferences,
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) UUID coverMediaId) {}
  public record FrozenInventoryPlanResponse(
      UUID warehouseId,
      UUID inventoryId,
      UUID findingId,
      @Min(1) long sourceRevision,
      FrozenInventoryPlanSnapshot snapshot,
      String fingerprint) {}
  public record UpsertInventoryRepairRequest(
      @NotNull UUID warehouseId,
      @NotNull @Min(1) Long sourceRevision,
      @NotNull UUID rentalItemId,
      @NotNull @Min(0) Long rentalItemVersion,
      @NotNull LocalDate dispatchDate,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String planFingerprint,
      @NotNull @Valid FrozenInventoryPlanSnapshot snapshot) {}
  public record InventoryRepairUpsertResponse(
      RepairResponse repair,
      InventorySourceReference source,
      DeliverySnapshot delivery) {}
  public record InventoryRepairSnapshotRequest(
      @NotEmpty @Size(max = 5000) List<@NotNull UUID> assetIds) {
    public InventoryRepairSnapshotRequest {
      if (assetIds == null || assetIds.isEmpty() || assetIds.size() > 5000) {
        throw new IllegalArgumentException("assetIds must contain from 1 to 5000 identities");
      }
      if (assetIds.stream().anyMatch(java.util.Objects::isNull)) {
        throw new IllegalArgumentException("assetIds cannot contain null");
      }
      if (new java.util.HashSet<>(assetIds).size() != assetIds.size()) {
        throw new IllegalArgumentException("assetIds must be unique");
      }
      assetIds = List.copyOf(assetIds);
    }
  }
  public record InventoryRepairFact(
      UUID repairId,
      UUID rootRepairId,
      RepairOrigin origin,
      RepairKind kind,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      String planFingerprintSha256) {}
  public record InventoryRepairSnapshot(
      UUID assetId,
      List<InventoryRepairFact> repairs) {}
  public record InventoryRepairSnapshotsResponse(
      List<InventoryRepairSnapshot> assets) {}

  public record CreateDirectRepairRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull LocalDate dispatchDate,
      @JsonProperty(required = true) @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId) {
    public CreateDirectRepairRequest(
        UUID warehouseId,
        UUID rentalItemId,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(
          warehouseId,
          rentalItemId,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          null);
    }

    public CreateDirectRepairRequest(
        UUID warehouseId,
        UUID rentalItemId,
        LocalDate dispatchDate,
        String sourceParty,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(
          warehouseId,
          rentalItemId,
          dispatchDate,
          sourceParty,
          List.of(),
          plan,
          mediaReferences,
          null);
    }
  }
  public record UpdateRepairPlanRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> stages,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId) {
    public UpdateRepairPlanRequest(Long expectedVersion, List<PlanStageInput> stages) {
      this(expectedVersion, List.of(), stages, List.of(), null);
    }

    public UpdateRepairPlanRequest(
        Long expectedVersion,
        List<PlanStageInput> stages,
        List<MediaReferenceInput> mediaReferences) {
      this(expectedVersion, List.of(), stages, mediaReferences, null);
    }

    public UpdateRepairPlanRequest(
        Long expectedVersion,
        List<EstimateLineInput> lines,
        List<PlanStageInput> stages,
        List<MediaReferenceInput> mediaReferences) {
      this(expectedVersion, lines, stages, mediaReferences, null);
    }
  }

  @JsonTypeInfo(
      use = JsonTypeInfo.Id.NAME,
      include = JsonTypeInfo.As.EXISTING_PROPERTY,
      property = "disposition",
      visible = true)
  @JsonSubTypes({
    @JsonSubTypes.Type(value = AddedReworkLineInput.class, name = "ADDED"),
    @JsonSubTypes.Type(value = RepeatReworkLineInput.class, name = "REPEAT")
  })
  public sealed interface ReworkLineInput
      permits AddedReworkLineInput, RepeatReworkLineInput {
    UUID id();
    ReworkLineDisposition disposition();
  }

  public record AddedReworkLineInput(
      @NotNull UUID id,
      @NotNull ReworkLineDisposition disposition,
      @NotNull @Valid EstimateLineInput line)
      implements ReworkLineInput {}

  public record RepeatReworkLineInput(
      @NotNull UUID id,
      @NotNull ReworkLineDisposition disposition,
      @NotNull UUID sourceRepairId,
      @NotNull UUID sourceLineId,
      @NotBlank
          @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$")
          String quantity,
      @Size(max = 2000) String comment)
      implements ReworkLineInput {}

  public record CreateReworkRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 2000) String reason,
      @NotNull @Size(max = 2000) List<@Valid ReworkLineInput> lines,
      @NotEmpty @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId) {
    public CreateReworkRequest(
        Long expectedVersion,
        String reason,
        java.util.Collection<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(
          expectedVersion,
          reason,
          lines.stream()
              .map(
                  line ->
                      (ReworkLineInput)
                          new AddedReworkLineInput(
                              line.id(), ReworkLineDisposition.ADDED, line))
              .toList(),
          plan,
          mediaReferences,
          null);
    }

    public CreateReworkRequest(
        Long expectedVersion,
        String reason,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences) {
      this(expectedVersion, reason, List.of(), plan, mediaReferences, null);
    }
  }

  public record ReworkCandidateLine(
      UUID sourceRepairId,
      UUID sourceLineId,
      UUID lineageRootLineId,
      EstimateLineResponse line) {}

  public record ReworkCandidatesResponse(List<ReworkCandidateLine> items) {}
  public record RepairDecisionRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 2000) String comment,
      @NotEmpty @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {
    public RepairDecisionRequest(Long expectedVersion, String comment) {
      this(expectedVersion, comment, List.of());
    }
  }
  public record WriteOffRepairRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 2000) String comment) {}

  public record EstimateCommandResult(
      EstimateResponse estimate, RepairResponse repair, DeliverySnapshot delivery) {}
  public record RepairCommandResult(
      RepairResponse repair,
      List<RepairResponse> affectedSourceRepairs,
      DeliverySnapshot delivery) {}
  public record AcceptanceProjection(
      UUID repairId,
      UUID rootRepairId,
      UUID warehouseId,
      UUID rentalItemId,
      RepairExecutionState executionState,
      RepairAcceptanceState acceptanceState,
      long repairVersion,
      OffsetDateTime readyAt) {}
  public record WriteOffProjection(
      UUID repairId,
      UUID rootRepairId,
      UUID warehouseId,
      UUID rentalItemId,
      long repairVersion,
      OffsetDateTime writtenOffAt,
      ActorSnapshot actor) {}
  public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {}
}
