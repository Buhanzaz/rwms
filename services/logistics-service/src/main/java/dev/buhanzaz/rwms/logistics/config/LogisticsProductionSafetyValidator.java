package dev.buhanzaz.rwms.logistics.config;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsKafkaOutboxRelay;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsKafkaOutputBindingInitializer;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsOutboxProperties;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsSanitizedDltRelay;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsTransportTopics;
import dev.buhanzaz.rwms.logistics.inquiry.eventing.RentalInquiryBookedOutboxRelay;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import java.time.Duration;
import java.util.Arrays;
import java.util.Locale;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

/**
 * Rejects logistics startup when owner dependencies or non-local Kafka delivery are incomplete.
 *
 * <p>Dependency and authentication checks retain their production-only meaning. Kafka delivery is
 * required outside explicit {@code dev}/{@code test} profiles, and a simultaneously active
 * production profile takes precedence. The Kafka fence validates the exact canonical primary
 * outputs, explicit non-loopback brokers, acknowledged idempotent publishing, a publish wait
 * shorter than the outbox lease, the rental-inquiry outbox, and every required relay/binding bean.
 */
@Component
@EnableConfigurationProperties(RwmsKafkaProperties.class)
public final class LogisticsProductionSafetyValidator implements SmartInitializingSingleton {
  private final Environment environment;
  private final boolean dependenciesEnabled;
  private final boolean authBypass;
  private final boolean rentalInquiryOutboxEnabled;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsDependencyProperties dependencyProperties;
  private final RwmsKafkaProperties kafka;
  private final LogisticsOutboxProperties outbox;
  private final ObjectProvider<LogisticsKafkaOutboxRelay> outboxRelay;
  private final ObjectProvider<LogisticsSanitizedDltRelay> dltRelay;
  private final ObjectProvider<RentalInquiryBookedOutboxRelay> rentalInquiryRelay;
  private final ObjectProvider<LogisticsKafkaOutputBindingInitializer> outputBinding;

  /** Creates the startup guard from effective configuration and exact required delivery beans. */
  public LogisticsProductionSafetyValidator(
      Environment environment,
      @Value("${rwms.logistics.dependencies.enabled:false}") boolean dependenciesEnabled,
      @Value("${rwms.logistics.security.dev-auth-bypass:false}") boolean authBypass,
      @Value("${rwms.logistics.rental-inquiry.outbox-enabled:true}")
          boolean rentalInquiryOutboxEnabled,
      LogisticsDependencyGateway dependencies,
      LogisticsDependencyProperties dependencyProperties,
      RwmsKafkaProperties kafka,
      LogisticsOutboxProperties outbox,
      ObjectProvider<LogisticsKafkaOutboxRelay> outboxRelay,
      ObjectProvider<LogisticsSanitizedDltRelay> dltRelay,
      ObjectProvider<RentalInquiryBookedOutboxRelay> rentalInquiryRelay,
      ObjectProvider<LogisticsKafkaOutputBindingInitializer> outputBinding) {
    this.environment = environment;
    this.dependenciesEnabled = dependenciesEnabled;
    this.authBypass = authBypass;
    this.rentalInquiryOutboxEnabled = rentalInquiryOutboxEnabled;
    this.dependencies = dependencies;
    this.dependencyProperties = dependencyProperties;
    this.kafka = kafka;
    this.outbox = outbox;
    this.outboxRelay = outboxRelay;
    this.dltRelay = dltRelay;
    this.rentalInquiryRelay = rentalInquiryRelay;
    this.outputBinding = outputBinding;
  }

  /** Validates production dependencies first, then every required non-local Kafka guarantee. */
  @Override
  public void afterSingletonsInstantiated() {
    boolean production = productionProfile();
    if (production) {
      requireProductionDependencies();
    }
    if (explicitLocalProfile(production)) {
      return;
    }
    requireKafkaDelivery();
  }

  /** Requires real validated owner endpoints, a ready gateway, and fail-closed authentication. */
  private void requireProductionDependencies() {
    if (!dependenciesEnabled) {
      throw new IllegalStateException(
          "Production logistics-service requires real private dependencies");
    }
    try {
      dependencyProperties.validated();
    } catch (RuntimeException invalidConfiguration) {
      throw new IllegalStateException(
          "Production logistics-service dependency configuration is invalid");
    }
    if (!dependencies.productionReady()) {
      throw new IllegalStateException(
          "Production logistics-service dependency gateway is not ready");
    }
    if (authBypass) {
      throw new IllegalStateException(
          "Production logistics-service requires fail-closed authentication");
    }
  }

