package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventFactFactory;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueUsageReferenceRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerClassRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerGroupRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import jakarta.persistence.EntityManagerFactory;
import java.net.URISyntaxException;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.annotation.Transactional;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.MountableFile;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TaskBoardAdoptedV4JpaValidationIntegrationTest {

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
  private static final PostgreSQLContainer postgres = startPreparedVersionFourDatabase();
  private static final Map<String, String> beforeStartup = retainedDigests(jdbc());

  @Autowired JdbcTemplate jdbc;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired BoardTaskRepository tasks;
  @Autowired QueueEntryRepository entries;
  @Autowired QueueUsageReferenceRepository usageReferences;
  @Autowired TaskSyncSourceRepository taskSyncSources;
  @Autowired WorkQueueRepository queues;
  @Autowired WorkerClassRepository workerClasses;
  @Autowired WorkerGroupRepository workerGroups;
  @Autowired WorkerRepository workers;
  @Autowired TaskBoardEventFactFactory facts;
  @Autowired ObjectMapper objectMapper;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", postgres::getJdbcUrl);
    properties.add("spring.datasource.username", postgres::getUsername);
    properties.add("spring.datasource.password", postgres::getPassword);
  }

  @Test
  @Transactional
  void bootMigratesAdoptedVersionFourThroughVersionTwentyFiveAndValidatesJpa() {
    assertThat(entityManagerFactory.isOpen()).isTrue();
    assertThat(retainedDigests(jdbc)).containsExactlyInAnyOrderEntriesOf(beforeStartup);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='4' and type='BASELINE' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='18' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='19' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='20' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='21' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='22' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='24' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='25' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='26' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select to_regclass('public.queue_definition_class_binding')", String.class))
        .isEqualTo("queue_definition_class_binding");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from information_schema.columns
                 where table_schema='public'
                   and table_name='warehouse_kpi_settings'
                   and column_name like 'repair_%_boundary_minutes'
                """,
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='6' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='7' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from board_task where completion_deadline_enforced",
                Integer.class))
        .isZero();
    assertThat(taskSyncSources.findAll()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history "
                    + "where version='5' and type='SQL' and success",
                Integer.class))
        .isOne();
    assertThat(tasks.findAll()).hasSize(4);
    assertThat(queues.findAll()).hasSize(3);
    assertThat(workers.findAll()).hasSize(4);
    assertThat(jdbc.queryForObject("select count(*) from task_board_outbox", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from task_board_inbox", Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class))
        .isEqualTo(22);
    assertThat(jdbc.queryForObject("select count(*) from outbox_event", Integer.class)).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from board_task where external_task_id is not null "
                    + "and not completion_deadline_enforced "
                    + "and request_fingerprint ~ '^[0-9a-f]{64}$'",
                Integer.class))
        .isEqualTo(
            jdbc.queryForObject(
                "select count(*) from board_task where external_task_id is not null "
                    + "and not completion_deadline_enforced",
                Integer.class));

    workerClasses
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload(
                    "WORKER_CLASS", item.getId(), facts.workerClass(item, false)));
    workers
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload("WORKER", item.getId(), facts.worker(item, false)));
    workerGroups
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload(
                    "WORKER_GROUP", item.getId(), facts.workerGroup(item, false)));
    queues
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload("WORK_QUEUE", item.getId(), facts.workQueue(item, false)));
    usageReferences
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload(
                    "QUEUE_USAGE_REFERENCE",
                    item.getId(),
                    facts.queueUsageReference(item, false)));
    tasks
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload("BOARD_TASK", item.getId(), facts.boardTask(item, false)));
    entries
        .findAll()
        .forEach(
            item ->
                assertBaselinePayload(
                    "QUEUE_ENTRY", item.getId(), facts.queueEntry(item, false)));
  }

  @Test
  @Transactional
  void versionTwentyCanonicalizesVersionFiveQueueEntryBaselineForExactReplay() throws Exception {
    var sourceCorrelatedEntry =
        entries.findAll().stream()
            .filter(item -> item.getTask().getExternalTaskId() != null)
            .findFirst()
            .orElseThrow();
    String storedPayload =
        jdbc.queryForObject(
            "select payload::text from domain_event where aggregate_type='QUEUE_ENTRY' "
                + "and aggregate_id=? and baseline",
            String.class,
            sourceCorrelatedEntry.getId().toString());
    var payload = objectMapper.readTree(storedPayload);

    assertThat(payload.has("externalTaskId")).isFalse();
    assertThat(payload.required("taskId").textValue())
        .isEqualTo(sourceCorrelatedEntry.getTask().getId().toString());
    assertThat(payload.required("routeIndex").intValue())
        .isEqualTo(sourceCorrelatedEntry.getRouteIndex());
    assertThat(payload.has("originalBudgetSeconds")).isTrue();
    assertThat(payload.has("currentBudgetSeconds")).isTrue();
    assertBaselinePayload(
        "QUEUE_ENTRY",
        sourceCorrelatedEntry.getId(),
        facts.queueEntry(sourceCorrelatedEntry, false));
  }

  private void assertBaselinePayload(String aggregateType, UUID id, Object fact) {
    String serialized;
    try {
      JsonNode expectedFact = objectMapper.valueToTree(fact);
      serialized = objectMapper.writeValueAsString(expectedFact);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new AssertionError("Cannot serialize expected task-board baseline fact", exception);
    }
    String expected =
        jdbc.queryForObject("select (?::jsonb)::text", String.class, serialized);
    String actual =
        jdbc.queryForObject(
            "select payload::text from domain_event where aggregate_type=? "
                + "and aggregate_id=? and baseline",
            String.class,
            aggregateType,
            id.toString());
    assertThat(actual).isEqualTo(expected);
  }

  @AfterAll
  static void stopDatabase() {
    postgres.stop();
  }

  private static PostgreSQLContainer startPreparedVersionFourDatabase() {
    PostgreSQLContainer container = new PostgreSQLContainer("postgres:17-alpine");
    container.start();
    try {
      apply(container, "releases/V0001__adopt-task-board-schema/apply.sql");
      apply(container, "releases/V0002__add-integration-messaging/apply.sql");
      apply(container, "releases/V0003__enforce-case-insensitive-identities/apply.sql");
      apply(container, "releases/V0004__add-worker-credential-operation/apply.sql");
      JdbcTemplate jdbc = jdbc(container);
      createHistoricalMigrationEvidence(jdbc);
      apply(container, "fixtures/f0-nonempty.sql");
      jdbc.update(
          "update queue_usage_reference set reference_type='REPAIR_PLAN' "
              + "where reference_type='TASK_HISTORY'");
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
      seedRabbitCompatibilityRows(jdbc);
      apply(container, "flyway/verify-version-4.sql");
      Flyway.configure()
          .dataSource(container.getJdbcUrl(), container.getUsername(), container.getPassword())
          .locations("classpath:db/migration")
          .baselineOnMigrate(false)
          .baselineVersion("4")
          .baselineDescription("Task-board post-F2 schema")
          .validateOnMigrate(true)
          .validateMigrationNaming(true)
          .cleanDisabled(true)
          .outOfOrder(false)
          .load()
          .baseline();
      return container;
    } catch (Exception exception) {
      container.stop();
      throw new ExceptionInInitializerError(exception);
    }
  }

  private static void createHistoricalMigrationEvidence(JdbcTemplate jdbc) {
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
        jdbc,
        "0001",
        "1e1910dbeea555ac08af0b4948bfc97a59888a5499e847d5a605fc30c8106adb",
        "Adopt existing task-board schema");
    insertHistory(
        jdbc,
        "0002",
        "4a801d2a93c38ff7cd0bc59df5300a9d015d901f733fac1280ecb6e25b8b14b5",
        "Add task-board idempotency outbox and inbox");
    insertHistory(
        jdbc,
        "0003",
        "31a451efd2d770c7260cae5ae80335ebc6c44582568c1dada5c92e0b2db3b9af",
        "Enforce case-insensitive task-board identities");
    insertHistory(
        jdbc,
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

  private static void insertHistory(
      JdbcTemplate jdbc, String version, String checksum, String description) {
    jdbc.update(
        "insert into rwms_schema_history(version, checksum, description) values (?, ?, ?)",
        version,
        checksum,
        description);
  }

  private static void seedRabbitCompatibilityRows(JdbcTemplate jdbc) {
    jdbc.update(
        """
        insert into task_board_outbox(
          event_id, event_type, event_version, routing_key, aggregate_type, aggregate_id,
          aggregate_version, envelope_body, envelope_sha256, correlation_id, occurred_at,
          created_at, status, attempt_count, next_attempt_at)
        values (
          '70000000-0000-0000-0000-000000000101', 'task-board.board-task.created', 1,
          'task-board.board-task.created.v1', 'BOARD_TASK', 'restored-task', 1, '{}', ?,
          '70000000-0000-0000-0000-000000000102', '2026-01-01T00:00:00Z',
          '2026-01-01T00:00:00Z', 'PENDING', 0, '2026-01-01T00:00:00Z')
        """,
        "c".repeat(64));
    jdbc.update(
        """
        insert into task_board_inbox(
          consumer_name, event_id, event_hash, event_type, event_version, received_at, processed_at)
        values ('restored-consumer', '80000000-0000-0000-0000-000000000101', ?,
          'fixture.event', 1, '2026-01-01T00:00:00Z', '2026-01-01T00:00:01Z')
        """,
        "d".repeat(64));
  }

  private static JdbcTemplate jdbc() {
    return jdbc(postgres);
  }

  private static JdbcTemplate jdbc(PostgreSQLContainer container) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            container.getJdbcUrl(), container.getUsername(), container.getPassword()));
  }

  private static Map<String, String> retainedDigests(JdbcTemplate jdbc) {
    var result = new LinkedHashMap<String, String>();
    RETAINED_TABLES.forEach(table -> result.put(table, digest(jdbc, table)));
    return result;
  }

  private static String digest(JdbcTemplate jdbc, String table) {
    String json =
        "board_task".equals(table)
            ? "to_jsonb(row_value) - array['completion_deadline_enforced',"
                + "'scheduled_date','task_lane','priority','pinned','request_fingerprint']"
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

  private static void apply(PostgreSQLContainer container, String resource) throws Exception {
    String remote = "/tmp/" + resource.replace('/', '-');
    container.copyFileToContainer(resource(resource), remote);
    var result =
        container.execInContainer(
            "psql",
            "-X",
            "--single-transaction",
            "-v",
            "ON_ERROR_STOP=1",
            "-U",
            container.getUsername(),
            "-d",
            container.getDatabaseName(),
            "-f",
            remote);
    if (result.getExitCode() != 0) {
      throw new IllegalStateException(
          resource + " failed:\n" + result.getStdout() + "\n" + result.getStderr());
    }
  }

  private static MountableFile resource(String path) throws URISyntaxException {
    var resource =
        TaskBoardAdoptedV4JpaValidationIntegrationTest.class
            .getClassLoader()
            .getResource(path);
    if (resource == null) {
      throw new IllegalStateException("Missing adopted task-board test resource: " + path);
    }
    return MountableFile.forHostPath(Path.of(resource.toURI()));
  }
}
