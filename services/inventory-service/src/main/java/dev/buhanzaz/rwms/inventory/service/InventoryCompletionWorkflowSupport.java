package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FrozenStatistics;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Exact completion transaction cluster.
 *
 * <p>These fifteen domain dependencies are needed together only to fence fresh validation,
 * persist the terminal session/final-plan state, append its event and create recoverable follow-up
 * effects. No other workflow receives this aggregate transition surface.
 */
abstract class InventoryCompletionWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected static final String SESSION_TOPIC = "rwms.inventory.session.v1";
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryFinalPlanRepository finalPlans;
  protected final InventoryFinalPlanEntryRepository finalPlanEntries;
  protected final FindingMediaReferenceRepository mediaReferences;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryEventStore events;
  protected final InventoryIdempotencyPort idempotency;
  protected final InventoryFindingValidationService validationService;
  protected final InventoryPlanningService planningService;
  protected final InventoryReviewService reviewService;
  protected final InventoryStatisticsService statisticsService;
  protected final InventoryFindingService findingService;
  protected final InventoryPublicationService publicationService;
  protected final InventoryProjectionService projectionService;

  protected InventoryCompletionWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      FindingMediaReferenceRepository mediaReferences,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryFindingValidationService validationService,
      InventoryPlanningService planningService,
      InventoryReviewService reviewService,
      InventoryStatisticsService statisticsService,
      InventoryFindingService findingService,
      InventoryPublicationService publicationService,
      InventoryProjectionService projectionService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.finalPlans = finalPlans;
    this.finalPlanEntries = finalPlanEntries;
    this.mediaReferences = mediaReferences;
    this.dependencies = dependencies;
    this.events = events;
    this.idempotency = idempotency;
    this.validationService = validationService;
    this.planningService = planningService;
    this.reviewService = reviewService;
    this.statisticsService = statisticsService;
    this.findingService = findingService;
    this.publicationService = publicationService;
    this.projectionService = projectionService;
  }

  protected InventorySession requireSession(UUID inventoryId) {
    return sessions
        .findById(inventoryId)
        .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
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
    InventorySession value = requireSession(inventoryId);
    if (value.getLifecycle() != SessionLifecycle.ACTIVE) {
      throw InventoryException.conflict("Inventory session is not active");
    }
    return value;
  }

  protected void expectRevision(long actual, long expected) {
    if (actual != expected) throw InventoryException.conflict("Inventory revision is stale");
  }

  protected ObjectNode sessionPayload(
      InventorySession session, int findingCount, FrozenStatistics statistics) {
    ObjectNode payload = objectNode();
    payload.put("inventoryId", session.getId().toString());
    payload.put("warehouseId", session.getWarehouseId().toString());
    payload.put("sessionRevision", session.getRevision());
    payload.put("lifecycle", session.getLifecycle().name());
    payload.put("businessDate", session.getBusinessDate().toString());
    payload.put("expectedCount", session.getExpectedPopulationCount());
    payload.put("findingCount", findingCount);
    OffsetDateTime terminal = terminalAt(session);
    if (terminal == null) payload.putNull("terminalAt");
    else payload.put("terminalAt", terminal.toString());
    if (statistics == null) payload.putNull("statistics");
    else payload.set("statistics", valueTree(statisticsWithoutLines(statistics)));
    return payload;
  }

  private OffsetDateTime terminalAt(InventorySession session) {
    return session.getCompletedAt() != null ? session.getCompletedAt() : session.getCancelledAt();
  }

  private Map<String, Object> statisticsWithoutLines(FrozenStatistics statistics) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("expectedCount", statistics.expectedCount());
    result.put("inspectedCount", statistics.inspectedCount());
    result.put("missingCount", statistics.missingCount());
    result.put("readyCount", statistics.readyCount());
    result.put("withWorkCount", statistics.withWorkCount());
    result.put("addedCount", statistics.addedCount());
    result.put("unexpectedExistingCount", statistics.unexpectedExistingCount());
    result.put("conflictCount", statistics.conflictCount());
    result.put("workLineCount", statistics.workLineCount());
    result.put("materialLineCount", statistics.materialLineCount());
    result.put("workTotalMinor", statistics.workTotalMinor());
    result.put("materialTotalMinor", statistics.materialTotalMinor());
    result.put("grandTotalMinor", statistics.grandTotalMinor());
    result.put("roundingAdjustmentMinor", statistics.roundingAdjustmentMinor());
    result.put("normativeMinutes", statistics.normativeMinutes());
    result.put("durationSeconds", statistics.durationSeconds());
    return result;
  }

  protected Observation observation(ObservationPresence presence, String value) {
    return new Observation(presence, value == null ? null : read(value));
  }
}
