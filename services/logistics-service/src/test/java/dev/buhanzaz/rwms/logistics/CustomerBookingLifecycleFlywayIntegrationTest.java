package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/** Verifies booking-lifecycle upgrades and replay receipts against a real PostgreSQL schema. */
@Testcontainers
class CustomerBookingLifecycleFlywayIntegrationTest {
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
  void v79UpgradeCreatesFencedMutationStateAndRetainsReleasedSlotAudit() {
    Flyway before = flyway("79");
    before.migrate();
    assertThat(before.info().current().getVersion().getVersion()).isEqualTo("79");

    Flyway current = flyway("80");
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();
    assertThat(current.info().current().getVersion().getVersion()).isEqualTo("80");

    assertThat(table("customer_booking_mutation")).isEqualTo("customer_booking_mutation");
    assertThat(index("uk_customer_booking_mutation_subject_key")).isNotNull();
    assertThat(index("uk_customer_booking_mutation_open_booking")).isNotNull();
    assertThat(index("idx_customer_booking_mutation_open_order")).isNotNull();
    assertThat(index("idx_customer_booking_mutation_due")).isNotNull();
    assertThat(constraint("customer_booking_mutation", "ck_customer_booking_mutation_lifecycle"))
        .contains("PENDING", "QUARANTINED", "COMPLETED");
    assertThat(constraint("customer_rental_session", "ck_customer_rental_session_state"))
        .contains("CANCEL_PENDING", "CANCELLED");
    assertThat(constraint("customer_delivery_slot", "ck_customer_delivery_slot_booking"))
        .contains("RELEASED", "booking_id IS NOT NULL", "order_id IS NOT NULL");
    assertThat(indexDefinition("uk_customer_delivery_slot_booking"))
        .contains("CHECKOUT_PENDING", "CONFIRMED")
        .doesNotContain("RELEASED");
  }

  @Test
  void v87AddsObjectReceiptWithoutInvalidatingLegacyCompletedReschedules() {
    Flyway before = flyway("86");
    before.migrate();
    UUID mutationId = UUID.randomUUID();
    jdbc.execute("alter table customer_booking_mutation disable trigger all");
    jdbc.update(
        """
        insert into customer_booking_mutation(
          id,version,customer_subject_id,booking_id,inquiry_id,order_id,operation,state,
          idempotency_key,request_sha256,expected_session_version,expected_order_version,
          old_slot_id,new_slot_id,decision_code,decision_actor_subject_id,attempt_count,
          completed_at,created_at,updated_at)
        values (?,0,?,?,?,?, 'RESCHEDULE','COMPLETED',?,?,4,7,?,?,
          'CUSTOMER_AGREED',?,0,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        mutationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.execute("alter table customer_booking_mutation enable trigger all");

    Flyway current = flyway("87");
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();

    assertThat(
            jdbc.queryForObject(
                "select reschedule_result_json is null from customer_booking_mutation where id=?",
                Boolean.class,
                mutationId))
        .isTrue();
    jdbc.update(
        "update customer_booking_mutation set reschedule_result_json='{}'::jsonb where id=?",
        mutationId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update customer_booking_mutation set reschedule_result_json='[]'::jsonb where id=?",
                    mutationId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            constraint(
                "customer_booking_mutation", "ck_customer_booking_mutation_reschedule_result"))
        .contains("jsonb_typeof", "object");
  }

  private Flyway flyway(String target) {
    var configuration =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations("classpath:db/migration")
            .baselineOnMigrate(false)
            .validateMigrationNaming(true);
    if (target != null) configuration.target(target);
    return configuration.load();
  }

  private String table(String name) {
    return jdbc.queryForObject("select to_regclass('public.' || ?)", String.class, name);
  }

  private String index(String name) {
    return jdbc.queryForObject("select to_regclass('public.' || ?)", String.class, name);
  }

  private String constraint(String table, String name) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(c.oid)
        from pg_constraint c
        join pg_class t on t.oid = c.conrelid
        join pg_namespace n on n.oid = t.relnamespace
        where n.nspname = 'public' and t.relname = ? and c.conname = ?
        """,
        String.class,
        table,
        name);
  }

  private String indexDefinition(String name) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname = 'public' and indexname = ?",
        String.class,
        name);
  }
}
