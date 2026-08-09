package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises rental-inquiry outbox claiming and acknowledgement against PostgreSQL through the
 * store, while the relay retains its original outer transaction.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalInquiryBookedOutboxStoreIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired RentalInquiryBookedOutboxStore store;
  @Autowired RentalInquiryBookedOutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  @Autowired ObjectMapper json;

  @MockitoBean StreamBridge streamBridge;
  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void resetDatabaseAndBroker() {
    jdbc.execute("truncate table rental_inquiry_outbox");
    reset(streamBridge, dependencies);
  }

  @Test
  void brokerAcknowledgementMarksTheClaimedRowPublished() {
    UUID orderId = appendPendingEvent();
    when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

    relay.relay();

    UUID eventId = eventId(orderId);
    assertThat(jdbc.queryForMap("select status,attempt_count,published_at from rental_inquiry_outbox where event_id=?", eventId))
        .containsEntry("status", "PUBLISHED")
        .containsEntry("attempt_count", 0)
        .doesNotContainEntry("published_at", null);
    verify(streamBridge).send(anyString(), any(Message.class));
  }

  @Test
  void failedBrokerSendRetainsTheRowAndAdvancesItsBoundedRetryState() {
    UUID orderId = appendPendingEvent();
    OffsetDateTime createdAt =
        jdbc.queryForObject(
            "select created_at from rental_inquiry_outbox where order_id=?",
            OffsetDateTime.class,
            orderId);
    when(streamBridge.send(anyString(), any(Message.class))).thenReturn(false);

    relay.relay();

    assertThat(
            jdbc.queryForMap(
                "select status,attempt_count,published_at from rental_inquiry_outbox where order_id=?",
                orderId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 1)
        .containsEntry("published_at", null);
    assertThat(
            jdbc.queryForObject(
                "select next_attempt_at from rental_inquiry_outbox where order_id=?",
                OffsetDateTime.class,
                orderId))
        .isAfter(createdAt);
  }

  @Test
  void appendedPayloadIsTheExactCanonicalBookedEnvelope() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID managerSubjectId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-09T12:00:00Z");
    transactions.executeWithoutResult(
        ignored ->
            store.append(
                inquiryId,
                17,
                conversationId,
                bookingId,
                orderId,
                managerSubjectId,
                occurredAt));

    UUID eventId = eventId(orderId);
    JsonNode envelope =
        json.readTree(
            jdbc.queryForObject(
                "select payload::text from rental_inquiry_outbox where event_id=?",
                String.class,
                eventId));
    JsonNode schema =
        json.readTree(
            Files.readString(
                Path.of(
                    System.getProperty("rwms.contracts.dir"),
                    "events/logistics/rental-inquiry-events-v1.schema.json")));
    Set<String> schemaRootFields = new HashSet<>();
    schema.path("required").forEach(value -> schemaRootFields.add(value.stringValue()));
    assertThat(new HashSet<>(envelope.propertyNames())).isEqualTo(schemaRootFields);
    assertThat(envelope.path("envelopeVersion"))
        .isEqualTo(schema.at("/properties/envelopeVersion/const"));
    assertThat(envelope.path("eventType"))
        .isEqualTo(schema.at("/properties/eventType/const"));
    assertThat(envelope.path("eventVersion"))
        .isEqualTo(schema.at("/properties/eventVersion/const"));
    assertThat(envelope.path("producer")).isEqualTo(schema.at("/properties/producer/const"));
    assertThat(envelope.path("aggregateType"))
        .isEqualTo(schema.at("/properties/aggregateType/const"));
    assertThat(new HashSet<>(envelope.propertyNames()))
        .containsExactlyInAnyOrder(
            "envelopeVersion",
            "eventId",
            "eventType",
            "eventVersion",
            "occurredAt",
            "recordedAt",
            "producer",
            "aggregateType",
            "aggregateId",
            "aggregateVersion",
            "correlation",
            "actorRef",
            "payload");
    assertThat(envelope.path("envelopeVersion").intValue()).isEqualTo(2);
    assertThat(envelope.path("eventId").stringValue()).isEqualTo(eventId.toString());
    assertThat(envelope.path("eventType").stringValue())
        .isEqualTo("logistics.rental-inquiry.booked.v1");
    assertThat(envelope.path("eventVersion").intValue()).isEqualTo(1);
    assertThat(envelope.path("occurredAt").stringValue()).isEqualTo("2026-08-09T12:00:00Z");
    assertThat(OffsetDateTime.parse(envelope.path("recordedAt").stringValue())).isNotNull();
    assertThat(envelope.path("producer").stringValue()).isEqualTo("logistics-service");
    assertThat(envelope.path("aggregateType").stringValue()).isEqualTo("RENTAL_INQUIRY");
    assertThat(envelope.path("aggregateId").stringValue()).isEqualTo(inquiryId.toString());
    assertThat(envelope.path("aggregateVersion").longValue()).isEqualTo(17);
    assertThat(new HashSet<>(envelope.path("correlation").propertyNames()))
        .isEqualTo(Set.of("correlationId", "causationId"));
    assertThat(envelope.path("correlation").path("correlationId").stringValue())
        .isEqualTo(conversationId.toString());
    assertThat(envelope.path("correlation").path("causationId").stringValue())
        .isEqualTo(bookingId.toString());
    assertThat(new HashSet<>(envelope.path("actorRef").propertyNames()))
        .isEqualTo(Set.of("subjectId", "principalType", "profileRevision"));
    assertThat(envelope.path("actorRef").path("subjectId").stringValue())
        .isEqualTo(managerSubjectId.toString());
    assertThat(envelope.path("actorRef").path("principalType").stringValue())
        .isEqualTo("USER");
    assertThat(envelope.path("actorRef").path("profileRevision").isNull()).isTrue();
    assertThat(new HashSet<>(envelope.path("payload").propertyNames()))
        .isEqualTo(new HashSet<>(schema.at("/$defs/bookedPayload/properties").propertyNames()))
        .isEqualTo(Set.of("conversationId", "orderId"));
    assertThat(envelope.path("payload").path("conversationId").stringValue())
        .isEqualTo(conversationId.toString());
    assertThat(envelope.path("payload").path("orderId").stringValue())
        .isEqualTo(orderId.toString());
  }

  private UUID appendPendingEvent() {
    UUID orderId = UUID.randomUUID();
    transactions.executeWithoutResult(
        ignored ->
            store.append(
                UUID.randomUUID(),
                1,
                UUID.randomUUID(),
                UUID.randomUUID(),
                orderId,
                UUID.randomUUID(),
                OffsetDateTime.now()));
    return orderId;
  }

  private UUID eventId(UUID orderId) {
    return jdbc.queryForObject(
        "select event_id from rental_inquiry_outbox where order_id=?", UUID.class, orderId);
  }
}
