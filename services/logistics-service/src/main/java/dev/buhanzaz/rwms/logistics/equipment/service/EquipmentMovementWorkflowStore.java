package dev.buhanzaz.rwms.logistics.equipment.service;

import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLineState;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementLocationKind;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLimits;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskLineRepository;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Durable local half of source reservations, board work and eventual asset execution. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
class EquipmentMovementWorkflowStore {
  private static final int MAX_RETRIES = 3;

  private final EquipmentMovementTaskRepository tasks;
  private final EquipmentMovementTaskLineRepository lines;

  @Transactional
  public Optional<Work> nextWork(UUID taskId) {
    if (taskId == null) return Optional.empty();
    EquipmentMovementTask task =
        tasks
            .findForUpdate(taskId)
            .orElseThrow(LogisticsNotFoundException::new);
    OffsetDateTime now = now();
    if (task.getState().isTerminal() || !task.isDue(now)) return Optional.empty();

    // A worker can finish just before the deadline while the next 2-second poll happens
    // just after it. Read that authoritative completion before expiring the reservation.
    if (task.isExpired(now) && task.getState() == EquipmentMovementTaskState.AWAITING_WORKER) {
      return Optional.of(statusWork(task));
    }
    if (task.isExpired(now)
        && task.getState() != EquipmentMovementTaskState.CANCELLING
        && task.getState() != EquipmentMovementTaskState.EXECUTING) {
      task.beginCancellation(EquipmentMovementTaskState.EXPIRED, "RESERVATION_EXPIRED");
      tasks.saveAndFlush(task);
    }

    List<EquipmentMovementTaskLine> taskLines = lines.findAllByTask_IdOrderByLineNumberAsc(taskId);
    return switch (task.getState()) {
      case RESERVING -> reservingWork(task, taskLines);
      case REGISTERING_TASK -> Optional.of(registerWork(task, taskLines));
      case AWAITING_WORKER -> Optional.of(statusWork(task));
      case EXECUTING -> Optional.of(executeWork(task, taskLines));
      case CANCELLING -> cancellingWork(task, taskLines);
      case COMPLETED, CANCELLED, EXPIRED, CONFLICT, RECONCILIATION_REQUIRED -> Optional.empty();
    };
  }

