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
  void cumulativeVersionFourAndEventSourcingVersionFiveMigrateCleanDatabaseAndRepeatIsNoOp() {
    Flyway flyway = flyway(MIGRATION_LOCATION);

    assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
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
            "queue_entry",
            "queue_usage_reference",
            "task_assignment",
            "task_auto_interruption",
            "task_board_inbox",
            "task_board_outbox",
            "task_time_event",
            "version_gap_quarantine",
            "work_queue",
            "work_queue_class_binding",
            "worker",
            "worker_class",
            "worker_class_assignment",
            "worker_deletion_intent",
            "worker_group",
            "worker_group_member");
    assertThat(columnCounts())
        .containsAllEntriesOf(
            Map.ofEntries(
                Map.entry("board_task", 12),
                Map.entry("queue_entry", 16),
                Map.entry("queue_usage_reference", 6),
                Map.entry("task_assignment", 12),
                Map.entry("task_auto_interruption", 8),
                Map.entry("task_board_inbox", 7),
                Map.entry("task_board_outbox", 24),
                Map.entry("task_time_event", 10),
                Map.entry("work_queue", 15),
                Map.entry("work_queue_class_binding", 5),
                Map.entry("worker", 16),
                Map.entry("worker_class", 9),
                Map.entry("worker_class_assignment", 6),
                Map.entry("worker_deletion_intent", 7),
                Map.entry("worker_group", 8),
                Map.entry("worker_group_member", 6)));
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
    assertThat(adopted.migrate().migrationsExecuted).isOne();
    adopted.validate();
    assertThat(adopted.migrate().migrationsExecuted).isZero();

    assertThat(retainedContentDigests()).containsExactlyInAnyOrderEntriesOf(before);
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

  private Map<String, String> retainedContentDigests() {
    var result = new LinkedHashMap<String, String>();
    RETAINED_TABLES.forEach(table -> result.put(table, digest(table)));
    return result;
  }

  private String digest(String table) {
    return jdbc.queryForObject(
        "select md5(coalesce(string_agg(to_jsonb(row_value)::text, '|' "
            + "order by to_jsonb(row_value)::text), '')) from "
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
