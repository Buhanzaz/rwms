package dev.buhanzaz.rwms.inventory.domain;

/**
 * Enumerates permitted mutation state values in the inventory persistent workflow state.
 */
public enum MutationState {
  IDLE,
  SOURCE_CREATE_PENDING,
  SOURCE_CREATED,
  PLAN_RESOLVE_PENDING
}
