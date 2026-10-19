package dev.buhanzaz.rwms.inventory.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanLineInput;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanSelection;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import jakarta.validation.Validation;
import jakarta.validation.Validator;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.json.JsonMapper;

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
  void omittedCapitalChoiceDefaultsToFalseAndExplicitTrueIsRetained()
      throws JacksonException {
    String request =
        """
        {
          "mode":"AUTO",
          "priority":3,
          "coverMediaId":null,
          "movementToRepair":false,
          "logisticsPlanningMode":null,
          "logisticsScheduledDate":null,
          "lines":[],
          "stages":[]
        }
        """;
    JsonMapper mapper = JsonMapper.builder().findAndAddModules().build();

    PlanSelection omitted = mapper.readValue(request, PlanSelection.class);
    PlanSelection explicit =
        mapper.readValue(
            request.replace("\"movementToRepair\":false,", "\"movementToRepair\":false,\n  \"forceCapitalRepair\":true,"),
            PlanSelection.class);

    assertThat(omitted.forceCapitalRepair()).isFalse();
    assertThat(explicit.forceCapitalRepair()).isTrue();
    assertThatThrownBy(
            () ->
                mapper.readValue(
                    request.replace(
                        "\"movementToRepair\":false,",
                        "\"movementToRepair\":false,\n  \"forceCapitalRepair\":null,"),
                    PlanSelection.class))
        .isInstanceOf(JacksonException.class);
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

  @Test
  void rejectsInboundMovementTogetherWithExplicitCapitalRepair() {
    PlanSelection ambiguous =
        new PlanSelection(
            "AUTO",
            3,
            null,
            true,
            LogisticsPlanningMode.AUTO,
            null,
            List.of(),
            List.of(),
            true);

    assertThat(ambiguous.isRepairDestinationChoiceValid()).isFalse();
    assertThat(VALIDATOR.validate(ambiguous))
        .anyMatch(violation -> violation.getMessage().contains("mutually exclusive"));
  }

  private PlanSelection selection(
      boolean movementToRepair,
      LogisticsPlanningMode mode,
      LocalDate date) {
    return new PlanSelection(
        "AUTO", 3, null, movementToRepair, mode, date, List.of(), List.of());
  }
}
