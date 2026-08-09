package dev.buhanzaz.rwms.maintenance.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceKafkaOutboxRelay;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceOutboxProperties;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceSanitizedDltRelay;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceTransportTopics;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.io.IOException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.env.YamlPropertySourceLoader;
import org.springframework.core.io.ClassPathResource;
import org.springframework.mock.env.MockEnvironment;

/** Tests every maintenance dependency and Kafka startup fence without opening external systems. */
class MaintenanceProductionSafetyValidatorTest {
  private static final String CLIENT_SECRET = "maintenance-test-client-secret";

  @ParameterizedTest
  @ValueSource(strings = {"dev", "test"})
  void explicitLocalProfileMayDisableKafkaAndOmitDeliveryBeans(String profile) {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles(profile);

    assertThatCode(
            () ->
                validator(
                        environment,
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka(false),
                        Duration.ofSeconds(30),
                        false,
                        false,
                        false)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void productionProfileTakesPrecedenceOverSimultaneousTestProfile() {
    MockEnvironment environment = safeEnvironment();
    environment.setActiveProfiles("test", "production");

    assertThatThrownBy(
            () ->
                validator(
                        environment,
                        true,
                        false,
                        true,
                        validDependencies(),
                        kafka(false),
                        Duration.ofSeconds(30),
                        false,
                        false,
                        false)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("enabled");
  }

  @Test
  void missingExplicitLocalProfileRequiresKafkaDelivery() {
    MockEnvironment environment = new MockEnvironment();

    assertThatThrownBy(
            () ->
                validator(
                        environment,
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka(false),
                        Duration.ofSeconds(30),
                        false,
                        false,
                        false)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("enabled");
  }

  @ParameterizedTest(name = "rejects unsafe non-local Kafka property: {0}")
  @ValueSource(
      strings = {
        "disabled",
        "empty-destinations",
        "missing-destination",
        "wrong-destination",
        "wrong-order",
        "missing-broker",
        "blank-broker",
        "malformed-broker",
        "missing-port",
        "invalid-port",
        "empty-broker-entry",
        "loopback-broker",
        "mixed-loopback-broker",
        "auto-create",
        "async-producer",
        "weak-acks",
        "non-idempotent",
        "request-timeout",
        "delivery-timeout",
        "max-block",
        "delivery-before-request",
        "invalid-lease",
        "publish-wait"
      })
  void rejectsUnsafeKafkaConfigurationOutsideLocalProfiles(String unsafeProperty) {
    MockEnvironment environment = safeEnvironment();
    RwmsKafkaProperties kafka = kafka(true);
    Duration lease = Duration.ofSeconds(30);
    switch (unsafeProperty) {
      case "disabled" -> kafka.setEnabled(false);
      case "empty-destinations" -> kafka.setDestinations(List.of());
      case "missing-destination" ->
          kafka.setDestinations(MaintenanceTransportTopics.OUTPUTS.subList(0, 4));
      case "wrong-destination" ->
          kafka.setDestinations(
              List.of(
                  MaintenanceTransportTopics.CATALOG,
                  MaintenanceTransportTopics.ESTIMATE,
                  MaintenanceTransportTopics.REPAIR,
                  MaintenanceTransportTopics.PROPERTY_DISPOSITION,
                  "rwms.maintenance.unapproved.v1"));
      case "wrong-order" -> {
        List<String> reordered = new ArrayList<>(MaintenanceTransportTopics.OUTPUTS);
        Collections.swap(reordered, 0, 1);
        kafka.setDestinations(reordered);
      }
      case "missing-broker" -> environment = environmentWithoutBroker();
      case "blank-broker" ->
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", " ");
      case "malformed-broker" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "https://kafka.internal:9092");
      case "missing-port" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "kafka.internal");
      case "invalid-port" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "kafka.internal:70000");
      case "empty-broker-entry" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "kafka.internal:9092,");
      case "loopback-broker" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "[::1]:9092");
      case "mixed-loopback-broker" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers",
              "kafka-a.internal:9092,localhost:9092");
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
      case "request-timeout" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms", "0");
      case "delivery-timeout" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms", "0");
      case "max-block" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.max.block.ms", "0");
      case "delivery-before-request" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms", "4000");
      case "invalid-lease" -> lease = Duration.ZERO;
      case "publish-wait" -> lease = Duration.ofSeconds(20);
      default -> throw new IllegalArgumentException("Unknown test mutation");
    }

    MockEnvironment configuredEnvironment = environment;
    Duration configuredLease = lease;
    assertThatThrownBy(
            () ->
                validator(
                        configuredEnvironment,
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka,
                        configuredLease,
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class);
  }

  @ParameterizedTest(name = "retains production dependency guard: {0}")
  @ValueSource(strings = {"dependencies-disabled", "auth-bypass", "gateway-not-ready"})
  void retainsExistingProductionDependencyAndAuthenticationGuards(String unsafeProperty) {
    boolean dependenciesEnabled = !unsafeProperty.equals("dependencies-disabled");
    boolean authBypass = unsafeProperty.equals("auth-bypass");
    boolean gatewayReady = !unsafeProperty.equals("gateway-not-ready");

    assertThatThrownBy(
            () ->
                validator(
                        productionEnvironment(),
                        dependenciesEnabled,
                        authBypass,
                        gatewayReady,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("real dependencies")
        .hasMessageNotContaining(CLIENT_SECRET);
  }

  @Test
  void validatesProductionDependencyPropertiesWithoutExposingSecret() {
    MaintenanceDependencyProperties invalid =
        new MaintenanceDependencyProperties(
            true,
            "",
            "maintenance-service",
            CLIENT_SECRET,
            "https://asset.internal",
            "https://task-board.internal",
            "https://media.internal",
            "https://logistics.internal",
            "https://warehouse.internal",
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));

    assertThatThrownBy(
            () ->
                validator(
                        productionEnvironment(),
                        true,
                        false,
                        true,
                        invalid,
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("token-uri")
        .hasMessageNotContaining(CLIENT_SECRET);
  }

  @ParameterizedTest(name = "rejects missing delivery bean: {0}")
  @ValueSource(strings = {"outbox-relay", "dlt-relay", "binding"})
  void rejectsMissingDeliveryBean(String missingBean) {
    assertThatThrownBy(
            () ->
                validator(
                        safeEnvironment(),
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        !missingBean.equals("outbox-relay"),
                        !missingBean.equals("dlt-relay"),
                        !missingBean.equals("binding"))
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("bean");
  }

  @Test
  void brokerValidationDoesNotExposeConfiguredCredentials() {
    MockEnvironment environment = safeEnvironment();
    environment.setProperty(
        "spring.cloud.stream.kafka.binder.brokers",
        "maintenance-user:maintenance-password@kafka.internal:9092");

    assertThatThrownBy(
            () ->
                validator(
                        environment,
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("maintenance-user")
        .hasMessageNotContaining("maintenance-password");
  }

  @Test
  void acceptsCompleteProductionConfiguration() {
    assertThatCode(
            () ->
                validator(
                        productionEnvironment(),
                        true,
                        false,
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void baseConfigurationRequiresExplicitKafkaAndCanonicalFiveTopicOrder()
      throws IOException {
    var source =
        new YamlPropertySourceLoader()
            .load("maintenance-application", new ClassPathResource("application.yaml"))
            .getFirst();
    var devSource =
        new YamlPropertySourceLoader()
            .load("maintenance-dev", new ClassPathResource("application-dev.yaml"))
            .getFirst();

    assertThat(source.getProperty("rwms.platform.kafka.enabled"))
        .isEqualTo("${MAINTENANCE_KAFKA_ENABLED}");
    for (int index = 0; index < MaintenanceTransportTopics.OUTPUTS.size(); index++) {
      assertThat(source.getProperty("rwms.platform.kafka.destinations[" + index + "]"))
          .isEqualTo(MaintenanceTransportTopics.OUTPUTS.get(index));
    }
    assertThat(source.getProperty("rwms.platform.kafka.destinations[5]")).isNull();
    assertThat(source.getProperty("spring.cloud.stream.kafka.default.producer.sync"))
        .isEqualTo(true);
    assertThat(source.getProperty("spring.cloud.stream.kafka.binder.auto-create-topics"))
        .isEqualTo(false);
    assertThat(devSource.getProperty("rwms.platform.kafka.enabled"))
        .isEqualTo("${MAINTENANCE_KAFKA_ENABLED:false}");
  }

  private static MaintenanceProductionSafetyValidator validator(
      MockEnvironment environment,
      boolean dependenciesEnabled,
      boolean authBypass,
      boolean gatewayReady,
      MaintenanceDependencyProperties dependencyProperties,
      RwmsKafkaProperties kafka,
      Duration lease,
      boolean outboxRelayPresent,
      boolean dltRelayPresent,
      boolean bindingPresent) {
    MaintenanceDependencyGateway gateway = mock(MaintenanceDependencyGateway.class);
    when(gateway.productionReady()).thenReturn(gatewayReady);
    MaintenanceOutboxProperties outbox = new MaintenanceOutboxProperties();
    outbox.setLeaseDuration(lease);
    return new MaintenanceProductionSafetyValidator(
        environment,
        dependenciesEnabled,
        authBypass,
        gateway,
        dependencyProperties,
        kafka,
        outbox,
        provider(MaintenanceKafkaOutboxRelay.class, outboxRelayPresent),
        provider(MaintenanceSanitizedDltRelay.class, dltRelayPresent),
        provider(MaintenanceKafkaOutputBindingInitializer.class, bindingPresent));
  }

  private static RwmsKafkaProperties kafka(boolean enabled) {
    return new RwmsKafkaProperties(enabled, MaintenanceTransportTopics.OUTPUTS);
  }

  private static MaintenanceDependencyProperties validDependencies() {
    return new MaintenanceDependencyProperties(
        true,
        "https://auth.internal/oauth/token",
        "maintenance-service",
        CLIENT_SECRET,
        "https://asset.internal",
        "https://task-board.internal",
        "https://media.internal",
        "https://logistics.internal",
        "https://warehouse.internal",
        Duration.ofSeconds(2),
        Duration.ofSeconds(5));
  }

  private static MockEnvironment productionEnvironment() {
    MockEnvironment environment = safeEnvironment();
    environment.setActiveProfiles("prod");
    return environment;
  }

  private static MockEnvironment safeEnvironment() {
    return environmentWithoutBroker()
        .withProperty(
            "spring.cloud.stream.kafka.binder.brokers",
            "maintenance-kafka-a.internal:9092,maintenance-kafka-b.internal:9092");
  }

  private static MockEnvironment environmentWithoutBroker() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("stage");
    return environment
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
