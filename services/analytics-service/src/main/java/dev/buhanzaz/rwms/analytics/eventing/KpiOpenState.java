package dev.buhanzaz.rwms.analytics.eventing;

/** Describes the source task-board open-state snapshot used when calculating as-of active and idle time. */
public enum KpiOpenState {
  WORKING,
  IDLE_GRACE,
  IDLE_PENALIZED,
  EXCLUDED
}
