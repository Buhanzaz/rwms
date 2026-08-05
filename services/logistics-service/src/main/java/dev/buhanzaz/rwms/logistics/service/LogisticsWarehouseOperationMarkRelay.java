package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Delivers warehouse operation marks only after the local claim transaction has committed. */
@Component
public class LogisticsWarehouseOperationMarkRelay {
  private static final Logger log =
      LoggerFactory.getLogger(LogisticsWarehouseOperationMarkRelay.class);
  private static final int BATCH_SIZE = 100;
  private static final Duration CLAIM_LEASE = Duration.ofSeconds(30);

  private final LogisticsWarehouseOperationMarkStore store;
  private final LogisticsDependencyGateway dependencies;
  private final AtomicBoolean running = new AtomicBoolean();

  public LogisticsWarehouseOperationMarkRelay(
      LogisticsWarehouseOperationMarkStore store, LogisticsDependencyGateway dependencies) {
    this.store = store;
    this.dependencies = dependencies;
  }

  @Scheduled(
      initialDelayString = "${rwms.logistics.warehouse-operation-marks.initial-delay:2s}",
      fixedDelayString = "${rwms.logistics.warehouse-operation-marks.delay:2s}")
  public void relay() {
    if (!dependencies.productionReady() || !running.compareAndSet(false, true)) return;
    try {
      for (int index = 0; index < BATCH_SIZE; index++) {
        var claimed = store.claimNext(CLAIM_LEASE);
        if (claimed.isEmpty()) return;
        deliver(claimed.get());
      }
    } finally {
      running.set(false);
    }
  }

  private void deliver(LogisticsWarehouseOperationMarkStore.WorkItem work) {
    try {
      dependencies.markWarehouseOperation(
          work.warehouseId(), work.operationId(), work.occurredAt());
      store.confirmed(work);
    } catch (RuntimeException failure) {
      String code = safeCode(failure);
      boolean quarantined = store.failed(work, code);
      if (quarantined) {
        log.error(
            "Warehouse operation mark quarantined for warehouse {} operation {} code {}",
            work.warehouseId(),
            work.operationId(),
            code);
      } else {
        log.warn(
            "Warehouse operation mark delivery deferred for warehouse {} operation {} code {}",
            work.warehouseId(),
            work.operationId(),
            code);
      }
    }
  }

  private static String safeCode(RuntimeException failure) {
    if (failure instanceof LogisticsDependencyException dependency) {
      return "WAREHOUSE_" + dependency.kind().name();
    }
    return "WAREHOUSE_UNKNOWN_FAILURE";
  }
}
