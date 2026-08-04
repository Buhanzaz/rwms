package dev.buhanzaz.rwms.inventory.domain;

/** Immutable outcome returned by maintenance for a final-plan reconciliation. */
public enum MaintenancePublicationOutcome {
  CREATED,
  SUCCESSOR,
  MATCHED
}
