package dev.buhanzaz.rwms.maintenance.eventing.transport;

@FunctionalInterface
public interface MaintenanceRetryDelayer {
  void delay(long durationMillis);
}
