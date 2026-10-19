package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationApplyRequest;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationApplyResult;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationPreflightRequest;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.InventoryPublicationPreflightResponse;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Public completed-inventory publication boundary.
 *
 * <p>The facade preserves the controller/test seam while delegating read projection and durable
 * publication orchestration to cohesive collaborators. It owns no browser state.
 */
@Service
public class InventoryPublicationReconciliationService {
  private final InventoryPublicationPreflightProjection preflightProjection;
  private final InventoryPublicationApplyUseCases applyUseCases;

  public InventoryPublicationReconciliationService(
      InventoryPublicationPreflightProjection preflightProjection,
      InventoryPublicationApplyUseCases applyUseCases) {
    this.preflightProjection = preflightProjection;
    this.applyUseCases = applyUseCases;
  }

  @Transactional(readOnly = true)
  public InventoryPublicationPreflightResponse preflight(
      InventoryPublicationPreflightRequest request) {
    return preflightProjection.preflight(request);
  }

  public PublicationResult apply(
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      InventoryPublicationApplyRequest request) {
    InventoryPublicationWorkflowResult result =
        applyUseCases.apply(inventoryId, findingId, idempotencyKey, request);
    return new PublicationResult(result.response(), result.replayed());
  }

  public record PublicationResult(InventoryPublicationApplyResult response, boolean replayed) {}
}
