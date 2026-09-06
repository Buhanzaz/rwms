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
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Executes worker-owned task transitions, timers, assignments and interruption recovery.
 *
 * <p>It owns execution-side state transitions only. Persisted queue ordering and its locking
 * protocol are composed through {@link TaskBoardQueuePositionCoordinator}.
 */
@Service
class TaskBoardWorkerExecutionService {
  private static final Set<EntryStatus> UNFINISHED =
      Set.of(EntryStatus.WAITING, EntryStatus.IN_PROGRESS, EntryStatus.PAUSED);

  private final BoardTaskRepository tasks;
  private final QueueEntryRepository entries;
  private final WorkQueueClassBindingRepository bindings;
  private final TaskAssignmentRepository assignments;
  private final TaskTimeEventRepository events;
  private final TaskAutoInterruptionRepository interruptions;
  private final WorkerGroupMemberRepository members;
  private final WorkforceService workforce;
  private final JdbcTemplate jdbc;
  private final TaskBoardEventSourcing eventSourcing;
  private final TaskBoardProjectionWriter projectionWriter;
  private final TaskBoardEntryOwnerProofService ownerProofs;
  private final WarehouseKpiClock kpiClock;
  private final GroupKpiEvidenceService kpiEvidence;
  private final TaskBoardQueuePositionCoordinator queuePositions;
  private final DriverTaskAudienceService driverAudiences;
  private final MaintenanceTaskExecutionPackageService executionPackages;
  private final WorkerQueuePlanPolicy workerQueuePlans;
  private final PlanningReplanHoldFence replanHolds;
  private final LinkedQueueContinuationPolicy linkedQueues;

  TaskBoardWorkerExecutionService(
      BoardTaskRepository tasks,
      QueueEntryRepository entries,
      WorkQueueClassBindingRepository bindings,
      TaskAssignmentRepository assignments,
      TaskTimeEventRepository events,
      TaskAutoInterruptionRepository interruptions,
      WorkerGroupMemberRepository members,
      WorkforceService workforce,
      JdbcTemplate jdbc,
      TaskBoardEventSourcing eventSourcing,
      TaskBoardProjectionWriter projectionWriter,
      TaskBoardEntryOwnerProofService ownerProofs,
      WarehouseKpiClock kpiClock,
      GroupKpiEvidenceService kpiEvidence,
      TaskBoardQueuePositionCoordinator queuePositions,
      DriverTaskAudienceService driverAudiences,
      MaintenanceTaskExecutionPackageService executionPackages,
      WorkerQueuePlanPolicy workerQueuePlans,
      PlanningReplanHoldFence replanHolds,
      LinkedQueueContinuationPolicy linkedQueues) {
    this.tasks = tasks;
    this.entries = entries;
    this.bindings = bindings;
    this.assignments = assignments;
    this.events = events;
    this.interruptions = interruptions;
    this.members = members;
    this.workforce = workforce;
    this.jdbc = jdbc;
    this.eventSourcing = eventSourcing;
    this.projectionWriter = projectionWriter;
    this.ownerProofs = ownerProofs;
    this.kpiClock = kpiClock;
    this.kpiEvidence = kpiEvidence;
    this.queuePositions = queuePositions;
    this.driverAudiences = driverAudiences;
    this.executionPackages = executionPackages;
    this.workerQueuePlans = workerQueuePlans;
    this.replanHolds = replanHolds;
    this.linkedQueues = linkedQueues;
  }

