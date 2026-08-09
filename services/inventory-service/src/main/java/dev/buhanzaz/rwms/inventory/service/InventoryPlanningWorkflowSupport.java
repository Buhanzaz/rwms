package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanningSettingsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.Optional;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact dependencies for planning settings and immutable final-plan versions.
 *
 * <p>It owns local plan preparation and preflight inputs only; publication remains a separate
 * downstream effect after completion.
 */
abstract class InventoryPlanningWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryPlanningSettingsRepository planningSettings;
  protected final InventoryFinalPlanRepository finalPlans;
  protected final InventoryFinalPlanEntryRepository finalPlanEntries;
  protected final FindingMediaReferenceRepository mediaReferences;
  protected final FindingPlanSnapshotRepository planSnapshots;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryIdempotencyPort idempotency;
  protected final InventoryFrozenPlanFingerprint frozenPlanFingerprint;

  protected InventoryPlanningWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryPlanningSettingsRepository planningSettings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      FindingMediaReferenceRepository mediaReferences,
      FindingPlanSnapshotRepository planSnapshots,
      InventoryDependencyGateway dependencies,
      InventoryIdempotencyPort idempotency,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.planningSettings = planningSettings;
    this.finalPlans = finalPlans;
    this.finalPlanEntries = finalPlanEntries;
    this.mediaReferences = mediaReferences;
    this.planSnapshots = planSnapshots;
    this.dependencies = dependencies;
    this.idempotency = idempotency;
    this.frozenPlanFingerprint = frozenPlanFingerprint;
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
    if (value.getLifecycle() != dev.buhanzaz.rwms.inventory.domain.SessionLifecycle.ACTIVE) {
      throw InventoryException.conflict("Inventory session is not active");
    }
    return value;
  }

  protected Optional<FindingPlanSnapshot> activePlanSnapshot(InventoryFinding finding) {
    String fingerprint = finding.getMaintenancePlanFingerprintSha256();
    if (fingerprint == null) {
      return Optional.empty();
    }
    return planSnapshots.findFirstByFindingIdAndFingerprintOrderByFindingRevisionDesc(
        finding.getId(), fingerprint);
  }

  protected void expectRevision(long actual, long expected) {
    if (actual != expected) throw InventoryException.conflict("Inventory revision is stale");
  }
}
