package dev.buhanzaz.rwms.assistant.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Verifies bounded retry, interruption and sanitized terminal behavior of the booking consumer. */
class RentalInquiryBookedKafkaConsumerTest {
  @Test
  void usesFourLifetimeAttemptsWithOneTwoFourSecondDelaysThenRecordsDlt() throws Exception {
    RentalInquiryBookedEventParser parser =
        new RentalInquiryBookedEventParser(new ObjectMapper());
    RentalInquiryArchiveService archive = mock(RentalInquiryArchiveService.class);
    AssistantEventDeadLetterService deadLetters = mock(AssistantEventDeadLetterService.class);
    AssistantRetryDelayer delayer = mock(AssistantRetryDelayer.class);
    ConsumerRecord<String, String> record = validRecord();
    RentalInquiryBookedEventParser.ParsedEvent parsed =
        parser.parse(
            record.topic(),
            record.partition(),
            record.offset(),
            record.key(),
            record.value());
    when(archive.stage(parsed)).thenReturn(RentalInquiryArchiveService.StageOutcome.READY);
    when(archive.claimNextAttempt(parsed.event().eventId()))
        .thenReturn(
            claimed(1),
            claimed(2),
            claimed(3),
            claimed(4));
    when(archive.process(parsed)).thenThrow(new IllegalStateException("transient"));
    RentalInquiryBookedKafkaConsumer consumer =
        new RentalInquiryBookedKafkaConsumer(parser, archive, deadLetters, delayer);

    consumer.consume(record);

    verify(archive, times(4)).process(parsed);
    verify(archive).scheduleRetry(parsed.event().eventId(), 1);
    verify(archive).scheduleRetry(parsed.event().eventId(), 2);
    verify(archive).scheduleRetry(parsed.event().eventId(), 3);
    verify(delayer).delay(Duration.ofSeconds(1));
    verify(delayer).delay(Duration.ofSeconds(2));
    verify(delayer).delay(Duration.ofSeconds(4));
    verify(deadLetters).recordProcessingFailure(parsed);
  }

  @Test
  void malformedRedeliveryRetainsOnlyHashCoordinatesAndSafeFailureCode() throws Exception {
    RentalInquiryBookedEventParser parser =
        new RentalInquiryBookedEventParser(new ObjectMapper());
    RentalInquiryArchiveService archive = mock(RentalInquiryArchiveService.class);
    AssistantEventDeadLetterService deadLetters = mock(AssistantEventDeadLetterService.class);
    AssistantRetryDelayer delayer = mock(AssistantRetryDelayer.class);
    RentalInquiryBookedKafkaConsumer consumer =
        new RentalInquiryBookedKafkaConsumer(parser, archive, deadLetters, delayer);
    ConsumerRecord<String, String> invalid =
        new ConsumerRecord<>(
            RentalInquiryBookedEvent.SOURCE_TOPIC,
            2,
            91,
            UUID.randomUUID().toString(),
            "{\"authorization\":\"Bearer secret.value.token\"}");

    consumer.consume(invalid);
    consumer.consume(invalid);

    String expectedHash = AssistantCanonicalEventHasher.sha256(invalid.value());
    verify(deadLetters, times(2))
        .recordRejected(
            RentalInquiryBookedEvent.SOURCE_TOPIC,
            2,
            91,
            expectedHash,
            "SOURCE_SCHEMA_REJECTED");
    verifyNoInteractions(archive);
    verifyNoInteractions(delayer);
  }

  @Test
  void interruptionAndDltWriteFailurePropagateWithoutAcknowledgingTheRecord() throws Exception {
    RentalInquiryBookedEventParser parser =
        new RentalInquiryBookedEventParser(new ObjectMapper());
    RentalInquiryArchiveService archive = mock(RentalInquiryArchiveService.class);
    AssistantEventDeadLetterService deadLetters = mock(AssistantEventDeadLetterService.class);
    AssistantRetryDelayer delayer = mock(AssistantRetryDelayer.class);
    ConsumerRecord<String, String> record = validRecord();
    RentalInquiryBookedEventParser.ParsedEvent parsed =
        parser.parse(
            record.topic(),
            record.partition(),
            record.offset(),
            record.key(),
            record.value());
    when(archive.stage(parsed)).thenReturn(RentalInquiryArchiveService.StageOutcome.READY);
    when(archive.claimNextAttempt(parsed.event().eventId())).thenReturn(claimed(1));
    when(archive.process(parsed)).thenThrow(new IllegalStateException("transient"));
    doThrow(new InterruptedException("stop"))
        .when(delayer)
        .delay(Duration.ofSeconds(1));
    RentalInquiryBookedKafkaConsumer consumer =
        new RentalInquiryBookedKafkaConsumer(parser, archive, deadLetters, delayer);

    assertThatThrownBy(() -> consumer.consume(record))
        .isInstanceOf(InterruptedException.class);
    assertThat(Thread.currentThread().isInterrupted()).isTrue();
    Thread.interrupted();
    verify(deadLetters, never()).recordProcessingFailure(parsed);

    ConsumerRecord<String, String> malformed =
        new ConsumerRecord<>(
            RentalInquiryBookedEvent.SOURCE_TOPIC, 0, 101, "bad-key", "not-json");
    doThrow(new IllegalStateException("dlt unavailable"))
        .when(deadLetters)
        .recordRejected(
            RentalInquiryBookedEvent.SOURCE_TOPIC,
            0,
            101,
            AssistantCanonicalEventHasher.sha256("not-json"),
            "SOURCE_SCHEMA_REJECTED");
    assertThatThrownBy(() -> consumer.consume(malformed))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("dlt unavailable");
  }

  private static RentalInquiryArchiveService.AttemptDecision claimed(int number) {
    return new RentalInquiryArchiveService.AttemptDecision(
        RentalInquiryArchiveService.AttemptStatus.CLAIMED, number, Duration.ZERO);
  }

  private static ConsumerRecord<String, String> validRecord() {
    UUID eventId = UUID.fromString("10000000-0000-0000-0000-000000000001");
    UUID inquiryId = UUID.fromString("20000000-0000-0000-0000-000000000002");
    UUID conversationId = UUID.fromString("30000000-0000-0000-0000-000000000003");
    UUID orderId = UUID.fromString("40000000-0000-0000-0000-000000000004");
    String envelope =
        """
        {"envelopeVersion":2,"eventId":"%s",
         "eventType":"logistics.rental-inquiry.booked.v1","eventVersion":1,
         "occurredAt":"2026-08-09T10:00:00Z","recordedAt":"2026-08-09T10:00:01Z",
         "producer":"logistics-service","aggregateType":"RENTAL_INQUIRY",
         "aggregateId":"%s","aggregateVersion":5,
         "correlation":{"correlationId":"%s","causationId":null},"actorRef":null,
         "payload":{"conversationId":"%s","orderId":"%s"}}
        """
            .formatted(
                eventId,
                inquiryId,
                conversationId,
                conversationId,
                orderId);
    return new ConsumerRecord<>(
        RentalInquiryBookedEvent.SOURCE_TOPIC,
        1,
        17,
        conversationId.toString(),
        envelope);
  }
}
