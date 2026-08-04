package dev.buhanzaz.rwms.maintenance.disposition.domain;

/** The caller's aggregate or recovery fence is stale. */
public final class PropertyDispositionVersionConflictException
    extends PropertyDispositionConflictException {
  public PropertyDispositionVersionConflictException(String message) {
    super(message);
  }
}
