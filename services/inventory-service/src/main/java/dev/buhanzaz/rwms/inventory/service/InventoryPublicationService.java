package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.FinalPlanReconciliationStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryAssetOutcomeStatus;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanOutcomePolicy;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttempt;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationAttemptResult;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.MaintenancePublicationOutcome;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.PublicationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationAttemptResultRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
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
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns durable publication intents, attempts and retry/recovery dispatch to asset, media,
 * logistics and maintenance owners.
 *
 * <p>External publication calls are fenced by persisted intent state and independent
 * transactions, so recovery resumes an existing effect rather than recreating it.
 * Owner-facing idempotency remains stable across delivery attempts and changes only when an
 * explicit completed-history recovery advances the durable reapplication generation.
 */
@Service
final class InventoryPublicationService extends InventoryPublicationWorkflowSupport {
  private static final int MAX_AUTOMATIC_ATTEMPTS = 8;
  private static final Duration INITIAL_RETRY_DELAY = Duration.ofSeconds(5);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(5);
  private final InventoryPlanLogisticsReconciliationService planLogistics;

  InventoryPublicationService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryPublicationIntentRepository publications,
      InventoryFurnitureReconciliationIntentRepository furnitureReconciliations,
      InventoryPublicationAttemptRepository publicationAttempts,
      InventoryPublicationAttemptResultRepository publicationAttemptResults,
      FindingPlanSnapshotRepository planSnapshots,
      FindingMediaReferenceRepository mediaReferences,
      InventoryDependencyGateway dependencies,
      InventoryPlanLogisticsReconciliationService planLogistics,
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
        furnitureReconciliations,
        publicationAttempts,
        publicationAttemptResults,
        planSnapshots,
        mediaReferences,
        dependencies,
        events,
        idempotency,
        planningService,
        projectionService,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
    this.planLogistics = planLogistics;
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
      dispatchPublication(
          actor(jwt),
          session,
          intent,
          publicationAttemptKey(idempotencyKey, intent),
          false,
          null);
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
        publicationAttemptKey(idempotencyKey, intent),
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
              InventoryPublicationIntent intent =
                  requirePublicationForUpdate(inventoryId, findingId);
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
    if (!entries.isEmpty()) {
      planLogistics.schedule(session, finalPlan, entries, 0);
    }
    List<InventoryPublicationIntent> created = new ArrayList<>(entries.size());
    for (InventoryFinalPlanEntry entry : entries) {
      if (!InventoryFinalPlanOutcomePolicy.assetPublicationRequired(entry)) continue;
      InventoryAssetOutcomeStatus desiredStatus = desiredStatus(entry);
      created.add(
          InventoryPublicationIntent.readyForOutcome(
              session.getId(),
              entry.getFindingId(),
              Math.max(1, entry.getFindingRevision()),
              finalPlan.getFinalPlanVersion(),
              finalPlan.getFinalPlanSha256(),
              InventoryFinalPlanOutcomePolicy.maintenanceTarget(entry),
              desiredStatus,
              frozenPassportObservation(entry)));
    }
    for (InventoryPublicationIntent intent : publications.saveAllAndFlush(created)) {
      if (intent.getState() == PublicationState.READY) {
        appendPublicationReady(intent, session, actor, true);
      }
    }
  }

  /**
   * Reuses the frozen publication outcome so isolated source creation and later publication
   * cannot disagree about status, passport or final-plan fencing.
   */
  List<InventoryDependencyGateway.InventorySourceOutcomeCandidate> sourceAssetOutcomes(
      InventorySession session, List<InventoryFinalPlanEntry> entries) {
    if (session.getLifecycle() != SessionLifecycle.COMPLETED) {
      throw new IllegalStateException("Source materialization requires a completed inventory");
    }
    List<InventoryDependencyGateway.InventorySourceOutcomeCandidate> result = new ArrayList<>();
    for (InventoryFinalPlanEntry entry :
        entries.stream().sorted(Comparator.comparing(InventoryFinalPlanEntry::getFindingId)).toList()) {
      if (entry.getDispositionKind() != InventoryCabinDispositionKind.LOCAL) continue;
      InventoryFinding finding = requireFinding(session.getId(), entry.getFindingId());
      if (finding.getOrigin() != FindingOrigin.ADDED_NEW
          && finding.getOrigin() != FindingOrigin.ADDED_USED) continue;
      InventoryPublicationIntent intent = requirePublication(session.getId(), entry.getFindingId());
      result.add(
          new InventoryDependencyGateway.InventorySourceOutcomeCandidate(
              entry.getFindingId(), finalPlanPublicationRequest(session, intent).required("assetOutcome")));
    }
    return List.copyOf(result);
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
                      value.getId(), publicationAttemptKey(idempotencyKey, value)))
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
          intent.getId(), publicationAttemptKey(idempotencyKey, intent))) {
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
    if (!furnitureReconciliationSatisfied(session.getId())) {
      return;
    }
    InventoryPublicationIntent pending =
        independentTransactions.execute(
            status -> {
              InventoryPublicationIntent intent =
                  requirePublicationForUpdate(session.getId(), original.getFindingId());
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
                requirePublicationForUpdate(session.getId(), pending.getFindingId());
            if (intent.getState() != PublicationState.PENDING) return;
            if (result.value() == null) intent.succeedAssetOnly();
            else intent.succeed(result.value());
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

  UUID servicePublicationIdempotencyKey(InventoryPublicationIntent intent) {
    return UUID.nameUUIDFromBytes(
        ("rwms:inventory-service:publication:"
                + intent.getMaintenanceSourceKey()
                + ":attempt:"
                + Math.addExact(intent.getAttemptCount(), 1))
            .getBytes(StandardCharsets.UTF_8));
  }

  UUID publicationAttemptKey(UUID commandKey, InventoryPublicationIntent intent) {
    return UUID.nameUUIDFromBytes(
        ("rwms:inventory-service:publication-command:"
                + commandKey
                + ":"
                + intent.getId()
                + ":attempt:"
                + Math.addExact(intent.getAttemptCount(), 1))
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
          InventoryPublicationIntent intent =
              requirePublicationForUpdate(session.getId(), findingId);
          if (intent.getState() != PublicationState.PENDING) return;
          boolean semantic = semanticRejection(exception);
          boolean exhausted =
              !semantic && intent.getGenerationAttemptCount() >= MAX_AUTOMATIC_ATTEMPTS;
          boolean blocked = semantic || exhausted;
          String failureCode =
              semantic
                  ? publicationFailureCode(exception)
                  : exhausted ? "PUBLICATION_RETRY_EXHAUSTED" : "DEPENDENCY_UNAVAILABLE";
          if (blocked) intent.block(failureCode);
          else intent.transientFailure(nextRetryAt(intent.getGenerationAttemptCount()));
          InventoryPublicationIntent saved = publications.saveAndFlush(intent);
          insertPublicationAttempt(
              saved,
              idempotencyKey,
              reconcile,
              blocked ? "BLOCKED" : "TRANSIENT_FAILED",
              failureCode,
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
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    for (InventoryPublicationIntent retryable :
        publications
            .findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
                Set.of(PublicationState.READY, PublicationState.TRANSIENT_FAILED), current)) {
      recoverRetryablePublication(retryable);
    }
    OffsetDateTime eligibleBefore = current.minusSeconds(30);
    for (InventoryPublicationIntent pending :
        publications.findTop20ByStateOrderByUpdatedAtAsc(PublicationState.PENDING)) {
      try {
        if (pending.getUpdatedAt().isAfter(eligibleBefore)) continue;
        if (!furnitureReconciliationSatisfied(pending.getInventoryId())) continue;
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
                  requirePublicationForUpdate(session.getId(), pending.getFindingId());
              if (intent.getState() != PublicationState.PENDING) return;
              if (result.value() == null) intent.succeedAssetOnly();
              else intent.succeed(result.value());
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

  private static boolean semanticRejection(InventoryException exception) {
    return switch (exception.status()) {
      case BAD_REQUEST, NOT_FOUND, CONFLICT, UNPROCESSABLE_ENTITY -> true;
      default -> false;
    };
  }

  private static OffsetDateTime nextRetryAt(int generationAttemptCount) {
    int exponent = Math.min(6, Math.max(0, generationAttemptCount - 1));
    Duration delay = INITIAL_RETRY_DELAY.multipliedBy(1L << exponent);
    if (delay.compareTo(MAX_RETRY_DELAY) > 0) delay = MAX_RETRY_DELAY;
    return OffsetDateTime.now(ZoneOffset.UTC).plus(delay);
  }

  private static String publicationFailureCode(InventoryException exception) {
    String code = exception.code();
    return code != null && code.matches("^[A-Z][A-Z0-9_]{0,63}$")
        ? code
        : "SOURCE_PRECONDITION_CONFLICT";
  }

  private void recoverRetryablePublication(InventoryPublicationIntent retryable) {
    try {
      InventorySession session = requireCompleted(retryable.getInventoryId());
      dispatchPublication(
          PUBLICATION_RECOVERY_ACTOR,
          session,
          retryable,
          servicePublicationIdempotencyKey(retryable),
          false,
          null);
    } catch (RuntimeException exception) {
      // READY and TRANSIENT_FAILED remain durable scheduler work after a failed dispatch.
      log.error("Unable to recover retryable inventory publication {}", retryable.getId(), exception);
    }
  }

  void settleRecoveredPublicationFailure(
      InventoryPublicationIntent pending, InventoryException exception) {
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryPublicationIntent intent =
              requirePublicationForUpdate(pending.getInventoryId(), pending.getFindingId());
          if (intent.getState() != PublicationState.PENDING) return;
          InventoryPublicationAttempt attempt =
              publicationAttempts
                  .findByPublicationIntentIdAndAttemptNo(intent.getId(), intent.getAttemptCount())
                  .orElseThrow(
                      () -> new IllegalStateException("Pending publication has no durable attempt"));
          InventorySession session = requireCompleted(intent.getInventoryId());
          boolean semantic = semanticRejection(exception);
          boolean exhausted =
              !semantic && intent.getGenerationAttemptCount() >= MAX_AUTOMATIC_ATTEMPTS;
          boolean blocked = semantic || exhausted;
          String failureCode =
              semantic
                  ? publicationFailureCode(exception)
                  : exhausted ? "PUBLICATION_RETRY_EXHAUSTED" : "DEPENDENCY_UNAVAILABLE";
          if (blocked) intent.block(failureCode);
          else intent.transientFailure(nextRetryAt(intent.getGenerationAttemptCount()));
          InventoryPublicationIntent saved = publications.saveAndFlush(intent);
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
    InventoryAssetOutcomeStatus desiredStatus = desiredStatus(entry);
    if (entry.getFindingRevision() != intent.getSourceRevision()
        || entry.getAssetId() == null
        || entry.getAssetVersion() == null
        || intent.getDesiredAssetStatus() != desiredStatus
        || intent.getTargetKind() != InventoryFinalPlanOutcomePolicy.maintenanceTarget(entry)) {
      throw InventoryException.conflict("Publication final-plan outcome evidence is invalid");
    }
    ObjectNode request = mapper.createObjectNode();
    ObjectNode assetOutcome = request.putObject("assetOutcome");
    assetOutcome.put("warehouseId", session.getWarehouseId().toString());
    assetOutcome.put("assetId", entry.getAssetId().toString());
    assetOutcome.put("inventoryCompletedAt", session.getCompletedAt().toString());
    assetOutcome.put("finalPlanVersion", intent.getFinalPlanVersion());
    assetOutcome.put("finalPlanSha256", intent.getFinalPlanSha256());
    assetOutcome.put("findingRevision", entry.getFindingRevision());
    assetOutcome.put("desiredStatus", desiredStatus.name());
    JsonNode dispositionDetails = read(entry.getDispositionDetails());
    if (entry.getDispositionKind() == InventoryCabinDispositionKind.SHIPMENT) {
      JsonNode furniture = dispositionDetails.path("shipment").path("furniture");
      if (!furniture.isArray()) {
        throw InventoryException.conflict("Shipment furniture evidence is invalid");
      }
      assetOutcome.set("shipmentContents", furniture);
    } else {
      assetOutcome.putNull("shipmentContents");
    }
    JsonNode passportObservation = normalizedPassportObservation(intent);
    assetOutcome.set("passportObservation", passportObservation);
    assetOutcome.put(
        "passportObservationSha256", canonicalJsonTreeHash(passportObservation));
    JsonNode cabinPhotos = completedFindingPhotoRequest(session, intent, entry);
    if (cabinPhotos == null) request.putNull("cabinPhotos");
    else request.set("cabinPhotos", cabinPhotos);
    if (!entry.isHasWork()) {
      request.putNull("maintenance");
      return request;
    }
    if (entry.getPlanFingerprintSha256() == null) {
      throw InventoryException.conflict("Publication final-plan work evidence is invalid");
    }
    InventoryFinding finding = requireFinding(session.getId(), intent.getFindingId());
    FindingPlanSnapshot frozenPlan =
        activePlanSnapshot(finding)
            .orElseThrow(() -> InventoryException.conflict("Frozen maintenance plan is missing"));
    if (!entry.getPlanFingerprintSha256().equals(frozenPlan.getFingerprint())
        || entry.isForceCapitalRepair() != frozenPlan.isForceCapitalRepair()) {
      throw InventoryException.conflict("Publication frozen maintenance plan is stale");
    }
    FinalPlanReconciliationDecision decision =
        entry.getReconciliationDecision() == null
            ? new FinalPlanReconciliationDecision(FinalPlanReconciliationStrategy.CREATE, null, null)
            : planningService.finalPlanDecision(read(entry.getReconciliationDecision()));
    ObjectNode maintenance = request.putObject("maintenance");
    maintenance.put("warehouseId", session.getWarehouseId().toString());
    maintenance.put("inventoryCompletedAt", session.getCompletedAt().toString());
    maintenance.put("finalPlanVersion", intent.getFinalPlanVersion());
    maintenance.put("finalPlanSha256", intent.getFinalPlanSha256());
    maintenance.put("findingRevision", entry.getFindingRevision());
    maintenance.put("assetId", entry.getAssetId().toString());
    maintenance.put("assetVersion", entry.getAssetVersion());
    maintenance.put("planFingerprintSha256", entry.getPlanFingerprintSha256());
    maintenance.put("snapshotSchemaVersion", frozenPlan.getSnapshotSchemaVersion());
    maintenance.put("priority", entry.getPriority());
    maintenance.put("movementToRepair", entry.isMovementToRepair());
    maintenance.put("forceCapitalRepair", entry.isForceCapitalRepair());
    if (entry.getMovementScheduledDate() == null) maintenance.putNull("movementScheduledDate");
    else maintenance.put("movementScheduledDate", entry.getMovementScheduledDate().toString());
    maintenance.put("repairScheduledDate", entry.getRepairScheduledDate().toString());
    maintenance.put("strategy", decision.strategy().name());
    if (decision.selectedTargetKind() == null) maintenance.putNull("selectedTargetKind");
    else maintenance.put("selectedTargetKind", decision.selectedTargetKind().name());
    if (decision.selectedTargetId() == null) maintenance.putNull("selectedTargetId");
    else maintenance.put("selectedTargetId", decision.selectedTargetId().toString());
    maintenance.set("snapshot", read(frozenPlan.getSourceSnapshot()));
    maintenance.set("media", planningService.frozenPlanMedia(frozenPlan));
    return request;
  }

  /**
   * Freezes the exact completed finding observation before any downstream delivery can retry.
   * Final-plan identity and finding revision fence the snapshot against another cabin or edit.
   */
  String frozenPassportObservation(InventoryFinalPlanEntry entry) {
    InventoryFinding finding = requireFinding(entry.getInventoryId(), entry.getFindingId());
    if (finding.getRevision() != entry.getFindingRevision()
        || entry.getAssetId() == null
        || !entry.getAssetId().equals(finding.getAssetId())) {
      throw InventoryException.conflict("Publication passport observation is stale");
    }
    ObjectNode observation = mapper.createObjectNode();
    observation.put("presence", finding.getPassportObservationState().name());
    if (finding.getPassportObservation() == null) observation.putNull("value");
    else observation.set("value", read(finding.getPassportObservation()));
    return canonicalWrite(observation);
  }

  private JsonNode normalizedPassportObservation(InventoryPublicationIntent intent) {
    JsonNode frozen =
        boundedSafeSnapshot(
            intent.getAssetPassportObservation(), false, "asset passport observation");
    String presence = requiredText(frozen, "presence", "passport observation presence");
    JsonNode value = frozen.get("value");
    if (ObservationPresence.ABSENT.name().equals(presence)) {
      if (value == null || !value.isNull()) {
        throw InventoryException.conflict("Frozen ABSENT passport observation is invalid");
      }
      ObjectNode result = mapper.createObjectNode();
      result.put("presence", ObservationPresence.ABSENT.name());
      result.putNull("value");
      return result;
    }
    if (!ObservationPresence.PRESENT.name().equals(presence)
        || value == null
        || !value.isObject()) {
      throw InventoryException.conflict(
          "Frozen passport observation cannot update the asset passport");
    }
    ObjectNode normalizedValue = mapper.createObjectNode();
    normalizedValue.put("rentalType", passportText(value, "rentalType"));
    normalizedValue.put("dimensions", passportText(value, "dimensions"));
    normalizedValue.put("finishing", passportText(value, "finishing"));
    normalizedValue.put("category", passportText(value, "category"));
    ArrayNode characteristics = normalizedValue.putArray("characteristics");
    normalizedCharacteristics(value.get("characteristics")).forEach(characteristics::add);
    JsonNode linoleum = value.get("linoleum");
    if (linoleum != null && linoleum.isBoolean()) {
      normalizedValue.put("linoleum", linoleum.booleanValue());
    } else {
      normalizedValue.putNull("linoleum");
    }
    ObjectNode result = mapper.createObjectNode();
    result.put("presence", ObservationPresence.PRESENT.name());
    result.set("value", normalizedValue);
    return result;
  }

  private static String passportText(JsonNode value, String field) {
    JsonNode selected = value.get(field);
    if (selected == null || !selected.isTextual()) {
      throw InventoryException.conflict("Frozen passport " + field + " is missing");
    }
    String normalized = selected.asText().trim().replaceAll("[\\p{Z}\\s]+", " ");
    if (normalized.isEmpty() || normalized.length() > 255) {
      throw InventoryException.conflict("Frozen passport " + field + " is invalid");
    }
    return normalized;
  }

  private static List<String> normalizedCharacteristics(JsonNode source) {
    if (source == null || source.isNull()) return List.of();
    List<JsonNode> raw = new ArrayList<>();
    if (source.isTextual()) {
      raw.add(source);
    } else if (source.isArray() && source.size() <= 100) {
      source.forEach(raw::add);
    } else {
      throw InventoryException.conflict("Frozen passport characteristics are invalid");
    }
    Set<String> normalized = new LinkedHashSet<>();
    for (JsonNode value : raw) {
      if (!value.isTextual()) {
        throw InventoryException.conflict("Frozen passport characteristics are invalid");
      }
      for (String part : value.asText().split(",", -1)) {
        String name = part.trim().replaceAll("[\\p{Z}\\s]+", " ");
        if (!name.isEmpty()) normalized.add(name);
      }
    }
    if (normalized.size() > 100) {
      throw InventoryException.conflict("Frozen passport characteristics are invalid");
    }
    return List.copyOf(normalized);
  }

  private JsonNode completedFindingPhotoRequest(
      InventorySession session,
      InventoryPublicationIntent intent,
      InventoryFinalPlanEntry entry) {
    if (entry.getDispositionKind() != InventoryCabinDispositionKind.LOCAL) return null;
    List<FindingMediaReference> images =
        mediaReferences
            .findAllByFindingIdAndFindingRevisionOrderByMediaIdAscGenerationAsc(
                intent.getFindingId(), entry.getFindingRevision())
            .stream()
            .filter(reference -> "IMAGE".equals(reference.getMediaKind()))
            .toList();
    if (images.isEmpty()) {
      return null;
    }
    InventoryFinding finding = requireFinding(session.getId(), intent.getFindingId());
    if (finding.getRevision() != entry.getFindingRevision()
        || finding.getRevision() != intent.getSourceRevision()
        || !entry.getAssetId().equals(finding.getAssetId())) {
      throw InventoryException.conflict("Publication finding photo evidence is stale");
    }
    UUID coverMediaId = finding.getCoverMediaId();
    if (coverMediaId == null
        || images.stream().noneMatch(reference -> coverMediaId.equals(reference.getMediaId()))) {
      throw InventoryException.conflict("Publication finding cover photo evidence is invalid");
    }
    ObjectNode request = mapper.createObjectNode();
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("cabinId", entry.getAssetId().toString());
    request.put("completedAt", session.getCompletedAt().toString());
    request.put("sourceRevision", entry.getFindingRevision());
    request.put("finalPlanVersion", intent.getFinalPlanVersion());
    request.put("finalPlanSha256", intent.getFinalPlanSha256());
    request.put("coverMediaId", coverMediaId.toString());
    ArrayNode references = request.putArray("mediaReferences");
    for (FindingMediaReference image : images) {
      if (image.getGeneration() < 1) {
        throw InventoryException.conflict("Publication finding photo generation is invalid");
      }
      references
          .addObject()
          .put("mediaId", image.getMediaId().toString())
          .put("generation", image.getGeneration());
    }
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
    JsonNode assetRequest = request.path("assetOutcome");
    if (!assetRequest.isObject()) {
      throw InventoryException.conflict("Authoritative asset outcome request is missing");
    }
    UUID ownerEffectKey = authoritativeOutcomeIdempotencyKey(intent);
    InventoryDependencyGateway.InventoryAssetOutcome assetOutcome =
        dependencies.applyInventoryOutcome(
            session.getId(), intent.getFindingId(), ownerEffectKey, assetRequest);
    requireMatchingAssetOutcome(session, intent, assetRequest, assetOutcome);
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryPublicationIntent current =
              requirePublicationForUpdate(session.getId(), intent.getFindingId());
          if (current.getState() != PublicationState.PENDING) return;
          current.recordAssetOutcome(
              assetOutcome.assetVersion(),
              InventoryAssetOutcomeStatus.valueOf(assetOutcome.status()),
              canonicalWrite(assetOutcome.result()));
          publications.saveAndFlush(current);
        });
    JsonNode cabinPhotos = request.get("cabinPhotos");
    if (cabinPhotos != null && !cabinPhotos.isNull()) {
      if (!cabinPhotos.isObject()) {
        throw InventoryException.conflict("Inventory cabin photo publication request is invalid");
      }
      InventoryDependencyGateway.InventoryCabinPhotoOutcome photoOutcome =
          dependencies.publishInventoryCabinPhotos(
              session.getId(), intent.getFindingId(), ownerEffectKey, cabinPhotos);
      requireMatchingCabinPhotoOutcome(session, intent, cabinPhotos, photoOutcome);
    }
    planLogistics.requireApplied(session, intent);
    JsonNode maintenanceSource = request.get("maintenance");
    if (maintenanceSource == null || maintenanceSource.isNull()) {
      InventoryFinalPlanEntry entry =
          finalPlanEntries
              .findByInventoryIdAndFinalPlanVersionAndFindingId(
                  session.getId(), intent.getFinalPlanVersion(), intent.getFindingId())
              .orElseThrow(
                  () -> InventoryException.conflict("Publication final-plan finding is missing"));
      if (entry.getDispositionKind() == InventoryCabinDispositionKind.SHIPMENT) {
        return new PublicationTarget(null);
      }
      ObjectNode noWorkRequest = completedNoWorkRequest(assetRequest, assetOutcome.assetVersion());
      JsonNode noWorkOutcome =
          dependencies.applyNoWorkDisposition(
              session.getId(), intent.getFindingId(), ownerEffectKey, noWorkRequest);
      requireMatchingNoWorkOutcome(session, intent, assetRequest, noWorkOutcome);
      return new PublicationTarget(null);
    }
    if (!maintenanceSource.isObject()) {
      throw InventoryException.conflict("Maintenance publication request is invalid");
    }
    ObjectNode maintenanceRequest = (ObjectNode) maintenanceSource.deepCopy();
    maintenanceRequest.put("authoritativeAssetVersion", assetOutcome.assetVersion());
    return new PublicationTarget(
        publicationTarget(
            dependencies.applyReconciliation(
                session.getId(), intent.getFindingId(), ownerEffectKey, maintenanceRequest)));
  }

  /**
   * Projects the asset result into the maintenance-owned no-work contract without leaking the
   * asset-only passport observation fields across that boundary.
   */
  private ObjectNode completedNoWorkRequest(JsonNode assetRequest, long authoritativeAssetVersion) {
    ObjectNode request = mapper.createObjectNode();
    request.put("warehouseId", assetRequest.path("warehouseId").asText());
    request.put("assetId", assetRequest.path("assetId").asText());
    request.put("inventoryCompletedAt", assetRequest.path("inventoryCompletedAt").asText());
    request.put("finalPlanVersion", assetRequest.path("finalPlanVersion").asLong());
    request.put("finalPlanSha256", assetRequest.path("finalPlanSha256").asText());
    request.put("findingRevision", assetRequest.path("findingRevision").asLong());
    request.put("authoritativeAssetVersion", authoritativeAssetVersion);
    request.put("desiredStatus", assetRequest.path("desiredStatus").asText());
    return request;
  }

  private UUID authoritativeOutcomeIdempotencyKey(InventoryPublicationIntent intent) {
    return UUID.nameUUIDFromBytes(
        ("rwms:inventory-service:authoritative-outcome:"
                + intent.getId()
                + ":reapplication:"
                + intent.getOutcomeReapplicationNo())
            .getBytes(StandardCharsets.UTF_8));
  }

  private void requireMatchingNoWorkOutcome(
      InventorySession session,
      InventoryPublicationIntent intent,
      JsonNode request,
      JsonNode result) {
    if (!result.isObject()
        || !session.getId().toString().equals(result.path("inventoryId").asText())
        || !intent.getFindingId().toString().equals(result.path("findingId").asText())
        || !request.path("assetId").asText().equals(result.path("assetId").asText())) {
      throw InventoryException.dependency(
          "Maintenance-service returned mismatched no-work inventory outcome");
    }
  }

  private void requireMatchingCabinPhotoOutcome(
      InventorySession session,
      InventoryPublicationIntent intent,
      JsonNode request,
      InventoryDependencyGateway.InventoryCabinPhotoOutcome result) {
    UUID expectedCabinId = UUID.fromString(request.path("cabinId").asText());
    UUID expectedCoverMediaId = UUID.fromString(request.path("coverMediaId").asText());
    long expectedPhotoCount = request.path("mediaReferences").size();
    if (!session.getId().equals(result.inventoryId())
        || !intent.getFindingId().equals(result.findingId())
        || !expectedCabinId.equals(result.cabinId())
        || !expectedCoverMediaId.equals(result.coverMediaId())
        || result.folderId() == null
        || result.photoCount() != expectedPhotoCount
        || result.libraryVersion() < 1) {
      throw InventoryException.dependency("Media-service returned mismatched inventory cabin photos");
    }
  }

  private void requireMatchingAssetOutcome(
      InventorySession session,
      InventoryPublicationIntent intent,
      JsonNode request,
      InventoryDependencyGateway.InventoryAssetOutcome result) {
    UUID expectedAssetId = UUID.fromString(request.path("assetId").asText());
    if (!session.getId().equals(result.inventoryId())
        || !intent.getFindingId().equals(result.findingId())
        || !expectedAssetId.equals(result.assetId())
        || result.assetVersion() < 0
        || !intent.getDesiredAssetStatus().name().equals(result.status())) {
      throw InventoryException.dependency("Asset-service returned mismatched inventory outcome");
    }
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
            target == null ? null : target.maintenanceResult(),
            intent.getAssetOutcomeResult()));
  }

  void appendPublication(
      InventoryPublicationIntent intent,
      InventorySession session,
      String eventType,
      OpaqueActorReference actor) {
    appendPublication(intent, session, eventType, actor, false);
  }

  /** Initializes or appends the READY fact used by completion and history recovery. */
  void appendPublicationReady(
      InventoryPublicationIntent intent,
      InventorySession session,
      OpaqueActorReference actor,
      boolean initialize) {
    appendPublication(
        intent, session, "inventory.publication.ready.v1", actor, initialize);
  }

  private void appendPublication(
      InventoryPublicationIntent intent,
      InventorySession session,
      String eventType,
      OpaqueActorReference actor,
      boolean initialize) {
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
    if (initialize) {
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

  private boolean furnitureReconciliationSatisfied(UUID inventoryId) {
    return furnitureReconciliations
        .findById(inventoryId)
        .map(value -> value.getState() == FurnitureReconciliationState.SUCCEEDED)
        .orElse(true);
  }

  private static InventoryAssetOutcomeStatus desiredStatus(InventoryFinalPlanEntry entry) {
    return InventoryFinalPlanOutcomePolicy.desiredAssetStatus(entry);
  }

  /**
   * Carries the resolved persisted publication target without exposing raw target selection to the
   * dispatch workflow.
   */
  record PublicationTarget(InventoryPublicationIntent.PublicationTarget value) {}
}
