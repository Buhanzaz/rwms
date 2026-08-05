package dev.buhanzaz.rwms.taskboard.config;

import java.net.URI;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.Set;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.convert.DurationStyle;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TaskBoardProductionSafetyValidator implements ApplicationRunner {
  private final Environment environment;

  @Override
  public void run(ApplicationArguments args) {
    requireText(
        "TASK_BOARD_CLIENT_SECRET",
        "spring.security.oauth2.client.registration.auth-service.client-secret");
    boolean productionProfile =
        Arrays.stream(environment.getActiveProfiles())
            .anyMatch(profile -> profile.equals("prod") || profile.equals("production"));
    boolean localProfile =
        !productionProfile
            && Arrays.stream(environment.getActiveProfiles())
                .anyMatch(profile -> profile.equals("dev") || profile.equals("test"));
    if (!localProfile) requireKafkaCutover();
    List.of(
            new Endpoint("AUTH_ISSUER", "spring.security.oauth2.resourceserver.jwt.issuer-uri"),
            new Endpoint(
                "AUTH_TOKEN_URI", "spring.security.oauth2.client.provider.auth-service.token-uri"),
            new Endpoint("AUTH_WORKER_CREDENTIALS_URL", "rwms.auth.worker-credentials-url"),
            new Endpoint(
                "WAREHOUSE_SERVICE_INTERNAL_BASE_URL", "rwms.warehouse.lifecycle.base-url"))
        .forEach(endpoint -> requireEndpoint(endpoint, !localProfile));
    String origins = requireText("PANEL_ORIGIN/WORKER_ORIGIN", "rwms.cors.allowed-origins");
    for (String origin : origins.split(",")) {
      requireUri("CORS origin", origin.trim(), !localProfile, true);
    }
  }

  private void requireKafkaCutover() {
    if (!environment.getProperty("rwms.platform.kafka.enabled", Boolean.class, false)) {
      throw new IllegalStateException("TASK_BOARD_KAFKA_ENABLED должен быть true вне dev/test");
    }
    List<String> requiredDestinations =
        List.of(
            "rwms.task-board.worker-class.v1",
            "rwms.task-board.worker.v1",
            "rwms.task-board.worker-group.v1",
            "rwms.task-board.work-queue.v1",
            "rwms.task-board.queue-usage-reference.v1",
            "rwms.task-board.board-task.v1",
            "rwms.task-board.queue-entry.v1",
            "rwms.task-board.entry-owner-proof.v1",
            "rwms.task-board.task-evidence.v1",
            "rwms.task-board.group-kpi-day.v1");
    for (int index = 0; index < requiredDestinations.size(); index++) {
      String configured =
          environment.getProperty("rwms.platform.kafka.destinations[" + index + "]");
      if (!requiredDestinations.get(index).equals(configured)) {
        throw new IllegalStateException(
            "Task-board Kafka destinations должны точно соответствовать контракту F4T");
      }
    }
    if (environment.getProperty(
            "rwms.platform.kafka.destinations[" + requiredDestinations.size() + "]")
        != null) {
      throw new IllegalStateException(
          "Task-board Kafka destinations должны точно соответствовать контракту F4T");
    }
    requireText("TASK_BOARD_KAFKA_BROKERS", "spring.cloud.stream.kafka.binder.brokers");
    if (environment.getProperty(
        "spring.cloud.stream.kafka.binder.auto-create-topics", Boolean.class, true)) {
      throw new IllegalStateException("Kafka topic auto-creation запрещён вне local dev");
    }
    if (!environment.getProperty(
        "spring.cloud.stream.kafka.default.producer.sync", Boolean.class, false)) {
      throw new IllegalStateException(
          "Task-board Kafka producer должен ждать broker acknowledgement");
    }
    int requestTimeout =
        requiredPositiveInteger(
            "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms");
    int deliveryTimeout =
        requiredPositiveInteger(
            "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms");
    int maxBlock =
        requiredPositiveInteger("spring.cloud.stream.kafka.binder.configuration.max.block.ms");
    int rebootstrap =
        requiredPositiveInteger(
            "spring.cloud.stream.kafka.binder.configuration.metadata.recovery.rebootstrap.trigger.ms");
    Duration leaseDuration =
        DurationStyle.detectAndParse(
            environment.getProperty(
                "rwms.task-board.eventing.outbox.lease-duration", "30s"));
    if (rebootstrap != 1_000) {
      throw new IllegalStateException("Task-board Kafka producer recovery должен быть 1s");
    }
    long worstCasePublishWait = (long) maxBlock + deliveryTimeout;
    if (deliveryTimeout < requestTimeout
        || Duration.ofMillis(worstCasePublishWait).compareTo(leaseDuration) >= 0) {
      throw new IllegalStateException(
          "Task-board Kafka publish timeout должен быть меньше outbox lease");
    }
    Set<String> functions =
        Set.of(requireText("TASK_BOARD_KAFKA_FUNCTIONS", "spring.cloud.function.definition").split(";"));
    Set<String> requiredFunctions =
        Set.of(
            "taskBoardWorkerClassEvents",
            "taskBoardWorkerEvents",
            "taskBoardWorkerGroupEvents",
            "taskBoardWorkQueueEvents",
            "taskBoardQueueUsageReferenceEvents",
            "taskBoardBoardTaskEvents",
            "taskBoardQueueEntryEvents",
            "taskBoardMediaEvents",
            "taskBoardWarehouseEvents");
    if (!functions.equals(requiredFunctions)) {
      throw new IllegalStateException(
          "Task-board Kafka consumer functions должны точно соответствовать F4T");
    }
    requireConsumerBinding("taskBoardWorkerClassEvents-in-0", requiredDestinations.get(0));
    requireConsumerBinding("taskBoardWorkerEvents-in-0", requiredDestinations.get(1));
    requireConsumerBinding("taskBoardWorkerGroupEvents-in-0", requiredDestinations.get(2));
    requireConsumerBinding("taskBoardWorkQueueEvents-in-0", requiredDestinations.get(3));
    requireConsumerBinding(
        "taskBoardQueueUsageReferenceEvents-in-0", requiredDestinations.get(4));
    requireConsumerBinding("taskBoardBoardTaskEvents-in-0", requiredDestinations.get(5));
    requireConsumerBinding("taskBoardQueueEntryEvents-in-0", requiredDestinations.get(6));
    requireConsumerBinding(
        "taskBoardMediaEvents-in-0",
        "rwms.media.media.v1",
        "task-board-worker-evidence-v1");
    requireConsumerBinding(
        "taskBoardWarehouseEvents-in-0",
        "rwms.warehouse.warehouse.v1",
        "task-board-warehouse-metadata-v1");
  }

  private int requiredPositiveInteger(String property) {
    int value = environment.getProperty(property, Integer.class, -1);
    if (value <= 0) {
      throw new IllegalStateException("Task-board Kafka timeout configuration обязательна");
    }
    return value;
  }

  private void requireConsumerBinding(String binding, String destination) {
    requireConsumerBinding(binding, destination, "task-board-shadow-v1");
  }

  private void requireConsumerBinding(String binding, String destination, String consumerGroup) {
    String prefix = "spring.cloud.stream.bindings." + binding;
    if (!destination.equals(environment.getProperty(prefix + ".destination"))) {
      throw new IllegalStateException(
          "Task-board Kafka input destination не соответствует контракту F4T");
    }
    if (!consumerGroup.equals(environment.getProperty(prefix + ".group"))) {
      throw new IllegalStateException(
          "Task-board Kafka consumer group не соответствует контракту");
    }
    if (environment.getProperty(prefix + ".consumer.max-attempts", Integer.class, -1) != 1) {
      throw new IllegalStateException(
          "Binder retry должен быть отключён в пользу bounded task-board retry");
    }
    String kafkaPrefix =
        "spring.cloud.stream.kafka.bindings." + binding + ".consumer";
    if (environment.getProperty(kafkaPrefix + ".enable-dlq", Boolean.class, true)) {
      throw new IllegalStateException("Raw binder DLT запрещён для task-board events");
    }
    if (!"taskBoardFailClosedConsumerErrorHandler"
        .equals(environment.getProperty(kafkaPrefix + ".common-error-handler-bean-name"))) {
      throw new IllegalStateException(
          "Task-board Kafka consumer обязан использовать fail-closed handler");
    }
  }

  private void requireEndpoint(Endpoint endpoint, boolean requireHttps) {
    requireUri(
        endpoint.environmentName(),
        requireText(endpoint.environmentName(), endpoint.propertyName()),
        requireHttps,
        false);
  }

  private String requireText(String environmentName, String propertyName) {
    String value = environment.getProperty(propertyName);
    if (value == null || value.isBlank()) {
      throw new IllegalStateException(environmentName + " обязателен");
    }
    return value;
  }

  private void requireUri(String name, String value, boolean requireHttps, boolean originOnly) {
    try {
      URI uri = URI.create(value);
      if (uri.getHost() == null
          || uri.getUserInfo() != null
          || uri.getQuery() != null
          || uri.getFragment() != null
          || (originOnly
              && uri.getPath() != null
              && !uri.getPath().isEmpty()
              && !"/".equals(uri.getPath()))
          || (requireHttps && !"https".equalsIgnoreCase(uri.getScheme()))) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      String requirement = requireHttps ? "абсолютным HTTPS URI" : "абсолютным URI";
      throw new IllegalStateException(name + " должен быть " + requirement, exception);
    }
  }

  private record Endpoint(String environmentName, String propertyName) {}
}
