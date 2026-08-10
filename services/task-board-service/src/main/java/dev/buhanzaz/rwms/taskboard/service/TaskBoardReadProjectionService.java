package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.repository.*;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

/**
 * Builds task-board read projections from persisted task, route, assignment and source state.
 *
 * <p>This component deliberately has no command methods: callers receive DTO snapshots from the
 * same authoritative rows that command coordinators mutate.
 */
@Service
class TaskBoardReadProjectionService {
  private static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final ZoneId DEFAULT_SCHEDULE_ZONE = ZoneId.of("Europe/Moscow");
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final WorkQueueRepository queues;
  private final WorkQueueClassBindingRepository bindings;
  private final TaskAssignmentRepository assignments;
  private final TaskTimeEventRepository events;
  private final WorkerGroupMemberRepository members;
  private final WorkforceService workforce;
  private final RegistryService registry;
  private final TaskSyncSourceRepository taskSyncSources;
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final WarehouseKpiClock kpiClock;
  private final DriverTaskAudienceService driverAudiences;

  TaskBoardReadProjectionService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      WorkQueueRepository queues,
      WorkQueueClassBindingRepository bindings,
      TaskAssignmentRepository assignments,
      TaskTimeEventRepository events,
      WorkerGroupMemberRepository members,
      WorkforceService workforce,
      RegistryService registry,
      TaskSyncSourceRepository taskSyncSources,
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      WarehouseKpiClock kpiClock,
      DriverTaskAudienceService driverAudiences) {
    this.tasks = tasks;
    this.entries = entries;
    this.queues = queues;
    this.bindings = bindings;
    this.assignments = assignments;
    this.events = events;
    this.members = members;
    this.workforce = workforce;
    this.registry = registry;
    this.taskSyncSources = taskSyncSources;
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.kpiClock = kpiClock;
    this.driverAudiences = driverAudiences;
  }

  public TaskBoardSnapshot snapshot(
      UUID warehouseId, LocalDate requestedDate, boolean includeShadow) {
    var columns = new ArrayList<BoardColumnDto>();
    var allEntries =
        activeEntries(warehouseId).stream()
            .filter(
                entry ->
                    entry.getQueue() != null
                        && entry.getQueue().getPurpose() != QueuePurpose.LOGISTICS_DRIVER)
            .toList();
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
      if (queue.isHidden() || queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER) continue;
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
              queue.getPurpose(),
              queue.getSortOrder(),
              cards));
    }
    return new TaskBoardSnapshot(warehouseId, selectedDate, availableDates, columns);
  }

  TaskBoardSnapshot snapshot(UUID warehouseId, boolean includeShadow) {
    return snapshot(warehouseId, null, includeShadow);
  }

  /** Returns the separate driver-logistics projection for one warehouse. */
  LogisticsBoardSnapshot logisticsSnapshot(UUID warehouseId) {
    WorkQueue queue = requireLogisticsDriverQueue(warehouseId);
    List<QueueEntry> selected =
        activeEntries(warehouseId).stream()
            .filter(entry -> entry.getQueue() != null && entry.getQueue().equals(queue))
            .filter(entry -> entry.getEntryType() == EntryType.REAL)
            .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
            .toList();
    Map<UUID, TaskSourceReferenceDto> sources = sourceReferences(selected);
    List<BoardEntryDto> current =
        selected.stream()
            .filter(entry -> entry.getTask().getLane() == TaskLane.CURRENT)
            .map(entry -> dto(entry, sources.get(entry.getTask().getId())))
            .toList();
    List<LogisticsDateColumnDto> dates =
        selected.stream()
            .filter(entry -> entry.getTask().getLane() == TaskLane.SCHEDULED)
            .collect(
                java.util.stream.Collectors.groupingBy(
                    entry -> entry.getTask().getScheduledDate(),
                    java.util.TreeMap::new,
                    java.util.stream.Collectors.toList()))
            .entrySet()
            .stream()
            .map(
                item ->
                    new LogisticsDateColumnDto(
                        item.getKey(),
                        item.getValue().stream()
                            .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
                            .map(entry -> dto(entry, sources.get(entry.getTask().getId())))
                            .toList()))
            .toList();
    return new LogisticsBoardSnapshot(
        warehouseId, queue.getId(), queue.getVersion(), current, dates);
  }

  /**
   * Worker feed keeps the ordinary selected-date view and adds only actionable
   * logistics entries from the server-controlled current lane.  The lane is an
   * ordered queue; only its first waiting entry is actionable for a driver.
   */
  TaskBoardSnapshot workerSnapshot(UUID warehouseId, UUID workerId) {
    TaskBoardSnapshot ordinary = snapshot(warehouseId, null, false);
    List<BoardColumnDto> columns = new ArrayList<>(ordinary.columns());
    for (WorkQueue queue : queues.findAllActiveOrderedByWarehouseId(warehouseId)) {
      if (queue.isHidden() || queue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER) continue;
      List<QueueEntry> logisticsEntries =
          activeEntries(warehouseId).stream()
              .filter(entry -> entry.getQueue() != null && entry.getQueue().equals(queue))
              .filter(entry -> entry.getEntryType() == EntryType.REAL)
              .filter(entry -> entry.getTask().getLane() == TaskLane.CURRENT)
              .filter(entry -> driverAudiences.isVisibleTo(entry, workerId))
              .sorted(Comparator.comparingInt(QueueEntry::getQueuePosition))
              .toList();
      Map<UUID, TaskSourceReferenceDto> sources = sourceReferences(logisticsEntries);
      columns.add(
          new BoardColumnDto(
              queue.getId(),
              queue.getName(),
              queue.getType(),
              queue.getPurpose(),
              queue.getSortOrder(),
              logisticsEntries.stream()
                  .map(entry -> dto(entry, sources.get(entry.getTask().getId())))
                  .toList()));
    }
    return new TaskBoardSnapshot(
        warehouseId, ordinary.selectedDate(), ordinary.availableDates(), columns);
  }

  BoardEntryDto entry(UUID warehouseId, UUID entryId) {
    return dto(requireEntry(warehouseId, entryId));
  }

  /** Resolves a worker-visible entry and hides driver tasks outside its planned audience. */
  BoardEntryDto workerEntry(UUID warehouseId, UUID entryId, UUID workerId) {
    QueueEntry entry = requireEntry(warehouseId, entryId);
    if (entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER
        && !driverAudiences.isVisibleTo(entry, workerId)) {
      throw new NotFoundException("Задание не найдено");
    }
    return dto(entry);
  }

  TaskWorkerContentDto workerContent(UUID warehouseId, UUID entryId) {
    QueueEntry entry = requireEntry(warehouseId, entryId);
    return new TaskWorkerContentDto(
        readList(entry.getWorkerWorks(), new TypeReference<>() {}),
        readList(entry.getWorkerMaterials(), new TypeReference<>() {}),
        readList(entry.getWorkerComments(), new TypeReference<>() {}),
        readList(entry.getSourceMediaReferences(), new TypeReference<>() {}));
  }

  /** Resolves an external-task registration in the supplied warehouse scope. */
  BoardTaskRegistrationDto registration(UUID warehouseId, UUID externalTaskId) {
    BoardTask task =
        tasks
            .findByWarehouseIdAndExternalTaskId(warehouseId, externalTaskId)
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    return registrationDto(task);
  }

  /** Resolves an external task only when it belongs to the authenticated source client. */
  BoardTaskRegistrationDto externalTask(String sourceClientId, UUID externalTaskId) {
    return registrationDto(ownedExternalTask(sourceClientId, externalTaskId));
  }

  /** Returns the evidence selected for a completed source-owned task. */
  SelectedCompletionEvidenceDto selectedCompletionEvidence(
      String sourceClientId, UUID externalTaskId) {
    if (!LOGISTICS_SOURCE_CLIENT_ID.equals(sourceClientId)) {
      throw new NotFoundException("Задача не найдена");
    }
    BoardTask task = ownedExternalTask(sourceClientId, externalTaskId);
    TaskSyncSource source =
        taskSyncSources
            .findById(task.getId())
            .orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!source.hasSourceReference(
        TaskSourceType.LOGISTICS_DRIVER_TASK, source.getSourceId())) {
      throw new NotFoundException("Задача не найдена");
    }
    if (task.getStatus() != TaskStatus.DONE) {
      throw new ConflictException(
          "Фотография результата доступна только после завершения задания");
    }
    List<SelectedCompletionEvidenceDto> selected =
        jdbc.query(
            """
            select evidence.entry_id,
                   evidence.evidence_id,
                   evidence.media_id,
                   evidence.media_generation,
                   evidence.warehouse_id,
                   evidence.recorded_at
              from worker_task_evidence evidence
              join queue_entry entry on entry.id = evidence.entry_id
             where entry.task_id = ?
               and evidence.selected_for_completion
               and evidence.state = 'READY'
               and evidence.media_id is not null
               and evidence.media_generation is not null
             order by evidence.recorded_at, evidence.evidence_id
            """,
            (result, row) ->
                new SelectedCompletionEvidenceDto(
                    externalTaskId,
                    task.getId(),
                    result.getObject("entry_id", UUID.class),
                    result.getObject("evidence_id", UUID.class),
                    result.getObject("media_id", UUID.class),
                    result.getLong("media_generation"),
                    result.getObject("warehouse_id", UUID.class),
                    result.getObject("recorded_at", OffsetDateTime.class)),
            task.getId());
    if (selected.size() != 1) {
      throw new ConflictException(
          "Для завершённого логистического задания должна быть выбрана одна фотография");
    }
    return selected.getFirst();
  }


  /** Lists currently eligible operational groups for one physical queue. */
  List<WorkerGroupDto> eligibleGroups(UUID warehouseId, UUID queueId) {
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

  /** Returns the durable time-event history for an entry in the warehouse scope. */
  List<TimeEventDto> history(UUID warehouseId, UUID entryId) {
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


  private <T> List<T> readList(String value, TypeReference<List<T>> type) {
    try {
      return List.copyOf(objectMapper.readValue(value, type));
    } catch (JacksonException exception) {
      throw new IllegalStateException("Сохранённый снимок задания для рабочего повреждён", exception);
    }
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

  BoardEntryDto dto(QueueEntry entry) {
    TaskSourceReferenceDto source =
        taskSyncSources.findById(entry.getTask().getId()).map(this::sourceReference).orElse(null);
    return dto(entry, source);
  }

  BoardEntryDto dto(QueueEntry e, TaskSourceReferenceDto source) {
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
        t.getLane(),
        t.getPriority(),
        t.isPinned(),
        id(e.getQueue()),
        e.getQueue().getPurpose(),
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
        source,
        driverAudiences.dto(t));
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

  BoardTaskRegistrationDto registrationDto(BoardTask task) {
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



  private WorkQueue requireLogisticsDriverQueue(UUID warehouseId) {
    List<WorkQueue> candidates =
        queues.findAllActiveOrderedByWarehouseId(warehouseId).stream()
            .filter(queue -> !queue.isHidden())
            .filter(queue -> queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER)
            .toList();
    if (candidates.size() != 1) {
      throw new ConflictException(
          "Настройте ровно одну активную видимую очередь «Перемещение»");
    }
    return candidates.getFirst();
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


}
