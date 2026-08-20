package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;

/**
 * Primary application service for inventory sessions, findings, completion and publication workflow.
 * It persists local workflow state before external effects reach explicit recovery boundaries.
 */
@Service
public class InventoryApplicationService {
  private final InventorySessionService sessionService;
  private final InventoryReadService readService;
  private final InventoryFindingService findingService;
  private final InventoryReviewService reviewService;
  private final InventoryPlanningService planningService;
  private final InventoryCompletionService completionService;
  private final InventoryPublicationService publicationService;
  private final InventoryOutcomeRecoveryService outcomeRecoveryService;

  public InventoryApplicationService(
      InventorySessionService sessionService,
      InventoryReadService readService,
      InventoryFindingService findingService,
      InventoryReviewService reviewService,
      InventoryPlanningService planningService,
      InventoryCompletionService completionService,
      InventoryPublicationService publicationService,
      InventoryOutcomeRecoveryService outcomeRecoveryService) {
    this.sessionService = sessionService;
    this.readService = readService;
    this.findingService = findingService;
    this.reviewService = reviewService;
    this.planningService = planningService;
    this.completionService = completionService;
    this.publicationService = publicationService;
    this.outcomeRecoveryService = outcomeRecoveryService;
  }

  public SessionView start(Jwt jwt, UUID idempotencyKey, StartSessionRequest request) {
    return sessionService.start(jwt, idempotencyKey, request);
  }

  /** Delegates scheduled capture-release recovery and preserves the callable test/inbox seam. */
  public void recoverCaptureReleases() {
    sessionService.recoverCaptureReleases();
  }

  public PageResponse<SessionSummary> sessions(
      Jwt jwt,
      UUID warehouseId,
      SessionLifecycle lifecycle,
      LocalDate businessDateFrom,
      LocalDate businessDateTo,
      OffsetDateTime startedFrom,
      OffsetDateTime startedTo,
      OffsetDateTime terminalFrom,
      OffsetDateTime terminalTo,
      int page,
      int size,
      String sort) {
    return readService.sessions(
        jwt,
        warehouseId,
        lifecycle,
        businessDateFrom,
        businessDateTo,
        startedFrom,
        startedTo,
        terminalFrom,
        terminalTo,
        page,
        size,
        sort);
  }

  public Optional<SessionView> active(Jwt jwt, UUID warehouseId) {
    return readService.active(jwt, warehouseId);
  }

  public SessionView session(Jwt jwt, UUID inventoryId) {
    return readService.session(jwt, inventoryId);
  }

  /** Delegates a MANAGE-scoped refresh without moving membership ownership to the HTTP adapter. */
  public SessionView refresh(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, RefreshSessionRequest request) {
    return sessionService.refresh(jwt, inventoryId, idempotencyKey, request);
  }

  public PageResponse<FindingView> findings(
      Jwt jwt, UUID inventoryId, int page, int size, String sort) {
    return readService.findings(jwt, inventoryId, page, size, sort);
  }

  public FrozenStatistics preliminaryStatistics(Jwt jwt, UUID inventoryId) {
    return readService.preliminaryStatistics(jwt, inventoryId);
  }

  /**
   * Applies an inbox asset-membership signal using its original correlation and causation identity.
   */
  public void reconcileAssetMembership(
      UUID assetId,
      OpaqueActorReference sourceActor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt) {
    findingService.reconcileAssetMembership(
        assetId, sourceActor, correlationId, causationId, occurredAt);
  }

  /** Preserves the package-level producer seam used to verify finding event and owner-proof order. */
  void appendFindingFacts(
      InventoryFinding finding,
      InventorySession session,
      OpaqueActorReference actor,
      String eventType) {
    findingService.appendFindingFacts(finding, session, actor, eventType);
  }

  /** Preserves the package-level owner-proof event seam for recovery and integration tests. */
  void appendOwnerProof(
      InventoryFinding finding, UUID warehouseId, OpaqueActorReference actor) {
    findingService.appendOwnerProof(finding, warehouseId, actor);
  }

  public NumberResolutionView resolveNumber(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, ResolveNumberRequest request) {
    return findingService.resolveNumber(jwt, inventoryId, idempotencyKey, request);
  }

  public FindingView createAsset(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      CreateFindingAssetRequest request) {
    return findingService.createAsset(jwt, inventoryId, findingId, idempotencyKey, request);
  }

  public FindingView saveInspection(
      Jwt jwt, UUID inventoryId, UUID findingId, SaveInspectionRequest request) {
    return findingService.saveInspection(jwt, inventoryId, findingId, request);
  }

