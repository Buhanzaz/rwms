package dev.buhanzaz.rwms.dossier.domain;

/** Represents the sanitized lifecycle state of media projected into dossier activity. */
public enum DossierMediaState {
  PROCESSING,
  READY,
  FAILED,
  DELETED
}
