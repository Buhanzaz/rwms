package dev.buhanzaz.rwms.asset.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "equipment_balance")
public class EquipmentBalance {
  @Id
  @GeneratedValue(strategy = GenerationType.UUID)
  @Column(name = "id", nullable = false)
  private UUID id;

  @Version
  @Column(name = "version", nullable = false)
  private long version;

  @Column(name = "equipment_id", nullable = false)
  private UUID equipmentId;

  @Column(name = "warehouse_id", nullable = false)
  private UUID warehouseId;

  @Column(name = "rental_item_id")
  private UUID rentalItemId;

  @Enumerated(EnumType.STRING)
  @Column(name = "location_kind", nullable = false, length = 32)
  private BalanceLocationKind locationKind;

  @Column(name = "quantity", nullable = false)
  private long quantity;

  @Column(name = "created_at", nullable = false)
  private OffsetDateTime createdAt;

  @Column(name = "updated_at", nullable = false)
  private OffsetDateTime updatedAt;

  protected EquipmentBalance() {}

  public UUID getId() {
    return id;
  }

  public long getVersion() {
    return version;
  }

  public UUID getEquipmentId() {
    return equipmentId;
  }

  public UUID getWarehouseId() {
    return warehouseId;
  }

  public UUID getRentalItemId() {
    return rentalItemId;
  }

  public BalanceLocationKind getLocationKind() {
    return locationKind;
  }

  public long getQuantity() {
    return quantity;
  }

  public OffsetDateTime getCreatedAt() {
    return createdAt;
  }

  public OffsetDateTime getUpdatedAt() {
    return updatedAt;
  }
}
