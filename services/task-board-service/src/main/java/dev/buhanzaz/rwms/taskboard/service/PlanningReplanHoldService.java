package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.PlanningReplacementApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.EntryStatus;
import dev.buhanzaz.rwms.taskboard.domain.EntryType;
import dev.buhanzaz.rwms.taskboard.domain.PlanningReplanHold;
import dev.buhanzaz.rwms.taskboard.domain.PlanningReplanHoldState;
import dev.buhanzaz.rwms.taskboard.domain.QueueEntry;
import dev.buhanzaz.rwms.taskboard.domain.QueuePurpose;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.domain.TaskSyncSource;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.PlanningReplanHoldRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskAssignmentRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Owns the durable task-board half of a published-plan recovery. PREPARE proves that an exact
 * lineage is still safe to replace and blocks worker execution; COMMIT performs the tombstone and
 * complete remaining-plan replacement in one transaction; RELEASE is legal only before the
 * logistics owner mutation commits. The removed member may already carry the owner's authoritative
 * pre-start cancellation, while every remaining member must still be active and waiting.
 */
@Service
public class PlanningReplanHoldService {
  private static final String LOGISTICS_SERVICE = "logistics-service";

  private final PlanningReplanHoldRepository holds;
  private final TaskSyncSourceRepository sources;
  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final TaskAssignmentRepository assignments;
  private final TaskBoardExternalMutationService taskMutations;
  private final DriverShiftService shifts;
  private final ObjectMapper objectMapper;
  private final Clock clock;

  /** Creates the hold owner with a replaceable UTC clock for deterministic integration tests. */
  public PlanningReplanHoldService(
      PlanningReplanHoldRepository holds,
      TaskSyncSourceRepository sources,
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      TaskAssignmentRepository assignments,
      TaskBoardExternalMutationService taskMutations,
      DriverShiftService shifts,
      ObjectMapper objectMapper,
      ObjectProvider<Clock> clocks) {
    this.holds = holds;
    this.sources = sources;
    this.tasks = tasks;
    this.entries = entries;
    this.assignments = assignments;
    this.taskMutations = taskMutations;
    this.shifts = shifts;
    this.objectMapper = objectMapper;
    this.clock = clocks.getIfAvailable(Clock::systemUTC);
  }

  /** Locks an exact, complete source plan and creates or replays its execution hold. */
  @Transactional
  public PlanningReplanPrepareResponse prepare(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplanPrepareRequest request) {
    requireCommand(sourcePlanId, idempotencyKey, request);
    String fingerprint = fingerprint(sourcePlanId, request);
    PlanningReplanHold existing = holds.findByIdempotencyKeyForUpdate(idempotencyKey).orElse(null);
    if (existing != null) return replayPrepare(existing, sourcePlanId, request, fingerprint);

    List<TaskSyncSource> membership = lockMembership(sourcePlanId, request);
    existing = holds.findByIdempotencyKeyForUpdate(idempotencyKey).orElse(null);
    if (existing != null) return replayPrepare(existing, sourcePlanId, request, fingerprint);
    PlanningReplanHold occupied =
        holds
            .findRevisionForUpdate(sourcePlanId, request.replacementPlanVersion())
            .orElse(null);
    if (occupied != null) {
      throw new ConflictException("Эта версия плана уже участвует в другом переносе");
    }

    requireTaskFences(request, membership);
    shifts.requirePlansReplaceableAfterTaskRemoval(
        sourcePlanId,
        request.expectedSourcePlanVersion(),
        request.replacementPlanVersion(),
        request.warehouseId(),
        request.date(),
        request.driverShiftPlans());
    PlanningReplanHold hold =
        PlanningReplanHold.prepare(
            idempotencyKey,
            idempotencyKey,
            sourcePlanId,
            request.expectedSourcePlanVersion(),
            request.replacementPlanVersion(),
            request.warehouseId(),
            request.date(),
            request.removedAssignment().externalTaskId(),
            fingerprint,
            encode(request),
            OffsetDateTime.now(clock));
    holds.saveAndFlush(hold);
    return prepareResponse("PREPARED", hold);
  }

