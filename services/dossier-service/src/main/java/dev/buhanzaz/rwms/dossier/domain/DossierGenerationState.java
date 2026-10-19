package dev.buhanzaz.rwms.dossier.domain;

/** Describes the lifecycle of a projection generation while it is built, verified, activated or rejected. */
public enum DossierGenerationState {
  BUILDING,
  READY,
  ACTIVE,
  REJECTED,
  RETIRED
}
