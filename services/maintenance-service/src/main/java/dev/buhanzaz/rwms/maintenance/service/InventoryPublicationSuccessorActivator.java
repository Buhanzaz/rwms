package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSuccessor;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationSuccessorRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Releases local inventory successors only after maintenance owns a proven terminal predecessor
 * fact. The released successor goes through the existing durable QUEUE_REPAIR reconciliation;
 * this class never sends a task-board or logistics command itself.
 */
@Service
public class InventoryPublicationSuccessorActivator {
  private final InventoryPublicationSuccessorRepository successors;
  private final MaintenanceRepairRepository repairs;
  private final InventoryRepairReconciliationWriter repairQueue;

  public InventoryPublicationSuccessorActivator(
      InventoryPublicationSuccessorRepository successors,
      MaintenanceRepairRepository repairs,
      InventoryRepairReconciliationWriter repairQueue) {
    this.successors = successors;
    this.repairs = repairs;
    this.repairQueue = repairQueue;
  }

  /** Ordinary work is eligible only after every task-board stage reported a completion fact. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseAfterTaskBoardCompletion(
      MaintenanceRepair predecessor, UUID completionEventId, OffsetDateTime occurredAt) {
    if (predecessor == null || completionEventId == null) {
      throw new IllegalArgumentException("Inventory successor completion fact is required");
    }
    if (predecessor.getReclassificationState() == RepairReclassificationState.EXTERNAL_CAPITAL) {
      // A local capital handoff is not proof that the external physical work completed.
      return;
    }
    if (predecessor.getExecutionState() != RepairExecutionState.COMPLETED
        || (predecessor.getAcceptanceState() != RepairAcceptanceState.PENDING
            && predecessor.getAcceptanceState() != RepairAcceptanceState.ACCEPTED)) {
      throw new IllegalStateException(
          "Only a task-board completed repair can release an inventory successor");
    }
    release(predecessor, "TASK_BOARD_COMPLETION", completionEventId, occurredAt);
  }

  /**
   * Capital work has no maintenance-visible task completion fact. A recorded acceptance is the
   * first local proof that permits its successor to enter the normal queue orchestration.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void releaseAfterAcceptance(
      MaintenanceRepair predecessor, UUID acceptanceEventId, OffsetDateTime occurredAt) {
    if (predecessor == null || acceptanceEventId == null) {
      throw new IllegalArgumentException("Inventory successor acceptance fact is required");
    }
    if (predecessor.getReclassificationState() != RepairReclassificationState.EXTERNAL_CAPITAL) {
      return;
    }
    if (predecessor.getAcceptanceState() != RepairAcceptanceState.ACCEPTED) {
      throw new IllegalStateException(
          "Only an accepted external-capital repair can release an inventory successor");
    }
    release(predecessor, "REPAIR_ACCEPTANCE", acceptanceEventId, occurredAt);
  }

  private void release(
      MaintenanceRepair predecessor,
      String terminalFact,
      UUID terminalFactEventId,
      OffsetDateTime occurredAt) {
    List<InventoryPublicationSuccessor> waiting =
        successors.findWaitingByPredecessorRepairIdForUpdate(predecessor.getId());
    for (InventoryPublicationSuccessor relation : waiting) {
      MaintenanceRepair successor = repairs.findAllByIdForUpdate(List.of(relation.getSuccessorRepairId()))
          .stream()
          .findFirst()
          .orElseThrow(
              () -> new MaintenanceConflictException(
                  "MAINTENANCE_STATE_CONFLICT",
                  "Inventory successor relation points to a missing repair"));
      if (!predecessor.getWarehouseId().equals(successor.getWarehouseId())
          || !predecessor.getRentalItemId().equals(successor.getRentalItemId())
          || successor.getExecutionState() != RepairExecutionState.DRAFT) {
        throw new MaintenanceConflictException(
            "MAINTENANCE_STATE_CONFLICT",
            "Inventory successor is no longer a local draft for its predecessor asset");
      }
      if (!relation.release(terminalFact, terminalFactEventId, occurredAt)) {
        continue;
      }
      successors.saveAndFlush(relation);
      repairQueue.enqueue(successor.getId(), stableQueueKey(relation));
    }
  }

  private static UUID stableQueueKey(InventoryPublicationSuccessor relation) {
    return UUID.nameUUIDFromBytes(
        ("inventory-publication-successor-queue:"
                + relation.getId().getInventoryId()
                + ":"
                + relation.getId().getFinalPlanVersion()
                + ":"
                + relation.getId().getFindingId())
            .getBytes(StandardCharsets.UTF_8));
  }
}
