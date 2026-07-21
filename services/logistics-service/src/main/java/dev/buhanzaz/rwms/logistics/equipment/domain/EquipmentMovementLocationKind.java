package dev.buhanzaz.rwms.logistics.equipment.domain;

/** Locations that may participate in a worker-mediated equipment movement. */
public enum EquipmentMovementLocationKind {
  STOCK,
  CABIN_NON_RENTED,
  CABIN_RENTED;

  public boolean requiresRentalItem() {
    return this != STOCK;
  }
}
