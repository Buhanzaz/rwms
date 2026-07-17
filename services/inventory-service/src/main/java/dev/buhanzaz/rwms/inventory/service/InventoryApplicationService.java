package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
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
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventChecksum;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
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
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.inventory.service.InventoryStartPersistencePort.CapturedCapture;
import dev.buhanzaz.rwms.inventory.service.InventoryStartPersistencePort.StartOperation;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.math.BigDecimal;
import java.math.RoundingMode;
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
import java.util.Set;
import java.util.UUID;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@Service
public class InventoryApplicationService {
  private static final String SESSION_TOPIC = "rwms.inventory.session.v1";
  private static final String PUBLICATION_TOPIC = "rwms.inventory.publication.v1";
  private static final Set<String> CAPTURE_STATUSES =
      Set.of(
          "NEW",
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
  private final InventoryPublicationIntentRepository publications;
  private final InventoryPublicationAttemptRepository publicationAttempts;
  private final InventoryPublicationAttemptResultRepository publicationAttemptResults;
  private final InventoryExpectedItemRepository expectedItems;
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
  private final InventoryIdempotencyPort idempotency;
  private final InventoryStartPersistencePort startPersistence;
  private final InventoryCanonicalJsonPort canonicalJson;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;

  public InventoryApplicationService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryPublicationIntentRepository publications,
      InventoryPublicationAttemptRepository publicationAttempts,
      InventoryPublicationAttemptResultRepository publicationAttemptResults,
      InventoryExpectedItemRepository expectedItems,
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
      InventoryIdempotencyPort idempotency,
      InventoryStartPersistencePort startPersistence,
      InventoryCanonicalJsonPort canonicalJson,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager) {
    this.sessions = sessions;
    this.findings = findings;
    this.publications = publications;
    this.publicationAttempts = publicationAttempts;
    this.publicationAttemptResults = publicationAttemptResults;
    this.expectedItems = expectedItems;
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
    this.idempotency = idempotency;
    this.startPersistence = startPersistence;
    this.canonicalJson = canonicalJson;
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

  public SessionView active(Jwt jwt, UUID warehouseId) {
    authorizer.requireRead(jwt, warehouseId);
    return sessionView(
        sessions
            .findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE)
            .orElseThrow(() -> InventoryException.notFound("Active inventory session not found")));
  }

  public SessionView session(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    return sessionView(session);
  }

