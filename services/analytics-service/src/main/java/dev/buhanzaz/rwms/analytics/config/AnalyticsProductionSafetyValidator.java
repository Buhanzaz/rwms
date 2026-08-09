package dev.buhanzaz.rwms.analytics.config;

import java.util.Arrays;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/** Fails startup outside local profiles when the analytics projection's Kafka and DLT configuration diverges from its contract. */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public final class AnalyticsProductionSafetyValidator implements ApplicationRunner {
  private static final String KPI_TOPIC = "rwms.task-board.group-kpi-day.v1";
  private static final String KPI_CONSUMER_GROUP = "analytics-projection-v1";
  private static final String KPI_DLT =
      KPI_TOPIC + "." + KPI_CONSUMER_GROUP + ".dlt";

  private final Environment environment;

  public AnalyticsProductionSafetyValidator(Environment environment) {
    this.environment = environment;
  }

  @Override
  public void run(ApplicationArguments args) {
    boolean production =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    boolean local =
        !production
            && Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
    if (local) return;

    if (!environment.getProperty("rwms.platform.kafka.enabled", Boolean.class, false)) {
      throw new IllegalStateException("ANALYTICS_KAFKA_ENABLED должен быть true вне dev/test");
    }
    requireText("ANALYTICS_KAFKA_BROKERS", "spring.cloud.stream.kafka.binder.brokers");
    if (environment.getProperty(
        "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
      throw new IllegalStateException("Kafka topic auto-creation запрещён вне local dev");
    }
    if (!"analyticsKpiDayConsumer"
        .equals(environment.getProperty("spring.cloud.function.definition"))) {
      throw new IllegalStateException("Analytics Kafka function не соответствует контракту KPI");
    }
    requireExact(
        "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.destination",
        KPI_TOPIC,
        "Analytics KPI destination");
    requireExact(
        "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.group",
        KPI_CONSUMER_GROUP,
        "Analytics KPI consumer group");
    if (environment.getProperty(
            "spring.cloud.stream.bindings.analyticsKpiDayConsumer-in-0.consumer.max-attempts",
            Integer.class,
            -1)
        != 1) {
      throw new IllegalStateException("Binder retry должен быть отключён для KPI projection");
    }
    if (environment.getProperty(
        "spring.cloud.stream.kafka.bindings.analyticsKpiDayConsumer-in-0.consumer.enable-dlq",
        Boolean.class,
        true)) {
      throw new IllegalStateException("Raw binder DLT запрещён для analytics-service");
    }
    if (environment.getProperty(
        "rwms.platform.kafka.publisher-enabled", Boolean.class, true)) {
      throw new IllegalStateException("Analytics не публикует канонические доменные события");
    }
    requireExact(
        "spring.cloud.stream.dynamic-destinations[0]",
        KPI_DLT,
        "Analytics sanitized DLT destination");
    if (environment.getProperty("spring.cloud.stream.dynamic-destinations[1]") != null) {
      throw new IllegalStateException("Analytics DLT destination должен быть точным");
    }
    if (!environment.getProperty(
        "spring.cloud.stream.kafka.default.producer.sync", Boolean.class, false)) {
      throw new IllegalStateException("Analytics DLT producer должен ждать broker acknowledgement");
    }
  }

  private void requireExact(String property, String expected, String label) {
    if (!expected.equals(environment.getProperty(property))) {
      throw new IllegalStateException(label + " не соответствует контракту");
    }
  }

  private String requireText(String environmentName, String propertyName) {
    String value = environment.getProperty(propertyName);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(environmentName + " обязателен");
    }
    return value;
  }
}
