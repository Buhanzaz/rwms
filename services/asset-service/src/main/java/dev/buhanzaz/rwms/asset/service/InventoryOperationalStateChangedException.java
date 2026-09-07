package dev.buhanzaz.rwms.asset.service;

/** Reports that a post-inventory rental or transfer must not be replaced by a stale outcome. */
public class InventoryOperationalStateChangedException extends AssetConflictException {
  public InventoryOperationalStateChangedException(String message) {
    super(message);
  }

  public String code() {
    return "INVENTORY_OPERATIONAL_STATE_CHANGED";
  }
}
