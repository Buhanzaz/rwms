package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettings;
import dev.buhanzaz.rwms.taskboard.kpi.WarehouseWorkSchedule;
import dev.buhanzaz.rwms.taskboard.kpi.WorkBreak;
import dev.buhanzaz.rwms.taskboard.repository.KpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Applies the shared weekly schedule to warehouse-local operational timers.
 *
 * <p>Before the first activated schedule takes effect, existing task timers retain their historic
 * wall-clock behaviour. Starting at {@code effectiveFrom}, only shift time outside configured
 * breaks is counted. All scheduled revisions are retained so an active task crossing a future
 * schedule change is split against the correct revision.
 */
@Service
public class WarehouseKpiClock {
  private final KpiSettingsRepository settings;
  private final KpiWorkScheduleRepository schedules;
  private final WarehouseTimeZoneGateway timeZones;

  public WarehouseKpiClock(
      KpiSettingsRepository settings,
      KpiWorkScheduleRepository schedules,
      WarehouseTimeZoneGateway timeZones) {
    this.settings = settings;
    this.schedules = schedules;
    this.timeZones = timeZones;
  }

  @Transactional(readOnly = true)
  public long countedSeconds(UUID warehouseId, Instant fromInclusive, Instant toExclusive) {
    if (!fromInclusive.isBefore(toExclusive)) return 0;
    ScheduleContext context = context(warehouseId);
    if (context == null || context.revisions().isEmpty()) {
      return Duration.between(fromInclusive, toExclusive).toSeconds();
    }

    long total = 0;
    for (WarehouseTimeZoneGateway.TimeZoneSegment segment :
        timeZones.timeline(warehouseId, fromInclusive, toExclusive)) {
      total = Math.addExact(total, countedSeconds(context, segment));
    }
    return total;
  }

  @Transactional(readOnly = true)
  public ScheduleMoment moment(UUID warehouseId, Instant instant) {
    ScheduleContext context = context(warehouseId);
    if (context == null || context.revisions().isEmpty()) {
      return new ScheduleMoment(ScheduleState.WORKING, null, null);
    }

    List<WarehouseTimeZoneGateway.TimeZoneSegment> zoneTimeline =
        timeZones.timeline(warehouseId, instant, WarehouseTimeZoneGateway.FAR_FUTURE);
    WarehouseTimeZoneGateway.TimeZoneSegment activeZone = zoneTimeline.getFirst();
    ZoneId zone = activeZone.timeZone();
    LocalDate date = instant.atZone(zone).toLocalDate();
    KpiWorkScheduleRevision revision = revisionAt(context.revisions(), date);
    Instant nextZoneChange =
        activeZone.toExclusive().equals(WarehouseTimeZoneGateway.FAR_FUTURE)
            ? null
            : activeZone.toExclusive();
    if (revision == null) {
      Instant firstEffective =
          context.revisions().getFirst().getEffectiveFrom().atStartOfDay(zone).toInstant();
      return new ScheduleMoment(
          ScheduleState.WORKING,
          firstEffective.isAfter(instant)
              ? atOffset(earliest(firstEffective, nextZoneChange))
              : nextZoneChange == null ? null : atOffset(nextZoneChange),
          null);
    }

    WarehouseWorkSchedule schedule = schedule(zone, revision);
    ScheduleState state;
    if (schedule.isAccountedAt(instant)) {
      state = ScheduleState.WORKING;
    } else {
      var local = instant.atZone(zone);
      boolean insideBreak =
          !schedule.daysOff().contains(local.getDayOfWeek())
              && schedule.breaks().stream()
                  .anyMatch(
                      value ->
                          !local.toLocalTime().isBefore(value.start())
                              && local.toLocalTime().isBefore(value.end()));
      state = insideBreak ? ScheduleState.BREAK : ScheduleState.OFF_SHIFT;
    }

    Instant nextBoundary = schedule.nextBoundaryAfter(instant).orElse(null);
    Instant nextRevision =
        context.revisions().stream()
            .filter(value -> value.getEffectiveFrom().isAfter(date))
            .map(value -> value.getEffectiveFrom().atStartOfDay(zone).toInstant())
            .filter(value -> value.isAfter(instant))
            .min(Comparator.naturalOrder())
            .orElse(null);
    Instant next = earliest(nextBoundary, nextRevision, nextZoneChange);
    return new ScheduleMoment(
        state, next == null ? null : atOffset(next), context.dataAvailableFrom());
  }

