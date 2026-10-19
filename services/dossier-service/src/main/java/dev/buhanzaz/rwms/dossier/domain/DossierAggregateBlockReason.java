package dev.buhanzaz.rwms.dossier.domain;

/** Explains why a source aggregate is quarantined instead of allowing an unproven event order or identity. */
public enum DossierAggregateBlockReason {
  MISSING_PREFIX,
  EVENT_IDENTITY_CONFLICT,
  MEDIA_GENERATION_CONFLICT,
  PROCESSING_FAILED
}
