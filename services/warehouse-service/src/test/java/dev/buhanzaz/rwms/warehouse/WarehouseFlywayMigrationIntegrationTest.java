package dev.buhanzaz.rwms.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
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
class WarehouseFlywayMigrationIntegrationTest {
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
  void cleanInstallIsRepeatSafeAndOwnsOnlyWarehouseOutboxAndIdempotencyData() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isOne();
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(tableNames())
        .containsExactly("flyway_schema_history", "idempotency_record", "outbox_event", "warehouse");
    assertThat(toRegclass("domain_event")).isNull();
    assertThat(toRegclass("aggregate_snapshot")).isNull();
    assertThat(toRegclass("event_stream_head")).isNull();
    assertThat(toRegclass("inbox_message")).isNull();
    assertThat(toRegclass("warehouse_location")).isNull();
    assertThat(toRegclass("warehouse_topology")).isNull();
    assertThat(
            jdbc.queryForList(
                """
                select id::text || '|' || code || '|' || name || '|' || city || '|'
                       || coalesce(address, '<null>') || '|' || time_zone || '|' || active::text
                       || '|' || coalesce(sort_order::text, '<null>')
                  from warehouse order by id
                """,
                String.class))
        .containsExactly(
            "00000000-0000-0000-0000-000000000001|WH_00000000000000000000000000000001|СПБ|Санкт-Петербург|<null>|Europe/Moscow|true|<null>",
            "00000000-0000-0000-0000-000000000002|WH_00000000000000000000000000000002|Москва|Москва|<null>|Europe/Moscow|true|<null>");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event", Integer.class))
        .isZero();
  }

  @Test
  void modifiedAppliedMigrationIsRejectedByChecksumValidation(@TempDir Path directory)
      throws IOException {
    Path migration = directory.resolve("V1__warehouse_schema.sql");
    try (var source = requireResource("db/migration/V1__warehouse_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(
        migration,
        Files.readString(migration)
            .replace("code varchar(64) NOT NULL", "code varchar(63) NOT NULL"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() {
    jdbc.execute("create table legacy_warehouse_evidence (id uuid primary key)");

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
    if (resource == null) throw new IllegalStateException("Missing warehouse migration resource: " + path);
    return resource;
  }
}
