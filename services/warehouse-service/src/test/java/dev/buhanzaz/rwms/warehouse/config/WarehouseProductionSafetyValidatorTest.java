package dev.buhanzaz.rwms.warehouse.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseKafkaOutboxRelay;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Tests the warehouse startup fence without opening a database or broker connection. */
class WarehouseProductionSafetyValidatorTest {
  @Test
  void explicitLocalProfileMayDisableKafkaAndOmitDeliveryBeans() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("dev");

    assertThatCode(
            () ->
                validator(environment, kafka(false), Duration.ofSeconds(30), false, false)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void productionProfileTakesPrecedenceOverSimultaneousTestProfile() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test", "production");

    assertThatThrownBy(
            () ->
                validator(environment, kafka(false), Duration.ofSeconds(30), false, false)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("enabled");
  }

  @ParameterizedTest(name = "rejects unsafe non-local Kafka property: {0}")
  @ValueSource(
      strings = {
        "disabled",
        "empty-destinations",
        "destination",
        "blank-broker",
        "loopback-broker",
        "auto-create",
        "async-producer",
        "weak-acks",
        "non-idempotent",
        "missing-timeout",
        "publish-wait"
      })
  void rejectsUnsafeKafkaConfigurationOutsideLocalProfiles(String unsafeProperty) {
    MockEnvironment environment = safeEnvironment();
    RwmsKafkaProperties kafka = kafka(true);
    Duration lease = Duration.ofSeconds(30);
    switch (unsafeProperty) {
      case "disabled" -> kafka.setEnabled(false);
      case "empty-destinations" -> kafka.setDestinations(List.of());
      case "destination" ->
          kafka.setDestinations(List.of("rwms.warehouse.unapproved.v1"));
      case "blank-broker" ->
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", " ");
      case "loopback-broker" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "127.0.0.1:9092");
      case "auto-create" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.auto-create-topics", "true");
      case "async-producer" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.default.producer.sync", "false");
      case "weak-acks" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.acks", "1");
      case "non-idempotent" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.enable.idempotence", "false");
      case "missing-timeout" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms", "0");
      case "publish-wait" -> lease = Duration.ofSeconds(20);
      default -> throw new IllegalArgumentException("Unknown test mutation");
    }

    Duration configuredLease = lease;
    assertThatThrownBy(
            () ->
                validator(environment, kafka, configuredLease, true, true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest(name = "rejects missing delivery bean: {0}")
  @ValueSource(strings = {"relay", "binding"})
  void rejectsMissingDeliveryBean(String missingBean) {
    assertThatThrownBy(
            () ->
                validator(
                        safeEnvironment(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        !missingBean.equals("relay"),
                        !missingBean.equals("binding"))
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bean");
  }

  @Test
  void acceptsCompleteNonLocalKafkaDeliveryConfiguration() {
    assertThatCode(
            () ->
                validator(
                        safeEnvironment(), kafka(true), Duration.ofSeconds(30), true, true)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void baseConfigurationRequiresExplicitKafkaEnablement() throws IOException {
    var source =
        new YamlPropertySourceLoader()
            .load("warehouse-application", new ClassPathResource("application.yaml"))
            .getFirst();

    assertThat(source.getProperty("rwms.platform.kafka.enabled"))
        .isEqualTo("${WAREHOUSE_KAFKA_ENABLED}");
    assertThat(source.getProperty("spring.cloud.stream.kafka.default.producer.sync"))
        .isEqualTo(true);
    assertThat(source.getProperty("spring.cloud.stream.default.producer.sync")).isNull();
  }

  private static WarehouseProductionSafetyValidator validator(
      MockEnvironment environment,
      RwmsKafkaProperties kafka,
      Duration lease,
      boolean relayPresent,
      boolean bindingPresent) {
    WarehouseOutboxProperties outbox = new WarehouseOutboxProperties();
    outbox.setLeaseDuration(lease);
    return new WarehouseProductionSafetyValidator(
        environment,
        kafka,
        outbox,
        provider(WarehouseKafkaOutboxRelay.class, relayPresent),
        provider(WarehouseKafkaOutputBindingInitializer.class, bindingPresent));
  }

  private static RwmsKafkaProperties kafka(boolean enabled) {
    return new RwmsKafkaProperties(enabled, List.of("rwms.warehouse.warehouse.v1"));
  }

  private static MockEnvironment safeEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("stage");
    return environment
        .withProperty(
            "spring.cloud.stream.kafka.binder.brokers",
            "warehouse-kafka-a.internal:9092,warehouse-kafka-b.internal:9092")
        .withProperty("spring.cloud.stream.kafka.binder.auto-create-topics", "false")
        .withProperty("spring.cloud.stream.kafka.default.producer.sync", "true")
        .withProperty("spring.cloud.stream.kafka.binder.configuration.acks", "all")
        .withProperty(
            "spring.cloud.stream.kafka.binder.configuration.enable.idempotence", "true")
        .withProperty(
            "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms", "5000")
        .withProperty(
            "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms", "15000")
        .withProperty("spring.cloud.stream.kafka.binder.configuration.max.block.ms", "5000");
  }

  @SuppressWarnings("unchecked")
  private static <T> ObjectProvider<T> provider(Class<T> type, boolean present) {
    ObjectProvider<T> provider = mock(ObjectProvider.class);
    when(provider.getIfAvailable()).thenReturn(present ? mock(type) : null);
    return provider;
  }
}
