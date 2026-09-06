package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsOutboxProperties;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsTransportTopics;
import java.nio.charset.StandardCharsets;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.MessageHeaders;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.util.MimeTypeUtils;

/** Publishes individually leased rental-inquiry booking events with finite durable retries. */
@Component
@EnableConfigurationProperties({
  RentalInquiryOutboxProperties.class,
  LogisticsOutboxProperties.class
})
public class RentalInquiryBookedOutboxRelay {
  private static final Logger log = LoggerFactory.getLogger(RentalInquiryBookedOutboxRelay.class);

  private final RentalInquiryBookedOutboxStore outbox;
  private final StreamBridge streamBridge;
  private final RentalInquiryOutboxProperties properties;
  private final LogisticsOutboxProperties sharedOutboxProperties;

  public RentalInquiryBookedOutboxRelay(
      RentalInquiryBookedOutboxStore outbox,
      StreamBridge streamBridge,
      RentalInquiryOutboxProperties properties,
      LogisticsOutboxProperties sharedOutboxProperties) {
    this.outbox = outbox;
    this.streamBridge = streamBridge;
    this.properties = properties;
    this.sharedOutboxProperties = sharedOutboxProperties;
  }

  @Scheduled(
      fixedDelayString = "${rwms.logistics.rental-inquiry.outbox-delay:1s}",
      initialDelayString = "${rwms.logistics.rental-inquiry.outbox-initial-delay:1s}")
  public void relay() {
    if (!properties.outboxEnabled()) return;
    for (int index = 0; index < properties.maxPerRun(); index++) {
      RentalInquiryBookedOutboxStore.ClaimOutcome outcome =
          outbox.claim(
              sharedOutboxProperties.instanceId(),
              sharedOutboxProperties.leaseDuration(),
              properties.maxAttempts());
      if (!outcome.progressed()) return;
      outcome.claim().ifPresent(this::deliver);
    }
  }

  private void deliver(RentalInquiryBookedOutboxStore.Claim claim) {
    if (!outbox.hasValidEnvelope(claim)) {
      outbox.validationFailed(claim);
      return;
    }
    try {
      boolean sent =
          streamBridge.send(
              LogisticsTransportTopics.RENTAL_INQUIRY,
              MessageBuilder.withPayload(claim.payload().getBytes(StandardCharsets.UTF_8))
                  .setHeader(
                      KafkaHeaders.KEY,
                      claim.conversationId().toString().getBytes(StandardCharsets.UTF_8))
                  .setHeader(MessageHeaders.CONTENT_TYPE, MimeTypeUtils.APPLICATION_JSON)
                  .build());
      if (sent) {
        outbox.markPublished(claim);
      } else {
        outbox.deliveryFailed(claim, properties.maxAttempts(), "BROKER_REJECTED");
      }
    } catch (RuntimeException exception) {
      log.warn(
          "Rental inquiry outbox publish failed safely [failureType={}]",
          exception.getClass().getName());
      outbox.deliveryFailed(claim, properties.maxAttempts(), "PUBLISH_FAILED");
    }
  }
}
