package dev.buhanzaz.rwms.maintenance.disposition.domain;

/** The workflow that supplied the evidence for a disposition decision. */
public enum PropertyDispositionSource {
  MANUAL,
  REPAIR,
  ESTIMATE,
  UNACCOUNTED,
  INVENTORY
}
