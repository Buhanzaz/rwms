package dev.buhanzaz.rwms.analytics.eventing;

public final class AnalyticsValidationException extends RuntimeException {
  public AnalyticsValidationException(String code) {
    super(code);
  }

  public AnalyticsValidationException(String code, Throwable cause) {
    super(code, cause);
  }
}
