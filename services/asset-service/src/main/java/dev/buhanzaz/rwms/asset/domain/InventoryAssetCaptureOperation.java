package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JPA entity that persists inventory asset capture operation in the asset-owned database.
 */
@Entity
@Table(name = "inventory_asset_capture_operation")
public class InventoryAssetCaptureOperation {
  @Id
  @Column(name = "operation_id", nullable = false)
  private UUID operationId;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "request_fingerprint", nullable = false, length = 64)
  private String requestFingerprint;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryAssetCaptureOperation() {}

  public static InventoryAssetCaptureOperation register(
      UUID operationId, UUID warehouseId, String requestFingerprint) {
    if (operationId == null || warehouseId == null || !sha256(requestFingerprint)) {
      throw new IllegalArgumentException("Inventory capture operation identity is incomplete");
    }
    InventoryAssetCaptureOperation value = new InventoryAssetCaptureOperation();
    value.operationId = operationId;
    value.warehouseId = warehouseId;
    value.requestFingerprint = requestFingerprint;
    value.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    return value;
  }

  private static boolean sha256(String value) {
    return value != null && value.matches("[0-9a-f]{64}");
  }

  public UUID getOperationId() {
    return operationId;
  }

  public long getVersion() {
    return version;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getRequestFingerprint() {
    return requestFingerprint;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
