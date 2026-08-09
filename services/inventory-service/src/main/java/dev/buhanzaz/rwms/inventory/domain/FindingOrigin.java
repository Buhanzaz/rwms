package dev.buhanzaz.rwms.inventory.domain;

/**
 * Enumerates permitted finding origin values in the inventory persistent workflow state.
 */
public enum FindingOrigin {
  EXPECTED,
  ADDED_NEW,
  ADDED_USED,
  UNEXPECTED_EXISTING
}
