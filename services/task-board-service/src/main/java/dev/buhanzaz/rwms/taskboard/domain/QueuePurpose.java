package dev.buhanzaz.rwms.taskboard.domain;

/**
 * Stable business purpose of a global queue definition.
 *
 * <p>The purpose is deliberately independent from the mutable display name and
 * the broad visual queue type.
 */
public enum QueuePurpose {
  GENERAL,
  LOGISTICS_DRIVER
}
