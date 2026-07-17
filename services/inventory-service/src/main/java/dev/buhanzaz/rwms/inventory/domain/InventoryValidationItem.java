package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Entity
@Table(name = "inventory_validation_item")
@IdClass(InventoryValidationItem.Key.class)
public class InventoryValidationItem {
  @Id @Column(name = "inventory_id", nullable = false) private UUID inventoryId;
  @Id @Column(name = "asset_id", nullable = false) private UUID assetId;
  @Column(name = "found", nullable = false) private boolean found;
  @Column(name = "asset_version") private Long assetVersion;
  @Column(name = "asset_status", length = 48) private String assetStatus;
  @Column(name = "warehouse_id") private UUID warehouseId;
  @Column(name = "finding_id", nullable = false) private UUID findingId;

  protected InventoryValidationItem() {}
  public InventoryValidationItem(UUID inventoryId, UUID assetId, boolean found, Long version,
      String status, UUID warehouseId, UUID findingId) {
    this.inventoryId = inventoryId; this.assetId = assetId; this.found = found;
    assetVersion = version; assetStatus = status; this.warehouseId = warehouseId;
    this.findingId = findingId;
  }
  public static class Key implements Serializable {
    private UUID inventoryId; private UUID assetId;
    public Key() {}
    @Override public boolean equals(Object other) { return this == other || other instanceof Key key && Objects.equals(inventoryId, key.inventoryId) && Objects.equals(assetId, key.assetId); }
    @Override public int hashCode() { return Objects.hash(inventoryId, assetId); }
  }
}
