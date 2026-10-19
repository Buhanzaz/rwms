package dev.buhanzaz.rwms.asset.config;

import dev.buhanzaz.rwms.asset.eventing.AssetKafkaOutboxRelay;
import dev.buhanzaz.rwms.asset.eventing.AssetKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.asset.eventing.AssetOutboxProperties;
import dev.buhanzaz.rwms.asset.eventing.AssetSanitizedDltRelay;
import dev.buhanzaz.rwms.asset.integration.media.MediaAssetImportProperties;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when asset production dependencies or non-local Kafka delivery are incomplete.
 *
 * <p>The Kafka guard applies outside explicit {@code dev}/{@code test} profiles and treats any
 * simultaneously active production profile as authoritative. It validates the exact asset topic
 * allow-list, safe brokers, acknowledged idempotent publishing, a publish wait shorter than the
 * outbox lease, and the conditional relay/binding beans that make delivery possible.
 */
@Component
public final class AssetProductionSafetyValidator implements SmartInitializingSingleton {
  private static final List<String> REQUIRED_DESTINATIONS =
      List.of(
          "rwms.asset.rental-item.v1",
          "rwms.asset.equipment-catalog.v1",
          "rwms.asset.equipment-balance.v1",
          "rwms.asset.equipment-movement.v1",
          "rwms.asset.equipment-allocation-hold.v1",
          "rwms.asset.operation-lease.v1",
          "rwms.asset.classifier.v1",
          "rwms.asset.dlt.v1");

  private final Environment environment;
  private final WarehouseRegistryProperties registry;
  private final MediaAssetImportProperties mediaImport;
  private final RwmsKafkaProperties kafka;
  private final AssetOutboxProperties outbox;
  private final ObjectProvider<AssetKafkaOutboxRelay> outboxRelay;
  private final ObjectProvider<AssetSanitizedDltRelay> dltRelay;
  private final ObjectProvider<AssetKafkaOutputBindingInitializer> outputBinding;

  /** Creates the startup guard from effective configuration and required delivery beans. */
  public AssetProductionSafetyValidator(
      Environment environment,
      WarehouseRegistryProperties registry,
      MediaAssetImportProperties mediaImport,
      RwmsKafkaProperties kafka,
      AssetOutboxProperties outbox,
      ObjectProvider<AssetKafkaOutboxRelay> outboxRelay,
      ObjectProvider<AssetSanitizedDltRelay> dltRelay,
      ObjectProvider<AssetKafkaOutputBindingInitializer> outputBinding) {
    this.environment = environment;
    this.registry = registry;
    this.mediaImport = mediaImport;
    this.kafka = kafka;
    this.outbox = outbox;
    this.outboxRelay = outboxRelay;
    this.dltRelay = dltRelay;
    this.outputBinding = outputBinding;
  }

  /** Validates production dependencies and every non-local Kafka delivery prerequisite. */
  @Override
  public void afterSingletonsInstantiated() {
    boolean production = productionProfile();
    if (production && !registry.enabled()) {
      throw new IllegalStateException("Production asset-service requires the warehouse registry client");
    }
    if (production && !mediaImport.enabled()) {
      throw new IllegalStateException(
          "Production asset-service requires the media asset import client");
    }
    if (explicitLocalProfile(production)) {
      return;
    }
    requireKafkaDelivery();
  }

  private void requireKafkaDelivery() {
    if (!kafka.enabled()) {
      throw new IllegalStateException("Asset Kafka delivery must be enabled outside dev/test");
    }
    kafka.validate();
    if (!kafka.destinations().equals(REQUIRED_DESTINATIONS)) {
      throw new IllegalStateException(
          "Asset Kafka destinations must exactly match the service contract");
    }
    requireSafeBrokers();
    requireProducerGuarantees();
    requireDeliveryBeans();
  }

  private boolean productionProfile() {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
  }

  private boolean explicitLocalProfile(boolean production) {
    return !production
        && Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
  }

