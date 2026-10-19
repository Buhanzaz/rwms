package dev.buhanzaz.rwms.warehouse.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Deletes expired create-command replay records in bounded scheduled batches. */
@Component
public class WarehouseIdempotencyCleanup {
  private final WarehouseIdempotencyStore store;

  public WarehouseIdempotencyCleanup(WarehouseIdempotencyStore store) {
    this.store = store;
  }

  @Scheduled(fixedDelayString = "${rwms.warehouse.idempotency.cleanup-delay:1h}")
  public void removeExpiredRecords() {
    store.cleanupExpired();
  }
}
