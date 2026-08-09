package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Owns user-directed queue ordering: moves, date exchanges, pins and scheduled rollover.
 *
 * <p>Domain ordering commands compose the shared persisted-position coordinator rather than
 * maintaining a second lock or pin implementation.
 */
@Service
class TaskBoardOrderingService {
  private static final String MAINTENANCE_SOURCE_CLIENT_ID = "maintenance-service";
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final RegistryService registry;
  private final TaskSyncSourceRepository taskSyncSources;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final GroupKpiEvidenceService kpiEvidence;
  private final TaskBoardQueuePositionCoordinator queuePositions;

  TaskBoardOrderingService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      RegistryService registry,
      TaskSyncSourceRepository taskSyncSources,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      GroupKpiEvidenceService kpiEvidence,
      TaskBoardQueuePositionCoordinator queuePositions) {
    this.tasks = tasks;
    this.entries = entries;
    this.registry = registry;
    this.taskSyncSources = taskSyncSources;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.kpiEvidence = kpiEvidence;
    this.queuePositions = queuePositions;
  }

  void move(
      UUID warehouseId,
      UUID entryId,
      MoveEntryRequest request,
      boolean allowCurrentLogistics) {
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
    if (dateChanged || laneChanged) {
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
    if (dateChanged || laneChanged) {
      eventSourcing.taskChanged(
          task,
          queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    return;
  }



  void swapDates(UUID warehouseId, SwapTaskBoardDatesRequest request) {
    if (request.firstDate().equals(request.secondDate())) {
      throw new IllegalArgumentException("Date columns must be different");
    }
    queuePositions.lockQueueMutation(warehouseId);

    List<QueueEntry> columnEntries =
        activeEntries(warehouseId).stream()
            .filter(
                entry ->
                    entry.getTask().getScheduledDate().equals(request.firstDate())
                        || entry.getTask().getScheduledDate().equals(request.secondDate()))
            .toList();
    List<QueueEntry> firstColumnEntries =
        columnEntries.stream()
            .filter(entry -> entry.getTask().getScheduledDate().equals(request.firstDate()))
            .toList();
    List<QueueEntry> secondColumnEntries =
        columnEntries.stream()
            .filter(entry -> entry.getTask().getScheduledDate().equals(request.secondDate()))
            .toList();
    if (firstColumnEntries.isEmpty() || secondColumnEntries.isEmpty()) {
      throw new ConflictException("Для обмена должны существовать обе колонки дат");
    }

    Map<UUID, TaskBoardDateEntryExpectation> expectedEntries =
        new java.util.LinkedHashMap<>();
    for (TaskBoardDateEntryExpectation expectation : request.entries()) {
      if (expectedEntries.put(expectation.entryId(), expectation) != null) {
        throw new ConflictException("Ожидание этапа повторяется");
      }
    }
    if (expectedEntries.size() != columnEntries.size()) {
      throw new ConflictException("Колонки дат были изменены другим пользователем");
    }

    Map<UUID, BoardTask> tasksById = new java.util.LinkedHashMap<>();
    for (QueueEntry entry : columnEntries) {
      TaskBoardDateEntryExpectation expectation = expectedEntries.get(entry.getId());
      if (expectation == null) {
        throw new ConflictException("Колонки дат были изменены другим пользователем");
      }
      checkVersion(entry.getVersion(), expectation.expectedVersion(), "Этап");
      checkVersion(entry.getTask().getVersion(), expectation.expectedTaskVersion(), "Задача");
      tasksById.put(entry.getTask().getId(), entry.getTask());
    }

    List<BoardTask> dateTasks = new ArrayList<>(tasksById.values());
    dateTasks.sort(Comparator.comparing(task -> task.getId().toString()));
    for (BoardTask task : dateTasks) {
      boolean routeInProgress =
          entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
              .anyMatch(
                  entry ->
                      entry.getStatus() == EntryStatus.IN_PROGRESS
                          || entry.getStatus() == EntryStatus.PAUSED);
      if (routeInProgress) {
        throw new ConflictException("Задачу с этапом в работе перемещать нельзя");
      }
    }

    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        eventSourcing.lockStreams(
            dateTasks.stream()
                .map(
                    task ->
                        new TaskBoardEventStore.StreamRef(
                            TaskBoardAggregateType.BOARD_TASK, task.getId()))
                .toList());
    for (BoardTask task : dateTasks) {
      task.setScheduledDate(
          task.getScheduledDate().equals(request.firstDate())
              ? request.secondDate()
              : request.firstDate());
    }
    projectionWriter.saveAll(tasks, dateTasks);
    projectionWriter.flush();
    for (BoardTask task : dateTasks) {
      eventSourcing.taskChanged(
          task,
          queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    return;
  }


  /** Pins or unpins all route entries for a task without changing their positions. */

  LocalDate pin(UUID warehouseId, UUID taskId, PinTaskRequest request) {
    queuePositions.lockQueueMutation(warehouseId);
    BoardTask task = requireTask(warehouseId, taskId);
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    if (task.getStatus() != TaskStatus.ACTIVE) {
      throw new ConflictException("Закрепить можно только активную задачу");
    }
    if (task.isPinned() == request.pinned()) {
      return task.getScheduledDate();
    }
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.BOARD_TASK, task.getId());
    task.setPinned(request.pinned());
    projectionWriter.saveAndFlush(tasks, task);
    eventSourcing.taskChanged(
        task,
        streamVersion,
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
    return task.getScheduledDate();
  }



  int rolloverOverdueMaintenanceTasks(LocalDate targetDate) {
    if (targetDate == null) {
      throw new IllegalArgumentException("Rollover target date is required");
    }
    Set<UUID> maintenanceTaskIds =
        taskSyncSources.findAllBySourceClientId(MAINTENANCE_SOURCE_CLIENT_ID).stream()
            .map(TaskSyncSource::getBoardTaskId)
            .collect(java.util.stream.Collectors.toSet());
    List<BoardTask> overdue =
        tasks.findAllById(maintenanceTaskIds).stream()
            .filter(task -> task.getStatus() == TaskStatus.ACTIVE)
            .filter(task -> task.getScheduledDate().isBefore(targetDate))
            .sorted(
                Comparator.comparing(BoardTask::getScheduledDate)
                    .thenComparing(task -> task.getId().toString()))
            .toList();
    int rolledOver = 0;
    Map<UUID, List<BoardTask>> byWarehouse =
        overdue.stream()
            .collect(java.util.stream.Collectors.groupingBy(BoardTask::getWarehouseId));
    for (Map.Entry<UUID, List<BoardTask>> warehouse : byWarehouse.entrySet()) {
      rolledOver +=
          rolloverWarehouse(warehouse.getKey(), warehouse.getValue(), targetDate);
    }
    return rolledOver;
  }

  private int rolloverWarehouse(
      UUID warehouseId, List<BoardTask> candidates, LocalDate targetDate) {
    if (candidates.isEmpty()) return 0;
    queuePositions.lockQueueMutation(warehouseId);
    List<BoardTask> overdueTasks =
        tasks.findAllOverdueForUpdate(
            warehouseId,
            candidates.stream().map(BoardTask::getId).toList(),
            TaskStatus.ACTIVE,
            targetDate);
    if (overdueTasks.isEmpty()) return 0;
    Map<UUID, LocalDate> originalDates =
        overdueTasks.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    BoardTask::getId, BoardTask::getScheduledDate));
    Set<QueueEntry> movedEntries =
        overdueTasks.stream()
            .flatMap(
                task -> entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream())
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    List<WorkQueue> affectedQueues =
        movedEntries.stream().map(QueueEntry::getQueue).distinct().toList();
    queuePositions.lockQueuePositions(warehouseId, affectedQueues);
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(
        queue -> positionCandidates.addAll(queuePositions.orderedEntries(warehouseId, queue)));
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    List<TaskBoardEventStore.StreamRef> streams = new ArrayList<>();
    overdueTasks.stream()
        .map(
            task ->
                new TaskBoardEventStore.StreamRef(
                    TaskBoardAggregateType.BOARD_TASK, task.getId()))
        .forEach(streams::add);
    positionCandidates.stream()
        .map(
            entry ->
                new TaskBoardEventStore.StreamRef(
                    TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()))
        .forEach(streams::add);
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        eventSourcing.lockStreams(streams);
    overdueTasks.forEach(
        task -> {
          task.setScheduledDate(targetDate);
          projectionWriter.save(tasks, task);
        });
    for (WorkQueue queue : affectedQueues) {
      List<QueueEntry> targetEntries =
          queuePositions.orderedEntries(warehouseId, queue).stream()
              .filter(entry -> UNFINISHED.contains(entry.getStatus()))
              .filter(entry -> entry.getTask().getScheduledDate().equals(targetDate))
              .sorted(
                  Comparator.comparingInt(
                          (QueueEntry entry) -> {
                            boolean active =
                                entry.getStatus() == EntryStatus.IN_PROGRESS
                                    || entry.getStatus() == EntryStatus.PAUSED;
                            boolean rolledOver =
                                originalDates.containsKey(entry.getTask().getId());
                            if (active && !rolledOver) return 0;
                            if (active) return 1;
                            return rolledOver ? 2 : 3;
                          })
                      .thenComparing(
                          entry ->
                              originalDates.getOrDefault(
                                  entry.getTask().getId(), targetDate))
                      .thenComparingInt(
                          entry ->
                              positionsBefore
                                  .getOrDefault(
                                      entry.getId(),
                                      new TaskBoardQueuePositionCoordinator.QueueEntryPosition(
                                          id(entry.getQueue()),
                                          entry.getQueuePosition()))
                                  .position()))
              .toList();
      int position = 0;
      for (QueueEntry entry : targetEntries) {
        entry.setQueuePosition(position++);
        projectionWriter.save(entries, entry);
      }
      queuePositions.normalizePositions(warehouseId, queue);
    }
    projectionWriter.flush();
    Set<UUID> changedEntries = queuePositions.changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry entry : positionCandidates) {
      if (changedEntries.contains(entry.getId())) {
        eventSourcing.entryChanged(
            entry,
            queuePositions.streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_MOVED);
      }
    }
    for (BoardTask task : overdueTasks) {
      eventSourcing.taskChanged(
          task,
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    return overdueTasks.size();
  }




  private QueueEntry requireEntry(UUID warehouseId, UUID id) {
    var entry = entries.findById(id).orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!entry.getTask().getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Этап не найден");
    }
    return entry;
  }

  private BoardTask requireTask(UUID warehouseId, UUID id) {
    BoardTask task = tasks.findById(id).orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!task.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
  }

  private List<QueueEntry> activeEntries(UUID warehouseId) {
    var result = new ArrayList<QueueEntry>();
    for (var task : tasks.findAllByWarehouseIdAndStatusIn(warehouseId, Set.of(TaskStatus.ACTIVE))) {
      List<QueueEntry> route =
          entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
              .filter(entry -> UNFINISHED.contains(entry.getStatus()))
              .toList();
      int holdingBlocker =
          route.stream()
              .filter(
                  entry ->
                      entry.getQueue() != null && entry.getQueue().getType() == QueueType.HOLDING)
              .mapToInt(QueueEntry::getRouteIndex)
              .min()
              .orElse(Integer.MAX_VALUE);
      route.stream().filter(entry -> entry.getRouteIndex() <= holdingBlocker).forEach(result::add);
    }
    return result;
  }

  private UUID id(WorkQueue queue) {
    return queue == null ? null : queue.getId();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
