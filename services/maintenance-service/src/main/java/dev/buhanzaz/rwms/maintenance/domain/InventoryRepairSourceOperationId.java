package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.io.Serializable;
import java.util.Objects;
import java.util.UUID;

@Embeddable
public class InventoryRepairSourceOperationId implements Serializable {
  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "finding_id", nullable = false)
  private UUID findingId;

  /**
   * An inspection finding can be supplemented before inventory completion. Each resulting
   * finding revision owns a separate immutable maintenance-plan source operation.
   */
  @Column(name = "source_revision", nullable = false)
  private long sourceRevision;

  protected InventoryRepairSourceOperationId() {}

  public InventoryRepairSourceOperationId(UUID inventoryId, UUID findingId, long sourceRevision) {
    if (inventoryId == null || findingId == null || sourceRevision < 1) {
      throw new IllegalArgumentException("Inventory repair source identity is incomplete");
    }
    this.inventoryId = inventoryId;
    this.findingId = findingId;
    this.sourceRevision = sourceRevision;
  }

  public UUID getInventoryId() {
    return inventoryId;
  }

  public UUID getFindingId() {
    return findingId;
  }

  public long getSourceRevision() {
    return sourceRevision;
  }

  @Override
  public boolean equals(Object other) {
    if (this == other) {
      return true;
    }
    if (!(other instanceof InventoryRepairSourceOperationId value)) {
      return false;
    }
    return Objects.equals(inventoryId, value.inventoryId)
        && Objects.equals(findingId, value.findingId)
        && sourceRevision == value.sourceRevision;
  }

  @Override
  public int hashCode() {
    return Objects.hash(inventoryId, findingId, sourceRevision);
  }
}
