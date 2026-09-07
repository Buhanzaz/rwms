package dev.buhanzaz.rwms.maintenance.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Service-local input truth; fenced HTTP CAS remains the command authority. */
@Entity
@Table(name = "rental_item_fact_projection")
public class RentalItemFactProjection {
  @Id
  @Column(name = "rental_item_id", nullable = false)
  private UUID rentalItemId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "asset_status", nullable = false, length = 64)
  private String assetStatus;

  @Column(name = "inventory_isolated", nullable = false)
  private boolean inventoryIsolated;

  @Column(name = "aggregate_version", nullable = false)
  private long aggregateVersion;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected RentalItemFactProjection() {}

  public static RentalItemFactProjection create(
      UUID rentalItemId, UUID warehouseId, String assetStatus, long aggregateVersion) {
    return create(rentalItemId, warehouseId, assetStatus, aggregateVersion, null);
  }

  public static RentalItemFactProjection create(
      UUID rentalItemId,
      UUID warehouseId,
      String assetStatus,
      long aggregateVersion,
      Boolean isolated) {
    RentalItemFactProjection value = new RentalItemFactProjection();
    value.rentalItemId = rentalItemId;
    value.apply(warehouseId, assetStatus, aggregateVersion, isolated);
    return value;
  }

  public boolean apply(UUID warehouseId, String assetStatus, long aggregateVersion) {
    return apply(warehouseId, assetStatus, aggregateVersion, null);
  }

  /** Only a visibility fact changes isolation; ordinary facts preserve the existing fence. */
  public boolean apply(
      UUID warehouseId, String assetStatus, long aggregateVersion, Boolean isolated) {
    if (warehouseId == null
        || assetStatus == null
        || assetStatus.isBlank()
        || aggregateVersion < 0) {
      throw new IllegalArgumentException("Rental-item fact is invalid");
    }
    if (updatedAt != null && aggregateVersion <= this.aggregateVersion) return false;
    this.warehouseId = warehouseId;
    this.assetStatus = assetStatus;
    this.aggregateVersion = aggregateVersion;
    if (isolated != null) this.inventoryIsolated = isolated;
    this.updatedAt = MaintenanceTime.now();
    return true;
  }

  public UUID getRentalItemId() {
    return rentalItemId;
  }

  public boolean isInventoryIsolated() {
    return inventoryIsolated;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public String getAssetStatus() {
    return assetStatus;
  }

  public long getAggregateVersion() {
    return aggregateVersion;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
