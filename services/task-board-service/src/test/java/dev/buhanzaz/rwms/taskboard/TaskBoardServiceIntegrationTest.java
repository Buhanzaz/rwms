package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.config.DevTaskBoardBootstrap;
import dev.buhanzaz.rwms.taskboard.config.TaskBoardClientProperties;
import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueUsageReferenceRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskTimeEventRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkerDeletionIntentRepository;
import dev.buhanzaz.rwms.taskboard.security.AccessLevel;
import dev.buhanzaz.rwms.taskboard.security.WarehouseAccessAuthorizer;
import dev.buhanzaz.rwms.taskboard.service.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.security.oauth2.server.resource.autoconfigure.OAuth2ResourceServerProperties;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TaskBoardServiceIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID W1 = UUID.fromString("00000000-0000-0000-0000-000000000101");
  private static final UUID W2 = UUID.fromString("00000000-0000-0000-0000-000000000102");
  @org.springframework.beans.factory.annotation.Autowired RegistryService registry;
  @org.springframework.beans.factory.annotation.Autowired WorkforceService workforce;
  @org.springframework.beans.factory.annotation.Autowired TaskBoardService board;
  @org.springframework.beans.factory.annotation.Autowired FakeCredentials credentials;
  @org.springframework.beans.factory.annotation.Autowired JdbcTemplate jdbc;

  @org.springframework.beans.factory.annotation.Autowired
  OAuth2ResourceServerProperties resourceServer;

  @org.springframework.beans.factory.annotation.Autowired WarehouseAccessAuthorizer authorizer;
  @org.springframework.beans.factory.annotation.Autowired TaskTimeEventRepository timeEvents;
  @org.springframework.beans.factory.annotation.Autowired BoardTaskRepository tasks;
  @org.springframework.beans.factory.annotation.Autowired QueueEntryRepository entries;
  @org.springframework.beans.factory.annotation.Autowired QueueUsageReferenceRepository queueReferences;
  @org.springframework.beans.factory.annotation.Autowired TaskAssignmentRepository assignments;
  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;
  @org.springframework.beans.factory.annotation.Autowired(required = false)
  DevTaskBoardBootstrap devBootstrap;

  @org.springframework.beans.factory.annotation.Autowired
  WorkerDeletionIntentRepository deletionIntents;

  @org.springframework.beans.factory.annotation.Autowired
  TaskBoardClientProperties clientProperties;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
    credentials.resetState();
  }

  @Test
  void holdingIsAlwaysLastAndStaleVersionConflicts() {
    var holding = registry.createQueue(W1, queue("HOLD", QueueType.HOLDING, List.of()));
    var repair = registry.createQueue(W1, queue("REPAIR", QueueType.REPAIR, List.of()));
    assertThat(registry.listQueues(W1))
        .extracting(WorkQueueDto::id)
        .containsExactly(repair.id(), holding.id());
    assertThatThrownBy(
            () ->
                registry.updateQueue(
                    W1,
                    repair.id(),
                    new WorkQueueRequest(
                        99L,
                        "REPAIR",
                        "Repair",
                        null,
                        QueueType.REPAIR,
                        true,
                        false,
                        false,
                        null,
                        null,
                        false,
                        List.of())))
        .isInstanceOf(StaleVersionException.class);
  }

  @Test
  void updateClassAndReusedReferenceReturnReusableVersions() {
    var created = registry.createClass(workerClass("VERSIONED"));
    var updated =
        registry.updateClass(
            created.id(),
            new WorkerClassRequest(
                created.version(), "VERSIONED", "Versioned", null, null, 10, true));
    assertThat(updated.version()).isGreaterThan(created.version());
    var updatedAgain =
        registry.updateClass(
            updated.id(),
            new WorkerClassRequest(
                updated.version(), "VERSIONED", "Versioned", null, null, 10, true));
    assertThat(updatedAgain.version()).isGreaterThan(updated.version());

    var queue = registry.createQueue(W1, queue("REF", QueueType.REPAIR, List.of()));
    var first =
        registry.registerReference(
            queue.id(), new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-version"));
    var reused =
        registry.registerReference(
            queue.id(), new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-version"));
    assertThat(reused.id()).isEqualTo(first.id());
    assertThat(reused.version()).isEqualTo(first.version());
    registry.deleteReference(first.type(), first.externalReferenceId(), first.version());
    assertThat(queueReferences.count()).isZero();
  }

  @Test
  void reusedReferenceRejectsAnotherQueueAndConcurrentRetryCreatesOneRow() throws Exception {
    var firstQueue = registry.createQueue(W1, queue("REF_FIRST", QueueType.REPAIR, List.of()));
    var secondQueue = registry.createQueue(W1, queue("REF_SECOND", QueueType.REPAIR, List.of()));
    var request = new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-concurrent");

    List<Object> outcomes =
        race(
            () -> registry.registerReference(firstQueue.id(), request),
            () -> registry.registerReference(firstQueue.id(), request));

    assertThat(outcomes).allMatch(QueueReferenceDto.class::isInstance);
    var registered = outcomes.stream().map(QueueReferenceDto.class::cast).toList();
    assertThat(registered).extracting(QueueReferenceDto::id).containsOnly(registered.getFirst().id());
    assertThat(registered)
        .extracting(QueueReferenceDto::version)
        .containsOnly(registered.getFirst().version());
    assertThat(queueReferences.count()).isEqualTo(1);
    assertThatThrownBy(() -> registry.registerReference(secondQueue.id(), request))
        .isInstanceOf(ConflictException.class);
    assertThat(queueReferences.count()).isEqualTo(1);
  }

  @Test
  void unfinishedHoldingHidesAllLaterShadowStagesEvenWhenRequested() {
    var holding = registry.createQueue(W1, queue("HOLD", QueueType.HOLDING, List.of()));
    var after = registry.createQueue(W1, queue("AFTER", QueueType.REPAIR, List.of()));
    var snapshot =
        board.createTask(
            W1,
            new CreateBoardTaskRequest(
                null,
                "holding-route",
                null,
                null,
                null,
                null,
                List.of(
                    new RouteStepRequest(holding.id(), null, "hold", null),
                    new RouteStepRequest(after.id(), null, "after", null))));
    assertThat(snapshot.columns().stream().flatMap(column -> column.entries().stream()))
        .extracting(BoardEntryDto::taskText)
        .containsExactly("hold");
    assertThat(board.snapshot(W1, true).columns().stream().flatMap(c -> c.entries().stream()))
        .extracting(BoardEntryDto::taskText)
        .containsExactly("hold");
  }

  @Test
  void createRejectsDuplicateEffectiveRouteAndExternalTaskId() {
    var queue = registry.createQueue(W1, queue("DUP", QueueType.REPAIR, List.of()));
    var duplicateRoute =
        new CreateBoardTaskRequest(
            null,
            "duplicate-route",
            null,
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(queue.id(), null, null, null),
                new RouteStepRequest(queue.id(), null, null, null)));
    assertThatThrownBy(() -> board.createTask(W1, duplicateRoute))
        .isInstanceOf(ConflictException.class);
    var duplicateCodeRoute =
        new CreateBoardTaskRequest(
            null,
            "duplicate-code-route",
            null,
            null,
            null,
            null,
            List.of(
                new RouteStepRequest(null, "virtual", null, null),
                new RouteStepRequest(null, "VIRTUAL", null, null)));
    assertThatThrownBy(() -> board.createTask(W1, duplicateCodeRoute))
        .isInstanceOf(ConflictException.class);
    assertThat(board.snapshot(W1, true).columns())
        .flatExtracting(BoardColumnDto::entries)
        .isEmpty();

    UUID externalTaskId = UUID.randomUUID();
    board.createTask(
        W1,
        new CreateBoardTaskRequest(
            externalTaskId,
            "external",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queue.id(), null, null, null))));
    assertThat(entry("external").externalTaskId()).isEqualTo(externalTaskId);
    assertThatThrownBy(
            () ->
                board.createTask(
                    W1,
                    new CreateBoardTaskRequest(
                        externalTaskId,
                        "external-duplicate",
                        null,
                        null,
                        null,
                        null,
                        List.of(new RouteStepRequest(queue.id(), null, null, null)))))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void identicalExternalTaskRetryReturnsCurrentStateWithoutSecondEvent() {
    var queue = registry.createQueue(W1, queue("IDEMPOTENT", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.parse("2026-07-13T12:00:00+03:00");
    var first =
        new CreateBoardTaskRequest(
            externalTaskId,
            "  Идемпотентная задача  ",
            "  БЫТ-001 ",
            " описание ",
            30,
            deadline,
            List.of(new RouteStepRequest(queue.id(), null, " работа ", 15)));
    var equivalent =
        new CreateBoardTaskRequest(
            externalTaskId,
            "Идемпотентная задача",
            "БЫТ-001",
            "описание",
            30,
            deadline.withOffsetSameInstant(ZoneOffset.UTC),
            List.of(new RouteStepRequest(queue.id(), "IGNORED", "работа", 15)));

    var created = board.createTask(W1, first);
    registry.updateQueue(
        W1,
        queue.id(),
        new WorkQueueRequest(
            queue.version(),
            queue.code(),
            "Переименованная очередь",
            queue.description(),
            queue.type(),
            true,
            queue.hidden(),
            queue.collapsed(),
            queue.holdingPeriodMinutes(),
            queue.notificationThreshold(),
            queue.notifyWhenThresholdReached(),
            List.of()));
    var replayed = board.createTask(W1, equivalent);

    assertThat(replayed.columns())
        .flatExtracting(BoardColumnDto::entries)
        .extracting(BoardEntryDto::taskId)
        .containsExactly(
            created.columns().stream()
                .flatMap(column -> column.entries().stream())
                .findFirst()
                .orElseThrow()
                .taskId());
    assertThat(tasks.findByExternalTaskId(externalTaskId)).isPresent();
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CREATED)).isEqualTo(1);
    assertThat(board.registration(W1, externalTaskId).route()).hasSize(1);
  }

  @Test
  void externalTaskRetryRejectsChangedLegacyAndCrossWarehouseCommands() {
    var queue = registry.createQueue(W1, queue("IDEMPOTENT_CONFLICT", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var request = externalTask(externalTaskId, queue.id(), "original");
    board.createTask(W1, request);

    assertThatThrownBy(() -> board.createTask(W1, externalTask(externalTaskId, queue.id(), "changed")))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(() -> board.createTask(W2, request))
        .isInstanceOf(ConflictException.class);

    jdbc.update(
        "update board_task set request_fingerprint = null where external_task_id = ?",
        externalTaskId);
    assertThatThrownBy(() -> board.createTask(W1, request)).isInstanceOf(ConflictException.class);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CREATED)).isEqualTo(1);
  }

  @Test
  void concurrentExternalTaskRetryCreatesOneTaskAndOneEvent() throws Exception {
    var queue = registry.createQueue(W1, queue("IDEMPOTENT_RACE", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var request = externalTask(externalTaskId, queue.id(), "race");
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return board.createTask(W1, request);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return board.createTask(W1, request);
              });
      start.countDown();
      first.get(10, TimeUnit.SECONDS);
      second.get(10, TimeUnit.SECONDS);
    }

    assertThat(tasks.count()).isEqualTo(1);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CREATED)).isEqualTo(1);
  }

  @Test
  void concurrentMixedCaseIdentitiesCommitOnlyOneCanonicalValue() throws Exception {
    List<Object> classOutcomes =
        race(
            () -> registry.createClass(workerClass("MixedClass")),
            () -> registry.createClass(workerClass("mixedclass")));
    assertSingleSuccess(classOutcomes);
    assertThat(registry.listClasses()).extracting(WorkerClassDto::code).containsExactly("MIXEDCLASS");

    List<Object> queueOutcomes =
        race(
            () -> registry.createQueue(W1, queue("MixedQueue", QueueType.REPAIR, List.of())),
            () -> registry.createQueue(W1, queue("mixedqueue", QueueType.REPAIR, List.of())));
    assertSingleSuccess(queueOutcomes);
    assertThat(registry.listQueues(W1)).extracting(WorkQueueDto::code).containsExactly("MIXEDQUEUE");

    List<Object> workerOutcomes =
        race(
            () ->
                workforce.createWorker(
                    W1, worker("Login One", "Mixed.Login", "password-123", List.of())),
            () ->
                workforce.createWorker(
                    W1, worker("Login Two", "mixed.login", "password-123", List.of())));
    assertSingleSuccess(workerOutcomes);
    assertThat(workforce.listWorkers(W1)).extracting(WorkerDto::appLogin).containsExactly("mixed.login");
    assertThat(credentials.lastConfiguredLogin).hasValue("mixed.login");
  }

  @Test
  void concurrentQueueCreationKeepsUniqueOrderAndHoldingLast() throws Exception {
    List<Object> outcomes =
        race(
            () -> registry.createQueue(W1, queue("REGULAR_RACE", QueueType.REPAIR, List.of())),
            () -> registry.createQueue(W1, queue("HOLDING_RACE", QueueType.HOLDING, List.of())));

    assertThat(outcomes).allMatch(WorkQueueDto.class::isInstance);
    var ordered = registry.listQueues(W1);
    assertThat(ordered).hasSize(2);
    assertThat(ordered).extracting(WorkQueueDto::sortOrder).doesNotHaveDuplicates();
    assertThat(ordered.getLast().type()).isEqualTo(QueueType.HOLDING);
  }

  @Test
  void reorderIsFullSetCasAndConcurrentCreateCannotCorruptOrder() throws Exception {
    var first = registry.createQueue(W1, queue("ORDER_FIRST", QueueType.REPAIR, List.of()));
    var second = registry.createQueue(W1, queue("ORDER_SECOND", QueueType.MOVEMENT, List.of()));
    var holding = registry.createQueue(W1, queue("ORDER_HOLDING", QueueType.HOLDING, List.of()));

    assertThatThrownBy(
            () ->
                registry.reorder(
                    W1,
                    new QueueOrderRequest(
                        List.of(
                            new QueueOrderItem(first.id(), first.version()),
                            new QueueOrderItem(second.id(), second.version())))))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                registry.reorder(
                    W1,
                    new QueueOrderRequest(
                        List.of(
                            new QueueOrderItem(first.id(), first.version()),
                            new QueueOrderItem(first.id(), first.version()),
                            new QueueOrderItem(holding.id(), holding.version())))))
        .isInstanceOf(ConflictException.class);
    var foreign = registry.createQueue(W2, queue("ORDER_FOREIGN", QueueType.REPAIR, List.of()));
    assertThatThrownBy(
            () ->
                registry.reorder(
                    W1,
                    new QueueOrderRequest(
                        List.of(
                            new QueueOrderItem(first.id(), first.version()),
                            new QueueOrderItem(second.id(), second.version()),
                            new QueueOrderItem(foreign.id(), foreign.version())))))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () ->
                registry.reorder(
                    W1,
                    new QueueOrderRequest(
                        List.of(
                            new QueueOrderItem(first.id(), first.version() + 1),
                            new QueueOrderItem(second.id(), second.version()),
                            new QueueOrderItem(holding.id(), holding.version())))))
        .isInstanceOf(StaleVersionException.class);

    var reorder =
        new QueueOrderRequest(
            List.of(
                new QueueOrderItem(second.id(), second.version()),
                new QueueOrderItem(holding.id(), holding.version()),
                new QueueOrderItem(first.id(), first.version())));
    List<Object> outcomes =
        race(
            () -> registry.reorder(W1, reorder),
            () -> registry.createQueue(W1, queue("ORDER_CONCURRENT", QueueType.REPAIR, List.of())));
    assertThat(outcomes).anyMatch(WorkQueueDto.class::isInstance);
    assertThat(outcomes)
        .allMatch(value -> value instanceof List<?> || value instanceof WorkQueueDto || value instanceof ConflictException);

    var ordered = registry.listQueues(W1);
    assertThat(ordered).hasSize(4);
    assertThat(ordered).extracting(WorkQueueDto::id).doesNotHaveDuplicates();
    assertThat(ordered).extracting(WorkQueueDto::sortOrder).doesNotHaveDuplicates();
    assertThat(ordered.getLast().type()).isEqualTo(QueueType.HOLDING);
  }

  @Test
  void kafkaOutboxInsertFailureRollsBackTaskAndRouteAtomically() {
    var queue = registry.createQueue(W1, queue("OUTBOX_ROLLBACK", QueueType.REPAIR, List.of()));
    long initialOutboxCount = kafkaOutboxCount(null);
    jdbc.execute(
        "create function reject_task_board_kafka_outbox() returns trigger language plpgsql as $$ begin raise exception 'outbox rejected'; end $$");
    jdbc.execute(
        "create trigger reject_task_board_kafka_outbox before insert on outbox_event for each row execute function reject_task_board_kafka_outbox()");
    try {
      assertThatThrownBy(
              () ->
                  board.createTask(
                      W1,
                      externalTask(UUID.randomUUID(), queue.id(), "atomic-rollback")))
          .isInstanceOf(RuntimeException.class);
      assertThat(tasks.count()).isZero();
      assertThat(entries.count()).isZero();
      assertThat(kafkaOutboxCount(null)).isEqualTo(initialOutboxCount);
    } finally {
      jdbc.execute("drop trigger if exists reject_task_board_kafka_outbox on outbox_event");
      jdbc.execute("drop function if exists reject_task_board_kafka_outbox()");
    }
  }

  @Test
  void unassignedOrderingIsIsolatedPerWarehouse() {
    UUID w2FirstExternal = UUID.randomUUID();
    UUID w2SecondExternal = UUID.randomUUID();
    board.createTask(W2, externalTask(w2FirstExternal, null, "w2-first"));
    board.createTask(W2, externalTask(w2SecondExternal, null, "w2-second"));
    var w2Before =
        entries.findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(W2).stream()
            .map(entry -> List.of(entry.getId(), entry.getVersion(), entry.getQueuePosition()))
            .toList();

    UUID w1FirstExternal = UUID.randomUUID();
    board.createTask(W1, externalTask(w1FirstExternal, null, "w1-first"));
    board.createTask(W1, externalTask(UUID.randomUUID(), null, "w1-second"));
    var firstRegistration = board.registration(W1, w1FirstExternal);
    board.cancelTask(
        W1,
        w1FirstExternal,
        new CancelTaskRequest(firstRegistration.taskVersion(), "warehouse isolation"));

    var w2After =
        entries.findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(W2).stream()
            .map(entry -> List.of(entry.getId(), entry.getVersion(), entry.getQueuePosition()))
            .toList();
    assertThat(w2After).isEqualTo(w2Before);
    assertThat(w2After).extracting(values -> values.get(2)).containsExactly(0, 1);
  }

  @Test
  void concurrentCompleteMoveAndCancelPreserveQueuePositionInvariants() throws Exception {
    var workerClass = registry.createClass(workerClass("QUEUE_RACE_WORKER"));
    var source =
        registry.createQueue(
            W1,
            queue(
                "QUEUE_RACE_SOURCE",
                QueueType.MOVEMENT,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var target =
        registry.createQueue(
            W1,
            queue(
                "QUEUE_RACE_TARGET",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Queue race worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    UUID completeExternal = UUID.randomUUID();
    UUID moveExternal = UUID.randomUUID();
    UUID cancelExternal = UUID.randomUUID();
    board.createTask(W1, externalTask(completeExternal, source.id(), "complete-race"));
    board.createTask(W1, externalTask(moveExternal, source.id(), "move-race"));
    board.createTask(W1, externalTask(cancelExternal, source.id(), "cancel-race"));
    var completeEntry = entry("complete-race");
    completeEntry =
        board.take(
            W1,
            completeEntry.id(),
            new TakeEntryRequest(completeEntry.version(), null, worker.id()),
            null);
    var moveEntry = entry("move-race");
    long cancelVersion = board.registration(W1, cancelExternal).taskVersion();

    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(3)) {
      BoardEntryDto finalCompleteEntry = completeEntry;
      var completeFuture =
          executor.submit(
              () -> {
                start.await();
                return board.complete(
                    W1,
                    finalCompleteEntry.id(),
                    new VersionCommand(finalCompleteEntry.version()),
                    null);
              });
      var moveFuture =
          executor.submit(
              () -> {
                start.await();
                try {
                  return board.move(
                      W1,
                      moveEntry.id(),
                      new MoveEntryRequest(moveEntry.version(), target.id(), 0));
                } catch (RuntimeException conflict) {
                  return conflict;
                }
              });
      var cancelFuture =
          executor.submit(
              () -> {
                start.await();
                return board.cancelTask(
                    W1,
                    cancelExternal,
                    new CancelTaskRequest(cancelVersion, "concurrent cancellation"));
              });
      start.countDown();
      assertThat(completeFuture.get(10, TimeUnit.SECONDS).status()).isEqualTo(EntryStatus.DONE);
      assertThat(cancelFuture.get(10, TimeUnit.SECONDS).status()).isEqualTo(TaskStatus.CANCELLED);
      Object moveResult = moveFuture.get(10, TimeUnit.SECONDS);
      assertThat(moveResult)
          .matches(
              value -> value instanceof TaskBoardSnapshot || value instanceof StaleVersionException);
    }

    assertThat(tasks.findByWarehouseIdAndExternalTaskId(W1, completeExternal).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.DONE);
    assertThat(tasks.findByWarehouseIdAndExternalTaskId(W1, cancelExternal).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.CANCELLED);
    var unfinished =
        jdbc.queryForList(
            """
            select t.external_task_id, e.queue_position
              from queue_entry e
              join board_task t on t.id = e.task_id
             where t.warehouse_id = ?
               and e.status in ('WAITING', 'IN_PROGRESS', 'PAUSED')
            """,
            W1);
    assertThat(unfinished).hasSize(1);
    assertThat(unfinished.getFirst().get("external_task_id")).isEqualTo(moveExternal);
    assertThat(unfinished.getFirst().get("queue_position")).isEqualTo(0);
  }

  @Test
  void cancellationStopsActiveWorkAndIsIdempotentAfterCancellation() {
    var workerClass = registry.createClass(workerClass("CANCEL_WORKER"));
    var queue =
        registry.createQueue(
            W1,
            queue(
                "CANCEL_QUEUE",
                QueueType.MOVEMENT,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Cancel worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Cancel group",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), null, true))));
    UUID externalTaskId = UUID.randomUUID();
    var created =
        board.createTask(W1, externalTask(externalTaskId, queue.id(), "cancel-active"));
    var entry =
        created.columns().stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    entry =
        board.take(
            W1, entry.id(), new TakeEntryRequest(entry.version(), group.id(), worker.id()), null);

    var cancelled =
        board.cancelTask(
            W1,
            externalTaskId,
            new CancelTaskRequest(entry.taskVersion(), "Отмена логистической операции"));

    assertThat(cancelled.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(cancelled.cancelledAt()).isNotNull();
    assertThat(entries.findAllByTaskIdOrderByRouteIndexAsc(cancelled.taskId()))
        .allSatisfy(
            cancelledEntry -> {
              assertThat(cancelledEntry.getStatus()).isEqualTo(EntryStatus.CANCELLED);
              assertThat(cancelledEntry.getActiveStartedAt()).isNull();
              assertThat(cancelledEntry.getDoneAt()).isNotNull();
            });
    assertThat(assignments.findAllByQueueEntryId(entry.id()))
        .extracting(TaskAssignment::getStatus)
        .containsExactly(AssignmentStatus.CANCELLED);
    assertThat(board.history(W1, entry.id()))
        .filteredOn(event -> event.eventType() == TimeEventType.CANCELLED)
        .extracting(TimeEventDto::reason)
        .containsExactly("Отмена логистической операции");

    var repeated =
        board.cancelTask(
            W1,
            externalTaskId,
            new CancelTaskRequest(entry.taskVersion(), "Повторная отмена"));
    assertThat(repeated.taskId()).isEqualTo(cancelled.taskId());
    assertThat(repeated.taskVersion()).isEqualTo(cancelled.taskVersion());
    assertThat(repeated.status()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(timeEvents.countByQueueEntryIdAndEventType(entry.id(), TimeEventType.CANCELLED))
        .isEqualTo(1);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CANCELLED)).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select aggregate_version from outbox_event where event_type=?",
                Long.class,
                TaskBoardEventTypes.BOARD_TASK_CANCELLED))
        .isEqualTo(cancelled.taskVersion());
    assertThat(
            jdbc.queryForObject(
                "select envelope_body::text from outbox_event where event_type=?",
                String.class,
                TaskBoardEventTypes.BOARD_TASK_CANCELLED))
        .doesNotContain("Отмена логистической операции", "Повторная отмена");
    var cancelledEntry = entries.findById(entry.id()).orElseThrow();
    assertThatThrownBy(
            () ->
                board.move(
                    W1,
                    cancelledEntry.getId(),
                    new MoveEntryRequest(cancelledEntry.getVersion(), null, 0)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("отмененный");
  }

  @Test
  void cancellationRejectsStaleActiveAndCompletedTasks() {
    var queue = registry.createQueue(W1, queue("CANCEL_CONFLICT", QueueType.MOVEMENT, List.of()));
    UUID staleExternalId = UUID.randomUUID();
    board.createTask(W1, externalTask(staleExternalId, queue.id(), "stale-cancel"));
    assertThatThrownBy(
            () ->
                board.cancelTask(
                    W1, staleExternalId, new CancelTaskRequest(99L, "Устаревшая команда")))
        .isInstanceOf(StaleVersionException.class);

    var workerClass = registry.createClass(workerClass("DONE_WORKER"));
    var boundQueue =
        registry.createQueue(
            W1,
            queue(
                "DONE_QUEUE",
                QueueType.MOVEMENT,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Done worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    UUID doneExternalId = UUID.randomUUID();
    var entry =
        board.createTask(W1, externalTask(doneExternalId, boundQueue.id(), "done-task"))
            .columns()
            .stream()
            .flatMap(column -> column.entries().stream())
            .filter(candidate -> candidate.externalTaskId().equals(doneExternalId))
            .findFirst()
            .orElseThrow();
    entry =
        board.take(
            W1, entry.id(), new TakeEntryRequest(entry.version(), null, worker.id()), null);
    board.complete(W1, entry.id(), new VersionCommand(entry.version()), null);
    var doneTask = tasks.findByWarehouseIdAndExternalTaskId(W1, doneExternalId).orElseThrow();
    assertThatThrownBy(
            () ->
                board.cancelTask(
                    W1,
                    doneExternalId,
                    new CancelTaskRequest(doneTask.getVersion(), "Поздняя отмена")))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Завершенную задачу");
    var completedEntry = entries.findById(entry.id()).orElseThrow();
    assertThatThrownBy(
            () ->
                board.move(
                    W1,
                    completedEntry.getId(),
                    new MoveEntryRequest(completedEntry.getVersion(), null, 0)))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Завершенный");
  }

  @Test
  void queueWithTaskCannotBeDeletedOrRenamed() {
    var queue = registry.createQueue(W1, queue("REPAIR", QueueType.REPAIR, List.of()));
    board.createTask(W1, task(queue.id(), "task"));
    var current = registry.listQueues(W1).getFirst();
    assertThatThrownBy(() -> registry.deleteQueue(W1, current.id(), current.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(kafkaOutboxCount(TaskBoardEventTypes.BOARD_TASK_CANCELLED)).isZero();
    assertThatThrownBy(
            () ->
                registry.updateQueue(
                    W1,
                    current.id(),
                    new WorkQueueRequest(
                        current.version(),
                        "NEW",
                        "Repair",
                        null,
                        QueueType.REPAIR,
                        true,
                        false,
                        false,
                        null,
                        null,
                        false,
                        List.of())))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void externalPlanReferenceBlocksQueueDeleteUntilReleased() {
    var queue = registry.createQueue(W1, queue("PLAN", QueueType.REPAIR, List.of()));
    var reference =
        registry.registerReference(
            queue.id(), new QueueReferenceRequest(QueueReferenceType.REPAIR_PLAN, "plan-1"));
    assertThatThrownBy(() -> registry.deleteQueue(W1, queue.id(), queue.version()))
        .isInstanceOf(ConflictException.class);
    registry.deleteReference(
        reference.type(), reference.externalReferenceId(), reference.version());
    registry.deleteQueue(W1, queue.id(), queue.version());
    assertThat(registry.listQueues(W1)).isEmpty();
  }

  @Test
  void groupRejectsCrossWarehouseMemberAndWorkerHistoryBlocksDelete() {
    var workerClass = registry.createClass(workerClass("REPAIR"));
    var worker =
        workforce.createWorker(
            W2,
            worker(
                "Worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        new WorkerGroupRequest(
            0L,
            workerClass.id(),
            "Group",
            null,
            true,
            List.of(new GroupMemberRequest(worker.id(), null, true)));
    assertThatThrownBy(() -> workforce.createGroup(W1, group))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(() -> workforce.deleteWorker(W2, worker.id(), worker.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(credentials.deletes).hasValue(0);
  }

  @Test
  void credentialFailureDoesNotRollbackWorker() {
    credentials.failConfigure.set(true);
    var created =
        workforce.createWorker(W1, worker("Worker", "worker.login", "password-123", List.of()));
    assertThat(created.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(workforce.listWorkers(W1)).hasSize(1);
    assertThat(created).extracting(WorkerDto::appLogin).isEqualTo("worker.login");
  }

  @Test
  void loginUpdateIsSagaSafeAndClearPublishesOnlyAfterDisable() throws Exception {
    var created =
        workforce.createWorker(W1, worker("Worker", "old.login", "password-123", List.of()));
    assertThatThrownBy(
            () ->
                workforce.updateWorker(
                    W1,
                    created.id(),
                    workerWithVersion(created.version(), "Worker", "new.login", null)))
        .isInstanceOf(ConflictException.class);
    var unchanged = workforce.listWorkers(W1).getFirst();
    assertThat(unchanged.appLogin()).isEqualTo("old.login");
    assertThat(unchanged.version()).isEqualTo(created.version());

    credentials.failConfigure.set(true);
    var failed =
        workforce.updateWorker(
            W1,
            created.id(),
            workerWithVersion(created.version(), "Worker updated", "new.login", "password-456"));
    assertThat(failed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(failed.appLogin()).isEqualTo("old.login");
    assertThat(credentials.lastConfiguredLogin).hasValue("new.login");

    assertThatThrownBy(
            () ->
                workforce.updateWorker(
                    W1,
                    failed.id(),
                    workerWithVersion(failed.version(), "Worker updated", null, null)))
        .isInstanceOf(ConflictException.class);
    assertThatThrownBy(
            () -> workforce.reconcileDisableCredentials(W1, failed.id(), failed.version()))
        .isInstanceOf(ConflictException.class);
    Thread.sleep(1_250);
    var converged =
        workforce.reconcileDisableCredentials(W1, failed.id(), failed.version());
    assertThat(converged.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(converged.appLogin()).isEqualTo("old.login");

    credentials.failConfigure.set(false);
    credentials.failDisable.set(true);
    assertThatThrownBy(
            () ->
                workforce.updateWorker(
                    W1,
                    converged.id(),
                    workerWithVersion(converged.version(), "Worker updated", null, null)))
        .isInstanceOf(ExternalServiceException.class);
    var clearFailed = workforce.listWorkers(W1).getFirst();
    assertThat(clearFailed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
    assertThat(clearFailed.appLogin()).isEqualTo("old.login");

    credentials.failDisable.set(false);
    assertThatThrownBy(
            () ->
                workforce.updateWorker(
                    W1,
                    clearFailed.id(),
                    workerWithVersion(
                        clearFailed.version(), "Worker updated", null, null)))
        .isInstanceOf(ConflictException.class);
    Thread.sleep(1_250);
    var cleared =
        workforce.reconcileDisableCredentials(
            W1, clearFailed.id(), clearFailed.version());
    assertThat(cleared.credentialStatus()).isEqualTo(CredentialStatus.NOT_CONFIGURED);
    assertThat(cleared.appLogin()).isNull();
  }

  @Test
  void pendingCredentialOperationBlocksMutationsAndReconcileRunsOnlyAfterFailure()
      throws Exception {
    var created =
        workforce.createWorker(W1, worker("Worker", "worker.fenced", "password-123", List.of()));
    credentials.delayReset.set(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reset =
          executor.submit(
              () ->
                  workforce.resetPassword(
                      W1, created.id(), created.version(), "password-reset"));
      assertThat(credentials.resetEntered.await(5, TimeUnit.SECONDS)).isTrue();
      var pending = workforce.listWorkers(W1).getFirst();
      assertThat(pending.credentialStatus()).isEqualTo(CredentialStatus.PENDING);

      assertThatThrownBy(() -> workforce.disableCredentials(W1, created.id(), pending.version()))
          .isInstanceOf(ConflictException.class);
      assertThatThrownBy(
              () ->
                  workforce.updateWorker(
                      W1,
                      created.id(),
                      workerWithVersion(
                          pending.version(), "Cannot mutate", "worker.fenced", null)))
          .isInstanceOf(ConflictException.class);
      Thread.sleep(1_250);
      assertThatThrownBy(
              () ->
                  workforce.reconcileDisableCredentials(W1, created.id(), pending.version()))
          .isInstanceOf(ConflictException.class);
      assertThat(credentials.disables).hasValue(0);

      credentials.releaseReset.countDown();
      var resetResult = reset.get(5, TimeUnit.SECONDS);
      assertThat(resetResult.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
      assertThat(resetResult.appLogin()).isEqualTo("worker.fenced");
      assertThat(credentials.externalCredentialState).hasValue("ACTIVE");

      credentials.failDisable.set(true);
      assertThatThrownBy(
              () ->
                  workforce.disableCredentials(W1, created.id(), resetResult.version()))
          .isInstanceOf(ExternalServiceException.class);
      var failed = workforce.listWorkers(W1).getFirst();
      assertThat(failed.credentialStatus()).isEqualTo(CredentialStatus.ERROR);
      assertThat(credentials.externalCredentialState).hasValue("ACTIVE");

      credentials.failDisable.set(false);
      assertThatThrownBy(
              () ->
                  workforce.reconcileDisableCredentials(
                      W1, created.id(), failed.version()))
          .isInstanceOf(ConflictException.class);
      Thread.sleep(1_250);
      var reconciled =
          workforce.reconcileDisableCredentials(W1, created.id(), failed.version());
      assertThat(reconciled.credentialStatus()).isEqualTo(CredentialStatus.NOT_CONFIGURED);
      assertThat(reconciled.appLogin()).isNull();
      assertThat(credentials.externalCredentialState).hasValue("DISABLED");
    }
  }

  @Test
  void expiredOrphanCredentialOperationCanBeRecoveredAfterSessionLockIsGone() {
    var created =
        workforce.createWorker(W1, worker("Orphan", "worker.orphan", "password-123", List.of()));
    UUID orphanOperationId = UUID.randomUUID();
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_error = null,
               credential_operation_id = ?,
               credential_operation_type = 'RESET',
               credential_operation_started_at = clock_timestamp() + interval '1 hour',
               version = version + 1
         where id = ?
        """,
        orphanOperationId,
        created.id());
    var nonExpired = workforce.listWorkers(W1).getFirst();

    assertThatThrownBy(
            () ->
                workforce.reconcileDisableCredentials(
                    W1, created.id(), nonExpired.version()))
        .isInstanceOf(ConflictException.class);
    assertThat(credentials.disables).hasValue(0);
    jdbc.update(
        "update worker set credential_operation_started_at = clock_timestamp() - interval '1 hour', version = version + 1 where id = ?",
        created.id());
    var orphan = workforce.listWorkers(W1).getFirst();

    var recovered =
        workforce.reconcileDisableCredentials(W1, created.id(), orphan.version());

    assertThat(recovered.credentialStatus()).isEqualTo(CredentialStatus.ACTIVE);
    assertThat(recovered.appLogin()).isEqualTo("worker.orphan");
    assertThat(credentials.externalCredentialState).hasValue("ACTIVE");
    assertThat(credentials.disables).hasValue(0);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from worker where id = ? and credential_operation_id is null and credential_operation_type is null and credential_operation_started_at is null",
                Integer.class,
                created.id()))
        .isEqualTo(1);

    credentials.externalCredentialState.set("DISABLED");
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_operation_id = ?,
               credential_operation_type = 'RESET',
               credential_operation_started_at = clock_timestamp() - interval '1 hour',
               version = version + 1
         where id = ?
        """,
        UUID.randomUUID(),
        created.id());
    var externallyDisabled = workforce.listWorkers(W1).getFirst();

    var disabledRecovery =
        workforce.reconcileDisableCredentials(
            W1, created.id(), externallyDisabled.version());

    assertThat(disabledRecovery.credentialStatus()).isEqualTo(CredentialStatus.NOT_CONFIGURED);
    assertThat(disabledRecovery.appLogin()).isNull();
    assertThat(credentials.disables).hasValue(1);
  }

  @Test
  void legacyPendingCredentialWithoutMetadataIsRecoveredByForcedDisable() {
    var created =
        workforce.createWorker(W1, worker("Legacy", "worker.legacy", "password-123", List.of()));
    jdbc.update(
        """
        update worker
           set credential_status = 'PENDING',
               credential_operation_id = null,
               credential_operation_type = null,
               credential_operation_started_at = null,
               version = version + 1
         where id = ?
        """,
        created.id());
    var legacy = workforce.listWorkers(W1).getFirst();

    var recovered =
        workforce.reconcileDisableCredentials(W1, created.id(), legacy.version());

    assertThat(recovered.credentialStatus()).isEqualTo(CredentialStatus.NOT_CONFIGURED);
    assertThat(recovered.appLogin()).isNull();
    assertThat(credentials.externalCredentialState).hasValue("DISABLED");
    assertThat(credentials.disables).hasValue(1);
  }

  @Test
  void completionIsFencedByOperationIdAndCannotClearReplacementOperation() throws Exception {
    var created =
        workforce.createWorker(W1, worker("Fenced", "worker.operation", "password-123", List.of()));
    credentials.delayReset.set(true);
    try (var executor = Executors.newSingleThreadExecutor()) {
      var reset =
          executor.submit(
              () ->
                  workforce.resetPassword(
                      W1, created.id(), created.version(), "password-reset"));
      assertThat(credentials.resetEntered.await(5, TimeUnit.SECONDS)).isTrue();
      UUID replacementOperationId = UUID.randomUUID();
      jdbc.update(
          "update worker set credential_operation_id = ?, version = version + 1 where id = ?",
          replacementOperationId,
          created.id());

      credentials.releaseReset.countDown();
      var result = reset.get(5, TimeUnit.SECONDS);

      assertThat(result.credentialStatus()).isEqualTo(CredentialStatus.PENDING);
      assertThat(
              jdbc.queryForObject(
                  "select credential_operation_id from worker where id = ?",
                  UUID.class,
                  created.id()))
          .isEqualTo(replacementOperationId);
      assertThat(credentials.externalCredentialState).hasValue("ACTIVE");
    }
  }

  @Test
  void failedDeleteKeepsPublicWorkerUnchangedAndCanBeReconciled() {
    var created = workforce.createWorker(W1, worker("Delete me", null, null, List.of()));
    credentials.failDelete.set(true);
    assertThatThrownBy(() -> workforce.deleteWorker(W1, created.id(), created.version()))
        .isInstanceOf(ExternalServiceException.class);
    var unchanged = workforce.listWorkers(W1).getFirst();
    assertThat(unchanged.version()).isEqualTo(created.version());
    assertThat(unchanged.displayName()).isEqualTo(created.displayName());
    assertThat(unchanged.active()).isEqualTo(created.active());
    assertThat(unchanged.credentialStatus()).isEqualTo(created.credentialStatus());
    assertThat(deletionIntents.findByWorkerId(created.id()).orElseThrow().getStatus())
        .isEqualTo(WorkerDeletionStatus.ERROR);

    credentials.failDelete.set(false);
    workforce.deleteWorker(W1, created.id(), created.version());
    assertThat(workforce.listWorkers(W1)).isEmpty();
    assertThat(deletionIntents.findByWorkerId(created.id())).isEmpty();
  }

  @Test
  void stopOnTakeResumesOnlyItsAutoInterruptedTask() {
    var workerClass = registry.createClass(workerClass("REPAIR"));
    var q1 =
        registry.createQueue(
            W1,
            queue(
                "NORMAL",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), false))));
    var q2 =
        registry.createQueue(
            W1,
            queue(
                "URGENT",
                QueueType.REPAIR,
                List.of(new QueueBindingRequest(workerClass.id(), true))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Worker",
                null,
                null,
                List.of(new QualificationRequest(workerClass.id(), true, null))));
    var group =
        workforce.createGroup(
            W1,
            new WorkerGroupRequest(
                0L,
                workerClass.id(),
                "Group",
                null,
                true,
                List.of(new GroupMemberRequest(worker.id(), null, true))));
    var first =
        board.createTask(W1, task(q1.id(), "first")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .filter(e -> e.title().equals("first"))
            .findFirst()
            .orElseThrow();
    first =
        board.take(
            W1, first.id(), new TakeEntryRequest(first.version(), group.id(), worker.id()), null);
    var second =
        board.createTask(W1, task(q2.id(), "second")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .filter(e -> e.title().equals("second"))
            .findFirst()
            .orElseThrow();
    second =
        board.take(
            W1, second.id(), new TakeEntryRequest(second.version(), group.id(), worker.id()), null);
    assertThat(entry("first").status()).isEqualTo(EntryStatus.PAUSED);
    assertThat(
            timeEvents.countByQueueEntryIdAndEventType(first.id(), TimeEventType.AUTO_INTERRUPTED))
        .isEqualTo(1);
    assertThat(board.history(W1, first.id()))
        .filteredOn(event -> event.eventType() == TimeEventType.AUTO_INTERRUPTED)
        .extracting(TimeEventDto::relatedEntryId)
        .containsExactly(second.id());
    board.complete(W1, second.id(), new VersionCommand(second.version()), null);
    assertThat(entry("first").status()).isEqualTo(EntryStatus.IN_PROGRESS);
    assertThat(board.history(W1, first.id()))
        .filteredOn(event -> event.eventType() == TimeEventType.AUTO_RESUMED)
        .extracting(TimeEventDto::relatedEntryId)
        .containsExactly(second.id());
  }

  @Test
  void directWorkerNeedsMatchingActiveQualification() {
    var required = registry.createClass(workerClass("REQUIRED"));
    var other = registry.createClass(workerClass("OTHER"));
    var queue =
        registry.createQueue(
            W1,
            queue(
                "BOUND", QueueType.REPAIR, List.of(new QueueBindingRequest(required.id(), true))));
    var worker =
        workforce.createWorker(
            W1,
            worker(
                "Worker", null, null, List.of(new QualificationRequest(other.id(), true, null))));
    var entry =
        board.createTask(W1, task(queue.id(), "task")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .findFirst()
            .orElseThrow();
    assertThatThrownBy(
            () ->
                board.take(
                    W1, entry.id(), new TakeEntryRequest(entry.version(), null, worker.id()), null))
        .isInstanceOf(ConflictException.class);
  }

  @Test
  void moveInsertsAtTargetAndNormalizesBothQueues() {
    var q1 = registry.createQueue(W1, queue("ONE", QueueType.REPAIR, List.of()));
    var q2 = registry.createQueue(W1, queue("TWO", QueueType.REPAIR, List.of()));
    var moving =
        board.createTask(W1, task(q1.id(), "moving")).columns().stream()
            .flatMap(c -> c.entries().stream())
            .filter(e -> e.title().equals("moving"))
            .findFirst()
            .orElseThrow();
    board.createTask(W1, task(q1.id(), "left"));
    board.createTask(W1, task(q2.id(), "target"));
    var snapshot = board.move(W1, moving.id(), new MoveEntryRequest(moving.version(), q2.id(), 0));
    var source =
        snapshot.columns().stream()
            .filter(c -> q1.id().equals(c.queueId()))
            .findFirst()
            .orElseThrow();
    var target =
        snapshot.columns().stream()
            .filter(c -> q2.id().equals(c.queueId()))
            .findFirst()
            .orElseThrow();
    assertThat(source.entries()).extracting(BoardEntryDto::queuePosition).containsExactly(0);
    assertThat(target.entries())
        .extracting(BoardEntryDto::title)
        .containsExactly("moving", "target");
    assertThat(target.entries()).extracting(BoardEntryDto::queuePosition).containsExactly(0, 1);
  }

  @Test
  void audiencePropertyAndWorkerScopesAreEnforced() {
    assertThat(resourceServer.getJwt().getAudiences()).containsExactly("rwms-services");
    assertThat(clientProperties.connectTimeout()).isEqualTo(java.time.Duration.ofMillis(250));
    assertThat(clientProperties.readTimeout()).isEqualTo(java.time.Duration.ofMillis(500));
    assertThat(clientProperties.credentialOperationTimeout())
        .isEqualTo(java.time.Duration.ofSeconds(1));
    Jwt workerWithoutScope = token("WORKER", W1, UUID.randomUUID(), "rwms.read");
    assertThatThrownBy(() -> authorizer.requireTaskScope(workerWithoutScope, true))
        .isInstanceOf(AccessDeniedException.class);
    Jwt worker = token("WORKER", W1, UUID.randomUUID(), "worker.tasks");
    authorizer.requireTaskScope(worker, true);
    authorizer.requireWarehouse(worker, W1, AccessLevel.EDIT, true);
    assertThatThrownBy(() -> authorizer.requireWarehouse(worker, W2, AccessLevel.VIEW, true))
        .isInstanceOf(AccessDeniedException.class);
  }

  @Test
  void corsAllowsPanelAndWorkerOriginsButRejectsUnknownOrigin() throws Exception {
    for (String origin : List.of("http://localhost:8080", "http://localhost:8082")) {
      mockMvc
          .perform(
              options("/api/warehouses/{warehouseId}/task-board", W1)
                  .header("Origin", origin)
                  .header("Access-Control-Request-Method", "GET")
                  .header(
                      "Access-Control-Request-Headers",
                      "Authorization, Content-Type, X-Correlation-Id"))
          .andExpect(status().isOk())
          .andExpect(header().string("Access-Control-Allow-Origin", origin))
          .andExpect(
              header().string(
                  "Access-Control-Allow-Headers",
                  org.hamcrest.Matchers.containsString("X-Correlation-Id")));
    }
    mockMvc
        .perform(
            options("/api/warehouses/{warehouseId}/task-board", W1)
                .header("Origin", "https://evil.example")
                .header("Access-Control-Request-Method", "GET"))
        .andExpect(status().isForbidden())
        .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));

    mockMvc
        .perform(
            get("/api/worker-classes")
                .header("Origin", "http://localhost:8080")
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("scope", "rwms.read"))))
        .andExpect(status().isOk())
        .andExpect(
            header().string(
                "Access-Control-Expose-Headers",
                org.hamcrest.Matchers.containsString("X-Correlation-Id")))
        .andExpect(header().exists("X-Correlation-Id"));
  }

  @Test
  void taskBoardApiRequiresAuthenticationOutsideDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/worker-classes")).andExpect(status().isUnauthorized());
  }

  @Test
  void staleControllerMutationReturns409ProblemDetail() throws Exception {
    var workerClass = registry.createClass(workerClass("HTTP"));
    String body =
        """
        {"version":99,"code":"HTTP","name":"HTTP","description":null,"comment":null,"sortOrder":10,"active":true}
        """;
    mockMvc
        .perform(
            put("/api/worker-classes/{id}", workerClass.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            put("/api/worker-classes/{id}", workerClass.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"))
        .andExpect(jsonPath("$.correlation.correlationId").isNotEmpty());
  }

  @Test
  void duplicateRouteControllerMutationReturns409() throws Exception {
    var queue = registry.createQueue(W1, queue("HTTP_ROUTE", QueueType.REPAIR, List.of()));
    String body =
        """
        {"externalTaskId":null,"title":"duplicate","route":[
          {"queueId":"%s","queueCode":null,"taskText":null,"plannedDurationMinutes":null},
          {"queueId":"%s","queueCode":null,"taskText":null,"plannedDurationMinutes":null}
        ]}
        """
            .formatted(queue.id(), queue.id());
    mockMvc
        .perform(
            post("/api/warehouses/{warehouseId}/task-board/tasks", W1)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isConflict());
  }

  @Test
  void cancellationApiRequiresUserWriteAndEditWarehouseAccess() throws Exception {
    var queue = registry.createQueue(W1, queue("HTTP_CANCEL", QueueType.MOVEMENT, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    var entry =
        board.createTask(W1, externalTask(externalTaskId, queue.id(), "http-cancel"))
            .columns()
            .stream()
            .flatMap(column -> column.entries().stream())
            .findFirst()
            .orElseThrow();
    String body =
        """
        {"expectedTaskVersion":%d,"reason":"Отмена оператором"}
        """
            .formatted(entry.taskVersion());
    String path =
        "/api/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel";

    mockMvc
        .perform(post(path, W1, externalTaskId).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "WAREHOUSE_MANAGER")
                                    .claim("scope", "rwms.read")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId", W1.toString(), "level", "EDIT")))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "WAREHOUSE_MANAGER")
                                    .claim("scope", "rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId", W1.toString(), "level", "VIEW")))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "WAREHOUSE_MANAGER")
                                    .claim("scope", "rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId", W1.toString(), "level", "EDIT")))))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isOk());

    var cancelledEntry = entries.findById(entry.id()).orElseThrow();
    mockMvc
        .perform(
            post(
                    "/api/warehouses/{warehouseId}/task-board/entries/{entryId}/move",
                    W1,
                    entry.id())
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedVersion\":"
                        + cancelledEntry.getVersion()
                        + ",\"targetQueueId\":null,\"targetIndex\":0}"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));
  }

  @Test
  void mutableVersionTokensMustBePresentNonNullAndNonNegative() throws Exception {
    var queue = registry.createQueue(W1, queue("HTTP_VERSION_REQUIRED", QueueType.MOVEMENT, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    board.createTask(W1, externalTask(externalTaskId, queue.id(), "version-required"));
    String path =
        "/api/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}/cancel";
    var admin =
        jwt()
            .jwt(
                token ->
                    token
                        .claim("principal_type", "USER")
                        .claim("global_role", "SYSTEM_ADMIN")
                        .claim("scope", "rwms.write"));

    for (String invalidBody :
        List.of(
            "{\"reason\":\"missing\"}",
            "{\"expectedTaskVersion\":null,\"reason\":\"null\"}",
            "{\"expectedTaskVersion\":-1,\"reason\":\"negative\"}")) {
      mockMvc
          .perform(
              post(path, W1, externalTaskId)
                  .with(admin)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(invalidBody))
          .andExpect(status().isBadRequest());
    }
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"missing\"}"))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
        .andExpect(jsonPath("$.code").value("TASK_BOARD_VALIDATION_FAILED"))
        .andExpect(jsonPath("$.violations[0].field").value("expectedTaskVersion"))
        .andExpect(jsonPath("$.correlation.correlationId").isNotEmpty());
    assertThat(tasks.findByExternalTaskId(externalTaskId).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.ACTIVE);

    long currentVersion = board.registration(W1, externalTaskId).taskVersion();
    mockMvc
        .perform(
            post(path, W1, externalTaskId)
                .with(admin)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"expectedTaskVersion\":"
                        + currentVersion
                        + ",\"reason\":\"valid\"}"))
        .andExpect(status().isOk());

    var workerClass = registry.createClass(workerClass("NEGATIVE_QUERY_VERSION"));
    mockMvc
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete(
                    "/api/worker-classes/{id}", workerClass.id())
                .queryParam("expectedVersion", "-1")
                .with(admin))
        .andExpect(status().isBadRequest());
  }

  @Test
  void exactExternalTaskRegistrationApiReturnsCurrentRoute() throws Exception {
    var queue = registry.createQueue(W1, queue("HTTP_LOOKUP", QueueType.REPAIR, List.of()));
    UUID externalTaskId = UUID.randomUUID();
    board.createTask(W1, externalTask(externalTaskId, queue.id(), "http-lookup"));

    mockMvc
        .perform(
            get(
                    "/api/warehouses/{warehouseId}/task-board/tasks/by-external-id/{externalTaskId}",
                    W1,
                    externalTaskId)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "USER")
                                    .claim("global_role", "SYSTEM_ADMIN")
                                    .claim("scope", "rwms.read"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
        .andExpect(jsonPath("$.status").value("ACTIVE"))
        .andExpect(jsonPath("$.route.length()").value(1))
        .andExpect(jsonPath("$.route[0].queueId").value(queue.id().toString()));
  }

  @Test
  void developmentBootstrapIsAbsentOutsideDevelopmentProfile() {
    assertThat(devBootstrap).isNull();
  }

  private long kafkaOutboxCount(String eventType) {
    Long count =
        eventType == null
            ? jdbc.queryForObject("select count(*) from outbox_event", Long.class)
            : jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?", Long.class, eventType);
    return count == null ? 0 : count;
  }

  private BoardEntryDto entry(String title) {
    return board.snapshot(W1, true).columns().stream()
        .flatMap(c -> c.entries().stream())
        .filter(e -> e.title().equals(title))
        .findFirst()
        .orElseThrow();
  }

  private WorkerClassRequest workerClass(String code) {
    return new WorkerClassRequest(0L, code, code, null, null, 10, true);
  }

  private WorkQueueRequest queue(String code, QueueType type, List<QueueBindingRequest> bindings) {
    return new WorkQueueRequest(
        0L,
        code,
        code,
        null,
        type,
        true,
        false,
        false,
        type == QueueType.HOLDING ? 10 : null,
        type == QueueType.HOLDING ? 2 : null,
        false,
        bindings);
  }

  private WorkerRequest worker(
      String name, String login, String password, List<QualificationRequest> q) {
    return new WorkerRequest(0L, name, null, null, null, true, null, login, password, q);
  }

  private WorkerRequest workerWithVersion(
      long version, String name, String login, String password) {
    return new WorkerRequest(
        version, name, null, null, null, true, null, login, password, List.of());
  }

  private CreateBoardTaskRequest task(UUID queue, String title) {
    return new CreateBoardTaskRequest(
        null,
        title,
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(queue, null, null, null)));
  }

  private CreateBoardTaskRequest externalTask(UUID externalTaskId, UUID queue, String title) {
    return new CreateBoardTaskRequest(
        externalTaskId,
        title,
        null,
        null,
        null,
        null,
        List.of(new RouteStepRequest(queue, null, title, null)));
  }

  private List<Object> race(Supplier<Object> firstCommand, Supplier<Object> secondCommand)
      throws Exception {
    CountDownLatch start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                try {
                  return firstCommand.get();
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                try {
                  return secondCommand.get();
                } catch (RuntimeException exception) {
                  return exception;
                }
              });
      start.countDown();
      return List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS));
    }
  }

  private void assertSingleSuccess(List<Object> outcomes) {
    assertThat(outcomes).filteredOn(RuntimeException.class::isInstance).hasSize(1);
    assertThat(outcomes).filteredOn(value -> !(value instanceof RuntimeException)).hasSize(1);
  }

  private Jwt token(String type, UUID warehouse, UUID worker, String scope) {
    return Jwt.withTokenValue("token")
        .header("alg", "none")
        .subject("subject")
        .issuedAt(Instant.now())
        .expiresAt(Instant.now().plusSeconds(60))
        .claim("principal_type", type)
        .claim("warehouse_id", warehouse.toString())
        .claim("worker_id", worker.toString())
        .claim("scope", scope)
        .build();
  }

  @TestConfiguration
  static class TestConfig {
    @Bean
    @Primary
    FakeCredentials fakeCredentials() {
      return new FakeCredentials();
    }
  }

  static class FakeCredentials implements WorkerCredentialGateway {
    final AtomicBoolean failConfigure = new AtomicBoolean();
    final AtomicBoolean failDisable = new AtomicBoolean();
    final AtomicBoolean failDelete = new AtomicBoolean();
    final AtomicInteger deletes = new AtomicInteger();
    final AtomicInteger disables = new AtomicInteger();
    final AtomicReference<String> lastConfiguredLogin = new AtomicReference<>();
    final AtomicReference<String> externalAppLogin = new AtomicReference<>();
    final AtomicReference<String> externalCredentialState = new AtomicReference<>();
    final AtomicBoolean delayReset = new AtomicBoolean();
    CountDownLatch resetEntered = new CountDownLatch(1);
    CountDownLatch releaseReset = new CountDownLatch(1);

    void resetState() {
      failConfigure.set(false);
      failDisable.set(false);
      failDelete.set(false);
      deletes.set(0);
      disables.set(0);
      lastConfiguredLogin.set(null);
      externalAppLogin.set(null);
      externalCredentialState.set(null);
      delayReset.set(false);
      resetEntered = new CountDownLatch(1);
      releaseReset = new CountDownLatch(1);
    }

    public void configure(UUID workerId, UUID warehouseId, String appLogin, String password) {
      lastConfiguredLogin.set(appLogin);
      if (failConfigure.get()) throw new IllegalStateException("auth down");
      externalAppLogin.set(appLogin);
      externalCredentialState.set("ACTIVE");
    }

    public void reset(UUID workerId, String password) {
      if (!delayReset.get()) return;
      resetEntered.countDown();
      try {
        if (!releaseReset.await(5, TimeUnit.SECONDS))
          throw new IllegalStateException("reset timeout");
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("reset interrupted", exception);
      }
      externalCredentialState.set("ACTIVE");
    }

    public void disable(UUID workerId) {
      if (failDisable.get()) throw new IllegalStateException("auth down");
      disables.incrementAndGet();
      externalCredentialState.set("DISABLED");
    }

    public void delete(UUID workerId) {
      deletes.incrementAndGet();
      if (failDelete.get()) throw new IllegalStateException("auth down");
      externalCredentialState.set(null);
    }

    public WorkerCredentialSnapshot status(UUID workerId, UUID expectedWarehouseId) {
      return switch (externalCredentialState.get()) {
        case "ACTIVE" ->
            new WorkerCredentialSnapshot(
                workerId,
                expectedWarehouseId,
                externalAppLogin.get(),
                WorkerCredentialStatus.ACTIVE);
        case "DISABLED" ->
            new WorkerCredentialSnapshot(
                workerId,
                expectedWarehouseId,
                externalAppLogin.get(),
                WorkerCredentialStatus.DISABLED);
        case null ->
            new WorkerCredentialSnapshot(
                workerId, expectedWarehouseId, null, WorkerCredentialStatus.ABSENT);
        default -> throw new IllegalStateException("unknown fake credential state");
      };
    }
  }
}
