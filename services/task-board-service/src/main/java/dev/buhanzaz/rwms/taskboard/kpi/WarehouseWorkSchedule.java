package dev.buhanzaz.rwms.taskboard.kpi;

import java.time.DayOfWeek;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;

public final class WarehouseWorkSchedule {
  private final ZoneId zoneId;
  private final LocalTime shiftStart;
  private final LocalTime shiftEnd;
  private final Set<DayOfWeek> daysOff;
  private final List<WorkBreak> breaks;

  public WarehouseWorkSchedule(
      ZoneId zoneId,
      LocalTime shiftStart,
      LocalTime shiftEnd,
      Set<DayOfWeek> daysOff,
      List<WorkBreak> breaks) {
    this.zoneId = Objects.requireNonNull(zoneId, "Warehouse time zone is required");
    this.shiftStart = Objects.requireNonNull(shiftStart, "Shift start is required");
    this.shiftEnd = Objects.requireNonNull(shiftEnd, "Shift end is required");
    this.daysOff = Set.copyOf(Objects.requireNonNull(daysOff, "Days off are required"));
    if (!shiftStart.isBefore(shiftEnd)) {
      throw new IllegalArgumentException("Work shift must not cross midnight");
    }
    this.breaks = normalizeBreaks(Objects.requireNonNull(breaks, "Breaks are required"));
  }

  public ZoneId zoneId() {
    return zoneId;
  }

  public LocalTime shiftStart() {
    return shiftStart;
  }

  public LocalTime shiftEnd() {
    return shiftEnd;
  }

  public Set<DayOfWeek> daysOff() {
    return daysOff;
  }

  public List<WorkBreak> breaks() {
    return breaks;
  }

  public Duration countedDuration(Instant fromInclusive, Instant toExclusive) {
    Objects.requireNonNull(fromInclusive, "Interval start is required");
    Objects.requireNonNull(toExclusive, "Interval end is required");
    if (!fromInclusive.isBefore(toExclusive)) return Duration.ZERO;

    LocalDate first = fromInclusive.atZone(zoneId).toLocalDate();
    LocalDate last = toExclusive.minusNanos(1).atZone(zoneId).toLocalDate();
    long seconds = 0;
    for (LocalDate date = first; !date.isAfter(last); date = date.plusDays(1)) {
      if (daysOff.contains(date.getDayOfWeek())) continue;
      Instant workStart = instant(date, shiftStart);
      Instant workEnd = instant(date, shiftEnd);
      seconds += overlapSeconds(fromInclusive, toExclusive, workStart, workEnd);
      for (WorkBreak workBreak : breaks) {
        seconds -=
            overlapSeconds(
                fromInclusive,
                toExclusive,
                instant(date, workBreak.start()),
                instant(date, workBreak.end()));
      }
    }
    return Duration.ofSeconds(Math.max(0, seconds));
  }

  public boolean isAccountedAt(Instant instant) {
    LocalDate date = instant.atZone(zoneId).toLocalDate();
    if (daysOff.contains(date.getDayOfWeek())) return false;
    if (instant.isBefore(instant(date, shiftStart)) || !instant.isBefore(instant(date, shiftEnd))) {
      return false;
    }
    return breaks.stream()
        .noneMatch(
            workBreak ->
                !instant.isBefore(instant(date, workBreak.start()))
                    && instant.isBefore(instant(date, workBreak.end())));
  }

  public Optional<Instant> nextBoundaryAfter(Instant instant) {
    LocalDate startDate = instant.atZone(zoneId).toLocalDate();
    for (int offset = 0; offset <= 14; offset++) {
      LocalDate date = startDate.plusDays(offset);
      if (daysOff.contains(date.getDayOfWeek())) continue;
      List<Instant> boundaries = new ArrayList<>();
      boundaries.add(instant(date, shiftStart));
      boundaries.add(instant(date, shiftEnd));
      for (WorkBreak workBreak : breaks) {
        boundaries.add(instant(date, workBreak.start()));
        boundaries.add(instant(date, workBreak.end()));
      }
      Optional<Instant> result =
          boundaries.stream().filter(value -> value.isAfter(instant)).min(Comparator.naturalOrder());
      if (result.isPresent()) return result;
    }
    return Optional.empty();
  }

  private List<WorkBreak> normalizeBreaks(List<WorkBreak> source) {
    List<WorkBreak> sorted =
        source.stream().sorted(Comparator.comparing(WorkBreak::start)).toList();
    List<WorkBreak> normalized = new ArrayList<>();
    for (WorkBreak workBreak : sorted) {
      if (workBreak.start().isBefore(shiftStart) || workBreak.end().isAfter(shiftEnd)) {
        throw new IllegalArgumentException("Break must stay inside the work shift");
      }
      if (normalized.isEmpty()) {
        normalized.add(workBreak);
        continue;
      }
      WorkBreak previous = normalized.getLast();
      if (workBreak.start().isBefore(previous.end())) {
        throw new IllegalArgumentException("Work breaks must not overlap");
      }
      if (workBreak.start().equals(previous.end())) {
        normalized.set(
            normalized.size() - 1, new WorkBreak(previous.start(), workBreak.end()));
      } else {
        normalized.add(workBreak);
      }
    }
    return List.copyOf(normalized);
  }

  private Instant instant(LocalDate date, LocalTime time) {
    return date.atTime(time).atZone(zoneId).toInstant();
  }

  private static long overlapSeconds(
      Instant firstStart, Instant firstEnd, Instant secondStart, Instant secondEnd) {
    Instant start = firstStart.isAfter(secondStart) ? firstStart : secondStart;
    Instant end = firstEnd.isBefore(secondEnd) ? firstEnd : secondEnd;
    return start.isBefore(end) ? Duration.between(start, end).toSeconds() : 0;
  }
}
