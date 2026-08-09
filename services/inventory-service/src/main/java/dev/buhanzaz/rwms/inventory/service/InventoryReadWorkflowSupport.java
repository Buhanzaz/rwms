package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Read-side session and finding repositories plus the projections used by query use cases.
 *
 * <p>Scope filtering stays here so read endpoints preserve their not-found behavior for an
 * inaccessible warehouse rather than leaking inventory identifiers.
 */
abstract class InventoryReadWorkflowSupport extends InventoryTechnicalRuntimeSupport {
  protected final InventorySessionRepository sessions;
  protected final InventoryFindingRepository findings;
  protected final InventoryStatisticsService statisticsService;
  protected final InventoryReviewService reviewService;
  protected final InventoryProjectionService projectionService;

  protected InventoryReadWorkflowSupport(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryStatisticsService statisticsService,
      InventoryReviewService reviewService,
      InventoryProjectionService projectionService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(mapper, canonicalJson, authorizer, transactionManager);
    this.sessions = sessions;
    this.findings = findings;
    this.statisticsService = statisticsService;
    this.reviewService = reviewService;
    this.projectionService = projectionService;
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

  protected Sort sessionSort(String value) {
    if (!Set.of("startedAt,asc", "startedAt,desc", "businessDate,asc", "businessDate,desc")
        .contains(value)) {
      throw InventoryException.badRequest("Unsupported session sort");
    }
    String field = value != null && value.startsWith("businessDate") ? "businessDate" : "startedAt";
    Sort.Direction direction =
        value != null && value.endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field).and(Sort.by("id"));
  }

  protected Sort statisticsSort(String value) {
    if (!Set.of("completedAt,asc", "completedAt,desc", "businessDate,asc", "businessDate,desc")
        .contains(value)) {
      throw InventoryException.badRequest("Unsupported statistics sort");
    }
    String field = value != null && value.startsWith("businessDate") ? "businessDate" : "completedAt";
    Sort.Direction direction =
        value != null && value.endsWith(",asc") ? Sort.Direction.ASC : Sort.Direction.DESC;
    return Sort.by(direction, field).and(Sort.by("id"));
  }

  protected Specification<InventorySession> sessionFilter(
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

  protected Sort findingSort(String value) {
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
}
