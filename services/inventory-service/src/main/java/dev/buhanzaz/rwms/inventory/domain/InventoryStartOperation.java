package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity that persists inventory start operation in the inventory-owned database.
 */
@Entity
@Table(name = "inventory_start_operation")
public class InventoryStartOperation {
  @Id
  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Column(name = "subject_id", nullable = false)
  private UUID subjectId;

  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "state", nullable = false, length = 24)
  private String state;

  @Column(name = "session_id")
  private UUID sessionId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  protected InventoryStartOperation() {}

  public static InventoryStartOperation request(
      UUID operationId,
      UUID subjectId,
      UUID idempotencyKey,
      String requestSha256,
      UUID warehouseId,
      OffsetDateTime now) {
    if (operationId == null
        || subjectId == null
        || idempotencyKey == null
        || !sha256(requestSha256)
        || warehouseId == null
        || now == null) {
      throw new IllegalArgumentException("Start operation identity is incomplete");
    }
    InventoryStartOperation value = new InventoryStartOperation();
    value.operationId = operationId;
    value.subjectId = subjectId;
    value.idempotencyKey = idempotencyKey;
    value.requestSha256 = requestSha256;
    value.warehouseId = warehouseId;
    value.state = "REQUESTED";
    value.createdAt = now;
    value.updatedAt = now;
    value.expiresAt = now.plusDays(7);
    return value;
  }

  public void markCaptured(OffsetDateTime now) {
    requireUncommitted();
    state = "CAPTURED";
    updatedAt = now;
  }

  public void markCaptureFailed(OffsetDateTime now) {
    requireUncommitted();
    state = "FAILED";
    updatedAt = now;
  }

  public void markSessionCommitted(UUID sessionId, OffsetDateTime now) {
    if (sessionId == null || now == null || this.sessionId != null || !"CAPTURED".equals(state)) {
      throw new IllegalStateException("Start operation cannot be committed");
    }
    this.sessionId = sessionId;
    state = "RELEASE_PENDING";
    updatedAt = now;
  }

  public void markReleasePending(OffsetDateTime now) {
    if (now == null || "RELEASED".equals(state)) {
      throw new IllegalStateException("Released start operation cannot be reopened");
    }
    state = "RELEASE_PENDING";
    updatedAt = now;
  }

  public void markReleased(OffsetDateTime now) {
    if (now == null) {
      throw new IllegalArgumentException("Release time is required");
    }
    state = "RELEASED";
    updatedAt = now;
  }

  public void markCaptureReleased(OffsetDateTime now) {
    if (now == null || sessionId != null) {
      throw new IllegalStateException("Only an uncommitted capture can be released for retry");
    }
    state = "FAILED";
    updatedAt = now;
  }

  private void requireUncommitted() {
    if (sessionId != null || "RELEASED".equals(state) || "RELEASE_PENDING".equals(state)) {
      throw new IllegalStateException("Start operation already has a durable outcome");
    }
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public UUID getOperationId() {
    return operationId;
  }

  public UUID getSubjectId() {
    return subjectId;
  }

  public UUID getIdempotencyKey() {
    return idempotencyKey;
  }

  public String getRequestSha256() {
    return requestSha256;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getState() {
    return state;
  }

  public UUID getSessionId() {
    return sessionId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
