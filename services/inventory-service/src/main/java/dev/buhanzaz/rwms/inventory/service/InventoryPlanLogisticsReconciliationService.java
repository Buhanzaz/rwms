package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanOutcomePolicy;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanEffectState;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanLogisticsEffect;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanLogisticsEffectRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/**
 * Owns the one durable logistics reconciliation effect shared by every finding in a completed
 * inventory plan generation.
 *
 * <p>The immutable plan request is persisted before remote I/O, claimed in a short independent
 * transaction and retried with one stable idempotency key. Per-finding publication retries only
 * verify this effect, so a plan with {@code N} findings no longer rebuilds and sends the same
 * {@code N}-row request {@code N} times.
 */
@Service
final class InventoryPlanLogisticsReconciliationService {
  private static final Logger log =
      LoggerFactory.getLogger(InventoryPlanLogisticsReconciliationService.class);
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);
  private static final int MAX_AUTOMATIC_ATTEMPTS = 8;

  private final InventoryPlanLogisticsEffectRepository effects;
  private final InventoryFinalPlanRepository finalPlans;
  private final InventoryFinalPlanEntryRepository finalPlanEntries;
  private final InventoryPublicationIntentRepository publications;
  private final InventoryDependencyGateway dependencies;
  private final ObjectMapper mapper;
  private final InventoryCanonicalJsonPort canonicalJson;
  private final TransactionTemplate independentTransactions;

  InventoryPlanLogisticsReconciliationService(
      InventoryPlanLogisticsEffectRepository effects,
      InventoryFinalPlanRepository finalPlans,
      InventoryFinalPlanEntryRepository finalPlanEntries,
      InventoryPublicationIntentRepository publications,
      InventoryDependencyGateway dependencies,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      PlatformTransactionManager transactionManager) {
    this.effects = effects;
    this.finalPlans = finalPlans;
    this.finalPlanEntries = finalPlanEntries;
    this.publications = publications;
    this.dependencies = dependencies;
    this.mapper = mapper;
    this.canonicalJson = canonicalJson;
    independentTransactions = new TransactionTemplate(transactionManager);
    independentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Persists one immutable plan-level request in the caller's local transaction. */
  void schedule(
      InventorySession session,
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> entries,
      long outcomeReapplicationNo) {
    requirePlanIdentity(session, plan, entries, outcomeReapplicationNo);
    PlanRequest request = planRequest(session, plan, entries, outcomeReapplicationNo);
    InventoryPlanLogisticsEffect current =
        effects
            .findForUpdate(
                session.getId(), plan.getFinalPlanVersion(), outcomeReapplicationNo)
            .orElse(null);
    if (current == null) {
      effects.saveAndFlush(
          InventoryPlanLogisticsEffect.ready(
              session.getId(),
              plan.getFinalPlanVersion(),
              plan.getFinalPlanSha256(),
              outcomeReapplicationNo,
              request.idempotencyKey(),
              request.sha256(),
              request.body()));
      return;
    }
    current.requireSame(
        plan.getFinalPlanSha256(), request.idempotencyKey(), request.sha256());
  }

  /** Returns the latest persisted generation without reconstructing any plan rows. */
  long latestGeneration(UUID inventoryId, long finalPlanVersion) {
    return effects
        .findFirstByInventoryIdAndFinalPlanVersionOrderByOutcomeReapplicationNoDesc(
            inventoryId, finalPlanVersion)
        .map(InventoryPlanLogisticsEffect::getOutcomeReapplicationNo)
        .orElse(-1L);
  }

  /**
   * Ensures the exact plan-level logistics command has succeeded before a finding continues to its
   * maintenance-owned effect.
   */
  void requireApplied(InventorySession session, InventoryPublicationIntent intent) {
    if (intent.getFinalPlanVersion() == null) return;
    ensureScheduledForExistingPlan(session, intent);
    EffectClaim claim = claim(session, intent);
    if (claim == null) return;

    try {
      JsonNode result =
          dependencies.applyLogisticsOutcomes(
              session.getId(), claim.idempotencyKey(), read(claim.requestBody()));
      requireMatchingOutcome(claim, result);
      settleSuccess(claim, result);
    } catch (InventoryException exception) {
      settleFailure(claim, exception);
      throw exception;
    } catch (RuntimeException exception) {
      InventoryException failure =
          InventoryException.dependency("Logistics inventory outcome dispatch failed");
      settleFailure(claim, failure);
      throw failure;
    }
  }

  private void ensureScheduledForExistingPlan(
      InventorySession session, InventoryPublicationIntent intent) {
    independentTransactions.executeWithoutResult(
        status -> {
          if (effects
              .findForUpdate(
                  session.getId(),
                  requireFinalPlanVersion(intent),
                  intent.getOutcomeReapplicationNo())
              .isPresent()) {
            return;
          }
          InventoryFinalPlan plan =
              finalPlans
                  .findById(session.getId())
                  .orElseThrow(
                      () -> InventoryException.conflict("Inventory final plan is missing"));
          List<InventoryFinalPlanEntry> entries =
              finalPlanEntries
                  .findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
                      session.getId(), requireFinalPlanVersion(intent));
          requireSharedGeneration(session, intent, entries);
          schedule(session, plan, entries, intent.getOutcomeReapplicationNo());
        });
  }

  private EffectClaim claim(InventorySession session, InventoryPublicationIntent intent) {
    return independentTransactions.execute(
        status -> {
          InventoryPlanLogisticsEffect effect = requireEffect(session, intent);
          OffsetDateTime current = now();
          if (effect.getState() == InventoryPlanEffectState.SUCCEEDED) return null;
          if (effect.getState() == InventoryPlanEffectState.BLOCKED) {
            throw new InventoryException(
                HttpStatus.CONFLICT,
                effect.getFailureCode(),
                "Logistics rejected the completed inventory plan: " + effect.getFailureCode());
          }
          if (effect.getNextAttemptAt().isAfter(current)) {
            throw InventoryException.dependency(
                "Completed inventory logistics reconciliation is awaiting retry");
          }
          effect.beginAttempt(current.plus(CLAIM_LEASE));
          effect = effects.saveAndFlush(effect);
          return new EffectClaim(
              effect.getId(),
              effect.getAttemptCount(),
              effect.getInventoryId(),
              effect.getFinalPlanVersion(),
              effect.getIdempotencyKey(),
              effect.getRequestBody());
        });
  }

  private void settleSuccess(EffectClaim claim, JsonNode result) {
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryPlanLogisticsEffect effect =
              effects.findByIdForUpdate(claim.effectId()).orElse(null);
          if (!claimedBy(effect, claim)) return;
          effect.succeed(write(result));
          effects.saveAndFlush(effect);
        });
  }

  private void settleFailure(EffectClaim claim, InventoryException exception) {
    independentTransactions.executeWithoutResult(
        status -> {
          InventoryPlanLogisticsEffect effect =
              effects.findByIdForUpdate(claim.effectId()).orElse(null);
          if (!claimedBy(effect, claim)) return;
          String failureCode = safeFailureCode(exception);
          if (semanticRejection(exception) || claim.attemptNo() >= MAX_AUTOMATIC_ATTEMPTS) {
            effect.block(
                claim.attemptNo() >= MAX_AUTOMATIC_ATTEMPTS
                    ? "LOGISTICS_RETRY_EXHAUSTED"
                    : failureCode);
          } else {
            effect.transientFailure(failureCode, now().plus(RETRY_DELAY));
          }
          effects.saveAndFlush(effect);
        });
    log.warn(
        "Inventory plan logistics effect {} attempt {} failed with {}",
        claim.effectId(),
        claim.attemptNo(),
        safeFailureCode(exception));
  }

  private InventoryPlanLogisticsEffect requireEffect(
      InventorySession session, InventoryPublicationIntent intent) {
    InventoryPlanLogisticsEffect effect =
        effects
            .findForUpdate(
                session.getId(),
                requireFinalPlanVersion(intent),
                intent.getOutcomeReapplicationNo())
            .orElseThrow(
                () ->
                    new IllegalStateException(
                        "Scheduled inventory plan logistics effect is missing"));
    if (!intent.getFinalPlanSha256().equals(effect.getFinalPlanSha256())) {
      throw InventoryException.conflict("Inventory plan logistics evidence is stale");
    }
    return effect;
  }

  private void requireSharedGeneration(
      InventorySession session,
      InventoryPublicationIntent selected,
      List<InventoryFinalPlanEntry> entries) {
    Map<UUID, InventoryPublicationIntent> byFinding = new LinkedHashMap<>();
    publications
        .findAllByInventoryIdOrderByFindingId(session.getId())
        .forEach(value -> byFinding.put(value.getFindingId(), value));
    for (InventoryFinalPlanEntry entry : entries) {
      if (!InventoryFinalPlanOutcomePolicy.assetPublicationRequired(entry)) continue;
      InventoryPublicationIntent intent = byFinding.get(entry.getFindingId());
      if (intent == null
          || !selected.getFinalPlanVersion().equals(intent.getFinalPlanVersion())
          || !selected.getFinalPlanSha256().equals(intent.getFinalPlanSha256())
          || selected.getOutcomeReapplicationNo() != intent.getOutcomeReapplicationNo()) {
        throw InventoryException.conflict(
            "Inventory outcome reapplication generation is inconsistent");
      }
    }
  }

  private PlanRequest planRequest(
      InventorySession session,
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> entries,
      long outcomeReapplicationNo) {
    ObjectNode request = mapper.createObjectNode();
    request.put("warehouseId", session.getWarehouseId().toString());
    request.put("inventoryCompletedAt", session.getCompletedAt().toString());
    request.put("finalPlanVersion", plan.getFinalPlanVersion());
    request.put("finalPlanSha256", plan.getFinalPlanSha256());
    ArrayNode outcomes = request.putArray("outcomes");
    for (InventoryFinalPlanEntry entry : entries) {
      if (entry.getAssetId() == null) {
        throw InventoryException.conflict("Inventory final-plan asset identity is missing");
      }
      JsonNode details = read(entry.getDispositionDetails());
      ObjectNode outcome = outcomes.addObject();
      outcome.put("findingId", entry.getFindingId().toString());
      outcome.put("assetId", entry.getAssetId().toString());
      outcome.put("dispositionKind", entry.getDispositionKind().name());
      switch (entry.getDispositionKind()) {
        case LOCAL -> {
          outcome.put(
              "desiredStatus", InventoryFinalPlanOutcomePolicy.desiredAssetStatus(entry).name());
          JsonNode formerRental = details.get("formerRental");
          if (formerRental == null || formerRental.isNull()) outcome.putNull("formerRental");
          else if (formerRental.isObject()) outcome.set("formerRental", formerRental);
          else throw InventoryException.conflict("Former rental evidence is invalid");
          outcome.putNull("shipment");
        }
        case SHIPMENT -> {
          JsonNode shipment = details.get("shipment");
          if (shipment == null || !shipment.isObject()) {
            throw InventoryException.conflict("Shipment evidence is invalid");
          }
          outcome.put("desiredStatus", "RENTED");
          outcome.putNull("formerRental");
          outcome.set("shipment", shipment);
        }
        case WRITE_OFF -> {
          outcome.put("desiredStatus", "WRITE_OFF_PENDING");
          outcome.putNull("formerRental");
          outcome.putNull("shipment");
        }
      }
    }
    Object canonicalValue = canonicalValue(request);
    String sha256 = canonicalJson.sha256(canonicalValue);
    UUID idempotencyKey =
        UUID.nameUUIDFromBytes(
            ("rwms:inventory-service:logistics-outcomes:"
                    + session.getId()
                    + ":"
                    + plan.getFinalPlanVersion()
                    + ":"
                    + plan.getFinalPlanSha256()
                    + ":reapplication:"
                    + outcomeReapplicationNo)
                .getBytes(StandardCharsets.UTF_8));
    return new PlanRequest(idempotencyKey, sha256, write(request));
  }

  private static void requirePlanIdentity(
      InventorySession session,
      InventoryFinalPlan plan,
      List<InventoryFinalPlanEntry> entries,
      long outcomeReapplicationNo) {
    if (session == null
        || plan == null
        || !session.getId().equals(plan.getInventoryId())
        || plan.getFinalPlanVersion() < 1
        || plan.getFinalPlanSha256() == null
        || entries == null
        || entries.isEmpty()
        || outcomeReapplicationNo < 0) {
      throw InventoryException.conflict("Inventory final plan logistics evidence is incomplete");
    }
    for (InventoryFinalPlanEntry entry : entries) {
      if (!session.getId().equals(entry.getInventoryId())
          || entry.getFinalPlanVersion() != plan.getFinalPlanVersion()) {
        throw InventoryException.conflict("Inventory final plan logistics evidence is stale");
      }
    }
  }

  private static void requireMatchingOutcome(EffectClaim claim, JsonNode result) {
    if (!result.isObject()
        || !claim.inventoryId().toString().equals(result.path("inventoryId").asText())
        || result.path("finalPlanVersion").asLong(-1) != claim.finalPlanVersion()) {
      throw InventoryException.dependency(
          "Logistics-service returned mismatched inventory outcomes");
    }
  }

  /** Recovers plan-wide logistics even when every finding is an asset-less write-off. */
  @Scheduled(fixedDelayString = "${rwms.inventory.plan-logistics-recovery-delay-ms:5000}")
  public void recoverPlanLogisticsEffects() {
    List<InventoryPlanLogisticsEffect> candidates =
        effects.findTop20ByStateInAndNextAttemptAtLessThanEqualOrderByNextAttemptAtAscIdAsc(
            EnumSet.of(
                InventoryPlanEffectState.READY,
                InventoryPlanEffectState.TRANSIENT_FAILED,
                InventoryPlanEffectState.PENDING),
            now());
    for (InventoryPlanLogisticsEffect candidate : candidates) {
      EffectClaim claim = claim(candidate.getId());
      if (claim == null) continue;
      try {
        JsonNode result =
            dependencies.applyLogisticsOutcomes(
                claim.inventoryId(), claim.idempotencyKey(), read(claim.requestBody()));
        requireMatchingOutcome(claim, result);
        settleSuccess(claim, result);
      } catch (InventoryException exception) {
        settleFailure(claim, exception);
      } catch (RuntimeException exception) {
        settleFailure(
            claim, InventoryException.dependency("Logistics inventory outcome dispatch failed"));
      }
    }
  }

  private EffectClaim claim(UUID effectId) {
    return independentTransactions.execute(
        ignored -> {
          InventoryPlanLogisticsEffect effect = effects.findByIdForUpdate(effectId).orElse(null);
          if (effect == null
              || effect.getState() == InventoryPlanEffectState.SUCCEEDED
              || effect.getState() == InventoryPlanEffectState.BLOCKED
              || effect.getNextAttemptAt().isAfter(now())) {
            return null;
          }
          effect.beginAttempt(now().plus(CLAIM_LEASE));
          effect = effects.saveAndFlush(effect);
          return new EffectClaim(
              effect.getId(),
              effect.getAttemptCount(),
              effect.getInventoryId(),
              effect.getFinalPlanVersion(),
              effect.getIdempotencyKey(),
              effect.getRequestBody());
        });
  }

  private static boolean claimedBy(
      InventoryPlanLogisticsEffect effect, EffectClaim claim) {
    return effect != null
        && effect.getState() == InventoryPlanEffectState.PENDING
        && effect.getAttemptCount() == claim.attemptNo();
  }

  private static boolean semanticRejection(InventoryException exception) {
    return switch (exception.status()) {
      case BAD_REQUEST, NOT_FOUND, CONFLICT, UNPROCESSABLE_ENTITY -> true;
      default -> false;
    };
  }

  private static String safeFailureCode(InventoryException exception) {
    String code = exception.code();
    if (code != null && code.matches("^[A-Z][A-Z0-9_]{0,63}$")) return code;
    return exception.status() == HttpStatus.SERVICE_UNAVAILABLE
        ? "LOGISTICS_DEPENDENCY_UNAVAILABLE"
        : "LOGISTICS_PLAN_REJECTED";
  }

  private static long requireFinalPlanVersion(InventoryPublicationIntent intent) {
    Long version = intent.getFinalPlanVersion();
    if (version == null || version < 1) {
      throw InventoryException.conflict("Inventory final plan version is missing");
    }
    return version;
  }

  private JsonNode read(String value) {
    try {
      return mapper.readTree(value);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored inventory logistics request is invalid", exception);
    }
  }

  private String write(JsonNode value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory logistics value is not serializable", exception);
    }
  }

  private Object canonicalValue(JsonNode value) {
    try {
      return mapper.treeToValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inventory logistics value cannot be canonicalized", exception);
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** Immutable remote-call claim fenced by its persisted attempt number. */
  private record EffectClaim(
      UUID effectId,
      int attemptNo,
      UUID inventoryId,
      long finalPlanVersion,
      UUID idempotencyKey,
      String requestBody) {}

  /** Immutable canonical logistics request identity. */
  private record PlanRequest(UUID idempotencyKey, String sha256, String body) {}
}
