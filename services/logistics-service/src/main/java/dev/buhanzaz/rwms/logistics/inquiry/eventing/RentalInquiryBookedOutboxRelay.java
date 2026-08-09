package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsTransportTopics;
import java.nio.charset.StandardCharsets;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.util.MimeTypeUtils;

/**
 * Relays committed rental-inquiry booking notifications from durable local outbox state after the
 * write transaction.
 */
@Component
@RequiredArgsConstructor
public class RentalInquiryBookedOutboxRelay {
  private final RentalInquiryBookedOutboxStore outbox;
  private final StreamBridge streamBridge;

  @Value("${rwms.logistics.rental-inquiry.outbox-enabled:true}")
  private boolean enabled;

  /**
   * Publishes each currently due committed row to the canonical rental-inquiry topic and advances
   * it only after the synchronous transport reports success.
   */
  @Scheduled(
      fixedDelayString = "${rwms.logistics.rental-inquiry.outbox-delay:1s}",
      initialDelayString = "${rwms.logistics.rental-inquiry.outbox-initial-delay:1s}")
  @Transactional
  public void relay() {
    if (!enabled) {
      return;
    }
    List<RentalInquiryBookedOutboxStore.PendingEvent> rows = outbox.claimDueForRelay();
    for (RentalInquiryBookedOutboxStore.PendingEvent row : rows) {
      boolean sent =
          streamBridge.send(
              LogisticsTransportTopics.RENTAL_INQUIRY,
              MessageBuilder.withPayload(row.payload().getBytes(StandardCharsets.UTF_8))
                  .setHeader(
                      KafkaHeaders.KEY,
                      row.conversationId().toString().getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (sent) {
        outbox.markPublished(row.eventId());
      } else {
        outbox.scheduleRetry(row.eventId());
      }
    }
  }
}
