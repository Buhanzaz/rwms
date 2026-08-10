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

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(26);
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
            "queue_definition_class_binding",
            "queue_entry",
            "queue_usage_reference",
            "task_assignment",
            "task_auto_interruption",
            "task_board_inbox",
            "task_board_outbox",
            "task_time_event",
            "task_sync_source",
            "task_relocation_receipt",
            "task_board_warehouse_lifecycle_intent",
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
                Map.entry("board_task", 20),
                Map.entry("queue_entry", 22),
                Map.entry("queue_usage_reference", 6),
                Map.entry("task_assignment", 12),
                Map.entry("task_auto_interruption", 8),
                Map.entry("task_board_inbox", 7),
                Map.entry("task_board_outbox", 24),
                Map.entry("task_sync_source", 6),
                Map.entry("task_time_event", 10),
                Map.entry("task_board_warehouse_lifecycle_intent", 10),
                Map.entry("warehouse_kpi_settings", 10),
                Map.entry("queue_definition", 16),
                Map.entry("queue_definition_class_binding", 8),
                Map.entry("work_queue", 13),
                Map.entry("work_queue_class_binding", 8),
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
  void versionTwentyEightBackfillsOnlyExistingLogisticsDriverTasks() {
    configuration(MIGRATION_LOCATION).target("27").load().migrate();
    UUID warehouseId = UUID.randomUUID();
    UUID driverTaskId = UUID.randomUUID();
    UUID ordinaryTaskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    jdbc.update(
        """
        insert into board_task(
          id,version,warehouse_id,external_task_id,title,status,scheduled_date,
          task_lane,priority,pinned,completion_deadline_enforced)
        values (?,0,?,?,'driver','ACTIVE',date '2026-08-10','SCHEDULED',3,false,false),
               (?,0,?,null,'ordinary','ACTIVE',date '2026-08-10','SCHEDULED',3,false,false)
        """,
        driverTaskId,
        warehouseId,
        externalTaskId,
        ordinaryTaskId,
        warehouseId);
    jdbc.update(
        """
        insert into task_sync_source(
          board_task_id,external_task_id,source_client_id,source_type,source_id)
        values (?,?,'logistics-service','LOGISTICS_DRIVER_TASK',?)
        """,
        driverTaskId,
        externalTaskId,
        UUID.randomUUID());

    Flyway current = configuration(MIGRATION_LOCATION).target("28").load();
    assertThat(current.migrate().migrationsExecuted).isOne();

    assertThat(
            jdbc.queryForObject(
                "select driver_audience_mode from board_task where id=?",
                String.class,
                driverTaskId))
        .isEqualTo("WAREHOUSE_DRIVERS");
    assertThat(
            jdbc.queryForObject(
                "select driver_audience_mode from board_task where id=?",
                String.class,
                ordinaryTaskId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                """
                select character_maximum_length
                  from information_schema.columns
                 where table_schema='public' and table_name='board_task'
                   and column_name='planned_driver_name_snapshot'
                """,
                Integer.class))
        .isEqualTo(512);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update board_task
                       set driver_audience_mode='ASSIGNED_DRIVER',
                           planned_driver_worker_id=null,
                           planned_driver_name_snapshot=null
                     where id=?
                    """,
                    driverTaskId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
  }

  @Test
  void versionTwentyNineRemovesSharedDriverIdentityAndEnforcesIdentityFreeAudience() {
    configuration(MIGRATION_LOCATION).target("28").load().migrate();
    UUID taskId = UUID.randomUUID();
    UUID workerId = UUID.randomUUID();
    jdbc.update(
        """
        insert into board_task(
          id,version,warehouse_id,title,status,scheduled_date,task_lane,priority,pinned,
          completion_deadline_enforced,driver_audience_mode,planned_driver_worker_id,
          planned_driver_name_snapshot)
        values (?,0,?,'shared driver task','ACTIVE',date '2026-08-10','SCHEDULED',3,
          false,false,'WAREHOUSE_DRIVERS',?,'Старый ответственный')
        """,
        taskId,
        UUID.randomUUID(),
        workerId);

    Flyway upgraded = configuration(MIGRATION_LOCATION).target("29").load();
    assertThat(upgraded.migrate().migrationsExecuted).isOne();
    upgraded.validate();

    assertThat(
            jdbc.queryForMap(
                """
                select driver_audience_mode,planned_driver_worker_id,
                       planned_driver_name_snapshot
                  from board_task where id=?
                """,
                taskId))
        .containsEntry("driver_audience_mode", "WAREHOUSE_DRIVERS")
        .containsEntry("planned_driver_worker_id", null)
        .containsEntry("planned_driver_name_snapshot", null);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update board_task
                       set planned_driver_worker_id=?,
                           planned_driver_name_snapshot='Повторный ответственный'
                     where id=?
                    """,
                    UUID.randomUUID(),
                    taskId))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class)
        .hasMessageContaining("ck_board_task_driver_audience");
  }

  @Test
  void versionTwentySixMakesExistingGlobalQueuePositionsContiguous() {
    configuration(MIGRATION_LOCATION).target("25").load().migrate();
    UUID external = UUID.fromString("26000000-0000-0000-0000-000000000001");
    UUID internal = UUID.fromString("26000000-0000-0000-0000-000000000002");
    UUID holding = UUID.fromString("26000000-0000-0000-0000-000000000003");

    jdbc.update(
        """
        insert into queue_definition(
          id,version,revision_marker,name,normalized_name,description,queue_type,queue_purpose,
          sort_order,active,hidden,collapsed,holding_period_minutes,notification_threshold,
          notify_when_threshold_reached,result_photo_min_count)
        values
          (?,0,?,'Внешние работы','внешние работы',null,'REPAIR','GENERAL',40,true,false,false,null,null,false,1),
          (?,0,?,'Внутренние работы','внутренние работы',null,'REPAIR','GENERAL',100,true,false,false,null,null,false,1),
          (?,0,?,'Удержание','удержание',null,'HOLDING','GENERAL',5,true,false,false,null,null,false,1)
        """,
        external,
        UUID.randomUUID(),
        internal,
        UUID.randomUUID(),
        holding,
        UUID.randomUUID());

    Flyway v26 = configuration(MIGRATION_LOCATION).target("26").load();
    assertThat(v26.migrate().migrationsExecuted).isOne();
    assertThat(
            jdbc.queryForList(
                "select id,sort_order from queue_definition where queue_purpose='GENERAL' order by sort_order"))
        .extracting(row -> row.get("id"), row -> row.get("sort_order"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(external, 1),
            org.assertj.core.groups.Tuple.tuple(internal, 2),
            org.assertj.core.groups.Tuple.tuple(holding, 3));
    assertThat(v26.migrate().migrationsExecuted).isZero();
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
  void versionTwentyOneCutsReviewedSpbDriverQueueOverOnceWithoutLosingTaskState() {
    configuration(MIGRATION_LOCATION).target("20").load().migrate();
    UUID spb = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID msk = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID driverDefinition =
        UUID.fromString("c4d01176-43d3-4b69-ae1f-9a2a2e6c9971");
    UUID driverClass = UUID.fromString("442f7eed-563a-4a98-8a6c-84886256a07a");
    UUID slingerClass = UUID.fromString("60000000-0000-0000-0000-000000000001");
    UUID secondaryBinding = UUID.fromString("61000000-0000-0000-0000-000000000001");
    UUID mskQueue = UUID.fromString("62000000-0000-0000-0000-000000000001");
    UUID taskId = UUID.fromString("63000000-0000-0000-0000-000000000001");
    UUID entryId = UUID.fromString("64000000-0000-0000-0000-000000000001");
    UUID historyId = UUID.fromString("65000000-0000-0000-0000-000000000001");

    jdbc.update(
        """
        insert into queue_definition(
          id,version,revision_marker,name,normalized_name,description,queue_type)
        values (?,0,?,'Перемещения','перемещения','СПБ source','MOVEMENT')
        """,
        driverDefinition,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into worker_class(
          id,version,revision_marker,name,description,comment_text,sort_order,active)
        values
          (?,0,?,'Старое имя водителей',null,null,10,true),
          (?,0,?,'Стропальщики',null,null,20,true)
        """,
        driverClass,
        UUID.randomUUID(),
        slingerClass,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into work_queue(
          id,version,revision_marker,warehouse_id,sort_order,active,hidden,collapsed,
          holding_period_minutes,notification_threshold,notify_when_threshold_reached,
          result_photo_min_count,definition_id)
        values
          (?,0,?,?,7,true,false,false,null,null,false,0,?),
          (?,0,?,?,4,true,false,false,null,null,false,0,?)
        """,
        driverDefinition,
        UUID.randomUUID(),
        spb,
        driverDefinition,
        mskQueue,
        UUID.randomUUID(),
        msk,
        driverDefinition);
    jdbc.update(
        """
        insert into work_queue_class_binding(
          id,version,queue_id,worker_class_id,stop_task_on_take,binding_order,notify_urgent)
        values
          (?,0,?,?,false,0,false),
          (?,0,?,?,false,1,true),
          (?,0,?,?,false,0,false)
        """,
        UUID.randomUUID(),
        driverDefinition,
        driverClass,
        secondaryBinding,
        driverDefinition,
        slingerClass,
        UUID.randomUUID(),
        mskQueue,
        slingerClass);
    jdbc.update(
        """
        insert into board_task(
          id,version,warehouse_id,external_task_id,title,status,priority,pinned,
          completion_deadline_enforced)
        values (?,3,?,null,'Существующее перемещение','ACTIVE',2,true,false)
        """,
        taskId,
        spb);
    jdbc.update(
        """
        insert into queue_entry(
          id,version,revision_marker,task_id,queue_id,route_index,queue_position,
          entry_type,status,active_work_seconds)
        values (?,5,?,?,?,0,11,'REAL','IN_PROGRESS',125)
        """,
        entryId,
        UUID.randomUUID(),
        taskId,
        driverDefinition);
    jdbc.update(
        """
        insert into task_time_event(
          id,version,queue_entry_id,event_type,reason,created_at)
        values (?,2,?,'STARTED','История существующего задания',clock_timestamp())
        """,
        historyId,
        entryId);

    Flyway cutover = configuration(MIGRATION_LOCATION).target("21").load();
    assertThat(cutover.migrate().migrationsExecuted).isOne();

    assertThat(
            jdbc.queryForMap(
                "select queue_purpose,name from queue_definition where id=?",
                driverDefinition))
        .containsEntry("queue_purpose", "LOGISTICS_DRIVER")
        .containsEntry("name", "Перемещения");
    assertThat(
            jdbc.queryForMap(
                """
                select sort_order,active,hidden,result_photo_min_count
                  from work_queue
                 where id=?
                """,
                driverDefinition))
        .containsEntry("sort_order", 7)
        .containsEntry("active", true)
        .containsEntry("hidden", false)
        .containsEntry("result_photo_min_count", 1);
    assertThat(
            jdbc.queryForMap(
                "select active,hidden from work_queue where id=?", mskQueue))
        .containsEntry("active", false)
        .containsEntry("hidden", true);
    assertThat(
            jdbc.queryForMap(
                """
                select participation_policy,notify_on_primary_take
                  from work_queue_class_binding
                 where id=?
                """,
                secondaryBinding))
        .containsEntry("participation_policy", "OPTIONAL")
        .containsEntry("notify_on_primary_take", true);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from information_schema.columns
                 where table_schema='public'
                   and table_name='work_queue_class_binding'
                   and column_name='notify_urgent'
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "select version,status,title,task_lane from board_task where id=?", taskId))
        .containsEntry("version", 3L)
        .containsEntry("status", "ACTIVE")
        .containsEntry("title", "Существующее перемещение")
        .containsEntry("task_lane", "SCHEDULED");
    assertThat(
            jdbc.queryForMap(
                """
                select version,queue_id,queue_position,status,active_work_seconds
                  from queue_entry
                 where id=?
                """,
                entryId))
        .containsEntry("version", 5L)
        .containsEntry("queue_id", driverDefinition)
        .containsEntry("queue_position", 11)
        .containsEntry("status", "IN_PROGRESS")
        .containsEntry("active_work_seconds", 125L);
    assertThat(
            jdbc.queryForMap(
                "select version,queue_entry_id,event_type,reason from task_time_event where id=?",
                historyId))
        .containsEntry("version", 2L)
        .containsEntry("queue_entry_id", entryId)
        .containsEntry("event_type", "STARTED")
        .containsEntry("reason", "История существующего задания");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from queue_definition where queue_purpose='LOGISTICS_DRIVER'",
                Integer.class))
        .isOne();
    assertThat(cutover.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionTwentyTwoRemovesOnlyDefaultLegacyRepairComplexityAndPreservesSettings() {
    configuration(MIGRATION_LOCATION).target("21").load().migrate();
    UUID settingsId = UUID.fromString("71000000-0000-0000-0000-000000000001");
    UUID warehouseId = UUID.fromString("72000000-0000-0000-0000-000000000001");
    jdbc.update(
        """
        insert into warehouse_kpi_settings(
          id,version,revision_marker,warehouse_id,time_zone,status,data_available_from,
          palette_id,active_schedule_id,pending_schedule_id,
          repair_light_boundary_minutes,repair_medium_boundary_minutes,
          repair_complex_boundary_minutes)
        values (?,4,?,?,?,'DRAFT',null,null,null,null,60,180,360)
        """,
        settingsId,
        UUID.randomUUID(),
        warehouseId,
        "Europe/Moscow");

    Flyway cutover = configuration(MIGRATION_LOCATION).target("22").load();
    assertThat(cutover.migrate().migrationsExecuted).isOne();

    assertThat(
            jdbc.queryForMap(
                """
                select id,version,warehouse_id,time_zone,status
                  from warehouse_kpi_settings
                 where id=?
                """,
                settingsId))
        .containsEntry("id", settingsId)
        .containsEntry("version", 4L)
        .containsEntry("warehouse_id", warehouseId)
        .containsEntry("time_zone", "Europe/Moscow")
        .containsEntry("status", "DRAFT");
    assertThat(
            jdbc.queryForList(
                """
                select column_name
                  from information_schema.columns
                 where table_schema='public'
                   and table_name='warehouse_kpi_settings'
                   and column_name in (
                     'repair_light_boundary_minutes',
                     'repair_medium_boundary_minutes',
                     'repair_complex_boundary_minutes')
                """,
                String.class))
        .isEmpty();
    assertThat(cutover.migrate().migrationsExecuted).isZero();
  }

  @Test
  void versionTwentyTwoFailsBeforeDroppingNonDefaultLegacyRepairComplexity() {
    configuration(MIGRATION_LOCATION).target("21").load().migrate();
    jdbc.update(
        """
        insert into warehouse_kpi_settings(
          id,version,revision_marker,warehouse_id,time_zone,status,data_available_from,
          palette_id,active_schedule_id,pending_schedule_id,
          repair_light_boundary_minutes,repair_medium_boundary_minutes,
          repair_complex_boundary_minutes)
        values (?,?,?,?,'Europe/Moscow','DRAFT',null,null,null,null,45,120,300)
        """,
        UUID.randomUUID(),
        7L,
        UUID.randomUUID(),
        UUID.randomUUID());

    Flyway cutover = configuration(MIGRATION_LOCATION).target("22").load();
    assertThatThrownBy(cutover::migrate)
        .isInstanceOf(FlywayException.class)
        .hasStackTraceContaining("On the old deployment, first run the controlled maintenance import");

    assertThat(
            jdbc.queryForList(
                """
                select column_name
                  from information_schema.columns
                 where table_schema='public'
                   and table_name='warehouse_kpi_settings'
                   and column_name in (
                     'repair_light_boundary_minutes',
                     'repair_medium_boundary_minutes',
                     'repair_complex_boundary_minutes')
                 order by column_name
                """,
                String.class))
        .containsExactly(
            "repair_complex_boundary_minutes",
            "repair_light_boundary_minutes",
            "repair_medium_boundary_minutes");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='22' and success",
                Integer.class))
        .isZero();
  }

  @Test
  void versionTwentyFourRestoresAuditedLocalConnectionsAndRemovesOnlyUnusedV23SyntheticQueues() {
    configuration(MIGRATION_LOCATION).target("22").load().migrate();
    UUID spb = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID msk = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID generatedWarehouse = UUID.fromString("f5338f81-2831-4b14-ab99-2e611d09ba3e");
    UUID externalDefinition = UUID.fromString("8873b3d1-2148-47cf-a0ed-789b853f1242");
    UUID furnitureDefinition = UUID.fromString("096ada15-e366-4478-a86c-037bcddbd83e");
    UUID spbExternal = externalDefinition;
    UUID mskExternal = UUID.fromString("54641234-be75-480c-926c-e4ee695d9bdb");
    UUID reviewedWorkerClass = UUID.fromString("6cee2fd5-a5d9-4205-a2bf-272181b8a284");
    UUID reviewedBinding = UUID.fromString("8e0d6717-e41f-4eaf-ab32-136087c6d5a6");

    for (UUID warehouseId : List.of(spb, msk, generatedWarehouse)) {
      jdbc.update(
          """
          insert into warehouse_metadata(id,version,source_version,time_zone,active)
          values (?,0,0,'Europe/Moscow',true)
          """,
          warehouseId);
    }
    jdbc.update(
        """
        insert into queue_definition(
          id,version,revision_marker,name,normalized_name,description,queue_type,queue_purpose)
        values
          (?,0,?,'Внешние работы','внешние работы',null,'REPAIR','GENERAL'),
          (?,0,?,'Перемещение мебели','перемещение мебели',null,'FURNITURE_MOVEMENT','GENERAL')
        """,
        externalDefinition,
        UUID.randomUUID(),
        furnitureDefinition,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into worker_class(
          id,version,revision_marker,name,description,comment_text,sort_order,active)
        values (?,0,?,'Рабочие',null,null,10,true)
        """,
        reviewedWorkerClass,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into work_queue(
          id,version,revision_marker,warehouse_id,sort_order,active,hidden,collapsed,
          holding_period_minutes,notification_threshold,notify_when_threshold_reached,
          result_photo_min_count,definition_id)
        values
          (?,0,?,?,30,true,false,false,null,null,false,1,?),
          (?,0,?,?,30,true,false,false,null,null,false,1,?)
        """,
        spbExternal,
        UUID.randomUUID(),
        spb,
        externalDefinition,
        mskExternal,
        UUID.randomUUID(),
        msk,
        externalDefinition);
    jdbc.update(
        """
        insert into work_queue_class_binding(
          id,version,queue_id,worker_class_id,stop_task_on_take,binding_order,
          participation_policy,notify_on_primary_take)
        values (?,0,?,?,false,0,'PRIMARY',false)
        """,
        reviewedBinding,
        spbExternal,
        reviewedWorkerClass);

    Flyway v23 = configuration(MIGRATION_LOCATION).target("23").load();
    assertThat(v23.migrate().migrationsExecuted).isOne();

    // Existing production queues already have a stream.  Cover the V24 append path as well as
    // the baseline path used for a queue that had no event history before the faulty V23 run.
    seedQueueBaseline(spbExternal);

    UUID mskSyntheticFurniture = syntheticQueueId(msk, furnitureDefinition);
    UUID protectedSyntheticExternal = syntheticQueueId(generatedWarehouse, externalDefinition);
    appendLegacyBatchQueueEvent(mskSyntheticFurniture);
    UUID taskId = UUID.fromString("32000000-0000-0000-0000-000000000024");
    UUID entryId = UUID.fromString("42000000-0000-0000-0000-000000000024");
    jdbc.update(
        """
        insert into board_task(
          id,version,warehouse_id,external_task_id,title,status,priority,pinned,
          completion_deadline_enforced)
        values (?,0,?,null,'Защищённая синтетическая очередь','ACTIVE',3,false,false)
        """,
        taskId,
        generatedWarehouse);
    jdbc.update(
        """
        insert into queue_entry(
          id,version,revision_marker,task_id,queue_id,route_index,queue_position,
          entry_type,status,active_work_seconds)
        values (?,0,?,?,?,0,0,'REAL','WAITING',0)
        """,
        entryId,
        UUID.randomUUID(),
        taskId,
        protectedSyntheticExternal);

    Flyway v24 = configuration(MIGRATION_LOCATION).target("24").load();
    assertThat(v24.migrate().migrationsExecuted).isOne();

    assertThat(
            jdbc.queryForMap(
                "select warehouse_id,definition_id,sort_order,active,hidden,collapsed,"
                    + "result_photo_min_count from work_queue where id=?",
                spbExternal))
        .containsEntry("warehouse_id", spb)
        .containsEntry("definition_id", externalDefinition)
        .containsEntry("sort_order", 30)
        .containsEntry("active", true)
        .containsEntry("hidden", false)
        .containsEntry("collapsed", false)
        .containsEntry("result_photo_min_count", 1);
    assertThat(
            jdbc.queryForMap(
                "select id,version,worker_class_id,binding_order,participation_policy "
                    + "from work_queue_class_binding where queue_id=?",
                spbExternal))
        .containsEntry("id", reviewedBinding)
        .containsEntry("version", 0L)
        .containsEntry("worker_class_id", reviewedWorkerClass)
        .containsEntry("binding_order", 0)
        .containsEntry("participation_policy", "PRIMARY");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from work_queue_class_binding where queue_id=?",
                Integer.class,
                mskExternal))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "select current_version,last_event_id from event_stream_head "
                    + "where aggregate_type='WORK_QUEUE' and aggregate_id=?",
                spbExternal.toString()))
        .containsEntry("current_version", 1L);
    assertThat(
            jdbc.queryForMap(
                "select aggregate_version,event_type,payload->>'sortOrder' as sort_order "
                    + "from domain_event where aggregate_type='WORK_QUEUE' and aggregate_id=? "
                    + "order by aggregate_version desc limit 1",
                spbExternal.toString()))
        .containsEntry("aggregate_version", 1L)
        .containsEntry("event_type", "task-board.work-queue.changed.v1")
        .containsEntry("sort_order", "30");
    assertThat(
            jdbc.queryForMap(
                "select aggregate_version,envelope_body->'payload'->>'sortOrder' as sort_order "
                    + "from outbox_event where aggregate_type='WORK_QUEUE' and aggregate_id=?",
                spbExternal.toString()))
        .containsEntry("aggregate_version", 1L)
        .containsEntry("sort_order", "30");
    assertThat(
            jdbc.queryForObject("select count(*) from work_queue where id=?", Integer.class, mskSyntheticFurniture))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='WORK_QUEUE' and aggregate_id=?",
                Integer.class,
                mskSyntheticFurniture.toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject("select count(*) from outbox_event where aggregate_id=?", Integer.class, mskSyntheticFurniture.toString()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from work_queue where id=?", Integer.class, protectedSyntheticExternal))
        .isOne();
    assertThat(
            jdbc.queryForObject("select queue_id from queue_entry where id=?", UUID.class, entryId))
        .isEqualTo(protectedSyntheticExternal);
    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.queue_definition_class_binding')", String.class))
        .isNull();
    assertThat(
            jdbc.queryForList(
                "select column_name from information_schema.columns "
                    + "where table_schema='public' and table_name='queue_definition' "
                    + "and column_name in ('sort_order','active','hidden','collapsed',"
                    + "'holding_period_minutes','notification_threshold',"
                    + "'notify_when_threshold_reached','result_photo_min_count')",
                String.class))
        .isEmpty();
    assertThat(v24.migrate().migrationsExecuted).isZero();
  }

  private UUID syntheticQueueId(UUID warehouseId, UUID definitionId) {
    return jdbc.queryForObject(
        "select md5(? || ':' || ? || ':global-work-queue:v23')::uuid",
        UUID.class,
        warehouseId.toString(),
        definitionId.toString());
  }

  private void seedQueueBaseline(UUID queueId) {
    UUID eventId = UUID.randomUUID();
    String aggregateId = queueId.toString();
    String payload =
        jdbc.queryForObject(
            "select jsonb_build_object('workQueueId', ?::uuid, 'deleted', false)::text",
            String.class,
            aggregateId);
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('WORK_QUEUE',?,0,?,clock_timestamp())
        """,
        aggregateId,
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref,payload,payload_sha256,
          baseline)
        values (
          ?,'WORK_QUEUE',?,0,'task-board.work-queue.baseline.v1',1,
          null,clock_timestamp(),?,null,null,?::jsonb,
          encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),true)
        """,
        eventId,
        aggregateId,
        UUID.randomUUID(),
        payload,
        payload);
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values (
          'task-board-live-v1','WORK_QUEUE',?,0,
          encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),clock_timestamp())
        """,
        aggregateId,
        payload);
  }

  /** Simulates the pre-fix V23 UI batch: event-store-only history is not operational usage. */
  private void appendLegacyBatchQueueEvent(UUID queueId) {
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    String aggregateId = queueId.toString();
    String payload =
        jdbc.queryForObject(
            "select jsonb_build_object('workQueueId', ?::uuid, 'deleted', false)::text",
            String.class,
            aggregateId);
    jdbc.update(
        """
        update event_stream_head
           set current_version=1,last_event_id=?,updated_at=clock_timestamp()
         where aggregate_type='WORK_QUEUE' and aggregate_id=? and current_version=0
        """,
        eventId,
        aggregateId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,causation_id,actor_ref,payload,payload_sha256,
          baseline)
        values (
          ?,'WORK_QUEUE',?,1,'task-board.work-queue.changed.v1',1,
          clock_timestamp(),clock_timestamp(),?,null,null,?::jsonb,
          encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),false)
        """,
        eventId,
        aggregateId,
        correlationId,
        payload,
        payload);
    String envelope =
        jdbc.queryForObject(
            """
            select jsonb_build_object(
              'envelopeVersion', 2,
              'eventId', event.event_id,
              'eventType', event.event_type,
              'eventVersion', event.event_version,
              'occurredAt', event.occurred_at,
              'recordedAt', event.recorded_at,
              'producer', 'task-board-service',
              'aggregateType', event.aggregate_type,
              'aggregateId', event.aggregate_id,
              'aggregateVersion', event.aggregate_version,
              'correlation', jsonb_build_object(
                'correlationId', event.correlation_id,
                'causationId', event.causation_id),
              'actorRef', event.actor_ref,
              'payload', event.payload)::text
            from domain_event event
            where event.event_id=?
            """,
            String.class,
            eventId);
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
          envelope_body,envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (
          ?,'WORK_QUEUE',?,1,'task-board.work-queue.changed.v1',
          'rwms.task-board.work-queue.v1',?::jsonb,
          encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),
          'PENDING',0,clock_timestamp(),clock_timestamp())
        """,
        eventId,
        aggregateId,
        envelope,
        envelope);
    jdbc.update(
        """
        insert into projection_checkpoint(
          projection_name,aggregate_type,aggregate_id,aggregate_version,projection_sha256,updated_at)
        values (
          'task-board-live-v1','WORK_QUEUE',?,1,
          encode(sha256(convert_to((?::jsonb)::text,'UTF8')),'hex'),clock_timestamp())
        on conflict (projection_name,aggregate_type,aggregate_id)
        do update set aggregate_version=excluded.aggregate_version,
                      projection_sha256=excluded.projection_sha256,
                      updated_at=excluded.updated_at
        """,
        aggregateId,
        payload);
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
    assertThat(adopted.migrate().migrationsExecuted).isEqualTo(23);
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
                + "'scheduled_date','task_lane','priority','pinned','request_fingerprint',"
                + "'driver_audience_mode','planned_driver_worker_id',"
                + "'planned_driver_name_snapshot']"
            : "queue_entry".equals(table)
                ? "to_jsonb(row_value) - array['queue_code','worker_works','worker_materials',"
                    + "'worker_comments','source_media_references','revision_marker',"
                    + "'original_budget_seconds','current_budget_seconds']"
                : "work_queue".equals(table)
                    ? "to_jsonb(row_value) - array['code','result_photo_min_count',"
                        + "'name','description','queue_type','definition_id',"
                        + "'sort_order','active','hidden','collapsed',"
                        + "'holding_period_minutes','notification_threshold',"
                        + "'notify_when_threshold_reached','revision_marker']"
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
