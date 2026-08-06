package dev.buhanzaz.rwms.inventory.api;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonProperty;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanReconciliationStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanScheduleMode;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanState;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovementType;
import dev.buhanzaz.rwms.inventory.domain.InventoryReviewStage;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.MaintenancePublicationOutcome;
import dev.buhanzaz.rwms.inventory.domain.MutationState;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

public final class InventoryApiModels {
  private InventoryApiModels() {}

  public record StartSessionRequest(@NotNull UUID warehouseId) {}

  public record ResolveNumberRequest(
      @Min(0) long expectedSessionRevision, @NotBlank @Size(max = 128) String submittedNumber) {}

  public record CreateFindingAssetRequest(
      @Min(0) long expectedSessionRevision,
      @Min(0) long expectedFindingRevision,
      @Min(1) long sourceRevision,
      @NotNull FindingOrigin origin,
      @NotBlank @Size(max = 128) String displayCanonicalNumber,
      @NotNull JsonNode safePassport) {}

  public record Observation(@NotNull ObservationPresence presence, JsonNode value) {}

  public record MediaReference(@NotNull UUID mediaId, @Min(0) long generation) {}

  public record PlanLineInput(
      @NotBlank @Pattern(regexp = "^(CATALOG|MANUAL)$") String aggregationKind,
      UUID catalogNodeId,
      @JsonProperty(required = true) UUID routingCatalogNodeId,
      @Size(max = 1000) String description,
      @Pattern(regexp = "^(WORK|MATERIAL)$") String type,
      @Size(max = 32) String unit,
      @NotBlank
          @Pattern(regexp = "^(?:0|[1-9][0-9]{0,13})(?:\\.[0-9]{1,3})?$")
          String quantity,
      @Min(0) Long unitPriceMinor,
      @Pattern(regexp = "^(?:0|[1-9][0-9]*)(?:\\.[0-9]{1,3})?$")
          String normativeMinutes,
      @Size(max = 2000) String groupComment,
      @NotNull @Size(max = 100) List<@Valid MediaReference> mediaReferences) {}

  public record PlanStageSelection(
      @NotNull UUID catalogNodeId,
      @NotBlank @Pattern(regexp = "^REPAIR_WORK$") String kind,
      @Min(0) int order) {}

  public record PlanSelection(
      @NotBlank @Pattern(regexp = "^(AUTO|MANUAL)$") String mode,
      @NotNull @Min(1) @Max(5) Integer priority,
      UUID coverMediaId,
      @JsonProperty(required = true) boolean movementToRepair,
      @JsonProperty(required = true) LogisticsPlanningMode logisticsPlanningMode,
      @JsonProperty(required = true) LocalDate logisticsScheduledDate,
      @NotNull @Size(min = 1, max = 2000) List<@Valid PlanLineInput> lines,
      @NotNull @Size(max = 1000) List<@Valid PlanStageSelection> stages) {
    @AssertTrue(
        message =
            "inbound logistics planning must be present only when movementToRepair is selected")
    @JsonIgnore
    public boolean isLogisticsPlanningValid() {
      return LogisticsPlanningMode.validInboundPlanning(
          movementToRepair, logisticsPlanningMode, logisticsScheduledDate);
    }
  }

  public record SaveInspectionRequest(
      @Min(0) long expectedSessionRevision,
      @Min(0) long expectedFindingRevision,
      @NotNull InspectionState inspection,
      @NotNull @Size(max = 2000) String comment,
      @NotNull @Valid Observation passportObservation,
      @NotNull @Valid Observation equipmentObservation,
      @NotNull @Size(max = 100) List<@Valid MediaReference> media,
      UUID coverMediaId,
      @Valid PlanSelection planSelection) {
    public SaveInspectionRequest(
        long expectedSessionRevision,
        long expectedFindingRevision,
        InspectionState inspection,
        Observation passportObservation,
        Observation equipmentObservation,
        List<MediaReference> media,
        PlanSelection planSelection) {
      this(
          expectedSessionRevision,
          expectedFindingRevision,
          inspection,
          "",
          passportObservation,
          equipmentObservation,
          media,
          null,
          planSelection);
    }
  }

  public record ResolveConflictRequest(
      @Min(0) long expectedSessionRevision,
      @Min(0) long expectedFindingRevision,
      @NotNull ConflictResolutionStrategy strategy,
      @Size(max = 2000) String reason) {}

  public record RevisionExpectation(
      @NotNull UUID findingId, @Min(0) long expectedFindingRevision) {}

  public record RegistryReviewRequest(
      @Min(0) long expectedSessionRevision,
      @NotNull @Size(max = 10000) List<@Valid RevisionExpectation> findingRevisions) {}

