package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/**
 * JPA composite identifier for inventory asset source id.
 */
@Embeddable
public class InventoryAssetSourceId implements Serializable {
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  protected InventoryAssetSourceId() {}

  public InventoryAssetSourceId(UUID inventoryId, UUID findingId) {
    if (inventoryId == null || findingId == null) {
      throw new IllegalArgumentException("Inventory asset source identity is incomplete");
    }
    this.inventoryId = inventoryId;
    this.findingId = findingId;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InventoryAssetSourceId value)) {
      return false;
    }
    return Objects.equals(inventoryId, value.inventoryId)
        && Objects.equals(findingId, value.findingId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(inventoryId, findingId);
  }
}
