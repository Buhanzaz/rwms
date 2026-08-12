package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import dev.buhanzaz.rwms.taskboard.domain.*;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardAggregateType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventStore;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.repository.*;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionPermit;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Registers and creates source-owned task-board work.
 *
 * <p>This coordinator owns idempotent source identity, canonical request fingerprints, route
 * construction and lifecycle admission. Later source mutations deliberately live in
 * {@link TaskBoardExternalMutationService}.
 */
@Service
class TaskBoardExternalRegistrationService {
  static final String LOGISTICS_SOURCE_CLIENT_ID = "logistics-service";
  private static final String MAINTENANCE_SOURCE_CLIENT_ID = "maintenance-service";
  private static final ZoneId DEFAULT_SCHEDULE_ZONE = ZoneId.of("Europe/Moscow");

  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final RegistryService registry;
  private final TaskSyncSourceRepository taskSyncSources;
  private final TaskBoardRoutePayloadCodec routePayloads;
  private final TaskBoardQueuePositionCoordinator queuePositions;
  private final TaskBoardProjectionWriter projectionWriter;
  private final TaskBoardEventSourcing eventSourcing;
  private final GroupKpiEvidenceService kpiEvidence;
  private final WarehouseLifecycleFence warehouseLifecycleFence;
  private final TransactionTemplate lifecycleMutations;
  private final WorkQueueClassBindingRepository bindings;
  private final WorkforceService workforce;
  private final WorkerInvalidationHub workerInvalidations;
  private final DriverTaskAudienceService driverAudiences;
  private final JdbcTemplate jdbc;

