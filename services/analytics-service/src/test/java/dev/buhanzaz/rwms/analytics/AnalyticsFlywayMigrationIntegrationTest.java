package dev.buhanzaz.rwms.analytics;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.analytics.eventing.AnalyticsEnvelopeValidator;
import dev.buhanzaz.rwms.analytics.eventing.AnalyticsTopics;
import dev.buhanzaz.rwms.analytics.repository.GroupKpiDayEvidenceRepository;
import dev.buhanzaz.rwms.analytics.service.AnalyticsInboxProcessor;
import jakarta.persistence.EntityManagerFactory;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class AnalyticsFlywayMigrationIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void cleanInstallIsRepeatSafeAndPassesJpaValidation() {
    Flyway flyway =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .cleanDisabled(true)
            .outOfOrder(false)
            .load();

    assertThat(flyway.migrate().migrationsExecuted).isOne();
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames())
        .containsExactlyInAnyOrder(
            "analytics_aggregate_checkpoint",
            "analytics_group_kpi_day",
            "analytics_inbox",
            "analytics_partition_checkpoint",
            "analytics_sanitized_dead_letter",
            "analytics_source_fact",
            "flyway_schema_history");

    try (var context =
        new SpringApplicationBuilder(AnalyticsServiceApplication.class)
            .profiles("test")
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "ANALYTICS_DB_URL=" + POSTGRES.getJdbcUrl(),
                "ANALYTICS_DB_USERNAME=" + POSTGRES.getUsername(),
                "ANALYTICS_DB_PASSWORD=" + POSTGRES.getPassword(),
                "AUTH_ISSUER=http://issuer.invalid",
                "AUTH_AUDIENCE=rwms-services",
                "PANEL_ORIGIN=http://localhost:5173",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:65535/jwks",
                "rwms.platform.kafka.enabled=false",
                "rwms.analytics.security.dev-auth-bypass=false")
            .run()) {
      assertThat(context.getBean(EntityManagerFactory.class).isOpen()).isTrue();
      assertGapIsHeldAndDrained(context);
    }
  }

  private void assertGapIsHeldAndDrained(
      org.springframework.context.ApplicationContext context) {
    UUID evidenceId = UUID.randomUUID();
    UUID eventZero = UUID.randomUUID();
    UUID eventOne = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID workerGroupId = UUID.randomUUID();
    String zero =
        envelope(eventZero, evidenceId, warehouseId, workerGroupId, 0, 1200);
    String one =
        envelope(eventOne, evidenceId, warehouseId, workerGroupId, 1, 2400);
    AnalyticsEnvelopeValidator validator = context.getBean(AnalyticsEnvelopeValidator.class);
    AnalyticsInboxProcessor processor = context.getBean(AnalyticsInboxProcessor.class);

    assertThat(
            processor.process(
                validator.validate(
                    AnalyticsTopics.INPUT,
                    0,
                    1,
                    evidenceId,
                    one.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo(AnalyticsInboxProcessor.Outcome.VERSION_GAP);
    assertThat(
            processor.process(
                validator.validate(
                    AnalyticsTopics.INPUT,
                    0,
                    2,
                    evidenceId,
                    zero.getBytes(StandardCharsets.UTF_8))))
        .isEqualTo(AnalyticsInboxProcessor.Outcome.PROCESSED);

    var evidence =
        context.getBean(GroupKpiDayEvidenceRepository.class).findById(evidenceId).orElseThrow();
    assertThat(evidence.getSourceAggregateVersion()).isEqualTo(1);
    assertThat(evidence.getActiveSeconds()).isEqualTo(2400);
  }

  private static String envelope(
      UUID eventId,
      UUID evidenceId,
      UUID warehouseId,
      UUID workerGroupId,
      long aggregateVersion,
      long activeSeconds) {
    return """
        {
          "envelopeVersion":2,
          "eventId":"%s",
          "eventType":"task-board.group-kpi-day.changed.v1",
          "eventVersion":1,
          "occurredAt":"2026-07-30T08:00:00Z",
          "recordedAt":"2026-07-30T08:00:01Z",
          "producer":"task-board-service",
          "aggregateType":"GROUP_KPI_DAY",
          "aggregateId":"%s",
          "aggregateVersion":%d,
          "correlation":{"correlationId":"%s","causationId":null},
          "actorRef":null,
          "payload":{
            "evidenceId":"%s",
            "warehouseId":"%s",
            "workerGroupId":"%s",
            "localDate":"2026-07-30",
            "dataAvailableFrom":"2026-07-01",
            "formulaVersion":"kpi-v1",
            "completedBudgetSeconds":3600,
            "earnedRemainingSeconds":1800,
            "activeSeconds":%d,
            "penalizedIdleSeconds":600,
            "completedTaskCount":1,
            "openState":null,
            "openStateStartedAt":null,
            "penaltyStartsAt":null,
            "nextTransitionAt":null,
            "asOf":"2026-07-30T08:00:00Z"
          }
        }
        """
        .formatted(
            eventId,
            evidenceId,
            aggregateVersion,
            UUID.randomUUID(),
            evidenceId,
            warehouseId,
            workerGroupId,
            activeSeconds);
  }

  private List<String> tableNames() {
    return jdbc.queryForList(
        """
        select table_name
        from information_schema.tables
        where table_schema='public'
        order by table_name
        """,
        String.class);
  }
}
