package dev.buhanzaz.rwms.inventory.eventing;

import dev.buhanzaz.rwms.inventory.service.InventoryLogisticsReturnInboxProcessor;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Retries one due normal-return inbox record through the bounded durable retry policy. */
@Component
public class InventoryLogisticsReturnRecoveryWorker {
  private final InventoryLogisticsReturnRetryStore retries;
  private final InventoryLogisticsReturnInboxProcessor processor;

  public InventoryLogisticsReturnRecoveryWorker(
      InventoryLogisticsReturnRetryStore retries,
      InventoryLogisticsReturnInboxProcessor processor) {
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
