package dev.buhanzaz.rwms.logistics.domain;

/** Local transfer projection of an asset-owned loose-furniture reservation lifecycle. */
public enum TransferLooseFurnitureState {
  PENDING,
  RESERVED,
  IN_TRANSIT,
  EXECUTED,
  RELEASED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
