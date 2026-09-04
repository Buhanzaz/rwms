package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.repository.KpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseKpiClockInventoryCalendarTest {

  @Test
  void resolvesEffectiveDaysOffTimezoneAndScheduleVersionIntoStableEvidence() {
    UUID warehouseId = UUID.randomUUID();
    KpiWorkScheduleRepository schedules = mock(KpiWorkScheduleRepository.class);
    WarehouseTimeZoneGateway timeZones = mock(WarehouseTimeZoneGateway.class);
    KpiWorkScheduleRevision schedule = mock(KpiWorkScheduleRevision.class);
    UUID scheduleId = UUID.randomUUID();
    LocalDate monday = LocalDate.of(2026, 9, 7);
    when(schedule.getId()).thenReturn(scheduleId);
    when(schedule.getVersion()).thenReturn(5L);
    when(schedule.getEffectiveFrom()).thenReturn(monday);
    when(schedule.getDaysOff()).thenReturn(List.of(1));
    when(schedules.findAllGlobalScheduledOrderByEffectiveFromAsc())
        .thenReturn(List.of(schedule));
    when(timeZones.timeZoneAt(eq(warehouseId), any(Instant.class)))
        .thenAnswer(
            invocation -> {
              Instant instant = invocation.getArgument(1, Instant.class);
              return instant.isBefore(Instant.parse("2026-09-08T00:00:00Z"))
                  ? new WarehouseTimeZoneGateway.TimeZoneDecision(ZoneId.of("Europe/Moscow"), Instant.EPOCH)
                  : new WarehouseTimeZoneGateway.TimeZoneDecision(
                      ZoneId.of("Asia/Yekaterinburg"), Instant.parse("2026-09-08T00:00:00Z"));
            });
    WarehouseKpiClock clock = clock(schedules, timeZones);

    WarehouseKpiClock.WorkCalendarSnapshot snapshot =
        clock.workCalendarSnapshot(warehouseId, monday, monday.plusDays(1));

    assertThat(snapshot.dates())
        .extracting(
            WarehouseKpiClock.WorkCalendarDate::date,
            WarehouseKpiClock.WorkCalendarDate::working,
            WarehouseKpiClock.WorkCalendarDate::timeZone,
            WarehouseKpiClock.WorkCalendarDate::scheduleId,
            WarehouseKpiClock.WorkCalendarDate::scheduleVersion)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(monday, false, "Europe/Moscow", scheduleId, 5L),
            org.assertj.core.groups.Tuple.tuple(
                monday.plusDays(1), true, "Asia/Yekaterinburg", scheduleId, 5L));
    assertThat(snapshot.dates().get(1).timeZoneEffectiveFrom())
        .isEqualTo(java.time.OffsetDateTime.parse("2026-09-08T00:00:00Z"));

    when(schedule.getVersion()).thenReturn(6L);
    assertThat(clock.workCalendarSnapshot(warehouseId, monday, monday.plusDays(1)).calendarFingerprint())
        .isNotEqualTo(snapshot.calendarFingerprint());
  }

  @Test
  void dateBeforeAnyActivatedScheduleIsExplicitlyUnavailable() {
    UUID warehouseId = UUID.randomUUID();
    KpiWorkScheduleRepository schedules = mock(KpiWorkScheduleRepository.class);
    WarehouseTimeZoneGateway timeZones = mock(WarehouseTimeZoneGateway.class);
    when(schedules.findAllGlobalScheduledOrderByEffectiveFromAsc())
        .thenReturn(List.of());
    when(timeZones.timeZoneAt(eq(warehouseId), any(Instant.class)))
        .thenReturn(new WarehouseTimeZoneGateway.TimeZoneDecision(ZoneId.of("UTC"), Instant.EPOCH));
    WarehouseKpiClock clock = clock(schedules, timeZones);

    WarehouseKpiClock.WorkCalendarDate date =
        clock.workCalendarSnapshot(
                warehouseId, LocalDate.of(2026, 9, 7), LocalDate.of(2026, 9, 7))
            .dates()
            .getFirst();

    assertThat(date.working()).isFalse();
    assertThat(date.scheduleId()).isNull();
    assertThat(date.scheduleVersion()).isNull();
  }

  private static WarehouseKpiClock clock(
      KpiWorkScheduleRepository schedules, WarehouseTimeZoneGateway timeZones) {
    return new WarehouseKpiClock(mock(KpiSettingsRepository.class), schedules, timeZones);
  }
}
