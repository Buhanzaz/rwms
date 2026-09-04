package dev.buhanzaz.rwms.taskboard.domain;

/** Durable active/tombstone state of one task inside its immutable planner lineage. */
public enum PlannerMembershipState {
  ACTIVE,
  REMOVED
}
