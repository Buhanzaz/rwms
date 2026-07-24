package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static dev.buhanzaz.rwms.taskboard.service.RegistryService.checkVersion;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.mapper.LogisticsTaskResponseMapper;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.security.MessageDigest;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TaskBoardService {
  public static final String UNASSIGNED_CODE = "UNASSIGNED";
  public static final String REQUEST_FINGERPRINT_SCHEMA = "task-board-create:v1";
  private static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final String LOGISTICS_PREPARATION_TITLE = "Logistics preparation";
  private static final String LOGISTICS_PREPARATION_TASK_TEXT = "LOGISTICS_PREPARATION";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_TITLE = "Перемещение мебели";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_CANCEL_REASON =
      "LOGISTICS_EQUIPMENT_MOVEMENT_CANCELLED";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_FINGERPRINT_SCHEMA =
      "task-board-logistics-equipment-movement:v1";
  private static final int MAX_EQUIPMENT_MOVEMENT_OPERATIONS = 10;
  private static final int EQUIPMENT_MOVEMENT_DISPLAY_NAME_LENGTH = 64;
  private static final Pattern EQUIPMENT_CODE =
      Pattern.compile("^[A-Z0-9][A-Z0-9_-]{0,63}$");
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);
  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository bindings;
  private final TaskAssignmentRepository assignments;
  private final TaskTimeEventRepository events;
  private final TaskAutoInterruptionRepository interruptions;
  private final WorkerGroupMemberRepository members;
  private final WorkforceService workforce;
  private final RegistryService registry;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final TaskSyncSourceRepository taskSyncSources;
  private final LogisticsTaskResponseMapper logisticsTaskMapper;

  public TaskBoardService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      WorkQueueRepository queues,
      WorkQueueClassBindingRepository bindings,
      TaskAssignmentRepository assignments,
      TaskTimeEventRepository events,
      TaskAutoInterruptionRepository interruptions,
      WorkerGroupMemberRepository members,
      WorkforceService workforce,
      RegistryService registry,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      TaskSyncSourceRepository taskSyncSources,
      LogisticsTaskResponseMapper logisticsTaskMapper) {
    this.tasks = tasks;
    this.entries = entries;
    this.queues = queues;
    this.bindings = bindings;
    this.assignments = assignments;
    this.events = events;
    this.interruptions = interruptions;
    this.members = members;
    this.workforce = workforce;
    this.registry = registry;
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.taskSyncSources = taskSyncSources;
    this.logisticsTaskMapper = logisticsTaskMapper;
  }

  @Transactional(readOnly = true)
  public TaskBoardSnapshot snapshot(UUID warehouseId, boolean includeShadow) {
    var columns = new ArrayList<BoardColumnDto>();
    var allEntries = activeEntries(warehouseId);
    for (var queue :
        queues.findAllByWarehouseIdAndActiveTrueOrderBySortOrderAscNameAsc(warehouseId)) {
      if (queue.isHidden()) continue;
      var cards =
          allEntries.stream()
              .filter(e -> e.getQueue() != null && e.getQueue().getId().equals(queue.getId()))
              .filter(e -> includeShadow || e.getEntryType() == EntryType.REAL)
              .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
              .map(this::dto)
              .toList();
      columns.add(
          new BoardColumnDto(
              queue.getId(),
              queue.getCode(),
              queue.getName(),
              queue.getType(),
              queue.getSortOrder(),
              false,
              cards));
    }
    var unassigned =
        allEntries.stream()
            .filter(e -> e.getQueue() == null)
            .filter(e -> includeShadow || e.getEntryType() == EntryType.REAL)
            .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
            .map(this::dto)
            .toList();
    columns.add(
        new BoardColumnDto(
            null, UNASSIGNED_CODE, "Без очереди", null, Integer.MAX_VALUE, true, unassigned));
    return new TaskBoardSnapshot(warehouseId, columns);
  }

  @Transactional
  public TaskBoardSnapshot createTask(UUID warehouseId, CreateBoardTaskRequest request) {
    createTask(warehouseId, request, null, false);
    return snapshot(warehouseId, true);
  }

  @Transactional
  public BoardTaskRegistrationDto registerExternalTask(
      String sourceClientId, RegisterExternalTaskRequest request) {
    CreateBoardTaskRequest createRequest =
        new CreateBoardTaskRequest(
            request.externalTaskId(),
            request.title(),
            request.unitNumber(),
            request.description(),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            request.route());
    BoardTask task = createTask(request.warehouseId(), createRequest, sourceClientId, true);
    return registrationDto(task);
  }

  /**
   * Creates a source-owned preparation task in the task-board-owned unassigned
   * column. Logistics supplies no queue, worker, route or operator text.
   */
  @Transactional
  public LogisticsTaskSnapshot registerLogisticsPreparationTask(
      RegisterLogisticsPreparationTaskRequest request) {
    BoardTask task =
        createTask(
            request.warehouseId(),
            new CreateBoardTaskRequest(
                request.externalTaskId(),
                LOGISTICS_PREPARATION_TITLE,
                null,
                null,
                request.plannedDurationMinutes(),
                request.deadlineAt(),
                List.of(
                    new RouteStepRequest(
                        null,
                        UNASSIGNED_CODE,
                        LOGISTICS_PREPARATION_TASK_TEXT,
                        request.plannedDurationMinutes()))),
            LOGISTICS_SOURCE_CLIENT_ID,
            false);
    return logisticsTaskMapper.toLogisticsTaskSnapshot(task);
  }

  /**
   * Registers a source-owned task whose generated detail is limited to typed furniture operations.
   * The persisted flag fences completion at the reservation deadline without changing generic tasks.
   */
  @Transactional
  public LogisticsTaskSnapshot registerLogisticsEquipmentMovementTask(
      RegisterLogisticsEquipmentMovementTaskRequest request) {
    NormalizedEquipmentMovementRequest normalized = normalizeEquipmentMovementRequest(request);
    BoardTask task =
        createTask(
            request.warehouseId(),
            new CreateBoardTaskRequest(
                request.externalTaskId(),
                equipmentMovementTitle(normalized.unitNumber()),
                normalized.unitNumber(),
                equipmentMovementText(normalized.operations()),
                request.plannedDurationMinutes(),
                request.deadlineAt(),
                List.of(
                    new RouteStepRequest(
                        null,
                        UNASSIGNED_CODE,
                        equipmentMovementText(normalized.operations()),
                        request.plannedDurationMinutes()))),
            LOGISTICS_SOURCE_CLIENT_ID,
            false,
            true,
            equipmentMovementFingerprint(request, normalized));
    return logisticsTaskMapper.toLogisticsTaskSnapshot(requireEquipmentMovementTask(task));
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues) {
    return createTask(
        warehouseId, request, sourceClientId, allowRepeatedQueues, false, null);
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues,
      boolean completionDeadlineEnforced,
      String suppliedFingerprint) {
    String requestFingerprint =
        suppliedFingerprint == null ? fingerprint(warehouseId, request) : suppliedFingerprint;
    if (request.externalTaskId() != null) {
      lock("external-task:" + request.externalTaskId());
      var existing = tasks.findByExternalTaskId(request.externalTaskId());
      if (existing.isPresent()) {
        BoardTask task = existing.get();
        if (warehouseId.equals(task.getWarehouseId())
            && task.getRequestFingerprint() != null
            && task.getRequestFingerprint().equals(requestFingerprint)
            && (sourceClientId == null
                || taskSyncSources.existsByBoardTaskIdAndSourceClientId(
                    task.getId(), sourceClientId))) {
          return task;
        }
        throw new ConflictException("Задача с externalTaskId уже существует с другими данными");
      }
    }
    lockQueueMutation(warehouseId);
    List<ResolvedRouteStep> routeSteps =
        resolveRoute(warehouseId, request.route(), allowRepeatedQueues);
    lockQueuePositions(warehouseId, routeSteps.stream().map(ResolvedRouteStep::queue).toList());
    var task = new BoardTask();
    task.setWarehouseId(warehouseId);
    task.setExternalTaskId(request.externalTaskId());
    task.setTitle(request.title().trim());
    task.setUnitNumber(trim(request.unitNumber()));
    task.setDescription(trim(request.description()));
    task.setPlannedDurationMinutes(request.plannedDurationMinutes());
    task.setDeadlineAt(request.deadlineAt());
    task.setCompletionDeadlineEnforced(completionDeadlineEnforced);
    task.setRequestFingerprint(request.externalTaskId() == null ? null : requestFingerprint);
    try {
      task = projectionWriter.saveAndFlush(tasks, task);
    } catch (DataIntegrityViolationException exception) {
      throw new ConflictException("Задача с externalTaskId уже существует");
    }
    if (sourceClientId != null) {
      projectionWriter.saveAndFlush(
          taskSyncSources,
          new TaskSyncSource(task.getId(), request.externalTaskId(), sourceClientId));
    }
    int route = 0;
    for (var resolved : routeSteps) {
      RouteStepRequest step = resolved.request();
      WorkQueue queue = resolved.queue();
      var entry = new QueueEntry();
      entry.setTask(task);
      entry.setQueue(queue);
      entry.setQueueCode(resolved.queueCode());
      entry.setRouteIndex(route);
      entry.setEntryType(route == 0 ? EntryType.REAL : EntryType.SHADOW);
      entry.setStatus(EntryStatus.WAITING);
      entry.setQueuePosition(nextPosition(warehouseId, queue));
      entry.setTaskText(trim(step.taskText()));
      entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
      projectionWriter.save(entries, entry);
      route++;
    }
    projectionWriter.flush();
    eventSourcing.created(task);
    entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).forEach(eventSourcing::created);
    return task;
  }

  @Transactional
  public CancelledTaskDto cancelTask(
      UUID warehouseId, UUID externalTaskId, CancelTaskRequest request) {
    lock("external-task:" + externalTaskId);
    lockQueueMutation(warehouseId);
    var task =
        tasks
            .findByWarehouseIdAndExternalTaskId(warehouseId, externalTaskId)
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (task.getStatus() == TaskStatus.CANCELLED) return cancelledTaskDto(task);
    if (task.getStatus() == TaskStatus.DONE)
      throw new ConflictException("Завершенную задачу отменить нельзя");
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    OffsetDateTime now = now();
    List<QueueEntry> taskEntries = entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId());
    Set<UUID> cancelledEntryIds =
        taskEntries.stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .map(QueueEntry::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<QueueEntry> interruptionNeighbours = interruptionNeighbours(taskEntries);
    Set<WorkQueue> affectedQueues =
        taskEntries.stream()
            .filter(entry -> cancelledEntryIds.contains(entry.getId()))
            .map(QueueEntry::getQueue)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    boolean affectedUnassigned =
        taskEntries.stream()
            .anyMatch(
                entry ->
                    cancelledEntryIds.contains(entry.getId()) && entry.getQueue() == null);
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
    if (affectedUnassigned) positionCandidates.addAll(orderedEntries(warehouseId, null));
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
    Set<QueueEntry> streamsToLock = new LinkedHashSet<>(taskEntries);
    streamsToLock.addAll(interruptionNeighbours);
    streamsToLock.addAll(positionCandidates);
    lockQueuePositions(warehouseId, taskEntries.stream().map(QueueEntry::getQueue).toList());
    var streamVersions = lockTaskAndEntryStreams(task, streamsToLock);
    long taskStreamVersion =
        streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId());
    for (var entry : taskEntries) {
      if (!UNFINISHED.contains(entry.getStatus())) continue;
      stopTimer(entry, now);
      entry.setStatus(EntryStatus.CANCELLED);
      entry.setDoneAt(now);
      entry.setPausedAt(null);
      entry.setPauseOrigin(null);
      var activeAssignments =
          assignments.findAllByQueueEntryIdAndStatusIn(
              entry.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED));
      if (activeAssignments.isEmpty()) {
        event(entry, null, null, TimeEventType.CANCELLED, request.reason().trim(), null, now);
      } else {
        for (var assignment : activeAssignments) {
          assignment.setStatus(AssignmentStatus.CANCELLED);
          assignment.setPausedAt(null);
          assignment.setFinishedAt(now);
          projectionWriter.save(assignments, assignment);
          event(
              entry,
              assignment.getWorker(),
              assignment.getWorkerGroup(),
              TimeEventType.CANCELLED,
              request.reason().trim(),
              null,
              now);
        }
      }
      projectionWriter.save(entries, entry);
      resolveInterruptions(entry, now);
      closeInterruptedLinks(entry, now);
    }
    affectedQueues.forEach(queue -> normalizePositions(warehouseId, queue));
    if (affectedUnassigned) normalizePositions(warehouseId, null);
    task.setStatus(TaskStatus.CANCELLED);
    task.setDoneAt(now);
    task = projectionWriter.saveAndFlush(tasks, task);
    projectionWriter.flush();
    Set<UUID> changedNeighbourIds =
        interruptionNeighbours.stream()
            .map(QueueEntry::getId)
            .collect(java.util.stream.Collectors.toSet());
    Set<UUID> repositionedIds = changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry entry : streamsToLock) {
      String eventType =
          cancelledEntryIds.contains(entry.getId())
              ? TaskBoardEventTypes.QUEUE_ENTRY_CANCELLED
              : changedNeighbourIds.contains(entry.getId()) || repositionedIds.contains(entry.getId())
                  ? TaskBoardEventTypes.QUEUE_ENTRY_CHANGED
                  : null;
      if (eventType != null) {
        eventSourcing.entryChanged(
            entry,
            streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
            eventType);
      }
    }
    eventSourcing.taskChanged(task, taskStreamVersion, TaskBoardEventTypes.BOARD_TASK_CANCELLED);
    return cancelledTaskDto(task);
  }

  @Transactional(readOnly = true)
  public BoardTaskRegistrationDto registration(UUID warehouseId, UUID externalTaskId) {
    BoardTask task =
        tasks
            .findByWarehouseIdAndExternalTaskId(warehouseId, externalTaskId)
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    return registrationDto(task);
  }

  @Transactional(readOnly = true)
  public BoardTaskRegistrationDto externalTask(String sourceClientId, UUID externalTaskId) {
    return registrationDto(ownedExternalTask(sourceClientId, externalTaskId));
  }

  @Transactional(readOnly = true)
  public LogisticsTaskSnapshot logisticsPreparationTask(UUID externalTaskId) {
    return logisticsTaskMapper.toLogisticsTaskSnapshot(
        ownedLogisticsPreparationTask(externalTaskId));
  }

  /**
   * Closes a logistics-owned preparation task when the shipment operator
   * confirms the physical departure.  This source command is intentionally
   * narrow: it cannot complete arbitrary task-board work.
   */
  @Transactional
  public LogisticsTaskSnapshot completeLogisticsPreparationTask(
      UUID externalTaskId, CompleteLogisticsPreparationTaskRequest request) {
    lock("external-task:" + externalTaskId);
    BoardTask task = ownedLogisticsPreparationTask(externalTaskId);
    if (task.getStatus() == TaskStatus.DONE) {
      return logisticsTaskMapper.toLogisticsTaskSnapshot(task);
    }
    if (task.getStatus() == TaskStatus.CANCELLED) {
      throw new ConflictException("Отмененную задачу завершить нельзя");
    }
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    UUID warehouseId = task.getWarehouseId();
    lockQueueMutation(warehouseId);
    List<QueueEntry> taskEntries =
        entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId());
    Set<QueueEntry> unfinishedEntries =
        taskEntries.stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<QueueEntry> interruptionNeighbours = interruptionNeighbours(taskEntries);
    Set<WorkQueue> affectedQueues =
        unfinishedEntries.stream()
            .map(QueueEntry::getQueue)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    boolean affectedUnassigned =
        unfinishedEntries.stream().anyMatch(entry -> entry.getQueue() == null);
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(
        queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
    if (affectedUnassigned) {
      positionCandidates.addAll(orderedEntries(warehouseId, null));
    }
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
    Set<QueueEntry> streamsToLock = new LinkedHashSet<>(taskEntries);
    streamsToLock.addAll(interruptionNeighbours);
    streamsToLock.addAll(positionCandidates);
    lockQueuePositions(
        warehouseId, taskEntries.stream().map(QueueEntry::getQueue).toList());
    var streamVersions = lockTaskAndEntryStreams(task, streamsToLock);
    long taskStreamVersion =
        streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId());
    OffsetDateTime completedAt = now();
    Set<UUID> completedEntryIds =
        unfinishedEntries.stream()
            .map(QueueEntry::getId)
            .collect(java.util.stream.Collectors.toSet());
    for (QueueEntry entry : unfinishedEntries) {
      stopTimer(entry, completedAt);
      entry.setStatus(EntryStatus.DONE);
      entry.setDoneAt(completedAt);
      entry.setPausedAt(null);
      entry.setPauseOrigin(null);
      List<TaskAssignment> activeAssignments =
          assignments.findAllByQueueEntryIdAndStatusIn(
              entry.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED));
      if (activeAssignments.isEmpty()) {
        event(
            entry,
            null,
            null,
            TimeEventType.FINISHED,
            "LOGISTICS_SHIPMENT_CONFIRMED",
            null,
            completedAt);
      } else {
        for (TaskAssignment assignment : activeAssignments) {
          assignment.setStatus(AssignmentStatus.DONE);
          assignment.setPausedAt(null);
          assignment.setFinishedAt(completedAt);
          projectionWriter.save(assignments, assignment);
          event(
              entry,
              assignment.getWorker(),
              assignment.getWorkerGroup(),
              TimeEventType.FINISHED,
              "LOGISTICS_SHIPMENT_CONFIRMED",
              null,
              completedAt);
        }
      }
      projectionWriter.save(entries, entry);
      resolveInterruptions(entry, completedAt);
      closeInterruptedLinks(entry, completedAt);
    }
    affectedQueues.forEach(queue -> normalizePositions(warehouseId, queue));
    if (affectedUnassigned) normalizePositions(warehouseId, null);
    task.setStatus(TaskStatus.DONE);
    task.setDoneAt(completedAt);
    task = projectionWriter.saveAndFlush(tasks, task);
    projectionWriter.flush();
    Set<UUID> changedNeighbourIds =
        interruptionNeighbours.stream()
            .map(QueueEntry::getId)
            .collect(java.util.stream.Collectors.toSet());
    Set<UUID> repositionedIds = changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry entry : streamsToLock) {
      String eventType =
          completedEntryIds.contains(entry.getId())
              ? TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED
              : changedNeighbourIds.contains(entry.getId())
                      || repositionedIds.contains(entry.getId())
                  ? TaskBoardEventTypes.QUEUE_ENTRY_CHANGED
                  : null;
      if (eventType != null) {
        eventSourcing.entryChanged(
            entry,
            streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
            eventType);
      }
    }
    eventSourcing.taskChanged(
        task, taskStreamVersion, TaskBoardEventTypes.BOARD_TASK_COMPLETED);
    return logisticsTaskMapper.toLogisticsTaskSnapshot(task);
  }

  @Transactional(readOnly = true)
  public LogisticsTaskSnapshot logisticsEquipmentMovementTask(UUID externalTaskId) {
    return logisticsTaskMapper.toLogisticsTaskSnapshot(
        ownedLogisticsEquipmentMovementTask(externalTaskId));
  }

  @Transactional
  public CancelledTaskDto cancelExternalTask(
      String sourceClientId, UUID externalTaskId, CancelTaskRequest request) {
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    return cancelTask(task.getWarehouseId(), externalTaskId, request);
  }

  @Transactional
  public LogisticsTaskSnapshot cancelLogisticsPreparationTask(
      UUID externalTaskId, CancelLogisticsPreparationTaskRequest request) {
    BoardTask task = ownedLogisticsPreparationTask(externalTaskId);
    cancelTask(
        task.getWarehouseId(),
        externalTaskId,
        new CancelTaskRequest(request.expectedTaskVersion(), "LOGISTICS_PREPARATION_CANCELLED"));
    return logisticsTaskMapper.toLogisticsTaskSnapshot(
        ownedLogisticsPreparationTask(externalTaskId));
  }

  @Transactional
  public LogisticsTaskSnapshot cancelLogisticsEquipmentMovementTask(
      UUID externalTaskId, CancelLogisticsEquipmentMovementTaskRequest request) {
    BoardTask task = ownedLogisticsEquipmentMovementTask(externalTaskId);
    cancelTask(
        task.getWarehouseId(),
        externalTaskId,
        new CancelTaskRequest(
            request.expectedTaskVersion(), LOGISTICS_EQUIPMENT_MOVEMENT_CANCEL_REASON));
    return logisticsTaskMapper.toLogisticsTaskSnapshot(
        ownedLogisticsEquipmentMovementTask(externalTaskId));
  }

  @Transactional
  public BoardTaskRegistrationDto updateExternalTaskBeforeStart(
      String sourceClientId, UUID externalTaskId, PreStartUpdateTaskRequest request) {
    lock("external-task:" + externalTaskId);
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    UUID warehouseId = task.getWarehouseId();
    lockQueueMutation(warehouseId);
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
            && assignments.existsByQueueEntryIdInAndStartedAtIsNotNull(oldEntryIds))) {
      throw new ConflictException("Маршрут или назначение задачи уже начали выполнять");
    }

    List<ResolvedRouteStep> routeSteps = resolveRoute(warehouseId, request.route(), true);
    Set<WorkQueue> affectedQueues =
        oldEntries.stream()
            .map(QueueEntry::getQueue)
            .filter(Objects::nonNull)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    routeSteps.stream()
        .map(ResolvedRouteStep::queue)
        .filter(Objects::nonNull)
        .forEach(affectedQueues::add);
    boolean affectedUnassigned =
        oldEntries.stream().anyMatch(entry -> entry.getQueue() == null)
            || routeSteps.stream().anyMatch(step -> step.queue() == null);
    lockQueuePositions(
        warehouseId,
        java.util.stream.Stream.concat(
                oldEntries.stream().map(QueueEntry::getQueue),
                routeSteps.stream().map(ResolvedRouteStep::queue))
            .toList());

    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
    if (affectedUnassigned) positionCandidates.addAll(orderedEntries(warehouseId, null));
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        lockTaskAndEntryStreams(task, positionCandidates);

    Set<UUID> deletedIds = new LinkedHashSet<>();
    for (QueueEntry oldEntry : oldEntries) {
      eventSourcing.entryDeleted(
          oldEntry,
          streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, oldEntry.getId()));
      deletedIds.add(oldEntry.getId());
    }
    projectionWriter.deleteAll(entries, oldEntries);
    projectionWriter.flush();
    affectedQueues.forEach(queue -> normalizePositions(warehouseId, queue));
    if (affectedUnassigned) normalizePositions(warehouseId, null);
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
    task.setRequestFingerprint(fingerprint(warehouseId, replacement));
    task = projectionWriter.saveAndFlush(tasks, task);

    int routeIndex = 0;
    for (ResolvedRouteStep resolved : routeSteps) {
      RouteStepRequest step = resolved.request();
      QueueEntry entry = new QueueEntry();
      entry.setTask(task);
      entry.setQueue(resolved.queue());
      entry.setQueueCode(resolved.queueCode());
      entry.setRouteIndex(routeIndex);
      entry.setEntryType(routeIndex == 0 ? EntryType.REAL : EntryType.SHADOW);
      entry.setStatus(EntryStatus.WAITING);
      entry.setQueuePosition(nextPosition(warehouseId, resolved.queue()));
      entry.setTaskText(trim(step.taskText()));
      entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
      projectionWriter.save(entries, entry);
      routeIndex++;
    }
    projectionWriter.flush();
    entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).forEach(eventSourcing::created);

    Set<UUID> repositionedIds = changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry candidate : positionCandidates) {
      if (!deletedIds.contains(candidate.getId()) && repositionedIds.contains(candidate.getId())) {
        eventSourcing.entryChanged(
            candidate,
            streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, candidate.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    eventSourcing.taskChanged(
        task,
        streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
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

  private BoardTask ownedLogisticsPreparationTask(UUID externalTaskId) {
    BoardTask task = ownedExternalTask(LOGISTICS_SOURCE_CLIENT_ID, externalTaskId);
    if (task.isCompletionDeadlineEnforced()) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
  }

  private BoardTask ownedLogisticsEquipmentMovementTask(UUID externalTaskId) {
    return requireEquipmentMovementTask(
        ownedExternalTask(LOGISTICS_SOURCE_CLIENT_ID, externalTaskId));
  }

  private BoardTask requireEquipmentMovementTask(BoardTask task) {
    if (!task.isCompletionDeadlineEnforced()) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
  }

  private NormalizedEquipmentMovementRequest normalizeEquipmentMovementRequest(
      RegisterLogisticsEquipmentMovementTaskRequest request) {
    if (request == null
        || request.warehouseId() == null
        || request.externalTaskId() == null
        || request.deadlineAt() == null) {
      throw new IllegalArgumentException("Warehouse, external task and deadline are required");
    }
    if (request.plannedDurationMinutes() != null && request.plannedDurationMinutes() < 0) {
      throw new IllegalArgumentException("Planned duration must not be negative");
    }
    String unitNumber = trim(request.unitNumber());
    if (unitNumber != null && unitNumber.length() > 64) {
      throw new IllegalArgumentException("Unit number is too long");
    }
    if (request.operations() == null
        || request.operations().isEmpty()
        || request.operations().size() > MAX_EQUIPMENT_MOVEMENT_OPERATIONS) {
      throw new IllegalArgumentException("Equipment movement must contain from one to ten operations");
    }
    List<NormalizedEquipmentMovementOperation> operations =
        request.operations().stream().map(this::normalizeEquipmentMovementOperation).toList();
    return new NormalizedEquipmentMovementRequest(unitNumber, operations);
  }

  private NormalizedEquipmentMovementOperation normalizeEquipmentMovementOperation(
      EquipmentMovementOperation operation) {
    if (operation == null || operation.direction() == null || operation.quantity() == null) {
      throw new IllegalArgumentException("Equipment movement operation is incomplete");
    }
    String code = compact(operation.equipmentCode());
    String name = compact(operation.equipmentName());
    if (code == null || !EQUIPMENT_CODE.matcher(code).matches()) {
      throw new IllegalArgumentException("Equipment code must be canonical");
    }
    if (name == null || name.length() > 255) {
      throw new IllegalArgumentException("Equipment name is invalid");
    }
    if (operation.quantity() < 1) {
      throw new IllegalArgumentException("Equipment quantity must be positive");
    }
    return new NormalizedEquipmentMovementOperation(
        operation.direction(), code, name, operation.quantity());
  }

  private String equipmentMovementTitle(String unitNumber) {
    return unitNumber == null
        ? LOGISTICS_EQUIPMENT_MOVEMENT_TITLE
        : LOGISTICS_EQUIPMENT_MOVEMENT_TITLE + " — бытовка " + unitNumber;
  }

  private String equipmentMovementText(List<NormalizedEquipmentMovementOperation> operations) {
    return operations.stream()
        .map(
            operation ->
                movementDirectionLabel(operation.direction())
                    + ": "
                    + abbreviated(operation.equipmentName(), EQUIPMENT_MOVEMENT_DISPLAY_NAME_LENGTH)
                    + " ("
                    + operation.equipmentCode()
                    + ") — "
                    + operation.quantity()
                    + " шт.")
        .collect(java.util.stream.Collectors.joining("\n"));
  }

  private String movementDirectionLabel(EquipmentMovementDirection direction) {
    return switch (direction) {
      case BRING_TO_CABIN -> "Занести в бытовку";
      case TAKE_FROM_CABIN -> "Вынести из бытовки";
    };
  }

  private String abbreviated(String value, int maxLength) {
    if (value.length() <= maxLength) return value;
    return value.substring(0, maxLength - 1) + "…";
  }

  private String equipmentMovementFingerprint(
      RegisterLogisticsEquipmentMovementTaskRequest request,
      NormalizedEquipmentMovementRequest normalized) {
    var canonical = new StringBuilder(LOGISTICS_EQUIPMENT_MOVEMENT_FINGERPRINT_SCHEMA);
    appendFingerprint(canonical, request.warehouseId());
    appendFingerprint(canonical, request.externalTaskId());
    appendFingerprint(canonical, normalized.unitNumber());
    appendFingerprint(canonical, request.plannedDurationMinutes());
    appendFingerprint(canonical, request.deadlineAt().toInstant().toString());
    appendFingerprint(canonical, normalized.operations().size());
    for (NormalizedEquipmentMovementOperation operation : normalized.operations()) {
      appendFingerprint(canonical, operation.direction());
      appendFingerprint(canonical, operation.equipmentCode());
      appendFingerprint(canonical, operation.equipmentName());
      appendFingerprint(canonical, operation.quantity());
    }
    return fingerprintDigest(canonical);
  }

  private String fingerprint(UUID warehouseId, CreateBoardTaskRequest request) {
    var canonical = new StringBuilder(REQUEST_FINGERPRINT_SCHEMA);
    appendFingerprint(canonical, warehouseId);
    appendFingerprint(canonical, request.externalTaskId());
    appendFingerprint(canonical, request.title().trim());
    appendFingerprint(canonical, trim(request.unitNumber()));
    appendFingerprint(canonical, trim(request.description()));
    appendFingerprint(canonical, request.plannedDurationMinutes());
    appendFingerprint(
        canonical,
        request.deadlineAt() == null ? null : request.deadlineAt().toInstant().toString());
    appendFingerprint(canonical, request.route().size());
    for (RouteStepRequest step : request.route()) {
      appendFingerprint(canonical, step.queueId());
      appendFingerprint(canonical, step.queueId() == null ? normalizeQueueCode(step.queueCode()) : null);
      appendFingerprint(canonical, trim(step.taskText()));
      appendFingerprint(canonical, step.plannedDurationMinutes());
    }
    return fingerprintDigest(canonical);
  }

  private String fingerprintDigest(StringBuilder canonical) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256")
                  .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
    } catch (Exception exception) {
      throw new IllegalStateException("Не удалось вычислить fingerprint задачи", exception);
    }
  }

  private void appendFingerprint(StringBuilder canonical, Object value) {
    if (value == null) {
      canonical.append("|-1:");
      return;
    }
    String text = String.valueOf(value);
    canonical.append('|').append(text.length()).append(':').append(text);
  }

  private void lockQueuePositions(UUID warehouseId, List<WorkQueue> routeQueues) {
    routeQueues.stream()
        .map(queue -> queue == null ? "warehouse:" + warehouseId + ":unassigned" : queue.getId().toString())
        .distinct()
        .sorted()
        .forEach(key -> lock("queue-position:" + key));
  }

  private void lockQueueMutation(UUID warehouseId) {
    lock("queue-position:warehouse:" + warehouseId);
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

  private List<ResolvedRouteStep> resolveRoute(
      UUID warehouseId, List<RouteStepRequest> requestedRoute, boolean allowRepeatedQueues) {
    Set<UUID> queueIds = new LinkedHashSet<>();
    Set<String> queueCodes = new LinkedHashSet<>();
    List<ResolvedRouteStep> result = new ArrayList<>();
    for (RouteStepRequest step : requestedRoute) {
      WorkQueue queue =
          step.queueId() == null ? null : registry.requireQueue(warehouseId, step.queueId());
      String queueCode = queue == null ? normalizeQueueCode(step.queueCode()) : queue.getCode();
      if (!allowRepeatedQueues
          && ((queue != null && !queueIds.add(queue.getId())) || !queueCodes.add(queueCode))) {
        throw new ConflictException("Маршрут содержит повторяющуюся очередь: " + queueCode);
      }
      result.add(new ResolvedRouteStep(step, queue, queueCode));
    }
    return result;
  }

  @Transactional(readOnly = true)
  public List<WorkerGroupDto> eligibleGroups(UUID warehouseId, UUID queueId) {
    var queue = registry.requireQueue(warehouseId, queueId);
    return workforce.eligibleGroups(queue, bindings.findAllByQueueId(queueId)).stream()
        .map(
            g -> {
              var ms =
                  members.findAllByWorkerGroupId(g.getId()).stream()
                      .map(
                          m ->
                              new GroupMemberDto(
                                  m.getId(),
                                  m.getVersion(),
                                  m.getWorker().getId(),
                                  m.getWorker().getDisplayName(),
                                  m.getRoleInGroup(),
                                  m.isActive()))
                      .toList();
              return new WorkerGroupDto(
                  g.getId(),
                  g.getVersion(),
                  g.getWarehouseId(),
                  registry.dto(g.getWorkerClass()),
                  g.getName(),
                  g.getDescription(),
                  g.isActive(),
                  ms);
            })
        .toList();
  }

  @Transactional(readOnly = true)
  public List<TimeEventDto> history(UUID warehouseId, UUID entryId) {
    requireEntry(warehouseId, entryId);
    return events.findAllByQueueEntryIdOrderByCreatedAtAsc(entryId).stream()
        .map(
            event ->
                new TimeEventDto(
                    event.getId(),
                    event.getVersion(),
                    event.getQueueEntry().getId(),
                    event.getWorker() == null ? null : event.getWorker().getId(),
                    event.getWorkerNameSnapshot(),
                    event.getGroupNameSnapshot(),
                    event.getEventType(),
                    event.getReason(),
                    event.getCreatedAt(),
                    event.getRelatedEntryId()))
        .toList();
  }

  @Transactional
  public BoardEntryDto take(
      UUID warehouseId, UUID entryId, TakeEntryRequest request, UUID authenticatedWorkerId) {
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    if (entry.getEntryType() != EntryType.REAL || entry.getStatus() != EntryStatus.WAITING)
      throw new ConflictException("Взять можно только ожидающий REAL-этап");
    ensureFirstAvailable(entry);
    if (request.workerGroupId() == null && request.workerId() == null)
      throw new ConflictException("Выберите группу или рабочего");
    WorkerGroup group =
        request.workerGroupId() == null
            ? null
            : workforce.requireGroup(warehouseId, request.workerGroupId());
    Worker selected =
        request.workerId() == null
            ? null
            : workforce.requireWorker(warehouseId, request.workerId());
    if (group != null && !group.isActive()) throw new ConflictException("Группа неактивна");
    if (selected != null && !selected.isActive()) throw new ConflictException("Рабочий неактивен");
    if (authenticatedWorkerId != null
        && (selected == null || !authenticatedWorkerId.equals(selected.getId())))
      throw new ConflictException("Worker token может взять задачу только на себя");
    var queueBindings = bindings.findAllByQueueId(entry.getQueue().getId());
    if (group != null && !workforce.eligibleGroups(entry.getQueue(), queueBindings).contains(group))
      throw new ConflictException("Группа не подходит выбранной очереди");
    if (group != null
        && selected != null
        && members.findAllByWorkerGroupIdAndActiveTrue(group.getId()).stream()
            .noneMatch(m -> m.getWorker().equals(selected)))
      throw new ConflictException("Рабочий не состоит в группе");
    if (group == null && selected != null && !queueBindings.isEmpty()) {
      var qualifiedClassIds =
          workforce.activeQualifications(selected.getId()).stream()
              .map(q -> q.getWorkerClass().getId())
              .collect(java.util.stream.Collectors.toSet());
      if (queueBindings.stream()
          .noneMatch(b -> qualifiedClassIds.contains(b.getWorkerClass().getId())))
        throw new ConflictException("Квалификация рабочего не подходит очереди");
    }
    List<Worker> assigned =
        selected != null
            ? List.of(selected)
            : members.findAllByWorkerGroupIdAndActiveTrue(group.getId()).stream()
                .map(WorkerGroupMember::getWorker)
                .filter(Worker::isActive)
                .toList();
    if (assigned.isEmpty()) throw new ConflictException("В группе нет активных рабочих");
    OffsetDateTime now = now();
    boolean stopCurrentWork = shouldStop(entry.getQueue(), group, selected);
    List<InterruptedWork> interruptionPlan =
        stopCurrentWork ? interruptibleWork(assigned, entry) : List.of();
    Set<QueueEntry> interruptedEntries =
        interruptionPlan.stream()
            .map(InterruptedWork::entry)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<QueueEntry> streamsToLock = new LinkedHashSet<>(interruptedEntries);
    streamsToLock.add(entry);
    var streamVersions = lockEntryStreams(streamsToLock);
    if (stopCurrentWork) autoInterrupt(interruptionPlan, entry, now);
    entry.setStatus(EntryStatus.IN_PROGRESS);
    entry.setActiveStartedAt(now);
    entry.setPausedAt(null);
    entry.setPauseOrigin(null);
    for (var worker : assigned) {
      var a = new TaskAssignment();
      a.setQueueEntry(entry);
      a.setWorkerGroup(group);
      a.setWorker(worker);
      a.setWorkerNameSnapshot(worker.getDisplayName());
      a.setGroupNameSnapshot(group == null ? null : group.getName());
      a.setStatus(AssignmentStatus.ACTIVE);
      a.setAssignedAt(now);
      a.setStartedAt(now);
      projectionWriter.save(assignments, a);
      event(entry, worker, group, TimeEventType.STARTED, "Задача взята в работу", null, now);
    }
    entry = projectionWriter.saveAndFlush(entries, entry);
    projectionWriter.flush();
    interruptedEntries.forEach(
        interrupted ->
            eventSourcing.entryChanged(
                interrupted,
                streamVersion(
                    streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, interrupted.getId()),
                TaskBoardEventTypes.QUEUE_ENTRY_INTERRUPTED));
    eventSourcing.entryChanged(
        entry,
        streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entryId),
        TaskBoardEventTypes.QUEUE_ENTRY_TAKEN);
    return dto(entry);
  }

  @Transactional
  public BoardEntryDto pause(
      UUID warehouseId, UUID entryId, PauseEntryRequest request, UUID authenticatedWorkerId) {
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.QUEUE_ENTRY, entryId);
    assertAssigned(entry, authenticatedWorkerId);
    if (entry.getStatus() != EntryStatus.IN_PROGRESS)
      throw new ConflictException("Поставить на паузу можно только этап в работе");
    pauseEntry(
        entry,
        PauseOrigin.MANUAL,
        trim(request.reason()) == null ? "Пауза" : trim(request.reason()),
        null,
        now());
    entry = projectionWriter.saveAndFlush(entries, entry);
    eventSourcing.entryChanged(entry, streamVersion, TaskBoardEventTypes.QUEUE_ENTRY_PAUSED);
    return dto(entry);
  }

  @Transactional
  public BoardEntryDto resume(
      UUID warehouseId, UUID entryId, VersionCommand request, UUID authenticatedWorkerId) {
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.QUEUE_ENTRY, entryId);
    assertAssigned(entry, authenticatedWorkerId);
    if (entry.getStatus() != EntryStatus.PAUSED)
      throw new ConflictException("Возобновить можно только этап на паузе");
    if (interruptions.existsByInterruptedEntryIdAndActiveTrue(entryId))
      throw new ConflictException("Этап автоматически прерван активной задачей");
    resumeEntry(entry, "Таймер возобновлен", TimeEventType.RESUMED, null, now());
    entry = projectionWriter.saveAndFlush(entries, entry);
    eventSourcing.entryChanged(entry, streamVersion, TaskBoardEventTypes.QUEUE_ENTRY_RESUMED);
    return dto(entry);
  }

  @Transactional
  public BoardEntryDto complete(
      UUID warehouseId, UUID entryId, VersionCommand request, UUID authenticatedWorkerId) {
    lockQueueMutation(warehouseId);
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    assertAssigned(entry, authenticatedWorkerId);
    if (entry.getStatus() != EntryStatus.IN_PROGRESS)
      throw new ConflictException("Завершить можно только этап в работе");
    lockQueuePositions(warehouseId, List.of(entry.getQueue()));
    List<QueueEntry> taskRoute = entries.findAllByTaskIdOrderByRouteIndexAsc(entry.getTask().getId());
    QueueEntry nextEntry =
        taskRoute.stream()
            .filter(candidate -> !candidate.equals(entry) && UNFINISHED.contains(candidate.getStatus()))
            .min(Comparator.comparingInt(QueueEntry::getRouteIndex))
            .orElse(null);
    Set<QueueEntry> resumedEntries =
        interruptions.findAllByInterruptingEntryIdAndActiveTrue(entryId).stream()
            .map(TaskAutoInterruption::getInterruptedEntry)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>(orderedEntries(warehouseId, entry.getQueue()));
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
    Set<QueueEntry> streamsToLock = new LinkedHashSet<>(positionCandidates);
    streamsToLock.addAll(resumedEntries);
    streamsToLock.add(entry);
    if (nextEntry != null) streamsToLock.add(nextEntry);
    var streamVersions =
        nextEntry == null
            ? lockTaskAndEntryStreams(entry.getTask(), streamsToLock)
            : lockEntryStreams(streamsToLock);
    OffsetDateTime now = now();
    ensureCompletionBeforeDeadline(entry.getTask(), now);
    stopTimer(entry, now);
    entry.setStatus(EntryStatus.DONE);
    entry.setDoneAt(now);
    entry.setPauseOrigin(null);
    var active =
        assignments.findAllByQueueEntryIdAndStatusIn(
            entryId, Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED));
    for (var a : active) {
      a.setStatus(AssignmentStatus.DONE);
      a.setFinishedAt(now);
      projectionWriter.save(assignments, a);
      event(
          entry,
          a.getWorker(),
          a.getWorkerGroup(),
          TimeEventType.FINISHED,
          "Этап завершен",
          null,
          now);
    }
    resolveInterruptions(entry, now);
    if (nextEntry != null) {
      nextEntry.setEntryType(EntryType.REAL);
      projectionWriter.save(entries, nextEntry);
    } else {
      entry.getTask().setStatus(TaskStatus.DONE);
      entry.getTask().setDoneAt(now);
      BoardTask completedTask = projectionWriter.saveAndFlush(tasks, entry.getTask());
      eventSourcing.taskChanged(
          completedTask,
          streamVersion(
              streamVersions, TaskBoardAggregateType.BOARD_TASK, completedTask.getId()),
          TaskBoardEventTypes.BOARD_TASK_COMPLETED);
    }
    normalizePositions(warehouseId, entry.getQueue());
    QueueEntry completedEntry = projectionWriter.saveAndFlush(entries, entry);
    Set<UUID> repositionedIds = changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry resumedEntry : resumedEntries) {
      eventSourcing.entryChanged(
          resumedEntry,
          streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, resumedEntry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_RESUMED);
    }
    if (nextEntry != null) {
      eventSourcing.entryChanged(
          nextEntry,
          streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, nextEntry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
    }
    for (QueueEntry positionCandidate : positionCandidates) {
      if (!positionCandidate.equals(entry)
          && !positionCandidate.equals(nextEntry)
          && !resumedEntries.contains(positionCandidate)
          && repositionedIds.contains(positionCandidate.getId())) {
        eventSourcing.entryChanged(
            positionCandidate,
            streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, positionCandidate.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    eventSourcing.entryChanged(
        completedEntry,
        streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entryId),
        TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED);
    return dto(completedEntry);
  }

  @Transactional
  public TaskBoardSnapshot move(UUID warehouseId, UUID entryId, MoveEntryRequest request) {
    lockQueueMutation(warehouseId);
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    if (!UNFINISHED.contains(entry.getStatus()))
      throw new ConflictException("Завершенный или отмененный этап перемещать нельзя");
    if (entry.getStatus() == EntryStatus.IN_PROGRESS)
      throw new ConflictException("Этап в работе перемещать нельзя");
    WorkQueue oldQueue = entry.getQueue();
    int oldPosition = entry.getQueuePosition();
    WorkQueue target =
        request.targetQueueId() == null
            ? null
            : registry.requireQueue(warehouseId, request.targetQueueId());
    lockQueuePositions(warehouseId, java.util.Arrays.asList(oldQueue, target));
    var duplicate =
        entries.findAllByTaskIdOrderByRouteIndexAsc(entry.getTask().getId()).stream()
            .filter(
                e ->
                    !e.equals(entry)
                        && UNFINISHED.contains(e.getStatus())
                        && Objects.equals(id(e.getQueue()), id(target)))
            .findFirst();
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>(orderedEntries(warehouseId, oldQueue));
    positionCandidates.addAll(orderedEntries(warehouseId, target));
    duplicate.ifPresent(positionCandidates::add);
    positionCandidates.add(entry);
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
    var streamVersions = lockEntryStreams(positionCandidates);
    if (duplicate.isPresent()) {
      var other = duplicate.get();
      if (entry.getEntryType() != EntryType.REAL
          || entry.getStatus() != EntryStatus.WAITING
          || other.getEntryType() != EntryType.SHADOW
          || other.getStatus() != EntryStatus.WAITING)
        throw new ConflictException("В целевой очереди уже есть незавершенный этап этой задачи");
      if (Objects.equals(id(oldQueue), id(target)))
        throw new ConflictException("В очереди уже есть другой незавершенный этап этой задачи");
      String oldCode = entry.getQueueCode();
      entry.setQueue(other.getQueue());
      entry.setQueueCode(other.getQueueCode());
      other.setQueue(oldQueue);
      other.setQueueCode(oldCode);
      other.setQueuePosition(oldPosition);
      projectionWriter.save(entries, other);
    } else {
      entry.setQueue(target);
      entry.setQueueCode(target == null ? UNASSIGNED_CODE : target.getCode());
    }
    insertAtPosition(warehouseId, entry, target, request.targetIndex());
    if (!Objects.equals(id(oldQueue), id(target))) normalizePositions(warehouseId, oldQueue);
    projectionWriter.flush();
    Set<UUID> changedIds = changedPositionIds(positionCandidates, positionsBefore);
    changedIds.add(entry.getId());
    duplicate.ifPresent(other -> changedIds.add(other.getId()));
    for (QueueEntry candidate : positionCandidates) {
      if (changedIds.contains(candidate.getId())) {
        eventSourcing.entryChanged(
            candidate,
            streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, candidate.getId()),
            candidate.equals(entry) || duplicate.filter(candidate::equals).isPresent()
                ? TaskBoardEventTypes.QUEUE_ENTRY_MOVED
                : TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    return snapshot(warehouseId, true);
  }

  private void ensureFirstAvailable(QueueEntry entry) {
    if (entry.getQueue() == null) throw new ConflictException("Назначьте этап в реальную очередь");
    var first =
        entries
            .findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
                entry.getQueue().getId(), Set.of(EntryStatus.WAITING))
            .stream()
            .filter(e -> e.getEntryType() == EntryType.REAL)
            .findFirst();
    if (first.isEmpty() || !first.get().equals(entry))
      throw new ConflictException("Сначала возьмите первый доступный этап очереди");
    var routeFirst =
        entries.findFirstByTaskIdAndStatusNotInOrderByRouteIndexAsc(
            entry.getTask().getId(), Set.of(EntryStatus.DONE, EntryStatus.CANCELLED));
    if (routeFirst.isEmpty() || !routeFirst.get().equals(entry))
      throw new ConflictException("Сначала завершите предыдущий этап маршрута");
  }

  private void assertAssigned(QueueEntry entry, UUID workerId) {
    if (workerId != null
        && assignments.findAllByQueueEntryId(entry.getId()).stream()
            .noneMatch(a -> a.getWorker() != null && workerId.equals(a.getWorker().getId())))
      throw new ConflictException("Worker token не назначен на этот этап");
  }

  private boolean shouldStop(WorkQueue queue, WorkerGroup group, Worker worker) {
    if (queue == null) return false;
    var queueBindings = bindings.findAllByQueueId(queue.getId());
    if (group != null)
      return queueBindings.stream()
          .anyMatch(b -> b.getWorkerClass().equals(group.getWorkerClass()) && b.isStopTaskOnTake());
    if (worker == null) return false;
    var qualified =
        workforce.activeQualifications(worker.getId()).stream()
            .map(q -> q.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toSet());
    return queueBindings.stream()
        .anyMatch(b -> qualified.contains(b.getWorkerClass().getId()) && b.isStopTaskOnTake());
  }

  private List<InterruptedWork> interruptibleWork(
      List<Worker> workers, QueueEntry interrupting) {
    List<InterruptedWork> result = new ArrayList<>();
    for (Worker worker : workers) {
      for (TaskAssignment assignment :
          assignments.findAllByWorkerIdAndStatusIn(
              worker.getId(), Set.of(AssignmentStatus.ACTIVE))) {
        QueueEntry candidate = assignment.getQueueEntry();
        if (!candidate.equals(interrupting) && candidate.getStatus() == EntryStatus.IN_PROGRESS) {
          result.add(new InterruptedWork(worker, candidate));
        }
      }
    }
    return result;
  }

  private Set<QueueEntry> interruptionNeighbours(Collection<QueueEntry> roots) {
    Set<QueueEntry> result = new LinkedHashSet<>();
    for (QueueEntry root : roots) {
      for (TaskAutoInterruption interruption :
          interruptions.findAllByInterruptedEntryIdOrInterruptingEntryId(
              root.getId(), root.getId())) {
        if (!interruption.isActive()) continue;
        result.add(interruption.getInterruptedEntry());
        result.add(interruption.getInterruptingEntry());
      }
    }
    return result;
  }

  private Map<TaskBoardEventStore.StreamRef, Long> lockEntryStreams(
      Collection<QueueEntry> values) {
    return eventSourcing.lockStreams(
        values.stream()
            .map(
                value ->
                    new TaskBoardEventStore.StreamRef(
                        TaskBoardAggregateType.QUEUE_ENTRY, value.getId()))
            .toList());
  }

  private Map<TaskBoardEventStore.StreamRef, Long> lockTaskAndEntryStreams(
      BoardTask task, Collection<QueueEntry> values) {
    List<TaskBoardEventStore.StreamRef> streams = new ArrayList<>();
    streams.add(
        new TaskBoardEventStore.StreamRef(TaskBoardAggregateType.BOARD_TASK, task.getId()));
    values.stream()
        .map(
            value ->
                new TaskBoardEventStore.StreamRef(
                    TaskBoardAggregateType.QUEUE_ENTRY, value.getId()))
        .forEach(streams::add);
    return eventSourcing.lockStreams(streams);
  }

  private long streamVersion(
      Map<TaskBoardEventStore.StreamRef, Long> versions,
      TaskBoardAggregateType aggregateType,
      UUID aggregateId) {
    Long version = versions.get(new TaskBoardEventStore.StreamRef(aggregateType, aggregateId));
    if (version == null) {
      throw new IllegalStateException(
          "No locked event stream for " + aggregateType + "/" + aggregateId);
    }
    return version;
  }

  private Map<UUID, QueueEntryPosition> positionsOf(Collection<QueueEntry> values) {
    return values.stream()
        .collect(
            java.util.stream.Collectors.toMap(
                QueueEntry::getId,
                value -> new QueueEntryPosition(id(value.getQueue()), value.getQueuePosition()),
                (left, right) -> left,
                java.util.LinkedHashMap::new));
  }

  private Set<UUID> changedPositionIds(
      Collection<QueueEntry> values, Map<UUID, QueueEntryPosition> before) {
    Set<UUID> result = new LinkedHashSet<>();
    for (QueueEntry value : values) {
      QueueEntryPosition old = before.get(value.getId());
      QueueEntryPosition current =
          new QueueEntryPosition(id(value.getQueue()), value.getQueuePosition());
      if (!Objects.equals(old, current)) result.add(value.getId());
    }
    return result;
  }

  private void autoInterrupt(
      List<InterruptedWork> pending, QueueEntry interrupting, OffsetDateTime now) {
    for (var old :
        pending.stream()
            .map(InterruptedWork::entry)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))
      pauseEntry(
          old, PauseOrigin.AUTO, "Рабочий переключен на другую задачу", interrupting.getId(), now);
    for (var item : pending) {
      var link = new TaskAutoInterruption();
      link.setWorker(item.worker());
      link.setInterruptedEntry(item.entry());
      link.setInterruptingEntry(interrupting);
      link.setCreatedAt(now);
      projectionWriter.save(interruptions, link);
    }
  }

  private void resolveInterruptions(QueueEntry completed, OffsetDateTime now) {
    var affected = new LinkedHashSet<QueueEntry>();
    for (var link : interruptions.findAllByInterruptingEntryIdAndActiveTrue(completed.getId())) {
      link.setActive(false);
      link.setResolvedAt(now);
      projectionWriter.save(interruptions, link);
      affected.add(link.getInterruptedEntry());
    }
    for (var old : affected)
      if (old.getStatus() == EntryStatus.PAUSED
          && old.getPauseOrigin() == PauseOrigin.AUTO
          && !interruptions.existsByInterruptedEntryIdAndActiveTrue(old.getId()))
        resumeEntry(
            old,
            "Автоматическое прерывание завершено",
            TimeEventType.AUTO_RESUMED,
            completed.getId(),
            now);
  }

  private void closeInterruptedLinks(QueueEntry cancelled, OffsetDateTime now) {
    for (var link : interruptions.findAllByInterruptedEntryIdAndActiveTrue(cancelled.getId())) {
      link.setActive(false);
      link.setResolvedAt(now);
      projectionWriter.save(interruptions, link);
    }
  }

  private void pauseEntry(
      QueueEntry entry,
      PauseOrigin origin,
      String reason,
      UUID relatedEntryId,
      OffsetDateTime now) {
    stopTimer(entry, now);
    entry.setStatus(EntryStatus.PAUSED);
    entry.setPausedAt(now);
    entry.setPauseOrigin(origin);
    for (var a :
        assignments.findAllByQueueEntryIdAndStatusIn(
            entry.getId(), Set.of(AssignmentStatus.ACTIVE))) {
      a.setStatus(AssignmentStatus.PAUSED);
      a.setPausedAt(now);
      projectionWriter.save(assignments, a);
      event(
          entry,
          a.getWorker(),
          a.getWorkerGroup(),
          origin == PauseOrigin.AUTO ? TimeEventType.AUTO_INTERRUPTED : TimeEventType.PAUSED,
          reason,
          relatedEntryId,
          now);
    }
    projectionWriter.save(entries, entry);
  }

  private void resumeEntry(
      QueueEntry entry,
      String reason,
      TimeEventType type,
      UUID relatedEntryId,
      OffsetDateTime now) {
    entry.setStatus(EntryStatus.IN_PROGRESS);
    entry.setActiveStartedAt(now);
    entry.setPausedAt(null);
    entry.setPauseOrigin(null);
    for (var a :
        assignments.findAllByQueueEntryIdAndStatusIn(
            entry.getId(), Set.of(AssignmentStatus.PAUSED))) {
      a.setStatus(AssignmentStatus.ACTIVE);
      a.setPausedAt(null);
      projectionWriter.save(assignments, a);
      event(entry, a.getWorker(), a.getWorkerGroup(), type, reason, relatedEntryId, now);
    }
    projectionWriter.save(entries, entry);
  }

  private void stopTimer(QueueEntry entry, OffsetDateTime now) {
    if (entry.getActiveStartedAt() != null) {
      entry.setActiveWorkSeconds(
          entry.getActiveWorkSeconds()
              + Math.max(0, Duration.between(entry.getActiveStartedAt(), now).toSeconds()));
      entry.setActiveStartedAt(null);
    }
  }

  private void event(
      QueueEntry entry,
      Worker worker,
      WorkerGroup group,
      TimeEventType type,
      String reason,
      UUID related,
      OffsetDateTime now) {
    var e = new TaskTimeEvent();
    e.setQueueEntry(entry);
    e.setWorker(worker);
    e.setWorkerNameSnapshot(worker == null ? null : worker.getDisplayName());
    e.setGroupNameSnapshot(group == null ? null : group.getName());
    e.setEventType(type);
    e.setReason(reason);
    e.setRelatedEntryId(related);
    e.setCreatedAt(now);
    projectionWriter.save(events, e);
  }

  private QueueEntry requireEntry(UUID warehouseId, UUID id) {
    var e = entries.findById(id).orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!e.getTask().getWarehouseId().equals(warehouseId))
      throw new NotFoundException("Этап не найден");
    return e;
  }

  private List<QueueEntry> activeEntries(UUID warehouseId) {
    var result = new ArrayList<QueueEntry>();
    for (var task : tasks.findAllByWarehouseIdAndStatusIn(warehouseId, Set.of(TaskStatus.ACTIVE))) {
      List<QueueEntry> route =
          entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
              .filter(e -> UNFINISHED.contains(e.getStatus()))
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

  private int nextPosition(UUID warehouseId, WorkQueue q) {
    return orderedEntries(warehouseId, q).stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .mapToInt(QueueEntry::getQueuePosition)
            .max()
            .orElse(-1)
        + 1;
  }

  private void normalizePositions(UUID warehouseId, WorkQueue q) {
    int i = 0;
    for (var e : orderedEntries(warehouseId, q))
      if (UNFINISHED.contains(e.getStatus())) {
        e.setQueuePosition(i++);
        projectionWriter.save(entries, e);
      }
  }

  private void insertAtPosition(
      UUID warehouseId, QueueEntry entry, WorkQueue target, int requestedIndex) {
    var ordered =
        new ArrayList<>(
            orderedEntries(warehouseId, target).stream()
                .filter(e -> !e.equals(entry) && UNFINISHED.contains(e.getStatus()))
                .toList());
    int index = Math.max(0, Math.min(requestedIndex, ordered.size()));
    ordered.add(index, entry);
    entry.setQueue(target);
    int position = 0;
    for (var current : ordered) {
      current.setQueuePosition(position++);
      projectionWriter.save(entries, current);
    }
  }

  private List<QueueEntry> orderedEntries(UUID warehouseId, WorkQueue q) {
    return q == null
        ? entries.findAllByQueueIsNullAndTask_WarehouseIdOrderByQueuePositionAsc(warehouseId)
        : entries.findAllByQueueIdOrderByQueuePositionAsc(q.getId());
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

  private String normalizeQueueCode(String v) {
    String value = trim(v);
    return value == null ? UNASSIGNED_CODE : value.toUpperCase(java.util.Locale.ROOT);
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  private void ensureCompletionBeforeDeadline(BoardTask task, OffsetDateTime completionAt) {
    if (!task.isCompletionDeadlineEnforced()) return;
    OffsetDateTime deadline = task.getDeadlineAt();
    if (deadline == null || !completionAt.isBefore(deadline)) {
      throw new ConflictException("Срок резерва мебели истек: завершение задания недоступно");
    }
  }

  private BoardEntryDto dto(QueueEntry e) {
    var as =
        assignments.findAllByQueueEntryId(e.getId()).stream()
            .map(
                a ->
                    new AssignmentDto(
                        a.getId(),
                        a.getVersion(),
                        a.getWorker() == null ? null : a.getWorker().getId(),
                        a.getWorkerNameSnapshot(),
                        a.getWorkerGroup() == null ? null : a.getWorkerGroup().getId(),
                        a.getGroupNameSnapshot(),
                        a.getStatus(),
                        a.getAssignedAt(),
                        a.getStartedAt(),
                        a.getPausedAt(),
                        a.getFinishedAt()))
            .toList();
    var t = e.getTask();
    return new BoardEntryDto(
        e.getId(),
        e.getVersion(),
        t.getId(),
        t.getExternalTaskId(),
        t.getVersion(),
        t.getTitle(),
        t.getUnitNumber(),
        t.getStatus(),
        id(e.getQueue()),
        e.getQueueCode(),
        e.getRouteIndex(),
        e.getQueuePosition(),
        e.getEntryType(),
        e.getStatus(),
        e.getTaskText(),
        e.getPlannedDurationMinutes(),
        e.getActiveStartedAt(),
        e.getPausedAt(),
        e.getActiveWorkSeconds(),
        as);
  }

  private CancelledTaskDto cancelledTaskDto(BoardTask task) {
    return new CancelledTaskDto(
        task.getId(),
        task.getExternalTaskId(),
        task.getVersion(),
        task.getStatus(),
        task.getDoneAt());
  }

  private BoardTaskRegistrationDto registrationDto(BoardTask task) {
    var route =
        entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
            .map(
                entry ->
                    new RegisteredRouteStepDto(
                        entry.getId(),
                        entry.getVersion(),
                        entry.getQueue() == null ? null : entry.getQueue().getId(),
                        entry.getQueueCode(),
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
        task.getDoneAt(),
        route);
  }

  private record InterruptedWork(Worker worker, QueueEntry entry) {}

  private record QueueEntryPosition(UUID queueId, int position) {}

  private record ResolvedRouteStep(RouteStepRequest request, WorkQueue queue, String queueCode) {}

  private record NormalizedEquipmentMovementRequest(
      String unitNumber, List<NormalizedEquipmentMovementOperation> operations) {
    private NormalizedEquipmentMovementRequest {
      operations = List.copyOf(operations);
    }
  }

  private record NormalizedEquipmentMovementOperation(
      EquipmentMovementDirection direction, String equipmentCode, String equipmentName, long quantity) {}
}
