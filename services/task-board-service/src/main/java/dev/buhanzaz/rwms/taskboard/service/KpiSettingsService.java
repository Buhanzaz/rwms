package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.KpiSettings;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettingsStatus;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.mapper.KpiSettingsMapper;
import dev.buhanzaz.rwms.taskboard.repository.KpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns the one versioned work schedule shared by every warehouse.
 *
 * <p>The effective date is a common local-calendar date. Operational clocks interpret the shift
 * and breaks in each warehouse's authoritative timezone, so the same policy applies everywhere
 * without making a warehouse the configuration owner.
 */
@Service
@RequiredArgsConstructor
public class KpiSettingsService {
  private final KpiSettingsRepository settingsRepository;
  private final KpiWorkScheduleRepository scheduleRepository;
  private final KpiSettingsMapper mapper;
  private final JdbcTemplate jdbc;

  /** Returns the global configuration or an unconfigured version-zero response. */
  @Transactional
  public KpiSettingsResponse get() {
    var stored = settingsRepository.findById(KpiSettings.SINGLETON_ID);
    if (stored.isEmpty()) return unconfigured();
    KpiSettings settings = stored.orElseThrow();
    synchronize(settings);
    return response(settings);
  }

  /** Saves a validated current-day or future schedule as the single pending revision. */
  @Transactional
  public KpiSettingsResponse saveWorkSchedule(SaveWorkScheduleRequest request) {
    KpiSettings settings = loadOrCreate(request.expectedVersion());
    validateSchedule(request);
    List<KpiWorkBreakInterval> breaks =
        request.breaks().stream()
            .sorted(Comparator.comparing(KpiWorkBreakRequest::start))
            .map(value -> new KpiWorkBreakInterval(value.start(), value.end()))
            .toList();
    var schedule =
        new KpiWorkScheduleRevision(
            request.effectiveFrom(),
            request.shiftStart(),
            request.shiftEnd(),
            request.daysOff(),
            breaks);
    KpiWorkScheduleRevision previousPending = settings.getPendingSchedule();
    if (previousPending != null) {
      previousPending.unschedule();
      scheduleRepository.save(previousPending);
    }
    scheduleRepository.saveAndFlush(schedule);
    settings.setPendingSchedule(schedule);
    return response(settingsRepository.saveAndFlush(settings));
  }

  /** Deletes the pending shared schedule under the observed settings version. */
  @Transactional
  public void deletePendingWorkSchedule(long expectedVersion) {
    KpiSettings settings = requireSettings();
    synchronize(settings);
    requireVersion(settings, expectedVersion);
    if (settings.getPendingSchedule() == null) {
      throw new NotFoundException("Будущий рабочий график не найден");
    }
    settings.getPendingSchedule().unschedule();
    scheduleRepository.save(settings.getPendingSchedule());
    settings.removePendingSchedule();
    settingsRepository.saveAndFlush(settings);
  }

  /** Activates the pending shared schedule exactly once for the stable operation ID. */
  @Transactional
  public KpiSettingsResponse activate(
      UUID operationId, ActivateKpiSettingsRequest request) {
    var replay =
        jdbc.query(
                """
                select expected_version
                  from kpi_activation_receipt
                 where operation_id=?
                """,
                (resultSet, row) -> resultSet.getLong("expected_version"),
                operationId)
            .stream()
            .findFirst();
    if (replay.isPresent()) {
      long expectedVersion = replay.orElseThrow();
      if (expectedVersion != request.expectedVersion()) {
        throw new ConflictException("Idempotency-Key уже использован для другой активации KPI");
      }
      return response(requireSettings());
    }

    KpiSettings settings = requireSettings();
    synchronize(settings);
    requireVersion(settings, request.expectedVersion());
    try {
      KpiWorkScheduleRevision activeSchedule = settings.getActiveSchedule();
      KpiWorkScheduleRevision pendingSchedule = settings.getPendingSchedule();
      if (activeSchedule != null
          && pendingSchedule != null
          && activeSchedule.getEffectiveFrom().equals(pendingSchedule.getEffectiveFrom())) {
        activeSchedule.unschedule();
        scheduleRepository.saveAndFlush(activeSchedule);
      }
      settings.activatePendingSchedule();
      settings.promoteSchedule(minimumEffectiveDate());
    } catch (IllegalStateException exception) {
      throw new ConflictException(exception.getMessage());
    }
    settingsRepository.saveAndFlush(settings);
    jdbc.update(
        """
        insert into kpi_activation_receipt(
          operation_id,expected_version,resulting_version,processed_at)
        values (?,?,?,clock_timestamp())
        """,
        operationId,
        request.expectedVersion(),
        settings.getVersion());
    return response(settings);
  }

