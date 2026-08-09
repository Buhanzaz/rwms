package dev.buhanzaz.rwms.taskboard.domain;

/** Visible state of a worker credential workflow whose secret material remains auth-owned. */
public enum CredentialStatus {
  NOT_CONFIGURED,
  PENDING,
  ACTIVE,
  DISABLED,
  ERROR
}
