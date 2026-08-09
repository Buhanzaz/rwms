package dev.buhanzaz.rwms.analytics.eventing;

/** Abstracts bounded retry delays so consumer recovery can be verified without waiting in tests. */
@FunctionalInterface
public interface AnalyticsRetryDelayer {
  void delay(long milliseconds);
}
