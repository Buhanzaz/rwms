package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanReconciliationStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanScheduleMode;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanState;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.FurnitureLossIntentState;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureLossIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovement;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanningSettings;
import dev.buhanzaz.rwms.inventory.domain.MaintenancePublicationOutcome;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.InventorySourceAttachment;
import dev.buhanzaz.rwms.inventory.domain.InventoryValidationSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InventoryValidationItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryCompletionStatistics;
import dev.buhanzaz.rwms.inventory.domain.InventoryStatisticsLine;
import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttempt;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttemptResult;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.InventoryReviewStage;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.mapper.InventorySessionMapper;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureLossIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanningSettingsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySourceAttachmentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryValidationItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryCompletionStatisticsRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStatisticsLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMembershipMovementRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.inventory.service.InventoryStartPersistencePort.CapturedCapture;
import dev.buhanzaz.rwms.inventory.service.InventoryStartPersistencePort.StartOperation;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class InventoryApplicationService {
  private static final Logger log = LoggerFactory.getLogger(InventoryApplicationService.class);
  private static final String SESSION_TOPIC = "rwms.inventory.session.v1";
  private static final String PUBLICATION_TOPIC = "rwms.inventory.publication.v1";
  private static final OpaqueActorReference ASSET_SYNC_ACTOR =
      new OpaqueActorReference(
          UUID.nameUUIDFromBytes(
                  "rwms:inventory-service:asset-membership".getBytes(StandardCharsets.UTF_8))
              .toString(),
          "SERVICE",
              null);
  private static final OpaqueActorReference PUBLICATION_RECOVERY_ACTOR =
      new OpaqueActorReference(
          UUID.nameUUIDFromBytes(
                  "rwms:inventory-service:publication-recovery".getBytes(StandardCharsets.UTF_8))
              .toString(),
          "SERVICE",
          null);
  private static final Set<String> RENTAL_ITEM_STATUSES =
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
  private static final Set<String> CAPTURE_STATUSES =
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

  private final InventorySessionRepository sessions;
  private final InventoryFindingRepository findings;
  private final InventoryPlanningSettingsRepository planningSettings;
  private final InventoryFinalPlanRepository finalPlans;
  private final InventoryFinalPlanEntryRepository finalPlanEntries;
  private final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
  private final InventoryFurnitureLossIntentRepository furnitureLosses;
  private final InventoryPublicationIntentRepository publications;
  private final InventoryPublicationAttemptRepository publicationAttempts;
  private final InventoryPublicationAttemptResultRepository publicationAttemptResults;
  private final InventoryExpectedItemRepository expectedItems;
  private final InventoryMembershipMovementRepository membershipMovements;
  private final InventorySourceAttachmentRepository sourceAttachments;
  private final FindingMediaReferenceRepository mediaReferences;
  private final InventoryMediaFactProjectionRepository mediaFacts;
  private final FindingPlanSnapshotRepository planSnapshots;
  private final FindingPlanLineRepository planLines;
  private final FindingPlanStageRepository planStages;
  private final InventoryValidationSnapshotRepository validationSnapshots;
  private final InventoryValidationItemRepository validationItems;
  private final InventoryCompletionStatisticsRepository completionStatistics;
  private final InventoryStatisticsLineRepository statisticsLines;
  private final InventoryDependencyGateway dependencies;
  private final InventoryEventStore events;
  private final InventoryAuthorizer authorizer;
  private final InventorySessionMapper sessionMapper;
  private final InventoryIdempotencyPort idempotency;
  private final InventoryStartPersistencePort startPersistence;
  private final InventoryCanonicalJsonPort canonicalJson;
  private final InventoryFrozenPlanFingerprint frozenPlanFingerprint;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final TransactionTemplate independentTransactions;

  public InventoryApplicationService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryPlanningSettingsRepository planningSettings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryFurnitureLossIntentRepository furnitureLosses,
      InventoryPublicationIntentRepository publications,
      InventoryPublicationAttemptRepository publicationAttempts,
      InventoryPublicationAttemptResultRepository publicationAttemptResults,
      InventoryExpectedItemRepository expectedItems,
      InventoryMembershipMovementRepository membershipMovements,
      InventorySourceAttachmentRepository sourceAttachments,
      FindingMediaReferenceRepository mediaReferences,
      InventoryMediaFactProjectionRepository mediaFacts,
      FindingPlanSnapshotRepository planSnapshots,
      FindingPlanLineRepository planLines,
      FindingPlanStageRepository planStages,
      InventoryValidationSnapshotRepository validationSnapshots,
      InventoryValidationItemRepository validationItems,
      InventoryCompletionStatisticsRepository completionStatistics,
      InventoryStatisticsLineRepository statisticsLines,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryAuthorizer authorizer,
      InventorySessionMapper sessionMapper,
      InventoryIdempotencyPort idempotency,
      InventoryStartPersistencePort startPersistence,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.sessions = sessions;
    this.findings = findings;
    this.planningSettings = planningSettings;
    this.finalPlans = finalPlans;
    this.finalPlanEntries = finalPlanEntries;
    this.furnitureReconciliations = furnitureReconciliations;
    this.furnitureLosses = furnitureLosses;
    this.publications = publications;
    this.publicationAttempts = publicationAttempts;
    this.publicationAttemptResults = publicationAttemptResults;
    this.expectedItems = expectedItems;
    this.membershipMovements = membershipMovements;
    this.sourceAttachments = sourceAttachments;
    this.mediaReferences = mediaReferences;
    this.mediaFacts = mediaFacts;
    this.planSnapshots = planSnapshots;
    this.planLines = planLines;
    this.planStages = planStages;
    this.validationSnapshots = validationSnapshots;
    this.validationItems = validationItems;
    this.completionStatistics = completionStatistics;
    this.statisticsLines = statisticsLines;
    this.dependencies = dependencies;
    this.events = events;
    this.authorizer = authorizer;
    this.sessionMapper = sessionMapper;
    this.idempotency = idempotency;
    this.startPersistence = startPersistence;
    this.canonicalJson = canonicalJson;
    this.frozenPlanFingerprint = frozenPlanFingerprint;
    this.mapper = mapper;
    transactions = new TransactionTemplate(transactionManager);
    independentTransactions = new TransactionTemplate(transactionManager);
    independentTransactions.setPropagationBehavior(
        org.springframework.transaction.TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  public SessionView start(Jwt jwt, UUID idempotencyKey, StartSessionRequest request) {
    authorizer.requireEdit(jwt, request.warehouseId());
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.start",
        idempotencyKey,
        request,
        HttpStatus.CREATED.value(),
        SessionView.class,
        () -> doStart(jwt, idempotencyKey, request));
  }

  private SessionView doStart(Jwt jwt, UUID idempotencyKey, StartSessionRequest request) {
    authorizer.requireEdit(jwt, request.warehouseId());
    UUID subjectId = authorizer.subjectId(jwt);
    String requestHash = canonicalHash(request);
    StartOperation operation =
        startOperation(subjectId, idempotencyKey, requestHash, request.warehouseId());
    if (operation.sessionId() != null) {
      return sessionView(
          sessions
              .findById(operation.sessionId())
              .orElseThrow(() ->
                  new IllegalStateException("Start operation points to a missing session")));
    }
    InventoryDependencyGateway.WarehouseOperation warehouse =
        dependencies.beginWarehouseOperation(
            request.warehouseId(),
            operation.operationId(),
            operation.createdAt(),
            InventoryDependencyGateway.WarehouseOperationDirection.INCOMING);
    InventoryDependencyGateway.Capture previousCapture = captured(operation);
    InventoryDependencyGateway.Capture capture =
        previousCapture == null ? acquireCapture(operation, requestHash) : previousCapture;
    List<InventoryDependencyGateway.CaptureMember> members;
    try {
      members = copyCapture(capture);
    } catch (RuntimeException failure) {
      releaseCapture(operation.operationId(), capture.captureId());
      throw failure;
    }
    String actorJson = actorJson(jwt);
    OpaqueActorReference actor = actor(jwt);
    try {
      SessionView created =
          transactions.execute(
              status -> {
                InventorySession session =
                    sessions.saveAndFlush(
                        InventorySession.start(
                            warehouse.warehouseId(),
                            warehouse.warehouseVersion(),
                            warehouse.timeZone(),
                            LocalDate.now(ZoneId.of(warehouse.timeZone())),
                            operation.operationId(),
                            idempotencyKey,
                            requestHash,
                            members.size(),
                            capture.membershipDigest(),
                            subjectId,
                            authorizer.displayName(jwt),
                            actorJson));
                copyExpectedPopulation(session, members, actorJson, actor);
                ObjectNode payload = sessionPayload(session, members.size(), null);
                events.initialize(
                    "SESSION",
                    session.getId(),
                    "inventory.session.started.v1",
                    SESSION_TOPIC,
                    payload,
                    correlationId(),
                    null,
                    actor);
                startPersistence.sessionCommitted(operation.operationId(), session.getId());
                return sessionView(session);
              });
      releaseCaptureAfterCompletion(operation.operationId(), capture.captureId());
      return created;
    } catch (DataIntegrityViolationException exception) {
      InventorySession winner =
          sessions.findByStartOperationId(operation.operationId()).orElse(null);
      if (winner != null && requestHash.equals(winner.getStartRequestSha256())) {
        releaseCapture(operation.operationId(), capture.captureId());
        return sessionView(winner);
      }
      releaseCapture(operation.operationId(), capture.captureId());
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACTIVE_SESSION_CONFLICT",
          "Warehouse already has an active inventory session");
    }
  }

  private StartOperation startOperation(
      UUID subjectId, UUID idempotencyKey, String requestHash, UUID warehouseId) {
    return startPersistence.reserve(subjectId, idempotencyKey, requestHash, warehouseId);
  }

  private InventoryDependencyGateway.Capture captured(StartOperation operation) {
    CapturedCapture captured = startPersistence.latestCapture(operation.operationId());
    if (captured == null) return null;
    return new InventoryDependencyGateway.Capture(
        captured.captureId(),
        operation.operationId(),
        captured.technicalAttempt(),
        operation.warehouseId(),
        captured.totalCount(),
        captured.membershipDigest(),
        operation.createdAt(),
        captured.expiresAt());
  }

  private InventoryDependencyGateway.Capture acquireCapture(
      StartOperation operation, String requestHash) {
    long attempt =
        startPersistence.beginCaptureAttempt(operation.operationId(), requestHash);
    InventoryDependencyGateway.Capture capture;
    try {
      UUID technicalKey =
          UUID.nameUUIDFromBytes(
              (operation.operationId() + ":capture:" + attempt)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8));
      capture =
          dependencies.createCapture(
              technicalKey,
              new InventoryDependencyGateway.CaptureRequest(
                  operation.operationId(), attempt, requestHash, operation.warehouseId()));
    } catch (InventoryException exception) {
      startPersistence.recordCaptureFailure(
          operation.operationId(),
          attempt,
          exception.status().is4xxClientError(),
          exception.code());
      throw exception;
    }
    startPersistence.recordCaptured(
        operation.operationId(),
        attempt,
        capture.captureId(),
        capture.membershipDigest(),
        capture.totalCount(),
        capture.expiresAt());
    return capture;
  }

  private void releaseCapture(UUID operationId, UUID captureId) {
    try {
      dependencies.releaseCapture(captureId);
      startPersistence.releaseSucceeded(operationId, captureId);
    } catch (RuntimeException exception) {
      startPersistence.releaseFailed(operationId, "DEPENDENCY_UNAVAILABLE");
    }
  }

  private void releaseCaptureAfterCompletion(UUID operationId, UUID captureId) {
    if (!TransactionSynchronizationManager.isActualTransactionActive()) {
      releaseCapture(operationId, captureId);
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCompletion(int status) {
            releaseCapture(operationId, captureId);
          }
        });
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.capture-release-recovery-delay-ms:5000}")
  public void recoverCaptureReleases() {
    for (InventoryStartPersistencePort.CaptureReleaseClaim claim :
        startPersistence.claimPendingReleases("inventory-service", 20, Duration.ofSeconds(30))) {
      try {
        dependencies.releaseCapture(claim.captureId());
        startPersistence.recordClaimSucceeded(
            claim.operationId(), claim.captureId(), claim.leaseToken());
      } catch (RuntimeException exception) {
        startPersistence.recordClaimFailed(
            claim.operationId(),
            claim.captureId(),
            claim.leaseToken(),
            "DEPENDENCY_UNAVAILABLE");
      }
    }
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
    authorizer.requireRead(jwt, warehouseId);
    PageRequest request = PageRequest.of(page, size, sessionSort(sort));
    Page<InventorySession> result =
        sessions.findAll(
            sessionFilter(
                warehouseId,
                lifecycle,
                businessDateFrom,
                businessDateTo,
                startedFrom,
                startedTo,
                terminalFrom,
                terminalTo),
            request);
    List<InventorySession> pageContent = result.getContent();
    Set<UUID> inventoryIds =
        pageContent.stream()
            .map(InventorySession::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, SessionCounts> counts = sessionCounts(inventoryIds);
    Map<UUID, List<PublicationView>> publicationViews = sessionPublicationViews(inventoryIds);
    return new PageResponse<>(
        pageContent.stream()
            .map(
                value ->
                    sessionSummary(
                        value,
                        counts.getOrDefault(value.getId(), SessionCounts.EMPTY),
                        publicationViews.getOrDefault(value.getId(), List.of())))
            .toList(),
        new PageMetadata(page, size, result.getTotalElements(), result.getTotalPages()));
  }

  public Optional<SessionView> active(Jwt jwt, UUID warehouseId) {
    authorizer.requireRead(jwt, warehouseId);
    return sessions
        .findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE)
        .map(this::sessionView);
  }

  public SessionView session(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    return sessionView(session);
  }

  public PageResponse<FindingView> findings(
      Jwt jwt, UUID inventoryId, int page, int size, String sort) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    Page<InventoryFinding> result =
        findings.findByInventoryIdAndMembershipActiveTrue(
            inventoryId, PageRequest.of(page, size, findingSort(sort)));
    return new PageResponse<>(
        findingViews(result.getContent()),
        new PageMetadata(page, size, result.getTotalElements(), result.getTotalPages()));
  }

  /**
   * Returns the active cabin-review totals from persisted inventory facts only. Completion performs
   * a separate fresh asset validation before it freezes its final statistics.
   */
  public FrozenStatistics preliminaryStatistics(Jwt jwt, UUID inventoryId) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.manageScope(jwt)), SessionLifecycle.ACTIVE);
    requireCabinReviewStage(session);
    List<InventoryFinding> activeFindings =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId);
    return calculatePersistedStatistics(session, activeFindings);
  }

  public void reconcileAssetMembership(
      UUID assetId,
      OpaqueActorReference sourceActor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt) {
    if (assetId == null
        || correlationId == null
        || causationId == null
        || occurredAt == null) {
      throw new IllegalArgumentException("Asset membership event identity is incomplete");
    }
    Optional<InventoryDependencyGateway.LiveAssetSnapshot> current =
        dependencies.currentAsset(assetId);
    if (current.isPresent() && !RENTAL_ITEM_STATUSES.contains(current.get().status())) {
      throw InventoryException.dependency("Asset-service returned an unknown rental-item status");
    }
    OpaqueActorReference actor = normalizedAssetActor(sourceActor);
    transactions.executeWithoutResult(
        ignored ->
            reconcileAssetMembership(
                assetId,
                current.orElse(null),
                actor,
                correlationId,
                causationId,
                occurredAt));
  }

  private void reconcileAssetMembership(
      UUID assetId,
      InventoryDependencyGateway.LiveAssetSnapshot current,
      OpaqueActorReference actor,
      UUID correlationId,
      UUID causationId,
      OffsetDateTime occurredAt) {
    UUID currentWarehouseId = current == null ? null : current.warehouseId();
    boolean eligible = current != null && CAPTURE_STATUSES.contains(current.status());

    for (InventoryFinding finding :
        findings.findInActiveSessionsByAssetIdForUpdate(assetId)) {
      InventorySession session =
          sessions
              .findByIdAndLifecycleForUpdate(
                  finding.getInventoryId(), SessionLifecycle.ACTIVE)
              .orElse(null);
      if (session == null) continue;
      boolean departed =
          current == null
              || !eligible
              || !currentWarehouseId.equals(session.getWarehouseId());
      UUID previousWarehouseId = finding.getCurrentWarehouseId();
      String previousStatus = finding.getCurrentStatus();
      String previousTenant = finding.getCurrentTenantSnapshot();
      boolean snapshotChanged =
          !java.util.Objects.equals(
                  finding.getAssetVersion(), current == null ? null : current.version())
              || !java.util.Objects.equals(
                  finding.getCurrentWarehouseId(),
                  current == null ? null : current.warehouseId())
              || !java.util.Objects.equals(
                  finding.getCurrentStatus(), current == null ? null : current.status())
              || !java.util.Objects.equals(
                  finding.getCurrentTenantSnapshot(),
                  current == null ? null : current.tenantSnapshot())
              || !java.util.Objects.equals(
                  finding.getCurrentDisplayCanonicalNumber(),
                  current == null ? null : current.displayCanonicalNumber())
              || !storedJsonEquals(
                  finding.getCurrentPassportSnapshot(),
                  current == null ? null : current.passportSnapshot())
              || !storedJsonEquals(
                  finding.getCurrentContentsSnapshot(),
                  current == null ? null : current.contentsSnapshot());
      boolean snapshotRefreshed =
          snapshotChanged
              && (finding.getMutationState()
                      == dev.buhanzaz.rwms.inventory.domain.MutationState.IDLE
                  || finding.getMutationState()
                      == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATED);
      if (snapshotRefreshed) {
        JsonNode repairs =
            current == null || finding.getCurrentRepairsSnapshot() == null
                ? mapper.createArrayNode()
                : boundedSafeSnapshot(
                    finding.getCurrentRepairsSnapshot(), true, "current repairs");
        CurrentItemSnapshot live =
            current == null
                ? null
                : new CurrentItemSnapshot(
                    current.assetId(),
                    current.version(),
                    current.warehouseId(),
                    current.status(),
                    current.displayCanonicalNumber(),
                    current.tenantSnapshot(),
                    current.passportSnapshot(),
                    current.contentsSnapshot(),
                    repairs);
        ReconciliationState nextReconciliation =
            current == null
                ? ReconciliationState.MISSING
                : finding.getInspection() == InspectionState.NOT_INSPECTED
                    ? ReconciliationState.MATCHED
                    : conflictViews(finding, session.getWarehouseId(), live).isEmpty()
                        ? ReconciliationState.MATCHED
                        : ReconciliationState.CONFLICT;
        finding.refreshCurrentAsset(
            current == null ? null : current.version(),
            current == null ? null : current.warehouseId(),
            current == null ? null : current.status(),
            current == null ? null : current.tenantSnapshot(),
            current == null ? null : current.displayCanonicalNumber(),
            current == null ? null : json(current.passportSnapshot()),
            current == null ? null : json(current.contentsSnapshot()),
            current == null
                ? null
                : finding.getCurrentRepairsSnapshot() == null
                    ? "[]"
                    : finding.getCurrentRepairsSnapshot(),
            nextReconciliation);
      }
      // Departure changes the session's live population even when the source snapshot happened
      // to be identical to the last one we observed. Historical findings remain immutable audit
      // evidence, but every active projection and completion barrier must see them as gone.
      boolean membershipChanged = departed && finding.changeMembership(false);
      if (!snapshotRefreshed && !membershipChanged) continue;
      findings.saveAndFlush(finding);
      if (membershipChanged && finding.getOrigin() == FindingOrigin.EXPECTED) {
        session.changeExpectedPopulation(-1);
      }
      invalidateFurnitureReviewAfterCabinChange(session);
      invalidateFinalPlan(session);
      session.touch();
      sessions.saveAndFlush(session);
      if (membershipChanged) {
        membershipMovements.saveAndFlush(
            InventoryMembershipMovement.departed(
                session.getId(),
                causationId,
                assetId,
                finding.getOrigin(),
                finding.getDisplayCanonicalNumber(),
                session.getWarehouseId(),
                current != null
                        && current.warehouseId() != null
                        && !current.warehouseId().equals(session.getWarehouseId())
                    ? current.warehouseId()
                    : null,
                current == null ? previousStatus : current.status(),
                current == null ? previousTenant : current.tenantSnapshot(),
                occurredAt));
        appendFindingFacts(
            finding,
            session,
            actor,
            "inventory.finding.membership-departed.v1",
            correlationId,
            causationId);
      }
    }

    if (!eligible) return;
    InventorySession session =
        sessions
            .findByWarehouseIdAndLifecycleForUpdate(
                currentWarehouseId, SessionLifecycle.ACTIVE)
            .orElse(null);
    if (session == null) return;
    InventoryFinding existing =
        findings
            .findActiveByInventoryIdAndIdentityMatchKeyForUpdate(
                session.getId(), current.identityMatchKey())
            .orElse(null);
    if (existing != null) {
      if (existing.getAssetId() == null) {
        return;
      }
      if (!assetId.equals(existing.getAssetId())) {
        throw InventoryException.conflict(
            "Live inventory asset identity is already bound to another cabin");
      }
      boolean snapshotChanged =
          !java.util.Objects.equals(existing.getAssetVersion(), current.version())
              || !java.util.Objects.equals(
                  existing.getCurrentWarehouseId(), current.warehouseId())
              || !java.util.Objects.equals(existing.getCurrentStatus(), current.status())
              || !java.util.Objects.equals(
                  existing.getCurrentTenantSnapshot(), current.tenantSnapshot());
      if (!snapshotChanged) return;
      existing.refreshCurrentAsset(
          current.version(),
          current.warehouseId(),
          current.status(),
          current.tenantSnapshot(),
          current.displayCanonicalNumber(),
          json(current.passportSnapshot()),
          json(current.contentsSnapshot()),
          existing.getCurrentRepairsSnapshot() == null
              ? "[]"
              : existing.getCurrentRepairsSnapshot(),
          ReconciliationState.MATCHED);
      findings.saveAndFlush(existing);
      invalidateFurnitureReviewAfterCabinChange(session);
      invalidateFinalPlan(session);
      session.touch();
      sessions.saveAndFlush(session);
      appendFindingFacts(
          existing,
          session,
          actor,
          "inventory.finding.membership-refreshed.v1",
          correlationId,
          causationId);
      return;
    }

    UUID expectedId = UUID.randomUUID();
    InventoryFinding finding =
        InventoryFinding.expected(
            session.getId(),
            expectedId,
            current.assetId(),
            current.version(),
            current.warehouseId(),
            current.status(),
            current.tenantSnapshot(),
            current.displayCanonicalNumber(),
            current.identityMatchKey(),
            write(actor));
    finding.refreshCurrentAsset(
        current.version(),
        current.warehouseId(),
        current.status(),
        current.tenantSnapshot(),
        current.displayCanonicalNumber(),
        json(current.passportSnapshot()),
        json(current.contentsSnapshot()),
        "[]",
        ReconciliationState.MATCHED);
    finding = findings.saveAndFlush(finding);
    expectedItems.saveAndFlush(
        new InventoryExpectedItem(
            expectedId,
            session.getId(),
            finding.getId(),
            Math.addExact(expectedItems.maximumOrder(session.getId()), 1),
            current.assetId(),
            current.version(),
            current.status(),
            current.displayCanonicalNumber(),
            current.identityMatchKey(),
            current.passportSnapshot() == null ? "{}" : json(current.passportSnapshot()),
            current.contentsSnapshot() == null ? "[]" : json(current.contentsSnapshot())));
    session.changeExpectedPopulation(1);
    invalidateFurnitureReviewAfterCabinChange(session);
    invalidateFinalPlan(session);
    session.touch();
    sessions.saveAndFlush(session);
    membershipMovements.saveAndFlush(
        InventoryMembershipMovement.arrived(
            session.getId(),
            causationId,
            assetId,
            finding.getOrigin(),
            finding.getDisplayCanonicalNumber(),
            null,
            session.getWarehouseId(),
            current.status(),
            current.tenantSnapshot(),
            occurredAt));
    appendFindingFacts(
        finding,
        session,
        actor,
        "inventory.finding.added.v1",
        correlationId,
        causationId);
  }

  public NumberResolutionView resolveNumber(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, ResolveNumberRequest request) {
    requireScopedSession(inventoryId, authorizer.editScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.resolve-number",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        NumberResolutionView.class,
        () -> doResolveNumber(jwt, inventoryId, idempotencyKey, request));
  }

  private NumberResolutionView doResolveNumber(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, ResolveNumberRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    requireCabinReviewStage(session);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryDependencyGateway.NumberResolution resolved =
        dependencies.resolveNumber(session.getWarehouseId(), request.submittedNumber());
    if (resolved.asset() != null && !RENTAL_ITEM_STATUSES.contains(resolved.asset().status())) {
      throw InventoryException.dependency("Asset-service returned an unknown rental-item status");
    }
    InventoryFinding existing =
        findings
            .findByInventoryIdAndIdentityMatchKeyAndMembershipActiveTrue(
                inventoryId, resolved.identityMatchKey())
            .orElse(null);
    if (existing != null) {
      CurrentItemSnapshot current = currentItemSnapshot(resolved.asset());
      ValidatedFinding validation = validatedFinding(session, existing, current);
      return new NumberResolutionView(
          resolved.displayCanonicalNumber(),
          resolved.identityMatchKey(),
          numberResolutionOutcome(session.getWarehouseId(), current, true),
          findingView(existing, validation));
    }
    if (!resolved.found() || resolved.asset() == null) {
      return new NumberResolutionView(
          resolved.displayCanonicalNumber(), resolved.identityMatchKey(), "NOT_FOUND", null);
    }
    InventoryDependencyGateway.AssetSnapshot asset = resolved.asset();
    String outcome;
    ReconciliationState reconciliation;
    if (!session.getWarehouseId().equals(asset.warehouseId())) {
      outcome = "CROSS_WAREHOUSE_CONFLICT";
      reconciliation = ReconciliationState.MISSING;
    } else if (isTerminalDispositionStatus(asset.status())) {
      outcome = "EXCLUDED_STATUS_CONFLICT";
      reconciliation = ReconciliationState.MISSING;
    } else {
      outcome = "MATCHED";
      reconciliation = ReconciliationState.MATCHED;
    }
    String actorJson = actorJson(jwt);
    OpaqueActorReference actor = actor(jwt);
    InventoryFinding created;
    try {
      created =
          transactions.execute(
              status -> {
                InventorySession locked = requireActive(inventoryId);
                expectRevision(locked.getRevision(), request.expectedSessionRevision());
                InventoryFinding value =
                    findings.saveAndFlush(
                        InventoryFinding.unexpected(
                            inventoryId,
                            FindingOrigin.UNEXPECTED_EXISTING,
                            asset.assetId(),
                            asset.version(),
                            asset.warehouseId(),
                            asset.status(),
                            asset.tenantSnapshot(),
                            asset.displayCanonicalNumber(),
                            asset.identityMatchKey(),
                            reconciliation,
                            actorJson));
                locked.touch();
                sessions.saveAndFlush(locked);
                if ("MATCHED".equals(outcome)) {
                  membershipMovements.saveAndFlush(
                      InventoryMembershipMovement.arrived(
                          locked.getId(),
                          idempotencyKey,
                          asset.assetId(),
                          value.getOrigin(),
                          value.getDisplayCanonicalNumber(),
                          null,
                          locked.getWarehouseId(),
                          asset.status(),
                          asset.tenantSnapshot(),
                          OffsetDateTime.now(ZoneOffset.UTC)));
                }
                appendFindingFacts(value, locked, actor, "inventory.finding.added.v1");
                return value;
              });
    } catch (DataIntegrityViolationException race) {
      created =
          findings
              .findByInventoryIdAndIdentityMatchKeyAndMembershipActiveTrue(
                  inventoryId, resolved.identityMatchKey())
              .orElseThrow(() -> InventoryException.conflict("Number resolution raced"));
    }
    return new NumberResolutionView(
        resolved.displayCanonicalNumber(),
        resolved.identityMatchKey(),
        outcome,
        findingView(created));
  }

  public FindingView createAsset(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      CreateFindingAssetRequest request) {
    requireScopedSession(inventoryId, authorizer.editScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "finding.create-source",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "findingId", findingId, "request", request),
        HttpStatus.OK.value(),
        FindingView.class,
        () -> doCreateAsset(jwt, inventoryId, findingId, idempotencyKey, request));
  }

  private FindingView doCreateAsset(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      CreateFindingAssetRequest request) {
    InventorySession session = requireSession(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    if (session.getLifecycle() == SessionLifecycle.ACTIVE) {
      requireCabinReviewStage(session);
    }
    if (request.origin() != FindingOrigin.ADDED_NEW
        && request.origin() != FindingOrigin.ADDED_USED) {
      throw new IllegalArgumentException("Created asset origin must be ADDED_NEW or ADDED_USED");
    }
    if (request.sourceRevision() != Math.addExact(request.expectedFindingRevision(), 1)) {
      throw new IllegalArgumentException("Source revision must follow the expected finding revision");
    }
    String display = canonicalDisplayNumber(request.displayCanonicalNumber());
    String matchKey = display.replace(" ", "").replace("-", "");
    String actorJson = actorJson(jwt);
    ObjectNode remoteRequest =
        sourceAssetRequest(inventoryId, findingId, session, display, request.safePassport());
    String requestHash = canonicalHash(remoteRequest);
    InventorySourceAttachment recovery =
        sourceAttachments.findByInventoryIdAndFindingId(inventoryId, findingId).orElse(null);
    if (recovery != null) {
      if (!requestHash.equals(recovery.getRequestSha256())) {
        throw InventoryException.conflict("Finding source request is already bound");
      }
      InventoryFinding recoveredFinding = requireFinding(inventoryId, findingId);
      if (recovery.isAttached()) {
        if (!recovery.getCreatedAssetId().equals(recoveredFinding.getAssetId())
            || !recovery.getCreatedAssetVersion().equals(recoveredFinding.getAssetVersion())) {
          throw new IllegalStateException("Attached source asset differs from its finding");
        }
        return findingView(recoveredFinding);
      }
    } else {
      if (session.getLifecycle() != SessionLifecycle.ACTIVE) {
        throw InventoryException.conflict("Only an active session may create a finding asset");
      }
      expectRevision(session.getRevision(), request.expectedSessionRevision());
    }
    transactions.executeWithoutResult(
        status -> {
          InventorySession locked = requireSession(inventoryId);
          InventoryFinding pending =
              findings.findByIdAndInventoryId(findingId, inventoryId).orElse(null);
          if (pending == null) {
            if (locked.getLifecycle() != SessionLifecycle.ACTIVE) {
              throw InventoryException.conflict("Only an active session may create a finding asset");
            }
            expectRevision(locked.getRevision(), request.expectedSessionRevision());
            if (request.expectedFindingRevision() != 0) {
              throw InventoryException.conflict("New finding revision must start at zero");
            }
            pending =
                findings.saveAndFlush(
                    InventoryFinding.unexpectedWithId(
                        findingId,
                        inventoryId,
                        request.origin(),
                        display,
                        matchKey,
                        actorJson));
            locked.touch();
            sessions.saveAndFlush(locked);
            appendFindingFacts(pending, locked, actor(jwt), "inventory.finding.added.v1");
          } else {
            InventorySourceAttachment existingAttachment =
                sourceAttachments
                    .findByInventoryIdAndFindingId(inventoryId, findingId)
                    .orElse(null);
            if (existingAttachment == null) {
              if (locked.getLifecycle() != SessionLifecycle.ACTIVE) {
                throw InventoryException.conflict(
                    "Only an active session may create a finding asset");
              }
              expectRevision(locked.getRevision(), request.expectedSessionRevision());
              expectRevision(pending.getRevision(), request.expectedFindingRevision());
            }
          }
          InventorySourceAttachment attachment =
              sourceAttachments.findByInventoryIdAndFindingId(inventoryId, findingId).orElse(null);
          if (attachment == null) {
            sourceAttachments.saveAndFlush(
                InventorySourceAttachment.pending(
                    inventoryId,
                    findingId,
                    request.sourceRevision(),
                    idempotencyKey,
                    requestHash));
          } else if (!requestHash.equals(attachment.getRequestSha256())) {
            throw InventoryException.conflict("Finding source request is already bound");
          }
        });
    InventoryDependencyGateway.SourceAsset remote =
        dependencies.createSourceAsset(idempotencyKey, remoteRequest);
    if (!RENTAL_ITEM_STATUSES.contains(remote.asset().status())) {
      throw InventoryException.dependency("Asset-service returned an unknown rental-item status");
    }
    if (request.origin() == FindingOrigin.ADDED_NEW && !"FREE".equals(remote.asset().status())) {
      throw InventoryException.dependency("A newly created inventory cabin must be FREE");
    }
    InventoryFinding attached =
        transactions.execute(
            status -> {
              InventoryFinding finding = requireFinding(inventoryId, findingId);
              if (finding.getAssetId() == null) {
                if (finding.getMutationState()
                    != dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING) {
                  throw InventoryException.conflict("Finding source-create state is inconsistent");
                }
                finding.attachCreatedAsset(
                    remote.asset().assetId(),
                    remote.asset().version(),
                    remote.asset().warehouseId(),
                    remote.asset().status(),
                    remote.asset().tenantSnapshot());
                findings.saveAndFlush(finding);
              }
              InventorySourceAttachment attachment =
                  sourceAttachments
                      .findByInventoryIdAndFindingId(inventoryId, findingId)
                      .orElseThrow(() ->
                          new IllegalStateException("Finding source attachment is missing"));
              if (!requestHash.equals(attachment.getRequestSha256())) {
                throw InventoryException.conflict("Finding source request changed");
              }
              attachment.attach(
                  remote.asset().assetId(), remote.asset().version(), canonicalHash(remote));
              sourceAttachments.saveAndFlush(attachment);
              InventorySession currentSession = requireSession(inventoryId);
              boolean expectedActive = currentSession.getLifecycle() == SessionLifecycle.ACTIVE;
              if (finding.isOwnerProofActive() != expectedActive) {
                throw new IllegalStateException("Finding owner proof lifecycle is inconsistent");
              }
              membershipMovements.saveAndFlush(
                  InventoryMembershipMovement.arrived(
                      currentSession.getId(),
                      idempotencyKey,
                      remote.asset().assetId(),
                      finding.getOrigin(),
                      finding.getDisplayCanonicalNumber(),
                      null,
                      currentSession.getWarehouseId(),
                      remote.asset().status(),
                      remote.asset().tenantSnapshot(),
                      OffsetDateTime.now(ZoneOffset.UTC)));
              appendOwnerProof(finding, currentSession.getWarehouseId(), actor(jwt));
              return finding;
            });
    return findingView(attached);
  }

  public FindingView saveInspection(
      Jwt jwt, UUID inventoryId, UUID findingId, SaveInspectionRequest request) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.editScope(jwt)), SessionLifecycle.ACTIVE);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryFinding finding = requireFinding(inventoryId, findingId);
    expectRevision(finding.getRevision(), request.expectedFindingRevision());
    InventoryDependencyGateway.Validation currentValidation = validateAssets(List.of(finding));
    ValidatedFinding currentTruth =
        validatedFindings(session, List.of(finding), currentValidation, true).getFirst();
    ConflictView blockingConflict =
        blockingInspectionConflict(session.getWarehouseId(), currentTruth.currentSnapshot());
    if (blockingConflict != null) {
      throw InventoryException.conflict(
          "Нельзя сохранить осмотр: " + blockingConflict.message());
    }
    validateObservation(request.passportObservation(), false);
    validateObservation(request.equipmentObservation(), true);
    validatePlanSelection(request.inspection(), request.planSelection());
    SaveInspectionRequest canonicalRequest =
        currentMediaRequest(session, finding, request);
    validateCoverMedia(canonicalRequest);
    if (request.inspection() == InspectionState.READY
        && currentTruth.currentSnapshot() != null
        && "AFTER_RENT".equals(currentTruth.currentSnapshot().status())
        && canonicalRequest.media().isEmpty()) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Для приёмки бытовки после аренды загрузите хотя бы одну фотографию");
    }
    validateReadyMedia(
        findingId,
        session.getWarehouseId(),
        canonicalRequest.media(),
        canonicalRequest.coverMediaId());
    InventoryDependencyGateway.FrozenPlan plan = null;
    if (request.inspection() == InspectionState.WORK_STAGED) {
      plan =
          dependencies.freezePlan(
              UUID.randomUUID(), freezeRequest(session, finding, canonicalRequest));
    } else if (request.inspection() != InspectionState.READY) {
      throw new IllegalArgumentException("Inspection must be READY or WORK_STAGED");
    }
    if (plan != null
        && (!session.getWarehouseId().equals(plan.warehouseId())
            || !session.getId().equals(plan.inventoryId())
            || !finding.getId().equals(plan.findingId())
            || Math.addExact(finding.getRevision(), 1) != plan.sourceRevision()
            || !frozenPlanFingerprint.sha256(plan.snapshot()).equals(plan.fingerprint()))) {
      throw InventoryException.dependency("Maintenance-service returned mismatched frozen plan");
    }
    InventoryDependencyGateway.FrozenPlan frozenPlan = plan;
    InventoryFinding saved =
        transactions.execute(
            status -> {
              InventorySession lockedSession = requireActive(inventoryId);
              requireCabinOrFurnitureReviewStage(lockedSession);
              InventoryFinding lockedFinding = requireFinding(inventoryId, findingId);
              expectRevision(lockedSession.getRevision(), request.expectedSessionRevision());
              expectRevision(lockedFinding.getRevision(), request.expectedFindingRevision());
              CurrentItemSnapshot current = currentTruth.currentSnapshot();
              lockedFinding.refreshCurrentAsset(
                  current == null ? null : current.assetVersion(),
                  current == null ? null : current.warehouseId(),
                  current == null ? null : current.status(),
                  current == null ? null : current.tenantSnapshot(),
                  current == null ? null : current.displayCanonicalNumber(),
                  current == null ? null : json(current.passportSnapshot()),
                  current == null ? null : json(current.contentsSnapshot()),
                  current == null ? null : json(current.repairsSnapshot()),
                  current == null ? ReconciliationState.MISSING : ReconciliationState.MATCHED);
              lockedFinding.saveInspection(
                  request.inspection(),
                  lockedFinding.getReconciliation(),
                  request.passportObservation().presence(),
                  json(request.passportObservation().value()),
                  request.equipmentObservation().presence(),
                  json(request.equipmentObservation().value()),
                  frozenPlan == null ? null : frozenPlan.fingerprint(),
                  request.comment(),
                  canonicalRequest.coverMediaId(),
                  actorJson(jwt));
              InventoryFinding result = findings.saveAndFlush(lockedFinding);
              persistMedia(result, canonicalRequest.media());
              if (frozenPlan != null) {
                persistPlan(result, canonicalRequest.planSelection(), frozenPlan);
              }
              invalidateFurnitureReviewAfterCabinChange(lockedSession);
              invalidateFinalPlan(lockedSession);
              appendFindingFacts(
                  result, lockedSession, actor(jwt), "inventory.finding.inspection-saved.v1");
              return result;
            });
    return findingView(saved, currentTruth);
  }

  public FindingView resolveConflict(
      Jwt jwt, UUID inventoryId, UUID findingId, ResolveConflictRequest request) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.editScope(jwt)), SessionLifecycle.ACTIVE);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryFinding finding = requireFinding(inventoryId, findingId);
    expectRevision(finding.getRevision(), request.expectedFindingRevision());
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
      throw InventoryException.conflict(
          "Сначала проверьте бытовку, затем разрешайте изменения реестра");
    }
    InventoryDependencyGateway.Validation validation = validateAssets(List.of(finding));
    ValidatedFinding currentTruth =
        validatedFindings(session, List.of(finding), validation).getFirst();
    if (currentTruth.conflicts().isEmpty()) {
      throw InventoryException.conflict("Актуального конфликта реестра больше нет");
    }
    if (request.strategy() == ConflictResolutionStrategy.ACCEPT_REGISTRY
        && currentTruth.currentSnapshot() == null) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Отсутствующую в реестре бытовку нельзя принять как новую базовую версию");
    }
    if (request.strategy() == ConflictResolutionStrategy.KEEP_INSPECTION
        && (request.reason() == null || request.reason().isBlank())) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Укажите причину сохранения данных осмотра");
    }
    CurrentItemSnapshot current = currentTruth.currentSnapshot();
    String fingerprint = semanticFingerprint(current);
    InventoryFinding saved =
        transactions.execute(
            ignored -> {
              InventorySession lockedSession = requireActive(inventoryId);
              InventoryFinding lockedFinding = requireFinding(inventoryId, findingId);
              expectRevision(lockedSession.getRevision(), request.expectedSessionRevision());
              expectRevision(lockedFinding.getRevision(), request.expectedFindingRevision());
              lockedFinding.refreshCurrentAsset(
                  current == null ? null : current.assetVersion(),
                  current == null ? null : current.warehouseId(),
                  current == null ? null : current.status(),
                  current == null ? null : current.tenantSnapshot(),
                  current == null ? null : current.displayCanonicalNumber(),
                  current == null ? null : json(current.passportSnapshot()),
                  current == null ? null : json(current.contentsSnapshot()),
                  current == null ? null : json(current.repairsSnapshot()),
                  current == null ? ReconciliationState.MISSING : ReconciliationState.CONFLICT);
              lockedFinding.resolveConflict(
                  request.strategy(), fingerprint, request.reason(), actorJson(jwt));
              InventoryFinding result = findings.saveAndFlush(lockedFinding);
              invalidateFurnitureReviewAfterCabinChange(lockedSession);
              invalidateFinalPlan(lockedSession);
              appendFindingFacts(
                  result,
                  lockedSession,
                  actor(jwt),
                  "inventory.finding.inspection-saved.v1");
              return result;
            });
    return findingView(saved, validatedFinding(session, saved, current));
  }

  public RegistryReviewView registryReview(
      Jwt jwt, UUID inventoryId, RegistryReviewRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    RevisionState revisions =
        revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryDependencyGateway.Validation validation = validateAssets(revisions.findings());
    List<ValidatedFinding> validated =
        validatedFindings(session, revisions.findings(), validation);

    // Do not return remote truth for a local revision vector that changed while dependencies were
    // being read. The caller refreshes and repeats this read-only review instead.
    revisionState(
        requireActive(inventoryId),
        request.expectedSessionRevision(),
        request.findingRevisions());
    return new RegistryReviewView(
        inventoryId,
        session.getRevision(),
        revisions.expectations(),
        validation.validatedAt(),
        validated);
  }

  public FurnitureReviewView startFurnitureReview(
      Jwt jwt,
      UUID inventoryId,
      UUID idempotencyKey,
      StartFurnitureReviewRequest request) {
    requireScopedSession(inventoryId, authorizer.editScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.furniture-review.start",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        FurnitureReviewView.class,
        () -> doStartFurnitureReview(jwt, inventoryId, request));
  }

  private FurnitureReviewView doStartFurnitureReview(
      Jwt jwt, UUID inventoryId, StartFurnitureReviewRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    requireFurnitureReviewCanStart(session);
    RevisionState revisions =
        revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryDependencyGateway.Validation validation = validateAssets(revisions.findings());
    List<ValidatedFinding> validated =
        validatedFindings(session, revisions.findings(), validation);
    List<CompletionRisk> risks = risks(session, revisions.findings(), validated);
    validateFurnitureStageTransition(risks, request.acknowledgeIncomplete());
    InventoryDependencyGateway.FurnitureSnapshot snapshot =
        dependencies.furnitureSnapshot(
            session.getWarehouseId(), furnitureAssetIds(session, revisions.findings()));
    validateFurnitureSnapshot(session, revisions.findings(), snapshot);
    String snapshotBody = write(snapshot);
    InventorySession saved =
        transactions.execute(
            status -> {
              InventorySession locked = requireActive(inventoryId);
              requireFurnitureReviewCanStart(locked);
              RevisionState lockedRevisions =
                  revisionState(
                      locked, request.expectedSessionRevision(), request.findingRevisions());
              if (lockedRevisions.findings().size() != revisions.findings().size()) {
                throw InventoryException.conflict("Inventory finding set changed before furniture review");
              }
              locked.beginFurnitureReview(snapshot.snapshotSha256(), snapshotBody);
              return sessions.saveAndFlush(locked);
            });
    return furnitureReviewView(saved);
  }

  public FurnitureReviewView furnitureReview(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    requireFurnitureReviewStage(session);
    return furnitureReviewView(session);
  }

  public FurnitureReviewView saveFurnitureReview(
      Jwt jwt, UUID inventoryId, SaveFurnitureReviewRequest request) {
    InventorySession session =
        requireLifecycle(
            requireScopedSession(inventoryId, authorizer.editScope(jwt)), SessionLifecycle.ACTIVE);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    requireFurnitureReviewStage(session);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    FurnitureReviewSubmission submission =
        validateFurnitureReviewSubmission(session, snapshot, request);
    InventorySession saved =
        transactions.execute(
            status -> {
              InventorySession locked = requireActive(inventoryId);
              requireFurnitureReviewStage(locked);
              expectRevision(locked.getRevision(), request.expectedSessionRevision());
              if (!request.assetSnapshotSha256().equals(locked.getFurnitureAssetSnapshotSha256())) {
                throw InventoryException.conflict("Furniture review snapshot is stale");
              }
              List<InventoryFinding> active =
                  findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId);
              Map<UUID, InventoryFinding> byId =
                  active.stream()
                      .collect(
                          java.util.stream.Collectors.toMap(
                              InventoryFinding::getId,
                              java.util.function.Function.identity(),
                              (left, right) -> {
                                throw new IllegalStateException("Duplicate inventory finding");
                              },
                              LinkedHashMap::new));
              for (Map.Entry<UUID, Long> expected : submission.findingRevisions().entrySet()) {
                InventoryFinding finding = byId.get(expected.getKey());
                if (finding == null || finding.getRevision() != expected.getValue()) {
                  throw InventoryException.conflict("Furniture review finding revision is stale");
                }
              }
              for (Map.Entry<UUID, FurnitureObservation> observation :
                  submission.equipmentObservationByFinding().entrySet()) {
                InventoryFinding finding = byId.get(observation.getKey());
                if (finding == null) {
                  throw InventoryException.conflict("Furniture review finding is no longer active");
                }
                finding.saveFurnitureObservation(
                    observation.getValue().presence(),
                    observation.getValue().body(),
                    actorJson(jwt));
              }
              findings.saveAllAndFlush(
                  submission.equipmentObservationByFinding().keySet().stream()
                      .map(byId::get)
                      .toList());
              locked.confirmFurnitureReview(
                  request.assetSnapshotSha256(),
                  submission.reviewSha256(),
                  submission.reviewBody(),
                  actorJson(jwt));
              return sessions.saveAndFlush(locked);
            });
    return furnitureReviewView(saved);
  }

  /** Returns the warehouse-local calendar used to derive final maintenance planning. */
  public PlanningSettingsView planningSettings(Jwt jwt, UUID warehouseId) {
    authorizer.requireManage(jwt, warehouseId);
    return planningSettingsView(warehouseId, planningSpecification(warehouseId));
  }

  public PlanningSettingsView updatePlanningSettings(
      Jwt jwt, UUID warehouseId, PlanningSettingsUpdateRequest request) {
    authorizer.requireManage(jwt, warehouseId);
    PlanningSpecification requested = planningSpecification(request);
    return transactions.execute(
        ignored -> {
          InventoryPlanningSettings existing = planningSettings.findById(warehouseId).orElse(null);
          long revision = existing == null ? 0 : existing.getRevision();
          expectRevision(revision, request.expectedSettingsRevision());
          InventoryPlanningSettings saved;
          if (existing == null) {
            saved =
                planningSettings.saveAndFlush(
                    InventoryPlanningSettings.create(
                        warehouseId,
                        requested.movementDailyCapacity(),
                        requested.repairDailyCapacity(),
                        weekdaysJson(requested.workingWeekdays()),
                        holidaysJson(requested.holidays())));
          } else {
            existing.replace(
                requested.movementDailyCapacity(),
                requested.repairDailyCapacity(),
                weekdaysJson(requested.workingWeekdays()),
                holidaysJson(requested.holidays()));
            saved = planningSettings.saveAndFlush(existing);
          }
          sessions
              .findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE)
              .ifPresent(this::invalidateFinalPlan);
          return planningSettingsView(warehouseId, planningSpecification(saved));
        });
  }

  public FinalPlanView prepareFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PrepareFinalPlanRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.final-plan.prepare",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        FinalPlanView.class,
        () -> doPrepareFinalPlan(jwt, inventoryId, idempotencyKey, request));
  }

  private FinalPlanView doPrepareFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PrepareFinalPlanRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    PlanningSpecification settings = planningSpecification(session.getWarehouseId());
    expectRevision(settings.revision(), request.expectedSettingsRevision());
    InventoryFinalPlan existing = finalPlans.findById(inventoryId).orElse(null);
    long finalPlanVersion = existing == null ? 1 : Math.addExact(existing.getFinalPlanVersion(), 1);
    List<FinalPlanDraft> draft =
        scheduleFinalPlan(
            session,
            settings,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            initialFinalPlanDrafts(session));
    String sha256 =
        finalPlanSha256(
            session,
            settings,
            finalPlanVersion,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            draft);
    List<FinalPlanDraft> withCandidates =
        attachPreflightCandidates(
            draft,
            dependencies.preflightReconciliation(
                idempotencyKey,
                reconciliationPreflightRequest(
                    session, finalPlanVersion, sha256, draft)),
            inventoryId,
            finalPlanVersion,
            sha256);
    return persistFinalPlanVersion(
        inventoryId,
        request.expectedSessionRevision(),
        settings.revision(),
        existing == null ? 0 : existing.getFinalPlanVersion(),
        finalPlanVersion,
        sha256,
        request.movementScheduleMode(),
        request.repairScheduleMode(),
        withCandidates);
  }

  public FinalPlanView finalPlan(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    InventoryFinalPlan plan =
        finalPlans
            .findById(inventoryId)
            .orElseThrow(() -> InventoryException.notFound("Inventory final plan is not prepared"));
    return finalPlanView(
        session,
        plan,
        finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
            inventoryId, plan.getFinalPlanVersion()));
  }

  public FinalPlanView updateFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, FinalPlanUpdateRequest request) {
    requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "session.final-plan.update",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        FinalPlanView.class,
        () -> doUpdateFinalPlan(jwt, inventoryId, idempotencyKey, request));
  }

  private FinalPlanView doUpdateFinalPlan(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, FinalPlanUpdateRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryFinalPlan current =
        finalPlans
            .findById(inventoryId)
            .orElseThrow(() -> InventoryException.conflict("Inventory final plan is not prepared"));
    if (current.getState() != FinalPlanState.DRAFT) {
      throw InventoryException.conflict("Inventory final plan must be prepared again");
    }
    expectRevision(current.getFinalPlanVersion(), request.expectedFinalPlanVersion());
    PlanningSpecification settings = planningSpecification(session.getWarehouseId());
    long nextVersion = Math.addExact(current.getFinalPlanVersion(), 1);
    List<FinalPlanDraft> draft =
        scheduleFinalPlan(
            session,
            settings,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            updateFinalPlanDrafts(session, request.entries()));
    String sha256 =
        finalPlanSha256(
            session,
            settings,
            nextVersion,
            request.movementScheduleMode(),
            request.repairScheduleMode(),
            draft);
    List<FinalPlanDraft> withCandidates =
        attachPreflightCandidates(
            draft,
            dependencies.preflightReconciliation(
                idempotencyKey,
                reconciliationPreflightRequest(session, nextVersion, sha256, draft)),
            inventoryId,
            nextVersion,
            sha256);
    return persistFinalPlanVersion(
        inventoryId,
        request.expectedSessionRevision(),
        settings.revision(),
        current.getFinalPlanVersion(),
        nextVersion,
        sha256,
        request.movementScheduleMode(),
        request.repairScheduleMode(),
        withCandidates);
  }

  private PlanningSpecification planningSpecification(UUID warehouseId) {
    return planningSettings
        .findById(warehouseId)
        .map(this::planningSpecification)
        .orElseGet(
            () ->
                new PlanningSpecification(
                    0,
                    6,
                    6,
                    List.of(
                        DayOfWeek.MONDAY,
                        DayOfWeek.TUESDAY,
                        DayOfWeek.WEDNESDAY,
                        DayOfWeek.THURSDAY,
                        DayOfWeek.FRIDAY),
                    List.of()));
  }

  private PlanningSpecification planningSpecification(InventoryPlanningSettings value) {
    return new PlanningSpecification(
        value.getRevision(),
        value.getMovementDailyCapacity(),
        value.getRepairDailyCapacity(),
        parseWorkingWeekdays(read(value.getWorkingWeekdays())),
        parseHolidays(read(value.getHolidays())));
  }

  private PlanningSpecification planningSpecification(PlanningSettingsUpdateRequest request) {
    if (request == null) throw new IllegalArgumentException("Planning settings are required");
    return new PlanningSpecification(
        request.expectedSettingsRevision(),
        request.movementDailyCapacity(),
        request.repairDailyCapacity(),
        parseWorkingWeekdays(request.workingWeekdays()),
        parseHolidays(request.holidays()));
  }

  private PlanningSettingsView planningSettingsView(
      UUID warehouseId, PlanningSpecification specification) {
    return new PlanningSettingsView(
        warehouseId,
        specification.revision(),
        specification.movementDailyCapacity(),
        specification.repairDailyCapacity(),
        specification.workingWeekdays().stream().map(Enum::name).toList(),
        specification.holidays());
  }

  private List<DayOfWeek> parseWorkingWeekdays(List<String> values) {
    if (values == null || values.isEmpty() || values.size() > 7) {
      throw new IllegalArgumentException("Working weekdays must contain one to seven days");
    }
    EnumSet<DayOfWeek> result = EnumSet.noneOf(DayOfWeek.class);
    for (String value : values) {
      try {
        DayOfWeek day = DayOfWeek.valueOf(value.trim().toUpperCase(Locale.ROOT));
        if (!result.add(day)) {
          throw new IllegalArgumentException("Working weekdays must not contain duplicates");
        }
      } catch (NullPointerException | IllegalArgumentException exception) {
        if (exception.getMessage() != null
            && exception.getMessage().contains("duplicates")) {
          throw exception;
        }
        throw new IllegalArgumentException("Working weekday is invalid", exception);
      }
    }
    return List.copyOf(result);
  }

  private List<DayOfWeek> parseWorkingWeekdays(JsonNode values) {
    if (values == null || !values.isArray()) {
      throw new IllegalStateException("Stored planning weekdays are invalid");
    }
    List<String> text = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) {
        throw new IllegalStateException("Stored planning weekdays are invalid");
      }
      text.add(value.asText());
    }
    return parseWorkingWeekdays(text);
  }

  private List<LocalDate> parseHolidays(List<LocalDate> values) {
    if (values == null || values.size() > 3660) {
      throw new IllegalArgumentException("Holidays must contain at most 3660 dates");
    }
    Set<LocalDate> unique = new LinkedHashSet<>();
    for (LocalDate value : values) {
      if (value == null || !unique.add(value)) {
        throw new IllegalArgumentException("Holidays must contain unique ISO dates");
      }
    }
    return unique.stream().sorted().toList();
  }

  private List<LocalDate> parseHolidays(JsonNode values) {
    if (values == null || !values.isArray()) {
      throw new IllegalStateException("Stored planning holidays are invalid");
    }
    List<LocalDate> dates = new ArrayList<>();
    for (JsonNode value : values) {
      if (!value.isTextual()) {
        throw new IllegalStateException("Stored planning holidays are invalid");
      }
      try {
        dates.add(LocalDate.parse(value.asText()));
      } catch (RuntimeException exception) {
        throw new IllegalStateException("Stored planning holidays are invalid", exception);
      }
    }
    return parseHolidays(dates);
  }

  private String weekdaysJson(List<DayOfWeek> values) {
    return write(values.stream().map(Enum::name).toList());
  }

  private String holidaysJson(List<LocalDate> values) {
    return write(values.stream().map(LocalDate::toString).toList());
  }

  private List<FinalPlanDraft> initialFinalPlanDrafts(InventorySession session) {
    List<FinalPlanDraft> draft = new ArrayList<>();
    for (InventoryFinding finding :
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId())) {
      draft.add(finalPlanDraft(finding, null, null, null, null));
    }
    draft.sort(
        Comparator.comparing((FinalPlanDraft value) -> value.hasWork() ? 0 : 1)
            .thenComparing(value -> value.priority() == null ? Integer.MAX_VALUE : value.priority())
            .thenComparing(value -> value.finding().getDisplayCanonicalNumber())
            .thenComparing(value -> value.finding().getId()));
    List<FinalPlanDraft> ordered = new ArrayList<>();
    for (int index = 0; index < draft.size(); index++) {
      ordered.add(draft.get(index).withOrder(index));
    }
    return List.copyOf(ordered);
  }

  private List<FinalPlanDraft> updateFinalPlanDrafts(
      InventorySession session, List<FinalPlanEntryUpdate> submitted) {
    if (submitted == null) throw new IllegalArgumentException("Final-plan entries are required");
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    if (submitted.size() != active.size()) {
      throw InventoryException.conflict("Final plan must contain every active finding exactly once");
    }
    Map<UUID, InventoryFinding> byId = new LinkedHashMap<>();
    active.forEach(value -> byId.put(value.getId(), value));
    List<FinalPlanEntryUpdate> ordered = new ArrayList<>(submitted);
    ordered.sort(Comparator.comparingInt(FinalPlanEntryUpdate::order));
    Set<UUID> ids = new HashSet<>();
    List<FinalPlanDraft> result = new ArrayList<>();
    for (int index = 0; index < ordered.size(); index++) {
      FinalPlanEntryUpdate input = ordered.get(index);
      if (input == null
          || input.order() != index
          || !ids.add(input.findingId())) {
        throw new IllegalArgumentException("Final-plan order must be contiguous without duplicates");
      }
      InventoryFinding finding = byId.get(input.findingId());
      if (finding == null || finding.getRevision() != input.expectedFindingRevision()) {
        throw InventoryException.conflict("Final-plan finding revision is stale");
      }
      if (input.movementToRepair() == null) {
        throw new IllegalArgumentException("Final-plan movement choice is required");
      }
      FinalPlanDraft draft =
          finalPlanDraft(
              finding,
              input.priority(),
              input.movementToRepair(),
              input.movementScheduledDate(),
              input.repairScheduledDate());
      if (!draft.hasWork()) {
        if (input.priority() != null
            || input.movementToRepair()
            || input.movementScheduledDate() != null
            || input.repairScheduledDate() != null
            || input.reconciliationDecision() != null) {
          throw new IllegalArgumentException("No-work final-plan entry has operational data");
        }
      } else {
        validateFinalPlanPriority(input.priority());
        draft = draft.withDecision(decisionJson(input.reconciliationDecision()));
      }
      result.add(draft.withOrder(index));
    }
    if (ids.size() != byId.size()) {
      throw InventoryException.conflict("Final plan does not match the active finding set");
    }
    return List.copyOf(result);
  }

  private FinalPlanDraft finalPlanDraft(
      InventoryFinding finding,
      Integer requestedPriority,
      Boolean requestedMovementToRepair,
      LocalDate requestedMovementDate,
      LocalDate requestedRepairDate) {
    if (finding.getInspection() != InspectionState.WORK_STAGED) {
      return FinalPlanDraft.noWork(finding);
    }
    FindingPlanSnapshot snapshot =
        activePlanSnapshot(finding)
            .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
    if (!snapshot.getFingerprint().equals(finding.getMaintenancePlanFingerprintSha256())
        || !snapshot.getFingerprint().equals(frozenPlanFingerprint.sha256(read(snapshot.getSourceSnapshot())))) {
      throw InventoryException.conflict("Frozen maintenance plan is stale");
    }
    Integer defaultPriority = read(snapshot.getSourceSnapshot()).path("priority").asInt(-1);
    validateFinalPlanPriority(defaultPriority);
    int priority = requestedPriority == null ? defaultPriority : requestedPriority;
    boolean movement =
        requestedMovementToRepair == null ? snapshot.isMovementToRepair() : requestedMovementToRepair;
    return new FinalPlanDraft(
        finding,
        snapshot,
        true,
        "AFTER_RENT".equals(finding.getCurrentStatus())
            ? FinalPlanTargetKind.ESTIMATE
            : FinalPlanTargetKind.REPAIR,
        0,
        priority,
        movement,
        requestedMovementDate,
        requestedRepairDate,
        "[]",
        null);
  }

  private void validateFinalPlanPriority(Integer value) {
    if (value == null || value < 1 || value > 5) {
      throw new IllegalArgumentException("Final-plan priority must be between 1 and 5");
    }
  }

  private List<FinalPlanDraft> scheduleFinalPlan(
      InventorySession session,
      PlanningSpecification settings,
      FinalPlanScheduleMode movementMode,
      FinalPlanScheduleMode repairMode,
      List<FinalPlanDraft> input) {
    if (movementMode == null || repairMode == null) {
      throw new IllegalArgumentException("Final-plan schedule modes are required");
    }
    LocalDate planningDate = planningDate(session);
    List<FinalPlanDraft> result = new ArrayList<>(input);
    Map<LocalDate, Integer> movementUsed = new LinkedHashMap<>();
    for (int index = 0; index < result.size(); index++) {
      FinalPlanDraft entry = result.get(index);
      if (!entry.hasWork()) continue;
      if (!entry.movementToRepair()) {
        if (entry.movementScheduledDate() != null) {
          throw new IllegalArgumentException("Movement date requires movement to repair");
        }
        continue;
      }
      LocalDate date;
      if (movementMode == FinalPlanScheduleMode.AUTO) {
        if (entry.movementScheduledDate() != null) {
          throw new IllegalArgumentException("AUTO movement schedule cannot include dates");
        }
        date =
            reserveAutomaticDate(
                planningDate, settings, movementUsed, settings.movementDailyCapacity());
      } else {
        date = entry.movementScheduledDate();
        reserveManualDate(
            date,
            planningDate,
            settings,
            movementUsed,
            settings.movementDailyCapacity(),
            "movement");
      }
      result.set(index, entry.withDates(date, entry.repairScheduledDate()));
    }
    Map<LocalDate, Integer> repairUsed = new LinkedHashMap<>();
    for (int index = 0; index < result.size(); index++) {
      FinalPlanDraft entry = result.get(index);
      if (!entry.hasWork()) continue;
      LocalDate earliest =
          entry.movementToRepair() && entry.movementScheduledDate().isAfter(planningDate)
              ? entry.movementScheduledDate()
              : planningDate;
      LocalDate date;
      if (repairMode == FinalPlanScheduleMode.AUTO) {
        if (entry.repairScheduledDate() != null) {
          throw new IllegalArgumentException("AUTO repair schedule cannot include dates");
        }
        date = reserveAutomaticDate(earliest, settings, repairUsed, settings.repairDailyCapacity());
      } else {
        date = entry.repairScheduledDate();
        reserveManualDate(
            date,
            planningDate,
            settings,
            repairUsed,
            settings.repairDailyCapacity(),
            "repair");
        if (entry.movementToRepair() && date.isBefore(entry.movementScheduledDate())) {
          throw new IllegalArgumentException("Repair date cannot precede movement date");
        }
      }
      result.set(index, entry.withDates(entry.movementScheduledDate(), date));
    }
    return List.copyOf(result);
  }

  private LocalDate planningDate(InventorySession session) {
    LocalDate warehouseToday = LocalDate.now(ZoneId.of(session.getWarehouseTimeZone()));
    return session.getBusinessDate().isAfter(warehouseToday)
        ? session.getBusinessDate()
        : warehouseToday;
  }

  private LocalDate reserveAutomaticDate(
      LocalDate earliest,
      PlanningSpecification settings,
      Map<LocalDate, Integer> used,
      int capacity) {
    LocalDate date = earliest;
    for (int offset = 0; offset < 20_000; offset++, date = date.plusDays(1)) {
      if (!workingDay(date, settings) || used.getOrDefault(date, 0) >= capacity) continue;
      used.merge(date, 1, Integer::sum);
      return date;
    }
    throw new IllegalStateException("Planning calendar has no available date");
  }

  private void reserveManualDate(
      LocalDate date,
      LocalDate planningDate,
      PlanningSpecification settings,
      Map<LocalDate, Integer> used,
      int capacity,
      String stream) {
    if (date != null && date.isBefore(planningDate)) {
      throw new IllegalArgumentException(
          "Manual " + stream + " date cannot precede the current warehouse planning date");
    }
    if (date == null || !workingDay(date, settings)) {
      throw new IllegalArgumentException("Manual " + stream + " date must be a working day");
    }
    int next = Math.addExact(used.getOrDefault(date, 0), 1);
    if (next > capacity) {
      throw new IllegalArgumentException("Manual " + stream + " schedule exceeds daily capacity");
    }
    used.put(date, next);
  }

  private boolean workingDay(LocalDate date, PlanningSpecification settings) {
    return settings.workingWeekdays().contains(date.getDayOfWeek())
        && !settings.holidays().contains(date);
  }

  private String finalPlanSha256(
      InventorySession session,
      PlanningSpecification settings,
      long finalPlanVersion,
      FinalPlanScheduleMode movementMode,
      FinalPlanScheduleMode repairMode,
      List<FinalPlanDraft> entries) {
    List<Map<String, Object>> canonicalEntries = new ArrayList<>();
    for (FinalPlanDraft entry : entries) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("findingId", entry.finding().getId());
      value.put("findingRevision", entry.finding().getRevision());
      value.put("assetId", entry.finding().getAssetId());
      value.put("assetVersion", entry.finding().getAssetVersion());
      value.put("planFingerprintSha256", entry.planFingerprintSha256());
      value.put("hasWork", entry.hasWork());
      value.put("targetKind", entry.targetKind());
      value.put("order", entry.order());
      value.put("priority", entry.priority());
      value.put("movementToRepair", entry.movementToRepair());
      value.put("movementScheduledDate", entry.movementScheduledDate());
      value.put("repairScheduledDate", entry.repairScheduledDate());
      value.put(
          "reconciliationDecision",
          entry.reconciliationDecision() == null ? null : convert(read(entry.reconciliationDecision()), Object.class));
      canonicalEntries.add(value);
    }
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("inventoryId", session.getId());
    payload.put("sessionRevision", session.getRevision());
    payload.put("planningSettingsRevision", settings.revision());
    payload.put("finalPlanVersion", finalPlanVersion);
    payload.put("movementScheduleMode", movementMode);
    payload.put("repairScheduleMode", repairMode);
    payload.put("entries", canonicalEntries);
    return canonicalHash(payload);
  }

  private ObjectNode reconciliationPreflightRequest(
      InventorySession session,
      long finalPlanVersion,
      String finalPlanSha256,
      List<FinalPlanDraft> entries) {
    ObjectNode request = mapper.createObjectNode();
    request.put("inventoryId", session.getId().toString());
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("finalPlanVersion", finalPlanVersion);
    request.put("finalPlanSha256", finalPlanSha256);
    ArrayNode findingsBody = request.putArray("findings");
    for (FinalPlanDraft entry : entries) {
      if (!entry.hasWork()) continue;
      InventoryFinding finding = entry.finding();
      FindingPlanSnapshot snapshot = entry.snapshot();
      if (finding.getAssetId() == null || finding.getAssetVersion() == null || snapshot == null) {
        throw InventoryException.conflict("Work final-plan finding has incomplete asset evidence");
      }
      ObjectNode body = findingsBody.addObject();
      body.put("findingId", finding.getId().toString());
      body.put("findingRevision", finding.getRevision());
      body.put("assetId", finding.getAssetId().toString());
      body.put("assetVersion", finding.getAssetVersion());
      body.put("planFingerprintSha256", entry.planFingerprintSha256());
      body.put("snapshotSchemaVersion", snapshot.getSnapshotSchemaVersion());
      body.put("priority", entry.priority());
      body.put("movementToRepair", entry.movementToRepair());
      if (entry.movementScheduledDate() == null) body.putNull("movementScheduledDate");
      else body.put("movementScheduledDate", entry.movementScheduledDate().toString());
      body.put("repairScheduledDate", entry.repairScheduledDate().toString());
      body.set("snapshot", read(snapshot.getSourceSnapshot()));
      body.set("media", frozenPlanMedia(snapshot));
    }
    return request;
  }

  private ArrayNode frozenPlanMedia(FindingPlanSnapshot snapshot) {
    JsonNode source = read(snapshot.getSourceSnapshot());
    ArrayNode output = mapper.createArrayNode();
    Set<String> seen = new LinkedHashSet<>();
    appendFrozenPlanMedia(output, seen, source.path("mediaReferences"));
    JsonNode lines = source.path("lines");
    if (!lines.isArray()) {
      throw InventoryException.conflict("Frozen maintenance plan lines are invalid");
    }
    for (JsonNode line : lines) {
      if (!line.isObject()) {
        throw InventoryException.conflict("Frozen maintenance plan lines are invalid");
      }
      appendFrozenPlanMedia(output, seen, line.path("mediaReferences"));
    }
    return output;
  }

  private void appendFrozenPlanMedia(ArrayNode output, Set<String> seen, JsonNode references) {
    if (references.isMissingNode() || references.isNull()) return;
    if (!references.isArray()) {
      throw InventoryException.conflict("Frozen maintenance plan media is invalid");
    }
    for (JsonNode reference : references) {
      UUID mediaId = requiredUuid(reference, "mediaId", "frozen plan media id");
      long generation = reference.path("generation").asLong(-1);
      if (generation < 0) {
        throw InventoryException.conflict("Frozen maintenance plan media generation is invalid");
      }
      String key = mediaId + ":" + generation;
      if (!seen.add(key)) continue;
      ObjectNode normalized = output.addObject();
      normalized.put("mediaId", mediaId.toString());
      normalized.put("generation", generation);
    }
  }

  private List<FinalPlanDraft> attachPreflightCandidates(
      List<FinalPlanDraft> entries,
      JsonNode response,
      UUID inventoryId,
      long finalPlanVersion,
      String finalPlanSha256) {
    if (!inventoryId.equals(requiredUuid(response, "inventoryId", "reconciliation inventory id"))
        || response.path("finalPlanVersion").asLong(-1) != finalPlanVersion
        || !finalPlanSha256.equals(response.path("finalPlanSha256").asText())
        || !response.path("findings").isArray()) {
      throw InventoryException.dependency("Maintenance reconciliation preflight is malformed");
    }
    Map<UUID, String> candidatesByFinding = new LinkedHashMap<>();
    for (JsonNode finding : response.path("findings")) {
      UUID findingId = requiredUuid(finding, "findingId", "reconciliation finding id");
      JsonNode candidates = finding.path("candidates");
      if (!candidates.isArray() || candidatesByFinding.put(findingId, canonicalWrite(candidates)) != null) {
        throw InventoryException.dependency("Maintenance reconciliation candidates are malformed");
      }
      for (JsonNode candidate : candidates) validateFinalPlanCandidate(candidate);
    }
    Set<UUID> workIds = new LinkedHashSet<>();
    for (FinalPlanDraft entry : entries) {
      if (entry.hasWork()) workIds.add(entry.finding().getId());
    }
    if (!candidatesByFinding.keySet().equals(workIds)) {
      throw InventoryException.dependency("Maintenance reconciliation candidate set is incomplete");
    }
    List<FinalPlanDraft> result = new ArrayList<>();
    for (FinalPlanDraft entry : entries) {
      FinalPlanDraft withCandidates =
          entry.hasWork()
              ? entry.withCandidates(candidatesByFinding.get(entry.finding().getId()))
              : entry;
      validateFinalPlanDecision(withCandidates);
      result.add(withCandidates);
    }
    return List.copyOf(result);
  }

  private void validateFinalPlanCandidate(JsonNode candidate) {
    try {
      if (!candidate.isObject()) throw new IllegalArgumentException();
      FinalPlanTargetKind kind =
          FinalPlanTargetKind.valueOf(candidate.path("targetKind").asText());
      UUID targetId = requiredUuid(candidate, "targetId", "reconciliation target id");
      UUID estimateId = nullableUuid(candidate, "estimateId", "reconciliation estimate id");
      UUID repairId = nullableUuid(candidate, "repairId", "reconciliation repair id");
      if ((kind == FinalPlanTargetKind.ESTIMATE && (!targetId.equals(estimateId) || repairId != null))
          || (kind == FinalPlanTargetKind.REPAIR && (!targetId.equals(repairId) || estimateId != null))
          || candidate.path("version").asLong(-1) < 0
          || candidate.path("state").asText().isBlank()
          || !candidate.path("started").isBoolean()
          || !candidate.path("active").isBoolean()) {
        throw new IllegalArgumentException();
      }
      if (candidate.hasNonNull("priority")) validateFinalPlanPriority(candidate.path("priority").asInt(-1));
      if (candidate.hasNonNull("planFingerprintSha256")
          && !candidate.path("planFingerprintSha256").asText().matches("^[0-9a-f]{64}$")) {
        throw new IllegalArgumentException();
      }
      JsonNode summary = candidate.path("planSummary");
      if (!summary.isObject()
          || summary.path("workLineCount").asInt(-1) < 0
          || summary.path("materialLineCount").asInt(-1) < 0
          || summary.path("grandTotalMinor").asLong(-1) < 0) {
        throw new IllegalArgumentException();
      }
    } catch (RuntimeException exception) {
      throw InventoryException.dependency("Maintenance reconciliation candidate is malformed");
    }
  }

  private String decisionJson(FinalPlanReconciliationDecision decision) {
    if (decision == null) return null;
    if (decision.strategy() == null) {
      throw new IllegalArgumentException("Final-plan reconciliation strategy is required");
    }
    if (decision.strategy() == FinalPlanReconciliationStrategy.CREATE) {
      if (decision.selectedTargetKind() != null || decision.selectedTargetId() != null) {
        throw new IllegalArgumentException("CREATE reconciliation cannot select an existing target");
      }
    } else if (decision.selectedTargetKind() == null || decision.selectedTargetId() == null) {
      throw new IllegalArgumentException("MERGE and REPLACE reconciliation require a target");
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("strategy", decision.strategy().name());
    if (decision.selectedTargetKind() == null) result.putNull("selectedTargetKind");
    else result.put("selectedTargetKind", decision.selectedTargetKind().name());
    if (decision.selectedTargetId() == null) result.putNull("selectedTargetId");
    else result.put("selectedTargetId", decision.selectedTargetId().toString());
    return canonicalWrite(result);
  }

  private void validateFinalPlanDecision(FinalPlanDraft entry) {
    if (!entry.hasWork()) return;
    validateFinalPlanDecision(entry, finalPlanCandidateSet(entry));
  }

  private void validateFinalPlanDecision(FinalPlanDraft entry, FinalPlanCandidateSet candidates) {
    if (entry.reconciliationDecision() == null) return;
    FinalPlanReconciliationDecision decision = finalPlanDecision(read(entry.reconciliationDecision()));
    if (candidates.active().size() > 1) {
      throw InventoryException.conflict(
          "Maintenance reconciliation has multiple active candidates");
    }
    if (decision.strategy() == FinalPlanReconciliationStrategy.CREATE) {
      if (!candidates.active().isEmpty()) {
        throw InventoryException.conflict(
            "CREATE reconciliation is only valid with no active maintenance candidate");
      }
      return;
    }
    FinalPlanCandidateView selected =
        candidates.all().stream()
            .filter(
                candidate ->
                    candidate.targetKind() == decision.selectedTargetKind()
                        && candidate.targetId().equals(decision.selectedTargetId()))
            .findFirst()
            .orElse(null);
    if (selected == null) {
      throw InventoryException.conflict("Reconciliation selection is no longer a maintenance candidate");
    }
    if (!selected.active()) {
      throw InventoryException.conflict(
          "Reconciliation selection must be an active maintenance candidate");
    }
    if (selected.started() && decision.strategy() != FinalPlanReconciliationStrategy.MERGE) {
      throw InventoryException.conflict("Started maintenance candidate requires MERGE reconciliation");
    }
  }

  private FinalPlanCandidateSet finalPlanCandidateSet(FinalPlanDraft entry) {
    List<FinalPlanCandidateView> all = finalPlanCandidates(read(entry.collisionCandidates()));
    return new FinalPlanCandidateSet(all, all.stream().filter(FinalPlanCandidateView::active).toList());
  }

  private FinalPlanView persistFinalPlanVersion(
      UUID inventoryId,
      long expectedSessionRevision,
      long expectedSettingsRevision,
      long expectedPriorVersion,
      long finalPlanVersion,
      String sha256,
      FinalPlanScheduleMode movementMode,
      FinalPlanScheduleMode repairMode,
      List<FinalPlanDraft> entries) {
    return transactions.execute(
        ignored -> {
          InventorySession locked =
              sessions
                  .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.ACTIVE)
                  .orElseThrow(() -> InventoryException.conflict("Inventory session is not active"));
          expectRevision(locked.getRevision(), expectedSessionRevision);
          PlanningSpecification lockedSettings = planningSpecification(locked.getWarehouseId());
          expectRevision(lockedSettings.revision(), expectedSettingsRevision);
          InventoryFinalPlan head = finalPlans.findByInventoryIdForUpdate(inventoryId).orElse(null);
          long prior = head == null ? 0 : head.getFinalPlanVersion();
          expectRevision(prior, expectedPriorVersion);
          if (finalPlanVersion != Math.addExact(prior, 1)) {
            throw InventoryException.conflict("Inventory final-plan version changed");
          }
          InventoryFinalPlan saved;
          if (head == null) {
            saved =
                InventoryFinalPlan.create(
                    inventoryId,
                    locked.getRevision(),
                    lockedSettings.revision(),
                    sha256,
                    movementMode,
                    repairMode);
          } else {
            head.nextVersion(
                locked.getRevision(),
                lockedSettings.revision(),
                sha256,
                movementMode,
                repairMode);
            saved = head;
          }
          saved = finalPlans.saveAndFlush(saved);
          List<InventoryFinalPlanEntry> persisted =
              entries.stream()
                  .map(entry -> finalPlanEntry(inventoryId, finalPlanVersion, entry))
                  .toList();
          finalPlanEntries.saveAllAndFlush(persisted);
          return finalPlanView(locked, saved, persisted);
        });
  }

  private InventoryFinalPlanEntry finalPlanEntry(
      UUID inventoryId, long finalPlanVersion, FinalPlanDraft entry) {
    return new InventoryFinalPlanEntry(
        inventoryId,
        finalPlanVersion,
        entry.finding().getId(),
        entry.finding().getRevision(),
        entry.finding().getAssetId(),
        entry.finding().getAssetVersion(),
        entry.planFingerprintSha256(),
        entry.hasWork(),
        entry.targetKind(),
        entry.order(),
        entry.priority(),
        entry.movementToRepair(),
        entry.movementScheduledDate(),
        entry.repairScheduledDate(),
        entry.collisionCandidates(),
        entry.reconciliationDecision());
  }

  private FinalPlanView finalPlanView(
      InventorySession session, InventoryFinalPlan plan, List<InventoryFinalPlanEntry> entries) {
    return new FinalPlanView(
        session.getId(),
        session.getRevision(),
        plan.getFinalPlanVersion(),
        plan.getFinalPlanSha256(),
        plan.getPlanningSettingsRevision(),
        plan.getState(),
        plan.getMovementScheduleMode(),
        plan.getRepairScheduleMode(),
        entries.stream().map(this::finalPlanEntryView).toList());
  }

  private FinalPlanEntryView finalPlanEntryView(InventoryFinalPlanEntry entry) {
    return new FinalPlanEntryView(
        entry.getFindingId(),
        entry.getFindingRevision(),
        entry.getPlanFingerprintSha256(),
        entry.isHasWork(),
        entry.getTargetKind(),
        entry.getOrder(),
        entry.getPriority(),
        entry.isMovementToRepair(),
        entry.getMovementScheduledDate(),
        entry.getRepairScheduledDate(),
        finalPlanCandidates(read(entry.getCollisionCandidates())),
        entry.getReconciliationDecision() == null
            ? null
            : finalPlanDecision(read(entry.getReconciliationDecision())));
  }

  private List<FinalPlanCandidateView> finalPlanCandidates(JsonNode candidates) {
    if (!candidates.isArray()) {
      throw new IllegalStateException("Stored final-plan candidates are invalid");
    }
    List<FinalPlanCandidateView> result = new ArrayList<>();
    for (JsonNode candidate : candidates) {
      validateFinalPlanCandidate(candidate);
      JsonNode summary = candidate.path("planSummary");
      result.add(
          new FinalPlanCandidateView(
              FinalPlanTargetKind.valueOf(candidate.path("targetKind").asText()),
              requiredUuid(candidate, "targetId", "stored reconciliation target id"),
              nullableUuid(candidate, "estimateId", "stored reconciliation estimate id"),
              nullableUuid(candidate, "repairId", "stored reconciliation repair id"),
              candidate.path("version").asLong(),
              candidate.path("state").asText(),
              candidate.path("started").asBoolean(),
              candidate.path("active").asBoolean(),
              candidate.hasNonNull("priority") ? candidate.path("priority").asInt() : null,
              candidate.hasNonNull("sourceParty") ? candidate.path("sourceParty").asText() : null,
              candidate.hasNonNull("planFingerprintSha256")
                  ? candidate.path("planFingerprintSha256").asText()
                  : null,
              new FinalPlanSummaryView(
                  summary.path("workLineCount").asInt(),
                  summary.path("materialLineCount").asInt(),
                  summary.path("grandTotalMinor").asLong())));
    }
    return List.copyOf(result);
  }

  private FinalPlanReconciliationDecision finalPlanDecision(JsonNode decision) {
    try {
      if (!decision.isObject()) throw new IllegalArgumentException();
      FinalPlanReconciliationStrategy strategy =
          FinalPlanReconciliationStrategy.valueOf(decision.path("strategy").asText());
      FinalPlanTargetKind targetKind =
          decision.hasNonNull("selectedTargetKind")
              ? FinalPlanTargetKind.valueOf(decision.path("selectedTargetKind").asText())
              : null;
      UUID targetId = nullableUuid(decision, "selectedTargetId", "stored reconciliation target id");
      return new FinalPlanReconciliationDecision(strategy, targetKind, targetId);
    } catch (RuntimeException exception) {
      throw new IllegalStateException("Stored final-plan reconciliation decision is invalid", exception);
    }
  }

  private UUID nullableUuid(JsonNode node, String field, String label) {
    if (!node.hasNonNull(field)) return null;
    return requiredUuid(node, field, label);
  }

  private boolean explicitNull(JsonNode node, String field) {
    return node.has(field) && node.get(field).isNull();
  }

  private void invalidateFinalPlan(InventorySession session) {
    finalPlans
        .findById(session.getId())
        .ifPresent(
            plan -> {
              plan.markStale();
              finalPlans.saveAndFlush(plan);
            });
  }

  /**
   * Completion is bound to the exact server-owned plan version, not merely to a client-side list
   * of finding revisions. Re-running maintenance preflight makes a changed candidate set a
   * conflict before any terminal session mutation is possible.
   */
  private CompletionFinalPlan requireCompletionFinalPlan(
      InventorySession session,
      long expectedVersion,
      String expectedSha256,
      boolean refreshMaintenanceCandidates) {
    InventoryFinalPlan plan =
        finalPlans
            .findById(session.getId())
            .orElseThrow(() -> InventoryException.conflict("Inventory final plan is not prepared"));
    if (plan.getState() != FinalPlanState.DRAFT
        || plan.getFinalPlanVersion() != expectedVersion
        || !plan.getFinalPlanSha256().equals(expectedSha256)
        || plan.getBasisSessionRevision() != session.getRevision()) {
      throw InventoryException.conflict("Inventory final plan is stale");
    }
    PlanningSpecification settings = planningSpecification(session.getWarehouseId());
    if (settings.revision() != plan.getPlanningSettingsRevision()) {
      throw InventoryException.conflict("Inventory final plan calendar is stale");
    }
    List<InventoryFinalPlanEntry> entries =
        finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
            session.getId(), plan.getFinalPlanVersion());
    requireCurrentFinalPlanDates(session, entries);
    List<FinalPlanDraft> draft = completionFinalPlanDrafts(session, plan, entries);
    String calculated =
        finalPlanSha256(
            session,
            settings,
            plan.getFinalPlanVersion(),
            plan.getMovementScheduleMode(),
            plan.getRepairScheduleMode(),
            draft);
    if (!plan.getFinalPlanSha256().equals(calculated)) {
      throw InventoryException.conflict("Inventory final plan evidence is stale");
    }
    if (refreshMaintenanceCandidates) {
      UUID key =
          UUID.nameUUIDFromBytes(
              ("rwms:inventory:preflight:"
                      + session.getId()
                      + ":"
                      + plan.getFinalPlanVersion()
                      + ":"
                      + plan.getFinalPlanSha256())
                  .getBytes(StandardCharsets.UTF_8));
      List<FinalPlanDraft> fresh =
          attachPreflightCandidates(
              draft,
              dependencies.preflightReconciliation(
                  key,
                  reconciliationPreflightRequest(
                      session, plan.getFinalPlanVersion(), plan.getFinalPlanSha256(), draft)),
              session.getId(),
              plan.getFinalPlanVersion(),
              plan.getFinalPlanSha256());
      for (int index = 0; index < draft.size(); index++) {
        if (!canonicalJsonTreeHash(read(draft.get(index).collisionCandidates()))
            .equals(canonicalJsonTreeHash(read(fresh.get(index).collisionCandidates())))) {
          throw InventoryException.conflict("Maintenance reconciliation candidates changed");
        }
      }
    }
    for (FinalPlanDraft entry : draft) {
      FinalPlanCandidateSet candidates = finalPlanCandidateSet(entry);
      if (candidates.active().size() > 1) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_FINAL_PLAN_AMBIGUOUS_ACTIVE_CANDIDATES",
            "Resolve multiple active maintenance candidates before completion");
      }
      if (entry.hasWork()
          && !candidates.active().isEmpty()
          && entry.reconciliationDecision() == null) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_FINAL_PLAN_INCOMPLETE",
            "Choose a reconciliation strategy for every active maintenance candidate");
      }
      validateFinalPlanDecision(entry, candidates);
    }
    return new CompletionFinalPlan(plan, entries, draft);
  }

  private void requireCurrentFinalPlanDates(
      InventorySession session, List<InventoryFinalPlanEntry> entries) {
    LocalDate planningDate = planningDate(session);
    boolean hasPastOperationalDate =
        entries.stream()
            .filter(InventoryFinalPlanEntry::isHasWork)
            .anyMatch(
                entry ->
                    (entry.getMovementScheduledDate() != null
                            && entry.getMovementScheduledDate().isBefore(planningDate))
                        || (entry.getRepairScheduledDate() != null
                            && entry.getRepairScheduledDate().isBefore(planningDate)));
    if (hasPastOperationalDate) {
      throw InventoryException.conflict(
          "Inventory final plan has operational dates before the current warehouse planning date; prepare it again");
    }
  }

  private List<FinalPlanDraft> completionFinalPlanDrafts(
      InventorySession session, InventoryFinalPlan plan, List<InventoryFinalPlanEntry> entries) {
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    if (active.size() != entries.size()) {
      throw InventoryException.conflict("Inventory final plan no longer covers the active population");
    }
    Map<UUID, InventoryFinalPlanEntry> byId = new LinkedHashMap<>();
    for (int index = 0; index < entries.size(); index++) {
      InventoryFinalPlanEntry entry = entries.get(index);
      if (entry.getOrder() != index || byId.put(entry.getFindingId(), entry) != null) {
        throw InventoryException.conflict("Inventory final-plan ordering is invalid");
      }
    }
    List<FinalPlanDraft> result = new ArrayList<>();
    for (InventoryFinding finding : active) {
      InventoryFinalPlanEntry entry = byId.get(finding.getId());
      if (entry == null || entry.getFindingRevision() != finding.getRevision()) {
        throw InventoryException.conflict("Inventory final plan finding evidence is stale");
      }
      if (!entry.isHasWork()) {
        if (finding.getInspection() == InspectionState.WORK_STAGED) {
          throw InventoryException.conflict("Inventory final plan omitted staged maintenance work");
        }
        result.add(FinalPlanDraft.noWork(finding).withOrder(entry.getOrder()));
        continue;
      }
      if (finding.getInspection() != InspectionState.WORK_STAGED
          || finding.getAssetId() == null
          || finding.getAssetVersion() == null
          || !finding.getAssetId().equals(entry.getAssetId())
          || !finding.getAssetVersion().equals(entry.getAssetVersion())) {
        throw InventoryException.conflict("Inventory final-plan work evidence is stale");
      }
      FindingPlanSnapshot snapshot =
          activePlanSnapshot(finding)
              .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
      FinalPlanTargetKind expectedTarget =
          "AFTER_RENT".equals(finding.getCurrentStatus())
              ? FinalPlanTargetKind.ESTIMATE
              : FinalPlanTargetKind.REPAIR;
      if (!snapshot.getFingerprint().equals(entry.getPlanFingerprintSha256())
          || entry.getTargetKind() != expectedTarget
          || entry.getPriority() == null
          || entry.getRepairScheduledDate() == null
          || entry.isMovementToRepair() != (entry.getMovementScheduledDate() != null)) {
        throw InventoryException.conflict("Inventory final-plan work fields are invalid");
      }
      result.add(
          new FinalPlanDraft(
              finding,
              snapshot,
              true,
              entry.getTargetKind(),
              entry.getOrder(),
              entry.getPriority(),
              entry.isMovementToRepair(),
              entry.getMovementScheduledDate(),
              entry.getRepairScheduledDate(),
              entry.getCollisionCandidates(),
              entry.getReconciliationDecision()));
    }
    result.sort(Comparator.comparingInt(FinalPlanDraft::order));
    return List.copyOf(result);
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
    RevisionState revisions =
        revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    CompletionFinalPlan finalPlan =
        requireCompletionFinalPlan(
            session,
            request.finalPlanVersion(),
            request.finalPlanSha256(),
            true);
    InventoryDependencyGateway.Validation validation = validateAssets(revisions.findings());
    List<ValidatedFinding> validatedFindings =
        validatedFindings(session, revisions.findings(), validation);
    List<CompletionRisk> risks = risks(session, revisions.findings(), validatedFindings);
    FurnitureCompletionFact furniture = requireConfirmedFurnitureReview(session);
    if (risks.stream().noneMatch(risk -> "CONFLICT".equals(risk.code()))) {
      requireCurrentFurnitureReviewSnapshot(session, revisions.findings());
    }
    FrozenStatistics statistics =
        calculateStatistics(session, revisions.findings(), validatedFindings);
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
        status -> persistValidation(session, validation, acknowledgement, response));
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
    RevisionState revisions =
        revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    CompletionFinalPlan finalPlan =
        requireCompletionFinalPlan(
            session,
            request.finalPlanVersion(),
            request.finalPlanSha256(),
            true);
    InventoryDependencyGateway.Validation fresh = validateAssets(revisions.findings());
    ValidationRecord preview = validationRecord(inventoryId);
    if (!request.acknowledgementSha256().equals(preview.acknowledgement())
        || !request.validationSha256().equals(preview.validation())
        || preview.sessionRevision() != session.getRevision()
        || preview.preview().finalPlanVersion() != finalPlan.plan().getFinalPlanVersion()
        || !finalPlan.plan().getFinalPlanSha256().equals(preview.preview().finalPlanSha256())
        || !fresh.validationDigest().equals(preview.validation())
        || !semanticValidationDigest(preview.validationTruth().path("assets"))
            .equals(preview.validation())) {
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACKNOWLEDGEMENT_STALE",
          "Inventory preview acknowledgement is stale");
    }
    List<ValidatedFinding> validatedFindings =
        validatedFindings(session, revisions.findings(), fresh);
    List<CompletionRisk> risks = risks(session, revisions.findings(), validatedFindings);
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
    FurnitureCompletionFact furniture =
        requireConfirmedCurrentFurnitureReview(session, revisions.findings());
    FrozenStatistics previewStatistics = preview.preview().statistics();
    InventorySession completed =
        transactions.execute(
            status -> {
              InventorySession locked =
                  sessions
                      .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.ACTIVE)
                      .orElseThrow(
                          () -> InventoryException.conflict("Inventory session is not active"));
              RevisionState lockedRevisions =
                  revisionState(locked, request.expectedSessionRevision(), request.findingRevisions());
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
              completionFinalPlanDrafts(locked, lockedFinalPlan, lockedPlanEntries);
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
              persistValidation(
                  locked, fresh, request.acknowledgementSha256(), completionSnapshot);
              requireLockedFurnitureReview(locked, furniture);
              locked.complete(
                  fresh.validationDigest(),
                  request.acknowledgementSha256(),
                  fresh.validatedAt(),
                  actorJson(jwt));
              InventorySession result = sessions.saveAndFlush(locked);
              lockedFinalPlan.complete();
              finalPlans.saveAndFlush(lockedFinalPlan);
              FrozenStatistics finalStatistics =
                  calculateStatistics(result, lockedRevisions.findings(), validatedFindings);
              persistStatistics(result, finalStatistics);
              createFurnitureLossIntents(result);
              createFurnitureReconciliationIntent(result);
              createPublicationIntents(result, lockedFinalPlan, lockedPlanEntries, actor(jwt));
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
    dispatchCompletionEffectsAfterCommit(completed.getId());
    return sessionView(requireSession(completed.getId()));
  }

  private void dispatchCompletionEffectsAfterCommit(UUID inventoryId) {
    Runnable dispatch =
        () -> {
          dispatchFurnitureLosses(inventoryId);
          dispatchFurnitureReconciliation(inventoryId);
          dispatchReadyPublications(inventoryId);
        };
    if (!TransactionSynchronizationManager.isSynchronizationActive()) {
      dispatch.run();
      return;
    }
    TransactionSynchronizationManager.registerSynchronization(
        new TransactionSynchronization() {
          @Override
          public void afterCommit() {
            dispatch.run();
          }
        });
  }

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
              for (InventoryFinding finding : cancelledFindings) {
                finding.transitionOwnerProof(false);
              }
              findings.saveAllAndFlush(cancelledFindings);
              for (InventoryFinding finding : cancelledFindings) {
                appendOwnerProof(finding, result.getWarehouseId(), actor(jwt));
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
    return sessionView(cancelled);
  }

  public PublicationBatch publish(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PublishFindingsRequest request) {
    requireLifecycle(
        requireScopedSession(inventoryId, authorizer.manageScope(jwt)), SessionLifecycle.COMPLETED);
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "publication.publish",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "request", request),
        HttpStatus.OK.value(),
        PublicationBatch.class,
        () -> doPublish(jwt, inventoryId, idempotencyKey, request));
  }

  private PublicationBatch doPublish(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, PublishFindingsRequest request) {
    InventorySession session = requireCompleted(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    dependencies.warehouseAdmission(
        session.getWarehouseId(),
        InventoryDependencyGateway.WarehouseOperationDirection.OUTGOING);
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    List<InventoryPublicationIntent> selected =
        selectPublications(inventoryId, idempotencyKey, request);
    for (InventoryPublicationIntent intent : selected) {
      dispatchPublication(actor(jwt), session, intent, idempotencyKey, false, null);
    }
    return publicationBatch(inventoryId);
  }

  public PublicationView retryPublication(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      RetryPublicationRequest request) {
    requireLifecycle(
        requireScopedSession(inventoryId, authorizer.manageScope(jwt)), SessionLifecycle.COMPLETED);
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "publication.retry",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "findingId", findingId, "request", request),
        HttpStatus.OK.value(),
        PublicationView.class,
        () -> doRetryPublication(jwt, inventoryId, findingId, idempotencyKey, request));
  }

  private PublicationView doRetryPublication(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      RetryPublicationRequest request) {
    InventorySession session = requireCompleted(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    dependencies.warehouseAdmission(
        session.getWarehouseId(),
        InventoryDependencyGateway.WarehouseOperationDirection.OUTGOING);
    InventoryPublicationIntent intent = requirePublication(inventoryId, findingId);
    expectRevision(intent.getRevision(), request.expectedPublicationRevision());
    if (intent.getState() == PublicationState.BLOCKED
        && (request.reconcileReason() == null || request.currentPreconditionSha256() == null)) {
      throw new IllegalArgumentException("Blocked publication retry requires reconciliation proof");
    }
    if (intent.getState() != PublicationState.BLOCKED
        && (request.reconcileReason() != null
            || request.currentPreconditionSha256() != null)) {
      throw new IllegalArgumentException(
          "Reconciliation proof is accepted only for a blocked publication");
    }
    dispatchPublication(
        actor(jwt),
        session,
        intent,
        idempotencyKey,
        intent.getState() == PublicationState.BLOCKED,
        request.currentPreconditionSha256());
    return publicationView(requirePublication(inventoryId, findingId));
  }

  public PublicationView closePublication(
      Jwt jwt,
      UUID inventoryId,
      UUID findingId,
      UUID idempotencyKey,
      ClosePublicationRequest request) {
    requireLifecycle(
        requireScopedSession(inventoryId, authorizer.manageScope(jwt)), SessionLifecycle.COMPLETED);
    return idempotency.execute(
        authorizer.subjectId(jwt),
        "publication.close",
        idempotencyKey,
        Map.of("inventoryId", inventoryId, "findingId", findingId, "request", request),
        HttpStatus.OK.value(),
        PublicationView.class,
        () -> doClosePublication(jwt, inventoryId, findingId, request));
  }

  private PublicationView doClosePublication(
      Jwt jwt, UUID inventoryId, UUID findingId, ClosePublicationRequest request) {
    InventorySession session = requireCompleted(inventoryId);
    authorizer.requireManage(jwt, session.getWarehouseId());
    InventoryPublicationIntent result =
        transactions.execute(
            status -> {
              InventoryPublicationIntent intent = requirePublication(inventoryId, findingId);
              expectRevision(intent.getRevision(), request.expectedPublicationRevision());
              intent.close(request.reason(), actorJson(jwt));
              InventoryPublicationIntent saved = publications.saveAndFlush(intent);
              appendPublication(
                  saved, session, "inventory.publication.closed-blocked.v1", actor(jwt));
              return saved;
            });
    return publicationView(result);
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
    authorizer.requireRead(jwt, warehouseId);
    Specification<InventorySession> filter =
        sessionFilter(
            warehouseId,
            SessionLifecycle.COMPLETED,
            businessDateFrom,
            businessDateTo,
            startedFrom,
            startedTo,
            terminalFrom,
            terminalTo);
    Page<InventorySession> result =
        sessions.findAll(filter, PageRequest.of(page, size, statisticsSort(sort)));
    List<SessionStatistics> content =
        result.getContent().stream()
            .map(
                value ->
                    new SessionStatistics(
                        value.getId(),
                        value.getWarehouseId(),
                        value.getBusinessDate(),
                        value.getStartedAt(),
                        value.getCompletedAt(),
                        readStatistics(value.getId())))
            .toList();
    return new PageResponse<>(
        content,
        new PageMetadata(page, size, result.getTotalElements(), result.getTotalPages()));
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
    authorizer.requireRead(jwt, warehouseId);
    List<UUID> ids =
        sessions
            .findAll(
                sessionFilter(
                    warehouseId,
                    SessionLifecycle.COMPLETED,
                    businessDateFrom,
                    businessDateTo,
                    startedFrom,
                    startedTo,
                    terminalFrom,
                    terminalTo))
            .stream()
            .map(InventorySession::getId)
            .toList();
    FrozenStatistics total = summarizeStatistics(ids);
    return new StatisticsSummary(ids.size(), total);
  }

  private List<InventoryDependencyGateway.CaptureMember> copyCapture(
      InventoryDependencyGateway.Capture capture) {
    if (capture.totalCount() > Integer.MAX_VALUE) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Inventory capture is too large");
    }
    List<InventoryDependencyGateway.CaptureMember> result = new ArrayList<>();
    Set<UUID> assetIds = new HashSet<>();
    Set<String> matchKeys = new HashSet<>();
    String cursor = null;
    do {
      InventoryDependencyGateway.CapturePage page =
          dependencies.readCapture(capture.captureId(), cursor, 200);
      if (!capture.captureId().equals(page.captureId())
          || !capture.operationId().equals(page.operationId())
          || capture.technicalAttempt() != page.technicalAttempt()
          || !capture.warehouseId().equals(page.warehouseId())
          || !capture.membershipDigest().equals(page.membershipDigest())
          || capture.totalCount() != page.totalCount()) {
        throw InventoryException.dependency("Asset capture changed while being copied");
      }
      for (InventoryDependencyGateway.CaptureMember member : page.content()) {
        if (member.sequence() != result.size()
            || !capture.warehouseId().equals(member.warehouseId())
            || member.version() < 0
            || !CAPTURE_STATUSES.contains(member.status())
            || member.displayCanonicalNumber() == null
            || member.displayCanonicalNumber().isBlank()
            || member.identityMatchKey() == null
            || member.identityMatchKey().isBlank()
            || member.passportSnapshot() == null
            || !member.passportSnapshot().isObject()
            || member.contentsSnapshot() == null
            || (!member.contentsSnapshot().isObject() && !member.contentsSnapshot().isArray())
            || !assetIds.add(member.assetId())
            || !matchKeys.add(member.identityMatchKey())) {
          throw InventoryException.dependency("Asset capture order or uniqueness is invalid");
        }
        result.add(member);
      }
      if (page.nextCursor() != null && page.nextCursor().equals(cursor)) {
        throw InventoryException.dependency("Asset capture cursor did not advance");
      }
      cursor = page.nextCursor();
    } while (cursor != null);
    if (result.size() != capture.totalCount()) {
      throw InventoryException.dependency("Asset capture count is incomplete");
    }
    List<Map<String, Object>> digestMembers = new ArrayList<>(result.size());
    for (InventoryDependencyGateway.CaptureMember member : result) {
      Map<String, Object> digestMember = new LinkedHashMap<>();
      digestMember.put("sequence", member.sequence());
      digestMember.put("assetId", member.assetId());
      digestMember.put("version", member.version());
      digestMember.put("warehouseId", member.warehouseId());
      digestMember.put("status", member.status());
      digestMember.put("displayCanonicalNumber", member.displayCanonicalNumber());
      digestMember.put("identityMatchKey", member.identityMatchKey());
      digestMember.put("passportSnapshot", convert(member.passportSnapshot(), Map.class));
      digestMember.put("contentsSnapshot", member.contentsSnapshot());
      digestMembers.add(digestMember);
    }
    if (!canonicalHash(digestMembers).equals(capture.membershipDigest())) {
      throw InventoryException.dependency("Asset capture digest does not match copied members");
    }
    return List.copyOf(result);
  }

  private void copyExpectedPopulation(
      InventorySession session,
      List<InventoryDependencyGateway.CaptureMember> members,
      String actorJson,
      OpaqueActorReference actor) {
    for (InventoryDependencyGateway.CaptureMember member : members) {
      UUID expectedId = UUID.randomUUID();
      InventoryFinding finding =
          findings.save(
              InventoryFinding.expected(
                  session.getId(),
                  expectedId,
                  member.assetId(),
                  member.version(),
                  member.warehouseId(),
                  member.status(),
                  tenantSnapshot(member.passportSnapshot()),
                  member.displayCanonicalNumber(),
                  member.identityMatchKey(),
                  actorJson));
      expectedItems.save(
          new InventoryExpectedItem(
              expectedId,
              session.getId(),
              finding.getId(),
              Math.toIntExact(member.sequence()),
              member.assetId(),
              member.version(),
              member.status(),
              member.displayCanonicalNumber(),
              member.identityMatchKey(),
              json(member.passportSnapshot()),
              json(member.contentsSnapshot())));
      appendFindingFacts(finding, session, actor, "inventory.finding.added.v1");
    }
  }

  void appendFindingFacts(
      InventoryFinding finding,
      InventorySession session,
      OpaqueActorReference actor,
      String eventType) {
    appendFindingFacts(
        finding, session, actor, eventType, correlationId(), null);
  }

  private void appendFindingFacts(
      InventoryFinding finding,
      InventorySession session,
      OpaqueActorReference actor,
      String eventType,
      UUID correlationId,
      UUID causationId) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("inventoryId", finding.getInventoryId().toString());
    payload.put("findingId", finding.getId().toString());
    payload.put("warehouseId", session.getWarehouseId().toString());
    payload.put("sessionRevision", session.getRevision());
    payload.put("findingRevision", finding.getRevision());
    payload.put("origin", finding.getOrigin().name());
    payload.put("inspection", finding.getInspection().name());
    payload.put("reconciliation", finding.getReconciliation().name());
    if (finding.getAssetId() == null) payload.putNull("assetId");
    else payload.put("assetId", finding.getAssetId().toString());
    payload.put(
        "sourceAttached",
        finding.getMutationState()
            == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATED);
    payload.put("mediaCount", mediaCount(finding));
    if (finding.getMaintenancePlanFingerprintSha256() == null) {
      payload.putNull("planFingerprintSha256");
    } else {
      payload.put("planFingerprintSha256", finding.getMaintenancePlanFingerprintSha256());
    }
    InventoryEventStore.AppendResult appended;
    if ("inventory.finding.added.v1".equals(eventType)) {
      appended =
          events.initialize(
              "FINDING",
              finding.getId(),
              eventType,
              SESSION_TOPIC,
              payload,
              correlationId,
              causationId,
              actor);
    } else {
      appended =
          events.append(
              "FINDING",
              finding.getId(),
              events.currentVersion("FINDING", finding.getId()),
              eventType,
              SESSION_TOPIC,
              payload,
              correlationId,
              causationId,
              actor);
    }
    appendOwnerProof(finding, session.getWarehouseId(), actor, appended.aggregateVersion());
  }

  void appendOwnerProof(
      InventoryFinding finding, UUID warehouseId, OpaqueActorReference actor) {
    appendOwnerProof(
        finding, warehouseId, actor, events.currentVersion("FINDING", finding.getId()));
  }

  private void appendOwnerProof(
      InventoryFinding finding,
      UUID warehouseId,
      OpaqueActorReference actor,
      long expectedEventVersion) {
    appendOwnerProof(
        finding,
        warehouseId,
        actor,
        expectedEventVersion,
        correlationId(),
        null);
  }

  private void appendOwnerProof(
      InventoryFinding finding,
      UUID warehouseId,
      OpaqueActorReference actor,
      long expectedEventVersion,
      UUID correlationId,
      UUID causationId) {
    events.append(
        "FINDING",
        finding.getId(),
        expectedEventVersion,
        "inventory.finding.owner-proof.v1",
        SESSION_TOPIC,
        ownerProofPayload(finding, warehouseId),
        correlationId,
        causationId,
        actor);
  }

  ObjectNode ownerProofPayload(InventoryFinding finding, UUID warehouseId) {
    ObjectNode proof = mapper.createObjectNode();
    proof.put("ownerType", "INVENTORY_FINDING");
    proof.put("ownerId", finding.getId().toString());
    proof.put("warehouseId", warehouseId.toString());
    proof.put("ownerRevision", finding.getOwnerProofRevision());
    proof.put("active", finding.isOwnerProofActive());
    return proof;
  }

  private ObjectNode sessionPayload(
      InventorySession session, int findingCount, FrozenStatistics statistics) {
    ObjectNode payload = mapper.createObjectNode();
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
    else payload.set("statistics", mapper.valueToTree(statisticsWithoutLines(statistics)));
    return payload;
  }

  private ObjectNode sourceAssetRequest(
      UUID inventoryId,
      UUID findingId,
      InventorySession session,
      String display,
      JsonNode safePassport) {
    ObjectNode request = mapper.createObjectNode();
    request.put("inventoryId", inventoryId.toString());
    request.put("findingId", findingId.toString());
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("number", display);
    if (safePassport != null && safePassport.isObject()) {
      safePassport
          .properties()
          .forEach(
              entry -> {
                if (!Set.of("inventoryId", "findingId", "warehouseId", "number")
                    .contains(entry.getKey())) {
                  request.set(entry.getKey(), entry.getValue());
                }
              });
    }
    return request;
  }

  private ObjectNode freezeRequest(
      InventorySession session, InventoryFinding finding, SaveInspectionRequest request) {
    PlanSelection selection = request.planSelection();
    ObjectNode body = mapper.createObjectNode();
    body.put("warehouseId", session.getWarehouseId().toString());
    body.put("inventoryId", session.getId().toString());
    body.put("findingId", finding.getId().toString());
    body.put("sourceRevision", Math.addExact(finding.getRevision(), 1));
    body.put("mode", selection.mode());
    body.put("priority", selection.priority());
    body.put("movementToRepair", selection.movementToRepair());
    if (selection.logisticsPlanningMode() == null) {
      body.putNull("logisticsPlanningMode");
    } else {
      body.put("logisticsPlanningMode", selection.logisticsPlanningMode().name());
    }
    if (selection.logisticsScheduledDate() == null) {
      body.putNull("logisticsScheduledDate");
    } else {
      body.put(
          "logisticsScheduledDate", selection.logisticsScheduledDate().toString());
    }
    if (selection.coverMediaId() == null) body.putNull("coverMediaId");
    else body.put("coverMediaId", selection.coverMediaId().toString());
    body.set("lines", mapper.valueToTree(selection.lines()));
    body.set("plan", mapper.valueToTree(selection.stages()));
    ArrayNode media = body.putArray("mediaReferences");
    for (MediaReference reference : request.media()) {
      ObjectNode item = media.addObject();
      item.put("mediaId", reference.mediaId().toString());
      item.put("generation", reference.generation());
    }
    return body;
  }

  private void validateObservation(Observation observation, boolean equipment) {
    if (observation == null || observation.presence() == null) {
      throw new IllegalArgumentException("Observation presence is required");
    }
    JsonNode value = observation.value();
    switch (observation.presence()) {
      case ABSENT -> {
        if (value != null && !value.isNull()) {
          throw new IllegalArgumentException("ABSENT observation value must be null");
        }
      }
      case EXPLICIT_EMPTY -> {
        if (value == null
            || (equipment ? !value.isArray() || !value.isEmpty() : !value.isObject() || !value.isEmpty())) {
          throw new IllegalArgumentException("EXPLICIT_EMPTY observation has the wrong JSON shape");
        }
      }
      case PRESENT -> {
        if (value == null
            || (equipment ? !value.isArray() || value.isEmpty() : !value.isObject() || value.isEmpty())) {
          throw new IllegalArgumentException("PRESENT observation has the wrong JSON shape");
        }
        if (equipment) {
          for (JsonNode item : value) {
            if (!item.isObject()) {
              throw new IllegalArgumentException("Equipment observation items must be objects");
            }
          }
        }
      }
    }
  }

  private void validatePlanSelection(InspectionState inspection, PlanSelection selection) {
    if (inspection == InspectionState.READY) {
      if (selection != null) throw new IllegalArgumentException("READY inspection rejects a plan");
      return;
    }
    if (inspection != InspectionState.WORK_STAGED || selection == null) {
      throw new IllegalArgumentException("WORK_STAGED inspection requires a plan");
    }
    if (selection.lines().isEmpty()) {
      throw new IllegalArgumentException("Inventory plan requires at least one line");
    }
    if (selection.priority() == null || selection.priority() < 1 || selection.priority() > 5) {
      throw new IllegalArgumentException("Inventory plan priority must be between 1 and 5");
    }
    if (!selection.isLogisticsPlanningValid()) {
      throw new IllegalArgumentException("Inventory logistics planning is invalid");
    }
    boolean manualMode = "MANUAL".equals(selection.mode());
    for (PlanLineInput line : selection.lines()) {
      BigDecimal quantity = new BigDecimal(line.quantity());
      if (quantity.signum() <= 0) throw new IllegalArgumentException("Plan quantity must be positive");
      if ("CATALOG".equals(line.aggregationKind())) {
        if (line.catalogNodeId() == null
            || line.routingCatalogNodeId() != null
            || line.description() != null
            || line.type() != null
            || line.unit() != null
            || line.unitPriceMinor() != null
            || line.normativeMinutes() != null) {
          throw new IllegalArgumentException("CATALOG plan line evidence is invalid");
        }
      } else if (!manualMode
          || line.catalogNodeId() != null
          || line.routingCatalogNodeId() == null
          || line.description() == null
          || line.description().isBlank()
          || line.type() == null
          || line.unit() == null
          || line.unit().isBlank()
          || line.unitPriceMinor() == null
          || line.normativeMinutes() == null) {
        throw new IllegalArgumentException("MANUAL plan line evidence requires MANUAL mode");
      }
      if (line.groupComment() != null && line.groupComment().isBlank()) {
        throw new IllegalArgumentException("Plan group comment cannot be blank");
      }
    }
    if (manualMode && selection.stages().isEmpty()) {
      throw new IllegalArgumentException("MANUAL plan requires ordered stages");
    }
    for (int index = 0; index < selection.stages().size(); index++) {
      PlanStageSelection stage = selection.stages().get(index);
      if (!"REPAIR_WORK".equals(stage.kind())) {
        throw new IllegalArgumentException("Inventory plan stages can contain repair work only");
      }
      if (stage.order() != index) {
        throw new IllegalArgumentException("Plan stage order must be contiguous");
      }
    }
  }

  /**
   * A repeat inspection starts from an immutable historical snapshot. Media rotation keeps the
   * logical media ID but advances its READY generation, so a retained historical reference must
   * be rebased before a new finding and maintenance-source revision is frozen. New references
   * still have to name the exact current generation; only a reference proven to belong to the
   * active previous revision may advance implicitly.
   */
  private SaveInspectionRequest currentMediaRequest(
      InventorySession session, InventoryFinding finding, SaveInspectionRequest request) {
    Map<UUID, Long> retained = retainedMediaGenerations(finding);
    List<MediaReference> currentMedia =
        currentReadyMediaReferences(
            finding.getId(), session.getWarehouseId(), request.media(), retained);
    PlanSelection selection = request.planSelection();
    PlanSelection currentSelection = null;
    if (selection != null) {
      List<PlanLineInput> currentLines =
          selection.lines().stream()
              .map(
                  line ->
                      new PlanLineInput(
                          line.aggregationKind(),
                          line.catalogNodeId(),
                          line.routingCatalogNodeId(),
                          line.description(),
                          line.type(),
                          line.unit(),
                          line.quantity(),
                          line.unitPriceMinor(),
                          line.normativeMinutes(),
                          line.groupComment(),
                          currentReadyMediaReferences(
                              finding.getId(),
                              session.getWarehouseId(),
                              line.mediaReferences(),
                              retained)))
              .toList();
      currentSelection =
          new PlanSelection(
              selection.mode(),
              selection.priority(),
              selection.coverMediaId(),
              selection.movementToRepair(),
              selection.logisticsPlanningMode(),
              selection.logisticsScheduledDate(),
              currentLines,
              selection.stages());
    }
    return new SaveInspectionRequest(
        request.expectedSessionRevision(),
        request.expectedFindingRevision(),
        request.inspection(),
        request.comment(),
        request.passportObservation(),
        request.equipmentObservation(),
        currentMedia,
        request.coverMediaId(),
        currentSelection);
  }

  private Map<UUID, Long> retainedMediaGenerations(InventoryFinding finding) {
    Map<UUID, Long> retained = new LinkedHashMap<>();
    for (FindingMediaReference reference :
        mediaReferences.findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
            finding.getId(), finding.getRevision())) {
      putRetainedMediaGeneration(
          retained, reference.getMediaId(), reference.getGeneration());
    }
    planSnapshots
        .findByFindingIdAndFindingRevision(finding.getId(), finding.getRevision())
        .ifPresent(
            plan -> {
              JsonNode snapshot = read(plan.getSourceSnapshot());
              collectRetainedMediaGenerations(
                  snapshot.path("mediaReferences"), retained, "frozen plan media");
              JsonNode lines = snapshot.path("lines");
              if (!lines.isArray()) {
                throw new IllegalStateException("Persisted frozen plan lines are invalid");
              }
              for (JsonNode line : lines) {
                collectRetainedMediaGenerations(
                    line.path("mediaReferences"), retained, "frozen plan line media");
              }
            });
    return Map.copyOf(retained);
  }

  private void collectRetainedMediaGenerations(
      JsonNode references, Map<UUID, Long> retained, String name) {
    if (references.isMissingNode() || references.isNull()) return;
    if (!references.isArray() || references.size() > 100) {
      throw new IllegalStateException("Persisted " + name + " are invalid");
    }
    for (JsonNode reference : references) {
      if (!reference.isObject()) {
        throw new IllegalStateException("Persisted " + name + " are invalid");
      }
      UUID mediaId = requiredUuid(reference, "mediaId", name + " id");
      long generation = reference.path("generation").asLong(-1);
      if (generation < 0) {
        throw new IllegalStateException("Persisted " + name + " are invalid");
      }
      putRetainedMediaGeneration(retained, mediaId, generation);
    }
  }

  private void putRetainedMediaGeneration(
      Map<UUID, Long> retained, UUID mediaId, long generation) {
    Long previous = retained.putIfAbsent(mediaId, generation);
    if (previous != null && previous != generation) {
      throw new IllegalStateException(
          "Persisted inspection references multiple generations of one media object");
    }
  }

  private List<MediaReference> currentReadyMediaReferences(
      UUID findingId,
      UUID warehouseId,
      List<MediaReference> requested,
      Map<UUID, Long> retained) {
    Set<UUID> unique = new HashSet<>();
    List<MediaReference> current = new ArrayList<>(requested.size());
    for (MediaReference reference : requested) {
      if (!unique.add(reference.mediaId())) {
        throw new IllegalArgumentException(
            "Media references must contain only one generation per media object");
      }
      var fact =
          mediaFacts
              .findFirstByMediaIdAndOwnerTypeAndOwnerIdAndWarehouseIdOrderByAggregateVersionDesc(
                  reference.mediaId(), "INVENTORY_FINDING", findingId, warehouseId)
              .orElse(null);
      if (fact == null || !"READY".equals(fact.getMediaStatus())) {
        throw mediaNotReady();
      }
      if (reference.generation() != fact.getGeneration()) {
        Long retainedGeneration = retained.get(reference.mediaId());
        if (retainedGeneration == null || retainedGeneration != reference.generation()) {
          throw mediaNotReady();
        }
      }
      current.add(new MediaReference(reference.mediaId(), fact.getGeneration()));
    }
    return List.copyOf(current);
  }

  private InventoryException mediaNotReady() {
    return new InventoryException(
        HttpStatus.UNPROCESSABLE_ENTITY,
        "INVENTORY_MEDIA_NOT_READY",
        "Referenced media generation is not READY for this finding");
  }

  private void validateCoverMedia(SaveInspectionRequest request) {
    UUID coverMediaId = request.coverMediaId();
    List<MediaReference> media = request.media();
    if (media.isEmpty()) {
      if (coverMediaId != null) {
        throw validationFailure("Титульное фото нельзя выбрать без фотографий");
      }
    } else {
      if (coverMediaId == null) {
        throw validationFailure("Выберите титульную фотографию");
      }
      if (media.stream().noneMatch(reference -> coverMediaId.equals(reference.mediaId()))) {
        throw validationFailure("Титульная фотография отсутствует среди фотографий проверки");
      }
    }
    PlanSelection selection = request.planSelection();
    if (selection != null && !java.util.Objects.equals(selection.coverMediaId(), coverMediaId)) {
      throw validationFailure(
          "Титульная фотография рабочего плана не совпадает с фотографией проверки");
    }
  }

  private InventoryException validationFailure(String message) {
    return new InventoryException(
        HttpStatus.UNPROCESSABLE_ENTITY, "INVENTORY_VALIDATION_FAILED", message);
  }

  private void validateReadyMedia(
      UUID findingId,
      UUID warehouseId,
      List<MediaReference> media,
      UUID coverMediaId) {
    Set<UUID> unique = new HashSet<>();
    for (MediaReference reference : media) {
      if (!unique.add(reference.mediaId())) {
        throw new IllegalArgumentException(
            "Media references must contain only one generation per media object");
      }
      var fact =
          mediaFacts
              .findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
                  reference.mediaId(),
                  reference.generation(),
                  "INVENTORY_FINDING",
                  findingId,
                  warehouseId,
                  "READY")
              .orElse(null);
      if (fact == null) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_MEDIA_NOT_READY",
            "Referenced media generation is not READY for this finding");
      }
      if (reference.mediaId().equals(coverMediaId) && !"IMAGE".equals(fact.getMediaKind())) {
        throw validationFailure("Титульным медиа может быть только фотография");
      }
    }
  }

  private void persistMedia(InventoryFinding finding, List<MediaReference> media) {
    for (MediaReference reference : media) {
      var fact =
          mediaFacts
              .findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
                  reference.mediaId(),
                  reference.generation(),
                  "INVENTORY_FINDING",
                  finding.getId(),
                  requireSession(finding.getInventoryId()).getWarehouseId(),
                  "READY")
              .orElseThrow(() ->
                  new InventoryException(
                      HttpStatus.UNPROCESSABLE_ENTITY,
                      "INVENTORY_MEDIA_NOT_READY",
                      "Media readiness changed before inspection commit"));
      mediaReferences.save(
          new FindingMediaReference(
              finding.getId(),
              finding.getRevision(),
              reference.mediaId(),
              reference.generation(),
              fact.getMediaKind()));
    }
  }

  private void persistPlan(
      InventoryFinding finding,
      PlanSelection selection,
      InventoryDependencyGateway.FrozenPlan frozen) {
    JsonNode snapshot = frozen.snapshot();
    UUID catalogVersionId = UUID.fromString(snapshot.path("catalogVersionId").asText());
    boolean movementToRepair =
        requiredBoolean(snapshot, "movementToRepair", "frozen movement to repair");
    LogisticsPlanningMode logisticsPlanningMode;
    LocalDate logisticsScheduledDate;
    try {
      logisticsPlanningMode =
          nullableLogisticsPlanningMode(
              snapshot, "logisticsPlanningMode", "frozen logistics planning mode");
      logisticsScheduledDate =
          nullableLocalDate(
              snapshot, "logisticsScheduledDate", "frozen logistics scheduled date");
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException(
          "Persisted frozen logistics planning is invalid", exception);
    }
    if (!LogisticsPlanningMode.validInboundPlanning(
        movementToRepair, logisticsPlanningMode, logisticsScheduledDate)) {
      throw new IllegalStateException("Persisted frozen logistics planning is invalid");
    }
    if (movementToRepair != selection.movementToRepair()
        || logisticsPlanningMode != selection.logisticsPlanningMode()
        || !java.util.Objects.equals(
            logisticsScheduledDate, selection.logisticsScheduledDate())) {
      throw InventoryException.dependency(
          "Maintenance-service returned mismatched logistics planning");
    }
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            finding.getId(),
            finding.getRevision(),
            finding.getInventoryId(),
            selection.mode(),
            movementToRepair,
            logisticsPlanningMode,
            logisticsScheduledDate,
            catalogVersionId,
            frozen.fingerprint(),
            write(snapshot),
            2));
    int lineNo = 0;
    for (JsonNode line : snapshot.path("lines")) {
      String unit = line.path("unit").isNull() ? null : line.path("unit").asText();
      if (unit == null || unit.isBlank()) {
        throw new IllegalArgumentException("Frozen inventory line unit is required");
      }
      BigDecimal quantity = new BigDecimal(line.path("quantity").asText());
      long priceMinor = line.path("unitPriceMinor").longValue();
      BigDecimal normative = new BigDecimal(line.path("normativeMinutes").asText());
      String sourceKind = line.path("aggregationKind").asText();
      UUID lineCatalogVersionId =
          line.path("catalogVersionId").isNull()
              ? null
              : UUID.fromString(line.path("catalogVersionId").asText());
      UUID catalogNodeId =
          line.path("catalogNodeId").isNull()
              ? null
              : UUID.fromString(line.path("catalogNodeId").asText());
      String normalizedDescription =
          line.path("normalizedDescription").isNull()
              ? null
              : line.path("normalizedDescription").asText();
      planLines.save(
          new FindingPlanLine(
              finding.getId(),
              finding.getRevision(),
              lineNo++,
              sourceKind,
              line.path("type").asText(),
              lineCatalogVersionId,
              catalogNodeId,
              line.path("description").asText(),
              normalizedDescription,
              unit,
              quantity,
              priceMinor,
              normative));
    }
    int stageNo = 0;
    for (JsonNode stage : snapshot.path("stages")) {
      if (!"REPAIR_WORK".equals(requiredText(stage, "kind", "frozen plan stage kind"))) {
        throw new IllegalStateException("Frozen inventory plan contains a legacy movement stage");
      }
      JsonNode routing = stage.path("routing");
      planStages.save(
          new FindingPlanStage(
              finding.getId(),
              finding.getRevision(),
              stageNo++,
              stage.path("kind").asText(),
              UUID.fromString(stage.path("catalogNodeId").asText()),
              stage.path("catalogNodeName").asText(),
              UUID.fromString(routing.path("queueId").asText()),
              routing.path("queueName").asText(),
              routing.path("queueType").asText(),
              false,
              write(stage)));
    }
  }

  private RevisionState revisionState(
      InventorySession session,
      long expectedSessionRevision,
      List<RevisionExpectation> expectations) {
    expectRevision(session.getRevision(), expectedSessionRevision);
    List<InventoryFinding> all =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    Map<UUID, Long> expected = new LinkedHashMap<>();
    for (RevisionExpectation item : expectations) {
      if (expected.put(item.findingId(), item.expectedFindingRevision()) != null) {
        throw new IllegalArgumentException("Finding revision vector contains duplicates");
      }
    }
    if (all.size() != expected.size()) {
      throw InventoryException.conflict("Finding revision vector is incomplete");
    }
    for (InventoryFinding finding : all) {
      Long revision = expected.get(finding.getId());
      if (revision == null || revision != finding.getRevision()) {
        throw InventoryException.conflict("Finding revision vector is stale");
      }
      if (finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING
          || finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.PLAN_RESOLVE_PENDING) {
        throw InventoryException.conflict("Finding mutation is still in flight");
      }
    }
    return new RevisionState(
        all,
        all.stream()
            .map(value -> new RevisionExpectation(value.getId(), value.getRevision()))
            .toList());
  }

  private void validateFurnitureStageTransition(
      List<CompletionRisk> risks, boolean acknowledgeIncomplete) {
    if (risks.stream().anyMatch(risk -> "CONFLICT".equals(risk.code()))) {
      throw InventoryException.conflict(
          "Урегулируйте конфликты реестра перед сверкой мебели");
    }
    if (risks.stream()
        .anyMatch(
            risk ->
                !"MISSING".equals(risk.code()) && !"NOT_INSPECTED".equals(risk.code()))) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Сверка мебели недоступна, пока в осмотре есть незавершённые изменения");
    }
    if (!acknowledgeIncomplete && !risks.isEmpty()) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Подтвердите только ненайденные и непроверенные бытовки перед сверкой мебели");
    }
  }

  /**
   * Furniture is reconciled only for cabins that are still physically in this inventory's
   * warehouse and are eligible for a capture. Accepted registry changes may legitimately leave a
   * historical finding active while its cabin has departed; such a finding is intentionally not a
   * member of the frozen furniture scope.
   */
  private List<InventoryFinding> furnitureFindings(
      InventorySession session, List<InventoryFinding> values) {
    return values.stream()
        .filter(value -> value.getAssetId() != null)
        .filter(value -> session.getWarehouseId().equals(value.getCurrentWarehouseId()))
        .filter(value -> CAPTURE_STATUSES.contains(value.getCurrentStatus()))
        .toList();
  }

  private List<UUID> furnitureAssetIds(
      InventorySession session, List<InventoryFinding> values) {
    return furnitureFindings(session, values).stream()
        .map(InventoryFinding::getAssetId)
        .distinct()
        .sorted()
        .toList();
  }

  private void validateFurnitureSnapshot(
      InventorySession session,
      List<InventoryFinding> activeFindings,
      InventoryDependencyGateway.FurnitureSnapshot snapshot) {
    if (snapshot == null
        || !session.getWarehouseId().equals(snapshot.warehouseId())
        || snapshot.snapshotSha256() == null
        || !snapshot.snapshotSha256().matches("^[0-9a-f]{64}$")
        || snapshot.items() == null) {
      throw InventoryException.dependency("Asset-service returned malformed furniture snapshot");
    }
    Set<UUID> knownAssets = new HashSet<>(furnitureAssetIds(session, activeFindings));
    Set<UUID> equipmentIds = new HashSet<>();
    for (InventoryDependencyGateway.FurnitureSnapshotItem item : snapshot.items()) {
      if (item == null
          || item.equipmentId() == null
          || !equipmentIds.add(item.equipmentId())
          || item.catalogVersion() < 0
          || item.equipmentName() == null
          || item.equipmentName().isBlank()
          || item.currentStockQuantity() < 0
          || (item.stockBalanceVersion() != null && item.stockBalanceVersion() < 0)
          || (item.currentStockQuantity() > 0 && item.stockBalanceVersion() == null)
          || item.cabins() == null) {
        throw InventoryException.dependency("Asset-service returned malformed furniture item");
      }
      Set<UUID> cabinAssets = new HashSet<>();
      for (InventoryDependencyGateway.FurnitureSnapshotCabin cabin : item.cabins()) {
        if (cabin == null
            || cabin.assetId() == null
            || !knownAssets.contains(cabin.assetId())
            || !cabinAssets.add(cabin.assetId())
            || cabin.assetVersion() < 0
            || cabin.displayCanonicalNumber() == null
            || cabin.displayCanonicalNumber().isBlank()
            || cabin.status() == null
            || cabin.status().isBlank()
            || cabin.currentQuantity() < 0) {
          throw InventoryException.dependency("Asset-service returned malformed furniture cabin");
        }
      }
      if (!cabinAssets.equals(knownAssets)) {
        throw InventoryException.dependency(
            "Asset-service returned an incomplete furniture cabin snapshot");
      }
    }
  }

  private InventoryDependencyGateway.FurnitureSnapshot furnitureSnapshot(InventorySession session) {
    if (session.getFurnitureAssetSnapshot() == null) {
      throw InventoryException.conflict("Furniture review has not started");
    }
    try {
      InventoryDependencyGateway.FurnitureSnapshot snapshot =
          convert(
              read(session.getFurnitureAssetSnapshot()),
              InventoryDependencyGateway.FurnitureSnapshot.class);
      if (!session.getFurnitureAssetSnapshotSha256().equals(snapshot.snapshotSha256())) {
        throw InventoryException.conflict("Stored furniture review snapshot is inconsistent");
      }
      return snapshot;
    } catch (IllegalArgumentException exception) {
      throw InventoryException.conflict("Stored furniture review snapshot is invalid");
    }
  }

  private FurnitureReviewSubmission validateFurnitureReviewSubmission(
      InventorySession session,
      InventoryDependencyGateway.FurnitureSnapshot snapshot,
      SaveFurnitureReviewRequest request) {
    if (!request.assetSnapshotSha256().equals(session.getFurnitureAssetSnapshotSha256())) {
      throw InventoryException.conflict("Furniture review snapshot is stale");
    }
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    validateFurnitureSnapshot(session, active, snapshot);
    List<InventoryFinding> scopedFurnitureFindings = furnitureFindings(session, active);
    Map<UUID, InventoryFinding> findingByAsset = new LinkedHashMap<>();
    for (InventoryFinding finding : scopedFurnitureFindings) {
      if (findingByAsset.put(finding.getAssetId(), finding) != null) {
        throw new IllegalStateException("Inventory furniture asset is bound twice");
      }
    }
    Map<UUID, FurnitureReviewItemInput> submittedByEquipment = new LinkedHashMap<>();
    for (FurnitureReviewItemInput item : request.items()) {
      if (item == null
          || item.equipmentId() == null
          || item.catalogVersion() < 0
          || item.observedStockQuantity() < 0
          || item.cabins() == null
          || submittedByEquipment.put(item.equipmentId(), item) != null) {
        throw new IllegalArgumentException("Furniture review item set is invalid");
      }
    }
    if (submittedByEquipment.size() != snapshot.items().size()) {
      throw InventoryException.conflict("Furniture review item set is incomplete");
    }
    ObjectNode review = mapper.createObjectNode();
    review.put("assetSnapshotSha256", request.assetSnapshotSha256());
    ArrayNode reviewItems = review.putArray("items");
    Map<UUID, Long> findingRevisions = new LinkedHashMap<>();
    Map<UUID, List<ObjectNode>> observations = new LinkedHashMap<>();
    for (InventoryFinding finding : scopedFurnitureFindings) {
      observations.put(finding.getId(), new ArrayList<>());
    }
    for (InventoryDependencyGateway.FurnitureSnapshotItem source :
        snapshot.items().stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId))
            .toList()) {
      FurnitureReviewItemInput submitted = submittedByEquipment.get(source.equipmentId());
      if (submitted == null || submitted.catalogVersion() != source.catalogVersion()) {
        throw InventoryException.conflict("Furniture review catalog item changed");
      }
      Map<UUID, FurnitureReviewCabinInput> cabinsByFinding = new LinkedHashMap<>();
      for (FurnitureReviewCabinInput cabin : submitted.cabins()) {
        if (cabin == null
            || cabin.findingId() == null
            || cabin.expectedFindingRevision() < 0
            || cabin.observedQuantity() < 0
            || cabinsByFinding.put(cabin.findingId(), cabin) != null) {
          throw new IllegalArgumentException("Furniture review cabin set is invalid");
        }
      }
      if (cabinsByFinding.size() != source.cabins().size()) {
        throw InventoryException.conflict("Furniture review cabin set is incomplete");
      }
      ObjectNode reviewItem = reviewItems.addObject();
      reviewItem.put("equipmentId", source.equipmentId().toString());
      reviewItem.put("catalogVersion", source.catalogVersion());
      reviewItem.put("observedStockQuantity", submitted.observedStockQuantity());
      ArrayNode reviewCabins = reviewItem.putArray("cabins");
      for (InventoryDependencyGateway.FurnitureSnapshotCabin sourceCabin :
          source.cabins().stream()
              .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotCabin::assetId))
              .toList()) {
        InventoryFinding finding = findingByAsset.get(sourceCabin.assetId());
        if (finding == null) {
          throw InventoryException.conflict("Furniture review cabin is no longer active");
        }
        FurnitureReviewCabinInput submittedCabin = cabinsByFinding.get(finding.getId());
        if (submittedCabin == null
            || submittedCabin.expectedFindingRevision() != finding.getRevision()) {
          throw InventoryException.conflict("Furniture review finding revision is stale");
        }
        Long previous = findingRevisions.put(finding.getId(), finding.getRevision());
        if (previous != null && previous.longValue() != finding.getRevision()) {
          throw new IllegalStateException("Furniture review repeats inconsistent finding revision");
        }
        ObjectNode reviewCabin = reviewCabins.addObject();
        reviewCabin.put("findingId", finding.getId().toString());
        reviewCabin.put("assetId", sourceCabin.assetId().toString());
        reviewCabin.put("observedQuantity", submittedCabin.observedQuantity());
        ObjectNode observation = mapper.createObjectNode();
        observation.put("equipmentId", source.equipmentId().toString());
        observation.put("catalogVersion", source.catalogVersion());
        observation.put("observedQuantity", submittedCabin.observedQuantity());
        observations.computeIfAbsent(finding.getId(), ignored -> new ArrayList<>()).add(observation);
      }
    }
    if (!submittedByEquipment.keySet().equals(
        snapshot.items().stream()
            .map(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId)
            .collect(java.util.stream.Collectors.toSet()))) {
      throw InventoryException.conflict("Furniture review contains an unknown catalog item");
    }
    Map<UUID, FurnitureObservation> observationJson = new LinkedHashMap<>();
    observations.forEach(
        (findingId, entries) -> {
          entries.sort(
              Comparator.<ObjectNode, UUID>comparing(
                      value -> UUID.fromString(value.path("equipmentId").asText()))
                  .thenComparingLong(value -> value.path("catalogVersion").asLong()));
          ArrayNode value = mapper.createArrayNode();
          entries.forEach(value::add);
          observationJson.put(
              findingId,
              new FurnitureObservation(
                  entries.isEmpty() ? ObservationPresence.EXPLICIT_EMPTY : ObservationPresence.PRESENT,
                  write(value)));
        });
    return new FurnitureReviewSubmission(
        write(review),
        canonicalJsonTreeHash(review),
        Map.copyOf(findingRevisions),
        Map.copyOf(observationJson));
  }

  private FurnitureReviewView furnitureReviewView(InventorySession session) {
    requireFurnitureReviewStage(session);
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    List<InventoryFinding> active =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId());
    List<InventoryFinding> scopedFurnitureFindings = furnitureFindings(session, active);
    Map<UUID, InventoryFinding> findingByAsset = new LinkedHashMap<>();
    for (InventoryFinding finding : scopedFurnitureFindings) {
      findingByAsset.put(finding.getAssetId(), finding);
    }
    JsonNode review =
        session.getFurnitureStockObservation() == null
            ? null
            : read(session.getFurnitureStockObservation());
    List<FurnitureReviewItemView> items = new ArrayList<>();
    for (InventoryDependencyGateway.FurnitureSnapshotItem item :
        snapshot.items().stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId))
            .toList()) {
      List<FurnitureReviewCabinView> cabins = new ArrayList<>();
      for (InventoryDependencyGateway.FurnitureSnapshotCabin cabin :
          item.cabins().stream()
              .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotCabin::assetId))
              .toList()) {
        InventoryFinding finding = findingByAsset.get(cabin.assetId());
        if (finding == null) continue;
        long observed =
            reviewCabinQuantity(review, item.equipmentId(), finding.getId())
                .or(() -> findingFurnitureQuantity(finding, item.equipmentId(), item.catalogVersion()))
                .orElse(cabin.currentQuantity());
        cabins.add(
            new FurnitureReviewCabinView(
                finding.getId(),
                cabin.assetId(),
                cabin.displayCanonicalNumber(),
                cabin.status(),
                cabin.currentQuantity(),
                observed));
      }
      long observedStock =
          reviewStockQuantity(review, item.equipmentId()).orElse(item.currentStockQuantity());
      items.add(
          new FurnitureReviewItemView(
              item.equipmentId(),
              item.catalogVersion(),
              item.equipmentName(),
              item.currentStockQuantity(),
              observedStock,
              List.copyOf(cabins)));
    }
    return new FurnitureReviewView(
        session.getId(),
        session.getRevision(),
        session.getReviewStage(),
        session.getFurnitureAssetSnapshotSha256(),
        session.getFurnitureReviewSha256(),
        session.getFurnitureReviewSha256() != null,
        List.copyOf(items));
  }

  private Optional<Long> reviewStockQuantity(JsonNode review, UUID equipmentId) {
    if (review == null || !review.isObject()) return Optional.empty();
    for (JsonNode item : review.path("items")) {
      if (equipmentId.toString().equals(item.path("equipmentId").asText())) {
        return nonNegativeLong(item.path("observedStockQuantity"));
      }
    }
    return Optional.empty();
  }

  private Optional<Long> reviewCabinQuantity(JsonNode review, UUID equipmentId, UUID findingId) {
    if (review == null || !review.isObject()) return Optional.empty();
    for (JsonNode item : review.path("items")) {
      if (!equipmentId.toString().equals(item.path("equipmentId").asText())) continue;
      for (JsonNode cabin : item.path("cabins")) {
        if (findingId.toString().equals(cabin.path("findingId").asText())) {
          return nonNegativeLong(cabin.path("observedQuantity"));
        }
      }
    }
    return Optional.empty();
  }

  private Optional<Long> findingFurnitureQuantity(
      InventoryFinding finding, UUID equipmentId, long catalogVersion) {
    if (finding.getEquipmentObservationState() == ObservationPresence.ABSENT
        || finding.getEquipmentObservation() == null) {
      return Optional.empty();
    }
    JsonNode observations = read(finding.getEquipmentObservation());
    if (!observations.isArray()) return Optional.empty();
    for (JsonNode observation : observations) {
      if (equipmentId.toString().equals(observation.path("equipmentId").asText())
          && catalogVersion == observation.path("catalogVersion").asLong(Long.MIN_VALUE)) {
        return nonNegativeLong(observation.path("observedQuantity"));
      }
    }
    return Optional.empty();
  }

  private Optional<Long> nonNegativeLong(JsonNode value) {
    if (value == null || !value.canConvertToLong() || value.longValue() < 0) {
      return Optional.empty();
    }
    return Optional.of(value.longValue());
  }

  private FurnitureCompletionFact requireConfirmedCurrentFurnitureReview(
      InventorySession session, List<InventoryFinding> active) {
    FurnitureCompletionFact review = requireConfirmedFurnitureReview(session);
    requireCurrentFurnitureReviewSnapshot(session, active);
    return review;
  }

  private FurnitureCompletionFact requireConfirmedFurnitureReview(InventorySession session) {
    requireFurnitureReviewStage(session);
    if (session.getFurnitureReviewSha256() == null || session.getFurnitureStockObservation() == null) {
      throw InventoryException.conflict("Furniture review must be confirmed before inventory completion");
    }
    if (!canonicalJsonTreeHash(read(session.getFurnitureStockObservation()))
        .equals(session.getFurnitureReviewSha256())) {
      throw InventoryException.conflict("Furniture review acknowledgement is inconsistent");
    }
    return new FurnitureCompletionFact(
        session.getFurnitureAssetSnapshotSha256(),
        session.getFurnitureReviewSha256(),
        read(session.getFurnitureStockObservation()));
  }

  private void requireCurrentFurnitureReviewSnapshot(
      InventorySession session, List<InventoryFinding> active) {
    InventoryDependencyGateway.FurnitureSnapshot fresh =
        dependencies.furnitureSnapshot(session.getWarehouseId(), furnitureAssetIds(session, active));
    validateFurnitureSnapshot(session, active, fresh);
    if (!session.getFurnitureAssetSnapshotSha256().equals(fresh.snapshotSha256())) {
      throw InventoryException.conflict("Furniture review snapshot is stale");
    }
  }

  private void requireLockedFurnitureReview(
      InventorySession session, FurnitureCompletionFact expected) {
    requireFurnitureReviewStage(session);
    if (!expected.assetSnapshotSha256().equals(session.getFurnitureAssetSnapshotSha256())
        || !expected.reviewSha256().equals(session.getFurnitureReviewSha256())
        || session.getFurnitureStockObservation() == null
        || !canonicalJsonTreeHash(read(session.getFurnitureStockObservation()))
            .equals(expected.reviewSha256())) {
      throw InventoryException.conflict("Furniture review changed before inventory completion");
    }
  }

  private void requireCabinReviewStage(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.CABINS) {
      throw InventoryException.conflict("Cabin review is frozen after furniture review starts");
    }
  }

  private void requireCabinOrFurnitureReviewStage(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.CABINS
        && session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      throw InventoryException.conflict("Inventory review stage is invalid");
    }
  }

  private void requireFurnitureReviewCanStart(InventorySession session) {
    requireCabinOrFurnitureReviewStage(session);
    if (session.getReviewStage() == InventoryReviewStage.FURNITURE
        && session.getFurnitureReviewSha256() != null) {
      throw InventoryException.conflict("Furniture review is already confirmed");
    }
  }

  /**
   * Cabin facts and the furniture snapshot describe the same active population. Any saved cabin
   * inspection or resolved registry conflict therefore invalidates the frozen furniture review;
   * it never mutates or deletes the historical finding facts themselves.
   */
  private void invalidateFurnitureReviewAfterCabinChange(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      return;
    }
    session.restartCabinReview();
    furnitureReconciliations.findById(session.getId()).ifPresent(furnitureReconciliations::delete);
    sessions.saveAndFlush(session);
  }

  private void requireFurnitureReviewStage(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      throw InventoryException.conflict("Furniture review has not started");
    }
  }

  private void createFurnitureLossIntents(InventorySession session) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw new IllegalStateException("Furniture loss proposals require a completed inventory");
    }
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    JsonNode review = confirmedFurnitureReview(session);
    for (InventoryDependencyGateway.FurnitureSnapshotItem source : snapshot.items()) {
      long observed =
          reviewStockQuantity(review, source.equipmentId())
              .orElseThrow(
                  () ->
                      new IllegalStateException(
                          "Stored furniture review omits a warehouse stock quantity"));
      if (observed >= source.currentStockQuantity()) {
        continue;
      }
      if (source.stockBalanceVersion() == null) {
        throw new IllegalStateException(
            "A positive warehouse stock balance must carry its optimistic version");
      }
      long shortage = Math.subtractExact(source.currentStockQuantity(), observed);
      UUID findingId =
          stableUuid(
              "rwms:inventory:furniture-loss:"
                  + session.getId()
                  + ":"
                  + source.equipmentId());
      UUID idempotencyKey =
          stableUuid(
              "rwms:inventory:furniture-loss-delivery:"
                  + session.getId()
                  + ":"
                  + source.equipmentId());
      InventoryDependencyGateway.InventoryLossDispositionRequest request =
          new InventoryDependencyGateway.InventoryLossDispositionRequest(
              session.getId(),
              findingId,
              session.getWarehouseId(),
              source.equipmentId(),
              source.equipmentName(),
              source.catalogVersion(),
              shortage,
              source.stockBalanceVersion(),
              "Недостача «"
                  + source.equipmentName()
                  + "» по итогам инвентаризации "
                  + session.getId()
                  + ": ожидалось "
                  + source.currentStockQuantity()
                  + ", фактически "
                  + observed
                  + ".",
              null);
      String requestBody = canonicalWrite(request);
      furnitureLosses.saveAndFlush(
          InventoryFurnitureLossIntent.pending(
              findingId,
              session.getId(),
              session.getWarehouseId(),
              source.equipmentId(),
              idempotencyKey,
              canonicalJsonTreeHash(read(requestBody)),
              requestBody));
    }
  }

  private JsonNode confirmedFurnitureReview(InventorySession session) {
    if (session.getFurnitureReviewSha256() == null || session.getFurnitureStockObservation() == null) {
      throw new IllegalStateException("Completed inventory has no confirmed furniture review");
    }
    JsonNode review = read(session.getFurnitureStockObservation());
    if (!review.isObject()
        || !session
            .getFurnitureAssetSnapshotSha256()
            .equals(review.path("assetSnapshotSha256").asText())
        || !session.getFurnitureReviewSha256().equals(canonicalJsonTreeHash(review))) {
      throw new IllegalStateException("Stored furniture review is invalid");
    }
    return review;
  }

  private static UUID stableUuid(String source) {
    return UUID.nameUUIDFromBytes(source.getBytes(StandardCharsets.UTF_8));
  }

  private void createFurnitureReconciliationIntent(InventorySession session) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw new IllegalStateException("Furniture reconciliation requires a completed inventory");
    }
    InventoryDependencyGateway.FurnitureReconciliationRequest request =
        furnitureReconciliationRequest(session);
    if (request.items().isEmpty()) {
      return;
    }
    String requestBody = canonicalWrite(request);
    String requestSha256 = canonicalJsonTreeHash(read(requestBody));
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("rwms:inventory:furniture-reconciliation:"
                    + session.getId()
                    + ":"
                    + session.getFurnitureReviewSha256())
                .getBytes(StandardCharsets.UTF_8));
    InventoryFurnitureReconciliationIntent intent =
        InventoryFurnitureReconciliationIntent.pending(
            session.getId(),
            idempotencyKey,
            session.getFurnitureAssetSnapshotSha256(),
            session.getFurnitureReviewSha256(),
            requestSha256,
            requestBody);
    furnitureReconciliations.saveAndFlush(intent);
  }

  private InventoryDependencyGateway.FurnitureReconciliationRequest furnitureReconciliationRequest(
      InventorySession session) {
    InventoryDependencyGateway.FurnitureSnapshot snapshot = furnitureSnapshot(session);
    JsonNode review = confirmedFurnitureReview(session);
    Map<UUID, JsonNode> reviewedItems = new LinkedHashMap<>();
    for (JsonNode item : review.path("items")) {
      UUID equipmentId = requiredUuid(item, "equipmentId", "furniture review equipment id");
      if (reviewedItems.put(equipmentId, item) != null) {
        throw new IllegalStateException("Stored furniture review repeats an equipment item");
      }
    }
    List<InventoryDependencyGateway.FurnitureReconciliationItem> items = new ArrayList<>();
    for (InventoryDependencyGateway.FurnitureSnapshotItem source :
        snapshot.items().stream()
            .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotItem::equipmentId))
            .toList()) {
      JsonNode reviewedItem = reviewedItems.remove(source.equipmentId());
      if (reviewedItem == null
          || requiredNonNegativeLong(
                  reviewedItem.path("catalogVersion"), "furniture review catalog version")
              != source.catalogVersion()) {
        throw new IllegalStateException("Stored furniture review does not match its snapshot");
      }
      Map<UUID, JsonNode> reviewedCabins = new LinkedHashMap<>();
      for (JsonNode cabin : reviewedItem.path("cabins")) {
        UUID assetId = requiredUuid(cabin, "assetId", "furniture review cabin asset id");
        if (reviewedCabins.put(assetId, cabin) != null) {
          throw new IllegalStateException("Stored furniture review repeats a cabin");
        }
      }
      List<InventoryDependencyGateway.FurnitureReconciliationCabin> cabins = new ArrayList<>();
      for (InventoryDependencyGateway.FurnitureSnapshotCabin sourceCabin :
          source.cabins().stream()
              .sorted(Comparator.comparing(InventoryDependencyGateway.FurnitureSnapshotCabin::assetId))
              .toList()) {
        JsonNode reviewedCabin = reviewedCabins.remove(sourceCabin.assetId());
        if (reviewedCabin == null) {
          throw new IllegalStateException("Stored furniture review omits a cabin");
        }
        cabins.add(
            new InventoryDependencyGateway.FurnitureReconciliationCabin(
                sourceCabin.assetId(),
                requiredNonNegativeLong(
                    reviewedCabin.path("observedQuantity"), "furniture review cabin quantity")));
      }
      if (!reviewedCabins.isEmpty()) {
        throw new IllegalStateException("Stored furniture review contains an unknown cabin");
      }
      items.add(
          new InventoryDependencyGateway.FurnitureReconciliationItem(
              source.equipmentId(),
              source.catalogVersion(),
              Math.max(
                  source.currentStockQuantity(),
                  requiredNonNegativeLong(
                      reviewedItem.path("observedStockQuantity"),
                      "furniture review stock quantity")),
              List.copyOf(cabins)));
    }
    if (!reviewedItems.isEmpty()) {
      throw new IllegalStateException("Stored furniture review contains an unknown equipment item");
    }
    return new InventoryDependencyGateway.FurnitureReconciliationRequest(
        session.getWarehouseId(),
        session.getFurnitureAssetSnapshotSha256(),
        session.getFurnitureReviewSha256(),
        List.copyOf(items));
  }

  private long requiredNonNegativeLong(JsonNode value, String field) {
    return nonNegativeLong(value)
        .orElseThrow(() -> new IllegalStateException("Persisted " + field + " is invalid"));
  }

  private void dispatchFurnitureReconciliation(UUID inventoryId) {
    try {
      FurnitureReconciliationDispatch dispatch =
          independentTransactions.execute(
              status -> {
                InventoryFurnitureReconciliationIntent intent =
                    furnitureReconciliations.findByInventoryIdForUpdate(inventoryId).orElse(null);
                if (intent == null) {
                  return null;
                }
                if (intent.getState() != FurnitureReconciliationState.PENDING
                    && intent.getState() != FurnitureReconciliationState.TRANSIENT_FAILED) {
                  return null;
                }
                InventoryDependencyGateway.FurnitureReconciliationRequest request =
                    frozenFurnitureReconciliationRequest(intent);
                intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60));
                InventoryFurnitureReconciliationIntent saved = furnitureReconciliations.saveAndFlush(intent);
                return new FurnitureReconciliationDispatch(
                    saved.getInventoryId(),
                    saved.getIdempotencyKey(),
                    saved.getAttemptCount(),
                    request);
              });
      if (dispatch == null) return;
      try {
        dependencies.reconcileFurniture(
            dispatch.inventoryId(), dispatch.idempotencyKey(), dispatch.request());
      } catch (RuntimeException exception) {
        settleFurnitureReconciliationFailure(inventoryId, dispatch.attemptCount(), exception);
        return;
      }
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryFurnitureReconciliationIntent intent =
                furnitureReconciliations
                    .findByInventoryIdForUpdate(inventoryId)
                    .orElseThrow(
                        () ->
                            new IllegalStateException(
                                "Furniture reconciliation intent disappeared during dispatch"));
            if (intent.getState() == FurnitureReconciliationState.PENDING
                && intent.getAttemptCount() == dispatch.attemptCount()) {
              intent.succeed();
              furnitureReconciliations.saveAndFlush(intent);
            }
          });
    } catch (RuntimeException exception) {
      log.warn("Furniture reconciliation dispatch deferred for inventory {}", inventoryId, exception);
      settleFurnitureReconciliationFailure(inventoryId, null, exception);
    }
  }

  private InventoryDependencyGateway.FurnitureReconciliationRequest frozenFurnitureReconciliationRequest(
      InventoryFurnitureReconciliationIntent intent) {
    JsonNode requestBody = read(intent.getRequestBody());
    if (!canonicalJsonTreeHash(requestBody).equals(intent.getRequestSha256())) {
      throw new IllegalStateException("Furniture reconciliation request snapshot is inconsistent");
    }
    InventoryDependencyGateway.FurnitureReconciliationRequest request =
        convert(requestBody, InventoryDependencyGateway.FurnitureReconciliationRequest.class);
    if (!intent.getAssetSnapshotSha256().equals(request.expectedSnapshotSha256())
        || !intent.getReviewSha256().equals(request.reviewSha256())
        || request.warehouseId() == null
        || request.items() == null) {
      throw new IllegalStateException("Furniture reconciliation request snapshot is invalid");
    }
    for (InventoryDependencyGateway.FurnitureReconciliationItem item : request.items()) {
      if (item == null
          || item.equipmentId() == null
          || item.catalogVersion() < 0
          || item.stockQuantity() < 0
          || item.cabins() == null
          || item.cabins().stream()
              .anyMatch(cabin -> cabin == null || cabin.assetId() == null || cabin.quantity() < 0)) {
        throw new IllegalStateException("Furniture reconciliation request snapshot is invalid");
      }
    }
    return request;
  }

  private void dispatchFurnitureLosses(UUID inventoryId) {
    for (InventoryFurnitureLossIntent intent :
        furnitureLosses.findAllByInventoryIdOrderByFindingIdAsc(inventoryId)) {
      if (intent.getState() == FurnitureLossIntentState.PENDING
          || intent.getState() == FurnitureLossIntentState.TRANSIENT_FAILED) {
        dispatchFurnitureLoss(intent.getFindingId());
      }
    }
  }

  private void dispatchFurnitureLoss(UUID findingId) {
    FurnitureLossDispatch dispatch;
    try {
      dispatch =
          independentTransactions.execute(
              status -> {
                InventoryFurnitureLossIntent intent =
                    furnitureLosses.findByFindingIdForUpdate(findingId).orElse(null);
                if (intent == null
                    || (intent.getState() != FurnitureLossIntentState.PENDING
                        && intent.getState() != FurnitureLossIntentState.TRANSIENT_FAILED)) {
                  return null;
                }
                InventoryDependencyGateway.InventoryLossDispositionRequest request =
                    frozenFurnitureLossRequest(intent);
                intent.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(60));
                InventoryFurnitureLossIntent saved = furnitureLosses.saveAndFlush(intent);
                return new FurnitureLossDispatch(
                    saved.getFindingId(),
                    saved.getIdempotencyKey(),
                    saved.getAttemptCount(),
                    request);
              });
    } catch (RuntimeException exception) {
      log.warn("Furniture loss proposal dispatch deferred for finding {}", findingId, exception);
      settleFurnitureLossFailure(findingId, null, exception);
      return;
    }
    if (dispatch == null) {
      return;
    }
    InventoryDependencyGateway.InventoryLossDisposition decision;
    try {
      decision =
          dependencies.createInventoryLossDisposition(
              dispatch.idempotencyKey(), dispatch.request());
    } catch (RuntimeException exception) {
      settleFurnitureLossFailure(findingId, dispatch.attemptCount(), exception);
      return;
    }
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryFurnitureLossIntent intent =
              furnitureLosses
                  .findByFindingIdForUpdate(findingId)
                  .orElseThrow(
                      () ->
                          new IllegalStateException(
                              "Furniture loss intent disappeared during dispatch"));
          if (intent.getState() == FurnitureLossIntentState.PENDING
              && intent.getAttemptCount() == dispatch.attemptCount()) {
            intent.succeed(decision.id());
            furnitureLosses.saveAndFlush(intent);
          }
        });
  }

  private InventoryDependencyGateway.InventoryLossDispositionRequest frozenFurnitureLossRequest(
      InventoryFurnitureLossIntent intent) {
    JsonNode requestBody = read(intent.getRequestBody());
    if (!canonicalJsonTreeHash(requestBody).equals(intent.getRequestSha256())) {
      throw new IllegalStateException("Furniture loss request snapshot is inconsistent");
    }
    InventoryDependencyGateway.InventoryLossDispositionRequest request =
        convert(requestBody, InventoryDependencyGateway.InventoryLossDispositionRequest.class);
    if (!intent.getInventoryId().equals(request.inventorySessionId())
        || !intent.getFindingId().equals(request.findingId())
        || !intent.getWarehouseId().equals(request.warehouseId())
        || !intent.getEquipmentId().equals(request.equipmentId())
        || request.equipmentName() == null
        || request.equipmentName().isBlank()
        || request.expectedAssetVersion() < 0
        || request.expectedSourceBalanceVersion() < 0
        || request.quantity() < 1
        || request.reason() == null
        || request.reason().isBlank()) {
      throw new IllegalStateException("Furniture loss request snapshot is invalid");
    }
    return request;
  }

  private void settleFurnitureLossFailure(
      UUID findingId, Integer attemptCount, RuntimeException failure) {
    try {
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryFurnitureLossIntent intent =
                furnitureLosses.findByFindingIdForUpdate(findingId).orElse(null);
            if (intent == null
                || intent.getState() != FurnitureLossIntentState.PENDING
                || (attemptCount != null && intent.getAttemptCount() != attemptCount)) {
              return;
            }
            if (failure instanceof InventoryException exception
                && (exception.status() == HttpStatus.CONFLICT
                    || exception.status() == HttpStatus.UNPROCESSABLE_ENTITY
                    || exception.status() == HttpStatus.BAD_REQUEST)) {
              intent.block(
                  exception.status() == HttpStatus.CONFLICT
                      ? "MAINTENANCE_DECISION_CONFLICT"
                      : "MAINTENANCE_REQUEST_REJECTED");
            } else if (failure instanceof IllegalStateException) {
              intent.block("FROZEN_REQUEST_CORRUPTED");
            } else {
              long retrySeconds = Math.min(300L, 15L * Math.max(1, intent.getAttemptCount()));
              intent.transientFailure(
                  "MAINTENANCE_SERVICE_UNAVAILABLE",
                  OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(retrySeconds));
            }
            furnitureLosses.saveAndFlush(intent);
          });
    } catch (RuntimeException persistenceFailure) {
      log.error(
          "Could not persist furniture loss retry state for finding {}",
          findingId,
          persistenceFailure);
    }
  }

  private void settleFurnitureReconciliationFailure(
      UUID inventoryId, Integer attemptCount, RuntimeException failure) {
    try {
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryFurnitureReconciliationIntent intent =
                furnitureReconciliations.findByInventoryIdForUpdate(inventoryId).orElse(null);
            if (intent == null
                || intent.getState() != FurnitureReconciliationState.PENDING
                || (attemptCount != null && intent.getAttemptCount() != attemptCount)) {
              return;
            }
            if (failure instanceof InventoryException exception
                && exception.status() == HttpStatus.CONFLICT) {
              intent.block("ASSET_SNAPSHOT_CONFLICT");
            } else {
              long retrySeconds = Math.min(300L, 15L * Math.max(1, intent.getAttemptCount()));
              intent.transientFailure(
                  "ASSET_SERVICE_UNAVAILABLE",
                  OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(retrySeconds));
            }
            furnitureReconciliations.saveAndFlush(intent);
          });
    } catch (RuntimeException persistenceFailure) {
      log.error(
          "Could not persist furniture reconciliation retry state for inventory {}",
          inventoryId,
          persistenceFailure);
    }
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.furniture-reconciliation-recovery-delay-ms:5000}")
  public void recoverFurnitureReconciliations() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (InventoryFurnitureReconciliationIntent intent :
        furnitureReconciliations
            .findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscInventoryIdAsc(
                List.of(
                    FurnitureReconciliationState.PENDING,
                    FurnitureReconciliationState.TRANSIENT_FAILED),
                now)) {
      dispatchFurnitureReconciliation(intent.getInventoryId());
    }
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.furniture-loss-recovery-delay-ms:5000}")
  public void recoverFurnitureLosses() {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    for (InventoryFurnitureLossIntent intent :
        furnitureLosses
            .findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscFindingIdAsc(
                List.of(
                    FurnitureLossIntentState.PENDING,
                    FurnitureLossIntentState.TRANSIENT_FAILED),
                now)) {
      dispatchFurnitureLoss(intent.getFindingId());
    }
  }

  private InventoryDependencyGateway.Validation validateAssets(List<InventoryFinding> all) {
    List<UUID> assetIds =
        all.stream()
            .map(InventoryFinding::getAssetId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    if (assetIds.isEmpty()) {
      return new InventoryDependencyGateway.Validation(
          OffsetDateTime.now(ZoneOffset.UTC), canonicalHash(List.of()), List.of());
    }
    InventoryDependencyGateway.Validation validation = dependencies.validateAssets(assetIds);
    if (validation.assets() == null
        || validation.assets().size() != assetIds.size()
        || !validation.validationDigest().matches("^[0-9a-f]{64}$")
        || validation.assets().stream()
            .anyMatch(item -> item.found() && !RENTAL_ITEM_STATUSES.contains(item.status()))) {
      throw InventoryException.dependency("Asset-service returned malformed validation truth");
    }
    return new InventoryDependencyGateway.Validation(
        validation.validatedAt(),
        semanticValidationDigest(validation.assets()),
        validation.assets());
  }

  private Map<String, Object> acknowledgementFacts(
      InventorySession session,
      RevisionState revisions,
      CompletionFinalPlan finalPlan,
      InventoryDependencyGateway.Validation validation,
      FurnitureCompletionFact furniture,
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
    result.put("validationAssets", semanticValidationAssets(mapper.valueToTree(validation.assets())));
    result.put("furnitureAssetSnapshotSha256", furniture.assetSnapshotSha256());
    result.put("furnitureReviewSha256", furniture.reviewSha256());
    result.put("furnitureObservation", furniture.observation());
    result.put("statistics", statistics);
    result.put("risks", risks);
    result.put("validatedFindings", semanticValidatedFindings(validatedFindings));
    return result;
  }

  private String semanticValidationDigest(
      List<InventoryDependencyGateway.ValidationItem> assets) {
    return semanticValidationDigest(mapper.valueToTree(assets));
  }

  private String semanticValidationDigest(JsonNode assets) {
    return canonicalHash(semanticValidationAssets(assets));
  }

  private List<Object> semanticValidationAssets(JsonNode assets) {
    if (assets == null || !assets.isArray()) {
      throw new IllegalArgumentException("Inventory validation assets must be an array");
    }
    List<Object> semantic = new ArrayList<>();
    for (JsonNode asset : assets) {
      if (!asset.isObject()) {
        throw new IllegalArgumentException("Inventory validation asset must be an object");
      }
      ObjectNode value = ((ObjectNode) asset).deepCopy();
      value.remove("version");
      semantic.add(convert(value, Object.class));
    }
    return List.copyOf(semantic);
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

  private List<CompletionRisk> risks(
      InventorySession session,
      List<InventoryFinding> all,
      List<ValidatedFinding> validatedFindings) {
    Map<UUID, ValidatedFinding> current =
        validatedFindings.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    ValidatedFinding::findingId,
                    java.util.function.Function.identity(),
                    (left, right) -> {
                      throw new IllegalStateException("Duplicate validated finding");
                    },
                    LinkedHashMap::new));
    List<CompletionRisk> result = new ArrayList<>();
    for (InventoryFinding finding : all) {
      ValidatedFinding truth = current.get(finding.getId());
      if (truth == null) {
        throw new IllegalStateException("Inventory validation does not cover every finding");
      }
      if (truth.currentSnapshot() == null) {
        result.add(new CompletionRisk(finding.getId(), "MISSING"));
      }
      if (!truth.conflicts().isEmpty()) {
        result.add(new CompletionRisk(finding.getId(), "CONFLICT"));
      }
      if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
        result.add(new CompletionRisk(finding.getId(), "NOT_INSPECTED"));
      }
      if (finding.getInspection() == InspectionState.WORK_STAGED) {
        FindingPlanSnapshot plan = activePlanSnapshot(finding).orElse(null);
        if (finding.getMaintenancePlanFingerprintSha256() == null
            || plan == null
            || !finding.getMaintenancePlanFingerprintSha256().equals(plan.getFingerprint())
            || !plan.getFingerprint().equals(frozenPlanFingerprint.sha256(read(plan.getSourceSnapshot())))) {
          result.add(new CompletionRisk(finding.getId(), "PLAN_STALE"));
        }
      }
      for (FindingMediaReference reference :
          mediaReferences.findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
              finding.getId(), finding.getRevision())) {
        if (mediaFacts
            .findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
                reference.getMediaId(),
                reference.getGeneration(),
                "INVENTORY_FINDING",
                finding.getId(),
                session.getWarehouseId(),
                "READY")
            .isEmpty()) {
          result.add(new CompletionRisk(finding.getId(), "MEDIA_NOT_READY"));
          break;
        }
      }
      if (finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING
          || finding.getMutationState()
              == dev.buhanzaz.rwms.inventory.domain.MutationState.PLAN_RESOLVE_PENDING) {
        result.add(new CompletionRisk(finding.getId(), "MUTATION_IN_FLIGHT"));
      }
    }
    return result.stream()
        .distinct()
        .sorted(Comparator.comparing(CompletionRisk::findingId).thenComparing(CompletionRisk::code))
        .toList();
  }

  private List<ValidatedFinding> validatedFindings(
      InventorySession session,
      List<InventoryFinding> all,
      InventoryDependencyGateway.Validation validation) {
    return validatedFindings(session, all, validation, false);
  }

  private List<ValidatedFinding> validatedFindings(
      InventorySession session,
      List<InventoryFinding> all,
      InventoryDependencyGateway.Validation validation,
      boolean includeUninspectedRepairs) {
    Map<UUID, InventoryDependencyGateway.ValidationItem> currentByAsset =
        new LinkedHashMap<>();
    for (InventoryDependencyGateway.ValidationItem item : validation.assets()) {
      currentByAsset.put(item.assetId(), item);
    }
    Map<UUID, JsonNode> repairsByAsset =
        currentRepairSnapshots(all, includeUninspectedRepairs);
    return all.stream()
        .map(
            finding -> {
              InventoryDependencyGateway.ValidationItem item =
                  finding.getAssetId() == null
                      ? null
                      : currentByAsset.get(finding.getAssetId());
              CurrentItemSnapshot current =
                  currentItemSnapshot(
                      item,
                      finding.getAssetId() == null
                          ? mapper.createArrayNode()
                          : repairsByAsset.getOrDefault(
                              finding.getAssetId(), mapper.createArrayNode()));
              return new ValidatedFinding(
                  finding.getId(),
                  current,
                  conflictViews(finding, session.getWarehouseId(), current));
            })
        .toList();
  }

  private Map<UUID, JsonNode> currentRepairSnapshots(
      List<InventoryFinding> all, boolean includeUninspected) {
    List<UUID> assetIds =
        all.stream()
            .filter(
                value ->
                    includeUninspected
                        || value.getInspection() != InspectionState.NOT_INSPECTED)
            .map(InventoryFinding::getAssetId)
            .filter(java.util.Objects::nonNull)
            .distinct()
            .sorted()
            .toList();
    if (assetIds.isEmpty()) return Map.of();
    InventoryDependencyGateway.RepairSnapshots response =
        dependencies.repairSnapshots(assetIds);
    if (response == null
        || response.assets() == null
        || !response.assets().stream()
            .map(InventoryDependencyGateway.RepairAssetSnapshot::assetId)
            .toList()
            .equals(assetIds)) {
      throw InventoryException.dependency(
          "Maintenance-service returned incomplete inventory repair truth");
    }
    Map<UUID, JsonNode> result = new LinkedHashMap<>();
    for (InventoryDependencyGateway.RepairAssetSnapshot asset : response.assets()) {
      JsonNode snapshot = mapper.valueToTree(asset.repairs());
      if (!snapshot.isArray() || result.put(asset.assetId(), snapshot) != null) {
        throw InventoryException.dependency(
            "Maintenance-service returned malformed inventory repair truth");
      }
    }
    return result;
  }

  private CurrentItemSnapshot currentItemSnapshot(
      InventoryDependencyGateway.ValidationItem item, JsonNode repairsSnapshot) {
    if (item == null || !item.found()) return null;
    return new CurrentItemSnapshot(
        item.assetId(),
        item.version(),
        item.warehouseId(),
        item.status(),
        item.displayCanonicalNumber(),
        item.tenantSnapshot(),
        item.passportSnapshot(),
        item.contentsSnapshot(),
        repairsSnapshot);
  }

  private CurrentItemSnapshot currentItemSnapshot(
      InventoryDependencyGateway.AssetSnapshot item) {
    if (item == null) return null;
    return new CurrentItemSnapshot(
        item.assetId(),
        item.version(),
        item.warehouseId(),
        item.status(),
        item.displayCanonicalNumber(),
        item.tenantSnapshot(),
        mapper.createObjectNode(),
        mapper.createArrayNode(),
        mapper.createArrayNode());
  }

  private ValidatedFinding validatedFinding(
      InventorySession session, InventoryFinding finding, CurrentItemSnapshot current) {
    return new ValidatedFinding(
        finding.getId(),
        current,
        conflictViews(finding, session.getWarehouseId(), current));
  }

  private static boolean isTerminalDispositionStatus(String status) {
    return "WRITTEN_OFF".equals(status) || "LOST".equals(status);
  }

  private String numberResolutionOutcome(
      UUID inventoryWarehouseId, CurrentItemSnapshot current, boolean existingFinding) {
    if (current == null) return existingFinding ? "MISSING_CONFLICT" : "NOT_FOUND";
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      return "CROSS_WAREHOUSE_CONFLICT";
    }
    if (isTerminalDispositionStatus(current.status())) return "EXCLUDED_STATUS_CONFLICT";
    return "MATCHED";
  }

  private List<ConflictView> conflictViews(
      InventoryFinding finding,
      UUID inventoryWarehouseId,
      CurrentItemSnapshot current) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) {
      return List.of();
    }
    CurrentItemSnapshot baseline = inspectionBaselineSnapshot(finding);
    String currentFingerprint = semanticFingerprint(current);
    if (finding.getConflictResolutionStrategy() != null
        && currentFingerprint.equals(finding.getConflictResolutionCurrentSha256())) {
      return List.of();
    }
    List<ConflictView> conflicts = new ArrayList<>();
    if (current == null) {
      conflicts.add(
          new ConflictView(
              "RENTAL_ITEM_MISSING",
              "Бытовка отсутствует в актуальном реестре",
              baseline.assetId().toString(),
              null));
      return List.copyOf(conflicts);
    }
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      conflicts.add(
          new ConflictView(
              "OTHER_WAREHOUSE",
              "Бытовка относится к другому складу",
              inventoryWarehouseId.toString(),
              current.warehouseId().toString()));
    }
    if (!baseline.warehouseId().equals(current.warehouseId())) {
      conflicts.add(
          new ConflictView(
              "WAREHOUSE_CHANGED",
              "Склад бытовки изменился после осмотра",
              baseline.warehouseId().toString(),
              current.warehouseId().toString()));
    }
    if (!baseline.status().equals(current.status())) {
      String code =
          isTerminalDispositionStatus(current.status())
              ? "WRITTEN_OFF"
              : "RENTED".equals(current.status()) ? "RENTED" : "STATUS_CHANGED";
      conflicts.add(
          new ConflictView(
              code,
              "Статус бытовки изменился после осмотра",
              baseline.status(),
              current.status()));
    }
    if (!java.util.Objects.equals(baseline.tenantSnapshot(), current.tenantSnapshot())) {
      conflicts.add(
          new ConflictView(
              "TENANT_CHANGED",
              "Арендатор бытовки изменился после осмотра",
              baseline.tenantSnapshot(),
              current.tenantSnapshot()));
    }
    if (!baseline.displayCanonicalNumber().equals(current.displayCanonicalNumber())) {
      conflicts.add(
          new ConflictView(
              "NUMBER_CHANGED",
              "Номер бытовки изменился после осмотра",
              baseline.displayCanonicalNumber(),
              current.displayCanonicalNumber()));
    }
    if (!canonicalJsonTreeHash(passportWithoutTenant(baseline.passportSnapshot()))
        .equals(canonicalJsonTreeHash(passportWithoutTenant(current.passportSnapshot())))) {
      conflicts.add(
          new ConflictView(
              "PASSPORT_CHANGED",
              "Паспорт бытовки изменился после осмотра",
              write(passportWithoutTenant(baseline.passportSnapshot())),
              write(passportWithoutTenant(current.passportSnapshot()))));
    }
    if (!canonicalJsonTreeHash(baseline.contentsSnapshot())
        .equals(canonicalJsonTreeHash(current.contentsSnapshot()))) {
      conflicts.add(
          new ConflictView(
              "CONTENTS_CHANGED",
              "Состав бытовки изменился после осмотра",
              write(baseline.contentsSnapshot()),
              write(current.contentsSnapshot())));
    }
    if (!canonicalJsonTreeHash(baseline.repairsSnapshot())
        .equals(canonicalJsonTreeHash(current.repairsSnapshot()))) {
      conflicts.add(
          new ConflictView(
              "REPAIRS_CHANGED",
              "Ремонты бытовки изменились после осмотра",
              write(baseline.repairsSnapshot()),
              write(current.repairsSnapshot())));
    }
    return conflicts.stream()
        .distinct()
        .sorted(Comparator.comparing(ConflictView::code))
        .toList();
  }

  private String semanticFingerprint(CurrentItemSnapshot current) {
    if (current == null) return canonicalHash(Map.of("missing", true));
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("assetId", current.assetId());
    value.put("warehouseId", current.warehouseId());
    value.put("status", current.status());
    value.put("displayCanonicalNumber", current.displayCanonicalNumber());
    value.put("tenantSnapshot", current.tenantSnapshot());
    value.put("passportSnapshot", canonicalJsonValue(passportWithoutTenant(current.passportSnapshot())));
    value.put("contentsSnapshot", canonicalJsonValue(current.contentsSnapshot()));
    value.put("repairsSnapshot", canonicalJsonValue(current.repairsSnapshot()));
    return canonicalHash(value);
  }

  private JsonNode passportWithoutTenant(JsonNode passport) {
    if (passport == null || !passport.isObject()) return mapper.createObjectNode();
    ObjectNode result = ((ObjectNode) passport).deepCopy();
    result.remove("tenant");
    return result;
  }

  private CurrentItemSnapshot inspectionBaselineSnapshot(InventoryFinding finding) {
    if (finding.getInspection() == InspectionState.NOT_INSPECTED) return null;
    if (finding.getAssetId() == null
        || finding.getInspectionAssetVersion() == null
        || finding.getInspectionWarehouseId() == null
        || finding.getInspectionStatus() == null
        || finding.getInspectionDisplayCanonicalNumber() == null
        || finding.getInspectionPassportSnapshot() == null
        || finding.getInspectionContentsSnapshot() == null
        || finding.getInspectionRepairsSnapshot() == null) {
      throw new IllegalStateException("Inspected finding is missing its registry baseline");
    }
    return new CurrentItemSnapshot(
        finding.getAssetId(),
        finding.getInspectionAssetVersion(),
        finding.getInspectionWarehouseId(),
        finding.getInspectionStatus(),
        finding.getInspectionDisplayCanonicalNumber(),
        finding.getInspectionTenantSnapshot(),
        boundedSafeSnapshot(finding.getInspectionPassportSnapshot(), false, "inspection passport"),
        boundedSafeSnapshot(finding.getInspectionContentsSnapshot(), true, "inspection contents"),
        boundedSafeSnapshot(finding.getInspectionRepairsSnapshot(), true, "inspection repairs"));
  }

  private ConflictView blockingInspectionConflict(
      UUID inventoryWarehouseId, CurrentItemSnapshot current) {
    if (current == null) {
      return new ConflictView(
          "RENTAL_ITEM_MISSING",
          "Бытовка отсутствует в актуальном реестре",
          null,
          null);
    }
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      return new ConflictView(
          "OTHER_WAREHOUSE",
          "Бытовка относится к другому складу",
          inventoryWarehouseId.toString(),
          current.warehouseId().toString());
    }
    if (isTerminalDispositionStatus(current.status())) {
      return new ConflictView(
          "WRITTEN_OFF",
          "LOST".equals(current.status()) ? "Бытовка утеряна" : "Бытовка списана",
          null,
          current.status());
    }
    return null;
  }

  private FrozenStatistics calculateStatistics(
      InventorySession session,
      List<InventoryFinding> all,
      List<ValidatedFinding> validatedFindings) {
    Map<UUID, StatisticsReconciliation> reconciliationByFinding = new LinkedHashMap<>();
    for (ValidatedFinding finding : validatedFindings) {
      if (reconciliationByFinding.put(
              finding.findingId(),
              new StatisticsReconciliation(
                  validatedReconciliation(finding.currentSnapshot(), finding.conflicts())
                      == ReconciliationState.MISSING,
                  !finding.conflicts().isEmpty()))
          != null) {
        throw new IllegalStateException("Inventory validation contains duplicate findings");
      }
    }
    if (!reconciliationByFinding.keySet().equals(
        all.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))) {
      throw new IllegalStateException("Inventory validation does not cover every finding");
    }
    return calculateStatistics(session, all, reconciliationByFinding);
  }

  private FrozenStatistics calculatePersistedStatistics(
      InventorySession session, List<InventoryFinding> all) {
    Map<UUID, StatisticsReconciliation> reconciliationByFinding = new LinkedHashMap<>();
    for (InventoryFinding finding : all) {
      ReconciliationState reconciliation = finding.getReconciliation();
      if (reconciliationByFinding.put(
              finding.getId(),
              new StatisticsReconciliation(
                  reconciliation == ReconciliationState.MISSING,
                  reconciliation == ReconciliationState.CONFLICT))
          != null) {
        throw new IllegalStateException("Inventory findings contain duplicate identifiers");
      }
    }
    return calculateStatistics(session, all, reconciliationByFinding);
  }

  private FrozenStatistics calculateStatistics(
      InventorySession session,
      List<InventoryFinding> all,
      Map<UUID, StatisticsReconciliation> reconciliationByFinding) {
    int inspected =
        (int)
            all.stream()
                .filter(value -> value.getInspection() != InspectionState.NOT_INSPECTED)
                .count();
    int missing =
        (int)
            all.stream()
                .filter(
                    value ->
                        reconciliationByFinding.get(value.getId()).missing())
                .count();
    int ready =
        (int) all.stream().filter(value -> value.getInspection() == InspectionState.READY).count();
    int withWork =
        (int)
            all.stream()
                .filter(value -> value.getInspection() == InspectionState.WORK_STAGED)
                .count();
    int added =
        (int)
            all.stream()
                .filter(
                    value ->
                        value.getOrigin() == FindingOrigin.ADDED_NEW
                            || value.getOrigin() == FindingOrigin.ADDED_USED)
                .count();
    int unexpected =
        (int)
            all.stream()
                .filter(value -> value.getOrigin() == FindingOrigin.UNEXPECTED_EXISTING)
                .count();
    int conflicts =
        (int)
            reconciliationByFinding.values().stream()
                .filter(StatisticsReconciliation::conflict)
                .count();
    List<FindingPlanLine> activeLines = planLines.findActiveByInventoryId(session.getId());
    List<PlanTotal> totals =
        List.of(
            planTotal(activeLines, "WORK"), planTotal(activeLines, "MATERIAL"));
    BigDecimal workRaw = raw(totals, "WORK");
    BigDecimal materialRaw = raw(totals, "MATERIAL");
    long workMinor = workRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long materialMinor = materialRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long grandMinor = workRaw.add(materialRaw).setScale(0, RoundingMode.HALF_UP).longValueExact();
    int adjustment = Math.toIntExact(grandMinor - workMinor - materialMinor);
    BigDecimal normative =
        totals.stream()
            .map(PlanTotal::normative)
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .setScale(3, RoundingMode.UNNECESSARY);
    OffsetDateTime statisticsAt = terminalAt(session);
    if (statisticsAt == null) {
      statisticsAt = OffsetDateTime.now(ZoneOffset.UTC);
    }
    long duration =
        Math.max(0, ChronoUnit.SECONDS.between(session.getStartedAt(), statisticsAt));
    return new FrozenStatistics(
        session.getExpectedPopulationCount(),
        inspected,
        missing,
        ready,
        withWork,
        added,
        unexpected,
        conflicts,
        count(totals, "WORK"),
        count(totals, "MATERIAL"),
        workMinor,
        materialMinor,
        grandMinor,
        adjustment,
        normative.stripTrailingZeros().toPlainString(),
        duration,
        aggregateLines(activeLines));
  }

  private List<StatisticsLine> aggregateLines(List<FindingPlanLine> activeLines) {
    Map<StatisticsKey, BigDecimal> quantities = new LinkedHashMap<>();
    for (FindingPlanLine line : activeLines) {
      StatisticsKey key =
          new StatisticsKey(
              line.getSourceKind(),
              line.getCatalogVersionId(),
              line.getCatalogNodeId(),
              line.getNormalizedDescription(),
              line.getLineType(),
              line.getUnit(),
              line.getUnitPriceMinor());
      quantities.merge(key, line.getQuantity(), BigDecimal::add);
    }
    Comparator<StatisticsKey> order =
        Comparator.comparing(StatisticsKey::type)
            .thenComparing(StatisticsKey::kind)
            .thenComparing(
                StatisticsKey::catalogVersionId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                StatisticsKey::catalogNodeId, Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(
                StatisticsKey::normalizedDescription,
                Comparator.nullsFirst(Comparator.naturalOrder()))
            .thenComparing(StatisticsKey::unit)
            .thenComparingLong(StatisticsKey::unitPriceMinor);
    return quantities.entrySet().stream()
        .sorted(Map.Entry.comparingByKey(order))
        .map(
            entry -> {
              StatisticsKey key = entry.getKey();
              BigDecimal quantity = entry.getValue();
              long rowTotal =
                  quantity
                      .multiply(BigDecimal.valueOf(key.unitPriceMinor()))
                      .setScale(0, RoundingMode.HALF_UP)
                      .longValueExact();
              return new StatisticsLine(
                  key.kind(),
                  key.catalogVersionId(),
                  key.catalogNodeId(),
                  key.normalizedDescription(),
                  key.type(),
                  key.unit(),
                  key.unitPriceMinor(),
                  quantity.stripTrailingZeros().toPlainString(),
                  rowTotal);
            })
        .toList();
  }

  private PlanTotal planTotal(List<FindingPlanLine> lines, String type) {
    List<FindingPlanLine> selected =
        lines.stream().filter(line -> type.equals(line.getLineType())).toList();
    BigDecimal raw =
        selected.stream()
            .map(
                line ->
                    line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    BigDecimal normative =
        selected.stream()
            .map(line -> line.getNormativeMinutes().multiply(line.getQuantity()))
            .reduce(BigDecimal.ZERO, BigDecimal::add);
    return new PlanTotal(type, selected.size(), raw, normative);
  }

  private void persistValidation(
      InventorySession session,
      InventoryDependencyGateway.Validation validation,
      String acknowledgement,
      Object snapshot) {
    validationItems.deleteByInventoryId(session.getId());
    validationSnapshots.saveAndFlush(
        new InventoryValidationSnapshot(
            session.getId(),
            session.getRevision(),
            validation.validationDigest(),
            acknowledgement,
            validation.validatedAt(),
            write(Map.of("preview", snapshot, "validation", validation))));
    Map<UUID, UUID> findingByAsset = new LinkedHashMap<>();
    for (InventoryFinding finding :
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(session.getId())) {
      if (finding.getAssetId() != null) findingByAsset.put(finding.getAssetId(), finding.getId());
    }
    for (InventoryDependencyGateway.ValidationItem item : validation.assets()) {
      UUID findingId = findingByAsset.get(item.assetId());
      if (findingId == null) {
        throw InventoryException.dependency("Asset validation returned an unrequested asset");
      }
      boolean foundInOwningWarehouse =
          item.found() && session.getWarehouseId().equals(item.warehouseId());
      validationItems.save(
          new InventoryValidationItem(
              session.getId(),
              item.assetId(),
              foundInOwningWarehouse,
              foundInOwningWarehouse ? item.version() : null,
              foundInOwningWarehouse ? item.status() : null,
              foundInOwningWarehouse ? item.warehouseId() : null,
              findingId));
    }
  }

  private ValidationRecord validationRecord(UUID inventoryId) {
    InventoryValidationSnapshot value =
        validationSnapshots
            .findById(inventoryId)
            .orElseThrow(
                () ->
                    new InventoryException(
                        HttpStatus.CONFLICT,
                        "INVENTORY_ACKNOWLEDGEMENT_STALE",
                        "Completion preview is required"));
    JsonNode stored = read(value.getSnapshotBody());
    return new ValidationRecord(
        value.getValidationSha256(),
        value.getAcknowledgementSha256(),
        value.getSessionRevision(),
        stored.path("validation"),
        convert(stored.path("preview"), CompletionPreview.class));
  }

  private void persistStatistics(InventorySession session, FrozenStatistics value) {
    completionStatistics.saveAndFlush(
        new InventoryCompletionStatistics(
            session.getId(),
            value.expectedCount(),
            value.inspectedCount(),
            value.missingCount(),
            value.readyCount(),
            value.withWorkCount(),
            value.addedCount(),
            value.unexpectedExistingCount(),
            value.conflictCount(),
            value.workLineCount(),
            value.materialLineCount(),
            value.workTotalMinor(),
            value.materialTotalMinor(),
            value.grandTotalMinor(),
            value.roundingAdjustmentMinor(),
            new BigDecimal(value.normativeMinutes()),
            value.durationSeconds()));
    for (StatisticsLine line : value.aggregateLines()) {
      statisticsLines.save(
          new InventoryStatisticsLine(
              session.getId(),
              line.aggregationKind(),
              line.catalogVersionId(),
              line.catalogNodeId(),
              line.normalizedDescription(),
              line.type(),
              line.unit(),
              line.unitPriceMinor(),
              new BigDecimal(line.quantity()),
              line.rowTotalMinor()));
    }
  }

  private void createPublicationIntents(
      InventorySession session,
      InventoryFinalPlan finalPlan,
      List<InventoryFinalPlanEntry> entries,
      OpaqueActorReference actor) {
    for (InventoryFinalPlanEntry entry : entries) {
      if (!entry.isHasWork()) continue;
      InventoryPublicationIntent intent =
          publications.saveAndFlush(
              InventoryPublicationIntent.ready(
                  session.getId(),
                  entry.getFindingId(),
                  Math.max(1, entry.getFindingRevision()),
                  finalPlan.getFinalPlanVersion(),
                  finalPlan.getFinalPlanSha256(),
                  entry.getTargetKind()));
      if (intent.getState() == PublicationState.READY) {
        appendPublication(intent, session, "inventory.publication.ready.v1", actor);
      }
    }
  }

  private List<InventoryPublicationIntent> selectPublications(
      UUID inventoryId, UUID idempotencyKey, PublishFindingsRequest request) {
    List<InventoryPublicationIntent> all =
        publications.findAllByInventoryIdOrderByFindingId(inventoryId);
    if (request.allEligible()) {
      if (!request.findings().isEmpty()) {
        throw new IllegalArgumentException("allEligible cannot be combined with selected findings");
      }
      return all.stream()
          .filter(
              value ->
                  value.getState() == PublicationState.READY
                      || value.getState() == PublicationState.TRANSIENT_FAILED)
          .filter(
              value ->
                  !publicationAttempts.existsByPublicationIntentIdAndIdempotencyKey(
                      value.getId(), idempotencyKey))
          .toList();
    }
    if (request.findings().isEmpty()) {
      throw new IllegalArgumentException("Selected publication request requires findings");
    }
    Map<UUID, InventoryPublicationIntent> byFinding = new LinkedHashMap<>();
    all.forEach(value -> byFinding.put(value.getFindingId(), value));
    List<InventoryPublicationIntent> selected = new ArrayList<>();
    Set<UUID> selectedIds = new HashSet<>();
    for (PublicationExpectation expected : request.findings()) {
      if (!selectedIds.add(expected.findingId())) {
        throw new IllegalArgumentException("Publication selection contains duplicates");
      }
      InventoryPublicationIntent intent = byFinding.get(expected.findingId());
      if (intent == null) throw InventoryException.notFound("Publication intent not found");
      if (publicationAttempts.existsByPublicationIntentIdAndIdempotencyKey(
          intent.getId(), idempotencyKey)) {
        continue;
      }
      expectRevision(intent.getRevision(), expected.expectedPublicationRevision());
      if (intent.getState() != PublicationState.READY
          && intent.getState() != PublicationState.TRANSIENT_FAILED) {
        throw InventoryException.conflict("Publication intent is not eligible");
      }
      selected.add(intent);
    }
    return selected;
  }

  private void dispatchPublication(
      OpaqueActorReference eventActor,
      InventorySession session,
      InventoryPublicationIntent original,
      UUID idempotencyKey,
      boolean reconcile,
      String preconditionHash) {
    InventoryPublicationIntent pending =
        independentTransactions.execute(
            status -> {
              InventoryPublicationIntent intent =
                  requirePublication(session.getId(), original.getFindingId());
              String requestHash = publicationRequestHash(session, intent);
              if (reconcile) intent.reconcileRetry(preconditionHash);
              else intent.request(requestHash, preconditionHash);
              InventoryPublicationIntent saved = publications.saveAndFlush(intent);
              publicationAttempts.saveAndFlush(
                  new InventoryPublicationAttempt(
                      saved.getId(),
                      saved.getAttemptCount(),
                      idempotencyKey,
                      reconcile
                          ? "RECONCILE_RETRY"
                          : saved.getAttemptCount() == 1 ? "REQUEST" : "RETRY",
                      saved.getRequestSha256()));
              appendPublication(saved, session, "inventory.publication.requested.v1", eventActor);
              return saved;
            });
    try {
      JsonNode request = publicationRequest(session, pending);
      PublicationTarget result =
          dispatchPublicationEffect(session, pending, idempotencyKey, request);
      independentTransactions.executeWithoutResult(
          status -> {
            InventoryPublicationIntent intent =
                requirePublication(session.getId(), pending.getFindingId());
            intent.succeed(result.value());
            InventoryPublicationIntent saved = publications.saveAndFlush(intent);
            insertPublicationAttempt(
                saved,
                idempotencyKey,
                reconcile,
                "SUCCEEDED",
                null,
                result.value());
            appendPublication(saved, session, "inventory.publication.succeeded.v1", eventActor);
          });
    } catch (InventoryException exception) {
      settlePublicationFailure(
          session, pending.getFindingId(), idempotencyKey, reconcile, eventActor, exception);
    } catch (RuntimeException exception) {
      log.warn(
          "Inventory publication dispatch deferred for inventory {} finding {}",
          session.getId(),
          pending.getFindingId(),
          exception);
      settlePublicationFailure(
          session,
          pending.getFindingId(),
          idempotencyKey,
          reconcile,
          eventActor,
          InventoryException.dependency("Maintenance publication dispatch failed"));
    }
  }

  /**
   * Completion writes READY intents inside its terminal transaction and dispatches only after the
   * transaction commits. A process failure in between therefore leaves a durable READY intent for
   * the scheduler, while a failure after the PENDING transition reuses its durable attempt key.
   */
  private void dispatchReadyPublications(UUID inventoryId) {
    InventorySession session;
    try {
      session = requireCompleted(inventoryId);
    } catch (RuntimeException exception) {
      log.error("Unable to load completed inventory {} for publication dispatch", inventoryId, exception);
      return;
    }
    for (InventoryPublicationIntent intent :
        publications.findAllByInventoryIdOrderByFindingId(inventoryId)) {
      if (intent.getState() != PublicationState.READY) continue;
      try {
        dispatchPublication(
            PUBLICATION_RECOVERY_ACTOR,
            session,
            intent,
            servicePublicationIdempotencyKey(intent),
            false,
            null);
      } catch (RuntimeException exception) {
        // The ready intent is durable and will be picked up by the recovery scheduler.
        log.error(
            "Unable to dispatch ready inventory publication {} for inventory {}",
            intent.getId(),
            inventoryId,
            exception);
      }
    }
  }

  private UUID servicePublicationIdempotencyKey(InventoryPublicationIntent intent) {
    return UUID.nameUUIDFromBytes(
        ("rwms:inventory-service:publication:" + intent.getMaintenanceSourceKey())
            .getBytes(StandardCharsets.UTF_8));
  }

  private void settlePublicationFailure(
      InventorySession session,
      UUID findingId,
      UUID idempotencyKey,
      boolean reconcile,
      OpaqueActorReference eventActor,
      InventoryException exception) {
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryPublicationIntent intent = requirePublication(session.getId(), findingId);
          if (intent.getState() != PublicationState.PENDING) return;
          boolean blocked = exception.status() == HttpStatus.CONFLICT;
          if (blocked) intent.block("SOURCE_PRECONDITION_CONFLICT");
          else intent.transientFailure();
          InventoryPublicationIntent saved = publications.saveAndFlush(intent);
          insertPublicationAttempt(
              saved,
              idempotencyKey,
              reconcile,
              blocked ? "BLOCKED" : "TRANSIENT_FAILED",
              blocked ? "SOURCE_PRECONDITION_CONFLICT" : "DEPENDENCY_UNAVAILABLE",
              null);
          appendPublication(
              saved,
              session,
              blocked
                  ? "inventory.publication.blocked.v1"
                  : "inventory.publication.transient-failed.v1",
              eventActor);
        });
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.publication-recovery-delay-ms:5000}")
  public void recoverPendingPublications() {
    for (InventoryPublicationIntent ready :
        publications.findTop20ByStateOrderByUpdatedAtAsc(PublicationState.READY)) {
      recoverReadyPublication(ready);
    }
    OffsetDateTime eligibleBefore = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30);
    for (InventoryPublicationIntent pending :
        publications.findTop20ByStateOrderByUpdatedAtAsc(PublicationState.PENDING)) {
      try {
        if (pending.getUpdatedAt().isAfter(eligibleBefore)) continue;
        InventoryPublicationAttempt attempt =
            publicationAttempts
                .findByPublicationIntentIdAndAttemptNo(
                    pending.getId(), pending.getAttemptCount())
                .orElseThrow(
                    () -> new IllegalStateException("Pending publication has no durable attempt"));
        InventorySession session = requireCompleted(pending.getInventoryId());
        JsonNode request = publicationRequest(session, pending);
        PublicationTarget result =
            dispatchPublicationEffect(session, pending, attempt.getIdempotencyKey(), request);
        independentTransactions.executeWithoutResult(
            status -> {
              InventoryPublicationIntent intent =
                  requirePublication(session.getId(), pending.getFindingId());
              if (intent.getState() != PublicationState.PENDING) return;
              intent.succeed(result.value());
              InventoryPublicationIntent saved = publications.saveAndFlush(intent);
              insertPublicationAttempt(
                  saved,
                  attempt.getIdempotencyKey(),
                  false,
                  "SUCCEEDED",
                  null,
                  result.value());
              appendPublication(
                  saved,
                  session,
                  "inventory.publication.succeeded.v1",
                  PUBLICATION_RECOVERY_ACTOR);
            });
      } catch (InventoryException exception) {
        try {
          settleRecoveredPublicationFailure(pending, exception);
        } catch (RuntimeException settlementException) {
          // A failed state transition for this row must not prevent the remaining recovery batch.
          log.error(
              "Unable to settle failed inventory publication {}", pending.getId(), settlementException);
        }
      } catch (RuntimeException exception) {
        // A poison row must never prevent recovery of independent pending publications.
        log.error("Unable to recover inventory publication {}", pending.getId(), exception);
      }
    }
  }

  private void recoverReadyPublication(InventoryPublicationIntent ready) {
    try {
      InventorySession session = requireCompleted(ready.getInventoryId());
      dispatchPublication(
          PUBLICATION_RECOVERY_ACTOR,
          session,
          ready,
          servicePublicationIdempotencyKey(ready),
          false,
          null);
    } catch (RuntimeException exception) {
      // A READY record is the durable post-commit hand-off when completion died before dispatch.
      log.error("Unable to recover ready inventory publication {}", ready.getId(), exception);
    }
  }

  private void settleRecoveredPublicationFailure(
      InventoryPublicationIntent pending, InventoryException exception) {
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryPublicationIntent intent =
              requirePublication(pending.getInventoryId(), pending.getFindingId());
          if (intent.getState() != PublicationState.PENDING) return;
          InventoryPublicationAttempt attempt =
              publicationAttempts
                  .findByPublicationIntentIdAndAttemptNo(intent.getId(), intent.getAttemptCount())
                  .orElseThrow(
                      () -> new IllegalStateException("Pending publication has no durable attempt"));
          InventorySession session = requireCompleted(intent.getInventoryId());
          boolean blocked = exception.status() == HttpStatus.CONFLICT;
          if (blocked) intent.block("SOURCE_PRECONDITION_CONFLICT");
          else intent.transientFailure();
          InventoryPublicationIntent saved = publications.saveAndFlush(intent);
          String failureCode = blocked ? "SOURCE_PRECONDITION_CONFLICT" : "DEPENDENCY_UNAVAILABLE";
          insertPublicationAttempt(
              saved,
              attempt.getIdempotencyKey(),
              false,
              blocked ? "BLOCKED" : "TRANSIENT_FAILED",
              failureCode,
              null);
          appendPublication(
              saved,
              session,
              blocked
                  ? "inventory.publication.blocked.v1"
                  : "inventory.publication.transient-failed.v1",
              PUBLICATION_RECOVERY_ACTOR);
        });
  }

  private JsonNode publicationRequest(InventorySession session, InventoryPublicationIntent intent) {
    if (intent.getFinalPlanVersion() != null) {
      return finalPlanPublicationRequest(session, intent);
    }
    InventoryFinding finding = requireFinding(session.getId(), intent.getFindingId());
    if (finding.getInspection() != InspectionState.WORK_STAGED) {
      throw InventoryException.conflict("Finding has no staged maintenance plan");
    }
    FindingPlanSnapshot frozenPlan =
        activePlanSnapshot(finding)
            .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
    ObjectNode request = mapper.createObjectNode();
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("sourceRevision", intent.getSourceRevision());
    request.put("rentalItemId", finding.getAssetId().toString());
    request.put("rentalItemVersion", finding.getAssetVersion());
    request.put("dispatchDate", session.getBusinessDate().toString());
    request.put("planFingerprint", finding.getMaintenancePlanFingerprintSha256());
    request.set("snapshot", read(frozenPlan.getSourceSnapshot()));
    return request;
  }

  private JsonNode finalPlanPublicationRequest(
      InventorySession session, InventoryPublicationIntent intent) {
    InventoryFinalPlan plan =
        finalPlans
            .findById(session.getId())
            .orElseThrow(() -> InventoryException.conflict("Inventory final plan is missing"));
    if (plan.getFinalPlanVersion() != intent.getFinalPlanVersion()
        || !plan.getFinalPlanSha256().equals(intent.getFinalPlanSha256())) {
      throw InventoryException.conflict("Publication final-plan source is stale");
    }
    InventoryFinalPlanEntry entry =
        finalPlanEntries
            .findByInventoryIdAndFinalPlanVersionAndFindingId(
                session.getId(), intent.getFinalPlanVersion(), intent.getFindingId())
            .orElseThrow(() -> InventoryException.conflict("Publication final-plan finding is missing"));
    if (!entry.isHasWork()
        || entry.getTargetKind() == null
        || intent.getTargetKind() != entry.getTargetKind()
        || entry.getFindingRevision() != intent.getSourceRevision()
        || entry.getAssetId() == null
        || entry.getAssetVersion() == null
        || entry.getPlanFingerprintSha256() == null) {
      throw InventoryException.conflict("Publication final-plan work evidence is invalid");
    }
    InventoryFinding finding = requireFinding(session.getId(), intent.getFindingId());
    FindingPlanSnapshot frozenPlan =
        activePlanSnapshot(finding)
            .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
    if (!entry.getPlanFingerprintSha256().equals(frozenPlan.getFingerprint())) {
      throw InventoryException.conflict("Publication frozen maintenance plan is stale");
    }
    FinalPlanReconciliationDecision decision =
        entry.getReconciliationDecision() == null
            ? new FinalPlanReconciliationDecision(FinalPlanReconciliationStrategy.CREATE, null, null)
            : finalPlanDecision(read(entry.getReconciliationDecision()));
    ObjectNode request = mapper.createObjectNode();
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("finalPlanVersion", intent.getFinalPlanVersion());
    request.put("finalPlanSha256", intent.getFinalPlanSha256());
    request.put("findingRevision", entry.getFindingRevision());
    request.put("assetId", entry.getAssetId().toString());
    request.put("assetVersion", entry.getAssetVersion());
    request.put("planFingerprintSha256", entry.getPlanFingerprintSha256());
    request.put("snapshotSchemaVersion", frozenPlan.getSnapshotSchemaVersion());
    request.put("priority", entry.getPriority());
    request.put("movementToRepair", entry.isMovementToRepair());
    if (entry.getMovementScheduledDate() == null) request.putNull("movementScheduledDate");
    else request.put("movementScheduledDate", entry.getMovementScheduledDate().toString());
    request.put("repairScheduledDate", entry.getRepairScheduledDate().toString());
    request.put("strategy", decision.strategy().name());
    if (decision.selectedTargetKind() == null) request.putNull("selectedTargetKind");
    else request.put("selectedTargetKind", decision.selectedTargetKind().name());
    if (decision.selectedTargetId() == null) request.putNull("selectedTargetId");
    else request.put("selectedTargetId", decision.selectedTargetId().toString());
    request.set("snapshot", read(frozenPlan.getSourceSnapshot()));
    request.set("media", frozenPlanMedia(frozenPlan));
    return request;
  }

  private PublicationTarget dispatchPublicationEffect(
      InventorySession session,
      InventoryPublicationIntent intent,
      UUID idempotencyKey,
      JsonNode request) {
    if (intent.getFinalPlanVersion() == null) {
      InventoryDependencyGateway.RepairUpsert legacy =
          dependencies.upsertRepair(session.getId(), intent.getFindingId(), idempotencyKey, request);
      return new PublicationTarget(
          new InventoryPublicationIntent.PublicationTarget(
              FinalPlanTargetKind.REPAIR, legacy.repairId(), null, legacy.repairId()));
    }
    return new PublicationTarget(
        publicationTarget(
            dependencies.applyReconciliation(
                session.getId(), intent.getFindingId(), idempotencyKey, request)));
  }

  private InventoryPublicationIntent.PublicationTarget publicationTarget(JsonNode response) {
    try {
      MaintenancePublicationOutcome outcome =
          MaintenancePublicationOutcome.valueOf(response.path("outcome").asText());
      String resultSnapshot = canonicalWrite(response);
      if (outcome == MaintenancePublicationOutcome.MATCHED) {
        if (!explicitNull(response, "targetKind")
            || !explicitNull(response, "targetId")
            || !explicitNull(response, "estimateId")
            || !explicitNull(response, "repairId")) {
          throw new IllegalArgumentException();
        }
        return new InventoryPublicationIntent.PublicationTarget(
            outcome, null, null, null, null, resultSnapshot);
      }
      FinalPlanTargetKind kind = FinalPlanTargetKind.valueOf(response.path("targetKind").asText());
      UUID targetId = requiredUuid(response, "targetId", "maintenance publication target id");
      UUID estimateId = nullableUuid(response, "estimateId", "maintenance estimate id");
      UUID repairId = nullableUuid(response, "repairId", "maintenance repair id");
      InventoryPublicationIntent.PublicationTarget target =
          new InventoryPublicationIntent.PublicationTarget(
              outcome, kind, targetId, estimateId, repairId, resultSnapshot);
      if ((kind == FinalPlanTargetKind.ESTIMATE
              && (!targetId.equals(estimateId) || repairId != null))
          || (kind == FinalPlanTargetKind.REPAIR
              && (!targetId.equals(repairId) || estimateId != null))
          || (outcome == MaintenancePublicationOutcome.SUCCESSOR
              && kind != FinalPlanTargetKind.REPAIR)) {
        throw new IllegalArgumentException();
      }
      return target;
    } catch (RuntimeException exception) {
      throw InventoryException.dependency("Maintenance reconciliation result is malformed");
    }
  }

  private Optional<FindingPlanSnapshot> activePlanSnapshot(InventoryFinding finding) {
    String fingerprint = finding.getMaintenancePlanFingerprintSha256();
    if (fingerprint == null) {
      return Optional.empty();
    }
    return planSnapshots.findFirstByFindingIdAndFingerprintOrderByFindingRevisionDesc(
        finding.getId(), fingerprint);
  }

  private String publicationRequestHash(
      InventorySession session, InventoryPublicationIntent intent) {
    return canonicalHash(publicationRequest(session, intent));
  }

  private void insertPublicationAttempt(
      InventoryPublicationIntent intent,
      UUID idempotencyKey,
      boolean reconcile,
      String state,
      String failureCode,
      InventoryPublicationIntent.PublicationTarget target) {
    InventoryPublicationAttempt attempt =
        publicationAttempts
            .findByPublicationIntentIdAndAttemptNo(intent.getId(), intent.getAttemptCount())
            .orElseThrow(() -> new IllegalStateException("Publication attempt request is missing"));
    publicationAttemptResults.saveAndFlush(
        new InventoryPublicationAttemptResult(
            attempt.getId(),
            state,
            failureCode,
            failureCode == null ? null : hash(failureCode),
            target == null ? null : target.repairId(),
            target == null ? null : target.outcome(),
            target == null ? null : target.maintenanceResult()));
  }

  private void appendPublication(
      InventoryPublicationIntent intent,
      InventorySession session,
      String eventType,
      OpaqueActorReference actor) {
    ObjectNode payload = mapper.createObjectNode();
    payload.put("inventoryId", intent.getInventoryId().toString());
    payload.put("findingId", intent.getFindingId().toString());
    payload.put("publicationIntentId", intent.getId().toString());
    payload.put("warehouseId", session.getWarehouseId().toString());
    payload.put("publicationRevision", intent.getRevision());
    payload.put("state", intent.getState().name());
    payload.put("attemptCount", intent.getAttemptCount());
    if (intent.getFinalPlanVersion() == null) payload.putNull("finalPlanVersion");
    else payload.put("finalPlanVersion", intent.getFinalPlanVersion());
    if (intent.getFinalPlanSha256() == null) payload.putNull("finalPlanSha256");
    else payload.put("finalPlanSha256", intent.getFinalPlanSha256());
    if (intent.getTargetKind() == null) payload.putNull("targetKind");
    else payload.put("targetKind", intent.getTargetKind().name());
    if (intent.getTargetId() == null) payload.putNull("targetId");
    else payload.put("targetId", intent.getTargetId().toString());
    if (intent.getMaintenanceEstimateId() == null) payload.putNull("maintenanceEstimateId");
    else payload.put("maintenanceEstimateId", intent.getMaintenanceEstimateId().toString());
    if (intent.getMaintenanceRepairId() == null) payload.putNull("maintenanceRepairId");
    else payload.put("maintenanceRepairId", intent.getMaintenanceRepairId().toString());
    if (intent.getMaintenanceOutcome() == null) payload.putNull("maintenanceOutcome");
    else payload.put("maintenanceOutcome", intent.getMaintenanceOutcome().name());
    if (intent.getMaintenanceResult() == null) payload.putNull("maintenanceResult");
    else payload.set("maintenanceResult", read(intent.getMaintenanceResult()));
    if (intent.getBlockedFailureCode() == null) payload.putNull("failureCode");
    else payload.put("failureCode", intent.getBlockedFailureCode());
    ObjectNode source = payload.putObject("sourceReference");
    source.put("inventoryId", intent.getInventoryId().toString());
    source.put("findingId", intent.getFindingId().toString());
    source.put("sourceRevision", intent.getSourceRevision());
    if (intent.getFinalPlanVersion() == null) source.putNull("finalPlanVersion");
    else source.put("finalPlanVersion", intent.getFinalPlanVersion());
    source.put(
        "requestSha256",
        intent.getRequestSha256() == null
            ? hash(
                intent.getInventoryId()
                    + ":"
                    + intent.getFindingId()
                    + ":"
                    + intent.getSourceRevision())
            : intent.getRequestSha256());
    if ("inventory.publication.ready.v1".equals(eventType)) {
      events.initialize(
          "PUBLICATION",
          intent.getId(),
          eventType,
          PUBLICATION_TOPIC,
          payload,
          correlationId(),
          null,
          actor);
    } else {
      events.append(
          "PUBLICATION",
          intent.getId(),
          events.currentVersion("PUBLICATION", intent.getId()),
          eventType,
          PUBLICATION_TOPIC,
          payload,
          correlationId(),
          null,
          actor);
    }
  }

  private PublicationBatch publicationBatch(UUID inventoryId) {
    List<PublicationView> views =
        publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
            .map(this::publicationView)
            .toList();
    return new PublicationBatch(inventoryId, aggregatePublicationState(views), views);
  }

  private String aggregatePublicationState(List<PublicationView> intents) {
    List<PublicationView> required =
        intents.stream().filter(value -> value.state() != PublicationState.NOT_REQUIRED).toList();
    if (required.isEmpty()) return "NOT_REQUESTED";
    if (required.stream().allMatch(value -> value.state() == PublicationState.SUCCEEDED)) {
      return "SUCCEEDED";
    }
    if (required.stream().anyMatch(value -> value.state() == PublicationState.SUCCEEDED)) {
      return "PARTIAL";
    }
    if (required.stream()
        .anyMatch(
            value ->
                value.state() == PublicationState.READY
                    || value.state() == PublicationState.PENDING
                    || value.state() == PublicationState.TRANSIENT_FAILED)) {
      return "PENDING";
    }
    return "BLOCKED";
  }

  private SessionView sessionView(InventorySession value) {
    Set<UUID> inventoryIds = Set.of(value.getId());
    SessionCounts counts =
        sessionCounts(inventoryIds).getOrDefault(value.getId(), SessionCounts.EMPTY);
    List<PublicationView> publicationViews =
        sessionPublicationViews(inventoryIds).getOrDefault(value.getId(), List.of());
    FrozenStatistics statistics =
        value.getLifecycle() == SessionLifecycle.COMPLETED ? readStatistics(value.getId()) : null;
    CancellationAudit cancellation =
        value.getLifecycle() == SessionLifecycle.CANCELLED
            ? new CancellationAudit(value.getCancellationReason(), value.getCancelledAt())
            : null;
    return new SessionView(
        value.getId(),
        value.getRevision(),
        value.getWarehouseId(),
        value.getWarehouseVersion(),
        value.getWarehouseTimeZone(),
        sessionMapper.toInventoryActorView(value),
        value.getBusinessDate(),
        value.getLifecycle(),
        value.getReviewStage(),
        furnitureReconciliationState(value),
        value.getExpectedPopulationCount(),
        counts.findingCount(),
        counts.inspectedCount(),
        value.getStartedAt(),
        terminalAt(value),
        aggregatePublicationState(publicationViews),
        membershipMovements
            .findAllByInventoryIdOrderByOccurredAtAscIdAsc(value.getId())
            .stream()
            .map(sessionMapper::toMembershipMovementView)
            .toList(),
        statistics,
        cancellation);
  }

  private SessionSummary sessionSummary(
      InventorySession value, SessionCounts counts, List<PublicationView> publicationViews) {
    return new SessionSummary(
        value.getId(),
        value.getRevision(),
        value.getWarehouseId(),
        value.getWarehouseVersion(),
        value.getWarehouseTimeZone(),
        sessionMapper.toInventoryActorView(value),
        value.getBusinessDate(),
        value.getLifecycle(),
        value.getReviewStage(),
        furnitureReconciliationState(value),
        value.getExpectedPopulationCount(),
        counts.findingCount(),
        counts.inspectedCount(),
        value.getStartedAt(),
        terminalAt(value),
        aggregatePublicationState(publicationViews));
  }

  private FurnitureReconciliationState furnitureReconciliationState(InventorySession session) {
    if (session.getReviewStage() != InventoryReviewStage.FURNITURE) {
      return FurnitureReconciliationState.NOT_REQUIRED;
    }
    return furnitureReconciliations
        .findById(session.getId())
        .map(InventoryFurnitureReconciliationIntent::getState)
        .orElse(
            session.getFurnitureReviewSha256() == null || !furnitureReviewHasItems(session)
                ? FurnitureReconciliationState.NOT_REQUIRED
                : FurnitureReconciliationState.READY);
  }

  private boolean furnitureReviewHasItems(InventorySession session) {
    if (session.getFurnitureStockObservation() == null) {
      return false;
    }
    JsonNode review = read(session.getFurnitureStockObservation());
    return review.isObject() && review.path("items").isArray() && !review.path("items").isEmpty();
  }

  private FindingView findingView(InventoryFinding value) {
    return findingViews(List.of(value)).getFirst();
  }

  private FindingView findingView(InventoryFinding value, ValidatedFinding validation) {
    return findingViews(List.of(value), Map.of(value.getId(), validation)).getFirst();
  }

  private List<FindingView> findingViews(List<InventoryFinding> values) {
    return findingViews(values, Map.of());
  }

  private List<FindingView> findingViews(
      List<InventoryFinding> values, Map<UUID, ValidatedFinding> currentOverrides) {
    if (values.isEmpty()) return List.of();
    Set<UUID> findingIds =
        values.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Set<UUID> inventoryIds =
        values.stream()
            .map(InventoryFinding::getInventoryId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, UUID> warehouseByInventory = new LinkedHashMap<>();
    Set<UUID> completedInventoryIds = new LinkedHashSet<>();
    for (InventorySession session : sessions.findAllById(inventoryIds)) {
      warehouseByInventory.put(session.getId(), session.getWarehouseId());
      if (session.getLifecycle() == SessionLifecycle.COMPLETED) {
        completedInventoryIds.add(session.getId());
      }
    }
    Map<UUID, ExpectedItemSnapshot> expectedByFinding = new LinkedHashMap<>();
    for (InventoryExpectedItem expected :
        expectedItems.findAllByFindingIdInOrderByFindingId(findingIds)) {
      InventoryFinding finding =
          values.stream()
              .filter(value -> value.getId().equals(expected.getFindingId()))
              .findFirst()
              .orElseThrow();
      UUID warehouseId = warehouseByInventory.get(finding.getInventoryId());
      if (warehouseId == null) {
        throw new IllegalStateException("Finding session is missing");
      }
      expectedByFinding.put(
          expected.getFindingId(), expectedItemSnapshot(expected, warehouseId));
    }
    Map<UUID, List<MediaReference>> mediaByFinding = new LinkedHashMap<>();
    Map<UUID, Set<UUID>> readyImageIdsByFinding = new LinkedHashMap<>();
    for (FindingMediaReference reference : mediaReferences.findActiveByFindingIds(findingIds)) {
      mediaByFinding
          .computeIfAbsent(reference.getFindingId(), ignored -> new ArrayList<>())
          .add(new MediaReference(reference.getMediaId(), reference.getGeneration()));
      if ("IMAGE".equals(reference.getMediaKind())) {
        readyImageIdsByFinding
            .computeIfAbsent(reference.getFindingId(), ignored -> new LinkedHashSet<>())
            .add(reference.getMediaId());
      }
    }
    Map<UUID, PublicationView> publicationByFinding = new LinkedHashMap<>();
    for (InventoryPublicationIntent publication :
        publications.findAllByFindingIdInOrderByFindingId(findingIds)) {
      publicationByFinding.put(publication.getFindingId(), publicationView(publication));
    }
    Map<UUID, List<FindingPlanLine>> linesByFinding = new LinkedHashMap<>();
    for (FindingPlanLine line : planLines.findActiveByFindingIds(findingIds)) {
      linesByFinding.computeIfAbsent(line.getFindingId(), ignored -> new ArrayList<>()).add(line);
    }
    Map<UUID, List<FindingPlanStage>> stagesByFinding = new LinkedHashMap<>();
    for (FindingPlanStage stage : planStages.findActiveByFindingIds(findingIds)) {
      stagesByFinding.computeIfAbsent(stage.getFindingId(), ignored -> new ArrayList<>()).add(stage);
    }
    Map<UUID, FrozenPlanView> planByFinding = new LinkedHashMap<>();
    for (FindingPlanSnapshot snapshot : planSnapshots.findActiveByFindingIds(findingIds)) {
      planByFinding.put(
          snapshot.getFindingId(),
          frozenPlanView(
              snapshot,
              linesByFinding.getOrDefault(snapshot.getFindingId(), List.of()),
              stagesByFinding.getOrDefault(snapshot.getFindingId(), List.of())));
    }
    Map<UUID, ValidatedFinding> validatedByFinding =
        completedValidatedFindings(completedInventoryIds);
    validatedByFinding.putAll(currentOverrides);
    return values.stream()
        .map(
            value ->
                findingView(
                    value,
                    warehouseByInventory.get(value.getInventoryId()),
                    expectedByFinding.get(value.getId()),
                    planByFinding.get(value.getId()),
                    mediaByFinding.getOrDefault(value.getId(), List.of()),
                    readyImageIdsByFinding.getOrDefault(value.getId(), Set.of()),
                    publicationByFinding.get(value.getId()),
                    validatedByFinding.get(value.getId())))
        .toList();
  }

  private Map<UUID, ValidatedFinding> completedValidatedFindings(
      Set<UUID> completedInventoryIds) {
    Map<UUID, ValidatedFinding> result = new LinkedHashMap<>();
    if (completedInventoryIds.isEmpty()) return result;
    for (InventoryValidationSnapshot snapshot :
        validationSnapshots.findAllById(completedInventoryIds)) {
      JsonNode body = read(snapshot.getSnapshotBody());
      JsonNode values = body.path("preview").path("validatedFindings");
      if (!values.isArray()) {
        throw new IllegalStateException(
            "Completed inventory validation snapshot is missing validated findings");
      }
      for (JsonNode value : values) {
        ValidatedFinding finding = convert(value, ValidatedFinding.class);
        if (result.put(finding.findingId(), finding) != null) {
          throw new IllegalStateException("Completed inventory validation projection is duplicated");
        }
      }
    }
    return result;
  }

  private ReconciliationState validatedReconciliation(
      CurrentItemSnapshot currentSnapshot, List<ConflictView> conflicts) {
    if (currentSnapshot == null) {
      return ReconciliationState.MISSING;
    }
    return conflicts.isEmpty() ? ReconciliationState.MATCHED : ReconciliationState.CONFLICT;
  }

  private FindingView findingView(
      InventoryFinding value,
      UUID inventoryWarehouseId,
      ExpectedItemSnapshot expectedSnapshot,
      FrozenPlanView frozenPlan,
      List<MediaReference> media,
      Set<UUID> readyImageIds,
      PublicationView publication,
      ValidatedFinding validatedFinding) {
    if (value.getOrigin() != FindingOrigin.EXPECTED) expectedSnapshot = null;
    if (value.getInspection() == InspectionState.WORK_STAGED && frozenPlan == null) {
      throw new IllegalStateException("WORK_STAGED finding is missing its frozen plan");
    }
    if (value.getInspection() != InspectionState.WORK_STAGED) frozenPlan = null;
    CurrentItemSnapshot currentSnapshot =
        validatedFinding == null
            ? currentItemSnapshot(value)
            : validatedFinding.currentSnapshot();
    List<ConflictView> conflicts =
        validatedFinding == null
            ? conflictViews(value, inventoryWarehouseId, currentSnapshot)
            : List.copyOf(validatedFinding.conflicts());
    ReconciliationState reconciliation =
        validatedFinding == null
            ? value.getReconciliation()
            : validatedReconciliation(currentSnapshot, conflicts);
    return new FindingView(
        value.getId(),
        value.getInventoryId(),
        value.getRevision(),
        value.getOrigin(),
        value.getInspection(),
        value.getInspection() == InspectionState.NOT_INSPECTED ? null : "INVENTORY",
        reconciliation,
        value.getAssetId(),
        value.getAssetVersion(),
        value.getDisplayCanonicalNumber(),
        value.getIdentityMatchKey(),
        observation(value.getPassportObservationState(), value.getPassportObservation()),
        observation(value.getEquipmentObservationState(), value.getEquipmentObservation()),
        value.getMutationState(),
        value.getMaintenancePlanFingerprintSha256(),
        value.getInspectionComment(),
        expectedSnapshot,
        inspectionBaselineSnapshot(value),
        currentSnapshot,
        conflicts,
        conflictResolutionView(value, currentSnapshot),
        frozenPlan,
        coverMediaId(value, media, readyImageIds),
        List.copyOf(media),
        publication);
  }

  private UUID coverMediaId(
      InventoryFinding finding, List<MediaReference> media, Set<UUID> readyImageIds) {
    if (finding.getCoverMediaId() != null
        && readyImageIds.contains(finding.getCoverMediaId())) {
      return finding.getCoverMediaId();
    }
    return media.stream()
        .map(MediaReference::mediaId)
        .filter(readyImageIds::contains)
        .findFirst()
        .orElse(null);
  }

  private ExpectedItemSnapshot expectedItemSnapshot(
      InventoryExpectedItem value, UUID warehouseId) {
    JsonNode passport = boundedSafeSnapshot(value.getPassportSnapshot(), false, "passport");
    return new ExpectedItemSnapshot(
        value.getAssetId(),
        value.getAssetVersion(),
        warehouseId,
        value.getAssetStatus(),
        value.getDisplayCanonicalNumber(),
        tenantSnapshot(passport),
        passport,
        boundedSafeSnapshot(value.getContentsSnapshot(), true, "contents"));
  }

  private CurrentItemSnapshot currentItemSnapshot(InventoryFinding value) {
    return currentItemSnapshot(
        value,
        value.getCurrentRepairsSnapshot() == null
            ? mapper.createArrayNode()
            : boundedSafeSnapshot(value.getCurrentRepairsSnapshot(), true, "current repairs"));
  }

  private CurrentItemSnapshot currentItemSnapshot(
      InventoryFinding value, JsonNode repairsSnapshot) {
    if (value.getAssetId() == null) return null;
    if (value.getCurrentWarehouseId() == null || value.getCurrentStatus() == null) {
      return null;
    }
    return new CurrentItemSnapshot(
        value.getAssetId(),
        value.getAssetVersion(),
        value.getCurrentWarehouseId(),
        value.getCurrentStatus(),
        value.getCurrentDisplayCanonicalNumber() == null
            ? value.getDisplayCanonicalNumber()
            : value.getCurrentDisplayCanonicalNumber(),
        value.getCurrentTenantSnapshot(),
        value.getCurrentPassportSnapshot() == null
            ? mapper.createObjectNode()
            : boundedSafeSnapshot(value.getCurrentPassportSnapshot(), false, "current passport"),
        value.getCurrentContentsSnapshot() == null
            ? mapper.createArrayNode()
            : boundedSafeSnapshot(value.getCurrentContentsSnapshot(), true, "current contents"),
        repairsSnapshot);
  }

  private ConflictResolutionView conflictResolutionView(
      InventoryFinding finding, CurrentItemSnapshot current) {
    if (finding.getConflictResolutionStrategy() == null) return null;
    if (finding.getConflictResolvedAt() == null
        || finding.getConflictResolutionCurrentSha256() == null) {
      throw new IllegalStateException("Conflict resolution audit is incomplete");
    }
    if (!semanticFingerprint(current)
        .equals(finding.getConflictResolutionCurrentSha256())) {
      return null;
    }
    return new ConflictResolutionView(
        finding.getConflictResolutionStrategy(),
        finding.getConflictResolutionReason(),
        finding.getConflictResolvedAt());
  }

  private FrozenPlanView frozenPlanView(
      FindingPlanSnapshot snapshot,
      List<FindingPlanLine> lines,
      List<FindingPlanStage> stages) {
    JsonNode source = read(snapshot.getSourceSnapshot());
    boolean sourceMovementToRepair =
        requiredBoolean(source, "movementToRepair", "frozen plan movement to repair");
    LogisticsPlanningMode sourceLogisticsPlanningMode =
        nullableLogisticsPlanningMode(
            source, "logisticsPlanningMode", "frozen plan logistics planning mode");
    LocalDate sourceLogisticsScheduledDate =
        nullableLocalDate(
            source, "logisticsScheduledDate", "frozen plan logistics scheduled date");
    if (!LogisticsPlanningMode.validInboundPlanning(
            sourceMovementToRepair, sourceLogisticsPlanningMode, sourceLogisticsScheduledDate)
        || sourceMovementToRepair != snapshot.isMovementToRepair()
        || sourceLogisticsPlanningMode != snapshot.getLogisticsPlanningMode()
        || !java.util.Objects.equals(
            sourceLogisticsScheduledDate, snapshot.getLogisticsScheduledDate())) {
      throw new IllegalStateException("Persisted frozen plan movement snapshot is invalid");
    }
    int priority = source.path("priority").asInt(-1);
    if (priority < 1 || priority > 5) {
      throw new IllegalStateException("Persisted frozen plan priority is invalid");
    }
    UUID coverMediaId =
        source.hasNonNull("coverMediaId")
            ? requiredUuid(source, "coverMediaId", "frozen plan cover media id")
            : null;
    JsonNode sourceLines = source.path("lines");
    JsonNode sourceStages = source.path("stages");
    List<FrozenPlanLineView> lineViews =
        lines.stream()
            .map(
                line -> {
                  JsonNode sourceLine = sourceLines.path(line.getLineNo());
                  return
                    new FrozenPlanLineView(
                        line.getId(),
                        line.getSourceKind(),
                        line.getLineType(),
                        line.getCatalogVersionId(),
                        line.getCatalogNodeId(),
                        line.getDescription(),
                        line.getNormalizedDescription(),
                        line.getUnit(),
                        exactDecimal(line.getQuantity()),
                        line.getUnitPriceMinor(),
                        exactDecimal(line.getNormativeMinutes()),
                        nullableText(sourceLine.get("groupComment")),
                        frozenPlanLineMediaReferences(sourceLine));
                })
            .toList();
    List<FrozenPlanStageView> stageViews =
        stages.stream()
            .map(
                stage -> {
                  JsonNode sourceStage = sourceStages.path(stage.getStageNo());
                  return
                    new FrozenPlanStageView(
                        requiredUuid(sourceStage, "id", "frozen plan stage id"),
                        stage.getStageNo(),
                        stage.getCatalogNodeId(),
                        stage.getCatalogNodeName(),
                        stage.getStageKind(),
                        stage.getRoutingQueueId(),
                        stage.getRoutingQueueName(),
                        stage.getRoutingQueueType(),
                        stage.isPhotoRequired(),
                        sourceStage.path("normativeDurationMinutes").asInt(0));
                })
            .toList();
    return new FrozenPlanView(
        snapshot.getPlanMode(),
        snapshot.getCatalogVersionId(),
        snapshot.getFingerprint(),
        priority,
        coverMediaId,
        snapshot.isMovementToRepair(),
        snapshot.getLogisticsPlanningMode(),
        snapshot.getLogisticsScheduledDate(),
        lineViews,
        stageViews);
  }

  private List<MediaReference> frozenPlanLineMediaReferences(JsonNode sourceLine) {
    if (sourceLine.isMissingNode() || sourceLine.isNull()) return List.of();
    if (!sourceLine.isObject()) {
      throw new IllegalStateException("Persisted frozen plan line is invalid");
    }
    JsonNode references = sourceLine.path("mediaReferences");
    if (references.isMissingNode() || references.isNull()) return List.of();
    if (!references.isArray() || references.size() > 100) {
      throw new IllegalStateException("Persisted frozen plan line media is invalid");
    }
    List<MediaReference> result = new ArrayList<>();
    Set<UUID> seenMediaIds = new HashSet<>();
    for (JsonNode reference : references) {
      if (!reference.isObject()) {
        throw new IllegalStateException("Persisted frozen plan line media is invalid");
      }
      UUID mediaId = requiredUuid(reference, "mediaId", "frozen plan line media id");
      long generation = reference.path("generation").asLong(-1);
      if (generation < 0 || !seenMediaIds.add(mediaId)) {
        throw new IllegalStateException("Persisted frozen plan line media is invalid");
      }
      result.add(new MediaReference(mediaId, generation));
    }
    return List.copyOf(result);
  }

  private UUID requiredUuid(JsonNode value, String field, String name) {
    try {
      return UUID.fromString(requiredText(value, field, name));
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted " + name + " is invalid", exception);
    }
  }

  private boolean requiredBoolean(JsonNode value, String field, String name) {
    JsonNode result = value.get(field);
    if (result == null || !result.isBoolean()) {
      throw new IllegalStateException("Persisted " + name + " is missing");
    }
    return result.booleanValue();
  }

  private LogisticsPlanningMode nullableLogisticsPlanningMode(
      JsonNode value, String field, String name) {
    JsonNode result = value.get(field);
    if (result == null || result.isNull()) return null;
    if (!result.isTextual()) {
      throw new IllegalStateException("Persisted " + name + " is invalid");
    }
    try {
      return LogisticsPlanningMode.valueOf(result.stringValue());
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted " + name + " is invalid", exception);
    }
  }

  private LocalDate nullableLocalDate(JsonNode value, String field, String name) {
    JsonNode result = value.get(field);
    if (result == null || result.isNull()) return null;
    if (!result.isTextual()) {
      throw new IllegalStateException("Persisted " + name + " is invalid");
    }
    try {
      return LocalDate.parse(result.stringValue());
    } catch (java.time.format.DateTimeParseException exception) {
      throw new IllegalStateException("Persisted " + name + " is invalid", exception);
    }
  }

  private String requiredText(JsonNode value, String field, String name) {
    String result = value.path(field).asText();
    if (result.isBlank()) {
      throw new IllegalStateException("Persisted " + name + " is missing");
    }
    return result;
  }

  private String nullableText(JsonNode value) {
    return value == null || value.isNull() ? null : value.asText();
  }

  private String tenantSnapshot(JsonNode passportSnapshot) {
    if (passportSnapshot == null || !passportSnapshot.isObject()) return null;
    return nullableText(passportSnapshot.get("tenant"));
  }

  private JsonNode boundedSafeSnapshot(String value, boolean arrayAllowed, String name) {
    if (value == null || value.length() > 65_536) {
      throw new IllegalStateException("Persisted " + name + " snapshot exceeds the safe bound");
    }
    JsonNode parsed;
    try {
      parsed = read(value);
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("Persisted " + name + " snapshot is invalid", exception);
    }
    if (!parsed.isObject() && !(arrayAllowed && parsed.isArray())) {
      throw new IllegalStateException("Persisted " + name + " snapshot has an unsafe shape");
    }
    return parsed;
  }

  private String exactDecimal(BigDecimal value) {
    return value.stripTrailingZeros().toPlainString();
  }

  private Map<UUID, SessionCounts> sessionCounts(Set<UUID> inventoryIds) {
    if (inventoryIds.isEmpty()) return Map.of();
    Map<UUID, SessionCounts> result = new LinkedHashMap<>();
    for (InventoryFindingRepository.InventoryFindingCounts counts :
        findings.countByInventoryIds(inventoryIds, InspectionState.NOT_INSPECTED)) {
      result.put(
          counts.getInventoryId(),
          new SessionCounts(counts.getFindingCount(), counts.getInspectedCount()));
    }
    return result;
  }

  private Map<UUID, List<PublicationView>> sessionPublicationViews(Set<UUID> inventoryIds) {
    if (inventoryIds.isEmpty()) return Map.of();
    Map<UUID, List<PublicationView>> result = new LinkedHashMap<>();
    for (InventoryPublicationIntent publication :
        publications.findAllByInventoryIdInOrderByInventoryIdAscFindingIdAsc(inventoryIds)) {
      result
          .computeIfAbsent(publication.getInventoryId(), ignored -> new ArrayList<>())
          .add(publicationView(publication));
    }
    return result;
  }

  private record SessionCounts(long findingCount, long inspectedCount) {
    private static final SessionCounts EMPTY = new SessionCounts(0, 0);
  }

  private PublicationView publicationView(InventoryPublicationIntent value) {
    return new PublicationView(
        value.getId(),
        value.getInventoryId(),
        value.getFindingId(),
        value.getRevision(),
        value.getState(),
        value.getSourceRevision(),
        value.getAttemptCount(),
        value.getFinalPlanVersion(),
        value.getTargetKind(),
        value.getTargetId(),
        value.getMaintenanceEstimateId(),
        value.getMaintenanceRepairId(),
        value.getMaintenanceOutcome(),
        value.getMaintenanceResult() == null ? null : read(value.getMaintenanceResult()),
        value.getBlockedFailureCode());
  }

  private FrozenStatistics readStatistics(UUID inventoryId) {
    return completionStatistics
        .findById(inventoryId)
        .map(
            value ->
                new FrozenStatistics(
                    value.getExpectedCount(),
                    value.getInspectedCount(),
                    value.getMissingCount(),
                    value.getReadyCount(),
                    value.getWithWorkCount(),
                    value.getAddedCount(),
                    value.getUnexpectedExistingCount(),
                    value.getConflictCount(),
                    value.getWorkLineCount(),
                    value.getMaterialLineCount(),
                    value.getWorkTotalMinor(),
                    value.getMaterialTotalMinor(),
                    value.getGrandTotalMinor(),
                    value.getRoundingAdjustmentMinor(),
                    value.getNormativeMinutes().stripTrailingZeros().toPlainString(),
                    value.getDurationSeconds(),
                    readStatisticsLines(inventoryId)))
        .orElseGet(this::zeroStatistics);
  }

  private List<StatisticsLine> readStatisticsLines(UUID inventoryId) {
    return statisticsLines
        .findAllByInventoryIdOrderByLineTypeAscCatalogVersionIdAscCatalogNodeIdAscNormalizedDescriptionAscUnitAscUnitPriceMinorAsc(
            inventoryId)
        .stream()
        .map(
            line ->
                new StatisticsLine(
                    line.getAggregationKind(),
                    line.getCatalogVersionId(),
                    line.getCatalogNodeId(),
                    line.getNormalizedDescription(),
                    line.getLineType(),
                    line.getUnit(),
                    line.getUnitPriceMinor(),
                    line.getQuantity().stripTrailingZeros().toPlainString(),
                    line.getRowTotalMinor()))
        .toList();
  }

  private FrozenStatistics sumStatistics(List<FrozenStatistics> values) {
    if (values.isEmpty()) return zeroStatistics();
    long work =
        values.stream().map(FrozenStatistics::workTotalMinor).reduce(0L, Math::addExact);
    long material =
        values.stream().map(FrozenStatistics::materialTotalMinor).reduce(0L, Math::addExact);
    long grand =
        values.stream().map(FrozenStatistics::grandTotalMinor).reduce(0L, Math::addExact);
    return new FrozenStatistics(
        sumInt(values, FrozenStatistics::expectedCount),
        sumInt(values, FrozenStatistics::inspectedCount),
        sumInt(values, FrozenStatistics::missingCount),
        sumInt(values, FrozenStatistics::readyCount),
        sumInt(values, FrozenStatistics::withWorkCount),
        sumInt(values, FrozenStatistics::addedCount),
        sumInt(values, FrozenStatistics::unexpectedExistingCount),
        sumInt(values, FrozenStatistics::conflictCount),
        sumInt(values, FrozenStatistics::workLineCount),
        sumInt(values, FrozenStatistics::materialLineCount),
        work,
        material,
        grand,
        Math.toIntExact(grand - work - material),
        values.stream()
            .map(value -> new BigDecimal(value.normativeMinutes()))
            .reduce(BigDecimal.ZERO, BigDecimal::add)
            .stripTrailingZeros()
            .toPlainString(),
        values.stream().map(FrozenStatistics::durationSeconds).reduce(0L, Math::addExact),
        List.of());
  }

  private FrozenStatistics summarizeStatistics(List<UUID> inventoryIds) {
    FrozenStatistics counts =
        sumStatistics(inventoryIds.stream().map(this::readStatistics).toList());
    List<InventoryStatisticsLine> lines =
        inventoryIds.stream()
            .flatMap(
                id ->
                    statisticsLines
                        .findAllByInventoryIdOrderByLineTypeAscCatalogVersionIdAscCatalogNodeIdAscNormalizedDescriptionAscUnitAscUnitPriceMinorAsc(
                            id)
                        .stream())
            .toList();
    BigDecimal workRaw = statisticsRaw(lines, "WORK");
    BigDecimal materialRaw = statisticsRaw(lines, "MATERIAL");
    long work = workRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long material = materialRaw.setScale(0, RoundingMode.HALF_UP).longValueExact();
    long grand = workRaw.add(materialRaw).setScale(0, RoundingMode.HALF_UP).longValueExact();
    return new FrozenStatistics(
        counts.expectedCount(),
        counts.inspectedCount(),
        counts.missingCount(),
        counts.readyCount(),
        counts.withWorkCount(),
        counts.addedCount(),
        counts.unexpectedExistingCount(),
        counts.conflictCount(),
        counts.workLineCount(),
        counts.materialLineCount(),
        work,
        material,
        grand,
        Math.toIntExact(grand - work - material),
        counts.normativeMinutes(),
        counts.durationSeconds(),
        aggregatePersistedLines(lines));
  }

  private BigDecimal statisticsRaw(List<InventoryStatisticsLine> lines, String type) {
    return lines.stream()
        .filter(line -> type.equals(line.getLineType()))
        .map(
            line ->
                line.getQuantity().multiply(BigDecimal.valueOf(line.getUnitPriceMinor())))
        .reduce(BigDecimal.ZERO, BigDecimal::add);
  }

  private List<StatisticsLine> aggregatePersistedLines(List<InventoryStatisticsLine> lines) {
    Map<StatisticsKey, BigDecimal> quantities = new LinkedHashMap<>();
    for (InventoryStatisticsLine line : lines) {
      StatisticsKey key =
          new StatisticsKey(
              line.getAggregationKind(),
              line.getCatalogVersionId(),
              line.getCatalogNodeId(),
              line.getNormalizedDescription(),
              line.getLineType(),
              line.getUnit(),
              line.getUnitPriceMinor());
      quantities.merge(key, line.getQuantity(), BigDecimal::add);
    }
    return quantities.entrySet().stream()
        .sorted(
            Map.Entry.comparingByKey(
                Comparator.comparing(StatisticsKey::type)
                    .thenComparing(StatisticsKey::kind)
                    .thenComparing(
                        StatisticsKey::catalogVersionId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        StatisticsKey::catalogNodeId,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(
                        StatisticsKey::normalizedDescription,
                        Comparator.nullsFirst(Comparator.naturalOrder()))
                    .thenComparing(StatisticsKey::unit)
                    .thenComparingLong(StatisticsKey::unitPriceMinor)))
        .map(
            entry -> {
              StatisticsKey key = entry.getKey();
              BigDecimal quantity = entry.getValue();
              return new StatisticsLine(
                  key.kind(),
                  key.catalogVersionId(),
                  key.catalogNodeId(),
                  key.normalizedDescription(),
                  key.type(),
                  key.unit(),
                  key.unitPriceMinor(),
                  quantity.stripTrailingZeros().toPlainString(),
                  quantity
                      .multiply(BigDecimal.valueOf(key.unitPriceMinor()))
                      .setScale(0, RoundingMode.HALF_UP)
                      .longValueExact());
            })
        .toList();
  }

  private int sumInt(
      List<FrozenStatistics> values, java.util.function.ToIntFunction<FrozenStatistics> extractor) {
    return values.stream().mapToInt(extractor).reduce(0, Math::addExact);
  }

  private FrozenStatistics zeroStatistics() {
    return new FrozenStatistics(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "0", 0, List.of());
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

  private InventorySession requireSession(UUID inventoryId) {
    return sessions
        .findById(inventoryId)
        .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
  }

  private InventorySession requireScopedSession(
      UUID inventoryId, InventoryAuthorizer.WarehouseScope scope) {
    if (!scope.unrestricted() && scope.warehouseIds().isEmpty()) {
      throw InventoryException.notFound("Inventory session not found");
    }
    return (scope.unrestricted()
            ? sessions.findById(inventoryId)
            : sessions.findByIdAndWarehouseIdIn(inventoryId, scope.warehouseIds()))
        .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
  }

  private InventorySession requireLifecycle(
      InventorySession session, SessionLifecycle expectedLifecycle) {
    if (session.getLifecycle() != expectedLifecycle) {
      throw InventoryException.conflict(
          expectedLifecycle == SessionLifecycle.ACTIVE
              ? "Inventory session is not active"
              : "Inventory session is not completed");
    }
    return session;
  }

  private InventorySession requireActive(UUID inventoryId) {
    InventorySession value = requireSession(inventoryId);
    if (value.getLifecycle() != SessionLifecycle.ACTIVE) {
      throw InventoryException.conflict("Inventory session is not active");
    }
    return value;
  }

  private InventorySession requireCompleted(UUID inventoryId) {
    InventorySession value = requireSession(inventoryId);
    if (value.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw InventoryException.conflict("Inventory session is not completed");
    }
    return value;
  }

  private InventoryFinding requireFinding(UUID inventoryId, UUID findingId) {
    return findings
        .findByIdAndInventoryIdAndMembershipActiveTrue(findingId, inventoryId)
        .orElseThrow(() -> InventoryException.notFound("Inventory finding not found"));
  }

  private InventoryPublicationIntent requirePublication(UUID inventoryId, UUID findingId) {
    return publications
        .findByInventoryIdAndFindingId(inventoryId, findingId)
        .orElseThrow(() -> InventoryException.notFound("Publication intent not found"));
  }

  private int mediaCount(InventoryFinding finding) {
    return Math.toIntExact(
        mediaReferences.countByFindingIdAndFindingRevision(
            finding.getId(), finding.getRevision()));
  }

  private OffsetDateTime terminalAt(InventorySession session) {
    return session.getCompletedAt() != null ? session.getCompletedAt() : session.getCancelledAt();
  }

  private Sort sessionSort(String value) {
    if (!Set.of("startedAt,asc", "startedAt,desc", "businessDate,asc", "businessDate,desc")
        .contains(value)) {
      throw InventoryException.badRequest("Unsupported session sort");
    }
    String field = value != null && value.startsWith("businessDate") ? "businessDate" : "startedAt";
    Sort.Direction direction =
        value != null && value.endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field).and(Sort.by("id"));
  }

  private Sort statisticsSort(String value) {
    if (!Set.of("completedAt,asc", "completedAt,desc", "businessDate,asc", "businessDate,desc")
        .contains(value)) {
      throw InventoryException.badRequest("Unsupported statistics sort");
    }
    String field =
        value != null && value.startsWith("businessDate") ? "businessDate" : "completedAt";
    Sort.Direction direction =
        value != null && value.endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field).and(Sort.by("id"));
  }

  private Specification<InventorySession> sessionFilter(
      UUID warehouseId,
      SessionLifecycle lifecycle,
      LocalDate businessDateFrom,
      LocalDate businessDateTo,
      OffsetDateTime startedFrom,
      OffsetDateTime startedTo,
      OffsetDateTime terminalFrom,
      OffsetDateTime terminalTo) {
    validateRange(businessDateFrom, businessDateTo, "business date");
    validateRange(startedFrom, startedTo, "started time");
    validateRange(terminalFrom, terminalTo, "terminal time");
    return (root, query, criteria) -> {
      List<jakarta.persistence.criteria.Predicate> predicates = new ArrayList<>();
      predicates.add(criteria.equal(root.get("warehouseId"), warehouseId));
      if (lifecycle != null) predicates.add(criteria.equal(root.get("lifecycle"), lifecycle));
      if (businessDateFrom != null) {
        predicates.add(criteria.greaterThanOrEqualTo(root.get("businessDate"), businessDateFrom));
      }
      if (businessDateTo != null) {
        predicates.add(criteria.lessThan(root.get("businessDate"), businessDateTo));
      }
      if (startedFrom != null) {
        predicates.add(criteria.greaterThanOrEqualTo(root.get("startedAt"), startedFrom));
      }
      if (startedTo != null) {
        predicates.add(criteria.lessThan(root.get("startedAt"), startedTo));
      }
      if (terminalFrom != null) {
        predicates.add(
            criteria.or(
                criteria.greaterThanOrEqualTo(root.get("completedAt"), terminalFrom),
                criteria.greaterThanOrEqualTo(root.get("cancelledAt"), terminalFrom)));
      }
      if (terminalTo != null) {
        predicates.add(
            criteria.or(
                criteria.lessThan(root.get("completedAt"), terminalTo),
                criteria.lessThan(root.get("cancelledAt"), terminalTo)));
      }
      return criteria.and(predicates.toArray(jakarta.persistence.criteria.Predicate[]::new));
    };
  }

  private void validateRange(LocalDate from, LocalDate to, String field) {
    if (from != null && to != null) {
      if (to.isBefore(from)) throw InventoryException.badRequest(field + " range is reversed");
      if (ChronoUnit.DAYS.between(from, to) > 366) {
        throw InventoryException.badRequest(field + " range exceeds 366 days");
      }
    }
  }

  private void validateRange(OffsetDateTime from, OffsetDateTime to, String field) {
    if (from != null && to != null) {
      if (to.isBefore(from)) throw InventoryException.badRequest(field + " range is reversed");
      if (ChronoUnit.DAYS.between(from, to) > 366) {
        throw InventoryException.badRequest(field + " range exceeds 366 days");
      }
    }
  }

  private Sort findingSort(String value) {
    if (!Set.of(
            "createdAt,asc",
            "createdAt,desc",
            "displayCanonicalNumber,asc",
            "displayCanonicalNumber,desc")
        .contains(value)) {
      throw InventoryException.badRequest("Unsupported finding sort");
    }
    String field =
        value != null && value.startsWith("displayCanonicalNumber")
            ? "displayCanonicalNumber"
            : "createdAt";
    Sort.Direction direction =
        value != null && value.endsWith(",desc") ? Sort.Direction.DESC : Sort.Direction.ASC;
    return Sort.by(direction, field).and(Sort.by("id"));
  }

  private void expectRevision(long actual, long expected) {
    if (actual != expected) throw InventoryException.conflict("Inventory revision is stale");
  }

  private OpaqueActorReference actor(Jwt jwt) {
    return new OpaqueActorReference(
        authorizer.subjectId(jwt).toString(), "USER", authorizer.profileRevision(jwt));
  }

  private OpaqueActorReference normalizedAssetActor(OpaqueActorReference actor) {
    return actor != null && Set.of("USER", "SERVICE").contains(actor.principalType())
        ? actor
        : ASSET_SYNC_ACTOR;
  }

  private String actorJson(Jwt jwt) {
    return write(actor(jwt));
  }

  private UUID correlationId() {
    String value = MDC.get("correlationId");
    try {
      return value == null ? UUID.randomUUID() : UUID.fromString(value);
    } catch (IllegalArgumentException exception) {
      return UUID.randomUUID();
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory value is not serializable", exception);
    }
  }

  private String canonicalWrite(Object value) {
    try {
      return mapper
          .writer()
          .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
          .writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory value is not serializable", exception);
    }
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Stored inventory JSON is invalid", exception);
    }
  }

  private boolean storedJsonEquals(String stored, JsonNode current) {
    if (stored == null || current == null || current.isNull()) {
      return stored == null && (current == null || current.isNull());
    }
    return canonicalJsonTreeHash(read(stored)).equals(canonicalJsonTreeHash(current));
  }

  private <T> T convert(JsonNode value, Class<T> type) {
    try {
      return mapper.treeToValue(value, type);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Stored inventory JSON has the wrong shape", exception);
    }
  }

  private Observation observation(
      dev.buhanzaz.rwms.inventory.domain.ObservationPresence presence, String value) {
    return new Observation(presence, value == null ? null : read(value));
  }

  private String json(JsonNode value) {
    return value == null || value.isNull() ? null : write(value);
  }

  private String hash(String value) {
    return InventoryEventChecksum.sha256(value);
  }

  private String canonicalHash(Object value) {
    return canonicalJson.sha256(value);
  }

  /**
   * PostgreSQL jsonb does not preserve object field order. Persisted immutable JSON facts must
   * therefore be hashed as ordinary map/list data rather than directly as an ObjectNode.
   */
  private String canonicalJsonTreeHash(JsonNode value) {
    return canonicalHash(canonicalJsonValue(value));
  }

  private Object canonicalJsonValue(JsonNode value) {
    try {
      return mapper.treeToValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory JSON cannot be canonicalized", exception);
    }
  }

  private String canonicalDisplayNumber(String value) {
    if (value == null) throw new IllegalArgumentException("Rental number is required");
    String display =
        value.trim().replaceAll("\\s+", " ").toUpperCase(Locale.forLanguageTag("ru-RU"));
    if (!display.matches("^[\\p{L}\\p{N}][\\p{L}\\p{N} -]{0,127}$")) {
      throw new IllegalArgumentException("Rental number contains unsupported punctuation");
    }
    return display;
  }

  private BigDecimal raw(List<PlanTotal> values, String type) {
    return values.stream()
        .filter(value -> type.equals(value.type()))
        .map(PlanTotal::raw)
        .findFirst()
        .orElse(BigDecimal.ZERO);
  }

  private int count(List<PlanTotal> values, String type) {
    return values.stream()
        .filter(value -> type.equals(value.type()))
        .mapToInt(PlanTotal::count)
        .findFirst()
        .orElse(0);
  }

  private record PlanningSpecification(
      long revision,
      int movementDailyCapacity,
      int repairDailyCapacity,
      List<DayOfWeek> workingWeekdays,
      List<LocalDate> holidays) {}

  /** A transient, validated proposal before one immutable final-plan version is persisted. */
  private record FinalPlanDraft(
      InventoryFinding finding,
      FindingPlanSnapshot snapshot,
      boolean hasWork,
      FinalPlanTargetKind targetKind,
      int order,
      Integer priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate,
      LocalDate repairScheduledDate,
      String collisionCandidates,
      String reconciliationDecision) {
    static FinalPlanDraft noWork(InventoryFinding finding) {
      return new FinalPlanDraft(
          finding, null, false, null, 0, null, false, null, null, "[]", null);
    }

    String planFingerprintSha256() {
      return snapshot == null ? null : snapshot.getFingerprint();
    }

    FinalPlanDraft withOrder(int value) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          value,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          collisionCandidates,
          reconciliationDecision);
    }

    FinalPlanDraft withDates(LocalDate movement, LocalDate repair) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movement,
          repair,
          collisionCandidates,
          reconciliationDecision);
    }

    FinalPlanDraft withCandidates(String candidates) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          candidates,
          reconciliationDecision);
    }

    FinalPlanDraft withDecision(String decision) {
      return new FinalPlanDraft(
          finding,
          snapshot,
          hasWork,
          targetKind,
          order,
          priority,
          movementToRepair,
          movementScheduledDate,
          repairScheduledDate,
          collisionCandidates,
          decision);
    }
  }

  private record FinalPlanCandidateSet(
      List<FinalPlanCandidateView> all, List<FinalPlanCandidateView> active) {}

  private record CompletionFinalPlan(
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> entries,
      List<FinalPlanDraft> drafts) {}

  private record PublicationTarget(InventoryPublicationIntent.PublicationTarget value) {}

  private record RevisionState(
      List<InventoryFinding> findings, List<RevisionExpectation> expectations) {}

  private record FurnitureObservation(ObservationPresence presence, String body) {}

  private record FurnitureReviewSubmission(
      String reviewBody,
      String reviewSha256,
      Map<UUID, Long> findingRevisions,
      Map<UUID, FurnitureObservation> equipmentObservationByFinding) {}

  private record FurnitureCompletionFact(
      String assetSnapshotSha256, String reviewSha256, JsonNode observation) {}

  private record FurnitureLossDispatch(
      UUID findingId,
      UUID idempotencyKey,
      int attemptCount,
      InventoryDependencyGateway.InventoryLossDispositionRequest request) {}

  private record FurnitureReconciliationDispatch(
      UUID inventoryId,
      UUID idempotencyKey,
      int attemptCount,
      InventoryDependencyGateway.FurnitureReconciliationRequest request) {}

  private record ValidationRecord(
      String validation,
      String acknowledgement,
      long sessionRevision,
      JsonNode validationTruth,
      CompletionPreview preview) {}

  private record PlanTotal(String type, int count, BigDecimal raw, BigDecimal normative) {}

  private record StatisticsReconciliation(boolean missing, boolean conflict) {}

  private record StatisticsKey(
      String kind,
      UUID catalogVersionId,
      UUID catalogNodeId,
      String normalizedDescription,
      String type,
      String unit,
      long unitPriceMinor) {}
}