  public record StartFurnitureReviewRequest(
      @Min(0) long expectedSessionRevision,
      @NotNull @Size(max = 10000) List<@Valid RevisionExpectation> findingRevisions,
      boolean acknowledgeIncomplete) {}

  public record FurnitureReviewCabinInput(
      @NotNull UUID findingId,
      @Min(0) long expectedFindingRevision,
      @Min(0) long observedQuantity) {}

  public record FurnitureReviewItemInput(
      @NotNull UUID equipmentId,
      @Min(0) long catalogVersion,
      @Min(0) long observedStockQuantity,
      @NotNull @Size(max = 10000) List<@Valid FurnitureReviewCabinInput> cabins) {}

  public record SaveFurnitureReviewRequest(
      @Min(0) long expectedSessionRevision,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String assetSnapshotSha256,
      @NotNull @Size(max = 10000) List<@Valid FurnitureReviewItemInput> items) {}

  public record PlanningSettingsUpdateRequest(
      @Min(0) long expectedSettingsRevision,
      @Min(1) @Max(1000) int movementDailyCapacity,
      @Min(1) @Max(1000) int repairDailyCapacity,
      @NotNull @Size(min = 1, max = 7) List<@NotBlank String> workingWeekdays,
      @NotNull @Size(max = 3660) List<@NotNull LocalDate> holidays) {}

  public record PlanningSettingsView(
      UUID warehouseId,
      long settingsRevision,
      int movementDailyCapacity,
      int repairDailyCapacity,
      List<String> workingWeekdays,
      List<LocalDate> holidays) {}

  public record PrepareFinalPlanRequest(
      @Min(0) long expectedSessionRevision,
      @Min(0) long expectedSettingsRevision,
      @NotNull FinalPlanScheduleMode movementScheduleMode,
      @NotNull FinalPlanScheduleMode repairScheduleMode) {}

  public record FinalPlanReconciliationDecision(
      @NotNull FinalPlanReconciliationStrategy strategy,
      FinalPlanTargetKind selectedTargetKind,
      UUID selectedTargetId) {}

  public record FinalPlanEntryUpdate(
      @NotNull UUID findingId,
      @Min(0) long expectedFindingRevision,
      @Min(0) int order,
      Integer priority,
      @NotNull Boolean movementToRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      @Valid FinalPlanReconciliationDecision reconciliationDecision) {}

  public record FinalPlanUpdateRequest(
      @Min(0) long expectedSessionRevision,
      @Min(1) long expectedFinalPlanVersion,
      @NotNull FinalPlanScheduleMode movementScheduleMode,
      @NotNull FinalPlanScheduleMode repairScheduleMode,
      @NotNull @Size(max = 10000) List<@Valid FinalPlanEntryUpdate> entries) {}

  public record CompletionPreviewRequest(
      @Min(0) long expectedSessionRevision,
      @Min(1) long finalPlanVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotNull List<@Valid RevisionExpectation> findingRevisions) {
    public CompletionPreviewRequest(
        long expectedSessionRevision, List<RevisionExpectation> findingRevisions) {
      this(expectedSessionRevision, 0, null, findingRevisions);
    }
  }

  public record CompleteSessionRequest(
      @Min(0) long expectedSessionRevision,
      @Min(1) long finalPlanVersion,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String finalPlanSha256,
      @NotNull List<@Valid RevisionExpectation> findingRevisions,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String acknowledgementSha256,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String validationSha256) {
    public CompleteSessionRequest(
        long expectedSessionRevision,
        List<RevisionExpectation> findingRevisions,
        String acknowledgementSha256,
        String validationSha256) {
      this(
          expectedSessionRevision,
          0,
          null,
          findingRevisions,
          acknowledgementSha256,
          validationSha256);
    }
  }

  public record CancelSessionRequest(
      @Min(0) long expectedSessionRevision, @NotBlank @Size(max = 2000) String reason) {}

  public record PublicationExpectation(
      @NotNull UUID findingId, @Min(0) long expectedPublicationRevision) {}

  public record PublishFindingsRequest(
      @Min(0) long expectedSessionRevision,
      boolean allEligible,
      @NotNull List<@Valid PublicationExpectation> findings) {}

  public record RetryPublicationRequest(
      @Min(0) long expectedPublicationRevision,
      @Size(max = 2000) String reconcileReason,
      @Pattern(regexp = "^[0-9a-f]{64}$") String currentPreconditionSha256) {}

  public record ClosePublicationRequest(
      @Min(0) long expectedPublicationRevision, @NotBlank @Size(max = 2000) String reason) {}

