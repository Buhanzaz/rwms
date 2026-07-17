package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import java.time.OffsetDateTime;
import java.util.Set;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Durable local work item for post-commit asset and task-board effects. */
@Entity
@Table(
    name = "integration_reconciliation",
    uniqueConstraints =
        @UniqueConstraint(
            name = "uk_integration_reconciliation_key",
            columnNames = {"dependency_type", "operation_type", "idempotency_key"}))
public class MaintenanceReconciliation {
  private static final OffsetDateTime REVIEW_REQUIRED_NEXT_ATTEMPT =
      OffsetDateTime.parse("9999-12-31T23:59:59Z");
  private static final Set<String> DEPENDENCIES = Set.of("ASSET", "TASK_BOARD");
  private static final Set<String> CLAIMABLE_STATES =
      Set.of("PENDING", "RETRY_PENDING", "RECONCILIATION_REQUIRED");

  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "repair_id")
  private UUID repairId;

  @Column(name = "dependency_type", nullable = false, length = 32)
  private String dependencyType;

  @Column(name = "operation_type", nullable = false, length = 64)
  private String operationType;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "state", nullable = false, length = 32)
  private String state;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "last_error_code", length = 64)
  private String lastErrorCode;

  @Column(name = "response_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String responseSnapshot;

  @Column(name = "review_version", nullable = false)
  private long reviewVersion;

  @Column(name = "review_subject_id")
  private UUID reviewSubjectId;

  @Column(name = "review_reason", length = 2000)
  private String reviewReason;

  @Column(name = "reviewed_at")
  private OffsetDateTime reviewedAt;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected MaintenanceReconciliation() {}

  public static MaintenanceReconciliation pending(
      UUID repairId,
      String dependencyType,
      String operationType,
      UUID idempotencyKey,
      String responseSnapshot,
      OffsetDateTime now) {
    return create(
        repairId,
        dependencyType,
        operationType,
        idempotencyKey,
        "PENDING",
        responseSnapshot,
        now,
        now);
  }

  public static MaintenanceReconciliation reconciliationRequired(
      UUID repairId,
      String dependencyType,
      String operationType,
      UUID idempotencyKey,
      String responseSnapshot,
      OffsetDateTime now) {
    return create(
        repairId,
        dependencyType,
        operationType,
        idempotencyKey,
        "RECONCILIATION_REQUIRED",
        responseSnapshot,
        now,
        REVIEW_REQUIRED_NEXT_ATTEMPT);
  }

  private static MaintenanceReconciliation create(
      UUID repairId,
      String dependencyType,
      String operationType,
      UUID idempotencyKey,
      String state,
      String responseSnapshot,
      OffsetDateTime now,
      OffsetDateTime nextAttemptAt) {
    if (!DEPENDENCIES.contains(dependencyType)
        || operationType == null
        || operationType.isBlank()
        || operationType.length() > 64
        || idempotencyKey == null
        || responseSnapshot == null
        || now == null
        || nextAttemptAt == null) {
      throw new IllegalArgumentException("Reconciliation identity is required");
    }
    MaintenanceReconciliation value = new MaintenanceReconciliation();
    value.id = UUID.randomUUID();
    value.repairId = repairId;
    value.dependencyType = dependencyType;
    value.operationType = operationType;
    value.idempotencyKey = idempotencyKey;
    value.state = state;
    value.attemptCount = 0;
    value.nextAttemptAt = nextAttemptAt;
    value.responseSnapshot = responseSnapshot;
    value.reviewVersion = 0;
    value.createdAt = now;
    value.updatedAt = now;
    return value;
  }

  public void confirm(int expectedAttemptCount, String responseSnapshot, OffsetDateTime now) {
    requireClaim(expectedAttemptCount);
    if (responseSnapshot == null || now == null) {
      throw new IllegalArgumentException("Confirmed reconciliation response is required");
    }
    state = "CONFIRMED";
    attemptCount = Math.addExact(attemptCount, 1);
    this.responseSnapshot = responseSnapshot;
    lastErrorCode = null;
    updatedAt = now;
  }

  public boolean fail(
      int expectedAttemptCount,
      int maximumAttempts,
      String failureCode,
      OffsetDateTime now) {
    requireClaim(expectedAttemptCount);
    if (maximumAttempts < 1
        || failureCode == null
        || failureCode.isBlank()
        || failureCode.length() > 64
        || now == null) {
      throw new IllegalArgumentException("Reconciliation failure is invalid");
    }
    attemptCount = Math.addExact(attemptCount, 1);
    boolean quarantined = attemptCount >= maximumAttempts;
    state = quarantined ? "QUARANTINED" : "RETRY_PENDING";
    int backoffSeconds = 1 << Math.min(expectedAttemptCount, 2);
    nextAttemptAt = now.plusSeconds(backoffSeconds);
    lastErrorCode = failureCode;
    updatedAt = now;
    return quarantined;
  }

  public void resume(
      long expectedReviewVersion,
      UUID reviewSubjectId,
      String reviewReason,
      OffsetDateTime now) {
    if (reviewVersion != expectedReviewVersion) {
      throw new IllegalArgumentException("REVIEW_VERSION");
    }
    if (!"QUARANTINED".equals(state)) {
      throw new IllegalArgumentException("REVIEW_STATE");
    }
    if (reviewSubjectId == null
        || reviewReason == null
        || reviewReason.isBlank()
        || reviewReason.trim().length() > 2000
        || now == null) {
      throw new IllegalArgumentException("Reviewed reconciliation resume metadata is invalid");
    }
    state = "RETRY_PENDING";
    attemptCount = 0;
    nextAttemptAt = now;
    lastErrorCode = null;
    reviewVersion = Math.addExact(reviewVersion, 1);
    this.reviewSubjectId = reviewSubjectId;
    this.reviewReason = reviewReason.trim();
    reviewedAt = now;
    updatedAt = now;
  }

  public void requireStableIdentity(UUID repairId) {
    if (!java.util.Objects.equals(this.repairId, repairId)) {
      throw new IllegalArgumentException("STABLE_IDENTITY");
    }
  }

  private void requireClaim(int expectedAttemptCount) {
    if (!CLAIMABLE_STATES.contains(state) || attemptCount != expectedAttemptCount) {
      throw new IllegalArgumentException("CLAIM_CHANGED");
    }
  }

  public UUID getId() {
    return id;
  }

  public UUID getRepairId() {
    return repairId;
  }

  public String getDependencyType() {
    return dependencyType;
  }

  public String getOperationType() {
    return operationType;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getState() {
    return state;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public OffsetDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getResponseSnapshot() {
    return responseSnapshot;
  }

  public long getReviewVersion() {
    return reviewVersion;
  }

  public UUID getReviewSubjectId() {
    return reviewSubjectId;
  }

  public String getReviewReason() {
    return reviewReason;
  }

  public OffsetDateTime getReviewedAt() {
    return reviewedAt;
  }
}
