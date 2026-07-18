package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@Testcontainers
class LogisticsFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";

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
  void cleanInstallIsRepeatSafeAndCreatesOnlyLogisticsOwnedState() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(6);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(tableNames())
        .contains(
            "aggregate_snapshot",
            "consumer_aggregate_checkpoint",
            "domain_event",
            "event_stream_head",
            "flyway_schema_history",
            "inbox_message",
            "logistics_document",
            "logistics_document_line",
            "logistics_equipment_hold_reference",
            "logistics_external_attempt",
            "logistics_guard",
            "logistics_idempotency_record",
            "logistics_inbound_observation",
            "logistics_inbound_replay_message",
            "logistics_media_reference",
            "logistics_reconciliation",
            "logistics_reconciliation_request",
            "logistics_return_shortage_snapshot",
            "logistics_task_reference",
            "outbox_event",
            "projection_checkpoint",
            "sanitized_dead_letter",
            "version_gap_quarantine")
        .doesNotContain("warehouse", "rental_item", "inventory_session", "reservation");
    assertThat(toRegclass("databasechangelog")).isNull();
  }

  @Test
  void v6UpgradePreservesHistoricalInboxRowsWithoutFabricatingSourceEvidence() {
    Flyway beforeV6 = configuration(MIGRATIONS).target("5").load();
    assertThat(beforeV6.migrate().migrationsExecuted).isEqualTo(5);
    UUID eventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,
          status,attempt_count,received_at)
        values (?,?,?,?,?,?,'PROCESSED',0,clock_timestamp())
        """,
        "historical-logistics-inbox",
        eventId,
        "RETURN",
        UUID.randomUUID().toString(),
        0,
        "a".repeat(64));

    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isOne();
    assertThat(
            jdbc.queryForObject(
                "select source_topic from inbox_message where event_id=?", String.class, eventId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select event_type from inbox_message where event_id=?", String.class, eventId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select envelope_body from inbox_message where event_id=?", String.class, eventId))
        .isNull();
  }

  @Test
  void modifiedAppliedMigrationIsRejectedByChecksumValidation(@TempDir Path directory)
      throws IOException {
    Path migration = directory.resolve("V1__logistics_schema.sql");
    try (var source = requireResource("db/migration/V1__logistics_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(
        migration,
        Files.readString(migration)
            .replace("party_snapshot varchar(512)", "party_snapshot varchar(511)"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() {
    jdbc.execute("create table legacy_logistics_evidence (id uuid primary key)");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(toRegclass("flyway_schema_history")).isNull();
  }

  private Flyway flyway(String locations) {
    return configuration(locations).load();
  }

  private FluentConfiguration configuration(String locations) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(locations)
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false);
  }

  private List<String> tableNames() {
    return jdbc.queryForList(
        "select table_name from information_schema.tables where table_schema='public' order by table_name",
        String.class);
  }

  private String toRegclass(String table) {
    return jdbc.queryForObject("select to_regclass(?)", String.class, "public." + table);
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null) throw new IllegalStateException("Missing logistics migration resource: " + path);
    return resource;
  }
}
