package dev.buhanzaz.rwms.taskboard.domain;

/** Current open-work condition used to account KPI responsibility and idle penalties. */
public enum GroupKpiOpenState {
  WORKING,
  IDLE_GRACE,
  IDLE_PENALIZED,
  EXCLUDED
}
