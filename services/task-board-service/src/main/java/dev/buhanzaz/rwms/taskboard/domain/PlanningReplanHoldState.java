package dev.buhanzaz.rwms.taskboard.domain;

/** Durable lifecycle of a source-plan execution hold used by cross-date rescheduling. */
public enum PlanningReplanHoldState {
  PREPARED,
  COMMITTED,
  RELEASED
}
