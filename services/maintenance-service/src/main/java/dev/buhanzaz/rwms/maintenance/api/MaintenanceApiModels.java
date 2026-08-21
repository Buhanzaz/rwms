package dev.buhanzaz.rwms.maintenance.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.JsonSubTypes;
import com.fasterxml.jackson.annotation.JsonTypeInfo;
import com.fasterxml.jackson.annotation.Nulls;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.core.JsonParser;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.PropertyName;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.annotation.JsonDeserialize;
import tools.jackson.databind.exc.InvalidNullException;

/** Canonical Stage 6 HTTP wire records. Component names mirror maintenance-service.yaml. */
public final class MaintenanceApiModels {
  private MaintenanceApiModels() {}

  private static boolean validInboundLogisticsPlanning(
      boolean movementToRepair,
      RepairLogisticsPlanningMode mode,
      LocalDate scheduledDate) {
    if (!movementToRepair) {
      return mode == null && scheduledDate == null;
    }
    return mode != null
        && ((mode == RepairLogisticsPlanningMode.AUTO && scheduledDate == null)
            || (mode == RepairLogisticsPlanningMode.FIXED_DATE && scheduledDate != null));
  }

  public enum ActorType { USER, SERVICE }
  public enum CatalogNodeType { CATEGORY, SUBCATEGORY, WORK, MATERIAL, LOCATION, OPTION }
  public enum CatalogLinkType { DEPENDENCY, FOLLOW_UP }
  public enum CatalogLinkAnchor { TOP, BOTTOM }
  public enum DeliveryState { PENDING, RETRY_PENDING, DELIVERED, QUARANTINED }
  public enum LeaseReconciliationState {
    NOT_ACQUIRED, ACTIVE, RELEASED, RECONCILIATION_REQUIRED
  }
  public enum GenerationState { PENDING_GENERATION, GENERATED, NOT_REQUIRED, FAILED }
  public enum InventoryPlanMode { AUTO, MANUAL }
  public enum InventoryPlanLineKind { CATALOG, MANUAL }
  public enum InventoryPlanLineType { WORK, MATERIAL }
  /** The maintenance aggregate selected from the current asset truth for an inventory finding. */
  public enum InventoryPublicationTargetKind { ESTIMATE, REPAIR }
  /** A manager-approved publication decision for one completed inventory finding. */
  public enum InventoryPublicationStrategy { CREATE, REPLACE, MERGE }
  /** Immutable result shape for a completed-inventory publication source. */
  public enum InventoryPublicationOutcome { CREATED, SUCCESSOR, MATCHED }
  /** Lifecycle of a locally stored successor; only maintenance may release it to queueing. */
  public enum InventoryPublicationSuccessorState { WAITING_PREDECESSOR, RELEASED }
  /** Proven fact that made a locally stored successor eligible for normal queue orchestration. */
  public enum InventoryPublicationTerminalFact { TASK_BOARD_COMPLETION, REPAIR_ACCEPTANCE }
  /** Why a frozen inventory line was retained in, or removed from, the successor delta. */
  public enum InventoryPublicationDeltaDisposition {
    RETAINED,
    RETAINED_AFTER_DEDUCTION,
    REMOVED_AS_ALREADY_PRESENT,
    RETAINED_AMBIGUOUS
  }
  public enum EstimateLineType { WORK, MATERIAL }
  public enum ReworkLineDisposition { ADDED, REPEAT }

