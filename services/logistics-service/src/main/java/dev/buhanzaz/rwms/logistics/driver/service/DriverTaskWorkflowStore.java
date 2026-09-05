package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.service.CustomerDeliveryCapacityFence;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns transaction-scoped claims and confirmations of driver workflow state. It performs no
 * dependency calls, so DriverTaskProcessor executes remote effects outside local write
 * transactions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DriverTaskWorkflowStore {
  /**
   * Ordinary logistics command paths request immediate processing; this poll is bounded fallback
   * recovery for changes made directly in task-board and must not issue one HTTP request per task
   * every relay second.
   */
  static final long UNCHANGED_STATUS_POLL_DELAY_SECONDS = 30;

  /**
   * Retain the former retry horizon as a saturation point, not a terminal cutoff. A transient
   * outage must never strand a physical movement in reconciliation.
   */
  static final int MAX_TRANSIENT_RETRY_COUNT = 5;

  static final long MAX_TRANSIENT_RETRY_DELAY_SECONDS = 1L << MAX_TRANSIENT_RETRY_COUNT;

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDocumentRepository documents;
  private final LogisticsDocumentLineRepository documentLines;
  private final RentalOrderUnitTermRepository rentalTerms;
  private final CustomerDeliveryCapacityFence capacityFence;
  private final DriverTaskWorkerContentCodec workerContentCodec;

  /** Execution admission must also check the calendar while a bounded expiry scan catches up. */
  public Optional<UUID> expirableTripWarehouse(UUID taskId) {
    var task = tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    return task.getTripExpiryRequestedAt() == null
            && !task.getState().isTerminal()
            && task.getState() != DriverTaskState.FINALIZING
            && (task.getKind() == DriverTaskKind.SHIPMENT
                || task.getKind() == DriverTaskKind.RETURN
                || task.getKind() == DriverTaskKind.TRANSFER)
        ? Optional.of(task.getWarehouseId())
        : Optional.empty();
  }

  @Transactional
  public Optional<Work> nextWork(UUID taskId) {
    DriverLogisticsTask task =
        tasks.findForUpdate(taskId).orElseThrow(LogisticsNotFoundException::new);
    if (task.getState().isTerminal() || !task.isDue(now())) return Optional.empty();
    if (task.getTripExpiryRequestedAt() != null
        && task.getState() != DriverTaskState.REGISTERING
        && task.getState() != DriverTaskState.FINALIZING) {
      return Optional.of(
          new ExpiryWork(task.getId(), task.getExternalTaskId(), task.getScheduledDate()));
    }
    return switch (task.getState()) {
      case REGISTERING ->
          Optional.of(
              new RegisterWork(
                  task.getId(),
                  task.getWarehouseId(),
                  task.getExternalTaskId(),
                  task.getDriverQueueDefinitionId(),
                  task.getUnitNumber(),
                  title(task),
                  description(task),
                  task.getScheduledDate(),
                  task.getPriority(),
                  new LogisticsDependencyGateway.DriverTaskAudience(
                      task.getDriverAudienceMode(),
                      task.getPlannedDriverWorkerId(),
                      task.getPlannedDriverNameSnapshot()),
                  workerContentCodec.decode(task.getWorkerContentJson()),
                  task.getSourcePlanId() == null
                      ? null
                      : new LogisticsDependencyGateway.DriverTaskPlannerLineage(
                          task.getSourcePlanId(),
                          task.getSourcePlanVersion(),
                          task.getSourcePlanWarehouseId(),
                          task.getSourcePlanDate())));
      case SCHEDULED ->
          task.hasPendingRepairPlaceRelease()
                  && task.getKind().consumesRepairPlace()
                  && task.getRepairPlaceAllocationVersion() != null
              ? Optional.of(
                  new ReservationReleaseWork(
                      task.getId(),
                      task.getWarehouseId(),
                      task.getRepairId(),
                      task.getRepairPlaceAllocationId(),
                      task.getRepairPlaceAllocationVersion()))
              : Optional.of(new StatusWork(task.getId(), task.getExternalTaskId()));
      case CURRENT -> currentWork(task);
      case FINALIZING -> finalizingWork(task);
      case COMPLETED, CANCELLED, RECONCILIATION_REQUIRED -> Optional.empty();
    };
  }

  @Transactional
  public void confirmRegistration(UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    fenceCapacityObservation(board);
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.REGISTERING
        && task.getState() != DriverTaskState.SCHEDULED) {
      return;
    }
    requireBoardTask(task, board);
    task.registerBoardTask(
        board.taskId(),
        board.taskVersion(),
        board.entryId(),
        board.entryStatus(),
        board.lane(),
        board.doneAt());
    task.observeAudience(
        board.driverAudience().mode(),
        board.driverAudience().workerId(),
        board.driverAudience().workerName());
    tasks.saveAndFlush(task);
  }

  /** Commits expiry intent independently of the eventual remote cancellation. */
  @Transactional
  public void requestTripExpiry(UUID taskId, java.time.LocalDate today) {
    DriverLogisticsTask task = locked(taskId);
    task.requestTripExpiry(today, now());
    tasks.saveAndFlush(task);
  }

  /** Retains possible loaded cargo before issuing an interrupting cancellation. */
  @Transactional
  public void observeExpiredTripCargo(
      UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    DriverLogisticsTask task = locked(taskId);
    requireBoardTask(task, board);
    task.observeExpiredTripCargo(board.entryStatus());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmStatus(UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    fenceCapacityObservation(board);
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.SCHEDULED
        && task.getState() != DriverTaskState.CURRENT) {
      return;
    }
    requireBoardTask(task, board);
    synchronizeGroupedDocumentDate(task, board);
    if (task.getTripExpiryRequestedAt() != null
        && "ACTIVE".equals(board.status())
        && "WAITING".equals(board.entryStatus())
        && !task.getScheduledDate().equals(board.scheduledDate())) {
      task.withdrawStaleTripExpiry();
    }
    if (matchesCurrentStatus(task, board)) {
      int deferred =
          tasks.deferStatusPoll(
              task.getId(),
              task.getVersion(),
              task.getState(),
              now().plusSeconds(UNCHANGED_STATUS_POLL_DELAY_SECONDS));
      if (deferred != 1) {
        throw new LogisticsConflictException("Driver task status poll fence changed");
      }
      return;
    }
    task.observeBoardTask(
        board.taskId(),
        board.taskVersion(),
        board.entryId(),
        board.entryStatus(),
        board.scheduledDate(),
        board.lane(),
        board.status(),
        board.doneAt());
    task.observeAudience(
        board.driverAudience().mode(),
        board.driverAudience().workerId(),
        board.driverAudience().workerName());
    tasks.saveAndFlush(task);
  }

  /**
   * Returns a task-board identity only for legacy-generic or task-board-stage dependency failures
   * that can be proved again from the authoritative remote task. Effect and compensation codes stay
   * terminal until their owning workflow resolves them.
   */
  public Optional<UUID> recoverableReconciliationExternalTaskId(UUID taskId) {
    DriverLogisticsTask task =
        tasks.findById(taskId).orElseThrow(LogisticsNotFoundException::new);
    if (task.getState() != DriverTaskState.RECONCILIATION_REQUIRED
        || !isRecoverableDependencyReconciliation(task)) {
      return Optional.empty();
    }
    return Optional.of(task.getExternalTaskId());
  }

  /**
   * Restores a recoverable task-board dependency checkpoint only after a matching authoritative
   * snapshot has been read. The existing aggregate transition then resumes scheduled, current,
   * finalizing or cancelled recovery without inventing local status.
   */
  @Transactional
  public void confirmReconciliationStatus(
      UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    fenceCapacityObservation(board);
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.RECONCILIATION_REQUIRED
        || !isRecoverableDependencyReconciliation(task)) {
      return;
    }
    requireBoardTask(task, board);
    synchronizeGroupedDocumentDate(task, board);
    if (task.getTripExpiryRequestedAt() != null
        && "ACTIVE".equals(board.status())
        && "WAITING".equals(board.entryStatus())
        && !task.getScheduledDate().equals(board.scheduledDate())) {
      task.withdrawStaleTripExpiry();
    }
    if (task.getTaskBoardTaskId() == null) {
      task.registerBoardTask(
          board.taskId(),
          board.taskVersion(),
          board.entryId(),
          board.entryStatus(),
          board.lane(),
          board.doneAt());
    }
    task.observeBoardTask(
        board.taskId(),
        board.taskVersion(),
        board.entryId(),
        board.entryStatus(),
        board.scheduledDate(),
        board.lane(),
        board.status(),
        board.doneAt());
    task.observeAudience(
        board.driverAudience().mode(),
        board.driverAudience().workerId(),
        board.driverAudience().workerName());
    tasks.saveAndFlush(task);
  }

  /**
   * Locks and verifies the owning grouped document before a public board move calls task-board. The
   * surrounding move transaction retains this lock until the remote receipt and local date
   * projection are confirmed together.
   */
  @Transactional
  public void requireGroupedDocumentMovePreStart(UUID taskId) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT) {
      return;
    }
    LogisticsDocument document =
        documents
            .findForUpdate(task.getSourceId())
            .orElseThrow(
                () -> new LogisticsConflictException("Логистический документ ходки не найден"));
    if (!matchesDocumentType(task.getKind(), document.getDocumentType())) {
      throw new LogisticsConflictException("Задание водителя не соответствует документу ходки");
    }
    try {
      document.requirePreStartTripReschedule();
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException("Начатую ходку нельзя перенести или переупорядочить");
    }
    UUID documentTaskWarehouse =
        document.getDocumentType() == LogisticsDocumentType.SHIPMENT
            ? DocumentDriverTaskPlanner.taskWarehouse(
                document,
                documentLines.findAllByDocument_IdOrderByLineNumber(document.getId()))
            : document.getWarehouseId();
    if (!task.getWarehouseId().equals(documentTaskWarehouse)) {
      throw new LogisticsConflictException("Задание водителя не соответствует документу ходки");
    }
  }

  /**
   * Confirms a task-board whole-trip calendar move into the owning document and its rental terms in
   * the same local transaction as the driver-task snapshot. A later status poll repeats this
   * method, so a crash after the remote move converges without a browser compensation.
   */
  private void synchronizeGroupedDocumentDate(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverBoardTask board) {
    if (task.getSourceType() != DriverTaskSourceType.LOGISTICS_DOCUMENT
        || !"SCHEDULED".equals(board.lane())
        || board.scheduledDate() == null) {
      return;
    }
    LogisticsDocument document =
        documents
            .findForUpdate(task.getSourceId())
            .orElseThrow(
                () -> new LogisticsConflictException("Логистический документ ходки не найден"));
    List<LogisticsDocumentLine> shipmentLines =
        document.getDocumentType() == LogisticsDocumentType.SHIPMENT
            ? documentLines.findAllByDocument_IdOrderByLineNumber(document.getId())
            : List.of();
    UUID documentTaskWarehouse =
        document.getDocumentType() == LogisticsDocumentType.SHIPMENT
            ? DocumentDriverTaskPlanner.taskWarehouse(document, shipmentLines)
            : document.getWarehouseId();
    if (!task.getWarehouseId().equals(documentTaskWarehouse)
        || !matchesDocumentType(task.getKind(), document.getDocumentType())) {
      throw new LogisticsConflictException("Задание водителя не соответствует документу ходки");
    }
    if (board.scheduledDate().equals(document.getScheduledDate())) {
      return;
    }
    try {
      document.reschedulePreStartTrip(board.scheduledDate());
    } catch (IllegalStateException exception) {
      throw new LogisticsConflictException("Начатую ходку нельзя перенести на другую дату");
    }
    synchronizeRentalTerms(document, shipmentLines, board.scheduledDate());
    documents.saveAndFlush(document);
  }

  private void fenceCapacityObservation(LogisticsDependencyGateway.DriverBoardTask board) {
    if (board.scheduledDate() != null) {
      capacityFence.acquireDay(board.warehouseId(), board.scheduledDate());
    }
  }

  private void synchronizeRentalTerms(
      LogisticsDocument document,
      List<LogisticsDocumentLine> shipmentLines,
      java.time.LocalDate date) {
    if (document.getDocumentType() != LogisticsDocumentType.SHIPMENT
        || document.getRentalOrderId() == null) {
      return;
    }
    List<UUID> unitIds = shipmentLines.stream().map(LogisticsDocumentLine::getAssetId).toList();
    List<RentalOrderUnitTerm> terms =
        rentalTerms.findAllByOrder_IdAndRentalItemIdInOrderByRentalItemIdAsc(
            document.getRentalOrderId(), unitIds);
    if (terms.size() != unitIds.size()) {
      throw new LogisticsConflictException(
          "Для каждой бытовки отгрузки должен быть задан срок аренды");
    }
    Map<UUID, RentalOrderUnitTerm> byUnit = new LinkedHashMap<>();
    terms.forEach(term -> byUnit.put(term.getRentalItemId(), term));
    for (UUID unitId : unitIds) {
      RentalOrderUnitTerm term = byUnit.get(unitId);
      if (term == null) {
        throw new LogisticsConflictException("Срок аренды бытовки не найден");
      }
      term.rescheduleShipment(document.getId(), date);
    }
    rentalTerms.saveAllAndFlush(terms);
  }

  private static boolean matchesDocumentType(
      DriverTaskKind kind, LogisticsDocumentType documentType) {
    return (kind == DriverTaskKind.SHIPMENT && documentType == LogisticsDocumentType.SHIPMENT)
        || (kind == DriverTaskKind.RETURN && documentType == LogisticsDocumentType.RETURN)
        || (kind == DriverTaskKind.TRANSFER && documentType == LogisticsDocumentType.TRANSFER);
  }

  /**
   * The relay polls active task-board work as a fallback for missed events. Do not turn an
   * identical poll response into a local aggregate write: it advances the JPA version and can race
   * an operator command that has already applied at task-board.
   */
  private static boolean matchesCurrentStatus(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverBoardTask board) {
    if (!Objects.equals(task.getTaskBoardTaskId(), board.taskId())
        || !Objects.equals(task.getTaskBoardTaskVersion(), board.taskVersion())
        || !Objects.equals(task.getTaskBoardEntryId(), board.entryId())
        || !Objects.equals(task.getTaskBoardEntryStatus(), board.entryStatus())
        || !Objects.equals(task.getTaskBoardDoneAt(), board.doneAt())
        || !Objects.equals(task.getScheduledDate(), board.scheduledDate())
        || task.getDriverAudienceMode() != board.driverAudience().mode()
        || !Objects.equals(task.getPlannedDriverWorkerId(), board.driverAudience().workerId())
        || !Objects.equals(task.getPlannedDriverNameSnapshot(), board.driverAudience().workerName())
        || task.getRetryCount() != 0
        || task.getFailureCode() != null) {
      return false;
    }
    return "ACTIVE".equals(board.status())
        && (("CURRENT".equals(board.lane()) && task.getState() == DriverTaskState.CURRENT)
            || ("SCHEDULED".equals(board.lane()) && task.getState() == DriverTaskState.SCHEDULED));
  }

  @Transactional
  public void confirmEvidence(
      UUID taskId, LogisticsDependencyGateway.DriverCompletionEvidence evidence) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.FINALIZING) return;
    if (!task.getExternalTaskId().equals(evidence.externalTaskId())
        || !task.getTaskBoardTaskId().equals(evidence.taskId())
        || !task.getWarehouseId().equals(evidence.warehouseId())) {
      throw new LogisticsConflictException(
          "Task-board returned mismatched driver completion evidence");
    }
    task.captureEvidence(
        evidence.evidenceId(), evidence.mediaId(), evidence.mediaGeneration(), evidence.entryId());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmCover(UUID taskId, LogisticsDependencyGateway.CabinCoverChange cover) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.FINALIZING || task.isCoverApplied()) return;
    if (!task.getWarehouseId().equals(cover.warehouseId())
        || !task.getCompletionMediaId().equals(cover.coverMediaId())
        || !task.getCompletionEntryId().equals(cover.taskBoardEntryId())) {
      throw new LogisticsConflictException(
          "Media-service returned a mismatched cabin cover result");
    }
    if (task.isGroupedDocument()) {
      task.markGroupedShipmentMemberCoverApplied(
          cover.cabinId(), cover.coverMediaId(), cover.taskBoardEntryId());
      tasks.saveAndFlush(task);
      return;
    }
    if (!task.getCabinId().equals(cover.cabinId())) {
      throw new LogisticsConflictException(
          "Media-service returned a mismatched cabin cover result");
    }
    task.markCoverApplied();
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmRepairPlaceEffect(
      UUID taskId, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.FINALIZING || task.isRepairPlaceEffectApplied()) {
      return;
    }
    if (!task.getWarehouseId().equals(allocation.warehouseId())
        || !task.getRepairId().equals(allocation.repairId())
        || !task.getCabinId().equals(allocation.rentalItemId())) {
      throw new LogisticsConflictException(
          "Maintenance-service returned a mismatched repair-place effect");
    }
    String expectedState = task.getKind().consumesRepairPlace() ? "OCCUPIED" : "RELEASED";
    if (!expectedState.equals(allocation.state())) {
      throw new LogisticsConflictException(
          "Maintenance-service returned an unexpected repair-place state");
    }
    task.markRepairPlaceEffect(allocation.id(), allocation.version());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void bindReleaseAllocation(
      UUID taskId, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    DriverLogisticsTask task = locked(taskId);
    if (!task.getKind().releasesRepairPlace()
        || !task.getWarehouseId().equals(allocation.warehouseId())
        || !task.getRepairId().equals(allocation.repairId())
        || !task.getCabinId().equals(allocation.rentalItemId())
        || !"READY_TO_RELEASE".equals(allocation.state())) {
      throw new LogisticsConflictException(
          "Ready repair-place allocation does not match the outbound task");
    }
    // This method is called from the periodic repair-place discovery pass. A previously bound
    // allocation is already a durable checkpoint, so persisting it again on every pass only
    // advances the local optimistic-lock version and can race a board move.
    if (Objects.equals(task.getRepairPlaceAllocationId(), allocation.id())
        && Objects.equals(task.getRepairPlaceAllocationVersion(), allocation.version())) {
      return;
    }
    task.bindRemovalRepairPlace(allocation.id(), allocation.version());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmReservation(
      UUID taskId, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.SCHEDULED || !task.getKind().consumesRepairPlace()) {
      return;
    }
    if (!task.getWarehouseId().equals(allocation.warehouseId())
        || !task.getRepairId().equals(allocation.repairId())
        || !task.getCabinId().equals(allocation.rentalItemId())
        || !"RESERVED".equals(allocation.state())) {
      throw new LogisticsConflictException(
          "Repair-place reservation does not match the delivery task");
    }
    task.reserveRepairPlace(allocation.id(), allocation.version());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmReservationRelease(
      UUID taskId, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    DriverLogisticsTask task = locked(taskId);
    if (!task.hasPendingRepairPlaceRelease() || task.getRepairPlaceAllocationVersion() == null) {
      return;
    }
    if (!task.getWarehouseId().equals(allocation.warehouseId())
        || !task.getRepairId().equals(allocation.repairId())
        || !task.getCabinId().equals(allocation.rentalItemId())
        || !"RELEASED".equals(allocation.state())) {
      throw new LogisticsConflictException(
          "Released repair-place reservation does not match the driver task");
    }
    task.releaseRepairPlaceReservation(allocation.id(), allocation.version());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmCurrent(UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.SCHEDULED) return;
    requireBoardTask(task, board);
    if (!"CURRENT".equals(board.lane()) || !"ACTIVE".equals(board.status())) {
      throw new LogisticsConflictException("Task-board did not move the driver task to CURRENT");
    }
    task.moveToCurrent(board.taskVersion(), board.entryId(), board.entryStatus());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void recordFailure(UUID taskId, LogisticsDependencyException exception) {
    recordFailure(taskId, exception, null);
  }

  /**
   * Records a dependency failure with the workflow stage that can authoritatively reconcile it.
   * Only register/status/evidence failures are recoverable from a task-board snapshot; media and
   * maintenance effects retain their own terminal checkpoint instead of being falsely reopened.
   */
  @Transactional
  public void recordFailure(Work work, LogisticsDependencyException exception) {
    String stage =
        switch (work) {
          case RegisterWork ignored -> "TASK_BOARD";
          case StatusWork ignored -> "TASK_BOARD";
          case ExpiryWork ignored -> "TASK_BOARD";
          case EvidenceWork ignored -> "TASK_BOARD";
          case TransferDepartureWork ignored -> "TRANSFER_EFFECT";
          case TransferArrivalWork ignored -> "TRANSFER_EFFECT";
          case CoverWork ignored -> "COVER_EFFECT";
          case RepairPlaceEffectWork ignored -> "REPAIR_PLACE_EFFECT";
          case ReservationReleaseWork ignored -> "REPAIR_PLACE_EFFECT";
        };
    recordFailure(workTaskId(work), exception, stage);
  }

  private void recordFailure(
      UUID taskId, LogisticsDependencyException exception, String stage) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState().isTerminal()) return;
    String code =
        (stage == null ? "" : stage + "_") + "DEPENDENCY_" + exception.kind().name();
    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      task.requireReconciliation(code);
    } else {
      task.retryAfterSeconds(
          transientRetryDelaySeconds(task.getRetryCount()), code, MAX_TRANSIENT_RETRY_COUNT);
    }
    tasks.saveAndFlush(task);
  }

  private static long transientRetryDelaySeconds(int retryCount) {
    int boundedRetryCount = Math.min(Math.max(retryCount, 0), MAX_TRANSIENT_RETRY_COUNT);
    return Math.min(1L << boundedRetryCount, MAX_TRANSIENT_RETRY_DELAY_SECONDS);
  }

  private static boolean isRecoverableDependencyReconciliation(String failureCode) {
    return "DEPENDENCY_CONFIGURATION".equals(failureCode)
        || "DEPENDENCY_PERMANENT_REJECTION".equals(failureCode)
        || "TASK_BOARD_DEPENDENCY_CONFIGURATION".equals(failureCode)
        || "TASK_BOARD_DEPENDENCY_PERMANENT_REJECTION".equals(failureCode);
  }

  private static boolean isRecoverableDependencyReconciliation(DriverLogisticsTask task) {
    return isRecoverableDependencyReconciliation(task.getFailureCode())
        || (task.getTripExpiryRequestedAt() != null
            && "TASK_BOARD_DEPENDENCY_TRANSIENT".equals(task.getFailureCode()));
  }

  private static UUID workTaskId(Work work) {
    return switch (work) {
      case RegisterWork value -> value.taskId();
      case StatusWork value -> value.taskId();
      case ExpiryWork value -> value.taskId();
      case EvidenceWork value -> value.taskId();
      case TransferDepartureWork value -> value.taskId();
      case TransferArrivalWork value -> value.taskId();
      case CoverWork value -> value.taskId();
      case RepairPlaceEffectWork value -> value.taskId();
      case ReservationReleaseWork value -> value.taskId();
    };
  }

  private Optional<Work> currentWork(DriverLogisticsTask task) {
    if (!isAssignedTransferTask(task)) {
      return Optional.of(new StatusWork(task.getId(), task.getExternalTaskId()));
    }
    TransferProgress progress = transferProgress(task, false);
    if (progress.work() != null) return Optional.of(progress.work());
    if (task.getState() == DriverTaskState.RECONCILIATION_REQUIRED) return Optional.empty();
    return Optional.of(new StatusWork(task.getId(), task.getExternalTaskId()));
  }

  private Optional<Work> finalizingWork(DriverLogisticsTask task) {
    if (task.getCompletionEvidenceId() == null) {
      return Optional.of(new EvidenceWork(task.getId(), task.getExternalTaskId()));
    }
    if (isAssignedTransferTask(task) && !task.isCoverApplied()) {
      // Task-board evidence and the cabin media binding still belong to the departure warehouse
      // while the asset is in transit. Freeze the cover before the arrival saga changes its
      // operational warehouse; the transfer itself remains incomplete until the owner confirms
      // every arrival effect below.
      return coverWork(task);
    }
    if (isAssignedTransferTask(task)) {
      TransferProgress progress = transferProgress(task, true);
      if (progress.work() != null) return Optional.of(progress.work());
      if (task.getState() == DriverTaskState.RECONCILIATION_REQUIRED) return Optional.empty();
      if (!progress.completed()) {
        deferTransferProgress(task);
        return Optional.empty();
      }
    }
    if (!task.isCoverApplied()) {
      return coverWork(task);
    }
    if (!task.isRepairPlaceEffectApplied()) {
      if (task.getRepairPlaceAllocationVersion() == null || task.getRepairId() == null) {
        throw new LogisticsConflictException(
            "Driver task has no repair-place allocation checkpoint");
      }
      return Optional.of(
          new RepairPlaceEffectWork(
              task.getId(),
              task.getWarehouseId(),
              task.getRepairId(),
              task.getRepairPlaceAllocationVersion(),
              task.getKind().consumesRepairPlace() ? "occupy" : "release"));
    }
    task.complete();
    tasks.saveAndFlush(task);
    return Optional.empty();
  }

  private Optional<Work> coverWork(DriverLogisticsTask task) {
    if (task.isGroupedDocument()) {
      var member = task.nextUncoveredGroupedShipmentMember();
      if (member == null) {
        throw new LogisticsConflictException(
            "Групповая ходка не синхронизировала общий checkpoint бытовок");
      }
      return Optional.of(
          new CoverWork(
              task.getId(),
              member.getCabinId(),
              task.getCompletionEntryId(),
              task.getCompletionMediaId(),
              true));
    }
    return Optional.of(
        new CoverWork(
            task.getId(),
            task.getCabinId(),
            task.getCompletionEntryId(),
            task.getCompletionMediaId(),
            false));
  }

  private TransferProgress transferProgress(DriverLogisticsTask task, boolean arrivalAllowed) {
    TransferExecutionSnapshot snapshot = transferSnapshot(task);
    if (snapshot == null) return TransferProgress.waiting();
    LogisticsDocument document = snapshot.document();
    List<LogisticsDocumentLine> lines = snapshot.lines();

    if (lines.stream()
        .anyMatch(
            line ->
                line.getState() == LogisticsLineState.CONFLICT
                    || line.getState() == LogisticsLineState.CANCELLED)) {
      requireTransferReconciliation(task, "TRANSFER_EXECUTION_LINE_CONFLICT");
      return TransferProgress.waiting();
    }

    Optional<LogisticsDocumentLine> pending =
        lines.stream().filter(line -> line.getState() == LogisticsLineState.PENDING).findFirst();
    if (pending.isPresent()) {
      if (document.getState() != LogisticsDocumentState.DRAFT
          && document.getState() != LogisticsDocumentState.DEPARTING) {
        requireTransferReconciliation(task, "TRANSFER_EXECUTION_DEPARTURE_STATE_CONFLICT");
        return TransferProgress.waiting();
      }
      LogisticsDocumentLine line = pending.orElseThrow();
      return TransferProgress.work(
          new TransferDepartureWork(
              task.getId(),
              task.getPlannedDriverWorkerId(),
              document.getId(),
              line.getId(),
              document.getVersion(),
              line.getVersion()));
    }

    if (lines.isEmpty() && document.getState() == LogisticsDocumentState.DRAFT) {
      return TransferProgress.work(
          new TransferDepartureWork(
              task.getId(),
              task.getPlannedDriverWorkerId(),
              document.getId(),
              null,
              document.getVersion(),
              null));
    }
    if (!arrivalAllowed) return TransferProgress.waiting();

    if (document.getState() == LogisticsDocumentState.COMPLETED) {
      if (lines.stream().allMatch(line -> line.getState() == LogisticsLineState.ARRIVED)) {
        return TransferProgress.complete();
      }
      requireTransferReconciliation(task, "TRANSFER_EXECUTION_COMPLETION_CONFLICT");
      return TransferProgress.waiting();
    }
    if (document.getState() == LogisticsDocumentState.IN_TRANSIT
        || document.getState() == LogisticsDocumentState.ARRIVING) {
      Optional<LogisticsDocumentLine> departed =
          lines.stream()
              .filter(line -> line.getState() == LogisticsLineState.DEPARTED)
              .findFirst();
      if (departed.isPresent()) {
        LogisticsDocumentLine line = departed.orElseThrow();
        return TransferProgress.work(
            new TransferArrivalWork(
                task.getId(),
                task.getPlannedDriverWorkerId(),
                document.getId(),
                line.getId(),
                document.getVersion(),
                line.getVersion(),
                task.getCompletionMediaId(),
                task.getCompletionMediaGeneration(),
                task.getPriority()));
      }
      if (lines.isEmpty() && document.getState() == LogisticsDocumentState.IN_TRANSIT) {
        return TransferProgress.work(
            new TransferArrivalWork(
                task.getId(),
                task.getPlannedDriverWorkerId(),
                document.getId(),
                null,
                document.getVersion(),
                null,
                task.getCompletionMediaId(),
                task.getCompletionMediaGeneration(),
                task.getPriority()));
      }
      return TransferProgress.waiting();
    }
    if (document.getState() == LogisticsDocumentState.DEPARTING) {
      return TransferProgress.waiting();
    }
    requireTransferReconciliation(task, "TRANSFER_EXECUTION_DOCUMENT_STATE_CONFLICT");
    return TransferProgress.waiting();
  }

  private TransferExecutionSnapshot transferSnapshot(DriverLogisticsTask task) {
    LogisticsDocument document =
        documents.findByIdAndDocumentType(task.getSourceId(), LogisticsDocumentType.TRANSFER)
            .orElse(null);
    if (document == null || !task.getWarehouseId().equals(document.getWarehouseId())) {
      requireTransferReconciliation(task, "TRANSFER_EXECUTION_SOURCE_CONFLICT");
      return null;
    }
    List<LogisticsDocumentLine> lines =
        documentLines.findAllByDocument_IdOrderByLineNumber(document.getId());
    List<UUID> taskLineIds =
        task.getMembers().stream().map(member -> member.getDocumentLineId()).toList();
    List<UUID> documentLineIds = lines.stream().map(LogisticsDocumentLine::getId).toList();
    if ((task.isFurnitureCargoTransfer() && !lines.isEmpty())
        || (!task.isFurnitureCargoTransfer() && !taskLineIds.equals(documentLineIds))) {
      requireTransferReconciliation(task, "TRANSFER_EXECUTION_MEMBERSHIP_CONFLICT");
      return null;
    }
    if (task.getPlannedDriverWorkerId() == null) {
      requireTransferReconciliation(task, "TRANSFER_EXECUTION_DRIVER_MISSING");
      return null;
    }
    return new TransferExecutionSnapshot(document, lines);
  }

  private void deferTransferProgress(DriverLogisticsTask task) {
    int deferred =
        tasks.deferStatusPoll(
            task.getId(),
            task.getVersion(),
            task.getState(),
            now().plusSeconds(UNCHANGED_STATUS_POLL_DELAY_SECONDS));
    if (deferred != 1) {
      throw new LogisticsConflictException("Transfer execution poll fence changed");
    }
  }

  private void requireTransferReconciliation(DriverLogisticsTask task, String code) {
    task.requireReconciliation(code);
    tasks.saveAndFlush(task);
  }

  /**
   * Returns whether logistics owns the exact executor needed for version-fenced transfer effects.
   * Shared warehouse-pool tasks keep their prior task-board-only lifecycle until a canonical
   * executor identity is assigned; guessing the worker from a mutable mobile claim is forbidden.
   */
  private static boolean isAssignedTransferTask(DriverLogisticsTask task) {
    return task.getSourceType() == DriverTaskSourceType.LOGISTICS_DOCUMENT
        && task.getKind() == DriverTaskKind.TRANSFER
        && task.getPlannedDriverWorkerId() != null;
  }

  private DriverLogisticsTask locked(UUID taskId) {
    return tasks.findForUpdate(taskId).orElseThrow(LogisticsNotFoundException::new);
  }

  private static void requireBoardTask(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverBoardTask board) {
    if (board == null
        || !task.getWarehouseId().equals(board.warehouseId())
        || !task.getExternalTaskId().equals(board.externalTaskId())
        || (task.getTaskBoardTaskId() != null
            && !task.getTaskBoardTaskId().equals(board.taskId()))) {
      throw new LogisticsConflictException("Task-board returned a mismatched driver task");
    }
  }

  private static String title(DriverLogisticsTask task) {
    if (task.isFurnitureCargoTransfer()) {
      return "Переместить мебель между складами";
    }
    if (task.isGroupedDocument()) {
      return "Отгрузить бытовки";
    }
    return switch (task.getKind()) {
      case DELIVER_TO_REPAIR -> "Доставить бытовку в ремонт";
      case REMOVE_FROM_REPAIR -> "Переместить бытовку с ремонта";
      case CAPITAL_TO_PRODUCTION -> "Переместить бытовку на производство";
      case GENERAL_MOVEMENT -> "Переместить бытовку";
      case SHIPMENT -> "Отгрузить бытовку";
      case RETURN -> "Забрать бытовку";
      case TRANSFER -> "Переместить бытовку между складами";
    };
  }

  private static String description(DriverLogisticsTask task) {
    return task.getComment() == null ? title(task) : task.getComment();
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** One exact persisted workflow stage selected for a remote relay attempt. */
  sealed interface Work
      permits RegisterWork,
          ExpiryWork,
          StatusWork,
          EvidenceWork,
          TransferDepartureWork,
          TransferArrivalWork,
          CoverWork,
          RepairPlaceEffectWork,
          ReservationReleaseWork {}

  /** Task-board registration payload frozen from a logistics-owned driver task. */
  record RegisterWork(
      UUID taskId,
      UUID warehouseId,
      UUID externalTaskId,
      UUID queueDefinitionId,
      String unitNumber,
      String title,
      String description,
      java.time.LocalDate scheduledDate,
      int priority,
      LogisticsDependencyGateway.DriverTaskAudience driverAudience,
      DriverTaskWorkerContent workerContent,
      LogisticsDependencyGateway.DriverTaskPlannerLineage plannerLineage)
      implements Work {}

  /** Status lookup used only to reconcile an already registered task-board identity. */
  record StatusWork(UUID taskId, UUID externalTaskId) implements Work {}

  /** Version-fenced overdue cancellation; completion wins a race with this work item. */
  record ExpiryWork(UUID taskId, UUID externalTaskId, java.time.LocalDate scheduledDate)
      implements Work {}

  /** Completion-evidence lookup for a task whose task-board card is already done. */
  record EvidenceWork(UUID taskId, UUID externalTaskId) implements Work {}

  /** Version-fenced departure selected from the current logistics-owned transfer snapshot. */
  record TransferDepartureWork(
      UUID taskId,
      UUID actorId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      Long expectedLineVersion)
      implements Work {}

  /** Arrival command pinned to the selected task-board media evidence and transfer revisions. */
  record TransferArrivalWork(
      UUID taskId,
      UUID actorId,
      UUID documentId,
      UUID lineId,
      long expectedDocumentVersion,
      Long expectedLineVersion,
      UUID mediaId,
      long mediaGeneration,
      int priority)
      implements Work {}

  /** Asset cover effect that follows task-board completion evidence. */
  record CoverWork(
      UUID taskId,
      UUID cabinId,
      UUID taskBoardEntryId,
      UUID evidenceMediaId,
      boolean groupedShipment)
      implements Work {}

  /** Maintenance repair-place effect performed after the driver task is final. */
  record RepairPlaceEffectWork(
      UUID taskId, UUID warehouseId, UUID repairId, long expectedVersion, String transition)
      implements Work {}

  /** Immediate maintenance reservation release after work leaves the current lane. */
  record ReservationReleaseWork(
      UUID taskId, UUID warehouseId, UUID repairId, UUID allocationId, long expectedVersion)
      implements Work {}

  private record TransferExecutionSnapshot(
      LogisticsDocument document, List<LogisticsDocumentLine> lines) {}

  private record TransferProgress(Work work, boolean completed) {
    private static TransferProgress work(Work work) {
      return new TransferProgress(Objects.requireNonNull(work), false);
    }

    private static TransferProgress waiting() {
      return new TransferProgress(null, false);
    }

    private static TransferProgress complete() {
      return new TransferProgress(null, true);
    }
  }
}
