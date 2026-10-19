package dev.buhanzaz.rwms.analytics.service;

import java.math.BigDecimal;
import java.math.RoundingMode;

/** Pure KPI arithmetic over raw seconds and counts; callers round only after aggregating the selected period. */
public final class KpiFormula {
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final int INTERNAL_SCALE = 12;

  private KpiFormula() {}

  public static Result calculate(
      long completedBudgetSeconds,
      long earnedRemainingSeconds,
      long activeSeconds,
      long penalizedIdleSeconds) {
    if (completedBudgetSeconds < 0
        || earnedRemainingSeconds < 0
        || activeSeconds < 0
        || penalizedIdleSeconds < 0
        || earnedRemainingSeconds > completedBudgetSeconds) {
      throw new IllegalArgumentException("KPI components are inconsistent");
    }
    BigDecimal speed =
        completedBudgetSeconds == 0
            ? null
            : percent(earnedRemainingSeconds, completedBudgetSeconds);
    long utilizationDenominator = Math.addExact(activeSeconds, penalizedIdleSeconds);
    BigDecimal utilization =
        utilizationDenominator == 0
            ? null
            : percent(activeSeconds, utilizationDenominator);

    BigDecimal kpi;
    if (speed != null && utilization != null) {
      kpi =
          speed
              .multiply(utilization)
              .divide(HUNDRED, INTERNAL_SCALE, RoundingMode.HALF_UP);
    } else if (speed != null) {
      kpi = speed;
    } else if (penalizedIdleSeconds > 0) {
      kpi = utilization;
    } else {
      kpi = null;
      utilization = null;
    }
    return new Result(normalize(kpi), normalize(speed), normalize(utilization));
  }

  public static BigDecimal round(BigDecimal value) {
    return value == null ? null : value.setScale(2, RoundingMode.HALF_UP);
  }

  private static BigDecimal percent(long numerator, long denominator) {
    return BigDecimal.valueOf(numerator)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(denominator), INTERNAL_SCALE, RoundingMode.HALF_UP);
  }

  private static BigDecimal normalize(BigDecimal value) {
    return value == null ? null : value.stripTrailingZeros();
  }

  public record Result(BigDecimal kpi, BigDecimal speed, BigDecimal utilization) {}
}
