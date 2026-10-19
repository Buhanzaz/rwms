package dev.buhanzaz.rwms.inventory.eventing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled worker that retries due inventory inbox records through their durable retry store.
 */
@Component
public class InventoryAssetRecoveryWorker {
  private final InventoryAssetRetryStore retries;
  private final InventoryAssetInboxProcessor processor;

  public InventoryAssetRecoveryWorker(
      InventoryAssetRetryStore retries, InventoryAssetInboxProcessor processor) {
    this.retries = retries;
    this.processor = processor;
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.eventing.inbox.retry-poll-delay:1s}")
  public void retryOne() {
    retries
        .due()
        .ifPresent(
            eventId -> {
              try {
                processor.retry(eventId);
              } catch (RuntimeException exception) {
                retries.failed(eventId);
              }
            });
  }
}
