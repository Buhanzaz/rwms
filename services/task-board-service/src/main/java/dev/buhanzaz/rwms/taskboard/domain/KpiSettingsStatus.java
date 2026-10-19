package dev.buhanzaz.rwms.taskboard.domain;

/** Publication lifecycle of warehouse KPI configuration revisions. */
public enum KpiSettingsStatus {
  UNCONFIGURED,
  DRAFT,
  SCHEDULED,
  ACTIVE
}
