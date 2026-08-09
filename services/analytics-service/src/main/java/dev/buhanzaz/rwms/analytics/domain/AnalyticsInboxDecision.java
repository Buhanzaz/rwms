package dev.buhanzaz.rwms.analytics.domain;

/** Records the durable outcome of one delivered analytics event so at-least-once redelivery cannot apply it twice. */
public enum AnalyticsInboxDecision {
  RECEIVED,
  HELD,
  PROCESSED,
  STALE,
  DLT
}
