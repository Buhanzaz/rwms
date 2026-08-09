package dev.buhanzaz.rwms.analytics.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.analytics.AnalyticsServiceApplication;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsAggregateCheckpoint;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsDltFailureCode;
import dev.buhanzaz.rwms.analytics.domain.AnalyticsSanitizedDeadLetter;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsAggregateCheckpointRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSanitizedDeadLetterRepository;
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
 * Proves the analytics recovery gauges against a clean Flyway-managed PostgreSQL schema and JPA
 * startup.
 */
@SpringBootTest(
    classes = {
      AnalyticsServiceApplication.class,
      AnalyticsRecoveryMetricsIntegrationTest.FixedClockConfiguration.class
    },
    properties = {
      "rwms.platform.kafka.enabled=false",
      "spring.jpa.hibernate.ddl-auto=validate"
    })
@ActiveProfiles("test")
class AnalyticsRecoveryMetricsIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 8, 9, 9, 0, 0, 0, ZoneOffset.UTC);
  private static final String FIRST_DIGEST = "a".repeat(64);
  private static final String SECOND_DIGEST = "b".repeat(64);
  private static final String THIRD_DIGEST = "c".repeat(64);
  private static final List<String> METRIC_NAMES =
      List.of(
          "rwms.analytics.recovery.gaps.active",
          "rwms.analytics.recovery.gaps.terminal",
          "rwms.analytics.recovery.gaps.oldest.age.seconds",
          "rwms.analytics.recovery.gaps.maximum.attempt",
          "rwms.analytics.recovery.dlt.backlog",
          "rwms.analytics.recovery.dlt.backlog.oldest.age.seconds",
          "rwms.analytics.recovery.dlt.terminal");

  static {
    POSTGRES.start();
  }

  @Autowired private MeterRegistry registry;
  @Autowired private Flyway flyway;
  @Autowired private EntityManagerFactory entityManagerFactory;
  @Autowired private AnalyticsAggregateCheckpointRepository checkpoints;
  @Autowired private AnalyticsSanitizedDeadLetterRepository deadLetters;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("ANALYTICS_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("ANALYTICS_DB_USERNAME", POSTGRES::getUsername);
    registry.add("ANALYTICS_DB_PASSWORD", POSTGRES::getPassword);
    registry.add("AUTH_ISSUER", () -> "http://issuer.invalid");
    registry.add("AUTH_AUDIENCE", () -> "rwms-services");
    registry.add("PANEL_ORIGIN", () -> "http://localhost:5173");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:65535/jwks");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void cleanJpaContextReadsEmptyThenPersistedRecoveryState() {
    assertThat(flyway.info().current()).isNotNull();
    assertThat(flyway.info().applied()).hasSize(1);
    assertThat(entityManagerFactory.isOpen()).isTrue();
    assertEveryGaugeIsZero();

    persistRecoveryState();

    assertThat(metric("rwms.analytics.recovery.gaps.active")).isEqualTo(1.0);
    assertThat(metric("rwms.analytics.recovery.gaps.terminal")).isEqualTo(1.0);
    assertThat(metric("rwms.analytics.recovery.gaps.oldest.age.seconds")).isEqualTo(120.0);
    assertThat(metric("rwms.analytics.recovery.gaps.maximum.attempt")).isEqualTo(4.0);
    assertThat(metric("rwms.analytics.recovery.dlt.backlog")).isEqualTo(2.0);
    assertThat(metric("rwms.analytics.recovery.dlt.backlog.oldest.age.seconds")).isEqualTo(90.0);
    assertThat(metric("rwms.analytics.recovery.dlt.terminal")).isEqualTo(1.0);
    assertNoDynamicRecoveryLabels();
  }

  /** Asserts that a just-migrated schema has no recovery work. */
  private void assertEveryGaugeIsZero() {
    for (String metricName : METRIC_NAMES) {
      assertThat(metric(metricName)).as(metricName).isZero();
    }
  }

  /**
   * Persists one active gap, one terminal gap, and each sanitized DLT state observed by the
   * gauges.
   */
  private void persistRecoveryState() {
    AnalyticsAggregateCheckpoint active = checkpoint(NOW.minusSeconds(60));
    AnalyticsAggregateCheckpoint terminal = checkpoint(NOW.minusSeconds(120));
    for (int attempt = 0; attempt < 4; attempt++) {
      terminal.retryGap(4, NOW.minusSeconds(110 - attempt));
    }
    checkpoints.saveAllAndFlush(List.of(active, terminal));

    AnalyticsSanitizedDeadLetter pending =
        pendingDeadLetter(1, FIRST_DIGEST, NOW.minusSeconds(90));
    AnalyticsSanitizedDeadLetter retry =
        pendingDeadLetter(2, SECOND_DIGEST, NOW.minusSeconds(45));
    retry.retry(NOW.minusSeconds(30));
    AnalyticsSanitizedDeadLetter terminalDeadLetter =
        pendingDeadLetter(3, THIRD_DIGEST, NOW.minusSeconds(150));
    terminalDeadLetter.terminalFailure();
    deadLetters.saveAllAndFlush(List.of(pending, retry, terminalDeadLetter));
  }

  /** Creates a retained gap whose first-seen timestamp is the value observed by the age gauge. */
  private static AnalyticsAggregateCheckpoint checkpoint(OffsetDateTime firstSeenAt) {
    AnalyticsAggregateCheckpoint checkpoint =
        AnalyticsAggregateCheckpoint.start(
            "analytics-recovery-metrics-test",
            "rwms.task-board.group-kpi-day.v1",
            "GROUP_KPI_DAY",
            UUID.randomUUID(),
            firstSeenAt);
    checkpoint.holdGap(2, firstSeenAt);
    return checkpoint;
  }

  /** Creates a pending sanitized DLT record with the supplied immutable failure timestamp. */
  private static AnalyticsSanitizedDeadLetter pendingDeadLetter(
      long sourceOffset, String digest, OffsetDateTime failedAt) {
    return AnalyticsSanitizedDeadLetter.pending(
        UUID.randomUUID(),
        UUID.randomUUID(),
        "rwms.task-board.group-kpi-day.v1",
        0,
        sourceOffset,
        digest,
        digest,
        AnalyticsDltFailureCode.PROCESSING_FAILED,
        failedAt);
  }

  private void assertNoDynamicRecoveryLabels() {
    for (String metricName : METRIC_NAMES) {
      assertThat(gauge(metricName).getId().getTags())
          .extracting(Tag::getKey)
          .doesNotContain(
              "aggregate",
              "aggregate_id",
              "consumer_group",
              "event_id",
              "error",
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

  /** Supplies deterministic observation time without changing production recovery fencing. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    /** Provides the fixed observation time used by age gauges in this isolated context. */
    @Bean
    @Primary
    Clock fixedAnalyticsClock() {
      return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    }
  }
}