  @Transactional(readOnly = true)
  public Optional<LocalDate> dataAvailableFrom(UUID warehouseId) {
    return globalSettings().map(KpiSettings::getDataAvailableFrom);
  }

  @Transactional(readOnly = true)
  public LocalDate localDate(UUID warehouseId, Instant instant) {
    return instant.atZone(timeZones.timeZoneAt(warehouseId, instant).timeZone()).toLocalDate();
  }

  /**
   * Resolves the effective object work calendar for a bounded local-date range.
   *
   * <p>This deliberately exposes only the day-level availability needed by inventory planning.
   * Shift and break details remain task-board owned. A date before the first activated schedule is
   * unavailable rather than inheriting the historic timer fallback: inventory must never invent a
   * working-day policy where none has been configured.
   */
  @Transactional(readOnly = true)
  public WorkCalendarSnapshot workCalendarSnapshot(
      UUID warehouseId, LocalDate from, LocalDate through) {
    if (warehouseId == null || from == null || through == null || through.isBefore(from)) {
      throw new IllegalArgumentException("Work-calendar range is invalid");
    }
    if (from.plusDays(365).isBefore(through)) {
      throw new IllegalArgumentException("Work-calendar range must not exceed 366 days");
    }
    List<KpiWorkScheduleRevision> revisions =
        schedules.findAllGlobalScheduledOrderByEffectiveFromAsc();
    List<WorkCalendarDate> dates = new ArrayList<>();
    for (LocalDate date = from; !date.isAfter(through); date = date.plusDays(1)) {
      WarehouseTimeZoneGateway.TimeZoneDecision zone =
          timeZones.timeZoneAt(warehouseId, date.atStartOfDay(ZoneOffset.UTC).toInstant());
      KpiWorkScheduleRevision revision = revisionAt(revisions, date);
      boolean working =
          revision != null && !revision.getDaysOff().contains(date.getDayOfWeek().getValue());
      dates.add(
          new WorkCalendarDate(
              date,
              working,
              zone.timeZone().getId(),
              atOffset(zone.effectiveFrom()),
              revision == null ? null : revision.getId(),
              revision == null ? null : revision.getVersion(),
              revision == null ? null : revision.getEffectiveFrom()));
    }
    return new WorkCalendarSnapshot(
        warehouseId, from, through, calendarFingerprint(warehouseId, from, through, dates), dates);
  }

  private ScheduleContext context(UUID warehouseId) {
    KpiSettings globalSettings = globalSettings().orElse(null);
    if (globalSettings == null) return null;
    return new ScheduleContext(
        globalSettings.getDataAvailableFrom(),
        schedules.findAllGlobalScheduledOrderByEffectiveFromAsc());
  }

  private Optional<KpiSettings> globalSettings() {
    return settings.findById(KpiSettings.SINGLETON_ID);
  }

