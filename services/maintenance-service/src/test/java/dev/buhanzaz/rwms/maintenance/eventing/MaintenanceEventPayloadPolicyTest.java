package dev.buhanzaz.rwms.maintenance.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventContractFixtures.FactCase;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import java.util.Arrays;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceEventPayloadPolicyTest {
  private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MaintenanceEventPayloadPolicy policy = new MaintenanceEventPayloadPolicy(mapper);

  @Test
  void exactTypedPayloadForEveryEventTypePassesIdentityFamilyAndSemanticValidation() {
    for (MaintenanceEventType eventType : MaintenanceEventType.values()) {
      FactCase fact = MaintenanceEventContractFixtures.fact(eventType);

      assertThatCode(() -> policy.validateAndConvert(
              eventType, fact.aggregateType(), fact.aggregateId(), fact.payload()))
          .as(eventType.value())
          .doesNotThrowAnyException();
    }
  }

  @Test
  void missingNullableAndAdditionalFieldsFailExactPayloadValidation() {
    FactCase estimate = MaintenanceEventContractFixtures.fact(MaintenanceEventType.ESTIMATE_COMPLETED);
    var missingNullable = mapper.valueToTree(estimate.payload()).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) missingNullable).remove("repairId");
    var additional = mapper.valueToTree(estimate.payload()).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) additional).put("comment", "must stay local");

    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.ESTIMATE_COMPLETED.value(),
            MaintenanceAggregateType.ESTIMATE,
            estimate.aggregateId(),
            missingNullable))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact schema");
    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.ESTIMATE_COMPLETED.value(),
            MaintenanceAggregateType.ESTIMATE,
            estimate.aggregateId(),
            additional))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void wrongAggregateFamilyAndIdentityAreRejected() {
    FactCase catalog = MaintenanceEventContractFixtures.fact(MaintenanceEventType.CATALOG_IMPORTED);
    var payload = mapper.valueToTree(catalog.payload());

    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.CATALOG_IMPORTED.value(),
            MaintenanceAggregateType.ESTIMATE,
            catalog.aggregateId(),
            payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("aggregate family");
    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.CATALOG_IMPORTED.value(),
            MaintenanceAggregateType.CATALOG_VERSION,
            java.util.UUID.randomUUID(),
            payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("identity mismatch");
  }

  @Test
  void repairPriorityOutsideSupportedRangeIsRejected() {
    FactCase repair = MaintenanceEventContractFixtures.fact(MaintenanceEventType.REPAIR_QUEUED);
    var payload = mapper.valueToTree(repair.payload()).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) payload).put("priority", 0);

    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.REPAIR_QUEUED.value(),
            MaintenanceAggregateType.REPAIR,
            repair.aggregateId(),
            payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("typed semantic validation");
  }

  @Test
  void repairWithoutPriorityIsRejectedAfterTheOneTimeDataMigration() {
    FactCase repair = MaintenanceEventContractFixtures.fact(MaintenanceEventType.REPAIR_QUEUED);
    var payload = mapper.valueToTree(repair.payload()).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) payload).remove("priority");

    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.REPAIR_QUEUED.value(),
            MaintenanceAggregateType.REPAIR,
            repair.aggregateId(),
            payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact schema");
  }

  @ParameterizedTest
  @ValueSource(strings = {
      "login",
      "email",
      "displayName",
      "comment",
      "reason",
      "sourceParty",
      "mediaUrl",
      "objectKey",
      "objectPath",
      "signedUrl",
      "password",
      "secret",
      "token",
      "jwt"
  })
  void piiSecretsBusinessTextAndStorageLocationsCannotExtendTheExactContract(
      String forbiddenField) {
    FactCase repair = MaintenanceEventContractFixtures.fact(MaintenanceEventType.REPAIR_QUEUED);
    var payload = mapper.valueToTree(repair.payload()).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) payload).put(forbiddenField, "sensitive-value");

    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.REPAIR_QUEUED.value(),
            MaintenanceAggregateType.REPAIR,
            repair.aggregateId(),
            payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("exact schema");
  }

  @Test
  void sensitiveTechnicalValuesAreRejectedEvenUnderAnUnknownBenignFieldName() {
    FactCase repair = MaintenanceEventContractFixtures.fact(MaintenanceEventType.REPAIR_QUEUED);
    var payload = mapper.valueToTree(repair.payload()).deepCopy();
    ((tools.jackson.databind.node.ObjectNode) payload)
        .put("rootRepairId", "operator@example.test");

    assertThatThrownBy(() -> policy.validateNode(
            MaintenanceEventType.REPAIR_QUEUED.value(),
            MaintenanceAggregateType.REPAIR,
            repair.aggregateId(),
            payload))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("sensitive technical value");
  }

  @Test
  void springRegistersExactlyOneKafkaSafetyValidatorForEveryExactEventType() {
    try (var context = new AnnotationConfigApplicationContext()) {
      context.registerBean("maintenanceEventPayloadPolicy", MaintenanceEventPayloadPolicy.class,
          () -> policy);
      context.register(MaintenanceEventPayloadValidatorConfiguration.class);
      context.refresh();

      Map<String, RwmsKafkaPayloadSafetyValidator> validators =
          context.getBeansOfType(RwmsKafkaPayloadSafetyValidator.class);
      Set<String> expected = Arrays.stream(MaintenanceEventType.values())
          .map(MaintenanceEventType::value)
          .collect(Collectors.toUnmodifiableSet());

      assertThat(validators).hasSize(expected.size());
      assertThat(validators.values())
          .extracting(RwmsKafkaPayloadSafetyValidator::eventType)
          .containsExactlyInAnyOrderElementsOf(expected);
      for (RwmsKafkaPayloadSafetyValidator validator : validators.values()) {
        MaintenanceEventType eventType = Arrays.stream(MaintenanceEventType.values())
            .filter(candidate -> candidate.value().equals(validator.eventType()))
            .findFirst()
            .orElseThrow();
        validator.validate(mapper.valueToTree(
            MaintenanceEventContractFixtures.fact(eventType).payload()));
      }
    }
  }
}
