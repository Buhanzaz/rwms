package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Immutable receipt preventing a replayed administrative repair from re-isolating released assets.
 */
@Entity
@Table(name = "inventory_source_isolation_repair")
public class InventorySourceIsolationRepair {
  @Id
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "manifest_sha256", nullable = false, length = 64)
  private String manifestSha256;

  @Column(name = "source_count", nullable = false)
  private int sourceCount;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventorySourceIsolationRepair() {}

  public static InventorySourceIsolationRepair complete(
      UUID inventoryId, UUID warehouseId, String manifestSha256, int sourceCount) {
    if (inventoryId == null
        || warehouseId == null
        || manifestSha256 == null
        || !manifestSha256.matches("[0-9a-f]{64}")
        || sourceCount < 1) {
      throw new IllegalArgumentException("Inventory isolation repair receipt is incomplete");
    }
    var receipt = new InventorySourceIsolationRepair();
    receipt.inventoryId = inventoryId;
    receipt.warehouseId = warehouseId;
    receipt.manifestSha256 = manifestSha256;
    receipt.sourceCount = sourceCount;
    receipt.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    return receipt;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getManifestSha256() {
    return manifestSha256;
  }

  public int getSourceCount() {
    return sourceCount;
  }
}
