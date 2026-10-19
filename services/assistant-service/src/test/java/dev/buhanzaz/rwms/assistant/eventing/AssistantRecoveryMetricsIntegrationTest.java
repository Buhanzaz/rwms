package dev.buhanzaz.rwms.assistant.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.assistant.AssistantServiceApplication;
import dev.buhanzaz.rwms.assistant.domain.AssistantConversation;
import dev.buhanzaz.rwms.assistant.domain.AssistantMessage;
import dev.buhanzaz.rwms.assistant.domain.AssistantToolCall;
import dev.buhanzaz.rwms.assistant.repository.AssistantConversationRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantMessageRepository;
import dev.buhanzaz.rwms.assistant.repository.AssistantToolCallRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

/**
 * Proves assistant recovery gauges against a clean Flyway-managed PostgreSQL schema and JPA
 * startup.
 *
 * <p>The persisted fixture includes one STARTED, one COMPLETED, and one FAILED tool call so the
 * observed count and age prove that valid terminal history is not reported as recovery backlog.
 */
@SpringBootTest(
    classes = AssistantServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.NONE,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.assistant.llm.api-key=test-key",
      "rwms.assistant.llm.require-api-key-on-startup=false",
      "rwms.assistant.kafka.enabled=false"
    })
@ActiveProfiles("test")
class AssistantRecoveryMetricsIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("assistant_recovery_metrics");
  private static final OffsetDateTime EMPTY_OBSERVATION_TIME =
      OffsetDateTime.of(2026, 8, 9, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final String STARTED_COUNT =
      "rwms.assistant.recovery.tool-calls.started";
  private static final String OLDEST_STARTED_AGE =
      "rwms.assistant.recovery.tool-calls.started.oldest.age.seconds";
  private static final List<String> METRIC_NAMES = List.of(STARTED_COUNT, OLDEST_STARTED_AGE);

  static {
    POSTGRES.start();
  }

  @Autowired private Flyway flyway;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private AssistantConversationRepository conversations;
  @Autowired private AssistantMessageRepository messages;
  @Autowired private AssistantToolCallRepository toolCalls;

  /** Supplies the isolated PostgreSQL connection used by Flyway and Hibernate validation. */
  @DynamicPropertySource
  static void postgresProperties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  /** Stops the isolated PostgreSQL container after the Spring context closes. */
  @AfterAll
  static void stopPostgres() {
    POSTGRES.stop();
  }

  @Test
  void cleanJpaContextCountsOnlyStartedRowsAndReportsDeterministicOldestAge() {
    assertThat(flyway.info().current()).isNotNull();
    assertThat(entityManagerFactory.isOpen()).isTrue();

    SimpleMeterRegistry emptyRegistry = metricsRegistry(EMPTY_OBSERVATION_TIME);
    assertThat(metric(emptyRegistry, STARTED_COUNT)).isZero();
    assertThat(metric(emptyRegistry, OLDEST_STARTED_AGE)).isZero();

    persistStartedAndTerminalToolCalls();
    assertThat(toolCalls.count()).isEqualTo(3);
    OffsetDateTime persistedStartedAt =
        toolCalls
            .findOldestCreatedAtByStatus(
                dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus.STARTED)
            .orElseThrow();
    SimpleMeterRegistry populatedRegistry = metricsRegistry(persistedStartedAt.plusSeconds(120));

    assertThat(metric(populatedRegistry, STARTED_COUNT)).isEqualTo(1.0);
    assertThat(metric(populatedRegistry, OLDEST_STARTED_AGE)).isEqualTo(120.0);
    assertThat(populatedRegistry.getMeters())
        .extracting(meter -> meter.getId().getName())
        .containsExactlyInAnyOrderElementsOf(METRIC_NAMES);
    assertThat(METRIC_NAMES.stream().map(name -> populatedRegistry.get(name).gauge()).toList())
        .allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
  }

  /** Persists one recoverable STARTED call and two valid terminal history rows through JPA. */
  private void persistStartedAndTerminalToolCalls() {
    UUID conversationId = UUID.randomUUID();
    conversations.saveAndFlush(
        AssistantConversation.create(
            conversationId,
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "PERSON",
            "Recovery metrics fixture"));
    AssistantMessage turn =
        messages.saveAndFlush(AssistantMessage.user(conversationId, "Find an available cabin"));
    ObjectMapper objectMapper = new ObjectMapper();

    AssistantToolCall started =
        AssistantToolCall.start(
            conversationId,
            turn.getId(),
            "provider-started",
            "search_available_cabins",
            objectMapper.createObjectNode());
    AssistantToolCall completed =
        AssistantToolCall.start(
            conversationId,
            turn.getId(),
            "provider-completed",
            "search_available_cabins",
            objectMapper.createObjectNode());
    completed.complete(objectMapper.createObjectNode().put("result", "ok"));
    AssistantToolCall failed =
        AssistantToolCall.start(
            conversationId,
            turn.getId(),
            "provider-failed",
            "search_available_cabins",
            objectMapper.createObjectNode());
    failed.fail(
        "LOGISTICS_UNAVAILABLE",
        objectMapper.createObjectNode().put("code", "LOGISTICS_UNAVAILABLE"));

    toolCalls.saveAllAndFlush(List.of(started, completed, failed));
  }

  /** Creates an isolated meter registry whose clock only affects displayed metric age. */
  private SimpleMeterRegistry metricsRegistry(OffsetDateTime observationTime) {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    Clock clock = Clock.fixed(observationTime.toInstant(), ZoneOffset.UTC);
    new AssistantRecoveryMetrics(registry, toolCalls, clock);
    return registry;
  }

  /** Reads one required gauge from the supplied isolated registry. */
  private static double metric(SimpleMeterRegistry registry, String name) {
    Gauge gauge = registry.find(name).gauge();
    assertThat(gauge).as(name).isNotNull();
    return gauge.value();
  }
}
