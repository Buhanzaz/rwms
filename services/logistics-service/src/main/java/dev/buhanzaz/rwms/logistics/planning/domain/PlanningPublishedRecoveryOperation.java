package dev.buhanzaz.rwms.logistics.planning.domain;

/** Owner checkpoint performed before one published plan member is tombstoned. */
public enum PlanningPublishedRecoveryOperation {
  RESCHEDULE,
  CANCELLATION
}
