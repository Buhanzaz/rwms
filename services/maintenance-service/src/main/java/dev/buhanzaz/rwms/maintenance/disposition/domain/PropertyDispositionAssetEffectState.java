package dev.buhanzaz.rwms.maintenance.disposition.domain;

/** State of the separately executed asset-service effect. */
public enum PropertyDispositionAssetEffectState {
  NOT_STARTED,
  PENDING,
  APPLIED,
  QUARANTINED
}
