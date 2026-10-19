package dev.buhanzaz.rwms.dossier.domain;

/** Enumerates sanitized, stable reasons a dossier source record is published to its consumer DLT. */
public enum DossierDltFailureCode {
  INVALID_ENVELOPE,
  UNSUPPORTED_PRODUCER,
  UNSUPPORTED_AGGREGATE_TYPE,
  UNSUPPORTED_EVENT_TYPE,
  UNSUPPORTED_EVENT_VERSION,
  INVALID_PAYLOAD,
  RECORD_KEY_MISMATCH,
  PROCESSING_FAILED,
  EVENT_IDENTITY_CONFLICT,
  MEDIA_GENERATION_CONFLICT
}
