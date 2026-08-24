package dev.buhanzaz.rwms.taskboard.domain;

/** Distinguishes executable route stages from manager-visible non-actionable future stages. */
public enum EntryType {
  REAL,
  SHADOW
}
