package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialGateway;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/** Integration coverage for aggregate ordinary-board availability and SES route gating. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class OrdinaryQueueAvailabilityIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000611");

  @org.springframework.beans.factory.annotation.Autowired RegistryService registry;
  @org.springframework.beans.factory.annotation.Autowired TaskBoardService board;
  @org.springframework.beans.factory.annotation.Autowired WorkforceService workforce;
  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Autowired EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void aggregateBoardUsesPriorityWindowAndTakeRejectsOnlyTheNextWaitingCard() {
    WorkerClassDto workerClass = registry.createClass(workerClass("ordinary-window"));
    WorkQueueDto queue = queue("ordinary-window", QueueType.REPAIR, 2, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());

    BoardEntryDto excludedSeed =
        entry(create(queue.definitionId(), "priority-three", LocalDate.of(2026, 8, 19), 3),
            "priority-three");
    create(queue.definitionId(), "priority-one", LocalDate.of(2026, 8, 23), 1);
    create(queue.definitionId(), "priority-two", LocalDate.of(2026, 8, 20), 2);

    TaskBoardSnapshot snapshot = board.snapshot(WAREHOUSE_ID);
    BoardColumnDto column = column(snapshot, queue.id());
    assertThat(column.availableTaskLimit()).isEqualTo(2);
    assertThat(column.entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("priority-one", "priority-two");

    BoardEntryDto excluded = board.entry(WAREHOUSE_ID, excludedSeed.id());
    assertThatThrownBy(
            () ->
                board.take(
                    WAREHOUSE_ID,
                    excluded.id(),
                    new TakeEntryRequest(excluded.version(), group.id(), worker.id()),
                    null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("пределами доступных");

    BoardEntryDto secondAvailable = entry(snapshot, "priority-two");
    assertThat(
            board.take(
                    WAREHOUSE_ID,
                    secondAvailable.id(),
                    new TakeEntryRequest(
                        secondAvailable.version(), group.id(), worker.id()),
                    null)
                .status())
        .isEqualTo(EntryStatus.IN_PROGRESS);
  }

  @Test
  void holdingStepIsTheOnlyOrdinaryCardUntilItCompletesThenFirstRepairIsPromoted() {
    WorkerClassDto workerClass = registry.createClass(workerClass("ses-gate"));
    WorkQueueDto repair = queue("repair-after-ses", QueueType.REPAIR, 6, 1, workerClass.id());
    WorkQueueDto holding = queue("ses", QueueType.HOLDING, 6, 0, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());

    board.createTask(
        WAREHOUSE_ID,
        new CreateBoardTaskRequest(
            null,
            "ses-route",
            "CAB-SES",
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(repair.definitionId(), "Ремонт", null),
                new RouteStepRequest(holding.definitionId(), "Обработка СЭС", null)),
            LocalDate.of(2026, 8, 21),
            1));

    TaskBoardSnapshot actionable = board.snapshot(WAREHOUSE_ID);
    assertThat(actionable.columns().stream().flatMap(column -> column.entries().stream()))
        .singleElement()
        .satisfies(
            card -> {
              assertThat(card.queueId()).isEqualTo(holding.id());
              assertThat(card.entryType()).isEqualTo(EntryType.REAL);
            });
    assertThat(actionable.columns().stream()
            .filter(column -> column.queueId().equals(repair.id()))
            .flatMap(column -> column.entries().stream()))
        .isEmpty();
    BoardEntryDto holdingEntry = entry(actionable, "ses-route", holding.id());
    assertThat(holdingEntry.entryType()).isEqualTo(EntryType.REAL);

    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            holdingEntry.id(),
            new TakeEntryRequest(holdingEntry.version(), group.id(), worker.id()),
            null);
    board.complete(
        WAREHOUSE_ID, taken.id(), new VersionCommand(taken.version()), null);

    TaskBoardSnapshot afterHolding = board.snapshot(WAREHOUSE_ID);
    assertThat(afterHolding.columns().stream().flatMap(column -> column.entries().stream()))
        .singleElement()
        .satisfies(
            card -> {
              assertThat(card.queueId()).isEqualTo(repair.id());
              assertThat(card.entryType()).isEqualTo(EntryType.REAL);
              assertThat(card.status()).isEqualTo(EntryStatus.WAITING);
            });
  }

  @Test
  void ordinaryQueueKeepsAggregateInsertionOrderInsteadOfSortingByHistoricDate() {
    WorkerClassDto workerClass = registry.createClass(workerClass("aggregate-order"));
    WorkQueueDto queue = queue("aggregate-order", QueueType.REPAIR, 6, 0, workerClass.id());

    create(queue.definitionId(), "created-first", LocalDate.of(2026, 9, 10), 3);
    create(queue.definitionId(), "created-second", LocalDate.of(2026, 8, 10), 3);

    assertThat(column(board.snapshot(WAREHOUSE_ID), queue.id()).entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("created-first", "created-second");
  }

  @Test
  void actionableSnapshotBoundsEntityMaterializationAndBatchesAssignments() {
    WorkerClassDto workerClass = registry.createClass(workerClass("bounded-read"));
    WorkQueueDto queue = queue("bounded-read", QueueType.REPAIR, 6, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    seedWaitingBacklog(queue.id(), worker.id(), group.id(), 300, 6);

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    BoardColumnDto column = column(board.snapshot(WAREHOUSE_ID), queue.id());

    assertThat(column.entries())
        .hasSize(6)
        .extracting(BoardEntryDto::title)
        .containsExactly(
            "bounded-1",
            "bounded-2",
            "bounded-3",
            "bounded-4",
            "bounded-5",
            "bounded-6");
    assertThat(column.entries())
        .allSatisfy(card -> assertThat(card.assignments()).hasSize(1));
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(5);
    assertThat(statistics.getEntityLoadCount()).isEqualTo(22);
  }

  private WorkQueueDto queue(
      String name,
      QueueType type,
      int availableTaskLimit,
      int resultPhotoMinCount,
      UUID workerClassId) {
    QueueDefinitionDto definition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(
                0L,
                name,
                null,
                type,
                QueuePurpose.GENERAL,
                0,
                true,
                false,
                false,
                type == QueueType.HOLDING ? 10 : null,
                null,
                false,
                resultPhotoMinCount,
                availableTaskLimit,
                List.of(
                    new QueueBindingRequest(
                        workerClassId, 0, false, ParticipationPolicy.PRIMARY, false))));
    return QueueRegistryTestFixtures.create(
        registry,
        jdbc,
        WAREHOUSE_ID,
        new QueueFixtureModels.QueueFixtureRequest(
            0L,
            definition.id(),
            true,
            false,
            false,
            type == QueueType.HOLDING ? 10 : null,
            null,
            false,
            resultPhotoMinCount,
            List.of(
                new QueueBindingRequest(
                    workerClassId, 0, false, ParticipationPolicy.PRIMARY, false))));
  }

  private WorkerClassRequest workerClass(String name) {
    return new WorkerClassRequest(0L, name, null, null, 0, true);
  }

  private WorkerDto worker(UUID workerClassId) {
    return workforce.createWorker(
        WAREHOUSE_ID,
        new WorkerRequest(
            0L,
            "Исполнитель",
            null,
            null,
            null,
            true,
            null,
            null,
            null,
            List.of(new QualificationRequest(workerClassId, true, null))));
  }

  private WorkerGroupDto group(UUID workerClassId, UUID workerId) {
    return workforce.createGroup(
        WAREHOUSE_ID,
        new WorkerGroupRequest(
            0L,
            workerClassId,
            "Бригада",
            null,
            true,
            List.of(new GroupMemberRequest(workerId, true))));
  }

  private TaskBoardSnapshot create(
      UUID queueDefinitionId, String title, LocalDate date, int priority) {
    return board.createTask(
        WAREHOUSE_ID,
        new CreateBoardTaskRequest(
            null,
            title,
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queueDefinitionId, title, null)),
            date,
            priority));
  }

  private void seedWaitingBacklog(
      UUID queueId, UUID workerId, UUID workerGroupId, int taskCount, int assignedCount) {
    jdbc.update(
        """
        insert into board_task(
          id,version,warehouse_id,title,status,scheduled_date,task_lane,priority,pinned,
          completion_deadline_enforced)
        select md5('bounded-task-' || item)::uuid,
               0,
               ?,
               'bounded-' || item,
               'ACTIVE',
               date '2026-08-21' + item,
               'SCHEDULED',
               case when item <= ? then 1 else 5 end,
               false,
               false
          from generate_series(1, ?) item
        """,
        WAREHOUSE_ID,
        assignedCount,
        taskCount);
    jdbc.update(
        """
        insert into queue_entry(
          id,version,revision_marker,task_id,queue_id,route_index,queue_position,entry_type,status,
          worker_works,worker_materials,worker_comments,source_media_references,active_work_seconds)
        select md5('bounded-entry-' || item)::uuid,
               0,
               md5('bounded-revision-' || item)::uuid,
               md5('bounded-task-' || item)::uuid,
               ?,
               0,
               item,
               'REAL',
               'WAITING',
               '[]',
               '[]',
               '[]',
               '[]',
               0
          from generate_series(1, ?) item
        """,
        queueId,
        taskCount);
    jdbc.update(
        """
        insert into task_assignment(
          id,version,queue_entry_id,worker_group_id,worker_id,worker_name_snapshot,
          group_name_snapshot,status,assigned_at)
        select md5('bounded-assignment-' || item)::uuid,
               0,
               md5('bounded-entry-' || item)::uuid,
               ?,
               ?,
               'Исполнитель',
               'Бригада',
               'ACTIVE',
               '2026-08-21T08:00:00Z'
          from generate_series(1, ?) item
        """,
        workerGroupId,
        workerId,
        assignedCount);
  }

  private BoardColumnDto column(TaskBoardSnapshot snapshot, UUID queueId) {
    return snapshot.columns().stream()
        .filter(candidate -> candidate.queueId().equals(queueId))
        .findFirst()
        .orElseThrow();
  }

  private BoardEntryDto entry(TaskBoardSnapshot snapshot, String title) {
    return snapshot.columns().stream()
        .flatMap(column -> column.entries().stream())
        .filter(candidate -> candidate.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private BoardEntryDto entry(TaskBoardSnapshot snapshot, String title, UUID queueId) {
    return snapshot.columns().stream()
        .filter(column -> column.queueId().equals(queueId))
        .flatMap(column -> column.entries().stream())
        .filter(candidate -> candidate.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  /** Test-only credential boundary for workers created without authentication identities. */
  @TestConfiguration
  static class TestConfig {
    /** Supplies a no-op credential boundary because these tests create workers without logins. */
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
