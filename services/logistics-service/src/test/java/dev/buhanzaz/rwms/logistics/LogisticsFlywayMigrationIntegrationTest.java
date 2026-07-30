package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
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
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
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
    int pendingMigrations = flyway.info().pending().length;

    assertThat(pendingMigrations).isPositive();
    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(pendingMigrations);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(tableNames())
        .contains(
            "aggregate_snapshot",
            "client_presentation",
            "client_presentation_item",
            "consumer_aggregate_checkpoint",
            "equipment_movement_task",
            "equipment_movement_task_line",
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
            "order_client",
            "outbox_event",
            "projection_checkpoint",
            "presentation_booking",
            "rental_inquiry",
            "rental_inquiry_outbox",
            "rental_order",
            "rental_order_audit_event",
            "rental_order_command_receipt",
            "rental_settings",
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

    assertThat(configuration(MIGRATIONS).target("6").load().migrate().migrationsExecuted).isOne();
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
    Flyway latest = flyway(MIGRATIONS);
    int pendingMigrations = latest.info().pending().length;
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(pendingMigrations);
    latest.validate();
  }

  @Test
  void v13AndV17UpgradeConvertsEveryLogisticsScheduleToCalendarDate() {
    Flyway beforeV13 = configuration(MIGRATIONS).target("12").load();
    assertThat(beforeV13.migrate().migrationsExecuted).isEqualTo(12);

    UUID warehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID shipmentId = UUID.randomUUID();
    OffsetDateTime returnTime = OffsetDateTime.parse("2026-07-22T08:00:00Z");
    OffsetDateTime shipmentTime = OffsetDateTime.parse("2026-07-23T17:30:00Z");
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,party_snapshot,driver_snapshot,
          requested_by_subject_id,correlation_id,scheduled_at,created_at,updated_at)
        values (?,0,'RETURN','DRAFT',?,null,?,?,?, ?,clock_timestamp(),clock_timestamp())
        """,
        returnId,
        warehouseId,
        "Return driver",
        subjectId,
        UUID.randomUUID(),
        returnTime);
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,party_snapshot,driver_snapshot,
          requested_by_subject_id,correlation_id,scheduled_at,created_at,updated_at)
        values (?,0,'SHIPMENT','DRAFT',?,?,?, ?,?,?,clock_timestamp(),clock_timestamp())
        """,
        shipmentId,
        warehouseId,
        "Shipment party",
        "Shipment driver",
        subjectId,
        UUID.randomUUID(),
        shipmentTime);

    assertThat(configuration(MIGRATIONS).target("13").load().migrate().migrationsExecuted).isOne();
    assertThat(
            jdbc.queryForObject(
                "select scheduled_date::text from logistics_document where id=?",
                String.class,
                returnId))
        .isEqualTo("2026-07-22");
    assertThat(
            jdbc.queryForObject(
                "select scheduled_date::text from logistics_document where id=?",
                String.class,
                shipmentId))
        .isEqualTo("2026-07-23");
    assertThat(
            jdbc.queryForObject(
                "select scheduled_at from logistics_document where id=?",
                OffsetDateTime.class,
                returnId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select scheduled_at from logistics_document where id=?",
                OffsetDateTime.class,
                shipmentId))
        .isNull();

    UUID transferId = UUID.randomUUID();
    OffsetDateTime transferTaskTime = OffsetDateTime.parse("2026-07-24T09:45:00Z");
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,destination_warehouse_id,
          requested_by_subject_id,correlation_id,scheduled_at,created_at,updated_at)
        values (?,0,'TRANSFER','DRAFT',?,?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        transferId,
        warehouseId,
        destinationWarehouseId,
        subjectId,
        UUID.randomUUID(),
        transferTaskTime);
    assertThat(
            jdbc.queryForObject(
                "select scheduled_at from logistics_document where id=?",
                OffsetDateTime.class,
                transferId))
        .isEqualTo(transferTaskTime);

    assertThat(configuration(MIGRATIONS).target("17").load().migrate().migrationsExecuted).isPositive();
    assertThat(
            jdbc.queryForObject(
                "select scheduled_date::text from logistics_document where id=?",
                String.class,
                transferId))
        .isEqualTo("2026-07-24");
    assertThat(
            jdbc.queryForObject(
                "select scheduled_at from logistics_document where id=?",
                OffsetDateTime.class,
                transferId))
        .isNull();
  }

  @Test
  void v20UpgradeBackfillsChatHoldWithoutChangingPresentationHold() {
    Flyway beforeV20 = configuration(MIGRATIONS).target("19").load();
    assertThat(beforeV20.migrate().migrationsExecuted).isEqualTo(19);
    jdbc.update(
        """
        update rental_settings
        set presentation_hold_minutes=75,
            updated_at=clock_timestamp()
        where id='00000000-0000-0000-0000-000000000001'
        """);

    assertThat(configuration(MIGRATIONS).target("20").load().migrate().migrationsExecuted)
        .isOne();

    Map<String, Object> settings =
        jdbc.queryForMap(
            """
            select chat_selection_hold_minutes, presentation_hold_minutes
            from rental_settings
            where id='00000000-0000-0000-0000-000000000001'
            """);
    assertThat(settings)
        .containsEntry("chat_selection_hold_minutes", 10)
        .containsEntry("presentation_hold_minutes", 75);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update rental_settings
                    set chat_selection_hold_minutes=0
                    where id='00000000-0000-0000-0000-000000000001'
                    """))
        .hasMessageContaining("ck_rental_settings_chat_hold_minutes");
  }

  @Test
  void v21SanitizesOldPanelAndTechnicalOnlyPresentationCabinPassports() {
    Flyway beforeV21 = configuration(MIGRATIONS).target("20").load();
    assertThat(beforeV21.migrate().migrationsExecuted).isEqualTo(20);

    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','ООО Клиент','ооо клиент',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        clientId,
        subjectId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,
          manager_role,warehouse_id,state,created_at,updated_at)
        values (?,0,?,?,?,'Менеджер','RENTAL_MANAGER',?,'ACTIVE',
          clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        UUID.randomUUID(),
        clientId,
        subjectId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into client_presentation(
          id,version,inquiry_id,revision,warehouse_id,state,expires_at,view_until,
          last_publish_idempotency_key,last_publish_request_sha256,created_at,updated_at)
        values (?,0,?,1,?,'ACTIVE',
          clock_timestamp() + interval '1 hour',
          clock_timestamp() + interval '2 hours',
          ?,?,clock_timestamp(),clock_timestamp())
        """,
        presentationId,
        inquiryId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
    String oldPanelSnapshot = """
        {
          "number": "БЫТ-001",
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
          "other": {"source": "presentation-domain-source"}
        }
        """;
    String technicalOnlySnapshot =
        oldPanelSnapshot.replace("\"source\": \"old-panel-rental-items-v1\",", "");
    jdbc.update(
        """
        insert into client_presentation_item(
          id,presentation_id,presentation_revision,rental_item_id,group_key,
          group_label,sort_order,cabin_snapshot_json,media_snapshot_json,created_at)
        values
          (?,?,1,?,'group-1','Группа 1',0,?::jsonb,'[]'::jsonb,clock_timestamp()),
          (?,?,1,?,'group-1','Группа 1',1,?::jsonb,'[]'::jsonb,clock_timestamp())
        """,
        UUID.randomUUID(),
        presentationId,
        UUID.randomUUID(),
        oldPanelSnapshot,
        UUID.randomUUID(),
        presentationId,
        UUID.randomUUID(),
        technicalOnlySnapshot);

    Flyway latest = configuration(MIGRATIONS).target("21").load();
    assertThat(latest.migrate().migrationsExecuted).isOne();
    latest.validate();

    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from client_presentation_item
                where presentation_id=?
                  and (
                    (cabin_snapshot_json->'passport')::text ilike '%legacy%'
                    or (cabin_snapshot_json->'passport')::text ilike
                      '%old-panel-rental-items-v1%'
                    or (cabin_snapshot_json->'passport')::text ilike
                      '%locationNodeId%'
                    or (cabin_snapshot_json->'passport')::text ilike '%hasPhotos%'
                    or (cabin_snapshot_json->'passport')::text ilike '%photoCount%'
                    or (cabin_snapshot_json->'passport')::text ilike '%mainPhotoUrl%'
                    or (cabin_snapshot_json->'passport')::text ilike
                      '%previewPhotoUrls%')
                """,
                Integer.class,
                presentationId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from client_presentation_item
                where presentation_id=?
                  and cabin_snapshot_json->'passport'->>'price'='42000'
                  and cabin_snapshot_json->'passport'->>'shipmentDate'='2026-07-20'
                  and cabin_snapshot_json->'passport'->>'tenant'='ООО Полезные данные'
                  and cabin_snapshot_json->'other'->>'source'='presentation-domain-source'
                """,
                Integer.class,
                presentationId))
        .isEqualTo(2);
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void v22AcknowledgesHistoricalBookingsButLeavesNewConfirmationsPendingForManager() {
    Flyway beforeV22 = configuration(MIGRATIONS).target("21").load();
    assertThat(beforeV22.migrate().migrationsExecuted).isEqualTo(21);

    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    UUID selectedCabinId = UUID.randomUUID();
    UUID historicalBookingId = UUID.randomUUID();
    OffsetDateTime historicalCompletedAt =
        OffsetDateTime.parse("2026-07-27T10:15:00Z");
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','ООО Исторический клиент','ооо исторический клиент',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        clientId,
        subjectId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,
          manager_role,warehouse_id,state,created_at,updated_at)
        values (?,0,?,?,?,'Менеджер','RENTAL_MANAGER',?,'ACTIVE',
          clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        UUID.randomUUID(),
        clientId,
        subjectId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into client_presentation(
          id,version,inquiry_id,revision,warehouse_id,state,expires_at,view_until,
          last_publish_idempotency_key,last_publish_request_sha256,created_at,updated_at)
        values (?,0,?,1,?,'ACTIVE',
          clock_timestamp() + interval '1 hour',
          clock_timestamp() + interval '2 hours',
          ?,?,clock_timestamp(),clock_timestamp())
        """,
        presentationId,
        inquiryId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
    jdbc.update(
        """
        insert into presentation_booking(
          id,version,presentation_id,presentation_revision,idempotency_key,order_id,
          selected_item_ids_json,state,attempt_count,created_at,updated_at,completed_at)
        values (?,3,?,1,?,?,?::jsonb,'COMPLETED',0,?,?,?)
        """,
        historicalBookingId,
        presentationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "[\"" + selectedCabinId + "\"]",
        historicalCompletedAt.minusMinutes(2),
        historicalCompletedAt,
        historicalCompletedAt);

    Flyway latest = configuration(MIGRATIONS).target("22").load();
    assertThat(latest.migrate().migrationsExecuted).isOne();
    latest.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select manager_action, manager_action_idempotency_key,
                       manager_acted_at=completed_at as acted_at_matches_completion
                from presentation_booking
                where id=?
                """,
                historicalBookingId))
        .containsEntry("manager_action", "KEEP_DRAFT")
        .containsEntry("manager_action_idempotency_key", historicalBookingId)
        .containsEntry("acted_at_matches_completion", true);

    UUID newBookingId = UUID.randomUUID();
    jdbc.update("update client_presentation set revision=2 where id=?", presentationId);
    jdbc.update(
        """
        insert into presentation_booking(
          id,version,presentation_id,presentation_revision,idempotency_key,order_id,
          selected_item_ids_json,state,attempt_count,created_at,updated_at,completed_at)
        values (?,0,?,2,?,?,?::jsonb,'COMPLETED',0,
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        newBookingId,
        presentationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "[\"" + selectedCabinId + "\"]");
    assertThat(
            jdbc.queryForObject(
                """
                select manager_action is null
                  and manager_action_idempotency_key is null
                  and manager_acted_at is null
                from presentation_booking
                where id=?
                """,
                Boolean.class,
                newBookingId))
        .isTrue();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update presentation_booking set manager_action='KEEP_DRAFT' where id=?",
                    newBookingId))
        .hasMessageContaining("ck_presentation_booking_manager_action_fields");
    assertJpaValidationStarts();
  }

  @Test
  void v23RemovesBusinessCodeColumnsAndPreservesUuidRowsAndNames() {
    Flyway beforeV23 = configuration(MIGRATIONS).target("22").load();
    assertThat(beforeV23.migrate().migrationsExecuted).isEqualTo(22);

    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID requirementId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','ООО Клиент','ооо клиент',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        clientId,
        subjectId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-900001','DRAFT',?,?,'Менеджер',?,'Менеджер',
          'RENTAL_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        subjectId,
        subjectId,
        warehouseId,
        UUID.randomUUID(),
        "b".repeat(64));
    jdbc.update(
        """
        insert into rental_order_equipment_requirement(
          id,version,order_id,rental_item_id,equipment_id,equipment_code,equipment_name,
          quantity,created_at,updated_at)
        values (?,0,?,?,?,'TABLE','Стол',2,clock_timestamp(),clock_timestamp())
        """,
        requirementId,
        orderId,
        rentalItemId,
        equipmentId);
    jdbc.update(
        """
        insert into equipment_movement_task(
          id,version,warehouse_id,external_task_id,deadline_at,state,
          created_by_subject_id,idempotency_key,request_sha256,created_at,updated_at)
        values (?,0,?,?,clock_timestamp() + interval '1 day','RESERVING',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        taskId,
        warehouseId,
        UUID.randomUUID(),
        subjectId,
        UUID.randomUUID(),
        "c".repeat(64));
    jdbc.update(
        """
        insert into equipment_movement_task_line(
          id,version,task_id,line_number,equipment_id,source_warehouse_id,
          source_location_kind,expected_source_balance_version,target_warehouse_id,
          target_rental_item_id,target_location_kind,quantity,equipment_code,equipment_name,
          state,created_at,updated_at)
        values (?,0,?,1,?,?,'STOCK',0,?,?,'CABIN_NON_RENTED',2,'TABLE','Стол',
          'PENDING_RESERVATION',clock_timestamp(),clock_timestamp())
        """,
        lineId,
        taskId,
        equipmentId,
        warehouseId,
        warehouseId,
        rentalItemId);

    Flyway v23 = configuration(MIGRATIONS).target("23").load();
    assertThat(v23.migrate().migrationsExecuted).isOne();
    v23.validate();

    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and column_name='equipment_code'
                  and table_name in (
                    'rental_order_equipment_requirement',
                    'equipment_movement_task_line')
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select equipment_name from rental_order_equipment_requirement where id=?",
                String.class,
                requirementId))
        .isEqualTo("Стол");
    assertThat(
            jdbc.queryForObject(
                "select equipment_name from equipment_movement_task_line where id=?",
                String.class,
                lineId))
        .isEqualTo("Стол");
    assertJpaValidationStarts();
  }

  @Test
  void v25NormalizesLegacyMovementDurationsBeforeMakingThemRequired() {
    Flyway beforeV25 = configuration(MIGRATIONS).target("24").load();
    assertThat(beforeV25.migrate().migrationsExecuted).isEqualTo(24);
    UUID taskId = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_movement_task(
          id,version,warehouse_id,external_task_id,planned_duration_minutes,deadline_at,state,
          created_by_subject_id,idempotency_key,request_sha256,created_at,updated_at)
        values (?,0,?,?,null,clock_timestamp() + interval '1 day','RESERVING',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        taskId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));

    Flyway upgraded = configuration(MIGRATIONS).target("25").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select planned_duration_minutes from equipment_movement_task where id=?",
                Integer.class,
                taskId))
        .isEqualTo(60);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update equipment_movement_task set planned_duration_minutes=null where id=?",
                    taskId))
        .hasMessageContaining("not-null");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update equipment_movement_task set planned_duration_minutes=0 where id=?",
                    taskId))
        .hasMessageContaining("ck_equipment_movement_task_duration");
    assertJpaValidationStarts();
  }

  @Test
  void v26AddsNullableRepairContinuationTruthWithoutRewritingHistoricalLines() {
    Flyway beforeV26 = configuration(MIGRATIONS).target("25").load();
    assertThat(beforeV26.migrate().migrationsExecuted).isEqualTo(25);
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,requested_by_subject_id,correlation_id,
          created_at,updated_at)
        values (?,0,'RETURN','DRAFT',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_document_line(
          id,version,document_id,line_number,asset_id,asset_version,state,created_at,updated_at)
        values (?,0,?,1,?,0,'PENDING',clock_timestamp(),clock_timestamp())
        """,
        lineId,
        documentId,
        UUID.randomUUID());

    Flyway upgraded = configuration(MIGRATIONS).target("26").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(
            jdbc.queryForMap(
                """
                select transfer_asset_status,
                       active_repair_id,
                       active_repair_version,
                       repair_continuation_priority,
                       movement_to_shipment
                  from logistics_document_line
                 where id=?
                """,
                lineId))
        .containsOnlyKeys(
            "transfer_asset_status",
            "active_repair_id",
            "active_repair_version",
            "repair_continuation_priority",
            "movement_to_shipment")
        .allSatisfy((ignored, value) -> assertThat(value).isNull());

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update logistics_document_line
                       set transfer_asset_status='REPAIR',
                           maintenance_prepared_at=clock_timestamp()
                     where id=?
                    """,
                    lineId))
        .hasMessageContaining("ck_logistics_document_line_repair_truth");
    assertJpaValidationStarts();
  }

  @Test
  void v1DatabaseUpgradesInPlaceToLatestPreservesRowsAndPassesJpaValidation(
      @TempDir Path directory) throws IOException {
    copyMigration(directory, "V1__logistics_schema.sql");
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();

    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID guardId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    UUID mediaReferenceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,requested_by_subject_id,correlation_id,
          created_at,updated_at)
        values (?,4,'RETURN','DRAFT',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_document_line(
          id,version,document_id,line_number,asset_id,asset_version,state,created_at,updated_at)
        values (?,3,?,1,?,7,'PENDING',clock_timestamp(),clock_timestamp())
        """,
        lineId,
        documentId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_guard(
          id,document_id,line_id,asset_id,guard_state,lease_id,fence_token,
          observed_asset_version,acquired_at,created_at,updated_at)
        select ?,?,?,asset_id,'ACTIVE',?,11,7,clock_timestamp(),clock_timestamp(),clock_timestamp()
          from logistics_document_line where id=?
        """,
        guardId,
        documentId,
        lineId,
        UUID.randomUUID(),
        lineId);
    jdbc.update(
        """
        insert into logistics_external_attempt(
          id,document_id,line_id,operation_id,target_service,operation_type,request_sha256,
          result,retry_count,next_attempt_at,correlation_id,created_at)
        values (?,?,?,?,'WAREHOUSE','RETURN_WAREHOUSE_IDENTITY',?,'PENDING',0,
          clock_timestamp(),?,clock_timestamp())
        """,
        attemptId,
        documentId,
        lineId,
        UUID.randomUUID(),
        "a".repeat(64),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_media_reference(
          id,document_id,line_id,media_id,purpose,readiness,created_at)
        values (?,?,?,?,'RETURN_INSPECTION','PENDING',clock_timestamp())
        """,
        mediaReferenceId,
        documentId,
        lineId,
        UUID.randomUUID());

    for (String migration :
        List.of(
            "V2__return_registration_attempts.sql",
            "V3__return_completion_workflow.sql",
            "V4__shipment_workflow.sql",
            "V5__transfer_workflow.sql",
            "V6__inbound_reconciliation.sql",
            "V7__mutable_projection_versions.sql",
            "V8__orders_module.sql",
            "V9__equipment_movement_tasks.sql")) {
      copyMigration(directory, migration);
    }
    Flyway latest = flyway(location);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(8);
    latest.validate();
    assertThat(latest.migrate().migrationsExecuted).isZero();

    assertThat(
            jdbc.queryForObject(
                "select version from logistics_document where id=?", Long.class, documentId))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select version from logistics_document_line where id=?", Long.class, lineId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select row_version from logistics_guard where id=?", Long.class, guardId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select row_version from logistics_external_attempt where id=?",
                Long.class,
                attemptId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select row_version from logistics_media_reference where id=?",
                Long.class,
                mediaReferenceId))
        .isZero();
    assertJpaValidationStarts();
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

  private Path copyMigration(Path directory, String resource) throws IOException {
    Path destination = directory.resolve(resource);
    try (var source = requireResource("db/migration/" + resource).openStream()) {
      Files.copy(source, destination);
    }
    return destination;
  }

  private void assertJpaValidationStarts() {
    try (var context =
        new SpringApplicationBuilder(LogisticsServiceApplication.class)
            .profiles("test")
            .web(WebApplicationType.SERVLET)
            .properties(
                "server.port=0",
                "spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "spring.datasource.username=" + POSTGRES.getUsername(),
                "spring.datasource.password=" + POSTGRES.getPassword(),
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "LOGISTICS_DB_URL=" + POSTGRES.getJdbcUrl(),
                "LOGISTICS_DB_USERNAME=" + POSTGRES.getUsername(),
                "LOGISTICS_DB_PASSWORD=" + POSTGRES.getPassword(),
                "AUTH_ISSUER=http://issuer.invalid",
                "AUTH_AUDIENCE=rwms-services",
                "PANEL_ORIGIN=http://localhost",
                "spring.flyway.enabled=true",
                "spring.jpa.hibernate.ddl-auto=validate",
                "rwms.platform.kafka.enabled=false",
                "rwms.logistics.dependencies.enabled=false",
                "rwms.cors.allowed-origins=http://localhost",
                "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://issuer.invalid",
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri=http://127.0.0.1:65535/jwks")
            .run("--server.port=0")) {
      assertThat(context.getBean(EntityManagerFactory.class).isOpen()).isTrue();
    }
  }
}
