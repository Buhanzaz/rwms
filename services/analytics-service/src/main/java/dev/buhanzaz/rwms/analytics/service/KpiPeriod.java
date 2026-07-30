package dev.buhanzaz.rwms.analytics.service;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.PeriodType;
import java.time.DateTimeException;
import java.time.LocalDate;
import java.time.YearMonth;

public record KpiPeriod(PeriodType type, LocalDate start, LocalDate end) {
  public static KpiPeriod resolve(
      PeriodType type, Integer year, Integer month, Integer day, Integer quarter) {
    if (type == null) throw new IllegalArgumentException("periodType is required");
    if (year == null || year < 1970 || year > 9999) {
      throw new IllegalArgumentException("year must be between 1970 and 9999");
    }
    try {
      return switch (type) {
        case YEAR -> {
          reject(month != null, "month is not allowed for YEAR");
          reject(day != null, "day is not allowed for YEAR");
          reject(quarter != null, "quarter is not allowed for YEAR");
          yield new KpiPeriod(type, LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31));
        }
        case QUARTER -> {
          reject(month != null, "month is not allowed for QUARTER");
          reject(day != null, "day is not allowed for QUARTER");
          if (quarter == null || quarter < 1 || quarter > 4) {
            throw new IllegalArgumentException("quarter must be between 1 and 4");
          }
          int firstMonth = (quarter - 1) * 3 + 1;
          LocalDate start = LocalDate.of(year, firstMonth, 1);
          yield new KpiPeriod(type, start, start.plusMonths(3).minusDays(1));
        }
        case MONTH -> {
          reject(day != null, "day is not allowed for MONTH");
          reject(quarter != null, "quarter is not allowed for MONTH");
          if (month == null) throw new IllegalArgumentException("month is required for MONTH");
          YearMonth value = YearMonth.of(year, month);
          yield new KpiPeriod(type, value.atDay(1), value.atEndOfMonth());
        }
        case DAY -> {
          reject(quarter != null, "quarter is not allowed for DAY");
          if (month == null) throw new IllegalArgumentException("month is required for DAY");
          if (day == null) throw new IllegalArgumentException("day is required for DAY");
          LocalDate value = LocalDate.of(year, month, day);
          yield new KpiPeriod(type, value, value);
        }
      };
    } catch (DateTimeException exception) {
      throw new IllegalArgumentException("period date is invalid", exception);
    }
  }

  private static void reject(boolean condition, String message) {
    if (condition) throw new IllegalArgumentException(message);
  }
}
