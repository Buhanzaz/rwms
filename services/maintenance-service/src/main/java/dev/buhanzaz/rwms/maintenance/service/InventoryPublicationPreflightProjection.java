package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Component;

/**
 * Read-only projection of eligible publication targets and their plan summaries for a completed
 * inventory finding. It never changes maintenance state.
 */
@Component
final class InventoryPublicationPreflightProjection {
  private final RentalItemFactProjectionRepository rentalItems;
  private final InventoryPublicationPlanValidation planValidation;

  InventoryPublicationPreflightProjection(
      RentalItemFactProjectionRepository rentalItems,
      InventoryPublicationPlanValidation planValidation) {
    this.rentalItems = rentalItems;
    this.planValidation = planValidation;
  }

  InventoryPublicationPreflightResponse preflight(InventoryPublicationPreflightRequest request) {
    requireUniqueFindings(request.findings());
    List<InventoryPublicationPreflightFinding> findings = new ArrayList<>();
    for (InventoryPublicationFindingInput finding : request.findings()) {
      planValidation.validatePublication(request.warehouseId(), finding);
      // Reserved inventory sources are materialized only after completion. An absent event
      // projection cannot block planning; apply still requires the authoritative asset fence.
      rentalItems.findById(finding.assetId()).ifPresent(
          asset -> InventoryPublicationAssetFence.requireFrozen(request.warehouseId(), finding, asset));
      findings.add(
          new InventoryPublicationPreflightFinding(
              finding.findingId(),
              InventoryPublicationTargetKind.REPAIR,
              List.of()));
    }
    return new InventoryPublicationPreflightResponse(
        request.inventoryId(),
        request.finalPlanVersion(),
        request.finalPlanSha256(),
        List.copyOf(findings));
  }

  private static void requireUniqueFindings(List<InventoryPublicationFindingInput> findings) {
    Set<UUID> ids = new LinkedHashSet<>();
    for (InventoryPublicationFindingInput finding : findings) {
      if (finding == null || finding.findingId() == null || !ids.add(finding.findingId())) {
        throw InventoryPublicationPlanValidation.invalid(
            "Inventory publication finding IDs must be unique");
      }
    }
  }
}