  /**
   * Commits the exact held revision. A retry after a successful commit returns the stored
   * authoritative result and never repeats task-board mutation.
   */
  @Transactional
  public PlanningReplanCommitResponse commit(UUID holdId, UUID idempotencyKey) {
    PlanningReplanHold hold = requireHoldIdentity(holdId, idempotencyKey);
    if (hold.getState() == PlanningReplanHoldState.COMMITTED) {
      return replayCommit(hold);
    }
    if (hold.getState() != PlanningReplanHoldState.PREPARED) {
      throw new ConflictException("Перенос уже освобождён и не может быть применён");
    }

    PlanningReplanPrepareRequest request = decodeRequest(hold.getRequestJson());
    List<TaskSyncSource> membership = lockMembership(hold.getSourcePlanId(), request);
    LockedTaskSet locked = requireTaskFences(request, membership);
    shifts.requirePlansReplaceableAfterTaskRemoval(
        hold.getSourcePlanId(),
        request.expectedSourcePlanVersion(),
        request.replacementPlanVersion(),
        request.warehouseId(),
        request.date(),
        request.driverShiftPlans());

    BoardTask removedTask = locked.tasksByExternalId().get(request.removedAssignment().externalTaskId());
    var cancelled =
        taskMutations.cancelOwnedExternalTask(
            LOGISTICS_SERVICE,
            removedTask.getExternalTaskId(),
            new CancelTaskRequest(
                removedTask.getVersion(),
                "Согласованный перенос клиентской доставки на другую дату"));
    List<PlanningReplacementTaskResult> remaining =
        request.remainingAssignments().isEmpty()
            ? List.of()
            : taskMutations.replacePlannerTasksAfterSiblingRemoval(
                request.date(), request.remainingAssignments());
    List<PlanningReplacementShiftResult> shiftResults =
        shifts.replacePlansAfterTaskRemoval(
            hold.getSourcePlanId(),
            request.expectedSourcePlanVersion(),
            request.replacementPlanVersion(),
            request.warehouseId(),
            request.date(),
            request.driverShiftPlans());

    OffsetDateTime now = OffsetDateTime.now(clock);
    for (TaskSyncSource source : membership) {
      if (source.getExternalTaskId().equals(request.removedAssignment().externalTaskId())) {
        source.removeFromSourcePlan(
            request.expectedSourcePlanVersion(), request.replacementPlanVersion(), now);
      } else {
        source.advanceSourcePlan(
            request.expectedSourcePlanVersion(), request.replacementPlanVersion());
      }
    }
    sources.saveAllAndFlush(membership);

    PlanningReplanCommitResponse response =
        new PlanningReplanCommitResponse(
            "APPLIED",
            holdId,
            hold.getSourcePlanId(),
            request.replacementPlanVersion(),
            new PlanningRemovedTaskResult(
                cancelled.externalTaskId(), cancelled.taskVersion(), cancelled.status().name()),
            remaining,
            shiftResults);
    hold.commit(encode(response), now);
    holds.saveAndFlush(hold);
    return response;
  }

  /** Releases an uncommitted hold and leaves the original source plan completely unchanged. */
  @Transactional
  public PlanningReplanReleaseResponse release(UUID holdId, UUID idempotencyKey) {
    PlanningReplanHold hold = requireHoldIdentity(holdId, idempotencyKey);
    if (hold.getState() == PlanningReplanHoldState.COMMITTED) {
      throw new ConflictException("Применённый перенос нельзя освободить");
    }
    boolean replay = hold.getState() == PlanningReplanHoldState.RELEASED;
    if (!replay) {
      hold.release(OffsetDateTime.now(clock));
      holds.saveAndFlush(hold);
    }
    return new PlanningReplanReleaseResponse(
        replay ? "REPLAYED" : "RELEASED", holdId, hold.getSourcePlanId());
  }

