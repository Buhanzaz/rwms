package dev.buhanzaz.rwms.dossier.domain;

/** Identifies a source service whose committed facts are accepted by the dossier projection contract. */
public enum DossierProducer {
  ASSET,
  MAINTENANCE,
  INVENTORY,
  MEDIA,
  LOGISTICS,
  TASK_BOARD
}
