package dev.buhanzaz.rwms.logistics.domain;

public enum LogisticsTaskReferenceState {
  PENDING,
  REGISTERED,
  READY,
  DONE,
  CANCELLED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
