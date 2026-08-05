package dev.buhanzaz.rwms.taskboard.service;

import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWorkPage;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Pull-based recovery for missed lifecycle events and service restarts. All work is bounded; a
 * dependency or local inspection failure leaves the warehouse unconfirmed for the next cycle.
 */
@Component
@ConditionalOnProperty(
    prefix = "rwms.warehouse.lifecycle.reconciliation",
    name = "enabled",
    havingValue = "true",
    matchIfMissing = true)
public class WarehouseLifecycleReadinessReconciler {
  private static final Logger log = LoggerFactory.getLogger(WarehouseLifecycleReadinessReconciler.class);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES_PER_RUN = 100;

  private final WarehouseLifecycleGateway warehouseLifecycle;
  private final WarehouseLifecycleFence fence;

  public WarehouseLifecycleReadinessReconciler(
      WarehouseLifecycleGateway warehouseLifecycle, WarehouseLifecycleFence fence) {
    this.warehouseLifecycle = warehouseLifecycle;
    this.fence = fence;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reconcileOnApplicationReady() {
    reconcile();
  }

  @Scheduled(
      fixedDelayString = "${rwms.warehouse.lifecycle.reconciliation.delay:PT30S}",
      initialDelayString = "${rwms.warehouse.lifecycle.reconciliation.initial-delay:PT30S}")
  public void reconcilePeriodically() {
    reconcile();
  }

  /** Visible for focused failure and paging tests; normal callers use startup or the scheduler. */
  public void reconcile() {
    UUID after = null;
    Set<UUID> observedCursors = new HashSet<>();
    for (int pageNumber = 0; pageNumber < MAX_PAGES_PER_RUN; pageNumber++) {
      ReadinessWorkPage page;
      try {
        page = warehouseLifecycle.readinessWork(after, PAGE_SIZE);
      } catch (RuntimeException exception) {
        log.warn("Could not load task-board warehouse lifecycle readiness work", exception);
        return;
      }
      if (page == null || page.items() == null) {
        log.warn("Warehouse-service returned malformed task-board lifecycle readiness work");
        return;
      }
      for (ReadinessWork work : page.items()) {
        reconcileWarehouse(work);
      }
      UUID nextAfter = page.nextAfter();
      if (nextAfter == null) {
        return;
      }
      if (page.items().isEmpty() || !observedCursors.add(nextAfter)) {
        log.warn("Warehouse-service returned a non-progressing lifecycle readiness cursor");
        return;
      }
      after = nextAfter;
    }
    log.warn("Stopped task-board warehouse lifecycle reconciliation after {} pages", MAX_PAGES_PER_RUN);
  }

  private void reconcileWarehouse(ReadinessWork work) {
    try {
      if (!fence.confirmReadinessIfNoLiveWork(work)) {
        log.debug("Task-board still has live work for draining warehouse {}", work.warehouseId());
      }
    } catch (WarehouseLifecycleReadinessConflictException stale) {
      // The warehouse owner changed between page read and confirmation. Start from the first page
      // next cycle so the current DRAINING version is observed before another confirmation.
      log.debug("Warehouse lifecycle version changed for {}; deferring to the next cycle", work.warehouseId());
    } catch (RuntimeException exception) {
      log.warn(
          "Could not reconcile task-board lifecycle readiness for warehouse {}",
          work == null ? null : work.warehouseId(),
          exception);
    }
  }
}
