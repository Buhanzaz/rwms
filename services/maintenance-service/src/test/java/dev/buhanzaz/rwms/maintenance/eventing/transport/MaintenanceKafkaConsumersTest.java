package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.messaging.support.MessageBuilder;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceKafkaConsumersTest {
  private static final byte[] RAW = "{\"safe\":true}".getBytes(StandardCharsets.UTF_8);

  @Test
  void validationFailureGoesDirectlyToSanitizedDltWithoutStaging() {
    var validator = mock(MaintenanceInboundEnvelopeValidator.class);
    var staging = mock(MaintenanceInboundStagingStore.class);
    var inbox = mock(MaintenanceInboxProcessor.class);
    var deadLetters = mock(MaintenanceSanitizedDltPublisher.class);
    var delayer = mock(MaintenanceRetryDelayer.class);
    doThrow(new MaintenanceInboundValidationException("invalid"))
        .when(validator)
        .validate(eq(MaintenanceTransportTopics.MEDIA), any(), eq(RAW));

    new MaintenanceKafkaConsumers()
        .consume(message(), validator, staging, inbox, deadLetters, delayer);

    verify(staging, never()).stage(any());
    verify(inbox, never()).process(any());
    verify(deadLetters)
        .publish(RAW, "VALIDATION_REJECTED", MaintenanceTransportTopics.MEDIA, null);
    verify(delayer, never()).delay(anyLong());
  }

  @Test
  void transientFailureUsesFirstAttemptPlusExactOneTwoFourSecondBackoffs() {
    var validator = mock(MaintenanceInboundEnvelopeValidator.class);
    var staging = mock(MaintenanceInboundStagingStore.class);
    var inbox = mock(MaintenanceInboxProcessor.class);
    var deadLetters = mock(MaintenanceSanitizedDltPublisher.class);
    var delayer = mock(MaintenanceRetryDelayer.class);
    var event = event();
    when(validator.validate(eq(MaintenanceTransportTopics.MEDIA), any(), eq(RAW)))
        .thenReturn(event);
    when(inbox.process(event))
        .thenThrow(new IllegalStateException("db"))
        .thenThrow(new IllegalStateException("db"))
        .thenThrow(new IllegalStateException("db"))
        .thenReturn(MaintenanceInboxProcessor.Outcome.PROCESSED);

    new MaintenanceKafkaConsumers()
        .consume(message(), validator, staging, inbox, deadLetters, delayer);

    verify(inbox, times(4)).process(event);
    verify(staging, times(4)).stage(event);
    verify(delayer).delay(1_000);
    verify(delayer).delay(2_000);
    verify(delayer).delay(4_000);
    verify(deadLetters, never()).publishHash(any(), any(), any(), any());
  }

  @Test
  void exhaustedTransientFailureStagesOneReplayableSanitizedDlt() {
    var validator = mock(MaintenanceInboundEnvelopeValidator.class);
    var staging = mock(MaintenanceInboundStagingStore.class);
    var inbox = mock(MaintenanceInboxProcessor.class);
    var deadLetters = mock(MaintenanceSanitizedDltPublisher.class);
    var delayer = mock(MaintenanceRetryDelayer.class);
    var event = event();
    when(validator.validate(eq(MaintenanceTransportTopics.MEDIA), any(), eq(RAW)))
        .thenReturn(event);
    when(inbox.process(event)).thenThrow(new IllegalStateException("db"));

    new MaintenanceKafkaConsumers()
        .consume(message(), validator, staging, inbox, deadLetters, delayer);

    verify(inbox, times(4)).process(event);
    verify(staging).markDlt(event.eventId());
    verify(deadLetters)
        .publishHash(
            event.rawMessageSha256(),
            "PROCESSING_FAILED",
            event.sourceTopic(),
            event.eventId());
  }

  private static Message<byte[]> message() {
    return MessageBuilder.withPayload(RAW)
        .setHeader(KafkaHeaders.RECEIVED_TOPIC, MaintenanceTransportTopics.MEDIA)
        .setHeader(KafkaHeaders.RECEIVED_KEY, UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8))
        .build();
  }

  private static MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event() {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    return new MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent(
        MaintenanceTransportTopics.MEDIA,
        eventId,
        "media.media.ready.v1",
        "MEDIA",
        aggregateId.toString(),
        0,
        true,
        "a".repeat(64),
        "{\"payload\":{}}",
        JsonMapper.builder().build().createObjectNode());
  }
}
