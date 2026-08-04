package dev.buhanzaz.rwms.inventory.domain;

/** Manager decision for a maintenance collision discovered by final-plan preflight. */
public enum FinalPlanReconciliationStrategy {
  CREATE,
  REPLACE,
  MERGE
}
