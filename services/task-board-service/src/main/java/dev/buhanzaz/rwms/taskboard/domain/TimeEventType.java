package dev.buhanzaz.rwms.taskboard.domain;

/** Supported append-only task execution timing transitions. */
public enum TimeEventType {
  STARTED,
  PAUSED,
  RESUMED,
  FINISHED,
  CANCELLED,
  AUTO_INTERRUPTED,
  AUTO_RESUMED
}
