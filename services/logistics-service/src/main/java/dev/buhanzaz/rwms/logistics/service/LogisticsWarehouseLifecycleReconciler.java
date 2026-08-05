package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseLifecycleReadinessWork;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseLifecycleReadinessWorkPage;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Durable startup/periodic reconciliation of logistics-owned warehouse drain readiness. */
@Component
public class LogisticsWarehouseLifecycleReconciler {
  private static final Logger log =
      LoggerFactory.getLogger(LogisticsWarehouseLifecycleReconciler.class);
  private static final int PAGE_SIZE = 100;
  private static final int MAX_PAGES = 100;

  private final LogisticsDependencyGateway dependencies;
  private final LogisticsWarehouseLifecycleStore store;
  private final AtomicBoolean running = new AtomicBoolean();

  public LogisticsWarehouseLifecycleReconciler(
      LogisticsDependencyGateway dependencies, LogisticsWarehouseLifecycleStore store) {
    this.dependencies = dependencies;
    this.store = store;
  }

  @EventListener(ApplicationReadyEvent.class)
  public void reconcileAtStartup() {
    reconcile();
  }

  @Scheduled(
      initialDelayString = "${rwms.logistics.warehouse-lifecycle.initial-delay:5s}",
      fixedDelayString = "${rwms.logistics.warehouse-lifecycle.delay:30s}")
  public void reconcile() {
    if (!dependencies.productionReady() || !running.compareAndSet(false, true)) return;
    try {
      for (WarehouseLifecycleReadinessWork work : loadWork()) {
        confirmWhenReady(work);
      }
    } catch (RuntimeException failure) {
      log.warn("Logistics warehouse lifecycle reconciliation failed", failure);
    } finally {
      running.set(false);
    }
  }

  private List<WarehouseLifecycleReadinessWork> loadWork() {
    UUID after = null;
    Set<UUID> cursors = new HashSet<>();
    List<WarehouseLifecycleReadinessWork> work = new ArrayList<>();
    for (int page = 0; page < MAX_PAGES; page++) {
      WarehouseLifecycleReadinessWorkPage response =
          dependencies.warehouseLifecycleReadinessWork(after, PAGE_SIZE);
      work.addAll(response.items());
      UUID next = response.nextAfter();
      if (next == null) return List.copyOf(work);
      if (!cursors.add(next)) {
        throw new IllegalStateException("Warehouse readiness worklist cursor repeated");
      }
      after = next;
    }
    log.warn("Logistics warehouse readiness reached its {} page run limit", MAX_PAGES);
    return List.copyOf(work);
  }

  private void confirmWhenReady(WarehouseLifecycleReadinessWork work) {
    LogisticsWarehouseLifecycleStore.ReadinessAttempt attempt =
        store.beginReadiness(work.warehouseId(), work.warehouseVersion());
    if (!attempt.shouldConfirm() || attempt.sealed()) return;
    try {
      dependencies.confirmWarehouseLifecycleReadiness(
          attempt.warehouseId(), attempt.warehouseVersion());
      store.sealReadiness(attempt.warehouseId(), attempt.warehouseVersion());
    } catch (LogisticsDependencyException failure) {
      if (failure.kind() == LogisticsDependencyException.FailureKind.PERMANENT_REJECTION) {
        store.releaseReadiness(attempt.warehouseId(), attempt.warehouseVersion());
        log.debug(
            "Logistics readiness fence changed for warehouse {}",
            attempt.warehouseId(),
            failure);
        return;
      }
      // Unknown outcome keeps CONFIRMING. That local seal is required because warehouse-service
      // may already have committed the idempotent readiness record before the response was lost.
      throw failure;
    }
  }
}
