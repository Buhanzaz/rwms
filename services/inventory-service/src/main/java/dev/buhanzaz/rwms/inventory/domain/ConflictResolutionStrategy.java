package dev.buhanzaz.rwms.inventory.domain;

/**
 * Enumerates permitted conflict resolution strategy values in the inventory persistent workflow state.
 */
public enum ConflictResolutionStrategy {
  ACCEPT_REGISTRY,
  KEEP_INSPECTION
}
