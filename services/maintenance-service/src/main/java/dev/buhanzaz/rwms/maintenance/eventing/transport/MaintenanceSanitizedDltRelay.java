package dev.buhanzaz.rwms.maintenance.eventing.transport;

import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
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
public class MaintenanceSanitizedDltRelay {
  private final MaintenanceSanitizedDltStore store;
  private final MaintenanceOutboxProperties properties;
  private final StreamBridge bridge;

  public MaintenanceSanitizedDltRelay(
      MaintenanceSanitizedDltStore store,
      MaintenanceOutboxProperties properties,
      StreamBridge bridge) {
    this.store = store;
    this.properties = properties;
    this.bridge = bridge;
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
    MaintenanceSanitizedDltStore.Claim claim = optional.get();
    byte[] body = claim.safeBody().getBytes(StandardCharsets.UTF_8);
    if (!MaintenanceChecksum.sha256(body).equals(claim.bodySha256())) {
      store.transientFailure(claim);
      return false;
    }
    try {
      boolean acknowledged =
          bridge.send(
              claim.destination(),
              MessageBuilder.withPayload(body)
                  .setHeader(
                      KafkaHeaders.KEY,
                      claim.messageSha256().getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (!acknowledged) {
        throw new IllegalStateException("Maintenance DLT broker acknowledgement is missing");
      }
      return store.markPublished(claim);
    } catch (RuntimeException exception) {
      store.transientFailure(claim);
      return false;
    }
  }
}
