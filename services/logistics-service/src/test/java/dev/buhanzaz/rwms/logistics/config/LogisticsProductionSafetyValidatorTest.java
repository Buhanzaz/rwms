package dev.buhanzaz.rwms.logistics.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsKafkaOutboxRelay;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsOutboxProperties;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsSanitizedDltRelay;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsTransportTopics;
import dev.buhanzaz.rwms.logistics.inquiry.eventing.RentalInquiryBookedOutboxRelay;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyProperties;
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

/** Tests every logistics dependency and Kafka startup fence without opening external systems. */
class LogisticsProductionSafetyValidatorTest {
  private static final String CLIENT_SECRET = "logistics-test-client-secret";

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
                        true,
                        false,
                        false,
                        validDependencies(),
                        kafka(false),
                        Duration.ofSeconds(30),
                        false,
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
                        true,
                        validDependencies(),
                        kafka(false),
                        Duration.ofSeconds(30),
                        false,
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
                        false,
                        false,
                        validDependencies(),
                        kafka(false),
                        Duration.ofSeconds(30),
                        false,
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
        "missing-function",
        "wrong-function",
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
        "ipv4-loopback-broker",
        "wildcard-broker",
        "ipv6-wildcard-broker",
        "mixed-loopback-broker",
        "auto-create",
        "async-producer",
        "misplaced-sync-path-only",
        "weak-acks",
        "non-idempotent",
        "request-timeout",
        "delivery-timeout",
        "max-block",
        "delivery-before-request",
        "invalid-lease",
        "publish-wait",
        "rental-inquiry-outbox-disabled"
      })
  void rejectsUnsafeKafkaConfigurationOutsideLocalProfiles(String unsafeProperty) {
    MockEnvironment environment = safeEnvironment();
    RwmsKafkaProperties kafka = kafka(true);
    Duration lease = Duration.ofSeconds(30);
    boolean rentalInquiryOutboxEnabled = true;
    switch (unsafeProperty) {
      case "disabled" -> kafka.setEnabled(false);
      case "missing-function" ->
          environment.setProperty("spring.cloud.function.definition", " ");
      case "wrong-function" ->
          environment.setProperty("spring.cloud.function.definition", "unknownInbound");
      case "empty-destinations" -> kafka.setDestinations(List.of());
      case "missing-destination" ->
          kafka.setDestinations(LogisticsTransportTopics.PRIMARY_OUTPUTS.subList(0, 3));
      case "wrong-destination" ->
          kafka.setDestinations(
              List.of(
                  LogisticsTransportTopics.RETURN,
                  LogisticsTransportTopics.SHIPMENT,
                  LogisticsTransportTopics.TRANSFER,
                  "rwms.logistics.unapproved.v1"));
      case "wrong-order" -> {
        List<String> reordered = new ArrayList<>(LogisticsTransportTopics.PRIMARY_OUTPUTS);
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
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "kafka.internal");
      case "invalid-port" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "kafka.internal:70000");
      case "empty-broker-entry" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "kafka.internal:9092,");
      case "loopback-broker" ->
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "[::1]:9092");
      case "ipv4-loopback-broker" ->
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "127.0.0.1:9092");
      case "wildcard-broker" ->
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "0.0.0.0:9092");
      case "ipv6-wildcard-broker" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "[0:0:0:0:0:0:0:0]:9092");
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
      case "misplaced-sync-path-only" -> environment = misplacedSyncEnvironment();
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
      case "rental-inquiry-outbox-disabled" -> rentalInquiryOutboxEnabled = false;
      default -> throw new IllegalArgumentException("Unknown test mutation");
    }

    MockEnvironment configuredEnvironment = environment;
    Duration configuredLease = lease;
    boolean configuredRentalInquiryOutbox = rentalInquiryOutboxEnabled;
    assertThatThrownBy(
            () ->
                validator(
                        configuredEnvironment,
                        false,
                        false,
                        false,
                        configuredRentalInquiryOutbox,
                        validDependencies(),
                        kafka,
                        configuredLease,
                        true,
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
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining(CLIENT_SECRET);
  }

  @Test
  void validatesProductionDependencyPropertiesWithoutExposingSecret() {
    LogisticsDependencyProperties invalid =
        new LogisticsDependencyProperties(
            true,
            "https://" + CLIENT_SECRET + "@[",
            "logistics-service",
            CLIENT_SECRET,
            "https://asset.internal",
            "https://warehouse.internal",
            "https://task-board.internal",
            "https://maintenance.internal",
            "https://media.internal",
            Duration.ofSeconds(2),
            Duration.ofSeconds(5));

    assertThatThrownBy(
            () ->
                validator(
                        productionEnvironment(),
                        true,
                        false,
                        true,
                        true,
                        invalid,
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("dependency configuration")
        .hasMessageNotContaining(CLIENT_SECRET)
        .hasNoCause();
  }

  @ParameterizedTest(name = "rejects missing delivery bean: {0}")
  @ValueSource(strings = {"outbox-relay", "dlt-relay", "rental-inquiry-relay", "binding"})
  void rejectsMissingDeliveryBean(String missingBean) {
    assertThatThrownBy(
            () ->
                validator(
                        safeEnvironment(),
                        false,
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        !missingBean.equals("outbox-relay"),
                        !missingBean.equals("dlt-relay"),
                        !missingBean.equals("rental-inquiry-relay"),
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
        "logistics-user:logistics-password@kafka.internal:9092");

    assertThatThrownBy(
            () ->
                validator(
                        environment,
                        false,
                        false,
                        false,
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageNotContaining("logistics-user")
        .hasMessageNotContaining("logistics-password");
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
                        true,
                        validDependencies(),
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void baseConfigurationRequiresExplicitKafkaAndCanonicalFourTopicOrder() throws IOException {
    var source =
        new YamlPropertySourceLoader()
            .load("logistics-application", new ClassPathResource("application.yaml"))
            .getFirst();
    var devSource =
        new YamlPropertySourceLoader()
            .load("logistics-dev", new ClassPathResource("application-dev.yaml"))
            .getFirst();

    assertThat(source.getProperty("rwms.platform.kafka.enabled"))
        .isEqualTo("${LOGISTICS_KAFKA_ENABLED}");
    assertThat(source.getProperty("spring.cloud.function.definition"))
        .isEqualTo("${LOGISTICS_KAFKA_FUNCTION_DEFINITION:}");
    for (int index = 0; index < LogisticsTransportTopics.PRIMARY_OUTPUTS.size(); index++) {
      assertThat(source.getProperty("rwms.platform.kafka.destinations[" + index + "]"))
          .isEqualTo(LogisticsTransportTopics.PRIMARY_OUTPUTS.get(index));
    }
    assertThat(source.getProperty("rwms.platform.kafka.destinations[4]")).isNull();
    assertThat(source.getProperty("spring.cloud.stream.kafka.default.producer.sync"))
        .isEqualTo(true);
    assertThat(source.getProperty("spring.cloud.stream.default.producer.sync")).isNull();
    assertThat(source.getProperty("spring.cloud.stream.kafka.binder.auto-create-topics"))
        .isEqualTo(false);
    assertThat(source.getProperty("rwms.logistics.rental-inquiry.outbox-enabled"))
        .isEqualTo("${LOGISTICS_RENTAL_INQUIRY_OUTBOX_ENABLED:true}");
    assertThat(devSource.getProperty("rwms.platform.kafka.enabled"))
        .isEqualTo("${LOGISTICS_KAFKA_ENABLED:false}");
  }

  private static LogisticsProductionSafetyValidator validator(
      MockEnvironment environment,
      boolean dependenciesEnabled,
      boolean authBypass,
      boolean gatewayReady,
      boolean rentalInquiryOutboxEnabled,
      LogisticsDependencyProperties dependencyProperties,
      RwmsKafkaProperties kafka,
      Duration lease,
      boolean outboxRelayPresent,
      boolean dltRelayPresent,
      boolean rentalInquiryRelayPresent,
      boolean bindingPresent) {
    LogisticsDependencyGateway gateway = mock(LogisticsDependencyGateway.class);
    when(gateway.productionReady()).thenReturn(gatewayReady);
    LogisticsOutboxProperties outbox = new LogisticsOutboxProperties();
    outbox.setLeaseDuration(lease);
    return new LogisticsProductionSafetyValidator(
        environment,
        dependenciesEnabled,
        authBypass,
        rentalInquiryOutboxEnabled,
        gateway,
        dependencyProperties,
        kafka,
        outbox,
        provider(LogisticsKafkaOutboxRelay.class, outboxRelayPresent),
        provider(LogisticsSanitizedDltRelay.class, dltRelayPresent),
        provider(RentalInquiryBookedOutboxRelay.class, rentalInquiryRelayPresent),
        provider(LogisticsKafkaOutputBindingInitializer.class, bindingPresent));
  }

  private static RwmsKafkaProperties kafka(boolean enabled) {
    return new RwmsKafkaProperties(enabled, LogisticsTransportTopics.PRIMARY_OUTPUTS);
  }

  private static LogisticsDependencyProperties validDependencies() {
    return new LogisticsDependencyProperties(
        true,
        "https://auth.internal/oauth/token",
        "logistics-service",
        CLIENT_SECRET,
        "https://asset.internal",
        "https://warehouse.internal",
        "https://task-board.internal",
        "https://maintenance.internal",
        "https://media.internal",
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
            "logistics-kafka-a.internal:9092,logistics-kafka-b.internal:9092");
  }

  private static MockEnvironment environmentWithoutBroker() {
    return baseProducerEnvironment()
        .withProperty("spring.cloud.stream.kafka.default.producer.sync", "true");
  }

  private static MockEnvironment misplacedSyncEnvironment() {
    return baseProducerEnvironment()
        .withProperty(
            "spring.cloud.stream.kafka.binder.brokers",
            "logistics-kafka-a.internal:9092,logistics-kafka-b.internal:9092")
        .withProperty("spring.cloud.stream.default.producer.sync", "true");
  }

  private static MockEnvironment baseProducerEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("stage");
    return environment
        .withProperty("spring.cloud.function.definition", "logisticsInbound")
        .withProperty("spring.cloud.stream.kafka.binder.auto-create-topics", "false")
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
