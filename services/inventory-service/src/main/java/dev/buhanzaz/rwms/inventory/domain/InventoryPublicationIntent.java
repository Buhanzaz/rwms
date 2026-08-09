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

  @Column(name = "target_id")
  private UUID targetId;

  @Column(name = "source_revision", nullable = false)
  private long sourceRevision;

  @Column(name = "request_sha256", length = 64)
  private String requestSha256;

  @Column(name = "current_precondition_sha256", length = 64)
  private String currentPreconditionSha256;

  @Column(name = "maintenance_repair_id")
  private UUID maintenanceRepairId;

  @Column(name = "maintenance_estimate_id")
  private UUID maintenanceEstimateId;

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

  public void request(String requestHash, String preconditionHash) {
    if (state != PublicationState.READY && state != PublicationState.TRANSIENT_FAILED) {
      throw new IllegalStateException("Publication is not requestable");
    }
    requestSha256 = hash(requestHash);
    currentPreconditionSha256 = preconditionHash == null ? null : hash(preconditionHash);
    attemptCount = Math.addExact(attemptCount, 1);
    state = PublicationState.PENDING;
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

  public void transientFailure() {
    requirePending();
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

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    createdAt = current;
    updatedAt = current;
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

  public String getMaintenanceSourceKey() {
    return maintenanceSourceKey;
  }

  public int getAttemptCount() {
    return attemptCount;
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