  /** Returns whether either supported production profile is active. */
  private boolean productionProfile() {
    return Arrays.stream(environment.getActiveProfiles())
        .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
  }

  /** Returns whether an explicit local profile may opt out when production is not also active. */
  private boolean explicitLocalProfile(boolean production) {
    return !production
        && Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
  }

  /** Applies the complete non-local Kafka fence without including configured values in failures. */
  private void requireKafkaDelivery() {
    if (!kafka.enabled()) {
      throw new IllegalStateException("Logistics Kafka delivery must be enabled outside dev/test");
    }
    if (!kafka.destinations().equals(LogisticsTransportTopics.PRIMARY_OUTPUTS)) {
      throw new IllegalStateException(
          "Logistics Kafka destinations must exactly match the canonical ordered outputs");
    }
    kafka.validate();
    requireSafeBrokers();
    requireProducerGuarantees();
    requireDeliveryBeans();
  }

  /** Requires every comma-delimited broker to be an explicit safe host and valid TCP port. */
  private void requireSafeBrokers() {
    String brokers = environment.getProperty("spring.cloud.stream.kafka.binder.brokers");
    if (brokers == null || brokers.isBlank()) {
      throw new IllegalStateException("Logistics Kafka brokers are required outside dev/test");
    }
    for (String broker : brokers.split(",", -1)) {
      if (!safeBroker(broker.strip())) {
        throw new IllegalStateException(
            "Logistics Kafka brokers must be explicit non-loopback host:port endpoints");
      }
    }
  }

  /** Verifies synchronous idempotent acknowledgement and the outbox lease timing inequality. */
  private void requireProducerGuarantees() {
    if (environment.getProperty(
        "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
      throw new IllegalStateException("Logistics Kafka forbids topic auto-creation");
    }
    requireTrue(
        "spring.cloud.stream.kafka.default.producer.sync", "synchronous acknowledgement");
    if (!"all"
        .equalsIgnoreCase(
            environment.getProperty(
                "spring.cloud.stream.kafka.binder.configuration.acks", ""))) {
      throw new IllegalStateException("Logistics Kafka producer requires acks=all");
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
          "Logistics Kafka delivery timeout must not be shorter than request timeout");
    }
    Duration lease = outbox.leaseDuration();
    if (lease == null || lease.isZero() || lease.isNegative()) {
      throw new IllegalStateException("Logistics outbox lease must be positive");
    }
    long maximumPublishWait = (long) maxBlock + deliveryTimeout;
    if (Duration.ofMillis(maximumPublishWait).compareTo(lease) >= 0) {
      throw new IllegalStateException(
          "Logistics Kafka publish timeout must be shorter than the outbox lease");
    }
    if (!rentalInquiryOutboxEnabled) {
      throw new IllegalStateException(
          "Logistics rental-inquiry Kafka outbox must be enabled outside dev/test");
    }
  }

  /** Requires the exact typed relay and binding beans needed by all configured output families. */
  private void requireDeliveryBeans() {
    if (outboxRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Logistics Kafka outbox relay bean is required");
    }
    if (dltRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Logistics sanitized DLT relay bean is required");
    }
    if (rentalInquiryRelay.getIfAvailable() == null) {
      throw new IllegalStateException("Logistics rental-inquiry outbox relay bean is required");
    }
    if (outputBinding.getIfAvailable() == null) {
      throw new IllegalStateException("Logistics Kafka output binding bean is required");
    }
  }

  /** Reads one positive millisecond configuration value without echoing it in an error. */
  private int requirePositive(String property) {
    int value = environment.getProperty(property, Integer.class, -1);
    if (value <= 0) {
      throw new IllegalStateException(
          "Logistics Kafka request, delivery and max-block timeouts must be positive");
    }
    return value;
  }

  /** Requires one boolean producer guarantee without echoing its configured value. */
  private void requireTrue(String property, String guarantee) {
    if (!environment.getProperty(property, Boolean.class, false)) {
      throw new IllegalStateException("Logistics Kafka requires " + guarantee);
    }
  }

  /**
   * Parses one broker endpoint without DNS resolution and rejects credentials, URLs, loopback, and
   * wildcard listeners.
   */
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

  /** Returns whether a normalized broker host is local-only or an unspecified listener address. */
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
        || normalized.equals("0:0:0:0:0:0:0:0")
        || normalized.equals("::");
  }
}
