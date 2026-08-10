package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns transaction-scoped claims and confirmations of driver workflow state. It performs no
 * dependency calls, so DriverTaskProcessor executes remote effects outside local write transactions.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DriverTaskWorkflowStore {
  /**
   * Retain the former retry horizon as a saturation point, not a terminal cutoff. A transient
   * outage must never strand a physical movement in reconciliation.
   */
  static final int MAX_TRANSIENT_RETRY_COUNT = 5;
  static final long MAX_TRANSIENT_RETRY_DELAY_SECONDS = 1L << MAX_TRANSIENT_RETRY_COUNT;

  private final DriverLogisticsTaskRepository tasks;

  @Transactional
  public Optional<Work> nextWork(UUID taskId) {
    DriverLogisticsTask task =
        tasks.findForUpdate(taskId).orElseThrow(LogisticsNotFoundException::new);
    if (task.getState().isTerminal() || !task.isDue(now())) return Optional.empty();
    return switch (task.getState()) {
      case REGISTERING -> Optional.of(
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
                  task.getPlannedDriverNameSnapshot())));
      case SCHEDULED ->
          task.hasManualPromotionHold()
                  && task.getKind().consumesRepairPlace()
                  && task.getRepairPlaceAllocationVersion() != null
              ? Optional.of(
                  new ManualReservationReleaseWork(
                      task.getId(),
                      task.getWarehouseId(),
                      task.getRepairId(),
                      task.getRepairPlaceAllocationId(),
                      task.getRepairPlaceAllocationVersion()))
              : Optional.of(new StatusWork(task.getId(), task.getExternalTaskId()));
      case CURRENT -> Optional.of(new StatusWork(task.getId(), task.getExternalTaskId()));
      case FINALIZING -> finalizingWork(task);
      case COMPLETED, CANCELLED, RECONCILIATION_REQUIRED -> Optional.empty();
    };
  }

  @Transactional
  public void confirmRegistration(
      UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
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

  @Transactional
  public void confirmStatus(
      UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.SCHEDULED
        && task.getState() != DriverTaskState.CURRENT) {
      return;
    }
    requireBoardTask(task, board);
    if (matchesCurrentStatus(task, board)) {
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
   * The relay polls active task-board work as a fallback for missed events. Do not turn an
   * identical poll response into a local aggregate write: it advances the JPA version and can
   * race an operator command that has already applied at task-board.
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
        || !Objects.equals(
            task.getPlannedDriverWorkerId(), board.driverAudience().workerId())
        || !Objects.equals(
            task.getPlannedDriverNameSnapshot(), board.driverAudience().workerName())
        || task.getRetryCount() != 0
        || task.getFailureCode() != null) {
      return false;
    }
    return "ACTIVE".equals(board.status())
        && (("CURRENT".equals(board.lane()) && task.getState() == DriverTaskState.CURRENT)
            || ("SCHEDULED".equals(board.lane())
                && task.getState() == DriverTaskState.SCHEDULED));
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
        evidence.evidenceId(),
        evidence.mediaId(),
        evidence.mediaGeneration(),
        evidence.entryId());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmCover(
      UUID taskId, LogisticsDependencyGateway.CabinCoverChange cover) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.FINALIZING || task.isCoverApplied()) return;
    if (!task.getWarehouseId().equals(cover.warehouseId())
        || !task.getCompletionMediaId().equals(cover.coverMediaId())
        || !task.getCompletionEntryId().equals(cover.taskBoardEntryId())) {
      throw new LogisticsConflictException(
          "Media-service returned a mismatched cabin cover result");
    }
    if (task.isGroupedShipment()) {
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
    if (task.getState() != DriverTaskState.FINALIZING
        || task.isRepairPlaceEffectApplied()) {
      return;
    }
    if (!task.getWarehouseId().equals(allocation.warehouseId())
        || !task.getRepairId().equals(allocation.repairId())
        || !task.getCabinId().equals(allocation.rentalItemId())) {
      throw new LogisticsConflictException(
          "Maintenance-service returned a mismatched repair-place effect");
    }
    String expectedState =
        task.getKind().consumesRepairPlace() ? "OCCUPIED" : "RELEASED";
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
    if (task.getState() != DriverTaskState.SCHEDULED
        || !task.getKind().consumesRepairPlace()) {
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
  public void confirmManualReservationRelease(
      UUID taskId, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    DriverLogisticsTask task = locked(taskId);
    if (!task.hasManualPromotionHold() || task.getRepairPlaceAllocationVersion() == null) return;
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
  public void confirmCurrent(
      UUID taskId, LogisticsDependencyGateway.DriverBoardTask board) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState() != DriverTaskState.SCHEDULED) return;
    requireBoardTask(task, board);
    if (!"CURRENT".equals(board.lane()) || !"ACTIVE".equals(board.status())) {
      throw new LogisticsConflictException(
          "Task-board did not move the driver task to CURRENT");
    }
    task.moveToCurrent(board.taskVersion(), board.entryId(), board.entryStatus());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void recordFailure(UUID taskId, LogisticsDependencyException exception) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getState().isTerminal()) return;
    String code = "DEPENDENCY_" + exception.kind().name();
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

  private Optional<Work> finalizingWork(DriverLogisticsTask task) {
    if (task.getCompletionEvidenceId() == null) {
      return Optional.of(new EvidenceWork(task.getId(), task.getExternalTaskId()));
    }
    if (!task.isCoverApplied()) {
      if (task.isGroupedShipment()) {
        var member = task.nextUncoveredGroupedShipmentMember();
        if (member == null) {
          throw new LogisticsConflictException(
              "Групповая отгрузка не синхронизировала общий checkpoint бытовок");
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
      throw new LogisticsConflictException(
          "Task-board returned a mismatched driver task");
    }
  }

  private static String title(DriverLogisticsTask task) {
    if (task.isGroupedShipment()) {
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

  sealed interface Work
      permits RegisterWork,
          StatusWork,
          EvidenceWork,
          CoverWork,
          RepairPlaceEffectWork,
          ManualReservationReleaseWork {}

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
      LogisticsDependencyGateway.DriverTaskAudience driverAudience)
      implements Work {}

  record StatusWork(UUID taskId, UUID externalTaskId) implements Work {}

  record EvidenceWork(UUID taskId, UUID externalTaskId) implements Work {}

  record CoverWork(
      UUID taskId,
      UUID cabinId,
      UUID taskBoardEntryId,
      UUID evidenceMediaId,
      boolean groupedShipment)
      implements Work {}

  record RepairPlaceEffectWork(
      UUID taskId,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition)
      implements Work {}

  record ManualReservationReleaseWork(
      UUID taskId,
      UUID warehouseId,
      UUID repairId,
      UUID allocationId,
      long expectedVersion)
      implements Work {}
}
