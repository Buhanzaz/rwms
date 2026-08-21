package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import jakarta.persistence.EntityManagerFactory;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
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
            "driver_logistics_task",
            "driver_logistics_task_member",
            "equipment_movement_task",
            "equipment_movement_task_line",
            "domain_event",
            "event_stream_head",
            "flyway_schema_history",
            "inbox_message",
            "inventory_asset_outcome_watermark",
            "inventory_outcome_receipt",
            "inventory_outcome_receipt_asset",
            "inventory_outcome_task_action",
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
            "logistics_warehouse_admission_intent",
            "logistics_warehouse_readiness_fence",
            "order_client",
            "order_client_additional_contact",
            "outbox_event",
            "projection_checkpoint",
            "presentation_booking",
            "rental_inquiry",
            "rental_inquiry_outbox",
            "rental_inquiry_search_attempt",
            "rental_inquiry_selection_receipt",
            "rental_order",
            "rental_order_additional_contact",
            "rental_order_audit_event",
            "rental_order_command_receipt",
            "rental_order_desired_delivery_window",
            "rental_order_unit_term",
            "rental_settings",
            "shipment_task_settings",
            "sanitized_dead_letter",
            "warehouse_operation_mark_outbox",
            "warehouse_operation_mark_recovery_audit",
            "version_gap_quarantine")
        .doesNotContain("warehouse", "rental_item", "inventory_session", "reservation");
    assertThat(toRegclass("databasechangelog")).isNull();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public' and table_name='logistics_document'
                  and column_name in (
                    'inventory_source_id','inventory_source_finding_id',
                    'inventory_source_disposition_kind','inventory_source_completed_at',
                    'inventory_source_final_plan_version','inventory_source_final_plan_sha256')
                """,
                Integer.class))
        .isEqualTo(6);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public' and table_name='logistics_document_line'
                  and column_name in (
                    'inventory_source_id','inventory_source_finding_id',
                    'inventory_source_disposition_kind','inventory_shipment_furniture')
                """,
                Integer.class))
        .isEqualTo(4);
    assertThat(toRegclass("uk_logistics_document_inventory_source")).isNotNull();
    assertThat(
            constraintDefinition(
                "inventory_outcome_receipt_asset",
                "ck_inventory_outcome_receipt_asset_disposition"))
        .contains("WRITE_OFF", "WRITE_OFF_PENDING", "SHIPMENT", "RENTED");
    assertThat(
            constraintDefinition(
                "inventory_outcome_receipt_asset",
                "ck_inventory_outcome_receipt_asset_document"))
        .contains("WRITE_OFF", "created_document_id IS NULL", "created_line_id IS NULL");
    assertJpaValidationStarts();
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

    assertThat(configuration(MIGRATIONS).target("17").load().migrate().migrationsExecuted)
        .isPositive();
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

    assertThat(configuration(MIGRATIONS).target("20").load().migrate().migrationsExecuted).isOne();

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
  void v28AndV29ReplaceWholeOrderShipmentIndexesWithPerCabinTermsAndReturnLinkage() {
    Flyway beforeV28 = configuration(MIGRATIONS).target("27").load();
    assertThat(beforeV28.migrate().migrationsExecuted).isEqualTo(27);
    assertThat(toRegclass("uk_logistics_document_shipment_order")).isNotNull();
    assertThat(toRegclass("uk_logistics_document_return_order")).isNotNull();

    assertThat(configuration(MIGRATIONS).target("28").load().migrate().migrationsExecuted).isOne();

    assertThat(tableNames()).contains("rental_order_unit_term");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='logistics_document'
                  and column_name='rental_shipment_id'
                """,
                Long.class))
        .isOne();
    assertThat(toRegclass("uk_logistics_document_shipment_order")).isNull();
    assertThat(toRegclass("uk_logistics_document_return_order")).isNull();
    assertThat(toRegclass("uk_logistics_document_return_shipment")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from pg_constraint
                where conname in (
                  'uk_rental_order_unit_term',
                  'fk_rental_order_unit_term_order',
                  'fk_rental_order_unit_term_rental_shipment',
                  'ck_rental_order_unit_term_dates'
                )
                """,
                Long.class))
        .isEqualTo(4);

    assertThat(configuration(MIGRATIONS).target("29").load().migrate().migrationsExecuted).isOne();
    assertThat(toRegclass("uk_logistics_document_return_shipment")).isNull();
    assertThat(toRegclass("idx_logistics_document_return_shipment")).isNotNull();
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
    String oldPanelSnapshot =
        """
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
                Integer.class, presentationId))
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
    OffsetDateTime historicalCompletedAt = OffsetDateTime.parse("2026-07-27T10:15:00Z");
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
  void v27AddsDriverTaskIntentStateWithoutFabricatingTaskBoardOrRepairPlaceFacts() {
    Flyway beforeV27 = configuration(MIGRATIONS).target("26").load();
    assertThat(beforeV27.migrate().migrationsExecuted).isEqualTo(26);

    Flyway upgraded = configuration(MIGRATIONS).target("27").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    UUID id = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,repair_id,source_type,source_id,task_kind,
          planning_mode,scheduled_date,priority,unit_number,driver_queue_definition_id,
          external_task_id,state,cover_applied,repair_place_effect_applied,
          created_by_subject_id,idempotency_key,request_sha256,retry_count,
          created_at,updated_at)
        values (?,0,?,?,?,'REPAIR',?,'DELIVER_TO_REPAIR','AUTO',current_date,3,
          'БЫТ-001',?,?,'REGISTERING',false,false,?,?,?,0,
          clock_timestamp(),clock_timestamp())
        """,
        id,
        warehouseId,
        UUID.randomUUID(),
        repairId,
        repairId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));

    assertThat(
            jdbc.queryForMap(
                """
                select task_board_task_id,task_board_entry_id,
                       repair_place_allocation_id,completion_evidence_id
                  from driver_logistics_task
                 where id=?
                """,
                id))
        .allSatisfy((ignored, value) -> assertThat(value).isNull());

    assertThatThrownBy(
            () -> jdbc.update("update driver_logistics_task set priority=0 where id=?", id))
        .hasMessageContaining("ck_driver_logistics_task_priority");
    assertJpaValidationStarts();
  }

  @Test
  void v30TurnsCurrentDriverSingletonIntoQueueAndAddsDurableManualHold() {
    Flyway beforeV30 = configuration(MIGRATIONS).target("29").load();
    assertThat(beforeV30.migrate().migrationsExecuted).isEqualTo(29);

    UUID warehouseId = UUID.randomUUID();
    insertCurrentDriverTask(UUID.randomUUID(), warehouseId, UUID.randomUUID());

    Flyway upgraded = configuration(MIGRATIONS).target("30").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    UUID secondId = UUID.randomUUID();
    insertCurrentDriverTask(secondId, warehouseId, UUID.randomUUID());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from driver_logistics_task where warehouse_id=? and"
                    + " state='CURRENT'",
                Long.class,
                warehouseId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select manual_promotion_hold from driver_logistics_task where id=?",
                Boolean.class,
                secondId))
        .isFalse();
    assertJpaValidationStarts();
  }

  @Test
  void v31ReopensOnlyLegacyTransientDriverTasksAtTheirPersistedWorkflowPhase() {
    Flyway beforeV31 = configuration(MIGRATIONS).target("30").load();
    assertThat(beforeV31.migrate().migrationsExecuted).isEqualTo(30);

    UUID warehouseId = UUID.randomUUID();
    UUID registeringId = UUID.randomUUID();
    UUID finalizingByDoneAtId = UUID.randomUUID();
    UUID finalizingByEntryStatusId = UUID.randomUUID();
    UUID scheduledId = UUID.randomUUID();
    UUID configurationId = UUID.randomUUID();
    UUID rejectedId = UUID.randomUUID();
    OffsetDateTime historicalNextAttempt = OffsetDateTime.parse("2026-07-01T12:00:00Z");

    insertReconciliationDriverTask(
        registeringId, warehouseId, null, null, null, 5, null, "DEPENDENCY_TRANSIENT");
    insertReconciliationDriverTask(
        finalizingByDoneAtId,
        warehouseId,
        UUID.randomUUID(),
        "WAITING",
        OffsetDateTime.parse("2026-07-01T11:00:00Z"),
        5,
        null,
        "DEPENDENCY_TRANSIENT");
    insertReconciliationDriverTask(
        finalizingByEntryStatusId,
        warehouseId,
        UUID.randomUUID(),
        "DONE",
        null,
        5,
        null,
        "DEPENDENCY_TRANSIENT");
    insertReconciliationDriverTask(
        scheduledId,
        warehouseId,
        UUID.randomUUID(),
        "WAITING",
        null,
        5,
        null,
        "DEPENDENCY_TRANSIENT");
    insertReconciliationDriverTask(
        configurationId,
        warehouseId,
        UUID.randomUUID(),
        "WAITING",
        null,
        17,
        historicalNextAttempt,
        "DEPENDENCY_CONFIGURATION");
    insertReconciliationDriverTask(
        rejectedId,
        warehouseId,
        null,
        null,
        null,
        19,
        historicalNextAttempt,
        "DEPENDENCY_PERMANENT_REJECTION");

    Flyway upgraded = configuration(MIGRATIONS).target("31").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();

    assertRecoveredDriverTask(registeringId, "REGISTERING");
    assertRecoveredDriverTask(finalizingByDoneAtId, "FINALIZING");
    assertRecoveredDriverTask(finalizingByEntryStatusId, "FINALIZING");
    assertRecoveredDriverTask(scheduledId, "SCHEDULED");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from driver_logistics_task
                where id=?
                  and state='RECONCILIATION_REQUIRED'
                  and failure_code='DEPENDENCY_CONFIGURATION'
                  and retry_count=17
                  and next_attempt_at=?
                """,
                Long.class,
                configurationId,
                historicalNextAttempt))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from driver_logistics_task
                where id=?
                  and state='RECONCILIATION_REQUIRED'
                  and failure_code='DEPENDENCY_PERMANENT_REJECTION'
                  and retry_count=19
                  and next_attempt_at=?
                """,
                Long.class,
                rejectedId,
                historicalNextAttempt))
        .isOne();
    assertJpaValidationStarts();
  }

  @Test
  void v32ReplacesLegacyManualBooleanWithTimedHoldAndAddsRollingQueueFacts() {
    Flyway beforeV32 = configuration(MIGRATIONS).target("31").load();
    assertThat(beforeV32.migrate().migrationsExecuted).isEqualTo(31);

    UUID warehouseId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    OffsetDateTime updatedAt = OffsetDateTime.parse("2026-08-01T10:15:00Z");
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,repair_id,source_type,source_id,task_kind,
          planning_mode,scheduled_date,priority,unit_number,driver_queue_definition_id,
          external_task_id,state,cover_applied,repair_place_effect_applied,manual_promotion_hold,
          created_by_subject_id,idempotency_key,request_sha256,retry_count,created_at,updated_at)
        values (?,0,?,?,?,'REPAIR',?,'DELIVER_TO_REPAIR','FIXED_DATE',date '2026-08-04',3,
          'БЫТ-032',?,?,'SCHEDULED',false,false,true,?,?,?,0,?,?)
        """,
        taskId,
        warehouseId,
        UUID.randomUUID(),
        repairId,
        repairId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        updatedAt,
        updatedAt);

    Flyway upgraded = configuration(MIGRATIONS).target("32").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();

    Map<String, Object> migrated =
        jdbc.queryForMap(
            """
            select manual_promotion_hold_until,fixed_date_lower_bound,movement_comment
            from driver_logistics_task where id=?
            """,
            taskId);
    assertThat(((java.sql.Timestamp) migrated.get("manual_promotion_hold_until")).toInstant())
        .isEqualTo(updatedAt.plusMinutes(5).toInstant());
    assertThat(migrated)
        .containsEntry("fixed_date_lower_bound", java.sql.Date.valueOf("2026-08-04"))
        .containsEntry("movement_comment", null);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='driver_logistics_task'
                  and column_name='manual_promotion_hold'
                """,
                Integer.class))
        .isZero();

    jdbc.update(
        """
        update driver_logistics_task
           set source_type='MANUAL', task_kind='GENERAL_MOVEMENT', repair_id=null,
               movement_comment='Переместить к воротам'
         where id=?
        """,
        taskId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update driver_logistics_task set movement_comment=null where id=?", taskId))
        .hasMessageContaining("ck_driver_logistics_task_manual_comment");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update driver_logistics_task set fixed_date_lower_bound=null where id=?",
                    taskId))
        .hasMessageContaining("ck_driver_logistics_task_fixed_lower_bound");
    assertJpaValidationStarts();
  }

  @Test
  void v33KeepsCancelledCapitalMovementHistoryAndAllowsOneNewActiveMovement() {
    Flyway beforeV33 = configuration(MIGRATIONS).target("32").load();
    assertThat(beforeV33.migrate().migrationsExecuted).isEqualTo(32);

    UUID warehouseId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID queueDefinitionId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.parse("2026-08-03T08:00:00Z");
    insertDriverMovement(
        UUID.randomUUID(), warehouseId, cabinId, repairId, queueDefinitionId, "CANCELLED", now);

    Flyway upgraded = configuration(MIGRATIONS).target("33").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    insertDriverMovement(
        UUID.randomUUID(),
        warehouseId,
        cabinId,
        repairId,
        queueDefinitionId,
        "SCHEDULED",
        now.plusMinutes(1));
    insertDriverMovement(
        UUID.randomUUID(),
        warehouseId,
        cabinId,
        repairId,
        queueDefinitionId,
        "CANCELLED",
        now.plusMinutes(2));

    assertThatThrownBy(
            () ->
                insertDriverMovement(
                    UUID.randomUUID(),
                    warehouseId,
                    cabinId,
                    repairId,
                    queueDefinitionId,
                    "CURRENT",
                    now.plusMinutes(3)))
        .hasMessageContaining("uk_driver_logistics_task_active_source_kind");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from driver_logistics_task where source_id=?",
                Long.class,
                repairId))
        .isEqualTo(3);
    assertJpaValidationStarts();
  }

  private void insertDriverMovement(
      UUID id,
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      UUID queueDefinitionId,
      String state,
      OffsetDateTime timestamp) {
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,repair_id,source_type,source_id,task_kind,
          planning_mode,scheduled_date,fixed_date_lower_bound,priority,movement_comment,
          unit_number,driver_queue_definition_id,external_task_id,state,cover_applied,
          repair_place_effect_applied,created_by_subject_id,idempotency_key,request_sha256,
          retry_count,created_at,updated_at)
        values (?,0,?,?,null,'CAPITAL_REPAIR',?,'CAPITAL_TO_PRODUCTION','AUTO',
          date '2026-08-03',null,2,null,'БЫТ-КАП',?,?,?,false,true,?,?,?,0,?,?)
        """,
        id,
        warehouseId,
        cabinId,
        repairId,
        queueDefinitionId,
        UUID.randomUUID(),
        state,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "c".repeat(64),
        timestamp,
        timestamp);
  }

  private void assertRecoveredDriverTask(UUID id, String expectedState) {
    Map<String, Object> row =
        jdbc.queryForMap(
            """
            select state,retry_count,failure_code,next_attempt_at
            from driver_logistics_task
            where id=?
            """,
            id);
    assertThat(row.get("state")).isEqualTo(expectedState);
    assertThat(row.get("retry_count")).isEqualTo(0);
    assertThat(row.get("failure_code")).isNull();
    assertThat(row.get("next_attempt_at")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select next_attempt_at <= clock_timestamp() from driver_logistics_task where id=?",
                Boolean.class,
                id))
        .isTrue();
  }

  private void insertReconciliationDriverTask(
      UUID id,
      UUID warehouseId,
      UUID taskBoardTaskId,
      String taskBoardEntryStatus,
      OffsetDateTime taskBoardDoneAt,
      int retryCount,
      OffsetDateTime nextAttemptAt,
      String failureCode) {
    UUID repairId = UUID.randomUUID();
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,repair_id,source_type,source_id,task_kind,
          planning_mode,scheduled_date,priority,unit_number,driver_queue_definition_id,
          external_task_id,task_board_task_id,task_board_task_version,task_board_entry_id,
          task_board_entry_status,task_board_done_at,state,cover_applied,repair_place_effect_applied,
          created_by_subject_id,idempotency_key,request_sha256,retry_count,next_attempt_at,failure_code,
          created_at,updated_at)
        values (?,0,?,?,?,'REPAIR',?,'DELIVER_TO_REPAIR','AUTO',current_date,3,
          ?,?,?,?,?,?,?,?, 'RECONCILIATION_REQUIRED',false,false,?,?,?,?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        id,
        warehouseId,
        UUID.randomUUID(),
        repairId,
        repairId,
        "БЫТ-" + id.toString().substring(0, 4),
        UUID.randomUUID(),
        UUID.randomUUID(),
        taskBoardTaskId,
        taskBoardTaskId == null ? null : 0L,
        taskBoardTaskId == null ? null : UUID.randomUUID(),
        taskBoardEntryStatus,
        taskBoardDoneAt,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        retryCount,
        nextAttemptAt,
        failureCode);
  }

  private void insertCurrentDriverTask(UUID id, UUID warehouseId, UUID repairId) {
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,repair_id,source_type,source_id,task_kind,
          planning_mode,scheduled_date,priority,unit_number,driver_queue_definition_id,
          external_task_id,task_board_task_id,task_board_task_version,task_board_entry_id,
          task_board_entry_status,state,cover_applied,repair_place_effect_applied,
          created_by_subject_id,idempotency_key,request_sha256,retry_count,
          created_at,updated_at)
        values (?,0,?,?,?,'REPAIR',?,'DELIVER_TO_REPAIR','AUTO',current_date,3,
          ?,?,?,?,0,?,'WAITING','CURRENT',false,false,?,?,?,0,
          clock_timestamp(),clock_timestamp())
        """,
        id,
        warehouseId,
        UUID.randomUUID(),
        repairId,
        repairId,
        "БЫТ-" + id.toString().substring(0, 4),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
  }

  @Test
  void v1DatabaseUpgradesInPlaceToLatestPreservesRowsAndPassesJpaValidation(@TempDir Path directory)
      throws IOException {
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
  void v34AddsIndependentManualBookingHoldDurationAndValidatesJpa() {
    Flyway beforeV34 = configuration(MIGRATIONS).target("33").load();
    assertThat(beforeV34.migrate().migrationsExecuted).isEqualTo(33);

    assertThat(configuration(MIGRATIONS).target("34").load().migrate().migrationsExecuted).isOne();

    assertThat(
            jdbc.queryForObject(
                "select manual_booking_hold_minutes from rental_settings", Integer.class))
        .isEqualTo(60);
    assertThatThrownBy(
            () -> jdbc.update("update rental_settings set manual_booking_hold_minutes=4"))
        .hasMessageContaining("ck_rental_settings_manual_booking_hold_minutes");
    assertJpaValidationStarts();
  }

  @Test
  void v35ConvertsShipmentMovementHistoryAndRejectsTheRemovedKind() {
    Flyway beforeV35 = configuration(MIGRATIONS).target("34").load();
    assertThat(beforeV35.migrate().migrationsExecuted).isEqualTo(34);
    UUID taskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,repair_id,source_type,source_id,task_kind,
          planning_mode,scheduled_date,priority,unit_number,driver_queue_definition_id,
          external_task_id,state,created_by_subject_id,idempotency_key,request_sha256,
          retry_count,created_at,updated_at)
        values (?,0,?,?,?,'REPAIR_PLACE',?,'MOVE_TO_SHIPMENT','AUTO',current_date,2,
          'БТ-35',?,?,'SCHEDULED',?,?,?,0,clock_timestamp(),clock_timestamp())
        """,
        taskId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        repairId,
        repairId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));

    assertThat(configuration(MIGRATIONS).target("35").load().migrate().migrationsExecuted).isOne();
    assertThat(
            jdbc.queryForObject(
                "select task_kind from driver_logistics_task where id=?", String.class, taskId))
        .isEqualTo("REMOVE_FROM_REPAIR");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update driver_logistics_task set task_kind='MOVE_TO_SHIPMENT' where id=?",
                    taskId))
        .hasMessageContaining("ck_driver_logistics_task_kind");
    assertJpaValidationStarts();
  }

  @Test
  void v38AdmitsStartReturnEstimatesWithoutInvalidatingHistoricalEstimateReceipts() {
    Flyway beforeV38 = configuration(MIGRATIONS).target("37").load();
    assertThat(beforeV38.migrate().migrationsExecuted).isEqualTo(37);

    assertThat(logisticsConstraintDefinition("ck_logistics_idempotency_operation"))
        .contains("REQUEST_RETURN_ESTIMATE")
        .doesNotContain("START_RETURN_ESTIMATES");

    assertThat(configuration(MIGRATIONS).target("38").load().migrate().migrationsExecuted).isOne();

    assertThat(logisticsConstraintDefinition("ck_logistics_idempotency_operation"))
        .contains("REQUEST_RETURN_ESTIMATE", "START_RETURN_ESTIMATES");
    assertJpaValidationStarts();
  }

  @Test
  void v39KeepsLegacyMarksUnprovenAndConstrainsExactAdmissionEvidence() {
    Flyway beforeV39 = configuration(MIGRATIONS).target("38").load();
    assertThat(beforeV39.migrate().migrationsExecuted).isEqualTo(38);
    UUID legacyOperationId = UUID.randomUUID();
    UUID legacyWarehouseId = UUID.randomUUID();
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,state,attempt_count,next_attempt_at,
          created_at,updated_at)
        values (?, ?, clock_timestamp(),'PENDING',0,clock_timestamp(),
          clock_timestamp(),clock_timestamp())
        """,
        legacyOperationId,
        legacyWarehouseId);

    Flyway upgraded = configuration(MIGRATIONS).target("39").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where warehouse_id=? and operation_id=?
                """,
                legacyWarehouseId,
                legacyOperationId))
        .containsEntry("admission_direction", null)
        .containsEntry("admission_warehouse_version", null);
    assertThat(
            warehouseOperationMarkConstraintDefinition(
                "ck_warehouse_operation_mark_admission_evidence"))
        .contains("INCOMING", "OUTGOING", "admission_warehouse_version >= 0")
        .contains("admission_direction IS NULL", "admission_warehouse_version IS NULL");
    assertThat(toRegclass("idx_warehouse_operation_mark_operation")).isNotNull();

    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,admission_direction,
          admission_warehouse_version,state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?, ?, clock_timestamp(),'INCOMING',7,'PENDING',0,clock_timestamp(),
          clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse_operation_mark_outbox(
                      operation_id,warehouse_id,occurred_at,admission_direction,
                      admission_warehouse_version,state,attempt_count,next_attempt_at,
                      created_at,updated_at)
                    values (?, ?, clock_timestamp(),'OUTGOING',null,'PENDING',0,
                      clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_warehouse_operation_mark_admission_evidence");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse_operation_mark_outbox(
                      operation_id,warehouse_id,occurred_at,admission_direction,
                      admission_warehouse_version,state,attempt_count,next_attempt_at,
                      created_at,updated_at)
                    values (?, ?, clock_timestamp(),null,5,'PENDING',0,
                      clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_warehouse_operation_mark_admission_evidence");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse_operation_mark_outbox(
                      operation_id,warehouse_id,occurred_at,admission_direction,
                      admission_warehouse_version,state,attempt_count,next_attempt_at,
                      created_at,updated_at)
                    values (?, ?, clock_timestamp(),'SIDEWAYS',5,'PENDING',0,
                      clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_warehouse_operation_mark_admission_evidence");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into warehouse_operation_mark_outbox(
                      operation_id,warehouse_id,occurred_at,admission_direction,
                      admission_warehouse_version,state,attempt_count,next_attempt_at,
                      created_at,updated_at)
                    values (?, ?, clock_timestamp(),'INCOMING',-1,'PENDING',0,
                      clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_warehouse_operation_mark_admission_evidence");
    assertJpaValidationStarts();
  }

  @Test
  void v40UpgradesHistoricalAttemptsToUnleasedFencedClaimsAndValidatesJpa() {
    Flyway beforeV40 = configuration(MIGRATIONS).target("39").load();
    assertThat(beforeV40.migrate().migrationsExecuted).isEqualTo(39);
    UUID documentId = UUID.randomUUID();
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,requested_by_subject_id,correlation_id,
          created_at,updated_at)
        values (?,0,'RETURN','REGISTERING',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_external_attempt(
          id,document_id,operation_id,target_service,operation_type,request_sha256,result,
          retry_count,next_attempt_at,correlation_id,created_at)
        values (?,? ,?,'ASSET','RETURN_WAREHOUSE_IDENTITY',?,'PENDING',0,clock_timestamp(),
          ?,clock_timestamp())
        """,
        attemptId,
        documentId,
        UUID.randomUUID(),
        "a".repeat(64),
        UUID.randomUUID());

    Flyway upgraded = configuration(MIGRATIONS).target("40").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select lease_token,lease_fence,lease_expires_at
                from logistics_external_attempt where id=?
                """,
                attemptId))
        .containsEntry("lease_token", null)
        .containsEntry("lease_fence", 0L)
        .containsEntry("lease_expires_at", null);
    assertThat(toRegclass("idx_logistics_external_attempt_due_claim")).isNotNull();
    assertThat(toRegclass("idx_logistics_external_attempt_expired_lease_claim")).isNotNull();
    assertThat(externalAttemptConstraintDefinition("ck_logistics_external_attempt_lease_fence"))
        .contains("lease_fence >= 0");
    assertThat(externalAttemptConstraintDefinition("ck_logistics_external_attempt_lease_pair"))
        .contains("lease_token IS NULL", "lease_expires_at IS NULL");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update logistics_external_attempt set lease_fence=-1 where id=?", attemptId))
        .hasMessageContaining("ck_logistics_external_attempt_lease_fence");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update logistics_external_attempt
                    set lease_token=?, lease_expires_at=null where id=?
                    """,
                    UUID.randomUUID(),
                    attemptId))
        .hasMessageContaining("ck_logistics_external_attempt_lease_pair");
    assertJpaValidationStarts();
  }

  @Test
  void v41AddsSearchReceiptsAndCanonicalizesPendingAndPublishedBookingFacts() {
    Flyway beforeV41 = configuration(MIGRATIONS).target("40").load();
    assertThat(beforeV41.migrate().migrationsExecuted).isEqualTo(40);
    UUID[] pending = insertLegacyBookingOutbox("PENDING");
    UUID[] published = insertLegacyBookingOutbox("PUBLISHED");
    String pendingPayloadBefore =
        jdbc.queryForObject(
            "select payload::text from rental_inquiry_outbox where event_id=?",
            String.class,
            pending[0]);

    Flyway upgraded = configuration(MIGRATIONS).target("41").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(toRegclass("rental_inquiry_search_attempt")).isNotNull();
    assertThat(toRegclass("uk_rental_inquiry_search_attempt_one_prepared")).isNotNull();
    Map<String, Object> pendingEnvelope = bookedEnvelope(pending[0]);
    assertThat(pendingEnvelope)
        .containsEntry("status", "PENDING")
        .containsEntry("event_id", pending[0].toString())
        .containsEntry("envelope_version", "2")
        .containsEntry("event_type", "logistics.rental-inquiry.booked.v1")
        .containsEntry("event_version", "1")
        .containsEntry("producer", "logistics-service")
        .containsEntry("aggregate_type", "RENTAL_INQUIRY")
        .containsEntry("aggregate_id", pending[1].toString())
        .containsEntry("aggregate_version", "8")
        .containsEntry("correlation_id", pending[2].toString())
        .containsEntry("causation_id", pending[3].toString())
        .containsEntry("actor_subject_id", pending[5].toString())
        .containsEntry("principal_type", "USER")
        .containsEntry("profile_revision_type", "null")
        .containsEntry("payload_conversation_id", pending[2].toString())
        .containsEntry("payload_order_id", pending[4].toString())
        .containsEntry("root_field_count", 13L)
        .containsEntry("payload_field_count", 2L);
    assertThat(
            jdbc.queryForMap(
                "select status,published_at from rental_inquiry_outbox where event_id=?",
                published[0]))
        .containsEntry("status", "PUBLISHED")
        .doesNotContainEntry("published_at", null);
    assertThat(bookedEnvelope(published[0]))
        .containsEntry("event_id", published[0].toString())
        .containsEntry("correlation_id", published[2].toString())
        .containsEntry("causation_id", published[3].toString())
        .containsEntry("payload_order_id", published[4].toString());
    assertThat(
            jdbc.queryForObject(
                "select payload::text from rental_inquiry_outbox where event_id=?",
                String.class,
                pending[0]))
        .isNotEqualTo(pendingPayloadBefore);

    String pendingPayloadAfter =
        jdbc.queryForObject(
            "select payload::text from rental_inquiry_outbox where event_id=?",
            String.class,
            pending[0]);
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
    assertThat(
            jdbc.queryForObject(
                "select payload::text from rental_inquiry_outbox where event_id=?",
                String.class,
                pending[0]))
        .isEqualTo(pendingPayloadAfter);

    UUID firstAttempt = UUID.randomUUID();
    insertPreparedSearchAttempt(firstAttempt, pending[1], UUID.randomUUID());
    assertThatThrownBy(
            () -> insertPreparedSearchAttempt(UUID.randomUUID(), pending[1], UUID.randomUUID()))
        .hasMessageContaining("uk_rental_inquiry_search_attempt_one_prepared");
    jdbc.update(
        """
        update rental_inquiry_search_attempt
        set state='COMPLETED',response_body='{}',terminal_at=clock_timestamp(),
            updated_at=clock_timestamp()
        where id=?
        """,
        firstAttempt);
    insertPreparedSearchAttempt(UUID.randomUUID(), pending[1], UUID.randomUUID());
    assertJpaValidationStarts();
  }

  @Test
  void v42BackfillsTruthfulClientManagerAndAddsDeliveryFactsWithoutRewritingLegacyRows() {
    Flyway beforeV42 = configuration(MIGRATIONS).target("41").load();
    assertThat(beforeV42.migrate().migrationsExecuted).isEqualTo(41);
    UUID creatorId = UUID.randomUUID();
    UUID legacyClientId = UUID.randomUUID();
    UUID legacyOrderId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','ООО История','ооо история',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        legacyClientId,
        creatorId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-420001','DRAFT',?,?,'Исторический менеджер',
          ?,'Исторический менеджер','RENTAL_MANAGER',?,?,clock_timestamp(),clock_timestamp())
        """,
        legacyOrderId,
        legacyClientId,
        creatorId,
        creatorId,
        UUID.randomUUID(),
        "b".repeat(64));

    Flyway upgraded = configuration(MIGRATIONS).target("42").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select responsible_manager_id,responsible_manager_display_name,
                       contact_person,comment,source
                from order_client where id=?
                """,
                legacyClientId))
        .containsEntry("responsible_manager_id", creatorId)
        .containsEntry("responsible_manager_display_name", null)
        .containsEntry("contact_person", null)
        .containsEntry("comment", null)
        .containsEntry("source", null);
    assertThat(
            jdbc.queryForMap(
                """
                select delivery_address,latitude,longitude,contact_phone,comment
                from rental_order where id=?
                """,
                legacyOrderId))
        .containsEntry("delivery_address", null)
        .containsEntry("latitude", null)
        .containsEntry("longitude", null)
        .containsEntry("contact_phone", null)
        .containsEntry("comment", null);
    assertThat(toRegclass("rental_order_acceptable_delivery_date")).isNotNull();
    assertThat(toRegclass("rental_inquiry_selection_receipt")).isNotNull();
    assertThat(toRegclass("uq_rental_inquiry_selection_receipt_prepared")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                """
                select is_nullable
                from information_schema.columns
                where table_schema='public'
                  and table_name='rental_inquiry_selection_receipt'
                  and column_name='command_expires_at'
                """,
                String.class))
        .isEqualTo("NO");

    UUID proprietorId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,'SOLE_PROPRIETOR','ИП Новый','ип новый','+79990000001','+79990000001',
          'Иван Новый',?,'Менеджер',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        proprietorId,
        creatorId,
        creatorId,
        UUID.randomUUID(),
        "c".repeat(64));
    assertThat(
            jdbc.queryForObject(
                "select client_type from order_client where id=?", String.class, proprietorId))
        .isEqualTo("SOLE_PROPRIETOR");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into order_client(
                      id,version,client_type,display_name,normalized_name,phone,normalized_phone,
                      responsible_manager_id,created_by_subject_id,creation_idempotency_key,
                      creation_request_sha256,created_at,updated_at)
                    values (?,0,'LEGAL_ENTITY','ООО Без контакта','ооо без контакта',
                      '+79990000002','+79990000002',?,?,?, ?,clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    creatorId,
                    creatorId,
                    UUID.randomUUID(),
                    "d".repeat(64)))
        .hasMessageContaining("ck_order_client_contact_person");

    LocalDate deliveryDate = LocalDate.of(2026, 8, 10);
    jdbc.update(
        """
        insert into rental_order_acceptable_delivery_date(order_id,position,delivery_date)
        values (?,0,?)
        """,
        legacyOrderId,
        deliveryDate);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into rental_order_acceptable_delivery_date(order_id,position,delivery_date)
                    values (?,1,?)
                    """,
                    legacyOrderId,
                    deliveryDate))
        .hasMessageContaining("uq_rental_order_acceptable_delivery_date");
    assertJpaValidationStarts();
  }

  @Test
  void v43MapsHistoricalSoleProprietorsBeforeRestrictingTheClientTypeConstraint() {
    Flyway beforeV43 = configuration(MIGRATIONS).target("42").load();
    assertThat(beforeV43.migrate().migrationsExecuted).isEqualTo(42);

    UUID managerId = UUID.randomUUID();
    UUID proprietorId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,'SOLE_PROPRIETOR','ИП История','ип история','+79990000043','+79990000043',
          'Иван Исторический',?,'Исторический менеджер',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        proprietorId,
        managerId,
        managerId,
        UUID.randomUUID(),
        "a".repeat(64));

    Flyway upgraded = configuration(MIGRATIONS).target("43").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();

    assertThat(
            jdbc.queryForObject(
                "select client_type from order_client where id=?", String.class, proprietorId))
        .isEqualTo("LEGAL_ENTITY");
    assertThat(
            jdbc.queryForObject(
                "select contact_person from order_client where id=?", String.class, proprietorId))
        .isEqualTo("Иван Исторический");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into order_client(
                      id,version,client_type,display_name,normalized_name,phone,normalized_phone,
                      contact_person,responsible_manager_id,responsible_manager_display_name,
                      created_by_subject_id,creation_idempotency_key,creation_request_sha256,
                      created_at,updated_at)
                    values (?,0,'SOLE_PROPRIETOR','ИП После','ип после','+79990000044','+79990000044',
                      'Иван После',?,'Менеджер',?,?,?,clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    managerId,
                    managerId,
                    UUID.randomUUID(),
                    "b".repeat(64)))
        .hasMessageContaining("ck_order_client_type");
    assertJpaValidationStarts();
  }

  @Test
  void v43StopsBeforeAnAmbiguousSamePhoneReclassification() {
    Flyway beforeV43 = configuration(MIGRATIONS).target("42").load();
    assertThat(beforeV43.migrate().migrationsExecuted).isEqualTo(42);

    UUID managerId = UUID.randomUUID();
    String normalizedPhone = "+79990000045";
    insertV42Client("LEGAL_ENTITY", "ООО Дубликат", "ооо дубликат", normalizedPhone, managerId);
    insertV42Client("SOLE_PROPRIETOR", "ИП Дубликат", "ип дубликат", normalizedPhone, managerId);

    assertThatThrownBy(() -> configuration(MIGRATIONS).target("43").load().migrate())
        .hasMessageContaining("same normalized phone");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_client where client_type='SOLE_PROPRIETOR'",
                Long.class))
        .isOne();
  }

  @Test
  void v45RemovesTransferAndSharedDriverHintsAndEnforcesAudienceByTaskKind() {
    Flyway beforeV45 = configuration(MIGRATIONS).target("44").load();
    assertThat(beforeV45.migrate().migrationsExecuted).isEqualTo(44);
    UUID warehouseId = UUID.randomUUID();
    UUID destinationWarehouseId = UUID.randomUUID();
    UUID oldDriverId = UUID.randomUUID();
    UUID transferDocumentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,destination_warehouse_id,
          driver_snapshot,driver_worker_id,scheduled_date,requested_by_subject_id,
          correlation_id,created_at,updated_at)
        values (?,0,'TRANSFER','DRAFT',?,?,'Старый ответственный',?,date '2026-08-15',
          ?,?,clock_timestamp(),clock_timestamp())
        """,
        transferDocumentId,
        warehouseId,
        destinationWarehouseId,
        oldDriverId,
        UUID.randomUUID(),
        UUID.randomUUID());
    UUID transferTaskId = UUID.randomUUID();
    UUID shipmentTaskId = UUID.randomUUID();
    UUID returnTaskId = UUID.randomUUID();
    insertV44DocumentDriverTask(
        transferTaskId, warehouseId, "TRANSFER", oldDriverId, "Старый ответственный");
    insertV44DocumentDriverTask(
        shipmentTaskId, warehouseId, "SHIPMENT", oldDriverId, "Назначенный водитель");
    insertV44DocumentDriverTask(returnTaskId, warehouseId, "RETURN", null, null);

    Flyway upgraded = configuration(MIGRATIONS).target("45").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                "select driver_snapshot,driver_worker_id from logistics_document where id=?",
                transferDocumentId))
        .containsEntry("driver_snapshot", null)
        .containsEntry("driver_worker_id", null);
    assertThat(driverAudienceRow(transferTaskId))
        .containsEntry("driver_audience_mode", "WAREHOUSE_DRIVERS")
        .containsEntry("planned_driver_worker_id", null)
        .containsEntry("planned_driver_name_snapshot", null);
    assertThat(driverAudienceRow(shipmentTaskId))
        .containsEntry("driver_audience_mode", "ASSIGNED_DRIVER")
        .containsEntry("planned_driver_worker_id", oldDriverId)
        .containsEntry("planned_driver_name_snapshot", "Назначенный водитель");
    assertThat(driverAudienceRow(returnTaskId))
        .containsEntry("driver_audience_mode", "UNASSIGNED")
        .containsEntry("planned_driver_worker_id", null)
        .containsEntry("planned_driver_name_snapshot", null);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update logistics_document set driver_snapshot='Ошибка' where id=?",
                    transferDocumentId))
        .hasMessageContaining("ck_logistics_document_transfer_driver");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update driver_logistics_task
                       set driver_audience_mode='ASSIGNED_DRIVER',
                           planned_driver_worker_id=?,
                           planned_driver_name_snapshot='Ошибка'
                     where id=?
                    """,
                    UUID.randomUUID(),
                    transferTaskId))
        .hasMessageContaining("ck_driver_logistics_task_kind_audience");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update driver_logistics_task
                       set driver_audience_mode='WAREHOUSE_DRIVERS',
                           planned_driver_worker_id=null,
                           planned_driver_name_snapshot=null
                     where id=?
                    """,
                    shipmentTaskId))
        .hasMessageContaining("ck_driver_logistics_task_kind_audience");
    assertJpaValidationStarts();
  }

  @Test
  void v46AddsShipmentGroupingWithoutRegroupingLegacyDocumentLineTasks() {
    Flyway beforeV46 = configuration(MIGRATIONS).target("45").load();
    assertThat(beforeV46.migrate().migrationsExecuted).isEqualTo(45);
    UUID warehouseId = UUID.randomUUID();
    UUID legacyTaskId = UUID.randomUUID();
    insertV45LegacyShipmentLineTask(legacyTaskId, warehouseId);

    Flyway upgraded = configuration(MIGRATIONS).target("46").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(toRegclass("shipment_task_settings")).isNotNull();
    assertThat(toRegclass("driver_logistics_task_member")).isNotNull();
    assertThat(toRegclass("ix_driver_logistics_task_member_task_position")).isNotNull();
    assertThat(toRegclass("ix_driver_logistics_task_member_cabin")).isNotNull();
    assertThat(
            jdbc.queryForList(
                """
                select conname from pg_constraint
                 where conrelid='driver_logistics_task_member'::regclass
                """,
                String.class))
        .contains(
            "fk_driver_logistics_task_member_task",
            "fk_driver_logistics_task_member_document_line",
            "uk_driver_logistics_task_member_line",
            "uk_driver_logistics_task_member_cabin");
    assertThat(
            jdbc.queryForMap(
                "select source_type,client_snapshot from driver_logistics_task where id=?",
                legacyTaskId))
        .containsEntry("source_type", "LOGISTICS_DOCUMENT_LINE")
        .containsEntry("client_snapshot", null);

    UUID groupedTaskId = UUID.randomUUID();
    insertV46GroupedShipmentTask(groupedTaskId, warehouseId);
    assertThat(
            jdbc.queryForMap(
                "select source_type,task_kind,client_snapshot,unit_number from"
                    + " driver_logistics_task where id=?",
                groupedTaskId))
        .containsEntry("source_type", "LOGISTICS_DOCUMENT")
        .containsEntry("task_kind", "SHIPMENT")
        .containsEntry("client_snapshot", "ООО Группа")
        .containsEntry("unit_number", "2 бытовки");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update driver_logistics_task set client_snapshot=null where id=?",
                    groupedTaskId))
        .hasMessageContaining("ck_driver_logistics_task_document_group");
    jdbc.update(
        """
        insert into shipment_task_settings(
          warehouse_id,version,max_cabins_per_shipment_task,updated_by_subject_id,updated_at)
        values (?,0,3,?,clock_timestamp())
        """,
        warehouseId,
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update shipment_task_settings set max_cabins_per_shipment_task=0 where"
                        + " warehouse_id=?",
                    warehouseId))
        .hasMessageContaining("ck_shipment_task_settings_max_cabins");
    assertJpaValidationStarts();
  }

  @Test
  void v47MigratesDesiredWindowsAndBackfillsStableOrderTripNumbers() {
    Flyway beforeV47 = configuration(MIGRATIONS).target("46").load();
    assertThat(beforeV47.migrate().migrationsExecuted).isEqualTo(46);
    UUID warehouseId = UUID.randomUUID();
    UUID managerId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','ООО V47','ооо v47','+79990000047','+79990000047',
          'Контакт V47',?,'Менеджер V47',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        managerId,
        managerId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-470001','DRAFT',?,?,'Менеджер V47',?,'Менеджер V47',
          'RENTAL_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        managerId,
        managerId,
        warehouseId,
        UUID.randomUUID(),
        "b".repeat(64));
    LocalDate legacyDate = LocalDate.of(2026, 8, 11);
    jdbc.update(
        """
        insert into rental_order_acceptable_delivery_date(order_id,position,delivery_date)
        values (?,0,?)
        """,
        orderId,
        legacyDate);
    UUID firstDocumentId = UUID.randomUUID();
    UUID secondDocumentId = UUID.randomUUID();
    UUID standaloneDocumentId = UUID.randomUUID();
    insertV46OrderTripDocument(
        firstDocumentId, warehouseId, orderId, OffsetDateTime.parse("2026-08-10T10:00:00Z"));
    insertV46OrderTripDocument(
        secondDocumentId, warehouseId, orderId, OffsetDateTime.parse("2026-08-10T11:00:00Z"));
    insertV46OrderTripDocument(
        standaloneDocumentId, warehouseId, null, OffsetDateTime.parse("2026-08-10T12:00:00Z"));
    UUID firstTaskId = UUID.randomUUID();
    UUID secondTaskId = UUID.randomUUID();
    UUID standaloneTaskId = UUID.randomUUID();
    insertV46GroupedShipmentTask(firstTaskId, warehouseId, firstDocumentId);
    insertV46GroupedShipmentTask(secondTaskId, warehouseId, secondDocumentId);
    insertV46GroupedShipmentTask(standaloneTaskId, warehouseId, standaloneDocumentId);

    Flyway upgraded = configuration(MIGRATIONS).target("47").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(toRegclass("order_client_additional_contact")).isNotNull();
    assertThat(toRegclass("rental_order_additional_contact")).isNotNull();
    assertThat(toRegclass("rental_order_desired_delivery_window")).isNotNull();
    assertThat(toRegclass("rental_order_acceptable_delivery_date")).isNull();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='shipment_furniture_movement_task'
                  and column_name='replacement_source_reservation_id'
                """,
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select start_date from rental_order_desired_delivery_window
                where order_id=? and position=0
                """,
                LocalDate.class,
                orderId))
        .isEqualTo(legacyDate);
    assertThat(
            jdbc.queryForObject(
                """
                select end_date from rental_order_desired_delivery_window
                where order_id=? and position=0
                """,
                LocalDate.class,
                orderId))
        .isEqualTo(legacyDate);
    assertThat(
            jdbc.queryForMap(
                """
                select time_from,time_to from rental_order_desired_delivery_window
                where order_id=? and position=0
                """,
                orderId))
        .containsEntry("time_from", null)
        .containsEntry("time_to", null);
    assertThat(
            jdbc.queryForObject(
                "select trip_number from driver_logistics_task where id=?",
                Integer.class,
                firstTaskId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select trip_number from driver_logistics_task where id=?",
                Integer.class,
                secondTaskId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select trip_number from driver_logistics_task where id=?",
                Integer.class,
                standaloneTaskId))
        .isEqualTo(1);
    assertJpaValidationStarts();
  }

  @Test
  void v48AddsNullablePositiveClientSelectedRentalMonthsToExistingBookings() {
    Flyway beforeV48 = configuration(MIGRATIONS).target("47").load();
    assertThat(beforeV48.migrate().migrationsExecuted).isEqualTo(47);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='presentation_booking'
                  and column_name='rental_months'
                """,
                Integer.class))
        .isZero();

    Flyway upgraded = configuration(MIGRATIONS).target("48").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(
            jdbc.queryForMap(
                """
                select data_type,is_nullable
                from information_schema.columns
                where table_schema='public'
                  and table_name='presentation_booking'
                  and column_name='rental_months'
                """))
        .containsEntry("data_type", "bigint")
        .containsEntry("is_nullable", "YES");
    assertThat(
            jdbc.queryForObject(
                """
                select pg_get_constraintdef(c.oid)
                from pg_constraint c
                join pg_class relation on relation.oid=c.conrelid
                join pg_namespace namespace on namespace.oid=relation.relnamespace
                where namespace.nspname='public'
                  and relation.relname='presentation_booking'
                  and c.conname='ck_presentation_booking_rental_months'
                """,
                String.class))
        .contains("rental_months")
        .contains(">= 1");
    assertJpaValidationStarts();
  }

  @Test
  void v49AddsOnlyReplaySnapshotFieldsAndRetainsHistoricalDesiredTimeColumns() {
    Flyway beforeV49 = configuration(MIGRATIONS).target("48").load();
    assertThat(beforeV49.migrate().migrationsExecuted).isEqualTo(48);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='presentation_booking'
                  and column_name in ('delivery_address','latitude','longitude','additional_contacts_json')
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='rental_order_desired_delivery_window'
                  and column_name in ('time_from','time_to')
                """,
                Integer.class))
        .isEqualTo(2);

    Flyway upgraded = configuration(MIGRATIONS).target("49").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForList(
                """
                select column_name || ':' || data_type || ':' || is_nullable
                from information_schema.columns
                where table_schema='public'
                  and table_name='presentation_booking'
                  and column_name in ('delivery_address','latitude','longitude','additional_contacts_json')
                order by column_name
                """,
                String.class))
        .containsExactly(
            "additional_contacts_json:jsonb:YES",
            "delivery_address:character varying:YES",
            "latitude:numeric:YES",
            "longitude:numeric:YES");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='rental_order_desired_delivery_window'
                  and column_name in ('time_from','time_to')
                """,
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from pg_constraint c
                join pg_class relation on relation.oid=c.conrelid
                join pg_namespace namespace on namespace.oid=relation.relnamespace
                where namespace.nspname='public'
                  and relation.relname='presentation_booking'
                  and c.conname in (
                    'ck_presentation_booking_delivery_address',
                    'ck_presentation_booking_delivery_coordinates',
                    'ck_presentation_booking_additional_contacts_json')
                """,
                Integer.class))
        .isEqualTo(3);
    assertJpaValidationStarts();
  }

  @Test
  void v50AddsRecoverableInventorySupersessionWithoutRewritingHistoricalRows() {
    Flyway beforeV50 = configuration(MIGRATIONS).target("49").load();
    assertThat(beforeV50.migrate().migrationsExecuted).isEqualTo(49);
    UUID warehouseId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID termId = UUID.randomUUID();
    UUID taskId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,party_snapshot,driver_snapshot,
          requested_by_subject_id,correlation_id,created_at,updated_at)
        values (?,0,'SHIPMENT','DRAFT',?,'Клиент V50','Водитель V50',?,?,
          clock_timestamp(),clock_timestamp())
        """,
        documentId,
        warehouseId,
        subjectId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_document_line(
          id,version,document_id,line_number,asset_id,asset_version,state,created_at,updated_at)
        values (?,0,?,1,?,0,'PENDING',clock_timestamp(),clock_timestamp())
        """,
        lineId,
        documentId,
        assetId);
    jdbc.update(
        """
        insert into logistics_guard(
          id,document_id,line_id,asset_id,guard_state,created_at,updated_at)
        values (?,?,?,?, 'PENDING',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        documentId,
        lineId,
        assetId);
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,'LEGAL_ENTITY','Клиент V50','клиент v50','+79990000050','+79990000050',
          'Контакт V50',?,'Менеджер V50',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        subjectId,
        subjectId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-990050','DRAFT',?,?,'Менеджер V50',?,'Менеджер V50',
          'WAREHOUSE_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
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
        insert into rental_order_unit_term(
          id,version,order_id,rental_item_id,rental_months,created_at,updated_at)
        values (?,0,?,?,1,clock_timestamp(),clock_timestamp())
        """,
        termId,
        orderId,
        assetId);
    insertV45LegacyShipmentLineTask(taskId, warehouseId);

    Flyway upgraded = configuration(MIGRATIONS).target("50").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select inventory_superseded_by from logistics_document where id=?",
                UUID.class,
                documentId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select inventory_superseded_by from logistics_document_line where id=?",
                UUID.class,
                lineId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select inventory_superseded_by from rental_order where id=?", UUID.class, orderId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select inventory_superseded_by from rental_order_unit_term where id=?",
                UUID.class,
                termId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select inventory_cancelled_by from driver_logistics_task where id=?",
                UUID.class,
                taskId))
        .isNull();
    assertThat(tableNames())
        .contains(
            "inventory_outcome_receipt",
            "inventory_outcome_receipt_asset",
            "inventory_asset_outcome_watermark",
            "inventory_outcome_task_action");
    assertThat(jdbc.queryForObject("select count(*) from logistics_document", Long.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from logistics_document_line", Long.class))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from logistics_guard", Long.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from rental_order", Long.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from rental_order_unit_term", Long.class))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from driver_logistics_task", Long.class))
        .isOne();
    assertJpaValidationStarts();
  }

  @Test
  void v52BackfillsOnlyReturnsWithPhysicalInspectionArrivalEvidence() {
    Flyway beforeV52 = configuration(MIGRATIONS).target("51").load();
    assertThat(beforeV52.migrate().migrationsExecuted).isEqualTo(51);
    UUID warehouseId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID arrivedDocumentId = UUID.randomUUID();
    UUID eventlessDocumentId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    OffsetDateTime arrivedAt = OffsetDateTime.parse("2026-08-01T09:30:00Z");
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,driver_snapshot,scheduled_date,
          requested_by_subject_id,correlation_id,created_at,updated_at)
        values (?,1,'RETURN','INSPECTION_REQUIRED',?,'Водитель V52',date '2026-08-01',?,?,
          clock_timestamp(),clock_timestamp()),
          (?,0,'RETURN','DRAFT',?,'Водитель V52',date '2026-08-02',?,?,
          clock_timestamp(),clock_timestamp())
        """,
        arrivedDocumentId,
        warehouseId,
        subjectId,
        UUID.randomUUID(),
        eventlessDocumentId,
        warehouseId,
        subjectId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into logistics_document_line(
          id,version,document_id,line_number,asset_id,asset_version,state,created_at,updated_at)
        values (?,0,?,1,?,0,'ARRIVED',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        arrivedDocumentId,
        assetId);
    UUID eventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('RETURN',?,1,?,?)
        """,
        arrivedDocumentId.toString(),
        eventId,
        arrivedAt);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'RETURN',?,1,'logistics.return.inspection-required.v1',1,?,?,?,
          '{}'::jsonb,encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),false)
        """,
        eventId,
        arrivedDocumentId.toString(),
        arrivedAt,
        arrivedAt,
        UUID.randomUUID());

    Flyway upgraded = configuration(MIGRATIONS).target("52").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select return_arrived_at from logistics_document where id=?",
                OffsetDateTime.class,
                arrivedDocumentId))
        .isEqualTo(arrivedAt);
    assertThat(
            jdbc.queryForObject(
                "select return_arrived_at from logistics_document where id=?",
                OffsetDateTime.class,
                eventlessDocumentId))
        .isNull();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update logistics_document
                    set document_type='SHIPMENT', party_snapshot='Клиент',
                        driver_snapshot='Водитель'
                    where id=?
                    """,
                    arrivedDocumentId))
        .hasMessageContaining("ck_logistics_document_return_arrival");
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
        "select table_name from information_schema.tables where table_schema='public' order by"
            + " table_name",
        String.class);
  }

  private String toRegclass(String table) {
    return jdbc.queryForObject("select to_regclass(?)", String.class, "public." + table);
  }

  private String constraintDefinition(String table, String constraint) {
    return jdbc.queryForObject(
        "select pg_get_constraintdef(oid) from pg_constraint where conrelid=to_regclass(?) and conname=?",
        String.class,
        "public." + table,
        constraint);
  }

  private String logisticsConstraintDefinition(String constraintName) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(c.oid)
        from pg_constraint c
        join pg_class relation on relation.oid=c.conrelid
        join pg_namespace namespace on namespace.oid=relation.relnamespace
        where namespace.nspname='public' and relation.relname='logistics_idempotency_record'
          and c.conname=?
        """,
        String.class,
        constraintName);
  }

  private String warehouseOperationMarkConstraintDefinition(String constraintName) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(c.oid)
        from pg_constraint c
        join pg_class relation on relation.oid=c.conrelid
        join pg_namespace namespace on namespace.oid=relation.relnamespace
        where namespace.nspname='public' and relation.relname='warehouse_operation_mark_outbox'
          and c.conname=?
        """,
        String.class,
        constraintName);
  }

  private String externalAttemptConstraintDefinition(String constraintName) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(c.oid)
        from pg_constraint c
        join pg_class relation on relation.oid=c.conrelid
        join pg_namespace namespace on namespace.oid=relation.relnamespace
        where namespace.nspname='public' and relation.relname='logistics_external_attempt'
          and c.conname=?
        """,
        String.class,
        constraintName);
  }

  private UUID[] insertLegacyBookingOutbox(String status) {
    UUID eventId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID managerSubjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Migration client',?,?,?, ?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "migration-" + clientId,
        managerSubjectId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,booked_order_id,created_at,updated_at,booked_at)
        values (?,8,?,?,?,'Migration manager','RENTAL_MANAGER',?,'BOOKED',?,
          clock_timestamp() - interval '1 hour',clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        conversationId,
        clientId,
        managerSubjectId,
        UUID.randomUUID(),
        orderId);
    jdbc.update(
        """
        insert into client_presentation(
          id,version,inquiry_id,revision,warehouse_id,state,expires_at,view_until,
          booked_order_id,last_publish_idempotency_key,last_publish_request_sha256,
          created_at,updated_at,booked_at)
        select ?,3,?,1,warehouse_id,'BOOKED',clock_timestamp() + interval '1 hour',
          clock_timestamp() + interval '2 hours',?, ?,?,clock_timestamp(),clock_timestamp(),
          clock_timestamp()
        from rental_inquiry where id=?
        """,
        presentationId,
        inquiryId,
        orderId,
        UUID.randomUUID(),
        "b".repeat(64),
        inquiryId);
    jdbc.update(
        """
        insert into presentation_booking(
          id,version,presentation_id,presentation_revision,idempotency_key,order_id,
          selected_item_ids_json,state,attempt_count,created_at,updated_at,completed_at)
        values (?,5,?,1,?,?,'[\"00000000-0000-0000-0000-000000000001\"]'::jsonb,
          'COMPLETED',1,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        bookingId,
        presentationId,
        UUID.randomUUID(),
        orderId);
    jdbc.update(
        """
        insert into rental_inquiry_outbox(
          event_id,event_type,inquiry_id,conversation_id,order_id,payload,status,
          attempt_count,next_attempt_at,created_at,published_at)
        values (?,'logistics.rental-inquiry.booked.v1',?,?,?,?::jsonb,?,0,
          clock_timestamp(),clock_timestamp(),
          case when ?='PUBLISHED' then clock_timestamp() else null end)
        """,
        eventId,
        inquiryId,
        conversationId,
        orderId,
        "{\"eventId\":\""
            + eventId
            + "\",\"eventType\":\"logistics.rental-inquiry.booked.v1\","
            + "\"occurredAt\":\"2026-08-09T09:00:00Z\",\"rentalInquiryId\":\""
            + inquiryId
            + "\",\"conversationId\":\""
            + conversationId
            + "\",\"orderId\":\""
            + orderId
            + "\"}",
        status,
        status);
    return new UUID[] {eventId, inquiryId, conversationId, bookingId, orderId, managerSubjectId};
  }

  private Map<String, Object> bookedEnvelope(UUID eventId) {
    return jdbc.queryForMap(
        """
        select status,
          payload->>'eventId' as event_id,
          payload->>'envelopeVersion' as envelope_version,
          payload->>'eventType' as event_type,
          payload->>'eventVersion' as event_version,
          payload->>'producer' as producer,
          payload->>'aggregateType' as aggregate_type,
          payload->>'aggregateId' as aggregate_id,
          payload->>'aggregateVersion' as aggregate_version,
          payload#>>'{correlation,correlationId}' as correlation_id,
          payload#>>'{correlation,causationId}' as causation_id,
          payload#>>'{actorRef,subjectId}' as actor_subject_id,
          payload#>>'{actorRef,principalType}' as principal_type,
          jsonb_typeof(payload#>'{actorRef,profileRevision}') as profile_revision_type,
          payload#>>'{payload,conversationId}' as payload_conversation_id,
          payload#>>'{payload,orderId}' as payload_order_id,
          (select count(*) from jsonb_object_keys(payload)) as root_field_count,
          (select count(*) from jsonb_object_keys(payload->'payload')) as payload_field_count
        from rental_inquiry_outbox where event_id=?
        """,
        eventId);
  }

  private void insertPreparedSearchAttempt(UUID id, UUID inquiryId, UUID subjectId) {
    UUID publicKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into rental_inquiry_search_attempt(
          id,version,inquiry_id,subject_id,operation_name,public_idempotency_key,
          request_sha256,warehouse_id,downstream_idempotency_key,downstream_request_body,
          downstream_request_sha256,actor_role,hold_expires_at,state,created_at,updated_at)
        values (?,0,?,?,'RENTAL_INQUIRY_CABIN_SEARCH',?,?,?,?,'{}',?,
          'RENTAL_MANAGER',clock_timestamp() + interval '10 minutes','PREPARED',
          clock_timestamp(),clock_timestamp())
        """,
        id,
        inquiryId,
        subjectId,
        publicKey,
        "c".repeat(64),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "d".repeat(64));
  }

  private void insertV42Client(
      String clientType,
      String displayName,
      String normalizedName,
      String normalizedPhone,
      UUID managerId) {
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,?,?,?,?,?,'Контакт',?,'Менеджер',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        clientType,
        displayName,
        normalizedName,
        normalizedPhone,
        normalizedPhone,
        managerId,
        managerId,
        UUID.randomUUID(),
        "a".repeat(64));
  }

  /** Inserts one V44-era document driver task, including the now-obsolete shared-driver hint. */
  private void insertV44DocumentDriverTask(
      UUID taskId,
      UUID warehouseId,
      String taskKind,
      UUID plannedDriverWorkerId,
      String plannedDriverName) {
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,source_type,source_id,task_kind,planning_mode,
          scheduled_date,fixed_date_lower_bound,priority,unit_number,driver_queue_definition_id,
          driver_audience_mode,planned_driver_worker_id,planned_driver_name_snapshot,
          external_task_id,state,cover_applied,repair_place_effect_applied,created_by_subject_id,
          idempotency_key,request_sha256,retry_count,next_attempt_at,created_at,updated_at)
        values (?,0,?,?,'LOGISTICS_DOCUMENT_LINE',?,?,'FIXED_DATE',date '2026-08-15',
          date '2026-08-15',3,'БТ-V45',?,'WAREHOUSE_DRIVERS',?,?,?,'REGISTERING',
          false,true,?,?,?,0,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        taskId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        taskKind,
        UUID.randomUUID(),
        plannedDriverWorkerId,
        plannedDriverName,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "e".repeat(64));
  }

  /** Inserts a V45 line-owned shipment task to prove V46 does not mutate historical topology. */
  private void insertV45LegacyShipmentLineTask(UUID taskId, UUID warehouseId) {
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,source_type,source_id,task_kind,planning_mode,
          scheduled_date,fixed_date_lower_bound,priority,movement_comment,unit_number,
          driver_queue_definition_id,driver_audience_mode,planned_driver_worker_id,
          planned_driver_name_snapshot,external_task_id,state,cover_applied,
          repair_place_effect_applied,created_by_subject_id,idempotency_key,request_sha256,
          retry_count,next_attempt_at,created_at,updated_at)
        values (?,0,?,?,'LOGISTICS_DOCUMENT_LINE',?,'SHIPMENT','FIXED_DATE',current_date,
          current_date,3,'Отгрузка бытовки','БТ-V45',?,'ASSIGNED_DRIVER',?,'Водитель V45',?,
          'REGISTERING',false,true,?,?,?,0,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        taskId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "f".repeat(64));
  }

  /** Inserts a V46 document-owned group to prove the expanded source constraint is usable. */
  private void insertV46GroupedShipmentTask(UUID taskId, UUID warehouseId) {
    insertV46GroupedShipmentTask(taskId, warehouseId, UUID.randomUUID());
  }

  private void insertV46GroupedShipmentTask(UUID taskId, UUID warehouseId, UUID documentId) {
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,source_type,source_id,task_kind,planning_mode,
          scheduled_date,fixed_date_lower_bound,priority,movement_comment,client_snapshot,
          unit_number,driver_queue_definition_id,driver_audience_mode,planned_driver_worker_id,
          planned_driver_name_snapshot,external_task_id,state,cover_applied,
          repair_place_effect_applied,created_by_subject_id,idempotency_key,request_sha256,
          retry_count,next_attempt_at,created_at,updated_at)
        values (?,0,?,?,'LOGISTICS_DOCUMENT',?,'SHIPMENT','FIXED_DATE',current_date,
          current_date,3,'Клиент: ООО Группа. Бытовки: БТ-461, БТ-462','ООО Группа','2 бытовки',?,
          'ASSIGNED_DRIVER',?,'Водитель V46',?,'REGISTERING',false,true,?,?,?,0,
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        taskId,
        warehouseId,
        UUID.randomUUID(),
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));
  }

  private void insertV46OrderTripDocument(
      UUID documentId, UUID warehouseId, UUID rentalOrderId, OffsetDateTime createdAt) {
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,party_snapshot,driver_snapshot,
          driver_worker_id,scheduled_date,requested_by_subject_id,correlation_id,
          rental_order_id,created_at,updated_at)
        values (?,0,'SHIPMENT','DRAFT',?,'ООО V47','Водитель V47',?,date '2026-08-11',
          ?,?,?,?,?)
        """,
        documentId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        rentalOrderId,
        createdAt,
        createdAt);
  }

  private Map<String, Object> driverAudienceRow(UUID taskId) {
    return jdbc.queryForMap(
        """
        select driver_audience_mode,planned_driver_worker_id,planned_driver_name_snapshot
          from driver_logistics_task where id=?
        """,
        taskId);
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null)
      throw new IllegalStateException("Missing logistics migration resource: " + path);
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
