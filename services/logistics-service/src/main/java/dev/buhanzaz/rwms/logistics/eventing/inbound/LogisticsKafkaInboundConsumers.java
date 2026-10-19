package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/** One multiplexed source-fact consumer with bounded local retries and a durable DLT. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class LogisticsKafkaInboundConsumers {
  private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

  @Bean
  CommonErrorHandler logisticsFailClosedConsumerErrorHandler() {
    return new CommonContainerStoppingErrorHandler();
  }

  @Bean
  Consumer<Message<byte[]>> logisticsInbound(
      LogisticsInboundEnvelopeValidator validator,
      LogisticsInboundStagingStore staging,
      LogisticsInboxProcessor inbox,
      LogisticsInboundDltPublisher deadLetters,
      LogisticsRetryDelayer delayer) {
    return message -> consume(message, validator, staging, inbox, deadLetters, delayer);
  }

  void consume(
      Message<byte[]> message,
      LogisticsInboundEnvelopeValidator validator,
      LogisticsInboundStagingStore staging,
      LogisticsInboxProcessor inbox,
      LogisticsInboundDltPublisher deadLetters,
      LogisticsRetryDelayer delayer) {
    byte[] raw = message.getPayload() == null ? new byte[0] : message.getPayload();
    Object topicHeader = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
    String sourceTopic = topicHeader instanceof String value ? value : null;
    if (sourceTopic == null) {
      deadLetters.publish(raw, "VALIDATION_REJECTED", null, null);
      return;
    }

    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event;
    try {
      event = validator.validate(sourceTopic, message.getHeaders().get(KafkaHeaders.RECEIVED_KEY), raw);
    } catch (LogisticsInboundValidationException exception) {
      deadLetters.publish(raw, "VALIDATION_REJECTED", sourceTopic, null);
      return;
    }

    for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
      if (attempt > 0) {
        delayer.delay(BACKOFF_MILLIS[attempt]);
      }
      try {
        staging.stage(event);
        LogisticsInboxProcessor.Outcome outcome = inbox.process(event);
        if (outcome == LogisticsInboxProcessor.Outcome.VERSION_GAP
            || outcome == LogisticsInboxProcessor.Outcome.BLOCKED) {
          staging.markDlt(event.eventId());
          deadLetters.publishHash(
              event.rawMessageSha256(), "VERSION_GAP", event.sourceTopic(), event.eventId());
        }
        return;
      } catch (LogisticsInboundEventIdentityConflictException exception) {
        staging.markDlt(event.eventId());
        inbox.markDeadLetter(event.eventId(), "EVENT_ID_CONFLICT");
        deadLetters.publishHash(
            event.rawMessageSha256(), "EVENT_ID_CONFLICT", event.sourceTopic(), event.eventId());
        return;
      } catch (LogisticsInboundValidationException exception) {
        staging.markDlt(event.eventId());
        inbox.markDeadLetter(event.eventId(), "VALIDATION_REJECTED");
        deadLetters.publishHash(
            event.rawMessageSha256(), "VALIDATION_REJECTED", event.sourceTopic(), event.eventId());
        return;
      } catch (RuntimeException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          staging.markDlt(event.eventId());
          inbox.markDeadLetter(event.eventId(), "PROCESSING_FAILED");
          deadLetters.publishHash(
              event.rawMessageSha256(), "PROCESSING_FAILED", event.sourceTopic(), event.eventId());
          return;
        }
        inbox.recordTransientFailure(event, attempt + 1);
      }
    }
  }
}
