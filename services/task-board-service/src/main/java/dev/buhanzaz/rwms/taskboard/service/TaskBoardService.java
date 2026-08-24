package dev.buhanzaz.rwms.taskboard.service;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;

import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns operational task, route-entry, assignment and board-state transitions.
 *
 * <p>The service is the single command owner after a source domain has requested work. It holds
 * local transactions, emits task-board facts, fences warehouse lifecycle admission and protects
 * every mutable transition with aggregate versions or a stable source identity.
 */
@Service
public class TaskBoardService {
  private final TaskBoardReadProjectionService readProjections;
  private final TaskBoardExternalRegistrationService externalTasks;
  private final TaskBoardExternalMutationService externalMutations;
  private final TaskBoardLogisticsTaskService logisticsTasks;
  private final TaskBoardWorkerExecutionService workerExecutions;
  private final TaskBoardPinningService pinning;
  private final TaskBoardEntryOrderingService ordering;
  private final TaskBoardFutureAvailabilityService futureAvailability;

  public TaskBoardService(
      TaskBoardReadProjectionService readProjections,
      TaskBoardExternalRegistrationService externalTasks,
      TaskBoardExternalMutationService externalMutations,
      TaskBoardLogisticsTaskService logisticsTasks,
      TaskBoardWorkerExecutionService workerExecutions,
      TaskBoardPinningService pinning,
      TaskBoardEntryOrderingService ordering,
      TaskBoardFutureAvailabilityService futureAvailability) {
    this.readProjections = readProjections;
    this.externalTasks = externalTasks;
    this.externalMutations = externalMutations;
    this.logisticsTasks = logisticsTasks;
    this.workerExecutions = workerExecutions;
    this.pinning = pinning;
    this.ordering = ordering;
    this.futureAvailability = futureAvailability;
  }

  /** Returns every unfinished manager-visible entry in the stable aggregate ordinary board. */
  @Transactional(readOnly = true)
  public TaskBoardSnapshot snapshot(UUID warehouseId) {
    return readProjections.snapshot(warehouseId);
  }

  @Transactional(readOnly = true)
  /** Returns the separate driver-logistics projection for one warehouse. */
  public LogisticsBoardSnapshot logisticsSnapshot(UUID warehouseId) {
    return readProjections.logisticsSnapshot(warehouseId);
  }

  /**
   * Worker feed keeps the aggregate ordinary view, hides stages behind an unfinished SES gate and
   * adds only actionable logistics entries from the server-controlled current lane. The lane is
   * ordered; only its first waiting entry is actionable for a driver.
   */
  @Transactional(readOnly = true)
  public TaskBoardSnapshot workerSnapshot(UUID warehouseId, UUID workerId) {
    return readProjections.workerSnapshot(warehouseId, workerId);
  }

  /** Returns the feed projection under the exact native surface's audience policy. */
  @Transactional(readOnly = true)
  public TaskBoardSnapshot workerSnapshot(
      MobileTaskSurface surface, UUID warehouseId, UUID workerId) {
    return readProjections.workerSnapshot(surface, warehouseId, workerId);
  }

  @Transactional(readOnly = true)
  public BoardEntryDto entry(UUID warehouseId, UUID entryId) {
    return readProjections.entry(warehouseId, entryId);
  }

  @Transactional(readOnly = true)
  /** Returns an entry only when the authenticated worker may discover its driver audience. */
  public BoardEntryDto workerEntry(UUID warehouseId, UUID entryId, UUID workerId) {
    return readProjections.workerEntry(warehouseId, entryId, workerId);
  }

  /** Returns an entry only when its audience is visible to the exact native surface. */
  @Transactional(readOnly = true)
  public BoardEntryDto workerEntry(
      MobileTaskSurface surface, UUID warehouseId, UUID entryId, UUID workerId) {
    return readProjections.workerEntry(surface, warehouseId, entryId, workerId);
  }

  @Transactional(readOnly = true)
  public TaskWorkerContentDto workerContent(UUID warehouseId, UUID entryId) {
    return readProjections.workerContent(warehouseId, entryId);
  }

  @Transactional(propagation = Propagation.NEVER)
  /** Creates a manager-originated task and all of its route entries. */
  public TaskBoardSnapshot createTask(UUID warehouseId, CreateBoardTaskRequest request) {
    return externalTasks.createManagerTask(
        warehouseId,
        request,
        task -> readProjections.snapshot(warehouseId));
  }

