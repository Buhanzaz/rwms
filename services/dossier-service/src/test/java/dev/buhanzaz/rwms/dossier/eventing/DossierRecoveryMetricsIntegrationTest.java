package dev.buhanzaz.rwms.dossier.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.dossier.DossierServiceApplication;
import dev.buhanzaz.rwms.dossier.domain.DossierAggregateCheckpoint;
import dev.buhanzaz.rwms.dossier.domain.DossierDltFailureCode;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierProjectionGeneration;
import dev.buhanzaz.rwms.dossier.domain.DossierProducer;
import dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter;
import dev.buhanzaz.rwms.dossier.domain.DossierSourceFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedFact;
import dev.buhanzaz.rwms.dossier.domain.DossierUnlinkedReason;
import dev.buhanzaz.rwms.dossier.repository.DossierAggregateCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierProjectionGenerationRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
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
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves dossier recovery gauges against a clean Flyway-managed PostgreSQL schema and JPA startup.
 *
 * <p>The fixture stores every state through the service's aggregate factories and repositories so
 * the test exercises the actual derived and JPQL observation queries rather than mock results.
 */
@SpringBootTest(
    classes = {
      DossierServiceApplication.class,
      DossierRecoveryMetricsIntegrationTest.FixedClockConfiguration.class
    },
    properties = {
      "rwms.platform.kafka.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@ActiveProfiles("test")
class DossierRecoveryMetricsIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("dossier_recovery_metrics");
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 8, 9, 9, 0, 0, 0, ZoneOffset.UTC);
  private static final String FIRST_DIGEST = "a".repeat(64);
  private static final String SECOND_DIGEST = "b".repeat(64);
  private static final String THIRD_DIGEST = "c".repeat(64);
  private static final String FOURTH_DIGEST = "d".repeat(64);
  private static final List<String> METRIC_NAMES =
      List.of(
          "rwms.dossier.recovery.checkpoints.blocked",
          "rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds",
          "rwms.dossier.recovery.unlinked-facts.unresolved",
          "rwms.dossier.recovery.dlt.coverage.unresolved",
          "rwms.dossier.activity-outbox.backlog",
          "rwms.dossier.activity-outbox.backlog.oldest.age.seconds",
          "rwms.dossier.activity-outbox.terminal",
          "rwms.dossier.recovery.dlt.backlog",
          "rwms.dossier.recovery.dlt.backlog.oldest.age.seconds",
          "rwms.dossier.recovery.dlt.terminal");

  static {
    POSTGRES.start();
  }

  @Autowired private MeterRegistry registry;
  @Autowired private Flyway flyway;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private DossierAggregateCheckpointRepository checkpoints;
  @Autowired private DossierUnlinkedFactRepository unlinkedFacts;
  @Autowired private DossierSanitizedDeadLetterRepository deadLetters;
  @Autowired private DossierOutboxEventRepository activityOutbox;
  @Autowired private DossierProjectionGenerationRepository generations;
  @Autowired private DossierSourceFactRepository sourceFacts;

  /** Supplies the test database and fail-closed service settings without a Kafka broker. */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("DOSSIER_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("DOSSIER_DB_USERNAME", POSTGRES::getUsername);
    registry.add("DOSSIER_DB_PASSWORD", POSTGRES::getPassword);
    registry.add("AUTH_ISSUER", () -> "http://issuer.invalid");
    registry.add("AUTH_AUDIENCE", () -> "rwms-services");
    registry.add("PANEL_ORIGIN", () -> "http://localhost:5173");
    registry.add("DOSSIER_CURSOR_SECRET", () -> "dossier-recovery-metrics-test-cursor-secret");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:65535/jwks");
  }

  /** Stops the isolated PostgreSQL container after the Spring context has closed. */
  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void cleanJpaContextReadsEmptyThenPersistedRecoveryAndRelayState() {
    assertThat(flyway.info().current()).isNotNull();
    assertThat(entityManagerFactory.isOpen()).isTrue();
    assertEveryGaugeIsZero();

    persistObservedState();

    assertThat(metric("rwms.dossier.recovery.checkpoints.blocked")).isEqualTo(1.0);
    assertThat(metric("rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds"))
        .isEqualTo(120.0);
    assertThat(metric("rwms.dossier.recovery.unlinked-facts.unresolved")).isEqualTo(1.0);
    assertThat(metric("rwms.dossier.recovery.dlt.coverage.unresolved")).isEqualTo(1.0);
    assertThat(metric("rwms.dossier.activity-outbox.backlog")).isEqualTo(2.0);
    assertThat(metric("rwms.dossier.activity-outbox.backlog.oldest.age.seconds"))
        .isEqualTo(90.0);
    assertThat(metric("rwms.dossier.activity-outbox.terminal")).isEqualTo(1.0);
    assertThat(metric("rwms.dossier.recovery.dlt.backlog")).isEqualTo(2.0);
    assertThat(metric("rwms.dossier.recovery.dlt.backlog.oldest.age.seconds")).isEqualTo(90.0);
    assertThat(metric("rwms.dossier.recovery.dlt.terminal")).isEqualTo(1.0);
    assertNoDynamicRecoveryLabels();
  }

  /** Asserts the clean Flyway/JPA schema has no recovery or relay work. */
  private void assertEveryGaugeIsZero() {
    for (String metricName : METRIC_NAMES) {
      assertThat(metric(metricName)).as(metricName).isZero();
    }
  }

  /**
   * Stores one member of every counted state and excluded rows that prove status and coverage
   * filters are read-only observations rather than state transitions.
   */
  private void persistObservedState() {
    DossierAggregateCheckpoint checkpoint =
        DossierAggregateCheckpoint.start(
            "dossier-recovery-metrics-test",
            DossierProducer.ASSET,
            "rwms.asset.rental-item.v1",
            "RENTAL_ITEM",
            UUID.randomUUID(),
            -1,
            NOW.minusSeconds(120));
    checkpoint.blockGap(1, NOW.minusSeconds(120));
    checkpoints.saveAndFlush(checkpoint);

    DossierProjectionGeneration generation =
        generations.saveAndFlush(DossierProjectionGeneration.building(NOW.minusSeconds(200)));
    UUID unresolvedSourceEventId = UUID.randomUUID();
    UUID resolvedSourceEventId = UUID.randomUUID();
    sourceFacts.saveAllAndFlush(
        List.of(sourceFact(unresolvedSourceEventId, 1), sourceFact(resolvedSourceEventId, 2)));
    DossierUnlinkedFact unresolved =
        unlinkedFact(generation.getId(), unresolvedSourceEventId, NOW.minusSeconds(80));
    DossierUnlinkedFact resolved =
        unlinkedFact(generation.getId(), resolvedSourceEventId, NOW.minusSeconds(70));
    resolved.resolve(NOW.minusSeconds(60));
    unlinkedFacts.saveAllAndFlush(List.of(unresolved, resolved));

    UUID cabinId = UUID.randomUUID();
    DossierSanitizedDeadLetter coveragePending =
        deadLetter(1, FIRST_DIGEST, generation.getId(), cabinId, NOW.minusSeconds(90));
    DossierSanitizedDeadLetter retry =
        deadLetter(2, SECOND_DIGEST, null, null, NOW.minusSeconds(45));
    retry.retry(NOW.minusSeconds(30));
    DossierSanitizedDeadLetter terminal =
        deadLetter(3, THIRD_DIGEST, null, null, NOW.minusSeconds(150));
    terminal.deadLetter();
    DossierSanitizedDeadLetter resolvedCoverage =
        deadLetter(4, FOURTH_DIGEST, generation.getId(), cabinId, NOW.minusSeconds(30));
    resolvedCoverage.resolveCoverage(NOW.minusSeconds(20));
    resolvedCoverage.published(NOW.minusSeconds(10));
    deadLetters.saveAllAndFlush(List.of(coveragePending, retry, terminal, resolvedCoverage));

    UUID pendingSourceEventId = UUID.randomUUID();
    UUID retrySourceEventId = UUID.randomUUID();
    UUID terminalSourceEventId = UUID.randomUUID();
    UUID publishedSourceEventId = UUID.randomUUID();
    sourceFacts.saveAllAndFlush(
        List.of(
            sourceFact(pendingSourceEventId, 3),
            sourceFact(retrySourceEventId, 4),
            sourceFact(terminalSourceEventId, 5),
            sourceFact(publishedSourceEventId, 6)));
    DossierOutboxEvent pending =
        outbox(pendingSourceEventId, FIRST_DIGEST, NOW.minusSeconds(90));
    DossierOutboxEvent retryOutbox =
        outbox(retrySourceEventId, SECOND_DIGEST, NOW.minusSeconds(45));
    retryOutbox.retry(NOW.minusSeconds(30));
    DossierOutboxEvent terminalOutbox =
        outbox(terminalSourceEventId, THIRD_DIGEST, NOW.minusSeconds(150));
    terminalOutbox.deadLetter();
    DossierOutboxEvent publishedOutbox =
        outbox(publishedSourceEventId, FOURTH_DIGEST, NOW.minusSeconds(30));
    publishedOutbox.published(NOW.minusSeconds(10));
    activityOutbox.saveAllAndFlush(List.of(pending, retryOutbox, terminalOutbox, publishedOutbox));
  }

  /**
   * Creates an immutable local journal fact required by unlinked and activity-outbox foreign keys.
   */
  private static DossierSourceFact sourceFact(UUID eventId, long sourceOffset) {
    UUID aggregateId = UUID.randomUUID();
    return DossierSourceFact.record(
        eventId,
        DossierProducer.ASSET,
        "rwms.asset.rental-item.v1",
        0,
        sourceOffset,
        aggregateId,
        "RENTAL_ITEM",
        aggregateId,
        sourceOffset,
        "asset.rental-item.created.v1",
        1,
        FIRST_DIGEST,
        "{}",
        null,
        NOW.minusSeconds(200),
        null,
        null,
        null,
        UUID.randomUUID(),
        null,
        null,
        null,
        null,
        null,
        NOW.minusSeconds(200));
  }

  /**
   * Creates retained global unresolved evidence without making this test invent a cabin visibility
   * decision.
   */
  private static DossierUnlinkedFact unlinkedFact(
      UUID generationId, UUID sourceEventId, OffsetDateTime recordedAt) {
    return DossierUnlinkedFact.record(
        generationId,
        sourceEventId,
        null,
        DossierUnlinkedReason.SUBJECT_NOT_PROVIDED,
        DossierProducer.ASSET,
        "RENTAL_ITEM",
        UUID.randomUUID(),
        SECOND_DIGEST,
        recordedAt);
  }

  /** Creates one deterministic sanitized-DLT record with optional exact coverage. */
  private static DossierSanitizedDeadLetter deadLetter(
      long sourceOffset,
      String digest,
      UUID coverageGenerationId,
      UUID coverageSubjectCabinId,
      OffsetDateTime failedAt) {
    return DossierSanitizedDeadLetter.pending(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "rwms.asset.rental-item.v1",
        1,
        sourceOffset,
        digest,
        digest,
        DossierDltFailureCode.PROCESSING_FAILED,
        coverageGenerationId,
        coverageSubjectCabinId,
        failedAt);
  }

  /**
   * Creates one sanitized activity outbox fact whose source journal identity is already retained.
   */
  private static DossierOutboxEvent outbox(
      UUID sourceEventId, String digest, OffsetDateTime createdAt) {
    return DossierOutboxEvent.pending(
        UUID.randomUUID(), UUID.randomUUID(), 0, sourceEventId, digest, "{}", createdAt);
  }

  /** Asserts no source, aggregate, cabin, generation, payload, or error label is registered. */
  private void assertNoDynamicRecoveryLabels() {
    for (String metricName : METRIC_NAMES) {
      assertThat(gauge(metricName).getId().getTags())
          .extracting(Tag::getKey)
          .doesNotContain(
              "aggregate",
              "aggregate_id",
              "cabin_id",
              "consumer_group",
              "error",
              "event_id",
              "generation_id",
              "payload",
              "source_topic");
    }
  }

  private double metric(String name) {
    return gauge(name).value();
  }

  private Gauge gauge(String name) {
    Gauge gauge = registry.find(name).gauge();
    assertThat(gauge).as(name).isNotNull();
    return gauge;
  }

  /** Supplies deterministic observation time without changing a production recovery transition. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    /** Provides the fixed scrape-time clock used by the isolated recovery-gauge context. */
    @Bean
    @Primary
    Clock fixedDossierClock() {
      return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    }
  }
}
