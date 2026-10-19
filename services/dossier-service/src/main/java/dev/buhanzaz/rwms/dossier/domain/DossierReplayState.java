package dev.buhanzaz.rwms.dossier.domain;

/** Defines the ordered phases of a replay run and guards activation of incomplete generations. */
public enum DossierReplayState {
  BUILDING,
  TAILING,
  VERIFYING,
  READY,
  ACTIVATED,
  REJECTED
}
