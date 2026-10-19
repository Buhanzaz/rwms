package dev.buhanzaz.rwms.taskboard.eventing;

import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Periodically retries worker media inbox rows that arrived before their referenced evidence. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class WorkerMediaInboxReconciler {
  private final WorkerMediaEventProcessor processor;

  @Scheduled(
      fixedDelayString = "${rwms.worker.media-inbox.retry-delay:2s}",
      initialDelayString = "${rwms.worker.media-inbox.retry-initial-delay:2s}")
  public void reconcile() {
    processor.reconcilePending();
  }
}
