package dev.buhanzaz.rwms.asset;

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
class AssetFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";

  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void cleanInstallIsRepeatSafeAndContainsNoProductionFixtures() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(4);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames()).contains(
        "flyway_schema_history",
        "rental_item",
        "equipment_catalog_item",
        "equipment_balance",
        "equipment_movement",
        "equipment_movement_ledger",
        "equipment_allocation_hold",
        "operation_lease",
        "domain_event",
        "event_stream_head",
        "aggregate_snapshot",
        "outbox_event",
        "inbox_message",
        "projection_checkpoint",
        "consumer_aggregate_checkpoint",
        "version_gap_quarantine",
        "sanitized_dead_letter",
        "asset_idempotency_record",
        "inventory_asset_capture_operation",
        "inventory_asset_capture",
        "inventory_asset_capture_member",
        "inventory_asset_source_operation",
        "inventory_asset_number_claim",
        "inventory_asset_source");
    assertThat(jdbc.queryForObject("select count(*) from rental_item", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from equipment_catalog_item", Integer.class)).isZero();
  }

  @Test
  void versionTwoSchemaUpgradesToVersionThreeWithoutBaselineOrClean(@TempDir Path directory)
      throws IOException {
    Path versionOne = directory.resolve("V1__asset_schema.sql");
    try (var source = requireResource("db/migration/V1__asset_schema.sql").openStream()) {
      Files.copy(source, versionOne);
    }
    Path versionTwo = directory.resolve("V2__asset_event_stream_completion.sql");
    try (var source = requireResource("db/migration/V2__asset_event_stream_completion.sql").openStream()) {
      Files.copy(source, versionTwo);
    }
    String versionTwoLocation =
        "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');

    assertThat(flyway(versionTwoLocation).migrate().migrationsExecuted).isEqualTo(2);
    assertThat(appliedVersions()).containsExactly("1", "2");
    UUID existingId = UUID.randomUUID();
    jdbc.update("""
        insert into rental_item(id,version,warehouse_id,number,status,passport_json,tags_json,created_at,updated_at)
        values (?,0,?,'LEGACY77','FREE','{}','[]',clock_timestamp(),clock_timestamp())
        """, existingId, UUID.randomUUID());

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
    latest.validate();

    assertThat(appliedVersions()).containsExactly("1", "2", "3", "4");
    assertThat(columnCount("rental_item", "number")).isZero();
    assertThat(columnCount("rental_item", "display_canonical_number")).isEqualTo(1);
    assertThat(columnCount("rental_item", "identity_match_key")).isEqualTo(1);
    assertThat(jdbc.queryForMap(
        "select display_canonical_number,identity_match_key from rental_item where id=?", existingId))
        .containsEntry("display_canonical_number", "LEGACY77")
        .containsEntry("identity_match_key", "LEGACY77");
    assertThat(toRegclass("inventory_asset_capture")).isNotNull();
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionThreeRejectsCaseAliasCollisionWithoutGuessingOrPartialRewrite(
      @TempDir Path directory) throws IOException {
    Path versionOne = directory.resolve("V1__asset_schema.sql");
    try (var source = requireResource("db/migration/V1__asset_schema.sql").openStream()) {
      Files.copy(source, versionOne);
    }
    Path versionTwo = directory.resolve("V2__asset_event_stream_completion.sql");
    try (var source = requireResource("db/migration/V2__asset_event_stream_completion.sql").openStream()) {
      Files.copy(source, versionTwo);
    }
    String versionTwoLocation =
        "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(versionTwoLocation).migrate();
    UUID warehouseId = UUID.randomUUID();
    for (String number : List.of("AB12", "ab12")) {
      jdbc.update("""
          insert into rental_item(id,version,warehouse_id,number,status,passport_json,tags_json,
            created_at,updated_at)
          values (?,0,?,?, 'FREE','{}','[]',clock_timestamp(),clock_timestamp())
          """, UUID.randomUUID(), warehouseId, number);
    }

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("rental number identity collision requires explicit reconciliation");

    assertThat(columnCount("rental_item", "number")).isEqualTo(1);
    assertThat(columnCount("rental_item", "display_canonical_number")).isZero();
    assertThat(jdbc.queryForList("select number from rental_item order by number", String.class))
        .containsExactly("AB12", "ab12");
    assertThat(appliedVersions()).containsExactly("1", "2");
  }

  @Test
  void modifiedAppliedMigrationIsRejectedByChecksumValidation(@TempDir Path directory)
      throws IOException {
    Path migration = directory.resolve("V1__asset_schema.sql");
    try (var source = requireResource("db/migration/V1__asset_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(
        migration,
        Files.readString(migration).replace("number varchar(128) NOT NULL", "number varchar(127) NOT NULL"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() {
    jdbc.execute("create table historical_asset_evidence (id uuid primary key)");

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

  private List<String> appliedVersions() {
    return jdbc.queryForList(
        "select version from flyway_schema_history where success order by installed_rank", String.class);
  }

  private int columnCount(String table, String column) {
    Integer count = jdbc.queryForObject(
        "select count(*) from information_schema.columns where table_schema='public' and table_name=? and column_name=?",
        Integer.class,
        table,
        column);
    return count == null ? 0 : count;
  }

  private String constraintDefinition(String table, String constraint) {
    return jdbc.queryForObject(
        "select pg_get_constraintdef(oid) from pg_constraint where conrelid=to_regclass(?) and conname=?",
        String.class,
        "public." + table,
        constraint);
  }

  private String toRegclass(String table) {
    return jdbc.queryForObject("select to_regclass(?)", String.class, "public." + table);
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null) throw new IllegalStateException("Missing asset migration resource: " + path);
    return resource;
  }
}
