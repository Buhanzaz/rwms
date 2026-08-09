package dev.buhanzaz.rwms.taskboard.domain;

/** Operational purpose of a task-board queue and its allowed routing behavior. */
public enum QueueType {
  MOVEMENT,
  REPAIR,
  HOLDING,
  FURNITURE_MOVEMENT
}
