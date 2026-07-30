package dev.buhanzaz.rwms.taskboard.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class WarehouseWorkScheduleTest {
  private static final ZoneId MOSCOW = ZoneId.of("Europe/Moscow");

  @Test
  void excludesConfiguredBreakFromCountedTime() {
    WarehouseWorkSchedule schedule =
        schedule(
            Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            List.of(new WorkBreak(LocalTime.of(11, 0), LocalTime.of(11, 15))));

    Duration counted =
        schedule.countedDuration(
            instant(2026, 7, 30, 10, 50), instant(2026, 7, 30, 11, 20));

    assertThat(counted).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void excludesWeekendsAndOutsideShiftAcrossSeveralDays() {
    WarehouseWorkSchedule schedule =
        schedule(
            Set.of(DayOfWeek.SATURDAY, DayOfWeek.SUNDAY),
            List.of(new WorkBreak(LocalTime.NOON, LocalTime.of(13, 0))));

    Duration counted =
        schedule.countedDuration(
            instant(2026, 7, 31, 17, 30), instant(2026, 8, 3, 9, 30));

    assertThat(counted).isEqualTo(Duration.ofHours(1));
  }

  @Test
  void mergesAdjacentBreaksButRejectsOverlaps() {
    WarehouseWorkSchedule adjacent =
        schedule(
            Set.of(),
            List.of(
                new WorkBreak(LocalTime.of(11, 0), LocalTime.of(11, 15)),
                new WorkBreak(LocalTime.of(11, 15), LocalTime.NOON)));

    assertThat(adjacent.breaks())
        .containsExactly(new WorkBreak(LocalTime.of(11, 0), LocalTime.NOON));

    assertThatThrownBy(
            () ->
                schedule(
                    Set.of(),
                    List.of(
                        new WorkBreak(LocalTime.of(11, 0), LocalTime.NOON),
                        new WorkBreak(LocalTime.of(11, 30), LocalTime.of(12, 30)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("overlap");
  }

  @Test
  void rejectsOvernightShiftAndBreakOutsideShift() {
    assertThatThrownBy(
            () ->
                new WarehouseWorkSchedule(
                    MOSCOW,
                    LocalTime.of(18, 0),
                    LocalTime.of(9, 0),
                    Set.of(),
                    List.of()))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("midnight");

    assertThatThrownBy(
            () ->
                schedule(
                    Set.of(),
                    List.of(new WorkBreak(LocalTime.of(8, 0), LocalTime.of(9, 30)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shift");
  }

  @Test
  void reportsNextScheduleBoundary() {
    WarehouseWorkSchedule schedule =
        schedule(
            Set.of(),
            List.of(new WorkBreak(LocalTime.of(11, 0), LocalTime.of(11, 15))));

    assertThat(schedule.nextBoundaryAfter(instant(2026, 7, 30, 10, 50)))
        .contains(instant(2026, 7, 30, 11, 0));
    assertThat(schedule.nextBoundaryAfter(instant(2026, 7, 30, 11, 5)))
        .contains(instant(2026, 7, 30, 11, 15));
  }

  private static WarehouseWorkSchedule schedule(
      Set<DayOfWeek> daysOff, List<WorkBreak> breaks) {
    return new WarehouseWorkSchedule(
        MOSCOW, LocalTime.of(9, 0), LocalTime.of(18, 0), daysOff, breaks);
  }

  private static Instant instant(int year, int month, int day, int hour, int minute) {
    return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, MOSCOW).toInstant();
  }
}
