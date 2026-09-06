package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.yaml.snakeyaml.Yaml;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Exercises rental-inquiry outbox claiming and acknowledgement against PostgreSQL through
 * independently committed per-row relay attempts.
 */
@SpringBootTest(
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.outbox-delay=1h",
      "rwms.logistics.rental-inquiry.outbox-initial-delay=1h",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalInquiryBookedOutboxStoreIntegrationTest {
  private static final HttpClient HTTP = HttpClient.newHttpClient();
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired RentalInquiryBookedOutboxStore store;
  @Autowired RentalInquiryBookedOutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  @Autowired ObjectMapper json;
  @LocalServerPort int port;

  @MockitoBean StreamBridge streamBridge;
  @MockitoBean LogisticsDependencyGateway dependencies;
  @MockitoBean JwtDecoder jwtDecoder;

  @BeforeEach
  void resetDatabaseAndBroker() {
    jdbc.execute("truncate table rental_inquiry_outbox");
    reset(streamBridge, dependencies);
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(userToken("admin-token", "SYSTEM_ADMIN", "rwms.write"));
    when(jwtDecoder.decode("manager-token"))
        .thenReturn(userToken("manager-token", "WAREHOUSE_MANAGER", "rwms.write"));
  }

  @Test
  void brokerAcknowledgementMarksTheClaimedRowPublished() {
    UUID orderId = appendPendingEvent();
    when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

    relay.relay();

    UUID eventId = eventId(orderId);
    assertThat(jdbc.queryForMap("select status,attempt_count,published_at from rental_inquiry_outbox where event_id=?", eventId))
        .containsEntry("status", "PUBLISHED")
        .containsEntry("attempt_count", 1)
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
  void falseAndThrownBrokerResultsPersistIndependentlyAndDoNotBlockLaterRows() {
    UUID first = appendPendingEvent();
    UUID middle = appendPendingEvent();
    UUID last = appendPendingEvent();
    jdbc.update(
        "update rental_inquiry_outbox set created_at=clock_timestamp()-interval '3 seconds' where order_id=?",
        first);
    jdbc.update(
        "update rental_inquiry_outbox set created_at=clock_timestamp()-interval '2 seconds' where order_id=?",
        middle);
    jdbc.update(
        "update rental_inquiry_outbox set created_at=clock_timestamp()-interval '1 second' where order_id=?",
        last);
    when(streamBridge.send(anyString(), any(Message.class)))
        .thenReturn(false)
        .thenThrow(new IllegalStateException("broker unavailable"))
        .thenReturn(true);

    relay.relay();

    assertThat(deliveryState(first))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 1)
        .containsEntry("last_error_code", "BROKER_REJECTED");
    assertThat(deliveryState(middle))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 1)
        .containsEntry("last_error_code", "PUBLISH_FAILED");
    assertThat(deliveryState(last))
        .containsEntry("status", "PUBLISHED")
        .containsEntry("attempt_count", 1);
    verify(streamBridge, times(3)).send(anyString(), any(Message.class));
  }

  @Test
  void repeatedBrokerRejectionStopsExactlyAtTheConfiguredAttemptBudget() {
    UUID orderId = appendPendingEvent();
    when(streamBridge.send(anyString(), any(Message.class))).thenReturn(false);

    for (int attempt = 0; attempt < 4; attempt++) {
      jdbc.update(
          "update rental_inquiry_outbox set next_attempt_at=clock_timestamp() where order_id=?",
          orderId);
      relay.relay();
    }

    assertThat(deliveryState(orderId))
        .containsEntry("status", "QUARANTINED")
        .containsEntry("attempt_count", 4)
        .containsEntry("last_error_code", "BROKER_REJECTED");
    verify(streamBridge, times(4)).send(anyString(), any(Message.class));
  }

  @Test
  void expiredClaimsConsumeTheBudgetAndOldLeaseTokensCannotFinalize() {
    UUID orderId = appendPendingEvent();
    String originalPayload = persistedPayload(orderId);
    UUID eventId = eventId(orderId);

    var first = store.claim("first", Duration.ofSeconds(30), 2).claim().orElseThrow();
    jdbc.update(
        "update rental_inquiry_outbox set lease_until=clock_timestamp()-interval '1 second' where event_id=?",
        eventId);
    var second = store.claim("second", Duration.ofSeconds(30), 2).claim().orElseThrow();
    assertThat(second.attemptCount()).isEqualTo(2);
    assertThat(store.markPublished(first)).isFalse();
    jdbc.update(
        "update rental_inquiry_outbox set lease_until=clock_timestamp()-interval '1 second' where event_id=?",
        eventId);

    var terminal = store.claim("third", Duration.ofSeconds(30), 2);

    assertThat(terminal.progressed()).isTrue();
    assertThat(terminal.claim()).isEmpty();
    assertThat(deliveryState(orderId))
        .containsEntry("status", "QUARANTINED")
        .containsEntry("attempt_count", 2)
        .containsEntry("last_error_code", "LEASE_EXPIRED");
    assertThat(eventId(orderId)).isEqualTo(eventId);
    assertThat(persistedPayload(orderId)).isEqualTo(originalPayload);
  }

  @Test
  void reviewedRecoveryIsFencedAuditedIntegrityCheckedAndExactlyReplayable() {
    UUID orderId = appendPendingEvent();
    UUID eventId = eventId(orderId);
    UUID reviewer = UUID.randomUUID();
    String payload = persistedPayload(orderId);
    jdbc.update(
        "update rental_inquiry_outbox set status='QUARANTINED',attempt_count=4,last_error_code='PUBLISH_FAILED' where event_id=?",
        eventId);

    var recovered = store.recover(eventId, 0, reviewer, "  broker reviewed  ");
    var claim = store.claim("after-review", Duration.ofSeconds(30), 4).claim().orElseThrow();
    assertThat(store.markPublished(claim)).isTrue();
    var replay = store.recover(eventId, 0, reviewer, "broker reviewed");

    assertThat(recovered.status()).isEqualTo("PENDING");
    assertThat(recovered.recoveryVersion()).isEqualTo(1);
    assertThat(replay.status()).isEqualTo("PENDING");
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.reviewedAt()).isEqualTo(recovered.reviewedAt());
    assertThat(eventId(orderId)).isEqualTo(eventId);
    assertThat(persistedPayload(orderId)).isEqualTo(payload);
    assertThat(
            jdbc.queryForMap(
                "select recovery_version,prior_attempt_count,prior_last_error_code,reason from rental_inquiry_outbox_recovery_audit where event_id=?",
                eventId))
        .containsEntry("recovery_version", 1L)
        .containsEntry("prior_attempt_count", 4)
        .containsEntry("prior_last_error_code", "PUBLISH_FAILED")
        .containsEntry("reason", "broker reviewed");
    assertThatThrownBy(() -> store.recover(eventId, 0, reviewer, "changed review"))
        .hasMessageContaining("another reviewed command");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update rental_inquiry_outbox_recovery_audit set reason='changed' where event_id=?",
                    eventId))
        .hasMessageContaining("immutable");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "delete from rental_inquiry_outbox_recovery_audit where event_id=?", eventId))
        .hasMessageContaining("immutable");
    assertThatThrownBy(() -> jdbc.execute("truncate table rental_inquiry_outbox_recovery_audit"))
        .hasMessageContaining("immutable");
  }

  @Test
  void corruptedCanonicalPayloadChecksumCannotBeSentOrReviewed() {
    UUID orderId = appendPendingEvent();
    UUID eventId = eventId(orderId);
    jdbc.update(
        "update rental_inquiry_outbox set payload_sha256=? where event_id=?",
        "0".repeat(64),
        eventId);
    when(streamBridge.send(anyString(), any(Message.class))).thenReturn(true);

    relay.relay();

    assertThat(deliveryState(orderId))
        .containsEntry("status", "QUARANTINED")
        .containsEntry("last_error_code", "VALIDATION_REJECTED");
    verify(streamBridge, times(0)).send(anyString(), any(Message.class));
    assertThatThrownBy(() -> store.recover(eventId, 0, UUID.randomUUID(), "reviewed"))
        .hasMessageContaining("not safe");
  }

  @Test
  void inquiryRetryLimitsRejectNonPositiveValues() {
    assertThatThrownBy(() -> new RentalInquiryOutboxProperties(true, 0, 1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
    assertThatThrownBy(() -> new RentalInquiryOutboxProperties(true, 1, 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void realHttpBoundaryEnforcesAuthUnicodeValidationConflictReplayAndResponseSchema()
      throws Exception {
    UUID orderId = appendPendingEvent();
    UUID eventId = eventId(orderId);
    jdbc.update(
        "update rental_inquiry_outbox set status='QUARANTINED',attempt_count=4,last_error_code='PUBLISH_FAILED' where event_id=?",
        eventId);
    String path =
        "/api/logistics/v1/admin/rental-inquiry-outbox/" + eventId + "/recovery";
    String request = "{\"expectedRecoveryVersion\":0,\"reason\":\"broker reviewed\"}";

    assertThat(post(path, null, request).statusCode()).isEqualTo(401);
    assertThat(post(path, "manager-token", request).statusCode()).isEqualTo(403);
    assertThat(post(path, "admin-token", "{\"reason\":\"reviewed\"}").statusCode())
        .isEqualTo(422);
    assertThat(
            post(
                    path,
                    "admin-token",
                    "{\"expectedRecoveryVersion\":0,\"reason\":\"   \"}")
                .statusCode())
        .isEqualTo(422);

    HttpResponse<String> success = post(path, "admin-token", request);
    assertThat(success.statusCode()).isEqualTo(200);
    assertRecoveryResponseMatchesContract(success.body());
    assertThat(
            jdbc.queryForMap(
                "select status,attempt_count,lease_owner,lease_token,lease_until,"
                    + "next_attempt_at<=clock_timestamp() as due "
                    + "from rental_inquiry_outbox where event_id=?",
                eventId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("lease_owner", null)
        .containsEntry("lease_token", null)
        .containsEntry("lease_until", null)
        .containsEntry("due", true);
    var claim = store.claim("http-publish", Duration.ofSeconds(30), 4).claim().orElseThrow();
    assertThat(claim.eventId()).isEqualTo(eventId);
    assertThat(store.markPublished(claim)).isTrue();
    HttpResponse<String> replay = post(path, "admin-token", request);
    assertThat(replay.statusCode()).isEqualTo(200);
    assertThat(replay.body()).isEqualTo(success.body());
    assertThat(replay.headers().firstValue("Idempotency-Replayed")).contains("true");
    assertThat(
            post(
                    path,
                    "admin-token",
                    "{\"expectedRecoveryVersion\":0,\"reason\":\"changed\"}")
                .statusCode())
        .isEqualTo(409);

    UUID unicodeOrder = appendPendingEvent();
    UUID unicodeEvent = eventId(unicodeOrder);
    jdbc.update(
        "update rental_inquiry_outbox set status='QUARANTINED',attempt_count=4,last_error_code='PUBLISH_FAILED' where event_id=?",
        unicodeEvent);
    String unicodePath =
        "/api/logistics/v1/admin/rental-inquiry-outbox/" + unicodeEvent + "/recovery";
    assertThat(
            post(
                    unicodePath,
                    "admin-token",
                    "{\"expectedRecoveryVersion\":0,\"reason\":\""
                        + "😀".repeat(2000)
                        + "\"}")
                .statusCode())
        .isEqualTo(200);
    jdbc.update(
        "update rental_inquiry_outbox set status='QUARANTINED',attempt_count=4,last_error_code='PUBLISH_FAILED' where event_id=?",
        unicodeEvent);
    assertThat(
            post(
                    unicodePath,
                    "admin-token",
                    "{\"expectedRecoveryVersion\":1,\"reason\":\""
                        + "😀".repeat(2001)
                        + "\"}")
                .statusCode())
        .isEqualTo(422);
  }

  @Test
  void appendedPayloadIsTheExactCanonicalBookedEnvelope() throws Exception {
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID managerSubjectId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-08-09T12:00:00Z");
    insertBookedLineage(inquiryId, conversationId, orderId, managerSubjectId);
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
    assertThat(
            jdbc.queryForObject(
                "select payload_sha256=encode(sha256(convert_to(payload::text,'UTF8')),'hex') from rental_inquiry_outbox where event_id=?",
                Boolean.class,
                eventId))
        .isTrue();
  }

  private UUID appendPendingEvent() {
    UUID inquiryId = UUID.randomUUID();
    UUID conversationId = UUID.randomUUID();
    UUID orderId = UUID.randomUUID();
    UUID managerSubjectId = UUID.randomUUID();
    insertBookedLineage(inquiryId, conversationId, orderId, managerSubjectId);
    transactions.executeWithoutResult(
        ignored ->
            store.append(
                inquiryId,
                1,
                conversationId,
                UUID.randomUUID(),
                orderId,
                managerSubjectId,
                OffsetDateTime.now()));
    return orderId;
  }

  private void insertBookedLineage(
      UUID inquiryId, UUID conversationId, UUID orderId, UUID managerSubjectId) {
    UUID clientId = UUID.randomUUID();
    String phone =
        "+79%09d".formatted(Long.remainderUnsigned(clientId.getLeastSignificantBits(), 1_000_000_000L));
    jdbc.update(
        """
        insert into order_client(
          id,client_type,display_name,normalized_name,phone,normalized_phone,
          responsible_manager_id,responsible_manager_display_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,'INDIVIDUAL',?,?,?,?,?,'Менеджер',?,?,?,
          clock_timestamp(),clock_timestamp())
        """,
        clientId,
        "Клиент " + clientId,
        "клиент " + clientId,
        phone,
        phone,
        managerSubjectId,
        managerSubjectId,
        UUID.randomUUID(),
        "a".repeat(64));
    jdbc.update(
        """
        insert into rental_order(
          id,order_number,status,client_id,manager_id,manager_display_name,
          created_by_subject_id,created_by_display_name,created_by_role,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at)
        values (?,?,'DRAFT',?,?,'Менеджер',?,'Менеджер','RENTAL_MANAGER',?,?,
          clock_timestamp(),clock_timestamp())
        """,
        orderId,
        "ORD-%019d".formatted(orderId.getMostSignificantBits() & Long.MAX_VALUE),
        clientId,
        managerSubjectId,
        managerSubjectId,
        UUID.randomUUID(),
        "b".repeat(64));
    jdbc.update(
        """
        insert into rental_inquiry(
          id,conversation_id,creation_idempotency_key,client_id,manager_id,
          manager_display_name,manager_role,rental_order_id,state,booked_order_id,
          created_at,updated_at,booked_at)
        values (?,?,?,?,?,'Менеджер','RENTAL_MANAGER',?,'BOOKED',?,
          clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        inquiryId,
        conversationId,
        UUID.randomUUID(),
        clientId,
        managerSubjectId,
        orderId,
        orderId);
  }

  private UUID eventId(UUID orderId) {
    return jdbc.queryForObject(
        "select event_id from rental_inquiry_outbox where order_id=?", UUID.class, orderId);
  }

  private java.util.Map<String, Object> deliveryState(UUID orderId) {
    return jdbc.queryForMap(
        "select status,attempt_count,last_error_code from rental_inquiry_outbox where order_id=?",
        orderId);
  }

  private String persistedPayload(UUID orderId) {
    return jdbc.queryForObject(
        "select payload::text from rental_inquiry_outbox where order_id=?", String.class, orderId);
  }

  private HttpResponse<String> post(String path, String token, String body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path))
            .header(HttpHeaders.CONTENT_TYPE, "application/json");
    if (token != null) request.header(HttpHeaders.AUTHORIZATION, "Bearer " + token);
    return HTTP.send(
        request.POST(HttpRequest.BodyPublishers.ofString(body)).build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static Jwt userToken(String tokenValue, String role, String scope) {
    Instant now = Instant.now();
    return Jwt.withTokenValue(tokenValue)
        .header("alg", "none")
        .subject(UUID.randomUUID().toString())
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("principal_type", "USER")
        .claim("global_role", role)
        .claim("scope", scope)
        .build();
  }

  private void assertRecoveryResponseMatchesContract(String body) throws Exception {
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/logistics-service.yaml");
    java.util.Map<String, Object> yaml;
    try (var input = Files.newInputStream(contract)) {
      yaml = new Yaml().load(input);
    }
    com.fasterxml.jackson.databind.ObjectMapper schemaJson =
        new com.fasterxml.jackson.databind.ObjectMapper();
    com.fasterxml.jackson.databind.node.ObjectNode document = schemaJson.valueToTree(yaml);
    document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    document.put("$ref", "#/components/schemas/RentalInquiryOutboxRecoveryResponse");
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document);
    assertThat(schema.validate(schemaJson.readTree(body))).isEmpty();
  }
}
