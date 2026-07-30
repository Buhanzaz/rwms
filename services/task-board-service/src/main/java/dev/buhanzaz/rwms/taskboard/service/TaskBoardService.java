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
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
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
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class TaskBoardService {
  private static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final String MAINTENANCE_SOURCE_CLIENT_ID = "maintenance-service";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_TITLE = "Перемещение мебели";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_CANCEL_REASON =
      "LOGISTICS_EQUIPMENT_MOVEMENT_CANCELLED";
  private static final String LOGISTICS_EQUIPMENT_MOVEMENT_FINGERPRINT_SCHEMA =
      "task-board-logistics-equipment-movement:v1";
  private static final ZoneId DEFAULT_SCHEDULE_ZONE = ZoneId.of("Europe/Moscow");
  private static final int MAX_EQUIPMENT_MOVEMENT_OPERATIONS = 10;
  private static final int EQUIPMENT_MOVEMENT_DISPLAY_NAME_LENGTH = 64;
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
  private final ObjectMapper objectMapper;
  private final TaskBoardEntryOwnerProofService ownerProofs;
  private final WarehouseKpiClock kpiClock;
  private final GroupKpiEvidenceService kpiEvidence;

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
      LogisticsTaskResponseMapper logisticsTaskMapper,
      ObjectMapper objectMapper,
      TaskBoardEntryOwnerProofService ownerProofs,
      WarehouseKpiClock kpiClock,
      GroupKpiEvidenceService kpiEvidence) {
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
    this.objectMapper = objectMapper;
    this.ownerProofs = ownerProofs;
    this.kpiClock = kpiClock;
    this.kpiEvidence = kpiEvidence;
  }

  @Transactional(readOnly = true)
  public TaskBoardSnapshot snapshot(
      UUID warehouseId, LocalDate requestedDate, boolean includeShadow) {
    var columns = new ArrayList<BoardColumnDto>();
    var allEntries = activeEntries(warehouseId);
    List<LocalDate> availableDates =
        allEntries.stream()
            .map(entry -> entry.getTask().getScheduledDate())
            .distinct()
            .sorted()
            .toList();
    LocalDate selectedDate = selectDate(requestedDate, availableDates);
    var selectedEntries =
        allEntries.stream()
            .filter(entry -> Objects.equals(entry.getTask().getScheduledDate(), selectedDate))
            .toList();
    Map<UUID, TaskSourceReferenceDto> sources = sourceReferences(selectedEntries);
    for (var queue :
        queues.findAllActiveOrderedByWarehouseId(warehouseId)) {
      if (queue.isHidden()) continue;
      var cards =
          selectedEntries.stream()
              .filter(e -> e.getQueue() != null && e.getQueue().getId().equals(queue.getId()))
              .filter(e -> includeShadow || e.getEntryType() == EntryType.REAL)
              .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
              .map(entry -> dto(entry, sources.get(entry.getTask().getId())))
              .toList();
      columns.add(
          new BoardColumnDto(
              queue.getId(),
              queue.getName(),
              queue.getType(),
              queue.getSortOrder(),
              cards));
    }
    return new TaskBoardSnapshot(warehouseId, selectedDate, availableDates, columns);
  }

  @Transactional(readOnly = true)
  public TaskBoardSnapshot snapshot(UUID warehouseId, boolean includeShadow) {
    return snapshot(warehouseId, null, includeShadow);
  }

  @Transactional(readOnly = true)
  public BoardEntryDto entry(UUID warehouseId, UUID entryId) {
    return dto(requireEntry(warehouseId, entryId));
  }

  @Transactional(readOnly = true)
  public TaskWorkerContentDto workerContent(UUID warehouseId, UUID entryId) {
    QueueEntry entry = requireEntry(warehouseId, entryId);
    return new TaskWorkerContentDto(
        readList(entry.getWorkerWorks(), new TypeReference<>() {}),
        readList(entry.getWorkerMaterials(), new TypeReference<>() {}),
        readList(entry.getWorkerComments(), new TypeReference<>() {}),
        readList(entry.getSourceMediaReferences(), new TypeReference<>() {}));
  }

  @Transactional
  public TaskBoardSnapshot createTask(UUID warehouseId, CreateBoardTaskRequest request) {
    BoardTask task = createTask(warehouseId, request, null, false);
    return snapshot(warehouseId, task.getScheduledDate(), true);
  }

  @Transactional
  public BoardTaskRegistrationDto registerExternalTask(
      String sourceClientId, RegisterExternalTaskRequest request) {
    TaskSourceReferenceDto source = sourceReferenceFor(sourceClientId, request.source());
    CreateBoardTaskRequest createRequest =
        new CreateBoardTaskRequest(
            request.externalTaskId(),
            request.title(),
            request.unitNumber(),
            request.description(),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            request.route(),
            request.scheduledDate(),
            request.priority());
    Integer dailyCapacity = request.dailyCapacity();
    BoardTask task =
        createTask(
            request.warehouseId(),
            createRequest,
            sourceClientId,
            true,
            false,
            null,
            dailyCapacity,
            source);
    return registrationDto(task);
  }

  /**
   * Registers a source-owned task whose generated detail is limited to typed furniture operations.
   * The persisted flag fences completion at the reservation deadline without changing generic tasks.
   */
  @Transactional
  public LogisticsTaskSnapshot registerLogisticsEquipmentMovementTask(
      RegisterLogisticsEquipmentMovementTaskRequest request) {
    NormalizedEquipmentMovementRequest normalized = normalizeEquipmentMovementRequest(request);
    WorkQueue queue = requireFurnitureMovementQueue(request.warehouseId());
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
                        queue.getDefinition().getId(),
                        equipmentMovementText(normalized.operations()),
                        request.plannedDurationMinutes()))),
            LOGISTICS_SOURCE_CLIENT_ID,
            false,
            true,
            equipmentMovementFingerprint(request, normalized));
    return logisticsTaskMapper.toLogisticsTaskSnapshot(requireEquipmentMovementTask(task));
  }

  private WorkQueue requireFurnitureMovementQueue(UUID warehouseId) {
    List<WorkQueue> candidates =
        queues.findAllActiveOrderedByWarehouseId(warehouseId).stream()
            .filter(queue -> !queue.isHidden())
            .filter(queue -> queue.getType() == QueueType.FURNITURE_MOVEMENT)
            .toList();
    if (candidates.size() != 1) {
      throw new ConflictException(
          "Настройте ровно одну активную видимую очередь типа «Перемещение мебели»");
    }
    return candidates.getFirst();
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues) {
    return createTask(
        warehouseId, request, sourceClientId, allowRepeatedQueues, false, null, null, null);
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues,
      boolean completionDeadlineEnforced,
      String suppliedFingerprint) {
    return createTask(
        warehouseId,
        request,
        sourceClientId,
        allowRepeatedQueues,
        completionDeadlineEnforced,
        suppliedFingerprint,
        null,
        null);
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues,
      boolean completionDeadlineEnforced,
      String suppliedFingerprint,
      Integer dailyCapacity,
      TaskSourceReferenceDto sourceReference) {
    if (request.externalTaskId() != null) {
      lock("external-task:" + request.externalTaskId());
      var existing = tasks.findByExternalTaskId(request.externalTaskId());
      if (existing.isPresent()) {
        BoardTask task = existing.get();
        String requestFingerprint =
            suppliedFingerprint == null
                ? fingerprint(
                    warehouseId,
                    request,
                    (MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId)
                            && dailyCapacity != null)
                        || request.scheduledDate() == null
                        ? task.getScheduledDate()
                        : request.scheduledDate(),
                    request.priority() == null ? task.getPriority() : priority(request.priority()))
                : suppliedFingerprint;
        if (warehouseId.equals(task.getWarehouseId())
            && task.getRequestFingerprint() != null
            && task.getRequestFingerprint().equals(requestFingerprint)) {
          requireExactSourceReference(task, sourceClientId, sourceReference);
          return task;
        }
        throw new ConflictException("Задача с externalTaskId уже существует с другими данными");
      }
    }
    lockQueueMutation(warehouseId);
    List<ResolvedRouteStep> routeSteps =
        resolveRoute(warehouseId, request.route(), allowRepeatedQueues, sourceClientId);
    lockQueuePositions(warehouseId, routeSteps.stream().map(ResolvedRouteStep::queue).toList());
    LocalDate scheduledDate = scheduledDate(warehouseId, request, sourceClientId, dailyCapacity);
    Set<QueueEntry> existingEntries = new LinkedHashSet<>();
    routeSteps.stream()
        .map(ResolvedRouteStep::queue)
        .distinct()
        .forEach(
            queue ->
                existingEntries.addAll(orderedEntries(warehouseId, queue, scheduledDate)));
    Map<UUID, QueueEntryPosition> existingPositions = positionsOf(existingEntries);
    Map<TaskBoardEventStore.StreamRef, Long> existingStreamVersions =
        existingEntries.isEmpty() ? Map.of() : lockEntryStreams(existingEntries);
    int effectivePriority = priority(request.priority());
    String requestFingerprint =
        suppliedFingerprint == null
            ? fingerprint(warehouseId, request, scheduledDate, effectivePriority)
            : suppliedFingerprint;
    var task = new BoardTask();
    task.setWarehouseId(warehouseId);
    task.setExternalTaskId(request.externalTaskId());
    task.setTitle(request.title().trim());
    task.setUnitNumber(trim(request.unitNumber()));
    task.setDescription(trim(request.description()));
    task.setPlannedDurationMinutes(request.plannedDurationMinutes());
    task.setDeadlineAt(request.deadlineAt());
    task.setScheduledDate(scheduledDate);
    task.setPriority(effectivePriority);
    task.setPinned(false);
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
          new TaskSyncSource(
              task.getId(),
              request.externalTaskId(),
              sourceClientId,
              sourceReference == null ? null : sourceReference.type(),
              sourceReference == null ? null : sourceReference.sourceId()));
    }
    int route = 0;
    for (var resolved : routeSteps) {
      RouteStepRequest step = resolved.request();
      WorkQueue queue = resolved.queue();
      var entry = new QueueEntry();
      entry.setTask(task);
      entry.setQueue(queue);
      entry.setRouteIndex(route);
      entry.setEntryType(route == 0 ? EntryType.REAL : EntryType.SHADOW);
      entry.setStatus(EntryStatus.WAITING);
      entry.setQueuePosition(nextPosition(warehouseId, queue, task.getScheduledDate()));
      entry.setTaskText(trim(step.taskText()));
      entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
      setWorkerContent(entry, step);
      projectionWriter.save(entries, entry);
      route++;
    }
    for (QueueEntry entry : entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId())) {
      insertByPriority(warehouseId, entry);
    }
    projectionWriter.flush();
    eventSourcing.created(task);
    entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).forEach(eventSourcing::created);
    for (QueueEntry existing : existingEntries) {
      if (changedPositionIds(List.of(existing), existingPositions).contains(existing.getId())) {
        eventSourcing.entryChanged(
            existing,
            streamVersion(
                existingStreamVersions, TaskBoardAggregateType.QUEUE_ENTRY, existing.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
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
    Set<UUID> kpiGroups = new LinkedHashSet<>();
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
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
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
      kpiGroups.addAll(kpiEvidence.returnSegment(warehouseId, entry, now));
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
    task.setStatus(TaskStatus.CANCELLED);
    task.setDoneAt(now);
    task = projectionWriter.saveAndFlush(tasks, task);
    projectionWriter.flush();
    taskEntries.stream()
        .filter(entry -> cancelledEntryIds.contains(entry.getId()))
        .forEach(entry -> ownerProofs.publish(warehouseId, entry.getId(), false));
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
    kpiGroups.forEach(groupId -> kpiEvidence.refreshGroup(warehouseId, groupId, now));
    kpiEvidence.refreshWarehouse(warehouseId, now);
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

    List<ResolvedRouteStep> routeSteps =
        resolveRoute(warehouseId, request.route(), true, sourceClientId);
    Set<WorkQueue> affectedQueues =
        oldEntries.stream()
            .map(QueueEntry::getQueue)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    routeSteps.stream()
        .map(ResolvedRouteStep::queue)
        .forEach(affectedQueues::add);
    lockQueuePositions(
        warehouseId,
        java.util.stream.Stream.concat(
                oldEntries.stream().map(QueueEntry::getQueue),
                routeSteps.stream().map(ResolvedRouteStep::queue))
            .toList());

    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
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
        fingerprint(warehouseId, replacement, task.getScheduledDate(), task.getPriority()));
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
          nextPosition(warehouseId, resolved.queue(), task.getScheduledDate()));
      entry.setTaskText(trim(step.taskText()));
      entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
      setWorkerContent(entry, step);
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

  @Transactional
  public BoardTaskRegistrationDto relocateExternalTask(
      String sourceClientId,
      UUID externalTaskId,
      RelocateExternalTaskRequest request) {
    lock("external-task:" + externalTaskId);
    var replay =
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
            .findFirst();
    if (replay.isPresent()) {
      BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
      if (!task.getWarehouseId().equals(request.targetWarehouseId())
          || task.getVersion() != replay.orElseThrow()) {
        throw new ConflictException(
            "Состояние задачи не соответствует сохранённому результату переноса");
      }
      return registrationDto(task);
    }

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
        .forEach(this::lockQueueMutation);
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

    lockQueuePositions(
        targetWarehouseId, targetByDefinitionId.values().stream().distinct().toList());
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        lockTaskAndEntryStreams(task, unfinishedRoute);
    UUID taskId = task.getId();
    Map<UUID, Integer> nextPositions = new java.util.LinkedHashMap<>();
    for (WorkQueue target : targetByDefinitionId.values()) {
      int next =
          orderedEntries(targetWarehouseId, target, task.getScheduledDate()).stream()
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
      task.setRequestFingerprint(fingerprint(task, route));
    }
    task = projectionWriter.saveAndFlush(tasks, task);
    projectionWriter.flush();

    eventSourcing.taskChanged(
        task,
        streamVersion(
            streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
    for (QueueEntry entry : unfinishedRoute) {
      eventSourcing.entryChanged(
          entry,
          streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_MOVED);
      ownerProofs.publish(
          targetWarehouseId,
          entry.getId(),
          true);
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

  private TaskSourceReferenceDto sourceReferenceFor(
      String sourceClientId, TaskSourceReferenceDto source) {
    if (source == null) return null;
    if (source.type() == null
        || source.sourceId() == null
        || !MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId)
        || source.type() != TaskSourceType.MAINTENANCE_REPAIR) {
      throw new IllegalArgumentException(
          "Только maintenance-service может регистрировать источник ремонта");
    }
    return source;
  }

  private void requireExactSourceReference(
      BoardTask task, String sourceClientId, TaskSourceReferenceDto requested) {
    var registered = taskSyncSources.findById(task.getId());
    if (sourceClientId == null) {
      if (registered.isPresent() || requested != null) {
        throw new ConflictException("Источник задачи не совпадает с зарегистрированным владельцем");
      }
      return;
    }
    TaskSyncSource source =
        registered.orElseThrow(
            () -> new ConflictException("Не найден источник синхронизации задачи"));
    if (!Objects.equals(sourceClientId, source.getSourceClientId())) {
      throw new ConflictException("Источник задачи не совпадает с зарегистрированным владельцем");
    }
    boolean matches =
        requested == null
            ? !source.hasSourceReference()
            : source.hasSourceReference(requested.type(), requested.sourceId());
    if (!matches) {
      throw new ConflictException("Источник задачи нельзя заменить");
    }
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
    String name = compact(operation.equipmentName());
    if (operation.equipmentId() == null) {
      throw new IllegalArgumentException("Equipment identifier is required");
    }
    if (name == null || name.length() > 255) {
      throw new IllegalArgumentException("Equipment name is invalid");
    }
    if (operation.quantity() < 1) {
      throw new IllegalArgumentException("Equipment quantity must be positive");
    }
    return new NormalizedEquipmentMovementOperation(
        operation.direction(), operation.equipmentId(), name, operation.quantity());
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
                    + " — "
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
      appendFingerprint(canonical, operation.equipmentId());
      appendFingerprint(canonical, operation.equipmentName());
      appendFingerprint(canonical, operation.quantity());
    }
    return fingerprintDigest(canonical);
  }

  private String fingerprint(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      LocalDate scheduledDate,
      int priority) {
    ObjectNode payload =
        taskFingerprintPayload(
            warehouseId,
            request.externalTaskId(),
            request.title().trim(),
            trim(request.unitNumber()),
            trim(request.description()),
            request.plannedDurationMinutes(),
            request.deadlineAt(),
            scheduledDate,
            priority);
    ArrayNode route = payload.putArray("route");
    for (RouteStepRequest step : request.route()) {
      ObjectNode item = route.addObject();
      setFingerprintValue(item, "queueDefinitionId", step.queueDefinitionId());
      setFingerprintValue(item, "taskText", trim(step.taskText()));
      setFingerprintValue(item, "plannedDurationMinutes", step.plannedDurationMinutes());
      setFingerprintValue(item, "works", step.works());
      setFingerprintValue(item, "materials", step.materials());
      setFingerprintValue(item, "comments", step.comments());
      setFingerprintValue(item, "sourceMedia", step.sourceMedia());
    }
    return canonicalRequestFingerprint(payload);
  }

  private String fingerprint(BoardTask task, List<QueueEntry> routeEntries) {
    ObjectNode payload =
        taskFingerprintPayload(
            task.getWarehouseId(),
            task.getExternalTaskId(),
            task.getTitle(),
            task.getUnitNumber(),
            task.getDescription(),
            task.getPlannedDurationMinutes(),
            task.getDeadlineAt(),
            task.getScheduledDate(),
            task.getPriority());
    ArrayNode route = payload.putArray("route");
    for (QueueEntry entry : routeEntries) {
      ObjectNode item = route.addObject();
      setFingerprintValue(item, "queueDefinitionId", entry.getQueue().getDefinition().getId());
      setFingerprintValue(item, "taskText", entry.getTaskText());
      setFingerprintValue(item, "plannedDurationMinutes", entry.getPlannedDurationMinutes());
      item.set("works", readJson(entry.getWorkerWorks()));
      item.set("materials", readJson(entry.getWorkerMaterials()));
      item.set("comments", readJson(entry.getWorkerComments()));
      item.set("sourceMedia", readJson(entry.getSourceMediaReferences()));
    }
    return canonicalRequestFingerprint(payload);
  }

  private ObjectNode taskFingerprintPayload(
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      LocalDate scheduledDate,
      int priority) {
    ObjectNode payload = objectMapper.createObjectNode();
    setFingerprintValue(payload, "warehouseId", warehouseId);
    setFingerprintValue(payload, "externalTaskId", externalTaskId);
    setFingerprintValue(payload, "title", title);
    setFingerprintValue(payload, "unitNumber", unitNumber);
    setFingerprintValue(payload, "description", description);
    setFingerprintValue(payload, "plannedDurationMinutes", plannedDurationMinutes);
    setFingerprintValue(payload, "deadlineEpochMicros", deadlineEpochMicros(deadlineAt));
    setFingerprintValue(payload, "scheduledDate", scheduledDate);
    setFingerprintValue(payload, "priority", priority);
    return payload;
  }

  private void setFingerprintValue(ObjectNode object, String name, Object value) {
    object.set(name, objectMapper.valueToTree(value));
  }

  private Long deadlineEpochMicros(OffsetDateTime deadlineAt) {
    if (deadlineAt == null) return null;
    var instant = deadlineAt.toInstant();
    return Math.addExact(
        Math.multiplyExact(instant.getEpochSecond(), 1_000_000L),
        instant.getNano() / 1_000L);
  }

  private String canonicalRequestFingerprint(ObjectNode payload) {
    String fingerprint =
        jdbc.queryForObject(
            "select public.task_board_request_fingerprint_v4(cast(? as jsonb))",
            String.class,
            write(payload));
    if (fingerprint == null) {
      throw new IllegalStateException("Не удалось вычислить fingerprint задачи");
    }
    return fingerprint;
  }

  private JsonNode readJson(String value) {
    try {
      return objectMapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый снимок задания для рабочего повреждён", exception);
    }
  }

  private void setWorkerContent(QueueEntry entry, RouteStepRequest step) {
    entry.setWorkerWorks(write(step.works()));
    entry.setWorkerMaterials(write(step.materials()));
    entry.setWorkerComments(write(step.comments()));
    entry.setSourceMediaReferences(write(step.sourceMedia()));
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Снимок задания для рабочего не сериализуется", exception);
    }
  }

  private <T> List<T> readList(String value, TypeReference<List<T>> type) {
    try {
      return List.copyOf(objectMapper.readValue(value, type));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый снимок задания для рабочего повреждён", exception);
    }
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
        .map(queue -> queue.getId().toString())
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
      UUID warehouseId,
      List<RouteStepRequest> requestedRoute,
      boolean allowRepeatedQueues,
      String sourceClientId) {
    Set<UUID> queueIds = new LinkedHashSet<>();
    List<ResolvedRouteStep> result = new ArrayList<>();
    for (RouteStepRequest step : requestedRoute) {
      WorkQueue queue = resolveRouteQueue(warehouseId, step);
      if (!allowRepeatedQueues && !queueIds.add(queue.getId())) {
        throw new ConflictException("Маршрут содержит повторяющуюся очередь: " + queue.getName());
      }
      result.add(new ResolvedRouteStep(step, queue));
    }
    return result;
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

  @Transactional(readOnly = true)
  public List<WorkerGroupDto> eligibleGroups(UUID warehouseId, UUID queueId) {
    var queue = registry.requireQueue(warehouseId, queueId);
    return workforce
        .eligibleGroups(queue, bindings.findAllByQueueId(queueId))
        .stream()
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
                  g.getOperationalStatus(),
                  g.getUnavailableSince(),
                  g.getUnavailabilityReason(),
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
  public List<UUID> returnActiveWorkForGroup(UUID warehouseId, UUID groupId) {
    workforce.requireGroup(warehouseId, groupId);
    List<QueueEntry> affected =
        assignments
            .findAllByWorkerGroupIdAndStatusIn(
                groupId, Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
            .stream()
            .map(TaskAssignment::getQueueEntry)
            .distinct()
            .toList();
    if (affected.isEmpty()) {
      return List.of();
    }
    OffsetDateTime returnedAt = now();
    Set<UUID> kpiGroups = new LinkedHashSet<>();
    Map<TaskBoardEventStore.StreamRef, Long> streamVersions =
        lockEntryStreams(affected);
    for (QueueEntry entry : affected) {
      if (!entry.getTask().getWarehouseId().equals(warehouseId)) {
        throw new ConflictException("Активное задание группы относится к другому складу");
      }
      kpiGroups.addAll(kpiEvidence.returnSegment(warehouseId, entry, returnedAt));
      stopTimer(entry, returnedAt);
      for (TaskAssignment assignment :
          assignments.findAllByQueueEntryIdAndStatusIn(
              entry.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))) {
        assignment.setStatus(AssignmentStatus.CANCELLED);
        assignment.setPausedAt(null);
        assignment.setFinishedAt(returnedAt);
        projectionWriter.save(assignments, assignment);
        event(
            entry,
            assignment.getWorker(),
            assignment.getWorkerGroup(),
            TimeEventType.CANCELLED,
            "Задание возвращено из-за отключения группы",
            null,
            returnedAt);
      }
      closeReturnInterruptions(entry, returnedAt);
      Long originalBudget = entry.getOriginalBudgetSeconds();
      Long currentBudget = entry.getCurrentBudgetSeconds();
      if (originalBudget != null && currentBudget != null) {
        long remaining = currentBudget - entry.getActiveWorkSeconds();
        entry.resetResponsibilitySegment(remaining > 0 ? remaining : originalBudget);
      } else {
        entry.setActiveWorkSeconds(0);
      }
      entry.setStatus(EntryStatus.WAITING);
      entry.setActiveStartedAt(null);
      entry.setPausedAt(null);
      entry.setDoneAt(null);
      entry.setPauseOrigin(null);
      Integer front =
          jdbc.queryForObject(
              """
              select coalesce(min(queue_position),0) - 1
                from queue_entry
               where queue_id=? and status='WAITING' and id<>?
              """,
              Integer.class,
              entry.getQueue().getId(),
              entry.getId());
      entry.setQueuePosition(front == null ? 0 : front);
      entry = projectionWriter.saveAndFlush(entries, entry);
      eventSourcing.entryChanged(
          entry,
          streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_RETURNING);
      ownerProofs.publish(warehouseId, entry.getId(), false);
    }
    kpiGroups.forEach(
        affectedGroup ->
            kpiEvidence.refreshGroup(warehouseId, affectedGroup, returnedAt));
    return affected.stream().map(QueueEntry::getId).toList();
  }

  @Transactional
  public BoardEntryDto take(
      UUID warehouseId, UUID entryId, TakeEntryRequest request, UUID authenticatedWorkerId) {
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    boolean joiningUrgent = entry.getStatus() == EntryStatus.IN_PROGRESS;
    if (entry.getEntryType() != EntryType.REAL
        || (!joiningUrgent && entry.getStatus() != EntryStatus.WAITING)) {
      throw new ConflictException(
          "Взять можно ожидающий этап или присоединиться к срочному этапу в работе");
    }
    if (!joiningUrgent) ensureFirstAvailable(entry);
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
    if (authenticatedWorkerId != null) {
      if (selected == null || !authenticatedWorkerId.equals(selected.getId()))
        throw new ConflictException("Worker token может взять задачу только на себя");
      WorkerGroup currentGroup = selected.getCurrentGroup();
      if (currentGroup == null) {
        throw new ConflictException("Рабочему не назначена текущая группа");
      }
      if (group != null && !group.equals(currentGroup)) {
        throw new ConflictException("Задачу можно взять только текущей группой рабочего");
      }
      group = currentGroup;
    } else if (group == null && selected != null && selected.getCurrentGroup() != null) {
      group = selected.getCurrentGroup();
    }
    if (group != null && !group.isActive()) throw new ConflictException("Группа неактивна");
    if (group != null
        && group.getOperationalStatus() != GroupOperationalStatus.AVAILABLE) {
      throw new ConflictException("Группа временно недоступна");
    }
    if (selected != null && !selected.isActive()) throw new ConflictException("Рабочий неактивен");
    WorkerGroup assignedGroup = group;
    var queueBindings =
        bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(entry.getQueue().getId());
    if (assignedGroup != null
        && selected == null
        && !workforce.eligibleGroups(entry.getQueue(), queueBindings).contains(assignedGroup)) {
      throw new ConflictException("Группа не подходит выбранной очереди");
    }
    if (assignedGroup != null
        && selected != null
        && members.findAllByWorkerGroupIdAndActiveTrue(assignedGroup.getId()).stream()
            .noneMatch(m -> m.getWorker().equals(selected)))
      throw new ConflictException("Рабочий не состоит в группе");
    WorkQueueClassBinding takeBinding =
        bindingForTake(queueBindings, assignedGroup, selected, joiningUrgent);
    if (!queueBindings.isEmpty() && takeBinding == null) {
      throw new ConflictException(
          joiningUrgent
              ? "Присоединиться может только вторичный класс исполнителей"
              : "Начать задание может только основной класс исполнителей");
    }
    if (joiningUrgent && (takeBinding == null || !takeBinding.isNotifyUrgent())) {
      throw new ConflictException("Для этого класса срочное присоединение не настроено");
    }
    List<Worker> assigned =
        assignedGroup != null
            ? members.findAllByWorkerGroupIdAndActiveTrue(assignedGroup.getId()).stream()
                .map(WorkerGroupMember::getWorker)
                .filter(Worker::isActive)
                .filter(
                    worker ->
                        authenticatedWorkerId == null
                            || assignedGroup.equals(worker.getCurrentGroup()))
                .toList()
            : List.of(selected);
    var existingWorkerIds =
        assignments.findAllByQueueEntryId(entryId).stream()
            .filter(
                assignment ->
                    assignment.getStatus() == AssignmentStatus.ACTIVE
                        || assignment.getStatus() == AssignmentStatus.PAUSED)
            .map(assignment -> assignment.getWorker().getId())
            .collect(java.util.stream.Collectors.toSet());
    assigned =
        assigned.stream()
            .filter(worker -> !existingWorkerIds.contains(worker.getId()))
            .toList();
    if (assigned.isEmpty()) {
      throw new ConflictException(
          selected == null
              ? "В группе нет новых активных рабочих"
              : "Рабочий уже назначен на это задание");
    }
    OffsetDateTime now = now();
    if (assignedGroup != null) {
      kpiEvidence.refreshGroup(warehouseId, assignedGroup.getId(), now);
    }
    boolean stopCurrentWork = takeBinding != null && takeBinding.isStopTaskOnTake();
    if (assignedGroup != null && !stopCurrentWork) {
      boolean alreadyWorking =
          assignments
              .findAllByWorkerGroupIdAndStatusIn(
                  assignedGroup.getId(),
                  Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
              .stream()
              .anyMatch(value -> !value.getQueueEntry().getId().equals(entryId));
      if (alreadyWorking) {
        throw new ConflictException("У группы уже есть активное задание");
      }
    }
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
    if (!joiningUrgent) {
      entry.setStatus(EntryStatus.IN_PROGRESS);
      entry.setActiveStartedAt(now);
      entry.setPausedAt(null);
      entry.setPauseOrigin(null);
    } else {
      entry.touch();
    }
    for (var worker : assigned) {
      var a = new TaskAssignment();
      a.setQueueEntry(entry);
      a.setWorkerGroup(assignedGroup);
      a.setWorker(worker);
      a.setWorkerNameSnapshot(worker.getDisplayName());
      a.setGroupNameSnapshot(assignedGroup == null ? null : assignedGroup.getName());
      a.setStatus(AssignmentStatus.ACTIVE);
      a.setAssignedAt(now);
      a.setStartedAt(now);
      projectionWriter.save(assignments, a);
      event(
          entry,
          worker,
          assignedGroup,
          TimeEventType.STARTED,
          joiningUrgent
              ? "Рабочий присоединился к срочному заданию"
              : "Задача взята в работу",
          null,
          now);
    }
    entry = projectionWriter.saveAndFlush(entries, entry);
    projectionWriter.flush();
    if (assignedGroup != null) {
      kpiEvidence.beginSegment(warehouseId, assignedGroup.getId(), entry, now);
    }
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
    ownerProofs.publish(warehouseId, entryId, true);
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
    OffsetDateTime now = now();
    kpiEvidence.refreshEntryGroup(warehouseId, entryId, now);
    pauseEntry(
        entry,
        PauseOrigin.MANUAL,
        trim(request.reason()) == null ? "Пауза" : trim(request.reason()),
        null,
        now);
    entry = projectionWriter.saveAndFlush(entries, entry);
    kpiEvidence.refreshEntryGroup(warehouseId, entryId, now);
    eventSourcing.entryChanged(entry, streamVersion, TaskBoardEventTypes.QUEUE_ENTRY_PAUSED);
    ownerProofs.publish(warehouseId, entryId, true);
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
    OffsetDateTime now = now();
    kpiEvidence.refreshEntryGroup(warehouseId, entryId, now);
    resumeEntry(entry, "Таймер возобновлен", TimeEventType.RESUMED, null, now);
    entry = projectionWriter.saveAndFlush(entries, entry);
    kpiEvidence.refreshEntryGroup(warehouseId, entryId, now);
    eventSourcing.entryChanged(entry, streamVersion, TaskBoardEventTypes.QUEUE_ENTRY_RESUMED);
    ownerProofs.publish(warehouseId, entryId, true);
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
    ensureUrgentSecondaryAssignments(entry);
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
    Set<UUID> completedKpiGroups = kpiEvidence.completeSegment(warehouseId, entry, now);
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
    ownerProofs.publish(warehouseId, entryId, false);
    completedKpiGroups.forEach(
        groupId -> kpiEvidence.refreshGroup(warehouseId, groupId, now));
    resumedEntries.forEach(
        resumed -> kpiEvidence.refreshEntryGroup(warehouseId, resumed.getId(), now));
    kpiEvidence.refreshWarehouse(warehouseId, now);
    return dto(completedEntry);
  }

  @Transactional
  public TaskBoardSnapshot move(UUID warehouseId, UUID entryId, MoveEntryRequest request) {
    lockQueueMutation(warehouseId);
    var entry = requireEntry(warehouseId, entryId);
    BoardTask task = entry.getTask();
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
    lockQueuePositions(warehouseId, affectedQueues);
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
        .forEach(queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
    duplicate.ifPresent(positionCandidates::add);
    positionCandidates.addAll(taskEntries);
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
    var streamVersions = lockTaskAndEntryStreams(task, positionCandidates);
    Set<UUID> protectedTaskIds = protectedTaskIds(positionCandidates);
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
    if (dateChanged) {
      task.setScheduledDate(request.targetDate());
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
      insertAtPosition(
          warehouseId,
          routeTarget.getValue(),
          routeTarget.getKey(),
          request.targetDate(),
          request.targetIndex(),
          protectedTaskIds);
    }
    affectedQueues.stream().distinct().forEach(queue -> normalizePositions(warehouseId, queue));
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
    if (dateChanged) {
      eventSourcing.taskChanged(
          task,
          streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    return snapshot(warehouseId, request.targetDate(), true);
  }

  /**
   * Exchanges two complete date columns without changing any queue or queue-position assignment.
   * A board task owns the date shared by every route entry, so changing the task exactly once also
   * carries the new date to its complete route, including currently non-visible later steps.
   */
  @Transactional
  public TaskBoardSnapshot swapDates(UUID warehouseId, SwapTaskBoardDatesRequest request) {
    if (request.firstDate().equals(request.secondDate())) {
      throw new IllegalArgumentException("Date columns must be different");
    }
    lockQueueMutation(warehouseId);

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
          streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    return snapshot(warehouseId, request.firstDate(), true);
  }

  @Transactional
  public TaskBoardSnapshot pin(UUID warehouseId, UUID taskId, PinTaskRequest request) {
    lockQueueMutation(warehouseId);
    BoardTask task = requireTask(warehouseId, taskId);
    checkVersion(task.getVersion(), request.expectedTaskVersion(), "Задача");
    if (task.getStatus() != TaskStatus.ACTIVE) {
      throw new ConflictException("Закрепить можно только активную задачу");
    }
    if (task.isPinned() == request.pinned()) {
      return snapshot(warehouseId, task.getScheduledDate(), true);
    }
    long streamVersion = eventSourcing.lock(TaskBoardAggregateType.BOARD_TASK, task.getId());
    task.setPinned(request.pinned());
    projectionWriter.saveAndFlush(tasks, task);
    eventSourcing.taskChanged(
        task,
        streamVersion,
        TaskBoardEventTypes.BOARD_TASK_CHANGED);
    return snapshot(warehouseId, task.getScheduledDate(), true);
  }

  @Transactional
  public int rolloverOverdueMaintenanceTasks(LocalDate targetDate) {
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
    lockQueueMutation(warehouseId);
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
    lockQueuePositions(warehouseId, affectedQueues);
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>();
    affectedQueues.forEach(
        queue -> positionCandidates.addAll(orderedEntries(warehouseId, queue)));
    Map<UUID, QueueEntryPosition> positionsBefore = positionsOf(positionCandidates);
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
          orderedEntries(warehouseId, queue).stream()
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
                                      new QueueEntryPosition(
                                          id(entry.getQueue()),
                                          entry.getQueuePosition()))
                                  .position()))
              .toList();
      int position = 0;
      for (QueueEntry entry : targetEntries) {
        entry.setQueuePosition(position++);
        projectionWriter.save(entries, entry);
      }
      normalizePositions(warehouseId, queue);
    }
    projectionWriter.flush();
    Set<UUID> changedEntries = changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry entry : positionCandidates) {
      if (changedEntries.contains(entry.getId())) {
        eventSourcing.entryChanged(
            entry,
            streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_MOVED);
      }
    }
    for (BoardTask task : overdueTasks) {
      eventSourcing.taskChanged(
          task,
          streamVersion(
              streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId()),
          TaskBoardEventTypes.BOARD_TASK_CHANGED);
    }
    return overdueTasks.size();
  }

  private void ensureFirstAvailable(QueueEntry entry) {
    var first =
        entries
            .findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
                entry.getQueue().getId(), Set.of(EntryStatus.WAITING))
            .stream()
            .filter(
                candidate ->
                    candidate
                        .getTask()
                        .getScheduledDate()
                        .equals(entry.getTask().getScheduledDate()))
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

  private WorkQueueClassBinding bindingForTake(
      List<WorkQueueClassBinding> queueBindings,
      WorkerGroup group,
      Worker worker,
      boolean joiningUrgent) {
    Set<UUID> classIds =
        worker == null
            ? Set.of(group.getWorkerClass().getId())
            : workforce.activeQualifications(worker.getId()).stream()
                .map(qualification -> qualification.getWorkerClass().getId())
                .collect(java.util.stream.Collectors.toSet());
    return queueBindings.stream()
        .filter(
            binding ->
                joiningUrgent
                    ? binding.getBindingOrder() > 0
                    : binding.getBindingOrder() == 0)
        .filter(binding -> classIds.contains(binding.getWorkerClass().getId()))
        .findFirst()
        .orElse(null);
  }

  private void ensureUrgentSecondaryAssignments(QueueEntry entry) {
    List<WorkQueueClassBinding> required =
        bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(entry.getQueue().getId()).stream()
            .filter(binding -> binding.getBindingOrder() > 0)
            .filter(WorkQueueClassBinding::isNotifyUrgent)
            .toList();
    if (required.isEmpty()) return;
    Set<UUID> liveWorkerIds =
        assignments.findAllByQueueEntryIdAndStatusIn(
                entry.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
            .stream()
            .map(TaskAssignment::getWorker)
            .filter(Objects::nonNull)
            .map(Worker::getId)
            .collect(java.util.stream.Collectors.toSet());
    for (WorkQueueClassBinding binding : required) {
      boolean assigned =
          liveWorkerIds.stream()
              .flatMap(workerId -> workforce.activeQualifications(workerId).stream())
              .anyMatch(
                  qualification ->
                      qualification.getWorkerClass().equals(binding.getWorkerClass()));
      if (!assigned) {
        throw new ConflictException(
            "Сначала дождитесь вторичного исполнителя: "
                + binding.getWorkerClass().getName());
      }
    }
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

  private void closeReturnInterruptions(QueueEntry returned, OffsetDateTime now) {
    var links = new LinkedHashSet<TaskAutoInterruption>();
    links.addAll(interruptions.findAllByInterruptedEntryIdAndActiveTrue(returned.getId()));
    links.addAll(interruptions.findAllByInterruptingEntryIdAndActiveTrue(returned.getId()));
    for (TaskAutoInterruption link : links) {
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
              + Math.max(
                  0,
                  kpiClock.countedSeconds(
                      entry.getTask().getWarehouseId(),
                      entry.getActiveStartedAt().toInstant(),
                      now.toInstant())));
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

  private int nextPosition(UUID warehouseId, WorkQueue q, LocalDate scheduledDate) {
    return orderedEntries(warehouseId, q, scheduledDate).stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .mapToInt(QueueEntry::getQueuePosition)
            .max()
            .orElse(-1)
        + 1;
  }

  private void normalizePositions(UUID warehouseId, WorkQueue q) {
    Map<LocalDate, List<QueueEntry>> byDate =
        orderedEntries(warehouseId, q).stream()
            .filter(entry -> UNFINISHED.contains(entry.getStatus()))
            .collect(
                java.util.stream.Collectors.groupingBy(
                    entry -> entry.getTask().getScheduledDate(),
                    java.util.LinkedHashMap::new,
                    java.util.stream.Collectors.toList()));
    for (List<QueueEntry> datedEntries : byDate.values()) {
      int position = 0;
      for (QueueEntry entry : datedEntries) {
        entry.setQueuePosition(position++);
        projectionWriter.save(entries, entry);
      }
    }
  }

  private void insertAtPosition(
      UUID warehouseId,
      QueueEntry entry,
      WorkQueue target,
      LocalDate targetDate,
      int requestedIndex,
      Set<UUID> protectedTaskIds) {
    insertAtPosition(
        warehouseId,
        List.of(entry),
        target,
        targetDate,
        requestedIndex,
        protectedTaskIds);
  }

  private void insertAtPosition(
      UUID warehouseId,
      List<QueueEntry> routeEntries,
      WorkQueue target,
      LocalDate targetDate,
      int requestedIndex,
      Set<UUID> protectedTaskIds) {
    Set<UUID> routeEntryIds =
        routeEntries.stream().map(QueueEntry::getId).collect(java.util.stream.Collectors.toSet());
    var ordered =
        new ArrayList<>(
            orderedEntries(warehouseId, target, targetDate).stream()
                .filter(e -> !routeEntryIds.contains(e.getId()) && UNFINISHED.contains(e.getStatus()))
                .toList());
    int firstMovable = 0;
    for (int position = 0; position < ordered.size(); position++) {
      QueueEntry candidate = ordered.get(position);
      if (candidate.getTask().isPinned()
          || protectedTaskIds.contains(candidate.getTask().getId())) {
        firstMovable = position + 1;
      }
    }
    int index = Math.max(firstMovable, Math.min(requestedIndex, ordered.size()));
    List<QueueEntry> orderedRouteEntries =
        routeEntries.stream().sorted(Comparator.comparingInt(QueueEntry::getRouteIndex)).toList();
    ordered.addAll(index, orderedRouteEntries);
    orderedRouteEntries.forEach(routeEntry -> routeEntry.setQueue(target));
    int position = 0;
    for (var current : ordered) {
      current.setQueuePosition(position++);
      projectionWriter.save(entries, current);
    }
  }

  private Set<UUID> protectedTaskIds(Collection<QueueEntry> candidates) {
    return candidates.stream()
        .map(candidate -> candidate.getTask().getId())
        .distinct()
        .filter(
            taskId ->
                entries.findAllByTaskIdOrderByRouteIndexAsc(taskId).stream()
                    .anyMatch(
                        routeEntry ->
                            routeEntry.getStatus() == EntryStatus.IN_PROGRESS
                                || routeEntry.getStatus() == EntryStatus.PAUSED))
        .collect(java.util.stream.Collectors.toSet());
  }

  private List<QueueEntry> orderedEntries(UUID warehouseId, WorkQueue q) {
    return entries.findAllByQueueIdOrderByQueuePositionAsc(q.getId());
  }

  private List<QueueEntry> orderedEntries(
      UUID warehouseId, WorkQueue queue, LocalDate scheduledDate) {
    return orderedEntries(warehouseId, queue).stream()
        .filter(entry -> Objects.equals(entry.getTask().getScheduledDate(), scheduledDate))
        .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
        .toList();
  }

  private void insertByPriority(UUID warehouseId, QueueEntry entry) {
    WorkQueue queue = entry.getQueue();
    LocalDate date = entry.getTask().getScheduledDate();
    var ordered =
        new ArrayList<>(
            orderedEntries(warehouseId, queue, date).stream()
                .filter(candidate -> !candidate.equals(entry))
                .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
                .toList());
    int protectedPrefix = 0;
    for (int index = 0; index < ordered.size(); index++) {
      QueueEntry candidate = ordered.get(index);
      if (candidate.getStatus() == EntryStatus.IN_PROGRESS
          || candidate.getStatus() == EntryStatus.PAUSED
          || candidate.getTask().isPinned()) {
        protectedPrefix = index + 1;
      }
    }
    int insertion = protectedPrefix;
    while (insertion < ordered.size()
        && ordered.get(insertion).getTask().getPriority() <= entry.getTask().getPriority()) {
      insertion++;
    }
    ordered.add(insertion, entry);
    int position = 0;
    for (QueueEntry candidate : ordered) {
      candidate.setQueuePosition(position++);
      projectionWriter.save(entries, candidate);
    }
  }

  private LocalDate scheduleDate(CreateBoardTaskRequest request) {
    if (request.scheduledDate() != null) return request.scheduledDate();
    if (request.deadlineAt() != null) return request.deadlineAt().toLocalDate();
    return LocalDate.now(DEFAULT_SCHEDULE_ZONE);
  }

  private LocalDate scheduledDate(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      Integer dailyCapacity) {
    LocalDate requestedDate = scheduleDate(request);
    if (!MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId) || dailyCapacity == null) {
      return requestedDate;
    }
    if (dailyCapacity < 1) {
      throw new IllegalArgumentException("Daily capacity must be positive");
    }

    Set<UUID> maintenanceTaskIds = new LinkedHashSet<>();
    taskSyncSources
        .findAllBySourceClientId(MAINTENANCE_SOURCE_CLIENT_ID)
        .forEach(source -> maintenanceTaskIds.add(source.getBoardTaskId()));
    Map<LocalDate, Integer> activeTaskCounts = new java.util.HashMap<>();
    for (BoardTask task : tasks.findAllById(maintenanceTaskIds)) {
      if (warehouseId.equals(task.getWarehouseId()) && task.getStatus() == TaskStatus.ACTIVE) {
        activeTaskCounts.merge(task.getScheduledDate(), 1, Integer::sum);
      }
    }

    LocalDate selectedDate = requestedDate;
    while (activeTaskCounts.getOrDefault(selectedDate, 0) >= dailyCapacity) {
      selectedDate = selectedDate.plusDays(1);
    }
    return selectedDate;
  }

  private int priority(Integer value) {
    return value == null ? 3 : value;
  }

  private LocalDate selectDate(LocalDate requestedDate, List<LocalDate> availableDates) {
    if (requestedDate != null && availableDates.contains(requestedDate)) return requestedDate;
    if (availableDates.isEmpty()) return null;
    LocalDate today = LocalDate.now(DEFAULT_SCHEDULE_ZONE);
    if (availableDates.contains(today)) return today;
    return availableDates.stream()
        .filter(date -> !date.isBefore(today))
        .findFirst()
        .orElse(availableDates.getLast());
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

  private void ensureCompletionBeforeDeadline(BoardTask task, OffsetDateTime completionAt) {
    if (!task.isCompletionDeadlineEnforced()) return;
    OffsetDateTime deadline = task.getDeadlineAt();
    if (deadline == null || !completionAt.isBefore(deadline)) {
      throw new ConflictException("Срок резерва мебели истек: завершение задания недоступно");
    }
  }

  private Map<UUID, TaskSourceReferenceDto> sourceReferences(
      Collection<QueueEntry> boardEntries) {
    Set<UUID> taskIds =
        boardEntries.stream()
            .map(entry -> entry.getTask().getId())
            .collect(java.util.stream.Collectors.toSet());
    if (taskIds.isEmpty()) return Map.of();
    Map<UUID, TaskSourceReferenceDto> references = new java.util.HashMap<>();
    for (TaskSyncSource source : taskSyncSources.findAllByBoardTaskIdIn(taskIds)) {
      TaskSourceReferenceDto reference = sourceReference(source);
      if (reference != null) references.put(source.getBoardTaskId(), reference);
    }
    return references;
  }

  private TaskSourceReferenceDto sourceReference(TaskSyncSource source) {
    return source == null || !source.hasSourceReference()
        ? null
        : new TaskSourceReferenceDto(source.getSourceType(), source.getSourceId());
  }

  private BoardEntryDto dto(QueueEntry entry) {
    TaskSourceReferenceDto source =
        taskSyncSources.findById(entry.getTask().getId()).map(this::sourceReference).orElse(null);
    return dto(entry, source);
  }

  private BoardEntryDto dto(QueueEntry e, TaskSourceReferenceDto source) {
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
    OffsetDateTime serverTime = now();
    return new BoardEntryDto(
        e.getId(),
        e.getVersion(),
        t.getId(),
        t.getExternalTaskId(),
        t.getVersion(),
        t.getTitle(),
        t.getUnitNumber(),
        t.getStatus(),
        t.getScheduledDate(),
        t.getPriority(),
        t.isPinned(),
        id(e.getQueue()),
        e.getRouteIndex(),
        e.getQueuePosition(),
        e.getEntryType(),
        e.getStatus(),
        e.getTaskText(),
        e.getPlannedDurationMinutes(),
        e.getActiveStartedAt(),
        e.getPausedAt(),
        e.getActiveWorkSeconds(),
        as,
        timerSnapshot(e, serverTime),
        source);
  }

  private TaskTimerSnapshot timerSnapshot(QueueEntry entry, OffsetDateTime serverTime) {
    Long budget = entry.getCurrentBudgetSeconds();
    if (budget == null) return null;

    long counted = entry.getActiveWorkSeconds();
    TimerState timerState;
    OffsetDateTime nextTransitionAt = null;
    if (entry.getStatus() == EntryStatus.DONE || entry.getStatus() == EntryStatus.CANCELLED) {
      timerState = TimerState.DONE;
    } else if (entry.getStatus() == EntryStatus.IN_PROGRESS
        && entry.getActiveStartedAt() != null) {
      var moment = kpiClock.moment(entry.getTask().getWarehouseId(), serverTime.toInstant());
      counted =
          Math.addExact(
              counted,
              kpiClock.countedSeconds(
                  entry.getTask().getWarehouseId(),
                  entry.getActiveStartedAt().toInstant(),
                  serverTime.toInstant()));
      timerState =
          switch (moment.state()) {
            case WORKING -> TimerState.WORKING;
            case BREAK -> TimerState.BREAK;
            case OFF_SHIFT -> TimerState.OFF_SHIFT;
          };
      nextTransitionAt = moment.nextTransitionAt();
    } else {
      timerState = TimerState.PAUSED;
    }

    long remaining = Math.subtractExact(budget, counted);
    BigDecimal remainingPercent =
        BigDecimal.valueOf(remaining)
            .multiply(BigDecimal.valueOf(100))
            .divide(BigDecimal.valueOf(budget), 2, RoundingMode.HALF_UP);
    return new TaskTimerSnapshot(
        counted, remaining, remainingPercent, timerState, nextTransitionAt, serverTime);
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
        task.getPriority(),
        task.isPinned(),
        task.getDoneAt(),
        route);
  }

  private record InterruptedWork(Worker worker, QueueEntry entry) {}

  private record QueueEntryPosition(UUID queueId, int position) {}

  private record ResolvedRouteStep(RouteStepRequest request, WorkQueue queue) {}

  private record NormalizedEquipmentMovementRequest(
      String unitNumber, List<NormalizedEquipmentMovementOperation> operations) {
    private NormalizedEquipmentMovementRequest {
      operations = List.copyOf(operations);
    }
  }

  private record NormalizedEquipmentMovementOperation(
      EquipmentMovementDirection direction, UUID equipmentId, String equipmentName, long quantity) {}
}
