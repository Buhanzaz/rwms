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

/** Durable, retryable hand-off of an accepted furniture review to asset-service. */
@Entity
@Table(name = "inventory_furniture_reconciliation_intent")
public class InventoryFurnitureReconciliationIntent {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Version
  @Column(name = "intent_revision", nullable = false)
  private long revision;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private FurnitureReconciliationState state;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "asset_snapshot_sha256", nullable = false, length = 64)
  private String assetSnapshotSha256;

  @Column(name = "review_sha256", nullable = false, length = 64)
  private String reviewSha256;

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

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  protected InventoryFurnitureReconciliationIntent() {}

  public static InventoryFurnitureReconciliationIntent pending(
      UUID inventoryId,
      UUID idempotencyKey,
      String assetSnapshotSha256,
      String reviewSha256,
      String requestSha256,
      String requestBody) {
    if (inventoryId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Furniture reconciliation identity is required");
    }
    InventoryFurnitureReconciliationIntent value = new InventoryFurnitureReconciliationIntent();
    value.inventoryId = inventoryId;
    value.idempotencyKey = idempotencyKey;
    value.assetSnapshotSha256 = sha256(assetSnapshotSha256);
    value.reviewSha256 = sha256(reviewSha256);
    value.requestSha256 = sha256(requestSha256);
    value.requestBody = jsonObject(requestBody, "Furniture reconciliation request");
    value.state = FurnitureReconciliationState.PENDING;
    value.attemptCount = 0;
    value.nextAttemptAt = now();
    return value;
  }

  public void beginAttempt(OffsetDateTime retryNotBefore) {
    if ((state != FurnitureReconciliationState.PENDING
            && state != FurnitureReconciliationState.TRANSIENT_FAILED)
        || retryNotBefore == null
        || !retryNotBefore.isAfter(now())) {
      throw new IllegalStateException("Furniture reconciliation is not retryable");
    }
    state = FurnitureReconciliationState.PENDING;
    attemptCount = Math.addExact(attemptCount, 1);
    failureCode = null;
    nextAttemptAt = retryNotBefore;
  }

  public void succeed() {
    if (state != FurnitureReconciliationState.PENDING) {
      throw new IllegalStateException("Only a pending furniture reconciliation may succeed");
    }
    state = FurnitureReconciliationState.SUCCEEDED;
    failureCode = null;
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  public void transientFailure(String nextFailureCode, OffsetDateTime retryAt) {
    if (state != FurnitureReconciliationState.PENDING || retryAt == null || !retryAt.isAfter(now())) {
      throw new IllegalArgumentException("Furniture reconciliation retry data is invalid");
    }
    state = FurnitureReconciliationState.TRANSIENT_FAILED;
    failureCode = failureCode(nextFailureCode);
    nextAttemptAt = retryAt;
  }

  public void block(String nextFailureCode) {
    if (state != FurnitureReconciliationState.PENDING) {
      throw new IllegalStateException("Only a pending furniture reconciliation may be blocked");
    }
    state = FurnitureReconciliationState.BLOCKED;
    failureCode = failureCode(nextFailureCode);
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  /** Makes a failed authoritative furniture hand-off immediately eligible for recovery. */
  public void requeueForAuthoritativeRecovery() {
    if (state == FurnitureReconciliationState.SUCCEEDED) {
      return;
    }
    state = FurnitureReconciliationState.PENDING;
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

  private static String failureCode(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 64) {
      throw new IllegalArgumentException("Furniture reconciliation failure code is required");
    }
    return value.trim();
  }

  private static String jsonObject(String value, String field) {
    if (value == null || value.isBlank() || value.trim().length() > 1_000_000) {
      throw new IllegalArgumentException(field + " is required");
    }
    String normalized = value.trim();
    if (!normalized.startsWith("{")) {
      throw new IllegalArgumentException(field + " must be a JSON object");
    }
    return normalized;
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

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getRevision() {
    return revision;
  }

  public FurnitureReconciliationState getState() {
    return state;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getAssetSnapshotSha256() {
    return assetSnapshotSha256;
  }

  public String getReviewSha256() {
    return reviewSha256;
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

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
