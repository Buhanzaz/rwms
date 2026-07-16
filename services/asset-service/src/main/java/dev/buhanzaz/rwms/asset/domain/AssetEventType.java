package dev.buhanzaz.rwms.asset.domain;

public enum AssetEventType {
  RENTAL_ITEM_CREATED("asset.rental-item.created.v1"),
  RENTAL_ITEM_PASSPORT_CHANGED("asset.rental-item.passport-changed.v1"),
  RENTAL_ITEM_STATUS_CHANGED("asset.rental-item.status-changed.v1"),
  RENTAL_ITEM_WAREHOUSE_CHANGED("asset.rental-item.warehouse-changed.v1"),
  RENTAL_ITEM_GENERAL_COMMENT_CHANGED("asset.rental-item.general-comment-changed.v1"),
  RENTAL_ITEM_MANUAL_NOTE_ADDED("asset.rental-item.manual-note-added.v1"),
  EQUIPMENT_CATALOG_CREATED("asset.equipment-catalog.created.v1"),
  EQUIPMENT_CATALOG_CHANGED("asset.equipment-catalog.changed.v1"),
  EQUIPMENT_BALANCE_CHANGED("asset.equipment-balance.changed.v1"),
  EQUIPMENT_TRANSFERRED("asset.equipment-movement.transferred.v1"),
  EQUIPMENT_WRITTEN_OFF("asset.equipment-movement.written-off.v1"),
  EQUIPMENT_LOST("asset.equipment-movement.lost.v1"),
  EQUIPMENT_HOLD_ACQUIRED("asset.equipment-allocation-hold.acquired.v1"),
  EQUIPMENT_HOLD_RENEWED("asset.equipment-allocation-hold.renewed.v1"),
  EQUIPMENT_HOLD_COMMITTED("asset.equipment-allocation-hold.committed.v1"),
  EQUIPMENT_HOLD_RELEASED("asset.equipment-allocation-hold.released.v1"),
  EQUIPMENT_HOLD_EXPIRED("asset.equipment-allocation-hold.expired.v1"),
  OPERATION_LEASE_ACQUIRED("asset.operation-lease.acquired.v1"),
  OPERATION_LEASE_RENEWED("asset.operation-lease.renewed.v1"),
  OPERATION_LEASE_RELEASED("asset.operation-lease.released.v1"),
  OPERATION_LEASE_EXPIRED("asset.operation-lease.expired.v1"),
  CLASSIFIER_CREATED("asset.classifier.created.v1"),
  CLASSIFIER_CHANGED("asset.classifier.changed.v1");

  private final String value;

  AssetEventType(String value) {
    this.value = value;
  }

  public String value() {
    return value;
  }

  public static AssetEventType require(String value) {
    for (AssetEventType type : values()) if (type.value.equals(value)) return type;
    throw new IllegalArgumentException("Unsupported asset event type");
  }
}
