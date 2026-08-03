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
    assertThat(selection(false, false, null, null).isLogisticsPlanningValid())
        .isTrue();
    assertThat(
            selection(false, true, LogisticsPlanningMode.AUTO, null)
                .isLogisticsPlanningValid())
        .isFalse();
    assertThat(
            selection(false, false, null, LocalDate.of(2026, 8, 12))
                .isLogisticsPlanningValid())
        .isFalse();
  }

  @Test
  void acceptsAutomaticAndFixedDatePlanningOnlyForInboundMovement() {
    assertThat(selection(true, false, LogisticsPlanningMode.AUTO, null).isLogisticsPlanningValid())
        .isTrue();
    assertThat(
            selection(true, true, LogisticsPlanningMode.FIXED_DATE, LocalDate.of(2026, 8, 12))
                .isLogisticsPlanningValid())
        .isTrue();

    assertThat(
            selection(true, false, LogisticsPlanningMode.AUTO, LocalDate.of(2026, 8, 12))
                .isLogisticsPlanningValid())
        .isFalse();
    assertThat(
            selection(true, false, LogisticsPlanningMode.FIXED_DATE, null)
                .isLogisticsPlanningValid())
        .isFalse();
  }

  private PlanSelection selection(
      boolean movementToRepair,
      boolean movementToShipment,
      LogisticsPlanningMode mode,
      LocalDate date) {
    return new PlanSelection(
        "AUTO", 3, null, movementToRepair, movementToShipment, mode, date, List.of(), List.of());
  }
}
