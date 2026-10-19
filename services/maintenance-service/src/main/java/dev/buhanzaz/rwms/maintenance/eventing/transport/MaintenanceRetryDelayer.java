package dev.buhanzaz.rwms.maintenance.eventing.transport;

/** Schedules bounded delay before a maintenance inbound retry. */
@FunctionalInterface
public interface MaintenanceRetryDelayer {
  void delay(long durationMillis);
}
