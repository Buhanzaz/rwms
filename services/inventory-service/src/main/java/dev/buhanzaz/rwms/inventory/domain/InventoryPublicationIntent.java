package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * JPA entity that persists inventory publication intent in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_publication_intent")
public class InventoryPublicationIntent {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Version
  @Column(name = "publication_revision", nullable = false)
  private long revision;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private PublicationState state;

  @Column(name = "maintenance_source_key", nullable = false, length = 128)
  private String maintenanceSourceKey;

  @Column(name = "final_plan_version")
  private Long finalPlanVersion;

  @Column(name = "final_plan_sha256", length = 64)
  private String finalPlanSha256;

  @Enumerated(EnumType.STRING)
  @Column(name = "target_kind", length = 16)
  private FinalPlanTargetKind targetKind;

  @Enumerated(EnumType.STRING)
  @Column(name = "desired_asset_status", nullable = false, length = 24)
  private InventoryAssetOutcomeStatus desiredAssetStatus;

  @Column(name = "target_id")
  private UUID targetId;

  @Column(name = "source_revision", nullable = false)
  private long sourceRevision;

  @Column(name = "outcome_reapplication_no", nullable = false)
  private long outcomeReapplicationNo;

  /** Frozen finding passport observation used by every retry and manual reapplication. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "asset_passport_observation", nullable = false, columnDefinition = "jsonb")
  private String assetPassportObservation;

  @Column(name = "request_sha256", length = 64)
  private String requestSha256;

  @Column(name = "current_precondition_sha256", length = 64)
  private String currentPreconditionSha256;

  @Column(name = "maintenance_repair_id")
  private UUID maintenanceRepairId;

  @Column(name = "maintenance_estimate_id")
  private UUID maintenanceEstimateId;

  @Column(name = "effective_asset_version")
  private Long effectiveAssetVersion;

  /** Canonical response from the asset-owned authoritative inventory command. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "asset_outcome_result", columnDefinition = "jsonb")
  private String assetOutcomeResult;

  /** Immutable maintenance reconciliation outcome for a final-plan publication. */
  @Enumerated(EnumType.STRING)
  @Column(name = "maintenance_outcome", length = 16)
  private MaintenancePublicationOutcome maintenanceOutcome;