  public record PageMetadata(
      @Min(0) int page,
      @Min(1) @Max(200) int size,
      @Min(0) long totalElements,
      @Min(0) int totalPages) {}

  public record PageResponse<T>(List<T> content, PageMetadata page) {}

  public record SessionView(
      UUID id,
      long sessionRevision,
      UUID warehouseId,
      long warehouseVersion,
      String warehouseTimeZone,
      InventoryActorView author,
      LocalDate businessDate,
      SessionLifecycle lifecycle,
      InventoryReviewStage reviewStage,
      FurnitureReconciliationState furnitureReconciliationState,
      int expectedCount,
      long findingCount,
      long inspectedCount,
      OffsetDateTime startedAt,
      OffsetDateTime terminalAt,
      String publicationState,
      List<MembershipMovementView> membershipMovements,
      FrozenStatistics statistics,
      CancellationAudit cancellation) {}

  public record SessionSummary(
      UUID id,
      long sessionRevision,
      UUID warehouseId,
      long warehouseVersion,
      String warehouseTimeZone,
      InventoryActorView author,
      LocalDate businessDate,
      SessionLifecycle lifecycle,
      InventoryReviewStage reviewStage,
      FurnitureReconciliationState furnitureReconciliationState,
      int expectedCount,
      long findingCount,
      long inspectedCount,
      OffsetDateTime startedAt,
      OffsetDateTime terminalAt,
      String publicationState) {}

  public record InventoryActorView(UUID id, String displayName) {}

  public record CancellationAudit(String reason, OffsetDateTime cancelledAt) {}

  public record MembershipMovementView(
      UUID id,
      InventoryMembershipMovementType type,
      UUID assetId,
      String displayCanonicalNumber,
      FindingOrigin origin,
      UUID fromWarehouseId,
      UUID toWarehouseId,
      String status,
      String tenantSnapshot,
      OffsetDateTime occurredAt) {}

  public record FindingView(
      UUID id,
      UUID inventoryId,
      long findingRevision,
      FindingOrigin origin,
      InspectionState inspection,
      String inspectionSource,
      ReconciliationState reconciliation,
      UUID assetId,
      Long assetVersion,
      String displayCanonicalNumber,
      String identityMatchKey,
      Observation passportObservation,
      Observation equipmentObservation,
      MutationState mutationState,
      String planFingerprintSha256,
      String comment,
      ExpectedItemSnapshot expectedSnapshot,
      CurrentItemSnapshot inspectionBaseline,
      CurrentItemSnapshot currentSnapshot,
      List<ConflictView> conflicts,
      ConflictResolutionView conflictResolution,
      FrozenPlanView frozenPlan,
      UUID coverMediaId,
      List<MediaReference> media,
      PublicationView publication) {}

  public record ExpectedItemSnapshot(
      UUID assetId,
      long assetVersion,
      UUID warehouseId,
      String status,
      String displayCanonicalNumber,
      String tenantSnapshot,
      JsonNode passportSnapshot,
      JsonNode contentsSnapshot) {}

  public record CurrentItemSnapshot(
      UUID assetId,
      long assetVersion,
      UUID warehouseId,
      String status,
      String displayCanonicalNumber,
      String tenantSnapshot,
      JsonNode passportSnapshot,
      JsonNode contentsSnapshot,
      JsonNode repairsSnapshot) {}

  public record ConflictView(String code, String message, String expected, String actual) {}

  public record ConflictResolutionView(
      ConflictResolutionStrategy strategy, String reason, OffsetDateTime resolvedAt) {}

  public record FrozenPlanView(
      String mode,
      UUID catalogVersionId,
      String fingerprintSha256,
      int priority,
      UUID coverMediaId,
      boolean movementToRepair,
      LogisticsPlanningMode logisticsPlanningMode,
      LocalDate logisticsScheduledDate,
      List<FrozenPlanLineView> lines,
      List<FrozenPlanStageView> stages) {}

  public record FrozenPlanLineView(
      UUID id,
      String sourceKind,
      String lineType,
      UUID catalogVersionId,
      UUID catalogNodeId,
      String description,
      String normalizedDescription,
      String unit,
      String quantity,
      long unitPriceMinor,
      String normativeMinutes,
      String groupComment,
      List<MediaReference> mediaReferences) {}

  public record FrozenPlanStageView(
      UUID id,
      int order,
      UUID catalogNodeId,
      String catalogNodeName,
      String kind,
      UUID routingQueueId,
      String routingQueueName,
      String routingQueueType,
      boolean photoRequired,
      int normativeDurationMinutes) {}

  public record NumberResolutionView(
      String displayCanonicalNumber,
      String identityMatchKey,
      String outcome,
      FindingView finding) {}

