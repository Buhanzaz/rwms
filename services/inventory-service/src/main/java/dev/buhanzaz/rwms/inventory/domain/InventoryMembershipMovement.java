package dev.buhanzaz.rwms.inventory.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "inventory_membership_movement")
public class InventoryMembershipMovement {
  @Id
  @Column(name = "id", nullable = false)
  private UUID id;

  @Column(name = "inventory_id", nullable = false)
  private UUID inventoryId;

  @Column(name = "source_event_id", nullable = false)
  private UUID sourceEventId;

  @Column(name = "asset_id", nullable = false)
  private UUID assetId;

  @Enumerated(EnumType.STRING)
  @Column(name = "movement_type", nullable = false, length = 24)
  private InventoryMembershipMovementType type;

  @Enumerated(EnumType.STRING)
  @Column(name = "finding_origin", nullable = false, length = 32)
  private FindingOrigin origin;

  @Column(name = "display_canonical_number", nullable = false, length = 128)
  private String displayCanonicalNumber;

  @Column(name = "from_warehouse_id")
  private UUID fromWarehouseId;

  @Column(name = "to_warehouse_id")
  private UUID toWarehouseId;

  @Column(name = "asset_status", length = 48)
  private String status;

  @Column(name = "tenant_snapshot", length = 512)
  private String tenantSnapshot;

  @Column(name = "occurred_at", nullable = false)
  private OffsetDateTime occurredAt;

  protected InventoryMembershipMovement() {}

  private InventoryMembershipMovement(
      UUID inventoryId,
      UUID sourceEventId,
      UUID assetId,
      InventoryMembershipMovementType type,
      FindingOrigin origin,
      String displayCanonicalNumber,
      UUID fromWarehouseId,
      UUID toWarehouseId,
      String status,
      String tenantSnapshot,
      OffsetDateTime occurredAt) {
    if (inventoryId == null
        || sourceEventId == null
        || assetId == null
        || type == null
        || origin == null
        || displayCanonicalNumber == null
        || displayCanonicalNumber.isBlank()
        || displayCanonicalNumber.length() > 128
        || status != null && (status.isBlank() || status.length() > 48)
        || tenantSnapshot != null && tenantSnapshot.length() > 512
        || occurredAt == null) {
      throw new IllegalArgumentException("Inventory membership movement is incomplete");
    }
    id = UUID.randomUUID();
    this.inventoryId = inventoryId;
    this.sourceEventId = sourceEventId;
    this.assetId = assetId;
    this.type = type;
    this.origin = origin;
    this.displayCanonicalNumber = displayCanonicalNumber;
    this.fromWarehouseId = fromWarehouseId;
    this.toWarehouseId = toWarehouseId;
    this.status = status;
    this.tenantSnapshot = tenantSnapshot;
    this.occurredAt = occurredAt;
  }

  public static InventoryMembershipMovement departed(
      UUID inventoryId,
      UUID sourceEventId,
      UUID assetId,
      FindingOrigin origin,
      String displayCanonicalNumber,
      UUID fromWarehouseId,
      UUID toWarehouseId,
      String status,
      String tenantSnapshot,
      OffsetDateTime occurredAt) {
    InventoryMembershipMovementType type =
        "IN_TRANSFER".equals(status) || toWarehouseId != null
            ? InventoryMembershipMovementType.TRANSFERRED
            : InventoryMembershipMovementType.DEPARTED;
    return new InventoryMembershipMovement(
        inventoryId,
        sourceEventId,
        assetId,
        type,
        origin,
        displayCanonicalNumber,
        fromWarehouseId,
        toWarehouseId,
        status,
        tenantSnapshot,
        occurredAt);
  }

  public static InventoryMembershipMovement arrived(
      UUID inventoryId,
      UUID sourceEventId,
      UUID assetId,
      FindingOrigin origin,
      String displayCanonicalNumber,
      UUID fromWarehouseId,
      UUID toWarehouseId,
      String status,
      String tenantSnapshot,
      OffsetDateTime occurredAt) {
    return new InventoryMembershipMovement(
        inventoryId,
        sourceEventId,
        assetId,
        InventoryMembershipMovementType.ARRIVED,
        origin,
        displayCanonicalNumber,
        fromWarehouseId,
        toWarehouseId,
        status,
        tenantSnapshot,
        occurredAt);
  }

  public UUID getId() {
    return id;
  }

  public UUID getAssetId() {
    return assetId;
  }

  public InventoryMembershipMovementType getType() {
    return type;
  }

  public FindingOrigin getOrigin() {
    return origin;
  }

  public String getDisplayCanonicalNumber() {
    return displayCanonicalNumber;
  }

  public UUID getFromWarehouseId() {
    return fromWarehouseId;
  }

  public UUID getToWarehouseId() {
    return toWarehouseId;
  }

  public String getStatus() {
    return status;
  }

  public String getTenantSnapshot() {
    return tenantSnapshot;
  }

  public OffsetDateTime getOccurredAt() {
    return occurredAt;
  }
}
