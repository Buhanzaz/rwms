package dev.buhanzaz.rwms.warehouse.config;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseKafkaOutboxRelay;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseOutboxProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup outside explicit local profiles unless warehouse facts have durable Kafka delivery.
 *
 * <p>The guard validates the exact service topic, non-loopback brokers, synchronous idempotent
 * producer acknowledgements, bounded publish time, and the relay/binding beans that drain the
 * transactional outbox. A production profile always takes precedence over a simultaneously active
 * {@code dev} or {@code test} profile.
 */
@Component
@EnableConfigurationProperties(RwmsKafkaProperties.class)
public final class WarehouseProductionSafetyValidator implements SmartInitializingSingleton {
  private static final List<String> REQUIRED_DESTINATIONS =
      List.of("rwms.warehouse.warehouse.v1");

  private final Environment environment;
  private final RwmsKafkaProperties kafka;
  private final WarehouseOutboxProperties outbox;
  private final ObjectProvider<WarehouseKafkaOutboxRelay> relay;
  private final ObjectProvider<WarehouseKafkaOutputBindingInitializer> outputBinding;

  /** Creates the startup guard from effective configuration and the two required delivery beans. */
  public WarehouseProductionSafetyValidator(
      Environment environment,
      RwmsKafkaProperties kafka,
      WarehouseOutboxProperties outbox,
      ObjectProvider<WarehouseKafkaOutboxRelay> relay,
      ObjectProvider<WarehouseKafkaOutputBindingInitializer> outputBinding) {
    this.environment = environment;
    this.kafka = kafka;
    this.outbox = outbox;
    this.relay = relay;
    this.outputBinding = outputBinding;
  }

  /** Validates the non-local Kafka delivery boundary after conditional beans have been created. */
  @Override
  public void afterSingletonsInstantiated() {
    if (explicitLocalProfile()) {
      return;
    }
    if (!kafka.enabled()) {
      throw new IllegalStateException(
          "Warehouse Kafka delivery must be enabled outside dev/test");
    }
    kafka.validate();
    if (!kafka.destinations().equals(REQUIRED_DESTINATIONS)) {
      throw new IllegalStateException(
          "Warehouse Kafka destinations must exactly match the service contract");
    }
    requireSafeBrokers();
    requireProducerGuarantees();
    requireDeliveryBeans();
  }

  private boolean explicitLocalProfile() {
    boolean production =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    return !production
        && Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
  }

  private void requireSafeBrokers() {
    String brokers = environment.getProperty("spring.cloud.stream.kafka.binder.brokers");
    if (brokers == null || brokers.isBlank()) {
      throw new IllegalStateException("Warehouse Kafka brokers are required outside dev/test");
    }
    for (String broker : brokers.split(",", -1)) {
      if (!safeBroker(broker.strip())) {
        throw new IllegalStateException(
            "Warehouse Kafka brokers must be explicit non-loopback host:port endpoints");
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
      throw new IllegalStateException("Warehouse Kafka producer requires acks=all");
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
          "Warehouse Kafka publish timeout must be shorter than the outbox lease");
    }
  }

  private void requireDeliveryBeans() {
    if (relay.getIfAvailable() == null) {
      throw new IllegalStateException("Warehouse Kafka outbox relay bean is required");
    }
    if (outputBinding.getIfAvailable() == null) {
      throw new IllegalStateException("Warehouse Kafka output binding bean is required");
    }
  }

  private int requirePositive(String property) {
    int value = environment.getProperty(property, Integer.class, -1);
    if (value <= 0) {
      throw new IllegalStateException("Warehouse Kafka timeout configuration is required");
    }
    return value;
  }

  private void requireTrue(String property, String guarantee) {
    if (!environment.getProperty(property, Boolean.class, false)) {
      throw new IllegalStateException("Warehouse Kafka requires " + guarantee);
    }
  }

  private void requireTopicAutoCreationDisabled() {
    if (environment.getProperty(
        "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
      throw new IllegalStateException("Warehouse Kafka forbids topic auto-creation");
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
