package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.flywaydb.core.api.exception.FlywayValidateException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;

@Testcontainers
class TaskBoardEventStoreMigrationIntegrationTest {

  private static final String MIGRATION_LOCATION = "classpath:db/migration";
  private static final int SEEDED_STREAM_COUNT = 22;
  private static final List<String> RETAINED_TABLES =
      List.of(
          "board_task",
          "queue_entry",
          "queue_usage_reference",
          "task_assignment",
          "task_auto_interruption",
          "task_board_inbox",
          "task_board_outbox",
          "task_time_event",
          "work_queue",
          "work_queue_class_binding",
          "worker",
          "worker_class",
          "worker_class_assignment",
          "worker_deletion_intent",
          "worker_group",
          "worker_group_member",
          "rwms_schema_history",
          "databasechangelog",
          "databasechangeloglock");

  @Container
  static final PostgreSQLContainer postgres = new PostgreSQLContainer("postgres:17-alpine");

  private JdbcTemplate jdbc;

  @BeforeEach
  void resetDatabase() {
    jdbc =
        new JdbcTemplate(
            new DriverManagerDataSource(
                postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword()));
    resetPublicSchema();
  }

  @Test
  void cleanInstallAppliesV4ThroughV36AndRepeatIsNoOp() {
    Flyway flyway = flyway(MIGRATION_LOCATION);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(33);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(tables())
        .contains(
            "event_stream_head",
            "domain_event",
            "aggregate_snapshot",
            "projection_checkpoint",
            "outbox_event",
            "sanitized_dead_letter",
            "inbox_message",
            "consumer_aggregate_checkpoint",
            "version_gap_quarantine",
            "replay_operation_audit",
            "task_sync_source",
            "worker_task_evidence",
            "worker_action_receipt",
            "worker_feed_revision",
            "worker_media_event_inbox",
            "worker_device_registration",
            "task_board_outbox",
            "task_board_inbox",
            "warehouse_metadata",
            "warehouse_kpi_settings",
            "queue_definition",
            "queue_definition_class_binding",
            "task_relocation_receipt",
            "task_board_warehouse_lifecycle_intent");
    assertThat(tables()).doesNotContain("worker_pii");
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='5'"))
        .containsEntry("version", "5")
        .containsEntry("description", "task board event sourcing")
        .containsEntry("script", "V5__task_board_event_sourcing.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='7'"))
        .containsEntry("version", "7")
        .containsEntry("description", "equipment movement completion deadline")
        .containsEntry("script", "V7__equipment_movement_completion_deadline.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='11'"))
        .containsEntry("version", "11")
        .containsEntry("description", "queue group audience")
        .containsEntry("script", "V11__queue_group_audience.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='12'"))
        .containsEntry("version", "12")
        .containsEntry("description", "worker task content snapshot")
        .containsEntry("script", "V12__worker_task_content_snapshot.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='13'"))
        .containsEntry("version", "13")
        .containsEntry("description", "require task queue")
        .containsEntry("script", "V13__require_task_queue.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='14'"))
        .containsEntry("version", "14")
        .containsEntry("description", "ordered queue classes")
        .containsEntry("script", "V14__ordered_queue_classes.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='15'"))
        .containsEntry("version", "15")
        .containsEntry("description", "worker task works")
        .containsEntry("script", "V15__worker_task_works.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='16'"))
        .containsEntry("version", "16")
        .containsEntry("description", "repair queue entry stream origins")
        .containsEntry("script", "V16__repair_queue_entry_stream_origins.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='17'"))
        .containsEntry("version", "17")
        .containsEntry("description", "remove legacy business codes")
        .containsEntry("script", "V17__remove_legacy_business_codes.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='18'"))
        .containsEntry("version", "18")
        .containsEntry("description", "warehouse kpi settings")
        .containsEntry("script", "V18__warehouse_kpi_settings.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='19'"))
        .containsEntry("version", "19")
        .containsEntry("description", "global queue catalog and repair complexity")
        .containsEntry(
            "script", "V19__global_queue_catalog_and_repair_complexity.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='20'"))
        .containsEntry("version", "20")
        .containsEntry("description", "canonicalize task board runtime state")
        .containsEntry(
            "script", "V20__canonicalize_task_board_runtime_state.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='21'"))
        .containsEntry("version", "21")
        .containsEntry("description", "driver logistics queue")
        .containsEntry("script", "V21__driver_logistics_queue.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='22'"))
        .containsEntry("version", "22")
        .containsEntry("description", "remove legacy repair complexity")
        .containsEntry("script", "V22__remove_legacy_repair_complexity.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='25'"))
        .containsEntry("version", "25")
        .containsEntry("script", "V25__global_task_board_queue_standard.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='26'"))
        .containsEntry("version", "26")
        .containsEntry("script", "V26__use_contiguous_global_queue_positions.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='27'"))
        .containsEntry("version", "27")
        .containsEntry("script", "V27__warehouse_lifecycle_intents.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='28'"))
        .containsEntry("version", "28")
        .containsEntry("script", "V28__driver_task_audience.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='29'"))
        .containsEntry("version", "29")
        .containsEntry("description", "remove shared driver identity")
        .containsEntry("script", "V29__remove_shared_driver_identity.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForList(
                """
                select column_name
                  from information_schema.columns
                 where table_schema='public'
                   and table_name='warehouse_kpi_settings'
                   and column_name like 'repair_%_boundary_minutes'
                """,
                String.class))
        .isEmpty();
  }

  @Test
  void explicitVersionFourBaselineCreatesSanitizedNonPublishedStreamsWithoutChangingRows()
      throws Exception {
    migrateSeededVersionFour();

    assertThat(jdbc.queryForObject("select count(*) from event_stream_head", Integer.class))
        .isEqualTo(SEEDED_STREAM_COUNT);
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class))
        .isEqualTo(SEEDED_STREAM_COUNT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where baseline and occurred_at is null",
                Integer.class))
        .isEqualTo(SEEDED_STREAM_COUNT);
    assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from projection_checkpoint "
                    + "where projection_name='task-board-live-v1'",
                Integer.class))
        .isEqualTo(SEEDED_STREAM_COUNT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='WORKER' "
                    + "and payload ? 'profileRevision' "
                    + "and payload - array['workerId','warehouseId','active',"
                    + "'profileRevision','currentGroupId','qualifications','deleted'] = '{}'::jsonb",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event event join worker item "
                    + "on item.id::text=event.aggregate_id "
                    + "where event.aggregate_type='WORKER' "
                    + "and event.payload->>'profileRevision'=item.revision_marker::text",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='WORKER_CLASS' "
                    + "and payload - array['workerClassId','revisionMarker','sortOrder',"
                    + "'active','deleted'] = '{}'::jsonb",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='WORKER_GROUP' "
                    + "and payload - array['workerGroupId','revisionMarker','warehouseId',"
                    + "'workerClassId','active','operationalStatus','members','deleted'] = '{}'::jsonb "
                    + "and not jsonb_path_exists(payload, '$.members[*].roleInGroup')",
                Integer.class))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='WORK_QUEUE' "
                    + "and payload - array['workQueueId','revisionMarker','warehouseId',"
                    + "'queueDefinitionId','queueType','queuePurpose','sortOrder','active','hidden','collapsed',"
                    + "'holdingPeriodMinutes','notificationThreshold',"
                    + "'notifyWhenThresholdReached','resultPhotoMinCount',"
                    + "'classBindings','deleted'] = '{}'::jsonb "
                    + "and not jsonb_path_exists(payload, '$.classBindings[*] ? "
                    + "(!exists(@.bindingOrder) || !exists(@.participationPolicy) "
                    + "|| !exists(@.notifyOnPrimaryTake) || exists(@.notifyUrgent))')",
                Integer.class))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                    "select count(*) from domain_event where aggregate_type='BOARD_TASK' "
                    + "and payload - array['boardTaskId','warehouseId','externalTaskId','status',"
                    + "'scheduledDate','lane','priority','pinned','plannedDurationMinutes',"
                    + "'deadlineAt','doneAt','deleted'] = '{}'::jsonb",
                Integer.class))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event event join queue_usage_reference item "
                    + "on item.id::text=event.aggregate_id "
                    + "where event.aggregate_type='QUEUE_USAGE_REFERENCE' "
                    + "and event.payload - array['queueUsageReferenceId','revisionMarker',"
                    + "'queueId','referenceType','externalReferenceHash','deleted'] = '{}'::jsonb "
                    + "and not (event.payload ? 'externalReferenceId') "
                    + "and event.payload->>'externalReferenceHash'=encode(sha256("
                    + "convert_to('task-board-queue-reference:v1','UTF8') "
                    + "|| decode('00','hex') || convert_to(item.external_reference_id,'UTF8')),"
                    + "'hex')",
                Integer.class))
        .isEqualTo(
            jdbc.queryForObject("select count(*) from queue_usage_reference", Integer.class));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='QUEUE_ENTRY' "
                    + "and payload - array['queueEntryId','taskId','queueId','routeIndex',"
                    + "'queuePosition','entryType','status','plannedDurationMinutes',"
                    + "'activeStartedAt','pausedAt','doneAt','activeWorkSeconds',"
                    + "'originalBudgetSeconds','currentBudgetSeconds','pauseOrigin',"
                    + "'assignments','timeEvents','interruptions','deleted'] = '{}'::jsonb "
                    + "and not (payload ? 'taskText')",
                Integer.class))
        .isEqualTo(5);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where "
                    + "(aggregate_type in ('WORKER_CLASS','WORK_QUEUE') and payload ? 'code') "
                    + "or (aggregate_type='QUEUE_ENTRY' and payload ? 'queueCode')",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from aggregate_snapshot where "
                    + "(aggregate_type in ('WORKER_CLASS','WORK_QUEUE') and state ? 'code') "
                    + "or (aggregate_type='QUEUE_ENTRY' and state ? 'queueCode')",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where payload_sha256 <> "
                    + "encode(sha256(convert_to(payload::text, 'UTF8')), 'hex')",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from aggregate_snapshot where state_sha256 <> "
                    + "encode(sha256(convert_to(state::text, 'UTF8')), 'hex')",
                Integer.class))
        .isZero();
    assertThat(tables()).doesNotContain("worker_pii");
    assertThat(payloadText())
        .doesNotContain(
            "Active Worker",
            "Pending Worker",
            "Error Worker",
            "Unconfigured Worker",
            "fixture.active",
            "fixture.pending",
            "fixture.error",
            "credential active",
            "sanitized credential failure",
            "primary qualification",
            "Removed Worker Snapshot",
            "fixture interruption",
            "fixture cancellation",
            "Fixture group one",
            "Fixture group two",
            "Fixture general class",
            "Fixture inactive class",
            "General compatibility class",
            "Fixture movement",
            "Fixture repair",
            "Fixture holding",
            "Fixture active task",
            "Fixture done task",
            "Fixture cancelled task",
            "Fixture interrupting task",
            "Interrupted work",
            "Completed work",
            "Unassigned cancelled work",
            "Interrupting movement",
            "БЫТ-001",
            "БЫТ-003");
  }

  @Test
  void baselineIsDeterministicAndShadowReplayMatchesIdsVersionsCountsAndChecksums()
      throws Exception {
    migrateSeededVersionFour();
    List<Map<String, Object>> first = deterministicBaselineProjection();

    jdbc.update(
        "insert into projection_checkpoint(projection_name, aggregate_type, aggregate_id, "
            + "aggregate_version, projection_sha256, updated_at) "
            + "select 'task-board-replay-shadow-v1', aggregate_type, aggregate_id, "
            + "aggregate_version, payload_sha256, clock_timestamp() from domain_event");

    assertThat(
            jdbc.queryForObject(
                "select count(*) from projection_checkpoint "
                    + "where projection_name='task-board-replay-shadow-v1'",
                Integer.class))
        .isEqualTo(SEEDED_STREAM_COUNT);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from ((select aggregate_type, aggregate_id, aggregate_version, "
                    + "projection_sha256 from projection_checkpoint "
                    + "where projection_name='task-board-live-v1') except "
                    + "(select aggregate_type, aggregate_id, aggregate_version, projection_sha256 "
                    + "from projection_checkpoint where projection_name='task-board-replay-shadow-v1')) diff",
                Integer.class))
        .isZero();
    assertThat(canonicalProjectionChecksum("task-board-live-v1"))
        .isEqualTo(canonicalProjectionChecksum("task-board-replay-shadow-v1"));

    resetPublicSchema();
    migrateSeededVersionFour();
    assertThat(deterministicBaselineProjection()).isEqualTo(first);
  }

  @Test
  void domainEventsAreAppendOnly() throws Exception {
    migrateSeededVersionFour();
    Object eventId =
        jdbc.queryForObject(
            "select event_id from domain_event order by event_id::text limit 1", Object.class);

    assertThatThrownBy(
            () -> jdbc.update("update domain_event set payload='{}'::jsonb where event_id=?", eventId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.update("delete from domain_event where event_id=?", eventId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(() -> jdbc.execute("truncate table domain_event cascade"))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void outboxRequiresCanonicalRuntimeEnvelopeAndProtectsEventMetadata() throws Exception {
    migrateSeededVersionFour();
    UUID runtimeEventId = insertRuntimeWorkerClassEvent();
    UUID baselineEventId =
        jdbc.queryForObject(
            "select event_id from domain_event where aggregate_type='WORKER_CLASS' "
                + "and baseline order by aggregate_id limit 1",
            UUID.class);

    assertThatThrownBy(
            () ->
                insertOutbox(
                    baselineEventId,
                    "rwms.task-board.worker-class.v1",
                    envelopeFor(baselineEventId)))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("does not match authoritative domain event");
    assertThatThrownBy(
            () ->
                insertOutbox(
                    runtimeEventId,
                    "rwms.task-board.worker.v1",
                    envelopeFor(runtimeEventId)))
        .isInstanceOf(DataAccessException.class);

    String changedEnvelope =
        jdbc.queryForObject(
            "select jsonb_set(?::jsonb, '{payload,deleted}', 'true'::jsonb)::text",
            String.class,
            envelopeFor(runtimeEventId));
    assertThatThrownBy(
            () ->
                insertOutbox(
                    runtimeEventId,
                    "rwms.task-board.worker-class.v1",
                    changedEnvelope))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("does not match authoritative domain event");

    insertOutbox(
        runtimeEventId,
        "rwms.task-board.worker-class.v1",
        envelopeFor(runtimeEventId));
    UUID leaseToken = UUID.fromString("30000000-0000-0000-0000-000000000171");
    assertThat(
            jdbc.update(
                "update outbox_event set status='IN_FLIGHT', attempt_count=1, "
                    + "lease_owner='migration-test', lease_token=?, "
                    + "lease_until=now() + interval '1 minute' where event_id=?",
                leaseToken,
                runtimeEventId))
        .isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update outbox_event set created_at=created_at + interval '1 second' "
                        + "where event_id=?",
                    runtimeEventId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("metadata is immutable");
  }

  @Test
  void sanitizedDeadLettersExposeOnlyBoundedSafeMetadata() throws Exception {
    migrateSeededVersionFour();
    UUID dltId = UUID.fromString("30000000-0000-0000-0000-000000000172");
    String messageHash = "a".repeat(64);
    String safeBody =
        "{\"failureCode\":\"VALIDATION_REJECTED\","
            + "\"messageSha256\":\""
            + messageHash
            + "\",\"recordedAt\":\"2026-07-14T10:00:00Z\"}";
    insertSanitizedDeadLetter(
        dltId,
        "rwms.task-board.worker-class.v1.task-board-shadow-v1.dlt",
        "VALIDATION_REJECTED",
        messageHash,
        safeBody);

    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update sanitized_dead_letter set safe_body='{}'::jsonb where dlt_id=?",
                    dltId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("metadata is immutable");
    assertThatThrownBy(
            () ->
                insertSanitizedDeadLetter(
                    UUID.randomUUID(),
                    "rwms.task-board.worker-class.v1.task-board-shadow-v2.dlt",
                    "VALIDATION_REJECTED",
                    messageHash,
                    safeBody))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertSanitizedDeadLetter(
                    UUID.randomUUID(),
                    "rwms.task-board.worker-class.v1.task-board-shadow-v1.dlt",
                    "VALIDATION_REJECTED",
                    messageHash,
                    safeBody.replace(
                        "2026-07-14T10:00:00Z", "password=secret")))
        .isInstanceOf(DataAccessException.class);
    assertThatThrownBy(
            () ->
                insertSanitizedDeadLetter(
                    UUID.randomUUID(),
                    "rwms.task-board.worker-class.v1.task-board-shadow-v1.dlt",
                    "VALIDATION_REJECTED",
                    messageHash,
                    safeBody.substring(0, safeBody.length() - 1)
                        + ",\"rawPayload\":\"password=secret\"}"))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void versionGapAndReplayAuditRequireExplicitSanitizedResolution() throws Exception {
    migrateSeededVersionFour();
    UUID quarantineId = UUID.fromString("30000000-0000-0000-0000-000000000173");
    UUID receivedEventId = UUID.fromString("30000000-0000-0000-0000-000000000174");
    UUID actorId = UUID.fromString("30000000-0000-0000-0000-000000000175");
    jdbc.update(
        "insert into version_gap_quarantine(quarantine_id, consumer_group, aggregate_type, "
            + "aggregate_id, expected_version, received_version, received_event_id, "
            + "payload_sha256, reason_code, status, detected_at) values "
            + "(?, 'task-board-shadow-v1', 'WORKER_CLASS', ?, 8, 9, ?, ?, "
            + "'AGGREGATE_VERSION_GAP', 'OPEN', now())",
        quarantineId,
        UUID.fromString("10000000-0000-0000-0000-000000000001").toString(),
        receivedEventId,
        "b".repeat(64));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update version_gap_quarantine set status='RESOLVED', resolved_at=now() "
                        + "where quarantine_id=?",
                    quarantineId))
        .isInstanceOf(DataAccessException.class);
    assertThat(
            jdbc.update(
                "update version_gap_quarantine set status='RESOLVED', resolved_at=now(), "
                    + "resolution_reason='Verified replay parity', resolved_by_subject_id=? "
                    + "where quarantine_id=?",
                actorId,
                quarantineId))
        .isOne();

    UUID operationId = UUID.fromString("30000000-0000-0000-0000-000000000176");
    jdbc.update(
        "insert into replay_operation_audit(operation_id, actor_subject_id, reason, status, "
            + "started_at, before_aggregate_count, before_version_sum, before_checksum) "
            + "values (?, ?, 'Controlled shadow rebuild', 'STARTED', now(), 22, 22, ?)",
        operationId,
        actorId,
        "c".repeat(64));
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update replay_operation_audit set reason='Tampered', status='COMPLETED', "
                        + "completed_at=now(), after_aggregate_count=22, after_version_sum=22, "
                        + "after_checksum=? where operation_id=?",
                    "d".repeat(64),
                    operationId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("append-only after one terminal transition");
    assertThat(
            jdbc.update(
                "update replay_operation_audit set status='COMPLETED', completed_at=now(), "
                    + "after_aggregate_count=22, after_version_sum=22, after_checksum=? "
                    + "where operation_id=?",
                "d".repeat(64),
                operationId))
        .isOne();
    assertThatThrownBy(
            () -> jdbc.update("delete from replay_operation_audit where operation_id=?", operationId))
        .isInstanceOf(DataAccessException.class)
        .hasMessageContaining("cannot be removed");
  }

  @Test
  void changedAppliedV5FailsChecksumValidation(@TempDir Path directory) throws IOException {
    copyMigration(directory, "V4__task_board_schema.sql");
    Path versionFive = copyMigration(directory, "V5__task_board_event_sourcing.sql");
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();

    Files.writeString(
        versionFive,
        Files.readString(versionFive)
            .replace("aggregate_type varchar(64) NOT NULL", "aggregate_type varchar(63) NOT NULL"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  private void migrateSeededVersionFour() throws Exception {
    apply("db/migration/V4__task_board_schema.sql");
    apply("fixtures/f0-nonempty.sql");
    jdbc.update(
        "update queue_usage_reference set reference_type='REPAIR_PLAN' "
            + "where reference_type='TASK_HISTORY'");
    reconcileUnassignedFixtureTasks();
    createHistoricalMigrationEvidence();
    seedRabbitCompatibilityRows();
    Map<String, String> before = retainedContentDigests();

    Flyway adopted =
        configuration(MIGRATION_LOCATION)
            .baselineVersion("4")
            .baselineDescription("Task-board post-F2 schema")
            .load();
    adopted.baseline();
    assertThat(adopted.migrate().migrationsExecuted).isEqualTo(32);
    adopted.validate();
    assertThat(adopted.migrate().migrationsExecuted).isZero();
    assertThat(retainedContentDigests()).containsExactlyInAnyOrderEntriesOf(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from board_task where completion_deadline_enforced",
                Integer.class))
        .isZero();
  }

  private void reconcileUnassignedFixtureTasks() {
    jdbc.execute(
        """
        update queue_entry entry
        set (queue_id, queue_code) = (
          select work_queue.id, work_queue.code
          from work_queue
          join board_task on board_task.warehouse_id = work_queue.warehouse_id
          where board_task.id = entry.task_id
          order by work_queue.sort_order, work_queue.id
          limit 1
        )
        where entry.queue_id is null
        """);
  }

  private List<Map<String, Object>> deterministicBaselineProjection() {
    return jdbc.queryForList(
        "select event_id::text, aggregate_type, aggregate_id, aggregate_version, event_type, "
            + "correlation_id::text, payload::text, payload_sha256 "
            + "from domain_event order by aggregate_type, aggregate_id");
  }

  private String canonicalProjectionChecksum(String projectionName) {
    return jdbc.queryForObject(
        "select encode(sha256(convert_to(coalesce(string_agg(aggregate_type || ':' || "
            + "aggregate_id || ':' || aggregate_version || ':' || projection_sha256, '|' "
            + "order by aggregate_type, aggregate_id), ''), 'UTF8')), 'hex') "
            + "from projection_checkpoint where projection_name=?",
        String.class,
        projectionName);
  }

  private String payloadText() {
    return jdbc.queryForObject(
        "select coalesce(string_agg(payload::text, ' ' order by aggregate_type, aggregate_id), '') "
            + "from domain_event",
        String.class);
  }

  private UUID insertRuntimeWorkerClassEvent() {
    String aggregateId =
        jdbc.queryForObject(
            "select aggregate_id from event_stream_head where aggregate_type='WORKER_CLASS' "
                + "order by aggregate_id limit 1",
            String.class);
    long currentVersion =
        jdbc.queryForObject(
            "select current_version from event_stream_head "
                + "where aggregate_type='WORKER_CLASS' and aggregate_id=?",
            Long.class,
            aggregateId);
    long nextVersion = currentVersion + 1;
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    assertThat(
            jdbc.update(
                "update event_stream_head set current_version=?, last_event_id=?, "
                    + "updated_at=clock_timestamp() where aggregate_type='WORKER_CLASS' "
                    + "and aggregate_id=? and current_version=?",
                nextVersion,
                eventId,
                aggregateId,
                currentVersion))
        .isOne();
    String payload =
        jdbc.queryForObject(
            "select jsonb_build_object('workerClassId', ?::uuid, 'deleted', false)::text",
            String.class,
            aggregateId);
    jdbc.update(
        "with stamp as (select clock_timestamp() at) "
            + "insert into domain_event(event_id, aggregate_type, aggregate_id, "
            + "aggregate_version, event_type, event_version, occurred_at, recorded_at, "
            + "correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline) "
            + "select ?, 'WORKER_CLASS', ?, ?, 'task-board.worker-class.changed.v1', 1, "
            + "at, at, ?, null, null, ?::jsonb, ?, false from stamp",
        eventId,
        aggregateId,
        nextVersion,
        correlationId,
        payload,
        canonicalHash(payload));
    return eventId;
  }

  private String envelopeFor(UUID eventId) {
    return jdbc.queryForObject(
        "select jsonb_build_object("
            + "'envelopeVersion', 2, 'eventId', event_id, 'eventType', event_type, "
            + "'eventVersion', event_version, 'occurredAt', occurred_at, "
            + "'recordedAt', recorded_at, 'producer', 'task-board-service', "
            + "'aggregateType', aggregate_type, 'aggregateId', aggregate_id, "
            + "'aggregateVersion', aggregate_version, "
            + "'correlation', jsonb_build_object('correlationId', correlation_id, "
            + "'causationId', causation_id), 'actorRef', actor_ref, 'payload', payload)::text "
            + "from domain_event where event_id=?",
        String.class,
        eventId);
  }

  private void insertOutbox(UUID eventId, String topic, String envelope) {
    jdbc.update(
        "insert into outbox_event(event_id, aggregate_type, aggregate_id, aggregate_version, "
            + "event_type, topic, envelope_body, envelope_sha256, status, attempt_count, "
            + "next_attempt_at, created_at) select event_id, aggregate_type, aggregate_id, "
            + "aggregate_version, event_type, ?, ?::jsonb, ?, 'PENDING', 0, now(), now() "
            + "from domain_event where event_id=?",
        topic,
        envelope,
        canonicalHash(envelope),
        eventId);
  }

  private void insertSanitizedDeadLetter(
      UUID dltId,
      String destination,
      String failureCode,
      String messageHash,
      String safeBody) {
    jdbc.update(
        "insert into sanitized_dead_letter(dlt_id, destination, message_sha256, failure_code, "
            + "safe_body, body_sha256, status, attempt_count, next_attempt_at, created_at) "
            + "values (?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, now(), now())",
        dltId,
        destination,
        messageHash,
        failureCode,
        safeBody,
        canonicalHash(safeBody));
  }

  private String canonicalHash(String json) {
    return jdbc.queryForObject(
        "select encode(sha256(convert_to((?::jsonb)::text, 'UTF8')), 'hex')",
        String.class,
        json);
  }

  private void createHistoricalMigrationEvidence() {
    jdbc.execute(
        "create table public.rwms_schema_history (version varchar(64) primary key, "
            + "checksum varchar(64) not null, applied_at timestamptz not null default clock_timestamp(), "
            + "description varchar(255) not null)");
    insertHistory("0001", "1e1910dbeea555ac08af0b4948bfc97a59888a5499e847d5a605fc30c8106adb");
    insertHistory("0002", "4a801d2a93c38ff7cd0bc59df5300a9d015d901f733fac1280ecb6e25b8b14b5");
    insertHistory("0003", "31a451efd2d770c7260cae5ae80335ebc6c44582568c1dada5c92e0b2db3b9af");
    insertHistory("0004", "e07114131235f5de1dc6df68382a762c8abaeaf63db308bfbead3bb9bf4f23cc");
    jdbc.execute(
        "create table databasechangelog (id varchar(255) not null, author varchar(255) not null)");
    jdbc.update("insert into databasechangelog values ('001-task-board', 'fixture')");
    jdbc.execute(
        "create table databasechangeloglock (id integer primary key, locked boolean not null)");
    jdbc.update("insert into databasechangeloglock values (1, false)");
  }

  private void insertHistory(String version, String checksum) {
    jdbc.update(
        "insert into rwms_schema_history(version, checksum, description) values (?, ?, ?)",
        version,
        checksum,
        "retained history " + version);
  }

  private void seedRabbitCompatibilityRows() {
    jdbc.update(
        "insert into task_board_outbox(event_id,event_type,event_version,routing_key,aggregate_type,"
            + "aggregate_id,aggregate_version,envelope_body,envelope_sha256,correlation_id,occurred_at,"
            + "created_at,status,attempt_count,next_attempt_at) values (?,?,?,?,?,?,?,?,?,?,now(),now(),"
            + "'PENDING',0,now())",
        java.util.UUID.fromString("70000000-0000-0000-0000-000000000201"),
        "task-board.board-task.created",
        1,
        "task-board.board-task.created.v1",
        "BOARD_TASK",
        "fixture-task",
        1,
        "{}",
        "a".repeat(64),
        java.util.UUID.fromString("70000000-0000-0000-0000-000000000202"));
    jdbc.update(
        "insert into task_board_inbox(consumer_name,event_id,event_hash,event_type,event_version,"
            + "received_at,processed_at) values (?,?,?,?,?,now(),now())",
        "fixture-consumer",
        java.util.UUID.fromString("80000000-0000-0000-0000-000000000201"),
        "b".repeat(64),
        "fixture.event",
        1);
  }

  private Map<String, String> retainedContentDigests() {
    var result = new LinkedHashMap<String, String>();
    RETAINED_TABLES.forEach(table -> result.put(table, digest(table)));
    return result;
  }

  private String digest(String table) {
    String json =
        "board_task".equals(table)
            ? "to_jsonb(row_value) - array['completion_deadline_enforced',"
                + "'scheduled_date','task_lane','priority','pinned','request_fingerprint',"
                + "'driver_audience_mode','planned_driver_worker_id',"
                + "'planned_driver_name_snapshot']"
            : "queue_entry".equals(table)
                ? "to_jsonb(row_value) - array['queue_code','worker_works','worker_materials',"
                    + "'worker_comments','source_media_references','revision_marker',"
                    + "'original_budget_seconds','current_budget_seconds','queue_position',"
                    + "'entry_type']"
                : "work_queue".equals(table)
                    ? "to_jsonb(row_value) - array['code','result_photo_min_count',"
                        + "'name','description','queue_type','definition_id',"
                        + "'sort_order','active','hidden','collapsed',"
                        + "'holding_period_minutes','notification_threshold',"
                        + "'notify_when_threshold_reached','revision_marker',"
                        + "'available_task_limit','worker_feed_enabled']"
                    : "queue_usage_reference".equals(table)
                        ? "to_jsonb(row_value) - array['queue_id','queue_definition_id']"
                    : "worker_class".equals(table)
                        ? "to_jsonb(row_value) - 'code'"
                    : "work_queue_class_binding".equals(table)
                        ? "to_jsonb(row_value) - array['binding_order','notify_urgent',"
                            + "'participation_policy','notify_on_primary_take']"
                        : "worker_group_member".equals(table)
                            ? "to_jsonb(row_value) - 'role_in_group'"
                            : "worker".equals(table)
                                ? "to_jsonb(row_value) - 'current_group_id'"
                                : "worker_group".equals(table)
                                    ? "to_jsonb(row_value) - array['operational_status',"
                                        + "'unavailable_since','unavailability_reason']"
                                    : "to_jsonb(row_value)";
    return jdbc.queryForObject(
        "select md5(coalesce(string_agg(("
            + json
            + ")::text, '|' order by ("
            + json
            + ")::text), '')) from "
            + table
            + " row_value",
        String.class);
  }

  private List<String> tables() {
    return jdbc.queryForList(
        "select table_name from information_schema.tables "
            + "where table_schema='public' order by table_name",
        String.class);
  }

  private Path copyMigration(Path directory, String migration) throws IOException {
    Path target = directory.resolve(migration);
    try (var source = requireResource("db/migration/" + migration).openStream()) {
      Files.copy(source, target);
    }
    return target;
  }

  private void apply(String resource) throws Exception {
    String remote = "/tmp/" + resource.replace('/', '-');
    postgres.copyFileToContainer(resource(resource), remote);
    var result =
        postgres.execInContainer(
            "psql",
            "-X",
            "--single-transaction",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            postgres.getUsername(),
            "-d",
            postgres.getDatabaseName(),
            "-f",
            remote);
    assertThat(result.getExitCode())
        .withFailMessage("%s failed:%n%s%n%s", resource, result.getStdout(), result.getStderr())
        .isZero();
  }

  private MountableFile resource(String path) throws URISyntaxException {
    return MountableFile.forHostPath(Path.of(requireResource(path).toURI()));
  }

  private java.net.URL requireResource(String path) {
    var resource = getClass().getClassLoader().getResource(path);
    if (resource == null) {
      throw new IllegalStateException("Missing task-board migration resource: " + path);
    }
    return resource;
  }

  private Flyway flyway(String location) {
    return configuration(location).load();
  }

  private FluentConfiguration configuration(String location) {
    return Flyway.configure()
        .dataSource(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword())
        .locations(location)
        .baselineOnMigrate(false)
        .validateOnMigrate(true)
        .validateMigrationNaming(true)
        .cleanDisabled(true)
        .outOfOrder(false);
  }

  private void resetPublicSchema() {
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }
}
