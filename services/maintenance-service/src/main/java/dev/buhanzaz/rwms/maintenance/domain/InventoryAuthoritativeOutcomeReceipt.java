package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

/** Permanent idempotency receipt for one authoritative inventory outcome invocation. */
@Entity
@Table(name = "inventory_authoritative_outcome_receipt")
public class InventoryAuthoritativeOutcomeReceipt {
  @Id
  @Column(name = "idempotency_key", nullable = false)
  private UUID idempotencyKey;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "request_sha256", nullable = false, length = 64)
  private String requestSha256;

  @Column(name = "response_snapshot", columnDefinition = "jsonb")
  @JdbcTypeCode(SqlTypes.JSON)
  private String responseSnapshot;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "completed_at")
  private OffsetDateTime completedAt;

  protected InventoryAuthoritativeOutcomeReceipt() {}

  /** Registers a key against one immutable source and request fingerprint. */
  public static InventoryAuthoritativeOutcomeReceipt register(
      UUID idempotencyKey, InventoryPublicationSourceId sourceId, String requestSha256) {
    if (idempotencyKey == null
        || sourceId == null
        || requestSha256 == null
        || !requestSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Authoritative inventory receipt is incomplete");
    }
    InventoryAuthoritativeOutcomeReceipt value = new InventoryAuthoritativeOutcomeReceipt();
    value.idempotencyKey = idempotencyKey;
    value.inventoryId = sourceId.getInventoryId();
    value.finalPlanVersion = sourceId.getFinalPlanVersion();
    value.findingId = sourceId.getFindingId();
    value.requestSha256 = requestSha256;
    return value;
  }

  /** Fails when the same idempotency key is reused for another source or request. */
  public void requireSame(InventoryPublicationSourceId sourceId, String requestSha256) {
    if (sourceId == null
        || !Objects.equals(inventoryId, sourceId.getInventoryId())
        || finalPlanVersion != sourceId.getFinalPlanVersion()
        || !Objects.equals(findingId, sourceId.getFindingId())
        || !Objects.equals(this.requestSha256, requestSha256)) {
      throw new IllegalArgumentException("AUTHORITATIVE_OUTCOME_IDEMPOTENCY_MISMATCH");
    }
  }

  /** Completes the receipt with the exact source response; repeated completion must match. */
  public void complete(String responseSnapshot) {
    if (responseSnapshot == null) {
      throw new IllegalArgumentException("Authoritative inventory response is required");
    }
    if (this.responseSnapshot != null) {
      if (!Objects.equals(this.responseSnapshot, responseSnapshot)) {
        throw new IllegalStateException("Authoritative inventory receipt response cannot change");
      }
      return;
    }
    this.responseSnapshot = responseSnapshot;
    completedAt = MaintenanceTime.now();
  }

  @PrePersist
  void beforeInsert() {
    createdAt = MaintenanceTime.now();
  }

  public UUID getIdempotencyKey() { return idempotencyKey; }
  public UUID getInventoryId() { return inventoryId; }
  public long getFinalPlanVersion() { return finalPlanVersion; }
  public UUID getFindingId() { return findingId; }
  public String getRequestSha256() { return requestSha256; }
  public String getResponseSnapshot() { return responseSnapshot; }
  public OffsetDateTime getCreatedAt() { return createdAt; }
  public OffsetDateTime getCompletedAt() { return completedAt; }
}
