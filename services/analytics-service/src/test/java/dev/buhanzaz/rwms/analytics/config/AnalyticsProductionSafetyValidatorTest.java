package dev.buhanzaz.rwms.analytics.config;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;

class AnalyticsProductionSafetyValidatorTest {
  @Test
  void productionRequiresKafkaProjectionCutover() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("production");

    assertThatThrownBy(() -> run(environment))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("ANALYTICS_KAFKA_ENABLED");
  }

  @Test
  void productionAcceptsTheExactKpiProjectionContract() {
    assertThatCode(() -> run(secureProductionEnvironment())).doesNotThrowAnyException();
  }

  @Test
  void productionRejectsAnotherTopicOrRawBinderDlt() {
    MockEnvironment wrongTopic = secureProductionEnvironment();
    wrongTopic.setProperty(
        "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.destination",
        "rwms.task-board.another.v1");
    assertThatThrownBy(() -> run(wrongTopic))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("destination");

    MockEnvironment rawDlt = secureProductionEnvironment();
    rawDlt.setProperty(
        "spring.cloud.stream.kafka.bindings.analyticsKpiDayConsumer-in-0.consumer.enable-dlq",
        "true");
    assertThatThrownBy(() -> run(rawDlt))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("Raw binder DLT");
  }

  @Test
  void developmentMayRunWithoutKafka() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    assertThatCode(() -> run(environment)).doesNotThrowAnyException();
  }

  private static void run(MockEnvironment environment) throws Exception {
    new AnalyticsProductionSafetyValidator(environment)
        .run(new DefaultApplicationArguments(new String[0]));
  }

  private static MockEnvironment secureProductionEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("production");
    environment.setProperty("rwms.platform.kafka.enabled", "true");
    environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "kafka:9092");
    environment.setProperty("spring.cloud.stream.kafka.binder.auto-create-topics", "false");
    environment.setProperty("spring.cloud.function.definition", "analyticsKpiDayConsumer");
    environment.setProperty(
        "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.destination",
        "rwms.task-board.group-kpi-day.v1");
    environment.setProperty(
        "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.group",
        "analytics-projection-v1");
    environment.setProperty(
        "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.consumer.max-attempts", "1");
    environment.setProperty(
        "spring.cloud.stream.kafka.bindings.analyticsKpiDayConsumer-in-0.consumer.enable-dlq",
        "false");
    environment.setProperty(
        "rwms.platform.kafka.publisher-enabled",
        "false");
    environment.setProperty(
        "spring.cloud.stream.dynamic-destinations[0]",
        "rwms.task-board.group-kpi-day.v1.analytics-projection-v1.dlt");
    environment.setProperty("spring.cloud.stream.kafka.default.producer.sync", "true");
    return environment;
  }
}
