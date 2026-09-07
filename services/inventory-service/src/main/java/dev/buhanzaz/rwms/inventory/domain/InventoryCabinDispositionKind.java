package dev.buhanzaz.rwms.inventory.domain;

/** Frozen authoritative disposition applied to one inventory finding. */
public enum InventoryCabinDispositionKind {
  PRESERVE,
  LOCAL,
  SHIPMENT,
  WRITE_OFF
}
