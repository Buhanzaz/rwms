package dev.buhanzaz.rwms.taskboard.eventing;

import java.nio.charset.StandardCharsets;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Publishes leased sanitized DLT metadata without exposing the rejected source payload. */
@Component
@RequiredArgsConstructor
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class TaskBoardSanitizedDltRelay {
  private final TaskBoardSanitizedDltStore store;
  private final TaskBoardOutboxProperties properties;
  private final StreamBridge streamBridge;
  private final TaskBoardEventingMetrics metrics;

  @Scheduled(
      fixedDelayString = "${rwms.task-board.eventing.outbox.relay-delay:1s}",
      initialDelayString = "${rwms.task-board.eventing.outbox.relay-initial-delay:1s}")
  public void scheduledRelay() { relayOne(); }

  public boolean relayOne() {
    var claim = store.claim(properties.instanceId(), properties.leaseDuration());
    if (claim.isEmpty()) return false;
    byte[] body = claim.get().safeBody().getBytes(StandardCharsets.UTF_8);
    if (!TaskBoardEventStore.sha256(body).equals(claim.get().bodySha256())) {
      store.failed(claim.get());
      metrics.sanitizedDltFailed();
      return false;
    }
    try {
      boolean ack = streamBridge.send(
          claim.get().destination(),
          MessageBuilder.withPayload(body)
              .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
              .build());
      if (!ack) throw new IllegalStateException("Sanitized DLT broker acknowledgement missing");
      boolean published = store.published(claim.get());
      if (published) metrics.sanitizedDltPublished();
      return published;
    } catch (RuntimeException exception) {
      store.failed(claim.get());
      metrics.sanitizedDltFailed();
      return false;
    }
  }
}
