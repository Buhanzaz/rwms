package dev.buhanzaz.rwms.taskboard.kpi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class KpiFormulaTest {

  @Test
  void multipliesBudgetWeightedSpeedByUtilization() {
    KpiResult result =
        KpiFormula.calculate(new KpiRawComponents(120, 60, 60, 120, 2));

    assertThat(result.speed()).isEqualByComparingTo("50");
    assertThat(result.utilization()).isEqualByComparingTo("33.3333333333333333");
    assertThat(result.kpi()).isEqualByComparingTo("16.6666666666666667");
  }

  @Test
  void usesSpeedWhenTaskWasCompletedOutsideAccountedWorkTime() {
    KpiResult result =
        KpiFormula.calculate(new KpiRawComponents(60, 60, 0, 0, 1));

    assertThat(result.speed()).isEqualByComparingTo("100");
    assertThat(result.utilization()).isNull();
    assertThat(result.kpi()).isEqualByComparingTo("100");
  }

  @Test
  void usesUtilizationOnlyWhenThereIsPenalizedIdleWithoutCompletions() {
    KpiResult result =
        KpiFormula.calculate(new KpiRawComponents(0, 0, 10, 20, 0));

    assertThat(result.speed()).isNull();
    assertThat(result.utilization()).isEqualByComparingTo("33.3333333333333333");
    assertThat(result.kpi()).isEqualByComparingTo("33.3333333333333333");
  }

  @Test
  void doesNotAwardOneHundredPercentForUnfinishedWorkWithoutIdle() {
    KpiResult result =
        KpiFormula.calculate(new KpiRawComponents(0, 0, 60, 0, 0));

    assertThat(result.speed()).isNull();
    assertThat(result.utilization()).isEqualByComparingTo("100");
    assertThat(result.kpi()).isNull();
  }

  @Test
  void completingExactlyOnBudgetProducesZeroSpeed() {
    KpiResult result =
        KpiFormula.calculate(new KpiRawComponents(60, 0, 60, 0, 1));

    assertThat(result.speed()).isEqualByComparingTo(BigDecimal.ZERO);
    assertThat(result.utilization()).isEqualByComparingTo("100");
    assertThat(result.kpi()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  void aggregatesRawComponentsBeforeApplyingFormula() {
    KpiRawComponents first = new KpiRawComponents(60, 60, 60, 0, 1);
    KpiRawComponents second = new KpiRawComponents(540, 270, 270, 270, 1);

    KpiResult result = KpiFormula.calculate(first.plus(second));

    assertThat(result.speed()).isEqualByComparingTo("55");
    assertThat(result.utilization()).isEqualByComparingTo("55");
    assertThat(result.kpi()).isEqualByComparingTo("30.25");
  }

  @Test
  void rejectsImpossibleRawCounters() {
    assertThatThrownBy(
            () -> KpiFormula.calculate(new KpiRawComponents(60, 61, 0, 0, 1)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("earned");
  }
}
