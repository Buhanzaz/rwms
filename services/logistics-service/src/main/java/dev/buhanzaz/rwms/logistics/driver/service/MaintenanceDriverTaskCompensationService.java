package dev.buhanzaz.rwms.logistics.driver.service;

import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationOutcome;
import dev.buhanzaz.rwms.logistics.driver.api.DriverTaskApiModels.MaintenanceDriverTaskCompensationResponse;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Maintenance-only read and compensation boundary for not-yet-started repair movements.
 *
 * <p>Task-board owns the physical-start fence. Logistics calls only its atomic pre-start guard,
 * then releases a reserved repair place with a deterministic task-owned idempotency key before
 * making the local task terminal. An ambiguous remote outcome becomes a durable reconciliation
 * checkpoint; it never causes a broad task cancellation or an unguarded repair-place release.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MaintenanceDriverTaskCompensationService {
  private static final EnumSet<DriverTaskKind> COMPENSABLE_KINDS =
      EnumSet.of(DriverTaskKind.DELIVER_TO_REPAIR, DriverTaskKind.CAPITAL_TO_PRODUCTION);
  private static final String PRE_START_CANCELLATION_REASON =
      "Компенсация незапущенного логистического перемещения";
  private static final String RECONCILIATION_PREFIX = "MAINTENANCE_COMPENSATION_";
  private static final String GUARD_OUTCOME_UNKNOWN = RECONCILIATION_PREFIX + "GUARD_UNKNOWN";
  private static final String GUARD_OUTCOME_FAILED = RECONCILIATION_PREFIX + "GUARD_FAILED";
  private static final String RELEASE_OUTCOME_UNKNOWN = RECONCILIATION_PREFIX + "RELEASE_UNKNOWN";
  private static final String RELEASE_OUTCOME_FAILED = RECONCILIATION_PREFIX + "RELEASE_FAILED";
  private static final String VERSION_CONFLICT = RECONCILIATION_PREFIX + "VERSION_CONFLICT";
  private static final String BOARD_MISMATCH = RECONCILIATION_PREFIX + "BOARD_MISMATCH";
  private static final String RELEASE_MISMATCH = RECONCILIATION_PREFIX + "RELEASE_MISMATCH";
  private static final Set<String> RETRYABLE_RECONCILIATIONS =
      Set.of(GUARD_OUTCOME_UNKNOWN, RELEASE_OUTCOME_UNKNOWN, VERSION_CONFLICT);

  private final DriverLogisticsTaskRepository tasks;
  private final LogisticsDependencyGateway dependencies;
  private final LogisticsTransactionLock transactionLock;

  public MaintenanceDriverTaskCompensationResponse lookup(UUID repairId, DriverTaskKind kind) {
    requireCompensableTarget(repairId, kind);
    return tasks
        .findFirstByRepairIdAndKindOrderByCreatedAtDescIdDesc(repairId, kind)
        .map(task -> snapshot(repairId, kind, task, outcome(task), null))
        .orElseGet(() -> absent(repairId, kind));
  }

  /**
   * Uses task-board's atomic WAITING-only guard for registered work. A client retry may reuse the
   * same Idempotency-Key after a lost response; the downstream repair-place effect always has a
   * stable key derived from the local task, so it cannot be applied twice.
   */
  @Transactional
  public MaintenanceDriverTaskCompensationResponse cancel(
      UUID repairId, DriverTaskKind kind, UUID idempotencyKey) {
    requireCompensableTarget(repairId, kind);
    if (idempotencyKey == null) {
      throw new IllegalArgumentException("Maintenance compensation Idempotency-Key is required");
    }
    transactionLock.acquire("maintenance-driver-compensation:" + repairId + ":" + kind);
    DriverLogisticsTask task =
        tasks.findByRepairIdAndKindForUpdate(repairId, kind).stream().findFirst().orElse(null);
    if (task == null) return absent(repairId, kind);

    if (task.getState() == DriverTaskState.REGISTERING && task.getTaskBoardTaskId() == null) {
      task.cancelBeforeExternalRegistration();
      tasks.saveAndFlush(task);
      return snapshot(repairId, kind, task, MaintenanceDriverTaskCompensationOutcome.CANCELLED, null);
    }

    if (task.getState() != DriverTaskState.SCHEDULED
        && !isRetryableCompensationReconciliation(task)) {
      return snapshot(repairId, kind, task, outcome(task), null);
    }
    if (!hasTaskBoardIdentity(task)) {
      return requireReconciliation(repairId, kind, task, null, BOARD_MISMATCH);
    }
    return invokePreStartGuard(repairId, kind, task, task.getTaskBoardTaskVersion(), true);
  }

  private MaintenanceDriverTaskCompensationResponse invokePreStartGuard(
      UUID repairId,
      DriverTaskKind kind,
      DriverLogisticsTask task,
      long expectedTaskVersion,
      boolean permitVersionReconciliation) {
    LogisticsDependencyGateway.DriverTaskPreStartCancellation guard;
    try {
      guard =
          dependencies.cancelDriverTaskIfPreStart(
              task.getExternalTaskId(), expectedTaskVersion, PRE_START_CANCELLATION_REASON);
    } catch (LogisticsDependencyException exception) {
      return requireReconciliation(
          repairId,
          kind,
          task,
          null,
          exception.kind() == LogisticsDependencyException.FailureKind.TRANSIENT
              ? GUARD_OUTCOME_UNKNOWN
              : GUARD_OUTCOME_FAILED);
    }
    TaskBoardSnapshot guardSnapshot = TaskBoardSnapshot.from(guard);
    if (!matches(task, guard)) {
      return requireReconciliation(repairId, kind, task, guardSnapshot, BOARD_MISMATCH);
    }
    return switch (guard.outcome()) {
      case CANCELLED, ALREADY_CANCELLED ->
          confirmCancellationAndReleaseReservation(repairId, kind, task, guardSnapshot);
      case STARTED ->
          snapshot(
              repairId,
              kind,
              task,
              MaintenanceDriverTaskCompensationOutcome.STARTED,
              guardSnapshot);
      case VERSION_CONFLICT ->
          permitVersionReconciliation
              ? reconcileVersionConflict(repairId, kind, task, guardSnapshot)
              : requireReconciliation(repairId, kind, task, guardSnapshot, VERSION_CONFLICT);
    };
  }

  private MaintenanceDriverTaskCompensationResponse reconcileVersionConflict(
      UUID repairId,
      DriverTaskKind kind,
      DriverLogisticsTask task,
      TaskBoardSnapshot conflictSnapshot) {
    LogisticsDependencyGateway.DriverBoardTask current;
    try {
      current = dependencies.readDriverTask(task.getExternalTaskId());
    } catch (LogisticsDependencyException exception) {
      return requireReconciliation(
          repairId,
          kind,
          task,
          conflictSnapshot,
          exception.kind() == LogisticsDependencyException.FailureKind.TRANSIENT
              ? GUARD_OUTCOME_UNKNOWN
              : GUARD_OUTCOME_FAILED);
    }
    TaskBoardSnapshot boardSnapshot = TaskBoardSnapshot.from(current);
    if (!matches(task, current)) {
      return requireReconciliation(repairId, kind, task, boardSnapshot, BOARD_MISMATCH);
    }
    if (isCompleted(current)) {
      return snapshot(
          repairId,
          kind,
          task,
          MaintenanceDriverTaskCompensationOutcome.COMPLETED,
          boardSnapshot);
    }
    if (isStarted(current)) {
      return snapshot(
          repairId,
          kind,
          task,
          MaintenanceDriverTaskCompensationOutcome.STARTED,
          boardSnapshot);
    }
    if (!isPreStartWaiting(current)) {
      return requireReconciliation(repairId, kind, task, boardSnapshot, VERSION_CONFLICT);
    }
    // The fresh task-board version is used for exactly one retry. A second conflict is a durable
    // reconciliation case rather than an unbounded client-side compare-and-swap loop.
    return invokePreStartGuard(repairId, kind, task, current.taskVersion(), false);
  }

  private MaintenanceDriverTaskCompensationResponse confirmCancellationAndReleaseReservation(
      UUID repairId,
      DriverTaskKind kind,
      DriverLogisticsTask task,
      TaskBoardSnapshot guardSnapshot) {
    if (task.getKind() == DriverTaskKind.DELIVER_TO_REPAIR && hasRepairPlaceReservation(task)) {
      MaintenanceDriverTaskCompensationResponse releaseFailure =
          releaseReservedInboundPlace(repairId, kind, task, guardSnapshot);
      if (releaseFailure != null) return releaseFailure;
    }
    task.cancelAfterPreStartCancellation();
    tasks.saveAndFlush(task);
    return snapshot(
        repairId,
        kind,
        task,
        MaintenanceDriverTaskCompensationOutcome.CANCELLED,
        guardSnapshot);
  }

  /**
   * A repair delivery may free only its own still-RESERVED inbound allocation. An exact RELEASED
   * allocation is the durable proof that a prior release request succeeded but its response was
   * lost. A capital movement has not left the repair place, so its allocation never reaches this
   * method.
   */
  private MaintenanceDriverTaskCompensationResponse releaseReservedInboundPlace(
      UUID repairId,
      DriverTaskKind kind,
      DriverLogisticsTask task,
      TaskBoardSnapshot guardSnapshot) {
    if (task.getRepairPlaceAllocationId() == null
        || task.getRepairPlaceAllocationVersion() == null
        || task.getRepairId() == null
        || task.getId() == null) {
      return requireReconciliation(repairId, kind, task, guardSnapshot, RELEASE_MISMATCH);
    }
    LogisticsDependencyGateway.RepairPlaceProjection places;
    try {
      places = dependencies.readRepairPlaces(task.getWarehouseId());
    } catch (LogisticsDependencyException exception) {
      return requireReconciliation(
          repairId,
          kind,
          task,
          guardSnapshot,
          exception.kind() == LogisticsDependencyException.FailureKind.TRANSIENT
              ? RELEASE_OUTCOME_UNKNOWN
              : RELEASE_OUTCOME_FAILED);
    }
    if (places == null
        || !task.getWarehouseId().equals(places.warehouseId())
        || places.allocations() == null) {
      return requireReconciliation(repairId, kind, task, guardSnapshot, RELEASE_MISMATCH);
    }
    LogisticsDependencyGateway.RepairPlaceAllocation allocation =
        places.allocations().stream()
            .filter(value -> task.getRepairPlaceAllocationId().equals(value.id()))
            .findFirst()
            .orElse(null);
    if (matchesReleasedReservation(task, allocation)) {
      return null;
    }
    if (!matchesReservedReservation(task, allocation)) {
      return requireReconciliation(repairId, kind, task, guardSnapshot, RELEASE_MISMATCH);
    }
    LogisticsDependencyGateway.RepairPlaceAllocation released;
    try {
      released =
          dependencies.transitionRepairPlace(
              compensationReleaseKey(task.getId()),
              task.getWarehouseId(),
              task.getRepairId(),
              allocation.version(),
              "release");
    } catch (LogisticsDependencyException exception) {
      return requireReconciliation(
          repairId,
          kind,
          task,
          guardSnapshot,
          exception.kind() == LogisticsDependencyException.FailureKind.TRANSIENT
              ? RELEASE_OUTCOME_UNKNOWN
              : RELEASE_OUTCOME_FAILED);
    }
    if (!matchesReleasedReservation(task, released)) {
      return requireReconciliation(repairId, kind, task, guardSnapshot, RELEASE_MISMATCH);
    }
    return null;
  }

  private MaintenanceDriverTaskCompensationResponse requireReconciliation(
      UUID repairId,
      DriverTaskKind kind,
      DriverLogisticsTask task,
      TaskBoardSnapshot board,
      String code) {
    if (task.getState() != DriverTaskState.RECONCILIATION_REQUIRED) {
      task.requireReconciliation(code);
      tasks.saveAndFlush(task);
    }
    return snapshot(
        repairId,
        kind,
        task,
        MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED,
        board);
  }

  private static boolean hasTaskBoardIdentity(DriverLogisticsTask task) {
    return task.getExternalTaskId() != null
        && task.getTaskBoardTaskId() != null
        && task.getTaskBoardTaskVersion() != null
        && task.getTaskBoardTaskVersion() >= 0;
  }

  private static boolean isRetryableCompensationReconciliation(DriverLogisticsTask task) {
    return task.getState() == DriverTaskState.RECONCILIATION_REQUIRED
        && RETRYABLE_RECONCILIATIONS.contains(task.getFailureCode());
  }

  private static boolean hasRepairPlaceReservation(DriverLogisticsTask task) {
    return task.getRepairPlaceAllocationId() != null
        || task.getRepairPlaceAllocationVersion() != null;
  }

  private static boolean matches(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverTaskPreStartCancellation guard) {
    return guard != null
        && guard.outcome() != null
        && task.getTaskBoardTaskId().equals(guard.taskId())
        && task.getExternalTaskId().equals(guard.externalTaskId())
        && guard.taskVersion() >= task.getTaskBoardTaskVersion()
        && guard.status() != null;
  }

  private static boolean matches(
      DriverLogisticsTask task, LogisticsDependencyGateway.DriverBoardTask board) {
    return board != null
        && task.getWarehouseId().equals(board.warehouseId())
        && task.getTaskBoardTaskId().equals(board.taskId())
        && task.getExternalTaskId().equals(board.externalTaskId())
        && board.taskVersion() >= 0
        && board.entryId() != null
        && board.entryVersion() >= 0
        && board.entryStatus() != null
        && board.status() != null
        && board.lane() != null;
  }

  private static boolean matchesReleasedReservation(
      DriverLogisticsTask task, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    return allocation != null
        && task.getRepairPlaceAllocationId().equals(allocation.id())
        && allocation.version() >= task.getRepairPlaceAllocationVersion()
        && task.getWarehouseId().equals(allocation.warehouseId())
        && task.getRepairId().equals(allocation.repairId())
        && task.getCabinId().equals(allocation.rentalItemId())
        && "RELEASED".equals(allocation.state());
  }

  private static boolean matchesReservedReservation(
      DriverLogisticsTask task, LogisticsDependencyGateway.RepairPlaceAllocation allocation) {
    return allocation != null
        && task.getRepairPlaceAllocationId().equals(allocation.id())
        && task.getRepairPlaceAllocationVersion().equals(allocation.version())
        && task.getWarehouseId().equals(allocation.warehouseId())
        && task.getRepairId().equals(allocation.repairId())
        && task.getCabinId().equals(allocation.rentalItemId())
        && "RESERVED".equals(allocation.state());
  }

  private static boolean isCompleted(LogisticsDependencyGateway.DriverBoardTask board) {
    return "DONE".equals(board.status()) || "DONE".equals(board.entryStatus());
  }

  private static boolean isStarted(LogisticsDependencyGateway.DriverBoardTask board) {
    return "ACTIVE".equals(board.status())
        && ("IN_PROGRESS".equals(board.entryStatus()) || "PAUSED".equals(board.entryStatus()));
  }

  private static boolean isPreStartWaiting(LogisticsDependencyGateway.DriverBoardTask board) {
    return "ACTIVE".equals(board.status()) && "WAITING".equals(board.entryStatus());
  }

  private static UUID compensationReleaseKey(UUID taskId) {
    return UUID.nameUUIDFromBytes(
        ("driver-task:maintenance-compensation-release:" + taskId)
            .getBytes(StandardCharsets.UTF_8));
  }

  private static MaintenanceDriverTaskCompensationOutcome outcome(DriverLogisticsTask task) {
    return switch (task.getState()) {
      case REGISTERING, SCHEDULED -> MaintenanceDriverTaskCompensationOutcome.PENDING;
      case CANCELLED -> MaintenanceDriverTaskCompensationOutcome.CANCELLED;
      case CURRENT, FINALIZING -> MaintenanceDriverTaskCompensationOutcome.STARTED;
      case COMPLETED -> MaintenanceDriverTaskCompensationOutcome.COMPLETED;
      case RECONCILIATION_REQUIRED ->
          MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED;
    };
  }

  private static MaintenanceDriverTaskCompensationResponse absent(
      UUID repairId, DriverTaskKind kind) {
    return new MaintenanceDriverTaskCompensationResponse(
        repairId,
        kind,
        MaintenanceDriverTaskCompensationOutcome.ABSENT,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private static MaintenanceDriverTaskCompensationResponse snapshot(
      UUID repairId,
      DriverTaskKind kind,
      DriverLogisticsTask task,
      MaintenanceDriverTaskCompensationOutcome outcome,
      TaskBoardSnapshot board) {
    return new MaintenanceDriverTaskCompensationResponse(
        repairId,
        kind,
        outcome,
        task.getId(),
        task.getVersion(),
        task.getState(),
        task.getExternalTaskId(),
        board == null ? task.getTaskBoardTaskId() : board.taskId(),
        board == null ? task.getTaskBoardTaskVersion() : board.taskVersion(),
        board == null ? task.getTaskBoardEntryId() : board.entryId(),
        board == null ? null : board.entryVersion(),
        board == null ? task.getTaskBoardEntryStatus() : board.entryStatus(),
        board == null ? null : board.status(),
        board == null ? null : board.lane(),
        board == null ? task.getTaskBoardDoneAt() : board.doneAt(),
        task.getRepairPlaceAllocationId(),
        task.getRepairPlaceAllocationVersion());
  }

  private static void requireCompensableTarget(UUID repairId, DriverTaskKind kind) {
    if (repairId == null || kind == null || !COMPENSABLE_KINDS.contains(kind)) {
      throw new IllegalArgumentException(
          "Maintenance compensation supports only repair delivery or capital movement");
    }
  }

  private record TaskBoardSnapshot(
      UUID taskId,
      Long taskVersion,
      UUID entryId,
      Long entryVersion,
      String entryStatus,
      String status,
      String lane,
      OffsetDateTime doneAt) {
    private static TaskBoardSnapshot from(LogisticsDependencyGateway.DriverBoardTask value) {
      if (value == null) return null;
      return new TaskBoardSnapshot(
          value.taskId(),
          value.taskVersion(),
          value.entryId(),
          value.entryVersion(),
          value.entryStatus(),
          value.status(),
          value.lane(),
          value.doneAt());
    }

    private static TaskBoardSnapshot from(
        LogisticsDependencyGateway.DriverTaskPreStartCancellation value) {
      if (value == null) return null;
      return new TaskBoardSnapshot(
          value.taskId(), value.taskVersion(), null, null, null, value.status(), null, null);
    }
  }
}
