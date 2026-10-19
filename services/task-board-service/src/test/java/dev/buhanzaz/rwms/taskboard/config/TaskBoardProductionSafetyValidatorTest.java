package dev.buhanzaz.rwms.taskboard.config;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;

/** Keeps the fail-closed production startup guard aligned with the shipped configuration. */
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

  @Test
  void productionStartupAcceptsDisabledFirebasePush() {
    var environment = productionEnvironment();
    environment.setProperty("rwms.task-board.push.fcm.enabled", "false");
    environment.setProperty("rwms.task-board.push.fcm.project-id", "");
    var validator = new TaskBoardProductionSafetyValidator(environment);
    assertDoesNotThrow(() -> validator.run(new DefaultApplicationArguments(new String[0])));
  }

  @Test
  void enabledFirebasePushStillRequiresProjectId() {
    var environment = productionEnvironment();
    environment.setProperty("rwms.task-board.push.fcm.enabled", "true");
    environment.setProperty("rwms.task-board.push.fcm.project-id", " ");
    var validator = new TaskBoardProductionSafetyValidator(environment);
    var failure =
        assertThrows(
            IllegalStateException.class,
            () -> validator.run(new DefaultApplicationArguments(new String[0])));
    assertTrue(failure.getMessage().contains("TASK_BOARD_FCM_PROJECT_ID"));
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

  /** Shipped configuration plus the external values the managed installer supplies. */
  private MockEnvironment productionEnvironment() {
    var environment = environment();
    environment.setActiveProfiles("production");
    environment.setProperty(
        "spring.security.oauth2.client.registration.auth-service.client-secret",
        "external-task-board-secret");
    environment.setProperty(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://wms.example.test/auth");
    environment.setProperty(
        "spring.security.oauth2.client.provider.auth-service.token-uri",
        "https://wms.example.test:9443/oauth2/token");
    environment.setProperty(
        "rwms.auth.worker-credentials-url",
        "https://wms.example.test:9443/api/internal/worker-credentials");
    environment.setProperty(
        "rwms.warehouse.lifecycle.base-url", "https://wms.example.test:9443/warehouse");
    environment.setProperty("rwms.cors.allowed-origins", "https://wms.example.test");
    return environment;
  }
}
