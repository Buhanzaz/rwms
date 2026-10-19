package dev.buhanzaz.rwms.maintenance.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class RepairComplexitySettingsTest {
  private final RepairComplexitySettings settings =
      RepairComplexitySettings.create(120, 300, 500);

  @Test
  void classifiesContinuousInclusiveMinuteBoundaries() {
    assertThat(settings.classify(BigDecimal.ZERO, false))
        .isEqualTo(RepairComplexity.LIGHT);
    assertThat(settings.classify(BigDecimal.valueOf(120), false))
        .isEqualTo(RepairComplexity.LIGHT);
    assertThat(settings.classify(BigDecimal.valueOf(120.001), false))
        .isEqualTo(RepairComplexity.MEDIUM);
    assertThat(settings.classify(BigDecimal.valueOf(300), false))
        .isEqualTo(RepairComplexity.MEDIUM);
    assertThat(settings.classify(BigDecimal.valueOf(300.001), false))
        .isEqualTo(RepairComplexity.COMPLEX);
    assertThat(settings.classify(BigDecimal.valueOf(500), false))
        .isEqualTo(RepairComplexity.COMPLEX);
    assertThat(settings.classify(BigDecimal.valueOf(500.001), false))
        .isEqualTo(RepairComplexity.CAPITAL);
    assertThat(RepairComplexity.COMPLEX.displayName()).isEqualTo("Тяжёлый ремонт");
  }

  @Test
  void forcedWorkAlwaysMakesTheRepairCapital() {
    assertThat(settings.classify(BigDecimal.ZERO, true))
        .isEqualTo(RepairComplexity.CAPITAL);
  }

  @Test
  void rejectsOverlappingOrNegativeBoundariesAndTime() {
    assertThatThrownBy(
            () -> RepairComplexitySettings.create(120, 120, 500))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> settings.classify(BigDecimal.valueOf(-1), false))
        .isInstanceOf(IllegalArgumentException.class);
  }
}