  @Transactional
  public void confirmReservation(
      UUID taskId, UUID lineId, LogisticsDependencyGateway.EquipmentMovementReservation reservation) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState() != EquipmentMovementTaskState.RESERVING) return;
    EquipmentMovementTaskLine line = requiredLine(taskId, lineId);
    requireReservation(task, line, reservation, "ACTIVE");
    line.reserve(
        reservation.reservationId(),
        reservation.version(),
        reservation.equipmentId(),
        reservation.equipmentName(),
        reservation.state());
    lines.saveAndFlush(line);
  }

  @Transactional
  public void confirmTaskRegistration(
      UUID taskId, LogisticsDependencyGateway.EquipmentMovementBoardTask boardTask) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState() != EquipmentMovementTaskState.REGISTERING_TASK
        && task.getState() != EquipmentMovementTaskState.AWAITING_WORKER) {
      return;
    }
    requireBoardTask(task, boardTask, "ACTIVE");
    task.registerBoardTask(
        boardTask.taskId(), boardTask.taskVersion(), boardTask.status(), boardTask.doneAt());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmTaskStatus(
      UUID taskId, LogisticsDependencyGateway.EquipmentMovementBoardTask boardTask) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState() != EquipmentMovementTaskState.AWAITING_WORKER) return;
    requireBoardTask(task, boardTask, null);
    task.observeBoardTask(boardTask.taskVersion(), boardTask.status(), boardTask.doneAt());
    if (task.getState() == EquipmentMovementTaskState.AWAITING_WORKER && task.isExpired(now())) {
      task.beginCancellation(EquipmentMovementTaskState.EXPIRED, "RESERVATION_EXPIRED");
    }
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmExecution(
      UUID taskId, LogisticsDependencyGateway.EquipmentMovementExecution execution) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState() != EquipmentMovementTaskState.EXECUTING) return;
    if (execution == null
        || !task.assetMovementOwnerId().equals(execution.movementId())
        || execution.lines() == null) {
      throw new LogisticsConflictException("Asset-service returned a mismatched equipment movement execution");
    }
    List<EquipmentMovementTaskLine> taskLines = lines.findAllByTask_IdOrderByLineNumberAsc(taskId);
    Map<UUID, LogisticsDependencyGateway.EquipmentMovementExecutionLine> resultByLine = new HashMap<>();
    for (LogisticsDependencyGateway.EquipmentMovementExecutionLine resultLine : execution.lines()) {
      if (resultLine == null || resultLine.lineId() == null || resultByLine.put(resultLine.lineId(), resultLine) != null) {
        throw new LogisticsConflictException("Asset-service returned duplicate equipment movement execution lines");
      }
    }
    if (resultByLine.size() != taskLines.size()) {
      throw new LogisticsConflictException("Asset-service returned incomplete equipment movement execution");
    }
    for (EquipmentMovementTaskLine line : taskLines) {
      LogisticsDependencyGateway.EquipmentMovementExecutionLine result = resultByLine.get(line.getId());
      if (result == null
          || !line.getReservationId().equals(result.reservationId())
          || result.reservationVersion() < line.getReservationVersion()
          || result.movement() == null
          || !line.getEquipmentId().equals(result.movement().equipmentId())
          || line.getQuantity() != result.movement().quantity()) {
        throw new LogisticsConflictException("Asset-service returned a mismatched equipment movement line");
      }
      line.execute(result.reservationVersion());
    }
    lines.saveAllAndFlush(taskLines);
    task.complete();
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmTaskCancellation(
      UUID taskId, LogisticsDependencyGateway.EquipmentMovementBoardTask boardTask) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState() != EquipmentMovementTaskState.CANCELLING || task.isTaskBoardCancelled()) return;
    requireBoardTask(task, boardTask, "CANCELLED");
    task.boardTaskCancelled(boardTask.taskVersion());
    tasks.saveAndFlush(task);
  }

  @Transactional
  public void confirmReservationRelease(
      UUID taskId, UUID lineId, LogisticsDependencyGateway.EquipmentMovementReservation reservation) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState() != EquipmentMovementTaskState.CANCELLING) return;
    EquipmentMovementTaskLine line = requiredLine(taskId, lineId);
    requireReservation(task, line, reservation, "RELEASED");
    line.release(reservation.version(), reservation.state());
    lines.saveAndFlush(line);
  }

  @Transactional
  public void recordFailure(UUID taskId, LogisticsDependencyException exception) {
    EquipmentMovementTask task = lockedTask(taskId);
    if (task.getState().isTerminal()) return;
    String code = "DEPENDENCY_" + exception.kind().name();
    if (exception.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
      if (task.getState() == EquipmentMovementTaskState.CANCELLING) {
        // The board could have completed between the user's cancellation and our
        // request, or an already-expired reservation could need evidence review.
        task.requireReconciliation(code);
      } else if (task.getState() == EquipmentMovementTaskState.EXECUTING) {
        task.failExecution(code);
      } else {
        task.beginCancellation(EquipmentMovementTaskState.CONFLICT, code);
      }
      tasks.saveAndFlush(task);
      return;
    }
    if (exception.kind() == LogisticsDependencyException.FailureKind.CONFIGURATION
        || task.getRetryCount() >= MAX_RETRIES) {
      task.requireReconciliation(code);
      tasks.saveAndFlush(task);
      return;
    }
    task.retryAfterSeconds(1L << task.getRetryCount());
    tasks.saveAndFlush(task);
  }

  private Optional<Work> reservingWork(
      EquipmentMovementTask task, List<EquipmentMovementTaskLine> taskLines) {
    EquipmentMovementTaskLine pending =
        taskLines.stream()
            .filter(line -> line.getState() == EquipmentMovementLineState.PENDING_RESERVATION)
            .findFirst()
            .orElse(null);
    if (pending == null) {
      if (taskLines.isEmpty()
          || taskLines.stream().anyMatch(line -> line.getState() != EquipmentMovementLineState.RESERVED)) {
        throw new LogisticsConflictException("Equipment movement task reservations are inconsistent");
      }
      task.reservationsComplete();
      tasks.saveAndFlush(task);
      return Optional.of(registerWork(task, taskLines));
    }
    return Optional.of(
        new ReserveWork(
            task.getId(),
            task.assetMovementOwnerId(),
            pending.getId(),
            pending.getEquipmentId(),
            pending.getSourceWarehouseId(),
            pending.getSourceRentalItemId(),
            pending.getSourceLocationKind().name(),
            pending.getExpectedSourceBalanceVersion(),
            pending.getQuantity(),
            task.getDeadlineAt()));
  }

  private RegisterWork registerWork(
      EquipmentMovementTask task, List<EquipmentMovementTaskLine> taskLines) {
    if (taskLines.isEmpty()
        || taskLines.stream().anyMatch(line -> line.getState() != EquipmentMovementLineState.RESERVED)) {
      throw new LogisticsConflictException("Equipment movement task cannot be registered before all reservations");
    }
    List<LogisticsDependencyGateway.EquipmentMovementOperation> operations = operations(taskLines);
    return new RegisterWork(
        task.getId(),
        task.getWarehouseId(),
        task.getExternalTaskId(),
        task.getUnitNumber(),
        task.getPlannedDurationMinutes(),
        task.getDeadlineAt(),
        operations);
  }

  private StatusWork statusWork(EquipmentMovementTask task) {
    if (task.getTaskBoardTaskId() == null || task.getTaskBoardTaskVersion() == null) {
      throw new LogisticsConflictException("Equipment movement task board reference is missing");
    }
    return new StatusWork(task.getId(), task.getExternalTaskId());
  }

  private ExecuteWork executeWork(
      EquipmentMovementTask task, List<EquipmentMovementTaskLine> taskLines) {
    if (taskLines.isEmpty()
        || taskLines.stream().anyMatch(line -> line.getState() != EquipmentMovementLineState.RESERVED)) {
      throw new LogisticsConflictException("Equipment movement task execution reservations are inconsistent");
    }
    List<LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine> executionLines =
        taskLines.stream()
            .map(
                line ->
                    new LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine(
                        line.getReservationId(),
                        line.getReservationVersion(),
                        line.getId(),
                        line.getTargetWarehouseId(),
                        line.getTargetRentalItemId(),
                        line.getTargetLocationKind().name()))
            .toList();
    return new ExecuteWork(task.getId(), task.assetMovementOwnerId(), executionLines);
  }

  private Optional<Work> cancellingWork(
      EquipmentMovementTask task, List<EquipmentMovementTaskLine> taskLines) {
    if (task.getTaskBoardTaskId() != null && !task.isTaskBoardCancelled()) {
      return Optional.of(
          new CancelBoardTaskWork(
              task.getId(), task.getExternalTaskId(), task.getTaskBoardTaskVersion()));
    }
    EquipmentMovementTaskLine reserved =
        taskLines.stream()
            .filter(line -> line.getState() == EquipmentMovementLineState.RESERVED)
            .findFirst()
            .orElse(null);
    if (reserved != null) {
      return Optional.of(
          new ReleaseWork(
              task.getId(),
              task.assetMovementOwnerId(),
              reserved.getId(),
              reserved.getReservationId(),
              reserved.getReservationVersion()));
    }
    if (taskLines.stream()
        .anyMatch(
            line ->
                line.getState() != EquipmentMovementLineState.RELEASED
                    && line.getState() != EquipmentMovementLineState.PENDING_RESERVATION)) {
      throw new LogisticsConflictException("Equipment movement task cancellation reservations are inconsistent");
    }
    task.finishCancellation();
    tasks.saveAndFlush(task);
    return Optional.empty();
  }

  private static List<LogisticsDependencyGateway.EquipmentMovementOperation> operations(
      List<EquipmentMovementTaskLine> taskLines) {
    List<LogisticsDependencyGateway.EquipmentMovementOperation> operations = new ArrayList<>();
    for (EquipmentMovementTaskLine line : taskLines) {
      String name = line.getEquipmentName();
      if (name == null) {
        throw new LogisticsConflictException("Equipment movement reservation has no canonical equipment title");
      }
      if (line.getSourceLocationKind() == EquipmentMovementLocationKind.STOCK) {
        operations.add(
            new LogisticsDependencyGateway.EquipmentMovementOperation(
                "BRING_TO_CABIN", line.getEquipmentId(), name, line.getQuantity()));
      } else if (line.getTargetLocationKind() == EquipmentMovementLocationKind.STOCK) {
        operations.add(
            new LogisticsDependencyGateway.EquipmentMovementOperation(
                "TAKE_FROM_CABIN", line.getEquipmentId(), name, line.getQuantity()));
      } else {
        operations.add(
            new LogisticsDependencyGateway.EquipmentMovementOperation(
                "TAKE_FROM_CABIN", line.getEquipmentId(), name, line.getQuantity()));
        operations.add(
            new LogisticsDependencyGateway.EquipmentMovementOperation(
                "BRING_TO_CABIN", line.getEquipmentId(), name, line.getQuantity()));
      }
    }
    if (operations.isEmpty() || operations.size() > EquipmentMovementTaskLimits.MAX_WORKER_OPERATIONS) {
      throw new LogisticsConflictException("Equipment movement task has too many worker operations");
    }
    return List.copyOf(operations);
  }

  private static void requireReservation(
      EquipmentMovementTask task,
      EquipmentMovementTaskLine line,
      LogisticsDependencyGateway.EquipmentMovementReservation reservation,
      String expectedState) {
    if (reservation == null
        || !"LOGISTICS_EQUIPMENT_MOVEMENT".equals(reservation.ownerType())
        || !task.assetMovementOwnerId().equals(reservation.movementId())
        || !line.getId().equals(reservation.lineId())
        || !line.getEquipmentId().equals(reservation.equipmentId())
        || !line.getSourceWarehouseId().equals(reservation.sourceWarehouseId())
        || !java.util.Objects.equals(line.getSourceRentalItemId(), reservation.sourceRentalItemId())
        || !line.getSourceLocationKind().name().equals(reservation.sourceLocationKind())
        || line.getQuantity() != reservation.quantity()
        || !expectedState.equals(reservation.state())) {
      throw new LogisticsConflictException("Asset-service returned a mismatched equipment reservation");
    }
    if ("ACTIVE".equals(expectedState)
        && (reservation.reservedUntil() == null
            || reservation.reservedUntil().isAfter(task.getDeadlineAt()))) {
      throw new LogisticsConflictException("Asset-service returned an invalid equipment reservation deadline");
    }
    if (line.getReservationId() != null && !line.getReservationId().equals(reservation.reservationId())) {
      throw new LogisticsConflictException("Asset-service returned a conflicting equipment reservation");
    }
  }

  private static void requireBoardTask(
      EquipmentMovementTask task,
      LogisticsDependencyGateway.EquipmentMovementBoardTask boardTask,
      String expectedStatus) {
    if (boardTask == null
        || !task.getWarehouseId().equals(boardTask.warehouseId())
        || !task.getExternalTaskId().equals(boardTask.externalTaskId())
        || (task.getTaskBoardTaskId() != null && !task.getTaskBoardTaskId().equals(boardTask.taskId()))
        || (expectedStatus != null && !expectedStatus.equals(boardTask.status()))) {
      throw new LogisticsConflictException("Task-board returned a mismatched equipment movement task");
    }
  }

  private EquipmentMovementTask lockedTask(UUID taskId) {
    return tasks
        .findForUpdate(taskId)
        .orElseThrow(LogisticsNotFoundException::new);
  }

  private EquipmentMovementTaskLine requiredLine(UUID taskId, UUID lineId) {
    EquipmentMovementTaskLine line =
        lines
            .findById(lineId)
            .orElseThrow(() -> new LogisticsConflictException("Equipment movement line is not found"));
    if (!taskId.equals(line.getTask().getId())) {
      throw new LogisticsConflictException("Equipment movement line does not belong to the task");
    }
    return line;
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  sealed interface Work
      permits ReserveWork, RegisterWork, StatusWork, ExecuteWork, CancelBoardTaskWork, ReleaseWork {}

  record ReserveWork(
      UUID taskId,
      UUID assetMovementOwnerId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil)
      implements Work {}

  record RegisterWork(
      UUID taskId,
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<LogisticsDependencyGateway.EquipmentMovementOperation> operations)
      implements Work {}

  record StatusWork(UUID taskId, UUID externalTaskId) implements Work {}

  record ExecuteWork(
      UUID taskId,
      UUID assetMovementOwnerId,
      List<LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine> lines)
      implements Work {}

  record CancelBoardTaskWork(UUID taskId, UUID externalTaskId, long expectedTaskVersion)
      implements Work {}

  record ReleaseWork(
      UUID taskId,
      UUID assetMovementOwnerId,
      UUID lineId,
      UUID reservationId,
      long expectedReservationVersion)
      implements Work {}
}