  CancelledTaskDto cancelTask(
      UUID warehouseId, UUID externalTaskId, CancelTaskRequest request) {
    lock("external-task:" + externalTaskId);
    queuePositions.lockQueueMutation(warehouseId);
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
    affectedQueues.forEach(queue -> positionCandidates.addAll(queuePositions.orderedEntries(warehouseId, queue)));
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    Set<QueueEntry> streamsToLock = new LinkedHashSet<>(taskEntries);
    streamsToLock.addAll(interruptionNeighbours);
    streamsToLock.addAll(positionCandidates);
    queuePositions.lockQueuePositions(warehouseId, taskEntries.stream().map(QueueEntry::getQueue).toList());
    var streamVersions = queuePositions.lockTaskAndEntryStreams(task, streamsToLock);
    long taskStreamVersion =
        queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.BOARD_TASK, task.getId());
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
    affectedQueues.forEach(queue -> queuePositions.normalizePositions(warehouseId, queue));
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
    Set<UUID> repositionedIds = queuePositions.changedPositionIds(positionCandidates, positionsBefore);
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
            queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
            eventType);
      }
    }
    eventSourcing.taskChanged(task, taskStreamVersion, TaskBoardEventTypes.BOARD_TASK_CANCELLED);
    kpiGroups.forEach(groupId -> kpiEvidence.refreshGroup(warehouseId, groupId, now));
    kpiEvidence.refreshWarehouse(warehouseId, now);
    return cancelledTaskDto(task);
  }


  List<UUID> returnActiveWorkForGroup(UUID warehouseId, UUID groupId) {
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
        queuePositions.lockEntryStreams(affected);
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
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_RETURNING);
      ownerProofs.publish(warehouseId, entry.getId(), false);
    }
    kpiGroups.forEach(
        affectedGroup ->
            kpiEvidence.refreshGroup(warehouseId, affectedGroup, returnedAt));
    return affected.stream().map(QueueEntry::getId).toList();
  }

  /**
   * Takes or joins an entry with the observed version and optionally enforces WorkerApp's current
   * queue publication window before any assignment transition. An authenticated exact driver may
   * start logistics work at another physical warehouse; shared-pool and secondary participation
   * retain their same-warehouse workforce fences.
   */
  QueueEntry take(
      UUID warehouseId,
      UUID entryId,
      TakeEntryRequest request,
      UUID authenticatedWorkerId,
      boolean enforceWorkerPlan) {
    queuePositions.lockQueueMutation(warehouseId);
    // The row lock pairs with cancelExternalTaskIfPreStart.  Whichever transition wins is visible
    // to the loser before it validates WAITING, so a started entry cannot be cancelled by a
    // concurrent source compensation command and a cancelled entry cannot be resurrected.
    var entry = requireEntryForUpdate(warehouseId, entryId);
    replanHolds.requireExecutionAllowed(entry.getTask());
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    boolean joiningSecondary = entry.getStatus() == EntryStatus.IN_PROGRESS;
    if (entry.getEntryType() != EntryType.REAL
        || (!joiningSecondary && entry.getStatus() != EntryStatus.WAITING)) {
      throw new ConflictException(
          "Взять можно ожидающий этап или присоединиться к этапу в работе");
    }
    if (enforceWorkerPlan) {
      workerQueuePlans.requireTakeAllowed(entry);
    }
    if (entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER
        && entry.getTask().getLane() != TaskLane.CURRENT) {
      throw new ConflictException("Водитель может взять только текущее логистическое задание");
    }
    if (request.workerGroupId() == null && request.workerId() == null)
      throw new ConflictException("Выберите группу или рабочего");
    WorkerGroup group =
        request.workerGroupId() == null
            ? null
            : workforce.requireGroup(warehouseId, request.workerGroupId());
    Worker selected =
        request.workerId() == null
            ? null
            : entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER
                    && !joiningSecondary
                    && authenticatedWorkerId != null
                ? driverAudiences.requireExecutableWorker(entry, request.workerId())
                : workforce.requireWorker(warehouseId, request.workerId());
    if (authenticatedWorkerId != null) {
      if (selected == null || !authenticatedWorkerId.equals(selected.getId()))
        throw new ConflictException("Worker token может взять задачу только на себя");
      if (entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER) {
        if (joiningSecondary) {
          WorkerGroup currentGroup = selected.getCurrentGroup();
          if (currentGroup == null) {
            throw new ConflictException("Стропальщику не назначена текущая группа");
          }
          if (group != null && !group.equals(currentGroup)) {
            throw new ConflictException(
                "Присоединиться можно только из текущей группы рабочего");
          }
          group = currentGroup;
        } else if (group != null) {
          throw new ConflictException("Водитель берёт логистическое задание без бригады");
        }
      } else {
        WorkerGroup currentGroup = selected.getCurrentGroup();
        if (currentGroup == null) {
          throw new ConflictException("Рабочему не назначена текущая группа");
        }
        if (group != null && !group.equals(currentGroup)) {
          throw new ConflictException("Задачу можно взять только текущей группой рабочего");
        }
        group = currentGroup;
      }
    } else if (group == null && selected != null && selected.getCurrentGroup() != null) {
      group = selected.getCurrentGroup();
    }
    if (group != null && !group.isActive()) throw new ConflictException("Группа неактивна");
    if (group != null
        && group.getOperationalStatus() != GroupOperationalStatus.AVAILABLE) {
      throw new ConflictException("Группа временно недоступна");
    }
    if (selected != null && !selected.isActive()) throw new ConflictException("Рабочий неактивен");
    if (entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER) {
      if (joiningSecondary) {
        if (selected == null || group == null) {
          throw new ConflictException(
              "К логистическому заданию присоединяется стропальщик из текущей группы");
        }
      } else if (selected == null || group != null) {
        throw new ConflictException(
            "Логистическое задание назначается одному водителю без бригады");
      } else {
        driverAudiences.requireExecutableBy(entry, selected);
      }
    }
    if (!joiningSecondary) {
      ensureExecutableOrder(entry, selected == null ? null : selected.getId());
      if (entry.getQueue().getPurpose() == QueuePurpose.GENERAL) {
        linkedQueues
            .load(warehouseId)
            .requireTakeAllowed(entry.getId(), group == null ? null : group.getId());
      }
    }
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
        bindingForTake(queueBindings, assignedGroup, selected, joiningSecondary);
    boolean exactAssignedContractor =
        !joiningSecondary && driverAudiences.isExactAssignedContractor(entry, selected);
    if (!queueBindings.isEmpty() && takeBinding == null && !exactAssignedContractor) {
      throw new ConflictException(
          joiningSecondary
              ? "Присоединиться может только вторичный класс исполнителей"
              : "Начать задание может только основной класс исполнителей");
    }
    if (joiningSecondary
        && (takeBinding == null
            || takeBinding.getParticipationPolicy() == ParticipationPolicy.PRIMARY)) {
      throw new ConflictException("Для этого класса присоединение не настроено");
    }
    List<Worker> assigned =
        assignedGroup != null
                && (selected == null
                    || (!joiningSecondary && entry.getQueue().getPurpose() == QueuePurpose.GENERAL))
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
    boolean stopCurrentWork =
        takeBinding != null
            && (takeBinding.isStopTaskOnTake()
                || (joiningSecondary
                    && entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER));
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
    var streamVersions = queuePositions.lockEntryStreams(streamsToLock);
    if (stopCurrentWork) autoInterrupt(interruptionPlan, entry, now);
    if (!joiningSecondary) {
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
      a.recordParticipation(!joiningSecondary);
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
          joiningSecondary
              ? "Рабочий присоединился к заданию"
              : "Задача взята в работу",
          null,
          now);
    }
    entry = projectionWriter.saveAndFlush(entries, entry);
    projectionWriter.flush();
    if (assignedGroup != null) {
      List<QueueEntry> route =
          entries.findAllByTaskIdOrderByRouteIndexAsc(entry.getTask().getId());
      Long responsibilityBudget =
          executionPackages.budgetSeconds(
              executionPackages.unfinishedExecutionEntries(entry, route));
      kpiEvidence.beginSegment(
          warehouseId, assignedGroup.getId(), entry, now, responsibilityBudget);
    }
    interruptedEntries.forEach(
        interrupted ->
            eventSourcing.entryChanged(
                interrupted,
                queuePositions.streamVersion(
                    streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, interrupted.getId()),
                TaskBoardEventTypes.QUEUE_ENTRY_INTERRUPTED));
    eventSourcing.entryChanged(
        entry,
        queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entryId),
        TaskBoardEventTypes.QUEUE_ENTRY_TAKEN);
    ownerProofs.publish(warehouseId, entryId, true);
    return entry;
  }

  /** Pauses a taken entry under its observed version. */
  QueueEntry pause(
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
    return entry;
  }

  /** Resumes a paused entry under its observed version. */
  QueueEntry resume(
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
    return entry;
  }

  /**
   * Completes an entry under its observed version and completion rules.
   *
   * <p>A maintenance representative also completes later unfinished shadow entries in its
   * consecutive same-queue package and publishes each source-mapped completion independently.
   */
  QueueEntry complete(
      UUID warehouseId, UUID entryId, VersionCommand request, UUID authenticatedWorkerId) {
    queuePositions.lockQueueMutation(warehouseId);
    var entry = requireEntry(warehouseId, entryId);
    checkVersion(entry.getVersion(), request.expectedVersion(), "Этап");
    assertAssigned(entry, authenticatedWorkerId);
    if (entry.getStatus() != EntryStatus.IN_PROGRESS)
      throw new ConflictException("Завершить можно только этап в работе");
    ensureRequiredSecondaryAssignments(entry);
    queuePositions.lockQueuePositions(warehouseId, List.of(entry.getQueue()));
    List<QueueEntry> taskRoute = entries.findAllByTaskIdOrderByRouteIndexAsc(entry.getTask().getId());
    List<QueueEntry> completionEntries =
        executionPackages.unfinishedExecutionEntries(entry, taskRoute);
    Set<UUID> completionEntryIds =
        completionEntries.stream()
            .map(QueueEntry::getId)
            .collect(java.util.stream.Collectors.toSet());
    for (QueueEntry bundledEntry : completionEntries.subList(1, completionEntries.size())) {
      if (!assignments
          .findAllByQueueEntryIdAndStatusIn(
              bundledEntry.getId(),
              Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
          .isEmpty()) {
        throw new ConflictException(
            "Следующая часть пакета работ уже назначена отдельно");
      }
    }
    QueueEntry nextEntry =
        OrdinaryQueueAvailabilityPolicy.nextExecutableRouteEntry(
            taskRoute, completionEntryIds);
    Set<QueueEntry> resumedEntries =
        interruptions.findAllByInterruptingEntryIdAndActiveTrue(entryId).stream()
            .map(TaskAutoInterruption::getInterruptedEntry)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<QueueEntry> positionCandidates = new LinkedHashSet<>(queuePositions.orderedEntries(warehouseId, entry.getQueue()));
    Map<UUID, TaskBoardQueuePositionCoordinator.QueueEntryPosition> positionsBefore = queuePositions.positionsOf(positionCandidates);
    Set<QueueEntry> streamsToLock = new LinkedHashSet<>(positionCandidates);
    streamsToLock.addAll(resumedEntries);
    streamsToLock.addAll(completionEntries);
    if (nextEntry != null) streamsToLock.add(nextEntry);
    var streamVersions =
        nextEntry == null
            ? queuePositions.lockTaskAndEntryStreams(entry.getTask(), streamsToLock)
            : queuePositions.lockEntryStreams(streamsToLock);
    OffsetDateTime now = now();
    stopTimer(entry, now);
    Set<UUID> completedKpiGroups = kpiEvidence.completeSegment(warehouseId, entry, now);
    entry.setStatus(EntryStatus.DONE);
    entry.setDoneAt(now);
    entry.setPauseOrigin(null);
    List<TaskAssignment> active =
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
    for (QueueEntry bundledEntry : completionEntries.subList(1, completionEntries.size())) {
      completeBundledEntry(bundledEntry, active, now);
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
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.BOARD_TASK, completedTask.getId()),
          TaskBoardEventTypes.BOARD_TASK_COMPLETED);
    }
    queuePositions.normalizePositions(warehouseId, entry.getQueue());
    QueueEntry completedEntry = projectionWriter.saveAndFlush(entries, entry);
    projectionWriter.flush();
    Set<UUID> repositionedIds = queuePositions.changedPositionIds(positionCandidates, positionsBefore);
    for (QueueEntry resumedEntry : resumedEntries) {
      eventSourcing.entryChanged(
          resumedEntry,
          queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, resumedEntry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_RESUMED);
    }
    if (nextEntry != null) {
      eventSourcing.entryChanged(
          nextEntry,
          queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, nextEntry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
    }
    for (QueueEntry positionCandidate : positionCandidates) {
      if (!completionEntryIds.contains(positionCandidate.getId())
          && !positionCandidate.equals(nextEntry)
          && !resumedEntries.contains(positionCandidate)
          && repositionedIds.contains(positionCandidate.getId())) {
        eventSourcing.entryChanged(
            positionCandidate,
            queuePositions.streamVersion(
                streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, positionCandidate.getId()),
            TaskBoardEventTypes.QUEUE_ENTRY_CHANGED);
      }
    }
    for (QueueEntry bundledEntry : completionEntries.subList(1, completionEntries.size())) {
      eventSourcing.entryChanged(
          bundledEntry,
          queuePositions.streamVersion(
              streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, bundledEntry.getId()),
          TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED);
    }
    eventSourcing.entryChanged(
        completedEntry,
        queuePositions.streamVersion(streamVersions, TaskBoardAggregateType.QUEUE_ENTRY, entryId),
        TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED);
    completionEntries.forEach(
        completed -> ownerProofs.publish(warehouseId, completed.getId(), false));
    completedKpiGroups.forEach(
        groupId -> kpiEvidence.refreshGroup(warehouseId, groupId, now));
    resumedEntries.forEach(
        resumed -> kpiEvidence.refreshEntryGroup(warehouseId, resumed.getId(), now));
    kpiEvidence.refreshWarehouse(warehouseId, now);
    return completedEntry;
  }

  /**
   * Persists audit-equivalent completion for a shadow member executed with the representative
   * entry, without opening another timer or KPI responsibility segment.
   */
  private void completeBundledEntry(
      QueueEntry bundledEntry,
      List<TaskAssignment> representativeAssignments,
      OffsetDateTime completedAt) {
    bundledEntry.setStatus(EntryStatus.DONE);
    bundledEntry.setDoneAt(completedAt);
    bundledEntry.setActiveStartedAt(null);
    bundledEntry.setPausedAt(null);
    bundledEntry.setPauseOrigin(null);
    for (TaskAssignment representative : representativeAssignments) {
      TaskAssignment bundledAssignment = new TaskAssignment();
      bundledAssignment.setQueueEntry(bundledEntry);
      bundledAssignment.setWorkerGroup(representative.getWorkerGroup());
      bundledAssignment.recordParticipation(representative.getPrimaryParticipation());
      bundledAssignment.setWorker(representative.getWorker());
      bundledAssignment.setWorkerNameSnapshot(representative.getWorkerNameSnapshot());
      bundledAssignment.setGroupNameSnapshot(representative.getGroupNameSnapshot());
      bundledAssignment.setStatus(AssignmentStatus.DONE);
      bundledAssignment.setAssignedAt(representative.getAssignedAt());
      OffsetDateTime startedAt =
          representative.getStartedAt() == null
              ? representative.getAssignedAt()
              : representative.getStartedAt();
      bundledAssignment.setStartedAt(startedAt);
      bundledAssignment.setFinishedAt(completedAt);
      projectionWriter.save(assignments, bundledAssignment);
      event(
          bundledEntry,
          representative.getWorker(),
          representative.getWorkerGroup(),
          TimeEventType.STARTED,
          "Часть пакета работ взята вместе с основным этапом",
          null,
          startedAt);
      event(
          bundledEntry,
          representative.getWorker(),
          representative.getWorkerGroup(),
          TimeEventType.FINISHED,
          "Часть пакета работ завершена",
          null,
          completedAt);
    }
    projectionWriter.save(entries, bundledEntry);
  }



  private void ensureExecutableOrder(QueueEntry entry, UUID workerId) {
    if (entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER) {
      var first =
          entries
              .findAllByQueueIdAndStatusInOrderByQueuePositionAsc(
                  entry.getQueue().getId(), Set.of(EntryStatus.WAITING))
              .stream()
              .filter(candidate -> candidate.getTask().getLane() == TaskLane.CURRENT)
              .filter(candidate -> driverAudiences.isVisibleTo(candidate, workerId))
              .filter(candidate -> candidate.getEntryType() == EntryType.REAL)
              .findFirst();
      if (first.isEmpty() || !first.get().equals(entry)) {
        throw new ConflictException("Сначала возьмите первый доступный этап очереди");
      }
    }
    List<QueueEntry> route =
        entries.findAllByTaskIdOrderByRouteIndexAsc(entry.getTask().getId());
    if (entry.getQueue().getPurpose() == QueuePurpose.GENERAL) {
      QueueEntry unfinishedSes =
          route.stream()
              .filter(candidate -> UNFINISHED.contains(candidate.getStatus()))
              .filter(candidate -> RepairRoutePhaseOrder.isSesQueue(candidate.getQueue()))
              .min(Comparator.comparingInt(QueueEntry::getRouteIndex))
              .orElse(null);
      if (unfinishedSes != null && !entry.equals(unfinishedSes)) {
        throw new ConflictException("Сначала завершите обязательный этап СЭС");
      }
      // The manager command is the explicit route-order exception for ordinary work. EntryType
      // remains server-owned and the TAKE transition already rejects every SHADOW entry.
      return;
    }
    QueueEntry routeGate =
        OrdinaryQueueAvailabilityPolicy.nextExecutableRouteEntry(route, Set.of());
    if (!entry.equals(routeGate)) {
      throw new ConflictException("Сначала завершите обязательный этап маршрута");
    }
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
      boolean joiningSecondary) {
    Set<UUID> classIds =
        worker == null
            ? Set.of(group.getWorkerClass().getId())
            : workforce.activeQualifications(worker.getId()).stream()
                .map(qualification -> qualification.getWorkerClass().getId())
                .collect(java.util.stream.Collectors.toSet());
    return queueBindings.stream()
        .filter(
            binding ->
                joiningSecondary
                    ? binding.getBindingOrder() > 0
                    : binding.getBindingOrder() == 0)
        .filter(binding -> classIds.contains(binding.getWorkerClass().getId()))
        .findFirst()
        .orElse(null);
  }

  private void ensureRequiredSecondaryAssignments(QueueEntry entry) {
    if (entry.getQueue().getPurpose() == QueuePurpose.LOGISTICS_DRIVER) return;
    List<WorkQueueClassBinding> secondary =
        bindings.findAllByQueueIdOrderByBindingOrderAscIdAsc(entry.getQueue().getId()).stream()
            .filter(binding -> binding.getBindingOrder() > 0)
            .toList();
    List<WorkQueueClassBinding> required =
        secondary.stream()
            .filter(binding -> binding.getParticipationPolicy() == ParticipationPolicy.REQUIRED)
            .toList();
    if (required.isEmpty()) return;
    Set<UUID> liveSecondaryWorkerIds =
        assignments.findAllByQueueEntryIdAndStatusIn(
                entry.getId(), Set.of(AssignmentStatus.ACTIVE, AssignmentStatus.PAUSED))
            .stream()
            .map(TaskAssignment::getWorker)
            .filter(Objects::nonNull)
            .map(Worker::getId)
            .collect(java.util.stream.Collectors.toSet());
    for (WorkQueueClassBinding binding : required) {
      boolean assigned =
          liveSecondaryWorkerIds.stream()
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

  private QueueEntry requireEntryForUpdate(UUID warehouseId, UUID id) {
    var e =
        entries.findByIdForUpdate(id).orElseThrow(() -> new NotFoundException("Этап не найден"));
    if (!e.getTask().getWarehouseId().equals(warehouseId)) {
      throw new NotFoundException("Этап не найден");
    }
    return e;
  }

  private String trim(String v) {
    return v == null || v.isBlank() ? null : v.trim();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
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




  boolean routeHasStartedAssignments(List<UUID> entryIds) {
    return !entryIds.isEmpty()
        && assignments.existsByQueueEntryIdInAndStartedAtIsNotNull(entryIds);
  }

  void publishOwnership(UUID warehouseId, UUID entryId, boolean active) {
    ownerProofs.publish(warehouseId, entryId, active);
  }

  private CancelledTaskDto cancelledTaskDto(BoardTask task) {
    return new CancelledTaskDto(
        task.getId(),
        task.getExternalTaskId(),
        task.getVersion(),
        task.getStatus(),
        task.getDoneAt());
  }

  PreStartCancellationResult preStartCancellationResult(
      PreStartCancellationOutcome outcome, BoardTask task) {
    return new PreStartCancellationResult(
        outcome,
        task.getId(),
        task.getExternalTaskId(),
        task.getVersion(),
        task.getStatus(),
        task.getStatus() == TaskStatus.CANCELLED ? task.getDoneAt() : null);
  }


  /** Couples an assigned worker with active work selected for automatic interruption recovery. */
  private record InterruptedWork(Worker worker, QueueEntry entry) {}
}
