package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Testcontainers
class AssetFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";
  private static final UUID SPB_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final ObjectMapper JSON = new ObjectMapper();
  private static final List<String> STATUSES = List.of(
      "FREE",
      "RENTED",
      "AFTER_RENT",
      "WAITING_ESTIMATE_CONFIRMATION",
      "BOOKED",
      "REPAIR",
      "CAPITAL_REPAIR",
      "USED_SALE",
      "WAREHOUSE",
      "OWN_NEEDS");
  private static final List<String> RENTAL_TYPES = List.of(
      "БК-1",
      "БК-2",
      "БК-3",
      "БК-4",
      "БК-5",
      "БК-6",
      "БК-Склад",
      "БК-Санблок",
      "БК-Модуль из 2х",
      "БК-Модуль из 3",
      "БК-Пост охраны");
  private static final List<String> DIMENSIONS =
      List.of("2x2", "2.4x2", "2.4x2.4", "2.4x3", "2.4x4", "2.4x5", "2.4x6", "3x3", "4.8x6", "7.2x6");
  private static final List<String> FINISHINGS =
      List.of("ДВП", "ЛДСП", "ПВХ", "ОСБ", "Вагонка", "СМЛО", "Сэндвич");
  private static final List<String> CATEGORIES = List.of("Обычная", "ИТР", "Новая", "Санблок");
  private static final List<String> CHARACTERISTICS = List.of(
      "Пластиковое окно",
      "Электрика КК",
      "Электрика КК + УЗО",
      "Электрика КК + УЗО + счётчик",
      "Электрика КК + счётчик",
      "Металлическая дверь, кондиционер",
      "Две лампы",
      "Мама-папа");
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
  void cleanInstallIsRepeatSafeAndContainsTransferredWarehouseData() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(12);
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
        "inventory_asset_source",
        "logistics_return_equipment_receipt",
        "order_unit_reservation",
        "order_equipment_reservation");
    assertThat(columnCount("order_unit_reservation", "client_id")).isEqualTo(1);
    assertThat(columnCount("order_unit_reservation", "tenant_snapshot")).isEqualTo(1);
    assertThat(columnCount("order_equipment_reservation", "order_id")).isEqualTo(1);
    assertThat(columnCount("order_equipment_reservation", "equipment_id")).isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from rental_item", Integer.class)).isEqualTo(195);
    assertThat(jdbc.queryForObject("select count(*) from equipment_catalog_item", Integer.class)).isEqualTo(9);
    assertThat(jdbc.queryForObject(
        "select count(*) from equipment_balance where rental_item_id is not null", Integer.class))
        .isGreaterThan(0);
    assertThat(jdbc.queryForObject(
        "select count(*) from rental_item where passport_json::jsonb->>'source'='old-panel-rental-items-v1'",
        Integer.class)).isEqualTo(195);
    assertStockPhotoMetadataRemoved();
    assertThat(jdbc.queryForObject("""
        select count(distinct (warehouse_id, identity_match_key)) from rental_item
        """, Integer.class)).isEqualTo(195);
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='RENTAL_ITEM'", Integer.class))
        .isGreaterThan(195);
    assertThat(columnCount("equipment_allocation_hold", "source_balance_id")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "executed_at")).isEqualTo(1);
    assertTransferredCabins();
    assertTransferredEquipment();
    assertCanonicalEventState();
  }

  @Test
  void versionTwoSchemaUpgradesToLatestWithoutBaselineOrClean(@TempDir Path directory)
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
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(10);
    latest.validate();

    assertThat(appliedVersions())
        .containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
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
  void versionSevenUpgradeRemovesOnlyKnownOldPanelStockPhotoFields(@TempDir Path directory)
      throws IOException {
    List<String> scripts = List.of(
        "V1__asset_schema.sql",
        "V2__asset_event_stream_completion.sql",
        "V3__inventory_boundary.sql",
        "V4__logistics_asset_boundary.sql",
        "V5__old_panel_warehouse_data.sql",
        "V6__order_unit_reservations.sql",
        "V7__equipment_movement_reservations.sql");
    for (String script : scripts) {
      try (var source = requireResource("db/migration/" + script).openStream()) {
        Files.copy(source, directory.resolve(script));
      }
    }
    String versionSevenLocation =
        "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(versionSevenLocation).migrate().migrationsExecuted).isEqualTo(7);
    assertThat(appliedVersions()).containsExactly("1", "2", "3", "4", "5", "6", "7");
    assertThat(integer("""
        select count(*) from rental_item
        where passport_json like '%images.unsplash.com%'
        """)).isGreaterThan(0);

    UUID unrelatedId = UUID.randomUUID();
    String unrelatedPassport = """
        {"source":"manual-import","hasPhotos":true,"photoCount":1,
         "mainPhotoUrl":"https://example.test/keep.jpg",
         "previewPhotoUrls":["https://example.test/keep.jpg"],
         "legacyPhotos":[{"url":"https://example.test/keep.jpg"}],
         "tenant":"ООО Сохранить"}
        """;
    jdbc.update("""
        insert into rental_item(
          id,version,warehouse_id,display_canonical_number,identity_match_key,status,
          passport_json,tags_json,created_at,updated_at)
        values (?,0,?,'MANUAL-001','MANUAL001','FREE',?,'[]',clock_timestamp(),clock_timestamp())
        """, unrelatedId, UUID.randomUUID(), unrelatedPassport.trim());
    int domainEventCount = integer("select count(*) from domain_event");
    int outboxCount = integer("select count(*) from outbox_event");

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(5);
    latest.validate();
    assertThat(appliedVersions())
        .containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12");
    assertStockPhotoMetadataRemoved();
    assertThat(json(jdbc.queryForObject(
        "select passport_json from rental_item where id=?", String.class, unrelatedId)))
        .isEqualTo(json(unrelatedPassport));
    assertThat(integer("select count(*) from domain_event")).isEqualTo(domainEventCount + 75);
    assertThat(integer("select count(*) from outbox_event")).isEqualTo(outboxCount + 75);
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionEightUpgradeRestoresWarehouseLocalNumbersAndPreservesClaims(
      @TempDir Path directory) throws IOException {
    List<String> scripts = List.of(
        "V1__asset_schema.sql",
        "V2__asset_event_stream_completion.sql",
        "V3__inventory_boundary.sql",
        "V4__logistics_asset_boundary.sql",
        "V5__old_panel_warehouse_data.sql",
        "V6__order_unit_reservations.sql",
        "V7__equipment_movement_reservations.sql",
        "V8__remove_old_panel_stock_photo_metadata.sql");
    for (String script : scripts) {
      try (var source = requireResource("db/migration/" + script).openStream()) {
        Files.copy(source, directory.resolve(script));
      }
    }
    String versionEightLocation =
        "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(versionEightLocation).migrate().migrationsExecuted).isEqualTo(8);

    UUID completedInventoryId = UUID.randomUUID();
    UUID completedFindingId = UUID.randomUUID();
    UUID completedRentalItemId = UUID.fromString(
        "51000000-0000-4000-8000-000000000001");
    UUID orphanInventoryId = UUID.randomUUID();
    UUID orphanFindingId = UUID.randomUUID();
    jdbc.update("""
        insert into inventory_asset_source_operation(
          inventory_id,finding_id,version,request_fingerprint,created_at)
        values (?, ?, 0, ?, clock_timestamp()), (?, ?, 0, ?, clock_timestamp())
        """,
        completedInventoryId, completedFindingId, "a".repeat(64),
        orphanInventoryId, orphanFindingId, "b".repeat(64));
    jdbc.update("""
        insert into inventory_asset_number_claim(
          identity_match_key,version,inventory_id,finding_id,created_at)
        values ('БЫТ001',0,?,?,clock_timestamp()),
               ('ORPHAN01',0,?,?,clock_timestamp())
        """,
        completedInventoryId, completedFindingId, orphanInventoryId, orphanFindingId);
    jdbc.update("""
        insert into inventory_asset_source(
          inventory_id,finding_id,request_fingerprint,rental_item_id,response_body,created_at)
        values (?, ?, ?, ?, '{}'::jsonb, clock_timestamp())
        """,
        completedInventoryId, completedFindingId, "a".repeat(64), completedRentalItemId);

    String correctedAggregateId = "51000000-0000-4000-8000-000000000121";
    jdbc.update("""
        delete from projection_checkpoint
        where projection_name='asset-live-v1'
          and aggregate_type='RENTAL_ITEM' and aggregate_id=?
        """, correctedAggregateId);
    Flyway latest = flyway(MIGRATIONS);
    assertThatThrownBy(latest::migrate)
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining(
            "correction requires coherent current stream, snapshot, checkpoint and outbox state");
    assertThat(jdbc.queryForObject("""
        select display_canonical_number from rental_item where id=?::uuid
        """, String.class, correctedAggregateId)).isEqualTo("БЫТ-121");
    assertThat(appliedVersions())
        .containsExactly("1", "2", "3", "4", "5", "6", "7", "8");
    jdbc.update("""
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,
          projection_sha256,updated_at)
        select 'asset-live-v1',snapshot.aggregate_type,snapshot.aggregate_id,
          snapshot.aggregate_version,snapshot.state_sha256,clock_timestamp()
        from aggregate_snapshot snapshot
        where snapshot.aggregate_type='RENTAL_ITEM' and snapshot.aggregate_id=?
        order by snapshot.aggregate_version desc limit 1
        """, correctedAggregateId);
    latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(4);
    latest.validate();

    assertThat(integer("select count(*) from rental_item")).isEqualTo(195);
    assertThat(jdbc.queryForMap("""
        select id,warehouse_id,display_canonical_number,identity_match_key
        from rental_item where id='51000000-0000-4000-8000-000000000121'
        """))
        .containsEntry("id", UUID.fromString("51000000-0000-4000-8000-000000000121"))
        .containsEntry("warehouse_id", MSK_WAREHOUSE_ID)
        .containsEntry("display_canonical_number", "БЫТ-001")
        .containsEntry("identity_match_key", "БЫТ001");
    assertThat(integer("""
        select count(*) from (
          select warehouse_id,identity_match_key
          from rental_item group by warehouse_id,identity_match_key having count(*)>1
        ) duplicate
        """)).isZero();
    assertThat(integer("""
        select count(*) from rental_item spb
        join rental_item msk on msk.identity_match_key=spb.identity_match_key
        where spb.warehouse_id='00000000-0000-0000-0000-000000000001'
          and msk.warehouse_id='00000000-0000-0000-0000-000000000002'
        """)).isEqualTo(75);
    assertThat(constraintDefinition(
        "rental_item", "uk_rental_item_warehouse_identity_match_key"))
        .isEqualTo("UNIQUE (warehouse_id, identity_match_key)");
    assertThat(jdbc.queryForObject("""
        select warehouse_id from inventory_asset_number_claim
        where inventory_id=? and finding_id=?
        """, UUID.class, completedInventoryId, completedFindingId))
        .isEqualTo(SPB_WAREHOUSE_ID);
    assertThat(jdbc.queryForObject("""
        select warehouse_id from inventory_asset_number_claim
        where inventory_id=? and finding_id=?
        """, UUID.class, orphanInventoryId, orphanFindingId))
        .isNull();
    assertThat(integer("""
        select count(*) from inventory_asset_number_claim where claim_id is null
        """)).isZero();
    assertThat(constraintDefinition(
        "inventory_asset_number_claim",
        "uk_inventory_asset_number_claim_warehouse_key"))
        .isEqualTo("UNIQUE NULLS NOT DISTINCT (warehouse_id, identity_match_key)");
    assertCanonicalEventState();
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void activeOrderUnitReservationIsUniqueAndReleasedEvidenceIsRetained() {
    flyway(MIGRATIONS).migrate();
    UUID rentalItemId =
        jdbc.queryForObject(
            "select id from rental_item where status in ('FREE','WAREHOUSE') order by id limit 1",
            UUID.class);
    UUID firstReservationId = UUID.randomUUID();
    UUID firstOrderId = UUID.randomUUID();
    UUID warehouseId =
        jdbc.queryForObject(
            "select warehouse_id from rental_item where id=?", UUID.class, rentalItemId);
    UUID actorSubjectId = UUID.randomUUID();
    insertActiveReservation(
        firstReservationId, firstOrderId, rentalItemId, warehouseId, actorSubjectId);

    assertThatThrownBy(
            () ->
                insertActiveReservation(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    rentalItemId,
                    warehouseId,
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("uk_order_unit_reservation_active_item");

    jdbc.update(
        """
        update order_unit_reservation
        set version=version+1,state='RELEASED',released_by_subject_id=?,
          released_by_role='RENTAL_MANAGER',released_at=clock_timestamp(),
          updated_at=clock_timestamp()
        where id=?
        """,
        actorSubjectId,
        firstReservationId);
    UUID secondReservationId = UUID.randomUUID();
    insertActiveReservation(
        secondReservationId,
        UUID.randomUUID(),
        rentalItemId,
        warehouseId,
        UUID.randomUUID());

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from order_unit_reservation
                where rental_item_id=? and state='ACTIVE'
                """,
                Integer.class,
                rentalItemId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_unit_reservation where rental_item_id=?",
                Integer.class,
                rentalItemId))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from order_unit_reservation where id=?", secondReservationId))
        .isInstanceOf(org.springframework.jdbc.UncategorizedSQLException.class)
        .hasMessageContaining("evidence cannot be deleted");
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

  private void insertActiveReservation(
      UUID reservationId,
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID actorSubjectId) {
    jdbc.update(
        """
        insert into order_unit_reservation(
          id,version,order_id,rental_item_id,warehouse_id,state,
          added_by_subject_id,added_by_role,created_at,updated_at)
        values (?,0,?,?,?,'ACTIVE',?,'RENTAL_MANAGER',clock_timestamp(),clock_timestamp())
        """,
        reservationId,
        orderId,
        rentalItemId,
        warehouseId,
        actorSubjectId);
  }

  private void assertTransferredCabins() {
    List<CabinRow> actual = jdbc.query("""
        select id,version,warehouse_id,display_canonical_number,identity_match_key,status,
          rental_type,dimensions,finishing,category,characteristics,linoleum,general_comment,
          passport_json
        from rental_item
        where passport_json::jsonb->>'source'='old-panel-rental-items-v1'
        order by warehouse_id,display_canonical_number
        """, (rs, row) -> new CabinRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("display_canonical_number"),
        rs.getString("identity_match_key"),
        rs.getString("status"),
        rs.getString("rental_type"),
        rs.getString("dimensions"),
        rs.getString("finishing"),
        rs.getString("category"),
        rs.getString("characteristics"),
        rs.getObject("linoleum", Boolean.class),
        rs.getString("general_comment"),
        json(rs.getString("passport_json"))));

    List<CabinRow> expected = new ArrayList<>(195);
    for (int globalIndex = 1; globalIndex <= 195; globalIndex++) {
      expected.add(expectedCabin(globalIndex));
    }
    assertThat(actual).containsExactlyElementsOf(expected);
    assertThat(integer("""
        select count(*) from rental_item
        where warehouse_id='00000000-0000-0000-0000-000000000001'
        """)).isEqualTo(120);
    assertThat(integer("""
        select count(*) from rental_item
        where warehouse_id='00000000-0000-0000-0000-000000000002'
        """)).isEqualTo(75);
    assertThat(integer("""
        select count(*) from rental_item
        where warehouse_id='00000000-0000-0000-0000-000000000002'
          and display_canonical_number=passport_json::jsonb->>'legacyNumber'
        """)).isEqualTo(75);
    assertThat(integer("""
        select count(*) from (
          select warehouse_id,identity_match_key
          from rental_item group by warehouse_id,identity_match_key having count(*)>1
        ) duplicate
        """)).isZero();
  }

  private void assertStockPhotoMetadataRemoved() {
    assertThat(integer("""
        select count(*) from rental_item
        where passport_json::jsonb->>'source'='old-panel-rental-items-v1'
          and (passport_json::jsonb ? 'legacyPhotos'
            or passport_json::jsonb ? 'previewPhotoUrls'
            or passport_json::jsonb ? 'mainPhotoUrl'
            or (passport_json::jsonb->>'hasPhotos')::boolean
            or (passport_json::jsonb->>'photoCount')::integer<>0)
        """)).isZero();
    assertThat(integer("""
        select count(*) from rental_item
        where passport_json::jsonb->>'source'='old-panel-rental-items-v1'
          and passport_json like '%images.unsplash.com%'
        """)).isZero();
    JsonNode firstPassport = json(jdbc.queryForObject("""
        select passport_json from rental_item
        where display_canonical_number='БЫТ-001'
          and warehouse_id='00000000-0000-0000-0000-000000000001'
        """, String.class));
    assertThat(firstPassport.path("source").asText()).isEqualTo("old-panel-rental-items-v1");
    assertThat(firstPassport.path("legacyNumber").asText()).isEqualTo("БЫТ-001");
    assertThat(firstPassport.path("hasPhotos").asBoolean()).isFalse();
    assertThat(firstPassport.path("photoCount").asInt()).isZero();
    assertThat(firstPassport.has("legacyPhotos")).isFalse();
    assertThat(firstPassport.has("previewPhotoUrls")).isFalse();
    assertThat(firstPassport.has("mainPhotoUrl")).isFalse();
    assertThat(integer("""
        select count(*) from aggregate_snapshot snapshot
        join rental_item item
          on snapshot.aggregate_type='RENTAL_ITEM'
         and snapshot.aggregate_id=item.id::text
        where item.passport_json::jsonb->>'source'='old-panel-rental-items-v1'
          and (snapshot.state->'passport' ? 'legacyPhotos'
            or snapshot.state->'passport' ? 'previewPhotoUrls'
            or snapshot.state->'passport' ? 'mainPhotoUrl'
            or (snapshot.state->'passport'->>'hasPhotos')::boolean
            or (snapshot.state->'passport'->>'photoCount')::integer<>0
            or snapshot.state::text like '%images.unsplash.com%'
            or snapshot.state_sha256<>
              encode(sha256(convert_to(snapshot.state::text,'UTF8')),'hex'))
        """)).isZero();
  }

  private CabinRow expectedCabin(int globalIndex) {
    boolean spb = globalIndex <= 120;
    int localIndex = spb ? globalIndex : globalIndex - 120;
    int zeroIndex = localIndex - 1;
    UUID warehouseId = spb ? SPB_WAREHOUSE_ID : MSK_WAREHOUSE_ID;
    String legacyWarehouseId = spb ? "spb" : "msk";
    String number = "БЫТ-%03d".formatted(localIndex);
    String comment = zeroIndex % 5 == 0
        ? "Нужна проверка перед выдачей клиенту"
        : zeroIndex % 7 == 0 ? "Есть замечания по внутренней отделке" : null;
    String status = STATUSES.get(zeroIndex % STATUSES.size());
    return new CabinRow(
        UUID.fromString("51000000-0000-4000-8000-%012d".formatted(globalIndex)),
        (comment == null ? 1 : 2) + (spb ? 0 : 1),
        warehouseId,
        number,
        number.replace("-", ""),
        status,
        RENTAL_TYPES.get(zeroIndex % RENTAL_TYPES.size()),
        DIMENSIONS.get(zeroIndex % DIMENSIONS.size()),
        FINISHINGS.get(zeroIndex % FINISHINGS.size()),
        CATEGORIES.get(zeroIndex % CATEGORIES.size()),
        CHARACTERISTICS.get(zeroIndex % CHARACTERISTICS.size()),
        zeroIndex % 2 == 0,
        comment,
        expectedPassport(
            legacyWarehouseId,
            localIndex,
            zeroIndex,
            status));
  }

  private JsonNode expectedPassport(
      String legacyWarehouseId,
      int localIndex,
      int zeroIndex,
      String status) {
    String legacyId = legacyWarehouseId + "-" + localIndex;
    Map<String, Object> passport = new LinkedHashMap<>();
    passport.put("source", "old-panel-rental-items-v1");
    passport.put("legacyId", legacyId);
    passport.put("legacyWarehouseId", legacyWarehouseId);
    passport.put("legacyNumber", "БЫТ-%03d".formatted(localIndex));
    passport.put("locationNodeId", null);
    passport.put(
        "shipmentDate",
        status.equals("RENTED") ? "2026-05-%02d".formatted(zeroIndex % 27 + 1) : null);
    passport.put(
        "tenant",
        status.equals("RENTED")
            ? zeroIndex % 2 == 0 ? "ООО СтройПроект" : "ИП Петров А.В."
            : null);
    passport.put("price", zeroIndex % 3 == 0 ? 30000 + zeroIndex * 250 : null);
    passport.put("hasPhotos", false);
    passport.put("photoCount", 0);
    return JSON.valueToTree(passport);
  }

  private void assertTransferredEquipment() {
    List<EquipmentCatalogRow> catalog = jdbc.query("""
        select id,version,code,name,category,active,comment
        from equipment_catalog_item order by code
        """, (rs, row) -> new EquipmentCatalogRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getString("code"),
        rs.getString("name"),
        rs.getString("category"),
        rs.getBoolean("active"),
        rs.getString("comment")));
    List<EquipmentCatalogRow> expectedCatalog = List.of(
        equipment(1, "TABLE", "Стол", "FURNITURE"),
        equipment(2, "OFFICE_TABLE", "Стол офисный", "FURNITURE"),
        equipment(3, "BENCH", "Лавка", "FURNITURE"),
        equipment(4, "CHAIR", "Стул", "FURNITURE"),
        equipment(5, "BED", "Кровать", "FURNITURE"),
        equipment(6, "BUNK_BED", "Кровать 2-ярусная", "FURNITURE"),
        equipment(7, "WARDROBE", "Шкаф", "FURNITURE"),
        equipment(8, "CONVECTOR", "Конвектор", "ELECTRICAL"),
        equipment(9, "AIR_CONDITIONER", "Кондиционер", "ELECTRICAL"));
    expectedCatalog = expectedCatalog.stream()
        .sorted(Comparator.comparing(EquipmentCatalogRow::code))
        .toList();
    assertThat(catalog).containsExactlyElementsOf(expectedCatalog);

    List<WarehouseEquipmentRow> warehouseBalances = jdbc.query("""
        select b.warehouse_id,c.name,
          max(b.quantity) filter (where b.location_kind='STOCK') as stock,
          max(b.quantity) filter (where b.location_kind='WRITTEN_OFF') as written_off,
          max(b.quantity) filter (where b.location_kind='LOST') as lost
        from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id is null
        group by b.warehouse_id,c.name
        """, (rs, row) -> new WarehouseEquipmentRow(
        rs.getObject("warehouse_id", UUID.class),
        rs.getString("name"),
        rs.getLong("stock"),
        rs.getLong("written_off"),
        rs.getLong("lost")));
    assertThat(warehouseBalances).containsExactlyInAnyOrder(
        warehouse(SPB_WAREHOUSE_ID, "Стол", 0, 2, 1),
        warehouse(SPB_WAREHOUSE_ID, "Стол офисный", 1, 1, 0),
        warehouse(SPB_WAREHOUSE_ID, "Лавка", 20, 0, 2),
        warehouse(SPB_WAREHOUSE_ID, "Стул", 0, 5, 3),
        warehouse(SPB_WAREHOUSE_ID, "Кровать", 24, 1, 1),
        warehouse(SPB_WAREHOUSE_ID, "Кровать 2-ярусная", 0, 2, 0),
        warehouse(SPB_WAREHOUSE_ID, "Шкаф", 0, 0, 0),
        warehouse(SPB_WAREHOUSE_ID, "Конвектор", 24, 3, 2),
        warehouse(SPB_WAREHOUSE_ID, "Кондиционер", 11, 1, 0),
        warehouse(MSK_WAREHOUSE_ID, "Стол", 12, 1, 0),
        warehouse(MSK_WAREHOUSE_ID, "Стол офисный", 6, 0, 1),
        warehouse(MSK_WAREHOUSE_ID, "Стул", 20, 2, 1),
        warehouse(MSK_WAREHOUSE_ID, "Лавка", 8, 0, 1),
        warehouse(MSK_WAREHOUSE_ID, "Кровать", 10, 1, 2),
        warehouse(MSK_WAREHOUSE_ID, "Кровать 2-ярусная", 5, 1, 0),
        warehouse(MSK_WAREHOUSE_ID, "Шкаф", 7, 0, 0));
    assertThat(integer("select count(*) from equipment_balance where rental_item_id is null"))
        .isEqualTo(48);

    List<CabinEquipmentRow> cabinBalances = jdbc.query("""
        select r.display_canonical_number,c.name,b.quantity,b.location_kind
        from equipment_balance b
        join rental_item r on r.id=b.rental_item_id
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id is not null
        """, (rs, row) -> new CabinEquipmentRow(
        rs.getString("display_canonical_number"),
        rs.getString("name"),
        rs.getLong("quantity"),
        rs.getString("location_kind")));
    assertThat(cabinBalances).containsExactlyInAnyOrderElementsOf(expectedCabinEquipment());
    assertThat(cabinBalances).hasSize(212);
    assertThat(cabinBalances.stream().mapToLong(CabinEquipmentRow::quantity).sum()).isEqualTo(478);
  }

  private static EquipmentCatalogRow equipment(
      int index, String code, String name, String category) {
    return new EquipmentCatalogRow(
        UUID.fromString("52000000-0000-4000-8000-%012d".formatted(index)),
        0,
        code,
        name,
        category,
        true,
        null);
  }

  private static WarehouseEquipmentRow warehouse(
      UUID warehouseId, String name, long stock, long writtenOff, long lost) {
    return new WarehouseEquipmentRow(warehouseId, name, stock, writtenOff, lost);
  }

  private List<CabinEquipmentRow> expectedCabinEquipment() {
    List<CabinEquipmentRow> expected = new ArrayList<>();
    for (int globalIndex = 1; globalIndex <= 195; globalIndex++) {
      int localIndex = globalIndex <= 120 ? globalIndex : globalIndex - 120;
      int zeroIndex = localIndex - 1;
      String number = "БЫТ-%03d".formatted(localIndex);
      String locationKind = STATUSES.get(zeroIndex % STATUSES.size()).equals("RENTED")
          ? "CABIN_RENTED"
          : "CABIN_NON_RENTED";
      if (zeroIndex % 4 == 0) {
        expected.add(new CabinEquipmentRow(number, "Стол", 2, locationKind));
        expected.add(new CabinEquipmentRow(number, "Стул", 4, locationKind));
        expected.add(new CabinEquipmentRow(number, "Шкаф", 1, locationKind));
      } else if (zeroIndex % 6 == 0) {
        expected.add(new CabinEquipmentRow(number, "Кровать 2-ярусная", 3, locationKind));
        expected.add(new CabinEquipmentRow(number, "Стол офисный", 2, locationKind));
      } else if (zeroIndex % 9 == 0) {
        expected.add(new CabinEquipmentRow(number, "Кровать", 2, locationKind));
        expected.add(new CabinEquipmentRow(number, "Стол офисный", 2, locationKind));
        expected.add(new CabinEquipmentRow(number, "Конвектор", 1, locationKind));
      }
    }
    return expected;
  }

  private void assertCanonicalEventState() {
    assertThat(integer("select count(*) from event_stream_head")).isEqualTo(464);
    assertThat(integer("select count(*) from domain_event")).isEqualTo(795);
    assertThat(integer("select count(*) from aggregate_snapshot")).isEqualTo(795);
    assertThat(integer("select count(*) from outbox_event")).isEqualTo(795);
    assertThat(integer("""
        select count(*) from projection_checkpoint where projection_name='asset-live-v1'
        """)).isEqualTo(464);
    assertThat(integer("""
        select count(*) from domain_event
        where aggregate_type='RENTAL_ITEM'
          and event_type='asset.rental-item.passport-changed.v1'
        """)).isEqualTo(75);
    assertThat(integer("""
        select count(*) from domain_event
        where payload_sha256 <> encode(sha256(convert_to(payload::text,'UTF8')),'hex')
        """)).isZero();
    assertThat(integer("""
        select count(*) from aggregate_snapshot
        where state_sha256 <> encode(sha256(convert_to(state::text,'UTF8')),'hex')
        """)).isZero();
    assertThat(integer("""
        select count(*) from outbox_event
        where envelope_sha256 <> encode(sha256(convert_to(envelope_body::text,'UTF8')),'hex')
          or status <> 'PENDING' or attempt_count <> 0
        """)).isZero();
    assertThat(integer("""
        select count(*) from domain_event d
        left join aggregate_snapshot s
          on s.aggregate_type=d.aggregate_type and s.aggregate_id=d.aggregate_id
          and s.aggregate_version=d.aggregate_version
        left join outbox_event o on o.event_id=d.event_id
        where s.aggregate_id is null or o.event_id is null
          or o.aggregate_type<>d.aggregate_type or o.aggregate_id<>d.aggregate_id
          or o.aggregate_version<>d.aggregate_version or o.event_type<>d.event_type
          or o.envelope_body->>'eventId'<>d.event_id::text
          or o.envelope_body->>'aggregateId'<>d.aggregate_id
          or (o.envelope_body->>'aggregateVersion')::bigint<>d.aggregate_version
          or o.envelope_body->'payload'<>d.payload
        """)).isZero();
    assertThat(integer("""
        select count(*) from event_stream_head h
        where h.current_version + 1 <> (
            select count(*) from domain_event d
            where d.aggregate_type=h.aggregate_type and d.aggregate_id=h.aggregate_id)
          or not exists (
            select 1 from domain_event d
            where d.aggregate_type=h.aggregate_type and d.aggregate_id=h.aggregate_id
              and d.aggregate_version=h.current_version and d.event_id=h.last_event_id)
        """)).isZero();
    assertThat(integer("""
        select count(*) from projection_checkpoint p
        join event_stream_head h
          on h.aggregate_type=p.aggregate_type and h.aggregate_id=p.aggregate_id
        join aggregate_snapshot s
          on s.aggregate_type=h.aggregate_type and s.aggregate_id=h.aggregate_id
          and s.aggregate_version=h.current_version
        where p.projection_name='asset-live-v1'
          and (p.aggregate_version<>h.current_version
            or p.projection_sha256<>s.state_sha256)
        """)).isZero();
    assertThat(integer("""
        select count(*) from rental_item r
        join aggregate_snapshot s
          on s.aggregate_type='RENTAL_ITEM' and s.aggregate_id=r.id::text
          and s.aggregate_version=r.version
        where (s.state->>'version')::bigint<>r.version
          or s.state->>'warehouseId'<>r.warehouse_id::text
          or s.state->>'number'<>r.display_canonical_number
          or s.state->>'status'<>r.status
          or (s.state->>'generalComment') is distinct from r.general_comment
          or s.state->'passport'<>r.passport_json::jsonb
        """)).isZero();
    assertThat(integer("""
        select count(*) from rental_item r
        join lateral (
          select event.payload
          from domain_event event
          where event.aggregate_type='RENTAL_ITEM'
            and event.aggregate_id=r.id::text
            and event.payload ? 'numberSha256'
          order by event.aggregate_version desc
          limit 1
        ) d on true
        where d.payload->>'warehouseId'<>r.warehouse_id::text
          or d.payload->>'status'<>r.status
          or d.payload->>'numberSha256'<>
            encode(sha256(convert_to(r.display_canonical_number,'UTF8')),'hex')
        """)).isZero();
  }

  private int integer(String sql) {
    Integer value = jdbc.queryForObject(sql, Integer.class);
    return value == null ? 0 : value;
  }

  private static JsonNode json(String value) {
    try {
      return JSON.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Transferred asset JSON is invalid", exception);
    }
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

  private record CabinRow(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      String identityMatchKey,
      String status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      String comment,
      JsonNode passport) {}

  private record EquipmentCatalogRow(
      UUID id,
      long version,
      String code,
      String name,
      String category,
      boolean active,
      String comment) {}

  private record WarehouseEquipmentRow(
      UUID warehouseId, String name, long stock, long writtenOff, long lost) {}

  private record CabinEquipmentRow(
      String number, String name, long quantity, String locationKind) {}
}
