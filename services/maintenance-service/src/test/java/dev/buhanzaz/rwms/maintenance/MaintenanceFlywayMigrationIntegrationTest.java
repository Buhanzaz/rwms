package dev.buhanzaz.rwms.maintenance;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
class MaintenanceFlywayMigrationIntegrationTest {
  private static final String MIGRATIONS = "classpath:db/migration";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc = new JdbcTemplate(new DriverManagerDataSource(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()));
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void globalSettingsPreserveMatchingWarehouseValuesAndKeepSourceRows() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("50")
        .load()
        .migrate();
    for (int index = 0; index < 2; index++) {
      UUID warehouse = UUID.randomUUID();
      jdbc.update(
          "insert into estimate_creation_window_settings (warehouse_id, days, version, created_at,"
              + " updated_at) values (?, 14, 3, now(), now())",
          warehouse);
      jdbc.update(
          "insert into repair_complexity_settings (warehouse_id, light_boundary_minutes,"
              + " medium_boundary_minutes, complex_boundary_minutes, version, created_at,"
              + " updated_at) values (?, 120, 300, 600, 4, now(), now())",
          warehouse);
    }
    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isOne();
    assertThat(
            jdbc.queryForObject(
                "select days from global_estimate_creation_window_settings", Integer.class))
        .isEqualTo(14);
    assertThat(
            jdbc.queryForMap(
                "select light_boundary_minutes, medium_boundary_minutes, complex_boundary_minutes"
                    + " from global_repair_complexity_settings"))
        .containsEntry("light_boundary_minutes", 120)
        .containsEntry("medium_boundary_minutes", 300)
        .containsEntry("complex_boundary_minutes", 600);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from estimate_creation_window_settings where version=3",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from repair_complexity_settings where version=4", Integer.class))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "insert into global_estimate_creation_window_settings values (?, 0, 7, now(),"
                        + " now())",
                    UUID.randomUUID()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void globalSettingsRejectConflictingWarehouseValuesWithoutChangingThem() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("50")
        .load()
        .migrate();
    jdbc.update(
        "insert into estimate_creation_window_settings (warehouse_id, days, version, created_at,"
            + " updated_at) values (?, 7, 0, now(), now()), (?, 14, 0, now(), now())",
        UUID.randomUUID(),
        UUID.randomUUID());
    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("warehouse values differ");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from estimate_creation_window_settings", Integer.class))
        .isEqualTo(2);
    assertThat(tableNames())
        .doesNotContain(
            "global_estimate_creation_window_settings", "global_repair_complexity_settings");
  }

  @Test
  void cleanInstallIsRepeatSafeAndContainsTheAuthoritativeMaintenanceSchema() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(51);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames()).contains(
        "catalog_version", "catalog_node", "catalog_link", "maintenance_estimate",
        "estimate_revision", "estimate_line", "estimate_plan_stage", "maintenance_repair",
        "repair_stage", "maintenance_media_reference", "domain_event", "event_stream_head",
        "aggregate_snapshot", "projection_checkpoint", "outbox_event", "inbox_message",
        "consumer_aggregate_checkpoint", "version_gap_quarantine", "sanitized_dead_letter",
        "maintenance_inbound_replay_message", "maintenance_inbound_correlation",
        "rental_item_fact_projection", "operation_lease_fact_projection",
        "maintenance_idempotency_record", "integration_reconciliation",
        "inventory_repair_source_operation", "inventory_repair_source",
        "inventory_publication_source_operation", "inventory_publication_source",
        "inventory_publication_successor", "inventory_publication_prestart_replacement",
        "inventory_authoritative_outcome", "inventory_authoritative_outcome_receipt",
        "inventory_authoritative_outcome_watermark", "inventory_authoritative_outcome_target",
        "logistics_return_shortage", "repair_capacity_settings",
        "estimate_creation_window_settings",
            "global_estimate_creation_window_settings",
            "global_repair_complexity_settings", "repair_task_evidence",
        "repair_complexity_colors", "repair_complexity_settings", "repair_place_allocation",
        "property_disposition_decision", "property_disposition_contents_snapshot_line",
        "property_disposition_processing_attempt", "property_disposition_processing_claim",
        "warehouse_operation_mark_outbox", "warehouse_operation_mark_recovery_audit",
        "furniture_equipment_link_intent", "furniture_equipment_link_review_audit",
        "warehouse_readiness_fence");
    assertThat(columnCount("maintenance_estimate", "rental_item_version_snapshot")).isOne();
    assertThat(columnCount("maintenance_repair", "rental_item_version_snapshot")).isOne();
    assertThat(columnCount("maintenance_repair", "dispatch_date")).isOne();
    assertThat(columnCount("maintenance_repair", "priority")).isOne();
    assertThat(columns("maintenance_repair")).contains(
        "movement_to_repair", "movement_to_shipment", "transfer_state", "transfer_document_id",
        "transfer_line_id", "transfer_target_warehouse_id",
        "logistics_planning_mode", "logistics_scheduled_date", "furniture_accounting_mode",
        "historical_shipment_document_id", "historical_shipment_closure_state");
    assertThat(constraintDefinition(
            "maintenance_repair", "ck_maintenance_repair_furniture_accounting_mode"))
        .contains("TRACKED_CABIN_CONTENTS", "UNACCOUNTED_CABIN_CONTENTS");
    assertThat(
            constraintDefinition(
                "maintenance_repair",
                "ck_maintenance_repair_logistics_planning"))
        .contains(
            "AUTO",
            "FIXED_DATE",
            "movement_to_repair",
            "logistics_scheduled_date IS NULL",
            "logistics_scheduled_date IS NOT NULL");
    assertThat(constraintDefinition("repair_stage", "ck_repair_stage_kind"))
        .contains("REPAIR_WORK")
        .doesNotContain("MOVE_TO_REPAIR", "MOVE_FROM_REPAIR");
    assertThat(constraintDefinition("estimate_plan_stage", "ck_estimate_plan_kind"))
        .contains("REPAIR_WORK")
        .doesNotContain("MOVE_TO_REPAIR", "MOVE_FROM_REPAIR");
    assertThat(columnCount("maintenance_estimate", "cover_media_id")).isOne();
    assertThat(columns("maintenance_estimate")).contains(
        "priority", "movement_to_repair", "movement_scheduled_date", "inventory_superseded_at");
    assertThat(constraintDefinition("maintenance_estimate", "ck_maintenance_estimate_priority"))
        .contains("priority", "1", "5");
    assertThat(columns("inventory_publication_source")).contains(
        "inventory_id", "final_plan_version", "finding_id", "plan_snapshot", "media_snapshot",
        "strategy", "selected_target_kind", "superseded_target_kind", "target_kind",
        "estimate_id", "repair_id", "idempotency_key", "publication_outcome",
        "predecessor_repair_id", "delta_snapshot");
    assertThat(constraintDefinition(
        "inventory_publication_source", "ck_inventory_publication_source_strategy"))
        .contains("CREATE", "REPLACE", "MERGE");
    assertThat(constraintDefinition(
        "inventory_publication_source", "ck_inventory_publication_source_target"))
        .contains("ESTIMATE", "REPAIR", "SUCCESSOR", "MATCHED");
    assertThat(columns("inventory_publication_successor")).containsExactlyInAnyOrder(
        "inventory_id", "final_plan_version", "finding_id", "version", "predecessor_repair_id",
        "successor_repair_id", "state", "terminal_fact", "terminal_fact_event_id",
        "terminal_fact_occurred_at", "created_at", "released_at", "updated_at");
    assertThat(constraintDefinition(
        "inventory_publication_successor", "ck_inventory_publication_successor_state"))
        .contains("WAITING_PREDECESSOR", "RELEASED", "TASK_BOARD_COMPLETION", "REPAIR_ACCEPTANCE");
    assertThat(columns("inventory_publication_prestart_replacement")).containsExactlyInAnyOrder(
        "inventory_id", "final_plan_version", "finding_id", "version", "request_sha256",
        "request_idempotency_key", "request_snapshot", "warehouse_id", "asset_id",
        "predecessor_repair_id", "predecessor_mode", "driver_kind", "task_external_id",
        "task_expected_version", "task_guard_required", "remote_attempt_count", "lease_id", "lease_version",
        "fencing_token", "lease_owner_type", "lease_owner_id", "phase", "driver_outcome",
        "task_outcome", "occupancy_reassignment_required", "repair_place_allocation_id",
        "repair_place_allocation_version", "successor_repair_id", "compensated_at",
        "lease_released_at", "applied_at", "created_at", "updated_at");
    assertThat(constraintDefinition(
        "inventory_publication_prestart_replacement",
        "ck_inventory_publication_prestart_replacement_phase"))
        .contains("PREPARED", "COMPENSATED", "SUCCESSOR_CREATED", "LEASE_RELEASED", "APPLIED");
    assertThat(indexDefinition("uk_inventory_publication_prestart_replacement_active_repair"))
        .contains("UNIQUE INDEX", "predecessor_repair_id", "phase", "APPLIED");
    assertThat(columns("inventory_authoritative_outcome")).containsExactlyInAnyOrder(
        "inventory_id", "final_plan_version", "finding_id", "version", "request_sha256",
        "request_snapshot", "warehouse_id", "asset_id", "inventory_completed_at",
        "final_plan_sha256", "finding_revision", "authoritative_asset_version",
        "desired_status", "outcome_kind", "phase", "target_repair_id", "response_snapshot",
        "created_at", "updated_at", "applied_at");
    assertThat(columns("inventory_authoritative_outcome_receipt")).containsExactlyInAnyOrder(
        "idempotency_key", "inventory_id", "final_plan_version", "finding_id",
        "request_sha256", "response_snapshot", "created_at", "completed_at");
    assertThat(columns("inventory_authoritative_outcome_watermark")).containsExactlyInAnyOrder(
        "asset_id", "version", "warehouse_id", "inventory_completed_at", "inventory_id",
        "final_plan_version", "finding_id", "final_plan_sha256", "finding_revision",
        "desired_status", "request_sha256", "updated_at");
    assertThat(columns("inventory_authoritative_outcome_target")).containsExactlyInAnyOrder(
        "id", "version", "inventory_id", "final_plan_version", "finding_id", "target_kind",
        "target_id", "task_external_id", "task_expected_version", "task_attempt_count",
        "task_outcome", "driver_kind", "driver_attempt_count", "driver_outcome",
        "driver_task_id", "repair_place_allocation_id", "repair_place_allocation_version",
        "lease_id", "lease_version", "fencing_token", "lease_owner_type", "lease_owner_id",
        "lease_attempt_count", "lease_released", "local_superseded", "created_at", "updated_at");
    assertThat(constraintDefinition(
        "inventory_authoritative_outcome", "ck_inventory_authoritative_outcome_phase"))
        .contains("PREPARED", "EFFECTS_SETTLED", "TARGET_CREATED", "APPLIED");
    assertThat(indexDefinition("uk_inventory_authoritative_outcome_target"))
        .contains("UNIQUE INDEX", "inventory_id", "final_plan_version", "finding_id",
            "target_kind", "target_id");
    assertThat(columnCount("maintenance_repair", "cover_media_id")).isOne();
    assertThat(columns("repair_capacity_settings")).containsExactlyInAnyOrder(
        "warehouse_id", "version", "repair_place_count", "automatic_refill_delay_minutes",
        "created_at", "updated_at");
    assertThat(columnDefault("repair_capacity_settings", "repair_place_count")).contains("6");
    assertThat(columnDefault(
        "repair_capacity_settings", "automatic_refill_delay_minutes")).contains("5");
    assertThat(constraintDefinition(
        "repair_capacity_settings", "ck_repair_capacity_settings_count"))
        .contains("repair_place_count > 0");
    assertThat(constraintDefinition(
        "repair_capacity_settings", "ck_repair_capacity_settings_refill_delay"))
        .contains("automatic_refill_delay_minutes", "1", "1440");
    assertThat(columns("estimate_creation_window_settings")).containsExactlyInAnyOrder(
        "warehouse_id", "version", "days", "created_at", "updated_at");
    assertThat(columnDefault("estimate_creation_window_settings", "days")).contains("7");
    assertThat(constraintDefinition(
        "estimate_creation_window_settings", "ck_estimate_creation_window_settings_days"))
        .contains("days", "1", "3650");
    assertThat(columns("repair_complexity_settings")).contains(
        "warehouse_id", "version", "light_boundary_minutes", "medium_boundary_minutes",
        "complex_boundary_minutes", "imported_from_task_board_version", "imported_at");
    assertThat(columns("repair_place_allocation")).contains(
        "id", "version", "warehouse_id", "repair_id", "state");
    assertThat(indexDefinition("uk_repair_place_allocation_active_repair"))
        .contains("UNIQUE INDEX", "repair_id", "WHERE", "state", "RELEASED");
    assertThat(columns("maintenance_repair")).contains("reclassification_state");
    assertThat(constraintDefinition("maintenance_repair", "ck_repair_task_generation"))
        .contains("NOT_REQUIRED");
    assertThat(constraintDefinition("repair_stage", "ck_repair_stage_generation"))
        .contains("NOT_REQUIRED");
    assertThat(columnCount("repair_stage", "external_queue_entry_id")).isOne();
    assertThat(columns("catalog_node")).contains(
        "active", "parent_node_id", "furniture_category", "furniture_equipment_id",
        "furniture_equipment_name", "unit", "include_in_estimate",
        "common_item",
        "show_in_main_menu", "routing_queue_id", "routing_queue_name",
        "routing_queue_type", "comment", "canvas_x", "canvas_y", "display_color",
        "forces_capital_repair", "characteristic_id", "characteristic_name")
        .doesNotContain(
            "media_references", "photo_required", "code", "furniture_equipment_code",
            "routing_queue_code", "routing_queue_kind", "opaque_references");
    assertThat(columns("catalog_link")).contains("source_anchor", "target_anchor");
    assertThat(constraintDefinition("catalog_node", "ck_catalog_node_display_color"))
        .contains("#[0-9A-F]{6}");
    assertThat(constraintDefinition("catalog_node", "ck_catalog_node_material_comment"))
        .contains("node_type", "MATERIAL", "comment IS NULL");
    assertThat(constraintDefinition("estimate_line", "ck_estimate_line_material_comment"))
        .contains("line_type", "MATERIAL", "comment IS NULL");
    assertThat(constraintDefinition("repair_stage", "ck_repair_stage_material_comment"))
        .contains("material_lines", "comment");
    assertThat(constraintDefinition("catalog_link", "ck_catalog_link_anchors"))
        .contains("source_anchor", "target_anchor", "TOP", "BOTTOM");
    assertThat(constraintDefinition(
        "maintenance_media_reference", "ck_maintenance_media_owner"))
        .contains("MAINTENANCE_ESTIMATE", "MAINTENANCE_REPAIR", "MAINTENANCE_ACCEPTANCE")
        .doesNotContain("MAINTENANCE_CATALOG_NODE");
    assertThat(constraintDefinition(
        "integration_reconciliation", "ck_reconciliation_media_identity"))
        .doesNotContain("MAINTENANCE_CATALOG_NODE");
    assertThat(columns("estimate_line")).contains(
        "catalog_snapshot", "comment", "media_references", "unit");
    assertThat(columnType("estimate_line", "unit")).isEqualTo("character varying(32)");
    assertThat(columns("estimate_plan_stage")).contains(
        "routing_queue_id", "routing_queue_name", "routing_queue_type", "included_line_ids",
        "primary_line_id", "group_comment", "task_deadline");
    assertThat(columns("repair_stage")).contains(
        "routing_queue_id", "routing_queue_name", "routing_queue_type", "work_lines",
        "material_lines", "primary_line_id", "group_comment", "task_deadline");
    assertThat(columns("repair_task_evidence")).containsExactlyInAnyOrder(
        "evidence_id", "aggregate_version", "repair_id", "repair_stage_id", "entry_id",
        "task_id", "route_index", "worker_id", "worker_group_id", "media_id",
        "media_generation", "captured_at", "recorded_at", "evidence_state", "updated_at");
    assertThat(constraintDefinition(
        "repair_task_evidence", "ck_repair_task_evidence_state"))
        .contains("READY", "REVIEW_REQUIRED");
    assertThat(constraintDefinition(
        "repair_task_evidence", "ck_repair_task_evidence_generation"))
        .contains("media_generation >= 1");
    assertThat(constraintDefinition("inbox_message", "ck_maintenance_inbox_topic"))
        .contains("rwms.task-board.task-evidence.v1");
    assertThat(
            constraintDefinition(
                "maintenance_inbound_replay_message", "ck_maintenance_replay_topic"))
        .contains("rwms.task-board.task-evidence.v1");
    assertThat(columns("integration_reconciliation")).contains(
        "media_owner_type", "media_owner_id", "media_warehouse_id",
        "media_owner_revision", "media_aggregate_version", "media_source_id",
        "media_source_version", "media_proof_event_id", "media_active",
        "catalog_version_id", "catalog_node_id", "catalog_queue_id",
        "catalog_external_reference_id");
    assertThat(constraintDefinition(
        "integration_reconciliation", "ck_reconciliation_dependency"))
        .contains("MEDIA", "LOGISTICS");
    assertThat(constraintDefinition(
        "integration_reconciliation", "ck_reconciliation_catalog_identity"))
        .contains(
            "REGISTER_CATALOG_POSITION",
            "DELETE_CATALOG_POSITION",
            "catalog_version_id IS NOT NULL",
            "catalog_node_id IS NOT NULL",
            "catalog_queue_id IS NOT NULL");
    assertThat(columns("warehouse_operation_mark_outbox")).contains(
        "claim_token",
        "claim_until",
        "recovery_version",
        "recovered_by_subject_id",
        "recovery_reason",
        "recovered_at");
    assertThat(constraintDefinition(
            "warehouse_operation_mark_outbox",
            "ck_warehouse_operation_mark_outbox_claim"))
        .contains("IN_FLIGHT", "claim_token IS NOT NULL", "claim_until IS NOT NULL");
    assertThat(columns("warehouse_operation_mark_recovery_audit")).containsExactlyInAnyOrder(
        "id",
        "warehouse_id",
        "operation_id",
        "recovery_version",
        "reviewed_by_subject_id",
        "reason",
        "reviewed_at");
    assertThat(triggerDefinition("warehouse_operation_mark_recovery_audit_immutable"))
        .contains("reject_warehouse_operation_mark_recovery_audit_mutation");
    assertThat(columns("furniture_equipment_link_intent")).contains(
        "node_id", "warehouse_id", "source_catalog_version_id",
        "source_catalog_expected_version", "requested_name", "state", "equipment_id",
        "equipment_name", "observed_equipment_id", "observed_equipment_name",
        "requested_equipment_version", "requested_maximum_per_cabin", "equipment_version",
        "maximum_per_cabin", "observed_equipment_version", "observed_maximum_per_cabin",
        "attempt_count", "claim_token", "claim_until", "review_version");
    assertThat(
            constraintDefinition(
                "furniture_equipment_link_intent", "ck_furniture_link_maximum_per_cabin"))
        .contains("requested_maximum_per_cabin", "maximum_per_cabin > 0");
    assertThat(triggerDefinition("furniture_equipment_link_review_audit_immutable"))
        .contains("reject_furniture_link_review_audit_mutation");
    assertThat(columns("warehouse_readiness_fence")).containsExactlyInAnyOrder(
        "id", "warehouse_id", "warehouse_version", "state", "release_reason", "fenced_at",
        "sealed_at", "released_at", "updated_at");
    assertThat(indexDefinition("uq_warehouse_readiness_fence_active"))
        .contains("UNIQUE INDEX", "warehouse_id", "FENCED", "SEALED");
    assertThat(triggerDefinition("maintenance_estimate_readiness_guard"))
        .contains("guard_maintenance_warehouse_readiness_fence");
    assertThat(triggerDefinition("repair_place_allocation_readiness_guard"))
        .contains("guard_maintenance_warehouse_readiness_fence");
    assertThat(triggerDefinition("property_disposition_claim_readiness_guard"))
        .contains("guard_maintenance_disposition_claim_readiness");
    assertThat(triggerDefinition("integration_reconciliation_readiness_guard"))
        .contains("guard_maintenance_reconciliation_readiness");
    assertThat(triggerExists("catalog_version_readiness_guard")).isFalse();
    assertThat(triggerExists("furniture_equipment_link_readiness_guard")).isFalse();
    assertThat(columns("property_disposition_decision")).contains(
        "maintenance_custody_claim_id", "maintenance_custody_version");
    assertThat(columnNullable("property_disposition_decision", "expected_asset_version"))
        .isEqualTo("YES");
    assertThat(constraintDefinition(
            "property_disposition_decision", "ck_property_disposition_decision_asset_kind"))
        .contains(
            "maintenance_custody_claim_id",
            "maintenance_custody_version",
            "expected_asset_version IS NOT NULL",
            "expected_source_balance_version IS NULL",
            "UNACCOUNTED");
    assertThat(constraintDefinition(
            "property_disposition_decision", "ck_property_disposition_decision_source"))
        .contains("UNACCOUNTED");
    assertThat(constraintDefinition(
            "property_disposition_decision", "ck_property_disposition_decision_effect_state"))
        .contains("NOT_REQUIRED", "UNACCOUNTED");
    assertThat(indexDefinition(
            "uq_property_disposition_decision_maintenance_custody_claim"))
        .contains("UNIQUE INDEX", "maintenance_custody_claim_id");
    assertThat(indexDefinition("uk_property_disposition_unaccounted_repair_equipment"))
        .contains("UNIQUE INDEX", "source_repair_id", "asset_id", "UNACCOUNTED");
    assertThat(columns("property_disposition_processing_claim")).contains("failure_count");
    assertThat(constraintDefinition(
            "property_disposition_processing_claim",
            "ck_property_disposition_processing_claim_failure_count"))
        .contains("failure_count >= 0");
    assertThat(indexDefinition("idx_reconciliation_catalog_operation_history"))
        .contains("catalog_version_id", "catalog_node_id", "operation_type", "created_at")
        .doesNotContain("UNIQUE INDEX");
    assertThat(indexDefinition("uk_catalog_version_active_global"))
        .contains("UNIQUE INDEX", "catalog_version", "WHERE", "state", "ACTIVE")
        .doesNotContain("warehouse_id");
    assertThat(columns("inventory_repair_source")).contains(
        "inventory_id", "finding_id", "source_revision", "catalog_version_id",
        "plan_request_sha256", "plan_fingerprint", "plan_snapshot", "media_snapshot",
        "source_fingerprint", "rental_item_id", "rental_item_version_snapshot", "repair_id");
    assertThat(columns("inventory_repair_source_operation")).contains("source_revision");
    assertThat(constraintDefinition(
            "inventory_repair_source_operation", "inventory_repair_source_operation_pkey"))
        .contains("inventory_id", "finding_id", "source_revision");
    assertThat(constraintDefinition("inventory_repair_source", "uk_inventory_repair_source"))
        .contains("inventory_id", "finding_id", "source_revision");
    assertThat(constraintDefinition(
        "inventory_repair_source", "ck_inventory_repair_source_version"))
        .contains("source_revision >= 1");
    for (String hashColumn : List.of(
        "plan_request_sha256", "plan_fingerprint", "source_fingerprint")) {
      assertThat(columnType("inventory_repair_source", hashColumn))
          .isEqualTo("character varying(64)");
    }
    assertThat(constraintDefinition(
        "inventory_repair_source", "ck_inventory_repair_source_hashes"))
        .contains(
            "plan_request_sha256", "plan_fingerprint", "source_fingerprint", "{64}");
    assertThat(triggerDefinition("trg_inventory_repair_source_immutable"))
        .contains("enforce_inventory_repair_source_immutability");
    assertThat(columns("logistics_return_shortage")).contains(
        "return_id", "line_id", "rental_item_id", "rental_item_version_snapshot",
        "estimate_id", "source_sha256", "snapshot_sha256", "shortage_snapshot", "arrived_at");
    assertThat(constraintDefinition(
        "logistics_return_shortage", "ck_logistics_return_shortage_estimate_required"))
        .contains("estimate_id IS NOT NULL", "NOT VALID");
    assertThat(constraintDefinition(
        "logistics_return_shortage", "fk_logistics_return_shortage_estimate"))
        .contains("FOREIGN KEY (estimate_id)", "maintenance_estimate(id)");
    assertThat(constraintDefinition(
        "logistics_return_shortage", "uk_logistics_return_shortage_estimate"))
        .contains("UNIQUE (estimate_id)");
    assertThat(constraintDefinition(
        "logistics_return_shortage", "ck_logistics_return_shortage_snapshot"))
        .contains("jsonb_typeof");
    assertThat(jdbc.queryForObject("select count(*) from catalog_version", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from maintenance_repair", Integer.class)).isZero();
  }

  @Test
  void v50DetachesGlobalCatalogAuditFromWarehouseReadinessWithoutWeakeningOwnerChecks() {
    Flyway throughV49 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("49")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(throughV49.migrate().migrationsExecuted).isEqualTo(49);

    UUID auditWarehouseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID catalogId = UUID.randomUUID();
    insertCatalogVersion(catalogId, auditWarehouseId, "a".repeat(64));
    jdbc.update(
        """
        insert into warehouse_readiness_fence(
          id,warehouse_id,warehouse_version,state,fenced_at,updated_at)
        values (?,?,7,'FENCED',clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        auditWarehouseId);
    assertThatThrownBy(() -> jdbc.update(
            "update catalog_version set node_count=node_count+1 where id=?", catalogId))
        .hasMessageContaining("readiness fence");

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(2);
    upgraded.validate();
    assertThat(triggerExists("catalog_version_readiness_guard")).isFalse();
    assertThat(triggerExists("furniture_equipment_link_readiness_guard")).isFalse();
    assertThat(jdbc.update(
        "update catalog_version set node_count=node_count+1 where id=?", catalogId)).isOne();

    UUID reconciliationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into integration_reconciliation(
          id,repair_id,dependency_type,operation_type,idempotency_key,state,attempt_count,
          next_attempt_at,response_snapshot,review_version,created_at,updated_at,
          catalog_version_id,catalog_node_id,catalog_queue_id,catalog_external_reference_id)
        values (?,null,'TASK_BOARD','REGISTER_CATALOG_POSITION',?,'PENDING',0,
          clock_timestamp(),'{}'::jsonb,0,clock_timestamp(),clock_timestamp(),?,?,?,?)
        """,
        reconciliationId,
        UUID.randomUUID(),
        catalogId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "catalog:" + catalogId + ":position");
    assertThat(jdbc.queryForObject(
        """
        select count(*)
          from maintenance_reconciliation_warehouse_ids(
            (select to_jsonb(value) from integration_reconciliation value where id=?))
        """,
        Integer.class,
        reconciliationId)).isZero();
    assertThatThrownBy(() -> jdbc.update(
            """
            insert into integration_reconciliation(
              id,dependency_type,operation_type,idempotency_key,state,attempt_count,
              next_attempt_at,response_snapshot,review_version,created_at,updated_at)
            values (?,'ASSET','OWNERLESS_TEST',?,'PENDING',0,clock_timestamp(),'{}',0,
              clock_timestamp(),clock_timestamp())
            """,
            UUID.randomUUID(),
            UUID.randomUUID()))
        .hasMessageContaining("no warehouse owner");
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionFortySevenAdmitsTaskEvidenceIntoTheDurableInbox() {
    Flyway throughV46 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("46")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load();
    assertThat(throughV46.migrate().migrationsExecuted).isEqualTo(46);
    assertThat(constraintDefinition("inbox_message", "ck_maintenance_inbox_topic"))
        .doesNotContain("rwms.task-board.task-evidence.v1");
    assertThat(
            constraintDefinition(
                "maintenance_inbound_replay_message", "ck_maintenance_replay_topic"))
        .doesNotContain("rwms.task-board.task-evidence.v1");

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(5);
    upgraded.validate();
    assertThat(
            constraintDefinition(
                "maintenance_inbound_replay_message", "ck_maintenance_replay_topic"))
        .contains("rwms.task-board.task-evidence.v1");
    UUID eventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,source_topic,aggregate_type,aggregate_id,aggregate_version,
          event_type,payload_sha256,envelope_body,status,attempt_count,received_at)
        values (
          'maintenance-service-inbox-v1',?,'rwms.task-board.task-evidence.v1',
          'TASK_EVIDENCE',?,0,'task-board.task-evidence.ready.v1',?,
          '{}'::jsonb,'RECEIVED',0,clock_timestamp())
        """,
        eventId,
        UUID.randomUUID().toString(),
        "a".repeat(64));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from inbox_message where event_id=?", Integer.class, eventId))
        .isOne();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
  }

  @Test
  void appliedV34UpgradesAppendOnlyThroughFurnitureLinksAndReadinessFenceMigrations() {
    Flyway throughV34 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("34")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(throughV34.migrate().migrationsExecuted).isEqualTo(34);
    throughV34.validate();

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(17);
    upgraded.validate();
    assertThat(constraintDefinition("event_stream_head", "ck_maintenance_stream_type"))
        .contains("PROPERTY_DISPOSITION");
    assertThat(triggerDefinition("property_disposition_processing_attempt_immutable"))
        .contains("reject_property_disposition_processing_attempt_mutation");
    assertThat(tableNames()).contains(
        "furniture_equipment_link_intent", "furniture_equipment_link_review_audit",
        "warehouse_readiness_fence");
    assertThat(triggerDefinition("furniture_equipment_link_review_audit_immutable"))
        .contains("reject_furniture_link_review_audit_mutation");
  }

  @Test
  void appliedV42BackfillsOneCanonicalLegacyFurnitureLinkAcrossCatalogVersions() {
    Flyway throughV42 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("42")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(throughV42.migrate().migrationsExecuted).isEqualTo(42);

    UUID warehouseId = UUID.randomUUID();
    UUID supersededCatalogId = UUID.randomUUID();
    UUID activeCatalogId = UUID.randomUUID();
    UUID furnitureMaterialId = UUID.randomUUID();
    UUID ordinaryMaterialId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    insertCatalogVersion(supersededCatalogId, warehouseId, "e".repeat(64));
    insertCatalogVersion(activeCatalogId, warehouseId, "f".repeat(64));
    jdbc.update(
        """
        update catalog_version
           set state='SUPERSEDED',version=6,node_count=1,
               activated_at=clock_timestamp()-interval '1 day',updated_at=clock_timestamp()-interval '1 day'
         where id=?
        """,
        supersededCatalogId);
    jdbc.update(
        """
        update catalog_version
           set state='ACTIVE',version=7,node_count=2,
               activated_at=clock_timestamp(),updated_at=clock_timestamp()
         where id=?
        """,
        activeCatalogId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,furniture_category,
          furniture_equipment_id,furniture_equipment_name,duration_minutes,
          include_in_estimate,common_item,show_in_main_menu,forces_capital_repair)
        values (?,?,?,'MATERIAL','Chair material',true,false,?,'Chair',0,true,false,false,false)
        """,
        UUID.randomUUID(),
        furnitureMaterialId,
        supersededCatalogId,
        equipmentId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,furniture_category,
          furniture_equipment_id,furniture_equipment_name,duration_minutes,
          include_in_estimate,common_item,show_in_main_menu,forces_capital_repair)
        values (?,?,?,'MATERIAL','Chair material',true,false,?,'Chair',0,true,false,false,false)
        """,
        UUID.randomUUID(),
        furnitureMaterialId,
        activeCatalogId,
        equipmentId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,furniture_category,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          forces_capital_repair)
        values (?,?,?,'MATERIAL','Plywood',true,false,0,true,false,false,false)
        """,
        UUID.randomUUID(),
        ordinaryMaterialId,
        activeCatalogId);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(9);
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select warehouse_id,source_catalog_version_id,source_catalog_expected_version,
                       requested_name,state,attempt_count,equipment_id,equipment_name,
                       requested_equipment_version,requested_maximum_per_cabin
                from furniture_equipment_link_intent where node_id=?
                """,
                furnitureMaterialId))
        .containsEntry("warehouse_id", warehouseId)
        .containsEntry("source_catalog_version_id", activeCatalogId)
        .containsEntry("source_catalog_expected_version", 7L)
        .containsEntry("requested_name", "Chair")
        .containsEntry("state", "PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("equipment_id", null)
        .containsEntry("equipment_name", null)
        .containsEntry("requested_equipment_version", null)
        .containsEntry("requested_maximum_per_cabin", null);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from furniture_equipment_link_intent where node_id=?",
                Integer.class,
                ordinaryMaterialId))
        .isZero();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
  }

  @Test
  void v25RemovesDuplicateActiveCatalogPositionsAndKeepsTheEventStreamCanonical() {
    Flyway beforeV25 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("24")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV25.migrate().migrationsExecuted).isEqualTo(24);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID parentId = UUID.randomUUID();
    UUID canonicalWorkId = UUID.randomUUID();
    UUID duplicateWorkId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID parentRowId = UUID.randomUUID();
    UUID canonicalWorkRowId = UUID.randomUUID();
    UUID duplicateWorkRowId = UUID.randomUUID();
    UUID materialRowId = UUID.randomUUID();
    UUID canonicalFollowUpId = UUID.randomUUID();
    UUID duplicateFollowUpId = UUID.randomUUID();
    UUID duplicateDependencyId = UUID.randomUUID();
    UUID streamEventId = UUID.randomUUID();
    String state = """
        {
          "id":"%s","version":0,"warehouseId":"%s","lifecycle":"ACTIVE",
          "sourceSha256":"%s","nodeCount":4,"linkCount":3,
          "validationReport":{"valid":true,"errorCount":0,"warningCount":0,
            "contentSha256":"%s","nodeCount":4,"linkCount":3,
            "materialCount":1,"dependencyAcyclic":true,
            "reportSha256":"%s"},
          "activatedAt":"2026-07-31T00:00:00Z",
          "createdAt":"2026-07-31T00:00:00Z",
          "updatedAt":"2026-07-31T00:00:00Z",
          "nodes":[
            {"id":"%s","nodeType":"SUBCATEGORY","name":"External","active":true,
              "parentNodeId":null,"unit":null,"priceMinor":null,"durationMinutes":0,
              "includeInEstimate":false,"commonItem":false,"showInMainMenu":false,
              "canvasX":0,"canvasY":0,"displayColor":null,"forcesCapitalRepair":false,
              "characteristic":null,"routing":null,"comment":null},
            {"id":"%s","nodeType":"WORK","name":"Duplicate work","active":true,
              "parentNodeId":"%s","unit":"piece","priceMinor":10000,"durationMinutes":15,
              "includeInEstimate":true,"commonItem":false,"showInMainMenu":true,
              "canvasX":0,"canvasY":0,"displayColor":null,"forcesCapitalRepair":false,
              "characteristic":null,"routing":null,"comment":null},
            {"id":"%s","nodeType":"WORK","name":"Duplicate work","active":true,
              "parentNodeId":"%s","unit":"piece","priceMinor":10000,"durationMinutes":15,
              "includeInEstimate":true,"commonItem":false,"showInMainMenu":true,
              "canvasX":10,"canvasY":10,"displayColor":null,"forcesCapitalRepair":false,
              "characteristic":null,"routing":null,"comment":null},
            {"id":"%s","nodeType":"MATERIAL","name":"Sheet","active":true,
              "parentNodeId":null,"unit":"piece","priceMinor":5000,"durationMinutes":0,
              "includeInEstimate":true,"commonItem":false,"showInMainMenu":false,
              "canvasX":0,"canvasY":0,"displayColor":null,"forcesCapitalRepair":false,
              "characteristic":null,"routing":null,"comment":null}
          ],
          "links":[
            {"id":"%s","sourceNodeId":"%s","targetNodeId":"%s",
              "linkType":"FOLLOW_UP","sourceAnchor":"BOTTOM","targetAnchor":"TOP","sortOrder":0},
            {"id":"%s","sourceNodeId":"%s","targetNodeId":"%s",
              "linkType":"FOLLOW_UP","sourceAnchor":"BOTTOM","targetAnchor":"TOP","sortOrder":1},
            {"id":"%s","sourceNodeId":"%s","targetNodeId":"%s",
              "linkType":"DEPENDENCY","sourceAnchor":"BOTTOM","targetAnchor":"TOP","sortOrder":0}
          ]
        }
        """.formatted(
        catalogId,
        warehouseId,
        "f".repeat(64),
        "0".repeat(64),
        "0".repeat(64),
        parentId,
        canonicalWorkId,
        parentId,
        duplicateWorkId,
        parentId,
        materialId,
        canonicalFollowUpId,
        parentId,
        canonicalWorkId,
        duplicateFollowUpId,
        parentId,
        duplicateWorkId,
        duplicateDependencyId,
        duplicateWorkId,
        materialId);

    jdbc.update(
        """
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,activated_at,created_at,updated_at)
        values (?,0,?,'ACTIVE',?,4,3,?,'2026-07-31T00:00:00Z',
          '2026-07-31T00:00:00Z','2026-07-31T00:00:00Z')
        """,
        catalogId,
        warehouseId,
        "f".repeat(64),
        "{\"valid\":true,\"errorCount\":0,\"warningCount\":0,"
            + "\"contentSha256\":\"" + "0".repeat(64) + "\",\"nodeCount\":4,"
            + "\"linkCount\":3,\"materialCount\":1,\"dependencyAcyclic\":true,"
            + "\"reportSha256\":\"" + "0".repeat(64) + "\"}");
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          unit,price_minor,duration_minutes,include_in_estimate,common_item,
          show_in_main_menu,furniture_category,canvas_x,canvas_y)
        values (?,?,?,'SUBCATEGORY','External',true,null,null,null,0,false,false,
          false,false,0,0)
        """,
        parentRowId,
        parentId,
        catalogId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          unit,price_minor,duration_minutes,include_in_estimate,common_item,
          show_in_main_menu,furniture_category,canvas_x,canvas_y)
        values (?,?,?,'WORK','Duplicate work',true,?,'piece',10000,15,true,false,
          true,false,0,0)
        """,
        canonicalWorkRowId,
        canonicalWorkId,
        catalogId,
        parentId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          unit,price_minor,duration_minutes,include_in_estimate,common_item,
          show_in_main_menu,furniture_category,canvas_x,canvas_y)
        values (?,?,?,'WORK','Duplicate work',true,?,'piece',10000,15,true,false,
          true,false,10,10)
        """,
        duplicateWorkRowId,
        duplicateWorkId,
        catalogId,
        parentId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          unit,price_minor,duration_minutes,include_in_estimate,common_item,
          show_in_main_menu,furniture_category,canvas_x,canvas_y)
        values (?,?,?,'MATERIAL','Sheet',true,null,'piece',5000,0,true,false,
          false,false,0,0)
        """,
        materialRowId,
        materialId,
        catalogId);
    jdbc.update(
        """
        insert into catalog_link(
          row_id,link_id,catalog_version_id,source_node_id,target_node_id,
          link_type,source_anchor,target_anchor,sort_order)
        values
          (?,?,?,?,?,'FOLLOW_UP','BOTTOM','TOP',0),
          (?,?,?,?,?,'FOLLOW_UP','BOTTOM','TOP',1),
          (?,?,?,?,?,'DEPENDENCY','BOTTOM','TOP',0)
        """,
        UUID.randomUUID(), canonicalFollowUpId, catalogId, parentId, canonicalWorkId,
        UUID.randomUUID(), duplicateFollowUpId, catalogId, parentId, duplicateWorkId,
        UUID.randomUUID(), duplicateDependencyId, catalogId, duplicateWorkId, materialId);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,0,?,clock_timestamp())
        """,
        catalogId.toString(),
        streamEventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'CATALOG_VERSION',?,0,'maintenance.catalog-version.imported.v1',1,
          clock_timestamp(),clock_timestamp(),?,?::jsonb,?,false)
        """,
        streamEventId,
        catalogId.toString(),
        UUID.randomUUID(),
        "{\"model\":\"maintenance-full-state-v1\",\"event\":{},\"state\":" + state + "}",
        "0".repeat(64));
    jdbc.update(
        """
        insert into aggregate_snapshot(
          aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
        values ('CATALOG_VERSION',?,0,?::jsonb,?,clock_timestamp())
        """,
        catalogId.toString(),
        state,
        "0".repeat(64));
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at,
          published_at)
        values (?,'CATALOG_VERSION',?,0,'maintenance.catalog-version.imported.v1',
          'rwms.maintenance.catalog-version.v1','{}'::jsonb,?,'PUBLISHED',0,
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        streamEventId,
        catalogId.toString(),
        "0".repeat(64));

    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isEqualTo(27);

    assertThat(jdbc.queryForObject(
        "select count(*) from catalog_node where catalog_version_id=? and node_type='WORK'",
        Integer.class,
        catalogId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from catalog_link where catalog_version_id=? and link_type='FOLLOW_UP'",
        Integer.class,
        catalogId)).isOne();
    UUID survivingWorkId = jdbc.queryForObject(
        """
        select node_id from catalog_node
        where catalog_version_id=? and node_type='WORK'
        """,
        UUID.class,
        catalogId);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from catalog_link
        where catalog_version_id=? and source_node_id=? and target_node_id=? and link_type='DEPENDENCY'
        """,
        Integer.class,
        catalogId,
        survivingWorkId,
        materialId)).isOne();
    assertThat(jdbc.queryForObject(
        "select version from catalog_version where id=?", Integer.class, catalogId)).isEqualTo(1);
    assertThat(jdbc.queryForObject(
        """
        select current_version from event_stream_head
        where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """,
        Long.class,
        catalogId.toString())).isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from domain_event
        where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """,
        Integer.class,
        catalogId.toString())).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        """
        select aggregate_version from projection_checkpoint
        where projection_name='maintenance-live-v1'
          and aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """,
        Long.class,
        catalogId.toString())).isEqualTo(1L);
  }

  @Test
  void v18UpgradesCatalogIdentityToWarehouseScopedUuidSnapshots() {
    Flyway beforeV18 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("17")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV18.migrate().migrationsExecuted).isEqualTo(17);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "a".repeat(64));
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,routing_queue_id,
          routing_queue_code,routing_queue_kind,opaque_references)
        values (?,?,?,'WORK_A','WORK','Repair work',true,'piece',10000,15,true,false,true,?,
          'REPAIR','REPAIR','[{"referenceId":"legacy","code":"obsolete"}]')
        """,
        UUID.randomUUID(), nodeId, catalogId, queueId);

    Flyway upgraded = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("18")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(columns("catalog_node"))
        .contains("routing_queue_name", "routing_queue_type")
        .doesNotContain(
            "code", "furniture_equipment_code", "routing_queue_code", "routing_queue_kind",
            "opaque_references");
    assertThat(jdbc.queryForMap(
        "select routing_queue_name,routing_queue_type from catalog_node where node_id=?", nodeId))
        .containsEntry("routing_queue_name", "Очередь недоступна")
        .containsEntry("routing_queue_type", "REPAIR");

    jdbc.update("update catalog_version set state='ACTIVE' where id=?", catalogId);
    UUID secondWarehouseId = UUID.randomUUID();
    insertCatalogVersion(UUID.randomUUID(), secondWarehouseId, "b".repeat(64));
    jdbc.update(
        "update catalog_version set state='ACTIVE' where warehouse_id=?", secondWarehouseId);
    assertThatThrownBy(() -> {
      UUID duplicate = UUID.randomUUID();
      insertCatalogVersion(duplicate, warehouseId, "c".repeat(64));
      jdbc.update("update catalog_version set state='ACTIVE' where id=?", duplicate);
    }).hasMessageContaining("uk_catalog_version_active");
  }

  @Test
  void v19ThroughV23UpgradeWithoutRebuildingExistingAggregates() {
    Flyway beforeV19 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("18")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV19.migrate().migrationsExecuted).isEqualTo(18);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "d".repeat(64));

    Flyway upgraded =
        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("23")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load();
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(5);
    upgraded.validate();
    assertThat(columns("catalog_node")).contains("display_color");
    assertThat(constraintDefinition("catalog_node", "ck_catalog_node_display_color"))
        .contains("#[0-9A-F]{6}");
    assertThat(columnCount("maintenance_estimate", "cover_media_id")).isOne();
    assertThat(columnCount("maintenance_repair", "cover_media_id")).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from catalog_version where id=?", Integer.class, catalogId)).isOne();
  }

  @Test
  void v23ExtendsExistingRepairSnapshotsAndKeepsTheirCanonicalHashesAligned() {
    Flyway beforeV23 =
        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("22")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load();
    assertThat(beforeV23.migrate().migrationsExecuted).isEqualTo(22);

    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    insertRepair(
        repairId,
        warehouseId,
        null,
        "DIRECT_REPAIR",
        "PRIMARY",
        null,
        null);
    String state =
        """
        {"id":"%s","version":0,"warehouseId":"%s","executionState":"DRAFT"}
        """
            .formatted(repairId, warehouseId);
    String payload =
        """
        {"model":"maintenance-full-state-v1","event":%s,"state":%s}
        """
            .formatted(state, state);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('REPAIR',?,0,?,clock_timestamp())
        """,
        repairId.toString(),
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'REPAIR',?,0,'maintenance.repair.created.v1',1,clock_timestamp(),
          clock_timestamp(),?,?::jsonb,?,false)
        """,
        eventId,
        repairId.toString(),
        UUID.randomUUID(),
        payload,
        "0".repeat(64));
    jdbc.update(
        """
        insert into aggregate_snapshot(
          aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
        values ('REPAIR',?,0,?::jsonb,?,clock_timestamp())
        """,
        repairId.toString(),
        state,
        "0".repeat(64));
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,
          projection_sha256,updated_at)
        values ('maintenance-live-v1','REPAIR',?,0,?,clock_timestamp())
        """,
        repairId.toString(),
        "0".repeat(64));

    Flyway upgraded =
        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("23")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select reclassification_state from maintenance_repair where id=?",
                String.class,
                repairId))
        .isEqualTo("STABLE");
    assertThat(
            jdbc.queryForObject(
                """
                select payload #>> '{state,reclassificationState}' = 'STABLE'
                  and payload_sha256 =
                    encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
                from domain_event where event_id=?
                """,
                Boolean.class,
                eventId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select state ->> 'reclassificationState' = 'STABLE'
                  and state_sha256 =
                    encode(sha256(convert_to(state::text, 'UTF8')), 'hex')
                from aggregate_snapshot
                where aggregate_type='REPAIR' and aggregate_id=? and aggregate_version=0
                """,
                Boolean.class,
                repairId.toString()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select checkpoint.projection_sha256 = snapshot.state_sha256
                from projection_checkpoint checkpoint
                join aggregate_snapshot snapshot
                  on snapshot.aggregate_type=checkpoint.aggregate_type
                 and snapshot.aggregate_id=checkpoint.aggregate_id
                 and snapshot.aggregate_version=checkpoint.aggregate_version
                where checkpoint.projection_name='maintenance-live-v1'
                  and checkpoint.aggregate_type='REPAIR'
                  and checkpoint.aggregate_id=?
                """,
                Boolean.class,
                repairId.toString()))
        .isTrue();
  }

  @Test
  void v24ThroughV27CanonicalizeRepairPlanningAndRealignHistoricalStateHashes() {
    Flyway beforeV24 =
        Flyway.configure()
            .dataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("23")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .validateMigrationNaming(true)
            .outOfOrder(false)
            .load();
    assertThat(beforeV24.migrate().migrationsExecuted)
        .isEqualTo(23);

    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    insertRepair(
        repairId,
        warehouseId,
        null,
        "DIRECT_REPAIR",
        "PRIMARY",
        null,
        null);
    String state =
        """
        {"id":"%s","version":0,"warehouseId":"%s","reclassificationState":"STABLE"}
        """
            .formatted(repairId, warehouseId);
    String payload =
        """
        {"model":"maintenance-full-state-v1","event":%s,"state":%s}
        """
            .formatted(state, state);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('REPAIR',?,0,?,clock_timestamp())
        """,
        repairId.toString(),
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'REPAIR',?,0,'maintenance.repair.created.v1',1,clock_timestamp(),
          clock_timestamp(),?,?::jsonb,?,false)
        """,
        eventId,
        repairId.toString(),
        UUID.randomUUID(),
        payload,
        "0".repeat(64));
    jdbc.update(
        """
        insert into aggregate_snapshot(
          aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
        values ('REPAIR',?,0,?::jsonb,?,clock_timestamp())
        """,
        repairId.toString(),
        state,
        "0".repeat(64));
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,
          projection_sha256,updated_at)
        values ('maintenance-live-v1','REPAIR',?,0,?,clock_timestamp())
        """,
        repairId.toString(),
        "0".repeat(64));

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(28);
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select logistics_planning_mode,logistics_scheduled_date
                from maintenance_repair where id=?
                """,
                repairId))
        .containsEntry("logistics_planning_mode", null)
        .containsEntry("logistics_scheduled_date", null);
    assertThat(
            jdbc.queryForObject(
                """
                select payload #> '{state,movementToRepair}' = 'false'::jsonb
                  and payload #> '{state,logisticsPlanningMode}' = 'null'::jsonb
                  and payload #> '{state,logisticsScheduledDate}' = 'null'::jsonb
                  and payload_sha256 =
                    encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
                from domain_event where event_id=?
                """,
                Boolean.class,
                eventId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select state -> 'movementToRepair' = 'false'::jsonb
                  and state -> 'logisticsPlanningMode' = 'null'::jsonb
                  and state -> 'logisticsScheduledDate' = 'null'::jsonb
                  and state_sha256 =
                    encode(sha256(convert_to(state::text, 'UTF8')), 'hex')
                from aggregate_snapshot
                where aggregate_type='REPAIR'
                  and aggregate_id=?
                  and aggregate_version=0
                """,
                Boolean.class,
                repairId.toString()))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select checkpoint.projection_sha256 = snapshot.state_sha256
                from projection_checkpoint checkpoint
                join aggregate_snapshot snapshot
                  on snapshot.aggregate_type=checkpoint.aggregate_type
                 and snapshot.aggregate_id=checkpoint.aggregate_id
                 and snapshot.aggregate_version=checkpoint.aggregate_version
                where checkpoint.projection_name='maintenance-live-v1'
                  and checkpoint.aggregate_type='REPAIR'
                  and checkpoint.aggregate_id=?
                """,
                Boolean.class,
                repairId.toString()))
        .isTrue();
  }

  @Test
  void v27ConvertsCompletedMovementStagesWithoutLosingCanonicalHistoryOrExternalTaskArchive() {
    Flyway beforeV27 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("26")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV27.migrate().migrationsExecuted).isEqualTo(26);

    UUID warehouseId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID moveStageId = UUID.randomUUID();
    UUID workStageId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    insertRepair(repairId, warehouseId, null, "DIRECT_REPAIR", "PRIMARY", null, null);
    insertLegacyRepairStage(
        repairId,
        moveStageId,
        0,
        "MOVE_TO_REPAIR",
        "DONE",
        UUID.randomUUID());
    insertLegacyRepairStage(
        repairId,
        workStageId,
        1,
        "REPAIR_WORK",
        "PLANNED",
        null);

    String state = """
        {"id":"%s","version":0,"warehouseId":"%s",
         "movementToShipment":false,"logisticsPlanningMode":"AUTO",
         "logisticsScheduledDate":null,"stages":[
           {"id":"%s","stageNo":0,"kind":"MOVE_TO_REPAIR"},
           {"id":"%s","stageNo":1,"kind":"REPAIR_WORK"}
         ]}
        """.formatted(repairId, warehouseId, moveStageId, workStageId);
    String payload = """
        {"model":"maintenance-full-state-v1","event":{},"state":%s}
        """.formatted(state);
    String envelope = """
        {"payload":{"stages":[
          {"stageId":"%s","kind":"MOVE_TO_REPAIR","order":0},
          {"stageId":"%s","kind":"REPAIR_WORK","order":1}
        ]}}
        """.formatted(moveStageId, workStageId);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('REPAIR',?,0,?,clock_timestamp())
        """,
        repairId.toString(), eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'REPAIR',?,0,'maintenance.repair.created.v1',1,clock_timestamp(),
          clock_timestamp(),?,?::jsonb,?,false)
        """,
        eventId, repairId.toString(), UUID.randomUUID(), payload, "0".repeat(64));
    jdbc.update(
        """
        insert into aggregate_snapshot(
          aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
        values ('REPAIR',?,0,?::jsonb,?,clock_timestamp())
        """,
        repairId.toString(), state, "0".repeat(64));
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,
          projection_sha256,updated_at)
        values ('maintenance-live-v1','REPAIR',?,0,?,clock_timestamp())
        """,
        repairId.toString(), "0".repeat(64));
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'REPAIR',?,0,'maintenance.repair.created.v1',
          'rwms.maintenance.repair.v1',?::jsonb,?,'PENDING',0,clock_timestamp(),
          clock_timestamp())
        """,
        eventId, repairId.toString(), envelope, "0".repeat(64));

    UUID catalogId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "d".repeat(64));
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);
    insertLegacyEstimateStage(estimateId, UUID.randomUUID(), 0, "MOVE_TO_REPAIR");
    UUID estimateWorkId = UUID.randomUUID();
    insertLegacyEstimateStage(estimateId, estimateWorkId, 1, "REPAIR_WORK");
    insertLegacyEstimateStage(estimateId, UUID.randomUUID(), 2, "MOVE_FROM_REPAIR");

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(25);
    upgraded.validate();

    assertThat(jdbc.queryForMap(
        """
        select movement_to_repair,movement_to_shipment,logistics_planning_mode,
               logistics_scheduled_date
        from maintenance_repair where id=?
        """, repairId))
        .containsEntry("movement_to_repair", true)
        .containsEntry("movement_to_shipment", false)
        .containsEntry("logistics_planning_mode", "AUTO")
        .containsEntry("logistics_scheduled_date", null);
    assertThat(jdbc.queryForList(
        "select stage_id,stage_no,stage_kind from repair_stage where repair_id=? order by stage_no",
        repairId))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("stage_id", workStageId)
            .containsEntry("stage_no", 1)
            .containsEntry("stage_kind", "REPAIR_WORK"));
    assertThat(jdbc.queryForList(
                "select stage_id,stage_no,stage_kind from estimate_plan_stage where estimate_id=?"
                    + " order by stage_no",
        estimateId))
        .singleElement()
        .satisfies(row -> assertThat(row)
            .containsEntry("stage_id", estimateWorkId)
            .containsEntry("stage_no", 0)
            .containsEntry("stage_kind", "REPAIR_WORK"));
    assertThat(jdbc.queryForObject(
        """
        select payload #> '{state,movementToRepair}' = 'true'::jsonb
          and payload #> '{state,logisticsPlanningMode}' = '"AUTO"'::jsonb
          and jsonb_array_length(payload #> '{state,stages}') = 1
          and payload #>> '{state,stages,0,id}' = ?
          and payload_sha256 = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
        from domain_event where event_id=?
        """, Boolean.class, workStageId.toString(), eventId)).isTrue();
    assertThat(jdbc.queryForObject(
        """
        select state #> '{movementToRepair}' = 'true'::jsonb
          and state #> '{logisticsPlanningMode}' = '"AUTO"'::jsonb
          and jsonb_array_length(state -> 'stages') = 1
          and state #>> '{stages,0,id}' = ?
          and state_sha256 = encode(sha256(convert_to(state::text, 'UTF8')), 'hex')
        from aggregate_snapshot
        where aggregate_type='REPAIR' and aggregate_id=? and aggregate_version=0
        """, Boolean.class, workStageId.toString(), repairId.toString())).isTrue();
    assertThat(jdbc.queryForObject(
        """
        select jsonb_array_length(envelope_body #> '{payload,stages}') = 1
          and envelope_body #>> '{payload,stages,0,kind}' = 'REPAIR_WORK'
          and envelope_sha256 = encode(sha256(convert_to(envelope_body::text, 'UTF8')), 'hex')
        from outbox_event where event_id=?
        """, Boolean.class, eventId)).isTrue();
    assertThat(jdbc.queryForObject(
        """
        select checkpoint.projection_sha256 = snapshot.state_sha256
        from projection_checkpoint checkpoint
        join aggregate_snapshot snapshot
          on snapshot.aggregate_type=checkpoint.aggregate_type
         and snapshot.aggregate_id=checkpoint.aggregate_id
         and snapshot.aggregate_version=checkpoint.aggregate_version
        where checkpoint.projection_name='maintenance-live-v1'
          and checkpoint.aggregate_type='REPAIR'
          and checkpoint.aggregate_id=?
        """, Boolean.class, repairId.toString())).isTrue();
  }

  @Test
  void v27RejectsAnyActiveLegacyMovementStage() {
    Flyway beforeV27 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("26")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    beforeV27.migrate();
    UUID repairId = UUID.randomUUID();
    insertRepair(repairId, UUID.randomUUID(), null, "DIRECT_REPAIR", "PRIMARY", null, null);
    insertLegacyRepairStage(
        repairId, UUID.randomUUID(), 0, "MOVE_TO_REPAIR", "QUEUED", null);

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("cannot cut over active legacy movement stages");
  }

  @Test
  void v27RejectsLegacyMovementStagesReferencedByEvidence() {
    Flyway beforeV27 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("26")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    beforeV27.migrate();
    UUID repairId = UUID.randomUUID();
    UUID stageId = UUID.randomUUID();
    insertRepair(repairId, UUID.randomUUID(), null, "DIRECT_REPAIR", "PRIMARY", null, null);
    insertLegacyRepairStage(repairId, stageId, 0, "MOVE_FROM_REPAIR", "DONE", null);
    jdbc.update(
        """
        insert into repair_task_evidence(
          evidence_id,aggregate_version,repair_id,repair_stage_id,entry_id,task_id,route_index,
          worker_id,worker_group_id,media_id,media_generation,captured_at,recorded_at,
          evidence_state,updated_at)
        values (?,0,?,?,?,?,0,?,?,?,1,clock_timestamp(),clock_timestamp(),'READY',clock_timestamp())
        """,
        UUID.randomUUID(), repairId, stageId, UUID.randomUUID(), UUID.randomUUID(),
        UUID.randomUUID(), null, UUID.randomUUID());

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("repair_task_evidence");
  }

  @Test
  void v28DeletesLegacyMaterialCommentsAndEnforcesTheCanonicalInvariant() {
    Flyway beforeV28 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("27")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV28.migrate().migrationsExecuted).isEqualTo(27);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID repairStageId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "e".repeat(64));
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,duration_minutes,
          include_in_estimate,common_item,show_in_main_menu,comment)
        values (?,?,?,'MATERIAL','Материал',true,0,true,false,false,?)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        catalogId,
        "устаревший комментарий");
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);
    jdbc.update(
        """
        insert into estimate_line(
          row_id,line_id,estimate_id,estimate_revision,line_no,line_type,title,unit,
          quantity,unit_price_minor,duration_minutes,comment,media_references)
        values (?,?,?,1,0,'MATERIAL','Материал','шт.',1,0,0,?,'[]')
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        estimateId,
        "устаревший комментарий");
    insertRepair(repairId, warehouseId, null, "DIRECT_REPAIR", "PRIMARY", null, null);
    insertLegacyRepairStage(
        repairId, repairStageId, 0, "REPAIR_WORK", "PLANNED", null);
    jdbc.update(
        """
        update repair_stage
        set material_lines='[{"id":"00000000-0000-4000-8000-000000000001",\
          "lineType":"MATERIAL","comment":"устаревший комментарий"}]'::jsonb
        where repair_id=? and stage_id=?
        """,
        repairId,
        repairStageId);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(24);
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select comment from catalog_node where catalog_version_id=?",
                String.class,
                catalogId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select comment from estimate_line where estimate_id=?",
                String.class,
                estimateId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select material_lines #>> '{0,comment}' from repair_stage where repair_id=?",
                String.class,
                repairId))
        .isNull();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update catalog_node set comment='нельзя' where catalog_version_id=?",
                    catalogId))
        .hasMessageContaining("ck_catalog_node_material_comment");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update estimate_line set comment='нельзя' where estimate_id=?",
                    estimateId))
        .hasMessageContaining("ck_estimate_line_material_comment");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update repair_stage
                    set material_lines=jsonb_set(
                      material_lines,'{0,comment}','"нельзя"'::jsonb,true)
                    where repair_id=?
                    """,
                    repairId))
        .hasMessageContaining("ck_repair_stage_material_comment");
  }

  @Test
  void v29AddsPublicationSourcesAndCanonicalizesHistoricalEstimateSnapshots() {
    Flyway beforeV29 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("28")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV29.migrate().migrationsExecuted).isEqualTo(28);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "f".repeat(64));
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);
    String state = """
        {"id":"%s","version":0,"warehouseId":"%s","state":"DRAFT"}
        """.formatted(estimateId, warehouseId);
    String payload = """
        {"model":"maintenance-full-state-v1","event":{},"state":%s}
        """.formatted(state);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('ESTIMATE',?,0,?,clock_timestamp())
        """,
        estimateId.toString(),
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'ESTIMATE',?,0,'maintenance.estimate.created.v1',1,clock_timestamp(),
          clock_timestamp(),?,?::jsonb,?,false)
        """,
        eventId,
        estimateId.toString(),
        UUID.randomUUID(),
        payload,
        "0".repeat(64));
    jdbc.update(
        """
        insert into aggregate_snapshot(
          aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
        values ('ESTIMATE',?,0,?::jsonb,?,clock_timestamp())
        """,
        estimateId.toString(),
        state,
        "0".repeat(64));
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,
          projection_sha256,updated_at)
        values ('maintenance-live-v1','ESTIMATE',?,0,?,clock_timestamp())
        """,
        estimateId.toString(),
        "0".repeat(64));

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(23);
    upgraded.validate();

    assertThat(jdbc.queryForMap(
        """
        select priority,movement_to_repair,movement_scheduled_date,inventory_superseded_at
          from maintenance_estimate where id=?
        """, estimateId))
        .containsEntry("priority", 3)
        .containsEntry("movement_to_repair", false)
        .containsEntry("movement_scheduled_date", null)
        .containsEntry("inventory_superseded_at", null);
    assertThat(jdbc.queryForObject(
        """
        select payload #>> '{state,priority}' = '3'
          and payload #> '{state,movementToRepair}' = 'false'::jsonb
          and payload #> '{state,movementScheduledDate}' = 'null'::jsonb
          and payload #> '{state,inventorySupersededAt}' = 'null'::jsonb
          and payload_sha256 = encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')
        from domain_event where event_id=?
        """, Boolean.class, eventId)).isTrue();
    assertThat(jdbc.queryForObject(
        """
        select state #>> '{priority}' = '3'
          and state #> '{movementToRepair}' = 'false'::jsonb
          and state #> '{movementScheduledDate}' = 'null'::jsonb
          and state #> '{inventorySupersededAt}' = 'null'::jsonb
          and state_sha256 = encode(sha256(convert_to(state::text, 'UTF8')), 'hex')
        from aggregate_snapshot
        where aggregate_type='ESTIMATE' and aggregate_id=? and aggregate_version=0
        """, Boolean.class, estimateId.toString())).isTrue();
    assertThat(jdbc.queryForObject(
        """
        select checkpoint.projection_sha256 = snapshot.state_sha256
        from projection_checkpoint checkpoint
        join aggregate_snapshot snapshot
          on snapshot.aggregate_type=checkpoint.aggregate_type
         and snapshot.aggregate_id=checkpoint.aggregate_id
         and snapshot.aggregate_version=checkpoint.aggregate_version
        where checkpoint.projection_name='maintenance-live-v1'
          and checkpoint.aggregate_type='ESTIMATE'
          and checkpoint.aggregate_id=?
        """, Boolean.class, estimateId.toString())).isTrue();
  }

  @Test
  void v20ThroughV27GloballyConsolidatesCatalogsAndCanonicalizesLegacySnapshots() {
    Flyway beforeV21 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("20")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .outOfOrder(false)
        .load();
    assertThat(beforeV21.migrate().migrationsExecuted).isEqualTo(20);

    UUID spbWarehouseId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID otherWarehouseId = UUID.randomUUID();
    UUID spbCatalogId = UUID.randomUUID();
    UUID otherCatalogId = UUID.randomUUID();
    UUID spbNodeId = UUID.randomUUID();
    UUID otherNodeId = UUID.randomUUID();
    UUID spbQueueId = UUID.randomUUID();
    UUID otherQueueId = UUID.randomUUID();
    insertCatalogVersion(spbCatalogId, spbWarehouseId, "a".repeat(64));
    jdbc.update("update catalog_version set state='ACTIVE',activated_at=clock_timestamp() where id=?",
        spbCatalogId);

    // V15 had already installed a global unique index.  This is a deliberately controlled
    // representation of the pre-cutover inconsistent production state which V21 must merge.
    jdbc.execute("drop index if exists uk_catalog_version_active_global");
    jdbc.execute("drop index if exists uk_catalog_version_active");
    insertCatalogVersion(otherCatalogId, otherWarehouseId, "b".repeat(64));
    jdbc.update("update catalog_version set state='ACTIVE',activated_at=clock_timestamp() where id=?",
        otherCatalogId);
    insertV20WorkNode(spbCatalogId, spbNodeId, spbQueueId, "SPB work");
    insertV20WorkNode(otherCatalogId, otherNodeId, otherQueueId, "Other work");

    UUID estimateId = UUID.randomUUID();
    insertEstimateWithRevision(estimateId, spbCatalogId, spbWarehouseId);
    jdbc.update(
        """
        insert into estimate_line(
          row_id,line_id,estimate_id,estimate_revision,line_no,catalog_node_id,line_type,title,
          unit,quantity,unit_price_minor,duration_minutes,catalog_snapshot,media_references)
        values (?,?,?,1,0,?,'WORK','Legacy work','   ',1,10000,0,?::jsonb,'[]')
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        estimateId,
        spbNodeId,
        legacyCatalogSnapshot(spbCatalogId, spbNodeId, spbQueueId));

    insertV20CatalogStreamArtifacts(spbCatalogId, spbNodeId, "ACTIVE");
    insertV20CatalogStreamArtifacts(otherCatalogId, otherNodeId, "ACTIVE");

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(31);
    upgraded.validate();

    assertThat(jdbc.queryForObject(
        "select id from catalog_version where state='ACTIVE'", UUID.class)).isEqualTo(spbCatalogId);
    assertThat(jdbc.queryForObject(
        "select state from catalog_version where id=?", String.class, otherCatalogId))
        .isEqualTo("SUPERSEDED");
    assertThat(jdbc.queryForObject("select count(*) from catalog_version", Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from catalog_node", Integer.class)).isEqualTo(2);
    assertThat(indexDefinition("uk_catalog_version_active_global"))
        .contains("UNIQUE INDEX", "WHERE", "state", "ACTIVE")
        .doesNotContain("warehouse_id");

    assertThat(jdbc.queryForMap(
        "select unit,duration_minutes,catalog_snapshot from estimate_line where estimate_id=?",
        estimateId))
        .containsEntry("unit", "piece")
        .containsEntry("duration_minutes", 60);
    assertThat(jdbc.queryForObject(
        """
        select jsonb_exists(catalog_snapshot, 'code')
          or not jsonb_exists(catalog_snapshot, 'forcesCapitalRepair')
          or catalog_snapshot #>> '{routing,queueName}' <> 'External works'
        from estimate_line where estimate_id=?
        """, Boolean.class, estimateId)).isFalse();

    assertThat(jdbc.queryForObject(
        """
        select count(*) from domain_event event
        join event_stream_head head
          on head.aggregate_type=event.aggregate_type and head.aggregate_id=event.aggregate_id
         and head.current_version=event.aggregate_version and head.last_event_id=event.event_id
        join aggregate_snapshot snapshot
          on snapshot.aggregate_type=event.aggregate_type and snapshot.aggregate_id=event.aggregate_id
         and snapshot.aggregate_version=event.aggregate_version
        join projection_checkpoint checkpoint
          on checkpoint.aggregate_type=event.aggregate_type and checkpoint.aggregate_id=event.aggregate_id
         and checkpoint.aggregate_version=event.aggregate_version
        join outbox_event outbox on outbox.event_id=event.event_id
        where event.aggregate_type='CATALOG_VERSION'
          and event.aggregate_id=?
          and event.event_type='maintenance.catalog-version.superseded.v1'
          and encode(sha256(convert_to(event.payload::text, 'UTF8')),'hex')=event.payload_sha256
          and encode(sha256(convert_to(snapshot.state::text, 'UTF8')),'hex')=snapshot.state_sha256
          and checkpoint.projection_sha256=snapshot.state_sha256
          and encode(sha256(convert_to(outbox.envelope_body::text, 'UTF8')),'hex')=outbox.envelope_sha256
        """, Integer.class, otherCatalogId.toString())).isOne();
    assertThat(jdbc.queryForObject(
                "select current_version from event_stream_head where"
                    + " aggregate_type='CATALOG_VERSION' and aggregate_id=?",
        Long.class,
        otherCatalogId.toString())).isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from domain_event
        where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """, Integer.class, spbCatalogId.toString())).isOne();
  }

  @Test
  void appliedMigrationChecksumDriftIsRejected(@TempDir Path directory) throws IOException {
    copyMigration(directory, "V1__maintenance_schema.sql");
    copyMigration(directory, "V2__inventory_source.sql");
    Path migration = copyMigration(directory, "V3__logistics_return_shortage.sql");
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(migration, Files.readString(migration)
        .replace("shortage_snapshot jsonb NOT NULL", "shortage_snapshot jsonb NULL"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void existingV15EstimateLinesBackfillTrimmedCatalogUnits(@TempDir Path directory)
      throws IOException {
    for (String migration : List.of(
        "V1__maintenance_schema.sql",
        "V2__inventory_source.sql",
        "V3__logistics_return_shortage.sql",
        "V4__catalog_furniture_equipment.sql",
        "V5__media_owner_proof_reconciliation.sql",
        "V6__catalog_routing_reconciliation.sql",
        "V7__correct_initial_media_owner_proof_version.sql",
        "V8__catalog_canvas_and_remove_catalog_media.sql",
        "V9__remove_catalog_photo_requirement.sql",
        "V10__repair_priority.sql",
        "V11__repair_capacity_settings.sql",
        "V12__repair_stage_content.sql",
        "V13__repair_task_evidence_projection.sql",
        "V14__allow_catalog_route_replacement.sql",
        "V15__global_catalog_scope.sql")) {
      copyMigration(directory, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    Flyway upgraded = flyway(location);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(15);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "4".repeat(64));
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);
    UUID trimmedLineId = UUID.randomUUID();
    UUID blankLineId = UUID.randomUUID();
    jdbc.update(
        """
        insert into estimate_line(
          row_id,line_id,estimate_id,estimate_revision,line_no,line_type,title,
          quantity,unit_price_minor,duration_minutes,catalog_snapshot,media_references)
        values (?,?,?,1,0,'WORK','Trimmed unit',1,0,0,?::jsonb,'[]'),
               (?,?,?,1,1,'MATERIAL','Blank unit',1,0,0,?::jsonb,'[]')
        """,
        UUID.randomUUID(),
        trimmedLineId,
        estimateId,
        "{\"unit\":\"  piece  \"}",
        UUID.randomUUID(),
        blankLineId,
        estimateId,
        "{\"unit\":\"   \"}");

    copyMigration(directory, "V16__estimate_line_unit.sql");
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(jdbc.queryForObject(
        "select unit from estimate_line where line_id=?", String.class, trimmedLineId))
        .isEqualTo("piece");
    assertThat(jdbc.queryForObject(
        "select unit from estimate_line where line_id=?", String.class, blankLineId))
        .isNull();
  }

  @Test
  void appliedInventorySourceV2ChecksumDriftIsRejected(@TempDir Path directory)
      throws IOException {
    copyMigration(directory, "V1__maintenance_schema.sql");
    Path migration = copyMigration(directory, "V2__inventory_source.sql");
    copyMigration(directory, "V3__logistics_return_shortage.sql");
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(migration, Files.readString(migration)
        .replace("inventory repair plan/source fields are immutable",
            "inventory repair source fields are immutable"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void existingV1SchemaUpgradesInPlaceToV6AndBackfillsFurnitureMediaAndActiveRouting(
      @TempDir Path directory)
      throws IOException {
    copyMigration(directory, "V1__maintenance_schema.sql");
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();

    copyMigration(directory, "V2__inventory_source.sql");
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();
    copyMigration(directory, "V3__logistics_return_shortage.sql");
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID furnitureId = UUID.randomUUID();
    UUID materialRowId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "6".repeat(64));
    jdbc.update("update catalog_version set state='ACTIVE',node_count=2 where id=?", catalogId);
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,duration_minutes,
          include_in_estimate,common_item,show_in_main_menu,photo_required,
          opaque_references,media_references)
        values (?,?,?,'FURNITURE','CATEGORY','Furniture',true,0,false,false,true,false,'[]','[]')
        """, UUID.randomUUID(), furnitureId, catalogId);
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,parent_node_id,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,photo_required,
          opaque_references,media_references)
        values (?,?,?,'CHAIR','MATERIAL','Chair',true,?,0,true,false,false,false,'[]','[]')
        """, materialRowId, materialId, catalogId, furnitureId);
    UUID estimateId = UUID.randomUUID();
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);
    jdbc.update("""
        insert into estimate_line(
          row_id,line_id,estimate_id,estimate_revision,line_no,catalog_node_id,line_type,title,
          quantity,unit_price_minor,duration_minutes,catalog_snapshot,media_references)
        values (?,?,?,1,0,?,'MATERIAL','Chair',1,10000,0,?::jsonb,'[]')
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        estimateId,
        materialId,
        """
        {"catalogVersionId":"%s","nodeId":"%s","code":"CHAIR","nodeType":"MATERIAL",
         "name":"Chair","unit":"piece","unitPrice":"100.00","durationMinutes":0,
         "routing":null}
        """.formatted(catalogId, materialId));
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update("""
        insert into maintenance_idempotency_record(
          subject_id,command_scope,idempotency_key,request_sha256,response_status,response_body,
          created_at,expires_at)
        values (?, 'estimate.create', ?, ?, 201, ?::jsonb,
          clock_timestamp(), clock_timestamp() + interval '1 day')
        """,
        subjectId,
        idempotencyKey,
        "7".repeat(64),
        """
        {"revisions":[{"lines":[{"catalogSnapshot":
          {"catalogVersionId":"%s","nodeId":"%s","code":"CHAIR","nodeType":"MATERIAL",
           "name":"Chair","unit":"piece","unitPrice":"100.00","durationMinutes":0,
           "routing":null}}]}]}
        """.formatted(catalogId, materialId));
    copyMigration(directory, "V4__catalog_furniture_equipment.sql");
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();
    assertThat(flyway(location).migrate().migrationsExecuted).isZero();
    assertThat(tableNames()).contains("inventory_repair_source", "logistics_return_shortage");
    assertThat(constraintDefinition("maintenance_repair", "ck_repair_origin"))
        .contains("INVENTORY");
    assertThat(jdbc.queryForObject("""
        select furniture_category from catalog_node
        where catalog_version_id=? and node_id=?
        """, Boolean.class, catalogId, furnitureId)).isTrue();
    assertThat(jdbc.queryForObject("""
        select jsonb_exists(catalog_snapshot, 'furnitureEquipment')
          and catalog_snapshot->'furnitureEquipment' = 'null'::jsonb
        from estimate_line where estimate_id=?
        """, Boolean.class, estimateId)).isTrue();
    assertThat(jdbc.queryForObject("""
        select jsonb_exists(
          response_body #> '{revisions,0,lines,0,catalogSnapshot}', 'furnitureEquipment')
          and response_body #> '{revisions,0,lines,0,catalogSnapshot,furnitureEquipment}'
            = 'null'::jsonb
        from maintenance_idempotency_record
        where subject_id=? and command_scope='estimate.create' and idempotency_key=?
        """, Boolean.class, subjectId, idempotencyKey)).isTrue();
    assertThatThrownBy(() -> jdbc.update("""
        update catalog_node set furniture_equipment_id=? where row_id=?
        """, UUID.randomUUID(), materialRowId))
        .hasMessageContaining("ck_catalog_node_furniture_equipment");

    copyMigration(directory, "V5__media_owner_proof_reconciliation.sql");
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();
    assertThat(flyway(location).migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_owner_revision=0
          and media_active
        """, Integer.class)).isEqualTo(2);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_ESTIMATE'
          and media_owner_id=?
          and media_aggregate_version=0
        """, Integer.class, estimateId)).isOne();

    jdbc.update("""
        update catalog_node
        set routing_queue_id=?, routing_queue_code='REPAIR', routing_queue_kind='REPAIR'
        where catalog_version_id=? and node_id=?
        """, queueId, catalogId, materialId);
    copyMigration(directory, "V6__catalog_routing_reconciliation.sql");
    assertThat(flyway(location).migrate().migrationsExecuted).isOne();
    assertThat(flyway(location).migrate().migrationsExecuted).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='TASK_BOARD'
          and operation_type='REGISTER_CATALOG_POSITION'
          and catalog_version_id=?
          and catalog_node_id=?
          and catalog_queue_id=?
          and catalog_external_reference_id=?
          and state='PENDING'
        """, Integer.class, catalogId, materialId, queueId,
        "catalog:" + catalogId + ":" + materialId)).isOne();
    assertThat(jdbc.queryForObject("""
        select response_snapshot->'predecessorKeys' = '[]'::jsonb
        from integration_reconciliation
        where catalog_version_id=? and catalog_node_id=?
        """, Boolean.class, catalogId, materialId)).isTrue();
  }

  @Test
  void existingV4CatalogNodeSharedAcrossWarehousesGetsDistinctStableMediaOwnersWithoutDataLoss(
      @TempDir Path directory)
      throws IOException {
    for (String migration : List.of(
        "V1__maintenance_schema.sql",
        "V2__inventory_source.sql",
        "V3__logistics_return_shortage.sql",
        "V4__catalog_furniture_equipment.sql")) {
      copyMigration(directory, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(location).migrate().migrationsExecuted).isEqualTo(4);

    UUID firstWarehouseId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID secondWarehouseId = UUID.fromString("20000000-0000-0000-0000-000000000002");
    UUID firstCatalogId = UUID.fromString("30000000-0000-0000-0000-000000000003");
    UUID secondCatalogId = UUID.fromString("40000000-0000-0000-0000-000000000004");
    UUID sharedNodeId = UUID.fromString("50000000-0000-0000-0000-000000000005");
    UUID firstRowId = UUID.fromString("60000000-0000-0000-0000-000000000006");
    UUID secondRowId = UUID.fromString("70000000-0000-0000-0000-000000000007");
    UUID legacyMediaId = UUID.fromString("80000000-0000-0000-0000-000000000008");
    insertCatalogVersion(firstCatalogId, firstWarehouseId, "a".repeat(64));
    insertCatalogVersion(secondCatalogId, secondWarehouseId, "b".repeat(64));
    insertCatalogNode(firstRowId, sharedNodeId, firstCatalogId, "SHARED_NODE");
    insertCatalogNode(secondRowId, sharedNodeId, secondCatalogId, "SHARED_NODE");
    for (UUID rowId : List.of(firstRowId, secondRowId)) {
      jdbc.update("""
          insert into maintenance_media_reference(
            aggregate_type,aggregate_id,media_id,generation,owner_type,warehouse_id,
            safe_metadata,attached_at)
          values ('CATALOG_NODE',?,?,0,'MAINTENANCE_CATALOG_NODE',
            (select warehouse_id from catalog_version version
             join catalog_node node on node.catalog_version_id=version.id
             where node.row_id=?),'{"legacy":true}',clock_timestamp())
          """, rowId, legacyMediaId, rowId);
    }

    copyMigration(directory, "V5__media_owner_proof_reconciliation.sql");
    Flyway upgraded = flyway(location);
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();

    UUID firstOwnerId = catalogMediaOwnerId(firstWarehouseId, sharedNodeId);
    UUID secondOwnerId = catalogMediaOwnerId(secondWarehouseId, sharedNodeId);
    assertThat(firstOwnerId).isNotEqualTo(secondOwnerId);
    assertThat(jdbc.queryForList("""
        select media_owner_id from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_owner_revision=0
        order by media_warehouse_id
        """, UUID.class)).containsExactly(firstOwnerId, secondOwnerId);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_active
          and media_source_id in (?,?)
        """, Integer.class, firstCatalogId, secondCatalogId)).isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from catalog_version", Integer.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForObject("select count(*) from catalog_node", Integer.class))
        .isEqualTo(2);
    assertThat(jdbc.queryForList("""
        select aggregate_id from maintenance_media_reference
        where aggregate_type='CATALOG_NODE' and media_id=?
        """, UUID.class, legacyMediaId))
        .containsExactlyInAnyOrder(firstRowId, secondRowId);
  }

  @Test
  void existingV6CorrectsOnlyInvalidInitialMediaProofsWithoutResumingQuarantine(
      @TempDir Path directory)
      throws IOException {
    for (String migration : List.of(
        "V1__maintenance_schema.sql",
        "V2__inventory_source.sql",
        "V3__logistics_return_shortage.sql",
        "V4__catalog_furniture_equipment.sql",
        "V5__media_owner_proof_reconciliation.sql",
        "V6__catalog_routing_reconciliation.sql")) {
      copyMigration(directory, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(location).migrate().migrationsExecuted).isEqualTo(6);

    UUID warehouseId = UUID.randomUUID();
    UUID quarantinedOwnerId = UUID.randomUUID();
    UUID quarantinedSourceId = UUID.randomUUID();
    UUID quarantinedReconciliationId = UUID.randomUUID();
    UUID rejectedProofEventId = UUID.randomUUID();
    insertMediaProof(
        quarantinedReconciliationId,
        quarantinedOwnerId,
        warehouseId,
        quarantinedSourceId,
        0,
        7,
        19,
        rejectedProofEventId,
        "QUARANTINED",
        4,
        "EVENT_ID_CONFLICT");

    UUID pendingOwnerId = UUID.randomUUID();
    UUID pendingReconciliationId = UUID.randomUUID();
    insertMediaProof(
        pendingReconciliationId,
        pendingOwnerId,
        warehouseId,
        UUID.randomUUID(),
        0,
        4,
        4,
        UUID.randomUUID(),
        "PENDING",
        0,
        null);

    UUID correctOwnerId = UUID.randomUUID();
    UUID correctInitialProofEventId = UUID.randomUUID();
    insertMediaProof(
        UUID.randomUUID(),
        correctOwnerId,
        warehouseId,
        UUID.randomUUID(),
        0,
        0,
        12,
        correctInitialProofEventId,
        "RETRY_PENDING",
        2,
        "TEMPORARY_FAILURE");
    UUID laterProofEventId = UUID.randomUUID();
    insertMediaProof(
        UUID.randomUUID(),
        UUID.randomUUID(),
        warehouseId,
        UUID.randomUUID(),
        1,
        12,
        12,
        laterProofEventId,
        "CONFIRMED",
        1,
        null);

    UUID correctedQuarantinedProofEventId = UUID.nameUUIDFromBytes((
        "maintenance-media-proof:MAINTENANCE_REPAIR:" + quarantinedOwnerId + ":0:0")
        .getBytes(StandardCharsets.UTF_8));
    UUID correctedPendingProofEventId = UUID.nameUUIDFromBytes((
        "maintenance-media-proof:MAINTENANCE_REPAIR:" + pendingOwnerId + ":0:0")
        .getBytes(StandardCharsets.UTF_8));

    copyMigration(directory, "V7__correct_initial_media_owner_proof_version.sql");
    Flyway upgraded = flyway(location);
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();

    var quarantined = jdbc.queryForMap("""
        select media_aggregate_version,media_source_version,media_proof_event_id,
          idempotency_key,state,attempt_count,last_error_code,
          response_snapshot->>'aggregateVersion' as payload_aggregate_version,
          response_snapshot->>'proofEventId' as payload_proof_event_id
        from integration_reconciliation where id=?
        """, quarantinedReconciliationId);
    assertThat(quarantined)
        .containsEntry("media_aggregate_version", 0L)
        .containsEntry("media_source_version", 19L)
        .containsEntry("media_proof_event_id", correctedQuarantinedProofEventId)
        .containsEntry("idempotency_key", correctedQuarantinedProofEventId)
        .containsEntry("state", "QUARANTINED")
        .containsEntry("attempt_count", 4)
        .containsEntry("last_error_code", "EVENT_ID_CONFLICT")
        .containsEntry("payload_aggregate_version", "0")
        .containsEntry("payload_proof_event_id", correctedQuarantinedProofEventId.toString());

    var pending = jdbc.queryForMap("""
        select media_aggregate_version,media_source_version,media_proof_event_id,
          idempotency_key,state,response_snapshot->>'aggregateVersion' as payload_version,
          response_snapshot->>'proofEventId' as payload_event_id
        from integration_reconciliation where id=?
        """, pendingReconciliationId);
    assertThat(pending)
        .containsEntry("media_aggregate_version", 0L)
        .containsEntry("media_source_version", 4L)
        .containsEntry("media_proof_event_id", correctedPendingProofEventId)
        .containsEntry("idempotency_key", correctedPendingProofEventId)
        .containsEntry("state", "PENDING")
        .containsEntry("payload_version", "0")
        .containsEntry("payload_event_id", correctedPendingProofEventId.toString());

    assertThat(jdbc.queryForObject("""
        select media_proof_event_id from integration_reconciliation
        where media_owner_id=? and media_owner_revision=0
        """, UUID.class, correctOwnerId)).isEqualTo(correctInitialProofEventId);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where media_owner_revision=1 and media_aggregate_version=12 and media_proof_event_id=?
        """, Integer.class, laterProofEventId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation", Integer.class)).isEqualTo(4);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA' and operation_type='UPSERT_MEDIA_OWNER_PROOF'
          and media_owner_revision=0 and media_aggregate_version<>0
        """, Integer.class)).isZero();
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() {
    jdbc.execute("create table historical_maintenance_evidence (id uuid primary key)");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(jdbc.queryForObject(
        "select to_regclass('public.flyway_schema_history')", String.class)).isNull();
  }

  @Test
  void existingV7BackfillsCatalogCanvasRetiresCatalogMediaAndDropsOnlyPhotoFlag(
      @TempDir Path directory)
      throws IOException {
    for (String migration : List.of(
        "V1__maintenance_schema.sql",
        "V2__inventory_source.sql",
        "V3__logistics_return_shortage.sql",
        "V4__catalog_furniture_equipment.sql")) {
      copyMigration(directory, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    assertThat(flyway(location).migrate().migrationsExecuted).isEqualTo(4);

    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID sourceNodeId = UUID.fromString("c1b41ba9-1eb4-5a2b-8b89-3838b49d2c2f");
    UUID targetNodeId = UUID.fromString("1b5116c9-b9a6-53c0-91a5-d1b905ea7d37");
    UUID sourceRowId = UUID.randomUUID();
    UUID targetRowId = UUID.randomUUID();
    UUID linkId = UUID.fromString("019f2288-2aa8-782d-95af-b0b29a20f7fb");
    insertCatalogVersion(catalogId, warehouseId, "1".repeat(64));
    insertCatalogNode(sourceRowId, sourceNodeId, catalogId, "ORGALIT_ZINC_DOOR");
    insertCatalogNode(targetRowId, targetNodeId, catalogId, "INSTALL_ENTRY_DOOR");
    jdbc.update("""
        insert into catalog_link(
          row_id,link_id,catalog_version_id,source_node_id,target_node_id,link_type,sort_order)
        values (?,?,?,?,?,'DEPENDENCY',0)
        """, UUID.randomUUID(), linkId, catalogId, sourceNodeId, targetNodeId);
    UUID catalogMediaId = UUID.randomUUID();
    jdbc.update("""
        insert into maintenance_media_reference(
          aggregate_type,aggregate_id,media_id,generation,owner_type,warehouse_id,
          safe_metadata,attached_at)
        values ('CATALOG_NODE',?,?,0,'MAINTENANCE_CATALOG_NODE',?,'{}',clock_timestamp())
        """, sourceRowId, catalogMediaId, warehouseId);

    for (String migration : List.of(
        "V5__media_owner_proof_reconciliation.sql",
        "V6__catalog_routing_reconciliation.sql",
        "V7__correct_initial_media_owner_proof_version.sql")) {
      copyMigration(directory, migration);
    }
    assertThat(flyway(location).migrate().migrationsExecuted).isEqualTo(3);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA' and media_owner_type='MAINTENANCE_CATALOG_NODE'
        """, Integer.class)).isEqualTo(2);

    UUID repairProofId = UUID.randomUUID();
    insertMediaProof(
        repairProofId,
        UUID.randomUUID(),
        warehouseId,
        UUID.randomUUID(),
        0,
        0,
        0,
        UUID.randomUUID(),
        "PENDING",
        0,
        null);

    copyMigration(directory, "V8__catalog_canvas_and_remove_catalog_media.sql");
    Flyway upgraded = flyway(location);
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();

    assertThat(jdbc.queryForMap("""
        select canvas_x,canvas_y from catalog_node
        where catalog_version_id=? and node_id=?
        """, catalogId, sourceNodeId))
        .containsEntry("canvas_x", 912)
        .containsEntry("canvas_y", 1608);
    assertThat(jdbc.queryForMap("""
        select source_anchor,target_anchor from catalog_link
        where catalog_version_id=? and link_id=?
        """, catalogId, linkId))
        .containsEntry("source_anchor", "TOP")
        .containsEntry("target_anchor", "BOTTOM");
    assertThat(jdbc.queryForObject("""
        select count(*) from maintenance_media_reference
        where aggregate_type='CATALOG_NODE' or owner_type='MAINTENANCE_CATALOG_NODE'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA' and media_owner_type='MAINTENANCE_CATALOG_NODE'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where id=?",
        Integer.class,
        repairProofId)).isOne();
    assertThat(columns("catalog_node")).doesNotContain("media_references");

    copyMigration(directory, "V9__remove_catalog_photo_requirement.sql");
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
    assertThat(columns("catalog_node"))
        .contains("canvas_x", "canvas_y", "opaque_references")
        .doesNotContain("media_references", "photo_required");
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where id=?",
        Integer.class,
        repairProofId)).isOne();
  }

  @Test
  void technicalConstraintsAndDueIndexesMatchTheReviewedStageSixDefinitions() {
    flyway(MIGRATIONS).migrate();

    assertThat(constraintDefinition("estimate_line", "fk_estimate_line_revision"))
        .contains(
            "FOREIGN KEY (estimate_id, estimate_revision) REFERENCES estimate_revision(estimate_id,"
                + " revision)")
        .contains("DEFERRABLE INITIALLY DEFERRED");
    assertThat(constraintDefinition("estimate_plan_stage", "fk_estimate_plan_revision"))
        .contains(
            "FOREIGN KEY (estimate_id, estimate_revision) REFERENCES estimate_revision(estimate_id,"
                + " revision)")
        .contains("DEFERRABLE INITIALLY DEFERRED");
    assertThat(constraintDefinition("maintenance_repair", "ck_repair_hierarchy"))
        .contains("root_repair_id IS NULL", "source_repair_id <> id", "root_repair_id <> id");
    assertThat(constraintDefinition("integration_reconciliation", "ck_reconciliation_review"))
        .contains("review_version = 0", "review_subject_id IS NOT NULL", "btrim");
    assertThat(constraintDefinition("outbox_event", "ck_maintenance_outbox_review"))
        .contains("review_version = 0", "review_reason IS NOT NULL", "reviewed_at IS NOT NULL");
    assertThat(
            constraintDefinition(
                "maintenance_inbound_correlation",
                "fk_maintenance_inbound_correlation_counterpart"))
        .contains("FOREIGN KEY (counterpart_event_id)")
        .contains("REFERENCES maintenance_inbound_replay_message(event_id)");
    assertThat(
            constraintDefinition(
                "maintenance_inbound_correlation",
                "ck_maintenance_inbound_correlation_identity"))
        .contains("rwms.task-board.board-task.v1", "external_task_id IS NOT NULL")
        .contains("rwms.task-board.queue-entry.v1", "queue_entry_id IS NOT NULL");

    assertThat(indexDefinition("uk_repair_primary_estimate"))
        .contains("UNIQUE INDEX", "estimate_id IS NOT NULL");
    assertThat(indexDefinition("idx_repair_lease_renewal_due"))
        .contains("lease_expires_at", "lease_reconciliation_state", "ACTIVE");
    assertThat(indexDefinition("idx_integration_reconciliation_due"))
        .contains("next_attempt_at", "attempt_count < 4");
    assertThat(indexDefinition("idx_maintenance_inbound_correlation_pending_queue"))
        .contains("state", "PENDING", "queue_entry_id IS NOT NULL");
    assertThat(columnDefault("integration_reconciliation", "review_version")).contains("0");
    assertThat(columnDefault("outbox_event", "review_version")).contains("0");
    assertThat(columnNullable("integration_reconciliation", "review_version")).isEqualTo("NO");
    assertThat(columnNullable("outbox_event", "review_version")).isEqualTo("NO");
  }

  @Test
  void invalidRevisionRepairReviewAndCorrelationRowsAreRejectedByPostgres() {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = UUID.randomUUID();
    UUID estimateId = UUID.randomUUID();
    insertCatalogVersion(catalogId, warehouseId, "3".repeat(64));
    insertEstimateWithRevision(estimateId, catalogId, warehouseId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into estimate_line(
                      row_id,line_id,estimate_id,estimate_revision,line_no,line_type,title,
                      quantity,unit_price_minor,duration_minutes,unit,media_references)
                    values (?,?,?,2,0,'WORK','orphan revision',1,0,1,'piece','[]')
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    estimateId))
        .hasMessageContaining("fk_estimate_line_revision");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into estimate_plan_stage(
                      row_id,stage_id,estimate_id,estimate_revision,stage_no,stage_kind,
                      routing_queue_id,routing_queue_name,routing_queue_type)
                    values (?,?,?,2,0,'REPAIR_WORK',?,'Repair','REPAIR')
                    """,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    estimateId,
                    UUID.randomUUID()))
        .hasMessageContaining("fk_estimate_plan_revision");

    UUID firstRepairId = UUID.randomUUID();
    insertRepair(
        firstRepairId,
        warehouseId,
        estimateId,
        "ESTIMATE",
        "PRIMARY",
        null,
        null);
    assertThatThrownBy(
            () ->
                insertRepair(
                    UUID.randomUUID(),
                    warehouseId,
                    estimateId,
                    "ESTIMATE",
                    "PRIMARY",
                    null,
                    null))
        .hasMessageContaining("uk_repair_primary_estimate");
    UUID selfRepairId = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                insertRepair(
                    selfRepairId,
                    warehouseId,
                    null,
                    "DIRECT_REPAIR",
                    "REWORK",
                    selfRepairId,
                    selfRepairId))
        .hasMessageContaining("ck_repair_hierarchy");

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into integration_reconciliation(
                      id,repair_id,dependency_type,operation_type,idempotency_key,state,attempt_count,
                      next_attempt_at,response_snapshot,review_version,review_subject_id,reviewed_at,
                      created_at,updated_at)
                    values (?,?,'ASSET','TEST',?,'PENDING',0,clock_timestamp(),'{}',1,?,
                      clock_timestamp(),clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    firstRepairId,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_reconciliation_review");

    UUID outboxEventId = insertDomainEvent(UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into outbox_event(
                      event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
                      envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,
                      review_version,review_subject_id,reviewed_at,created_at)
                    select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,
                      'rwms.maintenance.catalog-version.v1','{}',payload_sha256,'PENDING',0,
                      clock_timestamp(),1,?,clock_timestamp(),clock_timestamp()
                    from domain_event where event_id=?
                    """,
                    UUID.randomUUID(),
                    outboxEventId))
        .hasMessageContaining("ck_maintenance_outbox_review");

    UUID replayEventId = insertReplayMessage("rwms.task-board.board-task.v1", "BOARD_TASK");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into maintenance_inbound_correlation(
                      source_event_id,source_topic,board_task_id,queue_entry_id,state,
                      recorded_at,updated_at)
                    values (?,'rwms.task-board.board-task.v1',?,?,'PENDING',
                      clock_timestamp(),clock_timestamp())
                    """,
                    replayEventId,
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("ck_maintenance_inbound_correlation_identity");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into maintenance_inbound_correlation(
                      source_event_id,source_topic,board_task_id,external_task_id,
                      counterpart_event_id,state,recorded_at,updated_at)
                    values (?,'rwms.task-board.board-task.v1',?,?,?,'CORRELATED',
                      clock_timestamp(),clock_timestamp())
                    """,
                    replayEventId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID()))
        .hasMessageContaining("fk_maintenance_inbound_correlation_counterpart");
  }

  private Flyway flyway(String location) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(location)
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false)
        .load();
  }

  private List<String> tableNames() {
    return jdbc.queryForList(
        "select table_name from information_schema.tables where table_schema='public'",
        String.class);
  }

  private int columnCount(String table, String column) {
    Integer count = jdbc.queryForObject("""
        select count(*) from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """, Integer.class, table, column);
    return count == null ? 0 : count;
  }

  private List<String> columns(String table) {
    return jdbc.queryForList("""
        select column_name from information_schema.columns
        where table_schema='public' and table_name=?
        """, String.class, table);
  }

  private String constraintDefinition(String table, String constraint) {
    return jdbc.queryForObject(
        """
        select pg_get_constraintdef(con.oid)
        from pg_constraint con
        join pg_class relation on relation.oid=con.conrelid
        join pg_namespace namespace on namespace.oid=relation.relnamespace
        where namespace.nspname='public' and relation.relname=? and con.conname=?
        """,
        String.class,
        table,
        constraint);
  }

  private String indexDefinition(String index) {
    return jdbc.queryForObject(
        "select indexdef from pg_indexes where schemaname='public' and indexname=?",
        String.class,
        index);
  }

  private String triggerDefinition(String trigger) {
    return jdbc.queryForObject(
        """
        select pg_get_triggerdef(trigger.oid)
        from pg_trigger trigger
        join pg_class relation on relation.oid=trigger.tgrelid
        join pg_namespace namespace on namespace.oid=relation.relnamespace
        where namespace.nspname='public' and not trigger.tgisinternal and trigger.tgname=?
        """,
        String.class,
        trigger);
  }

  private boolean triggerExists(String trigger) {
    Boolean exists = jdbc.queryForObject(
        """
        select exists (
          select 1
            from pg_trigger trigger
            join pg_class relation on relation.oid=trigger.tgrelid
            join pg_namespace namespace on namespace.oid=relation.relnamespace
           where namespace.nspname='public'
             and not trigger.tgisinternal
             and trigger.tgname=?)
        """,
        Boolean.class,
        trigger);
    return Boolean.TRUE.equals(exists);
  }

  private String columnDefault(String table, String column) {
    return jdbc.queryForObject(
        """
        select column_default from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """,
        String.class,
        table,
        column);
  }

  private String columnNullable(String table, String column) {
    return jdbc.queryForObject(
        """
        select is_nullable from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """,
        String.class,
        table,
        column);
  }

  private String columnType(String table, String column) {
    return jdbc.queryForObject(
        """
        select data_type || '(' || character_maximum_length || ')'
        from information_schema.columns
        where table_schema='public' and table_name=? and column_name=?
        """,
        String.class,
        table,
        column);
  }

  private void insertCatalogVersion(UUID id, UUID warehouseId, String sourceSha256) {
    jdbc.update("""
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,created_at,updated_at)
        values (?,0,?,'DRAFT',?,1,0,'{}',clock_timestamp(),clock_timestamp())
        """, id, warehouseId, sourceSha256);
  }

  private void insertCatalogNode(
      UUID rowId, UUID logicalNodeId, UUID versionId, String code) {
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,duration_minutes,
          include_in_estimate,common_item,show_in_main_menu,photo_required,
          opaque_references,media_references)
        values (?,?,?,?,'WORK','Node',true,0,true,false,false,false,'[]','[]')
        """, rowId, logicalNodeId, versionId, code);
  }

  private void insertEstimateWithRevision(
      UUID estimateId, UUID catalogId, UUID warehouseId) {
    jdbc.update(
        """
        insert into maintenance_estimate(
          id,version,warehouse_id,rental_item_id,rental_item_version_snapshot,
          catalog_version_id,state,revision,dispatch_date,actor_ref,created_at,updated_at)
        values (?,0,?,?,0,?,'DRAFT',1,current_date,'{}',clock_timestamp(),clock_timestamp())
        """,
        estimateId,
        warehouseId,
        UUID.randomUUID(),
        catalogId);
    jdbc.update(
        """
        insert into estimate_revision(
          id,estimate_id,revision,dispatch_date,total_minor,actor_ref,recorded_at)
        values (?,?,1,current_date,0,'{}',clock_timestamp())
        """,
        UUID.randomUUID(),
        estimateId);
  }

  private void insertLegacyEstimateStage(
      UUID estimateId, UUID stageId, int stageNo, String stageKind) {
    jdbc.update(
        """
        insert into estimate_plan_stage(
          row_id,stage_id,estimate_id,estimate_revision,stage_no,stage_kind,
          routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,1,?,?,?,'Ремонт','REPAIR')
        """,
        UUID.randomUUID(), stageId, estimateId, stageNo, stageKind, UUID.randomUUID());
  }

  private void insertLegacyRepairStage(
      UUID repairId,
      UUID stageId,
      int stageNo,
      String stageKind,
      String state,
      UUID externalQueueEntryId) {
    jdbc.update(
        """
        insert into repair_stage(
          row_id,stage_id,repair_id,stage_no,stage_kind,state,routing_queue_id,
          routing_queue_name,routing_queue_type,external_queue_entry_id,task_board_version,
          task_generation_state,delivery_state,delivery_attempts,delivery_updated_at,
          task_deadline,completed_event_id,completed_at)
        values (?,?,?,?,?,?,?,'Перемещения','MOVEMENT',?,null,
          ?,?,0,clock_timestamp(),null,null,?)
        """,
        UUID.randomUUID(),
        stageId,
        repairId,
        stageNo,
        stageKind,
        state,
        UUID.randomUUID(),
        externalQueueEntryId,
        "DONE".equals(state) ? "NOT_REQUIRED" : "PENDING_GENERATION",
        "DONE".equals(state) ? "DELIVERED" : "PENDING",
        "DONE".equals(state) ? java.time.OffsetDateTime.now() : null);
  }

  private void insertRepair(
      UUID id,
      UUID warehouseId,
      UUID estimateId,
      String origin,
      String kind,
      UUID rootRepairId,
      UUID sourceRepairId) {
    jdbc.update(
        """
        insert into maintenance_repair(
          id,version,warehouse_id,rental_item_id,rental_item_version_snapshot,
          root_repair_id,source_repair_id,estimate_id,origin,kind,execution_state,
          acceptance_state,dispatch_date,actor_ref,external_task_id,task_generation_state,
          delivery_state,delivery_attempts,delivery_updated_at,lease_reconciliation_state,
          reconciliation_state,created_at,updated_at)
        values (?,0,?,?,0,?,?,?, ?,?,'DRAFT','NOT_READY',current_date,'{}',?,
          'PENDING_GENERATION','PENDING',0,clock_timestamp(),'NOT_REQUIRED','NOT_REQUIRED',
          clock_timestamp(),clock_timestamp())
        """,
        id,
        warehouseId,
        UUID.randomUUID(),
        rootRepairId,
        sourceRepairId,
        estimateId,
        origin,
        kind,
        UUID.randomUUID());
  }

  private void insertV20WorkNode(
      UUID catalogId, UUID nodeId, UUID queueId, String name) {
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,'WORK',?,true,'piece',10000,0,true,false,true,?,'External works','REPAIR')
        """,
        UUID.randomUUID(),
        nodeId,
        catalogId,
        name,
        queueId);
  }

  private String legacyCatalogSnapshot(UUID catalogId, UUID nodeId, UUID queueId) {
    return """
        {"catalogVersionId":"%s","nodeId":"%s","code":"LEGACY_WORK",
         "nodeType":"WORK","name":"Legacy work","unit":"piece","unitPrice":"100.00",
         "durationMinutes":0,"routing":{"queueId":"%s"}}
        """.formatted(catalogId, nodeId, queueId);
  }

  private void insertV20CatalogStreamArtifacts(UUID catalogId, UUID nodeId, String lifecycle) {
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    String state = """
        {"id":"%s","version":0,"lifecycle":"%s","nodes":[
          {"id":"%s","nodeType":"WORK","name":"Legacy work","durationMinutes":0,
           "routingQueueCode":"REPAIR","photoRequired":true}]}
        """.formatted(catalogId, lifecycle, nodeId);
    String payload = "{\"model\":\"maintenance-full-state-v1\",\"state\":" + state + "}";
    String envelope = "{\"payload\":{}}";
    String hash = "c".repeat(64);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,0,?,clock_timestamp())
        """,
        catalogId.toString(),
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'CATALOG_VERSION',?,0,'maintenance.catalog-version.activated.v1',1,
          clock_timestamp(),clock_timestamp(),?,?::jsonb,?,false)
        """,
        eventId,
        catalogId.toString(),
        correlationId,
        payload,
        hash);
    jdbc.update(
        """
        insert into aggregate_snapshot(
          aggregate_type,aggregate_id,aggregate_version,state,state_sha256,recorded_at)
        values ('CATALOG_VERSION',?,0,?::jsonb,?,clock_timestamp())
        """,
        catalogId.toString(),
        state,
        hash);
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values ('maintenance-live-v1','CATALOG_VERSION',?,0,?,clock_timestamp())
        """,
        catalogId.toString(),
        hash);
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'CATALOG_VERSION',?,0,'maintenance.catalog-version.activated.v1',
          'rwms.maintenance.catalog-version.v1',?::jsonb,?,'PENDING',0,clock_timestamp(),
          clock_timestamp())
        """,
        eventId,
        catalogId.toString(),
        envelope,
        hash);
  }

  private void insertMediaProof(
      UUID reconciliationId,
      UUID ownerId,
      UUID warehouseId,
      UUID sourceId,
      long ownerRevision,
      long aggregateVersion,
      long sourceVersion,
      UUID proofEventId,
      String state,
      int attemptCount,
      String lastErrorCode) {
    jdbc.update(
        """
        insert into integration_reconciliation(
          id,repair_id,dependency_type,operation_type,idempotency_key,state,attempt_count,
          next_attempt_at,last_error_code,response_snapshot,review_version,created_at,updated_at,
          media_owner_type,media_owner_id,media_warehouse_id,media_owner_revision,
          media_aggregate_version,media_source_id,media_source_version,media_proof_event_id,
          media_active)
        values (?,null,'MEDIA','UPSERT_MEDIA_OWNER_PROOF',?,?,?,clock_timestamp(),?,
          jsonb_build_object(
            'ownerType','MAINTENANCE_REPAIR','ownerId',?::uuid,'warehouseId',?::uuid,
            'ownerRevision',?::bigint,'aggregateVersion',?::bigint,'proofEventId',?::uuid,
            'active',true),
          0,clock_timestamp(),clock_timestamp(),'MAINTENANCE_REPAIR',?,?,?,?,?,?,?,true)
        """,
        reconciliationId,
        proofEventId,
        state,
        attemptCount,
        lastErrorCode,
        ownerId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        proofEventId,
        ownerId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        sourceId,
        sourceVersion,
        proofEventId);
  }

  private static UUID catalogMediaOwnerId(UUID warehouseId, UUID nodeId) {
    return UUID.nameUUIDFromBytes((
        "maintenance-catalog-node-owner:" + warehouseId + ":" + nodeId)
        .getBytes(StandardCharsets.UTF_8));
  }

  private UUID insertDomainEvent(UUID aggregateId) {
    UUID eventId = UUID.randomUUID();
    String hash = "4".repeat(64);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,0,?,clock_timestamp())
        """,
        aggregateId.toString(),
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'CATALOG_VERSION',?,0,'maintenance.catalog-version.changed.v1',1,
          clock_timestamp(),clock_timestamp(),?,'{}',?,false)
        """,
        eventId,
        aggregateId.toString(),
        UUID.randomUUID(),
        hash);
    return eventId;
  }

  private UUID insertReplayMessage(String topic, String aggregateType) {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into maintenance_inbound_replay_message(
          event_id,source_topic,kafka_key,aggregate_type,aggregate_id,aggregate_version,
          event_type,envelope_body,message_sha256,state,staged_at,updated_at)
        values (?,?,?,?,?,0,'task-board.board-task.created.v1','{}',?,'STAGED',
          clock_timestamp(),clock_timestamp())
        """,
        eventId,
        topic,
        aggregateId.toString(),
        aggregateType,
        aggregateId.toString(),
        "5".repeat(64));
    return eventId;
  }

  private java.net.URL requireResource(String path) {
    java.net.URL resource = getClass().getClassLoader().getResource(path);
    if (resource == null) throw new IllegalStateException("Missing maintenance migration " + path);
    return resource;
  }

  private Path copyMigration(Path directory, String name) throws IOException {
    Path target = directory.resolve(name);
    try (var source = requireResource("db/migration/" + name).openStream()) {
      Files.copy(source, target);
    }
    return target;
  }
}
