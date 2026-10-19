package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinWriteOffIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinWriteOffIntentState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlan;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanEffectState;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryCabinWriteOffIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanLogisticsEffectRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Persists and dispatches maintenance-owned write-off proposals for cabins omitted from shipments.
 *
 * <p>The final plan freezes the original asset version and inventory never mutates that asset for
 * a write-off. Every automatic attempt therefore reuses both the exact request and idempotency key;
 * semantic rejection or eight transient attempts stop automatic recovery as {@code BLOCKED}.
 */
@Service
final class InventoryCabinWriteOffService {
  private static final Logger log = LoggerFactory.getLogger(InventoryCabinWriteOffService.class);
  private static final int MAX_AUTOMATIC_ATTEMPTS = 8;
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);
  private static final Duration RETRY_DELAY = Duration.ofSeconds(5);

  private final InventoryCabinWriteOffIntentRepository intents;
  private final InventoryDependencyGateway dependencies;
  private final InventoryPlanLogisticsEffectRepository logisticsEffects;
  private final ObjectMapper mapper;
  private final InventoryCanonicalJsonPort canonicalJson;
  private final TransactionTemplate independentTransactions;

  InventoryCabinWriteOffService(
      InventoryCabinWriteOffIntentRepository intents,
      InventoryPlanLogisticsEffectRepository logisticsEffects,
      InventoryDependencyGateway dependencies,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      PlatformTransactionManager transactionManager) {
    this.intents = intents;
    this.logisticsEffects = logisticsEffects;
    this.dependencies = dependencies;
    this.mapper = mapper;
    this.canonicalJson = canonicalJson;
    independentTransactions = new TransactionTemplate(transactionManager);
    independentTransactions.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
  }

  /** Creates immutable local work inside the inventory completion transaction. */
  void createIntents(
      InventorySession session,
      InventoryFinalPlan finalPlan,
      List<InventoryFinalPlanEntry> entries) {
    for (InventoryFinalPlanEntry entry : entries) {
      if (entry.getDispositionKind() != InventoryCabinDispositionKind.WRITE_OFF) continue;
      if (entry.getAssetId() == null || entry.getAssetVersion() == null) {
        throw InventoryException.conflict("Cabin write-off asset evidence is incomplete");
      }
      JsonNode details = read(entry.getDispositionDetails()).path("writeOff");
      String reason = requiredText(details, "reason");
      String evidenceLink = nullableText(details, "evidenceLink");
      InventoryDependencyGateway.InventoryCabinWriteOffRequest request =
          new InventoryDependencyGateway.InventoryCabinWriteOffRequest(
              session.getId(),
              entry.getFindingId(),
              session.getWarehouseId(),
              entry.getAssetId(),
              entry.getAssetVersion(),
              reason,
              evidenceLink);
      String body = write(request);
      String requestSha256 = canonicalJson.sha256(canonicalValue(read(body)));
      UUID key =
          UUID.nameUUIDFromBytes(
              ("rwms:inventory:cabin-write-off:"
                      + session.getId()
                      + ":"
                      + entry.getFindingId())
                  .getBytes(StandardCharsets.UTF_8));
      InventoryCabinWriteOffIntent existing = intents.findById(entry.getFindingId()).orElse(null);
      if (existing != null) {
        if (!existing.getInventoryId().equals(session.getId())
            || !existing.getCabinId().equals(entry.getAssetId())
            || existing.getFinalPlanVersion() != finalPlan.getFinalPlanVersion()
            || existing.getOutcomeReapplicationNo() != 0
            || !existing.getIdempotencyKey().equals(key)
            || !existing.getRequestSha256().equals(requestSha256)) {
          throw InventoryException.conflict("Cabin write-off intent source changed");
        }
        continue;
      }
      intents.saveAndFlush(
          InventoryCabinWriteOffIntent.pending(
              entry.getFindingId(),
              session.getId(),
              session.getWarehouseId(),
              entry.getAssetId(),
              finalPlan.getFinalPlanVersion(),
              0,
              key,
              requestSha256,
              body));
    }
  }

  /** Reopens incomplete write-off intents for the explicit completed-history recovery command. */
  void requeue(
      UUID inventoryId, long finalPlanVersion, long outcomeReapplicationNo) {
    for (InventoryCabinWriteOffIntent candidate :
        intents.findAllByInventoryIdOrderByFindingIdAsc(inventoryId)) {
      if (candidate.getState() == InventoryCabinWriteOffIntentState.SUCCEEDED) continue;
      InventoryCabinWriteOffIntent locked =
          intents.findByFindingIdForUpdate(candidate.getFindingId()).orElseThrow();
      locked.requeueForAuthoritativeRecovery(finalPlanVersion, outcomeReapplicationNo);
      intents.saveAndFlush(locked);
    }
  }

  /** Dispatches one bounded recovery page without allowing a poison row to stop the batch. */
  @Scheduled(fixedDelayString = "${rwms.inventory.cabin-write-off-recovery-delay-ms:5000}")
  public void recoverCabinWriteOffs() {
    List<InventoryCabinWriteOffIntent> ready =
        intents.findRecoverableAfterLogistics(
            EnumSet.of(
                InventoryCabinWriteOffIntentState.PENDING,
                InventoryCabinWriteOffIntentState.TRANSIENT_FAILED),
            EnumSet.of(InventoryPlanEffectState.SUCCEEDED, InventoryPlanEffectState.BLOCKED),
            now(),
            PageRequest.of(0, 20));
    for (InventoryCabinWriteOffIntent candidate : ready) {
      try {
        dispatch(candidate.getFindingId());
      } catch (RuntimeException exception) {
        log.error("Unable to recover cabin write-off {}", candidate.getFindingId(), exception);
      }
    }
  }

  private void dispatch(UUID findingId) {
    ClaimedIntent claimed =
        independentTransactions.execute(
            ignored -> {
              InventoryCabinWriteOffIntent intent =
                  intents.findByFindingIdForUpdate(findingId).orElse(null);
              if (intent == null
                  || intent.getState() == InventoryCabinWriteOffIntentState.SUCCEEDED
                  || intent.getState() == InventoryCabinWriteOffIntentState.BLOCKED
                  || intent.getNextAttemptAt().isAfter(now())) {
                return null;
              }
              var logisticsEffect =
                  logisticsEffects
                      .findForUpdate(
                          intent.getInventoryId(),
                          intent.getFinalPlanVersion(),
                          intent.getOutcomeReapplicationNo())
                      .orElse(null);
              if (logisticsEffect == null) {
                return null;
              }
              if (logisticsEffect.getState() == InventoryPlanEffectState.BLOCKED) {
                intent.block("CABIN_WRITE_OFF_UPSTREAM_LOGISTICS_BLOCKED");
                intents.saveAndFlush(intent);
                return null;
              }
              if (logisticsEffect.getState() != InventoryPlanEffectState.SUCCEEDED) return null;
              intent.beginAttempt(now().plus(CLAIM_LEASE));
              intent = intents.saveAndFlush(intent);
              return new ClaimedIntent(
                  intent.getFindingId(),
                  intent.getAttemptCount(),
                  intent.getIdempotencyKey(),
                  readRequest(intent.getRequestBody()));
            });
    if (claimed == null) return;
    try {
      InventoryDependencyGateway.InventoryCabinWriteOffOutcome outcome =
          dependencies.createInventoryCabinWriteOff(claimed.idempotencyKey(), claimed.request());
      requireMatching(claimed.request(), outcome);
      settleSuccess(claimed, outcome.id());
    } catch (InventoryException exception) {
      settleFailure(claimed, exception);
    } catch (RuntimeException exception) {
      settleFailure(
          claimed, InventoryException.dependency("Cabin write-off dependency unavailable"));
    }
  }

  private void settleSuccess(ClaimedIntent claimed, UUID decisionId) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryCabinWriteOffIntent intent =
              intents.findByFindingIdForUpdate(claimed.findingId()).orElse(null);
          if (!claimedBy(intent, claimed)) return;
          intent.succeed(decisionId);
          intents.saveAndFlush(intent);
        });
  }

  private void settleFailure(ClaimedIntent claimed, InventoryException failure) {
    independentTransactions.executeWithoutResult(
        ignored -> {
          InventoryCabinWriteOffIntent intent =
              intents.findByFindingIdForUpdate(claimed.findingId()).orElse(null);
          if (!claimedBy(intent, claimed)) return;
          String code = safeFailureCode(failure);
          if (semanticRejection(failure)
              || intent.getAttemptCount() >= MAX_AUTOMATIC_ATTEMPTS) {
            intent.block(
                intent.getAttemptCount() >= MAX_AUTOMATIC_ATTEMPTS
                    ? "CABIN_WRITE_OFF_RETRY_EXHAUSTED"
                    : code);
          } else {
            intent.transientFailure(code, now().plus(RETRY_DELAY));
          }
          intents.saveAndFlush(intent);
        });
  }

  private static boolean claimedBy(
      InventoryCabinWriteOffIntent intent, ClaimedIntent claimed) {
    return intent != null
        && intent.getState() == InventoryCabinWriteOffIntentState.PENDING
        && intent.getAttemptCount() == claimed.attemptNo();
  }

  private static boolean semanticRejection(InventoryException exception) {
    return switch (exception.status()) {
      case BAD_REQUEST, NOT_FOUND, CONFLICT, UNPROCESSABLE_ENTITY -> true;
      default -> false;
    };
  }

  private static void requireMatching(
      InventoryDependencyGateway.InventoryCabinWriteOffRequest request,
      InventoryDependencyGateway.InventoryCabinWriteOffOutcome outcome) {
    if (!request.inventorySessionId().equals(outcome.inventorySessionId())
        || !request.findingId().equals(outcome.findingId())
        || !request.warehouseId().equals(outcome.warehouseId())
        || !request.cabinId().equals(outcome.cabinId())
        || !"WRITE_OFF".equals(outcome.disposition())) {
      throw InventoryException.dependency(
          "Maintenance-service returned mismatched cabin write-off decision");
    }
  }

  private InventoryDependencyGateway.InventoryCabinWriteOffRequest readRequest(String body) {
    try {
      return mapper.readValue(body, InventoryDependencyGateway.InventoryCabinWriteOffRequest.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored cabin write-off request is invalid", exception);
    }
  }

  private JsonNode read(String body) {
    try {
      return mapper.readTree(body);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored cabin write-off JSON is invalid", exception);
    }
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Cabin write-off request is not serializable", exception);
    }
  }

  private Object canonicalValue(JsonNode value) {
    try {
      return mapper.treeToValue(value, Object.class);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Cabin write-off request cannot be canonicalized", exception);
    }
  }

  private static String requiredText(JsonNode value, String field) {
    String result = value.path(field).asText();
    if (!value.isObject() || result.isBlank() || result.length() > 2000) {
      throw InventoryException.conflict("Cabin write-off details are invalid");
    }
    return result;
  }

  private static String nullableText(JsonNode value, String field) {
    JsonNode result = value.get(field);
    if (result == null || result.isNull()) return null;
    if (!result.isTextual() || result.asText().isBlank() || result.asText().length() > 2000) {
      throw InventoryException.conflict("Cabin write-off evidence link is invalid");
    }
    return result.asText();
  }

  private static String safeFailureCode(InventoryException exception) {
    String code = exception.code();
    if (code != null && code.matches("^[A-Z][A-Z0-9_]{0,63}$")) return code;
    return exception.status() == HttpStatus.SERVICE_UNAVAILABLE
        ? "CABIN_WRITE_OFF_DEPENDENCY_UNAVAILABLE"
        : "CABIN_WRITE_OFF_REJECTED";
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  /** One lease-fenced remote attempt with the exact stored request. */
  private record ClaimedIntent(
      UUID findingId,
      int attemptNo,
      UUID idempotencyKey,
      InventoryDependencyGateway.InventoryCabinWriteOffRequest request) {}
}
