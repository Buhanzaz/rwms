package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.*;

import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.security.InventoryAuthorizer;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.ObjectMapper;

/**
 * Provides scoped inventory-session, finding and persisted-statistics read models.
 *
 * <p>It performs authorization before repository access and never refreshes remote registry state
 * for a read request; completion owns that stronger validation boundary.
 */
@Service
final class InventoryReadService extends InventoryReadWorkflowSupport {
  InventoryReadService(
      InventorySessionRepository sessions,
      InventoryFindingRepository findings,
      InventoryStatisticsService statisticsService,
      InventoryReviewService reviewService,
      InventoryProjectionService projectionService,
      ObjectMapper mapper,
      InventoryCanonicalJsonPort canonicalJson,
      InventoryAuthorizer authorizer,
      PlatformTransactionManager transactionManager) {
    super(
        sessions,
        findings,
        statisticsService,
        reviewService,
        projectionService,
        mapper,
        canonicalJson,
        authorizer,
        transactionManager);
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
    Map<UUID, InventoryProjectionService.SessionCounts> counts =
        projectionService.sessionCounts(inventoryIds);
    Map<UUID, String> publicationStates =
        projectionService.sessionPublicationStates(inventoryIds);
    Map<UUID, dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState>
        furnitureStates = projectionService.sessionFurnitureStates(pageContent);
    return new PageResponse<>(
        pageContent.stream()
            .map(
                value ->
                    projectionService.sessionSummary(
                        value,
                        counts.getOrDefault(value.getId(), InventoryProjectionService.SessionCounts.EMPTY),
                        publicationStates.getOrDefault(value.getId(), "NOT_REQUESTED"),
                        furnitureStates.getOrDefault(
                            value.getId(),
                            dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState
                                .NOT_REQUIRED)))
            .toList(),
        new PageMetadata(page, size, result.getTotalElements(), result.getTotalPages()));
  }

  public Optional<SessionView> active(Jwt jwt, UUID warehouseId) {
    authorizer.requireRead(jwt, warehouseId);
    return sessions
        .findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE)
        .map(projectionService::sessionView);
  }

  public SessionView session(Jwt jwt, UUID inventoryId) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    return projectionService.sessionView(session);
  }

  public PageResponse<FindingView> findings(
      Jwt jwt, UUID inventoryId, int page, int size, String sort) {
    InventorySession session = requireScopedSession(inventoryId, authorizer.readScope(jwt));
    Page<InventoryFinding> result =
        findings.findByInventoryIdAndMembershipActiveTrue(
            inventoryId, PageRequest.of(page, size, findingSort(sort)));
    return new PageResponse<>(
        projectionService.findingViews(result.getContent()),
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
    reviewService.requireCabinReviewStage(session);
    List<InventoryFinding> activeFindings =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId);
    return statisticsService.calculatePersistedStatistics(session, activeFindings);
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
                        statisticsService.readStatistics(value.getId())))
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
    FrozenStatistics total = statisticsService.summarizeStatistics(ids);
    return new StatisticsSummary(ids.size(), total);
  }
}
