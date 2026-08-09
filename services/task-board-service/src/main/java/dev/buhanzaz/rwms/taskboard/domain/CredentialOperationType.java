package dev.buhanzaz.rwms.taskboard.domain;

/** Remote auth-service effect currently being reconciled for a worker credential. */
public enum CredentialOperationType {
  CONFIGURE,
  RESET,
  DISABLE,
  CLEAR,
  RECONCILE_DISABLE
}
