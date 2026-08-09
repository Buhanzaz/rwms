package dev.buhanzaz.rwms.taskboard.domain;

/** Classifies a queue entry as ordinary routed work or a terminal holding entry. */
public enum EntryType {
  REAL,
  SHADOW
}
