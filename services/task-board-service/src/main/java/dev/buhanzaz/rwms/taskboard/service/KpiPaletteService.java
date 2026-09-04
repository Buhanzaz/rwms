package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.KpiPalette;
import dev.buhanzaz.rwms.taskboard.domain.KpiPaletteRange;
import dev.buhanzaz.rwms.taskboard.domain.KpiSettings;
import dev.buhanzaz.rwms.taskboard.mapper.KpiSettingsMapper;
import dev.buhanzaz.rwms.taskboard.repository.KpiPaletteRepository;
import dev.buhanzaz.rwms.taskboard.repository.KpiSettingsRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Owns the one KPI display palette shared by every warehouse. */
@Service
@RequiredArgsConstructor
public class KpiPaletteService {
  private static final Pattern RGB = Pattern.compile("^#[0-9A-F]{6}$");

  private final KpiSettingsRepository settingsRepository;
  private final KpiPaletteRepository paletteRepository;
  private final KpiSettingsMapper mapper;

  /** Returns the shared palette, or an unconfigured version-zero head. */
  @Transactional(readOnly = true)
  public KpiPaletteResponse get() {
    return settingsRepository
        .findById(KpiSettings.SINGLETON_ID)
        .map(this::response)
        .orElseGet(this::unconfigured);
  }

  /** Replaces the installation-wide palette under its observed aggregate version. */
  @Transactional
  public KpiPaletteResponse replace(SaveKpiPaletteRequest request) {
    KpiSettings settings =
        settingsRepository
            .findById(KpiSettings.SINGLETON_ID)
            .orElseGet(
                () -> {
                  if (request.expectedVersion() != 0) {
                    throw stale();
                  }
                  return KpiSettings.create();
                });
    if (settings.getVersion() != request.expectedVersion()) {
      throw stale();
    }

    List<KpiPaletteRange> ranges = validatedRanges(request.ranges());
    String overdueColor = normalizeColor(request.overdueColor());
    KpiPalette palette = settings.getPalette();
    if (palette == null) {
      palette = new KpiPalette(overdueColor, ranges);
    } else {
      palette.clearRanges();
      paletteRepository.saveAndFlush(palette);
      palette.replace(overdueColor, ranges);
    }
    paletteRepository.saveAndFlush(palette);
    settings.setPalette(palette);
    return response(settingsRepository.saveAndFlush(settings));
  }

  private KpiPaletteResponse response(KpiSettings settings) {
    return new KpiPaletteResponse(
        settings.getVersion(),
        settings.getPalette() == null ? null : mapper.toKpiPaletteDto(settings.getPalette()));
  }

  private KpiPaletteResponse unconfigured() {
    return new KpiPaletteResponse(0, null);
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

  private String normalizeColor(String value) {
    String normalized = value == null ? "" : value.trim().toUpperCase(Locale.ROOT);
    if (!RGB.matcher(normalized).matches()) {
      throw new IllegalArgumentException("Цвет должен быть задан в формате #RRGGBB");
    }
    return normalized;
  }

  private StaleVersionException stale() {
    return new StaleVersionException("Палитра KPI");
  }
}
