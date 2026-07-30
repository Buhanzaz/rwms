package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.KpiPalette;
import dev.buhanzaz.rwms.taskboard.domain.KpiPaletteRange;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.WarehouseKpiSettings;
import dev.buhanzaz.rwms.taskboard.domain.WarehouseMetadata;
import dev.buhanzaz.rwms.taskboard.mapper.KpiSettingsMapper;
import dev.buhanzaz.rwms.taskboard.repository.KpiPaletteRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiWorkScheduleRepository;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseKpiSettingsRepository;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseMetadataRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class KpiSettingsService {
  private static final Pattern RGB = Pattern.compile("^#[0-9A-F]{6}$");

  private final WarehouseMetadataRepository warehouseRepository;
  private final WarehouseKpiSettingsRepository settingsRepository;
  private final KpiPaletteRepository paletteRepository;
  private final KpiWorkScheduleRepository scheduleRepository;
  private final KpiSettingsMapper mapper;
  private final JdbcTemplate jdbc;

  @Transactional
  public WarehouseKpiSettingsResponse get(UUID warehouseId) {
    WarehouseMetadata warehouse = warehouse(warehouseId);
    var stored = settingsRepository.findByWarehouseId(warehouseId);
    if (stored.isEmpty()) {
      return mapper.toWarehouseKpiSettingsResponse(
          WarehouseKpiSettings.create(warehouseId, warehouse.getTimeZone()));
    }
    WarehouseKpiSettings settings = stored.orElseThrow();
    synchronize(settings, warehouse);
    return response(settings);
  }

  @Transactional
  public WarehouseKpiSettingsResponse savePalette(
      UUID warehouseId, SaveKpiPaletteRequest request) {
    WarehouseKpiSettings settings = loadOrCreate(warehouseId, request.expectedVersion());
    List<KpiPaletteRange> ranges = validatedRanges(request.ranges());
    String overdueColor = normalizeColor(request.overdueColor());

    KpiPalette palette = settings.getPalette();
    if (palette == null) {
      palette = new KpiPalette(overdueColor, ranges);
    } else {
      palette.replace(overdueColor, ranges);
    }
    paletteRepository.saveAndFlush(palette);
    settings.setPalette(palette);
    settingsRepository.saveAndFlush(settings);
    return response(settings);
  }

  @Transactional
  public WarehouseKpiSettingsResponse saveWorkSchedule(
      UUID warehouseId, SaveWorkScheduleRequest request) {
    WarehouseKpiSettings settings = loadOrCreate(warehouseId, request.expectedVersion());
    validateSchedule(settings.getTimeZone(), request);
    List<KpiWorkBreakInterval> breaks =
        request.breaks().stream()
            .sorted(Comparator.comparing(KpiWorkBreakRequest::start))
            .map(value -> new KpiWorkBreakInterval(value.start(), value.end()))
            .toList();
    var schedule =
        new KpiWorkScheduleRevision(
            warehouseId,
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
    settingsRepository.saveAndFlush(settings);
    return response(settings);
  }

  @Transactional
  public WarehouseKpiSettingsResponse saveRepairComplexity(
      UUID warehouseId, SaveRepairComplexityThresholdsRequest request) {
    WarehouseKpiSettings settings =
        loadOrCreate(warehouseId, request.expectedVersion());
    settings.setRepairComplexityBoundaries(
        request.lightBoundaryMinutes(),
        request.mediumBoundaryMinutes(),
        request.complexBoundaryMinutes());
    settingsRepository.saveAndFlush(settings);
    return response(settings);
  }

  @Transactional
  public RepairComplexityThresholdsResponse repairComplexity(UUID warehouseId) {
    WarehouseMetadata warehouse = warehouse(warehouseId);
    WarehouseKpiSettings settings =
        settingsRepository
            .findByWarehouseId(warehouseId)
            .orElseGet(
                () ->
                    WarehouseKpiSettings.create(
                        warehouseId, warehouse.getTimeZone()));
    synchronize(settings, warehouse);
    return new RepairComplexityThresholdsResponse(
        warehouseId,
        settings.getVersion(),
        settings.getRepairLightBoundaryMinutes(),
        settings.getRepairMediumBoundaryMinutes(),
        settings.getRepairComplexBoundaryMinutes());
  }

  @Transactional
  public void deletePendingWorkSchedule(UUID warehouseId, long expectedVersion) {
    WarehouseKpiSettings settings = requireSettings(warehouseId);
    requireVersion(settings, expectedVersion);
    if (settings.getPendingSchedule() == null) {
      throw new NotFoundException("Будущий рабочий график не найден");
    }
    settings.getPendingSchedule().unschedule();
    scheduleRepository.save(settings.getPendingSchedule());
    settings.removePendingSchedule();
    settingsRepository.saveAndFlush(settings);
  }

  @Transactional
  public WarehouseKpiSettingsResponse activate(
      UUID warehouseId, UUID operationId, ActivateKpiSettingsRequest request) {
    var replay =
        jdbc.query(
            """
            select warehouse_id,expected_version
              from kpi_activation_receipt
             where operation_id=?
            """,
            (resultSet, row) ->
                new ActivationReceipt(
                    resultSet.getObject("warehouse_id", UUID.class),
                    resultSet.getLong("expected_version")),
            operationId)
            .stream()
            .findFirst();
    if (replay.isPresent()) {
      ActivationReceipt receipt = replay.orElseThrow();
      if (!receipt.warehouseId().equals(warehouseId)
          || receipt.expectedVersion() != request.expectedVersion()) {
        throw new ConflictException(
            "Idempotency-Key уже использован для другой активации KPI");
      }
      return response(requireSettings(warehouseId));
    }
    WarehouseKpiSettings settings = requireSettings(warehouseId);
    requireVersion(settings, request.expectedVersion());
    try {
      settings.activatePendingSchedule();
    } catch (IllegalStateException exception) {
      throw new ConflictException(exception.getMessage());
    }
    settingsRepository.saveAndFlush(settings);
    jdbc.update(
        """
        insert into kpi_activation_receipt(
          operation_id,warehouse_id,expected_version,resulting_version,processed_at)
        values (?,?,?,?,clock_timestamp())
        """,
        operationId,
        warehouseId,
        request.expectedVersion(),
        settings.getVersion());
    return response(settings);
  }

  private WarehouseKpiSettings loadOrCreate(UUID warehouseId, long expectedVersion) {
    WarehouseMetadata warehouse = warehouse(warehouseId);
    var existing = settingsRepository.findByWarehouseId(warehouseId);
    if (existing.isEmpty()) {
      if (expectedVersion != 0) {
        throw stale(0, expectedVersion);
      }
      return WarehouseKpiSettings.create(warehouseId, warehouse.getTimeZone());
    }
    WarehouseKpiSettings settings = existing.orElseThrow();
    synchronize(settings, warehouse);
    requireVersion(settings, expectedVersion);
    return settings;
  }

  private WarehouseKpiSettings requireSettings(UUID warehouseId) {
    WarehouseMetadata warehouse = warehouse(warehouseId);
    WarehouseKpiSettings settings =
        settingsRepository
            .findByWarehouseId(warehouseId)
            .orElseThrow(() -> new NotFoundException("Настройки KPI склада не найдены"));
    synchronize(settings, warehouse);
    return settings;
  }

  private WarehouseMetadata warehouse(UUID warehouseId) {
    return warehouseRepository
        .findById(warehouseId)
        .filter(WarehouseMetadata::isActive)
        .orElseThrow(() -> new NotFoundException("Склад не найден или неактивен"));
  }

  private void synchronize(WarehouseKpiSettings settings, WarehouseMetadata warehouse) {
    settings.synchronizeTimeZone(warehouse.getTimeZone());
    LocalDate today = LocalDate.now(ZoneId.of(warehouse.getTimeZone()));
    if (settings.promoteSchedule(today)) {
      settingsRepository.saveAndFlush(settings);
    }
  }

  private WarehouseKpiSettingsResponse response(WarehouseKpiSettings settings) {
    return mapper.toWarehouseKpiSettingsResponse(settings);
  }

  private List<KpiPaletteRange> validatedRanges(List<KpiPaletteRangeRequest> values) {
    if (values == null || values.isEmpty() || values.size() > 6) {
      throw new IllegalArgumentException("Настройте от одного до шести диапазонов");
    }
    List<KpiPaletteRangeRequest> ranges =
        values.stream()
            .sorted(Comparator.comparingInt(KpiPaletteRangeRequest::fromPercent))
            .toList();
    if (ranges.getFirst().fromPercent() != 0 || ranges.getLast().toPercent() != 100) {
      throw new IllegalArgumentException("Диапазоны должны покрывать шкалу от 0 до 100%");
    }
    int expectedFrom = 0;
    var result = new java.util.ArrayList<KpiPaletteRange>();
    for (KpiPaletteRangeRequest range : ranges) {
      if (range.fromPercent() != expectedFrom
          || range.fromPercent() < 0
          || range.toPercent() > 100
          || range.fromPercent() >= range.toPercent()) {
        throw new IllegalArgumentException(
            "Диапазоны KPI должны быть непрерывными и не пересекаться");
      }
      result.add(
          new KpiPaletteRange(
              range.fromPercent(), range.toPercent(), normalizeColor(range.color())));
      expectedFrom = range.toPercent();
    }
    return List.copyOf(result);
  }

  private void validateSchedule(String timeZone, SaveWorkScheduleRequest request) {
    LocalDate today = LocalDate.now(ZoneId.of(timeZone));
    if (!request.effectiveFrom().isAfter(today)) {
      throw new IllegalArgumentException(
          "Дата вступления графика должна быть не раньше следующего дня");
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

  private String normalizeColor(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!RGB.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Цвет должен быть задан в формате #RRGGBB");
    }
    return normalized;
  }

  private void requireVersion(WarehouseKpiSettings settings, long expectedVersion) {
    if (settings.getVersion() != expectedVersion) {
      throw stale(settings.getVersion(), expectedVersion);
    }
  }

  private StaleVersionException stale(long actual, long expected) {
    return new StaleVersionException(
        "Версия настроек KPI изменилась: ожидалась " + expected + ", текущая " + actual);
  }

  private record ActivationReceipt(UUID warehouseId, long expectedVersion) {}
}
