package dev.buhanzaz.rwms.assistant;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.assistant.api.AssistantApiModels;
import jakarta.validation.Validation;
import jakarta.validation.ValidatorFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies assistant request validation matches the accepted logistics client-input boundary. */
class AssistantApiModelsValidationTest {
  @Test
  void acceptsHumanFormattedRussianPhoneAndRejectsAnUnnormalizableValue() {
    AssistantApiModels.NewClientRequest valid =
        new AssistantApiModels.NewClientRequest(
            "INDIVIDUAL", "Иван Иванов", "+7 (999) 000-00-00", null, null, null, null);
    AssistantApiModels.NewClientRequest invalid =
        new AssistantApiModels.NewClientRequest(
            "INDIVIDUAL", "Иван Иванов", "12345", null, null, null, null);

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(valid)).isEmpty();
      assertThat(factory.getValidator().validate(invalid))
          .extracting(violation -> violation.getPropertyPath().toString())
          .contains("phone");
    }
  }

  @Test
  void legalEntityRequiresAHumanContactPerson() {
    AssistantApiModels.NewClientRequest request =
        new AssistantApiModels.NewClientRequest(
            "LEGAL_ENTITY", "ООО Север", "8 (999) 000-00-00", null, null, null, null);

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request))
          .extracting(violation -> violation.getPropertyPath().toString())
          .contains("contactPersonRequirementSatisfied");
    }
  }

  @Test
  void removedSoleProprietorIsRejectedAtTheAssistantBoundary() {
    AssistantApiModels.NewClientRequest request =
        new AssistantApiModels.NewClientRequest(
            "SOLE_PROPRIETOR", "ИП Север", "8 (999) 000-00-00", "Иван Петров", null, null, null);

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(request))
          .extracting(violation -> violation.getPropertyPath().toString())
          .contains("clientType");
    }
  }

  @Test
  void rentalOrderLinkRequiresTheImmutableExistingClient() {
    AssistantApiModels.NewClientRequest inline =
        new AssistantApiModels.NewClientRequest(
            "INDIVIDUAL", "Новый клиент", "+79990000000", null, null, null, null);
    AssistantApiModels.CreateConversationRequest invalid =
        new AssistantApiModels.CreateConversationRequest(
            UUID.randomUUID(), null, inline, UUID.randomUUID());
    AssistantApiModels.CreateConversationRequest valid =
        new AssistantApiModels.CreateConversationRequest(
            UUID.randomUUID(), UUID.randomUUID(), null, UUID.randomUUID());

    try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
      assertThat(factory.getValidator().validate(invalid))
          .extracting(violation -> violation.getPropertyPath().toString())
          .contains("orderClientImmutable");
      assertThat(factory.getValidator().validate(valid)).isEmpty();
    }
  }
}
