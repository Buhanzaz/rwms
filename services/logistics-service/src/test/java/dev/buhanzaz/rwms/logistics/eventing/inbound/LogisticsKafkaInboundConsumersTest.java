package dev.buhanzaz.rwms.logistics.eventing.inbound;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.ObjectMapper;

class LogisticsKafkaInboundConsumersTest {
  @Test
  void versionGapIsQuarantinedAndEmitsOnlyAHashOnlyDltSummary() throws Exception {
    LogisticsInboundEnvelopeValidator validator = mock(LogisticsInboundEnvelopeValidator.class);
    LogisticsInboundStagingStore staging = mock(LogisticsInboundStagingStore.class);
    LogisticsInboxProcessor inbox = mock(LogisticsInboxProcessor.class);
    LogisticsInboundDltPublisher deadLetters = mock(LogisticsInboundDltPublisher.class);
    LogisticsRetryDelayer delayer = mock(LogisticsRetryDelayer.class);
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event = event();
    Message<byte[]> message = message();
    when(validator.validate(eq(LogisticsInboundTransportTopics.RENTAL_ITEM), any(), any()))
        .thenReturn(event);
    when(inbox.process(event)).thenReturn(LogisticsInboxProcessor.Outcome.VERSION_GAP);

    new LogisticsKafkaInboundConsumers().consume(
        message, validator, staging, inbox, deadLetters, delayer);

    verify(staging).stage(event);
    verify(staging).markDlt(event.eventId());
    verify(deadLetters)
        .publishHash(
            event.rawMessageSha256(), "VERSION_GAP", event.sourceTopic(), event.eventId());
  }

  @Test
  void retriesTransientProcessingThreeTimesBeforePersistingDlt() throws Exception {
    LogisticsInboundEnvelopeValidator validator = mock(LogisticsInboundEnvelopeValidator.class);
    LogisticsInboundStagingStore staging = mock(LogisticsInboundStagingStore.class);
    LogisticsInboxProcessor inbox = mock(LogisticsInboxProcessor.class);
    LogisticsInboundDltPublisher deadLetters = mock(LogisticsInboundDltPublisher.class);
    LogisticsRetryDelayer delayer = mock(LogisticsRetryDelayer.class);
    LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event = event();
    Message<byte[]> message = message();
    when(validator.validate(eq(LogisticsInboundTransportTopics.RENTAL_ITEM), any(), any()))
        .thenReturn(event);
    when(inbox.process(event)).thenThrow(new IllegalStateException("temporary"));

    new LogisticsKafkaInboundConsumers().consume(
        message, validator, staging, inbox, deadLetters, delayer);

    verify(inbox, times(4)).process(event);
    verify(inbox).recordTransientFailure(event, 1);
    verify(inbox).recordTransientFailure(event, 2);
    verify(inbox).recordTransientFailure(event, 3);
    verify(delayer).delay(1_000);
    verify(delayer).delay(2_000);
    verify(delayer).delay(4_000);
    verify(staging).markDlt(event.eventId());
    verify(inbox).markDeadLetter(event.eventId(), "PROCESSING_FAILED");
    verify(deadLetters)
        .publishHash(
            event.rawMessageSha256(), "PROCESSING_FAILED", event.sourceTopic(), event.eventId());
  }

  private static Message<byte[]> message() {
    return MessageBuilder.withPayload("{}".getBytes(StandardCharsets.UTF_8))
        .setHeader(KafkaHeaders.RECEIVED_TOPIC, LogisticsInboundTransportTopics.RENTAL_ITEM)
        .setHeader(KafkaHeaders.RECEIVED_KEY, UUID.randomUUID().toString())
        .build();
  }

  private static LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event() throws Exception {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    return new LogisticsInboundEnvelopeValidator.ValidatedInboundEvent(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        eventId,
        "asset.rental-item.created.v1",
        "RENTAL_ITEM",
        aggregateId.toString(),
        0,
        OffsetDateTime.parse("2026-07-17T08:00:00Z"),
        "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
        "{}",
        new ObjectMapper().readTree("{}"));
  }
}
