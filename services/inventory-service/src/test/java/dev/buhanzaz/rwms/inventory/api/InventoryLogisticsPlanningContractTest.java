package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanLineInput;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanSelection;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InventoryLogisticsPlanningContractTest {
  private static final Validator VALIDATOR =
      Validation.buildDefaultValidatorFactory().getValidator();

  @Test
  void beanValidationAcceptsTheExactMobileNonInboundMovementPayload() {
    PlanSelection selection =
        new PlanSelection(
            "AUTO",
            3,
            null,
            false,
            null,
            null,
            List.of(
                new PlanLineInput(
                    "CATALOG",
                    UUID.randomUUID(),
                    null,
                    null,
                    null,
                    null,
                    "1",
                    null,
                    null,
                    null,
                    List.of())),
            List.of());

    assertThat(VALIDATOR.validate(selection)).isEmpty();
  }

  @Test
  void acceptsNonInboundPlanOnlyWhenPlanningIsExplicitlyNull() {
    assertThat(selection(false, null, null).isLogisticsPlanningValid())
        .isTrue();
    assertThat(
            selection(false, LogisticsPlanningMode.AUTO, null)
                .isLogisticsPlanningValid())
        .isFalse();
    assertThat(
            selection(false, null, LocalDate.of(2026, 8, 12))
                .isLogisticsPlanningValid())
        .isFalse();
  }

  @Test
  void acceptsAutomaticAndFixedDatePlanningOnlyForInboundMovement() {
    assertThat(selection(true, LogisticsPlanningMode.AUTO, null).isLogisticsPlanningValid())
        .isTrue();
    assertThat(
            selection(true, LogisticsPlanningMode.FIXED_DATE, LocalDate.of(2026, 8, 12))
                .isLogisticsPlanningValid())
        .isTrue();

    assertThat(
            selection(true, LogisticsPlanningMode.AUTO, LocalDate.of(2026, 8, 12))
                .isLogisticsPlanningValid())
        .isFalse();
    assertThat(
            selection(true, LogisticsPlanningMode.FIXED_DATE, null)
                .isLogisticsPlanningValid())
        .isFalse();
  }

  private PlanSelection selection(
      boolean movementToRepair,
      LogisticsPlanningMode mode,
      LocalDate date) {
    return new PlanSelection(
        "AUTO", 3, null, movementToRepair, mode, date, List.of(), List.of());
  }
}
