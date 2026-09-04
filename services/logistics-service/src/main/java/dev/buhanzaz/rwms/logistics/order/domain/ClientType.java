package dev.buhanzaz.rwms.logistics.order.domain;

/** Supported legal forms for a logistics rental client. */
public enum ClientType {
  INDIVIDUAL,
  SOLE_PROPRIETOR,
  LEGAL_ENTITY;

  /** Returns whether the client acts through a responsible contact person. */
  public boolean requiresContactPerson() {
    return this == SOLE_PROPRIETOR || this == LEGAL_ENTITY;
  }
}
