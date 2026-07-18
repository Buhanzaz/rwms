package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.dossier.service.DossierInboxProcessor;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

/** Multiplexed earliest consumer with one attempt plus bounded 1s/2s/4s retries. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class DossierSourceConsumerConfiguration {
  private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

  @Bean
  CommonErrorHandler dossierFailClosedConsumerErrorHandler() {
    return new CommonContainerStoppingErrorHandler();
  }

  @Bean
  Consumer<Message<byte[]>> dossierSourceConsumer(
      DossierEnvelopeValidator validator,
      DossierInboxProcessor inbox,
      DossierDeadLetterService deadLetters,
      DossierRetryDelayer delayer) {
    return message -> consume(message, validator, inbox, deadLetters, delayer);
  }

  void consume(
      Message<byte[]> message,
      DossierEnvelopeValidator validator,
      DossierInboxProcessor inbox,
      DossierDeadLetterService deadLetters,
      DossierRetryDelayer delayer) {
    byte[] raw = message.getPayload() == null ? new byte[0] : message.getPayload();
    Object topicValue = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
    String topic = topicValue instanceof String value ? value : null;
    int partition = intNumber(message.getHeaders().get(KafkaHeaders.RECEIVED_PARTITION), -1);
    long offset = nonNegativeLong(message.getHeaders().get(KafkaHeaders.OFFSET), -1L);
    Object key = message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
    if (!DossierSourceTopics.inputs().contains(topic) || partition < 0 || offset < 0) {
      throw new IllegalStateException("DOSSIER_SOURCE_COORDINATES_MISSING");
    }

    DossierValidatedEvent event;
    try {
      event = validator.validate(topic, partition, offset, key, raw);
    } catch (DossierValidationException exception) {
      deadLetters.validationFailure(
          topic, partition, offset, key, raw, exception.getMessage());
      return;
    }

    for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
      if (attempt > 0) delayer.delay(BACKOFF_MILLIS[attempt]);
      try {
        inbox.process(event);
        return;
      } catch (IllegalStateException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          inbox.deadLetterAfterRetries(event, key);
          return;
        }
      } catch (RuntimeException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          inbox.deadLetterAfterRetries(event, key);
          return;
        }
      }
    }
  }

  private static int intNumber(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static long nonNegativeLong(Object value, long fallback) {
    if (!(value instanceof Number number)) return fallback;
    long parsed = number.longValue();
    return parsed >= 0 ? parsed : fallback;
  }
}
