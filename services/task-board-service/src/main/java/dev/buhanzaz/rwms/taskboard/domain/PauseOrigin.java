package dev.buhanzaz.rwms.taskboard.domain;

/** Identifies whether an entry pause was explicit or produced by task-board orchestration. */
public enum PauseOrigin {
  MANUAL,
  AUTO
}
