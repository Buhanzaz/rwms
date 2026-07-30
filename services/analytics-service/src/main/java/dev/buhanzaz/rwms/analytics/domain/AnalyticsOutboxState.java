package dev.buhanzaz.rwms.analytics.domain;

public enum AnalyticsOutboxState {
  PENDING,
  RETRY,
  PUBLISHED,
  DLT
}
