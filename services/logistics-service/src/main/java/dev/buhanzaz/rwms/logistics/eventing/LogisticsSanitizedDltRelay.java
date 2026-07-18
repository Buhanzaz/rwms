package dev.buhanzaz.rwms.logistics.eventing;

import java.nio.charset.StandardCharsets;
import dev.buhanzaz.rwms.logistics.eventing.inbound.LogisticsInboundTransportTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Delivers the sanitized metadata record, never the original failed envelope. */
@Component
@Slf4j
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsSanitizedDltRelay {
  private final LogisticsSanitizedDltStore store;
  private final LogisticsOutboxProperties properties;
  private final StreamBridge streamBridge;

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

    LogisticsSanitizedDltStore.Claim claim = claimed.get();
    byte[] body = claim.safeBody().getBytes(StandardCharsets.UTF_8);
    if (!LogisticsEventStore.sha256(body).equals(claim.bodySha256())) {
      store.failed(claim);
      return false;
    }

    try {
      if (!LogisticsInboundTransportTopics.SANITIZED_DLT.equals(claim.destination())) {
        LogisticsAggregateType.requireSanitizedDltTopic(claim.destination());
      }
      boolean acknowledged =
          streamBridge.send(
              claim.destination(),
              MessageBuilder.withPayload(body)
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (!acknowledged) {
        throw new IllegalStateException("Logistics sanitized DLT broker acknowledgement is missing");
      }
      return store.published(claim);
    } catch (RuntimeException exception) {
      log.warn(
          "Logistics sanitized DLT publish failed safely [failureType={}]",
          exception.getClass().getName());
      store.failed(claim);
      return false;
    }
  }
}
