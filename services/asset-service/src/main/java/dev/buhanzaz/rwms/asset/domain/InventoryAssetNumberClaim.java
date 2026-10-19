package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * JPA entity that persists inventory asset number claim in the asset-owned database.
 */
@Entity
@Table(name = "inventory_asset_number_claim")
public class InventoryAssetNumberClaim {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "claim_id", nullable = false)
  private UUID claimId;

  @Column(name = "warehouse_id")
  private UUID warehouseId;

  @Column(name = "identity_match_key", nullable = false, length = 128)
  private String identityMatchKey;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  protected InventoryAssetNumberClaim() {}

  public static InventoryAssetNumberClaim claim(
      UUID warehouseId, String identityMatchKey, InventoryAssetSourceId sourceId) {
    if (warehouseId == null || identityMatchKey == null || sourceId == null) {
      throw new IllegalArgumentException("Inventory number claim identity is incomplete");
    }
    InventoryAssetNumberClaim value = new InventoryAssetNumberClaim();
    value.warehouseId = warehouseId;
    value.identityMatchKey = identityMatchKey;
    value.inventoryId = sourceId.getInventoryId();
    value.findingId = sourceId.getFindingId();
    value.createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    return value;
  }

  public void bindWarehouse(UUID requestedWarehouseId, String requestedIdentityMatchKey) {
    if (requestedWarehouseId == null || requestedIdentityMatchKey == null) {
      throw new IllegalArgumentException("Inventory number claim scope is incomplete");
    }
    if (!identityMatchKey.equals(requestedIdentityMatchKey)) {
      throw new IllegalStateException("Inventory number claim is bound to another number");
    }
    if (warehouseId == null) {
      warehouseId = requestedWarehouseId;
    } else if (!warehouseId.equals(requestedWarehouseId)) {
      throw new IllegalStateException("Inventory number claim is bound to another warehouse");
    }
  }

  public boolean belongsTo(InventoryAssetSourceId sourceId) {
    return inventoryId.equals(sourceId.getInventoryId()) && findingId.equals(sourceId.getFindingId());
  }

  public UUID getClaimId() {
    return claimId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getIdentityMatchKey() {
    return identityMatchKey;
  }

  public long getVersion() {
    return version;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }
}
