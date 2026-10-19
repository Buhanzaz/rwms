package dev.buhanzaz.rwms.dossier.domain;

/** Captures the durable consumer disposition of a source event, including quarantine and terminal failure. */
public enum DossierInboxDecision {
  RECEIVED,
  PROCESSED,
  DUPLICATE,
  QUARANTINED,
  DLT
}
