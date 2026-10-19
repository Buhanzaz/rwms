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
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/**
 * Durable, generation-scoped hand-off of one completed inventory plan to logistics-service.
 *
 * <p>The immutable request is stored once per final-plan reapplication. A lease-like pending
 * deadline permits safe replay with the same idempotency key after a process failure, while older
 * generations remain append-only audit evidence.
 */
@Entity
@Table(name = "inventory_plan_logistics_effect")
public class InventoryPlanLogisticsEffect {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "effect_revision", nullable = false)
  private long revision;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Column(name = "final_plan_sha256", nullable = false, length = 64)
  private String finalPlanSha256;

  @Column(name = "outcome_reapplication_no", nullable = false)
  private long outcomeReapplicationNo;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 24)
  private InventoryPlanEffectState state;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "request_body", nullable = false, columnDefinition = "jsonb")
  private String requestBody;

  @JdbcTypeCode(SqlTypes.JSON)
  @Column(name = "response_body", columnDefinition = "jsonb")
  private String responseBody;

  @Column(name = "attempt_count", nullable = false)
  private int attemptCount;

  @Column(name = "next_attempt_at", nullable = false)
  private OffsetDateTime nextAttemptAt;

  @Column(name = "failure_code", length = 64)
  private String failureCode;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  protected InventoryPlanLogisticsEffect() {}

  /** Creates immutable plan-wide logistics work before any remote call is attempted. */
  public static InventoryPlanLogisticsEffect ready(
      UUID inventoryId,
      long finalPlanVersion,
      String finalPlanSha256,
      long outcomeReapplicationNo,
      UUID idempotencyKey,
      String requestSha256,
      String requestBody) {
    requireIdentity(
        inventoryId,
        finalPlanVersion,
        finalPlanSha256,
        outcomeReapplicationNo,
        idempotencyKey,
        requestSha256,
        requestBody);
    InventoryPlanLogisticsEffect value = new InventoryPlanLogisticsEffect();
    value.inventoryId = inventoryId;
    value.finalPlanVersion = finalPlanVersion;
    value.finalPlanSha256 = finalPlanSha256;
    value.outcomeReapplicationNo = outcomeReapplicationNo;
    value.idempotencyKey = idempotencyKey;
    value.requestSha256 = requestSha256;
    value.requestBody = requestBody.trim();
    value.state = InventoryPlanEffectState.READY;
    value.attemptCount = 0;
    value.nextAttemptAt = now();
    return value;
  }

  /** Rejects drift when the same plan generation is scheduled more than once. */
  public void requireSame(
      String expectedFinalPlanSha256,
      UUID expectedIdempotencyKey,
      String expectedRequestSha256) {
    if (!Objects.equals(finalPlanSha256, expectedFinalPlanSha256)
        || !Objects.equals(idempotencyKey, expectedIdempotencyKey)
        || !Objects.equals(requestSha256, expectedRequestSha256)) {
      throw new IllegalStateException("Inventory plan logistics effect changed at the same generation");
    }
  }

  /** Claims this effect until the supplied recovery deadline. */
  public void beginAttempt(OffsetDateTime recoveryAt) {
    OffsetDateTime current = now();
    if ((state != InventoryPlanEffectState.READY
            && state != InventoryPlanEffectState.TRANSIENT_FAILED
            && state != InventoryPlanEffectState.PENDING)
        || recoveryAt == null
        || !recoveryAt.isAfter(current)
        || (state == InventoryPlanEffectState.PENDING && nextAttemptAt.isAfter(current))) {
      throw new IllegalStateException("Inventory plan logistics effect is not claimable");
    }
    state = InventoryPlanEffectState.PENDING;
    attemptCount = Math.addExact(attemptCount, 1);
    nextAttemptAt = recoveryAt;
    failureCode = null;
  }

  /** Stores the exact successful owner response for future local replays. */
  public void succeed(String responseBody) {
    if (state != InventoryPlanEffectState.PENDING) {
      throw new IllegalStateException("Only a pending logistics effect may succeed");
    }
    this.responseBody = jsonObject(responseBody, "Logistics response");
    state = InventoryPlanEffectState.SUCCEEDED;
    failureCode = null;
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  /** Releases a failed claim for bounded automatic retry. */
  public void transientFailure(String failureCode, OffsetDateTime retryAt) {
    if (state != InventoryPlanEffectState.PENDING
        || retryAt == null
        || !retryAt.isAfter(now())) {
      throw new IllegalArgumentException("Inventory logistics retry data is invalid");
    }
    state = InventoryPlanEffectState.TRANSIENT_FAILED;
    this.failureCode = requiredFailureCode(failureCode);
    nextAttemptAt = retryAt;
  }

  /** Terminates automatic retry for a semantic owner rejection. */
  public void block(String failureCode) {
    if (state != InventoryPlanEffectState.PENDING) {
      throw new IllegalStateException("Only a pending logistics effect may be blocked");
    }
    state = InventoryPlanEffectState.BLOCKED;
    this.failureCode = requiredFailureCode(failureCode);
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  private static void requireIdentity(
      UUID inventoryId,
      long finalPlanVersion,
      String finalPlanSha256,
      long outcomeReapplicationNo,
      UUID idempotencyKey,
      String requestSha256,
      String requestBody) {
    if (inventoryId == null
        || finalPlanVersion < 1
        || !sha256(finalPlanSha256)
        || outcomeReapplicationNo < 0
        || idempotencyKey == null
        || !sha256(requestSha256)) {
      throw new IllegalArgumentException("Inventory plan logistics identity is incomplete");
    }
    jsonObject(requestBody, "Logistics request");
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("^[0-9a-f]{64}$");
  }

  private static String jsonObject(String value, String field) {
    if (value == null || value.isBlank() || value.trim().length() > 2_000_000) {
      throw new IllegalArgumentException(field + " is required");
    }
    String normalized = value.trim();
    if (!normalized.startsWith("{")) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return normalized;
  }

  private static String requiredFailureCode(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 64) {
      throw new IllegalArgumentException("Inventory logistics failure code is required");
    }
    return value.trim();
  }

  @PrePersist
  void beforeInsert() {
    OffsetDateTime current = now();
    createdAt = current;
    updatedAt = current;
    if (state == InventoryPlanEffectState.READY) {
      nextAttemptAt = current;
    }
  }

  @PreUpdate
  void beforeUpdate() {
    updatedAt = now();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public UUID getId() {
    return id;
  }

  public long getRevision() {
    return revision;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getFinalPlanVersion() {
    return finalPlanVersion;
  }

  public String getFinalPlanSha256() {
    return finalPlanSha256;
  }

  public long getOutcomeReapplicationNo() {
    return outcomeReapplicationNo;
  }

  public InventoryPlanEffectState getState() {
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
}
