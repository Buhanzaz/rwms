package dev.buhanzaz.rwms.maintenance.service;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;

import java.util.UUID;
import org.springframework.stereotype.Component;

/** Delegates normal work publication to the single authoritative inventory outcome workflow. */
@Component
final class InventoryPublicationApplyUseCases {
  private final InventoryAuthoritativeOutcomeService authoritativeOutcomes;

  InventoryPublicationApplyUseCases(InventoryAuthoritativeOutcomeService authoritativeOutcomes) {
    this.authoritativeOutcomes = authoritativeOutcomes;
  }

  InventoryPublicationWorkflowResult apply(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    return authoritativeOutcomes.applyWork(inventoryId, findingId, idempotencyKey, request);
  }
}
