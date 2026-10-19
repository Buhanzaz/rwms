package dev.buhanzaz.rwms.dossier.domain;

/** Records why a validated source fact cannot yet be represented as a proven cabin activity. */
public enum DossierUnlinkedReason {
  SUBJECT_NOT_PROVIDED,
  SUBJECT_NOT_YET_PROVEN,
  WAREHOUSE_NOT_PROVIDED,
  MISSING_PREFIX,
  AGGREGATE_QUARANTINED
}