  public record FurnitureReviewView(
      UUID inventoryId,
      long sessionRevision,
      InventoryReviewStage stage,
      String assetSnapshotSha256,
      String reviewSha256,
      boolean confirmed,
      List<FurnitureReviewItemView> items) {}

  public record FurnitureReviewItemView(
      UUID equipmentId,
      long catalogVersion,
      String equipmentName,
      long currentStockQuantity,
      long observedStockQuantity,
      List<FurnitureReviewCabinView> cabins) {}

  public record FurnitureReviewCabinView(
      UUID findingId,
      UUID assetId,
      String cabinNumber,
      String status,
      long currentQuantity,
      long observedQuantity) {}

  public record FinalPlanCandidateView(
      FinalPlanTargetKind targetKind,
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
      FinalPlanSummaryView planSummary) {}

  public record FinalPlanSummaryView(
      int workLineCount, int materialLineCount, long grandTotalMinor) {}

  public record FinalPlanEntryView(
      UUID findingId,
      long findingRevision,
      String planFingerprintSha256,
      boolean hasWork,
      FinalPlanTargetKind targetKind,
      int order,
      Integer priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      List<FinalPlanCandidateView> collisionCandidates,
      FinalPlanReconciliationDecision reconciliationDecision) {}

  public record FinalPlanView(
      UUID inventoryId,
      long sessionRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      long planningSettingsRevision,
      FinalPlanState state,
      FinalPlanScheduleMode movementScheduleMode,
      FinalPlanScheduleMode repairScheduleMode,
      List<FinalPlanEntryView> entries) {}

  public record FrozenStatistics(
      int expectedCount,
      int inspectedCount,
      int missingCount,
      int readyCount,
      int withWorkCount,
      int addedCount,
      int unexpectedExistingCount,
      int conflictCount,
      int workLineCount,
      int materialLineCount,
      long workTotalMinor,
      long materialTotalMinor,
      long grandTotalMinor,
      int roundingAdjustmentMinor,
      String normativeMinutes,
      long durationSeconds,
      List<StatisticsLine> aggregateLines) {}

  public record StatisticsLine(
      String aggregationKind,
      UUID catalogVersionId,
      UUID catalogNodeId,
      String normalizedDescription,
      String type,
      String unit,
      long unitPriceMinor,
      String quantity,
      long rowTotalMinor) {}

  public record CompletionRisk(UUID findingId, String code) {}

  public record ValidatedFinding(
      UUID findingId, CurrentItemSnapshot currentSnapshot, List<ConflictView> conflicts) {}

  public record RegistryReviewView(
      UUID inventoryId,
      long sessionRevision,
      List<RevisionExpectation> findingRevisions,
      OffsetDateTime validatedAt,
      List<ValidatedFinding> validatedFindings) {}

  public record CompletionPreview(
      UUID inventoryId,
      long sessionRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      List<RevisionExpectation> findingRevisions,
      String validationSha256,
      OffsetDateTime validatedAt,
      String acknowledgementSha256,
      FrozenStatistics statistics,
      List<CompletionRisk> risks,
      List<ValidatedFinding> validatedFindings) {}

  public record PublicationView(
      UUID id,
      UUID inventoryId,
      UUID findingId,
      long publicationRevision,
      PublicationState state,
      long sourceRevision,
      int attemptCount,
      Long finalPlanVersion,
      FinalPlanTargetKind targetKind,
      UUID targetId,
      UUID maintenanceEstimateId,
      UUID maintenanceRepairId,
      MaintenancePublicationOutcome maintenanceOutcome,
      JsonNode maintenanceResult,
      String failureCode) {
    public PublicationView(
        UUID id,
        UUID inventoryId,
        UUID findingId,
        long publicationRevision,
        PublicationState state,
        long sourceRevision,
        int attemptCount,
        UUID maintenanceRepairId,
        String failureCode) {
      this(
          id,
          inventoryId,
          findingId,
          publicationRevision,
          state,
          sourceRevision,
          attemptCount,
          null,
          maintenanceRepairId == null ? null : FinalPlanTargetKind.REPAIR,
          maintenanceRepairId,
          null,
          maintenanceRepairId,
          null,
          null,
          failureCode);
    }
  }

  public record PublicationBatch(
      UUID inventoryId, String aggregateState, List<PublicationView> intents) {}

  public record SessionStatistics(
      UUID inventoryId,
      UUID warehouseId,
      LocalDate businessDate,
      OffsetDateTime startedAt,
      OffsetDateTime completedAt,
      FrozenStatistics statistics) {}

  public record StatisticsSummary(long sessionCount, FrozenStatistics statistics) {}
}
