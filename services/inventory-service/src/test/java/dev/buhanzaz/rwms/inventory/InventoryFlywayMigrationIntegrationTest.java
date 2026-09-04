package dev.buhanzaz.rwms.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
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
class InventoryFlywayMigrationIntegrationTest {
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
  void cleanInstallIsRepeatSafeAndContainsNoSeedOrImporter() {
    Flyway flyway = flyway(MIGRATIONS);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(28);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();
    assertThat(tableNames())
        .contains(
            "inventory_session",
            "inventory_start_operation",
            "inventory_start_capture_attempt",
            "inventory_start_capture_result",
            "inventory_capture_release",
            "inventory_expected_item",
            "inventory_finding",
            "inventory_membership_movement",
            "finding_plan_snapshot",
            "finding_plan_line",
            "finding_plan_stage",
            "finding_media_reference",
            "inventory_source_attachment",
            "inventory_validation_snapshot",
            "inventory_validation_item",
            "inventory_completion_statistics",
            "inventory_statistics_line",
            "inventory_furniture_reconciliation_intent",
            "inventory_furniture_loss_intent",
            "inventory_planning_settings",
            "inventory_final_plan",
            "inventory_final_plan_entry",
            "inventory_cabin_disposition_review",
            "inventory_cabin_disposition_row",
            "inventory_cabin_write_off_intent",
            "inventory_plan_logistics_effect",
            "inventory_publication_intent",
            "inventory_publication_attempt",
            "inventory_publication_attempt_result",
            "inventory_media_fact_projection",
            "inventory_idempotency_record",
            "domain_event",
            "event_stream_head",
            "aggregate_snapshot",
            "projection_checkpoint",
            "outbox_event",
            "inbox_message",
            "consumer_aggregate_checkpoint",
            "version_gap_quarantine",
            "sanitized_dead_letter")
        .doesNotContain("legacy_inventory", "browser_inventory", "inventory_import");
    assertThat(count("inventory_session")).isZero();
    assertThat(count("inventory_finding")).isZero();
    assertThat(count("domain_event")).isZero();
    assertThat(columns("finding_plan_stage")).contains(
        "catalog_node_id", "catalog_node_name", "routing_queue_id", "routing_queue_name",
        "routing_queue_type")
        .doesNotContain("routing_queue_code", "routing_queue_kind", "movement_required");
    assertThat(columns("inventory_finding")).contains("cover_media_id");
    assertThat(columns("inventory_session"))
        .contains(
            "review_stage",
            "furniture_asset_snapshot_sha256",
            "furniture_asset_snapshot",
            "furniture_review_sha256",
            "furniture_stock_observation",
            "furniture_reviewed_by_actor_ref",
            "furniture_reviewed_at");
    assertThat(columns("finding_plan_snapshot"))
        .contains(
            "movement_to_repair",
            "movement_to_shipment",
            "force_capital_repair",
            "logistics_planning_mode",
            "logistics_scheduled_date",
            "snapshot_schema_version");
    assertThat(columns("inventory_final_plan_entry"))
        .contains("force_capital_repair", "disposition_kind", "disposition_details");
    assertThat(columns("inventory_final_plan"))
        .contains(
            "task_board_calendar_from",
            "task_board_calendar_through",
            "task_board_calendar_fingerprint",
            "task_board_calendar_snapshot");
    assertThat(columns("inventory_publication_intent"))
        .contains(
            "maintenance_outcome",
            "maintenance_result",
            "desired_asset_status",
            "effective_asset_version",
            "asset_outcome_result",
            "outcome_reapplication_no",
            "asset_passport_observation",
            "generation_attempt_count",
            "next_attempt_at");
    assertThat(columns("inventory_publication_attempt_result"))
        .contains("maintenance_outcome", "maintenance_result", "asset_outcome_result");
  }

