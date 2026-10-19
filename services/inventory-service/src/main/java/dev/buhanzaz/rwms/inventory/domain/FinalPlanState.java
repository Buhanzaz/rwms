package dev.buhanzaz.rwms.inventory.domain;

/** The current usability of the latest server-owned final plan for one inventory session. */
public enum FinalPlanState {
  DRAFT,
  STALE,
  COMPLETED
}
