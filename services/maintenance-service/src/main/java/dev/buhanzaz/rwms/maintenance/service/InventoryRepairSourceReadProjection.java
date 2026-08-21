package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventorySourceReference;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeReceiptRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeReceiptRepository.AuthoritativeRepairSourceEvidence;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeRepository;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;

/**
 * Resolves authoritative completed-inventory evidence for repair reads without mutating outcome
 * coordinators or their permanent receipts.
 */
@Service
final class InventoryRepairSourceReadProjection {
  private final InventoryAuthoritativeOutcomeReceiptRepository receipts;
  private final InventoryAuthoritativeOutcomeRepository outcomes;

  InventoryRepairSourceReadProjection(
      InventoryAuthoritativeOutcomeReceiptRepository receipts,
      InventoryAuthoritativeOutcomeRepository outcomes) {
    this.receipts = receipts;
    this.outcomes = outcomes;
  }

  /**
   * Returns the immutable source bound to {@code repairId}, preferring the newest completed
   * receipt generation over the newest applied coordinator target.
   */
  Optional<InventorySourceReference> findAuthoritativeSource(UUID repairId) {
    return receipts
        .findNewestCompletedSourceByRepairId(repairId)
        .map(InventoryRepairSourceReadProjection::reference)
        .or(
            () ->
                outcomes
                    .findNewestAppliedByTargetRepairId(repairId)
                    .map(InventoryRepairSourceReadProjection::reference));
  }

  private static InventorySourceReference reference(AuthoritativeRepairSourceEvidence source) {
    return new InventorySourceReference(
        source.getInventoryId(),
        source.getFindingId(),
        source.getFindingRevision(),
        source.getFinalPlanSha256(),
        source.getRequestSha256());
  }

  private static InventorySourceReference reference(InventoryAuthoritativeOutcome source) {
    return new InventorySourceReference(
        source.getId().getInventoryId(),
        source.getId().getFindingId(),
        source.getFindingRevision(),
        source.getFinalPlanSha256(),
        source.getRequestSha256());
  }
}
