package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanState;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InventoryAssetOutcomeStatus;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Rebuilds durable downstream work from immutable completed-inventory evidence.
 *
 * <p>The command performs no remote I/O. It locks the completed session and exact final plan,
 * restores any explicit observations lost by the obsolete automatic-membership rule, creates
 * missing outcomes, requeues every existing publication and unresolved furniture delivery in one
 * shared next reapplication generation, and leaves schedulers to call each owning service with
 * durable idempotency.
 */
@Service
final class InventoryOutcomeRecoveryService extends InventoryTechnicalRuntimeSupport {
  private final InventorySessionRepository sessions;
  private final InventoryFinalPlanRepository finalPlans;
  private final InventoryFinalPlanEntryRepository finalPlanEntries;
  private final InventoryPublicationIntentRepository publications;
  private final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
  private final InventoryIdempotencyPort idempotency;
  private final InventoryPublicationService publicationService;
  private final CompletedInventoryPlanCorrectionService planCorrection;

  InventoryOutcomeRecoveryService(
      InventorySessionRepository sessions,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryPublicationIntentRepository publications,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryIdempotencyPort idempotency,
      InventoryPublicationService publicationService,
      CompletedInventoryPlanCorrectionService planCorrection,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.finalPlans = finalPlans;
    this.finalPlanEntries = finalPlanEntries;
    this.publications = publications;
    this.furnitureReconciliations = furnitureReconciliations;
    this.idempotency = idempotency;
    this.publicationService = publicationService;
    this.planCorrection = planCorrection;
  }

