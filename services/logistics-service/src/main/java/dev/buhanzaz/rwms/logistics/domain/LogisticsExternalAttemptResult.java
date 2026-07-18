package dev.buhanzaz.rwms.logistics.domain;

public enum LogisticsExternalAttemptResult {
  PENDING,
  CONFIRMED,
  PERMANENT_REJECTION,
  RETRY,
  RECONCILIATION_REQUIRED
}
