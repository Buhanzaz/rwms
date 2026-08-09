package dev.buhanzaz.rwms.asset.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.asset.eventing.AssetKafkaOutboxRelay;
import dev.buhanzaz.rwms.asset.eventing.AssetKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.asset.eventing.AssetOutboxProperties;
import dev.buhanzaz.rwms.asset.eventing.AssetSanitizedDltRelay;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportProperties;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
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

/** Tests the asset startup fence without opening a database, private service, or broker connection. */
class AssetProductionSafetyValidatorTest {
  private static final List<String> DESTINATIONS =
      List.of(
          "rwms.asset.rental-item.v1",
          "rwms.asset.equipment-catalog.v1",
          "rwms.asset.equipment-balance.v1",
          "rwms.asset.equipment-movement.v1",
          "rwms.asset.equipment-allocation-hold.v1",
          "rwms.asset.operation-lease.v1",
          "rwms.asset.classifier.v1",
          "rwms.asset.dlt.v1");

  @Test
  void explicitLocalProfileMayDisableKafkaAndOmitDeliveryBeans() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test");

    assertThatCode(
            () ->
                validator(
                        environment,
                        false,
                        false,
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
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test", "prod");

    assertThatThrownBy(
            () ->
                validator(
                        environment,
                        true,
                        true,
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
      case "destination" -> kafka.setDestinations(DESTINATIONS.subList(0, 7));
      case "blank-broker" ->
          environment.setProperty("spring.cloud.stream.kafka.binder.brokers", " ");
      case "loopback-broker" ->
          environment.setProperty(
              "spring.cloud.stream.kafka.binder.brokers", "[::1]:9092");
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
              "spring.cloud.stream.kafka.binder.configuration.max.block.ms", "0");
      case "publish-wait" -> lease = Duration.ofSeconds(20);
      default -> throw new IllegalArgumentException("Unknown test mutation");
    }

    Duration configuredLease = lease;
    assertThatThrownBy(
            () ->
                validator(
                        environment,
                        false,
                        false,
                        kafka,
                        configuredLease,
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .isInstanceOf(IllegalStateException.class);
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
  void acceptsCompleteNonLocalKafkaDeliveryConfiguration() {
    assertThatCode(
            () ->
                validator(
                        safeEnvironment(),
                        false,
                        false,
                        kafka(true),
                        Duration.ofSeconds(30),
                        true,
                        true,
                        true)
                    .afterSingletonsInstantiated())
        .doesNotThrowAnyException();
  }

  @Test
  void baseConfigurationRequiresExplicitKafkaEnablement() throws IOException {
    var source =
        new YamlPropertySourceLoader()
            .load("asset-application", new ClassPathResource("application.yaml"))
            .getFirst();

    assertThat(source.getProperty("rwms.platform.kafka.enabled"))
        .isEqualTo("${ASSET_KAFKA_ENABLED}");
    assertThat(source.getProperty("spring.cloud.stream.kafka.default.producer.sync"))
        .isEqualTo(true);
    assertThat(source.getProperty("spring.cloud.stream.default.producer.sync")).isNull();
  }

  private static AssetProductionSafetyValidator validator(
      MockEnvironment environment,
      boolean registryEnabled,
      boolean mediaEnabled,
      RwmsKafkaProperties kafka,
      Duration lease,
      boolean outboxRelayPresent,
      boolean dltRelayPresent,
      boolean bindingPresent) {
    AssetOutboxProperties outbox = new AssetOutboxProperties();
    outbox.setLeaseDuration(lease);
    return new AssetProductionSafetyValidator(
        environment,
        warehouseRegistry(registryEnabled),
        mediaImport(mediaEnabled),
        kafka,
        outbox,
        provider(AssetKafkaOutboxRelay.class, outboxRelayPresent),
        provider(AssetSanitizedDltRelay.class, dltRelayPresent),
        provider(AssetKafkaOutputBindingInitializer.class, bindingPresent));
  }

  private static RwmsKafkaProperties kafka(boolean enabled) {
    return new RwmsKafkaProperties(enabled, DESTINATIONS);
  }

  private static WarehouseRegistryProperties warehouseRegistry(boolean enabled) {
    return new WarehouseRegistryProperties(
        enabled, null, null, "asset-service", null, null, null);
  }

  private static MediaAssetImportProperties mediaImport(boolean enabled) {
    return new MediaAssetImportProperties(
        enabled, null, null, "asset-service", null, null, null);
  }

  private static MockEnvironment safeEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("stage");
    return environment
        .withProperty(
            "spring.cloud.stream.kafka.binder.brokers",
            "asset-kafka-a.internal:9092,asset-kafka-b.internal:9092")
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