  public FindingView resolveConflict(
      Jwt jwt, UUID inventoryId, UUID findingId, ResolveConflictRequest request) {
    return findingService.resolveConflict(jwt, inventoryId, findingId, request);
  }

  public RegistryReviewView registryReview(
      Jwt jwt, UUID inventoryId, RegistryReviewRequest request) {
    return reviewService.registryReview(jwt, inventoryId, request);
  }

  public FurnitureReviewView startFurnitureReview(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      StartFurnitureReviewRequest request) {
    return reviewService.startFurnitureReview(jwt, inventoryId, idempotencyKey, request);
  }

  public FurnitureReviewView furnitureReview(Jwt jwt, UUID inventoryId) {
    return reviewService.furnitureReview(jwt, inventoryId);
  }

  public FurnitureReviewView saveFurnitureReview(
      Jwt jwt, UUID inventoryId, SaveFurnitureReviewRequest request) {
    return reviewService.saveFurnitureReview(jwt, inventoryId, request);
  }

  public PlanningSettingsView planningSettings(Jwt jwt, UUID warehouseId) {
    return planningService.planningSettings(jwt, warehouseId);
  }

  public PlanningSettingsView updatePlanningSettings(
      Jwt jwt, UUID warehouseId, PlanningSettingsUpdateRequest request) {
    return planningService.updatePlanningSettings(jwt, warehouseId, request);
  }

  public FinalPlanView prepareFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PrepareFinalPlanRequest request) {
    return planningService.prepareFinalPlan(jwt, inventoryId, idempotencyKey, request);
  }

  public FinalPlanView finalPlan(Jwt jwt, UUID inventoryId) {
    return planningService.finalPlan(jwt, inventoryId);
  }

  public FinalPlanView updateFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, FinalPlanUpdateRequest request) {
    return planningService.updateFinalPlan(jwt, inventoryId, idempotencyKey, request);
  }

  public CompletionPreview preview(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, CompletionPreviewRequest request) {
    return completionService.preview(jwt, inventoryId, idempotencyKey, request);
  }

  public SessionView complete(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, CompleteSessionRequest request) {
    return completionService.complete(jwt, inventoryId, idempotencyKey, request);
  }

  public SessionView cancel(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, CancelSessionRequest request) {
    return completionService.cancel(jwt, inventoryId, idempotencyKey, request);
  }

  public PublicationBatch publish(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PublishFindingsRequest request) {
    return publicationService.publish(jwt, inventoryId, idempotencyKey, request);
  }

  /** Queues the exact completed plan for authoritative owner-by-owner recovery. */
  public OutcomeRecalculation recalculateOutcome(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      RecalculateInventoryOutcomeRequest request) {
    return outcomeRecoveryService.recalculate(jwt, inventoryId, idempotencyKey, request);
  }

  public PublicationView retryPublication(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      RetryPublicationRequest request) {
    return publicationService.retryPublication(
        jwt, inventoryId, findingId, idempotencyKey, request);
  }

  public PublicationView closePublication(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      ClosePublicationRequest request) {
    return publicationService.closePublication(
        jwt, inventoryId, findingId, idempotencyKey, request);
  }

  public PageResponse<SessionStatistics> statistics(
      Jwt jwt,
      UUID warehouseId,
      LocalDate businessDateFrom,
      LocalDate businessDateTo,
      OffsetDateTime startedFrom,
      OffsetDateTime startedTo,
      OffsetDateTime terminalFrom,
      OffsetDateTime terminalTo,
      int page,
      int size,
      String sort) {
    return readService.statistics(
        jwt,
        warehouseId,
        businessDateFrom,
        businessDateTo,
        startedFrom,
        startedTo,
        terminalFrom,
        terminalTo,
        page,
        size,
        sort);
  }

  public StatisticsSummary statisticsSummary(
      Jwt jwt,
      UUID warehouseId,
      LocalDate businessDateFrom,
      LocalDate businessDateTo,
      OffsetDateTime startedFrom,
      OffsetDateTime startedTo,
      OffsetDateTime terminalFrom,
      OffsetDateTime terminalTo) {
    return readService.statisticsSummary(
        jwt,
        warehouseId,
        businessDateFrom,
        businessDateTo,
        startedFrom,
        startedTo,
        terminalFrom,
        terminalTo);
  }

  /** Delegates durable furniture-reconciliation recovery. */
  public void recoverFurnitureReconciliations() {
    reviewService.recoverFurnitureReconciliations();
  }

  /** Delegates durable furniture-loss recovery. */
  public void recoverFurnitureLosses() {
    reviewService.recoverFurnitureLosses();
  }

  /** Delegates durable maintenance-publication recovery. */
  public void recoverPendingPublications() {
    publicationService.recoverPendingPublications();
  }
}
