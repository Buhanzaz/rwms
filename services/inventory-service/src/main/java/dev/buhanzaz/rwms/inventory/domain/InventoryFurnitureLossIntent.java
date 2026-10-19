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

/**
 * One durable inventory-owned furniture shortage. The frozen request is retried until
 * maintenance-service has recorded the administrator-owned LOSS decision.
 */
@Entity
@Table(name = "inventory_furniture_loss_intent")
public class InventoryFurnitureLossIntent {
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

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 32)
  private FurnitureLossIntentState state;

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

  protected InventoryFurnitureLossIntent() {}

  public static InventoryFurnitureLossIntent pending(
      UUID findingId,
      UUID inventoryId,
      UUID warehouseId,
      UUID equipmentId,
      UUID idempotencyKey,
      String requestSha256,
      String requestBody) {
    if (findingId == null
        || inventoryId == null
        || warehouseId == null
        || equipmentId == null
        || idempotencyKey == null) {
      throw new IllegalArgumentException("Furniture loss identity is required");
    }
    InventoryFurnitureLossIntent value = new InventoryFurnitureLossIntent();
    value.findingId = findingId;
    value.inventoryId = inventoryId;
    value.warehouseId = warehouseId;
    value.equipmentId = equipmentId;
    value.idempotencyKey = idempotencyKey;
    value.requestSha256 = sha256(requestSha256);
    value.requestBody = jsonObject(requestBody);
    value.state = FurnitureLossIntentState.PENDING;
    value.nextAttemptAt = now();
    return value;
  }

  public void beginAttempt(OffsetDateTime retryNotBefore) {
    if ((state != FurnitureLossIntentState.PENDING
            && state != FurnitureLossIntentState.TRANSIENT_FAILED)
        || retryNotBefore == null
        || !retryNotBefore.isAfter(now())) {
      throw new IllegalStateException("Furniture loss proposal is not retryable");
    }
    state = FurnitureLossIntentState.PENDING;
    attemptCount = Math.addExact(attemptCount, 1);
    failureCode = null;
    nextAttemptAt = retryNotBefore;
  }

  public void succeed(UUID nextDecisionId) {
    if (state != FurnitureLossIntentState.PENDING || nextDecisionId == null) {
      throw new IllegalStateException("Only a pending furniture loss proposal may succeed");
    }
    state = FurnitureLossIntentState.SUCCEEDED;
    decisionId = nextDecisionId;
    failureCode = null;
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  public void transientFailure(String nextFailureCode, OffsetDateTime retryAt) {
    if (state != FurnitureLossIntentState.PENDING
        || retryAt == null
        || !retryAt.isAfter(now())) {
      throw new IllegalArgumentException("Furniture loss retry data is invalid");
    }
    state = FurnitureLossIntentState.TRANSIENT_FAILED;
    failureCode = failureCode(nextFailureCode);
    nextAttemptAt = retryAt;
  }

  public void block(String nextFailureCode) {
    if (state != FurnitureLossIntentState.PENDING) {
      throw new IllegalStateException("Only a pending furniture loss proposal may be blocked");
    }
    state = FurnitureLossIntentState.BLOCKED;
    failureCode = failureCode(nextFailureCode);
    completedAt = now();
    nextAttemptAt = completedAt;
  }

  private static String sha256(String value) {
    if (value == null || !value.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Canonical SHA-256 is required");
    }
    return value;
  }

  private static String jsonObject(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 100_000) {
      throw new IllegalArgumentException("Furniture loss request is required");
    }
    String normalized = value.trim();
    if (!normalized.startsWith("{")) {
      throw new IllegalArgumentException("Furniture loss request must be a JSON object");
    }
    return normalized;
  }

  private static String failureCode(String value) {
    if (value == null || value.isBlank() || value.trim().length() > 64) {
      throw new IllegalArgumentException("Furniture loss failure code is required");
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

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public FurnitureLossIntentState getState() {
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