  public PageResponse<FindingView> findings(
      Jwt jwt, UUID inventoryId, int page, int size, String sort) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    Page<InventoryFinding> result =
        findings.findByInventoryId(inventoryId, PageRequest.of(page, size, findingSort(sort)));
    return new PageResponse<>(
        findingViews(result.getContent()),
        new PageMetadata(page, size, result.getTotalElements(), result.getTotalPages()));
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
        () -> doResolveNumber(jwt, inventoryId, request));
  }

  private NumberResolutionView doResolveNumber(
      Jwt jwt, UUID inventoryId, ResolveNumberRequest request) {
    InventorySession session = requireActive(inventoryId);
    authorizer.requireEdit(jwt, session.getWarehouseId());
    expectRevision(session.getRevision(), request.expectedSessionRevision());
    InventoryDependencyGateway.NumberResolution resolved =
        dependencies.resolveNumber(request.submittedNumber());
    InventoryFinding existing =
        findings
            .findByInventoryIdAndIdentityMatchKey(inventoryId, resolved.identityMatchKey())
            .orElse(null);
    if (existing != null) {
      return new NumberResolutionView(
          resolved.displayCanonicalNumber(),
          resolved.identityMatchKey(),
          "MATCHED",
          findingView(existing));
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
      reconciliation = ReconciliationState.CONFLICT;
    } else if (!CAPTURE_STATUSES.contains(asset.status())) {
      outcome = "EXCLUDED_STATUS_CONFLICT";
      reconciliation = ReconciliationState.CONFLICT;
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
                            asset.displayCanonicalNumber(),
                            asset.identityMatchKey(),
                            reconciliation,
                            actorJson));
                locked.touch();
                sessions.saveAndFlush(locked);
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
    InventoryFinding attached =
        transactions.execute(
            status -> {
              InventoryFinding finding = requireFinding(inventoryId, findingId);
              if (finding.getAssetId() == null) {
                if (finding.getMutationState()
                    != dev.buhanzaz.rwms.inventory.domain.MutationState.SOURCE_CREATE_PENDING) {
                  throw InventoryException.conflict("Finding source-create state is inconsistent");
                }
                finding.attachCreatedAsset(remote.asset().assetId(), remote.asset().version());
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
    validateObservation(request.passportObservation(), false);
    validateObservation(request.equipmentObservation(), true);
    validatePlanSelection(request.inspection(), request.planSelection());
    validateReadyMedia(findingId, session.getWarehouseId(), request.media());
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
            || !canonicalHash(plan.snapshot()).equals(plan.fingerprint()))) {
      throw InventoryException.dependency("Maintenance-service returned mismatched frozen plan");
    }
    InventoryDependencyGateway.FrozenPlan frozenPlan = plan;
    InventoryFinding saved =
        transactions.execute(
            status -> {
              InventorySession lockedSession = requireActive(inventoryId);
              InventoryFinding lockedFinding = requireFinding(inventoryId, findingId);
              expectRevision(lockedSession.getRevision(), request.expectedSessionRevision());
              expectRevision(lockedFinding.getRevision(), request.expectedFindingRevision());
              lockedFinding.saveInspection(
                  request.inspection(),
                  lockedFinding.getReconciliation(),
                  request.passportObservation().presence(),
                  json(request.passportObservation().value()),
                  request.equipmentObservation().presence(),
                  json(request.equipmentObservation().value()),
                  frozenPlan == null ? null : frozenPlan.fingerprint(),
                  actorJson(jwt));
              InventoryFinding result = findings.saveAndFlush(lockedFinding);
              persistMedia(result, request.media());
              if (frozenPlan != null) persistPlan(result, request.planSelection(), frozenPlan);
              appendFindingFacts(
                  result, lockedSession, actor(jwt), "inventory.finding.inspection-saved.v1");
              return result;
            });
    return findingView(saved);
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
    List<CompletionRisk> risks = risks(session, revisions.findings(), validation);
    FrozenStatistics statistics = calculateStatistics(session, revisions.findings());
    String acknowledgement =
        canonicalHash(
            acknowledgementFacts(session, revisions, validation, statistics, risks));
    CompletionPreview response =
        new CompletionPreview(
            inventoryId,
            session.getRevision(),
            revisions.expectations(),
            validation.validationDigest(),
            validation.validatedAt(),
            acknowledgement,
            statistics,
            risks);
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
        || !canonicalHash(fresh.assets())
            .equals(canonicalHash(preview.validationTruth().path("assets")))) {
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACKNOWLEDGEMENT_STALE",
          "Inventory preview acknowledgement is stale");
    }
    List<CompletionRisk> risks = risks(session, revisions.findings(), fresh);
    if (!risks.equals(preview.preview().risks())) {
      throw new InventoryException(
          HttpStatus.CONFLICT,
          "INVENTORY_ACKNOWLEDGEMENT_STALE",
          "Inventory completion risks changed after preview");
    }
    if (risks.stream()
        .anyMatch(risk -> !"MISSING".equals(risk.code()) && !"CONFLICT".equals(risk.code()))) {
      throw new InventoryException(
          HttpStatus.UNPROCESSABLE_ENTITY,
          "INVENTORY_VALIDATION_FAILED",
          "Inventory has unresolved completion risks");
    }
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
                      risks);
              persistValidation(
                  locked, fresh, request.acknowledgementSha256(), completionSnapshot);
              locked.complete(
                  fresh.validationDigest(),
                  request.acknowledgementSha256(),
                  fresh.validatedAt(),
                  actorJson(jwt));
              InventorySession result = sessions.saveAndFlush(locked);
              FrozenStatistics finalStatistics =
                  calculateStatistics(result, lockedRevisions.findings());
              persistStatistics(result, finalStatistics);
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
    return sessionView(completed);
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
              correlationId(),
              null,
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
              correlationId(),
              null,
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
    events.append(
        "FINDING",
        finding.getId(),
        expectedEventVersion,
        "inventory.finding.owner-proof.v1",
        SESSION_TOPIC,
        ownerProofPayload(finding, warehouseId),
        correlationId(),
        null,
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
    boolean manualMode = "MANUAL".equals(selection.mode());
    Set<UUID> catalogLines = new HashSet<>();
    for (PlanLineInput line : selection.lines()) {
      BigDecimal quantity = new BigDecimal(line.quantity());
      if (quantity.signum() <= 0) throw new IllegalArgumentException("Plan quantity must be positive");
      if ("CATALOG".equals(line.aggregationKind())) {
        if (line.catalogNodeId() == null
            || line.description() != null
            || line.type() != null
            || line.unit() != null
            || line.unitPriceMinor() != null
            || line.normativeMinutes() != null
            || !catalogLines.add(line.catalogNodeId())) {
          throw new IllegalArgumentException("CATALOG plan line evidence is invalid or duplicated");
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
      if (selection.stages().get(index).order() != index) {
        throw new IllegalArgumentException("Plan stage order must be contiguous");
      }
    }
  }

  private void validateReadyMedia(UUID findingId, UUID warehouseId, List<MediaReference> media) {
    Set<String> unique = new HashSet<>();
    for (MediaReference reference : media) {
      if (!unique.add(reference.mediaId() + ":" + reference.generation())) {
        throw new IllegalArgumentException("Media references must be unique");
      }
      if (mediaFacts
          .findByMediaIdAndGenerationAndOwnerTypeAndOwnerIdAndWarehouseIdAndMediaStatus(
              reference.mediaId(),
              reference.generation(),
              "INVENTORY_FINDING",
              findingId,
              warehouseId,
              "READY")
          .isEmpty()) {
        throw new InventoryException(
            HttpStatus.UNPROCESSABLE_ENTITY,
            "INVENTORY_MEDIA_NOT_READY",
            "Referenced media generation is not READY for this finding");
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
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            finding.getId(),
            finding.getRevision(),
            finding.getInventoryId(),
            selection.mode(),
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
      JsonNode routing = stage.path("routing");
      planStages.save(
          new FindingPlanStage(
              finding.getId(),
              finding.getRevision(),
              stageNo++,
              stage.path("kind").asText(),
              UUID.fromString(routing.path("queueId").asText()),
              routing.path("queueCode").asText(),
              routing.path("queueKind").asText(),
              !"REPAIR_WORK".equals(stage.path("kind").asText()),
              false,
              write(stage)));
    }
  }

  private RevisionState revisionState(
      InventorySession session,
      long expectedSessionRevision,
      List<RevisionExpectation> expectations) {
    expectRevision(session.getRevision(), expectedSessionRevision);
    List<InventoryFinding> all = findings.findAllByInventoryIdOrderById(session.getId());
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
        || !canonicalHash(validation.assets()).equals(validation.validationDigest())) {
      throw InventoryException.dependency("Asset-service returned malformed validation truth");
    }
    return validation;
  }

  private Map<String, Object> acknowledgementFacts(
      InventorySession session,
      RevisionState revisions,
      InventoryDependencyGateway.Validation validation,
      FrozenStatistics statistics,
      List<CompletionRisk> risks) {
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
    result.put("validationAssets", validation.assets());
    result.put("statistics", statistics);
    result.put("risks", risks);
    return result;
  }

  private List<CompletionRisk> risks(
      InventorySession session,
      List<InventoryFinding> all,
      InventoryDependencyGateway.Validation validation) {
    Map<UUID, InventoryDependencyGateway.ValidationItem> current = new LinkedHashMap<>();
    for (InventoryDependencyGateway.ValidationItem item : validation.assets()) {
      current.put(item.assetId(), item);
    }
    List<CompletionRisk> result = new ArrayList<>();
    for (InventoryFinding finding : all) {
      if (finding.getReconciliation() == ReconciliationState.MISSING) {
        result.add(new CompletionRisk(finding.getId(), "MISSING"));
      }
      if (finding.getReconciliation() == ReconciliationState.CONFLICT) {
        result.add(new CompletionRisk(finding.getId(), "CONFLICT"));
      }
      if (finding.getAssetId() != null
          && finding.getReconciliation() != ReconciliationState.MISSING
          && finding.getReconciliation() != ReconciliationState.CONFLICT) {
        InventoryDependencyGateway.ValidationItem item = current.get(finding.getAssetId());
        if (item == null
            || !item.found()
            || !session.getWarehouseId().equals(item.warehouseId())
            || !finding.getAssetVersion().equals(item.version())) {
          result.add(new CompletionRisk(finding.getId(), "ASSET_CHANGED"));
        }
      }
      if (finding.getInspection() == InspectionState.NOT_INSPECTED
          && (finding.getOrigin() != FindingOrigin.EXPECTED
              || (finding.getReconciliation() != ReconciliationState.MISSING
                  && finding.getReconciliation() != ReconciliationState.CONFLICT))) {
        result.add(new CompletionRisk(finding.getId(), "CONFLICT"));
      }
      if (finding.getInspection() == InspectionState.WORK_STAGED) {
        FindingPlanSnapshot plan =
            planSnapshots
                .findByFindingIdAndFindingRevision(finding.getId(), finding.getRevision())
                .orElse(null);
        if (finding.getMaintenancePlanFingerprintSha256() == null
            || plan == null
            || !finding.getMaintenancePlanFingerprintSha256().equals(plan.getFingerprint())
            || !plan.getFingerprint().equals(canonicalHash(read(plan.getSourceSnapshot())))) {
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

  private FrozenStatistics calculateStatistics(
      InventorySession session, List<InventoryFinding> all) {
    int inspected =
        (int)
            all.stream()
                .filter(value -> value.getInspection() != InspectionState.NOT_INSPECTED)
                .count();
    int missing =
        (int)
            all.stream()
                .filter(value -> value.getReconciliation() == ReconciliationState.MISSING)
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
            all.stream()
                .filter(value -> value.getReconciliation() == ReconciliationState.CONFLICT)
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
    for (InventoryFinding finding : findings.findAllByInventoryIdOrderById(session.getId())) {
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
    String snapshot =
        planSnapshots
            .findByFindingIdAndFindingRevision(finding.getId(), finding.getRevision())
            .map(FindingPlanSnapshot::getSourceSnapshot)
            .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
    ObjectNode request = mapper.createObjectNode();
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("sourceRevision", intent.getSourceRevision());
    request.put("rentalItemId", finding.getAssetId().toString());
    request.put("rentalItemVersion", finding.getAssetVersion());
    request.put("dispatchDate", session.getBusinessDate().toString());
    request.put("planFingerprint", finding.getMaintenancePlanFingerprintSha256());
    request.set("snapshot", read(snapshot));
    return request;
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
        value.getBusinessDate(),
        value.getLifecycle(),
        value.getExpectedPopulationCount(),
        counts.findingCount(),
        counts.inspectedCount(),
        value.getStartedAt(),
        terminalAt(value),
        aggregatePublicationState(publicationViews),
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
        value.getBusinessDate(),
        value.getLifecycle(),
        value.getExpectedPopulationCount(),
        counts.findingCount(),
        counts.inspectedCount(),
        value.getStartedAt(),
        terminalAt(value),
        aggregatePublicationState(publicationViews));
  }

  private FindingView findingView(InventoryFinding value) {
    return findingViews(List.of(value)).getFirst();
  }

  private List<FindingView> findingViews(List<InventoryFinding> values) {
    if (values.isEmpty()) return List.of();
    Set<UUID> findingIds =
        values.stream()
            .map(InventoryFinding::getId)
            .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
    Map<UUID, ExpectedItemSnapshot> expectedByFinding = new LinkedHashMap<>();
    for (InventoryExpectedItem expected :
        expectedItems.findAllByFindingIdInOrderByFindingId(findingIds)) {
      expectedByFinding.put(expected.getFindingId(), expectedItemSnapshot(expected));
    }
    Map<UUID, List<MediaReference>> mediaByFinding = new LinkedHashMap<>();
    for (FindingMediaReference reference : mediaReferences.findActiveByFindingIds(findingIds)) {
      mediaByFinding
          .computeIfAbsent(reference.getFindingId(), ignored -> new ArrayList<>())
          .add(new MediaReference(reference.getMediaId(), reference.getGeneration()));
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
    return values.stream()
        .map(
            value ->
                findingView(
                    value,
                    expectedByFinding.get(value.getId()),
                    planByFinding.get(value.getId()),
                    mediaByFinding.getOrDefault(value.getId(), List.of()),
                    publicationByFinding.get(value.getId())))
        .toList();
  }

  private FindingView findingView(
      InventoryFinding value,
      ExpectedItemSnapshot expectedSnapshot,
      FrozenPlanView frozenPlan,
      List<MediaReference> media,
      PublicationView publication) {
    if (value.getOrigin() != FindingOrigin.EXPECTED) expectedSnapshot = null;
    if (value.getInspection() == InspectionState.WORK_STAGED && frozenPlan == null) {
      throw new IllegalStateException("WORK_STAGED finding is missing its frozen plan");
    }
    if (value.getInspection() != InspectionState.WORK_STAGED) frozenPlan = null;
    return new FindingView(
        value.getId(),
        value.getInventoryId(),
        value.getRevision(),
        value.getOrigin(),
        value.getInspection(),
        value.getReconciliation(),
        value.getAssetId(),
        value.getAssetVersion(),
        value.getDisplayCanonicalNumber(),
        value.getIdentityMatchKey(),
        observation(value.getPassportObservationState(), value.getPassportObservation()),
        observation(value.getEquipmentObservationState(), value.getEquipmentObservation()),
        value.getMutationState(),
        value.getMaintenancePlanFingerprintSha256(),
        expectedSnapshot,
        frozenPlan,
        List.copyOf(media),
        publication);
  }

  private ExpectedItemSnapshot expectedItemSnapshot(InventoryExpectedItem value) {
    return new ExpectedItemSnapshot(
        value.getAssetId(),
        value.getAssetVersion(),
        value.getAssetStatus(),
        value.getDisplayCanonicalNumber(),
        boundedSafeSnapshot(value.getPassportSnapshot(), false, "passport"),
        boundedSafeSnapshot(value.getContentsSnapshot(), true, "contents"));
  }

  private FrozenPlanView frozenPlanView(
      FindingPlanSnapshot snapshot,
      List<FindingPlanLine> lines,
      List<FindingPlanStage> stages) {
    List<FrozenPlanLineView> lineViews =
        lines.stream()
            .map(
                line ->
                    new FrozenPlanLineView(
                        line.getSourceKind(),
                        line.getLineType(),
                        line.getCatalogVersionId(),
                        line.getCatalogNodeId(),
                        line.getDescription(),
                        line.getNormalizedDescription(),
                        line.getUnit(),
                        exactDecimal(line.getQuantity()),
                        line.getUnitPriceMinor(),
                        exactDecimal(line.getNormativeMinutes())))
            .toList();
    List<FrozenPlanStageView> stageViews =
        stages.stream()
            .map(
                stage ->
                    new FrozenPlanStageView(
                        stage.getStageNo(),
                        stage.getStageKind(),
                        stage.getRoutingQueueId(),
                        stage.getRoutingQueueCode(),
                        stage.getRoutingQueueKind(),
                        stage.isMovementRequired(),
                        stage.isPhotoRequired()))
            .toList();
    return new FrozenPlanView(
        snapshot.getPlanMode(),
        snapshot.getCatalogVersionId(),
        snapshot.getFingerprint(),
        lineViews,
        stageViews);
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
        .findByIdAndInventoryId(findingId, inventoryId)
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

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Stored inventory JSON is invalid", exception);
    }
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
