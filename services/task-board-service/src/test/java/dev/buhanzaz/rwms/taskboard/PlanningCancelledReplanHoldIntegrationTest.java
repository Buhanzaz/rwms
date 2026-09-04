package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.PlannerMembershipState;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import dev.buhanzaz.rwms.taskboard.service.PlanningReplanHoldService;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.stream.IntStream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

/**
 * PostgreSQL coverage for published-plan holds whose removed member is already authoritatively
 * cancelled, plus the original active-member reschedule path.
 */
@SpringBootTest
@ActiveProfiles("test")
class PlanningCancelledReplanHoldIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000911");
  private static final LocalDate PLAN_DATE = LocalDate.of(2026, 9, 1);
  private static final long SOURCE_PLAN_VERSION = 1L;
  private static final long REPLACEMENT_PLAN_VERSION = 2L;
  private static final DriverTaskAudienceDto DRIVER_AUDIENCE =
      new DriverTaskAudienceDto(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null);

  @Autowired RegistryService registry;
  @Autowired TaskBoardService board;
  @Autowired PlanningReplanHoldService holds;
  @Autowired BoardTaskRepository tasks;
  @Autowired QueueEntryRepository entries;
  @Autowired TaskSyncSourceRepository sources;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    jdbc.update("delete from planning_replan_hold");
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void commitsAlreadyCancelledRemovedMemberWithoutRepeatingCancellation() {
    UUID sourcePlanId = UUID.randomUUID();
    WorkQueueDto queue = createDriverQueue();
    PublishedTask removed = register(sourcePlanId, queue.definitionId(), "Отменённая доставка");
    PublishedTask remaining = register(sourcePlanId, queue.definitionId(), "Оставшаяся доставка");

    var cancelled =
        board.cancelExternalTask(
            "logistics-service",
            removed.externalTaskId(),
            new CancelTaskRequest(
                removed.registration().taskVersion(), "Клиент отменил доставку"));
    BoardTask cancelledTask = tasks.findById(cancelled.taskId()).orElseThrow();
    var cancelledEntry = currentEntry(cancelledTask);
    long cancelledTaskVersion = cancelledTask.getVersion();
    long cancelledEntryVersion = cancelledEntry.getVersion();
    long cancellationEventsBeforeCommit = cancellationEvents(cancelledTask.getId());

    assertThat(cancelledTask.getStatus()).isEqualTo(TaskStatus.CANCELLED);
    assertThat(cancelledTask.getLane()).isEqualTo(TaskLane.SCHEDULED);
    assertThat(cancelledTask.getDoneAt()).isNotNull();
    assertThat(cancelledEntry.getStatus()).isEqualTo(EntryStatus.CANCELLED);
    assertThat(cancelledEntry.getDoneAt()).isNotNull();
    assertThat(cancelledEntry.getActiveStartedAt()).isNull();
    assertThat(cancelledEntry.getPausedAt()).isNull();
    assertThat(cancelledEntry.getActiveWorkSeconds()).isZero();
    assertThat(cancellationEventsBeforeCommit).isOne();

    UUID holdId = UUID.randomUUID();
    PlanningReplanPrepareResponse prepared =
        holds.prepare(
            sourcePlanId,
            holdId,
            prepareRequest(removed, List.of(remaining)));
    PlanningReplanCommitResponse committed = holds.commit(holdId, holdId);
    PlanningReplanCommitResponse replayed = holds.commit(holdId, holdId);

    assertThat(prepared.outcome()).isEqualTo("PREPARED");
    assertThat(committed.outcome()).isEqualTo("APPLIED");
    assertThat(committed.sourcePlanVersion()).isEqualTo(REPLACEMENT_PLAN_VERSION);
    assertThat(committed.removedAssignment().externalTaskId())
        .isEqualTo(removed.externalTaskId());
    assertThat(committed.removedAssignment().status()).isEqualTo("CANCELLED");
    assertThat(committed.removedAssignment().taskVersion()).isEqualTo(cancelledTaskVersion);
    assertThat(committed.remainingAssignments())
        .extracting(PlanningReplacementTaskResult::externalTaskId)
        .containsExactly(remaining.externalTaskId());
    assertThat(replayed.outcome()).isEqualTo("REPLAYED");
    assertThat(replayed.removedAssignment()).isEqualTo(committed.removedAssignment());

    BoardTask removedAfterCommit = tasks.findById(cancelledTask.getId()).orElseThrow();
    var removedEntryAfterCommit = currentEntry(removedAfterCommit);
    assertThat(removedAfterCommit.getVersion()).isEqualTo(cancelledTaskVersion);
    assertThat(removedEntryAfterCommit.getVersion()).isEqualTo(cancelledEntryVersion);
    assertThat(cancellationEvents(removedAfterCommit.getId()))
        .isEqualTo(cancellationEventsBeforeCommit);
    assertTombstone(removed, REPLACEMENT_PLAN_VERSION);
    assertActiveLineage(remaining, REPLACEMENT_PLAN_VERSION);
  }

  @Test
  void activeRemovedMemberStillUsesTheOriginalRescheduleCommitPath() {
    UUID sourcePlanId = UUID.randomUUID();
    WorkQueueDto queue = createDriverQueue();
    PublishedTask removed = register(sourcePlanId, queue.definitionId(), "Переносимая доставка");
    PublishedTask remaining = register(sourcePlanId, queue.definitionId(), "Сохраняемая доставка");
    UUID holdId = UUID.randomUUID();

    PlanningReplanPrepareResponse prepared =
        holds.prepare(
            sourcePlanId,
            holdId,
            prepareRequest(removed, List.of(remaining)));
    PlanningReplanCommitResponse committed = holds.commit(holdId, holdId);

    assertThat(prepared.outcome()).isEqualTo("PREPARED");
    assertThat(committed.outcome()).isEqualTo("APPLIED");
    assertThat(committed.removedAssignment().status()).isEqualTo("CANCELLED");
    assertThat(tasks.findById(removed.registration().taskId()).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.CANCELLED);
    assertThat(cancellationEvents(removed.registration().taskId())).isOne();
    assertTombstone(removed, REPLACEMENT_PLAN_VERSION);
    assertActiveLineage(remaining, REPLACEMENT_PLAN_VERSION);
  }

  private WorkQueueDto createDriverQueue() {
    QueueDefinitionDto definition =
        QueueRegistryTestFixtures.ensureDriverDefinition(
            registry, jdbc, "Водители опубликованного плана", QueueType.MOVEMENT);
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
            null,
            null,
            false,
            1,
            List.of()));
  }

  private PublishedTask register(UUID sourcePlanId, UUID queueDefinitionId, String title) {
    UUID externalTaskId = UUID.randomUUID();
    UUID sourceTaskId = UUID.randomUUID();
    BoardTaskRegistrationDto registration =
        board.registerExternalTask(
            "logistics-service",
            new RegisterExternalTaskRequest(
                WAREHOUSE_ID,
                externalTaskId,
                title,
                null,
                null,
                null,
                null,
                List.of(new RouteStepRequest(queueDefinitionId, title, null)),
                PLAN_DATE,
                3,
                new TaskSourceReferenceDto(TaskSourceType.LOGISTICS_DRIVER_TASK, sourceTaskId),
                TaskLane.SCHEDULED,
                DRIVER_AUDIENCE,
                new PlannerTaskLineageDto(
                    sourcePlanId, SOURCE_PLAN_VERSION, WAREHOUSE_ID, PLAN_DATE)));
    return new PublishedTask(externalTaskId, sourceTaskId, registration);
  }

  private PlanningReplanPrepareRequest prepareRequest(
      PublishedTask removed, List<PublishedTask> remaining) {
    BoardTask removedTask = tasks.findById(removed.registration().taskId()).orElseThrow();
    var removedEntry = currentEntry(removedTask);
    List<PlanningReplacementTaskRequest> remainingAssignments =
        IntStream.range(0, remaining.size())
            .mapToObj(
                index -> {
                  PublishedTask item = remaining.get(index);
                  BoardTask task = tasks.findById(item.registration().taskId()).orElseThrow();
                  var entry = currentEntry(task);
                  return new PlanningReplacementTaskRequest(
                      item.externalTaskId(),
                      item.sourceTaskId(),
                      WAREHOUSE_ID,
                      PLAN_DATE,
                      task.getVersion(),
                      entry.getVersion(),
                      index,
                      DRIVER_AUDIENCE);
                })
            .toList();
    return new PlanningReplanPrepareRequest(
        WAREHOUSE_ID,
        PLAN_DATE,
        SOURCE_PLAN_VERSION,
        REPLACEMENT_PLAN_VERSION,
        new PlanningRemovedTaskRequest(
            removed.externalTaskId(),
            removed.sourceTaskId(),
            WAREHOUSE_ID,
            PLAN_DATE,
            removedTask.getVersion(),
            removedEntry.getVersion()),
        remainingAssignments,
        List.of());
  }

  private dev.buhanzaz.rwms.taskboard.domain.QueueEntry currentEntry(BoardTask task) {
    return entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).getFirst();
  }

  private long cancellationEvents(UUID taskId) {
    return jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='BOARD_TASK' and aggregate_id=? and event_type=?",
        Long.class,
        taskId.toString(),
        TaskBoardEventTypes.BOARD_TASK_CANCELLED);
  }

  private void assertTombstone(PublishedTask task, long removedVersion) {
    var source = sources.findById(task.registration().taskId()).orElseThrow();
    assertThat(source.getPlannerMembershipState()).isEqualTo(PlannerMembershipState.REMOVED);
    assertThat(source.getSourcePlanVersion()).isEqualTo(SOURCE_PLAN_VERSION);
    assertThat(source.getRemovedSourcePlanVersion()).isEqualTo(removedVersion);
    assertThat(source.getRemovedAt()).isNotNull();
  }

  private void assertActiveLineage(PublishedTask task, long sourcePlanVersion) {
    var source = sources.findById(task.registration().taskId()).orElseThrow();
    assertThat(source.getPlannerMembershipState()).isEqualTo(PlannerMembershipState.ACTIVE);
    assertThat(source.getSourcePlanVersion()).isEqualTo(sourcePlanVersion);
    assertThat(source.getRemovedSourcePlanVersion()).isNull();
    assertThat(source.getRemovedAt()).isNull();
  }

  /** Exact source and task-board identities for one registered published-plan member. */
  private record PublishedTask(
      UUID externalTaskId, UUID sourceTaskId, BoardTaskRegistrationDto registration) {}
}
