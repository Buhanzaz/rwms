package dev.buhanzaz.rwms.logistics.eventing;

import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Persists sanitized dead-letter records for failed logistics outbox processing without exposing event payloads.
 */
@Component
@RequiredArgsConstructor
public class LogisticsSanitizedDltPublisher {
  private final LogisticsSanitizedDltStore store;

  public void validationRejected(LogisticsOutboxStore.Claim claim) {
    store.enqueue(claim, "VALIDATION_REJECTED");
  }

  public void processingFailed(LogisticsOutboxStore.Claim claim) {
    store.enqueue(claim, "PROCESSING_FAILED");
  }
}