  /**
   * Queues one exact completed plan for authoritative reapplication under MANAGE authorization.
   */
  OutcomeRecalculation recalculate(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      RecalculateInventoryOutcomeRequest request) {
    InventorySession scoped = requireScopedCompleted(inventoryId, authorizer.manageScope(jwt));
    authorizer.requireManage(jwt, scoped.getWarehouseId());
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "outcome.recalculate",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.ACCEPTED.value(),
        OutcomeRecalculation.class,
        () -> doRecalculate(jwt, inventoryId, request));
  }

  private OutcomeRecalculation doRecalculate(
      Jwt jwt, UUID inventoryId, RecalculateInventoryOutcomeRequest request) {
    InventorySession session =
        sessions
            .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.COMPLETED)
            .orElseThrow(() -> InventoryException.conflict("Inventory session is not completed"));
    authorizer.requireManage(jwt, session.getWarehouseId());
    expect(session.getRevision(), request.expectedSessionRevision());

    InventoryFinalPlan plan =
        finalPlans
            .findByInventoryIdForUpdate(inventoryId)
            .orElseThrow(() -> InventoryException.conflict("Inventory final plan is missing"));
    if (plan.getState() != FinalPlanState.COMPLETED
        || plan.getFinalPlanVersion() != request.finalPlanVersion()
        || !plan.getFinalPlanSha256().equals(request.finalPlanSha256())) {
      throw InventoryException.conflict("Inventory final plan evidence is stale");
    }
    List<InventoryFinalPlanEntry> entries =
        finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
            inventoryId, plan.getFinalPlanVersion());
    if (entries.isEmpty()) {
      throw InventoryException.conflict("Inventory final plan has no findings");
    }
    CompletedInventoryPlanCorrectionService.CorrectionResult correction =
        planCorrection.correct(session, plan, entries, actor(jwt));
    plan = correction.plan();
    entries = correction.entries();
    if (correction.restoredCount() > 0) {
      log.info(
          "Restored {} explicit inventory observations into corrected plan {} v{}",
          correction.restoredCount(),
          inventoryId,
          plan.getFinalPlanVersion());
    }

    Map<UUID, InventoryPublicationIntent> byFinding = new LinkedHashMap<>();
    publications
        .findAllByInventoryIdOrderByFindingId(inventoryId)
        .forEach(value -> byFinding.put(value.getFindingId(), value));
    long currentReapplicationNo = -1;
    for (InventoryFinalPlanEntry entry : entries) {
      InventoryPublicationIntent existing = byFinding.get(entry.getFindingId());
      if (existing == null) continue;
      if (currentReapplicationNo < 0) {
        currentReapplicationNo = existing.getOutcomeReapplicationNo();
      } else if (currentReapplicationNo != existing.getOutcomeReapplicationNo()) {
        throw InventoryException.conflict(
            "Inventory outcome reapplication generation is inconsistent");
      }
    }
    long nextReapplicationNo = nextReapplicationNo(currentReapplicationNo);
    int created = 0;
    int requeued = 0;
    for (InventoryFinalPlanEntry entry : entries) {
      requireOutcomeIdentity(entry);
      InventoryAssetOutcomeStatus desired = desiredStatus(entry);
      FinalPlanTargetKind target = entry.isHasWork() ? FinalPlanTargetKind.REPAIR : null;
      long sourceRevision = Math.max(1, entry.getFindingRevision());
      InventoryPublicationIntent intent = byFinding.get(entry.getFindingId());
      if (intent == null) {
        intent =
            InventoryPublicationIntent.readyForOutcome(
                inventoryId,
                entry.getFindingId(),
                sourceRevision,
                plan.getFinalPlanVersion(),
                plan.getFinalPlanSha256(),
                target,
                desired,
                nextReapplicationNo,
                publicationService.frozenPassportObservation(entry));
        intent = publications.saveAndFlush(intent);
        publicationService.appendPublicationReady(intent, session, actor(jwt), true);
        created++;
      } else {
        intent.requeueForAuthoritativeOutcome(
            sourceRevision,
            plan.getFinalPlanVersion(),
            plan.getFinalPlanSha256(),
            target,
            desired);
        if (intent.getOutcomeReapplicationNo() != nextReapplicationNo) {
          throw InventoryException.conflict(
              "Inventory outcome reapplication generation is inconsistent");
        }
        intent = publications.saveAndFlush(intent);
        publicationService.appendPublicationReady(intent, session, actor(jwt), false);
        requeued++;
      }
    }

    FurnitureReconciliationState furnitureState = FurnitureReconciliationState.NOT_REQUIRED;
    InventoryFurnitureReconciliationIntent furniture =
        furnitureReconciliations.findByInventoryIdForUpdate(inventoryId).orElse(null);
    if (furniture != null) {
      furniture.requeueForAuthoritativeRecovery();
      furniture = furnitureReconciliations.saveAndFlush(furniture);
      furnitureState = furniture.getState();
    }
    return new OutcomeRecalculation(
        inventoryId,
        session.getRevision(),
        plan.getFinalPlanVersion(),
        plan.getFinalPlanSha256(),
        furnitureState,
        created,
        requeued,
        0,
        publicationService.publicationBatch(inventoryId));
  }

  private InventorySession requireScopedCompleted(
      UUID inventoryId, InventoryAuthorizer.WarehouseScope scope) {
    if (!scope.unrestricted() && scope.warehouseIds().isEmpty()) {
      throw InventoryException.notFound("Inventory session not found");
    }
    InventorySession session =
        (scope.unrestricted()
                ? sessions.findById(inventoryId)
                : sessions.findByIdAndWarehouseIdIn(inventoryId, scope.warehouseIds()))
            .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
    if (session.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw InventoryException.conflict("Inventory session is not completed");
    }
    return session;
  }

  private static void requireOutcomeIdentity(InventoryFinalPlanEntry entry) {
    if (entry.getAssetId() == null || entry.getAssetVersion() == null) {
      throw InventoryException.conflict("Inventory final-plan asset identity is incomplete");
    }
  }

  private static InventoryAssetOutcomeStatus desiredStatus(InventoryFinalPlanEntry entry) {
    if (!entry.isHasWork()) return InventoryAssetOutcomeStatus.FREE;
    return entry.isForceCapitalRepair()
        ? InventoryAssetOutcomeStatus.CAPITAL_REPAIR
        : InventoryAssetOutcomeStatus.REPAIR;
  }

  private static long nextReapplicationNo(long currentReapplicationNo) {
    if (currentReapplicationNo < 0) return 0;
    try {
      return Math.addExact(currentReapplicationNo, 1);
    } catch (ArithmeticException exception) {
      throw InventoryException.conflict("Inventory outcome reapplication generation is exhausted");
    }
  }

  private static void expect(long actual, long expected) {
    if (actual != expected) throw InventoryException.conflict("Inventory revision is stale");
  }
}
