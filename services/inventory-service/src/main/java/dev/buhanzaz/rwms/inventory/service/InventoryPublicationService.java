package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanReconciliationStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttempt;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttemptResult;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.MaintenancePublicationOutcome;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns durable publication intents, attempts and retry/recovery dispatch to maintenance.
 *
 * <p>External publication calls are fenced by persisted intent state and independent
 * transactions, so recovery resumes an existing effect rather than recreating it.
 */
@Service
final class InventoryPublicationService extends InventoryPublicationWorkflowSupport {
  InventoryPublicationService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryPublicationIntentRepository publications,
      InventoryPublicationAttemptRepository publicationAttempts,
      InventoryPublicationAttemptResultRepository publicationAttemptResults,
      FindingPlanSnapshotRepository planSnapshots,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryPlanningService planningService,
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
        publications,
        publicationAttempts,
        publicationAttemptResults,
        planSnapshots,
        dependencies,
        events,
        idempotency,
        planningService,
        projectionService,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
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

  PublicationBatch doPublish(
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

  PublicationView doRetryPublication(
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
    return projectionService.publicationView(requirePublication(inventoryId, findingId));
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

  PublicationView doClosePublication(
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
    return projectionService.publicationView(result);
  }

  void createPublicationIntents(
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

  List<InventoryPublicationIntent> selectPublications(
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

  void dispatchPublication(
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
  void dispatchReadyPublications(UUID inventoryId) {
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

  UUID servicePublicationIdempotencyKey(InventoryPublicationIntent intent) {
    return UUID.nameUUIDFromBytes(
        ("rwms:inventory-service:publication:" + intent.getMaintenanceSourceKey())
            .getBytes(StandardCharsets.UTF_8));
  }

  void settlePublicationFailure(
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

  /** Reclaims publication intents whose durable state requires another at-least-once dispatch. */
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

  void recoverReadyPublication(InventoryPublicationIntent ready) {
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

  void settleRecoveredPublicationFailure(
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

  JsonNode publicationRequest(InventorySession session, InventoryPublicationIntent intent) {
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

  JsonNode finalPlanPublicationRequest(
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
            : planningService.finalPlanDecision(read(entry.getReconciliationDecision()));
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
    request.set("media", planningService.frozenPlanMedia(frozenPlan));
    return request;
  }

  PublicationTarget dispatchPublicationEffect(
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

  InventoryPublicationIntent.PublicationTarget publicationTarget(JsonNode response) {
    try {
      MaintenancePublicationOutcome outcome =
          MaintenancePublicationOutcome.valueOf(response.path("outcome").asText());
      String resultSnapshot = canonicalWrite(response);
      if (outcome == MaintenancePublicationOutcome.MATCHED) {
        if (!planningService.explicitNull(response, "targetKind")
            || !planningService.explicitNull(response, "targetId")
            || !planningService.explicitNull(response, "estimateId")
            || !planningService.explicitNull(response, "repairId")) {
          throw new IllegalArgumentException();
        }
        return new InventoryPublicationIntent.PublicationTarget(
            outcome, null, null, null, null, resultSnapshot);
      }
      FinalPlanTargetKind kind = FinalPlanTargetKind.valueOf(response.path("targetKind").asText());
      UUID targetId = requiredUuid(response, "targetId", "maintenance publication target id");
      UUID estimateId =
          planningService.nullableUuid(response, "estimateId", "maintenance estimate id");
      UUID repairId =
          planningService.nullableUuid(response, "repairId", "maintenance repair id");
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

  String publicationRequestHash(
      InventorySession session, InventoryPublicationIntent intent) {
    return canonicalHash(publicationRequest(session, intent));
  }

  void insertPublicationAttempt(
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

  void appendPublication(
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

  PublicationBatch publicationBatch(UUID inventoryId) {
    List<PublicationView> views =
        publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
            .map(projectionService::publicationView)
            .toList();
    return new PublicationBatch(inventoryId, projectionService.aggregatePublicationState(views), views);
  }

  /**
   * Carries the resolved persisted publication target without exposing raw target selection to the
   * dispatch workflow.
   */
  record PublicationTarget(InventoryPublicationIntent.PublicationTarget value) {}
}
