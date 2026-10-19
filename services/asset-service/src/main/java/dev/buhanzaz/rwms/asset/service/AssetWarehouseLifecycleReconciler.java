package dev.buhanzaz.rwms.asset.service;

import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseLifecycleReadinessWork;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient.WarehouseLifecycleReadinessWorkPage;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pulls the durable warehouse lifecycle backlog after startup and periodically.
 * It confirms ASSET readiness only once asset-owned physical balances and active
 * local workflows have drained; Kafka notifications are deliberately not used as
 * a correctness trigger.
 */
@Component
public class AssetWarehouseLifecycleReconciler {
  private static final Logger log =
      LoggerFactory.getLogger(AssetWarehouseLifecycleReconciler.class);
  private static final int READINESS_PAGE_SIZE = 100;
  private static final int MARK_PAGE_SIZE = 100;
  private static final int MAX_PAGES_PER_RUN = 100;

  private final WarehouseRegistryClient warehouses;
  private final AssetWarehouseLifecycleStore lifecycle;
  private final JdbcTemplate jdbc;
  private final AtomicBoolean running = new AtomicBoolean();

  public AssetWarehouseLifecycleReconciler(
      WarehouseRegistryClient warehouses, AssetWarehouseLifecycleStore lifecycle, JdbcTemplate jdbc) {
    this.warehouses = warehouses;
    this.lifecycle = lifecycle;
    this.jdbc = jdbc;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reconcileAtStartup() {
    reconcile();
  }

  @Scheduled(
      initialDelayString = "${rwms.asset.warehouse-lifecycle.initial-delay:5s}",
      fixedDelayString = "${rwms.asset.warehouse-lifecycle.reconciliation-delay:30s}")
  public void reconcilePeriodically() {
    reconcile();
  }

  /** Visible to focused tests; production invokes this only at startup or from the bounded scheduler. */
  public void reconcile() {
    if (!warehouses.lifecycleIntegrationEnabled() || !running.compareAndSet(false, true)) {
      return;
    }
    try {
      if (reconcileOperationMarks()) {
        reconcileReadinessPages();
      }
    } catch (RuntimeException failure) {
      log.warn("Asset warehouse lifecycle reconciliation failed", failure);
    } finally {
      running.set(false);
    }
  }

  /**
   * domain_event is the durable source of an asset operation. One stable first
   * event per warehouse is enough to prove that the warehouse has operated;
   * retries reuse exactly the same immutable event UUID and timestamp.
   */
  private boolean reconcileOperationMarks() {
    boolean complete = true;
    UUID after = null;
    for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_RUN; pageNumber++) {
      List<OperationMarkCandidate> page = operationMarkPage(after, MARK_PAGE_SIZE + 1);
      boolean hasMore = page.size() > MARK_PAGE_SIZE;
      List<OperationMarkCandidate> items =
          hasMore ? List.copyOf(page.subList(0, MARK_PAGE_SIZE)) : List.copyOf(page);
      for (OperationMarkCandidate candidate : items) {
        try {
          // Resolve at the immutable operation instant before marking it. A later timezone
          // decision can therefore never reinterpret this operation's local date boundary.
          warehouses.timeZoneAt(candidate.warehouseId(), candidate.occurredAt());
          warehouses.markOperation(
              candidate.warehouseId(), candidate.operationId(), candidate.occurredAt());
        } catch (RuntimeException failure) {
          complete = false;
          log.warn(
              "Could not reconcile asset warehouse operation mark: warehouseId={}, operationId={}",
              candidate.warehouseId(),
              candidate.operationId(),
              failure);
        }
      }
      if (!hasMore) return complete;
      after = items.getLast().warehouseId();
    }
    log.warn(
        "Stopped asset warehouse operation-mark reconciliation after {} pages", MAX_PAGES_PER_RUN);
    return false;
  }

