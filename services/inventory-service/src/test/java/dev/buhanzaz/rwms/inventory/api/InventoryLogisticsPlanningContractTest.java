package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanSelection;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanStageSelection;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryLogisticsPlanningContractTest {
  private static final PlanStageSelection MOVEMENT_STAGE =
      new PlanStageSelection(UUID.randomUUID(), "MOVE_TO_REPAIR", 0);

  @Test
  void acceptsOnlyCanonicalAutomaticAndFixedDateCombinations() {
    assertThat(selection(LogisticsPlanningMode.AUTO, null, List.of()).isLogisticsPlanningValid())
        .isTrue();
    assertThat(
            selection(
                    LogisticsPlanningMode.FIXED_DATE,
                    LocalDate.of(2026, 8, 12),
                    List.of(MOVEMENT_STAGE))
                .isLogisticsPlanningValid())
        .isTrue();

    assertThat(
            selection(LogisticsPlanningMode.AUTO, LocalDate.of(2026, 8, 12), List.of(MOVEMENT_STAGE))
                .isLogisticsPlanningValid())
        .isFalse();
    assertThat(
            selection(LogisticsPlanningMode.FIXED_DATE, null, List.of(MOVEMENT_STAGE))
                .isLogisticsPlanningValid())
        .isFalse();
    assertThat(
            selection(
                    LogisticsPlanningMode.FIXED_DATE, LocalDate.of(2026, 8, 12), List.of())
                .isLogisticsPlanningValid())
        .isFalse();
  }

  private PlanSelection selection(
      LogisticsPlanningMode mode, LocalDate date, List<PlanStageSelection> stages) {
    return new PlanSelection("AUTO", 3, null, mode, date, List.of(), stages);
  }
}
