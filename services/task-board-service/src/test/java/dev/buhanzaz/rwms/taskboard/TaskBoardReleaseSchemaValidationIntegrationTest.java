package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerRepository;
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

  @Autowired WorkQueueRepository queues;
  @Autowired WorkerRepository workers;
  @Autowired JdbcTemplate jdbc;

  @Test
  void flywayVersionTwentySchemaValidatesAndGlobalQueueBindingsAreReadableThroughJpa() {
    UUID warehouseOne = UUID.fromString("00000000-0000-0000-0000-000000000001");
    jdbc.update(
        """
        insert into worker(
          id,version,revision_marker,warehouse_id,display_name,active,credential_status)
        values (?,0,?,?,?,true,'NOT_CONFIGURED')
        """,
        UUID.randomUUID(), UUID.randomUUID(), warehouseOne, "Рабочий");
    UUID movementDefinition = UUID.randomUUID();
    UUID repairDefinition = UUID.randomUUID();
    jdbc.update(
        """
        insert into queue_definition(
          id,version,revision_marker,name,normalized_name,description,queue_type)
        values (?,0,?,'Перемещение','перемещение',null,'MOVEMENT'),
               (?,0,?,'Ремонт','ремонт',null,'REPAIR')
        """,
        movementDefinition,
        UUID.randomUUID(),
        repairDefinition,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into work_queue(
          id,version,revision_marker,warehouse_id,definition_id,sort_order,
          active,hidden,collapsed,holding_period_minutes,notification_threshold,
          notify_when_threshold_reached,result_photo_min_count)
        values (?,0,?,?,?,0,true,false,false,null,null,false,0),
               (?,0,?,?,?,1,true,false,false,null,null,false,0)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        warehouseOne,
        movementDefinition,
        UUID.randomUUID(),
        UUID.randomUUID(),
        warehouseOne,
        repairDefinition);

    assertThat(queues.findAllOrderedByWarehouseId(warehouseOne))
        .extracting(queue -> queue.getType())
        .containsExactly(QueueType.MOVEMENT, QueueType.REPAIR);
    assertThat(workers.findAllByWarehouseIdOrderByDisplayNameAsc(warehouseOne)).hasSize(1);
    assertThat(
            jdbc.queryForList(
                "select indexname from pg_indexes where schemaname='public'", String.class))
        .contains("idx_work_queue_order", "uk_worker_app_login_ci")
        .doesNotContain("uk_worker_class_code_ci", "uk_work_queue_code_ci");
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
                "select count(*) from flyway_schema_history where version='20' and success",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForList(
                """
                select table_name || '.' || column_name
                  from information_schema.columns
                 where table_schema='public'
                   and (table_name='worker_class' and column_name='code'
                     or table_name='work_queue' and column_name='code'
                     or table_name='queue_entry' and column_name='queue_code')
                """,
                String.class))
        .isEmpty();
  }
}
