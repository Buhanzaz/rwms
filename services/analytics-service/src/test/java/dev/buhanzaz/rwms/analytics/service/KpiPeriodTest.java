package dev.buhanzaz.rwms.analytics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels.PeriodType;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class KpiPeriodTest {
  @Test
  void resolvesLeapDay() {
    KpiPeriod period = KpiPeriod.resolve(PeriodType.DAY, 2028, 2, 29, null);

    assertThat(period.start()).isEqualTo(LocalDate.of(2028, 2, 29));
    assertThat(period.end()).isEqualTo(period.start());
  }

  @Test
  void resolvesQuarterBoundaries() {
    KpiPeriod period = KpiPeriod.resolve(PeriodType.QUARTER, 2027, null, null, 4);

    assertThat(period.start()).isEqualTo(LocalDate.of(2027, 10, 1));
    assertThat(period.end()).isEqualTo(LocalDate.of(2027, 12, 31));
  }

  @Test
  void rejectsParametersThatDoNotBelongToSelectedPeriod() {
    assertThatThrownBy(() -> KpiPeriod.resolve(PeriodType.YEAR, 2027, 1, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("month");
    assertThatThrownBy(() -> KpiPeriod.resolve(PeriodType.MONTH, 2027, null, null, null))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("month");
    assertThatThrownBy(() -> KpiPeriod.resolve(PeriodType.QUARTER, 2027, null, null, 5))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("quarter");
  }
}
