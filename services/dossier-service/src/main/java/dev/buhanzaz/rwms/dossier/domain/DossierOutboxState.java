package dev.buhanzaz.rwms.dossier.domain;

/** Defines the durable relay lifecycle shared by dossier activity and sanitized DLT outbox rows. */
public enum DossierOutboxState {
  PENDING,
  RETRY,
  PUBLISHED,
  DLT
}
