package dev.buhanzaz.rwms.dossier.domain;

public enum DossierOutboxState {
  PENDING,
  RETRY,
  PUBLISHED,
  DLT
}
