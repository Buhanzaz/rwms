package dev.buhanzaz.rwms.maintenance.disposition.domain;

/** A state transition violates the disposition aggregate's lifecycle. */
public class PropertyDispositionConflictException extends IllegalStateException {
  public PropertyDispositionConflictException(String message) {
    super(message);
  }
}
