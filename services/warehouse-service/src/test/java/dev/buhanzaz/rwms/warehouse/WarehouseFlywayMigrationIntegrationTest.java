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

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(8);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(tableNames())
        .containsExactly(
            "flyway_schema_history",
            "idempotency_record",
            "outbox_event",
            "warehouse",
            "warehouse_lifecycle_readiness",
            "warehouse_lifecycle_transition",
            "warehouse_operation_mark",
            "warehouse_operation_state",
            "warehouse_outbox_recovery_audit",
            "warehouse_support_link",
            "warehouse_support_link_allowed_date",
            "warehouse_support_link_excluded_date",
            "warehouse_support_link_weekday",
            "warehouse_time_zone_history");
    assertThat(toRegclass("domain_event")).isNull();
    assertThat(toRegclass("aggregate_snapshot")).isNull();
    assertThat(toRegclass("event_stream_head")).isNull();
    assertThat(toRegclass("inbox_message")).isNull();
    assertThat(toRegclass("warehouse_location")).isNull();
    assertThat(toRegclass("warehouse_topology")).isNull();
    assertThat(columnExists("warehouse", "code")).isFalse();
    assertThat(columnExists("warehouse", "normalized_name")).isTrue();
    assertThat(columnExists("warehouse", "time_zone_revision")).isTrue();
    assertThat(columnExists("warehouse", "lifecycle_state")).isTrue();
    assertThat(columnExists("warehouse", "lifecycle_revision")).isTrue();
    assertThat(columnExists("warehouse", "representative")).isTrue();
    assertThat(columnExists("warehouse", "latitude")).isTrue();
    assertThat(columnExists("warehouse", "longitude")).isTrue();
    assertThat(columnExists("warehouse", "support_link_revision")).isTrue();
    assertThat(columnExists("outbox_event", "review_version")).isTrue();
    assertThat(
            jdbc.queryForList(
                """
                select id::text || '|' || name || '|' || normalized_name || '|' || city || '|'
                       || coalesce(address, '<null>') || '|' || time_zone || '|' || active::text
                       || '|' || coalesce(sort_order::text, '<null>') || '|'
                       || representative::text
                  from warehouse order by id
                """,
                String.class))
        .containsExactly(
            "00000000-0000-0000-0000-000000000001|СПБ|спб|Санкт-Петербург|<null>|Europe/Moscow|true|<null>|false",
            "00000000-0000-0000-0000-000000000002|Москва|москва|Москва|<null>|Europe/Moscow|true|<null>|false");
    assertThat(
            jdbc.queryForList(
                "select lifecycle_state from warehouse order by id", String.class))
        .containsExactly("ACTIVE", "ACTIVE");
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
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_time_zone_history", Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_state", Integer.class))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update warehouse_time_zone_history
                       set time_zone='Europe/Samara'
                     where warehouse_id=?
                    """,
                    UUID.fromString("00000000-0000-0000-0000-000000000001")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("immutable");
    jdbc.update(
        """
        insert into warehouse_lifecycle_readiness(
            warehouse_id,readiness_owner,warehouse_version,confirmed_at)
        values (?, 'ASSET', 1, clock_timestamp())
        """,
        UUID.fromString("00000000-0000-0000-0000-000000000001"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update warehouse_lifecycle_readiness
                       set warehouse_version=2
                     where warehouse_id=? and readiness_owner='ASSET'
                    """,
                    UUID.fromString("00000000-0000-0000-0000-000000000001")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("immutable");
    jdbc.update(
        """
        insert into warehouse_lifecycle_transition(
            warehouse_id,warehouse_version,transition,recorded_at)
        values (?, 1, 'DRAINING_STARTED', clock_timestamp())
        """,
        UUID.fromString("00000000-0000-0000-0000-000000000001"));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update warehouse_lifecycle_transition
                       set transition='INACTIVATED'
                     where warehouse_id=? and warehouse_version=1
                    """,
                    UUID.fromString("00000000-0000-0000-0000-000000000001")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from warehouse_time_zone_history where warehouse_id=?",
                    UUID.fromString("00000000-0000-0000-0000-000000000001")))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("immutable");
  }

  @Test
  void versionSevenBackfillsExistingWarehousesAndDurableCreateReplays() {
    Flyway throughVersionSix = configuration(MIGRATIONS).target("6").load();
    assertThat(throughVersionSix.migrate().migrationsExecuted).isEqualTo(6);
    assertThat(columnExists("warehouse", "representative")).isFalse();
    UUID warehouseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into idempotency_record(
          subject_id,idempotency_key,request_sha256,response_status,response_body,warehouse_id,
          created_at,expires_at)
        values (?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',201,
          jsonb_build_object('id', ?::text),?,clock_timestamp(),clock_timestamp()+interval '1 hour')
        """,
        subjectId,
        idempotencyKey,
        warehouseId,
        warehouseId);

    Flyway current = configuration(MIGRATIONS).target("7").load();
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();

    assertThat(
            jdbc.queryForList(
                "select representative from warehouse order by id", Boolean.class))
        .containsExactly(false, false);
    assertThat(
            jdbc.queryForObject(
                """
                select is_nullable || '|' || column_default
                  from information_schema.columns
                 where table_schema='public' and table_name='warehouse'
                   and column_name='representative'
                """,
                String.class))
        .isEqualTo("NO|false");
    assertThat(
            jdbc.queryForObject(
                """
                select response_body->>'representative'
                  from idempotency_record
                 where subject_id=? and idempotency_key=?
                """,
                String.class,
                subjectId,
                idempotencyKey))
        .isEqualTo("false");
  }

  @Test
  void versionEightAddsNullableCoordinatePairsAndConstrainedDirectedSupportLinks() {
    Flyway throughVersionSeven = configuration(MIGRATIONS).target("7").load();
    assertThat(throughVersionSeven.migrate().migrationsExecuted).isEqualTo(7);
    UUID replayWarehouse = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into idempotency_record(
          subject_id,idempotency_key,request_sha256,response_status,response_body,warehouse_id,
          created_at,expires_at)
        values (?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',201,
          jsonb_build_object('id', ?::text),?,clock_timestamp(),clock_timestamp()+interval '1 hour')
        """,
        subjectId,
        idempotencyKey,
        replayWarehouse,
        replayWarehouse);

    Flyway current = flyway(MIGRATIONS);
    assertThat(current.migrate().migrationsExecuted).isOne();
    current.validate();

    assertThat(
            jdbc.queryForObject(
                """
                select jsonb_exists(response_body, 'latitude')
                   and jsonb_exists(response_body, 'longitude')
                  from idempotency_record
                 where subject_id=? and idempotency_key=?
                """,
                Boolean.class,
                subjectId,
                idempotencyKey))
        .isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update warehouse set latitude=59.900000, longitude=null where id=?",
                    replayWarehouse))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_warehouse_coordinate_pair");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update warehouse set latitude=91.000000, longitude=30.000000 where id=?",
                    replayWarehouse))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_warehouse_latitude");

    UUID servedWarehouse = UUID.randomUUID();
    jdbc.update(
        """
        insert into warehouse(
          id,version,name,normalized_name,city,address,latitude,longitude,time_zone,
          lifecycle_state,lifecycle_revision,time_zone_revision,active,representative,
          support_link_revision,sort_order,created_at,updated_at)
        values (?,0,'Regional','regional','Regional',null,58.500000,31.200000,'Europe/Moscow',
          'ACTIVE',0,0,true,true,0,null,clock_timestamp(),clock_timestamp())
        """,
        servedWarehouse);
    UUID linkId = UUID.randomUUID();
    jdbc.update(
        """
        insert into warehouse_support_link(
          id,version,support_warehouse_id,served_warehouse_id,active,priority,
          allow_drivers,allow_vehicles,allow_inventory,allow_direct_fulfillment,
          allow_interwarehouse_transfer,allow_contractor_fallback,service_start,service_end,
          created_at,updated_at)
        values (?,0,?,?,true,1,true,true,true,true,true,true,'08:00','18:00',
          clock_timestamp(),clock_timestamp())
        """,
        linkId,
        replayWarehouse,
        servedWarehouse);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse_support_link(
                      id,version,support_warehouse_id,served_warehouse_id,active,priority,
                      allow_drivers,allow_vehicles,allow_inventory,allow_direct_fulfillment,
                      allow_interwarehouse_transfer,allow_contractor_fallback,created_at,updated_at)
                    values (?,0,?,?,true,1,true,true,true,true,true,true,
                      clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    replayWarehouse,
                    servedWarehouse))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("uk_warehouse_support_link_direction");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse_support_link(
                      id,version,support_warehouse_id,served_warehouse_id,active,priority,
                      allow_drivers,allow_vehicles,allow_inventory,allow_direct_fulfillment,
                      allow_interwarehouse_transfer,allow_contractor_fallback,created_at,updated_at)
                    values (?,0,?,?,true,1,true,true,true,true,true,true,
                      clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    servedWarehouse,
                    servedWarehouse))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("ck_warehouse_support_link_direction");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update warehouse set representative=false where id=?", servedWarehouse))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("remove warehouse support links");
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
    assertThat(versionTwo.migrate().migrationsExecuted).isEqualTo(7);
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
    assertThat(
            jdbc.queryForObject(
                "select response_body->>'lifecycleState' from idempotency_record where subject_id=? and idempotency_key=?",
                String.class,
                subjectId,
                idempotencyKey))
        .isEqualTo("ACTIVE");
    assertThat(
            jdbc.queryForObject(
                "select response_body->>'representative' from idempotency_record where subject_id=? and idempotency_key=?",
                String.class,
                subjectId,
                idempotencyKey))
        .isEqualTo("false");
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
