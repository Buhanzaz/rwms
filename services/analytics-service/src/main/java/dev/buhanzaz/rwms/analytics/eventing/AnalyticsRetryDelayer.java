package dev.buhanzaz.rwms.analytics.eventing;

@FunctionalInterface
public interface AnalyticsRetryDelayer {
  void delay(long milliseconds);
}
