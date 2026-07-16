package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = "spring.jpa.hibernate.ddl-auto=validate")
@ActiveProfiles("test")
class TaskBoardReleaseSchemaValidationIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @DynamicPropertySource
  static void databaseProperties(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
  }

  @Autowired BoardTaskRepository tasks;
  @Autowired WorkQueueRepository queues;
  @Autowired WorkerRepository workers;
  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayVersionFiveSchemaValidatesAndLegacyGraphIsReadableThroughJpa() {
    jdbc.execute(resource("/fixtures/f0-nonempty.sql"));
    UUID warehouseOne = UUID.fromString("00000000-0000-0000-0000-000000000001");

    assertThat(tasks.findAll())
        .hasSize(4)
        .extracting(task -> task.getStatus())
        .contains(TaskStatus.ACTIVE, TaskStatus.DONE, TaskStatus.CANCELLED);
    assertThat(queues.findAllByWarehouseIdOrderBySortOrderAscNameAsc(warehouseOne))
        .extracting(queue -> queue.getType())
        .containsExactly(QueueType.MOVEMENT, QueueType.REPAIR);
    assertThat(workers.findAllByWarehouseIdOrderByDisplayNameAsc(warehouseOne)).hasSize(2);
    assertThat(
            jdbc.queryForList(
                "select indexname from pg_indexes where schemaname='public'", String.class))
        .contains(
            "uk_worker_class_code_ci", "uk_worker_app_login_ci", "uk_work_queue_code_ci");
    assertThat(
            jdbc.queryForMap(
                """
                select
                  count(*) filter (where credential_operation_id is not null) operation_ids,
                  count(*) filter (where credential_operation_type is not null) operation_types,
                  count(*) filter (where credential_operation_started_at is not null) started_times
                from worker
                """))
        .containsEntry("operation_ids", 0L)
        .containsEntry("operation_types", 0L)
        .containsEntry("started_times", 0L);
    assertThat(
            jdbc.queryForList(
                """
                select column_name
                from information_schema.columns
                where table_schema = 'public'
                  and table_name = 'worker'
                  and column_name in (
                    'credential_operation_id',
                    'credential_operation_type',
                    'credential_operation_started_at')
                  and is_nullable = 'YES'
                order by column_name
                """,
                String.class))
        .containsExactly(
            "credential_operation_id",
            "credential_operation_started_at",
            "credential_operation_type");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from flyway_schema_history where version='5' and success",
                Integer.class))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isZero();
  }

  private static String resource(String path) {
    try (InputStream input =
        TaskBoardReleaseSchemaValidationIntegrationTest.class.getResourceAsStream(path)) {
      if (input == null) {
        throw new IllegalStateException("Missing test resource: " + path);
      }
      return new String(input.readAllBytes(), StandardCharsets.UTF_8);
    } catch (IOException exception) {
      throw new IllegalStateException("Cannot read test resource: " + path, exception);
    }
  }
}
