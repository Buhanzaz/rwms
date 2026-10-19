package dev.buhanzaz.rwms.taskboard.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Keeps the fail-closed production Kafka inventory aligned with the shipped configuration. */
class TaskBoardProductionSafetyValidatorTest {
  @Test
  void acceptsCanonicalInventoryIncludingDriverShiftOwnerProof() {
    var validator = new TaskBoardProductionSafetyValidator(environment());
    assertDoesNotThrow(() -> ReflectionTestUtils.invokeMethod(validator, "requireKafkaCutover"));
  }

  @Test
  void rejectsMissingDriverShiftOwnerProof() {
    var environment = environment();
    environment.setProperty("rwms.platform.kafka.destinations[10]", "wrong-topic");
    var validator = new TaskBoardProductionSafetyValidator(environment);
    assertThrows(
        IllegalStateException.class,
        () -> ReflectionTestUtils.invokeMethod(validator, "requireKafkaCutover"));
  }

  @Test
  void stillRejectsAdditionalUnreviewedDestination() {
    var environment = environment();
    environment.setProperty("rwms.platform.kafka.destinations[11]", "unreviewed-topic");
    var validator = new TaskBoardProductionSafetyValidator(environment);
    assertThrows(
        IllegalStateException.class,
        () -> ReflectionTestUtils.invokeMethod(validator, "requireKafkaCutover"));
  }

  private MockEnvironment environment() {
    var yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yaml"));
    Properties properties = yaml.getObject();
    var environment = new MockEnvironment();
    properties.forEach((key, value) -> environment.setProperty(key.toString(), value.toString()));
    environment.setProperty("rwms.platform.kafka.enabled", "true");
    environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "kafka:9092");
    return environment;
  }
}