  @Transactional(propagation = Propagation.NEVER)
  /**
   * Registers work requested by an authenticated source service.
   *
   * <p>The source client ID and external task ID form the replay-safe ownership boundary.
   */
  public BoardTaskRegistrationDto registerExternalTask(
      String sourceClientId, RegisterExternalTaskRequest request) {
    return externalTasks.registerExternalTask(sourceClientId, request);
  }

  /**
   * Registers a source-owned task whose generated detail is limited to typed furniture operations.
   * The persisted logistics discriminator preserves the immutable equipment-movement task shape;
   * its deadline never prevents an authorized worker from recording actual completion.
   */
  @Transactional(propagation = Propagation.NEVER)
  /** Creates the task-board representation of immutable logistics equipment-movement facts. */
  public LogisticsTaskSnapshot registerLogisticsEquipmentMovementTask(
      RegisterLogisticsEquipmentMovementTaskRequest request) {
    return logisticsTasks.registerLogisticsEquipmentMovementTask(request);
  }

  @Transactional
  /** Cancels a task in a warehouse after its observed task version has been checked. */
  public CancelledTaskDto cancelTask(
      UUID warehouseId, UUID externalTaskId, CancelTaskRequest request) {
    return workerExecutions.cancelTask(warehouseId, externalTaskId, request);
  }

  @Transactional(readOnly = true)
  /** Resolves an external-task registration in the supplied warehouse scope. */
  public BoardTaskRegistrationDto registration(UUID warehouseId, UUID externalTaskId) {
    return readProjections.registration(warehouseId, externalTaskId);
  }

  @Transactional(readOnly = true)
  /** Resolves an external task only when it belongs to the authenticated source client. */
  public BoardTaskRegistrationDto externalTask(String sourceClientId, UUID externalTaskId) {
    return readProjections.externalTask(sourceClientId, externalTaskId);
  }

  @Transactional(readOnly = true)
  /** Returns the evidence selected for a completed source-owned task. */
  public SelectedCompletionEvidenceDto selectedCompletionEvidence(
      String sourceClientId, UUID externalTaskId) {
    return readProjections.selectedCompletionEvidence(sourceClientId, externalTaskId);
  }

  @Transactional
  /** Changes the lane of a source-owned driver task under its task-version fence. */
  public BoardTaskRegistrationDto setExternalTaskLane(
      String sourceClientId, UUID externalTaskId, SetTaskLaneRequest request) {
    return externalMutations.setExternalTaskLane(sourceClientId, externalTaskId, request);
  }

  @Transactional
  /** Moves a logistics-owned driver entry under task and entry version fences. */
  public BoardTaskRegistrationDto moveExternalLogisticsTask(
      UUID externalTaskId, MoveExternalLogisticsTaskRequest request) {
    return externalMutations.moveExternalLogisticsTask(externalTaskId, request);
  }

  @Transactional(readOnly = true)
  /** Resolves the typed equipment-movement snapshot for logistics. */
  public LogisticsTaskSnapshot logisticsEquipmentMovementTask(UUID externalTaskId) {
    return logisticsTasks.logisticsEquipmentMovementTask(externalTaskId);
  }

  @Transactional
  /** Cancels source-owned work under the supplied task version. */
  public CancelledTaskDto cancelExternalTask(
      String sourceClientId, UUID externalTaskId, CancelTaskRequest request) {
    return externalMutations.cancelExternalTask(sourceClientId, externalTaskId, request);
  }

  /**
   * Atomically cancels a source-owned task only while every route entry is still waiting.  This is
   * intentionally separate from the operator cancellation command, which is allowed to interrupt
   * active work.  Callers use the explicit outcome to reconcile a lost response without ever
   * turning started physical work into a cancellation.
   */
  @Transactional
  /** Reports a typed result when source-owned work is cancelled only before it starts. */
  public PreStartCancellationResult cancelExternalTaskIfPreStart(
      String sourceClientId, UUID externalTaskId, CancelTaskRequest request) {
    return externalMutations.cancelExternalTaskIfPreStart(sourceClientId, externalTaskId, request);
  }

  @Transactional
  /** Cancels the typed logistics equipment-movement task under its task version. */
  public LogisticsTaskSnapshot cancelLogisticsEquipmentMovementTask(
      UUID externalTaskId, CancelLogisticsEquipmentMovementTaskRequest request) {
    return logisticsTasks.cancelLogisticsEquipmentMovementTask(externalTaskId, request);
  }

  @Transactional
  /**
   * Replaces source task content only before execution, while returning an exact replay unchanged.
   */
  public BoardTaskRegistrationDto updateExternalTaskBeforeStart(
      String sourceClientId, UUID externalTaskId, PreStartUpdateTaskRequest request) {
    return externalMutations.updateExternalTaskBeforeStart(sourceClientId, externalTaskId, request);
  }

