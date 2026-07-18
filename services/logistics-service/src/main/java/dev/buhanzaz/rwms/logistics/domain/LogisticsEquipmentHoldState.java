package dev.buhanzaz.rwms.logistics.domain;

public enum LogisticsEquipmentHoldState {
  ACTIVE,
  COMMITTED,
  RELEASED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
