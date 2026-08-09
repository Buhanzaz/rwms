package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.warehouse.service.WarehouseChecksum;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Leases, validates and publishes warehouse outbox envelopes while preserving aggregate order. */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class WarehouseKafkaOutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(WarehouseKafkaOutboxRelay.class);
  private final WarehouseKafkaOutboxStore store;
  private final WarehouseOutboxProperties properties;
  private final RwmsKafkaOutboundEventPublisher publisher;

  public WarehouseKafkaOutboxRelay(
      WarehouseKafkaOutboxStore store,
      WarehouseOutboxProperties properties,
      RwmsKafkaOutboundEventPublisher publisher) {
    this.store = store;
    this.properties = properties;
    this.publisher = publisher;
  }

  @Scheduled(
      fixedDelayString = "${rwms.warehouse.eventing.outbox.relay-delay:1s}",
      initialDelayString = "${rwms.warehouse.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() {
    relayOne();
  }

  public boolean relayOne() {
    var claimed = store.claim(properties.instanceId(), properties.leaseDuration());
    if (claimed.isEmpty()) return false;
    WarehouseKafkaOutboxStore.Claim claim = claimed.get();
    byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
    if (!WarehouseChecksum.sha256(body).equals(claim.envelopeSha256())) {
      store.quarantine(claim, "CHECKSUM_MISMATCH");
      return false;
    }
    if (!store.hasValidEnvelope(claim)) {
      store.quarantine(claim, "ENVELOPE_MISMATCH");
      return false;
    }
    try {
      WarehouseAggregateType.requireTopic(claim.topic());
      publisher.publishSerializedV2(claim.topic(), body);
      return store.published(claim.eventId(), claim.leaseToken());
    } catch (IllegalArgumentException exception) {
      store.validationFailure(claim);
      return false;
    } catch (RuntimeException exception) {
      log.warn("Warehouse Kafka outbox publish failed safely [failureType={}]", exception.getClass().getName());
      store.transientFailure(claim);
      return false;
    }
  }
}
