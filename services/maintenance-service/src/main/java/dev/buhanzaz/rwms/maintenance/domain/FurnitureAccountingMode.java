package dev.buhanzaz.rwms.maintenance.domain;

/**
 * Determines whether furniture selected in an estimate is represented by a verified cabin
 * contents balance or by a separately approved unaccounted-loss decision.
 */
public enum FurnitureAccountingMode {
  TRACKED_CABIN_CONTENTS,
  UNACCOUNTED_CABIN_CONTENTS
}
