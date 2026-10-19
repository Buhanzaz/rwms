package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Claims one ordered task-board outbox row, publishes with broker acknowledgement, and finalizes its lease. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class TaskBoardKafkaOutboxRelay {
  private final TaskBoardKafkaOutboxStore store;
  private final TaskBoardOutboxProperties properties;
  private final RwmsKafkaOutboundEventPublisher publisher;
  private final TaskBoardEventingMetrics metrics;

  @Scheduled(
      fixedDelayString = "${rwms.task-board.eventing.outbox.relay-delay:1s}",
      initialDelayString = "${rwms.task-board.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() { relayOne(); }

  public boolean relayOne() {
    metrics.outboxBacklog(store.backlog());
    var claimed = store.claim(properties.instanceId(), properties.leaseDuration());
    if (claimed.isEmpty()) return false;
    var claim = claimed.get();
    byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
    if (!TaskBoardEventStore.sha256(body).equals(claim.envelopeSha256())) {
      store.quarantine(claim, "CHECKSUM_MISMATCH");
      metrics.outboxPublishFailed();
      return false;
    }
    if (!store.hasAuthoritativeEnvelope(claim)) {
      store.quarantine(claim, "AUTHORITATIVE_EVENT_MISMATCH");
      metrics.outboxPublishFailed();
      return false;
    }
    if (!store.hasValidPredecessor(claim)) {
      store.quarantine(claim, "AGGREGATE_VERSION_GAP");
      metrics.outboxPublishFailed();
      return false;
    }
    try {
      TaskBoardAggregateType.requireTopic(claim.topic());
      publisher.publishSerializedV2(claim.topic(), body);
      boolean published = store.published(claim.eventId(), claim.leaseToken());
      if (published) metrics.outboxPublished(); else metrics.outboxPublishFailed();
      return published;
    } catch (IllegalArgumentException exception) {
      store.validationFailure(claim);
      metrics.outboxPublishFailed();
      return false;
    } catch (RuntimeException exception) {
      log.warn("Task-board Kafka outbox publish failed safely [failureType={}]",
          exception.getClass().getName());
      store.transientFailure(claim);
      metrics.outboxRetried();
      metrics.outboxPublishFailed();
      return false;
    }
  }
}
