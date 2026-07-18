package dev.buhanzaz.rwms.dossier.domain;

public enum DossierAggregateBlockReason {
  MISSING_PREFIX,
  EVENT_IDENTITY_CONFLICT,
  MEDIA_GENERATION_CONFLICT,
  PROCESSING_FAILED
}
