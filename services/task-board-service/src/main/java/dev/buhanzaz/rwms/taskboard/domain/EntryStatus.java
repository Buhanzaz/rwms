package dev.buhanzaz.rwms.taskboard.domain;

/** Server-owned execution status of one ordered task route entry. */
public enum EntryStatus {
  WAITING,
  IN_PROGRESS,
  PAUSED,
  DONE,
  CANCELLED
}