  private void requireSafeBrokers() {
    String brokers = environment.getProperty("spring.cloud.stream.kafka.binder.brokers");
    if (brokers == null || brokers.isBlank()) {
      throw new IllegalStateException("Asset Kafka brokers are required outside dev/test");
    }
    for (String broker : brokers.split(",", -1)) {
      if (!safeBroker(broker.strip())) {
        throw new IllegalStateException(
            "Asset Kafka brokers must be explicit non-loopback host:port endpoints");
      }
    }
  }

  private void requireProducerGuarantees() {
    requireTopicAutoCreationDisabled();
    requireTrue(
        "spring.cloud.stream.kafka.default.producer.sync", "synchronous acknowledgement");
    if (!"all"
        .equalsIgnoreCase(
            environment.getProperty(
                "spring.cloud.stream.kafka.binder.configuration.acks", ""))) {
      throw new IllegalStateException("Asset Kafka producer requires acks=all");
    }
    requireTrue(
        "spring.cloud.stream.kafka.binder.configuration.enable.idempotence",
        "producer idempotence");

    int requestTimeout =
        requirePositive("spring.cloud.stream.kafka.binder.configuration.request.timeout.ms");
    int deliveryTimeout =
        requirePositive("spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms");
    int maxBlock =
        requirePositive("spring.cloud.stream.kafka.binder.configuration.max.block.ms");
    Duration lease = outbox.leaseDuration();
    long maximumPublishWait = (long) maxBlock + deliveryTimeout;
    if (lease == null
        || lease.isZero()
        || lease.isNegative()
        || deliveryTimeout < requestTimeout
        || Duration.ofMillis(maximumPublishWait).compareTo(lease) >= 0) {
      throw new IllegalStateException(
          "Asset Kafka publish timeout must be shorter than the outbox lease");
    }
  }

  private void requireDeliveryBeans() {
    if (outboxRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Asset Kafka outbox relay bean is required");
    }
    if (dltRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Asset sanitized DLT relay bean is required");
    }
    if (outputBinding.getIfAvailable() == null) {
      throw new IllegalStateException("Asset Kafka output binding bean is required");
    }
  }

  private int requirePositive(String property) {
    int value = environment.getProperty(property, Integer.class, -1);
    if (value <= 0) {
      throw new IllegalStateException("Asset Kafka timeout configuration is required");
    }
    return value;
  }

  private void requireTrue(String property, String guarantee) {
    if (!environment.getProperty(property, Boolean.class, false)) {
      throw new IllegalStateException("Asset Kafka requires " + guarantee);
    }
  }

  private void requireTopicAutoCreationDisabled() {
    if (environment.getProperty(
        "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
      throw new IllegalStateException("Asset Kafka forbids topic auto-creation");
    }
  }

  private static boolean safeBroker(String broker) {
    if (broker.isEmpty() || broker.contains("://") || broker.contains("/") || broker.contains("@")) {
      return false;
    }
    String host;
    String port;
    if (broker.startsWith("[")) {
      int bracket = broker.indexOf(']');
      if (bracket < 2 || bracket + 1 >= broker.length() || broker.charAt(bracket + 1) != ':') {
        return false;
      }
      host = broker.substring(1, bracket);
      port = broker.substring(bracket + 2);
    } else {
      int colon = broker.lastIndexOf(':');
      if (colon <= 0 || colon == broker.length() - 1 || broker.indexOf(':') != colon) {
        return false;
      }
      host = broker.substring(0, colon);
      port = broker.substring(colon + 1);
    }
    if (host.isBlank() || host.chars().anyMatch(Character::isWhitespace)) {
      return false;
    }
    try {
      int number = Integer.parseInt(port);
      return number > 0 && number <= 65_535 && !loopback(host);
    } catch (NumberFormatException exception) {
      return false;
    }
  }

  private static boolean loopback(String host) {
    String normalized = host.toLowerCase(Locale.ROOT);
    if (normalized.endsWith(".")) {
      normalized = normalized.substring(0, normalized.length() - 1);
    }
    return normalized.equals("localhost")
        || normalized.endsWith(".localhost")
        || normalized.startsWith("127.")
        || normalized.startsWith("::ffff:127.")
        || normalized.equals("::1")
        || normalized.equals("0:0:0:0:0:0:0:1")
        || normalized.equals("0.0.0.0")
        || normalized.equals("::");
  }
}
