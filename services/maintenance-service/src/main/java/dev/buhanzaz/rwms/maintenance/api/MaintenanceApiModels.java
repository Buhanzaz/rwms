package dev.buhanzaz.rwms.maintenance.api;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.annotation.JsonSetter;
import com.fasterxml.jackson.annotation.Nulls;
import dev.buhanzaz.rwms.maintenance.domain.CatalogVersionState;
import dev.buhanzaz.rwms.maintenance.domain.EstimateState;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
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
  public enum DeliveryState { PENDING, RETRY_PENDING, DELIVERED, QUARANTINED }
  public enum LeaseReconciliationState {
    NOT_ACQUIRED, ACTIVE, RELEASED, RECONCILIATION_REQUIRED
  }
  public enum GenerationState { PENDING_GENERATION, GENERATED, FAILED }

  public record ActorSnapshot(String actorId, ActorType actorType) {}
  public record MediaReferenceInput(@NotNull UUID mediaId, @NotNull @Min(0) Long generation) {}
  public record RoutingSnapshot(
      @NotNull UUID queueId,
      @NotBlank @Size(max = 64) String queueCode,
      @NotBlank @Size(max = 64) String queueKind) {}
  public record OpaqueCatalogReference(
      @NotBlank @Size(max = 128) String referenceId,
      @NotBlank @Size(max = 64) String code) {}

  public record CatalogNodeInput(
      @NotNull UUID id,
      @NotBlank @Pattern(regexp = "^[A-Z0-9][A-Z0-9_-]{0,63}$") String code,
      @NotNull CatalogNodeType nodeType,
      @NotBlank @Size(max = 255) String name,
      @NotNull @JsonProperty(required = true) @JsonSetter(nulls = Nulls.FAIL) Boolean active,
      UUID parentNodeId,
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
      @JsonProperty(defaultValue = "false")
          @JsonDeserialize(using = DefaultFalseBooleanDeserializer.class)
          Boolean photoRequired,
      @JsonProperty(required = true) @Valid RoutingSnapshot routing,
      @NotNull @Size(max = 100) List<@Valid OpaqueCatalogReference> references,
      @Size(max = 2000) String comment,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {
    public CatalogNodeInput {
      if (active == null) throw new IllegalArgumentException("active is required");
      includeInEstimate = includeInEstimate == null ? Boolean.TRUE : includeInEstimate;
      commonItem = commonItem == null ? Boolean.FALSE : commonItem;
      showInMainMenu = showInMainMenu == null ? Boolean.FALSE : showInMainMenu;
      photoRequired = photoRequired == null ? Boolean.FALSE : photoRequired;
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
      @Min(0) int sortOrder) {}
  public record ImportCatalogRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String sourceSha256,
      @NotNull @Size(max = 10000) List<@Valid CatalogNodeInput> nodes,
      @NotNull @Size(max = 20000) List<@Valid CatalogLinkInput> links) {}
  public record ReplaceCatalogNodesRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 10000) List<@Valid CatalogNodeInput> nodes) {}
  public record ReplaceCatalogLinksRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 20000) List<@Valid CatalogLinkInput> links) {}
  public record ChangeCatalogRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull List<@Valid CatalogNodeInput> nodes,
      @NotNull List<@Valid CatalogLinkInput> links) {}
  public record VersionCommand(@NotNull @Min(0) Long expectedVersion) {}
  public record CompleteEstimateRequest(@NotNull @Min(0) Long expectedVersion) {}

  public record CatalogCounts(int nodes, int links) {}
  public record CatalogValidationReport(
      boolean valid, int errorCount, int warningCount, String reportSha256) {}
  public record CatalogVersionResponse(
      UUID id,
      UUID warehouseId,
      long version,
      CatalogVersionState lifecycle,
      String sourceSha256,
      CatalogCounts counts,
      CatalogValidationReport validation,
      OffsetDateTime createdAt,
      OffsetDateTime activatedAt) {}
  public record CatalogNodeResponse(
      UUID id,
      UUID catalogVersionId,
      String code,
      CatalogNodeType nodeType,
      String name,
      boolean active,
      UUID parentNodeId,
      String unit,
      String unitPrice,
      int durationMinutes,
      boolean includeInEstimate,
      boolean commonItem,
      boolean showInMainMenu,
      boolean photoRequired,
      RoutingSnapshot routing,
      List<OpaqueCatalogReference> references,
      String comment,
      List<MediaReferenceInput> mediaReferences) {}
  public record CatalogLinkResponse(
      UUID id,
      UUID catalogVersionId,
      UUID fromNodeId,
      UUID toNodeId,
      CatalogLinkType linkType,
      int sortOrder) {}

  public record CatalogNodeSnapshot(
      @NotNull UUID catalogVersionId,
      @NotNull UUID nodeId,
      @NotBlank @Size(max = 64) String code,
      @NotNull CatalogNodeType nodeType,
      @NotBlank @Size(max = 255) String name,
      @JsonProperty(required = true) @Size(max = 32) String unit,
      @JsonProperty(required = true)
          @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{2})$") String unitPrice,
      @NotNull @Min(0) Integer durationMinutes,
      @JsonProperty(required = true) @Valid RoutingSnapshot routing) {}
  public record EstimateLineInput(
      @NotNull UUID id,
      @JsonProperty(required = true) @Valid CatalogNodeSnapshot catalogSnapshot,
      @NotBlank @Size(max = 1000) String description,
      @NotBlank @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$") String quantity,
      @NotBlank @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{2})$") String unitPrice,
      @Size(max = 2000) String comment,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record PlanStageInput(
      @NotNull UUID id,
      @NotNull RepairStageKind kind,
      @NotNull @Min(0) Integer order,
      @NotNull @Valid RoutingSnapshot routing,
      OffsetDateTime taskDeadline) {}
  public record CreateEstimateRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull LocalDate dispatchDate,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record UpdateEstimateRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull LocalDate dispatchDate,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record AmendEstimateRequest(
      @NotNull @Min(0) Long expectedVersion,
      @JsonProperty(required = true) @Min(0) Long expectedLinkedRepairVersion,
      @NotNull LocalDate dispatchDate,
      @NotBlank @Size(max = 2000) String reason,
      @Size(max = 512) String sourceParty,
      @NotNull @Size(max = 2000) List<@Valid EstimateLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}

  public record EstimateLineResponse(
      UUID id,
      CatalogNodeSnapshot catalogSnapshot,
      String description,
      String quantity,
      String unitPrice,
      String lineTotal,
      String comment,
      List<MediaReferenceInput> mediaReferences) {}
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
      OffsetDateTime createdAt,
      OffsetDateTime completedAt,
      ActorSnapshot actor) {}

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
  public record RepairStageResponse(
      UUID id,
      RepairStageKind kind,
      int order,
      RepairStageState state,
      RoutingSnapshot routing,
      OffsetDateTime taskDeadline,
      TaskSyncSnapshot taskSync,
      OffsetDateTime completedAt) {}
  public record RepairPlanResponse(
      UUID repairId, long repairVersion, List<RepairStageResponse> stages) {}
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
      String sourceParty,
      RepairPlanResponse plan,
      LeaseSnapshot lease,
      List<MediaReferenceInput> mediaReferences,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt,
      ActorSnapshot actor) {}

  public record CreateDirectRepairRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull LocalDate dispatchDate,
      @JsonProperty(required = true) @Size(max = 512) String sourceParty,
      @NotEmpty @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record UpdateRepairPlanRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotEmpty @Size(max = 1000) List<@Valid PlanStageInput> stages) {}
  public record CreateReworkRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 2000) String reason,
      @NotEmpty @Size(max = 1000) List<@Valid PlanStageInput> plan,
      @NotNull @Size(max = 100) List<@Valid MediaReferenceInput> mediaReferences) {}
  public record RepairDecisionRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 2000) String comment) {}
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
