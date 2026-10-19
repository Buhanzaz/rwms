package dev.buhanzaz.rwms.logistics.eventing.inbound;

/**
 * Defines the delay boundary used by inbound logistics retries after recoverable delivery failures.
 */
@FunctionalInterface
public interface LogisticsRetryDelayer {
  void delay(long durationMillis);
}
