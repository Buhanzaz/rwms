package dev.buhanzaz.rwms.taskboard.domain;

/** Participation rule for a worker class attached to a warehouse queue. */
public enum ParticipationPolicy {
  PRIMARY,
  REQUIRED,
  OPTIONAL
}
