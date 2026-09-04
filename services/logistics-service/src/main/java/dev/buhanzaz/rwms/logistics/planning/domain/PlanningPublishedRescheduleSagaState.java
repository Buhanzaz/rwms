package dev.buhanzaz.rwms.logistics.planning.domain;

/** Durable recovery checkpoint for the cross-owner published-plan reschedule protocol. */
public enum PlanningPublishedRescheduleSagaState {
  PENDING,
  PREPARED,
  OWNER_COMMITTED,
  BOARD_COMMITTED,
  COMPLETE,
  RELEASE_PENDING,
  RELEASED,
  QUARANTINED
}
