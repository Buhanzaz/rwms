package dev.buhanzaz.rwms.analytics.eventing;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.analytics.service.AnalyticsInboxProcessor;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.support.MessageBuilder;

class AnalyticsKpiDayConsumerConfigurationTest {
  @Test
  void invalidRecordIsSanitizedAndNeverReachesProjection() {
    AnalyticsEnvelopeValidator validator = mock(AnalyticsEnvelopeValidator.class);
    AnalyticsInboxProcessor inbox = mock(AnalyticsInboxProcessor.class);
    AnalyticsDeadLetterService deadLetters = mock(AnalyticsDeadLetterService.class);
    AnalyticsRetryDelayer delayer = ignored -> {};
    when(validator.validate(any(), anyInt(), anyLong(), any(), any()))
        .thenThrow(new AnalyticsValidationException("SOURCE_SCHEMA_REJECTED"));
    var message =
        MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8))
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, AnalyticsTopics.INPUT)
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 0)
            .setHeader(KafkaHeaders.OFFSET, 1L)
            .setHeader(KafkaHeaders.RECEIVED_KEY, UUID.randomUUID().toString())
            .build();

    new AnalyticsKpiDayConsumerConfiguration()
        .consume(message, validator, inbox, deadLetters, delayer);

    verify(deadLetters)
        .validationFailure(
            eq(AnalyticsTopics.INPUT), eq(0), eq(1L), any(), any(), eq("SOURCE_SCHEMA_REJECTED"));
    verify(inbox, never()).process(any());
  }

  @Test
  void boundedProcessingRetriesEndInDurableInboxDlt() {
    UUID aggregateId = UUID.randomUUID();
    AnalyticsValidatedEvent event = mock(AnalyticsValidatedEvent.class);
    AnalyticsEnvelopeValidator validator = mock(AnalyticsEnvelopeValidator.class);
    AnalyticsInboxProcessor inbox = mock(AnalyticsInboxProcessor.class);
    AnalyticsDeadLetterService deadLetters = mock(AnalyticsDeadLetterService.class);
    AnalyticsRetryDelayer delayer = mock(AnalyticsRetryDelayer.class);
    when(validator.validate(any(), anyInt(), anyLong(), any(), any())).thenReturn(event);
    when(inbox.process(event)).thenThrow(new IllegalStateException("database unavailable"));
    var message =
        MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8))
            .setHeader(KafkaHeaders.RECEIVED_TOPIC, AnalyticsTopics.INPUT)
            .setHeader(KafkaHeaders.RECEIVED_PARTITION, 0)
            .setHeader(KafkaHeaders.OFFSET, 1L)
            .setHeader(KafkaHeaders.RECEIVED_KEY, aggregateId.toString())
            .build();

    new AnalyticsKpiDayConsumerConfiguration()
        .consume(message, validator, inbox, deadLetters, delayer);

    verify(inbox).deadLetterAfterRetries(event, aggregateId.toString());
    verify(delayer).delay(1_000);
    verify(delayer).delay(2_000);
    verify(delayer).delay(4_000);
  }
}
