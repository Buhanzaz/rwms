package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.inventory.service.InventoryStartPersistencePort.CapturedCapture;
import dev.buhanzaz.rwms.inventory.service.InventoryStartPersistencePort.StartOperation;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns inventory-session start and capture-release recovery.
 *
 * <p>The service preserves the original reserve/capture/commit ordering: it records local
 * recovery state before a remote release can be retried.
 */
@Service
final class InventorySessionService extends InventorySessionWorkflowSupport {
  InventorySessionService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryExpectedItemRepository expectedItems,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryStartPersistencePort startPersistence,
      InventoryFindingService findingService,
      InventoryReviewService reviewService,
      InventoryPlanningService planningService,
      InventoryProjectionService projectionService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        expectedItems,
        dependencies,
        events,
        idempotency,
        startPersistence,
        findingService,
        projectionService,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    this.reviewService = reviewService;
    this.planningService = planningService;
  }

  private final InventoryReviewService reviewService;
  private final InventoryPlanningService planningService;

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

  /**
   * Rebuilds one active session's membership from a fresh read-only asset capture.
   *
   * <p>The initial revision is checked before the remote capture, then checked again under the
   * idempotent local transaction's session lock. Captured and locally active asset identities pass
   * through the same membership transitions as asset events, preserving findings, inspections,
   * media and movement history.
   */
  public SessionView refresh(
      Jwt jwt, UUID inventoryId, UUID idempotencyKey, RefreshSessionRequest request) {
    if (request == null
        || request.expectedSessionRevision() == null
        || request.expectedSessionRevision() < 0) {
      throw new IllegalArgumentException("Inventory refresh revision is required");
    }
    InventorySession initial = requireScopedSession(inventoryId, authorizer.manageScope(jwt));
    authorizer.requireManage(jwt, initial.getWarehouseId());
    Map<String, Object> command = Map.of("inventoryId", inventoryId, "request", request);
    String initialConflict =
        initial.getLifecycle() != SessionLifecycle.ACTIVE
            ? "Inventory session is not active"
            : initial.getRevision() != request.expectedSessionRevision()
                ? "Inventory revision is stale"
                : null;
    if (initialConflict != null) {
      return idempotency.execute(
          authorizer.subjectId(jwt),
          "session.refresh",
          idempotencyKey,
          command,
          HttpStatus.OK.value(),
          SessionView.class,
          () -> {
            throw InventoryException.conflict(initialConflict);
          });
    }

    UUID operationId = UUID.randomUUID();
    String requestFingerprint = canonicalHash(command);
    InventoryDependencyGateway.Capture capture =
        dependencies.createCapture(
            UUID.randomUUID(),
            new InventoryDependencyGateway.CaptureRequest(
                operationId, 1, requestFingerprint, initial.getWarehouseId()));
    try {
      requireRefreshCapture(capture, operationId, initial.getWarehouseId());
      List<InventoryDependencyGateway.CaptureMember> capturedMembers = copyCapture(capture);
      return idempotency.execute(
          authorizer.subjectId(jwt),
          "session.refresh",
          idempotencyKey,
          command,
          HttpStatus.OK.value(),
          SessionView.class,
          () ->
              applyRefresh(
                  jwt,
                  inventoryId,
                  request,
                  operationId,
                  capturedMembers));
    } finally {
      releaseRefreshCapture(capture == null ? null : capture.captureId());
    }
  }

  private SessionView applyRefresh(
      Jwt jwt,
      UUID inventoryId,
      RefreshSessionRequest request,
      UUID operationId,
      List<InventoryDependencyGateway.CaptureMember> capturedMembers) {
    InventorySession locked = requireActiveForUpdate(inventoryId);
    authorizer.requireManage(jwt, locked.getWarehouseId());
    expectRevision(locked.getRevision(), request.expectedSessionRevision());
    findingService.reconcileCapturedMembership(
        locked,
        capturedMembers,
        actor(jwt),
        correlationId(),
        operationId,
        OffsetDateTime.now(ZoneOffset.UTC));
    reviewService.invalidateFurnitureReviewAfterCabinChange(locked);
    planningService.invalidateFinalPlan(locked);
    locked.touch();
    return projectionService.sessionView(sessions.saveAndFlush(locked));
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

  private InventorySession requireActiveForUpdate(UUID inventoryId) {
    return sessions
        .findByIdAndLifecycleForUpdate(inventoryId, SessionLifecycle.ACTIVE)
        .orElseThrow(
            () ->
                sessions.existsById(inventoryId)
                    ? InventoryException.conflict("Inventory session is not active")
                    : InventoryException.notFound("Inventory session not found"));
  }

  private void expectRevision(long actual, long expected) {
    if (actual != expected) {
      throw InventoryException.conflict("Inventory revision is stale");
    }
  }

  private void requireRefreshCapture(
      InventoryDependencyGateway.Capture capture, UUID operationId, UUID warehouseId) {
    if (capture == null
        || capture.captureId() == null
        || !operationId.equals(capture.operationId())
        || capture.technicalAttempt() != 1
        || !warehouseId.equals(capture.warehouseId())
        || capture.totalCount() < 0
        || capture.membershipDigest() == null
        || !capture.membershipDigest().matches("^[0-9a-f]{64}$")) {
      throw InventoryException.dependency("Asset-service returned malformed refresh capture");
    }
  }

  private void releaseRefreshCapture(UUID captureId) {
    if (captureId == null) {
      return;
    }
    try {
      dependencies.releaseCapture(captureId);
    } catch (RuntimeException exception) {
      log.warn("Could not release read-only refresh capture {}", captureId, exception);
    }
  }

  private SessionView doStart(Jwt jwt, UUID idempotencyKey, StartSessionRequest request) {
    authorizer.requireEdit(jwt, request.warehouseId());
    UUID subjectId = authorizer.subjectId(jwt);
    String requestHash = canonicalHash(request);
    StartOperation operation =
        startOperation(subjectId, idempotencyKey, requestHash, request.warehouseId());
    if (operation.sessionId() != null) {
      return projectionService.sessionView(
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
                return projectionService.sessionView(session);
              });
      releaseCaptureAfterCompletion(operation.operationId(), capture.captureId());
      return created;
    } catch (DataIntegrityViolationException exception) {
      InventorySession winner =
          sessions.findByStartOperationId(operation.operationId()).orElse(null);
      if (winner != null && requestHash.equals(winner.getStartRequestSha256())) {
        releaseCapture(operation.operationId(), capture.captureId());
        return projectionService.sessionView(winner);
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

  /** Releases capture reservations whose local completion persisted before a dependency call. */
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
      findingService.appendFindingFacts(finding, session, actor, "inventory.finding.added.v1");
    }
  }

}
