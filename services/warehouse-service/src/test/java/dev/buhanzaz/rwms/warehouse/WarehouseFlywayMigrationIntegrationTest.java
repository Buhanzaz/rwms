package dev.buhanzaz.rwms.warehouse;

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
import org.springframework.dao.DataIntegrityViolationException;
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

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(3);
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
    assertThat(columnExists("warehouse", "code")).isFalse();
    assertThat(columnExists("warehouse", "normalized_name")).isTrue();
    assertThat(
            jdbc.queryForList(
                """
                select id::text || '|' || name || '|' || normalized_name || '|' || city || '|'
                       || coalesce(address, '<null>') || '|' || time_zone || '|' || active::text
                       || '|' || coalesce(sort_order::text, '<null>')
                  from warehouse order by id
                """,
                String.class))
        .containsExactly(
            "00000000-0000-0000-0000-000000000001|СПБ|спб|Санкт-Петербург|<null>|Europe/Moscow|true|<null>",
            "00000000-0000-0000-0000-000000000002|Москва|москва|Москва|<null>|Europe/Moscow|true|<null>");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.table_constraints
                where table_schema='public' and table_name='warehouse'
                  and constraint_type='UNIQUE'
                  and constraint_name='uk_warehouse_normalized_name'
                """,
                Integer.class))
        .isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse(
                      id,version,name,normalized_name,city,address,time_zone,active,sort_order,
                      created_at,updated_at)
                    values (?,0,'СПБ','спб','Санкт-Петербург',null,'Europe/Moscow',true,null,
                      clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uk_warehouse_normalized_name");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event", Integer.class))
        .isZero();
  }

  @Test
  void versionTwoSanitizesHistoricalOutboxAndIdempotencyBodiesBeforeDroppingCode() {
    Flyway versionOne = configuration(MIGRATIONS).target("1").load();
    assertThat(versionOne.migrate().migrationsExecuted).isOne();
    UUID warehouseId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    String envelope =
        "{\"payload\":{\"warehouseId\":\""
            + warehouseId
            + "\",\"code\":\"LEGACY\",\"timeZone\":\"Europe/Moscow\",\"active\":true,\"sortOrder\":null}}";
    jdbc.update(
        """
        insert into warehouse(
          id,version,code,name,city,address,time_zone,active,sort_order,created_at,updated_at)
        values (?,0,'LEGACY','Legacy warehouse','Москва',null,'Europe/Moscow',true,null,
          clock_timestamp(),clock_timestamp())
        """,
        warehouseId);
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,topic,
          occurred_at,recorded_at,envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'WAREHOUSE',?,0,'warehouse.warehouse.created.v1',1,
          'rwms.warehouse.warehouse.v1',clock_timestamp(),clock_timestamp(),?::jsonb,
          encode(sha256(convert_to(?::jsonb::text,'UTF8')),'hex'),'PENDING',0,
          clock_timestamp(),clock_timestamp())
        """,
        eventId,
        warehouseId.toString(),
        envelope,
        envelope);
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into idempotency_record(
          subject_id,idempotency_key,request_sha256,response_status,response_body,warehouse_id,
          created_at,expires_at)
        values (?,?,
          'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',201,?::jsonb,?,
          clock_timestamp(),clock_timestamp()+interval '1 hour')
        """,
        subjectId,
        idempotencyKey,
        "{\"id\":\"" + warehouseId + "\",\"code\":\"LEGACY\"}",
        warehouseId);

    Flyway versionTwo = flyway(MIGRATIONS);
    assertThat(versionTwo.migrate().migrationsExecuted).isEqualTo(2);
    versionTwo.validate();

    assertThat(columnExists("warehouse", "code")).isFalse();
    assertThat(jdbc.queryForObject(
        "select jsonb_exists(envelope_body->'payload', 'code') from outbox_event where event_id=?",
        Boolean.class,
        eventId)).isFalse();
    assertThat(jdbc.queryForObject(
        "select jsonb_exists(response_body, 'code') from idempotency_record where subject_id=? and idempotency_key=?",
        Boolean.class,
        subjectId,
        idempotencyKey)).isFalse();
    assertThat(jdbc.queryForObject(
        """
        select envelope_sha256=encode(sha256(convert_to(envelope_body::text,'UTF8')),'hex')
        from outbox_event where event_id=?
        """,
        Boolean.class,
        eventId)).isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update outbox_event
                    set envelope_body=envelope_body || '{\"migrationProbe\":true}'::jsonb
                    where event_id=?
                    """,
                    eventId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void versionThreeFailsFastWhenHistoricalCanonicalNamesCollide() {
    Flyway versionTwo = configuration(MIGRATIONS).target("2").load();
    assertThat(versionTwo.migrate().migrationsExecuted).isEqualTo(2);

    jdbc.update(
        """
        insert into warehouse(
          id,version,name,city,address,time_zone,active,sort_order,created_at,updated_at)
        values (?,0,?,'Москва',null,'Europe/Moscow',true,null,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        "\u00a0North\u00a0Hub\u00a0");
    jdbc.update(
        """
        insert into warehouse(
          id,version,name,city,address,time_zone,active,sort_order,created_at,updated_at)
        values (?,0,?,'Москва',null,'Europe/Moscow',false,null,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        "north  hub");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("duplicate canonical warehouse names");
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
            .replace("name varchar(255) NOT NULL", "name varchar(254) NOT NULL"));

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

  private boolean columnExists(String table, String column) {
    Integer count =
        jdbc.queryForObject(
            """
            select count(*)
            from information_schema.columns
            where table_schema='public' and table_name=? and column_name=?
            """,
            Integer.class,
            table,
            column);
    return count != null && count == 1;
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null) throw new IllegalStateException("Missing warehouse migration resource: " + path);
    return resource;
  }
}
