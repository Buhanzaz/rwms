package dev.buhanzaz.rwms.inventory.domain;

public enum MutationState {
  IDLE,
  SOURCE_CREATE_PENDING,
  SOURCE_CREATED,
  PLAN_RESOLVE_PENDING
}
