package dev.buhanzaz.rwms.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class KpiFormulaTest {
  @Test
  void combinesSpeedAndUtilizationMultiplicativelyWithoutPrematureRounding() {
    KpiFormula.Result result = KpiFormula.calculate(120, 60, 60, 120);

    assertThat(result.speed()).isEqualByComparingTo("50");
    assertThat(result.utilization()).isEqualByComparingTo("33.333333333333");
    assertThat(result.kpi()).isEqualByComparingTo("16.666666666667");
  }

  @Test
  void returnsSpeedWhenThereIsNoUtilizationEvidence() {
    KpiFormula.Result result = KpiFormula.calculate(60, 30, 0, 0);

    assertThat(result.speed()).isEqualByComparingTo("50");
    assertThat(result.utilization()).isNull();
    assertThat(result.kpi()).isEqualByComparingTo("50");
  }

  @Test
  void returnsUtilizationWhenOnlyPenalizedIdleExists() {
    KpiFormula.Result result = KpiFormula.calculate(0, 0, 20, 80);

    assertThat(result.speed()).isNull();
    assertThat(result.utilization()).isEqualByComparingTo("20");
    assertThat(result.kpi()).isEqualByComparingTo("20");
  }

  @Test
  void returnsNoKpiForActiveTimeWithoutCompletionsOrIdle() {
    KpiFormula.Result result = KpiFormula.calculate(0, 0, 20, 0);

    assertThat(result).isEqualTo(new KpiFormula.Result(null, null, null));
  }

  @Test
  void roundsOnlyAtTheApiBoundary() {
    assertThat(KpiFormula.round(new BigDecimal("16.666666666667")))
        .isEqualByComparingTo("16.67");
  }
}
