package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.*;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionPermit;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Applies source-authorized mutations to already registered task-board work.
 *
 * <p>Lane changes, pre-start compensation, route replacement and relocation share source ownership
 * and version fences, while the narrow queue-position coordinator handles only persisted ordering.
 */
@Service
class TaskBoardExternalMutationService {
  private static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final WorkQueueRepository queues;
  private final RegistryService registry;
  private final TaskSyncSourceRepository taskSyncSources;
  private final TaskBoardRoutePayloadCodec routePayloads;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final GroupKpiEvidenceService kpiEvidence;
  private final TaskBoardWorkerExecutionService workerExecutions;
  private final WarehouseLifecycleFence warehouseLifecycleFence;
  private final TransactionTemplate lifecycleMutations;
  private final TaskBoardQueuePositionCoordinator queuePositions;
  private final DriverTaskAudienceService driverAudiences;
  private final WorkerInvalidationHub workerInvalidations;

  TaskBoardExternalMutationService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      WorkQueueRepository queues,
      RegistryService registry,
      TaskSyncSourceRepository taskSyncSources,
      TaskBoardRoutePayloadCodec routePayloads,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      GroupKpiEvidenceService kpiEvidence,
      TaskBoardWorkerExecutionService workerExecutions,
      WarehouseLifecycleFence warehouseLifecycleFence,
      PlatformTransactionManager transactionManager,
      TaskBoardQueuePositionCoordinator queuePositions,
      DriverTaskAudienceService driverAudiences,
      WorkerInvalidationHub workerInvalidations) {
    this.tasks = tasks;
    this.entries = entries;
    this.queues = queues;
    this.registry = registry;
    this.taskSyncSources = taskSyncSources;
    this.routePayloads = routePayloads;
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.kpiEvidence = kpiEvidence;
    this.workerExecutions = workerExecutions;
    this.warehouseLifecycleFence = warehouseLifecycleFence;
    this.lifecycleMutations = new TransactionTemplate(transactionManager);
    this.queuePositions = queuePositions;
    this.driverAudiences = driverAudiences;
    this.workerInvalidations = workerInvalidations;
  }

  BoardTask requireOwnedExternalTask(String sourceClientId, UUID externalTaskId) {
    return ownedExternalTask(sourceClientId, externalTaskId);
  }

  CancelledTaskDto cancelOwnedExternalTask(
      String sourceClientId, UUID externalTaskId, CancelTaskRequest request) {
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    return workerExecutions.cancelTask(task.getWarehouseId(), externalTaskId, request);
  }

  public BoardTaskRegistrationDto setExternalTaskLane(
      String sourceClientId, UUID externalTaskId, SetTaskLaneRequest request) {
    if (!LOGISTICS_SOURCE_CLIENT_ID.equals(sourceClientId)) {
      throw new NotFoundException("Задача не найдена");
    }
    lock("external-task:" + externalTaskId);
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    TaskSyncSource source =
        taskSyncSources
            .findById(task.getId())
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!source.hasSourceReference(
        TaskSourceType.LOGISTICS_DRIVER_TASK, source.getSourceId())) {
      throw new NotFoundException("Задача не найдена");
    }
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    List<QueueEntry> route = entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId());
    if (route.stream()
        .anyMatch(
            entry ->
                entry.getQueue().getPurpose() != QueuePurpose.LOGISTICS_DRIVER)) {
      throw new ConflictException("Задача не относится к очереди водителей");
    }
    if (task.getStatus() != TaskStatus.ACTIVE
        || route.stream()
            .anyMatch(
                entry ->
                    entry.getStatus() == EntryStatus.IN_PROGRESS
                        || entry.getStatus() == EntryStatus.PAUSED)) {
      throw new ConflictException("Задание в работе нельзя переносить между колонками");
    }
    if (task.getLane() == request.lane()) {
      return registrationDto(task);
    }
    queuePositions.lockQueueMutation(task.getWarehouseId());
    UUID taskWarehouseId = task.getWarehouseId();
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>(route);
    route.stream()
        .map(QueueEntry::getQueue)
        .filter(Objects::nonNull)
        .distinct()
        .forEach(
            queue -> positionCandidates.addAll(queuePositions.orderedEntries(taskWarehouseId, queue)));
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        queuePositions.lockTaskAndEntryStreams(task, positionCandidates);
    task.setLane(request.lane());
    task = projectionWriter.saveAndFlush(tasks, task);
    if (request.lane() == TaskLane.CURRENT) {
      int nextPosition =
          entries.findAllByQueueIdOrderByQueuePositionAsc(route.getFirst().getQueue().getId()).stream()
              .filter(value -> value.getTask().getLane() == TaskLane.CURRENT)
              .filter(value -> UNFINISHED.contains(value.getStatus()))
              .mapToInt(QueueEntry::getQueuePosition)
              .max()
              .orElse(-1)
          + 1;
      for (QueueEntry value : route) {
        value.setQueuePosition(nextPosition++);
        projectionWriter.save(entries, value);
      }
    }
    queuePositions.normalizeCurrentLogisticsPositions(taskWarehouseId, route);
    projectionWriter.flush();
    for (UUID changedId : queuePositions.changedPositionIds(positionCandidates, positionsBefore)) {
      QueueEntry changed =
          positionCandidates.stream()
              .filter(value -> value.getId().equals(changedId))
              .findFirst()
              .orElseThrow();
      eventSourcing.entryChanged(
          changed,
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, changed.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
    }
    eventSourcing.taskChanged(
        task,
        queuePositions.streamVersion(
            streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
    return registrationDto(task);
  }

  public BoardTaskRegistrationDto moveExternalLogisticsTask(
      UUID externalTaskId, MoveExternalLogisticsTaskRequest request) {
    lock("external-task:" + externalTaskId);
    BoardTask task = ownedExternalTask(LOGISTICS_SOURCE_CLIENT_ID, externalTaskId);
    TaskSyncSource source =
        taskSyncSources
            .findById(task.getId())
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!source.hasSourceReference(
        TaskSourceType.LOGISTICS_DRIVER_TASK, source.getSourceId())) {
      throw new NotFoundException("Задача не найдена");
    }
    List<QueueEntry> route =
        entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
            .filter(entry -> entry.getEntryType() == EntryType.REAL)
            .toList();
    if (route.size() != 1
        || route.getFirst().getQueue().getPurpose() != QueuePurpose.LOGISTICS_DRIVER) {
      throw new ConflictException("Задача не относится к очереди водителей");
    }
    QueueEntry entry = route.getFirst();
    BoardTaskRegistrationDto result;
    if (request.targetLane() == TaskLane.CURRENT) {
      result = moveExternalLogisticsTaskToCurrent(task, entry, request);
    } else {
      move(
          task.getWarehouseId(),
          entry.getId(),
          new MoveEntryRequest(
              request.expectedEntryVersion(),
              request.expectedTaskVersion(),
              entry.getQueue().getId(),
              request.targetIndex(),
              request.targetDate()),
          true,
          request.targetDriverAudience());
      result = registrationDto(task);
    }
    publishWorkerFeedChangedAfterCommit();
    return result;
  }

  private BoardTaskRegistrationDto moveExternalLogisticsTaskToCurrent(
      BoardTask task,
      QueueEntry entry,
      MoveExternalLogisticsTaskRequest request) {
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    checkVersion(entry.getVersion(), request.expectedEntryVersion(), "Этап");
    if (task.getStatus() != TaskStatus.ACTIVE
        || entry.getStatus() != EntryStatus.WAITING) {
      throw new ConflictException("Задание в работе нельзя переставлять");
    }
    UUID warehouseId = task.getWarehouseId();
    WorkQueue queue = entry.getQueue();
    queuePositions.lockQueueMutation(warehouseId);
    queuePositions.lockQueuePositions(warehouseId, List.of(queue));
    Set<QueueEntry> positionCandidates =
        new LinkedHashSet<>(queuePositions.orderedEntries(warehouseId, queue));
    positionCandidates.add(entry);
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    Map<TaskBoardQueuePositionCoordinator.QueuePositionScope, List<TaskBoardQueuePositionCoordinator.PinnedQueueOrdinal>> pinnedOrdinals =
        queuePositions.pinnedQueueOrdinals(positionCandidates);
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        queuePositions.lockTaskAndEntryStreams(task, positionCandidates);
    boolean laneChanged = task.getLane() != TaskLane.CURRENT;
    DriverTaskAudienceDto audienceBefore = driverAudiences.dto(task);
    driverAudiences.replace(task, queue, request.targetDriverAudience());
    boolean audienceChanged = !Objects.equals(audienceBefore, driverAudiences.dto(task));
    if (laneChanged || audienceChanged) {
      task.setLane(TaskLane.CURRENT);
      task = projectionWriter.saveAndFlush(tasks, task);
    }
    List<QueueEntry> current =
        positionCandidates.stream()
            .filter(candidate -> !candidate.equals(entry))
            .filter(candidate -> candidate.getTask().getLane() == TaskLane.CURRENT)
            .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
            .sorted(
                Comparator.comparingInt(QueueEntry::getQueuePosition)
                    .thenComparing(candidate -> candidate.getId().toString()))
            .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    int protectedPrefix = 0;
    for (int index = 0; index < current.size(); index++) {
      QueueEntry candidate = current.get(index);
      if (candidate.getStatus() == EntryStatus.IN_PROGRESS
          || candidate.getStatus() == EntryStatus.PAUSED) {
        protectedPrefix = index + 1;
      }
    }
    int targetIndex =
        Math.max(protectedPrefix, Math.min(request.targetIndex(), current.size()));
    current.add(targetIndex, entry);
    int position = 0;
    for (QueueEntry candidate : current) {
      candidate.setQueuePosition(position++);
      projectionWriter.save(entries, candidate);
    }
    queuePositions.normalizePositions(warehouseId, queue);
    projectionWriter.flush();
    queuePositions.restorePinnedQueueOrdinals(pinnedOrdinals);
    projectionWriter.flush();
    Set<UUID> changedIds = queuePositions.changedPositionIds(positionCandidates, positionsBefore);
    if (laneChanged) changedIds.add(entry.getId());
    for (QueueEntry candidate : positionCandidates) {
      if (!changedIds.contains(candidate.getId())) continue;
      eventSourcing.entryChanged(
          candidate,
          queuePositions.streamVersion(
              streamVersions,
              TaskBoardAggregateType.QUEUE_ENTRY,
              candidate.getId()),
          candidate.equals(entry)
              ? TaskBoardEventTypes.QUEUE_ENTRY_MOVED
              : TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
    }
    if (laneChanged || audienceChanged) {
      eventSourcing.taskChanged(
          task,
          queuePositions.streamVersion(
              streamVersions,
              TaskBoardAggregateType.BOARD_TASK,
              task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    return registrationDto(task);
  }

  public CancelledTaskDto cancelExternalTask(
      String sourceClientId, UUID externalTaskId, CancelTaskRequest request) {
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    return workerExecutions.cancelTask(task.getWarehouseId(), externalTaskId, request);
  }

  /**
   * Atomically cancels a source-owned task only while every route entry is still waiting.  This is
   * intentionally separate from the operator cancellation command, which is allowed to interrupt
   * active work.  Callers use the explicit outcome to reconcile a lost response without ever
   * turning started physical work into a cancellation.
   */
  public PreStartCancellationResult cancelExternalTaskIfPreStart(
      String sourceClientId, UUID externalTaskId, CancelTaskRequest request) {
    lock("external-task:" + externalTaskId);
    BoardTask observed = ownedExternalTask(sourceClientId, externalTaskId);
    // Queue mutations take this warehouse lock before touching task/entry rows.  Keep the same
    // global order here so a concurrent scheduler move cannot deadlock with compensation.
    queuePositions.lockQueueMutation(observed.getWarehouseId());
    BoardTask task =
        tasks
            .findByExternalTaskIdForUpdate(externalTaskId)
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!taskSyncSources.existsByBoardTaskIdAndSourceClientId(task.getId(), sourceClientId)) {
      throw new NotFoundException("Задача не найдена");
    }
    List<QueueEntry> route = entries.findAllByTaskIdForUpdate(task.getId());
    if (task.getStatus() == TaskStatus.CANCELLED) {
      return workerExecutions.preStartCancellationResult(PreStartCancellationOutcome.ALREADY_CANCELLED, task);
    }
    List<UUID> routeIds = route.stream().map(QueueEntry::getId).toList();
    boolean routeStarted =
        route.stream()
            .anyMatch(
                entry ->
                    entry.getStatus() != EntryStatus.WAITING
                        || entry.getActiveStartedAt() != null
                        || entry.getPausedAt() != null
                        || entry.getDoneAt() != null
                        || entry.getActiveWorkSeconds() != 0)
            || (!routeIds.isEmpty()
                && workerExecutions.routeHasStartedAssignments(routeIds));
    if (task.getStatus() != TaskStatus.ACTIVE
        || route.isEmpty()
        || routeStarted) {
      return workerExecutions.preStartCancellationResult(PreStartCancellationOutcome.STARTED, task);
    }
    if (task.getVersion() != request.expectedTaskVersion()) {
      return workerExecutions.preStartCancellationResult(PreStartCancellationOutcome.VERSION_CONFLICT, task);
    }
    CancelledTaskDto cancelled = workerExecutions.cancelTask(task.getWarehouseId(), externalTaskId, request);
    BoardTask current = tasks.findById(cancelled.taskId()).orElseThrow();
    return workerExecutions.preStartCancellationResult(PreStartCancellationOutcome.CANCELLED, current);
  }

  public BoardTaskRegistrationDto updateExternalTaskBeforeStart(
      String sourceClientId, UUID externalTaskId, PreStartUpdateTaskRequest request) {
    lock("external-task:" + externalTaskId);
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    UUID warehouseId = task.getWarehouseId();
    queuePositions.lockQueueMutation(warehouseId);
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    if (task.getStatus() != TaskStatus.ACTIVE) {
      throw new ConflictException("Изменить можно только активную задачу до начала работ");
    }

    List<QueueEntry> oldEntries = entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId());
    boolean routeStarted =
        oldEntries.stream()
            .anyMatch(
                entry ->
                    entry.getStatus() != EntryStatus.WAITING
                        || entry.getActiveStartedAt() != null
                        || entry.getPausedAt() != null
                        || entry.getDoneAt() != null
                        || entry.getActiveWorkSeconds() != 0);
    List<UUID> oldEntryIds = oldEntries.stream().map(QueueEntry::getId).toList();
    if (routeStarted
        || (!oldEntryIds.isEmpty()
            && workerExecutions.routeHasStartedAssignments(oldEntryIds))) {
      throw new ConflictException("Маршрут или назначение задачи уже начали выполнять");
    }

    List<ResolvedRouteStep> routeSteps =
        resolveRoute(warehouseId, request.route(), true, sourceClientId);
    Set<WorkQueue> affectedQueues =
        oldEntries.stream()
            .map(QueueEntry::getQueue)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    routeSteps.stream()
        .map(ResolvedRouteStep::queue)
        .forEach(affectedQueues::add);
    queuePositions.lockQueuePositions(
        warehouseId,
        java.util.stream.Stream.concat(
                oldEntries.stream().map(QueueEntry::getQueue),
                routeSteps.stream().map(ResolvedRouteStep::queue))
            .toList());

    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(queue -> positionCandidates.addAll(queuePositions.orderedEntries(warehouseId, queue)));
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        queuePositions.lockTaskAndEntryStreams(task, positionCandidates);

    Set<UUID> deletedIds = new LinkedHashSet<>();
    for (QueueEntry oldEntry : oldEntries) {
      eventSourcing.entryDeleted(
          oldEntry,
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, oldEntry.getId()));
      deletedIds.add(oldEntry.getId());
    }
    projectionWriter.deleteAll(entries, oldEntries);
    projectionWriter.flush();
    affectedQueues.forEach(queue -> queuePositions.normalizePositions(warehouseId, queue));
    projectionWriter.flush();

    CreateBoardTaskRequest replacement =
        new CreateBoardTaskRequest(
            externalTaskId,
            request.title(),
            request.unitNumber(),
            request.description(),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            request.route());
    task.setTitle(request.title().trim());
    task.setUnitNumber(trim(request.unitNumber()));
    task.setDescription(trim(request.description()));
    task.setPlannedDurationMinutes(request.plannedDurationMinutes());
    task.setDeadlineAt(request.deadlineAt());
    task.setRequestFingerprint(
        routePayloads.fingerprint(
            warehouseId,
            replacement,
            task.getScheduledDate(),
            task.getPriority(),
            task.getLane(),
            driverAudiences.dto(task),
            jdbc));
    task = projectionWriter.saveAndFlush(tasks, task);

    int routeIndex = 0;
    for (ResolvedRouteStep resolved : routeSteps) {
      RouteStepRequest step = resolved.request();
      QueueEntry entry = new QueueEntry();
      entry.setTask(task);
      entry.setQueue(resolved.queue());
      entry.setRouteIndex(routeIndex);
      entry.setEntryType(routeIndex == 0 ? EntryType.REAL : EntryType.SHADOW);
      entry.setStatus(EntryStatus.WAITING);
      entry.setQueuePosition(
          queuePositions.nextPosition(warehouseId, resolved.queue(), task.getScheduledDate()));
      entry.setTaskText(trim(step.taskText()));
      entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
      routePayloads.setWorkerContent(entry, step);
      projectionWriter.save(entries, entry);
      routeIndex++;
    }
    projectionWriter.flush();
    entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).forEach(eventSourcing::created);

    Set<UUID> repositionedIds = queuePositions.changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry candidate : positionCandidates) {
      if (!deletedIds.contains(candidate.getId()) && repositionedIds.contains(candidate.getId())) {
        eventSourcing.entryChanged(
            candidate,
            queuePositions.streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, candidate.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    eventSourcing.taskChanged(
        task,
        queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
    return registrationDto(task);
  }

  public BoardTaskRegistrationDto relocateExternalTask(
      String sourceClientId,
      UUID externalTaskId,
      RelocateExternalTaskRequest request) {
    RelocationPreflight preflight =
        inLifecycleMutation(() -> relocationPreflight(sourceClientId, externalTaskId, request));
    if (preflight.completed() != null) {
      return preflight.completed();
    }
    AdmissionPermit admission =
        warehouseLifecycleFence.acquireRelocation(
            preflight.sourceWarehouseId(), preflight.targetWarehouseId());
    return inLifecycleMutation(
        () -> relocateExternalTaskAfterAdmission(sourceClientId, externalTaskId, request, admission));
  }

  private BoardTaskRegistrationDto relocateExternalTaskAfterAdmission(
      String sourceClientId,
      UUID externalTaskId,
      RelocateExternalTaskRequest request,
      AdmissionPermit admission) {
    warehouseLifecycleFence.terminalizeAdmission(admission);
    lock("external-task:" + externalTaskId);
    BoardTaskRegistrationDto replay = relocationReplay(sourceClientId, externalTaskId, request);
    if (replay != null) return replay;
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    UUID sourceWarehouseId = task.getWarehouseId();
    UUID targetWarehouseId = request.targetWarehouseId();
    if (sourceWarehouseId.equals(targetWarehouseId)) {
      return registrationDto(task);
    }

    java.util.stream.Stream.of(sourceWarehouseId, targetWarehouseId)
        .distinct()
        .sorted()
        .forEach(queuePositions::lockQueueMutation);
    List<QueueEntry> route = entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId());
    List<QueueEntry> unfinishedRoute =
        route.stream().filter(entry -> UNFINISHED.contains(entry.getStatus())).toList();
    Map<UUID, WorkQueue> targetByDefinitionId = new java.util.LinkedHashMap<>();
    for (QueueEntry entry : unfinishedRoute) {
      UUID definitionId = entry.getQueue().getDefinition().getId();
      WorkQueue target =
          queues
              .findByWarehouseIdAndDefinitionId(targetWarehouseId, definitionId)
              .orElseThrow(
                  () ->
                      new ConflictException(
                          "Целевой склад не подключил общую очередь " + definitionId));
      if (!target.isActive() || target.isHidden()) {
        throw new ConflictException(
            "Очередь целевого склада должна быть активна и видима: " + definitionId);
      }
      targetByDefinitionId.put(definitionId, target);
    }
    DriverTaskAudienceDto retainedAudience = driverAudiences.dto(task);
    if (retainedAudience != null) {
      targetByDefinitionId.values().stream()
          .distinct()
          .forEach(
              target ->
                  driverAudiences.requireCompatibleWarehouse(
                      targetWarehouseId, target, retainedAudience));
    }

    queuePositions.lockQueuePositions(
        targetWarehouseId, targetByDefinitionId.values().stream().distinct().toList());
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        queuePositions.lockTaskAndEntryStreams(task, unfinishedRoute);
    UUID taskId = task.getId();
    Map<UUID, Integer> nextPositions = new java.util.LinkedHashMap<>();
    for (WorkQueue target : targetByDefinitionId.values()) {
      int next =
          queuePositions.orderedEntries(targetWarehouseId, target, task.getScheduledDate()).stream()
                  .filter(entry -> UNFINISHED.contains(entry.getStatus()))
                  .filter(entry -> !entry.getTask().getId().equals(taskId))
                  .mapToInt(QueueEntry::getQueuePosition)
                  .max()
                  .orElse(-1)
              + 1;
      nextPositions.put(target.getId(), next);
    }

    task.setWarehouseId(targetWarehouseId);
    for (QueueEntry entry : unfinishedRoute) {
      WorkQueue target =
          targetByDefinitionId.get(entry.getQueue().getDefinition().getId());
      entry.setQueue(target);
      int next = nextPositions.get(target.getId());
      entry.setQueuePosition(next);
      nextPositions.put(target.getId(), next + 1);
      entry.touch();
      projectionWriter.save(entries, entry);
    }
    if (task.getExternalTaskId() != null && !task.isCompletionDeadlineEnforced()) {
      task.setRequestFingerprint(routePayloads.fingerprint(task, route, jdbc));
    }
    task = projectionWriter.saveAndFlush(tasks, task);
    projectionWriter.flush();

    eventSourcing.taskChanged(
        task,
        queuePositions.streamVersion(
            streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
    for (QueueEntry entry : unfinishedRoute) {
      eventSourcing.entryChanged(
          entry,
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_MOVED);
      workerExecutions.publishOwnership(targetWarehouseId, entry.getId(), true);
    }
    jdbc.update(
        """
        insert into task_relocation_receipt(
          source_client_id,external_task_id,target_warehouse_id,
          expected_task_version,resulting_task_version,processed_at)
        values (?,?,?,?,?,clock_timestamp())
        """,
        sourceClientId,
        externalTaskId,
        targetWarehouseId,
        request.expectedTaskVersion(),
        task.getVersion());
    kpiEvidence.refreshWarehouse(sourceWarehouseId, now());
    kpiEvidence.refreshWarehouse(targetWarehouseId, now());
    return registrationDto(task);
  }

  private BoardTask ownedExternalTask(String sourceClientId, UUID externalTaskId) {
    BoardTask task =
        tasks
            .findByExternalTaskId(externalTaskId)
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!taskSyncSources.existsByBoardTaskIdAndSourceClientId(task.getId(), sourceClientId)) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
  }

  /**
   * Verifies an idempotent relocation receipt without taking the task advisory lock. The caller
   * repeats the lookup under that lock after the remote lifecycle read has completed.
   */
  private BoardTaskRegistrationDto relocationReplay(
      String sourceClientId, UUID externalTaskId, RelocateExternalTaskRequest request) {
    Long resultingVersion =
        jdbc.query(
                """
                select resulting_task_version
                  from task_relocation_receipt
                 where source_client_id=?
                   and external_task_id=?
                   and target_warehouse_id=?
                   and expected_task_version=?
                """,
                (result, row) -> result.getLong("resulting_task_version"),
                sourceClientId,
                externalTaskId,
                request.targetWarehouseId(),
                request.expectedTaskVersion())
            .stream()
            .findFirst()
            .orElse(null);
    if (resultingVersion == null) {
      return null;
    }
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    if (!task.getWarehouseId().equals(request.targetWarehouseId())
        || task.getVersion() != resultingVersion) {
      throw new ConflictException("Состояние задачи не соответствует сохранённому результату переноса");
    }
    return registrationDto(task);
  }

  private RelocationPreflight relocationPreflight(
      String sourceClientId, UUID externalTaskId, RelocateExternalTaskRequest request) {
    BoardTaskRegistrationDto replay = relocationReplay(sourceClientId, externalTaskId, request);
    if (replay != null) {
      return new RelocationPreflight(replay, null, null);
    }
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    UUID sourceWarehouseId = task.getWarehouseId();
    UUID targetWarehouseId = request.targetWarehouseId();
    if (sourceWarehouseId.equals(targetWarehouseId)) {
      return new RelocationPreflight(registrationDto(task), null, null);
    }
    return new RelocationPreflight(null, sourceWarehouseId, targetWarehouseId);
  }

  /**
   * Separates a completed relocation replay from the source and target warehouse identities that
   * require remote lifecycle admission before the local task mutation lock is acquired.
   */
  private record RelocationPreflight(
      BoardTaskRegistrationDto completed, UUID sourceWarehouseId, UUID targetWarehouseId) {}

  private List<ResolvedRouteStep> resolveRoute(
      UUID warehouseId,
      List<RouteStepRequest> requestedRoute,
      boolean allowRepeatedQueues,
      String sourceClientId) {
    Set<UUID> queueIds = new LinkedHashSet<>();
    List<ResolvedRouteStep> result = new ArrayList<>();
    for (RouteStepRequest step : requestedRoute) {
      validateWorkSourceMedia(step);
      WorkQueue queue = resolveRouteQueue(warehouseId, step);
      if (!allowRepeatedQueues && !queueIds.add(queue.getId())) {
        throw new ConflictException("Маршрут содержит повторяющуюся очередь: " + queue.getName());
      }
      result.add(new ResolvedRouteStep(step, queue));
    }
    return result;
  }

  private static void validateWorkSourceMedia(RouteStepRequest step) {
    Set<UUID> available = step.sourceMedia().stream()
        .map(TaskSourceMediaSnapshotRequest::mediaId)
        .collect(java.util.stream.Collectors.toSet());
    Set<UUID> assigned = new HashSet<>();
    for (TaskWorkSnapshotRequest work : step.works()) {
      for (UUID mediaId : work.sourceMediaIds()) {
        if (!available.contains(mediaId)) {
          throw new ConflictException(
              "Фотография работы отсутствует в исходных материалах этапа");
        }
        if (!assigned.add(mediaId)) {
          throw new ConflictException(
              "Одна исходная фотография не может принадлежать двум работам");
        }
      }
    }
  }

  private void requireRoutePurpose(
      List<ResolvedRouteStep> routeSteps, TaskSourceReferenceDto sourceReference) {
    boolean logistics =
        sourceReference != null
            && sourceReference.type() == TaskSourceType.LOGISTICS_DRIVER_TASK;
    for (ResolvedRouteStep step : routeSteps) {
      boolean driverQueue =
          step.queue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER;
      if (logistics != driverQueue) {
        throw new ConflictException(
            logistics
                ? "Логистическое задание должно использовать очередь водителей"
                : "Очередь водителей принимает только задания logistics-service");
      }
    }
  }

  private WorkQueue resolveRouteQueue(UUID warehouseId, RouteStepRequest step) {
    if (step.queueDefinitionId() == null) {
      throw new ConflictException("Для маршрута требуется UUID общей очереди");
    }
    WorkQueue queue =
        registry.requireWarehouseBinding(warehouseId, step.queueDefinitionId());
    if (!queue.isActive() || queue.isHidden()) {
      throw new ConflictException("Очередь целевого склада должна быть активна и видима");
    }
    return queue;
  }

  private void move(
      UUID warehouseId,
      UUID entryId,
      MoveEntryRequest request,
      boolean allowCurrentLogistics,
      DriverTaskAudienceDto targetDriverAudience) {
    queuePositions.lockQueueMutation(warehouseId);
    var entry = requireEntry(warehouseId, entryId);
    BoardTask task = entry.getTask();
    boolean currentLogisticsEntry =
        task.getLane() == TaskLane.CURRENT
            && entry.getQueue() != null
            && entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER;
    if (task.getLane() == TaskLane.CURRENT
        && (!allowCurrentLogistics || !currentLogisticsEntry)) {
      throw new ConflictException("Текущее логистическое задание перемещает только планировщик");
    }
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    if (!UNFINISHED.contains(entry.getStatus()))
      throw new ConflictException("Завершенный или отмененный этап перемещать нельзя");
    if (entry.getStatus() == EntryStatus.IN_PROGRESS
        || entry.getStatus() == EntryStatus.PAUSED)
      throw new ConflictException("Этап в работе перемещать нельзя");
    LocalDate oldDate = task.getScheduledDate();
    WorkQueue oldQueue = entry.getQueue();
    int oldPosition = entry.getQueuePosition();
    WorkQueue target =
        registry.requireQueue(warehouseId, request.targetQueueId());
    List<QueueEntry> taskEntries =
        entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
            .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
            .toList();
    if (taskEntries.stream()
        .anyMatch(
            candidate ->
                candidate.getStatus() == EntryStatus.IN_PROGRESS
                    || candidate.getStatus() == EntryStatus.PAUSED)) {
      throw new ConflictException("Задачу с этапом в работе перемещать нельзя");
    }
    List<WorkQueue> affectedQueues =
        java.util.stream.Stream.concat(
                taskEntries.stream().map(QueueEntry::getQueue),
                java.util.stream.Stream.of(oldQueue, target))
            .toList();
    queuePositions.lockQueuePositions(warehouseId, affectedQueues);
    var duplicate =
        taskEntries.stream()
            .filter(
                e ->
                    !e.equals(entry)
                        && UNFINISHED.contains(e.getStatus())
                        && Objects.equals(id(e.getQueue()), id(target)))
            .findFirst();
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.stream()
        .distinct()
        .forEach(queue -> positionCandidates.addAll(queuePositions.orderedEntries(warehouseId, queue)));
    duplicate.ifPresent(positionCandidates::add);
    positionCandidates.addAll(taskEntries);
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    Map<TaskBoardQueuePositionCoordinator.QueuePositionScope, List<TaskBoardQueuePositionCoordinator.PinnedQueueOrdinal>> pinnedOrdinals =
        queuePositions.pinnedQueueOrdinals(positionCandidates);
    var streamVersions = queuePositions.lockTaskAndEntryStreams(task, positionCandidates);
    Set<UUID> protectedTaskIds = queuePositions.protectedTaskIds(positionCandidates);
    if (duplicate.isPresent()) {
      var other = duplicate.get();
      if (entry.getEntryType() != EntryType.REAL
          || entry.getStatus() != EntryStatus.WAITING
          || other.getEntryType() != EntryType.SHADOW
          || other.getStatus() != EntryStatus.WAITING)
        throw new ConflictException("В целевой очереди уже есть незавершенный этап этой задачи");
      if (!Objects.equals(id(oldQueue), id(target))) {
        entry.setQueue(other.getQueue());
        other.setQueue(oldQueue);
        other.setQueuePosition(oldPosition);
        projectionWriter.save(entries, other);
      }
    } else {
      entry.setQueue(target);
    }
    boolean dateChanged = !oldDate.equals(request.targetDate());
    boolean laneChanged = currentLogisticsEntry;
    DriverTaskAudienceDto audienceBefore = driverAudiences.dto(task);
    driverAudiences.replace(task, target, targetDriverAudience);
    boolean audienceChanged = !Objects.equals(audienceBefore, driverAudiences.dto(task));
    if (dateChanged || laneChanged || audienceChanged) {
      task.setScheduledDate(request.targetDate());
      if (laneChanged) task.setLane(TaskLane.SCHEDULED);
      projectionWriter.saveAndFlush(tasks, task);
    }
    Map<WorkQueue, List<QueueEntry>> routeEntriesByTarget = new java.util.LinkedHashMap<>();
    for (QueueEntry routeEntry : taskEntries) {
      WorkQueue routeTarget = routeEntry.equals(entry) ? target : routeEntry.getQueue();
      routeEntriesByTarget
          .computeIfAbsent(routeTarget, ignored -> new ArrayList<>())
          .add(routeEntry);
    }
    for (Map.Entry<WorkQueue, List<QueueEntry>> routeTarget : routeEntriesByTarget.entrySet()) {
      queuePositions.insertAtPosition(
          warehouseId,
          routeTarget.getValue(),
          routeTarget.getKey(),
          request.targetDate(),
          request.targetIndex(),
          protectedTaskIds);
    }
    affectedQueues.stream().distinct().forEach(queue -> queuePositions.normalizePositions(warehouseId, queue));
    if (laneChanged) {
      queuePositions.normalizeCurrentLogisticsPositions(warehouseId, List.of(entry));
    }
    projectionWriter.flush();
    queuePositions.restorePinnedQueueOrdinals(pinnedOrdinals);
    projectionWriter.flush();
    Set<UUID> changedIds = queuePositions.changedPositionIds(positionCandidates, positionsBefore);
    changedIds.add(entry.getId());
    duplicate.ifPresent(other -> changedIds.add(other.getId()));
    for (QueueEntry candidate : positionCandidates) {
      if (changedIds.contains(candidate.getId())) {
        eventSourcing.entryChanged(
            candidate,
            queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, candidate.getId()),
            candidate.equals(entry) || duplicate.filter(candidate::equals).isPresent()
                ? TaskBoardEventTypes.QUEUE_ENTRY_MOVED
                : TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    if (dateChanged || laneChanged || audienceChanged) {
      eventSourcing.taskChanged(
          task,
          queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    return;
  }


  private QueueEntry requireEntry(UUID warehouseId, UUID id) {
    var e = entries.findById(id).orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!e.getTask().getWarehouseId().equals(warehouseId))
      throw new NotFoundException("Этап не найден");
    return e;
  }

  private QueueEntry requireEntryForUpdate(UUID warehouseId, UUID id) {
    var e =
        entries.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!e.getTask().getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Этап не найден");
    }
    return e;
  }

  private UUID id(WorkQueue q) {
    return q == null ? null : q.getId();
  }

  private String trim(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private String compact(String value) {
    String trimmed = trim(value);
    return trimmed == null ? null : trimmed.replaceAll("\\s+", " ");
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /**
   * Invalidates every connected worker feed only after the atomic move and audience change commit.
   * This is deliberately projection-only because an audience change may revoke entry discovery.
   */
  private void publishWorkerFeedChangedAfterCommit() {
    Runnable dispatch = () -> workerInvalidations.feedChanged(workerRevision());
    if (TransactionSynchronizationManager.isSynchronizationActive()) {
      TransactionSynchronizationManager.registerSynchronization(
          new TransactionSynchronization() {
            @Override
            public void afterCommit() {
              dispatch.run();
            }
          });
    } else {
      dispatch.run();
    }
  }

  private long workerRevision() {
    Long revision =
        jdbc.queryForObject(
            "select coalesce(sum(current_version + 1), 0)::bigint from event_stream_head",
            Long.class);
    return revision == null ? 0 : revision;
  }

  private BoardTaskRegistrationDto registrationDto(BoardTask task) {
    var route =
        entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
            .map(
                entry ->
                    new RegisteredRouteStepDto(
                        entry.getId(),
                        entry.getVersion(),
                        entry.getQueue().getDefinition().getId(),
                        entry.getQueue().getId(),
                        entry.getQueue().getName(),
                        entry.getRouteIndex(),
                        entry.getQueuePosition(),
                        entry.getEntryType(),
                        entry.getStatus(),
                        entry.getTaskText(),
                        entry.getPlannedDurationMinutes()))
            .toList();
    return new BoardTaskRegistrationDto(
        task.getId(),
        task.getVersion(),
        task.getWarehouseId(),
        task.getExternalTaskId(),
        task.getTitle(),
        task.getUnitNumber(),
        task.getDescription(),
        task.getStatus(),
        task.getPlannedDurationMinutes(),
        task.getDeadlineAt(),
        task.getScheduledDate(),
        task.getLane(),
        task.getPriority(),
        task.isPinned(),
        driverAudiences.dto(task),
        task.getDoneAt(),
        route);
  }

  private <T> T inLifecycleMutation(Supplier<T> mutation) {
    T result = lifecycleMutations.execute(status -> mutation.get());
    if (result == null) {
      throw new IllegalStateException("Lifecycle task-board mutation returned no result");
    }
    return result;
  }

  private void lock(String key) {
    String database =
        jdbc.execute(
            (ConnectionCallback<String>)
                connection -> connection.getMetaData().getDatabaseProductName());
    if (database != null && "PostgreSQL".equalsIgnoreCase(database.trim())) {
      jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?, 0))", Object.class, key);
    }
  }

  /** Binds one requested route step to its authoritative task-board queue before persistence. */
  private record ResolvedRouteStep(RouteStepRequest request, WorkQueue queue) {}
}
