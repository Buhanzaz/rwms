package dev.buhanzaz.rwms.taskboard.api;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Transport models for inventory-service's private effective object work-calendar read. */
public final class InventoryWorkCalendarApiModels {
  private InventoryWorkCalendarApiModels() {}

  public record WorkCalendarSnapshotResponse(
      UUID warehouseId,
      LocalDate from,
      LocalDate through,
      String calendarFingerprint,
      List<WorkCalendarDateResponse> dates) {}

  public record WorkCalendarDateResponse(
      LocalDate date,
      boolean working,
      String timeZone,
      OffsetDateTime timeZoneEffectiveFrom,
      UUID scheduleId,
      Long scheduleVersion,
      LocalDate scheduleEffectiveFrom) {}
}
