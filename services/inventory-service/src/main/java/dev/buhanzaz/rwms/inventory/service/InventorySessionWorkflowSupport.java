package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FrozenStatistics;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exact dependency surface for starting inventory sessions and retrying capture releases.
 *
 * <p>The concrete session use case owns the reserve/capture workflow; this support only exposes
 * the local stores and collaborators that take part in that workflow.
 */
abstract class InventorySessionWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected static final String SESSION_TOPIC = "rwms.inventory.session.v1";
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
  protected final InventoryExpectedItemRepository expectedItems;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryEventStore events;
  protected final InventoryIdempotencyPort idempotency;
  protected final InventoryStartPersistencePort startPersistence;
  protected final InventoryFindingService findingService;
  protected final InventoryProjectionService projectionService;
  private final InventorySessionFactPayload sessionFactPayload;

  protected InventorySessionWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryExpectedItemRepository expectedItems,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryStartPersistencePort startPersistence,
      InventoryFindingService findingService,
      InventoryProjectionService projectionService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.expectedItems = expectedItems;
    this.dependencies = dependencies;
    this.events = events;
    this.idempotency = idempotency;
    this.startPersistence = startPersistence;
    this.findingService = findingService;
    this.projectionService = projectionService;
    sessionFactPayload = new InventorySessionFactPayload(mapper);
  }

  protected ObjectNode sessionPayload(
      InventorySession session, int findingCount, FrozenStatistics statistics) {
    return sessionFactPayload.build(session, findingCount, statistics);
  }

  protected String tenantSnapshot(JsonNode passportSnapshot) {
    if (passportSnapshot == null || !passportSnapshot.isObject()) return null;
    return nullableText(passportSnapshot.get("tenant"));
  }
}
