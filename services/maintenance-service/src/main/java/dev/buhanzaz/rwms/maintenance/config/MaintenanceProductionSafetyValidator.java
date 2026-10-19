package dev.buhanzaz.rwms.maintenance.config;

import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceKafkaOutboxRelay;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceOutboxProperties;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceSanitizedDltRelay;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceTransportTopics;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Fails startup when production dependencies or non-local Kafka delivery are incomplete.
 *
 * <p>The dependency and authentication checks retain their production-only meaning. Kafka is
 * required outside explicit {@code dev}/{@code test} profiles, and a simultaneously active
 * production profile takes precedence. The Kafka fence validates the canonical five-topic output
 * allow-list, broker and producer safety, bounded publish time, and every conditional delivery
 * bean without collecting dependencies in a universal context or bag.
 */
@Component
public final class MaintenanceProductionSafetyValidator implements SmartInitializingSingleton {
  private final Environment environment;
  private final boolean dependenciesEnabled;
  private final boolean authBypass;
  private final MaintenanceDependencyGateway dependencyGateway;
  private final MaintenanceDependencyProperties dependencyProperties;
  private final RwmsKafkaProperties kafka;
  private final MaintenanceOutboxProperties outbox;
  private final ObjectProvider<MaintenanceKafkaOutboxRelay> outboxRelay;
  private final ObjectProvider<MaintenanceSanitizedDltRelay> dltRelay;
  private final ObjectProvider<MaintenanceKafkaOutputBindingInitializer> outputBinding;

  /** Creates the startup fence from effective settings and the exact required delivery beans. */
  public MaintenanceProductionSafetyValidator(
      Environment environment,
      @Value("${rwms.maintenance.dependencies.enabled:false}") boolean dependenciesEnabled,
      @Value("${rwms.maintenance.security.dev-auth-bypass:false}") boolean authBypass,
      MaintenanceDependencyGateway dependencyGateway,
      MaintenanceDependencyProperties dependencyProperties,
      RwmsKafkaProperties kafka,
      MaintenanceOutboxProperties outbox,
      ObjectProvider<MaintenanceKafkaOutboxRelay> outboxRelay,
      ObjectProvider<MaintenanceSanitizedDltRelay> dltRelay,
      ObjectProvider<MaintenanceKafkaOutputBindingInitializer> outputBinding) {
    this.environment = environment;
    this.dependenciesEnabled = dependenciesEnabled;
    this.authBypass = authBypass;
    this.dependencyGateway = dependencyGateway;
    this.dependencyProperties = dependencyProperties;
    this.kafka = kafka;
    this.outbox = outbox;
    this.outboxRelay = outboxRelay;
    this.dltRelay = dltRelay;
    this.outputBinding = outputBinding;
  }

  /** Validates production dependencies first, then every required non-local Kafka guarantee. */
  @Override
  public void afterSingletonsInstantiated() {
    boolean production = productionProfile();
    if (production
        && (!dependenciesEnabled || authBypass || !dependencyGateway.productionReady())) {
      throw new IllegalStateException(
          "Production maintenance-service requires real dependencies and fail-closed authentication");
    }
    if (production) {
      dependencyProperties.validated();
    }
    if (explicitLocalProfile(production)) {
      return;
    }
    requireKafkaDelivery();
  }

  private boolean productionProfile() {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(value -> value.equals("prod") || value.equals("production"));
  }

  private boolean explicitLocalProfile(boolean production) {
    return !production
        && Arrays.stream(environment.getActiveProfiles())
            .anyMatch(value -> value.equals("dev") || value.equals("test"));
  }

  private void requireKafkaDelivery() {
    if (!kafka.enabled()) {
      throw new IllegalStateException(
          "Maintenance Kafka delivery must be enabled outside dev/test");
    }
    kafka.validate();
    if (!kafka.destinations().equals(MaintenanceTransportTopics.OUTPUTS)) {
      throw new IllegalStateException(
          "Maintenance Kafka destinations must exactly match the canonical ordered outputs");
    }
    requireSafeBrokers();
    requireProducerGuarantees();
    requireDeliveryBeans();
  }

  private void requireSafeBrokers() {
    String brokers = environment.getProperty("spring.cloud.stream.kafka.binder.brokers");
    if (brokers == null || brokers.isBlank()) {
      throw new IllegalStateException("Maintenance Kafka brokers are required outside dev/test");
    }
    for (String broker : brokers.split(",", -1)) {
      if (!safeBroker(broker.strip())) {
        throw new IllegalStateException(
            "Maintenance Kafka brokers must be explicit non-loopback host:port endpoints");
      }
    }
  }

  private void requireProducerGuarantees() {
    if (environment.getProperty(
        "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
      throw new IllegalStateException("Maintenance Kafka forbids topic auto-creation");
    }
    requireTrue(
        "spring.cloud.stream.kafka.default.producer.sync", "synchronous acknowledgement");
    if (!"all"
        .equalsIgnoreCase(
            environment.getProperty(
                "spring.cloud.stream.kafka.binder.configuration.acks", ""))) {
      throw new IllegalStateException("Maintenance Kafka producer requires acks=all");
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
    if (deliveryTimeout < requestTimeout) {
      throw new IllegalStateException(
          "Maintenance Kafka delivery timeout must not be shorter than request timeout");
    }
    Duration lease = outbox.leaseDuration();
    if (lease == null || lease.isZero() || lease.isNegative()) {
      throw new IllegalStateException("Maintenance outbox lease must be positive");
    }
    long maximumPublishWait = (long) maxBlock + deliveryTimeout;
    if (Duration.ofMillis(maximumPublishWait).compareTo(lease) >= 0) {
      throw new IllegalStateException(
          "Maintenance Kafka publish timeout must be shorter than the outbox lease");
    }
  }

  private void requireDeliveryBeans() {
    if (outboxRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Maintenance Kafka outbox relay bean is required");
    }
    if (dltRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Maintenance sanitized DLT relay bean is required");
    }
    if (outputBinding.getIfAvailable() == null) {
      throw new IllegalStateException("Maintenance Kafka output binding bean is required");
    }
  }

  private int requirePositive(String property) {
    int value = environment.getProperty(property, Integer.class, -1);
    if (value <= 0) {
      throw new IllegalStateException(
          "Maintenance Kafka request, delivery and max-block timeouts must be positive");
    }
    return value;
  }

  private void requireTrue(String property, String guarantee) {
    if (!environment.getProperty(property, Boolean.class, false)) {
      throw new IllegalStateException("Maintenance Kafka requires " + guarantee);
    }
  }

  private static boolean safeBroker(String broker) {
    if (broker.isEmpty()
        || broker.contains("://")
        || broker.contains("/")
        || broker.contains("@")
        || broker.contains("?")
        || broker.contains("#")) {
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
    if (host.isBlank()
        || host.chars().anyMatch(Character::isWhitespace)
        || port.isEmpty()
        || !port.chars().allMatch(Character::isDigit)) {
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
