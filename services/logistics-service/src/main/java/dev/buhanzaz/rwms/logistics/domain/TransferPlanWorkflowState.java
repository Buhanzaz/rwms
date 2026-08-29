package dev.buhanzaz.rwms.logistics.domain;

/**
 * Durable progress of the cross-service reservation and resource-assignment workflow attached to
 * one confirmed transfer plan.
 */
public enum TransferPlanWorkflowState {
  NOT_STARTED,
  RESERVING,
  READY,
  IN_TRANSIT,
  COMPLETING,
  COMPLETED,
  RELEASING,
  RELEASED,
  CONFLICT,
  RECONCILIATION_REQUIRED
}
