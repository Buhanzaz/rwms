package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

/** Stable completed-inventory finding identity. A later final plan version is a new source. */
@Embeddable
public class InventoryPublicationSourceId implements Serializable {
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "final_plan_version", nullable = false)
  private long finalPlanVersion;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  protected InventoryPublicationSourceId() {}

  public InventoryPublicationSourceId(UUID inventoryId, long finalPlanVersion, UUID findingId) {
    if (inventoryId == null || finalPlanVersion < 1 || findingId == null) {
      throw new IllegalArgumentException("Inventory publication source identity is incomplete");
    }
    this.inventoryId = inventoryId;
    this.finalPlanVersion = finalPlanVersion;
    this.findingId = findingId;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public long getFinalPlanVersion() {
    return finalPlanVersion;
  }

  public UUID getFindingId() {
    return findingId;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof InventoryPublicationSourceId value)) return false;
    return finalPlanVersion == value.finalPlanVersion
        && Objects.equals(inventoryId, value.inventoryId)
        && Objects.equals(findingId, value.findingId);
  }

  @Override
  public int hashCode() {
    return Objects.hash(inventoryId, finalPlanVersion, findingId);
  }
}
