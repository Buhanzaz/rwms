package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.Optional;
import java.util.Set;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Snapshot and remote-validation dependencies used when completion fences finding revisions.
 *
 * <p>The support is read/validation-only: it does not own completion state transitions or retry
 * dispatch.
 */
abstract class InventoryFindingValidationWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected static final Set<String> RENTAL_ITEM_STATUSES =
      Set.of(
          "RENTED",
          "BOOKED",
          "REPAIR",
          "WAITING_REPAIR_CHECK",
          "WRITTEN_OFF",
          "LOST",
          "CAPITAL_REPAIR",
          "AFTER_RENT",
          "WAITING_ESTIMATE_CONFIRMATION",
          "SALE",
          "USED_SALE",
          "RESERVED",
          "FREE",
          "WAREHOUSE",
          "OWN_NEEDS",
          "IN_TRANSFER");
  protected final InventoryFindingRepository findings;
  protected final FindingMediaReferenceRepository mediaReferences;
  protected final InventoryMediaFactProjectionRepository mediaFacts;
  protected final FindingPlanSnapshotRepository planSnapshots;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryFrozenPlanFingerprint frozenPlanFingerprint;

  protected InventoryFindingValidationWorkflowSupport(
      InventoryFindingRepository findings,
      FindingMediaReferenceRepository mediaReferences,
      InventoryMediaFactProjectionRepository mediaFacts,
      FindingPlanSnapshotRepository planSnapshots,
      InventoryDependencyGateway dependencies,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.findings = findings;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.planSnapshots = planSnapshots;
    this.dependencies = dependencies;
    this.frozenPlanFingerprint = frozenPlanFingerprint;
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

  protected String tenantSnapshot(JsonNode passportSnapshot) {
    if (passportSnapshot == null || !passportSnapshot.isObject()) return null;
    return nullableText(passportSnapshot.get("tenant"));
  }
}
