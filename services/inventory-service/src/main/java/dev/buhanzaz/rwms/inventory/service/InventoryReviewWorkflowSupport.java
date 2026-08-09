package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureLossIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact state and remote-effect dependencies for cabin and furniture review workflow.
 *
 * <p>Independent transactions remain in the technical base so recovered furniture effects keep
 * their original retry isolation.
 */
abstract class InventoryReviewWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected static final Set<String> CAPTURE_STATUSES =
      Set.of(
          "BOOKED",
          "REPAIR",
          "WAITING_REPAIR_CHECK",
          "CAPITAL_REPAIR",
          "AFTER_RENT",
          "SALE",
          "USED_SALE",
          "RESERVED",
          "FREE",
          "WAREHOUSE",
          "OWN_NEEDS");
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
  protected final InventoryFurnitureLossIntentRepository furnitureLosses;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryIdempotencyPort idempotency;
  protected final InventoryFindingValidationService validationService;

  protected InventoryReviewWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryFurnitureLossIntentRepository furnitureLosses,
      InventoryDependencyGateway dependencies,
      InventoryIdempotencyPort idempotency,
      InventoryFindingValidationService validationService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.furnitureReconciliations = furnitureReconciliations;
    this.furnitureLosses = furnitureLosses;
    this.dependencies = dependencies;
    this.idempotency = idempotency;
    this.validationService = validationService;
  }

  protected InventorySession requireScopedSession(
      UUID inventoryId, InventoryAuthorizer.WarehouseScope scope) {
    if (!scope.unrestricted() && scope.warehouseIds().isEmpty()) {
      throw InventoryException.notFound("Inventory session not found");
    }
    return (scope.unrestricted()
            ? sessions.findById(inventoryId)
            : sessions.findByIdAndWarehouseIdIn(inventoryId, scope.warehouseIds()))
        .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
  }

  protected InventorySession requireActive(UUID inventoryId) {
    InventorySession value =
        sessions
            .findById(inventoryId)
            .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
    if (value.getLifecycle() != SessionLifecycle.ACTIVE) {
      throw InventoryException.conflict("Inventory session is not active");
    }
    return value;
  }

  protected InventorySession requireLifecycle(
      InventorySession session, SessionLifecycle expectedLifecycle) {
    if (session.getLifecycle() != expectedLifecycle) {
      throw InventoryException.conflict(
          expectedLifecycle == SessionLifecycle.ACTIVE
              ? "Inventory session is not active"
              : "Inventory session is not completed");
    }
    return session;
  }

  protected void expectRevision(long actual, long expected) {
    if (actual != expected) throw InventoryException.conflict("Inventory revision is stale");
  }

  protected Observation observation(ObservationPresence presence, String value) {
    return new Observation(presence, value == null ? null : read(value));
  }
}