  private long countedSeconds(
      ScheduleContext context, WarehouseTimeZoneGateway.TimeZoneSegment segment) {
    ZoneId zone = segment.timeZone();
    LocalDate firstDate = segment.fromInclusive().atZone(zone).toLocalDate();
    LocalDate lastDate = segment.toExclusive().minusNanos(1).atZone(zone).toLocalDate();
    long total = 0;
    for (LocalDate date = firstDate; !date.isAfter(lastDate); date = date.plusDays(1)) {
      Instant dayStart = date.atStartOfDay(zone).toInstant();
      Instant dayEnd = date.plusDays(1).atStartOfDay(zone).toInstant();
      Instant sliceStart =
          segment.fromInclusive().isAfter(dayStart) ? segment.fromInclusive() : dayStart;
      Instant sliceEnd = segment.toExclusive().isBefore(dayEnd) ? segment.toExclusive() : dayEnd;
      KpiWorkScheduleRevision revision = revisionAt(context.revisions(), date);
      total =
          Math.addExact(
              total,
              revision == null
                  ? Duration.between(sliceStart, sliceEnd).toSeconds()
                  : schedule(zone, revision).countedDuration(sliceStart, sliceEnd).toSeconds());
    }
    return total;
  }

  private static Instant earliest(Instant... values) {
    Instant result = null;
    for (Instant value : values) {
      if (value != null && (result == null || value.isBefore(result))) {
        result = value;
      }
    }
    return result;
  }

  private static KpiWorkScheduleRevision revisionAt(
      List<KpiWorkScheduleRevision> revisions, LocalDate date) {
    KpiWorkScheduleRevision result = null;
    for (KpiWorkScheduleRevision candidate : revisions) {
      if (candidate.getEffectiveFrom().isAfter(date)) break;
      result = candidate;
    }
    return result;
  }

  private static WarehouseWorkSchedule schedule(
      ZoneId zone, KpiWorkScheduleRevision revision) {
    Set<java.time.DayOfWeek> daysOff =
        revision.getDaysOff().stream()
            .map(java.time.DayOfWeek::of)
            .collect(Collectors.toUnmodifiableSet());
    List<WorkBreak> breaks =
        revision.getBreaks().stream()
            .map(value -> new WorkBreak(value.getStart(), value.getEnd()))
            .toList();
    return new WarehouseWorkSchedule(
        zone, revision.getShiftStart(), revision.getShiftEnd(), daysOff, breaks);
  }

  private static java.time.OffsetDateTime atOffset(Instant value) {
    return java.time.OffsetDateTime.ofInstant(value, ZoneOffset.UTC);
  }

  private static String calendarFingerprint(
      UUID warehouseId, LocalDate from, LocalDate through, List<WorkCalendarDate> dates) {
    StringBuilder canonical =
        new StringBuilder("rwms:task-board:inventory-work-calendar:v1\n")
            .append(warehouseId)
            .append('\n')
            .append(from)
            .append('\n')
            .append(through)
            .append('\n');
    for (WorkCalendarDate date : dates) {
      canonical
          .append(date.date())
          .append('|')
          .append(date.working())
          .append('|')
          .append(date.timeZone())
          .append('|')
          .append(date.timeZoneEffectiveFrom())
          .append('|')
          .append(date.scheduleId())
          .append('|')
          .append(date.scheduleVersion())
          .append('|')
          .append(date.scheduleEffectiveFrom())
          .append('\n');
    }
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  public enum ScheduleState {
    WORKING,
    BREAK,
    OFF_SHIFT
  }

  public record ScheduleMoment(
      ScheduleState state, java.time.OffsetDateTime nextTransitionAt, LocalDate dataAvailableFrom) {}

  /** Immutable private inventory calendar evidence for one exact warehouse-local date range. */
  public record WorkCalendarSnapshot(
      UUID warehouseId,
      LocalDate from,
      LocalDate through,
      String calendarFingerprint,
      List<WorkCalendarDate> dates) {
    public WorkCalendarSnapshot {
      dates = List.copyOf(dates);
    }
  }

  /** One resolved warehouse-local date; null schedule fields mean no activated schedule applies. */
  public record WorkCalendarDate(
      LocalDate date,
      boolean working,
      String timeZone,
      OffsetDateTime timeZoneEffectiveFrom,
      UUID scheduleId,
      Long scheduleVersion,
      LocalDate scheduleEffectiveFrom) {}

  private record ScheduleContext(
      LocalDate dataAvailableFrom, List<KpiWorkScheduleRevision> revisions) {}
}
