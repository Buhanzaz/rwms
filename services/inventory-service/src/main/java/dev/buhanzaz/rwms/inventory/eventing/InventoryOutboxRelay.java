package dev.buhanzaz.rwms.inventory.eventing;

import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class InventoryOutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(InventoryOutboxRelay.class);
  private final InventoryOutboxStore store;
  private final InventoryOutboxProperties properties;
  private final InventoryDeadLetterStore deadLetters;
  private final StreamBridge bridge;

  public InventoryOutboxRelay(
      InventoryOutboxStore store,
      InventoryOutboxProperties properties,
      InventoryDeadLetterStore deadLetters,
      StreamBridge bridge) {
    this.store = store;
    this.properties = properties;
    this.deadLetters = deadLetters;
    this.bridge = bridge;
  }

  @Scheduled(
      fixedDelayString = "${rwms.inventory.eventing.outbox.relay-delay:1s}",
      initialDelayString = "${rwms.inventory.eventing.outbox.relay-delay:1s}")
  public void scheduledRelay() {
    relayOne();
  }

  public boolean relayOne() {
    var optional = store.claim(properties.instanceId(), properties.leaseDuration());
    if (optional.isEmpty()) return false;
    InventoryOutboxStore.Claim claim = optional.get();
    if (!InventoryEventChecksum.sha256(claim.envelopeBody()).equals(claim.envelopeSha256())
        || !approvedTopic(claim.topic())) {
      store.validationFailure(claim, "ENVELOPE_REJECTED");
      deadLetters.record(
          "VALIDATION_REJECTED", claim.envelopeSha256(), claim.topic(), claim.eventId());
      return false;
    }
    try {
      byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
      boolean acknowledged =
          bridge.send(
              claim.topic(),
              MessageBuilder.withPayload(body)
                  .setHeader(KafkaHeaders.KEY, claim.aggregateId().getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (!acknowledged) throw new IllegalStateException("Kafka acknowledgement is missing");
      return store.published(claim);
    } catch (RuntimeException exception) {
      log.warn(
          "Inventory outbox publish failed safely [failureType={}]",
          exception.getClass().getName());
      store.transientFailure(claim);
      return false;
    }
  }

  private static boolean approvedTopic(String topic) {
    return "rwms.inventory.session.v1".equals(topic)
        || "rwms.inventory.publication.v1".equals(topic);
  }
}
