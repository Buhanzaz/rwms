package dev.buhanzaz.rwms.warehouse.eventing;

import java.util.UUID;
import org.springframework.stereotype.Service;

/** Application boundary for a reviewed, immutable-envelope warehouse outbox requeue. */
@Service
public class WarehouseOutboxRecoveryService {
  private final WarehouseOutboxRecoveryStore store;

  public WarehouseOutboxRecoveryService(WarehouseOutboxRecoveryStore store) {
    this.store = store;
  }

  public WarehouseOutboxRecoveryStore.RecoveryResult recover(
      UUID eventId, long expectedReviewVersion, UUID reviewedBySubjectId, String reason) {
    return store.recover(eventId, expectedReviewVersion, reviewedBySubjectId, reason);
  }
}
