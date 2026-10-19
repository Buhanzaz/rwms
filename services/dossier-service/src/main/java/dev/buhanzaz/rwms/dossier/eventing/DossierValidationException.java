package dev.buhanzaz.rwms.dossier.eventing;

/** Permanent source failure. Messages are fixed codes and never echo source values. */
public final class DossierValidationException extends RuntimeException {
  public DossierValidationException(String failureCode) {
    super(failureCode);
  }

  public DossierValidationException(String failureCode, Throwable cause) {
    super(failureCode, cause);
  }
}
