package dev.buhanzaz.rwms.dossier.domain;

public enum DossierReplayState {
  BUILDING,
  TAILING,
  VERIFYING,
  READY,
  ACTIVATED,
  REJECTED
}
