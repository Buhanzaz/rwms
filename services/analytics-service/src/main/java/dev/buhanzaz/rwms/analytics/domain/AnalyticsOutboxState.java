package dev.buhanzaz.rwms.analytics.domain;

/** Defines the local lifecycle of a sanitized analytics dead-letter publication attempt. */
public enum AnalyticsOutboxState {
  PENDING,
  RETRY,
  PUBLISHED,
  DLT
}
