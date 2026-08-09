package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMembershipMovementRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.Set;
import java.util.UUID;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exact mutable finding workflow surface.
 *
 * <p>Finding source, media and frozen-plan persistence is intentionally delegated to
 * {@link InventoryFindingPersistenceService}; this support keeps authorization, event and
 * aggregate-transition ownership with the finding use case.
 */
abstract class InventoryFindingWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected static final String SESSION_TOPIC = "rwms.inventory.session.v1";
  protected static final OpaqueActorReference ASSET_SYNC_ACTOR =
      new OpaqueActorReference(
          UUID.nameUUIDFromBytes(
                  "rwms:inventory-service:asset-membership".getBytes(StandardCharsets.UTF_8))
              .toString(),
          "SERVICE",
          null);
  protected static final Set<String> RENTAL_ITEM_STATUSES =
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
  protected static final Set<String> CAPTURE_STATUSES =
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
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryExpectedItemRepository expectedItems;
  protected final InventoryMembershipMovementRepository membershipMovements;
  protected final InventoryDependencyGateway dependencies;
  protected final InventoryEventStore events;
  protected final InventoryIdempotencyPort idempotency;
  protected final InventoryFrozenPlanFingerprint frozenPlanFingerprint;
  protected final InventoryFindingValidationService validationService;
  protected final InventoryReviewService reviewService;
  protected final InventoryPlanningService planningService;
  protected final InventoryProjectionService projectionService;
  protected final InventoryFindingPersistenceService findingPersistence;

  protected InventoryFindingWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryExpectedItemRepository expectedItems,
      InventoryMembershipMovementRepository membershipMovements,
      InventoryDependencyGateway dependencies,
      InventoryEventStore events,
      InventoryIdempotencyPort idempotency,
      InventoryFrozenPlanFingerprint frozenPlanFingerprint,
      InventoryFindingValidationService validationService,
      InventoryReviewService reviewService,
      InventoryPlanningService planningService,
      InventoryProjectionService projectionService,
      InventoryFindingPersistenceService findingPersistence,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.expectedItems = expectedItems;
    this.membershipMovements = membershipMovements;
    this.dependencies = dependencies;
    this.events = events;
    this.idempotency = idempotency;
    this.frozenPlanFingerprint = frozenPlanFingerprint;
    this.validationService = validationService;
    this.reviewService = reviewService;
    this.planningService = planningService;
    this.projectionService = projectionService;
    this.findingPersistence = findingPersistence;
  }

  protected InventorySession requireSession(UUID inventoryId) {
    return sessions
        .findById(inventoryId)
        .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
  }

  protected InventorySession requireScopedSession(
      UUID inventoryId, InventoryAuthorizer.WarehouseScope scope) {
    if (!scope.unrestricted() && scope.warehouseIds().isEmpty()) {
      throw InventoryException.notFound("Inventory session not found");
    }
    return (scope.unrestricted()
            ? sessions.findById(inventoryId)
            : sessions.findByIdAndWarehouseIdIn(inventoryId, scope.warehouseIds()))
        .orElseThrow(() -> InventoryException.notFound("Inventory session not found"));
  }

  protected InventorySession requireActive(UUID inventoryId) {
    InventorySession value = requireSession(inventoryId);
    if (value.getLifecycle() != SessionLifecycle.ACTIVE) {
      throw InventoryException.conflict("Inventory session is not active");
    }
    return value;
  }

  protected InventoryFinding requireFinding(UUID inventoryId, UUID findingId) {
    return findings
        .findByIdAndInventoryIdAndMembershipActiveTrue(findingId, inventoryId)
        .orElseThrow(() -> InventoryException.notFound("Inventory finding not found"));
  }

  protected InventorySession requireLifecycle(
      InventorySession session, SessionLifecycle expectedLifecycle) {
    if (session.getLifecycle() != expectedLifecycle) {
      throw InventoryException.conflict(
          expectedLifecycle == SessionLifecycle.ACTIVE
              ? "Inventory session is not active"
              : "Inventory session is not completed");
    }
    return session;
  }

  protected void expectRevision(long actual, long expected) {
    if (actual != expected) throw InventoryException.conflict("Inventory revision is stale");
  }

  protected OpaqueActorReference normalizedAssetActor(OpaqueActorReference actor) {
    return actor != null && Set.of("USER", "SERVICE").contains(actor.principalType())
        ? actor
        : ASSET_SYNC_ACTOR;
  }

  protected String canonicalDisplayNumber(String value) {
    if (value == null) throw new IllegalArgumentException("Rental number is required");
    String display =
        value.trim().replaceAll("\\s+", " ").toUpperCase(java.util.Locale.forLanguageTag("ru-RU"));
    if (!display.matches("^[\\p{L}\\p{N}][\\p{L}\\p{N} -]{0,127}$")) {
      throw new IllegalArgumentException("Rental number contains unsupported punctuation");
    }
    return display;
  }

  protected int mediaCount(InventoryFinding finding) {
    return Math.toIntExact(findingPersistence.mediaCount(finding.getId(), finding.getRevision()));
  }

  protected LogisticsPlanningMode nullableLogisticsPlanningMode(
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

  protected LocalDate nullableLocalDate(JsonNode value, String field, String name) {
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

  protected String tenantSnapshot(JsonNode passportSnapshot) {
    if (passportSnapshot == null || !passportSnapshot.isObject()) return null;
    return nullableText(passportSnapshot.get("tenant"));
  }

  protected Observation observation(ObservationPresence presence, String value) {
    return new Observation(presence, value == null ? null : read(value));
  }
}
