package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact state, event and remote-dispatch surface for recoverable publication intents.
 *
 * <p>Publication remains downstream of a completed inventory and uses independent transactions
 * for retry settlement so a failed remote effect never rolls back the local recovery record.
 */
abstract class InventoryPublicationWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected static final String PUBLICATION_TOPIC = "rwms.inventory.publication.v1";
  protected static final OpaqueActorReference PUBLICATION_RECOVERY_ACTOR =
      new OpaqueActorReference(
          UUID.nameUUIDFromBytes(
                  "rwms:inventory-service:publication-recovery".getBytes(StandardCharsets.UTF_8))
              .toString(),
          "SERVICE",
          null);
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryFinalPlanRepository finalPlans;
  protected final InventoryFinalPlanEntryRepository finalPlanEntries;
  protected final InventoryPublicationIntentRepository publications;
  protected final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
  protected final InventoryPublicationAttemptRepository publicationAttempts;
  protected final InventoryPublicationAttemptResultRepository publicationAttemptResults;
  protected final FindingPlanSnapshotRepository planSnapshots;
  protected final FindingMediaReferenceRepository mediaReferences;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryEventStore events;
  protected final InventoryIdempotencyPort idempotency;
  protected final InventoryPlanningService planningService;
  protected final InventoryProjectionService projectionService;

  protected InventoryPublicationWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryPublicationIntentRepository publications,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryPublicationAttemptRepository publicationAttempts,
      InventoryPublicationAttemptResultRepository publicationAttemptResults,
      FindingPlanSnapshotRepository planSnapshots,
      FindingMediaReferenceRepository mediaReferences,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryPlanningService planningService,
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
    this.publications = publications;
    this.furnitureReconciliations = furnitureReconciliations;
    this.publicationAttempts = publicationAttempts;
    this.publicationAttemptResults = publicationAttemptResults;
    this.planSnapshots = planSnapshots;
    this.mediaReferences = mediaReferences;
    this.dependencies = dependencies;
    this.events = events;
    this.idempotency = idempotency;
    this.planningService = planningService;
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

  protected InventorySession requireCompleted(UUID inventoryId) {
    InventorySession value = requireSession(inventoryId);
    if (value.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw InventoryException.conflict("Inventory session is not completed");
    }
    return value;
  }

  protected InventoryFinding requireFinding(UUID inventoryId, UUID findingId) {
    return findings
        .findByIdAndInventoryIdAndMembershipActiveTrue(findingId, inventoryId)
        .orElseThrow(() -> InventoryException.notFound("Inventory finding not found"));
  }

  protected InventoryPublicationIntent requirePublication(UUID inventoryId, UUID findingId) {
    return publications
        .findByInventoryIdAndFindingId(inventoryId, findingId)
        .orElseThrow(() -> InventoryException.notFound("Publication intent not found"));
  }

  protected InventoryPublicationIntent requirePublicationForUpdate(
      UUID inventoryId, UUID findingId) {
    return publications
        .findByInventoryIdAndFindingIdForUpdate(inventoryId, findingId)
        .orElseThrow(() -> InventoryException.notFound("Publication intent not found"));
  }

  protected Optional<FindingPlanSnapshot> activePlanSnapshot(InventoryFinding finding) {
    String fingerprint = finding.getMaintenancePlanFingerprintSha256();
    if (fingerprint == null) {
      return Optional.empty();
    }
    return planSnapshots.findFirstByFindingIdAndFingerprintOrderByFindingRevisionDesc(
        finding.getId(), fingerprint);
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
}
