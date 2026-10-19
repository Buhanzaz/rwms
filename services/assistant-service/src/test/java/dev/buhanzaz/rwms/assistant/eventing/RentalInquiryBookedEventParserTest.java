package dev.buhanzaz.rwms.assistant.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Focused strict-shape and canonical-hash tests for the assistant booking envelope validator. */
class RentalInquiryBookedEventParserTest {
  private static final UUID EVENT_ID =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID INQUIRY_ID =
      UUID.fromString("20000000-0000-0000-0000-000000000002");
  private static final UUID CONVERSATION_ID =
      UUID.fromString("30000000-0000-0000-0000-000000000003");
  private static final UUID ORDER_ID =
      UUID.fromString("40000000-0000-0000-0000-000000000004");

  private final RentalInquiryBookedEventParser parser =
      new RentalInquiryBookedEventParser(new ObjectMapper());

  @Test
  void acceptsExactV2EnvelopeAndCanonicalizesObjectOrder() {
    var first = parse(envelope());
    String reordered =
        """
        {
          "payload":{"orderId":"%s","conversationId":"%s"},
          "actorRef":null,
          "correlation":{"causationId":null,"correlationId":"%s"},
          "aggregateVersion":7,
          "aggregateId":"%s",
          "aggregateType":"RENTAL_INQUIRY",
          "producer":"logistics-service",
          "recordedAt":"2026-08-09T10:00:01Z",
          "occurredAt":"2026-08-09T10:00:00Z",
          "eventVersion":1,
          "eventType":"logistics.rental-inquiry.booked.v1",
          "eventId":"%s",
          "envelopeVersion":2
        }
        """
            .formatted(
                ORDER_ID,
                CONVERSATION_ID,
                CONVERSATION_ID,
                INQUIRY_ID,
                EVENT_ID);
    var second = parse(reordered);

    assertThat(first.event().aggregateId()).isEqualTo(INQUIRY_ID);
    assertThat(first.event().conversationId()).isEqualTo(CONVERSATION_ID);
    assertThat(first.event().aggregateVersion()).isEqualTo(7);
    assertThat(first.canonicalSha256()).isEqualTo(second.canonicalSha256());
    assertThat(first.sourceTopic()).isEqualTo(RentalInquiryBookedEvent.SOURCE_TOPIC);
    assertThat(first.sourcePartition()).isEqualTo(3);
    assertThat(first.sourceOffset()).isEqualTo(19);
  }

  @Test
  void rejectsDuplicateFieldsAndUnknownNestedShape() {
    String duplicate =
        envelope()
            .replace(
                "\"orderId\":\"" + ORDER_ID + "\"",
                "\"orderId\":\"" + ORDER_ID + "\",\"orderId\":\"" + ORDER_ID + "\"");
    String unknown =
        envelope().replace("\"orderId\":", "\"unsupported\":null,\"orderId\":");

    assertRejected(duplicate, "SOURCE_SCHEMA_REJECTED");
    assertRejected(unknown, "SOURCE_SCHEMA_REJECTED");
  }

  @Test
  void rejectsChangedKeyCorrelationInvalidAggregateAndProducer() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    RentalInquiryBookedEvent.SOURCE_TOPIC,
                    3,
                    19,
                    UUID.randomUUID().toString(),
                    envelope()))
        .isInstanceOfSatisfying(
            AssistantEventValidationException.class,
            failure -> assertThat(failure.code()).isEqualTo("SOURCE_RECORD_KEY_MISMATCH"));
    assertRejected(
        envelope().replace(
            "\"correlationId\":\"" + CONVERSATION_ID + "\"",
            "\"correlationId\":\"" + UUID.randomUUID() + "\""),
        "SOURCE_SCHEMA_REJECTED");
    assertRejected(
        envelope().replace(
            "\"aggregateId\":\"" + INQUIRY_ID + "\"",
            "\"aggregateId\":\"not-a-uuid\""),
        "SOURCE_SCHEMA_REJECTED");
    assertRejected(
        envelope().replace("\"producer\":\"logistics-service\"", "\"producer\":\"asset-service\""),
        "SOURCE_SCHEMA_REJECTED");
  }

  @Test
  void acceptsOnlyTheCommonV2ActorProfileRevisionShape() {
    String revision = "50000000-0000-0000-0000-000000000005";
    String withUuidRevision =
        envelope()
            .replace(
                "\"actorRef\":null",
                "\"actorRef\":{\"subjectId\":\"60000000-0000-0000-0000-000000000006\","
                    + "\"principalType\":\"USER\",\"profileRevision\":\""
                    + revision
                    + "\"}");

    assertThat(parse(withUuidRevision).event().aggregateId()).isEqualTo(INQUIRY_ID);
    parse(withUuidRevision.replace(revision, "a".repeat(64)));
    assertRejected(
        withUuidRevision.replace(revision, "profile-revision-5"),
        "SOURCE_SCHEMA_REJECTED");
  }

  @Test
  void rejectsMissingOrWrongSourceCoordinatesWithoutPersistableValues() {
    assertThatThrownBy(
            () ->
                parser.parse(
                    "rwms.logistics.rental-inquiry.events.v2",
                    3,
                    19,
                    CONVERSATION_ID.toString(),
                    envelope()))
        .isInstanceOfSatisfying(
            AssistantEventValidationException.class,
            failure -> assertThat(failure.code()).isEqualTo("SOURCE_RECORD_INVALID"));
    assertThat(RentalInquiryBookedEventParser.hasDurableSource("unsafe topic", 0, 0))
        .isFalse();
    assertThat(RentalInquiryBookedEventParser.hasDurableSource(
            RentalInquiryBookedEvent.SOURCE_TOPIC, -1, 0))
        .isFalse();
  }

  private RentalInquiryBookedEventParser.ParsedEvent parse(String raw) {
    return parser.parse(
        RentalInquiryBookedEvent.SOURCE_TOPIC,
        3,
        19,
        CONVERSATION_ID.toString(),
        raw);
  }

  private void assertRejected(String raw, String code) {
    assertThatThrownBy(() -> parse(raw))
        .isInstanceOfSatisfying(
            AssistantEventValidationException.class,
            failure -> assertThat(failure.code()).isEqualTo(code));
  }

  private static String envelope() {
    return
        """
        {
          "envelopeVersion":2,
          "eventId":"%s",
          "eventType":"logistics.rental-inquiry.booked.v1",
          "eventVersion":1,
          "occurredAt":"2026-08-09T10:00:00Z",
          "recordedAt":"2026-08-09T10:00:01Z",
          "producer":"logistics-service",
          "aggregateType":"RENTAL_INQUIRY",
          "aggregateId":"%s",
          "aggregateVersion":7,
          "correlation":{"correlationId":"%s","causationId":null},
          "actorRef":null,
          "payload":{"conversationId":"%s","orderId":"%s"}
        }
        """
            .formatted(
                EVENT_ID,
                INQUIRY_ID,
                CONVERSATION_ID,
                CONVERSATION_ID,
                ORDER_ID);
  }
}
