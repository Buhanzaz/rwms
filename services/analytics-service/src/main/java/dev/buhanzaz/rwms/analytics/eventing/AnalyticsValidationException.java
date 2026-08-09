package dev.buhanzaz.rwms.analytics.eventing;

/** Signals a non-retryable source-envelope violation that must be handled as a sanitized validation failure. */
public final class AnalyticsValidationException extends RuntimeException {
  public AnalyticsValidationException(String code) {
    super(code);
  }

  public AnalyticsValidationException(String code, Throwable cause) {
    super(code, cause);
  }
}