  private PlanningReplanHold requireHoldIdentity(UUID holdId, UUID idempotencyKey) {
    if (holdId == null || idempotencyKey == null || !holdId.equals(idempotencyKey)) {
      throw new IllegalArgumentException("Idempotency-Key должен совпадать с идентификатором hold");
    }
    PlanningReplanHold hold =
        holds
            .findForUpdate(holdId)
            .orElseThrow(() -> new NotFoundException("Блокировка перепланирования не найдена"));
    if (!idempotencyKey.equals(hold.getIdempotencyKey())) {
      throw new ConflictException("Idempotency-Key не относится к этой блокировке");
    }
    return hold;
  }

  private List<TaskSyncSource> lockMembership(
      UUID sourcePlanId, PlanningReplanPrepareRequest request) {
    List<TaskSyncSource> membership =
        sources.findAllBySourcePlanIdForUpdate(LOGISTICS_SERVICE, sourcePlanId);
    if (membership.isEmpty()) {
      throw new ConflictException("Опубликованный план не найден или уже изменён");
    }
    Map<UUID, TaskRequestFence> requested = requestFences(request);
    Set<UUID> persisted =
        membership.stream()
            .map(TaskSyncSource::getExternalTaskId)
            .collect(Collectors.toUnmodifiableSet());
    if (!persisted.equals(requested.keySet())) {
      throw new ConflictException("Состав опубликованного плана изменился");
    }
    for (TaskSyncSource source : membership) {
      TaskRequestFence item = requested.get(source.getExternalTaskId());
      if (!sourcePlanId.equals(source.getSourcePlanId())
          || source.getSourcePlanVersion() == null
          || source.getSourcePlanVersion() != request.expectedSourcePlanVersion()
          || !request.warehouseId().equals(source.getSourcePlanWarehouseId())
          || !request.date().equals(source.getSourcePlanDate())
          || source.getSourceType() != TaskSourceType.LOGISTICS_DRIVER_TASK
          || !item.sourceTaskId().equals(source.getSourceId())) {
        throw new ConflictException("Версия или принадлежность опубликованного плана изменилась");
      }
    }
    return membership;
  }

