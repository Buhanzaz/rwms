package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.api.InternalQueueReferenceController;
import dev.buhanzaz.rwms.taskboard.config.TaskBoardClientProperties;
import dev.buhanzaz.rwms.taskboard.config.TaskBoardProductionSafetyValidator;
import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.security.QueueRegistryAuthorizer;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import java.net.URI;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.DefaultApplicationArguments;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;

class TaskBoardSecurityPolicyTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000501");

  @Test
  void productionProfileAlwaysOverridesDevelopmentOrTestBypass() {
    for (String[] profiles : List.of(new String[] {"dev", "prod"}, new String[] {"test", "production"})) {
      MockEnvironment environment = new MockEnvironment();
      environment.setActiveProfiles(profiles);
      WarehouseAccessAuthorizer authorizer = new WarehouseAccessAuthorizer(environment, true);

      assertThatThrownBy(() -> authorizer.requireUserScope(jwt("USER", "rwms.read"), "rwms.write"))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void credentialRecoveryTimeoutMustExceedAuthReadTimeout() {
    assertThatThrownBy(
            () ->
                new TaskBoardClientProperties(
                    URI.create("https://auth.example.test/credentials"),
                    Duration.ofSeconds(1),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(5)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void productionSafetyRejectsUserInfoQueryFragmentAndCorsPath() {
    for (String unsafe :
        List.of(
            "https://user@example.test/issuer",
            "https://example.test/issuer?tenant=x",
            "https://example.test/issuer#fragment")) {
      MockEnvironment environment = secureProductionEnvironment();
      environment.setProperty("spring.security.oauth2.resourceserver.jwt.issuer-uri", unsafe);
      assertThatThrownBy(
              () ->
                  new TaskBoardProductionSafetyValidator(environment)
                      .run(new DefaultApplicationArguments(new String[0])))
          .isInstanceOf(IllegalStateException.class);
    }

    MockEnvironment environment = secureProductionEnvironment();
    environment.setProperty("rwms.cors.allowed-origins", "https://panel.example.test/path");
    assertThatThrownBy(
            () ->
                new TaskBoardProductionSafetyValidator(environment)
                    .run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalStateException.class);
  }

  @Test
  void productionSafetyRequiresTheF4tKafkaCutover() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("production");
    environment.setProperty(
        "spring.security.oauth2.client.registration.auth-service.client-secret", "secret");

    assertThatThrownBy(
            () ->
                new TaskBoardProductionSafetyValidator(environment)
                    .run(new DefaultApplicationArguments(new String[0])))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("TASK_BOARD_KAFKA_ENABLED");
  }

  @Test
  void workerIdentityClaimIsRequiredAndMustBeUuid() {
    WarehouseAccessAuthorizer authorizer =
        new WarehouseAccessAuthorizer(new MockEnvironment(), false);
    assertThatThrownBy(() -> authorizer.workerId(jwt("WORKER", "worker.tasks")))
        .isInstanceOf(AccessDeniedException.class)
        .hasMessageContaining("worker_id");
    assertThatThrownBy(
            () -> authorizer.workerId(jwt("WORKER", "worker.tasks", "worker_id", "not-a-uuid")))
        .isInstanceOf(AccessDeniedException.class);

    UUID workerId = UUID.randomUUID();
    assertThat(
            authorizer.workerId(
                jwt("WORKER", "worker.tasks", "worker_id", workerId.toString())))
        .isEqualTo(workerId);
  }

  @Test
  void malformedWarehouseAccessClaimsFailClosed() {
    WarehouseAccessAuthorizer authorizer =
        new WarehouseAccessAuthorizer(new MockEnvironment(), false);
    for (Object malformed :
        List.of(
            "not-a-list",
            List.of("not-an-object"),
            List.of(Map.of("warehouseId", 42, "level", "EDIT")),
            List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", List.of("EDIT"))))) {
      Jwt token = jwt("USER", "rwms.read", "warehouse_access", malformed);
      assertThatThrownBy(
              () -> authorizer.requireWarehouse(token, WAREHOUSE, dev.buhanzaz.rwms.taskboard.security.AccessLevel.VIEW, false))
          .isInstanceOf(AccessDeniedException.class);
    }
  }

  @Test
  void internalQueueRegistryIsFailClosedAndAllowsOnlyConfiguredServiceClient() {
    RegistryService registry = mock(RegistryService.class);
    UUID queueId = UUID.randomUUID();
    QueueReferenceRequest request =
        new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "external-1");
    QueueReferenceDto response =
        new QueueReferenceDto(
            UUID.randomUUID(), 0, queueId, QueueReferenceType.REPAIR_PLAN, "external-1");
    when(registry.registerReference(any(), any())).thenReturn(response);

    assertThatThrownBy(
            () -> new QueueRegistryAuthorizer(List.of()))
        .isInstanceOf(IllegalStateException.class);

    var allowlisted =
        new InternalQueueReferenceController(
            registry, new QueueRegistryAuthorizer(List.of("maintenance-service")));
    assertThatThrownBy(
            () ->
                allowlisted.register(
                    queueRegistryJwt(
                        "USER", "maintenance-service", "maintenance-service", "queue-registry.write"),
                    queueId,
                    request))
        .isInstanceOf(AccessDeniedException.class);
    assertThatThrownBy(
            () ->
                allowlisted.register(
                    queueRegistryJwt(
                        "SERVICE", "maintenance-service", "maintenance-service", "rwms.write"),
                    queueId,
                    request))
        .isInstanceOf(AccessDeniedException.class);
    assertThat(
            allowlisted.register(
                queueRegistryJwt(
                    "SERVICE",
                    "maintenance-service",
                    "maintenance-service",
                    List.of("queue-registry.write")),
                queueId,
                request))
        .isEqualTo(response);
  }

  private Jwt queueRegistryJwt(
      String type, String clientId, String subject, Object scopes) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject(subject)
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", type)
        .claim("client_id", clientId)
        .claim("scope", scopes)
        .build();
  }

  private MockEnvironment secureProductionEnvironment() {
    MockEnvironment environment = new MockEnvironment();
    environment.setActiveProfiles("test", "production");
    environment.setProperty(
        "spring.security.oauth2.client.registration.auth-service.client-secret", "secret");
    environment.setProperty(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", "https://auth.example.test/issuer");
    environment.setProperty(
        "spring.security.oauth2.client.provider.auth-service.token-uri",
        "https://auth.example.test/oauth2/token");
    environment.setProperty(
        "rwms.auth.worker-credentials-url",
        "https://auth.example.test/api/internal/worker-credentials");
    environment.setProperty(
        "rwms.cors.allowed-origins", "https://panel.example.test,https://worker.example.test");
    environment.setProperty("rwms.platform.kafka.enabled", "true");
    List<String> destinations =
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
    for (int index = 0; index < destinations.size(); index++) {
      environment.setProperty(
          "rwms.platform.kafka.destinations[" + index + "]", destinations.get(index));
    }
    environment.setProperty("spring.cloud.stream.kafka.binder.brokers", "kafka:9092");
    environment.setProperty("spring.cloud.stream.kafka.binder.auto-create-topics", "false");
    environment.setProperty("spring.cloud.stream.kafka.default.producer.sync", "true");
    environment.setProperty(
        "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms", "5000");
    environment.setProperty(
        "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms", "15000");
    environment.setProperty(
        "spring.cloud.stream.kafka.binder.configuration.max.block.ms", "5000");
    environment.setProperty(
        "spring.cloud.stream.kafka.binder.configuration.metadata.recovery.rebootstrap.trigger.ms",
        "1000");
    environment.setProperty("rwms.task-board.eventing.outbox.lease-duration", "30s");
    List<String> functions =
        List.of(
            "taskBoardWorkerClassEvents",
            "taskBoardWorkerEvents",
            "taskBoardWorkerGroupEvents",
            "taskBoardWorkQueueEvents",
            "taskBoardQueueUsageReferenceEvents",
            "taskBoardBoardTaskEvents",
            "taskBoardQueueEntryEvents",
            "taskBoardMediaEvents",
            "taskBoardWarehouseEvents");
    environment.setProperty("spring.cloud.function.definition", String.join(";", functions));
    for (int index = 0; index < 7; index++) {
      String binding = functions.get(index) + "-in-0";
      String prefix = "spring.cloud.stream.bindings." + binding;
      environment.setProperty(prefix + ".destination", destinations.get(index));
      environment.setProperty(prefix + ".group", "task-board-shadow-v1");
      environment.setProperty(prefix + ".consumer.max-attempts", "1");
      String kafkaPrefix = "spring.cloud.stream.kafka.bindings." + binding + ".consumer";
      environment.setProperty(kafkaPrefix + ".enable-dlq", "false");
      environment.setProperty(
          kafkaPrefix + ".common-error-handler-bean-name",
          "taskBoardFailClosedConsumerErrorHandler");
    }
    String mediaBinding = "taskBoardMediaEvents-in-0";
    String mediaPrefix = "spring.cloud.stream.bindings." + mediaBinding;
    environment.setProperty(mediaPrefix + ".destination", "rwms.media.media.v1");
    environment.setProperty(mediaPrefix + ".group", "task-board-worker-evidence-v1");
    environment.setProperty(mediaPrefix + ".consumer.max-attempts", "1");
    String mediaKafkaPrefix =
        "spring.cloud.stream.kafka.bindings." + mediaBinding + ".consumer";
    environment.setProperty(mediaKafkaPrefix + ".enable-dlq", "false");
    environment.setProperty(
        mediaKafkaPrefix + ".common-error-handler-bean-name",
        "taskBoardFailClosedConsumerErrorHandler");
    String warehouseBinding = "taskBoardWarehouseEvents-in-0";
    String warehousePrefix = "spring.cloud.stream.bindings." + warehouseBinding;
    environment.setProperty(
        warehousePrefix + ".destination", "rwms.warehouse.warehouse.v1");
    environment.setProperty(
        warehousePrefix + ".group", "task-board-warehouse-metadata-v1");
    environment.setProperty(warehousePrefix + ".consumer.max-attempts", "1");
    String warehouseKafkaPrefix =
        "spring.cloud.stream.kafka.bindings." + warehouseBinding + ".consumer";
    environment.setProperty(warehouseKafkaPrefix + ".enable-dlq", "false");
    environment.setProperty(
        warehouseKafkaPrefix + ".common-error-handler-bean-name",
        "taskBoardFailClosedConsumerErrorHandler");
    return environment;
  }

  private Jwt jwt(String type, String scope, Object... additionalClaim) {
    var builder =
        Jwt.withTokenValue("token")
            .header("alg", "none")
            .subject("subject")
            .issuedAt(Instant.now())
            .expiresAt(Instant.now().plusSeconds(60))
            .claim("principal_type", type)
            .claim("scope", scope)
            .claim("warehouse_id", WAREHOUSE.toString());
    if (additionalClaim.length == 2)
      builder.claim(String.valueOf(additionalClaim[0]), additionalClaim[1]);
    return builder.build();
  }
}
