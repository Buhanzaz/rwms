package dev.buhanzaz.rwms.taskboard.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.WarehouseKpiSettings;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseKpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.service.WarehouseKpiClock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseKpiClockTest {
  private final UUID warehouseId = UUID.randomUUID();
  private final WarehouseKpiSettingsRepository settings = mock(WarehouseKpiSettingsRepository.class);
  private final KpiWorkScheduleRepository schedules = mock(KpiWorkScheduleRepository.class);
  private final WarehouseKpiClock clock = new WarehouseKpiClock(settings, schedules);

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
  }
}
