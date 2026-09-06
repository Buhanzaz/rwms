package dev.buhanzaz.rwms.inventory.eventing;

import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/**
 * inventory boundary for sanitized terminal event-processing failures.
 */
@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class InventoryDeadLetterRelay {
  private final InventoryDeadLetterRelayStore store;
  private final InventoryOutboxProperties properties;
  private final StreamBridge bridge;

  public InventoryDeadLetterRelay(
      InventoryDeadLetterRelayStore store,
      InventoryOutboxProperties properties,
      StreamBridge bridge) {
    this.store = store;
    this.properties = properties;
    this.bridge = bridge;
  }

  @Scheduled(fixedDelayString = "${rwms.inventory.eventing.outbox.relay-delay:1s}")
  public void relayOne() {
    store
        .claim(properties.instanceId(), properties.leaseDuration(), properties.maxAttempts())
        .ifPresent(
            claim -> {
              try {
                if (!InventoryEventChecksum.sha256(claim.body()).equals(claim.hash())) {
                  store.validationFailure(claim);
                  return;
                }
                boolean acknowledged =
                    bridge.send(
                        "rwms.inventory.dlt.v1",
                        MessageBuilder.withPayload(claim.body().getBytes(StandardCharsets.UTF_8))
                            .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                            .build());
                if (!acknowledged)
                  throw new IllegalStateException("DLT acknowledgement is missing");
                store.published(claim);
              } catch (RuntimeException exception) {
                store.transientFailure(claim, properties.maxAttempts());
              }
            });
  }
}
