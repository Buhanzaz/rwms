package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.GlobalQueueProjectionService;
import dev.buhanzaz.rwms.taskboard.service.MobileTaskSurface;
import dev.buhanzaz.rwms.taskboard.service.NotFoundException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkerCredentialGateway;
import dev.buhanzaz.rwms.taskboard.service.WorkerInvalidationHub;
import dev.buhanzaz.rwms.taskboard.service.WorkerQueuePlanService;
import dev.buhanzaz.rwms.taskboard.service.WorkerTaskBoardService;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.support.TransactionTemplate;

/** Integration coverage for complete ordinary-board projection and canonical SES gating. */
@SpringBootTest(properties = "spring.jpa.properties.hibernate.generate_statistics=true")
@ActiveProfiles("test")
@AutoConfigureMockMvc
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
  @org.springframework.beans.factory.annotation.Autowired WorkerTaskBoardService workerTasks;
  @org.springframework.beans.factory.annotation.Autowired TransactionTemplate transactions;
  @MockitoSpyBean WorkerInvalidationHub workerInvalidations;
  @org.springframework.beans.factory.annotation.Autowired
  dev.buhanzaz.rwms.taskboard.eventing.WorkerFeedRevisionStore feedRevisions;
  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    clearInvocations(workerInvalidations);
  }

  @Test
  void managerSuspensionReleasesWorkersPreservesTimeAndRestoresTheSameTask() {
    WorkerClassDto workerClass = registry.createClass(workerClass("suspension"));
    WorkQueueDto queue = queue("suspension", QueueType.REPAIR, 1, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    workforce.setCurrentGroup(WAREHOUSE_ID, worker.id(),
        new SetCurrentGroupRequest(worker.version(), group.id()));
    BoardEntryDto first = entry(create(queue.definitionId(), "suspended-first", LocalDate.now(), 3),
        "suspended-first");
    BoardEntryDto second = entry(create(queue.definitionId(), "available-next", LocalDate.now(), 3),
        "available-next");
    BoardEntryDto taken = board.takeFromMobile(MobileTaskSurface.WORKER, WAREHOUSE_ID, first.id(),
        new TakeEntryRequest(first.version(), group.id(), worker.id()), worker.id());
    jdbc.update("update queue_entry set active_work_seconds=120, original_budget_seconds=600, "
        + "current_budget_seconds=600 where id=?", first.id());
    long beforeSuspend = feedRevisions.current(WAREHOUSE_ID);
    clearInvocations(workerInvalidations);
    transactions.executeWithoutResult(transaction -> {
      board.suspendTask(WAREHOUSE_ID, taken.taskId(), new TaskSuspensionRequest(taken.taskVersion()));
      verify(workerInvalidations, never()).feedChanged(eq(WAREHOUSE_ID), anyLong());
      transaction.setRollbackOnly();
    });
    verify(workerInvalidations, never()).feedChanged(eq(WAREHOUSE_ID), anyLong());
    assertThat(feedRevisions.current(WAREHOUSE_ID)).isEqualTo(beforeSuspend);
    assertThat(board.entry(WAREHOUSE_ID, first.id()).suspended()).isFalse();
    BoardEntryDto suspended = entry(board.suspendTask(WAREHOUSE_ID, taken.taskId(),
        new TaskSuspensionRequest(taken.taskVersion())), "suspended-first");
    long afterSuspend = feedRevisions.current(WAREHOUSE_ID);
    assertThat(afterSuspend).isGreaterThan(beforeSuspend);
    verify(workerInvalidations).feedChanged(WAREHOUSE_ID, afterSuspend);
    assertThat(suspended.suspended()).isTrue();
    assertThat(suspended.taskStatus()).isEqualTo(TaskStatus.ACTIVE);
    assertThat(suspended.status()).isEqualTo(EntryStatus.WAITING);
    assertThat(suspended.activeStartedAt()).isNull();
    assertThat(suspended.activeWorkSeconds()).isZero();
    assertThat(jdbc.queryForObject("select current_budget_seconds from queue_entry where id=?",
        Long.class, first.id())).isBetween(470L, 480L);
    assertThat(board.history(WAREHOUSE_ID, first.id())).hasSize(2);
    assertThat(jdbc.queryForObject(
        "select count(*) from task_assignment where queue_entry_id=? and status in ('ACTIVE','PAUSED')",
        Integer.class, first.id())).isZero();
    assertThat(column(board.workerSnapshot(WAREHOUSE_ID, worker.id()), queue.id()).entries())
        .extracting(BoardEntryDto::id).containsExactly(second.id());
    assertThatThrownBy(() -> board.take(WAREHOUSE_ID, first.id(),
        new TakeEntryRequest(suspended.version(), group.id(), worker.id()), null))
        .isInstanceOf(ConflictException.class).hasMessageContaining("приостановлена");
    assertThatThrownBy(() -> board.complete(WAREHOUSE_ID, first.id(),
        new VersionCommand(suspended.version()), worker.id()))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> board.restoreTask(WAREHOUSE_ID, first.taskId(),
        new TaskSuspensionRequest(taken.taskVersion())))
        .isInstanceOf(ConflictException.class);
    verify(workerInvalidations, times(1)).feedChanged(eq(WAREHOUSE_ID), anyLong());
    assertThat(board.takeFromMobile(MobileTaskSurface.WORKER, WAREHOUSE_ID, second.id(),
        new TakeEntryRequest(second.version(), group.id(), worker.id()), worker.id()).status())
        .isEqualTo(EntryStatus.IN_PROGRESS);
    clearInvocations(workerInvalidations);
    long beforeRestore = feedRevisions.current(WAREHOUSE_ID);
    BoardEntryDto restored = entry(board.restoreTask(WAREHOUSE_ID, first.taskId(),
        new TaskSuspensionRequest(suspended.taskVersion())), "suspended-first");
    assertThat(feedRevisions.current(WAREHOUSE_ID)).isGreaterThan(beforeRestore);
    verify(workerInvalidations).feedChanged(WAREHOUSE_ID, feedRevisions.current(WAREHOUSE_ID));
    assertThat(restored.suspended()).isFalse();
    assertThat(restored.id()).isEqualTo(first.id());
    assertThat(restored.taskId()).isEqualTo(first.taskId());
    assertThat(restored.activeWorkSeconds()).isEqualTo(suspended.activeWorkSeconds());
    assertThat(restored.assignments()).allSatisfy(assignment ->
        assertThat(assignment.status()).isEqualTo(dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus.CANCELLED));
    assertThat(column(board.workerSnapshot(WAREHOUSE_ID, worker.id()), queue.id()).entries())
        .extracting(BoardEntryDto::id).contains(first.id(), second.id());
    assertThat(jdbc.queryForList(
        "select event_type from domain_event where aggregate_id in (?,?)", String.class,
        first.id().toString(), first.taskId().toString()))
        .doesNotContain("task-board.board-task.cancelled.v1", "task-board.queue-entry.cancelled.v1");
  }

  @Test
  void suspensionCoversTheWholeRouteAndResumesOtherAutoInterruptedWork() {
    WorkerClassDto workerClass = registry.createClass(workerClass("suspension-interruption"));
    WorkQueueDto normal = queue("normal", QueueType.REPAIR, 2, 0, workerClass.id());
    WorkQueueDto urgent = queue("urgent", QueueType.REPAIR, 2, 0, workerClass.id());
    WorkQueueDto later = queue("later", QueueType.REPAIR, 2, 0, workerClass.id());
    jdbc.update("update work_queue_class_binding set stop_task_on_take=true where queue_id=?", urgent.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    BoardEntryDto normalEntry = entry(create(normal.definitionId(), "interrupted", LocalDate.now(), 3),
        "interrupted");
    board.take(WAREHOUSE_ID, normalEntry.id(),
        new TakeEntryRequest(normalEntry.version(), group.id(), worker.id()), null);
    TaskBoardSnapshot route = parallelRoute("interrupting", urgent, later);
    BoardEntryDto urgentEntry = entry(column(route, urgent.id()).entries(), "interrupting");
    BoardEntryDto laterEntry = entry(column(route, later.id()).entries(), "interrupting");
    BoardEntryDto taken = board.take(WAREHOUSE_ID, urgentEntry.id(),
        new TakeEntryRequest(urgentEntry.version(), group.id(), worker.id()), null);
    assertThat(board.entry(WAREHOUSE_ID, normalEntry.id()).status()).isEqualTo(EntryStatus.PAUSED);
    board.suspendTask(WAREHOUSE_ID, taken.taskId(), new TaskSuspensionRequest(taken.taskVersion()));
    assertThat(board.entry(WAREHOUSE_ID, normalEntry.id()).status()).isEqualTo(EntryStatus.IN_PROGRESS);
    assertThat(board.entry(WAREHOUSE_ID, urgentEntry.id()).suspended()).isTrue();
    assertThat(board.entry(WAREHOUSE_ID, laterEntry.id()).suspended()).isTrue();
    assertThat(jdbc.queryForObject("select count(*) from task_auto_interruption where active=true",
        Integer.class)).isZero();
  }

  @Test
  void suspensionAndRestoreArePanelOnlyAndVersionFenced() throws Exception {
    WorkerClassDto workerClass = registry.createClass(workerClass("suspension-auth"));
    WorkQueueDto queue = queue("suspension-auth", QueueType.REPAIR, 1, 1, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    BoardEntryDto first = entry(create(queue.definitionId(), "auth-first", LocalDate.now(), 3), "auth-first");
    BoardEntryDto taken = board.take(WAREHOUSE_ID, first.id(),
        new TakeEntryRequest(first.version(), group.id(), worker.id()), null);
    for (String action : List.of("suspend", "restore")) {
      mockMvc.perform(post("/api/warehouses/{warehouseId}/task-board/tasks/{taskId}/" + action,
              WAREHOUSE_ID, first.taskId())
          .with(jwt().jwt(token -> token.claim("principal_type", "WORKER")
              .claim("worker_id", worker.id().toString()).claim("scope", "rwms.write")))
          .contentType(MediaType.APPLICATION_JSON)
          .content("{\"expectedTaskVersion\":" + taken.taskVersion() + "}"))
          .andExpect(status().isForbidden());
    }
    var admin = jwt().jwt(token -> token.claim("principal_type", "USER")
        .claim("global_role", "SYSTEM_ADMIN").claim("scope", "rwms.write"));
    String path = "/api/warehouses/{warehouseId}/task-board/tasks/{taskId}/suspend";
    mockMvc.perform(post(path, WAREHOUSE_ID, first.taskId()).with(admin)
        .contentType(MediaType.APPLICATION_JSON).content("{}"))
        .andExpect(status().isBadRequest());
    mockMvc.perform(post(path, WAREHOUSE_ID, first.taskId()).with(admin)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedTaskVersion\":" + taken.taskVersion() + "}"))
        .andExpect(status().isOk());
    mockMvc.perform(post(path, WAREHOUSE_ID, first.taskId()).with(admin)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"expectedTaskVersion\":" + taken.taskVersion() + "}"))
        .andExpect(status().isConflict());
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
  void managerCanExposeAndHideFutureElectricalWorkBeforeWorkerTakesItInParallel() {
    WorkerClassDto exteriorClass = registry.createClass(workerClass("parallel-exterior"));
    WorkerClassDto electricalClass = registry.createClass(workerClass("parallel-electrical"));
    WorkQueueDto exterior =
        queue("внешние работы", QueueType.REPAIR, 6, 0, exteriorClass.id());
    WorkQueueDto electrical =
        queue("электрика", QueueType.REPAIR, 6, 0, electricalClass.id());
    WorkerDto exteriorWorker = worker(exteriorClass.id(), "Маляр");
    WorkerDto electrician = worker(electricalClass.id(), "Электрик");
    WorkerGroupDto exteriorGroup =
        group(exteriorClass.id(), exteriorWorker.id(), "Бригада внешних работ");
    WorkerGroupDto electricalGroup =
        group(electricalClass.id(), electrician.id(), "Бригада электриков");
    exteriorWorker =
        workforce.setCurrentGroup(
            WAREHOUSE_ID,
            exteriorWorker.id(),
            new SetCurrentGroupRequest(exteriorWorker.version(), exteriorGroup.id()));
    electrician =
        workforce.setCurrentGroup(
            WAREHOUSE_ID,
            electrician.id(),
            new SetCurrentGroupRequest(electrician.version(), electricalGroup.id()));

    TaskBoardSnapshot created = parallelRoute("parallel-route", exterior, electrical);
    BoardEntryDto exteriorEntry = entry(created, "parallel-route", exterior.id());
    BoardEntryDto futureElectrical = entry(created, "parallel-route", electrical.id());
    assertThat(futureElectrical.entryType()).isEqualTo(EntryType.SHADOW);
    clearInvocations(workerInvalidations);

    TaskBoardSnapshot exposed =
        board.setFutureTaskEntryAvailability(
            WAREHOUSE_ID,
            futureElectrical.id(),
            new SetFutureTaskEntryAvailabilityRequest(futureElectrical.version(), true));
    BoardEntryDto exposedElectrical = entry(exposed, "parallel-route", electrical.id());
    assertThat(exposedElectrical.entryType()).isEqualTo(EntryType.REAL);
    assertThat(exposedElectrical.version()).isGreaterThan(futureElectrical.version());
    assertThat(workerFeedEntryIds(electrician.id())).contains(exposedElectrical.id());
    assertThat(ownerProofReaderWorkerIds(exposedElectrical.id())).contains(electrician.id());
    verify(workerInvalidations, times(1)).feedChanged(eq(WAREHOUSE_ID), anyLong());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='QUEUE_ENTRY' "
                    + "and aggregate_id=? and event_type='task-board.queue-entry.changed.v1'",
                Integer.class,
                exposedElectrical.id().toString()))
        .isEqualTo(1);

    clearInvocations(workerInvalidations);
    TaskBoardSnapshot hidden =
        board.setFutureTaskEntryAvailability(
            WAREHOUSE_ID,
            exposedElectrical.id(),
            new SetFutureTaskEntryAvailabilityRequest(exposedElectrical.version(), false));
    BoardEntryDto hiddenElectrical = entry(hidden, "parallel-route", electrical.id());
    assertThat(hiddenElectrical.entryType()).isEqualTo(EntryType.SHADOW);
    assertThat(workerFeedEntryIds(electrician.id())).doesNotContain(hiddenElectrical.id());
    assertThat(ownerProofReaderWorkerIds(hiddenElectrical.id())).doesNotContain(electrician.id());
    verify(workerInvalidations, times(1)).feedChanged(eq(WAREHOUSE_ID), anyLong());

    clearInvocations(workerInvalidations);
    TaskBoardSnapshot hiddenReplay =
        board.setFutureTaskEntryAvailability(
            WAREHOUSE_ID,
            hiddenElectrical.id(),
            new SetFutureTaskEntryAvailabilityRequest(hiddenElectrical.version(), false));
    BoardEntryDto replayedElectrical = entry(hiddenReplay, "parallel-route", electrical.id());
    assertThat(replayedElectrical.version()).isEqualTo(hiddenElectrical.version());
    verify(workerInvalidations, never()).feedChanged(eq(WAREHOUSE_ID), anyLong());

    TaskBoardSnapshot exposedAgain =
        board.setFutureTaskEntryAvailability(
            WAREHOUSE_ID,
            replayedElectrical.id(),
            new SetFutureTaskEntryAvailabilityRequest(replayedElectrical.version(), true));
    BoardEntryDto electricalToTake = entry(exposedAgain, "parallel-route", electrical.id());
    BoardEntryDto exteriorInProgress =
        board.takeFromMobile(
            MobileTaskSurface.WORKER,
            WAREHOUSE_ID,
            exteriorEntry.id(),
            new TakeEntryRequest(exteriorEntry.version(), exteriorGroup.id(), exteriorWorker.id()),
            exteriorWorker.id());
    BoardEntryDto electricalInProgress =
        board.takeFromMobile(
            MobileTaskSurface.WORKER,
            WAREHOUSE_ID,
            electricalToTake.id(),
            new TakeEntryRequest(
                electricalToTake.version(), electricalGroup.id(), electrician.id()),
            electrician.id());

    assertThat(exteriorInProgress.status()).isEqualTo(EntryStatus.IN_PROGRESS);
    assertThat(electricalInProgress.status()).isEqualTo(EntryStatus.IN_PROGRESS);
    BoardEntryDto electricalDone =
        board.complete(
            WAREHOUSE_ID,
            electricalInProgress.id(),
            new VersionCommand(electricalInProgress.version()),
            electrician.id());
    BoardEntryDto exteriorDone =
        board.complete(
            WAREHOUSE_ID,
            exteriorInProgress.id(),
            new VersionCommand(exteriorInProgress.version()),
            exteriorWorker.id());
    assertThat(electricalDone.status()).isEqualTo(EntryStatus.DONE);
    assertThat(exteriorDone.taskStatus()).isEqualTo(TaskStatus.DONE);
  }

  @Test
  void parallelFutureStagesCanAlsoFinishAfterTheEarlierStage() {
    WorkerClassDto workerClass = registry.createClass(workerClass("parallel-completion-order"));
    WorkQueueDto first = queue("parallel-first", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto second = queue("parallel-second", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkerDto firstWorker = worker(workerClass.id(), "Первый исполнитель");
    WorkerDto secondWorker = worker(workerClass.id(), "Второй исполнитель");
    WorkerGroupDto firstGroup =
        group(workerClass.id(), firstWorker.id(), "Первая бригада");
    WorkerGroupDto secondGroup =
        group(workerClass.id(), secondWorker.id(), "Вторая бригада");

    TaskBoardSnapshot created = parallelRoute("parallel-earlier-first", first, second);
    BoardEntryDto firstEntry = entry(created, "parallel-earlier-first", first.id());
    BoardEntryDto futureEntry = entry(created, "parallel-earlier-first", second.id());
    BoardEntryDto exposed =
        entry(
            board.setFutureTaskEntryAvailability(
                WAREHOUSE_ID,
                futureEntry.id(),
                new SetFutureTaskEntryAvailabilityRequest(futureEntry.version(), true)),
            "parallel-earlier-first",
            second.id());
    BoardEntryDto firstActive =
        board.take(
            WAREHOUSE_ID,
            firstEntry.id(),
            new TakeEntryRequest(firstEntry.version(), firstGroup.id(), firstWorker.id()),
            null);
    BoardEntryDto secondActive =
        board.take(
            WAREHOUSE_ID,
            exposed.id(),
            new TakeEntryRequest(exposed.version(), secondGroup.id(), secondWorker.id()),
            null);

    BoardEntryDto firstDone =
        board.complete(
            WAREHOUSE_ID, firstActive.id(), new VersionCommand(firstActive.version()), null);
    assertThat(firstDone.taskStatus()).isEqualTo(TaskStatus.ACTIVE);
    BoardEntryDto refreshedSecond = board.entry(WAREHOUSE_ID, secondActive.id());
    BoardEntryDto secondDone =
        board.complete(
            WAREHOUSE_ID,
            refreshedSecond.id(),
            new VersionCommand(refreshedSecond.version()),
            null);
    assertThat(secondDone.taskStatus()).isEqualTo(TaskStatus.DONE);
  }

  @Test
  void futureAvailabilityRejectsStaleCurrentActiveAndSesBlockedEntries() {
    WorkerClassDto workerClass = registry.createClass(workerClass("future-guards"));
    WorkQueueDto first = queue("future-guard-first", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto future = queue("future-guard-second", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    TaskBoardSnapshot created = parallelRoute("future-guards", first, future);
    BoardEntryDto current = entry(created, "future-guards", first.id());
    BoardEntryDto futureEntry = entry(created, "future-guards", future.id());

    assertThatThrownBy(
            () ->
                board.setFutureTaskEntryAvailability(
                    WAREHOUSE_ID,
                    current.id(),
                    new SetFutureTaskEntryAvailabilityRequest(current.version(), false)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Текущий этап");

    BoardEntryDto exposed =
        entry(
            board.setFutureTaskEntryAvailability(
                WAREHOUSE_ID,
                futureEntry.id(),
                new SetFutureTaskEntryAvailabilityRequest(futureEntry.version(), true)),
            "future-guards",
            future.id());
    assertThatThrownBy(
            () ->
                board.setFutureTaskEntryAvailability(
                    WAREHOUSE_ID,
                    futureEntry.id(),
                    new SetFutureTaskEntryAvailabilityRequest(futureEntry.version(), false)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("изменен");

    BoardEntryDto active =
        board.take(
            WAREHOUSE_ID,
            exposed.id(),
            new TakeEntryRequest(exposed.version(), group.id(), worker.id()),
            null);
    assertThatThrownBy(
            () ->
                board.setFutureTaskEntryAvailability(
                    WAREHOUSE_ID,
                    active.id(),
                    new SetFutureTaskEntryAvailabilityRequest(active.version(), false)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("ожидающего");

    WorkQueueDto ses = queue("сэс и санитария", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto afterSes = queue("after-ses-guard", QueueType.REPAIR, 6, 0, workerClass.id());
    TaskBoardSnapshot sesRoute = parallelRoute("ses-future-guard", ses, afterSes);
    BoardEntryDto afterSesEntry = entry(sesRoute, "ses-future-guard", afterSes.id());
    assertThatThrownBy(
            () ->
                board.setFutureTaskEntryAvailability(
                    WAREHOUSE_ID,
                    afterSesEntry.id(),
                    new SetFutureTaskEntryAvailabilityRequest(afterSesEntry.version(), true)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("СЭС");
  }

  @Test
  void rolledBackFutureAvailabilityDoesNotChangeProjectionEventOrWorkerFeedRevision() {
    WorkerClassDto workerClass = registry.createClass(workerClass("future-rollback"));
    WorkQueueDto first = queue("future-rollback-first", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto future = queue("future-rollback-second", QueueType.REPAIR, 6, 0, workerClass.id());
    BoardEntryDto futureEntry =
        entry(parallelRoute("future-rollback", first, future), "future-rollback", future.id());
    clearInvocations(workerInvalidations);

    transactions.executeWithoutResult(
        status -> {
          board.setFutureTaskEntryAvailability(
              WAREHOUSE_ID,
              futureEntry.id(),
              new SetFutureTaskEntryAvailabilityRequest(futureEntry.version(), true));
          status.setRollbackOnly();
        });

    BoardEntryDto afterRollback = board.entry(WAREHOUSE_ID, futureEntry.id());
    assertThat(afterRollback.entryType()).isEqualTo(EntryType.SHADOW);
    assertThat(afterRollback.version()).isEqualTo(futureEntry.version());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='QUEUE_ENTRY' "
                    + "and aggregate_id=? and event_type='task-board.queue-entry.changed.v1'",
                Integer.class,
                futureEntry.id().toString()))
        .isZero();
    verify(workerInvalidations, never()).feedChanged(eq(WAREHOUSE_ID), anyLong());
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

  @Test
  void linkedQueuesKeepTheNextCabinStageWithItsPrimaryGroupWithoutStartingItsTimer() {
    WorkerClassDto workerClass = registry.createClass(workerClass("linked-workers"));
    WorkQueueDto exterior = queue("Внешние работы", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto interior = queue("Внутренние работы", QueueType.REPAIR, 6, 0, workerClass.id());
    link(exterior, interior);
    WorkerDto worker = worker(workerClass.id(), "Первый рабочий");
    WorkerGroupDto group = group(workerClass.id(), worker.id(), "Первая бригада");
    WorkerDto otherWorker = worker(workerClass.id(), "Второй рабочий");
    WorkerGroupDto otherGroup = group(workerClass.id(), otherWorker.id(), "Вторая бригада");
    workforce.setCurrentGroup(
        WAREHOUSE_ID, worker.id(), new SetCurrentGroupRequest(worker.version(), group.id()));
    workforce.setCurrentGroup(
        WAREHOUSE_ID,
        otherWorker.id(),
        new SetCurrentGroupRequest(otherWorker.version(), otherGroup.id()));
    TaskBoardSnapshot route = parallelRoute("Бытовка 101", exterior, interior);
    BoardEntryDto first = entry(column(route, exterior.id()).entries(), "Бытовка 101");
    BoardEntryDto second = entry(column(route, interior.id()).entries(), "Бытовка 101");
    BoardEntryDto unrelated =
        entry(
            create(exterior.definitionId(), "Бытовка 102", LocalDate.of(2026, 8, 21), 3),
            "Бытовка 102");
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            first.id(),
            new TakeEntryRequest(first.version(), group.id(), null),
            null);
    board.complete(WAREHOUSE_ID, first.id(), new VersionCommand(taken.version()), null);
    BoardEntryDto continuation = board.entry(WAREHOUSE_ID, second.id());
    assertThat(continuation.status()).isEqualTo(EntryStatus.WAITING);
    assertThat(continuation.activeStartedAt()).isNull();
    assertThat(continuation.assignments()).isEmpty();
    assertThat(workerFeedEntryIds(worker.id()))
        .contains(second.id())
        .doesNotContain(unrelated.id());
    assertThat(workerFeedEntryIds(otherWorker.id()))
        .contains(unrelated.id())
        .doesNotContain(second.id());
    assertThatThrownBy(
            () ->
                board.take(
                    WAREHOUSE_ID,
                    second.id(),
                    new TakeEntryRequest(continuation.version(), otherGroup.id(), null),
                    null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Первая бригада");
    BoardEntryDto currentUnrelated = board.entry(WAREHOUSE_ID, unrelated.id());
    assertThatThrownBy(
            () ->
                board.take(
                    WAREHOUSE_ID,
                    unrelated.id(),
                    new TakeEntryRequest(currentUnrelated.version(), group.id(), null),
                    null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("связанной очереди");
    BoardEntryDto continued =
        board.take(
            WAREHOUSE_ID,
            second.id(),
            new TakeEntryRequest(continuation.version(), group.id(), null),
            null);
    assertThat(continued.status()).isEqualTo(EntryStatus.IN_PROGRESS);
    assertThat(continued.assignments())
        .allSatisfy(assignment -> assertThat(assignment.workerGroupId()).isEqualTo(group.id()));
    assertThat(
            jdbc.queryForObject(
                "select bool_and(primary_participation) from task_assignment where"
                    + " queue_entry_id=?",
                Boolean.class,
                first.id()))
        .isTrue();
  }

  @Test
  void queueLinksAreSymmetricFencedAndVisibleOnBothPhysicalColumns() {
    WorkerClassDto workerClass = registry.createClass(workerClass("linked-catalog"));
    WorkQueueDto first = queue("Парная первая", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto second = queue("Парная вторая", QueueType.REPAIR, 6, 0, workerClass.id());
    QueueDefinitionDto beforeFirst =
        registry.dto(registry.requireQueueDefinition(first.definitionId()));
    QueueDefinitionDto beforeSecond =
        registry.dto(registry.requireQueueDefinition(second.definitionId()));
    assertThatThrownBy(
            () ->
                registry.linkQueueDefinitions(
                    first.definitionId(),
                    new QueueLinkRequest(
                        beforeFirst.version(), second.definitionId(), beforeSecond.version() + 1)))
        .isInstanceOf(ConflictException.class);
    assertThat(
            registry
                .dto(registry.requireQueueDefinition(first.definitionId()))
                .linkedQueueDefinitionId())
        .isNull();
    link(first, second);
    assertThat(
            registry
                .dto(registry.requireQueueDefinition(first.definitionId()))
                .linkedQueueDefinitionId())
        .isEqualTo(second.definitionId());
    assertThat(
            registry
                .dto(registry.requireQueueDefinition(second.definitionId()))
                .linkedQueueDefinitionId())
        .isEqualTo(first.definitionId());
    TaskBoardSnapshot snapshot = board.snapshot(WAREHOUSE_ID);
    assertThat(column(snapshot, first.id()).linkedQueueId()).isEqualTo(second.id());
    assertThat(column(snapshot, second.id()).linkedQueueId()).isEqualTo(first.id());
    assertThat(column(snapshot, first.id()).linkedQueueName()).isEqualTo(second.name());
    QueueDefinitionDto currentFirst =
        registry.dto(registry.requireQueueDefinition(first.definitionId()));
    QueueDefinitionDto currentSecond =
        registry.dto(registry.requireQueueDefinition(second.definitionId()));
    assertThatThrownBy(
            () -> registry.deleteQueueDefinition(currentFirst.id(), currentFirst.version()))
        .hasMessageContaining("разорвите связь");
    registry.linkQueueDefinitions(
        currentFirst.id(),
        new QueueLinkRequest(currentFirst.version(), null, currentSecond.version()));
    assertThat(
            registry
                .dto(registry.requireQueueDefinition(first.definitionId()))
                .linkedQueueDefinitionId())
        .isNull();
    assertThat(
            registry
                .dto(registry.requireQueueDefinition(second.definitionId()))
                .linkedQueueDefinitionId())
        .isNull();
  }

  @Test
  void oneMemberTakesForTheWholeGroupAndAnotherCompletesForBoth() {
    WorkerClassDto workerClass = registry.createClass(workerClass("whole-group-continuation"));
    WorkQueueDto first = queue("Первый этап группы", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto second = queue("Второй этап группы", QueueType.REPAIR, 6, 0, workerClass.id());
    link(first, second);
    WorkerDto firstWorker = worker(workerClass.id(), "Первый участник");
    WorkerDto secondWorker = worker(workerClass.id(), "Второй участник");
    WorkerGroupDto group =
        workforce.createGroup(
            WAREHOUSE_ID,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Группа из двух",
                null,
                true,
                List.of(
                    new GroupMemberRequest(firstWorker.id(), true),
                    new GroupMemberRequest(secondWorker.id(), true))));
    workforce.setCurrentGroup(
        WAREHOUSE_ID,
        firstWorker.id(),
        new SetCurrentGroupRequest(firstWorker.version(), group.id()));
    workforce.setCurrentGroup(
        WAREHOUSE_ID,
        secondWorker.id(),
        new SetCurrentGroupRequest(secondWorker.version(), group.id()));
    TaskBoardSnapshot route = parallelRoute("Бытовка 107", first, second);
    BoardEntryDto source = entry(column(route, first.id()).entries(), "Бытовка 107");
    BoardEntryDto target = entry(column(route, second.id()).entries(), "Бытовка 107");
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            source.id(),
            new TakeEntryRequest(source.version(), group.id(), firstWorker.id()),
            firstWorker.id());
    assertThat(taken.assignments())
        .extracting(AssignmentDto::workerId)
        .containsExactlyInAnyOrder(firstWorker.id(), secondWorker.id());
    board.complete(
        WAREHOUSE_ID, source.id(), new VersionCommand(taken.version()), secondWorker.id());
    assertThat(board.entry(WAREHOUSE_ID, source.id()).assignments())
        .allSatisfy(
            assignment ->
                assertThat(assignment.status())
                    .isEqualTo(dev.buhanzaz.rwms.taskboard.domain.AssignmentStatus.DONE));
    assertThat(workerFeedEntryIds(firstWorker.id())).contains(target.id());
    assertThat(workerFeedEntryIds(secondWorker.id())).contains(target.id());
    BoardEntryDto continuation = board.entry(WAREHOUSE_ID, target.id());
    BoardEntryDto continued =
        board.take(
            WAREHOUSE_ID,
            target.id(),
            new TakeEntryRequest(continuation.version(), group.id(), secondWorker.id()),
            secondWorker.id());
    assertThat(continued.assignments())
        .extracting(AssignmentDto::workerId)
        .containsExactlyInAnyOrder(firstWorker.id(), secondWorker.id());
    board.complete(
        WAREHOUSE_ID, target.id(), new VersionCommand(continued.version()), firstWorker.id());
  }

  @Test
  void unlinkingReleasesTheContinuationForAnotherQualifiedGroup() {
    WorkerClassDto workerClass = registry.createClass(workerClass("unlink-work"));
    WorkQueueDto first = queue("Начало работ", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto second = queue("Продолжение работ", QueueType.REPAIR, 6, 0, workerClass.id());
    link(first, second);
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    WorkerDto otherWorker = worker(workerClass.id(), "Другой рабочий");
    WorkerGroupDto otherGroup = group(workerClass.id(), otherWorker.id(), "Другая бригада");
    TaskBoardSnapshot route = parallelRoute("Бытовка 103", first, second);
    BoardEntryDto source = entry(column(route, first.id()).entries(), "Бытовка 103");
    BoardEntryDto target = entry(column(route, second.id()).entries(), "Бытовка 103");
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            source.id(),
            new TakeEntryRequest(source.version(), group.id(), null),
            null);
    board.complete(WAREHOUSE_ID, source.id(), new VersionCommand(taken.version()), null);
    QueueDefinitionDto sourceDefinition =
        registry.dto(registry.requireQueueDefinition(first.definitionId()));
    QueueDefinitionDto targetDefinition =
        registry.dto(registry.requireQueueDefinition(second.definitionId()));
    registry.linkQueueDefinitions(
        sourceDefinition.id(),
        new QueueLinkRequest(sourceDefinition.version(), null, targetDefinition.version()));
    BoardEntryDto current = board.entry(WAREHOUSE_ID, target.id());
    assertThat(
            board
                .take(
                    WAREHOUSE_ID,
                    target.id(),
                    new TakeEntryRequest(current.version(), otherGroup.id(), null),
                    null)
                .status())
        .isEqualTo(EntryStatus.IN_PROGRESS);
  }

  @Test
  void linksRejectSelfHoldingAndDifferentPrimaryClasses() {
    WorkerClassDto firstClass = registry.createClass(workerClass("link-class-one"));
    WorkerClassDto secondClass = registry.createClass(workerClass("link-class-two"));
    WorkQueueDto first = queue("Первый класс", QueueType.REPAIR, 6, 0, firstClass.id());
    WorkQueueDto second = queue("Второй класс", QueueType.REPAIR, 6, 0, secondClass.id());
    WorkQueueDto holding = queue("Выдержка", QueueType.HOLDING, 6, 0, firstClass.id());
    assertThatThrownBy(() -> link(first, first)).hasMessageContaining("с собой");
    assertThatThrownBy(() -> link(first, second)).hasMessageContaining("общий основной класс");
    assertThatThrownBy(() -> link(first, holding)).hasMessageContaining("без выдержки");
  }

  private void link(WorkQueueDto first, WorkQueueDto second) {
    QueueDefinitionDto source = registry.dto(registry.requireQueueDefinition(first.definitionId()));
    QueueDefinitionDto target =
        registry.dto(registry.requireQueueDefinition(second.definitionId()));
    registry.linkQueueDefinitions(
        source.id(), new QueueLinkRequest(source.version(), target.id(), target.version()));
  }

  @Test
  void aSecondaryGroupCannotBecomeTheContinuationOwnerAfterItsClassChanges() {
    WorkerClassDto primaryClass = registry.createClass(workerClass("primary-continuation"));
    WorkerClassDto helperClass = registry.createClass(workerClass("helper-continuation"));
    WorkQueueDto first = queue("Основная работа", QueueType.REPAIR, 6, 0, primaryClass.id());
    WorkQueueDto second = queue("Следующая работа", QueueType.REPAIR, 6, 0, primaryClass.id());
    QueueDefinitionDto definition =
        registry.dto(registry.requireQueueDefinition(first.definitionId()));
    registry.updateQueueDefinition(
        definition.id(),
        new QueueDefinitionRequest(
            definition.version(),
            definition.name(),
            definition.description(),
            definition.type(),
            definition.purpose(),
            definition.sortOrder(),
            true,
            false,
            false,
            null,
            null,
            false,
            0,
            6,
            List.of(
                new QueueBindingRequest(
                    primaryClass.id(), 0, false, ParticipationPolicy.PRIMARY, false),
                new QueueBindingRequest(
                    helperClass.id(), 1, false, ParticipationPolicy.OPTIONAL, false))));
    link(first, second);
    WorkerDto worker = worker(primaryClass.id(), "Основной рабочий");
    WorkerGroupDto primary = group(primaryClass.id(), worker.id(), "Основная группа");
    WorkerDto helper =
        workforce.createWorker(
            WAREHOUSE_ID,
            new WorkerRequest(
                0L,
                "Помощник",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of(
                    new QualificationRequest(helperClass.id(), true, null),
                    new QualificationRequest(primaryClass.id(), true, null))));
    WorkerGroupDto secondary = group(helperClass.id(), helper.id(), "Помощники");
    TaskBoardSnapshot route = parallelRoute("Бытовка 104", first, second);
    BoardEntryDto source = entry(column(route, first.id()).entries(), "Бытовка 104");
    BoardEntryDto target = entry(column(route, second.id()).entries(), "Бытовка 104");
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            source.id(),
            new TakeEntryRequest(source.version(), primary.id(), null),
            null);
    BoardEntryDto joined =
        board.take(
            WAREHOUSE_ID,
            source.id(),
            new TakeEntryRequest(taken.version(), secondary.id(), helper.id()),
            null);
    board.complete(WAREHOUSE_ID, source.id(), new VersionCommand(joined.version()), null);
    WorkerGroupDto currentSecondary =
        workforce.listGroups(WAREHOUSE_ID).stream()
            .filter(value -> value.id().equals(secondary.id()))
            .findFirst()
            .orElseThrow();
    workforce.updateGroup(
        WAREHOUSE_ID,
        secondary.id(),
        new WorkerGroupRequest(
            currentSecondary.version(),
            primaryClass.id(),
            secondary.name(),
            null,
            true,
            List.of(new GroupMemberRequest(helper.id(), true))));
    BoardEntryDto continuation = board.entry(WAREHOUSE_ID, target.id());
    assertThatThrownBy(
            () ->
                board.take(
                    WAREHOUSE_ID,
                    target.id(),
                    new TakeEntryRequest(continuation.version(), secondary.id(), null),
                    null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Основная группа");
    assertThat(
            jdbc.queryForObject(
                "select primary_participation from task_assignment where queue_entry_id=? and"
                    + " worker_id=?",
                Boolean.class,
                source.id(),
                helper.id()))
        .isFalse();
  }

  @Test
  void aDisabledContinuationPlanDoesNotBlockTheGroupsOtherPublishedWork() {
    WorkerClassDto workerClass = registry.createClass(workerClass("continuation-plan"));
    WorkQueueDto first = queue("Начальный этап", QueueType.REPAIR, 6, 0, workerClass.id());
    WorkQueueDto second = queue("Закрытый план", QueueType.REPAIR, 6, 0, workerClass.id());
    link(first, second);
    WorkerDto worker = worker(workerClass.id());
    WorkerGroupDto group = group(workerClass.id(), worker.id());
    workforce.setCurrentGroup(
        WAREHOUSE_ID, worker.id(), new SetCurrentGroupRequest(worker.version(), group.id()));
    TaskBoardSnapshot route = parallelRoute("Бытовка 105", first, second);
    BoardEntryDto source = entry(column(route, first.id()).entries(), "Бытовка 105");
    BoardEntryDto target = entry(column(route, second.id()).entries(), "Бытовка 105");
    BoardEntryDto taken =
        board.take(
            WAREHOUSE_ID,
            source.id(),
            new TakeEntryRequest(source.version(), group.id(), null),
            null);
    board.complete(WAREHOUSE_ID, source.id(), new VersionCommand(taken.version()), null);
    WorkQueueDto currentQueue = registry.dto(registry.requireQueue(second.id()));
    workerPlans.update(
        WAREHOUSE_ID, second.id(), new WorkerQueuePlanRequest(currentQueue.version(), false, 6));
    BoardEntryDto unrelated =
        entry(
            create(first.definitionId(), "Бытовка 106", LocalDate.of(2026, 8, 21), 3),
            "Бытовка 106");
    assertThat(workerFeedEntryIds(worker.id()))
        .contains(unrelated.id())
        .doesNotContain(target.id());
    assertThat(
            board
                .take(
                    WAREHOUSE_ID,
                    unrelated.id(),
                    new TakeEntryRequest(unrelated.version(), group.id(), null),
                    null)
                .status())
        .isEqualTo(EntryStatus.IN_PROGRESS);
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
    return worker(workerClassId, "Исполнитель");
  }

  private WorkerDto worker(UUID workerClassId, String displayName) {
    return workforce.createWorker(
        WAREHOUSE_ID,
        new WorkerRequest(
            0L,
            displayName,
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
    return group(workerClassId, workerId, "Бригада");
  }

  private WorkerGroupDto group(UUID workerClassId, UUID workerId, String name) {
    return workforce.createGroup(
        WAREHOUSE_ID,
        new WorkerGroupRequest(
            0L,
            workerClassId,
            name,
            null,
            true,
            List.of(new GroupMemberRequest(workerId, true))));
  }

  private TaskBoardSnapshot parallelRoute(
      String title, WorkQueueDto first, WorkQueueDto second) {
    return board.createTask(
        WAREHOUSE_ID,
        new CreateBoardTaskRequest(
            null,
            title,
            null,
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(first.definitionId(), first.name(), null),
                new RouteStepRequest(second.definitionId(), second.name(), null)),
            LocalDate.of(2026, 8, 21),
            3));
  }

  private List<UUID> workerFeedEntryIds(UUID workerId) {
    return workerTasks.feed(workerId, WAREHOUSE_ID, null, 50).feed().categories().stream()
        .flatMap(category -> category.entries().stream())
        .map(entry -> entry.entryId())
        .toList();
  }

  private List<UUID> ownerProofReaderWorkerIds(UUID entryId) {
    return jdbc.queryForList(
        """
        select reader.value::uuid
          from event_stream_head head
          join domain_event event on event.event_id=head.last_event_id
          cross join lateral jsonb_array_elements_text(event.payload->'readerWorkerIds') reader(value)
         where head.aggregate_type='TASK_BOARD_ENTRY_OWNER_PROOF'
           and head.aggregate_id=?
         order by reader.value
        """,
        UUID.class,
        entryId.toString());
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
