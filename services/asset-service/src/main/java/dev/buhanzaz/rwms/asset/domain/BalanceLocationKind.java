package dev.buhanzaz.rwms.asset.domain;

/** Physical state buckets. Their sum is the operator-facing total. */
public enum BalanceLocationKind {
  STOCK,
  CABIN_NON_RENTED,
  CABIN_RENTED,
  WRITTEN_OFF,
  LOST
}
