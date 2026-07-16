package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.nio.charset.StandardCharsets;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

@Component
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AssetSanitizedDltRelay {
  private final AssetSanitizedDltStore store;
  private final AssetOutboxProperties properties;
  private final StreamBridge bridge;

  public AssetSanitizedDltRelay(AssetSanitizedDltStore store, AssetOutboxProperties properties, StreamBridge bridge) {
    this.store = store;
    this.properties = properties;
    this.bridge = bridge;
  }

  @Scheduled(fixedDelayString = "${rwms.asset.eventing.outbox.relay-delay:1s}", initialDelayString = "${rwms.asset.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() { relayOne(); }

  public boolean relayOne() {
    var claim = store.claim(properties.instanceId(), properties.leaseDuration());
    if (claim.isEmpty()) return false;
    byte[] body = claim.get().safeBody().getBytes(StandardCharsets.UTF_8);
    if (!AssetChecksum.sha256(body).equals(claim.get().bodySha256())) {
      store.failed(claim.get());
      return false;
    }
    try {
      boolean acknowledged = bridge.send(claim.get().destination(), MessageBuilder.withPayload(body)
          .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON).build());
      if (!acknowledged) throw new IllegalStateException("Asset sanitized DLT broker acknowledgement missing");
      return store.published(claim.get());
    } catch (RuntimeException exception) {
      store.failed(claim.get());
      return false;
    }
  }
}
