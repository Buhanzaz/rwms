package dev.buhanzaz.rwms.analytics.domain;

/** Enumerates safe, stable reasons why analytics quarantines a source record instead of projecting it. */
public enum AnalyticsDltFailureCode {
  INVALID_ENVELOPE,
  INVALID_PAYLOAD,
  RECORD_KEY_MISMATCH,
  EVENT_IDENTITY_CONFLICT,
  SOURCE_COORDINATE_CONFLICT,
  MISSING_AGGREGATE_VERSION,
  PROCESSING_FAILED
}
