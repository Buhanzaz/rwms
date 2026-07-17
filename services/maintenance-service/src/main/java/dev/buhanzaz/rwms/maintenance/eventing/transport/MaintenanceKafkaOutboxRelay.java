package dev.buhanzaz.rwms.maintenance.eventing.transport;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class MaintenanceKafkaOutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(MaintenanceKafkaOutboxRelay.class);
  private final MaintenanceKafkaOutboxStore store;
  private final MaintenanceOutboxProperties properties;
  private final RwmsKafkaOutboundEventPublisher publisher;
  private final MaintenanceSanitizedDltPublisher deadLetters;

  public MaintenanceKafkaOutboxRelay(
      MaintenanceKafkaOutboxStore store,
      MaintenanceOutboxProperties properties,
      RwmsKafkaOutboundEventPublisher publisher,
      MaintenanceSanitizedDltPublisher deadLetters) {
    this.store = store;
    this.properties = properties;
    this.publisher = publisher;
    this.deadLetters = deadLetters;
  }

  @Scheduled(
      fixedDelayString = "${rwms.maintenance.eventing.outbox.relay-delay:1s}",
      initialDelayString = "${rwms.maintenance.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() {
    relayOne();
  }

  public boolean relayOne() {
    var optional = store.claim(properties.instanceId(), properties.leaseDuration());
    if (optional.isEmpty()) {
      return false;
    }
    MaintenanceKafkaOutboxStore.Claim claim = optional.get();
    byte[] body = claim.envelopeBody().getBytes(StandardCharsets.UTF_8);
    if (!MaintenanceChecksum.sha256(body).equals(claim.envelopeSha256())) {
      store.validationFailure(claim, "CHECKSUM_MISMATCH");
      deadLetters.publishHash(claim.envelopeSha256(), "VALIDATION_REJECTED", null, null);
      return false;
    }
    if (!expectedTopic(claim.aggregateType()).equals(claim.topic())) {
      store.validationFailure(claim, "TOPIC_MISMATCH");
      deadLetters.publishHash(claim.envelopeSha256(), "VALIDATION_REJECTED", null, null);
      return false;
    }
    try {
      // Kafka binder producer sync=true makes this return only after the broker acknowledgement.
      // A crash after this call and before markPublished deliberately produces a harmless resend.
      publisher.publishSerializedV2(claim.topic(), body);
      return store.markPublished(claim.eventId(), claim.leaseToken());
    } catch (IllegalArgumentException exception) {
      store.validationFailure(claim, "ENVELOPE_REJECTED");
      deadLetters.publishHash(claim.envelopeSha256(), "VALIDATION_REJECTED", null, null);
      return false;
    } catch (RuntimeException exception) {
      log.warn(
          "Maintenance Kafka outbox publish failed safely [failureType={}]",
          exception.getClass().getName());
      store.transientFailure(claim);
      if (claim.attemptCount() + 1 >= 4) {
        deadLetters.publishHash(claim.envelopeSha256(), "PROCESSING_FAILED", null, null);
      }
      return false;
    }
  }

  private static String expectedTopic(String aggregateType) {
    return switch (aggregateType) {
      case "CATALOG_VERSION" -> MaintenanceTransportTopics.CATALOG;
      case "ESTIMATE" -> MaintenanceTransportTopics.ESTIMATE;
      case "REPAIR" -> MaintenanceTransportTopics.REPAIR;
      default -> throw new IllegalArgumentException("Unsupported maintenance aggregate type");
    };
  }
}
