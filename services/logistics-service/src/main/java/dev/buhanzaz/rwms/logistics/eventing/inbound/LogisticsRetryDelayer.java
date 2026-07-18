package dev.buhanzaz.rwms.logistics.eventing.inbound;

@FunctionalInterface
public interface LogisticsRetryDelayer {
  void delay(long durationMillis);
}
