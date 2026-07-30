package dev.buhanzaz.rwms.analytics.eventing;

import dev.buhanzaz.rwms.analytics.service.AnalyticsInboxProcessor;
import java.util.function.Consumer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.listener.CommonContainerStoppingErrorHandler;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
public class AnalyticsKpiDayConsumerConfiguration {
  private static final long[] BACKOFF_MILLIS = {0, 1_000, 2_000, 4_000};

  @Bean
  CommonErrorHandler analyticsFailClosedConsumerErrorHandler() {
    return new CommonContainerStoppingErrorHandler();
  }

  @Bean
  AnalyticsRetryDelayer analyticsRetryDelayer() {
    return milliseconds -> {
      try {
        Thread.sleep(milliseconds);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("ANALYTICS_RETRY_INTERRUPTED", exception);
      }
    };
  }

  @Bean
  Consumer<Message<byte[]>> analyticsKpiDayConsumer(
      AnalyticsEnvelopeValidator validator,
      AnalyticsInboxProcessor inbox,
      AnalyticsDeadLetterService deadLetters,
      AnalyticsRetryDelayer delayer) {
    return message -> consume(message, validator, inbox, deadLetters, delayer);
  }

  void consume(
      Message<byte[]> message,
      AnalyticsEnvelopeValidator validator,
      AnalyticsInboxProcessor inbox,
      AnalyticsDeadLetterService deadLetters,
      AnalyticsRetryDelayer delayer) {
    byte[] raw = message.getPayload() == null ? new byte[0] : message.getPayload();
    Object topicHeader = message.getHeaders().get(KafkaHeaders.RECEIVED_TOPIC);
    String topic = topicHeader instanceof String value ? value : null;
    int partition = number(message.getHeaders().get(KafkaHeaders.RECEIVED_PARTITION), -1);
    long offset = longNumber(message.getHeaders().get(KafkaHeaders.OFFSET), -1);
    Object key = message.getHeaders().get(KafkaHeaders.RECEIVED_KEY);
    if (!AnalyticsTopics.INPUT.equals(topic) || partition < 0 || offset < 0) {
      throw new IllegalStateException("ANALYTICS_SOURCE_COORDINATES_MISSING");
    }

    AnalyticsValidatedEvent event;
    try {
      event = validator.validate(topic, partition, offset, key, raw);
    } catch (AnalyticsValidationException exception) {
      deadLetters.validationFailure(topic, partition, offset, key, raw, exception.getMessage());
      return;
    }

    for (int attempt = 0; attempt < BACKOFF_MILLIS.length; attempt++) {
      if (attempt > 0) delayer.delay(BACKOFF_MILLIS[attempt]);
      try {
        inbox.process(event);
        return;
      } catch (RuntimeException exception) {
        if (attempt == BACKOFF_MILLIS.length - 1) {
          inbox.deadLetterAfterRetries(event, key);
          return;
        }
      }
    }
  }

  private static int number(Object value, int fallback) {
    return value instanceof Number number ? number.intValue() : fallback;
  }

  private static long longNumber(Object value, long fallback) {
    return value instanceof Number number ? number.longValue() : fallback;
  }
}