  private List<OperationMarkCandidate> operationMarkPage(UUID after, int limit) {
    String candidates =
        """
        with candidates as (
          select event_id,occurred_at,payload ->> 'warehouseId' as warehouse_id
            from domain_event where payload ? 'warehouseId'
          union all
          select event_id,occurred_at,payload ->> 'sourceWarehouseId' as warehouse_id
            from domain_event where payload ? 'sourceWarehouseId'
          union all
          select event_id,occurred_at,payload ->> 'targetWarehouseId' as warehouse_id
            from domain_event where payload ? 'targetWarehouseId'
          union all
          select event_id,occurred_at,payload ->> 'originWarehouseId' as warehouse_id
            from domain_event where payload ? 'originWarehouseId'
          union all
          select event_id,occurred_at,payload ->> 'destinationWarehouseId' as warehouse_id
            from domain_event where payload ? 'destinationWarehouseId'
        ), first_per_warehouse as (
          select distinct on (warehouse_id) warehouse_id,event_id,occurred_at
            from candidates
           where warehouse_id is not null and occurred_at is not null
           order by warehouse_id,occurred_at,event_id
        )
        select warehouse_id::uuid as warehouse_id,event_id,occurred_at
          from first_per_warehouse
        """;
    String query =
        after == null
            ? candidates + " order by warehouse_id::uuid limit ?"
            : candidates + " where warehouse_id::uuid > ? order by warehouse_id::uuid limit ?";
    return after == null
        ? jdbc.query(
            query,
            (resultSet, rowNumber) ->
                new OperationMarkCandidate(
                    resultSet.getObject("warehouse_id", UUID.class),
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getObject("occurred_at", OffsetDateTime.class)),
            limit)
        : jdbc.query(
            query,
            (resultSet, rowNumber) ->
                new OperationMarkCandidate(
                    resultSet.getObject("warehouse_id", UUID.class),
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getObject("occurred_at", OffsetDateTime.class)),
            after,
            limit);
  }

  private void reconcileReadinessPages() {
    UUID after = null;
    Set<UUID> seenCursors = new HashSet<>();
    for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_RUN; pageNumber++) {
      WarehouseLifecycleReadinessWorkPage page =
          warehouses.lifecycleReadinessWork(after, READINESS_PAGE_SIZE);
      for (WarehouseLifecycleReadinessWork work : page.items()) {
        confirmReadinessWhenDrained(work);
      }
      UUID next = page.nextAfter();
      if (next == null) return;
      if (page.items().isEmpty() || !seenCursors.add(next)) {
        throw new IllegalStateException("Warehouse readiness work cursor did not advance");
      }
      after = next;
    }
    log.warn("Stopped asset warehouse readiness reconciliation after {} pages", MAX_PAGES_PER_RUN);
  }

  private void confirmReadinessWhenDrained(WarehouseLifecycleReadinessWork work) {
    AssetWarehouseLifecycleStore.ReadinessAttempt attempt =
        lifecycle.beginReadiness(work.warehouseId(), work.warehouseVersion());
    if (!attempt.shouldConfirm()) {
      log.debug("Asset-service still owns live resources at draining warehouse {}", work.warehouseId());
      return;
    }
    if (attempt.sealed()) return;
    try {
      // beginReadiness() committed its local fence before this call. Keep HTTP outside every
      // asset transaction so an uncertain remote result leaves a durable, recoverable attempt.
      warehouses.confirmLifecycleReadiness(attempt.warehouseId(), attempt.warehouseVersion());
      lifecycle.sealReadiness(attempt.warehouseId(), attempt.warehouseVersion());
    } catch (AssetDependencyException conflictOrDependencyFailure) {
      if (conflictOrDependencyFailure.status() == HttpStatus.CONFLICT
          || conflictOrDependencyFailure.status() == HttpStatus.UNPROCESSABLE_CONTENT) {
        lifecycle.releaseReadiness(attempt.warehouseId(), attempt.warehouseVersion());
        log.debug(
            "Warehouse lifecycle changed before asset readiness confirmation: warehouseId={}, version={}",
            attempt.warehouseId(),
            attempt.warehouseVersion());
        return;
      }
      throw conflictOrDependencyFailure;
    }
  }

  private record OperationMarkCandidate(
      UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {}
}
