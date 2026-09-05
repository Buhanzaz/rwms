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
  void orderQuotedPriceUpgradePreservesHistoricalTermsAndValidatesJpa() {
    configuration(MIGRATIONS).target("102").load().migrate();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID termId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          responsible_manager_id,created_by_subject_id,creation_idempotency_key,
          creation_request_sha256,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Quoted price client',?,'+79990000103','+79990000103',
          ?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "price-client-" + clientId,
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
        values (?,7,'ORD-990103','DRAFT',?,?,'Manager',?,'Manager',
          'RENTAL_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        subjectId,
        subjectId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
    jdbc.update(
        """
        insert into rental_order_unit_term(
          id,version,order_id,rental_item_id,rental_months,created_at,updated_at)
        values (?,2,?,?,3,clock_timestamp(),clock_timestamp())
        """,
        termId,
        orderId,
        UUID.randomUUID());
    var before = jdbc.queryForMap("select * from rental_order_unit_term where id=?", termId);
    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    var after = jdbc.queryForMap("select * from rental_order_unit_term where id=?", termId);
    assertThat(after)
        .containsEntry("pricing_version", null)
        .containsEntry("monthly_price_rubles", null);
    after.remove("pricing_version");
    after.remove("monthly_price_rubles");
    assertThat(after).isEqualTo(before);
    assertThatThrownBy(
            () -> jdbc.update("update rental_order_unit_term set monthly_price_rubles=0"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order_unit_term set pricing_version=0,monthly_price_rubles=-1"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order_unit_term set pricing_version=-1,monthly_price_rubles=0"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "update rental_order_unit_term set pricing_version=0,monthly_price_rubles=?",
        Long.MAX_VALUE);
    assertThat(
            jdbc.queryForObject(
                "select monthly_price_rubles from rental_order_unit_term", Long.class))
        .isEqualTo(Long.MAX_VALUE);
    assertJpaValidationStarts();
  }

  @Test
  void paymentExpiryUpgradePreservesOpenCommandsAndRequiresReadBeforeReleaseEvidence() {
    configuration(MIGRATIONS).target("100").load().migrate();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          responsible_manager_id,created_by_subject_id,creation_idempotency_key,
          creation_request_sha256,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Expiry client',?,'+79990000101','+79990000101',
          ?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "expiry-client-" + clientId,
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
        values (?,7,'ORD-990101','DRAFT',?,?,'Manager',?,'Manager',
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
        insert into rental_order_mutation_command(
          id,order_id,operation,state,step,expected_order_version,
          actor_subject_id,actor_role,idempotency_key,request_sha256,warehouse_id,
          release_units_idempotency_key,release_equipment_idempotency_key,
          equipment_release_required,intent_json,next_attempt_at,created_at,updated_at)
        values (?,?,'CANCEL_ORDER','PENDING','RELEASE_UNITS',7,?,'RENTAL_MANAGER',
          ?,?,?,?, ?,true,'{}',clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        commandId,
        orderId,
        subjectId,
        UUID.randomUUID(),
        "c".repeat(64),
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID());
    Map<String, Object> previous =
        jdbc.queryForMap("select * from rental_order_mutation_command where id=?", commandId);
    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    assertThat(
            jdbc.queryForMap("select * from rental_order_mutation_command where id=?", commandId))
        .isEqualTo(previous);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order_mutation_command set intent_json=null where id=?",
                    commandId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order_mutation_command set step='READ_UNITS',intent_json=null"
                        + " where id=?",
                    commandId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "update rental_order_mutation_command set operation='EXPIRE_UNPAID_ORDER',"
            + " actor_role='LOGISTICS_SERVICE',actor_subject_id=?,step='READ_UNITS',intent_json=null"
            + " where id=?",
        dev.buhanzaz.rwms.logistics.order.domain.recovery.RentalOrderMutationCommand
            .AUTOMATIC_RELEASE_ACTOR_ID,
        commandId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order_mutation_command set released_units_receipt_json='{}'"
                        + " where id=?",
                    commandId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order_mutation_command set step='RELEASE_UNITS' where id=?",
                    commandId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "update rental_order_mutation_command set step='RELEASE_UNITS',intent_json='{}' where id=?",
        commandId);
    assertJpaValidationStarts();
  }

  @Test
  void paymentReservationUpgradePreservesHistoricalOrdersAndRejectsFalsePaymentEvidence() {
    configuration(MIGRATIONS).target("99").load().migrate();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          responsible_manager_id,created_by_subject_id,creation_idempotency_key,
          creation_request_sha256,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Payment client',?,'+79990000100','+79990000100',
          ?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "payment-client-" + clientId,
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
        values (?,7,'ORD-990100','SAVED',?,?,'Manager',?,'Manager',
          'RENTAL_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        subjectId,
        subjectId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
    Map<String, Object> previous =
        jdbc.queryForMap("select * from rental_order where id=?", orderId);

    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    assertThat(jdbc.queryForMap("select * from rental_order where id=?", orderId))
        .containsAllEntriesOf(previous)
        .containsEntry("payment_state", null)
        .containsEntry("payment_started_at", null)
        .containsEntry("payment_expires_at", null)
        .containsEntry("payment_resolved_at", null)
        .containsEntry("payment_source", null)
        .containsEntry("payment_confirmed_by_subject_id", null);
    assertThat(toRegclass("idx_rental_order_pending_payment")).isNotNull();
    assertThatThrownBy(
            () ->
                jdbc.update("update rental_order set payment_state='PENDING' where id=?", orderId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order set payment_state='PENDING', payment_started_at=current_timestamp,"
                        + " payment_expires_at=current_timestamp+interval '6 minutes' where id=?",
                    orderId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "update rental_order set payment_state='PENDING', payment_started_at=current_timestamp,"
            + " payment_expires_at=current_timestamp+interval '5 minutes' where id=?",
        orderId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order set payment_state='CONFIRMED', payment_resolved_at=payment_started_at,"
                        + " payment_confirmed_by_subject_id=? where id=?",
                    subjectId,
                    orderId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order set payment_state='CONFIRMED', payment_resolved_at=payment_expires_at,"
                        + " payment_source='CUSTOMER_TEST', payment_confirmed_by_subject_id=? where id=?",
                    subjectId,
                    orderId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_order set payment_state='EXPIRED', payment_resolved_at=payment_expires_at"
                        + " where id=?",
                    orderId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    jdbc.update(
        "update rental_order set payment_state='CONFIRMED', payment_resolved_at=payment_started_at,"
            + " payment_source='MANAGER_CONFIRMATION', payment_confirmed_by_subject_id=? where id=?",
        subjectId,
        orderId);
    assertJpaValidationStarts();
  }

  @Test
  void planningCommitmentAndRetentionFoundationMigratesOnACleanSchema() {
    Flyway flyway = flyway(MIGRATIONS);

    flyway.migrate();
    flyway.validate();

    assertThat(tableNames())
        .contains("logistics_retention_legal_hold", "logistics_archive_manifest");
    assertThat(
            jdbc.queryForList(
                """
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'driver_logistics_task'
                  and column_name like 'provisional_eta%'
                order by column_name
                """,
                String.class))
        .containsExactly(
            "provisional_eta",
            "provisional_eta_source_plan_id",
            "provisional_eta_source_plan_version");
    assertThat(
            jdbc.queryForList(
                """
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'customer_booking_mutation'
                  and column_name in ('decision_code', 'decision_actor_subject_id', 'decision_reason')
                order by column_name
                """,
                String.class))
        .containsExactly("decision_actor_subject_id", "decision_code", "decision_reason");
  }

  @Test
  void furniturePricingUpgradePreservesCabinTariffsAndValidatesJpa() {
    configuration(MIGRATIONS).target("101").load().migrate();
    UUID settingsId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    jdbc.update(
        "update rental_pricing_settings set version=7,updated_by_subject_id=?", UUID.randomUUID());
    jdbc.update(
        "insert into rental_pricing_rate values (?,?,?,8000)",
        settingsId,
        UUID.randomUUID(),
        UUID.randomUUID());
    var before = jdbc.queryForMap("select * from rental_pricing_settings");
    var ratesBefore = jdbc.queryForList("select * from rental_pricing_rate");
    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    assertThat(jdbc.queryForMap("select * from rental_pricing_settings")).isEqualTo(before);
    assertThat(jdbc.queryForList("select * from rental_pricing_rate")).isEqualTo(ratesBefore);
    assertThat(
            jdbc.queryForObject("select count(*) from rental_pricing_equipment_rate", Long.class))
        .isZero();
    UUID furniture = UUID.randomUUID();
    jdbc.update(
        "insert into rental_pricing_equipment_rate values (?,?,?)",
        settingsId,
        furniture,
        Long.MAX_VALUE);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into rental_pricing_equipment_rate values (?,?,1)",
                    settingsId,
                    furniture))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    for (long invalid : List.of(0L, -1L)) {
      assertThatThrownBy(
              () ->
                  jdbc.update(
                      "insert into rental_pricing_equipment_rate values (?,?,?)",
                      settingsId,
                      UUID.randomUUID(),
                      invalid))
          .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
    assertJpaValidationStarts();
  }

  @Test
  void globalRentalPricingUpgradeStartsAtZeroWithoutChangingExistingRentalSettings() {
    configuration(MIGRATIONS).target("97").load().migrate();
    jdbc.update(
        "update rental_settings set version=9, manual_booking_hold_minutes=75,"
            + " late_change_fee_mode='FIXED', late_change_fee_value=1234");
    Map<String, Object> previous = jdbc.queryForMap("select * from rental_settings");
    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    assertThat(jdbc.queryForMap("select * from rental_settings")).isEqualTo(previous);
    assertThat(jdbc.queryForMap("select * from rental_pricing_settings"))
        .containsEntry("id", UUID.fromString("00000000-0000-0000-0000-000000000001"))
        .containsEntry("version", 0L)
        .containsEntry("updated_by_subject_id", null);
    assertThat(jdbc.queryForObject("select count(*) from rental_pricing_rate", Long.class)).isZero();
    assertJpaValidationStarts();
  }

  @Test
  void rentalLateChangeSettingsUpgradePreservesHoldsAndValidatesJpa() {
    configuration(MIGRATIONS).target("93").load().migrate();
    jdbc.update(
        "update rental_settings set version=7, chat_selection_hold_minutes=12,"
            + " manual_booking_hold_minutes=75, presentation_hold_minutes=90,"
            + " draft_reservation_hold_minutes=2880");
    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    Map<String, Object> row = jdbc.queryForMap("select * from rental_settings");
    assertThat(row)
        .containsEntry("version", 7L)
        .containsEntry("chat_selection_hold_minutes", 12)
        .containsEntry("manual_booking_hold_minutes", 75)
        .containsEntry("presentation_hold_minutes", 90)
        .containsEntry("draft_reservation_hold_minutes", 2880)
        .containsEntry("late_change_notice_days", 2)
        .containsEntry("late_change_fee_mode", null)
        .containsEntry("late_change_fee_value", null)
        .containsEntry("rental_support_phone", null);
    jdbc.update(
        "update rental_settings set late_change_fee_mode='FIXED',"
            + " late_change_fee_value=9223372036854775807, rental_support_phone='+74951234567'");
    assertThat(
            jdbc.queryForObject(
                "select late_change_fee_value from rental_settings", java.math.BigDecimal.class))
        .isEqualByComparingTo("9223372036854775807");
    assertThatThrownBy(
            () ->
                jdbc.update("update rental_settings set late_change_fee_value=9223372036854775808"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update rental_settings set late_change_fee_mode=null"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_settings set late_change_fee_mode='PERCENT',"
                        + " late_change_fee_value=100.01"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(() -> jdbc.update("update rental_settings set late_change_notice_days=-1"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertJpaValidationStarts();
  }

  @Test
  void presentationPriceUpgradePreservesHistoricalSnapshotAndEnforcesCompleteNonnegativePairs() {
    configuration(MIGRATIONS).target("98").load().migrate();
    UUID managerId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          responsible_manager_id,responsible_manager_display_name,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at,phone,normalized_phone)
        values (?,0,'INDIVIDUAL','Клиент','клиент',?,?,'Менеджер',?,?,current_timestamp,current_timestamp,
          '+79990009999','+79990009999')
        """,
        clientId,
        managerId,
        managerId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,
          manager_role,warehouse_id,state,created_at,updated_at,creation_idempotency_key)
        values (?,0,?,?,?,'Менеджер','RENTAL_MANAGER',?,'ACTIVE',current_timestamp,current_timestamp,?)
        """,
        inquiryId,
        UUID.randomUUID(),
        clientId,
        managerId,
        warehouseId,
        inquiryId);
    jdbc.update(
        """
        insert into client_presentation(
          id,version,inquiry_id,revision,warehouse_id,state,expires_at,view_until,
          last_publish_idempotency_key,last_publish_request_sha256,created_at,updated_at)
        values (?,0,?,1,?,'ACTIVE',current_timestamp + interval '1 hour',
          current_timestamp + interval '2 hours',?,?,current_timestamp,current_timestamp)
        """,
        presentationId,
        inquiryId,
        warehouseId,
        UUID.randomUUID(),
        "b".repeat(64));
    UUID itemId = UUID.randomUUID();
    jdbc.update(
        """
        insert into client_presentation_item(
          id,presentation_id,presentation_revision,rental_item_id,group_key,
          group_label,sort_order,cabin_snapshot_json,media_snapshot_json,created_at)
        values (?,?,1,?,'group','Бытовки',0,'{"number":"БК-1"}'::jsonb,'[]'::jsonb,current_timestamp)
        """,
        itemId,
        presentationId,
        UUID.randomUUID());
    Map<String, Object> before =
        jdbc.queryForMap("select * from client_presentation_item where id=?", itemId);
    Flyway upgraded = flyway(MIGRATIONS);
    upgraded.migrate();
    upgraded.validate();
    Map<String, Object> after =
        jdbc.queryForMap("select * from client_presentation_item where id=?", itemId);
    assertThat(after)
        .containsEntry("pricing_version", null)
        .containsEntry("monthly_price_rubles", null);
    after.remove("pricing_version");
    after.remove("monthly_price_rubles");
    assertThat(after).isEqualTo(before);
    assertThatThrownBy(
            () -> jdbc.update("update client_presentation_item set monthly_price_rubles=0"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update client_presentation_item set pricing_version=0,monthly_price_rubles=-1"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThat(
            jdbc.update(
                "update client_presentation_item set pricing_version=0,monthly_price_rubles=0"))
        .isOne();
    assertJpaValidationStarts();
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
            "customer_warehouse_capacity_command_receipt",
            "customer_warehouse_capacity_isochrone_tariff",
            "customer_warehouse_capacity_job",
            "customer_warehouse_capacity_price_zone",
            "customer_warehouse_capacity_restriction_zone",
            "customer_warehouse_capacity_shift",
            "customer_warehouse_capacity_snapshot",
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
            "rental_order_mutation_command",
            "rental_order_unit_term",
            "rental_settings",
            "shipment_task_settings",
            "sanitized_dead_letter",
            "warehouse_operation_mark_outbox",
            "warehouse_operation_mark_recovery_audit",
            "version_gap_quarantine")
        .doesNotContain(
            "warehouse",
            "rental_item",
            "inventory_session",
            "reservation");
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
    assertThat(toRegclass("uk_customer_warehouse_capacity_snapshot_warehouse")).isNotNull();
    assertThat(toRegclass("idx_customer_warehouse_capacity_command_revision")).isNotNull();
    assertThat(toRegclass("idx_customer_warehouse_capacity_job_date")).isNotNull();
    assertThat(toRegclass("idx_customer_warehouse_capacity_isochrone_tariff_snapshot"))
        .isNotNull();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='shipment_furniture_movement_task'
                  and column_name='replacement_inventory_source_warehouse_id'
                """,
                Integer.class))
        .isOne();
    assertThat(toRegclass("idx_shipment_furniture_movement_task_replacement_source"))
        .isNotNull();
    assertThat(
            constraintDefinition(
                "shipment_furniture_movement_task",
                "ck_shipment_furniture_movement_task_replacement"))
        .contains("replacement_inventory_source_warehouse_id IS NOT NULL");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='driver_logistics_task'
                  and column_name='worker_content_json' and data_type='jsonb'
                """,
                Integer.class))
        .isOne();
    assertThat(
            constraintDefinition(
                "driver_logistics_task", "ck_driver_logistics_task_worker_content_json"))
        .contains("jsonb_typeof(worker_content_json) = 'object'");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name in (
                    'customer_warehouse_capacity_snapshot',
                    'customer_warehouse_capacity_command_receipt')
                  and column_name='source_generation'
                """,
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name in (
                    'customer_warehouse_capacity_snapshot',
                    'customer_warehouse_capacity_command_receipt')
                  and column_name='source_scenario_id'
                """,
                Integer.class))
        .isZero();
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
    UUID orderId = UUID.randomUUID();
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
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-220001','DRAFT',?,?,'Менеджер',?,'Менеджер',
          'RENTAL_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        subjectId,
        subjectId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
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
        orderId,
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
        orderId,
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
  void v86RestoresNewSoleProprietorsWithoutRewritingV43HistoryOnUpgradeAndCleanInstall() {
    Flyway beforeV43 = configuration(MIGRATIONS).target("42").load();
    assertThat(beforeV43.migrate().migrationsExecuted).isEqualTo(42);

    UUID managerId = UUID.randomUUID();
    UUID historicalProprietorId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          contact_person,responsible_manager_id,responsible_manager_display_name,
          created_by_subject_id,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (?,0,'SOLE_PROPRIETOR','ИП История V86','ип история v86',
          '+79990000086','+79990000086','Исторический контакт',?,'Менеджер',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        historicalProprietorId,
        managerId,
        managerId,
        UUID.randomUUID(),
        "8".repeat(64));

    Flyway beforeV86 = configuration(MIGRATIONS).target("85").load();
    assertThat(beforeV86.migrate().migrationsExecuted).isEqualTo(43);
    beforeV86.validate();
    assertThat(
            jdbc.queryForObject(
                "select client_type from order_client where id=?",
                String.class,
                historicalProprietorId))
        .isEqualTo("LEGAL_ENTITY");
    assertThatThrownBy(
            () ->
                insertV42Client(
                    "SOLE_PROPRIETOR",
                    "ИП До V86",
                    "ип до v86",
                    "+79990000186",
                    managerId))
        .hasMessageContaining("ck_order_client_type");

    Flyway upgraded = configuration(MIGRATIONS).target("86").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
    insertV42Client(
        "SOLE_PROPRIETOR", "ИП После V86", "ип после v86", "+79990000286", managerId);
    assertThat(
            jdbc.queryForObject(
                "select client_type from order_client where id=?",
                String.class,
                historicalProprietorId))
        .isEqualTo("LEGAL_ENTITY");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_client where client_type='SOLE_PROPRIETOR'",
                Long.class))
        .isOne();

    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
    Flyway cleanInstall = flyway(MIGRATIONS);
    assertThat(cleanInstall.migrate().migrationsExecuted).isPositive();
    cleanInstall.validate();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='86' and success",
                Long.class))
        .isOne();
    insertV42Client(
        "SOLE_PROPRIETOR", "ИП Чистая V86", "ип чистая v86", "+79990000386", managerId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_client where client_type='SOLE_PROPRIETOR'",
                Long.class))
        .isOne();
    assertJpaValidationStarts();
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
  void v53AddsExplicitFutureShipmentPoolWithoutReclassifyingExistingDocuments() {
    Flyway beforeV53 = configuration(MIGRATIONS).target("52").load();
    assertThat(beforeV53.migrate().migrationsExecuted).isEqualTo(52);
    UUID warehouseId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID shipmentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,party_snapshot,driver_snapshot,
          scheduled_date,requested_by_subject_id,correlation_id,created_at,updated_at)
        values (?,0,'SHIPMENT','DRAFT',?,'Клиент','Водитель',date '2026-08-25',?,?,
          clock_timestamp(),clock_timestamp())
        """,
        shipmentId,
        warehouseId,
        subjectId,
        UUID.randomUUID());

    Flyway upgraded = configuration(MIGRATIONS).target("53").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select warehouse_driver_pool from logistics_document where id=?",
                Boolean.class,
                shipmentId))
        .isFalse();
    jdbc.update(
        "update logistics_document set warehouse_driver_pool=true where id=?", shipmentId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update logistics_document set driver_worker_id=? where id=?",
                    UUID.randomUUID(),
                    shipmentId))
        .hasMessageContaining("ck_logistics_document_warehouse_driver_pool");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update logistics_document set document_type='RETURN' where id=?", shipmentId))
        .hasMessageContaining("ck_logistics_document_warehouse_driver_pool");
    assertThat(
            jdbc.queryForObject(
                """
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conname='ck_driver_logistics_task_kind_audience'
                """,
                String.class))
        .contains("'SHIPMENT'", "'RETURN'", "'WAREHOUSE_DRIVERS'");
    assertJpaValidationStarts();
  }

  @Test
  void v56AddsEmptyMetadataToExistingPhotoPresentationsAndValidatesJpa() {
    Flyway beforeV56 = configuration(MIGRATIONS).target("55").load();
    assertThat(beforeV56.migrate().migrationsExecuted).isEqualTo(55);
    UUID presentationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into cabin_photo_presentation(
          id,version,cabin_id,cabin_number,warehouse_id,rental_item_version,
          created_by_subject_id,idempotency_key,request_sha256,photo_snapshot_json,created_at)
        values (?,0,?,'БК-055',?,7,?,?,?,'[{}]'::jsonb,clock_timestamp())
        """,
        presentationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));

    Flyway upgraded = configuration(MIGRATIONS).target("56").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select metadata_snapshot_json::text from cabin_photo_presentation where id=?",
                String.class,
                presentationId))
        .isEqualTo("{}");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update cabin_photo_presentation set metadata_snapshot_json='[]'::jsonb where id=?",
                    presentationId))
        .hasMessageContaining("ck_cabin_photo_presentation_metadata_snapshot");
    assertJpaValidationStarts();
  }

  @Test
  void v57AdmitsHistoricalRentalMovementIdempotencyAndValidatesJpa() {
    Flyway beforeV57 = configuration(MIGRATIONS).target("56").load();
    assertThat(beforeV57.migrate().migrationsExecuted).isEqualTo(56);
    assertThat(
            jdbc.queryForObject(
                """
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conname='ck_logistics_idempotency_operation'
                """,
                String.class))
        .doesNotContain("CREATE_HISTORICAL_RENTAL_MOVEMENT");

    Flyway upgraded = configuration(MIGRATIONS).target("57").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                """
                select pg_get_constraintdef(oid)
                from pg_constraint
                where conname='ck_logistics_idempotency_operation'
                """,
                String.class))
        .contains("CREATE_HISTORICAL_RENTAL_MOVEMENT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema='public'"
                    + " and table_name='logistics_idempotency_record' and column_name='response_json'",
                Integer.class))
        .isZero();
  }

  @Test
  void v58AdmitsHistoricalRentalMovementUpdateIdempotencyAndValidatesJpa() {
    Flyway beforeV58 = configuration(MIGRATIONS).target("57").load();
    assertThat(beforeV58.migrate().migrationsExecuted).isEqualTo(57);
    assertThat(logisticsConstraintDefinition("ck_logistics_idempotency_operation"))
        .contains("CREATE_HISTORICAL_RENTAL_MOVEMENT")
        .doesNotContain("UPDATE_HISTORICAL_RENTAL_MOVEMENT");

    Flyway upgraded = configuration(MIGRATIONS).target("58").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(logisticsConstraintDefinition("ck_logistics_idempotency_operation"))
        .contains(
            "CREATE_HISTORICAL_RENTAL_MOVEMENT",
            "UPDATE_HISTORICAL_RENTAL_MOVEMENT");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from pg_constraint"
                    + " where conname='ck_logistics_historical_idempotency_response'",
                Integer.class))
        .isZero();
  }

  @Test
  void v59AddsCustomerBookingStateAndExpandsOnlyRequiredActorRoles() {
    Flyway beforeV59 = configuration(MIGRATIONS).target("58").load();
    assertThat(beforeV59.migrate().migrationsExecuted).isEqualTo(58);
    assertThat(toRegclass("customer_profile")).isNull();
    assertThat(constraintDefinition("rental_order", "ck_rental_order_creator_role"))
        .doesNotContain("CUSTOMER");

    Flyway upgraded = configuration(MIGRATIONS).target("59").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(tableNames())
        .contains("customer_profile", "customer_rental_session", "customer_delivery_slot");
    assertThat(constraintDefinition("rental_order", "ck_rental_order_creator_role"))
        .contains("CUSTOMER");
    assertThat(constraintDefinition("rental_inquiry", "ck_rental_inquiry_manager_role"))
        .contains("CUSTOMER");
    assertThat(
            constraintDefinition(
                "rental_inquiry_selection_receipt",
                "ck_rental_inquiry_selection_receipt_actor_role"))
        .contains("CUSTOMER");
    assertThat(toRegclass("uk_customer_delivery_slot_confirmed_order")).isNotNull();
    assertJpaValidationStarts();
  }

  @Test
  void v56ToV63UpgradePreservesPublishedHistoryAndAppliesProfileFollowupsThenValidatesJpa() {
    Flyway beforeV57 = configuration(MIGRATIONS).target("56").load();
    assertThat(beforeV57.migrate().migrationsExecuted).isEqualTo(56);
    assertThat(toRegclass("customer_profile")).isNull();
    assertThat(toRegclass("customer_scenario_capacity_snapshot")).isNull();

    Flyway throughPublishedHistory = configuration(MIGRATIONS).target("60").load();
    assertThat(throughPublishedHistory.migrate().migrationsExecuted).isEqualTo(4);
    throughPublishedHistory.validate();

    assertThat(
            jdbc.queryForList(
                "select checksum from flyway_schema_history"
                    + " where version in ('57','58','60') order by version",
                Integer.class))
        .containsExactly(-821369856, -1548850854, 1424256002);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from information_schema.columns where table_schema='public'"
                    + " and table_name='logistics_idempotency_record' and column_name='response_json'",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select pg_get_indexdef(to_regclass('idx_customer_scenario_capacity_command_revision'))",
                String.class))
        .doesNotContain("source_generation");

    Flyway upgraded = configuration(MIGRATIONS).target("63").load();
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(3);
    upgraded.validate();

    assertThat(tableNames())
        .contains(
            "customer_profile",
            "customer_delivery_slot",
            "customer_scenario_capacity_snapshot",
            "customer_scenario_capacity_command_receipt",
            "customer_scenario_capacity_job");
    assertThat(
            jdbc.queryForObject(
                "select pg_get_indexdef(to_regclass('idx_customer_scenario_capacity_command_revision'))",
                String.class))
        .contains(
            "warehouse_id",
            "source_scenario_id",
            "source_generation",
            "source_revision");
    assertThat(logisticsConstraintDefinition("ck_logistics_historical_idempotency_response"))
        .contains("response_json IS NOT NULL");
    assertThat(
            jdbc.queryForObject(
                "select convalidated from pg_constraint"
                    + " where conname='ck_logistics_historical_idempotency_response'",
                Boolean.class))
        .isFalse();
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_schema='public' and table_name='customer_profile'"
                    + " and column_name in"
                    + " ('avatar_warehouse_id','avatar_media_id','avatar_generation')"
                    + " order by column_name",
                String.class))
        .containsExactly("avatar_generation", "avatar_media_id", "avatar_warehouse_id");
    assertThat(toRegclass("ix_customer_profile_avatar_media")).isNotNull();
    assertThat(constraintDefinition("customer_profile", "ck_customer_profile_avatar_generation"))
        .contains("avatar_media_id IS NULL", "avatar_generation IS NULL")
        .contains("avatar_media_id IS NOT NULL", "avatar_generation >= 1");
    assertJpaValidationStarts();
  }

  @Test
  void v64MakesZonesTariffOnlyAndBackfillsExistingCapacityRows() {
    Flyway beforeV64 = configuration(MIGRATIONS).target("63").load();
    assertThat(beforeV64.migrate().migrationsExecuted).isEqualTo(63);
    UUID snapshotId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID legacySourceId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID sourceJobId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    jdbc.update(
        """
        insert into customer_scenario_capacity_snapshot(
          id,version,warehouse_id,source_scenario_id,source_generation,source_revision,
          created_at,updated_at)
        values (?,0,?,?,1,?,clock_timestamp(),clock_timestamp())
        """,
        snapshotId,
        warehouseId,
        legacySourceId,
        "a".repeat(64));
    jdbc.update(
        """
        insert into customer_scenario_capacity_job(
          id,snapshot_id,source_job_id,delivery_date,latitude,longitude,cabin_count,
          window_start,window_end,service_minutes)
        values (?,?,?,date '2026-08-29',59.9,30.3,1,time '09:00',time '12:00',60)
        """,
        jobId,
        snapshotId,
        sourceJobId);
    jdbc.update(
        """
        insert into customer_scenario_capacity_command_receipt(
          idempotency_key,warehouse_id,source_scenario_id,source_generation,source_revision,
          request_sha256,snapshot_version,job_count,shift_count,response_updated_at,created_at)
        values (?,?,?,1,?,?,0,1,0,clock_timestamp(),clock_timestamp())
        """,
        commandId,
        warehouseId,
        legacySourceId,
        "a".repeat(64),
        "b".repeat(64));

    Flyway upgraded = configuration(MIGRATIONS).target("64").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                "select task_type,trailer_access_allowed,priority,mandatory"
                    + " from customer_scenario_capacity_job where id=?",
                jobId))
        .containsEntry("task_type", "DELIVERY")
        .containsEntry("trailer_access_allowed", true)
        .containsEntry("priority", 0)
        .containsEntry("mandatory", true);
    assertThat(
            jdbc.queryForObject(
                "select price_zone_count from customer_scenario_capacity_command_receipt"
                    + " where idempotency_key=?",
                Integer.class,
                commandId))
        .isZero();
    assertThat(toRegclass("customer_scenario_capacity_price_zone")).isNotNull();
    assertThat(constraintDefinition("customer_delivery_slot", "ck_customer_delivery_slot_capacity"))
        .contains("travel_zone_hours >= 1")
        .doesNotContain("travel_zone_hours <= 4");
    assertJpaValidationStarts();
  }

  @Test
  void v65PreservesCapacityRowsWhileMakingWarehouseTheOnlyOwnerIdentity() {
    Flyway beforeV65 = configuration(MIGRATIONS).target("64").load();
    assertThat(beforeV65.migrate().migrationsExecuted).isEqualTo(64);
    UUID snapshotId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID legacySourceId = UUID.randomUUID();
    UUID commandId = UUID.randomUUID();
    UUID jobId = UUID.randomUUID();
    UUID shiftId = UUID.randomUUID();
    UUID zoneId = UUID.randomUUID();
    UUID sourceZoneId = UUID.randomUUID();
    UUID customerSubjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID mappedSlotId = UUID.randomUUID();
    UUID unmappedSlotId = UUID.randomUUID();
    jdbc.update(
        """
        insert into customer_scenario_capacity_snapshot(
          id,version,warehouse_id,source_scenario_id,source_generation,source_revision,
          created_at,updated_at)
        values (?,3,?,?,7,?,clock_timestamp(),clock_timestamp())
        """,
        snapshotId,
        warehouseId,
        legacySourceId,
        "a".repeat(64));
    jdbc.update(
        """
        insert into customer_scenario_capacity_command_receipt(
          idempotency_key,warehouse_id,source_scenario_id,source_generation,source_revision,
          request_sha256,snapshot_version,job_count,shift_count,price_zone_count,
          response_updated_at,created_at)
        values (?,?,?,7,?,?,3,1,1,1,clock_timestamp(),clock_timestamp())
        """,
        commandId,
        warehouseId,
        legacySourceId,
        "a".repeat(64),
        "b".repeat(64));
    jdbc.update(
        """
        insert into customer_scenario_capacity_job(
          id,snapshot_id,source_job_id,delivery_date,latitude,longitude,cabin_count,
          window_start,window_end,service_minutes)
        values (?,?,?,date '2026-08-30',59.9,30.3,1,time '09:00',time '12:00',60)
        """,
        jobId,
        snapshotId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into customer_scenario_capacity_shift(
          id,snapshot_id,source_shift_id,delivery_date,shift_start,shift_end,
          break_minutes,cabin_capacity)
        values (?,?,?,date '2026-08-30',time '08:00',time '20:00',30,2)
        """,
        shiftId,
        snapshotId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into customer_scenario_capacity_price_zone(
          id,snapshot_id,source_zone_id,source_zone_version,code,priority,
          delivery_price_rubles,pickup_price_rubles,geometry_json)
        values (?,?,?,?,?,10,3000,2000,?::text)
        """,
        zoneId,
        snapshotId,
        sourceZoneId,
        5,
        "legacy-zone",
        "{\"type\":\"MultiPolygon\",\"coordinates\":[[[[30,59],[31,59],[31,60],[30,60],[30,59]]]]}");
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,phone,normalized_phone,
          responsible_manager_id,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Capacity migration client',?,?,?, ?,
          '+79990000000','+79990000000',?,
          clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "capacity-migration-" + clientId,
        customerSubjectId,
        UUID.randomUUID(),
        "c".repeat(64),
        customerSubjectId);
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,creation_idempotency_key,created_at,updated_at)
        values (?,0,null,?,?,'Capacity migration customer','CUSTOMER',?,'ACTIVE',?,
          clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        clientId,
        customerSubjectId,
        warehouseId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into customer_delivery_slot(
          id,version,customer_subject_id,inquiry_id,warehouse_id,delivery_date,
          window_start,window_end,delivery_address,latitude,longitude,cabin_count,
          one_way_travel_seconds,travel_zone_hours,capacity_remaining,state,expires_at,
          delivery_price_rubles,price_zone_code,created_at,updated_at)
        values (?,0,?,?,?,date '2026-08-30',time '09:00',time '12:00','Migration address',
          59.9,30.3,1,1800,1,1,'RELEASED',clock_timestamp(),3000,?,
          clock_timestamp(),clock_timestamp())
        """,
        mappedSlotId,
        customerSubjectId,
        inquiryId,
        warehouseId,
        "legacy-zone");
    jdbc.update(
        """
        insert into customer_delivery_slot(
          id,version,customer_subject_id,inquiry_id,warehouse_id,delivery_date,
          window_start,window_end,delivery_address,latitude,longitude,cabin_count,
          one_way_travel_seconds,travel_zone_hours,capacity_remaining,state,expires_at,
          delivery_price_rubles,price_zone_code,created_at,updated_at)
        values (?,0,?,?,?,date '2026-08-30',time '12:00',time '15:00','Migration address',
          59.9,30.3,1,1800,1,1,'RELEASED',clock_timestamp(),4500,'removed-zone',
          clock_timestamp(),clock_timestamp())
        """,
        unmappedSlotId,
        customerSubjectId,
        inquiryId,
        warehouseId);

    Flyway upgraded = configuration(MIGRATIONS).target("65").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(tableNames())
        .contains(
            "customer_warehouse_capacity_snapshot",
            "customer_warehouse_capacity_command_receipt",
            "customer_warehouse_capacity_job",
            "customer_warehouse_capacity_shift",
            "customer_warehouse_capacity_price_zone")
        .doesNotContain(
            "customer_scenario_capacity_snapshot",
            "customer_scenario_capacity_command_receipt",
            "customer_scenario_capacity_job",
            "customer_scenario_capacity_shift",
            "customer_scenario_capacity_price_zone");
    assertThat(
            jdbc.queryForMap(
                "select warehouse_id,source_generation,source_revision"
                    + " from customer_warehouse_capacity_snapshot where id=?",
                snapshotId))
        .containsEntry("warehouse_id", warehouseId)
        .containsEntry("source_generation", 7L)
        .containsEntry("source_revision", "a".repeat(64));
    assertThat(
            jdbc.queryForMap(
                "select source_zone_id,source_zone_version,delivery_price_rubles,pickup_price_rubles"
                    + " from customer_warehouse_capacity_price_zone where id=?",
                zoneId))
        .containsEntry("source_zone_id", sourceZoneId)
        .containsEntry("source_zone_version", 5L)
        .containsEntry("delivery_price_rubles", 3_000L)
        .containsEntry("pickup_price_rubles", 2_000L);
    assertThat(
            jdbc.queryForObject(
                "select price_zone_id from customer_delivery_slot where id=?",
                UUID.class,
                mappedSlotId))
        .isEqualTo(sourceZoneId);
    assertThat(
            jdbc.queryForMap(
                "select delivery_price_rubles,price_zone_id from customer_delivery_slot where id=?",
                unmappedSlotId))
        .containsEntry("delivery_price_rubles", 4_500L)
        .containsEntry("price_zone_id", null);
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_schema='public'"
                    + " and table_name='customer_warehouse_capacity_price_zone'"
                    + " and column_name in ('code','priority','source_scenario_id')",
                String.class))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns"
                    + " where table_schema='public' and table_name='customer_delivery_slot'"
                    + " and column_name in ('price_zone_code')",
                String.class))
        .isEmpty();
    assertThat(toRegclass("idx_customer_warehouse_capacity_command_revision")).isNotNull();
    assertThat(toRegclass("idx_customer_warehouse_capacity_price_zone_snapshot")).isNotNull();
    assertJpaValidationStarts();
  }

  @Test
  void v66BackfillsFixedSlotsAndConstrainsTheExplicitSlotKind() {
    Flyway beforeV66 = configuration(MIGRATIONS).target("65").load();
    assertThat(beforeV66.migrate().migrationsExecuted).isEqualTo(65);
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,phone,normalized_phone,
          responsible_manager_id,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Slot kind migration client',?,?,?,?,
          '+79990000000','+79990000000',?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "slot-kind-migration-" + clientId,
        subjectId,
        UUID.randomUUID(),
        "d".repeat(64),
        subjectId);
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,creation_idempotency_key,created_at,updated_at)
        values (?,0,null,?,?,'Slot kind migration customer','CUSTOMER',?,'ACTIVE',?,
          clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        clientId,
        subjectId,
        warehouseId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into customer_delivery_slot(
          id,version,customer_subject_id,inquiry_id,warehouse_id,delivery_date,
          window_start,window_end,delivery_address,latitude,longitude,cabin_count,
          one_way_travel_seconds,travel_zone_hours,capacity_remaining,state,expires_at,
          created_at,updated_at)
        values (?,0,?,?,?,date '2026-08-30',time '09:00',time '12:00',
          'Slot kind migration address',59.9,30.3,1,1800,1,1,'RELEASED',
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        slotId,
        subjectId,
        inquiryId,
        warehouseId);

    Flyway upgraded = configuration(MIGRATIONS).target("66").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select slot_kind from customer_delivery_slot where id=?", String.class, slotId))
        .isEqualTo("FIXED_WINDOW");
    assertThat(constraintDefinition("customer_delivery_slot", "ck_customer_delivery_slot_kind"))
        .contains("FIXED_WINDOW", "DURING_DAY");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update customer_delivery_slot set slot_kind='UNKNOWN' where id=?", slotId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertJpaValidationStarts();
  }

  @Test
  void v69BackfillsIsochroneTariffsAndPersistsStrictRestrictionPolicies() {
    Flyway beforeV69 = configuration(MIGRATIONS).target("68").load();
    assertThat(beforeV69.migrate().migrationsExecuted).isPositive();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID snapshotId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    jdbc.update(
        """
        insert into customer_warehouse_capacity_snapshot(
          id,version,warehouse_id,source_generation,source_revision,created_at,updated_at)
        values (?,0,?,1,?,clock_timestamp(),clock_timestamp())
        """,
        snapshotId,
        warehouseId,
        "a".repeat(64));
    jdbc.update(
        """
        insert into customer_warehouse_capacity_command_receipt(
          idempotency_key,warehouse_id,source_generation,source_revision,request_sha256,
          snapshot_version,job_count,shift_count,price_zone_count,response_updated_at,created_at)
        values (?,?,1,?,?,0,0,0,0,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        warehouseId,
        "a".repeat(64),
        "b".repeat(64));
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,phone,normalized_phone,
          responsible_manager_id,created_at,updated_at)
        values (?,0,'INDIVIDUAL','Isochrone migration client',?,?,?, ?,
          '+79990000001','+79990000001',?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "isochrone-migration-" + clientId,
        subjectId,
        UUID.randomUUID(),
        "c".repeat(64),
        subjectId);
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,creation_idempotency_key,created_at,updated_at)
        values (?,0,null,?,?,'Isochrone migration customer','CUSTOMER',?,'ACTIVE',?,
          clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        clientId,
        subjectId,
        warehouseId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into customer_delivery_slot(
          id,version,customer_subject_id,inquiry_id,warehouse_id,delivery_date,slot_kind,
          window_start,window_end,delivery_address,latitude,longitude,cabin_count,
          one_way_travel_seconds,travel_zone_hours,capacity_remaining,state,expires_at,
          created_at,updated_at)
        values (?,0,?,?,?,date '2026-08-30','FIXED_WINDOW',time '09:00',time '12:00',
          'Isochrone migration address',59.9,30.3,1,1800,1,1,'RELEASED',
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        slotId,
        subjectId,
        inquiryId,
        warehouseId);

    Flyway upgraded = configuration(MIGRATIONS).target("69").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select isochrone_price_60_minutes,isochrone_price_120_minutes,
                       isochrone_price_180_minutes,isochrone_price_240_minutes
                from customer_warehouse_capacity_snapshot where id=?
                """,
                snapshotId))
        .containsEntry("isochrone_price_60_minutes", 10_000L)
        .containsEntry("isochrone_price_120_minutes", 15_000L)
        .containsEntry("isochrone_price_180_minutes", 20_000L)
        .containsEntry("isochrone_price_240_minutes", 25_000L);
    assertThat(
            jdbc.queryForObject(
                "select restriction_zone_count from customer_warehouse_capacity_command_receipt",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                """
                select delivery_price_rubles,price_zone_id,price_isochrone_minutes
                from customer_delivery_slot where id=?
                """,
                slotId))
        .containsEntry("delivery_price_rubles", 10_000L)
        .containsEntry("price_isochrone_minutes", 60)
        .containsEntry("price_zone_id", null);
    assertThat(toRegclass("customer_warehouse_capacity_restriction_zone")).isNotNull();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into customer_warehouse_capacity_restriction_zone(
                      id,snapshot_id,source_zone_id,source_zone_version,restriction_kind,geometry_json)
                    values (?,?,?,1,'SPECIAL_PRICE','{"type":"MultiPolygon","coordinates":[]}')
                    """,
                    UUID.randomUUID(),
                    snapshotId,
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertJpaValidationStarts();
  }

  @Test
  void v72BackfillsTheServiceWarehouseForExistingReplacementCheckpoints() {
    Flyway beforeV72 = configuration(MIGRATIONS).target("71").load();
    assertThat(beforeV72.migrate().migrationsExecuted).isPositive();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID checkpointId = UUID.randomUUID();
    UUID oldRentalItemId = UUID.randomUUID();
    UUID replacementRentalItemId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,phone,normalized_phone,
          responsible_manager_id,created_at,updated_at)
        values (?,0,'INDIVIDUAL','V72 replacement client',?,?,?, ?,
          '+79990000072','+79990000072',?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "v72-replacement-client-" + clientId,
        subjectId,
        UUID.randomUUID(),
        "7".repeat(64),
        subjectId);
    jdbc.update(
        """
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,'ORD-990072','DRAFT',?,?,'V72 manager',?,'V72 manager',
          'WAREHOUSE_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        subjectId,
        subjectId,
        serviceWarehouseId,
        UUID.randomUUID(),
        "8".repeat(64));
    jdbc.update(
        """
        insert into shipment_furniture_movement_task(
          id,version,document_id,rental_item_id,unit_number,equipment_movement_task_id,
          line_count,created_at,order_id,old_rental_item_id,replacement_reason,
          replacement_actor_subject_id,replacement_actor_role,replacement_idempotency_key,
          replacement_batch_idempotency_key,replacement_pair_index,replacement_request_sha256)
        values (?,0,null,?,'V72-UNIT',null,0,clock_timestamp(),?,?,?,?,'WAREHOUSE_MANAGER',
          ?,?,0,?)
        """,
        checkpointId,
        replacementRentalItemId,
        orderId,
        oldRentalItemId,
        "Legacy same-warehouse replacement",
        subjectId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "9".repeat(64));

    Flyway versionSeventyTwo = configuration(MIGRATIONS).target("72").load();
    assertThat(versionSeventyTwo.migrate().migrationsExecuted).isOne();
    versionSeventyTwo.validate();

    assertThat(
            jdbc.queryForObject(
                """
                select replacement_inventory_source_warehouse_id
                from shipment_furniture_movement_task where id=?
                """,
                UUID.class,
                checkpointId))
        .isEqualTo(serviceWarehouseId);
    assertThat(
            constraintDefinition(
                "shipment_furniture_movement_task",
                "ck_shipment_furniture_movement_task_replacement"))
        .contains("replacement_inventory_source_warehouse_id IS NOT NULL");
    assertJpaValidationStarts();
  }

  @Test
  void v73BackfillsEmptyWorkerContentAndEnforcesAnObjectSnapshot() {
    Flyway beforeV73 = configuration(MIGRATIONS).target("72").load();
    assertThat(beforeV73.migrate().migrationsExecuted).isPositive();
    UUID taskId = UUID.randomUUID();
    insertV72GroupedShipmentTask(taskId, UUID.randomUUID());

    Flyway versionSeventyThree = configuration(MIGRATIONS).target("73").load();
    assertThat(versionSeventyThree.migrate().migrationsExecuted).isOne();
    versionSeventyThree.validate();

    assertThat(
            jdbc.queryForObject(
                "select worker_content_json::text from driver_logistics_task where id=?",
                String.class,
                taskId))
        .isEqualTo("{}");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update driver_logistics_task set worker_content_json='[]'::jsonb where id=?",
                    taskId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("ck_driver_logistics_task_worker_content_json");
    assertJpaValidationStarts();
  }

  @Test
  void v74NormalizesIsochroneTariffsAndPreservesHistoricalSlotQuotes() {
    Flyway beforeV74 = configuration(MIGRATIONS).target("73").load();
    assertThat(beforeV74.migrate().migrationsExecuted).isPositive();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID inquiryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID snapshotId = UUID.randomUUID();
    UUID historicalZoneId = UUID.randomUUID();
    UUID slotId = UUID.randomUUID();
    jdbc.update(
        """
        insert into customer_warehouse_capacity_snapshot(
          id,version,warehouse_id,source_generation,source_revision,
          isochrone_price_60_minutes,isochrone_price_120_minutes,
          isochrone_price_180_minutes,isochrone_price_240_minutes,created_at,updated_at)
        values (?,0,?,1,?,11000,16000,21000,26000,clock_timestamp(),clock_timestamp())
        """,
        snapshotId,
        warehouseId,
        "a".repeat(64));
    jdbc.update(
        """
        insert into customer_warehouse_capacity_command_receipt(
          idempotency_key,warehouse_id,source_generation,source_revision,request_sha256,
          snapshot_version,job_count,shift_count,price_zone_count,restriction_zone_count,
          response_updated_at,created_at)
        values (?,?,1,?,?,0,0,0,2,1,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        warehouseId,
        "a".repeat(64),
        "b".repeat(64));
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,phone,normalized_phone,
          responsible_manager_id,created_at,updated_at)
        values (?,0,'INDIVIDUAL','V74 client',?,?,?,?,'+79990000001','+79990000001',?,
          clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "v74-client-" + clientId,
        subjectId,
        UUID.randomUUID(),
        "c".repeat(64),
        subjectId);
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,creation_idempotency_key,created_at,updated_at)
        values (?,0,null,?,?,'V74 customer','CUSTOMER',?,'ACTIVE',?,
          clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        clientId,
        subjectId,
        warehouseId,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into customer_delivery_slot(
          id,version,customer_subject_id,inquiry_id,warehouse_id,delivery_date,slot_kind,
          window_start,window_end,delivery_address,latitude,longitude,cabin_count,
          one_way_travel_seconds,travel_zone_hours,capacity_remaining,site_cabin_capacity,
          delivery_price_rubles,price_zone_id,price_isochrone_minutes,state,expires_at,
          created_at,updated_at)
        values (?,0,?,?,?,date '2026-08-30','FIXED_WINDOW',time '09:00',time '12:00',
          'Historical special tariff',59.9,30.3,1,1800,1,1,1,7777,?,null,'RELEASED',
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        slotId,
        subjectId,
        inquiryId,
        warehouseId,
        historicalZoneId);

    Flyway versionSeventyFour = configuration(MIGRATIONS).target("74").load();
    assertThat(versionSeventyFour.migrate().migrationsExecuted).isOne();
    versionSeventyFour.validate();

    assertThat(
            jdbc.queryForList(
                """
                select travel_minutes,price_rubles
                from customer_warehouse_capacity_isochrone_tariff
                where snapshot_id=? order by travel_minutes
                """,
                snapshotId))
        .containsExactly(
            Map.of("travel_minutes", 60, "price_rubles", 11_000L),
            Map.of("travel_minutes", 120, "price_rubles", 16_000L),
            Map.of("travel_minutes", 180, "price_rubles", 21_000L),
            Map.of("travel_minutes", 240, "price_rubles", 26_000L));
    assertThat(
            jdbc.queryForObject(
                "select isochrone_tariff_count from customer_warehouse_capacity_command_receipt",
                Integer.class))
        .isEqualTo(4);
    assertThat(toRegclass("customer_warehouse_capacity_price_zone")).isNull();
    assertThat(toRegclass("customer_warehouse_capacity_restriction_zone")).isNull();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from information_schema.columns
                where table_schema='public' and table_name='customer_warehouse_capacity_snapshot'
                  and column_name like 'isochrone_price_%'
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                """
                select delivery_price_rubles,price_zone_id,price_isochrone_minutes
                from customer_delivery_slot where id=?
                """,
                slotId))
        .containsEntry("delivery_price_rubles", 7_777L)
        .containsEntry("price_zone_id", historicalZoneId)
        .containsEntry("price_isochrone_minutes", null);
    jdbc.update(
        """
        update customer_delivery_slot
        set delivery_price_rubles=30000,price_zone_id=null,price_isochrone_minutes=300
        where id=?
        """,
        slotId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update customer_delivery_slot set price_isochrone_minutes=90 where id=?",
                    slotId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertJpaValidationStarts();
  }

  @Test
  void v76RestoresOnlyExceptionalZonePoliciesWithoutInventingDeletedGeometry() {
    Flyway beforeV76 = configuration(MIGRATIONS).target("75").load();
    assertThat(beforeV76.migrate().migrationsExecuted).isPositive();
    UUID warehouseId = UUID.randomUUID();
    UUID snapshotId = UUID.randomUUID();
    UUID receiptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into customer_warehouse_capacity_snapshot(
          id,version,warehouse_id,source_generation,source_revision,created_at,updated_at)
        values (?,0,?,1,?,clock_timestamp(),clock_timestamp())
        """,
        snapshotId,
        warehouseId,
        "a".repeat(64));
    jdbc.update(
        """
        insert into customer_warehouse_capacity_command_receipt(
          idempotency_key,warehouse_id,source_generation,source_revision,request_sha256,
          snapshot_version,job_count,shift_count,isochrone_tariff_count,
          response_updated_at,created_at)
        values (?,?,1,?,?,0,0,0,4,clock_timestamp(),clock_timestamp())
        """,
        receiptId,
        warehouseId,
        "a".repeat(64),
        "b".repeat(64));

    Flyway versionSeventySix = configuration(MIGRATIONS).target("76").load();
    assertThat(versionSeventySix.migrate().migrationsExecuted).isOne();
    versionSeventySix.validate();

    assertThat(toRegclass("customer_warehouse_capacity_price_zone")).isNotNull();
    assertThat(toRegclass("customer_warehouse_capacity_restriction_zone")).isNotNull();
    assertThat(
            jdbc.queryForMap(
                """
                select price_zone_count,restriction_zone_count
                from customer_warehouse_capacity_command_receipt where idempotency_key=?
                """,
                receiptId))
        .containsEntry("price_zone_count", 0)
        .containsEntry("restriction_zone_count", 0);
    UUID rollingDeploymentReceiptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into customer_warehouse_capacity_command_receipt(
          idempotency_key,warehouse_id,source_generation,source_revision,request_sha256,
          snapshot_version,job_count,shift_count,isochrone_tariff_count,
          response_updated_at,created_at)
        values (?,?,2,?,?,0,0,0,4,clock_timestamp(),clock_timestamp())
        """,
        rollingDeploymentReceiptId,
        warehouseId,
        "c".repeat(64),
        "d".repeat(64));
    assertThat(
            jdbc.queryForMap(
                """
                select price_zone_count,restriction_zone_count
                from customer_warehouse_capacity_command_receipt where idempotency_key=?
                """,
                rollingDeploymentReceiptId))
        .containsEntry("price_zone_count", 0)
        .containsEntry("restriction_zone_count", 0);

    String geometry =
        """
        {"type":"MultiPolygon","coordinates":[[[[30.0,59.0],[31.0,59.0],
        [31.0,60.0],[30.0,60.0],[30.0,59.0]]]]}
        """;
    jdbc.update(
        """
        insert into customer_warehouse_capacity_price_zone(
          id,snapshot_id,source_zone_id,source_zone_version,delivery_price_rubles,
          pickup_price_rubles,geometry_json)
        values (?,?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        snapshotId,
        UUID.randomUUID(),
        1,
        12_000,
        8_000,
        geometry);
    jdbc.update(
        """
        insert into customer_warehouse_capacity_restriction_zone(
          id,snapshot_id,source_zone_id,source_zone_version,restriction_kind,geometry_json)
        values (?,?,?,?,?,?)
        """,
        UUID.randomUUID(),
        snapshotId,
        UUID.randomUUID(),
        1,
        "NO_TRAILER",
        geometry);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into customer_warehouse_capacity_restriction_zone(
                      id,snapshot_id,source_zone_id,source_zone_version,restriction_kind,
                      geometry_json)
                    values (?,?,?,?,?,?)
                    """,
                    UUID.randomUUID(),
                    snapshotId,
                    UUID.randomUUID(),
                    1,
                    "ORDINARY_DELIVERY_ZONE",
                    geometry))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into customer_warehouse_capacity_price_zone(
                      id,snapshot_id,source_zone_id,source_zone_version,delivery_price_rubles,
                      pickup_price_rubles,geometry_json)
                    values (?,?,?,?,?,?,?)
                    """,
                    UUID.randomUUID(),
                    snapshotId,
                    UUID.randomUUID(),
                    1,
                    12_000,
                    8_000,
                    "{\"type\":\"Polygon\",\"coordinates\":[]}"))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertJpaValidationStarts();
  }

  @Test
  void v77AddsLeaseFencedRentalOrderMutationRecoveryWithoutChangingExistingOrders() {
    Flyway beforeV77 = configuration(MIGRATIONS).target("76").load();
    assertThat(beforeV77.migrate().migrationsExecuted).isPositive();
    UUID subjectId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,phone,normalized_phone,
          responsible_manager_id,created_by_subject_id,creation_idempotency_key,
          creation_request_sha256,created_at,updated_at)
        values (?,0,'INDIVIDUAL','V77 client',?,'+79990000077','+79990000077',
          ?,?,?,?,clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "v77-client-" + clientId,
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
        values (?,0,'ORD-990077','DRAFT',?,?,'V77 manager',?,'V77 manager',
          'WAREHOUSE_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        clientId,
        subjectId,
        subjectId,
        warehouseId,
        UUID.randomUUID(),
        "b".repeat(64));

    Flyway versionSeventySeven = configuration(MIGRATIONS).target("77").load();
    assertThat(versionSeventySeven.migrate().migrationsExecuted).isOne();
    versionSeventySeven.validate();

    assertThat(toRegclass("rental_order_mutation_command")).isNotNull();
    assertThat(toRegclass("uk_rental_order_mutation_command_open_order")).isNotNull();
    assertThat(toRegclass("idx_rental_order_mutation_command_due")).isNotNull();
    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, orderId))
        .isEqualTo("DRAFT");
    jdbc.update(
        """
        insert into rental_order_mutation_command(
          id,order_id,operation,state,step,target_unit_id,expected_order_version,
          actor_subject_id,actor_role,idempotency_key,request_sha256,warehouse_id,
          release_units_idempotency_key,release_equipment_idempotency_key,
          equipment_release_required,intent_json,next_attempt_at,created_at,updated_at)
        values (?,?,'CANCEL_ORDER','PENDING','RELEASE_UNITS',null,0,?,'WAREHOUSE_MANAGER',
          ?,?,?,?, ?,true,'{}',clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        orderId,
        subjectId,
        UUID.randomUUID(),
        "c".repeat(64),
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into rental_order_mutation_command(
                      id,order_id,operation,state,step,target_unit_id,expected_order_version,
                      actor_subject_id,actor_role,idempotency_key,request_sha256,warehouse_id,
                      release_units_idempotency_key,release_equipment_idempotency_key,
                      equipment_release_required,intent_json,next_attempt_at,created_at,updated_at)
                    values (?,?,'REMOVE_UNIT','PENDING','RELEASE_UNITS',?,0,?,
                      'WAREHOUSE_MANAGER',?,?,?, ?,?,true,'{}',clock_timestamp(),
                      clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    orderId,
                    UUID.randomUUID(),
                    subjectId,
                    UUID.randomUUID(),
                    "d".repeat(64),
                    warehouseId,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertJpaValidationStarts();
  }

  @Test
  void v85GeneralizesPublishedRecoveryWithoutLosingExistingRescheduleIntents() {
    Flyway beforeV85 = configuration(MIGRATIONS).target("84").load();
    assertThat(beforeV85.migrate().migrationsExecuted).isPositive();
    UUID existingId = UUID.randomUUID();
    UUID existingSourcePlanId = UUID.randomUUID();
    UUID existingOrderId = UUID.randomUUID();
    UUID existingBookingId = UUID.randomUUID();
    UUID existingCustomerId = UUID.randomUUID();
    jdbc.update(
        """
        insert into planning_published_reschedule_saga(
          id,version,order_id,booking_id,customer_subject_id,source_plan_id,
          expected_source_plan_version,replacement_plan_version,source_plan_warehouse_id,
          source_plan_date,removed_document_id,removed_external_task_id,request_sha256,
          request_json,state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?,0,?,?,?,?,1,2,?,date '2026-09-03',?,?,?,'{}','PENDING',0,
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        existingId,
        existingOrderId,
        existingBookingId,
        existingCustomerId,
        existingSourcePlanId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64));

    Flyway upgraded = configuration(MIGRATIONS).target("85").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                "select operation,booking_id,customer_subject_id from "
                    + "planning_published_reschedule_saga where id=?",
                existingId))
        .containsEntry("operation", "RESCHEDULE")
        .containsEntry("booking_id", existingBookingId)
        .containsEntry("customer_subject_id", existingCustomerId);
    UUID cancellationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into planning_published_reschedule_saga(
          id,version,order_id,booking_id,customer_subject_id,operation,source_plan_id,
          expected_source_plan_version,replacement_plan_version,source_plan_warehouse_id,
          source_plan_date,removed_document_id,removed_external_task_id,request_sha256,
          request_json,state,attempt_count,next_attempt_at,created_at,updated_at)
        values (?,0,?,null,null,'CANCELLATION',?,4,5,?,date '2026-09-04',?,?,?,'{}',
          'PENDING',0,clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        cancellationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        "b".repeat(64));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into planning_published_reschedule_saga(
                      id,version,order_id,booking_id,customer_subject_id,operation,source_plan_id,
                      expected_source_plan_version,replacement_plan_version,
                      source_plan_warehouse_id,source_plan_date,removed_document_id,
                      removed_external_task_id,request_sha256,request_json,state,attempt_count,
                      next_attempt_at,created_at,updated_at)
                    values (?,0,?,null,null,'CANCELLATION',?,1,3,?,date '2026-09-03',
                      ?,?,?,'{}','PENDING',0,clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    existingSourcePlanId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    "c".repeat(64)))
        .hasMessageContaining("uk_planning_published_recovery_active_source");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update planning_published_reschedule_saga set booking_id=null where id=?",
                    existingId))
        .hasMessageContaining("ck_planning_published_recovery_customer_context");
    assertJpaValidationStarts();
  }

  @Test
  void v91BackfillsRentalShipmentsAndFencesCustomerDeliveryPurpose() {
    Flyway beforeV91 = configuration(MIGRATIONS).target("90").load();
    assertThat(beforeV91.migrate().migrationsExecuted).isPositive();
    UUID shipmentId = UUID.randomUUID();
    UUID returnId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,party_snapshot,driver_snapshot,
          requested_by_subject_id,correlation_id,created_at,updated_at)
        values (?,0,'SHIPMENT','DRAFT',?,'Клиент','Водитель',?,?,clock_timestamp(),clock_timestamp()),
               (?,0,'RETURN','DRAFT',?,null,null,?,?,clock_timestamp(),clock_timestamp())
        """,
        shipmentId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        returnId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID());

    Flyway upgraded = configuration(MIGRATIONS).target("91").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select customer_delivery_purpose from logistics_document where id=?",
                String.class,
                shipmentId))
        .isEqualTo("RENTAL_DELIVERY");
    assertThat(
            jdbc.queryForObject(
                "select customer_delivery_purpose from logistics_document where id=?",
                String.class,
                returnId))
        .isNull();
    jdbc.update(
        "update logistics_document set customer_delivery_purpose='SALE_DELIVERY' where id=?",
        shipmentId);
    jdbc.update(
        "update logistics_document set customer_delivery_purpose='CUSTOMER_RELOCATION' where id=?",
        shipmentId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update logistics_document set customer_delivery_purpose='RENTAL_DELIVERY' where id=?",
                    returnId))
        .hasMessageContaining("ck_logistics_document_customer_delivery_purpose");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update logistics_document set customer_delivery_purpose='UNKNOWN' where id=?",
                    shipmentId))
        .hasMessageContaining("ck_logistics_document_customer_delivery_purpose");
    assertJpaValidationStarts();
  }

  @Test
  void v93RemovesPlatformCompanyOwnershipAndKeepsGlobalLogisticsFences() {
    Flyway beforeV93 = configuration(MIGRATIONS).target("92").load();
    assertThat(beforeV93.migrate().migrationsExecuted).isPositive();

    UUID bootstrapCompany = UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48");
    UUID companyClientId = UUID.randomUUID();
    UUID nullableLegacyClientId = UUID.randomUUID();
    jdbc.execute("alter table order_client alter column company_id drop not null");
    insertV92OrderClient(companyClientId, bootstrapCompany, "V93 installation client");
    // An interrupted historical ownership backfill can leave a nullable row; it is not a company.
    insertV92OrderClient(nullableLegacyClientId, null, "V93 nullable legacy client");

    Flyway upgraded = configuration(MIGRATIONS).target("93").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_client where id in (?, ?)",
                Long.class,
                companyClientId,
                nullableLegacyClientId))
        .isEqualTo(2L);

    assertThat(
            jdbc.queryForList(
                """
                select table_name
                from information_schema.columns
                where table_schema='public'
                  and column_name='company_id'
                  and table_name in (
                    'order_client', 'rental_order', 'rental_order_command_receipt',
                    'rental_order_mutation_command', 'customer_booking_mutation',
                    'rental_inquiry', 'rental_inquiry_search_attempt',
                    'rental_inquiry_selection_receipt', 'client_presentation',
                    'client_presentation_item', 'presentation_booking', 'rental_inquiry_outbox',
                    'customer_profile', 'customer_rental_session', 'customer_delivery_slot',
                    'customer_cabin_acceptance', 'customer_cabin_problem',
                    'customer_cabin_problem_action'
                  )
                order by table_name
                """,
                String.class))
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='customer_profile'
                  and column_name='company_name'
                """,
                Integer.class))
        .isOne();
    assertThat(constraintDefinition("order_client", "uk_order_client_creator_idempotency"))
        .contains("created_by_subject_id", "creation_idempotency_key")
        .doesNotContain("company_id");
    assertThat(constraintDefinition("rental_order", "fk_rental_order_client"))
        .contains("FOREIGN KEY (client_id)", "REFERENCES order_client(id)")
        .doesNotContain("company_id");
    assertThat(constraintDefinition("rental_inquiry", "uk_rental_inquiry_creation_key"))
        .contains("manager_id", "creation_idempotency_key")
        .doesNotContain("company_id");
    assertThat(constraintDefinition("customer_booking_mutation", "uk_customer_booking_mutation_subject_key"))
        .contains("customer_subject_id", "idempotency_key")
        .doesNotContain("company_id");
    assertThat(constraintDefinition("customer_cabin_problem_action", "fk_customer_cabin_problem_action_problem"))
        .contains("FOREIGN KEY (problem_id)", "REFERENCES customer_cabin_problem(id)")
        .doesNotContain("company_id");
    assertThat(
            jdbc.queryForList(
                """
                select indexdef
                from pg_indexes
                where schemaname='public' and indexdef ilike '%company_id%'
                """,
                String.class))
        .isEmpty();
    assertThat(
            jdbc.queryForList(
                """
                select trigger_name
                from information_schema.triggers
                where event_object_schema='public'
                  and trigger_name like '%company_change'
                """,
                String.class))
        .isEmpty();
    assertJpaValidationStarts();
  }

  @Test
  void v93RejectsMultiCompanyOwnershipBeforeSchemaChanges() {
    Flyway beforeV93 = configuration(MIGRATIONS).target("92").load();
    assertThat(beforeV93.migrate().migrationsExecuted).isPositive();

    insertV92OrderClient(
        UUID.randomUUID(),
        UUID.fromString("ae0d6f97-f0c5-576a-9ea7-1ddcc1a03b48"),
        "V93 first installation client");
    insertV92OrderClient(
        UUID.randomUUID(),
        UUID.fromString("be5a11c2-98ce-5ae5-85b2-21e5991e282a"),
        "V93 second installation client");

    Flyway upgraded = configuration(MIGRATIONS).target("93").load();

    assertThatThrownBy(upgraded::migrate)
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("Cannot collapse platform company ownership for a multi-company logistics database");

    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from information_schema.columns
                where table_schema='public'
                  and table_name='order_client'
                  and column_name='company_id'
                """,
                Integer.class))
        .isOne();
    assertThat(constraintDefinition("order_client", "uk_order_client_creator_idempotency"))
        .contains("company_id");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='93' and success",
                Integer.class))
        .isZero();
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

  private void insertV92OrderClient(UUID id, UUID companyId, String name) {
    UUID managerId = UUID.randomUUID();
    String phone =
        "+7%010d".formatted(Math.floorMod(id.getLeastSignificantBits(), 10_000_000_000L));
    jdbc.update(
        """
        insert into order_client(
          id,company_id,version,client_type,display_name,normalized_name,created_by_subject_id,
          phone,normalized_phone,contact_person,responsible_manager_id,
          responsible_manager_display_name,creation_idempotency_key,creation_request_sha256,
          created_at,updated_at)
        values (
          ?, ?, 0, 'LEGAL_ENTITY',
          ?, ?, ?, ?, ?, ?, ?, 'V93 manager', ?, ?,
          clock_timestamp(), clock_timestamp())
        """,
        id,
        companyId,
        name,
        name.toLowerCase(java.util.Locale.ROOT),
        managerId,
        phone,
        phone,
        "V93 contact",
        managerId,
        UUID.randomUUID(),
        "a".repeat(64));
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
    UUID warehouseId = UUID.randomUUID();
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
        insert into rental_order(
          id,version,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,warehouse_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,0,?,'DRAFT',?,?,'Migration manager',?,'Migration manager',
          'RENTAL_MANAGER',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        orderId,
        "ORD-%019d".formatted(orderId.getMostSignificantBits() & Long.MAX_VALUE),
        clientId,
        managerSubjectId,
        managerSubjectId,
        warehouseId,
        UUID.randomUUID(),
        "b".repeat(64));
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
        warehouseId,
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

  /** Inserts a current pre-V73 group while deliberately omitting the new worker-content column. */
  private void insertV72GroupedShipmentTask(UUID taskId, UUID warehouseId) {
    jdbc.update(
        """
        insert into driver_logistics_task(
          id,version,warehouse_id,cabin_id,source_type,source_id,task_kind,planning_mode,
          scheduled_date,fixed_date_lower_bound,priority,movement_comment,client_snapshot,
          trip_number,unit_number,driver_queue_definition_id,driver_audience_mode,
          planned_driver_worker_id,planned_driver_name_snapshot,external_task_id,state,
          cover_applied,repair_place_effect_applied,created_by_subject_id,idempotency_key,
          request_sha256,retry_count,next_attempt_at,created_at,updated_at)
        values (?,0,?,?,'LOGISTICS_DOCUMENT',?,'SHIPMENT','FIXED_DATE',current_date,
          current_date,3,'Клиент: V72. Бытовка: БТ-72','V72',1,'1 бытовка',?,
          'ASSIGNED_DRIVER',?,'Водитель V72',?,'REGISTERING',false,true,?,?,?,0,
          clock_timestamp(),clock_timestamp(),clock_timestamp())
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
        "7".repeat(64));
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
