package dev.buhanzaz.rwms.taskboard;
import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialGateway;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PostgresTaskBoardIntegrationTest extends PostgresIntegrationTestSupport {

  @org.springframework.beans.factory.annotation.Autowired RegistryService registry;
  @org.springframework.beans.factory.annotation.Autowired TaskBoardService board;
  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;

  @Test
  void jpaAndCoreBoardFlowWorkOnPostgres() {
    UUID warehouse = UUID.randomUUID();
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(0L, "Repair", null, QueueType.REPAIR));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            warehouse,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                null,
                List.of()));
    var snapshot =
        board.createTask(
            warehouse,
            new CreateBoardTaskRequest(
                null,
                "Postgres task",
                null,
                null,
                null,
                null,
                List.of(new RouteStepRequest(queue.definitionId(), null, null))));
    assertThat(snapshot.columns())
        .filteredOn(column -> queue.id().equals(column.queueId()))
        .flatExtracting(BoardColumnDto::entries)
        .extracting(BoardEntryDto::title)
        .containsExactly("Postgres task");
  }

  @Test
  void flywayVersionFourUsesReviewedConstraintAndIndexNames() {
    assertThat(
            jdbc.queryForList(
                "select conname from pg_constraint where connamespace = 'public'::regnamespace",
                String.class))
        .contains(
            "uk_board_task_external",
            "ck_board_task_request_fingerprint",
            "uk_queue_entry_route",
            "fk_queue_entry_task",
            "fk_work_queue_definition",
            "uk_work_queue_warehouse_definition",
            "uk_task_board_outbox_semantic",
            "ck_task_board_outbox_body",
            "ck_task_board_inbox_hash");
    assertThat(
            jdbc.queryForList(
                "select indexname from pg_indexes where schemaname='public'", String.class))
        .contains(
            "idx_board_task_warehouse_status",
            "idx_queue_entry_board",
            "idx_task_board_outbox_pending",
            "idx_task_board_outbox_expired_lease",
            "idx_task_board_outbox_aggregate_head");
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    WorkerCredentialGateway fakeCredentialGateway() {
      return new WorkerCredentialGateway() {
        public void configure(UUID workerId, UUID warehouseId, String appLogin, String password) {}

        public void reset(UUID workerId, String password) {}

        public void disable(UUID workerId) {}

        public void enable(UUID workerId) {}

        public void delete(UUID workerId) {}

        public WorkerCredentialSnapshot status(UUID workerId, UUID expectedWarehouseId) {
          return new WorkerCredentialSnapshot(
              workerId, expectedWarehouseId, null, WorkerCredentialStatus.ABSENT);
        }
      };
    }
  }
}