  @Transactional(propagation = Propagation.NEVER)
  /**
   * Relocates source-owned work after explicit outgoing and incoming warehouse admission.
   *
   * <p>The fence makes the network-visible lifecycle decision durable relative to the local task
   * transaction and avoids admitting a task into a draining target warehouse.
   */
  public BoardTaskRegistrationDto relocateExternalTask(
      String sourceClientId,
      UUID externalTaskId,
      RelocateExternalTaskRequest request) {
    return externalMutations.relocateExternalTask(sourceClientId, externalTaskId, request);
  }

  @Transactional(readOnly = true)
  /** Lists currently eligible operational groups for one physical queue. */
  public List<WorkerGroupDto> eligibleGroups(UUID warehouseId, UUID queueId) {
    return readProjections.eligibleGroups(warehouseId, queueId);
  }

  @Transactional(readOnly = true)
  /** Returns the durable time-event history for an entry in the warehouse scope. */
  public List<TimeEventDto> history(UUID warehouseId, UUID entryId) {
    return readProjections.history(warehouseId, entryId);
  }

  @Transactional
  public List<UUID> returnActiveWorkForGroup(UUID warehouseId, UUID groupId) {
    return workerExecutions.returnActiveWorkForGroup(warehouseId, groupId);
  }

  @Transactional
  /**
   * Takes work through the public board boundary; an authenticated worker identity also receives
   * the current WorkerApp plan fence, while a manager command remains complete-board scoped.
   */
  public BoardEntryDto take(
      UUID warehouseId, UUID entryId, TakeEntryRequest request, UUID authenticatedWorkerId) {
    return readProjections.dto(
        workerExecutions.take(
            warehouseId,
            entryId,
            request,
            authenticatedWorkerId,
            authenticatedWorkerId != null));
  }

  /** Takes work from a native surface while applying WorkerApp-only plan admission. */
  @Transactional
  public BoardEntryDto takeFromMobile(
      MobileTaskSurface surface,
      UUID warehouseId,
      UUID entryId,
      TakeEntryRequest request,
      UUID authenticatedWorkerId) {
    return readProjections.dto(
        workerExecutions.take(
            warehouseId,
            entryId,
            request,
            authenticatedWorkerId,
            surface == MobileTaskSurface.WORKER));
  }

  @Transactional
  /** Pauses a taken entry under its observed version. */
  public BoardEntryDto pause(
      UUID warehouseId, UUID entryId, PauseEntryRequest request, UUID authenticatedWorkerId) {
    return readProjections.dto(workerExecutions.pause(warehouseId, entryId, request, authenticatedWorkerId));
  }

  @Transactional
  /** Resumes a paused entry under its observed version. */
  public BoardEntryDto resume(
      UUID warehouseId, UUID entryId, VersionCommand request, UUID authenticatedWorkerId) {
    return readProjections.dto(workerExecutions.resume(warehouseId, entryId, request, authenticatedWorkerId));
  }

  @Transactional
  /** Completes an entry under its observed version and completion rules. */
  public BoardEntryDto complete(
      UUID warehouseId, UUID entryId, VersionCommand request, UUID authenticatedWorkerId) {
    return readProjections.dto(workerExecutions.complete(warehouseId, entryId, request, authenticatedWorkerId));
  }

  @Transactional
  /** Pins or unpins all route entries for a task without changing their positions. */
  public TaskBoardSnapshot pin(UUID warehouseId, UUID taskId, PinTaskRequest request) {
    pinning.pin(warehouseId, taskId, request);
    return readProjections.snapshot(warehouseId);
  }

  /**
   * Changes one still-future ordinary route entry between executable and shadow states.
   *
   * <p>The collaborator owns route eligibility, the entry CAS, its event fact and the after-commit
   * WorkerApp feed invalidation; this facade only returns the refreshed manager projection.
   */
  @Transactional
  public TaskBoardSnapshot setFutureTaskEntryAvailability(
      UUID warehouseId,
      UUID entryId,
      SetFutureTaskEntryAvailabilityRequest request) {
    futureAvailability.setAvailability(warehouseId, entryId, request);
    return readProjections.snapshot(warehouseId);
  }

  /** Reorders one eligible waiting real card and returns the resulting complete manager board. */
  @Transactional
  public TaskBoardSnapshot reorder(
      UUID warehouseId, UUID entryId, ReorderBoardEntryRequest request) {
    ordering.reorder(warehouseId, entryId, request);
    return readProjections.snapshot(warehouseId);
  }
}
