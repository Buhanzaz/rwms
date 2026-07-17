package dev.buhanzaz.rwms.inventory.eventing;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
public class InventoryMediaRecoveryWorker {
  private final InventoryMediaRetryStore retries;
  private final InventoryMediaInboxProcessor processor;

  public InventoryMediaRecoveryWorker(
      InventoryMediaRetryStore retries, InventoryMediaInboxProcessor processor) {
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