  private KpiSettings loadOrCreate(long expectedVersion) {
    var existing = settingsRepository.findById(KpiSettings.SINGLETON_ID);
    if (existing.isEmpty()) {
      if (expectedVersion != 0) throw stale(0, expectedVersion);
      return KpiSettings.create();
    }
    KpiSettings settings = existing.orElseThrow();
    synchronize(settings);
    requireVersion(settings, expectedVersion);
    return settings;
  }

  private KpiSettings requireSettings() {
    return settingsRepository
        .findById(KpiSettings.SINGLETON_ID)
        .orElseThrow(() -> new NotFoundException("Настройки KPI не найдены"));
  }

  private void synchronize(KpiSettings settings) {
    if (settings.promoteSchedule(minimumEffectiveDate())) {
      settingsRepository.saveAndFlush(settings);
    }
  }

  private KpiSettingsResponse response(KpiSettings settings) {
    return new KpiSettingsResponse(
        settings.getStatus(),
        settings.getVersion(),
        settings.getDataAvailableFrom(),
        minimumEffectiveDate(),
        settings.getPalette() == null ? null : mapper.toKpiPaletteDto(settings.getPalette()),
        settings.getActiveSchedule() == null
            ? null
            : mapper.toKpiWorkScheduleDto(settings.getActiveSchedule()),
        settings.getPendingSchedule() == null
            ? null
            : mapper.toKpiWorkScheduleDto(settings.getPendingSchedule()));
  }

  private KpiSettingsResponse unconfigured() {
    return new KpiSettingsResponse(
        KpiSettingsStatus.UNCONFIGURED, 0, null, minimumEffectiveDate(), null, null, null);
  }

  private void validateSchedule(SaveWorkScheduleRequest request) {
    if (request.effectiveFrom().isBefore(minimumEffectiveDate())) {
      throw new IllegalArgumentException(
          "Дата вступления графика не может быть раньше текущего дня");
    }
    if (!request.shiftStart().isBefore(request.shiftEnd())) {
      throw new IllegalArgumentException(
          "Смена должна начинаться раньше окончания в пределах одних суток");
    }
    if (request.daysOff().stream().distinct().count() != request.daysOff().size()) {
      throw new IllegalArgumentException("Выходные дни не должны повторяться");
    }
    List<KpiWorkBreakRequest> breaks =
        request.breaks().stream()
            .sorted(Comparator.comparing(KpiWorkBreakRequest::start))
            .toList();
    KpiWorkBreakRequest previous = null;
    for (KpiWorkBreakRequest current : breaks) {
      if (!current.start().isBefore(current.end())
          || current.start().isBefore(request.shiftStart())
          || current.end().isAfter(request.shiftEnd())) {
        throw new IllegalArgumentException(
            "Каждый перерыв должен находиться внутри рабочей смены");
      }
      if (previous != null && current.start().isBefore(previous.end())) {
        throw new IllegalArgumentException("Интервалы отдыха не должны пересекаться");
      }
      previous = current;
    }
  }

  private void requireVersion(KpiSettings settings, long expectedVersion) {
    if (settings.getVersion() != expectedVersion) {
      throw stale(settings.getVersion(), expectedVersion);
    }
  }

  private LocalDate minimumEffectiveDate() {
    return LocalDate.now(ZoneOffset.UTC);
  }

  private StaleVersionException stale(long actual, long expected) {
    return new StaleVersionException(
        "Версия настроек KPI изменилась: ожидалась " + expected + ", текущая " + actual);
  }
}
