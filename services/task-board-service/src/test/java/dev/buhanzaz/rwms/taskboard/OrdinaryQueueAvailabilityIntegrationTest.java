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
import dev.buhanzaz.rwms.taskboard.service.MobileTaskSurface;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.GlobalQueueProjectionService;
import dev.buhanzaz.rwms.taskboard.service.NotFoundException;
import dev.buhanzaz.rwms.taskboard.service.WorkerQueuePlanService;
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

/** Integration coverage for complete ordinary-board projection and canonical SES gating. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
class OrdinaryQueueAvailabilityIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000611");

  @org.springframework.beans.factory.annotation.Autowired RegistryService registry;
  @org.springframework.beans.factory.annotation.Autowired TaskBoardService board;
  @org.springframework.beans.factory.annotation.Autowired WorkforceService workforce;
  @org.springframework.beans.factory.annotation.Autowired WorkerQueuePlanService workerPlans;
  @org.springframework.beans.factory.annotation.Autowired
  GlobalQueueProjectionService globalQueueProjections;
  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;
  @org.springframework.beans.factory.annotation.Autowired EntityManagerFactory entityManagerFactory;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void aggregateBoardReturnsEveryRealCardAndDailyPlanCountDoesNotFenceTake() {
    WorkerClassDto workerClass = registry.createClass(workerClass("ordinary-window"));
    WorkQueueDto queue = queue("ordinary-window", QueueType.REPAIR, 2, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());

    BoardEntryDto laterSeed =
        entry(create(queue.definitionId(), "priority-three", LocalDate.of(2026, 8, 19), 3),
            "priority-three");
    create(queue.definitionId(), "priority-one", LocalDate.of(2026, 8, 23), 1);
    create(queue.definitionId(), "priority-two", LocalDate.of(2026, 8, 20), 2);

    TaskBoardSnapshot snapshot = board.snapshot(WAREHOUSE_ID);
    BoardColumnDto column = column(snapshot, queue.id());
    assertThat(column.availableTaskLimit()).isEqualTo(2);
    assertThat(column.entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("priority-one", "priority-two", "priority-three");

    BoardEntryDto later = board.entry(WAREHOUSE_ID, laterSeed.id());
    assertThat(
            board.take(
                    WAREHOUSE_ID,
                    later.id(),
                    new TakeEntryRequest(later.version(), group.id(), worker.id()),
                    null)
                .status())
        .isEqualTo(EntryStatus.IN_PROGRESS);
  }

  @Test
  void workerPlanBoundsFeedAndWorkerTakeWhileManagerBoardRemainsComplete() {
    WorkerClassDto workerClass = registry.createClass(workerClass("worker-plan-window"));
    WorkQueueDto queue = queue("worker-plan-window", QueueType.REPAIR, 2, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    workforce.setCurrentGroup(
        WAREHOUSE_ID,
        worker.id(),
        new SetCurrentGroupRequest(worker.version(), group.id()));

    create(queue.definitionId(), "plan-first", LocalDate.of(2026, 8, 21), 3);
    create(queue.definitionId(), "plan-second", LocalDate.of(2026, 8, 21), 3);
    BoardEntryDto third =
        entry(
            create(queue.definitionId(), "plan-third", LocalDate.of(2026, 8, 21), 3),
            "plan-third");

    assertThat(column(board.snapshot(WAREHOUSE_ID), queue.id()).entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("plan-first", "plan-second", "plan-third");
    assertThat(column(board.workerSnapshot(WAREHOUSE_ID, worker.id()), queue.id()).entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("plan-first", "plan-second");
    assertThatThrownBy(
            () ->
                board.takeFromMobile(
                    MobileTaskSurface.WORKER,
                    WAREHOUSE_ID,
                    third.id(),
                    new TakeEntryRequest(third.version(), group.id(), worker.id()),
                    worker.id()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("план WorkerApp");

    WorkQueueDto expanded =
        workerPlans.update(
            WAREHOUSE_ID,
            queue.id(),
            new WorkerQueuePlanRequest(queue.version(), true, 3));
    assertThat(expanded.availableTaskLimit()).isEqualTo(3);
    assertThat(column(board.workerSnapshot(WAREHOUSE_ID, worker.id()), queue.id()).entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("plan-first", "plan-second", "plan-third");
    assertThat(
            board.takeFromMobile(
                    MobileTaskSurface.WORKER,
                    WAREHOUSE_ID,
                    third.id(),
                    new TakeEntryRequest(third.version(), group.id(), worker.id()),
                    worker.id())
                .status())
        .isEqualTo(EntryStatus.IN_PROGRESS);
  }

  @Test
  void warehouseSwitchHidesTheWholeQueueIncludingActiveWork() {
    WorkerClassDto workerClass = registry.createClass(workerClass("worker-plan-switch"));
    WorkQueueDto queue = queue("worker-plan-switch", QueueType.REPAIR, 1, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    workforce.setCurrentGroup(
        WAREHOUSE_ID,
        worker.id(),
        new SetCurrentGroupRequest(worker.version(), group.id()));
    BoardEntryDto entry =
        entry(
            create(queue.definitionId(), "switch-task", LocalDate.of(2026, 8, 21), 3),
            "switch-task");

    WorkQueueDto disabled =
        workerPlans.update(
            WAREHOUSE_ID,
            queue.id(),
            new WorkerQueuePlanRequest(queue.version(), false, 1));
    assertThat(disabled.workerFeedEnabled()).isFalse();
    assertThat(board.snapshot(WAREHOUSE_ID).columns())
        .extracting(BoardColumnDto::queueId)
        .contains(queue.id());
    assertThat(board.workerSnapshot(WAREHOUSE_ID, worker.id()).columns())
        .extracting(BoardColumnDto::queueId)
        .doesNotContain(queue.id());
    assertThatThrownBy(() -> board.workerEntry(WAREHOUSE_ID, entry.id(), worker.id()))
        .isInstanceOf(NotFoundException.class);

    WorkQueueDto enabled =
        workerPlans.update(
            WAREHOUSE_ID,
            queue.id(),
            new WorkerQueuePlanRequest(disabled.version(), true, 1));
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            entry.id(),
            new TakeEntryRequest(entry.version(), group.id(), worker.id()),
            worker.id());
    WorkQueueDto disabledActive =
        workerPlans.update(
            WAREHOUSE_ID,
            queue.id(),
            new WorkerQueuePlanRequest(enabled.version(), false, 1));
    assertThat(disabledActive.workerFeedEnabled()).isFalse();
    assertThat(board.workerSnapshot(WAREHOUSE_ID, worker.id()).columns())
        .extracting(BoardColumnDto::queueId)
        .doesNotContain(queue.id());
    assertThatThrownBy(() -> board.workerEntry(WAREHOUSE_ID, taken.id(), worker.id()))
        .isInstanceOf(NotFoundException.class);
    assertThat(column(board.snapshot(WAREHOUSE_ID), queue.id()).entries())
        .extracting(BoardEntryDto::id, BoardEntryDto::status)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(taken.id(), EntryStatus.IN_PROGRESS));
  }

  @Test
  void globalSynchronizationPreservesWarehouseLocalWorkerPlan() {
    WorkerClassDto workerClass = registry.createClass(workerClass("local-plan-sync"));
    WorkQueueDto queue = queue("local-plan-sync", QueueType.REPAIR, 6, 1, workerClass.id());

    WorkQueueDto local =
        workerPlans.update(
            WAREHOUSE_ID,
            queue.id(),
            new WorkerQueuePlanRequest(queue.version(), false, 3));
    globalQueueProjections.synchronizeWarehouse(WAREHOUSE_ID);

    WorkQueueDto afterSync = registry.dto(registry.requireQueue(WAREHOUSE_ID, queue.id()));
    assertThat(afterSync.version()).isEqualTo(local.version());
    assertThat(afterSync.workerFeedEnabled()).isFalse();
    assertThat(afterSync.availableTaskLimit()).isEqualTo(3);
  }

  @Test
  void managerReordersOnlyUnpinnedWaitingRealCardsInsideTheirQueue() {
    WorkerClassDto workerClass = registry.createClass(workerClass("manual-order"));
    WorkQueueDto queue = queue("manual-order", QueueType.REPAIR, 2, 1, workerClass.id());
    create(queue.definitionId(), "order-a", LocalDate.of(2026, 8, 21), 3);
    create(queue.definitionId(), "order-b", LocalDate.of(2026, 8, 21), 3);
    create(queue.definitionId(), "order-c", LocalDate.of(2026, 8, 21), 3);

    BoardColumnDto before = column(board.snapshot(WAREHOUSE_ID), queue.id());
    BoardEntryDto moved = entry(board.snapshot(WAREHOUSE_ID), "order-c");
    TaskBoardSnapshot reordered =
        board.reorder(
            WAREHOUSE_ID,
            moved.id(),
            new ReorderBoardEntryRequest(
                moved.version(), before.queueVersion(), before.entries().getFirst().id(), 0));
    BoardColumnDto after = column(reordered, queue.id());
    assertThat(after.entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("order-c", "order-a", "order-b");
    assertThat(after.queueVersion()).isGreaterThan(before.queueVersion());

    BoardEntryDto orderB = entry(after.entries(), "order-b");
    assertThatThrownBy(
            () ->
                board.reorder(
                    WAREHOUSE_ID,
                    orderB.id(),
                    new ReorderBoardEntryRequest(
                        orderB.version(), after.queueVersion(), UUID.randomUUID(), 0)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Очередь изменилась");
    assertThatThrownBy(
            () ->
                board.reorder(
                    WAREHOUSE_ID,
                    orderB.id(),
                    new ReorderBoardEntryRequest(
                        orderB.version(),
                        before.queueVersion(),
                        after.entries().getFirst().id(),
                        0)))
        .isInstanceOf(ConflictException.class);

    BoardEntryDto orderA = entry(reordered, "order-a");
    TaskBoardSnapshot pinned =
        board.pin(
            WAREHOUSE_ID,
            orderA.taskId(),
            new PinTaskRequest(orderA.taskVersion(), true));
    BoardEntryDto pinnedA = entry(pinned, "order-a");
    assertThatThrownBy(
            () ->
                board.reorder(
                    WAREHOUSE_ID,
                    pinnedA.id(),
                    new ReorderBoardEntryRequest(
                        pinnedA.version(),
                        column(pinned, queue.id()).queueVersion(),
                        pinnedA.id(),
                        0)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("незакреплённый");
  }

  @Test
  void managerCanInspectSesFuturePathWhileWorkerSeesOnlyTheGateUntilCompletion() {
    WorkerClassDto workerClass = registry.createClass(workerClass("ses-gate"));
    WorkQueueDto repair = queue("repair-after-ses", QueueType.REPAIR, 6, 1, workerClass.id());
    WorkQueueDto ses = queue("сэс и санитария", QueueType.REPAIR, 6, 0, workerClass.id());
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
                new RouteStepRequest(ses.definitionId(), "Обработка СЭС", null),
                new RouteStepRequest(repair.definitionId(), "Ремонт", null)),
            LocalDate.of(2026, 8, 21),
            1));

    TaskBoardSnapshot managerBoard = board.snapshot(WAREHOUSE_ID);
    assertThat(managerBoard.columns().stream().flatMap(column -> column.entries().stream()))
        .extracting(BoardEntryDto::queueId, BoardEntryDto::entryType)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(ses.id(), EntryType.REAL),
            org.assertj.core.groups.Tuple.tuple(repair.id(), EntryType.SHADOW));

    TaskBoardSnapshot workerBoard = board.workerSnapshot(WAREHOUSE_ID, worker.id());
    assertThat(workerBoard.columns().stream().flatMap(column -> column.entries().stream()))
        .singleElement()
        .satisfies(
            card -> {
              assertThat(card.queueId()).isEqualTo(ses.id());
              assertThat(card.entryType()).isEqualTo(EntryType.REAL);
            });
    BoardEntryDto sesEntry = entry(managerBoard, "ses-route", ses.id());
    assertThat(sesEntry.entryType()).isEqualTo(EntryType.REAL);

    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            sesEntry.id(),
            new TakeEntryRequest(sesEntry.version(), group.id(), worker.id()),
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
  void realStageSkipsEarlierShadowsUntilTheEarlierRouteStageIsPromoted() {
    WorkerClassDto workerClass = registry.createClass(workerClass("real-before-shadow"));
    WorkQueueDto exterior = queue("exterior", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto electricity =
        queue("electricity", QueueType.REPAIR, 6, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());

    board.createTask(
        WAREHOUSE_ID,
        new CreateBoardTaskRequest(
            null,
            "route-first",
            "CAB-ROUTE",
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(exterior.definitionId(), "Внешние работы", null),
                new RouteStepRequest(electricity.definitionId(), "Электрика", null)),
            LocalDate.of(2026, 8, 21),
            3));
    create(
        electricity.definitionId(),
        "electricity-only",
        LocalDate.of(2026, 8, 21),
        3);

    TaskBoardSnapshot beforePromotion = board.snapshot(WAREHOUSE_ID);
    assertThat(column(beforePromotion, electricity.id()).entries())
        .extracting(BoardEntryDto::title, BoardEntryDto::entryType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("electricity-only", EntryType.REAL),
            org.assertj.core.groups.Tuple.tuple("route-first", EntryType.SHADOW));
    BoardEntryDto futureElectricity = entry(beforePromotion, "route-first", electricity.id());
    assertThatThrownBy(
            () ->
                board.take(
                    WAREHOUSE_ID,
                    futureElectricity.id(),
                    new TakeEntryRequest(futureElectricity.version(), group.id(), worker.id()),
                    null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Взять можно");

    BoardEntryDto exteriorEntry = entry(beforePromotion, "route-first", exterior.id());
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            exteriorEntry.id(),
            new TakeEntryRequest(exteriorEntry.version(), group.id(), worker.id()),
            null);
    board.complete(
        WAREHOUSE_ID, taken.id(), new VersionCommand(taken.version()), null);

    assertThat(column(board.snapshot(WAREHOUSE_ID), electricity.id()).entries())
        .extracting(BoardEntryDto::title, BoardEntryDto::entryType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("route-first", EntryType.REAL),
            org.assertj.core.groups.Tuple.tuple("electricity-only", EntryType.REAL));
  }

  @Test
  void pinnedRealStageStaysAheadWhenAnEarlierShadowIsPromoted() {
    WorkerClassDto workerClass = registry.createClass(workerClass("pinned-before-promotion"));
    WorkQueueDto exterior =
        queue("pinned-exterior", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto electricity =
        queue("pinned-electricity", QueueType.REPAIR, 6, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());

    board.createTask(
        WAREHOUSE_ID,
        new CreateBoardTaskRequest(
            null,
            "pinned-route-first",
            "CAB-PIN-ROUTE",
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(exterior.definitionId(), "Внешние работы", null),
                new RouteStepRequest(electricity.definitionId(), "Электрика", null)),
            LocalDate.of(2026, 8, 21),
            3));
    create(
        electricity.definitionId(),
        "pinned-electricity-only",
        LocalDate.of(2026, 8, 21),
        3);

    TaskBoardSnapshot initial = board.snapshot(WAREHOUSE_ID);
    BoardEntryDto electricityOnly =
        entry(initial, "pinned-electricity-only", electricity.id());
    board.pin(
        WAREHOUSE_ID,
        electricityOnly.taskId(),
        new PinTaskRequest(electricityOnly.taskVersion(), true));

    BoardEntryDto exteriorEntry = entry(initial, "pinned-route-first", exterior.id());
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            exteriorEntry.id(),
            new TakeEntryRequest(exteriorEntry.version(), group.id(), worker.id()),
            null);
    board.complete(
        WAREHOUSE_ID, taken.id(), new VersionCommand(taken.version()), null);

    assertThat(column(board.snapshot(WAREHOUSE_ID), electricity.id()).entries())
        .extracting(BoardEntryDto::title, BoardEntryDto::entryType, BoardEntryDto::pinned)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "pinned-electricity-only", EntryType.REAL, true),
            org.assertj.core.groups.Tuple.tuple(
                "pinned-route-first", EntryType.REAL, false));
  }

  @Test
  void completeBoardKeepsEveryRealAndFutureShadowRegardlessOfDailyPlanCount() {
    WorkerClassDto workerClass = registry.createClass(workerClass("complete-shadow-route"));
    WorkQueueDto exterior =
        queue("limited-exterior", QueueType.REPAIR, 1, 0, workerClass.id());
    WorkQueueDto electricity =
        queue("complete-electricity", QueueType.REPAIR, 1, 0, workerClass.id());

    for (String title : List.of("route-one", "route-two")) {
      board.createTask(
          WAREHOUSE_ID,
          new CreateBoardTaskRequest(
              null,
              title,
              "CAB-" + title,
              null,
              null,
              null,
              List.of(
                  new RouteStepRequest(exterior.definitionId(), "Внешние работы", null),
                  new RouteStepRequest(electricity.definitionId(), "Электрика", null)),
              LocalDate.of(2026, 8, 21),
              3));
    }
    create(
        electricity.definitionId(),
        "electricity-only-visible-real",
        LocalDate.of(2026, 8, 21),
        3);

    assertThat(column(board.snapshot(WAREHOUSE_ID), exterior.id()).entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("route-one", "route-two");
    assertThat(column(board.snapshot(WAREHOUSE_ID), electricity.id()).entries())
        .extracting(BoardEntryDto::title, BoardEntryDto::entryType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "electricity-only-visible-real", EntryType.REAL),
            org.assertj.core.groups.Tuple.tuple("route-one", EntryType.SHADOW),
            org.assertj.core.groups.Tuple.tuple("route-two", EntryType.SHADOW));
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
  void completeSnapshotBatchesAllBacklogAssignmentsWithoutNPlusOneQueries() {
    WorkerClassDto workerClass = registry.createClass(workerClass("bounded-read"));
    WorkQueueDto queue = queue("bounded-read", QueueType.REPAIR, 6, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    seedWaitingBacklog(queue.id(), worker.id(), group.id(), 300, 6);

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    BoardColumnDto column = column(board.snapshot(WAREHOUSE_ID), queue.id());

    assertThat(column.entries()).hasSize(300);
    assertThat(column.entries().subList(0, 6))
        .extracting(BoardEntryDto::title)
        .containsExactly(
            "bounded-1",
            "bounded-2",
            "bounded-3",
            "bounded-4",
            "bounded-5",
            "bounded-6");
    assertThat(column.entries().get(column.entries().size() - 1).title())
        .isEqualTo("bounded-300");
    assertThat(column.entries())
        .allSatisfy(card -> assertThat(card.assignments()).hasSize(1));
    assertThat(statistics.getPrepareStatementCount()).isEqualTo(4);
    assertThat(statistics.getEntityLoadCount()).isLessThan(910);
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
      UUID queueId, UUID workerId, UUID workerGroupId, int taskCount, int priorityOneCount) {
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
        priorityOneCount,
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
        taskCount);
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

  private BoardEntryDto entry(List<BoardEntryDto> entries, String title) {
    return entries.stream()
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
