package dev.buhanzaz.rwms.logistics.inventory.service;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReference;
import dev.buhanzaz.rwms.logistics.domain.LogisticsTaskReferenceState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import dev.buhanzaz.rwms.logistics.equipment.repository.EquipmentMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.DriverBoardTask;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.LogisticsOwnerType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OperationLease;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeReceipt;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskAction;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskActionState;
import dev.buhanzaz.rwms.logistics.inventory.domain.InventoryOutcomeTaskTargetType;
import dev.buhanzaz.rwms.logistics.inventory.repository.InventoryOutcomeReceiptRepository;
import dev.buhanzaz.rwms.logistics.inventory.repository.InventoryOutcomeTaskActionRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTaskReferenceRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * Owns short local transactions around each recoverable action. Remote task-board, asset and
 * maintenance calls are made only after {@link #begin(UUID)} has returned and its transaction has
 * ended.
 */
@Component
@RequiredArgsConstructor
public class InventoryOutcomeTaskStore {
  private static final UUID INVENTORY_ACTOR =
      UUID.nameUUIDFromBytes("rwms:inventory-service".getBytes(StandardCharsets.UTF_8));

  private final InventoryOutcomeTaskActionRepository actions;
  private final InventoryOutcomeReceiptRepository receipts;
  private final DriverLogisticsTaskRepository driverTasks;
  private final LogisticsTaskReferenceRepository documentTasks;
  private final EquipmentMovementTaskRepository equipmentTasks;
  private final LogisticsGuardRepository guards;

  public List<UUID> pendingActionIds(UUID receiptId) {
    return actions
        .findAllByReceiptIdAndStateOrderByTargetTypeAscTargetIdAsc(
            receiptId, InventoryOutcomeTaskActionState.PENDING)
        .stream()
        .map(InventoryOutcomeTaskAction::getId)
        .toList();
  }

  /** Returns immutable remote work or completes a purely local action in this transaction. */
  @Transactional
  public RemoteWork begin(UUID actionId) {
    InventoryOutcomeTaskAction action =
        actions
            .findForUpdate(actionId)
            .orElseThrow(() -> new LogisticsConflictException("Inventory task action is missing"));
    if (action.getState() != InventoryOutcomeTaskActionState.PENDING) return null;
    InventoryOutcomeReceipt receipt =
        receipts
            .findById(action.getReceiptId())
            .orElseThrow(() -> new LogisticsConflictException("Inventory outcome receipt is missing"));
    return switch (action.getTargetType()) {
      case DRIVER_TASK -> beginDriver(action, receipt);
      case DOCUMENT_TASK -> beginDocumentTask(action, receipt);
      case EQUIPMENT_TASK -> {
        beginEquipment(action, receipt);
        yield null;
      }
      case GUARD_LEASE -> beginGuardLease(action, receipt);
    };
  }

  /** Applies a remote DONE observation while retaining completed work as history. */
  @Transactional
  public void preserveCompleted(UUID actionId, DriverBoardTask board) {
    InventoryOutcomeTaskAction action = requiredPending(actionId);
    if (action == null) return;
    switch (action.getTargetType()) {
      case DRIVER_TASK -> {
        DriverLogisticsTask task =
            driverTasks
                .findForUpdate(action.getTargetId())
                .orElseThrow(() -> new LogisticsConflictException("Driver task is missing"));
        validate(task, board);
        if (task.getState() != DriverTaskState.COMPLETED) {
          task.observeBoardTask(
              board.taskId(),
              board.taskVersion(),
              board.entryId(),
              board.entryStatus(),
              board.scheduledDate(),
              board.lane(),
              board.status(),
              board.doneAt());
          driverTasks.saveAndFlush(task);
        }
      }
      case DOCUMENT_TASK -> {
        LogisticsTaskReference task =
            documentTasks
                .findForUpdate(action.getTargetId())
                .orElseThrow(() -> new LogisticsConflictException("Document task is missing"));
        validate(task, board);
        if (task.getTaskState() != LogisticsTaskReferenceState.DONE) {
          task.preserveCompletedForInventory(board.taskVersion(), board.doneAt());
        }
      }
      case EQUIPMENT_TASK, GUARD_LEASE ->
          throw new IllegalStateException("Local inventory action has no driver snapshot");
    }
    action.finish(InventoryOutcomeTaskActionState.PRESERVED);
  }

  /** Records a task-board-confirmed cancellation and clears the local task checkpoint. */
  @Transactional
  public void confirmCancelled(UUID actionId, DriverBoardTask board) {
    InventoryOutcomeTaskAction action = requiredPending(actionId);
    if (action == null) return;
    InventoryOutcomeReceipt receipt =
        receipts
            .findById(action.getReceiptId())
            .orElseThrow(() -> new LogisticsConflictException("Inventory outcome receipt is missing"));
    switch (action.getTargetType()) {
      case DRIVER_TASK -> {
        DriverLogisticsTask task =
            driverTasks
                .findForUpdate(action.getTargetId())
                .orElseThrow(() -> new LogisticsConflictException("Driver task is missing"));
        validate(task, board);
        if (task.getState() == DriverTaskState.COMPLETED) {
          action.finish(InventoryOutcomeTaskActionState.PRESERVED);
          return;
        }
        if (task.getState() != DriverTaskState.CANCELLED) {
          task.cancelForCompletedInventory(receipt.getInventoryId(), board.taskVersion());
          driverTasks.saveAndFlush(task);
        }
      }
      case DOCUMENT_TASK -> {
        LogisticsTaskReference task =
            documentTasks
                .findForUpdate(action.getTargetId())
                .orElseThrow(() -> new LogisticsConflictException("Document task is missing"));
        validate(task, board);
        if (task.getTaskState() == LogisticsTaskReferenceState.DONE) {
          action.finish(InventoryOutcomeTaskActionState.PRESERVED);
          return;
        }
        if (task.getTaskState() != LogisticsTaskReferenceState.CANCELLED) {
          task.cancelForCompletedInventory(board.taskVersion());
        }
      }
      case EQUIPMENT_TASK, GUARD_LEASE ->
          throw new IllegalStateException("Local inventory action has no driver snapshot");
    }
    action.finish(InventoryOutcomeTaskActionState.CANCELLED);
  }

  private RemoteTaskWork beginDriver(
      InventoryOutcomeTaskAction action, InventoryOutcomeReceipt receipt) {
    DriverLogisticsTask task =
        driverTasks
            .findForUpdate(action.getTargetId())
            .orElseThrow(() -> new LogisticsConflictException("Driver task is missing"));
    if (task.getState() == DriverTaskState.COMPLETED) {
      action.finish(InventoryOutcomeTaskActionState.PRESERVED);
      return null;
    }
    if (task.getState() == DriverTaskState.CANCELLED) {
      action.finish(
          receipt.getInventoryId().equals(task.getInventoryCancelledBy())
              ? InventoryOutcomeTaskActionState.CANCELLED
              : InventoryOutcomeTaskActionState.PRESERVED);
      return null;
    }
    if (task.getTaskBoardTaskId() == null) {
      task.cancelForCompletedInventory(receipt.getInventoryId(), null);
      driverTasks.saveAndFlush(task);
      action.finish(InventoryOutcomeTaskActionState.CANCELLED);
      return null;
    }
    return new RemoteTaskWork(
        action.getId(),
        action.getTargetType(),
        task.getId(),
        task.getWarehouseId(),
        task.getExternalTaskId(),
        task.getTaskBoardTaskId(),
        task.getKind(),
        task.getRepairId(),
        task.getCabinId(),
        task.getRepairPlaceAllocationId(),
        task.getRepairPlaceAllocationVersion());
  }

  private RemoteTaskWork beginDocumentTask(
      InventoryOutcomeTaskAction action, InventoryOutcomeReceipt receipt) {
    LogisticsTaskReference task =
        documentTasks
            .findForUpdate(action.getTargetId())
            .orElseThrow(() -> new LogisticsConflictException("Document task is missing"));
    if (task.getTaskState() == LogisticsTaskReferenceState.DONE) {
      action.finish(InventoryOutcomeTaskActionState.PRESERVED);
      return null;
    }
    if (task.getTaskState() == LogisticsTaskReferenceState.CANCELLED) {
      action.finish(InventoryOutcomeTaskActionState.PRESERVED);
      return null;
    }
    if (task.getTaskId() == null) {
      task.cancelForCompletedInventory(task.getTaskVersion() == null ? 0 : task.getTaskVersion());
      action.finish(InventoryOutcomeTaskActionState.CANCELLED);
      return null;
    }
    return new RemoteTaskWork(
        action.getId(),
        action.getTargetType(),
        task.getId(),
        task.getWarehouseId(),
        task.getExternalTaskId(),
        task.getTaskId(),
        null,
        null,
        null,
        null,
        null);
  }

  private void beginEquipment(
      InventoryOutcomeTaskAction action, InventoryOutcomeReceipt receipt) {
    EquipmentMovementTask task =
        equipmentTasks
            .findForUpdate(action.getTargetId())
            .orElseThrow(() -> new LogisticsConflictException("Equipment movement task is missing"));
    switch (task.getState()) {
      case COMPLETED, CANCELLED, EXPIRED, CONFLICT ->
          action.finish(InventoryOutcomeTaskActionState.PRESERVED);
      case CANCELLING -> action.finish(InventoryOutcomeTaskActionState.DURABLE_CANCELLING);
      case RESERVING, REGISTERING_TASK, AWAITING_WORKER -> {
        UUID key =
            UUID.nameUUIDFromBytes(
                ("inventory-outcome:equipment-cancel:" + receipt.getInventoryId() + ":" + task.getId())
                    .getBytes(StandardCharsets.UTF_8));
        String checksum =
            java.util.HexFormat.of()
                .formatHex(
                    digest(
                        receipt.getInventoryId()
                            + "\u001f"
                            + task.getId()
                            + "\u001f"
                            + task.getVersion()));
        task.requestCancellation(
            INVENTORY_ACTOR,
            key,
            checksum,
            EquipmentMovementTaskState.CANCELLED,
            "SUPERSEDED_BY_COMPLETED_INVENTORY");
        equipmentTasks.saveAndFlush(task);
        action.finish(InventoryOutcomeTaskActionState.DURABLE_CANCELLING);
      }
      case EXECUTING, RECONCILIATION_REQUIRED ->
          throw new LogisticsConflictException(
              "Equipment movement cannot be safely superseded in state " + task.getState());
    }
  }

  private GuardLeaseReleaseWork beginGuardLease(
      InventoryOutcomeTaskAction action, InventoryOutcomeReceipt receipt) {
    LogisticsGuard guard =
        guards
            .findForUpdate(action.getTargetId())
            .orElseThrow(() -> new LogisticsConflictException("Logistics guard is missing"));
    if (!receipt.getInventoryId().equals(guard.getInventorySupersededBy())) {
      action.finish(InventoryOutcomeTaskActionState.PRESERVED);
      return null;
    }
    if (guard.getGuardState() == LogisticsGuardState.RELEASED) {
      action.finish(InventoryOutcomeTaskActionState.RELEASED);
      return null;
    }
    if (guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getLeaseVersion() < 0
        || guard.getFenceToken() == null
        || guard.getFenceToken() < 1) {
      throw new LogisticsConflictException(
          "Inventory-displaced guard has no releasable asset lease capability");
    }
    return new GuardLeaseReleaseWork(
        action.getId(),
        guard.getId(),
        UUID.nameUUIDFromBytes(
            ("inventory-outcome:guard-release:"
                    + receipt.getInventoryId()
                    + ":"
                    + guard.getId())
                .getBytes(StandardCharsets.UTF_8)),
        guard.getLeaseId(),
        guard.getLeaseVersion(),
        guard.getFenceToken(),
        guard.getAssetId(),
        ownerType(guard.getDocument().getDocumentType()),
        guard.getDocument().getId(),
        guard.getLine().getId());
  }

  /** Closes the local guard only after asset-service confirmed the exact terminal capability. */
  @Transactional
  public void confirmLeaseReleased(UUID actionId, OperationLease lease) {
    InventoryOutcomeTaskAction action = requiredPending(actionId);
    if (action == null) return;
    if (action.getTargetType() != InventoryOutcomeTaskTargetType.GUARD_LEASE) {
      throw new IllegalStateException("Inventory action is not an asset lease release");
    }
    InventoryOutcomeReceipt receipt =
        receipts
            .findById(action.getReceiptId())
            .orElseThrow(() -> new LogisticsConflictException("Inventory outcome receipt is missing"));
    LogisticsGuard guard =
        guards
            .findForUpdate(action.getTargetId())
            .orElseThrow(() -> new LogisticsConflictException("Logistics guard is missing"));
    if (!receipt.getInventoryId().equals(guard.getInventorySupersededBy())) {
      action.finish(InventoryOutcomeTaskActionState.PRESERVED);
      return;
    }
    validate(guard, lease);
    guard.confirmInventoryLeaseTerminal(receipt.getInventoryId(), lease.version());
    guards.saveAndFlush(guard);
    action.finish(InventoryOutcomeTaskActionState.RELEASED);
  }

  private InventoryOutcomeTaskAction requiredPending(UUID actionId) {
    InventoryOutcomeTaskAction action =
        actions
            .findForUpdate(actionId)
            .orElseThrow(() -> new LogisticsConflictException("Inventory task action is missing"));
    return action.getState() == InventoryOutcomeTaskActionState.PENDING ? action : null;
  }

  private static void validate(DriverLogisticsTask task, DriverBoardTask board) {
    if (board == null
        || !task.getWarehouseId().equals(board.warehouseId())
        || !task.getExternalTaskId().equals(board.externalTaskId())
        || !task.getTaskBoardTaskId().equals(board.taskId())
        || board.taskVersion() < 0
        || board.entryId() == null
        || board.entryVersion() < 0
        || board.entryStatus() == null
        || board.status() == null) {
      throw new LogisticsConflictException("Task-board returned a mismatched driver task");
    }
  }

  private static void validate(LogisticsTaskReference task, DriverBoardTask board) {
    if (board == null
        || !task.getWarehouseId().equals(board.warehouseId())
        || !task.getExternalTaskId().equals(board.externalTaskId())
        || !task.getTaskId().equals(board.taskId())
        || board.taskVersion() < 0
        || board.status() == null) {
      throw new LogisticsConflictException("Task-board returned a mismatched document task");
    }
  }

  private static void validate(LogisticsGuard guard, OperationLease lease) {
    if (lease == null
        || guard.getLeaseId() == null
        || guard.getLeaseVersion() == null
        || guard.getFenceToken() == null
        || !guard.getLeaseId().equals(lease.leaseId())
        || !guard.getAssetId().equals(lease.rentalItemId())
        || lease.version() < guard.getLeaseVersion()
        || guard.getFenceToken() != lease.fencingToken()
        || (!"RELEASED".equals(lease.state()) && !"EXPIRED".equals(lease.state()))
        || lease.expiresAt() == null) {
      throw new LogisticsConflictException(
          "Asset-service returned a mismatched terminal logistics lease");
    }
  }

  private static LogisticsOwnerType ownerType(LogisticsDocumentType documentType) {
    return switch (documentType) {
      case RETURN -> LogisticsOwnerType.LOGISTICS_RETURN;
      case SHIPMENT -> LogisticsOwnerType.LOGISTICS_SHIPMENT;
      case TRANSFER -> LogisticsOwnerType.LOGISTICS_TRANSFER;
    };
  }

  private static byte[] digest(String value) {
    try {
      return java.security.MessageDigest.getInstance("SHA-256")
          .digest(value.getBytes(StandardCharsets.UTF_8));
    } catch (java.security.NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  /** Marker for immutable dependency work that must execute after the local transaction. */
  public sealed interface RemoteWork permits RemoteTaskWork, GuardLeaseReleaseWork {}

  /** Immutable task identity and optional repair-place checkpoint used outside a transaction. */
  public record RemoteTaskWork(
      UUID actionId,
      InventoryOutcomeTaskTargetType targetType,
      UUID localTaskId,
      UUID warehouseId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      DriverTaskKind driverTaskKind,
      UUID repairId,
      UUID cabinId,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) implements RemoteWork {}

  /** Exact logistics lease capability and typed document-line owner used for durable release. */
  public record GuardLeaseReleaseWork(
      UUID actionId,
      UUID guardId,
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      UUID assetId,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId) implements RemoteWork {}
}
