package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.Duration;
import java.util.HashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Startup and periodic repair loop for operation markers and lifecycle readiness. */
@Component
public class WarehouseLifecycleReconciliationScheduler {
  private static final Logger log =
      LoggerFactory.getLogger(WarehouseLifecycleReconciliationScheduler.class);
  private static final int MARK_BATCH_SIZE = 25;
  private static final Duration MARK_CLAIM_LEASE = Duration.ofMinutes(2);
  private static final int READINESS_PAGE_SIZE = 100;
  private static final int MAX_READINESS_PAGES = 10_000;

  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseOperationMarkStore operationMarks;
  private final WarehouseReadinessFenceStore readinessFences;
  private final AtomicBoolean readinessRunning = new AtomicBoolean();

  public WarehouseLifecycleReconciliationScheduler(
      MaintenanceDependencyGateway dependencies,
      WarehouseOperationMarkStore operationMarks,
      WarehouseReadinessFenceStore readinessFences) {
    this.dependencies = dependencies;
    this.operationMarks = operationMarks;
    this.readinessFences = readinessFences;
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.warehouse-lifecycle.operation-mark-delay:2s}",
      initialDelayString =
          "${rwms.maintenance.warehouse-lifecycle.operation-mark-initial-delay:0s}")
  public void reconcileOperationMarks() {
    if (!dependencies.productionReady()) return;
    for (int index = 0; index < MARK_BATCH_SIZE; index++) {
      Optional<WarehouseOperationMarkStore.WorkItem> next =
          operationMarks.claimNextDue(MARK_CLAIM_LEASE);
      if (next.isEmpty()) return;
      WarehouseOperationMarkStore.WorkItem work = next.orElseThrow();
      try {
        // claimNextDue committed its lease/token in a separate transaction. No database lock or
        // application transaction remains open while warehouse-service is called.
        dependencies.markWarehouseOperation(
            work.warehouseId(), work.operationId(), work.occurredAt());
        operationMarks.confirmed(work);
      } catch (RuntimeException failure) {
        recordOperationMarkFailure(work, failure);
      }
    }
  }

  private void recordOperationMarkFailure(
      WarehouseOperationMarkStore.WorkItem work, RuntimeException failure) {
    try {
      boolean quarantined = operationMarks.failed(work, failure);
      if (quarantined) {
        log.error(
            "Warehouse operation mark quarantined: operationId={}, warehouseId={}",
            work.operationId(),
            work.warehouseId(),
            failure);
      } else {
        log.warn(
            "Warehouse operation mark retry scheduled: operationId={}, warehouseId={}",
            work.operationId(),
            work.warehouseId(),
            failure);
      }
    } catch (MaintenanceConflictException staleClaim) {
      // A slow caller can outlive its lease while another node safely reclaims the same
      // idempotent operation. The newer claim owns the outcome; the stale worker must not mutate it.
      log.debug(
          "Warehouse operation mark claim changed before failure recording: operationId={}, warehouseId={}",
          work.operationId(),
          work.warehouseId());
    }
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.warehouse-lifecycle.readiness-delay:30s}",
      initialDelayString = "${rwms.maintenance.warehouse-lifecycle.readiness-initial-delay:0s}")
  public void reconcileReadiness() {
    if (!dependencies.productionReady() || !readinessRunning.compareAndSet(false, true)) return;
    try {
      reconcileReadinessPages();
    } finally {
      readinessRunning.set(false);
    }
  }

  private void reconcileReadinessPages() {
    UUID after = null;
    Set<UUID> cursors = new HashSet<>();
    for (int pageIndex = 0; pageIndex < MAX_READINESS_PAGES; pageIndex++) {
      MaintenanceDependencyGateway.WarehouseLifecycleReadinessWorkPage page =
          dependencies.warehouseLifecycleReadinessWork(after, READINESS_PAGE_SIZE);
      for (MaintenanceDependencyGateway.WarehouseLifecycleReadinessWork item : page.items()) {
        WarehouseReadinessFenceStore.BeginResult fence =
            readinessFences.begin(item.warehouseId(), item.warehouseVersion());
        if (fence.state() == WarehouseReadinessFenceStore.BeginState.FENCED) {
          confirmReadiness(item);
        }
      }
      UUID next = page.nextAfter();
      if (next == null) return;
      if (!cursors.add(next)) {
        throw new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "Warehouse readiness work cursor did not advance");
      }
      after = next;
    }
    throw new MaintenanceDependencyException(
        HttpStatus.SERVICE_UNAVAILABLE,
        "Warehouse readiness work exceeded the bounded pagination limit");
  }

  private void confirmReadiness(
      MaintenanceDependencyGateway.WarehouseLifecycleReadinessWork item) {
    try {
      dependencies.confirmWarehouseLifecycleReadiness(item.warehouseId(), item.warehouseVersion());
      readinessFences.seal(item.warehouseId(), item.warehouseVersion());
    } catch (MaintenanceDependencyException failure) {
      if (failure.status() == HttpStatus.CONFLICT || failure.status() == HttpStatus.NOT_FOUND) {
        readinessFences.release(
            item.warehouseId(),
            item.warehouseVersion(),
            "REMOTE_" + failure.status().value());
        log.debug(
            "Warehouse readiness work changed before confirmation: warehouseId={}, version={}",
            item.warehouseId(),
            item.warehouseVersion());
        return;
      }
      log.warn(
          "Warehouse readiness confirmation failed: warehouseId={}, version={}",
          item.warehouseId(),
          item.warehouseVersion(),
          failure);
    }
  }
}