  TaskBoardExternalRegistrationService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      RegistryService registry,
      TaskSyncSourceRepository taskSyncSources,
      TaskBoardRoutePayloadCodec routePayloads,
      TaskBoardQueuePositionCoordinator queuePositions,
      TaskBoardProjectionWriter projectionWriter,
      TaskBoardEventSourcing eventSourcing,
      GroupKpiEvidenceService kpiEvidence,
      WarehouseLifecycleFence warehouseLifecycleFence,
      PlatformTransactionManager transactionManager,
      WorkQueueClassBindingRepository bindings,
      WorkforceService workforce,
      WorkerInvalidationHub workerInvalidations,
      DriverTaskAudienceService driverAudiences,
      JdbcTemplate jdbc) {
    this.tasks = tasks;
    this.entries = entries;
    this.registry = registry;
    this.taskSyncSources = taskSyncSources;
    this.routePayloads = routePayloads;
    this.queuePositions = queuePositions;
    this.projectionWriter = projectionWriter;
    this.eventSourcing = eventSourcing;
    this.kpiEvidence = kpiEvidence;
    this.warehouseLifecycleFence = warehouseLifecycleFence;
    this.lifecycleMutations = new TransactionTemplate(transactionManager);
    this.bindings = bindings;
    this.workforce = workforce;
    this.workerInvalidations = workerInvalidations;
    this.driverAudiences = driverAudiences;
    this.jdbc = jdbc;
  }

  /**
   * Keeps the post-create board snapshot in the same local transaction shape as the former facade.
   */
  TaskBoardSnapshot createManagerTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      Function<BoardTask, TaskBoardSnapshot> snapshot) {
    BoardTask task = createTask(warehouseId, request, null, false);
    return inLifecycleMutation(() -> snapshot.apply(task));
  }

  BoardTask createLogisticsEquipmentMovementTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String fingerprint,
      OperationDirection admissionDirection) {
    return createTask(
        warehouseId,
        request,
        LOGISTICS_SOURCE_CLIENT_ID,
        false,
        true,
        fingerprint,
        null,
        null,
        TaskLane.SCHEDULED,
        null,
        admissionDirection);
  }

  BoardTaskRegistrationDto registerExternalTask(
      String sourceClientId, RegisterExternalTaskRequest request) {
    TaskSourceReferenceDto source = sourceReferenceFor(sourceClientId, request.source());
    DriverTaskAudienceDto driverAudience =
        driverAudiences.normalizeRegistration(sourceClientId, source, request.driverAudience());
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
            source,
            request.lane() == null ? TaskLane.SCHEDULED : request.lane(),
            driverAudience,
            OperationDirection.INCOMING);
    return inLifecycleMutation(
        () -> registrationDto(requireTask(task.getWarehouseId(), task.getId())));
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues) {
    return createTask(
        warehouseId,
        request,
        sourceClientId,
        allowRepeatedQueues,
        false,
        null,
        null,
        null,
        TaskLane.SCHEDULED,
        null,
        OperationDirection.INCOMING);
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
        null,
        TaskLane.SCHEDULED,
        null,
        OperationDirection.INCOMING);
  }

  private BoardTask createTask(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues,
      boolean completionDeadlineEnforced,
      String suppliedFingerprint,
      Integer dailyCapacity,
      TaskSourceReferenceDto sourceReference,
      TaskLane taskLane,
      DriverTaskAudienceDto driverAudience,
      OperationDirection admissionDirection) {
    TaskLane effectiveLane = taskLane == null ? TaskLane.SCHEDULED : taskLane;
    if (effectiveLane == TaskLane.CURRENT
        && (sourceReference == null
            || sourceReference.type() != TaskSourceType.LOGISTICS_DRIVER_TASK
            || !LOGISTICS_SOURCE_CLIENT_ID.equals(sourceClientId))) {
      throw new IllegalArgumentException(
          "Только logistics-service может создать текущее логистическое задание");
    }
    BoardTask existingTask =
        inLifecycleMutationNullable(
            () ->
                existingExternalTaskForCreate(
                    warehouseId,
                    request,
                    sourceClientId,
                    dailyCapacity,
                    sourceReference,
                    effectiveLane,
                    driverAudience,
                    suppliedFingerprint));
    if (existingTask != null) {
      return existingTask;
    }
    AdmissionPermit admission =
        warehouseLifecycleFence.acquireAdmission(warehouseId, admissionDirection);
    return inLifecycleMutation(
        () ->
            createTaskAfterAdmission(
                warehouseId,
                request,
                sourceClientId,
                allowRepeatedQueues,
                completionDeadlineEnforced,
                suppliedFingerprint,
                dailyCapacity,
                sourceReference,
                effectiveLane,
                driverAudience,
                admission));
  }

  private BoardTask createTaskAfterAdmission(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      boolean allowRepeatedQueues,
      boolean completionDeadlineEnforced,
      String suppliedFingerprint,
      Integer dailyCapacity,
      TaskSourceReferenceDto sourceReference,
      TaskLane effectiveLane,
      DriverTaskAudienceDto driverAudience,
      AdmissionPermit admission) {
    warehouseLifecycleFence.terminalizeAdmission(admission);
    if (request.externalTaskId() != null) {
      lock("external-task:" + request.externalTaskId());
      BoardTask existingTask =
          existingExternalTaskForCreate(
              warehouseId,
              request,
              sourceClientId,
              dailyCapacity,
              sourceReference,
              effectiveLane,
              driverAudience,
              suppliedFingerprint);
      if (existingTask != null) {
        return existingTask;
      }
    }
    queuePositions.lockQueueMutation(warehouseId);
    List<ResolvedRouteStep> routeSteps =
        resolveRoute(warehouseId, request.route(), allowRepeatedQueues, sourceClientId);
    requireRoutePurpose(routeSteps, sourceReference);
    queuePositions.lockQueuePositions(warehouseId, routeSteps.stream().map(ResolvedRouteStep::queue).toList());
    LocalDate scheduledDate = scheduledDate(warehouseId, request, sourceClientId, dailyCapacity);
    Set<QueueEntry> existingEntries = new LinkedHashSet<>();
    routeSteps.stream()
        .map(ResolvedRouteStep::queue)
        .distinct()
        .forEach(
            queue ->
                existingEntries.addAll(queuePositions.orderedEntries(warehouseId, queue, scheduledDate)));
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> existingPositions = queuePositions.positionsOf(existingEntries);
    Map<TaskBoardEventStore.StreamRef, Long> existingStreamVersions =
        existingEntries.isEmpty() ? Map.of() : queuePositions.lockEntryStreams(existingEntries);
    int effectivePriority = priority(request.priority());
    String requestFingerprint =
        suppliedFingerprint == null
            ? routePayloads.fingerprint(
                warehouseId,
                request,
                scheduledDate,
                effectivePriority,
                effectiveLane,
                driverAudience,
                jdbc)
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
    task.setLane(effectiveLane);
    task.setPriority(effectivePriority);
    task.setPinned(false);
    task.setCompletionDeadlineEnforced(completionDeadlineEnforced);
    if (driverAudience != null) {
      driverAudiences.applyNewTask(task, routeSteps.getFirst().queue(), driverAudience);
    }
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
      entry.setQueuePosition(queuePositions.nextPosition(warehouseId, queue, task.getScheduledDate()));
      entry.setTaskText(trim(step.taskText()));
      entry.setPlannedDurationMinutes(step.plannedDurationMinutes());
      routePayloads.setWorkerContent(entry, step);
      projectionWriter.save(entries, entry);
      route++;
    }
    for (QueueEntry entry : entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId())) {
      queuePositions.insertByPriority(warehouseId, entry);
    }
    projectionWriter.flush();
    eventSourcing.created(task);
    entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).forEach(eventSourcing::created);
    for (QueueEntry existing : existingEntries) {
      if (queuePositions.changedPositionIds(List.of(existing), existingPositions).contains(existing.getId())) {
        eventSourcing.entryChanged(
            existing,
            queuePositions.streamVersion(
                existingStreamVersions, TaskBoardAggregateType.QUEUE_ENTRY, existing.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    kpiEvidence.refreshWarehouse(warehouseId, now());
    publishTaskAvailabilityAfterCommit(task);
    return task;
  }

  /** Executes the local task-board CAS mutation only after lifecycle admission has completed. */
  private <T> T inLifecycleMutation(Supplier<T> mutation) {
    T result = inLifecycleMutationNullable(mutation);
    if (result == null) {
      throw new IllegalStateException("Lifecycle task-board mutation returned no result");
    }
    return result;
  }

  private <T> T inLifecycleMutationNullable(Supplier<T> mutation) {
    return lifecycleMutations.execute(status -> mutation.get());
  }

  /**
   * Checks an idempotent external registration without taking the task mutation advisory lock.
   * The caller repeats this check under that lock after lifecycle admission, so a concurrent create
   * cannot turn an owner-side read into a duplicate local task.
   */
  private BoardTask existingExternalTaskForCreate(
      UUID warehouseId,
      CreateBoardTaskRequest request,
      String sourceClientId,
      Integer dailyCapacity,
      TaskSourceReferenceDto sourceReference,
      TaskLane effectiveLane,
      DriverTaskAudienceDto driverAudience,
      String suppliedFingerprint) {
    if (request.externalTaskId() == null) {
      return null;
    }
    BoardTask task = tasks.findByExternalTaskId(request.externalTaskId()).orElse(null);
    if (task == null) {
      return null;
    }
    String requestFingerprint =
        suppliedFingerprint == null
            ? routePayloads.fingerprint(
                warehouseId,
                request,
                (MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId) && dailyCapacity != null)
                        || request.scheduledDate() == null
                    ? task.getScheduledDate()
                    : request.scheduledDate(),
                request.priority() == null ? task.getPriority() : priority(request.priority()),
                effectiveLane,
                driverAudience,
                jdbc)
            : suppliedFingerprint;
    boolean fingerprintMatches =
        task.getRequestFingerprint() != null
            && task.getRequestFingerprint().equals(requestFingerprint);
    if (!fingerprintMatches
        && suppliedFingerprint == null
        && driverAudience != null
        && task.getDriverAudienceMode() == DriverTaskAudienceMode.WAREHOUSE_DRIVERS
        && task.getPlannedDriverWorkerId() == null) {
      String legacyFingerprint =
          routePayloads.fingerprint(
              warehouseId,
              request,
              (MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId) && dailyCapacity != null)
                      || request.scheduledDate() == null
                  ? task.getScheduledDate()
                  : request.scheduledDate(),
              request.priority() == null ? task.getPriority() : priority(request.priority()),
              effectiveLane,
              null,
              jdbc);
      fingerprintMatches = task.getRequestFingerprint().equals(legacyFingerprint);
    }
    if (warehouseId.equals(task.getWarehouseId()) && fingerprintMatches) {
      requireExactSourceReference(task, sourceClientId, sourceReference);
      return task;
    }
    throw new ConflictException("Задача с externalTaskId уже существует с другими данными");
  }
  private TaskSourceReferenceDto sourceReferenceFor(
      String sourceClientId, TaskSourceReferenceDto source) {
    if (source == null) return null;
    if (source.type() == null || source.sourceId() == null) {
      throw new IllegalArgumentException("Источник задания заполнен не полностью");
    }
    boolean maintenance =
        MAINTENANCE_SOURCE_CLIENT_ID.equals(sourceClientId)
            && source.type() == TaskSourceType.MAINTENANCE_REPAIR;
    boolean logistics =
        LOGISTICS_SOURCE_CLIENT_ID.equals(sourceClientId)
            && source.type() == TaskSourceType.LOGISTICS_DRIVER_TASK;
    if (!maintenance && !logistics) {
      throw new IllegalArgumentException(
          "Источник задания не соответствует сервису-владельцу");
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




  private void publishTaskAvailabilityAfterCommit(BoardTask task) {
    boolean visibleToday =
        task.getLane() == TaskLane.CURRENT
            || Objects.equals(
                task.getScheduledDate(), LocalDate.now(DEFAULT_SCHEDULE_ZONE));
    if (!visibleToday) return;

    List<TaskAvailabilityNotification> notifications =
        entries.findAllByTaskIdOrderByRouteIndexAsc(task.getId()).stream()
            .filter(entry -> entry.getEntryType() == EntryType.REAL)
            .flatMap(
                entry ->
                    java.util.Arrays.stream(MobileTaskSurface.values())
                        .map(
                            surface ->
                                new TaskAvailabilityNotification(
                                    surface,
                                    entry.getId(),
                                    notificationWorkerIds(task, entry.getQueue(), surface),
                                    task.getPriority() <= 1)))
            .filter(notification -> !notification.workerIds().isEmpty())
            .toList();
    if (notifications.isEmpty()) return;

    Runnable dispatch =
        () -> {
          long revision = workerRevision();
          notifications.forEach(
              notification ->
                  workerInvalidations.taskAvailable(
                      notification.surface(),
                      notification.workerIds(),
                      notification.entryId(),
                      revision,
                      notification.urgent()));
        };
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

  private Set<UUID> eligibleWorkerIds(
      UUID warehouseId, WorkQueue queue, MobileTaskSurface surface) {
    Set<UUID> workerClassIds =
        bindings.findAllByQueueId(queue.getId()).stream()
            .filter(
                binding ->
                    surface == MobileTaskSurface.WORKER
                        || binding.getBindingOrder() == 0)
            .map(binding -> binding.getWorkerClass().getId())
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (workerClassIds.isEmpty()) return Set.of();

    List<WorkerDto> activeWorkers =
        workforce.listWorkers(warehouseId).stream().filter(WorkerDto::active).toList();
    Set<UUID> activeWorkerIds =
        activeWorkers.stream()
            .map(WorkerDto::id)
            .collect(java.util.stream.Collectors.toSet());
    Set<UUID> result =
        activeWorkers.stream()
            .filter(
                worker ->
                    worker.qualifications().stream()
                        .anyMatch(
                            qualification ->
                                qualification.active()
                                    && workerClassIds.contains(
                                        qualification.workerClass().id())))
            .map(WorkerDto::id)
            .collect(
                java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    if (queue.getPurpose() != QueuePurpose.LOGISTICS_DRIVER) {
      workforce.listGroups(warehouseId).stream()
          .filter(WorkerGroupDto::active)
          .filter(group -> workerClassIds.contains(group.workerClass().id()))
          .flatMap(group -> group.members().stream())
          .filter(GroupMemberDto::active)
          .map(GroupMemberDto::workerId)
          .filter(activeWorkerIds::contains)
          .forEach(result::add);
    }
    return Set.copyOf(result);
  }

  private Set<UUID> notificationWorkerIds(
      BoardTask task, WorkQueue queue, MobileTaskSurface surface) {
    if (queue.getPurpose() == QueuePurpose.LOGISTICS_DRIVER
        && surface == MobileTaskSurface.WORKER) {
      return Set.of();
    }
    if (task.getDriverAudienceMode() == DriverTaskAudienceMode.UNASSIGNED) {
      return Set.of();
    }
    if (task.getDriverAudienceMode() == DriverTaskAudienceMode.ASSIGNED_DRIVER) {
      return Set.of(task.getPlannedDriverWorkerId());
    }
    return eligibleWorkerIds(task.getWarehouseId(), queue, surface);
  }

  private long workerRevision() {
    Long revision =
        jdbc.queryForObject(
            "select coalesce(sum(current_version + 1), 0)::bigint from event_stream_head",
            Long.class);
    return revision == null ? 0 : revision;
  }


  private BoardTask requireTask(UUID warehouseId, UUID id) {
    BoardTask task = tasks.findById(id).orElseThrow(() -> new NotFoundException("Задача не найдена"));
    if (!task.getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Задача не найдена");
    }
    return task;
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

  /**
   * Binds one source-provided route step to the task-board-owned queue selected for persistence.
   */
  private record ResolvedRouteStep(RouteStepRequest request, WorkQueue queue) {}

  /**
   * Captures the post-commit worker invalidation for a newly available entry so rollback never
   * exposes work that was not durably registered.
   */
  private record TaskAvailabilityNotification(
      MobileTaskSurface surface, UUID entryId, Set<UUID> workerIds, boolean urgent) {}
}
