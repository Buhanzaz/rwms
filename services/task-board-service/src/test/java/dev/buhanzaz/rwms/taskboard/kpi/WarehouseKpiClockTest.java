package dev.buhanzaz.rwms.taskboard.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.WarehouseKpiSettings;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseKpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.service.WarehouseKpiClock;
import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseKpiClockTest {
  private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

  private final UUID warehouseId = UUID.randomUUID();
  private final WarehouseKpiSettingsRepository settings = mock(WarehouseKpiSettingsRepository.class);
  private final KpiWorkScheduleRepository schedules = mock(KpiWorkScheduleRepository.class);
  private final WarehouseTimeZoneGateway timeZones = mock(WarehouseTimeZoneGateway.class);
  private final WarehouseKpiClock clock = new WarehouseKpiClock(settings, schedules, timeZones);

  @Test
  void retainsWallClockBeforeFirstEffectiveDayAndExcludesBreakAfterIt() {
    configuredSchedule();

    assertThat(
            clock.countedSeconds(
                warehouseId,
                Instant.parse("2026-07-30T06:00:00Z"),
                Instant.parse("2026-07-30T07:00:00Z")))
        .isEqualTo(3600);
    assertThat(
            clock.countedSeconds(
                warehouseId,
                Instant.parse("2026-07-31T08:30:00Z"),
                Instant.parse("2026-07-31T10:30:00Z")))
        .isEqualTo(3600);
  }

  @Test
  void reportsBreakAndItsExactNextBoundary() {
    configuredSchedule();

    var moment = clock.moment(warehouseId, Instant.parse("2026-07-31T09:30:00Z"));

    assertThat(moment.state()).isEqualTo(WarehouseKpiClock.ScheduleState.BREAK);
    assertThat(moment.nextTransitionAt())
        .isEqualTo(OffsetDateTime.parse("2026-07-31T10:00:00Z"));
  }

  @Test
  void resolvesLocalDateAtTheOperationMomentInsteadOfCurrentWarehouseZone() {
    WarehouseKpiSettings warehouse = WarehouseKpiSettings.create(warehouseId, "Europe/Samara");
    when(settings.findByWarehouseId(warehouseId)).thenReturn(Optional.of(warehouse));
    Instant change = Instant.parse("2026-09-01T20:00:00Z");
    when(timeZones.timeZoneAt(warehouseId, Instant.parse("2026-09-01T19:30:00Z")))
        .thenReturn(new WarehouseTimeZoneGateway.TimeZoneDecision(MOSCOW, Instant.EPOCH));
    when(timeZones.timeZoneAt(warehouseId, Instant.parse("2026-09-01T20:30:00Z")))
        .thenReturn(
            new WarehouseTimeZoneGateway.TimeZoneDecision(ZoneId.of("Europe/Samara"), change));

    assertThat(clock.localDate(warehouseId, Instant.parse("2026-09-01T19:30:00Z")))
        .isEqualTo(LocalDate.of(2026, 9, 1));
    assertThat(clock.localDate(warehouseId, Instant.parse("2026-09-01T20:30:00Z")))
        .isEqualTo(LocalDate.of(2026, 9, 2));
  }

  @Test
  void splitsCountedIntervalAtTimezoneDecisionWithoutRecomputingPastCalendarTime() {
    WarehouseKpiSettings warehouse = WarehouseKpiSettings.create(warehouseId, "Europe/Samara");
    when(settings.findByWarehouseId(warehouseId)).thenReturn(Optional.of(warehouse));
    var schedule =
        new KpiWorkScheduleRevision(
            warehouseId,
            LocalDate.of(2026, 9, 1),
            LocalTime.MIDNIGHT,
            LocalTime.of(1, 0),
            List.of(),
            List.of());
    schedule.schedule();
    when(schedules.findAllByWarehouseIdAndScheduledTrueOrderByEffectiveFromAsc(warehouseId))
        .thenReturn(List.of(schedule));
    Instant from = Instant.parse("2026-09-01T20:00:00Z");
    Instant decision = Instant.parse("2026-09-01T20:30:00Z");
    Instant to = Instant.parse("2026-09-01T21:00:00Z");
    when(timeZones.timeline(warehouseId, from, to))
        .thenReturn(
            List.of(
                new WarehouseTimeZoneGateway.TimeZoneSegment(MOSCOW, from, decision),
                new WarehouseTimeZoneGateway.TimeZoneSegment(
                    ZoneId.of("Europe/Samara"), decision, to)));

    assertThat(clock.countedSeconds(warehouseId, from, to)).isEqualTo(1_800);
  }

  @Test
  void exposesTimezoneChangeAsTheNextKpiCalendarTransition() {
    WarehouseKpiSettings warehouse = WarehouseKpiSettings.create(warehouseId, "Europe/Samara");
    when(settings.findByWarehouseId(warehouseId)).thenReturn(Optional.of(warehouse));
    var schedule =
        new KpiWorkScheduleRevision(
            warehouseId,
            LocalDate.of(2026, 9, 1),
            LocalTime.MIDNIGHT,
            LocalTime.of(1, 0),
            List.of(),
            List.of());
    schedule.schedule();
    when(schedules.findAllByWarehouseIdAndScheduledTrueOrderByEffectiveFromAsc(warehouseId))
        .thenReturn(List.of(schedule));
    Instant at = Instant.parse("2026-09-01T20:20:00Z");
    Instant decision = Instant.parse("2026-09-01T20:30:00Z");
    when(timeZones.timeline(warehouseId, at, WarehouseTimeZoneGateway.FAR_FUTURE))
        .thenReturn(
            List.of(
                new WarehouseTimeZoneGateway.TimeZoneSegment(MOSCOW, at, decision),
                new WarehouseTimeZoneGateway.TimeZoneSegment(
                    ZoneId.of("Europe/Samara"),
                    decision,
                    WarehouseTimeZoneGateway.FAR_FUTURE)));

    var moment = clock.moment(warehouseId, at);

    assertThat(moment.state()).isEqualTo(WarehouseKpiClock.ScheduleState.OFF_SHIFT);
    assertThat(moment.nextTransitionAt()).isEqualTo(OffsetDateTime.parse("2026-09-01T20:30:00Z"));
  }

  private void configuredSchedule() {
    when(settings.findByWarehouseId(warehouseId))
        .thenReturn(Optional.of(WarehouseKpiSettings.create(warehouseId, "Europe/Moscow")));
    var schedule =
        new KpiWorkScheduleRevision(
            warehouseId,
            LocalDate.of(2026, 7, 31),
            LocalTime.of(9, 0),
            LocalTime.of(18, 0),
            List.of(6, 7),
            List.of(
                new KpiWorkBreakInterval(LocalTime.of(12, 0), LocalTime.of(13, 0))));
    schedule.schedule();
    when(schedules.findAllByWarehouseIdAndScheduledTrueOrderByEffectiveFromAsc(warehouseId))
        .thenReturn(List.of(schedule));
    when(timeZones.timeline(eq(warehouseId), any(Instant.class), any(Instant.class)))
        .thenAnswer(
            invocation -> {
              Instant from = invocation.getArgument(1);
              Instant to = invocation.getArgument(2);
              return List.of(new WarehouseTimeZoneGateway.TimeZoneSegment(MOSCOW, from, to));
            });
  }
}
