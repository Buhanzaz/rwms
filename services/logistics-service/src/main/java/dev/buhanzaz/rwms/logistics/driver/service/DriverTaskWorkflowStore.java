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
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class DriverTaskWorkflowStore {
  private static final int MAX_RETRIES = 5;

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
              title(task.getKind()),
              task.getScheduledDate(),
              task.getPriority()));
      case SCHEDULED, CURRENT ->
          Optional.of(new StatusWork(task.getId(), task.getExternalTaskId()));
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
    task.observeBoardTask(
        board.taskId(),
        board.taskVersion(),
        board.entryId(),
        board.entryStatus(),
        board.scheduledDate(),
        board.lane(),
        board.status(),
        board.doneAt());
    tasks.saveAndFlush(task);
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
    if (!task.getCabinId().equals(cover.cabinId())
        || !task.getWarehouseId().equals(cover.warehouseId())
        || !task.getCompletionMediaId().equals(cover.coverMediaId())
        || !task.getCompletionEntryId().equals(cover.taskBoardEntryId())) {
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
  public void bindRemovalAllocation(
      UUID taskId, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    DriverLogisticsTask task = locked(taskId);
    if (task.getKind() != DriverTaskKind.REMOVE_FROM_REPAIR
        || !task.getWarehouseId().equals(allocation.warehouseId())
        || !task.getRepairId().equals(allocation.repairId())
        || !task.getCabinId().equals(allocation.rentalItemId())
        || !"READY_TO_RELEASE".equals(allocation.state())) {
      throw new LogisticsConflictException(
          "Ready repair-place allocation does not match the removal task");
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
        || exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION
        || task.getRetryCount() >= MAX_RETRIES) {
      task.requireReconciliation(code);
    } else {
      task.retryAfterSeconds(1L << task.getRetryCount(), code);
    }
    tasks.saveAndFlush(task);
  }

  private Optional<Work> finalizingWork(DriverLogisticsTask task) {
    if (task.getCompletionEvidenceId() == null) {
      return Optional.of(new EvidenceWork(task.getId(), task.getExternalTaskId()));
    }
    if (!task.isCoverApplied()) {
      return Optional.of(
          new CoverWork(
              task.getId(),
              task.getCabinId(),
              task.getCompletionEntryId(),
              task.getCompletionMediaId()));
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

  private static String title(DriverTaskKind kind) {
    return switch (kind) {
      case DELIVER_TO_REPAIR -> "Доставить бытовку в ремонт";
      case REMOVE_FROM_REPAIR -> "Вывезти бытовку после ремонта";
      case CAPITAL_TO_PRODUCTION -> "Переместить бытовку на производство";
      case MOVE_TO_SHIPMENT -> "Переместить бытовку на отгрузку";
    };
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  sealed interface Work
      permits RegisterWork, StatusWork, EvidenceWork, CoverWork, RepairPlaceEffectWork {}

  record RegisterWork(
      UUID taskId,
      UUID warehouseId,
      UUID externalTaskId,
      UUID queueDefinitionId,
      String unitNumber,
      String title,
      java.time.LocalDate scheduledDate,
      int priority)
      implements Work {}

  record StatusWork(UUID taskId, UUID externalTaskId) implements Work {}

  record EvidenceWork(UUID taskId, UUID externalTaskId) implements Work {}

  record CoverWork(
      UUID taskId, UUID cabinId, UUID taskBoardEntryId, UUID evidenceMediaId)
      implements Work {}

  record RepairPlaceEffectWork(
      UUID taskId,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition)
      implements Work {}
}