  private LockedTaskSet requireTaskFences(
      PlanningReplanPrepareRequest request, List<TaskSyncSource> membership) {
    Map<UUID, TaskRequestFence> requested = requestFences(request);
    List<UUID> externalIds = requested.keySet().stream().sorted().toList();
    List<BoardTask> lockedTasks = tasks.findAllByExternalTaskIdInForUpdate(externalIds);
    if (lockedTasks.size() != requested.size()) {
      throw new ConflictException("Задания опубликованного плана изменились");
    }
    Map<UUID, BoardTask> tasksByExternalId =
        lockedTasks.stream()
            .collect(
                Collectors.toMap(
                    BoardTask::getExternalTaskId, Function.identity(), (left, right) -> left,
                    LinkedHashMap::new));
    Map<UUID, TaskSyncSource> sourceByExternal =
        membership.stream()
            .collect(Collectors.toMap(TaskSyncSource::getExternalTaskId, Function.identity()));
    List<QueueEntry> lockedEntries =
        entries.findAllByTaskIdInForUpdate(lockedTasks.stream().map(BoardTask::getId).toList());
    Map<UUID, List<QueueEntry>> entriesByTask =
        lockedEntries.stream().collect(Collectors.groupingBy(entry -> entry.getTask().getId()));
    if (assignments.existsByQueueEntryIdIn(lockedEntries.stream().map(QueueEntry::getId).toList())) {
      throw new ConflictException("Назначенное или начатое задание нельзя переносить");
    }

    Set<String> targetPositions = new HashSet<>();
    for (Map.Entry<UUID, TaskRequestFence> requestedEntry : requested.entrySet()) {
      TaskRequestFence item = requestedEntry.getValue();
      boolean removed =
          requestedEntry
              .getKey()
              .equals(request.removedAssignment().externalTaskId());
      BoardTask task = tasksByExternalId.get(requestedEntry.getKey());
      TaskSyncSource source = sourceByExternal.get(requestedEntry.getKey());
      List<QueueEntry> route =
          task == null ? List.of() : entriesByTask.getOrDefault(task.getId(), List.of());
      if (task == null
          || source == null
          || !source.getBoardTaskId().equals(task.getId())
          || task.getVersion() != item.expectedTaskVersion()
          || !hasReplaceableTaskState(task, removed)
          || task.getLane() != TaskLane.SCHEDULED
          || !task.getWarehouseId().equals(item.taskWarehouseId())
          || !request.date().equals(task.getScheduledDate())
          || !request.date().equals(item.scheduledDate())
          || route.size() != 1) {
        throw new ConflictException("Задание уже изменилось или больше не ожидает рейс");
      }
      QueueEntry entry = route.getFirst();
      if (entry.getVersion() != item.expectedEntryVersion()
          || entry.getEntryType() != EntryType.REAL
          || entry.getQueue() == null
          || entry.getQueue().getPurpose() != QueuePurpose.LOGISTICS_DRIVER
          || !hasReplaceableEntryState(entry, removed)
          || entry.getActiveStartedAt() != null
          || entry.getPausedAt() != null
          || entry.getActiveWorkSeconds() != 0) {
        throw new ConflictException("Маршрут задания уже начат или изменён");
      }
      if (item.targetQueuePosition() != null) {
        String position =
            task.getWarehouseId() + ":" + entry.getQueue().getId() + ":" + item.targetQueuePosition();
        if (!targetPositions.add(position)) {
          throw new IllegalArgumentException("Позиции оставшихся заданий должны быть уникальны");
        }
      }
    }
    return new LockedTaskSet(tasksByExternalId);
  }

  private static boolean hasReplaceableTaskState(BoardTask task, boolean removed) {
    if (task.getStatus() == TaskStatus.ACTIVE) {
      return task.getDoneAt() == null;
    }
    return removed
        && task.getStatus() == TaskStatus.CANCELLED
        && task.getDoneAt() != null;
  }

  private static boolean hasReplaceableEntryState(QueueEntry entry, boolean removed) {
    if (entry.getStatus() == EntryStatus.WAITING) {
      return entry.getDoneAt() == null;
    }
    return removed
        && entry.getStatus() == EntryStatus.CANCELLED
        && entry.getDoneAt() != null;
  }

  private Map<UUID, TaskRequestFence> requestFences(PlanningReplanPrepareRequest request) {
    List<TaskRequestFence> values = new ArrayList<>(request.remainingAssignments().size() + 1);
    PlanningRemovedTaskRequest removed = request.removedAssignment();
    values.add(
        new TaskRequestFence(
            removed.externalTaskId(),
            removed.sourceTaskId(),
            removed.taskWarehouseId(),
            removed.scheduledDate(),
            removed.expectedTaskVersion(),
            removed.expectedEntryVersion(),
            null));
    request.remainingAssignments().stream()
        .map(
            item ->
                new TaskRequestFence(
                    item.externalTaskId(),
                    item.sourceTaskId(),
                    item.taskWarehouseId(),
                    item.scheduledDate(),
                    item.expectedTaskVersion(),
                    item.expectedEntryVersion(),
                    item.targetQueuePosition()))
        .forEach(values::add);
    try {
      return values.stream()
          .collect(Collectors.toMap(TaskRequestFence::externalTaskId, Function.identity()));
    } catch (IllegalStateException duplicate) {
      throw new IllegalArgumentException("Опубликованный план содержит повторяющиеся задания", duplicate);
    }
  }