  public record ActorSnapshot(String actorId, ActorType actorType) {}
  public record UpsertLogisticsReturnEstimateSourceRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull @Min(0) Long rentalItemVersion,
      @NotNull LocalDate dispatchDate,
      @NotNull OffsetDateTime arrivedAt,
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record ReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
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
      @NotNull List<UUID> missingQueueDefinitionIds) {}
  public record CompleteTransferRepairRequest(
      @NotNull UUID rentalItemId,
      @NotNull @Min(0) Long rentalItemVersion,
      @NotNull UUID sourceWarehouseId,
      @NotNull UUID targetWarehouseId,
      @JsonProperty(required = true) @Min(1) @Max(5) Integer priority) {
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
  /**
   * Asset-owned furniture identity and editable versioned maximum shown in the maintenance catalog.
   * A null identity/version is allowed only while a newly submitted durable link intent is pending.
   */
  public record FurnitureEquipmentReference(
      UUID equipmentId,
      @NotBlank @Size(max = 255) String equipmentName,
      @Min(0) Long equipmentVersion,
      @Positive Integer maximumPerCabin) {
    public FurnitureEquipmentReference(UUID equipmentId, String equipmentName) {
      this(equipmentId, equipmentName, null, null);
    }
  }
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
      comment = nodeType == CatalogNodeType.MATERIAL
          ? null
          : comment == null || comment.isBlank() ? null : comment.trim();
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
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) boolean movementToRepair,
      @JsonProperty(required = true)
          RepairLogisticsPlanningMode logisticsPlanningMode,
      @JsonProperty(required = true)
          LocalDate logisticsScheduledDate,
      Boolean allowUnaccountedFurniture) {
    public CompleteEstimateRequest(Long expectedVersion) {
      this(
          expectedVersion,
          3,
          false,
          null,
          null,
          null);
    }

    public CompleteEstimateRequest(
        Long expectedVersion, Integer priority) {
      this(
          expectedVersion,
          priority,
          false,
          null,
          null,
          null);
    }

    public CompleteEstimateRequest(
        Long expectedVersion,
        Integer priority,
        boolean movementToRepair,
        RepairLogisticsPlanningMode logisticsPlanningMode,
        LocalDate logisticsScheduledDate) {
      this(
          expectedVersion,
          priority,
          movementToRepair,
          logisticsPlanningMode,
          logisticsScheduledDate,
          null);
    }

    @JsonIgnore
    public boolean allowsUnaccountedFurniture() {
      return Boolean.TRUE.equals(allowUnaccountedFurniture);
    }

    @AssertTrue(
        message =
            "inbound logistics planning must be present only when movementToRepair is selected")
    @JsonIgnore
    public boolean isLogisticsPlanningValid() {
      return validInboundLogisticsPlanning(
          movementToRepair, logisticsPlanningMode, logisticsScheduledDate);
    }
  }
  public record QueueRepairRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) boolean movementToRepair,
      @JsonProperty(required = true)
          RepairLogisticsPlanningMode logisticsPlanningMode,
      @JsonProperty(required = true)
          LocalDate logisticsScheduledDate) {
    public QueueRepairRequest(
        Long expectedVersion, Integer priority) {
      this(
          expectedVersion,
          priority,
          false,
          null,
          null);
    }

    @AssertTrue(
        message =
            "inbound logistics planning must be present only when movementToRepair is selected")
    @JsonIgnore
    public boolean isLogisticsPlanningValid() {
      return validInboundLogisticsPlanning(
          movementToRepair, logisticsPlanningMode, logisticsScheduledDate);
    }
  }
  /** Reviewed recovery input for the exact quarantined inbound driver-task intent. */
  public record RetryInboundDeliveryRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @JsonProperty(required = true)
          RepairLogisticsPlanningMode logisticsPlanningMode,
      @JsonProperty(required = true) LocalDate logisticsScheduledDate,
      @NotBlank @Size(max = 2000) String reason) {
    @AssertTrue(
        message =
            "inbound delivery recovery requires AUTO with no date or FIXED_DATE with a date")
    @JsonIgnore
    public boolean isLogisticsPlanningValid() {
      return validInboundLogisticsPlanning(
          true, logisticsPlanningMode, logisticsScheduledDate);
    }
  }

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
      UUID coverMediaId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public CreateEstimateRequest {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public CreateEstimateRequest(
        UUID warehouseId,
        UUID rentalItemId,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences,
        UUID coverMediaId) {
      this(
          warehouseId,
          rentalItemId,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          coverMediaId,
          false);
    }

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
          null,
          false);
    }
  }
  public record UpdateEstimateRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull LocalDate dispatchDate,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public UpdateEstimateRequest {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public UpdateEstimateRequest(
        Long expectedVersion,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences,
        UUID coverMediaId) {
      this(
          expectedVersion,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          coverMediaId,
          false);
    }

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
          null,
          false);
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
      UUID coverMediaId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public AmendEstimateRequest {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public AmendEstimateRequest(
        Long expectedVersion,
        Long expectedLinkedRepairVersion,
        LocalDate dispatchDate,
        String reason,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences,
        UUID coverMediaId) {
      this(
          expectedVersion,
          expectedLinkedRepairVersion,
          dispatchDate,
          reason,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          coverMediaId,
          false);
    }

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
          null,
          false);
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
      if (lineType == EstimateLineType.MATERIAL) {
        comment = null;
      } else {
        comment = comment == null || comment.isBlank() ? null : comment.trim();
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
      OffsetDateTime recordedAt,
      boolean forceCapitalRepair) {
    public EstimateRevisionResponse(
        int revision,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineResponse> lines,
        List<PlanStageInput> plan,
        String total,
        String reason,
        OffsetDateTime recordedAt) {
      this(
          revision,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          total,
          reason,
          recordedAt,
          false);
    }
  }
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
      ActorSnapshot actor,
      boolean forceCapitalRepair) {
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
        UUID coverMediaId,
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
          coverMediaId,
          createdAt,
          completedAt,
          actor,
          false);
    }

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
          actor,
          false);
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
      UUID taskBoardEntryId,
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
      @NotNull RepairReclassificationState reclassificationState,
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
      boolean movementToRepair,
      @JsonProperty(required = true)
          RepairLogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      ActorSnapshot actor,
      boolean forceCapitalRepair) {
    public RepairResponse(
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
        RepairReclassificationState reclassificationState,
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
        boolean movementToRepair,
        RepairLogisticsPlanningMode logisticsPlanningMode,
        LocalDate logisticsScheduledDate,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt,
        ActorSnapshot actor) {
      this(
          id,
          rootRepairId,
          sourceRepairId,
          estimateId,
          warehouseId,
          rentalItemId,
          origin,
          kind,
          executionState,
          acceptanceState,
          reclassificationState,
          version,
          dispatchDate,
          priority,
          sourceParty,
          plan,
          inventorySource,
          lease,
          mediaReferences,
          coverMediaId,
          complexity,
          movementToRepair,
          logisticsPlanningMode,
          logisticsScheduledDate,
          createdAt,
          updatedAt,
          actor,
          false);
    }
  }

  public record InventoryPlanLineInput(
      @NotNull InventoryPlanLineKind aggregationKind,
      @JsonProperty(required = true) UUID catalogNodeId,
      @JsonProperty(required = true) UUID routingCatalogNodeId,
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
      @JsonProperty(required = true) UUID coverMediaId,
      @JsonProperty(required = true) boolean movementToRepair,
      @JsonProperty(required = true)
          RepairLogisticsPlanningMode logisticsPlanningMode,
      @JsonProperty(required = true)
          LocalDate logisticsScheduledDate,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public FreezeInventoryPlanRequest {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public FreezeInventoryPlanRequest(
        UUID warehouseId,
        UUID inventoryId,
        UUID findingId,
        Long sourceRevision,
        InventoryPlanMode mode,
        List<InventoryPlanLineInput> lines,
        List<InventoryPlanStageSelection> plan,
        List<MediaReferenceInput> mediaReferences,
        Integer priority,
        UUID coverMediaId,
        boolean movementToRepair,
        RepairLogisticsPlanningMode logisticsPlanningMode,
        LocalDate logisticsScheduledDate) {
      this(
          warehouseId,
          inventoryId,
          findingId,
          sourceRevision,
          mode,
          lines,
          plan,
          mediaReferences,
          priority,
          coverMediaId,
          movementToRepair,
          logisticsPlanningMode,
          logisticsScheduledDate,
          false);
    }

    public FreezeInventoryPlanRequest(
        UUID warehouseId,
        UUID inventoryId,
        UUID findingId,
        Long sourceRevision,
        InventoryPlanMode mode,
        List<InventoryPlanLineInput> lines,
        List<InventoryPlanStageSelection> plan,
        List<MediaReferenceInput> mediaReferences,
        Integer priority,
        UUID coverMediaId) {
      this(
          warehouseId,
          inventoryId,
          findingId,
          sourceRevision,
          mode,
          lines,
          plan,
          mediaReferences,
          priority,
          coverMediaId,
          false,
          null,
          null,
          false);
    }

    @AssertTrue(
        message =
            "inbound logistics planning must be present only when movementToRepair is selected")
    @JsonIgnore
    public boolean isLogisticsPlanningValid() {
      return validInboundLogisticsPlanning(
          movementToRepair, logisticsPlanningMode, logisticsScheduledDate);
    }

    /** The explicit capital route and inbound repair delivery are alternative choices. */
    @AssertTrue(message = "movementToRepair and forceCapitalRepair are mutually exclusive")
    @JsonIgnore
    public boolean isRepairDestinationChoiceValid() {
      return !movementToRepair || !forceCapitalRepair;
    }
  }
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
      @JsonProperty(required = true) @Valid CabinCharacteristicReference characteristic) {
    public InventoryPlanLineSnapshot {
      if (mediaReferences == null) {
        throw new IllegalArgumentException("Inventory plan line media are required");
      }
      if (type == InventoryPlanLineType.MATERIAL) {
        groupComment = null;
      } else {
        groupComment =
            groupComment == null || groupComment.isBlank() ? null : groupComment.trim();
      }
      mediaReferences = List.copyOf(mediaReferences);
    }
  }
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
      @JsonProperty(required = true) boolean movementToRepair,
      @NotNull List<@Valid MediaReferenceInput> mediaReferences,
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) UUID coverMediaId,
      @JsonProperty(required = true)
          RepairLogisticsPlanningMode logisticsPlanningMode,
      @JsonProperty(required = true)
          LocalDate logisticsScheduledDate,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public FrozenInventoryPlanSnapshot {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public FrozenInventoryPlanSnapshot(
        UUID catalogVersionId,
        InventoryPlanMode mode,
        List<InventoryPlanLineSnapshot> lines,
        List<InventoryPlanStageSnapshot> stages,
        boolean movementToRepair,
        List<MediaReferenceInput> mediaReferences,
        Integer priority,
        UUID coverMediaId,
        RepairLogisticsPlanningMode logisticsPlanningMode,
        LocalDate logisticsScheduledDate) {
      this(
          catalogVersionId,
          mode,
          lines,
          stages,
          movementToRepair,
          mediaReferences,
          priority,
          coverMediaId,
          logisticsPlanningMode,
          logisticsScheduledDate,
          false);
    }

    public FrozenInventoryPlanSnapshot(
        UUID catalogVersionId,
        InventoryPlanMode mode,
        List<InventoryPlanLineSnapshot> lines,
        List<InventoryPlanStageSnapshot> stages,
        boolean movementToRepair,
        List<MediaReferenceInput> mediaReferences,
        Integer priority,
        UUID coverMediaId) {
      this(
          catalogVersionId,
          mode,
          lines,
          stages,
          movementToRepair,
          mediaReferences,
          priority,
          coverMediaId,
          null,
          null,
          false);
    }

    @AssertTrue(
        message =
            "inbound logistics planning must be present only when movementToRepair is selected")
    @JsonIgnore
    public boolean isLogisticsPlanningValid() {
      return validInboundLogisticsPlanning(
          movementToRepair, logisticsPlanningMode, logisticsScheduledDate);
    }

    /** The frozen capital route and inbound repair delivery cannot coexist. */
    @AssertTrue(message = "movementToRepair and forceCapitalRepair are mutually exclusive")
    @JsonIgnore
    public boolean isRepairDestinationChoiceValid() {
      return !movementToRepair || !forceCapitalRepair;
    }
  }
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

  /**
   * Immutable completed-inventory evidence.  {@code snapshot} deliberately remains raw JSON:
   * inventory schema version 1 retained the historical movementToShipment marker, while version
   * 2 removed it.  Maintenance hashes and stores that exact source document before adapting it
   * to the current executable plan model.
   */
  public record InventoryPublicationFindingInput(
      @NotNull UUID findingId,
      @NotNull @Min(1) Long findingRevision,
      @NotNull UUID assetId,
      @NotNull @Min(0) Long assetVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String planFingerprintSha256,
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) boolean movementToRepair,
      @JsonProperty(required = true) LocalDate movementScheduledDate,
      @NotNull LocalDate repairScheduledDate,
      @NotNull @JsonSetter(nulls = Nulls.FAIL) JsonNode snapshot,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> media,
      @NotNull @Min(1) @Max(2) Integer snapshotSchemaVersion,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public InventoryPublicationFindingInput(
        UUID findingId,
        Long findingRevision,
        UUID assetId,
        Long assetVersion,
        String planFingerprintSha256,
        Integer priority,
        boolean movementToRepair,
        LocalDate movementScheduledDate,
        LocalDate repairScheduledDate,
        JsonNode snapshot,
        List<MediaReferenceInput> media,
        Integer snapshotSchemaVersion) {
      this(
          findingId,
          findingRevision,
          assetId,
          assetVersion,
          planFingerprintSha256,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          snapshot,
          media,
          snapshotSchemaVersion,
          false);
    }

    public InventoryPublicationFindingInput {
      media = media == null ? null : List.copyOf(media);
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }
  }

  /**
   * Immutable final-plan identity and its maintenance candidate inputs.
   *
   * <p>An empty findings list is valid when the completed inventory contains no maintenance work;
   * the list itself remains required and bounded.
   */
  public record InventoryPublicationPreflightRequest(
      @NotNull UUID inventoryId,
      @NotNull UUID warehouseId,
      @NotNull @Min(1) Long finalPlanVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotNull @Size(max = 5000)
          List<@NotNull @Valid InventoryPublicationFindingInput> findings) {
    public InventoryPublicationPreflightRequest {
      findings = findings == null ? null : List.copyOf(findings);
    }
  }

  public record InventoryPublicationPlanSummary(
      @Min(0) int workLineCount,
      @Min(0) int materialLineCount,
      @Min(0) long grandTotalMinor) {}

  public record InventoryPublicationCandidate(
      @NotNull InventoryPublicationTargetKind targetKind,
      @NotNull UUID targetId,
      UUID estimateId,
      UUID repairId,
      @Min(0) long version,
      @NotBlank String state,
      boolean started,
      boolean active,
      @Min(1) @Max(5) Integer priority,
      String sourceParty,
      String planFingerprintSha256,
      @NotNull InventoryPublicationPlanSummary planSummary,
      boolean forceCapitalRepair) {
    public InventoryPublicationCandidate(
        InventoryPublicationTargetKind targetKind,
        UUID targetId,
        UUID estimateId,
        UUID repairId,
        long version,
        String state,
        boolean started,
        boolean active,
        Integer priority,
        String sourceParty,
        String planFingerprintSha256,
        InventoryPublicationPlanSummary planSummary) {
      this(
          targetKind,
          targetId,
          estimateId,
          repairId,
          version,
          state,
          started,
          active,
          priority,
          sourceParty,
          planFingerprintSha256,
          planSummary,
          false);
    }
  }

  public record InventoryPublicationPreflightFinding(
      @NotNull UUID findingId,
      @NotNull InventoryPublicationTargetKind targetKind,
      @NotNull List<InventoryPublicationCandidate> candidates) {}

  public record InventoryPublicationPreflightResponse(
      @NotNull UUID inventoryId,
      @Min(1) long finalPlanVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotNull List<InventoryPublicationPreflightFinding> findings) {}

  /**
   * Applies one immutable completed-inventory finding after asset-service has made that finding's
   * operational outcome authoritative.
   *
   * <p>{@code assetVersion} is the version frozen in the final inventory plan and remains source
   * evidence. {@code authoritativeAssetVersion} is the effective asset version returned by the
   * owning asset command and is the version attached to any newly materialized repair.
   */
  public record InventoryPublicationApplyRequest(
      @NotNull UUID warehouseId,
      @NotNull @Min(1) Long finalPlanVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotNull @Min(1) Long findingRevision,
      @NotNull UUID assetId,
      @NotNull @Min(0) Long assetVersion,
      @NotNull @Min(0) Long authoritativeAssetVersion,
      @NotNull OffsetDateTime inventoryCompletedAt,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String planFingerprintSha256,
      @NotNull @Min(1) @Max(5) Integer priority,
      @JsonProperty(required = true) boolean movementToRepair,
      @JsonProperty(required = true) LocalDate movementScheduledDate,
      @NotNull LocalDate repairScheduledDate,
      @NotNull @JsonSetter(nulls = Nulls.FAIL) JsonNode snapshot,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> media,
      @NotNull @Min(1) @Max(2) Integer snapshotSchemaVersion,
      @NotNull InventoryPublicationStrategy strategy,
      @JsonProperty(required = true) InventoryPublicationTargetKind selectedTargetKind,
      @JsonProperty(required = true) UUID selectedTargetId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public InventoryPublicationApplyRequest(
        UUID warehouseId,
        Long finalPlanVersion,
        String finalPlanSha256,
        Long findingRevision,
        UUID assetId,
        Long assetVersion,
        Long authoritativeAssetVersion,
        OffsetDateTime inventoryCompletedAt,
        String planFingerprintSha256,
        Integer priority,
        boolean movementToRepair,
        LocalDate movementScheduledDate,
        LocalDate repairScheduledDate,
        JsonNode snapshot,
        List<MediaReferenceInput> media,
        Integer snapshotSchemaVersion,
        InventoryPublicationStrategy strategy,
        InventoryPublicationTargetKind selectedTargetKind,
        UUID selectedTargetId) {
      this(
          warehouseId,
          finalPlanVersion,
          finalPlanSha256,
          findingRevision,
          assetId,
          assetVersion,
          authoritativeAssetVersion,
          inventoryCompletedAt,
          planFingerprintSha256,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          snapshot,
          media,
          snapshotSchemaVersion,
          strategy,
          selectedTargetKind,
          selectedTargetId,
          false);
    }

    public InventoryPublicationApplyRequest {
      media = media == null ? null : List.copyOf(media);
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    /** Ensures the post-command asset fence never predates the frozen inventory evidence. */
    @AssertTrue(message = "authoritativeAssetVersion must be greater than or equal to assetVersion")
    @JsonIgnore
    public boolean isAuthoritativeAssetVersionValid() {
      return assetVersion == null
          || authoritativeAssetVersion == null
          || authoritativeAssetVersion >= assetVersion;
    }

    /** Reconstructs the immutable finding evidence without replacing its observed asset version. */
    public InventoryPublicationFindingInput finding(UUID findingId) {
      return new InventoryPublicationFindingInput(
          findingId,
          findingRevision,
          assetId,
          assetVersion,
          planFingerprintSha256,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          snapshot,
          media,
          snapshotSchemaVersion,
          forceCapitalRepair);
    }
  }

  /**
   * Applies an authoritative FREE outcome without creating a maintenance estimate or repair.
   * Existing non-terminal maintenance work is superseded through the same durable workflow used
   * by a work-producing inventory finding.
   */
  public record InventoryNoWorkOutcomeRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID assetId,
      @NotNull OffsetDateTime inventoryCompletedAt,
      @NotNull @Min(1) Long finalPlanVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotNull @Min(1) Long findingRevision,
      @NotNull @Min(0) Long authoritativeAssetVersion,
      @NotBlank @Pattern(regexp = "^FREE$") String desiredStatus) {}

  /** Durable audit result of authoritative inventory cleanup that created no new work target. */
  public record InventoryNoWorkOutcomeResult(
      @NotNull UUID inventoryId,
      @NotNull UUID findingId,
      @NotNull UUID assetId,
      @NotNull List<UUID> supersededEstimateIds,
      @NotNull List<UUID> supersededRepairIds,
      @NotNull List<UUID> cancelledExternalTaskIds,
      @NotNull List<UUID> cancelledDriverTaskIds,
      @NotNull List<UUID> releasedLeaseIds,
      boolean replay) {
    public InventoryNoWorkOutcomeResult {
      supersededEstimateIds = sortedUnique(supersededEstimateIds);
      supersededRepairIds = sortedUnique(supersededRepairIds);
      cancelledExternalTaskIds = sortedUnique(cancelledExternalTaskIds);
      cancelledDriverTaskIds = sortedUnique(cancelledDriverTaskIds);
      releasedLeaseIds = sortedUnique(releasedLeaseIds);
    }

    private static List<UUID> sortedUnique(List<UUID> values) {
      if (values == null) return List.of();
      return values.stream().distinct().sorted().toList();
    }
  }

  /** Audit identity returned for both a new publication and an idempotent replay. */
  public record InventoryPublicationSourceReference(
      @NotNull UUID inventoryId,
      @Min(1) long finalPlanVersion,
      @NotNull UUID findingId,
      @Min(1) long findingRevision,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String planFingerprintSha256,
      @NotNull InventoryPublicationStrategy strategy,
      InventoryPublicationTargetKind selectedTargetKind,
      UUID selectedTargetId,
      InventoryPublicationTargetKind supersededTargetKind,
      UUID supersededTargetId) {}

  /**
   * One source-plan line's immutable successor decision. {@code sourceIndex} addresses the raw
   * frozen snapshot retained by {@link InventoryPublicationSourceReference}; quantities make a
   * partial semantic deduction inspectable without rewriting that source evidence.
   */
  public record InventoryPublicationDeltaLine(
      @Min(0) int sourceIndex,
      @NotNull InventoryPublicationDeltaDisposition disposition,
      @NotNull InventoryPlanLineType lineType,
      UUID catalogNodeId,
      @NotBlank String requestedQuantity,
      @NotBlank String retainedQuantity) {}

  /** Immutable, source-scoped explanation of the created successor or no-op match. */
  public record InventoryPublicationDelta(
      @NotNull List<@NotNull @Valid InventoryPublicationDeltaLine> lines) {
    public InventoryPublicationDelta {
      lines = lines == null ? null : List.copyOf(lines);
    }
  }

  /** Durable predecessor-to-successor lifecycle fact; null for CREATED and MATCHED outcomes. */
  public record InventoryPublicationSuccessorStatus(
      @NotNull UUID predecessorRepairId,
      @NotNull InventoryPublicationSuccessorState state,
      InventoryPublicationTerminalFact terminalFact,
      UUID terminalFactEventId,
      OffsetDateTime terminalFactOccurredAt,
      OffsetDateTime releasedAt) {}

  public record InventoryPublicationApplyResult(
      @NotNull InventoryPublicationSourceReference source,
      @NotNull InventoryPublicationOutcome outcome,
      InventoryPublicationTargetKind targetKind,
      UUID targetId,
      UUID estimateId,
      UUID repairId,
      InventoryPublicationSuccessorStatus successor,
      @NotNull @Valid InventoryPublicationDelta delta) {}

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
      String planFingerprintSha256,
      boolean forceCapitalRepair) {
    public InventoryRepairFact(
        UUID repairId,
        UUID rootRepairId,
        RepairOrigin origin,
        RepairKind kind,
        RepairExecutionState executionState,
        RepairAcceptanceState acceptanceState,
        String planFingerprintSha256) {
      this(
          repairId,
          rootRepairId,
          origin,
          kind,
          executionState,
          acceptanceState,
          planFingerprintSha256,
          false);
    }
  }
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
      UUID coverMediaId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public CreateDirectRepairRequest {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public CreateDirectRepairRequest(
        UUID warehouseId,
        UUID rentalItemId,
        LocalDate dispatchDate,
        String sourceParty,
        List<EstimateLineInput> lines,
        List<PlanStageInput> plan,
        List<MediaReferenceInput> mediaReferences,
        UUID coverMediaId) {
      this(
          warehouseId,
          rentalItemId,
          dispatchDate,
          sourceParty,
          lines,
          plan,
          mediaReferences,
          coverMediaId,
          false);
    }

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
          null,
          false);
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
          null,
          false);
    }
  }
  public record UpdateRepairPlanRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> stages,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences,
      UUID coverMediaId,
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean forceCapitalRepair) {
    public UpdateRepairPlanRequest {
      forceCapitalRepair = Boolean.TRUE.equals(forceCapitalRepair);
    }

    public UpdateRepairPlanRequest(
        Long expectedVersion,
        List<EstimateLineInput> lines,
        List<PlanStageInput> stages,
        List<MediaReferenceInput> mediaReferences,
        UUID coverMediaId) {
      this(
          expectedVersion,
          lines,
          stages,
          mediaReferences,
          coverMediaId,
          false);
    }

    public UpdateRepairPlanRequest(Long expectedVersion, List<PlanStageInput> stages) {
      this(expectedVersion, List.of(), stages, List.of(), null, false);
    }

    public UpdateRepairPlanRequest(
        Long expectedVersion,
        List<PlanStageInput> stages,
        List<MediaReferenceInput> mediaReferences) {
      this(expectedVersion, List.of(), stages, mediaReferences, null, false);
    }

    public UpdateRepairPlanRequest(
        Long expectedVersion,
        List<EstimateLineInput> lines,
        List<PlanStageInput> stages,
        List<MediaReferenceInput> mediaReferences) {
      this(expectedVersion, lines, stages, mediaReferences, null, false);
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
  public record PageResponse<T>(List<T> items, int page, int size, long totalElements) {}
}
