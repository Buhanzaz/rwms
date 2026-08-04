package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.repository.InventoryPublicationPrestartReplacementRepository;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Prevents a queued-work reconciliation from creating a new external effect mid-replacement. */
@Service
public class InventoryPublicationPrestartReplacementGuard {
  private final InventoryPublicationPrestartReplacementRepository replacements;

  public InventoryPublicationPrestartReplacementGuard(
      InventoryPublicationPrestartReplacementRepository replacements) {
    this.replacements = replacements;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean blocksRepairExecution(UUID repairId) {
    if (repairId == null) throw new IllegalArgumentException("Repair identity is required");
    return replacements.existsActiveForPredecessorRepairId(repairId);
  }

  /**
   * A cancellation event emitted by task-board's atomic pre-start command is proof for the
   * durable V31 saga, not an invitation to run the generic cancellation transition while that
   * saga still owns the predecessor. The final saga transaction mirrors the cancellation locally.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean ownsTaskCancellation(UUID repairId, UUID externalTaskId) {
    if (repairId == null || externalTaskId == null) {
      throw new IllegalArgumentException("Repair task cancellation identity is required");
    }
    return replacements
        .findActiveForPredecessorRepairIdForUpdate(repairId)
        .map(
            intent ->
                intent.isTaskGuardRequired()
                    && externalTaskId.equals(intent.getTaskExternalId()))
        .orElse(false);
  }
}
