package dev.buhanzaz.rwms.logistics.retention.domain;

/** Data classes governed by the logistics retention and legal-hold policy. */
public enum LogisticsRetentionDataset {
  BUSINESS_AUDIT_PROOF,
  EVENT_OUTBOX,
  EVENT_INBOX,
  GPS_TELEMETRY
}