  /** Canonical, immutable maintenance reconciliation response retained as audit evidence. */
  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "maintenance_result", columnDefinition = "jsonb")
  private String maintenanceResult;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "generation_attempt_count", nullable = false)
  private int generationAttemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "blocked_failure_code", length = 64)
  private String blockedFailureCode;

  @Column(name = "closed_reason", length = 2000)
  private String closedReason;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "closed_actor_ref", columnDefinition = "jsonb")
  private String closedActorRef;

  @Column(name = "closed_at")
  private OffsetDateTime closedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected InventoryPublicationIntent() {}

  public static InventoryPublicationIntent ready(
      UUID inventoryId, UUID findingId, long sourceRevision) {
    if (inventoryId == null || findingId == null || sourceRevision < 1) {
      throw new IllegalArgumentException("Publication source is invalid");
    }
    InventoryPublicationIntent value = new InventoryPublicationIntent();
    value.inventoryId = inventoryId;
    value.findingId = findingId;
    value.state = PublicationState.READY;
    value.maintenanceSourceKey = inventoryId + ":" + findingId;
    value.sourceRevision = sourceRevision;
    value.desiredAssetStatus = InventoryAssetOutcomeStatus.REPAIR;
    value.assetPassportObservation = absentPassportObservation();
    return value;
  }

  public static InventoryPublicationIntent notRequired(
      UUID inventoryId, UUID findingId, long sourceRevision) {
    InventoryPublicationIntent value = ready(inventoryId, findingId, sourceRevision);
    value.state = PublicationState.NOT_REQUIRED;
    return value;
  }

  public static InventoryPublicationIntent ready(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      FinalPlanTargetKind targetKind) {
    if (finalPlanVersion < 1 || finalPlanSha256 == null || !finalPlanSha256.matches("^[0-9a-f]{64}$")
        || targetKind == null) {
      throw new IllegalArgumentException("Final-plan publication source is invalid");
    }
    InventoryPublicationIntent value = ready(inventoryId, findingId, sourceRevision);
    value.finalPlanVersion = finalPlanVersion;
    value.finalPlanSha256 = finalPlanSha256;
    value.targetKind = targetKind;
    value.maintenanceSourceKey = inventoryId + ":" + finalPlanVersion + ":" + findingId;
    return value;
  }

  /** Creates one durable final-plan outcome, including asset-only FREE results. */
  public static InventoryPublicationIntent readyForOutcome(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      FinalPlanTargetKind targetKind,
      InventoryAssetOutcomeStatus desiredAssetStatus) {
    return readyForOutcome(
        inventoryId,
        findingId,
        sourceRevision,
        finalPlanVersion,
        finalPlanSha256,
        targetKind,
        desiredAssetStatus,
        0,
        absentPassportObservation());
  }

  /** Creates one durable final-plan outcome with its immutable inventory passport observation. */
  public static InventoryPublicationIntent readyForOutcome(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      FinalPlanTargetKind targetKind,
      InventoryAssetOutcomeStatus desiredAssetStatus,
      String assetPassportObservation) {
    return readyForOutcome(
        inventoryId,
        findingId,
        sourceRevision,
        finalPlanVersion,
        finalPlanSha256,
        targetKind,
        desiredAssetStatus,
        0,
        assetPassportObservation);
  }

  /** Creates one durable final-plan outcome in a shared manual reapplication generation. */
  public static InventoryPublicationIntent readyForOutcome(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      FinalPlanTargetKind targetKind,
      InventoryAssetOutcomeStatus desiredAssetStatus,
      long outcomeReapplicationNo) {
    return readyForOutcome(
        inventoryId,
        findingId,
        sourceRevision,
        finalPlanVersion,
        finalPlanSha256,
        targetKind,
        desiredAssetStatus,
        outcomeReapplicationNo,
        absentPassportObservation());
  }

  /** Creates one durable final-plan outcome in a shared generation with frozen passport truth. */
  public static InventoryPublicationIntent readyForOutcome(
      UUID inventoryId,
      UUID findingId,
      long sourceRevision,
      long finalPlanVersion,
      String finalPlanSha256,
      FinalPlanTargetKind targetKind,
      InventoryAssetOutcomeStatus desiredAssetStatus,
      long outcomeReapplicationNo,
      String assetPassportObservation) {
    requireOutcomeShape(targetKind, desiredAssetStatus);
    if (outcomeReapplicationNo < 0) {
      throw new IllegalArgumentException("Outcome reapplication number is invalid");
    }
    InventoryPublicationIntent value = ready(inventoryId, findingId, sourceRevision);
    if (finalPlanVersion < 1
        || finalPlanSha256 == null
        || !finalPlanSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Final-plan publication source is invalid");
    }
    value.finalPlanVersion = finalPlanVersion;
    value.finalPlanSha256 = finalPlanSha256;
    value.targetKind = targetKind;
    value.desiredAssetStatus = desiredAssetStatus;
    value.outcomeReapplicationNo = outcomeReapplicationNo;
    value.assetPassportObservation = requiredJsonObject(assetPassportObservation);
    value.maintenanceSourceKey = inventoryId + ":" + finalPlanVersion + ":" + findingId;
    return value;
  }

  /**
   * Reopens an outcome under an explicit history recovery command.
   *
   * <p>Prior attempts remain append-only evidence. Only derived delivery state is cleared so the
   * next attempt reasserts the completed inventory through every current downstream owner. A
   * formerly successful row is deliberately eligible because an older runtime may have completed
   * before later authoritative effects were introduced. Every invocation advances the durable
   * owner-effect generation exactly once; ordinary scheduler retries never call this transition.
   */
  public void requeueForAuthoritativeOutcome(
      long nextSourceRevision,
      long nextFinalPlanVersion,
      String nextFinalPlanSha256,
      FinalPlanTargetKind nextTargetKind,
      InventoryAssetOutcomeStatus nextDesiredAssetStatus) {
    if (nextSourceRevision < 1
        || nextFinalPlanVersion < 1
        || nextFinalPlanSha256 == null
        || !nextFinalPlanSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Final-plan publication source is invalid");
    }
    requireOutcomeShape(nextTargetKind, nextDesiredAssetStatus);
    state = PublicationState.READY;
    sourceRevision = nextSourceRevision;
    finalPlanVersion = nextFinalPlanVersion;
    finalPlanSha256 = nextFinalPlanSha256;
    maintenanceSourceKey = inventoryId + ":" + nextFinalPlanVersion + ":" + findingId;
    targetKind = nextTargetKind;
    desiredAssetStatus = nextDesiredAssetStatus;
    outcomeReapplicationNo = Math.addExact(outcomeReapplicationNo, 1);
    targetId = null;
    maintenanceRepairId = null;
    maintenanceEstimateId = null;
    maintenanceOutcome = null;
    maintenanceResult = null;
    effectiveAssetVersion = null;
    assetOutcomeResult = null;
    requestSha256 = null;
    currentPreconditionSha256 = null;
    generationAttemptCount = 0;
    nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC);
    blockedFailureCode = null;
    closedReason = null;
    closedActorRef = null;
    closedAt = null;
  }

  public void request(String requestHash, String preconditionHash) {
    if (state != PublicationState.READY && state != PublicationState.TRANSIENT_FAILED) {
      throw new IllegalStateException("Publication is not requestable");
    }
    requestSha256 = hash(requestHash);
    currentPreconditionSha256 = preconditionHash == null ? null : hash(preconditionHash);
    attemptCount = Math.addExact(attemptCount, 1);
    generationAttemptCount = Math.addExact(generationAttemptCount, 1);
    state = PublicationState.PENDING;
  }

  /** Records the asset-owner result before the optional maintenance effect is attempted. */
  public void recordAssetOutcome(
      long assetVersion, InventoryAssetOutcomeStatus appliedStatus, String canonicalResult) {
    requirePending();
    if (assetVersion < 0
        || appliedStatus != desiredAssetStatus
        || canonicalResult == null
        || canonicalResult.isBlank()
        || !canonicalResult.trim().startsWith("{")) {
      throw new IllegalArgumentException("Authoritative asset outcome is invalid");
    }
    effectiveAssetVersion = assetVersion;
    assetOutcomeResult = canonicalResult.trim();
  }

  /** Completes an asset-only FREE or inventory shipment RENTED outcome. */
  public void succeedAssetOnly() {
    requirePending();
    if ((desiredAssetStatus != InventoryAssetOutcomeStatus.FREE
            && desiredAssetStatus != InventoryAssetOutcomeStatus.RENTED)
        || targetKind != null
        || effectiveAssetVersion == null
        || assetOutcomeResult == null) {
      throw new IllegalStateException("Asset-only publication result is incomplete");
    }
    state = PublicationState.SUCCEEDED;
  }

  public void succeed(UUID repairId) {
    if (state != PublicationState.PENDING || repairId == null) {
      throw new IllegalStateException("Only pending publication may succeed");
    }
    targetKind = FinalPlanTargetKind.REPAIR;
    targetId = repairId;
    maintenanceRepairId = repairId;
    state = PublicationState.SUCCEEDED;
  }

  public void succeed(PublicationTarget target) {
    if (state != PublicationState.PENDING || target == null || target.outcome() == null) {
      throw new IllegalStateException("Only pending publication may succeed");
    }
    if (finalPlanVersion != null
        && (desiredAssetStatus == InventoryAssetOutcomeStatus.FREE
            || effectiveAssetVersion == null
            || assetOutcomeResult == null)) {
      throw new IllegalStateException("Authoritative asset outcome is incomplete");
    }
    if (target.maintenanceResult() == null || target.maintenanceResult().isBlank()) {
      throw new IllegalArgumentException("Maintenance publication result is required");
    }
    if (target.outcome() == MaintenancePublicationOutcome.MATCHED) {
      if (target.targetKind() != null
          || target.targetId() != null
          || target.estimateId() != null
          || target.repairId() != null) {
        throw new IllegalArgumentException("Matched maintenance publication cannot have a target");
      }
      targetKind = null;
      targetId = null;
      maintenanceEstimateId = null;
      maintenanceRepairId = null;
      maintenanceOutcome = target.outcome();
      maintenanceResult = target.maintenanceResult();
      state = PublicationState.SUCCEEDED;
      return;
    }
    if (target.targetId() == null
        || target.targetKind() == null
        || (target.outcome() != MaintenancePublicationOutcome.CREATED
            && target.outcome() != MaintenancePublicationOutcome.SUCCESSOR)
        || (target.outcome() == MaintenancePublicationOutcome.SUCCESSOR
            && target.targetKind() != FinalPlanTargetKind.REPAIR)) {
      throw new IllegalArgumentException("Maintenance publication target is invalid");
    }
    if (targetKind != null && targetKind != target.targetKind()) {
      throw new IllegalStateException("Maintenance returned the wrong final-plan target kind");
    }
    if ((target.targetKind() == FinalPlanTargetKind.ESTIMATE
            && (!target.targetId().equals(target.estimateId()) || target.repairId() != null))
        || (target.targetKind() == FinalPlanTargetKind.REPAIR
            && (!target.targetId().equals(target.repairId()) || target.estimateId() != null))) {
      throw new IllegalArgumentException("Maintenance publication target is invalid");
    }
    targetKind = target.targetKind();
    targetId = target.targetId();
    maintenanceEstimateId = target.estimateId();
    maintenanceRepairId = target.repairId();
    maintenanceOutcome = target.outcome();
    maintenanceResult = target.maintenanceResult();
    state = PublicationState.SUCCEEDED;
  }

  /** Defers a failed owner delivery until the persisted recovery deadline. */
  public void transientFailure(OffsetDateTime retryAt) {
    requirePending();
    nextAttemptAt = java.util.Objects.requireNonNull(retryAt, "Retry time is required");
    state = PublicationState.TRANSIENT_FAILED;
  }

  public void block(String failureCode) {
    requirePending();
    blockedFailureCode = required(failureCode, 64);
    state = PublicationState.BLOCKED;
  }

  public void reconcileRetry(String preconditionHash) {
    if (state != PublicationState.BLOCKED) {
      throw new IllegalStateException("Only blocked publication may reconcile");
    }
    currentPreconditionSha256 = hash(preconditionHash);
    blockedFailureCode = null;
    attemptCount = Math.addExact(attemptCount, 1);
    generationAttemptCount = 1;
    nextAttemptAt = OffsetDateTime.now(ZoneOffset.UTC);
    state = PublicationState.PENDING;
  }

  public void close(String reason, String actorRef) {
    if (state != PublicationState.BLOCKED) {
      throw new IllegalStateException("Only blocked publication may close");
    }
    closedReason = required(reason, 2000);
    closedActorRef = required(actorRef, 2000);
    closedAt = OffsetDateTime.now(ZoneOffset.UTC);
    state = PublicationState.CLOSED_BLOCKED;
  }

  private void requirePending() {
    if (state != PublicationState.PENDING) {
      throw new IllegalStateException("Publication is not pending");
    }
  }

  private static String hash(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Canonical SHA-256 is required");
    }
    return value;
  }

  private static String required(String value, int maximum) {
    if (value == null || value.isBlank()) throw new IllegalArgumentException("Text is required");
    String normalized = value.trim();
    if (normalized.length() > maximum) throw new IllegalArgumentException("Text is too long");
    return normalized;
  }

  private static String requiredJsonObject(String value) {
    String normalized = required(value, 16_384);
    if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
      throw new IllegalArgumentException("Frozen passport observation must be a JSON object");
    }
    return normalized;
  }

  private static String absentPassportObservation() {
    return "{\"presence\":\"ABSENT\",\"value\":null}";
  }

  private static void requireOutcomeShape(
      FinalPlanTargetKind targetKind, InventoryAssetOutcomeStatus desiredAssetStatus) {
    if (desiredAssetStatus == null
        || ((desiredAssetStatus == InventoryAssetOutcomeStatus.FREE
                || desiredAssetStatus == InventoryAssetOutcomeStatus.RENTED)
            && targetKind != null)
        || ((desiredAssetStatus == InventoryAssetOutcomeStatus.REPAIR
                || desiredAssetStatus == InventoryAssetOutcomeStatus.CAPITAL_REPAIR)
            && targetKind != FinalPlanTargetKind.REPAIR)) {
      throw new IllegalArgumentException("Inventory outcome target is invalid");
    }
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = current;
    updatedAt = current;
    if (nextAttemptAt == null) nextAttemptAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getRevision() {
    return revision;
  }

  public PublicationState getState() {
    return state;
  }

  public long getSourceRevision() {
    return sourceRevision;
  }

  public long getOutcomeReapplicationNo() {
    return outcomeReapplicationNo;
  }

  public String getAssetPassportObservation() {
    return assetPassportObservation;
  }

  public String getMaintenanceSourceKey() {
    return maintenanceSourceKey;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  /** Returns attempts consumed in the current explicit outcome-reapplication generation. */
  public int getGenerationAttemptCount() {
    return generationAttemptCount;
  }

  /** Returns the earliest instant at which automatic recovery may reclaim this intent. */
  public OffsetDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public UUID getMaintenanceRepairId() {
    return maintenanceRepairId;
  }

  public Long getFinalPlanVersion() {
    return finalPlanVersion;
  }

  public String getFinalPlanSha256() {
    return finalPlanSha256;
  }

  public FinalPlanTargetKind getTargetKind() {
    return targetKind;
  }

  public UUID getTargetId() {
    return targetId;
  }

  public UUID getMaintenanceEstimateId() {
    return maintenanceEstimateId;
  }

  public InventoryAssetOutcomeStatus getDesiredAssetStatus() {
    return desiredAssetStatus;
  }

  public Long getEffectiveAssetVersion() {
    return effectiveAssetVersion;
  }

  public String getAssetOutcomeResult() {
    return assetOutcomeResult;
  }

  /** Returns whether asset-service has durably confirmed the completed-inventory outcome. */
  public boolean hasAuthoritativeAssetOutcome() {
    return effectiveAssetVersion != null && assetOutcomeResult != null;
  }

  public MaintenancePublicationOutcome getMaintenanceOutcome() {
    return maintenanceOutcome;
  }

  public String getMaintenanceResult() {
    return maintenanceResult;
  }

  public String getBlockedFailureCode() {
    return blockedFailureCode;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public String getCurrentPreconditionSha256() {
    return currentPreconditionSha256;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }

  public record PublicationTarget(
      MaintenancePublicationOutcome outcome,
      FinalPlanTargetKind targetKind,
      UUID targetId,
      UUID estimateId,
      UUID repairId,
      String maintenanceResult) {
    public PublicationTarget(
        FinalPlanTargetKind targetKind, UUID targetId, UUID estimateId, UUID repairId) {
      this(null, targetKind, targetId, estimateId, repairId, null);
    }
  }
}
