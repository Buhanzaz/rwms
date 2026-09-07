package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
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
  private static final UUID INITIAL_COMPANY_ID =
      UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48");
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
      "Металлическая дверь, Кондиционер",
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
  void inventoryIsolationUpgradePreservesExistingCabinsAndStartsWithoutAnyRepair() {
    configuration(MIGRATIONS).target("51").load().migrate();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = jdbc.queryForObject("select id from rental_item order by id limit 1", UUID.class);
    jdbc.update("""
        insert into inventory_asset_source_operation(
          inventory_id,finding_id,version,request_fingerprint,reserved_rental_item_id,
          proposal_response,created_at)
        values (?,?,0,?,?,'{}'::jsonb,clock_timestamp())
        """, inventoryId, findingId, "a".repeat(64), assetId);
    jdbc.update("""
        insert into inventory_asset_source(
          inventory_id,finding_id,request_fingerprint,rental_item_id,response_body,created_at)
        values (?,?,?,?,'{}'::jsonb,clock_timestamp())
        """, inventoryId, findingId, "a".repeat(64), assetId);
    List<String> before = jdbc.queryForList(
        "select to_jsonb(item)::text from rental_item item order by id", String.class);
    List<String> eventsBefore = jdbc.queryForList(
        "select to_jsonb(event)::text from domain_event event order by event_id", String.class);
    List<String> sourcesBefore = jdbc.queryForList(
        "select to_jsonb(source)::text from inventory_asset_source source order by inventory_id,finding_id",
        String.class);
    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(3);
    latest.validate();
    assertThat(jdbc.queryForList(
        "select (to_jsonb(item)-'inventory_isolation_id')::text from rental_item item order by id",
        String.class)).containsExactlyElementsOf(before);
    assertThat(jdbc.queryForList(
        "select to_jsonb(event)::text from domain_event event order by event_id", String.class))
        .containsExactlyElementsOf(eventsBefore);
    assertThat(jdbc.queryForList(
        "select to_jsonb(source)::text from inventory_asset_source source order by inventory_id,finding_id",
        String.class)).containsExactlyElementsOf(sourcesBefore);
    assertThat(integer("select count(*) from rental_item where inventory_isolation_id is not null")).isZero();
    assertThat(integer("select count(*) from inventory_source_isolation_repair")).isZero();
    assertThat(constraintDefinition("domain_event", "ck_asset_domain_event_type"))
        .contains("asset.rental-item.inventory-visibility-changed.v1");
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void serviceReleaseUpgradePreservesExistingReservationsWithoutGrantingCreationRights() {
    configuration(MIGRATIONS).target("48").load().migrate();
    UUID reservationId = UUID.randomUUID();
    UUID cabinId =
        jdbc.queryForObject("select id from rental_item order by id limit 1", UUID.class);
    UUID actorId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_unit_reservation(
          id, order_id, rental_item_id, warehouse_id, state, added_by_subject_id, added_by_role,
          created_at, updated_at)
        values (?,?,?,?,'ACTIVE',?,'RENTAL_MANAGER',clock_timestamp(),clock_timestamp())
        """,
        reservationId,
        UUID.randomUUID(),
        cabinId,
        UUID.randomUUID(),
        actorId);
    Map<String, Object> before =
        jdbc.queryForMap("select * from order_unit_reservation where id=?", reservationId);

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(6);
    latest.validate();
    assertThat(jdbc.queryForMap("select * from order_unit_reservation where id=?", reservationId))
        .isEqualTo(before);
    assertThat(
            constraintDefinition(
                "order_unit_reservation", "ck_order_unit_reservation_released_role"))
        .contains("LOGISTICS_SERVICE");
    assertThat(
            constraintDefinition("order_unit_reservation", "ck_order_unit_reservation_added_role"))
        .doesNotContain("LOGISTICS_SERVICE");
    assertThat(constraintDefinition("presentation_unit_hold", "ck_presentation_unit_hold_role"))
        .doesNotContain("LOGISTICS_SERVICE");
    jdbc.update(
        """
        update order_unit_reservation set state='RELEASED', released_by_subject_id=?,
          released_by_role='LOGISTICS_SERVICE', released_at=clock_timestamp() where id=?
        """,
        UUID.randomUUID(),
        reservationId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update order_unit_reservation set added_by_role='LOGISTICS_SERVICE' where"
                        + " id=?",
                    reservationId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFiftyBackfillsOnlyMaterializedInventorySourceProposalReceipts() {
    Flyway versionFortyNine = configuration(MIGRATIONS).target("49").load();
    assertThat(versionFortyNine.migrate().migrationsExecuted).isEqualTo(49);
    List<String> tablesBefore = tableNames();
    int rentalItemCount = integer("select count(*) from rental_item");
    List<UUID> rentalItemIds =
        jdbc.queryForList("select id from rental_item order by id", UUID.class);
    UUID materializedInventoryId = UUID.randomUUID();
    UUID materializedFindingId = UUID.randomUUID();
    UUID pendingInventoryId = UUID.randomUUID();
    UUID pendingFindingId = UUID.randomUUID();
    UUID rentalItemId = rentalItemIds.getFirst();
    String response =
        "{\"inventoryId\":\""
            + materializedInventoryId
            + "\",\"findingId\":\""
            + materializedFindingId
            + "\",\"assetId\":\""
            + rentalItemId
            + "\"}";
    jdbc.update(
        """
        insert into inventory_asset_source_operation(
          inventory_id,finding_id,version,request_fingerprint,created_at)
        values (?,?,0,?,clock_timestamp()),(?,?,0,?,clock_timestamp())
        """,
        materializedInventoryId,
        materializedFindingId,
        "a".repeat(64),
        pendingInventoryId,
        pendingFindingId,
        "b".repeat(64));
    jdbc.update(
        """
        insert into inventory_asset_source(
          inventory_id,finding_id,request_fingerprint,rental_item_id,response_body,created_at)
        values (?,?,?,?,?::jsonb,clock_timestamp())
        """,
        materializedInventoryId,
        materializedFindingId,
        "a".repeat(64),
        rentalItemId,
        response);

    Flyway versionFifty = configuration(MIGRATIONS).target("50").load();
    assertThat(versionFifty.migrate().migrationsExecuted).isOne();
    versionFifty.validate();

    assertThat(columnCount("inventory_asset_source_operation", "reserved_rental_item_id"))
        .isOne();
    assertThat(columnCount("inventory_asset_source_operation", "source_plan")).isOne();
    assertThat(columnCount("inventory_asset_source_operation", "proposal_response")).isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select reserved_rental_item_id
                from inventory_asset_source_operation
                where inventory_id=? and finding_id=?
                """,
                UUID.class,
                materializedInventoryId,
                materializedFindingId))
        .isEqualTo(rentalItemId);
    assertThat(
            jdbc.queryForObject(
                """
                select source_plan is null
                from inventory_asset_source_operation
                where inventory_id=? and finding_id=?
                """,
                Boolean.class,
                materializedInventoryId,
                materializedFindingId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select proposal_response = ?::jsonb
                from inventory_asset_source_operation
                where inventory_id=? and finding_id=?
                """,
                Boolean.class,
                response,
                materializedInventoryId,
                materializedFindingId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select reserved_rental_item_id is null
                  and source_plan is null
                  and proposal_response is null
                from inventory_asset_source_operation
                where inventory_id=? and finding_id=?
                """,
                Boolean.class,
                pendingInventoryId,
                pendingFindingId))
        .isTrue();
    assertThat(integer("select count(*) from rental_item")).isEqualTo(rentalItemCount);
    assertThat(jdbc.queryForList("select id from rental_item order by id", UUID.class))
        .containsExactlyElementsOf(rentalItemIds);
    assertThat(integer("select count(*) from inventory_asset_source")).isOne();
    assertThat(tableNames()).containsExactlyElementsOf(tablesBefore);
    assertThat(versionFifty.migrate().migrationsExecuted).isZero();
  }

  @Test
  void freshInstallIncludesConstrainedInventorySourceProposalColumns() {
    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(54);
    latest.validate();

    assertThat(columnCount("inventory_asset_source_operation", "reserved_rental_item_id"))
        .isOne();
    assertThat(columnCount("inventory_asset_source_operation", "source_plan")).isOne();
    assertThat(columnCount("inventory_asset_source_operation", "proposal_response")).isOne();
    assertThat(
            constraintDefinition(
                "inventory_asset_source_operation",
                "uk_inventory_asset_source_operation_reserved_item"))
        .isEqualTo("UNIQUE (reserved_rental_item_id)");
    assertThat(
            constraintDefinition(
                "inventory_asset_source_operation", "ck_inventory_asset_source_operation_plan"))
        .contains("source_plan IS NULL", "jsonb_typeof(source_plan) = 'object'");
    assertThat(
            constraintDefinition(
                "inventory_asset_source_operation",
                "ck_inventory_asset_source_operation_proposal"))
        .contains("proposal_response IS NULL", "jsonb_typeof(proposal_response) = 'object'");
    assertThat(
            constraintDefinition(
                "inventory_asset_source_operation",
                "ck_inventory_asset_source_operation_proposal_shape"))
        .contains(
            "reserved_rental_item_id IS NULL",
            "source_plan IS NULL",
            "proposal_response IS NULL",
            "reserved_rental_item_id IS NOT NULL",
            "proposal_response IS NOT NULL");
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void globalStatusPaletteUpgradePreservesCabinsAndSeedsEveryCanonicalStatus() {
    configuration(MIGRATIONS).target("47").load().migrate();
    Long cabinsBefore = jdbc.queryForObject("select count(*) from rental_item", Long.class);
    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(7);
    latest.validate();
    assertThat(jdbc.queryForObject("select count(*) from rental_item", Long.class))
        .isEqualTo(cabinsBefore);
    assertThat(
            jdbc.queryForList(
                "select jsonb_object_keys(colors) from cabin_status_colors", String.class))
        .containsExactlyInAnyOrder(
            Arrays.stream(RentalItemStatus.values()).map(Enum::name).toArray(String[]::new));
    assertThat(
            jdbc.queryForObject(
                "select colors->>'FREE' from cabin_status_colors", String.class))
        .isEqualTo("#16A34A");
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void cleanInstallIsRepeatSafeAndContainsTransferredWarehouseData() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(54);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames()).contains(
        "flyway_schema_history",
        "rental_item",
        "rental_item_characteristic",
        "cabin_catalog_item",
        "cabin_status_colors",
        "cabin_type_dimension",
        "equipment_catalog_item",
        "equipment_balance",
        "equipment_movement",
        "equipment_movement_ledger",
        "equipment_allocation_hold",
        "operation_lease",
        "rental_item_creation_intent",
        "rental_item_creation_photo",
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
        "inventory_furniture_reconciliation",
        "inventory_asset_outcome_watermark",
        "inventory_asset_outcome_receipt",
        "logistics_return_equipment_receipt",
        "rental_item_html_import",
        "rental_item_html_import_row",
        "presentation_unit_hold",
        "order_unit_reservation",
        "order_equipment_reservation",
        "transfer_unit_reservation",
        "asset_warehouse_readiness_fence");
    assertThat(columnCount("order_unit_reservation", "client_id")).isEqualTo(1);
    assertThat(columnCount("rental_item", "company_id")).isZero();
    assertThat(columnCount("rental_item_html_import", "company_id")).isZero();
    assertThat(columnCount("order_unit_reservation", "tenant_snapshot")).isEqualTo(1);
    assertThat(columnCount("order_unit_reservation", "draft_reservation_expires_at")).isEqualTo(1);
    assertThat(columnCount("order_unit_reservation", "source_status")).isZero();
    assertThat(columnCount("order_equipment_reservation", "order_id")).isEqualTo(1);
    assertThat(columnCount("order_equipment_reservation", "equipment_id")).isEqualTo(1);
    assertThat(toRegclass("uk_order_equipment_reservation_active_order_equipment")).isNull();
    assertThat(
            toRegclass(
                "uk_order_equipment_reservation_active_order_warehouse_equipment"))
        .isNotNull();
    assertThat(columnCount("equipment_catalog_item", "maximum_per_cabin")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "movement_purpose")).isEqualTo(1);
    assertThat(
            constraintDefinition(
                "equipment_catalog_item", "ck_equipment_catalog_maximum_per_cabin"))
        .contains("maximum_per_cabin IS NULL", "maximum_per_cabin > 0");
    assertThat(columnCount("equipment_catalog_item", "code")).isZero();
    assertThat(columnCount("asset_classifier", "code")).isZero();
    assertThat(columnCount("rental_item", "rental_type")).isZero();
    assertThat(columnCount("rental_item", "dimensions")).isZero();
    assertThat(columnCount("rental_item", "finishing")).isZero();
    assertThat(columnCount("rental_item", "characteristics")).isZero();
    assertThat(columnCount("rental_item", "cabin_type_id")).isEqualTo(1);
    assertThat(columnCount("rental_item", "cabin_dimension_id")).isEqualTo(1);
    assertThat(columnCount("rental_item", "cabin_finishing_id")).isEqualTo(1);
    assertThat(columnCount("rental_item", "cabin_category_id")).isEqualTo(1);
    assertThat(columnCount("rental_item", "transfer_origin_status")).isEqualTo(1);
    assertThat(columnCount("inventory_asset_outcome_watermark", "passport_observation_sha256"))
        .isEqualTo(1);
    assertThat(columnCount("inventory_asset_outcome_watermark", "shipment_contents_sha256"))
        .isEqualTo(1);
    assertThat(
            constraintDefinition(
                "inventory_asset_outcome_watermark",
                "ck_inventory_outcome_watermark_shipment_contents"))
        .contains("RENTED", "shipment_contents_sha256 IS NULL");
    assertThat(
            constraintDefinition(
                "inventory_asset_outcome_receipt", "ck_inventory_outcome_receipt_status"))
        .contains("RENTED");
    assertThat(integer("""
        select count(*) from cabin_catalog_item where kind='CATEGORY'
        """)).isGreaterThanOrEqualTo(4);
    assertThat(integer("""
        select count(*)
        from cabin_catalog_item
        where kind in ('TYPE', 'DIMENSION', 'FINISHING')
          and name_normalized='—'
          and active
        """)).isEqualTo(3);
    assertThat(integer("""
        select count(*)
        from cabin_type_dimension link
        join cabin_catalog_item type_item on type_item.id=link.cabin_type_id
        join cabin_catalog_item dimension_item on dimension_item.id=link.dimension_id
        where type_item.name_normalized='—'
          and dimension_item.name_normalized='—'
        """)).isOne();
    assertThat(toRegclass("asset_attribute_definition")).isNull();
    assertThat(toRegclass("asset_attribute_option")).isNull();
    assertThat(toRegclass("asset_classifier_attribute")).isNull();
    assertThat(toRegclass("rental_item_attribute_value")).isNull();
    assertThat(toRegclass("rental_tag")).isNull();
    assertThat(toRegclass("rental_item_tag")).isNull();
    assertThat(jdbc.queryForObject("select count(*) from rental_item", Integer.class)).isEqualTo(195);
    assertThat(jdbc.queryForObject("select count(*) from equipment_catalog_item", Integer.class)).isEqualTo(20);
    assertThat(jdbc.queryForObject(
        "select count(*) from equipment_balance where rental_item_id is not null", Integer.class))
        .isGreaterThan(0);
    assertThat(jdbc.queryForObject(
        "select count(*) from rental_item where passport_json::jsonb->>'source'='old-panel-rental-items-v1'",
        Integer.class)).isZero();
    assertOldPanelTechnicalMetadataRemoved();
    assertLegacyIdentityMetadataRemoved();
    assertThat(jdbc.queryForObject("""
        select count(distinct (warehouse_id, identity_match_key)) from rental_item
        """, Integer.class)).isEqualTo(195);
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='RENTAL_ITEM'", Integer.class))
        .isGreaterThan(195);
    assertThat(columnCount("equipment_allocation_hold", "source_balance_id")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "executed_at")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "order_id")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "target_rental_item_id")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "order_units")).isEqualTo(1);
    assertThat(columnCount("equipment_allocation_hold", "replacement_source_reservation_id"))
        .isEqualTo(1);
    assertThat(
            constraintDefinition(
                "equipment_allocation_hold", "ck_equipment_hold_order_context"))
        .contains(
            "order_id IS NULL",
            "target_rental_item_id IS NULL",
            "order_units IS NULL",
            "replacement_source_reservation_id IS NULL",
            "order_id IS NOT NULL",
            "target_rental_item_id IS NOT NULL",
            "order_units IS NOT NULL");
    assertThat(toRegclass("ix_equipment_hold_active_order_target")).isNotNull();
    assertThat(toRegclass("idx_rental_item_creation_intent_pending")).isNotNull();
    assertThat(
            constraintDefinition(
                "rental_item_creation_photo",
                "ck_rental_item_creation_photo_content_type"))
        .contains("image/jpeg", "image/png", "image/webp");
    assertThat(
            integer("""
                select count(*) from pg_trigger
                where not tgisinternal
                  and tgname in ('ck_rental_item_creation_intent_manifest',
                                 'ck_rental_item_creation_photo_manifest')
                """))
        .isEqualTo(2);
    assertThat(
            constraintDefinition(
                "rental_item_creation_intent",
                "ck_rental_item_creation_intent_terminal"))
        .contains("PENDING", "COMPLETED", "ABANDONED", "media_proof_sha256");
    assertThat(integer("""
        select count(*) from pg_trigger
        where not tgisinternal
          and tgname in (
            'trg_rental_item_warehouse_readiness',
            'trg_equipment_balance_warehouse_readiness',
            'trg_equipment_allocation_hold_warehouse_readiness',
            'trg_order_equipment_reservation_warehouse_readiness',
            'trg_order_unit_reservation_warehouse_readiness',
            'trg_presentation_unit_hold_warehouse_readiness',
            'trg_inventory_asset_capture_warehouse_readiness',
            'trg_rental_item_html_import_warehouse_readiness',
            'trg_property_disposition_fence_warehouse_readiness',
            'trg_maintenance_furniture_custody_claim_warehouse_readiness',
            'trg_operation_lease_warehouse_readiness')
        """)).isEqualTo(11);
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
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(52);
    latest.validate();

    assertThat(appliedVersions())
        .containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50", "51", "52", "53", "54");
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
  void versionThirtyEightMakesExistingOutcomeWatermarksEligibleForOnePassportAdoption() {
    Flyway versionThirtyEight = configuration(MIGRATIONS).target("38").load();
    assertThat(versionThirtyEight.migrate().migrationsExecuted).isEqualTo(38);
    UUID existingId =
        jdbc.queryForObject("select id from rental_item order by id limit 1", UUID.class);
    UUID warehouseId =
        jdbc.queryForObject(
            "select warehouse_id from rental_item where id=?", UUID.class, existingId);
    jdbc.update(
        """
        insert into inventory_asset_outcome_watermark(
          asset_id,version,inventory_id,finding_id,warehouse_id,inventory_completed_at,
          final_plan_version,final_plan_sha256,finding_revision,desired_status,created_at,updated_at)
        values (?,0,?,?,?,?,2,?,3,'FREE',clock_timestamp(),clock_timestamp())
        """,
        existingId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        warehouseId,
        OffsetDateTime.now(),
        "a".repeat(64));

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(16);
    latest.validate();

    assertThat(columnCount("inventory_asset_outcome_watermark", "passport_observation_sha256"))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select passport_observation_sha256 from inventory_asset_outcome_watermark where asset_id=?",
                String.class,
                existingId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select shipment_contents_sha256 from inventory_asset_outcome_watermark where asset_id=?",
                String.class,
                existingId))
        .isNull();
    assertThat(jdbc.queryForObject("select count(*) from rental_item where id=?", Integer.class, existingId))
        .isOne();
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortyOneAcceptsCustomerPresentationAndOrderReservationAudit() {
    Flyway versionForty = configuration(MIGRATIONS).target("40").load();
    assertThat(versionForty.migrate().migrationsExecuted).isEqualTo(40);
    assertThat(
            constraintDefinition(
                "presentation_unit_hold", "ck_presentation_unit_hold_role"))
        .doesNotContain("CUSTOMER");
    assertThat(
            constraintDefinition(
                "order_unit_reservation", "ck_order_unit_reservation_added_role"))
        .doesNotContain("CUSTOMER");

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(14);
    latest.validate();
    assertThat(
            constraintDefinition(
                "presentation_unit_hold", "ck_presentation_unit_hold_role"))
        .contains("CUSTOMER");
    assertThat(
            constraintDefinition(
                "order_unit_reservation", "ck_order_unit_reservation_added_role"))
        .contains("CUSTOMER");
    assertThat(
            constraintDefinition(
                "order_unit_reservation", "ck_order_unit_reservation_released_role"))
        .contains("CUSTOMER");

    List<Map<String, Object>> cabins =
        jdbc.queryForList(
            """
            select id,warehouse_id
            from rental_item
            order by id
            limit 2
            """);
    UUID heldRentalItemId = (UUID) cabins.get(0).get("id");
    UUID heldWarehouseId = (UUID) cabins.get(0).get("warehouse_id");
    UUID reservedRentalItemId = (UUID) cabins.get(1).get("id");
    UUID reservedWarehouseId = (UUID) cabins.get(1).get("warehouse_id");
    UUID actorSubjectId = UUID.randomUUID();
    jdbc.update(
        """
        insert into presentation_unit_hold(
          id,version,presentation_id,rental_item_id,warehouse_id,state,expires_at,
          created_by_subject_id,created_by_role,created_at,updated_at)
        values (?,0,?,?,?,'ACTIVE',clock_timestamp()+interval '30 minutes',
          ?,'CUSTOMER',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        heldRentalItemId,
        heldWarehouseId,
        actorSubjectId);
    UUID reservationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_unit_reservation(
          id,version,order_id,rental_item_id,warehouse_id,state,
          added_by_subject_id,added_by_role,created_at,updated_at)
        values (?,0,?,?,?,'ACTIVE',?,'CUSTOMER',clock_timestamp(),clock_timestamp())
        """,
        reservationId,
        UUID.randomUUID(),
        reservedRentalItemId,
        reservedWarehouseId,
        actorSubjectId);
    assertThat(
            jdbc.update(
                """
                update order_unit_reservation
                set version=version+1,state='RELEASED',released_by_subject_id=?,
                  released_by_role='CUSTOMER',released_at=clock_timestamp(),
                  updated_at=clock_timestamp()
                where id=?
                """,
                actorSubjectId,
                reservationId))
        .isOne();
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortyTwoAddsHistoricalTransferUnitReservationFences() {
    Flyway versionFortyOne = configuration(MIGRATIONS).target("41").load();
    assertThat(versionFortyOne.migrate().migrationsExecuted).isEqualTo(41);
    assertThat(toRegclass("transfer_unit_reservation")).isNull();

    Flyway versionFortyTwo = configuration(MIGRATIONS).target("42").load();
    assertThat(versionFortyTwo.migrate().migrationsExecuted).isOne();
    versionFortyTwo.validate();

    assertThat(toRegclass("transfer_unit_reservation")).isNotNull();
    assertThat(
            constraintDefinition(
                "transfer_unit_reservation", "uk_transfer_unit_reservation_line"))
        .isEqualTo("UNIQUE (transfer_id, line_id)");
    assertThat(indexDefinition("uk_transfer_unit_reservation_active_item"))
        .contains("UNIQUE", "rental_item_id", "WHERE", "state", "ACTIVE");
    assertThat(versionFortyTwo.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortyThreePersistsClosedEquipmentMovementPurpose() {
    Flyway versionFortyTwo = configuration(MIGRATIONS).target("42").load();
    assertThat(versionFortyTwo.migrate().migrationsExecuted).isEqualTo(42);
    assertThat(columnCount("equipment_allocation_hold", "movement_purpose")).isZero();
    Map<String, Object> stock =
        jdbc.queryForMap(
            """
            select id,equipment_id,warehouse_id
            from equipment_balance
            where rental_item_id is null and location_kind='STOCK'
            order by id
            limit 1
            """);
    UUID legacyReservationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_allocation_hold(
          id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,
          state,idempotency_key,expires_at,created_at,updated_at)
        values (?,0,?,?,?,'LOGISTICS_EQUIPMENT_MOVEMENT',?,1,'ACTIVE',?,
          clock_timestamp()+interval '1 hour',clock_timestamp(),clock_timestamp())
        """,
        legacyReservationId,
        stock.get("equipment_id"),
        stock.get("warehouse_id"),
        stock.get("id"),
        UUID.randomUUID() + ":" + UUID.randomUUID(),
        UUID.randomUUID());

    Flyway latest = configuration(MIGRATIONS).target("43").load();
    assertThat(latest.migrate().migrationsExecuted).isOne();
    latest.validate();

    assertThat(columnCount("equipment_allocation_hold", "movement_purpose")).isEqualTo(1);
    assertThat(
            constraintDefinition(
                "equipment_allocation_hold", "ck_equipment_hold_movement_purpose"))
        .contains(
            "ALLOCATABLE_REBALANCE",
            "TRANSFER_REBALANCE",
            "MAINTENANCE_DISPOSITION");
    assertThat(
            jdbc.queryForObject(
                "select movement_purpose from equipment_allocation_hold where id=?",
                String.class,
                legacyReservationId))
        .isEqualTo("ALLOCATABLE_REBALANCE");
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortyFourPartitionsActiveEquipmentReservationsByPhysicalSource() {
    Flyway versionFortyThree = configuration(MIGRATIONS).target("43").load();
    assertThat(versionFortyThree.migrate().migrationsExecuted).isEqualTo(43);
    assertThat(toRegclass("uk_order_equipment_reservation_active_order_equipment")).isNotNull();
    assertThat(
            toRegclass(
                "uk_order_equipment_reservation_active_order_warehouse_equipment"))
        .isNull();

    UUID orderId = UUID.randomUUID();
    UUID equipmentId =
        jdbc.queryForObject(
            "select id from equipment_catalog_item order by id limit 1", UUID.class);
    UUID firstReservationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_equipment_reservation(
          id,version,order_id,equipment_id,warehouse_id,quantity,state,created_at,updated_at)
        values (?,0,?,?,?,1,'ACTIVE',clock_timestamp(),clock_timestamp())
        """,
        firstReservationId,
        orderId,
        equipmentId,
        SPB_WAREHOUSE_ID);

    Flyway versionFortyFour = configuration(MIGRATIONS).target("44").load();
    assertThat(versionFortyFour.migrate().migrationsExecuted).isOne();
    versionFortyFour.validate();
    assertThat(toRegclass("uk_order_equipment_reservation_active_order_equipment")).isNull();
    assertThat(
            toRegclass(
                "uk_order_equipment_reservation_active_order_warehouse_equipment"))
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_equipment_reservation where id=?",
                Integer.class,
                firstReservationId))
        .isOne();
    assertThat(
            jdbc.update(
                """
                insert into order_equipment_reservation(
                  id,version,order_id,equipment_id,warehouse_id,quantity,state,created_at,updated_at)
                values (?,0,?,?,?,1,'ACTIVE',clock_timestamp(),clock_timestamp())
                """,
                UUID.randomUUID(),
                orderId,
                equipmentId,
                MSK_WAREHOUSE_ID))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from order_equipment_reservation
                where order_id=? and equipment_id=? and state='ACTIVE'
                """,
                Integer.class,
                orderId,
                equipmentId))
        .isEqualTo(2);
  }

  @Test
  void versionFortyFiveAddsDurableCreationIntentWithoutRewritingExistingCabins() {
    Flyway versionFortyFour = configuration(MIGRATIONS).target("44").load();
    assertThat(versionFortyFour.migrate().migrationsExecuted).isEqualTo(44);
    int rentalItemCount = integer("select count(*) from rental_item");
    assertThat(toRegclass("rental_item_creation_intent")).isNull();

    Flyway versionFortyFive = configuration(MIGRATIONS).target("45").load();
    assertThat(versionFortyFive.migrate().migrationsExecuted).isOne();
    versionFortyFive.validate();

    assertThat(toRegclass("rental_item_creation_intent")).isNotNull();
    assertThat(toRegclass("rental_item_creation_photo")).isNotNull();
    assertThat(toRegclass("idx_rental_item_creation_intent_pending")).isNotNull();
    assertThat(integer("select count(*) from rental_item")).isEqualTo(rentalItemCount);
    assertThat(versionFortyFive.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortySixBackfillsImmutableCompanyOwnershipThenFortySevenRemovesIt() {
    Flyway versionFortyFive = configuration(MIGRATIONS).target("45").load();
    assertThat(versionFortyFive.migrate().migrationsExecuted).isEqualTo(45);
    int rentalItemCount = integer("select count(*) from rental_item");
    long rentalItemVersionTotal =
        jdbc.queryForObject("select coalesce(sum(version),0) from rental_item", Long.class);
    UUID htmlImportId = UUID.randomUUID();
    jdbc.update(
        """
        insert into rental_item_html_import(
          id,version,warehouse_id,actor_subject_id,idempotency_key,source_sha256,state,
          row_count,selected_count,invalid_count,unresolved_count,media_link_count,
          warning_count,plan_json,created_at,updated_at)
        values (?,0,?,?,?,?,'DRAFT',0,0,0,0,0,0,'{}',clock_timestamp(),clock_timestamp())
        """,
        htmlImportId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
    assertThat(columnCount("rental_item", "company_id")).isZero();

    Flyway versionFortySix = configuration(MIGRATIONS).target("46").load();
    assertThat(versionFortySix.migrate().migrationsExecuted).isOne();
    versionFortySix.validate();

    assertThat(integer("select count(*) from rental_item")).isEqualTo(rentalItemCount);
    assertThat(jdbc.queryForObject(
            "select coalesce(sum(version),0) from rental_item", Long.class))
        .isEqualTo(rentalItemVersionTotal);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item where company_id=?",
                Integer.class,
                INITIAL_COMPANY_ID))
        .isEqualTo(rentalItemCount);
    assertThat(columnCount("rental_item_html_import", "company_id")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select company_id from rental_item_html_import where id=?",
                UUID.class,
                htmlImportId))
        .isEqualTo(INITIAL_COMPANY_ID);
    assertThat(toRegclass("idx_rental_item_company_warehouse_status")).isNotNull();
    assertThat(toRegclass("idx_rental_item_html_import_company_warehouse")).isNotNull();
    UUID rentalItemId =
        jdbc.queryForObject("select id from rental_item order by id limit 1", UUID.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_item set company_id=? where id=?",
                    UUID.randomUUID(),
                    rentalItemId))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("company ownership is immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_item_html_import set company_id=? where id=?",
                    UUID.randomUUID(),
                    htmlImportId))
        .isInstanceOf(RuntimeException.class)
        .hasStackTraceContaining("company ownership is immutable");
    assertThat(versionFortySix.migrate().migrationsExecuted).isZero();

    Flyway versionFortySeven = configuration(MIGRATIONS).target("47").load();
    assertThat(versionFortySeven.migrate().migrationsExecuted).isOne();
    versionFortySeven.validate();
    assertThat(columnCount("rental_item", "company_id")).isZero();
    assertThat(columnCount("rental_item_html_import", "company_id")).isZero();
    assertThat(toRegclass("idx_rental_item_company_warehouse_status")).isNull();
    assertThat(toRegclass("idx_rental_item_html_import_company_warehouse")).isNull();
    assertThat(versionFortySeven.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortySevenRejectsAnUnprovenMultiCompanyAssetDatabase() {
    Flyway versionFortySix = configuration(MIGRATIONS).target("46").load();
    assertThat(versionFortySix.migrate().migrationsExecuted).isEqualTo(46);
    UUID rentalItemId =
        jdbc.queryForObject("select id from rental_item order by id limit 1", UUID.class);
    jdbc.execute("alter table rental_item disable trigger prevent_rental_item_company_change");
    jdbc.update("update rental_item set company_id=? where id=?", UUID.randomUUID(), rentalItemId);
    jdbc.execute("alter table rental_item enable trigger prevent_rental_item_company_change");

    Flyway versionFortySeven = configuration(MIGRATIONS).target("47").load();
    assertThatThrownBy(versionFortySeven::migrate)
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("multi-company asset database");
    assertThat(columnCount("rental_item", "company_id")).isEqualTo(1);
    assertThat(columnCount("rental_item_html_import", "company_id")).isEqualTo(1);
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
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(47);
    latest.validate();
    assertThat(appliedVersions())
        .containsExactly(
            "1", "2", "3", "4", "5", "6", "7", "8", "9", "10", "11", "12", "13", "14", "15", "16", "17", "18", "19", "20", "21", "22", "23", "24", "25", "26", "27", "28", "29", "30", "31", "32", "33", "34", "35", "36", "37", "38", "39", "40", "41", "42", "43", "44", "45", "46", "47", "48", "49", "50", "51", "52", "53", "54");
    assertOldPanelTechnicalMetadataRemoved();
    assertLegacyIdentityMetadataRemoved();
    JsonNode unrelated = json(jdbc.queryForObject(
        "select passport_json from rental_item where id=?", String.class, unrelatedId));
    assertThat(unrelated.path("source").asText()).isEqualTo("manual-import");
    assertThat(unrelated.path("tenant").asText()).isEqualTo("ООО Сохранить");
    assertTechnicalPassportFieldsRemoved(unrelated);
    assertThat(integer("select count(*) from domain_event")).isGreaterThan(domainEventCount + 75);
    assertThat(integer("select count(*) from outbox_event")).isGreaterThan(outboxCount + 75);
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
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(46);
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
    assertLegacyIdentityMetadataRemoved();
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
  void versionSixteenSanitizesRetainedPassportSnapshotsAndRestoresAppendOnlyGuard() {
    Flyway beforeVersionSixteen = configuration(MIGRATIONS).target("15").load();
    assertThat(beforeVersionSixteen.migrate().migrationsExecuted).isEqualTo(15);

    UUID usefulRentalItemId = jdbc.queryForObject(
        """
        select id
        from rental_item
        where passport_json::jsonb->>'shipmentDate' is not null
          and passport_json::jsonb->'price' <> 'null'::jsonb
        order by id
        limit 1
        """,
        UUID.class);
    JsonNode usefulPassportBefore = json(jdbc.queryForObject(
        "select passport_json from rental_item where id=?",
        String.class,
        usefulRentalItemId));
    UUID captureAssetId = jdbc.queryForObject(
        """
        select id
        from rental_item
        where status in (
          'NEW','BOOKED','REPAIR','WAITING_REPAIR_CHECK','CAPITAL_REPAIR',
          'AFTER_RENT','SALE','USED_SALE','RESERVED','FREE','WAREHOUSE','OWN_NEEDS')
        order by id
        limit 1
        """,
        UUID.class);
    UUID captureOperationId = UUID.randomUUID();
    UUID captureId = UUID.randomUUID();
    String captureRequestHash = "c".repeat(64);
    String membershipDigest = "d".repeat(64);
    jdbc.update(
        """
        insert into inventory_asset_capture_operation(
          operation_id,version,warehouse_id,request_fingerprint,created_at)
        select ?,0,warehouse_id,?,clock_timestamp()
        from rental_item
        where id=?
        """,
        captureOperationId,
        captureRequestHash,
        captureAssetId);
    jdbc.update(
        """
        with timing as (
          select clock_timestamp() - interval '2 hours' as created_at
        )
        insert into inventory_asset_capture(
          capture_id,operation_id,technical_attempt,warehouse_id,request_fingerprint,
          membership_digest,total_count,state,created_at,expires_at,released_at)
        select ?,?,1,item.warehouse_id,?,?,1,'EXPIRED',
          timing.created_at,
          timing.created_at + interval '30 minutes',
          timing.created_at + interval '30 minutes'
        from rental_item item
        cross join timing
        where item.id=?
        """,
        captureId,
        captureOperationId,
        captureRequestHash,
        membershipDigest,
        captureAssetId);
    String retainedSnapshot = """
        {
          "tenant": "Внешний снимок арендатора",
          "passport": {
            "source": "old-panel-rental-items-v1",
            "legacyId": "spb-legacy",
            "legacyWarehouseId": "spb",
            "legacyNumber": "БЫТ-LEGACY",
            "legacyPhotos": [{"url": "https://example.test/legacy.jpg"}],
            "locationNodeId": "legacy-node",
            "hasPhotos": true,
            "photoCount": 1,
            "mainPhotoUrl": "https://example.test/main.jpg",
            "previewPhotoUrls": ["https://example.test/preview.jpg"],
            "price": 42000,
            "shipmentDate": "2026-07-20",
            "tenant": "ООО Полезные данные",
            "nested": {
              "LeGaCyFutureMarker": "remove",
              "items": [{"LEGACYNestedMarker": "remove"}, {"keep": "value"}]
            }
          },
          "other": {"source": "inventory-domain-source"}
        }
        """;
    String technicalOnlySnapshot =
        retainedSnapshot.replace("\"source\": \"old-panel-rental-items-v1\",", "");
    jdbc.update(
        """
        insert into inventory_asset_capture_member(
          capture_id,sequence_no,asset_id,asset_version,warehouse_id,status,
          display_canonical_number,identity_match_key,passport_snapshot,contents_snapshot)
        select ?,0,id,version,warehouse_id,status,display_canonical_number,identity_match_key,
          ?::jsonb,'[]'::jsonb
        from rental_item
        where id=?
        """,
        captureId,
        retainedSnapshot,
        captureAssetId);

    UUID idempotencySubjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into asset_idempotency_record(
          subject_id,command_scope,idempotency_key,request_sha256,response_status,
          response_body,created_at,expires_at)
        values (
          ?,'maintenance.rental-item.fenced-status',?,?,200,?::jsonb,
          clock_timestamp(),clock_timestamp() + interval '1 day')
        """,
        idempotencySubjectId,
        idempotencyKey,
        "e".repeat(64),
        technicalOnlySnapshot);

    Flyway versionSixteen = configuration(MIGRATIONS).target("16").load();
    assertThat(versionSixteen.migrate().migrationsExecuted).isOne();
    versionSixteen.validate();

    JsonNode usefulPassportAfter = json(jdbc.queryForObject(
        "select passport_json from rental_item where id=?",
        String.class,
        usefulRentalItemId));
    assertThat(usefulPassportAfter.path("price")).isEqualTo(usefulPassportBefore.path("price"));
    assertThat(usefulPassportAfter.path("shipmentDate"))
        .isEqualTo(usefulPassportBefore.path("shipmentDate"));
    assertThat(usefulPassportAfter.path("tenant")).isEqualTo(usefulPassportBefore.path("tenant"));
    assertSanitizedOldPanelPassport(usefulPassportAfter);

    JsonNode captureSnapshot = json(jdbc.queryForObject(
        "select passport_snapshot::text from inventory_asset_capture_member where capture_id=?",
        String.class,
        captureId));
    assertUsefulPassportRetained(captureSnapshot.path("passport"));
    assertSanitizedOldPanelPassport(captureSnapshot.path("passport"));
    assertThat(captureSnapshot.path("other").path("source").asText())
        .isEqualTo("inventory-domain-source");
    assertThat(jdbc.queryForObject(
        "select membership_digest from inventory_asset_capture where capture_id=?",
        String.class,
        captureId)).isEqualTo(membershipDigest);

    JsonNode idempotencyResponse = json(jdbc.queryForObject(
        """
        select response_body::text
        from asset_idempotency_record
        where subject_id=? and command_scope='maintenance.rental-item.fenced-status'
          and idempotency_key=?
        """,
        String.class,
        idempotencySubjectId,
        idempotencyKey));
    assertUsefulPassportRetained(idempotencyResponse.path("passport"));
    assertSanitizedOldPanelPassport(idempotencyResponse.path("passport"));
    assertThat(idempotencyResponse.path("other").path("source").asText())
        .isEqualTo("inventory-domain-source");

    assertOldPanelTechnicalMetadataRemoved();
    assertThat(jdbc.queryForObject(
        """
        select tgenabled::text
        from pg_trigger
        where tgrelid='inventory_asset_capture_member'::regclass
          and tgname='trg_inventory_asset_capture_member_no_mutation'
        """,
        String.class)).isEqualTo("O");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update inventory_asset_capture_member
                    set passport_snapshot=passport_snapshot
                    where capture_id=?
                    """,
                    captureId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThat(versionSixteen.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionSeventeenReclassifiesNewAsFreeCategoryAndRemovesItFromTheSchema() {
    Flyway beforeVersionSeventeen = configuration(MIGRATIONS).target("16").load();
    assertThat(beforeVersionSeventeen.migrate().migrationsExecuted).isEqualTo(16);
    UUID rentalItemId =
        jdbc.queryForObject(
            """
            select item.id
            from rental_item item
            join event_stream_head head
              on head.aggregate_type='RENTAL_ITEM'
             and head.aggregate_id=item.id::text
            join domain_event event on event.event_id=head.last_event_id
            where item.status='FREE' and event.payload ? 'status'
            order by item.id
            limit 1
            """,
            UUID.class);
    UUID warehouseId =
        jdbc.queryForObject(
            "select warehouse_id from rental_item where id=?", UUID.class, rentalItemId);
    UUID eventId =
        jdbc.queryForObject(
            """
            select last_event_id from event_stream_head
            where aggregate_type='RENTAL_ITEM' and aggregate_id=?
            """,
            UUID.class,
            rentalItemId.toString());
    long version =
        jdbc.queryForObject(
            "select version from rental_item where id=?", Long.class, rentalItemId);

    jdbc.execute("alter table domain_event disable trigger trg_domain_event_immutable");
    jdbc.update(
        """
        update domain_event
        set payload=jsonb_set(payload,'{status}','"NEW"'::jsonb,false),
            payload_sha256=encode(sha256(convert_to(
              jsonb_set(payload,'{status}','"NEW"'::jsonb,false)::text,'UTF8')),'hex')
        where event_id=?
        """,
        eventId);
    jdbc.execute("alter table domain_event enable trigger trg_domain_event_immutable");
    jdbc.update(
        """
        update outbox_event
        set envelope_body=jsonb_set(envelope_body,'{payload,status}','"NEW"'::jsonb,false),
            envelope_sha256=encode(sha256(convert_to(
              jsonb_set(envelope_body,'{payload,status}','"NEW"'::jsonb,false)::text,
              'UTF8')),'hex')
        where event_id=?
        """,
        eventId);
    jdbc.update(
        """
        update aggregate_snapshot
        set state=jsonb_set(
              jsonb_set(state,'{status}','"NEW"'::jsonb,false),
              '{category}',to_jsonb('Обычная'::text),true),
            state_sha256=encode(sha256(convert_to(
              jsonb_set(
                jsonb_set(state,'{status}','"NEW"'::jsonb,false),
                '{category}',to_jsonb('Обычная'::text),true)::text,
              'UTF8')),'hex')
        where aggregate_type='RENTAL_ITEM' and aggregate_id=?
          and aggregate_version=?
        """,
        rentalItemId.toString(),
        version);
    jdbc.update(
        """
        update projection_checkpoint checkpoint
        set projection_sha256=snapshot.state_sha256
        from aggregate_snapshot snapshot
        where snapshot.aggregate_type=checkpoint.aggregate_type
          and snapshot.aggregate_id=checkpoint.aggregate_id
          and snapshot.aggregate_version=checkpoint.aggregate_version
          and checkpoint.aggregate_type='RENTAL_ITEM'
          and checkpoint.aggregate_id=?
          and checkpoint.aggregate_version=?
        """,
        rentalItemId.toString(),
        version);
    jdbc.update(
        "update rental_item set status='NEW',category='Обычная' where id=?",
        rentalItemId);

    UUID captureOperationId = UUID.randomUUID();
    UUID captureId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_asset_capture_operation(
          operation_id,version,warehouse_id,request_fingerprint,created_at)
        values (?,0,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          clock_timestamp())
        """,
        captureOperationId,
        warehouseId);
    jdbc.update(
        """
        with timing as (select clock_timestamp() as created_at)
        insert into inventory_asset_capture(
          capture_id,operation_id,technical_attempt,warehouse_id,request_fingerprint,
          membership_digest,total_count,state,created_at,expires_at,released_at)
        select ?,?,1,?,
          'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          'bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb',
          1,'ACTIVE',created_at,created_at+interval '30 minutes',null
        from timing
        """,
        captureId,
        captureOperationId,
        warehouseId);
    jdbc.update(
        """
        insert into inventory_asset_capture_member(
          capture_id,sequence_no,asset_id,asset_version,warehouse_id,status,
          display_canonical_number,identity_match_key,passport_snapshot,contents_snapshot)
        select ?,0,id,version,warehouse_id,'NEW',display_canonical_number,
          identity_match_key,
          '{"status":"NEW","category":"Обычная"}'::jsonb,
          '[]'::jsonb
        from rental_item where id=?
        """,
        captureId,
        rentalItemId);
    UUID idempotencySubject = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into asset_idempotency_record(
          subject_id,command_scope,idempotency_key,request_sha256,response_status,
          response_body,created_at,expires_at)
        values (?,'test.new-status',?,
          'cccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccccc',
          200,'{"status":"NEW","category":"Обычная"}'::jsonb,
          clock_timestamp(),clock_timestamp()+interval '1 hour')
        """,
        idempotencySubject,
        idempotencyKey);

    Flyway versionSeventeen = configuration(MIGRATIONS).target("17").load();
    assertThat(versionSeventeen.migrate().migrationsExecuted).isOne();
    versionSeventeen.validate();

    assertThat(
            jdbc.queryForObject(
                "select status from rental_item where id=?",
                String.class,
                rentalItemId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select category from rental_item where id=?",
                String.class,
                rentalItemId))
        .isEqualTo("Новая");
    assertThat(columnCount("order_unit_reservation", "source_status")).isZero();
    assertThat(constraintDefinition("rental_item", "ck_rental_item_status"))
        .contains("FREE")
        .doesNotContain("'NEW'");
    assertThat(
            constraintDefinition(
                "inventory_asset_capture_member",
                "ck_inventory_asset_capture_member_status"))
        .contains("FREE")
        .doesNotContain("'NEW'");
    assertThat(jdbc.queryForObject(
        "select status from inventory_asset_capture_member where capture_id=?",
        String.class,
        captureId)).isEqualTo("FREE");
    assertThat(jdbc.queryForObject(
        "select passport_snapshot->>'category' from inventory_asset_capture_member where capture_id=?",
        String.class,
        captureId)).isEqualTo("Новая");
    assertThat(jdbc.queryForObject(
        """
        select response_body->>'status'
        from asset_idempotency_record
        where subject_id=? and command_scope='test.new-status' and idempotency_key=?
        """,
        String.class,
        idempotencySubject,
        idempotencyKey)).isEqualTo("FREE");
    assertThat(integer(
        "select count(*) from domain_event where payload->>'status'='NEW'"))
        .isZero();
    assertThat(integer(
        "select count(*) from aggregate_snapshot where state->>'status'='NEW'"))
        .isZero();
    assertThat(integer(
        "select count(*) from outbox_event where envelope_body->'payload'->>'status'='NEW'"))
        .isZero();
    assertCanonicalEventState();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_item set status='NEW' where id=?",
                    rentalItemId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update inventory_asset_capture_member
                    set status=status
                    where capture_id=?
                    """,
                    captureId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void versionEighteenRemovesLegacyBusinessIdentifiersAndSanitizesReplayState() {
    Flyway beforeVersionEighteen = configuration(MIGRATIONS).target("17").load();
    assertThat(beforeVersionEighteen.migrate().migrationsExecuted).isEqualTo(17);
    UUID equipmentId = jdbc.queryForObject(
        "select id from equipment_catalog_item order by id limit 1", UUID.class);
    UUID eventId = jdbc.queryForObject(
        """
        select event_id from domain_event
        where aggregate_type='EQUIPMENT_CATALOG' and aggregate_id=?
        order by aggregate_version limit 1
        """,
        UUID.class,
        equipmentId.toString());
    UUID idempotencySubject = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into asset_idempotency_record(
          subject_id,command_scope,idempotency_key,request_sha256,response_status,
          response_body,created_at,expires_at)
        values (?,'equipment-catalog.create',?,
          'dddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddddd',
          201,?::jsonb,clock_timestamp(),clock_timestamp()+interval '1 hour')
        """,
        idempotencySubject,
        idempotencyKey,
        "{\"id\":\"" + equipmentId + "\",\"code\":\"LEGACY\",\"name\":\"Legacy item\"}");

    assertThat(integer(
        "select count(*) from domain_event where aggregate_type='EQUIPMENT_CATALOG' and payload ? 'code'"))
        .isPositive();
    assertThat(integer(
        "select count(*) from outbox_event where aggregate_type='EQUIPMENT_CATALOG' and envelope_body->'payload' ? 'code'"))
        .isPositive();

    Flyway versionEighteen = configuration(MIGRATIONS).target("18").load();
    assertThat(versionEighteen.migrate().migrationsExecuted).isOne();
    versionEighteen.validate();

    assertThat(columnCount("equipment_catalog_item", "code")).isZero();
    assertThat(columnCount("asset_classifier", "code")).isZero();
    assertThat(toRegclass("asset_attribute_definition")).isNull();
    assertThat(toRegclass("asset_attribute_option")).isNull();
    assertThat(toRegclass("asset_classifier_attribute")).isNull();
    assertThat(toRegclass("rental_item_attribute_value")).isNull();
    assertThat(toRegclass("rental_tag")).isNull();
    assertThat(toRegclass("rental_item_tag")).isNull();
    assertThat(integer(
        """
        select count(*) from domain_event
        where aggregate_type in ('EQUIPMENT_CATALOG','CLASSIFIER')
          and (payload ? 'code' or payload ? 'equipmentCode' or payload ? 'classifierCode')
        """))
        .isZero();
    assertThat(integer(
        """
        select count(*) from outbox_event
        where aggregate_type in ('EQUIPMENT_CATALOG','CLASSIFIER')
          and (envelope_body->'payload' ? 'code'
            or envelope_body->'payload' ? 'equipmentCode'
            or envelope_body->'payload' ? 'classifierCode')
        """))
        .isZero();
    assertThat(integer(
        """
        select count(*) from aggregate_snapshot
        where aggregate_type in ('EQUIPMENT_CATALOG','CLASSIFIER')
          and (state ? 'code' or state ? 'equipmentCode' or state ? 'classifierCode')
        """))
        .isZero();
    assertThat(jdbc.queryForObject(
        """
        select jsonb_exists(response_body, 'code')
        from asset_idempotency_record
        where subject_id=? and command_scope='equipment-catalog.create' and idempotency_key=?
        """,
        Boolean.class,
        idempotencySubject,
        idempotencyKey)).isFalse();
    assertThat(integer(
        """
        select count(*)
        from projection_checkpoint checkpoint
        join aggregate_snapshot snapshot
          on snapshot.aggregate_type=checkpoint.aggregate_type
         and snapshot.aggregate_id=checkpoint.aggregate_id
         and snapshot.aggregate_version=checkpoint.aggregate_version
        where checkpoint.projection_sha256<>snapshot.state_sha256
        """))
        .isZero();
    assertThatThrownBy(
            () -> jdbc.update(
                "update domain_event set payload=payload || '{\"migrationProbe\":true}'::jsonb where event_id=?",
                eventId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void versionNineteenNormalizesCabinCompositionAndSplitsLegacyCharacteristicText() {
    Flyway versionEighteen = configuration(MIGRATIONS).target("18").load();
    assertThat(versionEighteen.migrate().migrationsExecuted).isEqualTo(18);
    UUID rentalItemId =
        jdbc.queryForObject("select id from rental_item order by id limit 1", UUID.class);
    jdbc.update(
        """
        update rental_item
        set rental_type='БК-1', dimensions='2.4x6', finishing='ДВП',
            characteristics='Металлическая дверь, кондиционер, Металлическая дверь'
        where id=?
        """,
        rentalItemId);

    Flyway versionNineteen = configuration(MIGRATIONS).target("19").load();
    assertThat(versionNineteen.migrate().migrationsExecuted).isOne();
    versionNineteen.validate();

    assertThat(columnCount("rental_item", "rental_type")).isZero();
    assertThat(columnCount("rental_item", "dimensions")).isZero();
    assertThat(columnCount("rental_item", "finishing")).isZero();
    assertThat(columnCount("rental_item", "characteristics")).isZero();
    assertThat(jdbc.queryForObject(
        """
        select count(*)
        from rental_item
        where id=?
          and cabin_type_id is not null
          and cabin_dimension_id is not null
          and cabin_finishing_id is not null
        """,
        Integer.class,
        rentalItemId)).isOne();
    assertThat(jdbc.queryForList(
        """
        select catalog.name
        from rental_item_characteristic link
        join cabin_catalog_item catalog on catalog.id=link.characteristic_id
        where link.rental_item_id=?
        order by link.sort_order
        """,
        String.class,
        rentalItemId)).containsExactly("Металлическая дверь", "Кондиционер");
    assertThat(jdbc.queryForObject(
        """
        select count(*)
        from cabin_type_dimension link
        join cabin_catalog_item type_item on type_item.id=link.cabin_type_id
        join cabin_catalog_item dimension_item on dimension_item.id=link.dimension_id
        where type_item.kind='TYPE' and type_item.name='БК-1'
          and dimension_item.kind='DIMENSION' and dimension_item.name='2.4x6'
        """,
        Integer.class)).isOne();
  }

  @Test
  void versionTwentyAddsExpiryForDraftOrderReservations() {
    Flyway versionNineteen = configuration(MIGRATIONS).target("19").load();
    assertThat(versionNineteen.migrate().migrationsExecuted).isEqualTo(19);
    assertThat(columnCount("order_unit_reservation", "draft_reservation_expires_at")).isZero();

    Flyway versionTwenty = configuration(MIGRATIONS).target("20").load();
    assertThat(versionTwenty.migrate().migrationsExecuted).isOne();
    versionTwenty.validate();

    assertThat(columnCount("order_unit_reservation", "draft_reservation_expires_at")).isEqualTo(1);
    assertThat(toRegclass("idx_order_unit_reservation_draft_expiry")).isNotNull();
  }

  @Test
  void versionTwentyFourPreservesTheOnlyLegacyTransferOriginAndEnforcesItsLifecycle() {
    Flyway versionTwentyThree = configuration(MIGRATIONS).target("23").load();
    assertThat(versionTwentyThree.migrate().migrationsExecuted).isEqualTo(23);
    UUID rentalItemId =
        jdbc.queryForObject(
            """
            select rental.id
            from rental_item rental
            join event_stream_head head
              on head.aggregate_type='RENTAL_ITEM'
             and head.aggregate_id=rental.id::text
            join domain_event event on event.event_id=head.last_event_id
            where rental.status='FREE'
              and event.event_type not in (
                'asset.rental-item.general-comment-changed.v1',
                'asset.rental-item.manual-note-added.v1')
            order by rental.id limit 1
            """,
            UUID.class);
    UUID eventId =
        jdbc.queryForObject(
            """
            select last_event_id from event_stream_head
            where aggregate_type='RENTAL_ITEM' and aggregate_id=?
            """,
            UUID.class,
            rentalItemId.toString());
    jdbc.update(
        "update rental_item set status='IN_TRANSFER' where id=?",
        rentalItemId);
    jdbc.execute(
        "alter table domain_event disable trigger trg_domain_event_immutable");
    jdbc.update(
        """
        update domain_event
        set payload=payload || '{"status":"IN_TRANSFER"}'::jsonb,
            payload_sha256=encode(sha256(convert_to(
              (payload || '{"status":"IN_TRANSFER"}'::jsonb)::text,'UTF8')),'hex')
        where event_id=?
        """,
        eventId);
    jdbc.execute(
        "alter table domain_event enable trigger trg_domain_event_immutable");
    jdbc.update(
        """
        update outbox_event
        set envelope_body=jsonb_set(
              envelope_body,'{payload,status}',to_jsonb('IN_TRANSFER'::text),true),
            envelope_sha256=encode(sha256(convert_to(
              jsonb_set(
                envelope_body,'{payload,status}',to_jsonb('IN_TRANSFER'::text),true)::text,
              'UTF8')),'hex')
        where event_id=?
        """,
        eventId);
    jdbc.update(
        """
        update aggregate_snapshot
        set state=jsonb_set(
              state,'{status}',to_jsonb('IN_TRANSFER'::text),true),
            state_sha256=encode(sha256(convert_to(
              jsonb_set(
                state,'{status}',to_jsonb('IN_TRANSFER'::text),true)::text,
              'UTF8')),'hex')
        where aggregate_type='RENTAL_ITEM' and aggregate_id=?
          and aggregate_version=(
            select current_version from event_stream_head
            where aggregate_type='RENTAL_ITEM' and aggregate_id=?)
        """,
        rentalItemId.toString(),
        rentalItemId.toString());
    jdbc.update(
        """
        update projection_checkpoint checkpoint
        set projection_sha256=snapshot.state_sha256
        from aggregate_snapshot snapshot
        where snapshot.aggregate_type=checkpoint.aggregate_type
          and snapshot.aggregate_id=checkpoint.aggregate_id
          and snapshot.aggregate_version=checkpoint.aggregate_version
          and checkpoint.aggregate_type='RENTAL_ITEM'
          and checkpoint.aggregate_id=?
        """,
        rentalItemId.toString());

    Flyway versionTwentyFour = configuration(MIGRATIONS).target("24").load();
    assertThat(versionTwentyFour.migrate().migrationsExecuted).isOne();
    versionTwentyFour.validate();

    assertThat(
            jdbc.queryForObject(
                "select transfer_origin_status from rental_item where id=?",
                String.class,
                rentalItemId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                """
                select payload->>'transferAssetStatus'
                from domain_event where event_id=?
                """,
                String.class,
                eventId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                """
                select envelope_body#>>'{payload,transferAssetStatus}'
                from outbox_event where event_id=?
                """,
                String.class,
                eventId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                """
                select state->>'transferOriginStatus'
                from aggregate_snapshot
                where aggregate_type='RENTAL_ITEM' and aggregate_id=?
                order by aggregate_version desc limit 1
                """,
                String.class,
                rentalItemId.toString()))
        .isEqualTo("FREE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_item set transfer_origin_status='RENTED' where id=?",
                    rentalItemId))
        .isInstanceOf(RuntimeException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_item set status='FREE' where id=?",
                    rentalItemId))
        .isInstanceOf(RuntimeException.class);
    assertThat(
            jdbc.update(
                """
                update rental_item
                set status='REPAIR',transfer_origin_status=null
                where id=?
                """,
                rentalItemId))
        .isOne();
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
  void versionTwentyFiveAddsAnImmutableFurnitureReconciliationSource() {
    Flyway versionTwentyFour = configuration(MIGRATIONS).target("24").load();
    assertThat(versionTwentyFour.migrate().migrationsExecuted).isEqualTo(24);

    Flyway versionTwentyFive = configuration(MIGRATIONS).target("25").load();
    assertThat(versionTwentyFive.migrate().migrationsExecuted).isOne();
    versionTwentyFive.validate();

    UUID inventoryId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_furniture_reconciliation(
          inventory_id,version,request_sha256,idempotency_key,created_at,updated_at)
        values (?,0,?,?,clock_timestamp(),clock_timestamp())
        """,
        inventoryId,
        "a".repeat(64),
        UUID.randomUUID());
    assertThat(columnCount("inventory_furniture_reconciliation", "completed_at")).isEqualTo(1);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_furniture_reconciliation set request_sha256=? where inventory_id=?",
                    "b".repeat(64),
                    inventoryId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("source identity is immutable");
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

  private String indexDefinition(String index) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname='public' and indexname=?",
        String.class,
        index);
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
        select item.id,item.version,item.warehouse_id,item.display_canonical_number,
          item.identity_match_key,item.status,
          type_item.name as rental_type,dimension_item.name as dimensions,
          finishing_item.name as finishing,item.category,characteristics.names as characteristics,
          item.linoleum,item.general_comment,item.passport_json
        from rental_item item
        left join cabin_catalog_item type_item on type_item.id=item.cabin_type_id
        left join cabin_catalog_item dimension_item on dimension_item.id=item.cabin_dimension_id
        left join cabin_catalog_item finishing_item on finishing_item.id=item.cabin_finishing_id
        left join lateral (
          select string_agg(catalog.name, ', ' order by link.sort_order, link.id) as names
          from rental_item_characteristic link
          join cabin_catalog_item catalog on catalog.id=link.characteristic_id
          where link.rental_item_id=item.id
        ) characteristics on true
        where item.id::text like '51000000-0000-4000-8000-%'
        order by item.warehouse_id,item.display_canonical_number
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
    assertLegacyIdentityMetadataRemoved();
    assertThat(integer("""
        select count(*) from (
          select warehouse_id,identity_match_key
          from rental_item group by warehouse_id,identity_match_key having count(*)>1
        ) duplicate
        """)).isZero();
  }

  private void assertOldPanelTechnicalMetadataRemoved() {
    assertThat(integer("""
        select count(*) from rental_item
        where id::text like '51000000-0000-4000-8000-%'
          and (
            passport_json::jsonb ?| array[
              'source','legacyId','legacyWarehouseId','legacyNumber','legacyPhotos',
              'locationNodeId','hasPhotos','photoCount','mainPhotoUrl','previewPhotoUrls']
            or passport_json like '%images.unsplash.com%')
        """)).isZero();
    JsonNode firstPassport = json(jdbc.queryForObject("""
        select passport_json from rental_item
        where display_canonical_number='БЫТ-001'
          and warehouse_id='00000000-0000-0000-0000-000000000001'
        """, String.class));
    assertSanitizedOldPanelPassport(firstPassport);
    assertThat(integer("""
        select count(*) from aggregate_snapshot snapshot
        where snapshot.aggregate_type='RENTAL_ITEM'
          and snapshot.aggregate_id like '51000000-0000-4000-8000-%'
          and (snapshot.state->'passport' ?| array[
                'source','legacyId','legacyWarehouseId','legacyNumber','legacyPhotos',
                'locationNodeId','hasPhotos','photoCount','mainPhotoUrl','previewPhotoUrls']
            or snapshot.state::text like '%images.unsplash.com%'
            or snapshot.state_sha256<>
              encode(sha256(convert_to(snapshot.state::text,'UTF8')),'hex'))
        """)).isZero();
  }

  private void assertUsefulPassportRetained(JsonNode passport) {
    assertThat(passport.path("price").asInt()).isEqualTo(42000);
    assertThat(passport.path("shipmentDate").asText()).isEqualTo("2026-07-20");
    assertThat(passport.path("tenant").asText()).isEqualTo("ООО Полезные данные");
  }

  private void assertSanitizedOldPanelPassport(JsonNode passport) {
    assertThat(passport.has("source")).isFalse();
    assertThat(passport.toString()).doesNotContain("old-panel-rental-items-v1");
    assertTechnicalPassportFieldsRemoved(passport);
  }

  private void assertTechnicalPassportFieldsRemoved(JsonNode passport) {
    String serialized = passport.toString().toLowerCase();
    for (String key :
        List.of(
            "legacy",
            "locationnodeid",
            "hasphotos",
            "photocount",
            "mainphotourl",
            "previewphotourls")) {
      assertThat(serialized).as("recursive passport marker %s", key).doesNotContain(key);
    }
  }

  private void assertLegacyIdentityMetadataRemoved() {
    assertThat(integer("""
        select count(*) from rental_item
        where passport_json::jsonb ?| array[
          'legacyId','legacyWarehouseId','legacyNumber','legacyPhotos']
        """)).isZero();
    assertThat(integer("""
        select count(*) from aggregate_snapshot
        where aggregate_type='RENTAL_ITEM'
          and state->'passport' ?| array[
            'legacyId','legacyWarehouseId','legacyNumber','legacyPhotos']
        """)).isZero();
    assertThat(integer("""
        select count(*) from projection_checkpoint checkpoint
        join rental_item item
          on checkpoint.projection_name='asset-live-v1'
         and checkpoint.aggregate_type='RENTAL_ITEM'
         and checkpoint.aggregate_id=item.id::text
         and checkpoint.aggregate_version=item.version
        join aggregate_snapshot snapshot
          on snapshot.aggregate_type=checkpoint.aggregate_type
         and snapshot.aggregate_id=checkpoint.aggregate_id
         and snapshot.aggregate_version=checkpoint.aggregate_version
        where checkpoint.projection_sha256<>snapshot.state_sha256
        """)).isZero();
  }

  private CabinRow expectedCabin(int globalIndex) {
    boolean spb = globalIndex <= 120;
    int localIndex = spb ? globalIndex : globalIndex - 120;
    int zeroIndex = localIndex - 1;
    UUID warehouseId = spb ? SPB_WAREHOUSE_ID : MSK_WAREHOUSE_ID;
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
        expectedPassport(zeroIndex, status));
  }

  private JsonNode expectedPassport(int zeroIndex, String status) {
    Map<String, Object> passport = new LinkedHashMap<>();
    passport.put(
        "shipmentDate",
        status.equals("RENTED") ? "2026-05-%02d".formatted(zeroIndex % 27 + 1) : null);
    passport.put(
        "tenant",
        status.equals("RENTED")
            ? zeroIndex % 2 == 0 ? "ООО СтройПроект" : "ИП Петров А.В."
            : null);
    passport.put("price", zeroIndex % 3 == 0 ? 30000 + zeroIndex * 250 : null);
    return JSON.valueToTree(passport);
  }

  private void assertTransferredEquipment() {
    assertThat(jdbc.queryForList("""
        select id::text || ':' || name
        from equipment_catalog_item
        where active
        order by id
        """, String.class))
        .containsExactly(
            "52140000-0000-4000-8000-000000000001:Конвектор 1,5 кВ без доп розетки",
            "52140000-0000-4000-8000-000000000002:Кровать двухъярусная металлическая",
            "52140000-0000-4000-8000-000000000003:Лавка металлическая",
            "52140000-0000-4000-8000-000000000004:Стол обеденный",
            "52140000-0000-4000-8000-000000000005:Стол офисный ЛДСП 1200мм",
            "52140000-0000-4000-8000-000000000006:Стол офисный ЛДСП 900мм",
            "52140000-0000-4000-8000-000000000007:Стул офисный",
            "52140000-0000-4000-8000-000000000008:Тумба прикроватная",
            "52140000-0000-4000-8000-000000000009:Тумба с ящиками ЛДСП",
            "52140000-0000-4000-8000-000000000010:Шкаф для бумаг ЛДСП",
            "52140000-0000-4000-8000-000000000011:Шкаф офисный ЛДСП стеллаж");
    assertThat(integer("""
        select count(*) from equipment_catalog_item where not active
        """)).isEqualTo(9);
    assertThat(integer("""
        select count(*) from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where not c.active and b.quantity<>0
        """)).isZero();

    Map<String, Long> totals = jdbc.query("""
        select c.id::text as equipment_id,coalesce(sum(b.quantity),0) as quantity
        from equipment_catalog_item c
        left join equipment_balance b on b.equipment_id=c.id
        where c.active
        group by c.id
        """, rs -> {
      Map<String, Long> values = new LinkedHashMap<>();
      while (rs.next()) {
        values.put(rs.getString("equipment_id"), rs.getLong("quantity"));
      }
      return values;
    });
    assertThat(totals)
        .containsEntry("52140000-0000-4000-8000-000000000001", 40L)
        .containsEntry("52140000-0000-4000-8000-000000000002", 117L)
        .containsEntry("52140000-0000-4000-8000-000000000003", 31L)
        .containsEntry("52140000-0000-4000-8000-000000000004", 114L)
        .containsEntry("52140000-0000-4000-8000-000000000005", 32L)
        .containsEntry("52140000-0000-4000-8000-000000000006", 31L)
        .containsEntry("52140000-0000-4000-8000-000000000007", 227L)
        .containsEntry("52140000-0000-4000-8000-000000000008", 0L)
        .containsEntry("52140000-0000-4000-8000-000000000009", 0L)
        .containsEntry("52140000-0000-4000-8000-000000000010", 28L)
        .containsEntry("52140000-0000-4000-8000-000000000011", 28L);

    assertThat(integer("""
        select count(*) from (
          select b.rental_item_id
          from equipment_balance b
          join equipment_catalog_item c on c.id=b.equipment_id
          where b.rental_item_id is not null
            and b.quantity>0
            and c.id in (
              '52140000-0000-4000-8000-000000000005',
              '52140000-0000-4000-8000-000000000006')
          group by b.rental_item_id
          having count(*)<>2 or min(b.quantity)<>1 or max(b.quantity)<>1
        ) invalid_office_table_split
        """)).isZero();
    assertThat(integer("""
        select count(*) from (
          select b.rental_item_id
          from equipment_balance b
          join equipment_catalog_item c on c.id=b.equipment_id
          where b.rental_item_id is not null
            and b.quantity>0
            and c.id in (
              '52140000-0000-4000-8000-000000000010',
              '52140000-0000-4000-8000-000000000011')
          group by b.rental_item_id
          having count(*)<>1 or sum(b.quantity)<>1
        ) invalid_wardrobe_split
        """)).isZero();
    assertThat(jdbc.queryForObject("""
        select coalesce(sum(b.quantity),0)
        from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where c.active and b.rental_item_id is not null
        """, Long.class)).isEqualTo(478L);
  }

  private void assertCanonicalEventState() {
    int streamCount = integer("select count(*) from event_stream_head");
    int eventCount = integer("select count(*) from domain_event");
    assertThat(streamCount).isGreaterThan(464);
    assertThat(eventCount).isGreaterThan(795);
    assertThat(integer("select count(*) from aggregate_snapshot")).isEqualTo(eventCount);
    assertThat(integer("select count(*) from outbox_event")).isEqualTo(eventCount);
    assertThat(integer("""
        select count(*) from projection_checkpoint where projection_name='asset-live-v1'
        """)).isEqualTo(streamCount);
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
      String name,
      String category,
      boolean active,
      String comment) {}

  private record WarehouseEquipmentRow(
      UUID warehouseId, String name, long stock, long writtenOff, long lost) {}

  private record CabinEquipmentRow(
      String number, String name, long quantity, String locationKind) {}
}
