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
import org.testcontainers.utility.MountableFile;

@Testcontainers
class TaskBoardFlywayMigrationIntegrationTest {

  private static final String MIGRATION_LOCATION = "classpath:db/migration";
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
    var dataSource =
        new DriverManagerDataSource(
            postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
    jdbc = new JdbcTemplate(dataSource);
    jdbc.execute("drop schema public cascade");
    jdbc.execute("create schema public");
  }

  @Test
  void cumulativeVersionFourEventSourcingAndTaskSyncMigrateCleanDatabaseAndRepeatIsNoOp() {
    Flyway flyway = flyway(MIGRATION_LOCATION);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(17);
    flyway.validate();
    assertThat(flyway.migrate().migrationsExecuted).isZero();

    assertThat(
            jdbc.queryForList(
                "select table_name from information_schema.tables "
                    + "where table_schema='public' order by table_name",
                String.class))
        .contains(
            "aggregate_snapshot",
            "board_task",
            "consumer_aggregate_checkpoint",
            "domain_event",
            "event_stream_head",
            "flyway_schema_history",
            "inbox_message",
            "outbox_event",
            "projection_checkpoint",
            "queue_definition",
            "queue_entry",
            "queue_usage_reference",
            "task_assignment",
            "task_auto_interruption",
            "task_board_inbox",
            "task_board_outbox",
            "task_time_event",
            "task_sync_source",
            "task_relocation_receipt",
            "version_gap_quarantine",
            "work_queue",
            "work_queue_class_binding",
            "worker",
            "worker_class",
            "worker_class_assignment",
            "worker_deletion_intent",
            "worker_group",
            "worker_group_member",
            "worker_device_registration",
            "worker_media_event_inbox",
            "worker_task_evidence",
            "warehouse_metadata",
            "warehouse_event_inbox",
            "warehouse_kpi_settings",
            "kpi_palette",
            "kpi_palette_range",
            "kpi_work_schedule",
            "kpi_work_break");
    assertThat(columnCounts())
        .containsAllEntriesOf(
            Map.ofEntries(
                Map.entry("board_task", 16),
                Map.entry("queue_entry", 22),
                Map.entry("queue_usage_reference", 6),
                Map.entry("task_assignment", 12),
                Map.entry("task_auto_interruption", 8),
                Map.entry("task_board_inbox", 7),
                Map.entry("task_board_outbox", 24),
                Map.entry("task_sync_source", 6),
                Map.entry("task_time_event", 10),
                Map.entry("work_queue", 13),
                Map.entry("work_queue_class_binding", 7),
                Map.entry("worker", 17),
                Map.entry("worker_class", 8),
                Map.entry("worker_class_assignment", 6),
                Map.entry("worker_deletion_intent", 7),
                Map.entry("worker_group", 11),
                Map.entry("worker_group_member", 5)));
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='4'"))
        .containsEntry("version", "4")
        .containsEntry("description", "task board schema")
        .containsEntry("script", "V4__task_board_schema.sql")
        .containsEntry("success", true);
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
                    + "where version='6'"))
        .containsEntry("version", "6")
        .containsEntry("description", "task sync source ownership")
        .containsEntry("script", "V6__task_sync_source_ownership.sql")
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
                    + "where version='8'"))
        .containsEntry("version", "8")
        .containsEntry("description", "task scheduling")
        .containsEntry("script", "V8__task_scheduling.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='9'"))
        .containsEntry("version", "9")
        .containsEntry("description", "task sync source reference")
        .containsEntry("script", "V9__task_sync_source_reference.sql")
        .containsEntry("success", true);
    assertThat(
            jdbc.queryForMap(
                "select version, description, script, success from flyway_schema_history "
                    + "where version='10'"))
        .containsEntry("version", "10")
        .containsEntry("description", "worker task evidence")
        .containsEntry("script", "V10__worker_task_evidence.sql")
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
            jdbc.queryForObject(
                "select to_regprocedure('public.task_board_request_fingerprint_v4(jsonb)')",
                String.class))
        .isEqualTo("task_board_request_fingerprint_v4(jsonb)");
    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.work_queue_group_binding')", String.class))
        .isNull();
    assertThat(
            jdbc.queryForMap(
                "select is_nullable from information_schema.columns "
                    + "where table_schema='public' and table_name='queue_entry' "
                    + "and column_name='queue_id'"))
        .containsEntry("is_nullable", "NO");
  }

  @Test
  void changedAppliedVersionFourFailsChecksumValidation(@TempDir Path directory)
      throws IOException {
    Path migration = directory.resolve("V4__task_board_schema.sql");
    try (var source = requireResource("db/migration/V4__task_board_schema.sql").openStream()) {
      Files.copy(source, migration);
    }
    String location = "filesystem:" + directory.toAbsolutePath().toString().replace('\\', '/');
    flyway(location).migrate();

    Files.writeString(
        migration,
        Files.readString(migration)
            .replace("code varchar(64) NOT NULL", "code varchar(63) NOT NULL"));

    assertThatThrownBy(() -> flyway(location).validate())
        .isInstanceOf(FlywayValidateException.class)
        .hasMessageContaining("checksum");
  }

  @Test
  void versionNineteenUsesSpbDefinitionsAndPreservesBindingsTasksReferencesAndOrder() {
    configuration(MIGRATION_LOCATION).target("18").load().migrate();
    String spb = "00000000-0000-0000-0000-000000000001";
    String msk = "00000000-0000-0000-0000-000000000002";
    UUID spbExternal = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID spbInternal = UUID.fromString("10000000-0000-0000-0000-000000000002");
    UUID mskExternal = UUID.fromString("20000000-0000-0000-0000-000000000001");
    UUID taskId = UUID.fromString("30000000-0000-0000-0000-000000000001");
    UUID entryId = UUID.fromString("40000000-0000-0000-0000-000000000001");
    UUID historyId = UUID.fromString("45000000-0000-0000-0000-000000000001");
    UUID referenceId = UUID.fromString("50000000-0000-0000-0000-000000000001");
    jdbc.update(
        """
        insert into work_queue(
          id,version,revision_marker,warehouse_id,name,description,queue_type,sort_order,
          active,hidden,collapsed,holding_period_minutes,notification_threshold,
          notify_when_threshold_reached,result_photo_min_count)
        values
          (?,0,?,?,?,'SPB source','REPAIR',10,true,false,false,null,null,false,1),
          (?,0,?,?,?,'SPB source','REPAIR',20,true,false,false,null,null,false,1),
          (?,0,?,?,?,'MSK legacy spelling','REPAIR',30,true,false,false,null,null,false,1)
        """,
        spbExternal,
        UUID.randomUUID(),
        UUID.fromString(spb),
        "Внешние работы",
        spbInternal,
        UUID.randomUUID(),
        UUID.fromString(spb),
        "Внутренние работы",
        mskExternal,
        UUID.randomUUID(),
        UUID.fromString(msk),
        "  ВНЕШНИЕ   РАБОТЫ ");
    jdbc.update(
        """
        insert into board_task(
          id,version,warehouse_id,external_task_id,title,status,priority,pinned,
          completion_deadline_enforced)
        values (?,0,?,null,'Существующий ремонт','ACTIVE',3,false,false)
        """,
        taskId,
        UUID.fromString(spb));
    jdbc.update(
        """
        insert into queue_entry(
          id,version,revision_marker,task_id,queue_id,route_index,queue_position,
          entry_type,status,active_work_seconds)
        values (?,0,?,?,?,0,7,'REAL','WAITING',0)
        """,
        entryId,
        UUID.randomUUID(),
        taskId,
        spbExternal);
    jdbc.update(
        """
        insert into task_time_event(
          id,version,queue_entry_id,event_type,reason,created_at)
        values (?,3,?,'PAUSED','Существующая история',clock_timestamp())
        """,
        historyId,
        entryId);
    jdbc.update(
        """
        insert into queue_usage_reference(
          id,version,revision_marker,queue_id,reference_type,external_reference_id)
        values (?,0,?,?,'CATALOG_POSITION','catalog-position-1')
        """,
        referenceId,
        UUID.randomUUID(),
        mskExternal);

    assertThat(configuration(MIGRATION_LOCATION).target("19").load().migrate().migrationsExecuted)
        .isOne();

    assertThat(
            jdbc.queryForList(
                "select id from queue_definition order by normalized_name", UUID.class))
        .containsExactly(spbExternal, spbInternal);
    assertThat(
            jdbc.queryForList(
                """
                select id,definition_id,sort_order
                  from work_queue
                 where warehouse_id=?
                 order by sort_order
                """,
                UUID.fromString(spb)))
        .extracting(
            row -> row.get("id"),
            row -> row.get("definition_id"),
            row -> row.get("sort_order"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(spbExternal, spbExternal, 10),
            org.assertj.core.groups.Tuple.tuple(spbInternal, spbInternal, 20));
    assertThat(
            jdbc.queryForObject(
                "select definition_id from work_queue where id=?",
                UUID.class,
                mskExternal))
        .isEqualTo(spbExternal);
    assertThat(
            jdbc.queryForObject(
                "select queue_id from queue_entry where id=?", UUID.class, entryId))
        .isEqualTo(spbExternal);
    assertThat(
            jdbc.queryForMap(
                "select queue_entry_id,version,event_type,reason from task_time_event where id=?",
                historyId))
        .containsEntry("queue_entry_id", entryId)
        .containsEntry("version", 3L)
        .containsEntry("event_type", "PAUSED")
        .containsEntry("reason", "Существующая история");
    assertThat(
            jdbc.queryForMap(
                "select warehouse_id,status,title from board_task where id=?", taskId))
        .containsEntry("warehouse_id", UUID.fromString(spb))
        .containsEntry("status", "ACTIVE")
        .containsEntry("title", "Существующий ремонт");
    assertThat(
            jdbc.queryForObject(
                "select queue_definition_id from queue_usage_reference where id=?",
                UUID.class,
                referenceId))
        .isEqualTo(spbExternal);
  }

  @Test
  void nonEmptyUnversionedSchemaIsNeverAdoptedAutomatically() throws Exception {
    applyHistoricalSchema();
    createHistoricalMigrationEvidence();

    assertThatThrownBy(() -> flyway(MIGRATION_LOCATION).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");
    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history')", String.class))
        .isNull();
  }

  @Test
  void versionFourPreflightRejectsCatalogDriftBeforeBaseline() throws Exception {
    applyHistoricalSchema();
    createHistoricalMigrationEvidence();
    jdbc.execute("alter table worker alter column credential_operation_type type varchar(63)");

    applyExpectFailure("flyway/verify-version-4.sql");

    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.flyway_schema_history')", String.class))
        .isNull();
  }

  @Test
  void versionFourPreflightRejectsHistoricalChecksumDrift() throws Exception {
    applyHistoricalSchema();
    createHistoricalMigrationEvidence();
    jdbc.update(
        "update rwms_schema_history set checksum=? where version='0004'", "f".repeat(64));

    applyExpectFailure("flyway/verify-version-4.sql");
  }

  @Test
  void versionFourPreflightRejectsUnsupportedJpaEnumValues() throws Exception {
    applyHistoricalSchema();
    createHistoricalMigrationEvidence();
    apply("fixtures/f0-nonempty.sql");

    applyExpectFailure("flyway/verify-version-4.sql");
  }

  @Test
  void verifiedVersionFourDatabaseIsExplicitlyBaselinedWithoutChangingRows()
      throws Exception {
    applyHistoricalSchema();
    createHistoricalMigrationEvidence();
    apply("fixtures/f0-nonempty.sql");
    normalizeFixtureToCurrentJpaEnums();
    reconcileUnassignedFixtureTasks();
    seedRabbitCompatibilityRows();
    Map<String, String> before = retainedContentDigests();

    apply("flyway/verify-version-4.sql");
    assertThatThrownBy(() -> flyway(MIGRATION_LOCATION).migrate())
        .isInstanceOf(FlywayException.class)
        .hasMessageContaining("non-empty schema");

    Flyway adopted =
        configuration(MIGRATION_LOCATION)
            .baselineVersion("4")
            .baselineDescription("Task-board post-F2 schema")
            .load();
    adopted.baseline();
    assertThat(adopted.migrate().migrationsExecuted).isEqualTo(16);
    adopted.validate();
    assertThat(adopted.migrate().migrationsExecuted).isZero();

    assertThat(retainedContentDigests()).containsExactlyInAnyOrderEntriesOf(before);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from board_task where completion_deadline_enforced", Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "select version, description, type, success from flyway_schema_history "
                    + "where version='4'"))
        .containsEntry("version", "4")
        .containsEntry("description", "Task-board post-F2 schema")
        .containsEntry("type", "BASELINE")
        .containsEntry("success", true);
    assertThat(jdbc.queryForObject("select count(*) from rwms_schema_history", Integer.class))
        .isEqualTo(4);
    assertThat(jdbc.queryForObject("select count(*) from task_board_outbox", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from task_board_inbox", Integer.class))
        .isEqualTo(1);
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class))
        .isEqualTo(22);
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

  private void applyHistoricalSchema() throws Exception {
    apply("releases/V0001__adopt-task-board-schema/apply.sql");
    apply("releases/V0002__add-integration-messaging/apply.sql");
    apply("releases/V0003__enforce-case-insensitive-identities/apply.sql");
    apply("releases/V0004__add-worker-credential-operation/apply.sql");
  }

  private void createHistoricalMigrationEvidence() {
    jdbc.execute(
        """
        create table public.rwms_schema_history (
          version varchar(64) primary key,
          checksum varchar(64) not null check (checksum ~ '^[0-9a-f]{64}$'),
          applied_at timestamptz not null default clock_timestamp(),
          description varchar(255) not null
        )
        """);
    insertHistory(
        "0001",
        "1e1910dbeea555ac08af0b4948bfc97a59888a5499e847d5a605fc30c8106adb",
        "Adopt existing task-board schema");
    insertHistory(
        "0002",
        "4a801d2a93c38ff7cd0bc59df5300a9d015d901f733fac1280ecb6e25b8b14b5",
        "Add task-board idempotency outbox and inbox");
    insertHistory(
        "0003",
        "31a451efd2d770c7260cae5ae80335ebc6c44582568c1dada5c92e0b2db3b9af",
        "Enforce case-insensitive task-board identities");
    insertHistory(
        "0004",
        "e07114131235f5de1dc6df68382a762c8abaeaf63db308bfbead3bb9bf4f23cc",
        "Add durable worker credential operation metadata");
    jdbc.execute(
        "create table databasechangelog (id varchar(255) not null, author varchar(255) not null)");
    jdbc.update(
        "insert into databasechangelog(id, author) values (?, ?)",
        "001-task-board",
        "fixture");
    jdbc.execute(
        "create table databasechangeloglock (id integer primary key, locked boolean not null)");
    jdbc.update("insert into databasechangeloglock(id, locked) values (1, false)");
  }

  private void insertHistory(String version, String checksum, String description) {
    jdbc.update(
        "insert into rwms_schema_history(version, checksum, description) values (?, ?, ?)",
        version,
        checksum,
        description);
  }

  private void seedRabbitCompatibilityRows() {
    jdbc.update(
        """
        insert into task_board_outbox(
          event_id, event_type, event_version, routing_key, aggregate_type, aggregate_id,
          aggregate_version, envelope_body, envelope_sha256, correlation_id, occurred_at,
          created_at, status, attempt_count, next_attempt_at)
        values (
          '70000000-0000-0000-0000-000000000001', 'task-board.board-task.created', 1,
          'task-board.board-task.created.v1', 'BOARD_TASK', 'fixture-task', 1, '{}', ?,
          '70000000-0000-0000-0000-000000000002', '2026-01-01T00:00:00Z',
          '2026-01-01T00:00:00Z', 'PENDING', 0, '2026-01-01T00:00:00Z')
        """,
        "a".repeat(64));
    jdbc.update(
        """
        insert into task_board_inbox(
          consumer_name, event_id, event_hash, event_type, event_version, received_at, processed_at)
        values ('fixture-consumer', '80000000-0000-0000-0000-000000000001', ?,
          'fixture.event', 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z')
        """,
        "b".repeat(64));
  }

  private void normalizeFixtureToCurrentJpaEnums() {
    jdbc.update(
        "update queue_usage_reference set reference_type='REPAIR_PLAN' "
            + "where reference_type='TASK_HISTORY'");
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

  private Map<String, String> retainedContentDigests() {
    var result = new LinkedHashMap<String, String>();
    RETAINED_TABLES.forEach(table -> result.put(table, digest(table)));
    return result;
  }

  private String digest(String table) {
    String json =
        "board_task".equals(table)
            ? "to_jsonb(row_value) - array['completion_deadline_enforced',"
                + "'scheduled_date','priority','pinned','request_fingerprint']"
            : "queue_entry".equals(table)
                ? "to_jsonb(row_value) - array['queue_code','worker_works','worker_materials',"
                    + "'worker_comments','source_media_references','revision_marker',"
                    + "'original_budget_seconds','current_budget_seconds']"
                : "work_queue".equals(table)
                    ? "to_jsonb(row_value) - array['code','result_photo_min_count',"
                        + "'name','description','queue_type','definition_id']"
                    : "queue_usage_reference".equals(table)
                        ? "to_jsonb(row_value) - array['queue_id','queue_definition_id']"
                    : "worker_class".equals(table)
                        ? "to_jsonb(row_value) - 'code'"
                    : "work_queue_class_binding".equals(table)
                        ? "to_jsonb(row_value) - array['binding_order','notify_urgent']"
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

  private Map<String, Integer> columnCounts() {
    return jdbc.query(
        "select table_name, count(*) from information_schema.columns "
            + "where table_schema='public' group by table_name",
        result -> {
          var counts = new LinkedHashMap<String, Integer>();
          while (result.next()) {
            counts.put(result.getString(1), result.getInt(2));
          }
          return counts;
        });
  }

  private void apply(String resource) throws Exception {
    executeResource(resource, false);
  }

  private void applyExpectFailure(String resource) throws Exception {
    executeResource(resource, true);
  }

  private void executeResource(String resource, boolean failureExpected) throws Exception {
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
    if (failureExpected) {
      assertThat(result.getExitCode())
          .withFailMessage(
              "%s unexpectedly succeeded:%n%s%n%s",
              resource, result.getStdout(), result.getStderr())
          .isNotZero();
    } else {
      assertThat(result.getExitCode())
          .withFailMessage(
              "%s failed:%n%s%n%s", resource, result.getStdout(), result.getStderr())
          .isZero();
    }
  }

  private MountableFile resource(String path) throws URISyntaxException {
    return MountableFile.forHostPath(Path.of(requireResource(path).toURI()));
  }

  private java.net.URL requireResource(String path) {
    var resource = TaskBoardFlywayMigrationIntegrationTest.class.getClassLoader().getResource(path);
    if (resource == null) {
      throw new IllegalStateException("Missing task-board migration test resource: " + path);
    }
    return resource;
  }
}
