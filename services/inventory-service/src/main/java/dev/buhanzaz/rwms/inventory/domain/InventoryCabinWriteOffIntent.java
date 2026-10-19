package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
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

/** Durable retryable hand-off of one missing cabin to maintenance-owned write-off. */
@Entity
@Table(name = "inventory_cabin_write_off_intent")
public class InventoryCabinWriteOffIntent {
  @Id
  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Version
  @Column(name = "intent_revision", nullable = false)
  private long revision;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "cabin_id", nullable = false)
  private UUID cabinId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Column(name = "outcome_reapplication_no", nullable = false)
  private long outcomeReapplicationNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private InventoryCabinWriteOffIntentState state;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_body", nullable = false, columnDefinition = "jsonb")
  private String requestBody;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "decision_id")
  private UUID decisionId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  protected InventoryCabinWriteOffIntent() {}

  /** Creates immutable write-off work before any remote maintenance call. */
  public static InventoryCabinWriteOffIntent pending(
      UUID findingId,
      UUID inventoryId,
      UUID warehouseId,
      UUID cabinId,
      long finalPlanVersion,
      long outcomeReapplicationNo,
      UUID idempotencyKey,
      String requestSha256,
      String requestBody) {
    if (findingId == null
        || inventoryId == null
        || warehouseId == null
        || cabinId == null
        || finalPlanVersion < 1
        || outcomeReapplicationNo < 0
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Cabin write-off identity is required");
    }
    InventoryCabinWriteOffIntent value = new InventoryCabinWriteOffIntent();
    value.findingId = findingId;
    value.inventoryId = inventoryId;
    value.warehouseId = warehouseId;
    value.cabinId = cabinId;
    value.finalPlanVersion = finalPlanVersion;
    value.outcomeReapplicationNo = outcomeReapplicationNo;
    value.idempotencyKey = idempotencyKey;
    value.requestSha256 = sha256(requestSha256);
    value.requestBody = jsonObject(requestBody);
    value.state = InventoryCabinWriteOffIntentState.PENDING;
    value.nextAttemptAt = now();
    return value;
  }

  /** Claims the intent until the supplied recovery deadline. */
  public void beginAttempt(OffsetDateTime retryNotBefore) {
    if ((state != InventoryCabinWriteOffIntentState.PENDING
            && state != InventoryCabinWriteOffIntentState.TRANSIENT_FAILED)
        || retryNotBefore == null
        || !retryNotBefore.isAfter(now())) {
      throw new IllegalStateException("Cabin write-off is not retryable");
    }
    state = InventoryCabinWriteOffIntentState.PENDING;
    attemptCount = Math.addExact(attemptCount, 1);
    failureCode = null;
    nextAttemptAt = retryNotBefore;
  }

  /** Stores the maintenance-owned decision identity after a matching response. */
  public void succeed(UUID nextDecisionId) {
    if (state != InventoryCabinWriteOffIntentState.PENDING || nextDecisionId == null) {
      throw new IllegalStateException("Only a pending cabin write-off may succeed");
    }
    state = InventoryCabinWriteOffIntentState.SUCCEEDED;
    decisionId = nextDecisionId;
    failureCode = null;
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  /** Releases a failed delivery for bounded automatic retry. */
  public void transientFailure(String nextFailureCode, OffsetDateTime retryAt) {
    if (state != InventoryCabinWriteOffIntentState.PENDING
        || retryAt == null
        || !retryAt.isAfter(now())) {
      throw new IllegalArgumentException("Cabin write-off retry data is invalid");
    }
    state = InventoryCabinWriteOffIntentState.TRANSIENT_FAILED;
    failureCode = failureCode(nextFailureCode);
    nextAttemptAt = retryAt;
  }

  /** Stops automatic retry after a bounded semantic owner rejection. */
  public void block(String nextFailureCode) {
    if (state != InventoryCabinWriteOffIntentState.PENDING
        && state != InventoryCabinWriteOffIntentState.TRANSIENT_FAILED) {
      throw new IllegalStateException("Only a retryable cabin write-off may be blocked");
    }
    state = InventoryCabinWriteOffIntentState.BLOCKED;
    failureCode = failureCode(nextFailureCode);
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  /** Reopens an unresolved intent for the explicit history recovery command. */
  public void requeueForAuthoritativeRecovery(
      long nextFinalPlanVersion, long nextOutcomeReapplicationNo) {
    if (state == InventoryCabinWriteOffIntentState.SUCCEEDED) return;
    if (nextFinalPlanVersion < 1 || nextOutcomeReapplicationNo < 0) {
      throw new IllegalArgumentException("Cabin write-off generation is invalid");
    }
    finalPlanVersion = nextFinalPlanVersion;
    outcomeReapplicationNo = nextOutcomeReapplicationNo;
    state = InventoryCabinWriteOffIntentState.PENDING;
    failureCode = null;
    completedAt = null;
    nextAttemptAt = now();
  }

  private static String sha256(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Canonical SHA-256 is required");
    }
    return value;
  }

  private static String jsonObject(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 100_000) {
      throw new IllegalArgumentException("Cabin write-off request is required");
    }
    String normalized = value.trim();
    if (!normalized.startsWith("{") || !normalized.endsWith("}")) {
      throw new IllegalArgumentException("Cabin write-off request must be a JSON object");
    }
    return normalized;
  }

  private static String failureCode(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 64) {
      throw new IllegalArgumentException("Cabin write-off failure code is required");
    }
    return value.trim();
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = now();
    createdAt = current;
    updatedAt = current;
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getRevision() {
    return revision;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getCabinId() {
    return cabinId;
  }

  public long getFinalPlanVersion() {
    return finalPlanVersion;
  }

  public long getOutcomeReapplicationNo() {
    return outcomeReapplicationNo;
  }

  public InventoryCabinWriteOffIntentState getState() {
    return state;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public String getRequestBody() {
    return requestBody;
  }

  public int getAttemptCount() {
    return attemptCount;
  }

  public OffsetDateTime getNextAttemptAt() {
    return nextAttemptAt;
  }

  public String getFailureCode() {
    return failureCode;
  }

  public UUID getDecisionId() {
    return decisionId;
  }
}
