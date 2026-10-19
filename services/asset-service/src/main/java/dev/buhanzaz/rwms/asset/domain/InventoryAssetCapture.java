package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * JPA entity that persists inventory asset capture in the asset-owned database.
 */
@Entity
@Table(name = "inventory_asset_capture")
public class InventoryAssetCapture {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "capture_id", nullable = false)
  private UUID captureId;

  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Column(name = "technical_attempt", nullable = false)
  private long technicalAttempt;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "membership_digest", nullable = false, length = 64)
  private String membershipDigest;

  @Column(name = "total_count", nullable = false)
  private long totalCount;

  @Enumerated(EnumType.STRING)
  @Column(name = "state", nullable = false, length = 16)
  private InventoryAssetCaptureState state;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "expires_at", nullable = false)
  private OffsetDateTime expiresAt;

  @Column(name = "released_at")
  private OffsetDateTime releasedAt;

  protected InventoryAssetCapture() {}

  public static InventoryAssetCapture create(
      UUID operationId,
      long technicalAttempt,
      UUID warehouseId,
      String requestFingerprint,
      String membershipDigest,
      long totalCount,
      OffsetDateTime createdAt) {
    if (operationId == null
        || technicalAttempt < 1
        || warehouseId == null
        || !sha256(requestFingerprint)
        || !sha256(membershipDigest)
        || totalCount < 0
        || createdAt == null) {
      throw new IllegalArgumentException("Inventory capture is incomplete");
    }
    InventoryAssetCapture value = new InventoryAssetCapture();
    value.operationId = operationId;
    value.technicalAttempt = technicalAttempt;
    value.warehouseId = warehouseId;
    value.requestFingerprint = requestFingerprint;
    value.membershipDigest = membershipDigest;
    value.totalCount = totalCount;
    value.state = InventoryAssetCaptureState.ACTIVE;
    value.createdAt = createdAt;
    value.expiresAt = createdAt.plusMinutes(30);
    return value;
  }

  public void release(OffsetDateTime now) {
    if (state != InventoryAssetCaptureState.ACTIVE) {
      return;
    }
    if (!expiresAt.isAfter(now)) {
      state = InventoryAssetCaptureState.EXPIRED;
      releasedAt = expiresAt;
      return;
    }
    state = InventoryAssetCaptureState.RELEASED;
    releasedAt = now;
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public UUID getCaptureId() {
    return captureId;
  }

  public UUID getOperationId() {
    return operationId;
  }

  public long getTechnicalAttempt() {
    return technicalAttempt;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public String getMembershipDigest() {
    return membershipDigest;
  }

  public long getTotalCount() {
    return totalCount;
  }

  public InventoryAssetCaptureState getState() {
    return state;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getExpiresAt() {
    return expiresAt;
  }

  public OffsetDateTime getReleasedAt() {
    return releasedAt;
  }
}
