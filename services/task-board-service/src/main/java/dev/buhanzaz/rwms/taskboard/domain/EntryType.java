package dev.buhanzaz.rwms.taskboard.domain;

/** Distinguishes the one executable route stage from its non-actionable future stages. */
public enum EntryType {
  REAL,
  SHADOW
}
