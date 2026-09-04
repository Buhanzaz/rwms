package dev.buhanzaz.rwms.logistics.customer.claims;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies the V92 lifecycle backfill, append-only action ledger, and optimistic-version storage. */
@Testcontainers
class CustomerCabinProblemLifecycleFlywayIntegrationTest {
  private static final UUID COMPANY =
      UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48");
  private static final UUID PROBLEM =
      UUID.fromString("00000000-0000-0000-0000-000000000203");

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

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
  void v92BackfillsLegacyEvidenceAndFencesActionHistoryByProblemAndVersion() {
    flyway("91").migrate();
    seedLegacyProblem();

    Flyway current = flyway("92");
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();

    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.idx_customer_cabin_problem_company_deadline') is not null",
                Boolean.class))
        .isTrue();

    assertThat(
            jdbc.queryForObject(
                "select lifecycle_status from customer_cabin_problem where id=?", String.class, PROBLEM))
        .isEqualTo("OPEN");
    assertThat(
            jdbc.queryForObject(
                "select version from customer_cabin_problem where id=?", Long.class, PROBLEM))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select resolution_deadline = reported_at + interval '3 days'
                from customer_cabin_problem
                where id=?
                """,
                Boolean.class,
                PROBLEM))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select resolution_kind is null
                   and resolved_by_subject_id is null
                   and resolution_comment is null
                   and resolved_at is null
                from customer_cabin_problem
                where id=?
                """,
                Boolean.class,
                PROBLEM))
        .isTrue();

    UUID actionId = UUID.randomUUID();
    insertTransitionAction(actionId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update customer_cabin_problem_action set comment_text='rewrite' where id=?",
                    actionId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");

    assertThat(
            jdbc.update(
                """
                update customer_cabin_problem
                set lifecycle_status='IN_PROGRESS', version=version + 1
                where id=? and version=?
                """,
                PROBLEM,
                0L))
        .isOne();
    assertThat(
            jdbc.update(
                """
                update customer_cabin_problem
                set lifecycle_status='IN_PROGRESS', version=version + 1
                where id=? and version=?
                """,
                PROBLEM,
                0L))
        .isZero();
  }

  private void seedLegacyProblem() {
    jdbc.execute("alter table customer_cabin_problem disable trigger all");
    try {
      jdbc.update(
          """
          insert into customer_cabin_problem(
            id,company_id,customer_subject_id,booking_id,order_id,warehouse_id,cabin_unit_id,
            shipment_document_id,shipment_line_id,driver_task_id,category,phase,description,
            media_references_json,idempotency_key,request_sha256,reported_at)
          values (?,?,?,?,?,?,?,?,?,?, 'OTHER','BEFORE_ACCEPTANCE','Требуется проверка','[]',?,?,?)
          """,
          PROBLEM,
          COMPANY,
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          UUID.randomUUID(),
          "a".repeat(64),
          OffsetDateTime.parse("2026-09-02T08:15:30Z"));
    } finally {
      jdbc.execute("alter table customer_cabin_problem enable trigger all");
    }
  }

  private void insertTransitionAction(UUID actionId) {
    jdbc.update(
        """
        insert into customer_cabin_problem_action(
          id,company_id,problem_id,action_kind,previous_status,lifecycle_status,resolution_kind,
          actor_subject_id,comment_text,occurred_at)
        values (?,?,?,'STATUS_TRANSITION','OPEN','IN_PROGRESS',null,?,null,clock_timestamp())
        """,
        actionId,
        COMPANY,
        PROBLEM,
        UUID.randomUUID());
  }

  private Flyway flyway(String target) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations("classpath:db/migration")
        .baselineOnMigrate(false)
        .validateMigrationNaming(true)
        .target(target)
        .load();
  }
}
