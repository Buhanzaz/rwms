package dev.buhanzaz.rwms.dossier.service;

import org.springframework.http.HttpStatus;

/** Signals a client-visible invalid dossier query without exposing persistence or authorization internals. */
public final class DossierQueryException extends RuntimeException {
  private final HttpStatus status;
  private final String code;

  public DossierQueryException(HttpStatus status, String code, String detail) {
    super(detail);
    this.status = status;
    this.code = code;
  }

  public HttpStatus status() {
    return status;
  }

  public String code() {
    return code;
  }
}
