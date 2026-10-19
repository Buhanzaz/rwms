package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Scheduled relay that publishes verified asset outbox envelopes and records delivery outcomes.
 */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AssetKafkaOutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(AssetKafkaOutboxRelay.class);
  private final AssetKafkaOutboxStore store;
  private final AssetOutboxProperties properties;
  private final RwmsKafkaOutboundEventPublisher publisher;
  private final AssetSanitizedDltPublisher deadLetters;

  public AssetKafkaOutboxRelay(
      AssetKafkaOutboxStore store,
      AssetOutboxProperties properties,
      RwmsKafkaOutboundEventPublisher publisher,
      AssetSanitizedDltPublisher deadLetters) {
    this.store = store;
    this.properties = properties;
    this.publisher = publisher;
    this.deadLetters = deadLetters;
  }

  @Scheduled(fixedDelayString = "${rwms.asset.eventing.outbox.relay-delay:1s}", initialDelayString = "${rwms.asset.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() { relayOne(); }

  public boolean relayOne() {
    var optional = store.claim(properties.instanceId(), properties.leaseDuration());
    if (optional.isEmpty()) return false;
    AssetKafkaOutboxStore.Claim claim = optional.get();
    byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
    if (!AssetChecksum.sha256(body).equals(claim.envelopeSha256())) {
      store.quarantine(claim, "CHECKSUM_MISMATCH");
      deadLetters.publishHash(claim.envelopeSha256(), "VALIDATION_REJECTED");
      return false;
    }
    try {
      AssetAggregateType.requireTopic(claim.topic());
      publisher.publishSerializedV2(claim.topic(), body);
      return store.published(claim.eventId(), claim.leaseToken());
    } catch (IllegalArgumentException exception) {
      store.validationFailure(claim);
      deadLetters.publish(body, "VALIDATION_REJECTED");
      return false;
    } catch (RuntimeException exception) {
      log.warn("Asset Kafka outbox publish failed safely [failureType={}]", exception.getClass().getName());
      store.transientFailure(claim);
      if (claim.attemptCount() + 1 >= 4) deadLetters.publish(body, "PROCESSING_FAILED");
      return false;
    }
  }
}
