package dev.buhanzaz.rwms.taskboard.kpi;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;

public final class KpiFormula {
  public static final String VERSION = "kpi-v1";
  private static final BigDecimal HUNDRED = BigDecimal.valueOf(100);
  private static final MathContext PRECISION = new MathContext(18, RoundingMode.HALF_UP);

  private KpiFormula() {}

  public static KpiResult calculate(KpiRawComponents raw) {
    BigDecimal speed =
        raw.completedBudgetSeconds() == 0
            ? null
            : percentage(raw.earnedRemainingSeconds(), raw.completedBudgetSeconds());
    long utilizationDenominator =
        Math.addExact(raw.activeSeconds(), raw.penalizedIdleSeconds());
    BigDecimal utilization =
        utilizationDenominator == 0
            ? null
            : percentage(raw.activeSeconds(), utilizationDenominator);

    BigDecimal kpi;
    if (speed != null && utilization != null) {
      kpi = speed.multiply(utilization, PRECISION).divide(HUNDRED, PRECISION);
    } else if (speed != null) {
      kpi = speed;
    } else if (raw.penalizedIdleSeconds() > 0) {
      kpi = utilization;
    } else {
      kpi = null;
    }
    return new KpiResult(kpi, speed, utilization);
  }

  private static BigDecimal percentage(long numerator, long denominator) {
    return BigDecimal.valueOf(numerator)
        .multiply(HUNDRED)
        .divide(BigDecimal.valueOf(denominator), PRECISION);
  }
}
