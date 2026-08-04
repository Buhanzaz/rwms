package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovement;
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
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
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
  private static final Set<String> RENTAL_ITEM_STATUSES =
      Set.of(
          "RENTED",
          "BOOKED",
          "REPAIR",
          "WAITING_REPAIR_CHECK",
          "WRITTEN_OFF",
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
  private final InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
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

  public InventoryApplicationService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
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
    this.furnitureReconciliations = furnitureReconciliations;
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
    InventoryDependencyGateway.WarehouseMetadata warehouse =
        dependencies.warehouse(request.warehouseId());
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
                            warehouse.id(),
                            warehouse.version(),
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
      boolean movementDeparted =
          departed
              && previousWarehouseId != null
              && previousWarehouseId.equals(session.getWarehouseId())
              && previousStatus != null
              && CAPTURE_STATUSES.contains(previousStatus);
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
      if (!snapshotRefreshed) continue;
      findings.saveAndFlush(finding);
      session.touch();
      sessions.saveAndFlush(session);
      if (movementDeparted) {
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
            .findByInventoryIdAndIdentityMatchKeyForUpdate(
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
      boolean reactivated = existing.changeMembership(true);
      boolean snapshotChanged =
          !java.util.Objects.equals(existing.getAssetVersion(), current.version())
              || !java.util.Objects.equals(
                  existing.getCurrentWarehouseId(), current.warehouseId())
              || !java.util.Objects.equals(existing.getCurrentStatus(), current.status())
              || !java.util.Objects.equals(
                  existing.getCurrentTenantSnapshot(), current.tenantSnapshot());
      if (!reactivated && !snapshotChanged) return;
      UUID previousWarehouseId = existing.getCurrentWarehouseId();
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
      if (reactivated && existing.getOrigin() == FindingOrigin.EXPECTED) {
        session.changeExpectedPopulation(1);
      }
      session.touch();
      sessions.saveAndFlush(session);
      if (reactivated) {
        membershipMovements.saveAndFlush(
            InventoryMembershipMovement.arrived(
                session.getId(),
                causationId,
                assetId,
                existing.getOrigin(),
                existing.getDisplayCanonicalNumber(),
                previousWarehouseId != null
                        && !previousWarehouseId.equals(session.getWarehouseId())
                    ? previousWarehouseId
                    : null,
                session.getWarehouseId(),
                current.status(),
                current.tenantSnapshot(),
                occurredAt));
        appendOwnerProof(
            existing,
            session.getWarehouseId(),
            actor,
            events.currentVersion("FINDING", existing.getId()),
            correlationId,
            causationId);
      }
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
            .findByInventoryIdAndIdentityMatchKey(inventoryId, resolved.identityMatchKey())
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
    } else if ("WRITTEN_OFF".equals(asset.status())) {
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
              .findByInventoryIdAndIdentityMatchKey(inventoryId, resolved.identityMatchKey())
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
    validateCoverMedia(request);
    if (request.inspection() == InspectionState.READY
        && currentTruth.currentSnapshot() != null
        && "AFTER_RENT".equals(currentTruth.currentSnapshot().status())
        && request.media().isEmpty()) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Для приёмки бытовки после аренды загрузите хотя бы одну фотографию");
    }
    validateReadyMedia(
        findingId, session.getWarehouseId(), request.media(), request.coverMediaId());
    InventoryDependencyGateway.FrozenPlan plan = null;
    if (request.inspection() == InspectionState.WORK_STAGED) {
      plan = dependencies.freezePlan(UUID.randomUUID(), freezeRequest(session, finding, request));
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
                  request.coverMediaId(),
                  actorJson(jwt));
              InventoryFinding result = findings.saveAndFlush(lockedFinding);
              persistMedia(result, request.media());
              if (frozenPlan != null) persistPlan(result, request.planSelection(), frozenPlan);
              invalidateFurnitureReviewAfterCabinChange(lockedSession);
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
              appendFindingFacts(
                  result,
                  lockedSession,
                  actor(jwt),
                  "inventory.finding.inspection-saved.v1");
              return result;
            });
    return findingView(saved, validatedFinding(session, saved, current));
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
                session, revisions, validation, furniture, statistics, risks, validatedFindings));
    CompletionPreview response =
        new CompletionPreview(
            inventoryId,
            session.getRevision(),
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
    RevisionState revisions =
        revisionState(session, request.expectedSessionRevision(), request.findingRevisions());
    InventoryDependencyGateway.Validation fresh = validateAssets(revisions.findings());
    ValidationRecord preview = validationRecord(inventoryId);
    if (!request.acknowledgementSha256().equals(preview.acknowledgement())
        || !request.validationSha256().equals(preview.validation())
        || preview.sessionRevision() != session.getRevision()
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
              InventorySession locked = requireActive(inventoryId);
              RevisionState lockedRevisions =
                  revisionState(locked, request.expectedSessionRevision(), request.findingRevisions());
              CompletionPreview completionSnapshot =
                  new CompletionPreview(
                      inventoryId,
                      locked.getRevision(),
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
              FrozenStatistics finalStatistics =
                  calculateStatistics(result, lockedRevisions.findings(), validatedFindings);
              persistStatistics(result, finalStatistics);
              createFurnitureReconciliationIntent(result);
              createPublicationIntents(result, revisions.findings(), actor(jwt));
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
    dispatchFurnitureReconciliation(completed.getId());
    return sessionView(requireSession(completed.getId()));
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
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    List<InventoryPublicationIntent> selected =
        selectPublications(inventoryId, idempotencyKey, request);
    for (InventoryPublicationIntent intent : selected) {
      dispatchPublication(jwt, session, intent, idempotencyKey, false, null);
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
        jwt,
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
    body.put("movementToShipment", selection.movementToShipment());
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
            || line.description() != null
            || line.type() != null
            || line.unit() != null
            || line.unitPriceMinor() != null
            || line.normativeMinutes() != null) {
          throw new IllegalArgumentException("CATALOG plan line evidence is invalid");
        }
      } else if (!manualMode
          || line.catalogNodeId() != null
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
    boolean movementToShipment =
        requiredBoolean(snapshot, "movementToShipment", "frozen movement to shipment");
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
        || movementToShipment != selection.movementToShipment()
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
            movementToShipment,
            logisticsPlanningMode,
            logisticsScheduledDate,
            catalogVersionId,
            frozen.fingerprint(),
            write(snapshot)));
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
    if (session.getFurnitureReviewSha256() == null || session.getFurnitureStockObservation() == null) {
      throw new IllegalStateException("Completed inventory has no confirmed furniture review");
    }
    JsonNode review = read(session.getFurnitureStockObservation());
    if (!review.isObject()
        || !session
            .getFurnitureAssetSnapshotSha256()
            .equals(review.path("assetSnapshotSha256").asText())) {
      throw new IllegalStateException("Stored furniture review is invalid");
    }
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
              requiredNonNegativeLong(
                  reviewedItem.path("observedStockQuantity"), "furniture review stock quantity"),
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
          transactions.execute(
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
      transactions.executeWithoutResult(
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

  private void settleFurnitureReconciliationFailure(
      UUID inventoryId, Integer attemptCount, RuntimeException failure) {
    try {
      transactions.executeWithoutResult(
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

  private String numberResolutionOutcome(
      UUID inventoryWarehouseId, CurrentItemSnapshot current, boolean existingFinding) {
    if (current == null) return existingFinding ? "MISSING_CONFLICT" : "NOT_FOUND";
    if (!inventoryWarehouseId.equals(current.warehouseId())) {
      return "CROSS_WAREHOUSE_CONFLICT";
    }
    if ("WRITTEN_OFF".equals(current.status())) return "EXCLUDED_STATUS_CONFLICT";
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
          "WRITTEN_OFF".equals(current.status())
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
    if (!canonicalHash(passportWithoutTenant(baseline.passportSnapshot()))
        .equals(canonicalHash(passportWithoutTenant(current.passportSnapshot())))) {
      conflicts.add(
          new ConflictView(
              "PASSPORT_CHANGED",
              "Паспорт бытовки изменился после осмотра",
              write(passportWithoutTenant(baseline.passportSnapshot())),
              write(passportWithoutTenant(current.passportSnapshot()))));
    }
    if (!canonicalHash(baseline.contentsSnapshot())
        .equals(canonicalHash(current.contentsSnapshot()))) {
      conflicts.add(
          new ConflictView(
              "CONTENTS_CHANGED",
              "Состав бытовки изменился после осмотра",
              write(baseline.contentsSnapshot()),
              write(current.contentsSnapshot())));
    }
    if (!canonicalHash(baseline.repairsSnapshot())
        .equals(canonicalHash(current.repairsSnapshot()))) {
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
    value.put("passportSnapshot", passportWithoutTenant(current.passportSnapshot()));
    value.put("contentsSnapshot", current.contentsSnapshot());
    value.put("repairsSnapshot", current.repairsSnapshot());
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
    if ("WRITTEN_OFF".equals(current.status())) {
      return new ConflictView(
          "WRITTEN_OFF", "Бытовка списана", null, current.status());
    }
    return null;
  }

  private FrozenStatistics calculateStatistics(
      InventorySession session,
      List<InventoryFinding> all,
      List<ValidatedFinding> validatedFindings) {
    Map<UUID, ValidatedFinding> validatedByFinding = new LinkedHashMap<>();
    for (ValidatedFinding finding : validatedFindings) {
      if (validatedByFinding.put(finding.findingId(), finding) != null) {
        throw new IllegalStateException("Inventory validation contains duplicate findings");
      }
    }
    if (!validatedByFinding.keySet().equals(
        all.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new)))) {
      throw new IllegalStateException("Inventory validation does not cover every finding");
    }
    int inspected =
        (int)
            all.stream()
                .filter(value -> value.getInspection() != InspectionState.NOT_INSPECTED)
                .count();
    int missing =
        (int)
            all.stream()
                .filter(
                    value -> {
                      ValidatedFinding validated = validatedByFinding.get(value.getId());
                      return validatedReconciliation(
                              validated.currentSnapshot(), validated.conflicts())
                          == ReconciliationState.MISSING;
                    })
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
            validatedFindings.stream().filter(value -> !value.conflicts().isEmpty()).count();
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
        aggregateLines(session.getId()));
  }

  private List<StatisticsLine> aggregateLines(UUID inventoryId) {
    Map<StatisticsKey, BigDecimal> quantities = new LinkedHashMap<>();
    for (FindingPlanLine line : planLines.findActiveByInventoryId(inventoryId)) {
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
      InventorySession session, List<InventoryFinding> all, OpaqueActorReference actor) {
    for (InventoryFinding finding : all) {
      InventoryPublicationIntent intent =
          publications.saveAndFlush(
              finding.getInspection() == InspectionState.WORK_STAGED
                  ? InventoryPublicationIntent.ready(
                      session.getId(), finding.getId(), Math.max(1, finding.getRevision()))
                  : InventoryPublicationIntent.notRequired(
                      session.getId(), finding.getId(), Math.max(1, finding.getRevision())));
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
      Jwt jwt,
      InventorySession session,
      InventoryPublicationIntent original,
      UUID idempotencyKey,
      boolean reconcile,
      String preconditionHash) {
    InventoryPublicationIntent pending =
        transactions.execute(
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
              appendPublication(saved, session, "inventory.publication.requested.v1", actor(jwt));
              return saved;
            });
    JsonNode request = publicationRequest(session, pending);
    try {
      InventoryDependencyGateway.RepairUpsert result =
          dependencies.upsertRepair(
              session.getId(), pending.getFindingId(), idempotencyKey, request);
      transactions.executeWithoutResult(
          status -> {
            InventoryPublicationIntent intent =
                requirePublication(session.getId(), pending.getFindingId());
            intent.succeed(result.repairId());
            InventoryPublicationIntent saved = publications.saveAndFlush(intent);
            insertPublicationAttempt(
                saved, idempotencyKey, reconcile, "SUCCEEDED", null, result.repairId());
            appendPublication(saved, session, "inventory.publication.succeeded.v1", actor(jwt));
          });
    } catch (InventoryException exception) {
      transactions.executeWithoutResult(
          status -> {
            InventoryPublicationIntent intent =
                requirePublication(session.getId(), pending.getFindingId());
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
                actor(jwt));
          });
    }
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.publication-recovery-delay-ms:5000}")
  public void recoverPendingPublications() {
    OffsetDateTime eligibleBefore = OffsetDateTime.now(ZoneOffset.UTC).minusSeconds(30);
    for (InventoryPublicationIntent pending :
        publications.findTop20ByStateOrderByUpdatedAtAsc(PublicationState.PENDING)) {
      if (pending.getUpdatedAt().isAfter(eligibleBefore)) continue;
      InventoryPublicationAttempt attempt =
          publicationAttempts
              .findByPublicationIntentIdAndAttemptNo(
                  pending.getId(), pending.getAttemptCount())
              .orElseThrow(() ->
                  new IllegalStateException("Pending publication has no durable attempt"));
      InventorySession session = requireCompleted(pending.getInventoryId());
      JsonNode request = publicationRequest(session, pending);
      OpaqueActorReference serviceActor =
          new OpaqueActorReference("inventory-service", "SERVICE", "0");
      try {
        InventoryDependencyGateway.RepairUpsert result =
            dependencies.upsertRepair(
                session.getId(),
                pending.getFindingId(),
                attempt.getIdempotencyKey(),
                request);
        transactions.executeWithoutResult(
            status -> {
              InventoryPublicationIntent intent =
                  requirePublication(session.getId(), pending.getFindingId());
              if (intent.getState() != PublicationState.PENDING) return;
              intent.succeed(result.repairId());
              InventoryPublicationIntent saved = publications.saveAndFlush(intent);
              insertPublicationAttempt(
                  saved,
                  attempt.getIdempotencyKey(),
                  false,
                  "SUCCEEDED",
                  null,
                  result.repairId());
              appendPublication(
                  saved, session, "inventory.publication.succeeded.v1", serviceActor);
            });
      } catch (InventoryException exception) {
        transactions.executeWithoutResult(
            status -> {
              InventoryPublicationIntent intent =
                  requirePublication(session.getId(), pending.getFindingId());
              if (intent.getState() != PublicationState.PENDING) return;
              boolean blocked = exception.status() == HttpStatus.CONFLICT;
              if (blocked) intent.block("SOURCE_PRECONDITION_CONFLICT");
              else intent.transientFailure();
              InventoryPublicationIntent saved = publications.saveAndFlush(intent);
              String failureCode =
                  blocked ? "SOURCE_PRECONDITION_CONFLICT" : "DEPENDENCY_UNAVAILABLE";
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
                  serviceActor);
            });
      }
    }
  }

  private JsonNode publicationRequest(InventorySession session, InventoryPublicationIntent intent) {
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
      UUID repairId) {
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
            repairId));
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
    if (intent.getMaintenanceRepairId() == null) payload.putNull("maintenanceRepairId");
    else payload.put("maintenanceRepairId", intent.getMaintenanceRepairId().toString());
    if (intent.getBlockedFailureCode() == null) payload.putNull("failureCode");
    else payload.put("failureCode", intent.getBlockedFailureCode());
    ObjectNode source = payload.putObject("sourceReference");
    source.put("inventoryId", intent.getInventoryId().toString());
    source.put("findingId", intent.getFindingId().toString());
    source.put("sourceRevision", intent.getSourceRevision());
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
    boolean sourceMovementToShipment =
        requiredBoolean(source, "movementToShipment", "frozen plan movement to shipment");
    LogisticsPlanningMode sourceLogisticsPlanningMode =
        nullableLogisticsPlanningMode(
            source, "logisticsPlanningMode", "frozen plan logistics planning mode");
    LocalDate sourceLogisticsScheduledDate =
        nullableLocalDate(
            source, "logisticsScheduledDate", "frozen plan logistics scheduled date");
    if (!LogisticsPlanningMode.validInboundPlanning(
            sourceMovementToRepair, sourceLogisticsPlanningMode, sourceLogisticsScheduledDate)
        || sourceMovementToRepair != snapshot.isMovementToRepair()
        || sourceMovementToShipment != snapshot.isMovementToShipment()
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
                        nullableText(sourceLine.get("groupComment")));
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
        snapshot.isMovementToShipment(),
        snapshot.getLogisticsPlanningMode(),
        snapshot.getLogisticsScheduledDate(),
        lineViews,
        stageViews);
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
        value.getMaintenanceRepairId(),
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
    return canonicalHash(read(stored)).equals(canonicalHash(current));
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
    try {
      return canonicalHash(mapper.treeToValue(value, Object.class));
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

  private record StatisticsKey(
      String kind,
      UUID catalogVersionId,
      UUID catalogNodeId,
      String normalizedDescription,
      String type,
      String unit,
      long unitPriceMinor) {}
}