  private static void requireCommand(
      UUID sourcePlanId, UUID idempotencyKey, PlanningReplanPrepareRequest request) {
    if (sourcePlanId == null || idempotencyKey == null || request == null) {
      throw new IllegalArgumentException("Идентификаторы перепланирования обязательны");
    }
    if (request.expectedSourcePlanVersion() == null
        || request.replacementPlanVersion() == null
        || request.replacementPlanVersion() <= request.expectedSourcePlanVersion()) {
      throw new IllegalArgumentException("Новая версия плана должна быть строго больше текущей");
    }
  }

  private PlanningReplanPrepareResponse replayPrepare(
      PlanningReplanHold hold,
      UUID sourcePlanId,
      PlanningReplanPrepareRequest request,
      String fingerprint) {
    if (!sourcePlanId.equals(hold.getSourcePlanId())
        || hold.getExpectedSourcePlanVersion() != request.expectedSourcePlanVersion()
        || hold.getReplacementPlanVersion() != request.replacementPlanVersion()
        || !request.warehouseId().equals(hold.getSourcePlanWarehouseId())
        || !request.date().equals(hold.getSourcePlanDate())
        || !request.removedAssignment().externalTaskId().equals(hold.getRemovedExternalTaskId())
        || !fingerprint.equals(hold.getRequestSha256())) {
      throw new ConflictException("Idempotency-Key уже использован для другого переноса");
    }
    if (hold.getState() == PlanningReplanHoldState.RELEASED) {
      throw new ConflictException("Ранее освобождённую блокировку нельзя подготовить повторно");
    }
    return prepareResponse("REPLAYED", hold);
  }

  private PlanningReplanPrepareResponse prepareResponse(String outcome, PlanningReplanHold hold) {
    return new PlanningReplanPrepareResponse(
        outcome,
        hold.getId(),
        hold.getSourcePlanId(),
        hold.getExpectedSourcePlanVersion(),
        hold.getRemovedExternalTaskId());
  }

  private PlanningReplanCommitResponse replayCommit(PlanningReplanHold hold) {
    PlanningReplanCommitResponse applied = decodeResponse(hold.getCommitResponseJson());
    return new PlanningReplanCommitResponse(
        "REPLAYED",
        applied.holdId(),
        applied.sourcePlanId(),
        applied.sourcePlanVersion(),
        applied.removedAssignment(),
        applied.remainingAssignments(),
        applied.driverShiftPlans());
  }

  private String fingerprint(UUID sourcePlanId, PlanningReplanPrepareRequest request) {
    try {
      return TaskBoardEventStore.sha256(
          objectMapper.writeValueAsBytes(Map.of("sourcePlanId", sourcePlanId, "request", request)));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Запрос перепланирования нельзя сериализовать", exception);
    }
  }

  private String encode(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Состояние перепланирования нельзя сериализовать", exception);
    }
  }

  private PlanningReplanPrepareRequest decodeRequest(String json) {
    try {
      return objectMapper.readValue(json, PlanningReplanPrepareRequest.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый запрос перепланирования повреждён", exception);
    }
  }

  private PlanningReplanCommitResponse decodeResponse(String json) {
    try {
      return objectMapper.readValue(json, PlanningReplanCommitResponse.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый результат перепланирования повреждён", exception);
    }
  }

  /** Normalized version fence shared by removed and remaining assignments. */
  private record TaskRequestFence(
      UUID externalTaskId,
      UUID sourceTaskId,
      UUID taskWarehouseId,
      java.time.LocalDate scheduledDate,
      long expectedTaskVersion,
      long expectedEntryVersion,
      Integer targetQueuePosition) {}

  /** Locked task identities retained across the single commit transaction. */
  private record LockedTaskSet(Map<UUID, BoardTask> tasksByExternalId) {
    private LockedTaskSet {
      tasksByExternalId = Map.copyOf(Objects.requireNonNull(tasksByExternalId));
    }
  }
}
