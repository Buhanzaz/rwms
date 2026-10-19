package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.api.DriverShiftApiModels.DailyWeatherBriefing;
import java.math.BigDecimal;

/** Replaceable provider of informational, non-blocking daily weather summaries. */
public interface DriverWeatherProvider {
  /** Returns normalized data or an explicit unavailable projection without blocking the shift. */
  DailyWeatherBriefing briefing(BigDecimal latitude, BigDecimal longitude);
}
