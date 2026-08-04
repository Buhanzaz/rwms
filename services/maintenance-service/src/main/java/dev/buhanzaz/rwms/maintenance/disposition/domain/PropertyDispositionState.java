package dev.buhanzaz.rwms.maintenance.disposition.domain;

/** Overall lifecycle of one root-asset disposition decision. */
public enum PropertyDispositionState {
  PENDING_APPROVAL,
  APPROVED,
  MOVEMENT_PENDING,
  EFFECT_PENDING,
  EFFECTIVE,
  REJECTED,
  QUARANTINED
}
