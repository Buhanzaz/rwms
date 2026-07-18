package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Publishes one committed outbox fact only after its envelope has been revalidated. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsKafkaOutboxRelay {
  private final LogisticsOutboxStore store;
  private final LogisticsOutboxProperties properties;
  private final RwmsKafkaOutboundEventPublisher publisher;
  private final LogisticsSanitizedDltPublisher deadLetters;

  @Scheduled(
      fixedDelayString = "${rwms.logistics.eventing.outbox.relay-delay:1s}",
      initialDelayString = "${rwms.logistics.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() {
    relayOne();
  }

  public boolean relayOne() {
    var claimed = store.claim(properties.instanceId(), properties.leaseDuration());
    if (claimed.isEmpty()) {
      return false;
    }

    LogisticsOutboxStore.Claim claim = claimed.get();
    byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
    if (!LogisticsEventStore.sha256(body).equals(claim.envelopeSha256())) {
      store.quarantine(claim, "CHECKSUM_MISMATCH");
      deadLetters.validationRejected(claim);
      return false;
    }
    if (!store.hasValidEnvelope(claim)) {
      store.quarantine(claim, "ENVELOPE_MISMATCH");
      deadLetters.validationRejected(claim);
      return false;
    }

    try {
      LogisticsAggregateType.requireTopic(claim.topic());
      publisher.publishSerializedV2(claim.topic(), body);
      return store.published(claim.eventId(), claim.leaseToken());
    } catch (IllegalArgumentException exception) {
      store.validationFailure(claim);
      deadLetters.validationRejected(claim);
      return false;
    } catch (RuntimeException exception) {
      log.warn(
          "Logistics Kafka outbox publish failed safely [failureType={}]",
          exception.getClass().getName());
      store.transientFailure(claim);
      if (claim.attemptCount() + 1 >= 4) {
        deadLetters.processingFailed(claim);
      }
      return false;
    }
  }
}