  @Test
  void v28FencesFutureTaskBoardCalendarEvidenceAndStalesOnlyActiveDrafts() {
    Flyway beforeV28 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("27")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV28.migrate().migrationsExecuted).isEqualTo(27);
    UUID draftInventoryId = UUID.randomUUID();
    UUID completedInventoryId = UUID.randomUUID();
    insertActiveSession(draftInventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertActiveSession(completedInventoryId, UUID.randomUUID(), UUID.randomUUID());
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,?,0,0,?,'AUTO','AUTO',clock_timestamp(),clock_timestamp())
        """,
        draftInventoryId,
        "DRAFT",
        "a".repeat(64));
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,?,0,0,?,'AUTO','AUTO',clock_timestamp(),clock_timestamp())
        """,
        completedInventoryId,
        "COMPLETED",
        "b".repeat(64));

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isOne();
    latest.validate();

    assertThat(
            jdbc.queryForObject(
                "select state from inventory_final_plan where inventory_id=?",
                String.class,
                draftInventoryId))
        .isEqualTo("STALE");
    assertThat(
            jdbc.queryForMap(
                "select state,final_plan_version,final_plan_sha256 from inventory_final_plan where inventory_id=?",
                completedInventoryId))
        .containsEntry("state", "COMPLETED")
        .containsEntry("final_plan_version", 1L)
        .containsEntry("final_plan_sha256", "b".repeat(64));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_final_plan set task_board_calendar_from=current_date where inventory_id=?",
                    completedInventoryId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.update(
                """
                update inventory_final_plan
                   set task_board_calendar_from=current_date,
                       task_board_calendar_through=current_date,
                       task_board_calendar_fingerprint=?,
                       task_board_calendar_snapshot='{}'::jsonb
                 where inventory_id=?
                """,
                "c".repeat(64),
                completedInventoryId))
        .isOne();
  }

  @Test
  void v27BackfillsDurablePublicationRetryBudgetAndDueTime() {
    Flyway beforeV27 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("26")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV27.migrate().migrationsExecuted).isEqualTo(26);
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "V27-PUBLICATION");
    UUID intentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          source_revision,desired_asset_status,attempt_count,created_at,updated_at)
        values (?,?,?,0,'TRANSIENT_FAILED',?,1,'FREE',3,
                clock_timestamp()-interval '1 minute',clock_timestamp()-interval '1 minute')
        """,
        intentId,
        inventoryId,
        findingId,
        inventoryId + ":" + findingId);

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(2);
    latest.validate();

    assertThat(
            jdbc.queryForMap(
                "select generation_attempt_count,next_attempt_at from inventory_publication_intent where id=?",
                intentId))
        .containsEntry("generation_attempt_count", 0)
        .satisfies(row -> assertThat(row.get("next_attempt_at")).isNotNull());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_publication_intent set generation_attempt_count=attempt_count+1 where id=?",
                    intentId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v26UpgradesExistingFinalPlansToLocalDispositionWithoutInventingRemoteWork() {
    Flyway beforeV26 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("25")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV26.migrate().migrationsExecuted).isEqualTo(25);
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    insertFinding(inventoryId, findingId, assetId, "V26-LOCAL");
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,'DRAFT',0,0,?,'AUTO','AUTO',clock_timestamp(),clock_timestamp())
        """,
        inventoryId,
        "f".repeat(64));
    insertV23NoWorkPlanEntry(inventoryId, findingId, assetId, 0, 0);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(3);
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select disposition_kind,
                       disposition_details->'formerRental' = 'null'::jsonb as null_former_rental
                  from inventory_final_plan_entry
                 where inventory_id=? and finding_id=?
                """,
                inventoryId,
                findingId))
        .containsEntry("disposition_kind", "LOCAL")
        .containsEntry("null_former_rental", true);
    assertThat(tableNames())
        .contains(
            "inventory_cabin_disposition_review",
            "inventory_cabin_disposition_row",
            "inventory_cabin_write_off_intent");
    assertThat(count("inventory_cabin_disposition_review")).isZero();
    assertThat(count("inventory_cabin_write_off_intent")).isZero();
  }

  @Test
  void v17BackfillsManualCapitalChoiceWithoutChangingExistingPlanMeaning() {
    Flyway beforeV17 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("16")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV17.migrate().migrationsExecuted).isEqualTo(16);

    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "V17-CAPITAL");
    jdbc.update(
        """
        insert into finding_plan_snapshot(
          finding_id,finding_revision,inventory_id,plan_mode,movement_to_repair,
          movement_to_shipment,catalog_version_id,plan_fingerprint_sha256,
          snapshot_schema_version,source_snapshot,frozen_at)
        values (?,?,?,'AUTO',false,false,?,?,2,?::jsonb,?)
        """,
        findingId,
        0,
        inventoryId,
        UUID.randomUUID(),
        "a".repeat(64),
        """
        {"priority":3,"movementToRepair":false,"movementToShipment":false,
         "logisticsPlanningMode":null,"logisticsScheduledDate":null,"lines":[],"stages":[]}
        """,
        now());
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,'DRAFT',0,0,?,'AUTO','AUTO',?,?)
        """,
        inventoryId,
        "b".repeat(64),
        now(),
        now());
    jdbc.update(
        """
        insert into inventory_final_plan_entry(
          inventory_id,final_plan_version,finding_id,finding_revision,has_work,target_kind,
          plan_order,priority,movement_to_repair,movement_scheduled_date,repair_scheduled_date,
          collision_candidates,reconciliation_decision)
        values (?,1,?,0,false,null,0,null,false,null,null,'[]'::jsonb,null)
        """,
        inventoryId,
        findingId);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(12);
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select force_capital_repair from finding_plan_snapshot where finding_id=?",
                Boolean.class,
                findingId))
        .isFalse();
    assertThat(
            jdbc.queryForObject(
                "select force_capital_repair from inventory_final_plan_entry where finding_id=?",
                Boolean.class,
                findingId))
        .isFalse();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_final_plan_entry set force_capital_repair=true where finding_id=?",
                    findingId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v18CarriesOneExactPriorMediaRevisionOnlyWhenCurrentEvidenceIsEmpty() {
    Flyway beforeV18 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("17")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV18.migrate().migrationsExecuted).isEqualTo(17);

    UUID inventoryId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    UUID repairedFindingId = UUID.randomUUID();
    UUID intentionalEmptyFindingId = UUID.randomUUID();
    UUID alreadyCurrentFindingId = UUID.randomUUID();
    insertFinding(inventoryId, repairedFindingId, UUID.randomUUID(), "V18-REPAIRED");
    insertFinding(inventoryId, intentionalEmptyFindingId, UUID.randomUUID(), "V18-EMPTY");
    insertFinding(inventoryId, alreadyCurrentFindingId, UUID.randomUUID(), "V18-CURRENT");

    UUID coverMediaId = UUID.randomUUID();
    UUID oldestOnlyMediaId = UUID.randomUUID();
    UUID interveningMediaId = UUID.randomUUID();
    UUID newestOnlyMediaId = UUID.randomUUID();
    OffsetDateTime oldestAttachedAt = OffsetDateTime.parse("2026-08-01T10:00:00Z");
    OffsetDateTime newestAttachedAt = OffsetDateTime.parse("2026-08-03T12:00:00Z");
    jdbc.update(
        "update inventory_finding set finding_revision=4,cover_media_id=? where id=?",
        coverMediaId,
        repairedFindingId);
    insertFindingMedia(
        repairedFindingId, 1, coverMediaId, 1, "IMAGE", oldestAttachedAt);
    insertFindingMedia(
        repairedFindingId, 1, oldestOnlyMediaId, 1, "VIDEO", oldestAttachedAt.plusMinutes(1));
    insertFindingMedia(
        repairedFindingId, 2, interveningMediaId, 2, "IMAGE", oldestAttachedAt.plusDays(1));
    insertFindingMedia(
        repairedFindingId, 3, coverMediaId, 3, "IMAGE", newestAttachedAt);
    insertFindingMedia(
        repairedFindingId, 3, newestOnlyMediaId, 4, "VIDEO", newestAttachedAt.plusMinutes(1));

    UUID intentionalHistoricalMediaId = UUID.randomUUID();
    jdbc.update(
        "update inventory_finding set finding_revision=2,cover_media_id=null where id=?",
        intentionalEmptyFindingId);
    insertFindingMedia(
        intentionalEmptyFindingId,
        1,
        intentionalHistoricalMediaId,
        1,
        "IMAGE",
        oldestAttachedAt);

    UUID currentCoverMediaId = UUID.randomUUID();
    UUID historicalExtraMediaId = UUID.randomUUID();
    jdbc.update(
        "update inventory_finding set finding_revision=2,cover_media_id=? where id=?",
        currentCoverMediaId,
        alreadyCurrentFindingId);
    insertFindingMedia(
        alreadyCurrentFindingId, 1, currentCoverMediaId, 1, "IMAGE", oldestAttachedAt);
    insertFindingMedia(
        alreadyCurrentFindingId,
        1,
        historicalExtraMediaId,
        1,
        "VIDEO",
        oldestAttachedAt.plusMinutes(1));
    insertFindingMedia(
        alreadyCurrentFindingId, 2, currentCoverMediaId, 2, "IMAGE", newestAttachedAt);
    assertThat(count("finding_media_reference")).isEqualTo(9);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(11);
    upgraded.validate();

    assertThat(
            jdbc.queryForList(
                """
                select media_id from finding_media_reference
                 where finding_id=? and finding_revision=4
                """,
                UUID.class,
                repairedFindingId))
        .containsExactlyInAnyOrder(coverMediaId, newestOnlyMediaId);
    assertThat(
            jdbc.queryForObject(
                """
                select generation from finding_media_reference
                 where finding_id=? and finding_revision=4 and media_id=?
                """,
                Long.class,
                repairedFindingId,
                coverMediaId))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                """
                select attached_at from finding_media_reference
                 where finding_id=? and finding_revision=4 and media_id=?
                """,
                OffsetDateTime.class,
                repairedFindingId,
                coverMediaId))
        .isEqualTo(newestAttachedAt);
    assertThat(
            jdbc.queryForMap(
                """
                select generation,media_kind,media_status
                  from finding_media_reference
                 where finding_id=? and finding_revision=4 and media_id=?
                """,
                repairedFindingId,
                newestOnlyMediaId))
        .containsEntry("generation", 4L)
        .containsEntry("media_kind", "VIDEO")
        .containsEntry("media_status", "READY");
    assertThat(
            jdbc.queryForObject(
                """
                select attached_at from finding_media_reference
                 where finding_id=? and finding_revision=4 and media_id=?
                """,
                OffsetDateTime.class,
                repairedFindingId,
                newestOnlyMediaId))
        .isEqualTo(newestAttachedAt.plusMinutes(1));
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=4
                   and media_id in (?, ?)
                """,
                Integer.class,
                repairedFindingId,
                oldestOnlyMediaId,
                interveningMediaId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=2
                """,
                Integer.class,
                intentionalEmptyFindingId))
        .isZero();
    assertThat(
            jdbc.queryForList(
                """
                select media_id from finding_media_reference
                 where finding_id=? and finding_revision=2
                """,
                UUID.class,
                alreadyCurrentFindingId))
        .containsExactly(currentCoverMediaId);
    assertThat(
            jdbc.queryForMap(
                """
                select generation,media_kind,media_status
                  from finding_media_reference
                 where finding_id=? and finding_revision=2 and media_id=?
                """,
                alreadyCurrentFindingId,
                currentCoverMediaId))
        .containsEntry("generation", 2L)
        .containsEntry("media_kind", "IMAGE")
        .containsEntry("media_status", "READY");
    assertThat(
            jdbc.queryForObject(
                """
                select attached_at from finding_media_reference
                 where finding_id=? and finding_revision=2 and media_id=?
                """,
                OffsetDateTime.class,
                alreadyCurrentFindingId,
                currentCoverMediaId))
        .isEqualTo(newestAttachedAt);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision < 4
                """,
                Integer.class,
                repairedFindingId))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=1
                """,
                Integer.class,
                intentionalEmptyFindingId))
        .isOne();
    assertThat(count("finding_media_reference")).isEqualTo(11);
    assertThat(upgraded.migrate().migrationsExecuted).isZero();
  }

  @Test
  void v19BackfillsAuthoritativeStatusWithoutRewritingSuccessfulPublicationEvidence() {
    Flyway beforeV19 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("18")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV19.migrate().migrationsExecuted).isEqualTo(18);

    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID capitalFindingId = UUID.randomUUID();
    UUID repairFindingId = UUID.randomUUID();
    UUID capitalAssetId = UUID.randomUUID();
    UUID repairAssetId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    String finalPlanSha256 = "e".repeat(64);
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    insertFinding(inventoryId, capitalFindingId, capitalAssetId, "V19-CAPITAL");
    insertFinding(inventoryId, repairFindingId, repairAssetId, "V19-REPAIR");
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,'DRAFT',0,0,?,'AUTO','AUTO',?,?)
        """,
        inventoryId,
        finalPlanSha256,
        now(),
        now());
    insertV19WorkPlanEntry(
        inventoryId, capitalFindingId, capitalAssetId, 0, true, "a".repeat(64));
    insertV19WorkPlanEntry(
        inventoryId, repairFindingId, repairAssetId, 1, false, "b".repeat(64));

    UUID blockedIntentId = UUID.randomUUID();
    UUID succeededIntentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          source_revision,final_plan_version,final_plan_sha256,attempt_count,
          blocked_failure_code,created_at,updated_at)
        values (?,?,?,0,'BLOCKED',?,1,1,?,1,'SOURCE_PRECONDITION_CONFLICT',?,?)
        """,
        blockedIntentId,
        inventoryId,
        capitalFindingId,
        inventoryId + ":1:" + capitalFindingId,
        finalPlanSha256,
        now(),
        now());
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          source_revision,final_plan_version,final_plan_sha256,target_kind,target_id,
          maintenance_repair_id,attempt_count,created_at,updated_at)
        values (?,?,?,0,'SUCCEEDED',?,1,1,?,'REPAIR',?,?,1,?,?)
        """,
        succeededIntentId,
        inventoryId,
        repairFindingId,
        inventoryId + ":1:" + repairFindingId,
        finalPlanSha256,
        repairId,
        repairId,
        now(),
        now());
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_attempt(
          id,publication_intent_id,attempt_no,idempotency_key,transition_kind,
          request_sha256,started_at)
        values (?,?,1,?,'REQUEST',?,?)
        """,
        attemptId,
        succeededIntentId,
        UUID.randomUUID(),
        "c".repeat(64),
        now());
    jdbc.update(
        """
        insert into inventory_publication_attempt_result(
          publication_attempt_id,outcome,repair_id,finished_at)
        values (?,'SUCCEEDED',?,?)
        """,
        attemptId,
        repairId,
        now());

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(10);
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select desired_asset_status from inventory_publication_intent where id=?",
                String.class,
                blockedIntentId))
        .isEqualTo("CAPITAL_REPAIR");
    assertThat(
            jdbc.queryForObject(
                "select desired_asset_status from inventory_publication_intent where id=?",
                String.class,
                succeededIntentId))
        .isEqualTo("REPAIR");
    assertThat(
            jdbc.queryForObject(
                "select target_id from inventory_publication_intent where id=?",
                UUID.class,
                succeededIntentId))
        .isEqualTo(repairId);
    assertThat(
            jdbc.queryForObject(
                "select repair_id from inventory_publication_attempt_result where publication_attempt_id=?",
                UUID.class,
                attemptId))
        .isEqualTo(repairId);
    assertThat(
            jdbc.queryForObject(
                "select asset_outcome_result is null from inventory_publication_attempt_result where publication_attempt_id=?",
                Boolean.class,
                attemptId))
        .isTrue();
  }

  @Test
  void v20BackfillsStableGenerationAndRequeuesOnlyCompletedObsoleteFurnitureConflict() {
    Flyway beforeV20 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("19")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV20.migrate().migrationsExecuted).isEqualTo(19);

    UUID recoveredInventoryId = UUID.randomUUID();
    UUID otherFailureInventoryId = UUID.randomUUID();
    UUID activeInventoryId = UUID.randomUUID();
    insertActiveSession(recoveredInventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertActiveSession(otherFailureInventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertActiveSession(activeInventoryId, UUID.randomUUID(), UUID.randomUUID());
    completeSession(recoveredInventoryId);
    completeSession(otherFailureInventoryId);

    UUID findingId = UUID.randomUUID();
    insertFinding(recoveredInventoryId, findingId, UUID.randomUUID(), "V20-OUTCOME");
    UUID publicationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          source_revision,desired_asset_status,attempt_count,created_at,updated_at)
        values (?,?,?,0,'READY',?,1,'REPAIR',0,?,?)
        """,
        publicationId,
        recoveredInventoryId,
        findingId,
        recoveredInventoryId + ":" + findingId,
        now(),
        now());

    UUID recoveredKey = UUID.randomUUID();
    insertFurnitureReconciliation(
        recoveredInventoryId, recoveredKey, "ASSET_SNAPSHOT_CONFLICT", 4);
    insertFurnitureReconciliation(
        otherFailureInventoryId, UUID.randomUUID(), "CATALOG_VERSION_CONFLICT", 5);
    insertFurnitureReconciliation(
        activeInventoryId, UUID.randomUUID(), "ASSET_SNAPSHOT_CONFLICT", 6);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(9);
    upgraded.validate();

    assertThat(
            jdbc.queryForObject(
                "select outcome_reapplication_no from inventory_publication_intent where id=?",
                Long.class,
                publicationId))
        .isZero();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_publication_intent set outcome_reapplication_no=-1 where id=?",
                    publicationId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(
            jdbc.queryForMap(
                """
                select state,intent_revision,idempotency_key,asset_snapshot_sha256,
                       review_sha256,request_sha256,request_body::text as request_body,
                       attempt_count,failure_code,completed_at
                from inventory_furniture_reconciliation_intent
                where inventory_id=?
                """,
                recoveredInventoryId))
        .containsEntry("state", "PENDING")
        .containsEntry("intent_revision", 8L)
        .containsEntry("idempotency_key", recoveredKey)
        .containsEntry("asset_snapshot_sha256", "a".repeat(64))
        .containsEntry("review_sha256", "b".repeat(64))
        .containsEntry("request_sha256", "c".repeat(64))
        .containsEntry("request_body", "{}")
        .containsEntry("attempt_count", 4)
        .containsEntry("failure_code", null)
        .containsEntry("completed_at", null);
    assertThat(
            jdbc.queryForMap(
                """
                select state,intent_revision,attempt_count,failure_code,completed_at
                from inventory_furniture_reconciliation_intent
                where inventory_id=?
                """,
                otherFailureInventoryId))
        .containsEntry("state", "BLOCKED")
        .containsEntry("intent_revision", 7L)
        .containsEntry("attempt_count", 5)
        .containsEntry("failure_code", "CATALOG_VERSION_CONFLICT")
        .matches(row -> row.get("completed_at") != null);
    assertThat(
            jdbc.queryForMap(
                """
                select state,intent_revision,attempt_count,failure_code,completed_at
                from inventory_furniture_reconciliation_intent
                where inventory_id=?
                """,
                activeInventoryId))
        .containsEntry("state", "BLOCKED")
        .containsEntry("intent_revision", 7L)
        .containsEntry("attempt_count", 6)
        .containsEntry("failure_code", "ASSET_SNAPSHOT_CONFLICT")
        .matches(row -> row.get("completed_at") != null);
  }

  @Test
  void v23FreezesOnlyExactFinalPlanFindingRevisionAndAssetEvidence() {
    Flyway beforeV23 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("22")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV23.migrate().migrationsExecuted).isEqualTo(22);

    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID exactFindingId = UUID.randomUUID();
    UUID staleFindingId = UUID.randomUUID();
    UUID exactAssetId = UUID.randomUUID();
    UUID staleAssetId = UUID.randomUUID();
    String finalPlanSha = "f".repeat(64);
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    insertFinding(inventoryId, exactFindingId, exactAssetId, "V23-EXACT");
    insertFinding(inventoryId, staleFindingId, staleAssetId, "V23-STALE");
    String exactPassport =
        """
        {"rentalType":"БК-2","dimensions":"2.4x6","finishing":"ЛДСП",
         "category":"Обычная","characteristics":["Электрика КК, Пластиковое окно"],
         "linoleum":false}
        """;
    jdbc.update(
        """
        update inventory_finding
        set finding_revision=2,passport_observation_state='PRESENT',
            passport_observation=?::jsonb
        where id=?
        """,
        exactPassport,
        exactFindingId);
    jdbc.update(
        """
        update inventory_finding
        set finding_revision=3,passport_observation_state='PRESENT',
            passport_observation='{"rentalType":"changed"}'::jsonb
        where id=?
        """,
        staleFindingId);
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,'DRAFT',0,0,?,'AUTO','AUTO',clock_timestamp(),clock_timestamp())
        """,
        inventoryId,
        finalPlanSha);
    insertV23NoWorkPlanEntry(inventoryId, exactFindingId, exactAssetId, 2, 0);
    insertV23NoWorkPlanEntry(inventoryId, staleFindingId, staleAssetId, 2, 1);
    UUID exactIntentId = insertV23Publication(inventoryId, exactFindingId, finalPlanSha, 2);
    UUID staleIntentId = insertV23Publication(inventoryId, staleFindingId, finalPlanSha, 2);

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(6);
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select asset_passport_observation->>'presence' as presence,
                       asset_passport_observation#>>'{value,rentalType}' as rental_type,
                       asset_passport_observation#>>'{value,characteristics,0}' as characteristics
                from inventory_publication_intent where id=?
                """,
                exactIntentId))
        .containsEntry("presence", "PRESENT")
        .containsEntry("rental_type", "БК-2")
        .containsEntry("characteristics", "Электрика КК, Пластиковое окно");
    assertThat(
            jdbc.queryForMap(
                """
                select asset_passport_observation->>'presence' as presence,
                       asset_passport_observation->'value' = 'null'::jsonb as null_value
                from inventory_publication_intent where id=?
                """,
                staleIntentId))
        .containsEntry("presence", "ABSENT")
        .containsEntry("null_value", true);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_publication_intent set asset_passport_observation='{}'::jsonb where id=?",
                    exactIntentId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v11BackfillsLegacyFrozenPlansWithCanonicalAutomaticLogisticsPlanning() {
    Flyway beforeV11 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("10")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV11.migrate().migrationsExecuted).isEqualTo(10);

    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "V11-LOGISTICS");
    jdbc.update(
        """
        insert into finding_plan_snapshot(
          finding_id,finding_revision,inventory_id,plan_mode,catalog_version_id,
          plan_fingerprint_sha256,source_snapshot,frozen_at)
        values (?,?,?,'AUTO',?,?,?::jsonb,?)
        """,
        findingId,
        0,
        inventoryId,
        UUID.randomUUID(),
        "1".repeat(64),
        "{\"lines\":[],\"stages\":[]}",
        now());

    Flyway upgraded =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("11")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();
    assertThat(
            jdbc.queryForMap(
                """
                select logistics_planning_mode,logistics_scheduled_date
                from finding_plan_snapshot
                where finding_id=? and finding_revision=0
                """,
                findingId))
        .containsEntry("logistics_planning_mode", "AUTO")
        .containsEntry("logistics_scheduled_date", null);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update finding_plan_snapshot
                    set logistics_planning_mode='FIXED_DATE', logistics_scheduled_date=null
                    where finding_id=? and finding_revision=0
                    """,
                    findingId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v12CanonicalizesFrozenMovementAndRemovesLegacyStageRepresentation() {
    Flyway beforeV12 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("11")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV12.migrate().migrationsExecuted).isEqualTo(11);

    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    String legacyFingerprint = "b".repeat(64);
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "V12-MOVEMENT");
    jdbc.update(
        """
        update inventory_finding
        set inspection='WORK_STAGED', maintenance_plan_fingerprint_sha256=?,
            inspection_asset_version=0,inspection_warehouse_id=?,inspection_status='WAREHOUSE',
            inspection_display_canonical_number='V12-MOVEMENT',
            inspection_passport_snapshot='{}'::jsonb,inspection_contents_snapshot='[]'::jsonb,
            inspection_repairs_snapshot='[]'::jsonb
        where id=?
        """,
        legacyFingerprint,
        warehouseId,
        findingId);
    jdbc.update(
        """
        insert into finding_plan_snapshot(
          finding_id,finding_revision,inventory_id,plan_mode,logistics_planning_mode,
          logistics_scheduled_date,catalog_version_id,plan_fingerprint_sha256,source_snapshot,
          frozen_at)
        values (?,?,?,'MANUAL','FIXED_DATE',?,?,?,?::jsonb,?)
        """,
        findingId,
        0,
        inventoryId,
        LocalDate.of(2026, 8, 12),
        UUID.randomUUID(),
        legacyFingerprint,
        """
        {
          "priority":3,
          "moveToRepairRequired":false,
          "moveFromRepairRequired":true,
          "logisticsPlanningMode":"FIXED_DATE",
          "logisticsScheduledDate":"2026-08-12",
          "stages":[
            {"kind":"MOVE_TO_REPAIR","order":0},
            {"kind":"REPAIR_WORK","order":1,"id":"00000000-0000-0000-0000-000000000901"}
          ]
        }
        """,
        now());
    jdbc.update(
        """
        insert into finding_plan_stage(
          row_id,finding_id,finding_revision,stage_no,stage_kind,catalog_node_id,catalog_node_name,
          routing_queue_id,routing_queue_name,routing_queue_type,movement_required,photo_required,
          safe_snapshot)
        values (?,?,0,0,'MOVE_TO_REPAIR',?,'Inbound',?,'Logistics','LOGISTICS',true,false,
          '{}'::jsonb)
        """,
        UUID.randomUUID(),
        findingId,
        UUID.randomUUID(),
        UUID.randomUUID());
    jdbc.update(
        """
        insert into finding_plan_stage(
          row_id,finding_id,finding_revision,stage_no,stage_kind,catalog_node_id,catalog_node_name,
          routing_queue_id,routing_queue_name,routing_queue_type,movement_required,photo_required,
          safe_snapshot)
        values (?,?,0,1,'REPAIR_WORK',?,'Repair',?,'Maintenance','MAINTENANCE',false,true,
          '{}'::jsonb)
        """,
        UUID.randomUUID(),
        findingId,
        UUID.randomUUID(),
        UUID.randomUUID());

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(17);
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select movement_to_repair,movement_to_shipment,logistics_planning_mode,
                       logistics_scheduled_date::text as logistics_scheduled_date,
                       source_snapshot->>'movementToRepair' as source_inbound,
                       source_snapshot->>'movementToShipment' as source_outbound,
                       source_snapshot->>'logisticsPlanningMode' as source_mode,
                       jsonb_exists(source_snapshot, 'moveToRepairRequired') as legacy_inbound,
                       jsonb_exists(source_snapshot, 'moveFromRepairRequired') as legacy_outbound,
                       jsonb_array_length(source_snapshot->'stages') as source_stage_count,
                       source_snapshot #>> '{stages,0,kind}' as source_stage_kind,
                       snapshot_schema_version,
                       plan_fingerprint_sha256
                from finding_plan_snapshot where finding_id=? and finding_revision=0
                """,
                findingId))
        .containsEntry("movement_to_repair", true)
        .containsEntry("movement_to_shipment", true)
        .containsEntry("logistics_planning_mode", "FIXED_DATE")
        .containsEntry("logistics_scheduled_date", "2026-08-12")
        .containsEntry("source_inbound", "true")
        .containsEntry("source_outbound", "true")
        .containsEntry("source_mode", "FIXED_DATE")
        .containsEntry("legacy_inbound", false)
        .containsEntry("legacy_outbound", false)
        .containsEntry("source_stage_count", 1)
        .containsEntry("source_stage_kind", "REPAIR_WORK");
    assertThat(
            jdbc.queryForObject(
                "select snapshot_schema_version from finding_plan_snapshot where finding_id=? and finding_revision=0",
                Short.class,
                findingId))
        .isEqualTo((short) 1);
    assertThat(
            jdbc.queryForMap(
                """
                select stage_no,stage_kind
                from finding_plan_stage where finding_id=? and finding_revision=0
                """,
                findingId))
        .containsEntry("stage_no", 0)
        .containsEntry("stage_kind", "REPAIR_WORK");
    assertThat(columns("finding_plan_stage")).doesNotContain("movement_required");
    String canonicalFingerprint =
        jdbc.queryForObject(
            """
            select encode(sha256(convert_to(source_snapshot::text, 'UTF8')), 'hex')
            from finding_plan_snapshot where finding_id=? and finding_revision=0
            """,
            String.class,
            findingId);
    assertThat(
            jdbc.queryForObject(
                """
                select plan_fingerprint_sha256
                from finding_plan_snapshot where finding_id=? and finding_revision=0
                """,
                String.class,
                findingId))
        .isEqualTo(canonicalFingerprint);
    assertThat(
            jdbc.queryForObject(
                "select maintenance_plan_fingerprint_sha256 from inventory_finding where id=?",
                String.class,
                findingId))
        .isEqualTo(canonicalFingerprint);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update finding_plan_snapshot
                    set movement_to_repair=false
                    where finding_id=? and finding_revision=0
                    """,
                    findingId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v10AddsNullableFindingCoverWithoutRewritingLegacyRows() {
    Flyway beforeV10 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("9")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV10.migrate().migrationsExecuted).isEqualTo(9);

    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "V10-COVER");

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(19);
    upgraded.validate();
    assertThat(columns("inventory_finding")).contains("cover_media_id");
    assertThat(
            jdbc.queryForObject(
                "select cover_media_id from inventory_finding where id=?",
                UUID.class,
                findingId))
        .isNull();
  }

  @Test
  void v9UpgradesFrozenPlanStageIdentityToUuidNameAndTypeSnapshots() {
    Flyway beforeV9 = Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("8")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .load();
    assertThat(beforeV9.migrate().migrationsExecuted).isEqualTo(8);

    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID catalogNodeId = UUID.randomUUID();
    UUID routingQueueId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "V9-FROZEN-STAGE");
    jdbc.update(
        """
        insert into finding_plan_snapshot(
          finding_id,finding_revision,inventory_id,plan_mode,catalog_version_id,
          plan_fingerprint_sha256,source_snapshot,frozen_at)
        values (?,?,?,'AUTO',?,?,?::jsonb,?)
        """,
        findingId,
        0,
        inventoryId,
        UUID.randomUUID(),
        "9".repeat(64),
        "{\"catalogNodeCode\":\"obsolete\",\"queueCode\":\"REPAIR\"}",
        now());
    jdbc.update(
        """
        insert into finding_plan_stage(
          row_id,finding_id,finding_revision,stage_no,stage_kind,routing_queue_id,
          routing_queue_code,routing_queue_kind,movement_required,photo_required,safe_snapshot)
        values (?,?,0,0,'REPAIR_WORK',?,'REPAIR','REPAIR',false,false,?::jsonb)
        """,
        UUID.randomUUID(),
        findingId,
        routingQueueId,
        "{\"catalogNodeId\":\"" + catalogNodeId + "\",\"catalogNodeCode\":\"obsolete\","
            + "\"queueCode\":\"REPAIR\"}");

    Flyway upgraded = flyway(MIGRATIONS);
    assertThat(upgraded.migrate().migrationsExecuted).isEqualTo(20);
    upgraded.validate();
    assertThat(columns("finding_plan_stage"))
        .contains(
            "catalog_node_id", "catalog_node_name", "routing_queue_id", "routing_queue_name",
            "routing_queue_type")
        .doesNotContain("routing_queue_code", "routing_queue_kind");
    assertThat(jdbc.queryForMap(
        """
        select catalog_node_id,catalog_node_name,routing_queue_id,routing_queue_name,routing_queue_type,
               safe_snapshot::text
        from finding_plan_stage
        where finding_id=? and finding_revision=0 and stage_no=0
        """,
        findingId))
        .containsEntry("catalog_node_id", catalogNodeId)
        .containsEntry("catalog_node_name", "Позиция каталога недоступна")
        .containsEntry("routing_queue_id", routingQueueId)
        .containsEntry("routing_queue_name", "Очередь недоступна")
        .containsEntry("routing_queue_type", "REPAIR")
        .containsEntry("safe_snapshot", "{\"catalogNodeId\": \"" + catalogNodeId + "\"}");
  }

  @Test
  void v6SanitizesOldPanelAndTechnicalOnlyExpectedPassports() {
    Flyway beforeV6 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("5")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV6.migrate().migrationsExecuted).isEqualTo(5);

    UUID inventoryId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    UUID oldPanelFindingId = UUID.randomUUID();
    UUID oldPanelExpectedId = UUID.randomUUID();
    UUID technicalOnlyFindingId = UUID.randomUUID();
    UUID technicalOnlyExpectedId = UUID.randomUUID();
    insertExpectedFinding(
        inventoryId,
        oldPanelFindingId,
        oldPanelExpectedId,
        UUID.randomUUID(),
        "OLDPANEL1",
        0,
        true);
    insertExpectedFinding(
        inventoryId,
        technicalOnlyFindingId,
        technicalOnlyExpectedId,
        UUID.randomUUID(),
        "TECHNICAL2",
        1,
        true);
    String oldPanelSnapshot = """
        {
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
        oldPanelSnapshot.replace("\"source\": \"old-panel-rental-items-v1\",", "");
    jdbc.update(
        """
        update inventory_expected_item
        set safe_passport_snapshot=?::jsonb
        where row_id=?
        """,
        oldPanelSnapshot,
        oldPanelExpectedId);
    jdbc.update(
        """
        update inventory_expected_item
        set safe_passport_snapshot=?::jsonb
        where row_id=?
        """,
        technicalOnlySnapshot,
        technicalOnlyExpectedId);

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(23);
    latest.validate();

    assertThat(jdbc.queryForObject(
        """
        select count(*)
        from inventory_expected_item
        where inventory_id=?
          and (
            (safe_passport_snapshot->'passport')::text ilike '%legacy%'
            or (safe_passport_snapshot->'passport')::text ilike
              '%old-panel-rental-items-v1%'
            or (safe_passport_snapshot->'passport')::text ilike '%locationNodeId%'
            or (safe_passport_snapshot->'passport')::text ilike '%hasPhotos%'
            or (safe_passport_snapshot->'passport')::text ilike '%photoCount%'
            or (safe_passport_snapshot->'passport')::text ilike '%mainPhotoUrl%'
            or (safe_passport_snapshot->'passport')::text ilike '%previewPhotoUrls%')
        """,
        Integer.class,
        inventoryId)).isZero();
    assertThat(jdbc.queryForObject(
        """
        select count(*)
        from inventory_expected_item
        where inventory_id=?
          and safe_passport_snapshot->'passport'->>'price'='42000'
          and safe_passport_snapshot->'passport'->>'shipmentDate'='2026-07-20'
          and safe_passport_snapshot->'passport'->>'tenant'='ООО Полезные данные'
          and safe_passport_snapshot->'other'->>'source'='inventory-domain-source'
        """,
        Integer.class,
        inventoryId)).isEqualTo(2);
    assertThat(latest.migrate().migrationsExecuted).isZero();
  }

  @Test
  void v8RemovesNewFromMutableInventoryStatusProjectionsAndCaches() {
    Flyway beforeV8 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("7")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(beforeV8.migrate().migrationsExecuted).isEqualTo(7);

    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID expectedId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    insertExpectedFinding(
        inventoryId, findingId, expectedId, assetId, "NEW-STATUS-EXPECTED", 0, true);
    jdbc.update(
        "update inventory_expected_item set asset_status_snapshot='NEW' where row_id=?", expectedId);
    jdbc.update(
        """
        update inventory_finding
        set current_warehouse_id=?,current_status='NEW',current_display_canonical_number=?,
            current_passport_snapshot='{}'::jsonb,current_contents_snapshot='[]'::jsonb,
            current_repairs_snapshot='[]'::jsonb,inspection='READY',inspection_asset_version=0,
            inspection_warehouse_id=?,inspection_status='NEW',inspection_display_canonical_number=?,
            inspection_passport_snapshot='{}'::jsonb,inspection_contents_snapshot='[]'::jsonb,
            inspection_repairs_snapshot='[]'::jsonb
        where id=?
        """,
        warehouseId,
        "NEW-STATUS-EXPECTED",
        warehouseId,
        "NEW-STATUS-EXPECTED",
        findingId);
    jdbc.update(
        """
        insert into inventory_membership_movement(
          id,inventory_id,source_event_id,asset_id,movement_type,finding_origin,
          display_canonical_number,from_warehouse_id,to_warehouse_id,asset_status,
          tenant_snapshot,occurred_at)
        values (?,?,?,?,'ARRIVED','EXPECTED',?,null,?,'NEW',null,clock_timestamp())
        """,
        UUID.randomUUID(),
        inventoryId,
        UUID.randomUUID(),
        assetId,
        "NEW-STATUS-EXPECTED",
        warehouseId);

    jdbc.update(
        """
        insert into inventory_validation_snapshot(
          inventory_id,session_revision,validation_sha256,acknowledgement_sha256,
          validated_at,snapshot_body)
        values (?,0,?,?,clock_timestamp(),?::jsonb)
        """,
        inventoryId,
        "a".repeat(64),
        "b".repeat(64),
        "{\"validation\":{\"assets\":[{\"status\":\"NEW\"}]}}");
    jdbc.update(
        """
        insert into inventory_validation_item(
          inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
        values (?,?,true,0,'NEW',?,?)
        """,
        inventoryId,
        assetId,
        warehouseId,
        findingId);

    UUID cachedInventoryId = UUID.randomUUID();
    UUID cachedFindingId = UUID.randomUUID();
    UUID cachedAssetId = UUID.randomUUID();
    UUID cachedWarehouseId = UUID.randomUUID();
    insertActiveSession(cachedInventoryId, cachedWarehouseId, UUID.randomUUID());
    insertFinding(cachedInventoryId, cachedFindingId, cachedAssetId, "NEW-STATUS-CACHE");
    jdbc.update(
        """
        insert into inventory_validation_snapshot(
          inventory_id,session_revision,validation_sha256,acknowledgement_sha256,
          validated_at,snapshot_body)
        values (?,0,?,?,clock_timestamp(),'{}'::jsonb)
        """,
        cachedInventoryId,
        "c".repeat(64),
        "d".repeat(64));
    jdbc.update(
        """
        insert into inventory_validation_item(
          inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
        values (?,?,true,0,'NEW',?,?)
        """,
        cachedInventoryId,
        cachedAssetId,
        cachedWarehouseId,
        cachedFindingId);

    OffsetDateTime current = now();
    UUID idempotencyKey = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_idempotency_record(
          subject_id,command_scope,idempotency_key,request_sha256,state,attempt_count,
          response_status,response_body,created_at,updated_at,expires_at)
        values (?, 'new-status-cache', ?, ?, 'COMPLETED', 1, 200, ?::jsonb, ?, ?, ?)
        """,
        UUID.randomUUID(),
        idempotencyKey,
        "e".repeat(64),
        """
        {
          "status":"NEW",
          "origin":"ADDED_NEW",
          "count":2,
          "active":true,
          "optional":null,
          "items":[1,false,null,"NEW"]
        }
        """,
        current,
        current,
        current.plusDays(7));

    Flyway latest = flyway(MIGRATIONS);
    assertThat(latest.migrate().migrationsExecuted).isEqualTo(21);
    latest.validate();

    assertThat(
            jdbc.queryForObject(
                "select asset_status_snapshot from inventory_expected_item where row_id=?",
                String.class,
                expectedId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select current_status from inventory_finding where id=?", String.class, findingId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select inspection_status from inventory_finding where id=?", String.class, findingId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select asset_status from inventory_membership_movement where inventory_id=?",
                String.class,
                inventoryId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select asset_status from inventory_validation_item where inventory_id=?",
                String.class,
                cachedInventoryId))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_validation_snapshot where inventory_id=?",
                Integer.class,
                inventoryId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select response_body->>'status' from inventory_idempotency_record where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isEqualTo("FREE");
    assertThat(
            jdbc.queryForObject(
                "select response_body->>'origin' from inventory_idempotency_record where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isEqualTo("ADDED_NEW");
    assertThat(
            jdbc.queryForObject(
                "select response_body->>'count' from inventory_idempotency_record where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isEqualTo("2");
    assertThat(
            jdbc.queryForObject(
                "select response_body->>'active' from inventory_idempotency_record where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isEqualTo("true");
    assertThat(
            jdbc.queryForObject(
                "select jsonb_typeof(response_body->'optional') from inventory_idempotency_record where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isEqualTo("null");
    assertThat(
            jdbc.queryForObject(
                "select response_body#>>'{items,3}' from inventory_idempotency_record where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isEqualTo("FREE");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_membership_movement set asset_status='FREE' where inventory_id=?",
                    inventoryId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_expected_item(
                      row_id,inventory_id,finding_id,item_order,asset_id,asset_version_snapshot,
                      asset_status_snapshot,display_canonical_number,identity_match_key,
                      safe_passport_snapshot,safe_contents_snapshot,captured_at)
                    values (?,?,?,?,?,0,'NEW',?,?, '{}'::jsonb,'[]'::jsonb,clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    inventoryId,
                    UUID.randomUUID(),
                    99,
                    UUID.randomUUID(),
                    "NEW-STATUS-REJECTED",
                    "NEWSTATUSREJECTED"))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void v5RestoresLivePopulationAndBuffersMovementsAfterV4() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(MIGRATIONS)
        .target("3")
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .load()
        .migrate();
    UUID operationId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID baselineFindingId = UUID.randomUUID();
    UUID arrivedFindingId = UUID.randomUUID();
    UUID baselineExpectedId = UUID.randomUUID();
    UUID arrivedExpectedId = UUID.randomUUID();
    OffsetDateTime current = now();
    jdbc.update(
        """
        insert into inventory_start_operation(
          operation_id,subject_id,idempotency_key,request_sha256,warehouse_id,state,
          created_at,updated_at,expires_at)
        values (?,?,?,?,?,'CAPTURED',?,?,?)
        """,
        operationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "2".repeat(64),
        warehouseId,
        current,
        current,
        current.plusDays(7));
    jdbc.update(
        """
        insert into inventory_start_capture_attempt(
          operation_id,technical_attempt,request_fingerprint,requested_at)
        values (?,1,?,?)
        """,
        operationId,
        "3".repeat(64),
        current);
    jdbc.update(
        """
        insert into inventory_start_capture_result(
          operation_id,technical_attempt,outcome,capture_id,membership_digest,total_count,
          expires_at,recorded_at)
        values (?,1,'CAPTURED',?,?,1,?,?)
        """,
        operationId,
        UUID.randomUUID(),
        "4".repeat(64),
        current.plusMinutes(30),
        current);
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,2,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,2,?,?,
          'Inventory operator',?::jsonb,?,?,?)
        """,
        inventoryId,
        warehouseId,
        operationId,
        UUID.randomUUID(),
        "5".repeat(64),
        "4".repeat(64),
        UUID.randomUUID(),
        actor(),
        current,
        current,
        current);
    insertExpectedFinding(
        inventoryId,
        baselineFindingId,
        baselineExpectedId,
        UUID.randomUUID(),
        "START-1",
        0,
        true);
    insertExpectedFinding(
        inventoryId,
        arrivedFindingId,
        arrivedExpectedId,
        UUID.randomUUID(),
        "ARRIVED-2",
        1,
        true);
    UUID arrivedEventId = UUID.randomUUID();
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,event_body,event_sha256)
        values (?,'FINDING',?,0,'inventory.finding.added.v1',1,?,?,?,
          '{"origin":"EXPECTED"}'::jsonb,?)
        """,
        arrivedEventId,
        arrivedFindingId.toString(),
        current.plusMinutes(5),
        current.plusMinutes(5),
        UUID.randomUUID(),
        "6".repeat(64));

    Flyway v4 =
        Flyway.configure()
            .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
            .locations(MIGRATIONS)
            .target("4")
            .baselineOnMigrate(false)
            .cleanDisabled(true)
            .validateOnMigrate(true)
            .load();
    assertThat(v4.migrate().migrationsExecuted).isOne();

    assertThat(
            jdbc.queryForObject(
                "select expected_population_count from inventory_session where id=?",
                Integer.class,
                inventoryId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_expected_item where inventory_id=?",
                Integer.class,
                inventoryId))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                "select origin,expected_item_id,membership_active from inventory_finding where id=?",
                baselineFindingId))
        .containsEntry("origin", "EXPECTED")
        .containsEntry("expected_item_id", baselineExpectedId)
        .containsEntry("membership_active", true);
    assertThat(
            jdbc.queryForMap(
                "select origin,expected_item_id,membership_active from inventory_finding where id=?",
                arrivedFindingId))
        .containsEntry("origin", "UNEXPECTED_EXISTING")
        .containsEntry("expected_item_id", null)
        .containsEntry("membership_active", true);

    assertThat(flyway(MIGRATIONS).migrate().migrationsExecuted).isEqualTo(24);
    assertThat(
            jdbc.queryForObject(
                "select expected_population_count from inventory_session where id=?",
                Integer.class,
                inventoryId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_expected_item where inventory_id=?",
                Integer.class,
                inventoryId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForMap(
                "select origin,expected_item_id,membership_active from inventory_finding where id=?",
                arrivedFindingId))
        .containsEntry("origin", "EXPECTED")
        .containsEntry("membership_active", true);
    assertThat(
            jdbc.queryForMap(
                """
                select movement_type,finding_origin,display_canonical_number,
                       from_warehouse_id,to_warehouse_id,source_event_id
                  from inventory_membership_movement
                 where inventory_id=?
                """,
                inventoryId))
        .containsEntry("movement_type", "ARRIVED")
        .containsEntry("finding_origin", "EXPECTED")
        .containsEntry("display_canonical_number", "ARRIVED-2")
        .containsEntry("from_warehouse_id", null)
        .containsEntry("to_warehouse_id", warehouseId)
        .containsEntry("source_event_id", arrivedEventId);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_membership_movement set asset_status='RENTED' "
                        + "where inventory_id=?",
                    inventoryId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void findingOwnerProofLifecycleDefaultsActiveAtZeroAndRejectsNegativeRevision() {
    flyway(MIGRATIONS).migrate();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "AB12");

    assertThat(
            jdbc.queryForMap(
                "select owner_proof_revision,owner_proof_active,membership_active "
                    + "from inventory_finding where id=?",
                findingId))
        .containsEntry("owner_proof_revision", 0L)
        .containsEntry("owner_proof_active", true)
        .containsEntry("membership_active", true);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_finding set owner_proof_revision=-1 where id=?", findingId))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_finding set membership_active=false where id=?", findingId))
        .isInstanceOf(DataIntegrityViolationException.class);
    jdbc.update(
        "update inventory_finding set owner_proof_active=false,membership_active=false where id=?",
        findingId);
  }

  @Test
  void appliedChecksumDriftIsRejected(@TempDir Path directory) throws IOException {
    Path migration = directory.resolve("V1__inventory_schema.sql");
    try (var source = requireResource("db/migration/V1__inventory_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();
    Files.writeString(
        migration,
        Files.readString(migration)
            .replace("cancellation_reason varchar(2000)", "cancellation_reason varchar(1999)"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void nonEmptyUnversionedSchemaIsRejectedWithoutAutomaticBaseline() {
    jdbc.execute("create table historical_inventory_evidence (id uuid primary key)");

    assertThatThrownBy(() -> flyway(MIGRATIONS).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(
            jdbc.queryForObject("select to_regclass('public.flyway_schema_history')", String.class))
        .isNull();
  }

  @Test
  void databaseRejectsActiveSessionFindingSourceAndUnsafeDltViolations() throws Exception {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID firstSession = UUID.randomUUID();
    insertActiveSession(firstSession, warehouseId, UUID.randomUUID());

    assertThatThrownBy(() -> insertActiveSession(UUID.randomUUID(), warehouseId, UUID.randomUUID()))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID terminalSession = UUID.randomUUID();
    insertActiveSession(terminalSession, UUID.randomUUID(), UUID.randomUUID());
    jdbc.update(
        """
        update inventory_session
           set lifecycle='CANCELLED', cancelled_by_actor_ref=?::jsonb,
               cancellation_reason='reviewed', cancelled_at=clock_timestamp(),
               session_revision=1, updated_at=clock_timestamp()
         where id=?
        """,
        actor(),
        terminalSession);

    UUID finding = UUID.randomUUID();
    insertFinding(terminalSession, finding, UUID.randomUUID(), "AB12");
    assertThatThrownBy(
            () -> insertFinding(terminalSession, UUID.randomUUID(), UUID.randomUUID(), "AB12"))
        .isInstanceOf(DataIntegrityViolationException.class);

    UUID sourceId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_source_attachment(
          id,inventory_id,finding_id,source_key,source_revision,technical_attempt_id,
          request_sha256,state,created_at,updated_at)
        values (?,?,?,?,1,?,?,'PENDING',?,?)
        """,
        sourceId,
        terminalSession,
        finding,
        terminalSession + ":" + finding,
        UUID.randomUUID(),
        "1".repeat(64),
        now(),
        now());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_source_attachment(
                      id,inventory_id,finding_id,source_key,source_revision,technical_attempt_id,
                      request_sha256,state,created_at,updated_at)
                    values (?,?,?,?,1,?,?,'PENDING',?,?)
                    """,
                    UUID.randomUUID(),
                    terminalSession,
                    finding,
                    terminalSession + ":" + finding,
                    UUID.randomUUID(),
                    "2".repeat(64),
                    now(),
                    now()))
        .isInstanceOf(DataIntegrityViolationException.class);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into sanitized_dead_letter(
                      dlt_id,destination,message_sha256,failure_code,safe_body,body_sha256,
                      status,attempt_count,next_attempt_at,created_at)
                    values (?,'rwms.inventory.dlt.v1',?,'VALIDATION_REJECTED',?::jsonb,?,
                      'PENDING',0,clock_timestamp(),clock_timestamp())
                    """,
                    UUID.randomUUID(),
                    "3".repeat(64),
                    "{\"failureCode\":\"VALIDATION_REJECTED\",\"messageSha256\":\""
                        + "3".repeat(64)
                        + "\",\"recordedAt\":\"2026-07-17T12:00:00Z\",\"rawError\":\"secret\"}",
                    "4".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void domainEventsAndPublicationAttemptsAreDatabaseAppendOnly() {
    flyway(MIGRATIONS).migrate();
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          recorded_at,correlation_id,event_body,event_sha256)
        values (?,'SESSION',?,0,'inventory.session.started.v1',1,
          clock_timestamp(),?,'{}'::jsonb,?)
        """,
        eventId,
        aggregateId.toString(),
        UUID.randomUUID(),
        "5".repeat(64));

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update domain_event set event_sha256=? where event_id=?",
                    "6".repeat(64),
                    eventId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void startCaptureAndReleaseRecoveryLedgerIsDurableMonotonicAndAppendOnly() {
    flyway(MIGRATIONS).migrate();
    UUID operationId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime createdAt = now();
    jdbc.update(
        """
        insert into inventory_start_operation(
          operation_id,subject_id,idempotency_key,request_sha256,warehouse_id,state,
          created_at,updated_at,expires_at)
        values (?,?,?,?,?,'REQUESTED',?,?,?)
        """,
        operationId,
        subjectId,
        idempotencyKey,
        "7".repeat(64),
        warehouseId,
        createdAt,
        createdAt,
        createdAt.plusDays(7));
    jdbc.update(
        """
        insert into inventory_start_capture_attempt(
          operation_id,technical_attempt,request_fingerprint,requested_at)
        values (?,1,?,clock_timestamp())
        """,
        operationId,
        "8".repeat(64));
    UUID captureId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_start_capture_result(
          operation_id,technical_attempt,outcome,capture_id,membership_digest,total_count,
          expires_at,recorded_at)
        values (?,1,'CAPTURED',?,?,0,clock_timestamp()+interval '30 minutes',clock_timestamp())
        """,
        operationId,
        captureId,
        "9".repeat(64));
    jdbc.update(
        """
        insert into inventory_capture_release(
          operation_id,technical_attempt,capture_id,state,attempt_count,next_attempt_at,created_at)
        values (?,1,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        """,
        operationId,
        captureId);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_start_capture_attempt set technical_attempt=2 where operation_id=?",
                    operationId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_start_capture_attempt(
                      operation_id,technical_attempt,request_fingerprint,requested_at)
                    values (?,0,?,clock_timestamp())
                    """,
                    operationId,
                    "a".repeat(64)))
        .isInstanceOf(DataIntegrityViolationException.class);
    assertThat(count("inventory_capture_release")).isOne();
  }

  @Test
  void validationRowsDistinguishMissingAndEnforceOwningWarehouseForFoundAssets() {
    flyway(MIGRATIONS).migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    insertActiveSession(inventoryId, warehouseId, UUID.randomUUID());
    jdbc.update(
        """
        insert into inventory_validation_snapshot(
          inventory_id,session_revision,validation_sha256,acknowledgement_sha256,
          validated_at,snapshot_body)
        values (?,0,?,?,clock_timestamp(),'{}'::jsonb)
        """,
        inventoryId,
        "b".repeat(64),
        "c".repeat(64));
    UUID foundFinding = UUID.randomUUID();
    UUID missingFinding = UUID.randomUUID();
    UUID crossWarehouseFinding = UUID.randomUUID();
    insertFinding(inventoryId, foundFinding, UUID.randomUUID(), "FOUND");
    insertFinding(inventoryId, missingFinding, UUID.randomUUID(), "MISSING");
    insertFinding(inventoryId, crossWarehouseFinding, UUID.randomUUID(), "CROSS");
    jdbc.update(
        """
        insert into inventory_validation_item(
          inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
        values (?,?,true,3,'FREE',?,?)
        """,
        inventoryId,
        UUID.randomUUID(),
        warehouseId,
        foundFinding);
    jdbc.update(
        """
        insert into inventory_validation_item(
          inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
        values (?,?,false,null,null,null,?)
        """,
        inventoryId,
        UUID.randomUUID(),
        missingFinding);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    insert into inventory_validation_item(
                      inventory_id,asset_id,found,asset_version,asset_status,warehouse_id,finding_id)
                    values (?,?,true,3,'FREE',?,?)
                    """,
                    inventoryId,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    crossWarehouseFinding))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void publicationAttemptRequestPrecedesAndCannotBeMutatedWithSeparateTerminalResult() {
    flyway(MIGRATIONS).migrate();
    UUID inventoryId = UUID.randomUUID();
    insertActiveSession(inventoryId, UUID.randomUUID(), UUID.randomUUID());
    UUID findingId = UUID.randomUUID();
    insertFinding(inventoryId, findingId, UUID.randomUUID(), "PUB");
    UUID intentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          source_revision,desired_asset_status,attempt_count,created_at,updated_at)
        values (?,?,?,0,'READY',?,1,'REPAIR',0,clock_timestamp(),clock_timestamp())
        """,
        intentId,
        inventoryId,
        findingId,
        inventoryId + ":" + findingId);
    UUID attemptId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_attempt(
          id,publication_intent_id,attempt_no,idempotency_key,transition_kind,
          request_sha256,started_at)
        values (?,?,1,?,'REQUEST',?,clock_timestamp())
        """,
        attemptId,
        intentId,
        UUID.randomUUID(),
        "d".repeat(64));

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from inventory_publication_attempt attempt
                left join inventory_publication_attempt_result result
                  on result.publication_attempt_id=attempt.id
                where attempt.id=? and result.publication_attempt_id is null
                """,
                Integer.class,
                attemptId))
        .isOne();
    jdbc.update(
        """
        insert into inventory_publication_attempt_result(
          publication_attempt_id,outcome,repair_id,finished_at)
        values (?,'SUCCEEDED',?,clock_timestamp())
        """,
        attemptId,
        UUID.randomUUID());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_publication_attempt_result set outcome='BLOCKED' where publication_attempt_id=?",
                    attemptId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("delete from inventory_publication_attempt where id=?", attemptId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  private void insertV19WorkPlanEntry(
      UUID inventoryId,
      UUID findingId,
      UUID assetId,
      int planOrder,
      boolean forceCapitalRepair,
      String planFingerprintSha256) {
    jdbc.update(
        """
        insert into inventory_final_plan_entry(
          inventory_id,final_plan_version,finding_id,finding_revision,asset_id,asset_version,
          plan_fingerprint_sha256,has_work,target_kind,plan_order,priority,movement_to_repair,
          movement_scheduled_date,repair_scheduled_date,collision_candidates,
          reconciliation_decision,force_capital_repair)
        values (?,1,?,0,?,7,?,true,'REPAIR',?,3,false,null,?,'[]'::jsonb,null,?)
        """,
        inventoryId,
        findingId,
        assetId,
        planFingerprintSha256,
        planOrder,
        LocalDate.of(2026, 8, 25),
        forceCapitalRepair);
  }

  private void insertV23NoWorkPlanEntry(
      UUID inventoryId, UUID findingId, UUID assetId, long findingRevision, int planOrder) {
    jdbc.update(
        """
        insert into inventory_final_plan_entry(
          inventory_id,final_plan_version,finding_id,finding_revision,asset_id,asset_version,
          plan_fingerprint_sha256,has_work,target_kind,plan_order,priority,movement_to_repair,
          movement_scheduled_date,repair_scheduled_date,collision_candidates,
          reconciliation_decision,force_capital_repair)
        values (?,1,?,?,?,7,null,false,null,?,null,false,null,null,'[]'::jsonb,null,false)
        """,
        inventoryId,
        findingId,
        findingRevision,
        assetId,
        planOrder);
  }

  private UUID insertV23Publication(
      UUID inventoryId, UUID findingId, String finalPlanSha, long sourceRevision) {
    UUID publicationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,
          final_plan_version,final_plan_sha256,target_kind,desired_asset_status,source_revision,
          outcome_reapplication_no,attempt_count,created_at,updated_at)
        values (?,?,?,0,'READY',?,1,?,null,'FREE',?,0,0,clock_timestamp(),clock_timestamp())
        """,
        publicationId,
        inventoryId,
        findingId,
        inventoryId + ":1:" + findingId,
        finalPlanSha,
        sourceRevision);
    return publicationId;
  }

  private void completeSession(UUID inventoryId) {
    jdbc.update(
        """
        update inventory_session
           set lifecycle='COMPLETED',session_revision=session_revision+1,
               completion_validation_sha256=?,completion_acknowledgement_sha256=?,
               validated_at=started_at,completed_by_actor_ref=started_actor_ref,
               completed_at=started_at,updated_at=started_at
         where id=?
        """,
        "d".repeat(64),
        "e".repeat(64),
        inventoryId);
  }

  private void insertFurnitureReconciliation(
      UUID inventoryId, UUID idempotencyKey, String failureCode, int attemptCount) {
    OffsetDateTime current = now();
    jdbc.update(
        """
        insert into inventory_furniture_reconciliation_intent(
          inventory_id,intent_revision,state,idempotency_key,asset_snapshot_sha256,
          review_sha256,request_sha256,request_body,attempt_count,next_attempt_at,
          failure_code,created_at,updated_at,completed_at)
        values (?,7,'BLOCKED',?,?,?,?,'{}'::jsonb,?,?,?,?,?,?)
        """,
        inventoryId,
        idempotencyKey,
        "a".repeat(64),
        "b".repeat(64),
        "c".repeat(64),
        attemptCount,
        current,
        failureCode,
        current,
        current,
        current);
  }

  private void insertActiveSession(UUID id, UUID warehouseId, UUID idempotencyKey) {
    OffsetDateTime current = now();
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',?::jsonb,?,?,?)
        """,
        id,
        warehouseId,
        idempotencyKey,
        idempotencyKey,
        "0".repeat(64),
        "1".repeat(64),
        UUID.randomUUID(),
        actor(),
        current,
        current,
        current);
  }

  private void insertFinding(UUID inventoryId, UUID findingId, UUID assetId, String matchKey) {
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,asset_id,
          asset_version_snapshot,display_canonical_number,identity_match_key,
          passport_observation_state,equipment_observation_state,mutation_state,
          actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','MATCHED',?,0,?,?,'ABSENT',
          'ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        assetId,
        matchKey,
        matchKey,
        actor(),
        now(),
        now());
  }

  private void insertFindingMedia(
      UUID findingId,
      long findingRevision,
      UUID mediaId,
      long generation,
      String mediaKind,
      OffsetDateTime attachedAt) {
    jdbc.update(
        """
        insert into finding_media_reference(
          finding_id,finding_revision,media_id,generation,media_kind,media_status,attached_at)
        values (?,?,?,?,?,'READY',?)
        """,
        findingId,
        findingRevision,
        mediaId,
        generation,
        mediaKind,
        attachedAt);
  }

  private void insertExpectedFinding(
      UUID inventoryId,
      UUID findingId,
      UUID expectedItemId,
      UUID assetId,
      String matchKey,
      int itemOrder,
      boolean membershipActive) {
    jdbc.update(
        """
        with inserted_finding as (
          insert into inventory_finding(
            id,inventory_id,expected_item_id,finding_revision,owner_proof_revision,
            owner_proof_active,membership_active,origin,inspection,reconciliation,asset_id,
            asset_version_snapshot,display_canonical_number,identity_match_key,
            passport_observation_state,equipment_observation_state,mutation_state,
            actor_ref,created_at,updated_at)
          values (?,?,?,0,0,?,?,'EXPECTED','NOT_INSPECTED','MISSING',?,0,?,?,'ABSENT',
            'ABSENT','IDLE',?::jsonb,?,?)
          returning id
        )
        insert into inventory_expected_item(
          row_id,inventory_id,finding_id,item_order,asset_id,asset_version_snapshot,
          asset_status_snapshot,display_canonical_number,identity_match_key,
          safe_passport_snapshot,safe_contents_snapshot,captured_at)
        select ?,?,?,?, ?,0,'FREE',?,?, '{}'::jsonb,'[]'::jsonb,?
        from inserted_finding
        """,
        findingId,
        inventoryId,
        expectedItemId,
        membershipActive,
        membershipActive,
        assetId,
        matchKey,
        matchKey,
        actor(),
        now(),
        now(),
        expectedItemId,
        inventoryId,
        findingId,
        itemOrder,
        assetId,
        matchKey,
        matchKey,
        now());
  }

  private Flyway flyway(String location) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .locations(location)
        .baselineOnMigrate(false)
        .cleanDisabled(true)
        .validateOnMigrate(true)
        .load();
  }

  private Set<String> tableNames() {
    return jdbc
        .queryForList("select tablename from pg_tables where schemaname='public'", String.class)
        .stream()
        .collect(Collectors.toSet());
  }

  private Set<String> columns(String table) {
    return jdbc
        .queryForList(
            "select column_name from information_schema.columns "
                + "where table_schema='public' and table_name=?",
            String.class,
            table)
        .stream()
        .collect(Collectors.toSet());
  }

  private int count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Integer.class);
  }

  private java.net.URL requireResource(String name) {
    java.net.URL resource = getClass().getClassLoader().getResource(name);
    if (resource == null) throw new IllegalStateException("Missing resource " + name);
    return resource;
  }

  private static String actor() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\",\"principalType\":\"USER\",\"profileRevision\":null}";
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
