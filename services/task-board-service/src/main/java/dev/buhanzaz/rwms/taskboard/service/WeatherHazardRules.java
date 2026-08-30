package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.WeatherHazardView;
import dev.buhanzaz.rwms.taskboard.config.DriverShiftProperties;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import org.springframework.stereotype.Service;

/** Central configurable rules that derive advisories only from MET Norway forecast facts. */
@Service
public class WeatherHazardRules {
  private final DriverShiftProperties.Hazards thresholds;

  public WeatherHazardRules(DriverShiftProperties properties) {
    thresholds = properties.hazards();
  }

  /**
   * Derives de-duplicated, non-official advisories from the maximum supported facts in the
   * briefing's 24-hour horizon.
   */
  public List<WeatherHazardView> derive(
      Set<String> symbols,
      BigDecimal maximumGust,
      BigDecimal maximumRainPerHour,
      BigDecimal maximumFogAreaFraction) {
    Set<String> normalizedSymbols =
        symbols.stream().map(value -> value.toLowerCase(Locale.ROOT)).collect(java.util.stream.Collectors.toSet());
    List<WeatherHazardView> result = new ArrayList<>();
    if (maximumGust != null && maximumGust.doubleValue() >= thresholds.windGustWarning())
      result.add(hazard("HIGH_WIND", "Ожидаются сильные порывы ветра"));
    if (maximumRainPerHour != null
        && maximumRainPerHour.doubleValue() >= thresholds.heavyRainMmPerHour())
      result.add(hazard("HEAVY_RAIN", "Ожидается сильный дождь"));
    if (contains(normalizedSymbols, "freezingrain"))
      result.add(hazard("FREEZING_RAIN", "Возможен ледяной дождь"));
    if (contains(normalizedSymbols, "snow")) result.add(hazard("SNOW", "Ожидается снег"));
    if (contains(normalizedSymbols, "fog")
        || (maximumFogAreaFraction != null
            && maximumFogAreaFraction.doubleValue()
                >= thresholds.fogAreaFractionWarningPercent())) {
      result.add(hazard("LOW_VISIBILITY", "Возможна низкая видимость"));
    }
    if (contains(normalizedSymbols, "thunder"))
      result.add(hazard("THUNDERSTORM", "Возможна гроза"));
    if (contains(normalizedSymbols, "hail")) result.add(hazard("HAIL", "Возможен град"));
    return List.copyOf(result);
  }

  private boolean contains(Set<String> symbols, String fragment) {
    return symbols.stream().anyMatch(symbol -> symbol.contains(fragment));
  }

  private WeatherHazardView hazard(String type, String text) {
    return new WeatherHazardView(type, "WARNING", text, text + ". Соблюдайте осторожность.");
  }
}
