package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns preview acknowledgement and terminal completion/cancellation.
 *
 * <p>It validates fresh remote truth before changing session lifecycle, persists recovery intents
 * inside that transaction; durable schedulers perform every downstream effect after commit.
 */
@Service
final class InventoryCompletionService extends InventoryCompletionWorkflowSupport {
  private final InventoryFindingPersistenceService findingPersistence;
  private final InventoryCabinWriteOffService cabinWriteOffs;

  InventoryCompletionService(
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
      InventoryFindingPersistenceService findingPersistence,
      InventoryCabinWriteOffService cabinWriteOffs,
      InventoryPublicationService publicationService,
      InventoryProjectionService projectionService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        finalPlans,
        finalPlanEntries,
        mediaReferences,
        dependencies,
        events,
        idempotency,
        validationService,
        planningService,
        reviewService,
        statisticsService,
        findingService,
        publicationService,
        projectionService,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    this.findingPersistence = findingPersistence;
    this.cabinWriteOffs = cabinWriteOffs;
  }

  public CompletionPreview preview(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, CompletionPreviewRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.completion-preview",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        CompletionPreview.class,
        () -> doPreview(jwt, inventoryId, request));
  }

  private CompletionPreview doPreview(
      Jwt jwt, UUID inventoryId, CompletionPreviewRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    InventoryFindingValidationService.RevisionState revisions =
        validationService.revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryPlanningService.CompletionFinalPlan finalPlan =
        planningService.requireCompletionFinalPlan(
            session,
            request.finalPlanVersion(),
            request.finalPlanSha256(),
            true);
    InventoryDependencyGateway.Validation validation = validationService.validateAssets(revisions.findings());
    List<ValidatedFinding> validatedFindings =
        validationService.validatedFindings(session, revisions.findings(), validation);
    List<CompletionRisk> risks = validationService.risks(session, revisions.findings(), validatedFindings);
    InventoryReviewService.FurnitureCompletionFact furniture = reviewService.requireConfirmedFurnitureReview(session);
    if (risks.stream().noneMatch(risk -> "CONFLICT".equals(risk.code()))) {
      reviewService.requireCurrentFurnitureReviewSnapshot(session, revisions.findings());
    }
    FrozenStatistics statistics =
        statisticsService.calculateStatistics(session, revisions.findings(), validatedFindings);
    String acknowledgement =
        canonicalHash(
            acknowledgementFacts(
                session,
                revisions,
                finalPlan,
                validation,
                furniture,
                statistics,
                risks,
                validatedFindings));
    CompletionPreview response =
        new CompletionPreview(
            inventoryId,
            session.getRevision(),
            finalPlan.plan().getFinalPlanVersion(),
            finalPlan.plan().getFinalPlanSha256(),
            revisions.expectations(),
            validation.validationDigest(),
            validation.validatedAt(),
            acknowledgement,
            statistics,
            risks,
            validatedFindings);
    transactions.executeWithoutResult(
        status -> statisticsService.persistValidation(session, validation, acknowledgement, response));
    return response;
  }

  public SessionView complete(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, CompleteSessionRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.complete",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        SessionView.class,
        () -> doComplete(jwt, inventoryId, request));
  }

  private SessionView doComplete(Jwt jwt, UUID inventoryId, CompleteSessionRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    dependencies.warehouseAdmission(
        session.getWarehouseId(),
        InventoryDependencyGateway.WarehouseOperationDirection.OUTGOING);
    InventoryFindingValidationService.RevisionState revisions =
        validationService.revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryPlanningService.CompletionFinalPlan finalPlan =
        planningService.requireCompletionFinalPlan(
            session,
            request.finalPlanVersion(),
            request.finalPlanSha256(),
            true);
    InventoryDependencyGateway.Validation fresh = validationService.validateAssets(revisions.findings());
    InventoryStatisticsService.ValidationRecord preview = statisticsService.validationRecord(inventoryId);
    if (!request.acknowledgementSha256().equals(preview.acknowledgement())
        || !request.validationSha256().equals(preview.validation())
        || preview.sessionRevision() != session.getRevision()
        || preview.preview().finalPlanVersion() != finalPlan.plan().getFinalPlanVersion()
        || !finalPlan.plan().getFinalPlanSha256().equals(preview.preview().finalPlanSha256())
        || !fresh.validationDigest().equals(preview.validation())
        || !validationService.semanticValidationDigest(preview.validationTruth().path("assets"))
            .equals(preview.validation())) {
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACKNOWLEDGEMENT_STALE",
          "Inventory preview acknowledgement is stale");
    }
    List<ValidatedFinding> validatedFindings =
        validationService.validatedFindings(session, revisions.findings(), fresh);
    List<CompletionRisk> risks = validationService.risks(session, revisions.findings(), validatedFindings);
    if (!canonicalHash(semanticValidatedFindings(validatedFindings))
        .equals(
            canonicalHash(
                semanticValidatedFindings(preview.preview().validatedFindings())))) {
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACKNOWLEDGEMENT_STALE",
          "Inventory registry facts changed after preview");
    }
    if (!risks.equals(preview.preview().risks())) {
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACKNOWLEDGEMENT_STALE",
          "Inventory completion risks changed after preview");
    }
    if (risks.stream()
        .anyMatch(
            risk ->
                !"MISSING".equals(risk.code())
                    && !"NOT_INSPECTED".equals(risk.code()))) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Inventory has unresolved completion risks");
    }
    InventoryReviewService.FurnitureCompletionFact furniture =
        reviewService.requireConfirmedCurrentFurnitureReview(session, revisions.findings());
    FrozenStatistics previewStatistics = preview.preview().statistics();
    InventorySession completed =
        transactions.execute(
            status -> {
              InventorySession locked =
                  sessions
                      .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.ACTIVE)
                      .orElseThrow(
                          () -> InventoryException.conflict("Inventory session is not active"));
              InventoryFindingValidationService.RevisionState lockedRevisions =
                  validationService.revisionState(locked, request.expectedSessionRevision(), request.findingRevisions());
              InventoryFinalPlan lockedFinalPlan =
                  finalPlans
                      .findByInventoryIdForUpdate(inventoryId)
                      .orElseThrow(
                          () -> InventoryException.conflict("Inventory final plan is not prepared"));
              if (lockedFinalPlan.getState() != FinalPlanState.DRAFT
                  || lockedFinalPlan.getFinalPlanVersion() != request.finalPlanVersion()
                  || !lockedFinalPlan.getFinalPlanSha256().equals(request.finalPlanSha256())
                  || lockedFinalPlan.getBasisSessionRevision() != locked.getRevision()) {
                throw InventoryException.conflict("Inventory final plan is stale");
              }
              List<InventoryFinalPlanEntry> lockedPlanEntries =
                  finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
                      inventoryId, lockedFinalPlan.getFinalPlanVersion());
              planningService.completionFinalPlanDrafts(locked, lockedFinalPlan, lockedPlanEntries);
              CompletionPreview completionSnapshot =
                  new CompletionPreview(
                      inventoryId,
                      locked.getRevision(),
                      lockedFinalPlan.getFinalPlanVersion(),
                      lockedFinalPlan.getFinalPlanSha256(),
                      lockedRevisions.expectations(),
                      fresh.validationDigest(),
                      fresh.validatedAt(),
                      request.acknowledgementSha256(),
                      previewStatistics,
                      risks,
                      validatedFindings);
              statisticsService.persistValidation(
                  locked, fresh, request.acknowledgementSha256(), completionSnapshot);
              reviewService.requireLockedFurnitureReview(locked, furniture);
              locked.complete(
                  fresh.validationDigest(),
                  request.acknowledgementSha256(),
                  fresh.validatedAt(),
                  actorJson(jwt));
              InventorySession result = sessions.saveAndFlush(locked);
              lockedFinalPlan.complete();
              finalPlans.saveAndFlush(lockedFinalPlan);
              FrozenStatistics finalStatistics =
                  statisticsService.calculateStatistics(result, lockedRevisions.findings(), validatedFindings);
              statisticsService.persistStatistics(result, finalStatistics);
              reviewService.createFurnitureLossIntents(result);
              publicationService.createPublicationIntents(result, lockedFinalPlan, lockedPlanEntries, actor(jwt));
              reviewService.createFurnitureReconciliationIntent(
                  result, publicationService.sourceAssetOutcomes(result, lockedPlanEntries));
              cabinWriteOffs.createIntents(result, lockedFinalPlan, lockedPlanEntries);
              events.append(
                  "SESSION",
                  result.getId(),
                  events.currentVersion("SESSION", result.getId()),
                  "inventory.session.completed.v1",
                  SESSION_TOPIC,
                  sessionPayload(result, revisions.findings().size(), finalStatistics),
                  correlationId(),
                  null,
                  actor(jwt));
              return result;
            });
    return projectionService.sessionView(requireSession(completed.getId()));
  }

  /**
   * Cancels the session and carries every affected finding's exact prior-revision media set across
   * the owner-proof revision bump in the same transaction.
   */
  public SessionView cancel(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, CancelSessionRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.cancel",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        SessionView.class,
        () -> doCancel(jwt, inventoryId, request));
  }

  private SessionView doCancel(Jwt jwt, UUID inventoryId, CancelSessionRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    dependencies.warehouseAdmission(
        session.getWarehouseId(),
        InventoryDependencyGateway.WarehouseOperationDirection.OUTGOING);
    InventorySession cancelled =
        transactions.execute(
            status -> {
              InventorySession locked = requireActive(inventoryId);
              expectRevision(locked.getRevision(), request.expectedSessionRevision());
              locked.cancel(request.reason(), actorJson(jwt));
              InventorySession result = sessions.saveAndFlush(locked);
              List<InventoryFinding> cancelledFindings =
                  findings.findAllByInventoryIdForUpdateOrderById(inventoryId);
              Map<UUID, Long> mediaSourceRevisions = new LinkedHashMap<>();
              for (InventoryFinding finding : cancelledFindings) {
                mediaSourceRevisions.put(finding.getId(), finding.getRevision());
                finding.transitionOwnerProof(false);
              }
              findings.saveAllAndFlush(cancelledFindings);
              for (InventoryFinding finding : cancelledFindings) {
                findingPersistence.carryForwardMediaReferences(
                    finding.getId(),
                    mediaSourceRevisions.get(finding.getId()),
                    finding.getRevision());
                findingService.appendOwnerProof(finding, result.getWarehouseId(), actor(jwt));
              }
              events.append(
                  "SESSION",
                  result.getId(),
                  events.currentVersion("SESSION", result.getId()),
                  "inventory.session.cancelled.v1",
                  SESSION_TOPIC,
                  sessionPayload(result, cancelledFindings.size(), null),
                  correlationId(),
                  null,
                  actor(jwt));
              return result;
            });
    return projectionService.sessionView(cancelled);
  }

  private Map<String, Object> acknowledgementFacts(
      InventorySession session,
      InventoryFindingValidationService.RevisionState revisions,
      InventoryPlanningService.CompletionFinalPlan finalPlan,
      InventoryDependencyGateway.Validation validation,
      InventoryReviewService.FurnitureCompletionFact furniture,
      FrozenStatistics statistics,
      List<CompletionRisk> risks,
      List<ValidatedFinding> validatedFindings) {
    List<Map<String, Object>> findingFacts = new ArrayList<>();
    for (InventoryFinding finding : revisions.findings()) {
      Map<String, Object> fact = new LinkedHashMap<>();
      fact.put("findingId", finding.getId());
      fact.put("findingRevision", finding.getRevision());
      fact.put("origin", finding.getOrigin());
      fact.put("inspection", finding.getInspection());
      fact.put("reconciliation", finding.getReconciliation());
      fact.put("assetId", finding.getAssetId());
      fact.put("assetVersion", finding.getAssetVersion());
      fact.put("passportPresence", finding.getPassportObservationState());
      fact.put(
          "passportObservation",
          finding.getPassportObservation() == null
              ? null
              : read(finding.getPassportObservation()));
      fact.put("equipmentPresence", finding.getEquipmentObservationState());
      fact.put(
          "equipmentObservation",
          finding.getEquipmentObservation() == null
              ? null
              : read(finding.getEquipmentObservation()));
      fact.put("planFingerprintSha256", finding.getMaintenancePlanFingerprintSha256());
      fact.put(
          "media",
          mediaReferences
              .findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
                  finding.getId(), finding.getRevision())
              .stream()
              .map(
                  reference ->
                      new MediaReference(reference.getMediaId(), reference.getGeneration()))
              .toList());
      findingFacts.add(fact);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("inventoryId", session.getId());
    result.put("sessionRevision", session.getRevision());
    result.put("finalPlanVersion", finalPlan.plan().getFinalPlanVersion());
    result.put("finalPlanSha256", finalPlan.plan().getFinalPlanSha256());
    result.put("findingRevisions", revisions.expectations());
    result.put("findingFacts", findingFacts);
    result.put("validationSha256", validation.validationDigest());
    result.put(
        "validationAssets",
        validationService.semanticValidationAssets(mapper.valueToTree(validation.assets())));
    result.put("furnitureAssetSnapshotSha256", furniture.assetSnapshotSha256());
    result.put("furnitureReviewSha256", furniture.reviewSha256());
    result.put("furnitureObservation", furniture.observation());
    result.put("statistics", statistics);
    result.put("risks", risks);
    result.put("validatedFindings", semanticValidatedFindings(validatedFindings));
    return result;
  }

  private List<Object> semanticValidatedFindings(List<ValidatedFinding> findings) {
    ArrayNode semantic = mapper.valueToTree(findings);
    for (JsonNode finding : semantic) {
      JsonNode current = finding.path("currentSnapshot");
      if (current.isObject()) {
        ((ObjectNode) current).remove("assetVersion");
      }
    }
    List<Object> result = new ArrayList<>();
    for (JsonNode finding : semantic) {
      result.add(convert(finding, Object.class));
    }
    return List.copyOf(result);
  }
}
