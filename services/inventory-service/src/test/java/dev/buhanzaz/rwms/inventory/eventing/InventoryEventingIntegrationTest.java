package dev.buhanzaz.rwms.inventory.eventing;

import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.InventoryServiceApplication;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore.AppendCommand;
import dev.buhanzaz.rwms.inventory.service.InventoryApplicationService;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.http.HttpHeaders;
import org.springframework.messaging.Message;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;
import org.yaml.snakeyaml.Yaml;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryEventingIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final String SESSION_TOPIC = "rwms.inventory.session.v1";
  private static final HttpClient HTTP = HttpClient.newHttpClient();

  static {
    POSTGRES.start();
  }

  @Autowired InventoryEventStore events;
  @Autowired InventoryOutboxStore outbox;
  @Autowired InventoryDeadLetterStore deadLetters;
  @Autowired InventoryDeadLetterRelayStore deadLetterRelayStore;
  @Autowired InventoryEventingRecoveryService eventingRecovery;
  @Autowired InventoryMediaInboxProcessor media;
  @Autowired InventoryMediaFactProjectionRepository mediaFacts;
  @Autowired InventoryMediaRetryStore mediaRetries;
  @Autowired InventoryAssetInboxProcessor assetInbox;
  @Autowired InventoryAssetRetryStore assetRetries;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired ApplicationContext context;
  @LocalServerPort int port;
  @Autowired BindingService bindingService;
  @MockitoBean InventoryApplicationService inventory;
  @MockitoBean JwtDecoder jwtDecoder;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @BeforeEach
  void clearRows() {
    jdbc.execute(
        """
        truncate table inventory_session,domain_event,event_stream_head,aggregate_snapshot,
          projection_checkpoint,outbox_event,inbox_message,consumer_aggregate_checkpoint,
          version_gap_quarantine,sanitized_dead_letter,inventory_media_fact_projection
        restart identity cascade
        """);
    when(jwtDecoder.decode("admin-token"))
        .thenReturn(userToken("admin-token", "SYSTEM_ADMIN", "rwms.write"));
    when(jwtDecoder.decode("wms-admin-token"))
        .thenReturn(userToken("wms-admin-token", "WMS_ADMIN", "rwms.write"));
    when(jwtDecoder.decode("manager-token"))
        .thenReturn(userToken("manager-token", "WAREHOUSE_MANAGER", "rwms.write"));
  }

  @Test
  void testProfileStartsNeitherScheduledRecoveryNorKafkaConsumerBinding() {
    assertThat(context.getBeansOfType(ScheduledAnnotationBeanPostProcessor.class)).isEmpty();
    assertThat(bindingService.getConsumerBindingNames()).isEmpty();
  }

  @Test
  void initializeStartsAtZeroAppendUsesCasAndReplayMatchesSnapshot() {
    UUID aggregateId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    ObjectNode initial = mapper.createObjectNode().put("step", 0);
    ObjectNode changed = mapper.createObjectNode().put("step", 1);

    var first =
        events.initialize(
            "SESSION",
            aggregateId,
            "inventory.session.started.v1",
            SESSION_TOPIC,
            initial,
            correlationId,
            null,
            null);
    var second =
        events.append(
            "SESSION",
            aggregateId,
            0,
            "inventory.session.completed.v1",
            SESSION_TOPIC,
            changed,
            correlationId,
            first.eventId(),
            null);

    assertThat(first.aggregateVersion()).isZero();
    assertThat(second.aggregateVersion()).isOne();
    assertThat(events.currentVersion("SESSION", aggregateId)).isOne();
    assertThat(
            jdbc.queryForObject(
                "select last_event_id from event_stream_head where aggregate_type='SESSION' and aggregate_id=?",
                UUID.class,
                aggregateId.toString()))
        .isEqualTo(second.eventId());
    assertThatThrownBy(
            () ->
                events.append(
                    "SESSION",
                    aggregateId,
                    0,
                    "inventory.session.cancelled.v1",
                    SESSION_TOPIC,
                    changed,
                    correlationId,
                    second.eventId(),
                    null))
        .hasMessageContaining("concurrently");

    List<InventoryEventStore.StoredEvent> replay = events.readStream("SESSION", aggregateId);
    assertThat(replay).extracting(InventoryEventStore.StoredEvent::aggregateVersion).containsExactly(0L, 1L);
    int primaryProjection = replay.stream().mapToInt(value -> value.payload().path("step").asInt()).sum();
    int shadowProjection = replay.stream().mapToInt(value -> value.payload().path("step").asInt()).sum();
    assertThat(shadowProjection).isEqualTo(primaryProjection).isOne();
    ObjectNode snapshot = mapper.createObjectNode().put("step", primaryProjection);
    assertThat(events.saveSnapshot("SESSION", aggregateId, 1, snapshot).aggregateVersion()).isOne();
    assertThat(events.saveSnapshot("SESSION", aggregateId, 1, snapshot).sha256())
        .isEqualTo(events.latestSnapshot("SESSION", aggregateId).sha256());
    assertThatThrownBy(
            () ->
                events.saveSnapshot(
                    "SESSION", aggregateId, 1, mapper.createObjectNode().put("step", 99)))
        .hasMessageContaining("changed");
  }

  @Test
  void initializePersistsOpaqueActorReferenceAsJsonb() {
    UUID aggregateId = UUID.randomUUID();
    OpaqueActorReference actor =
        new OpaqueActorReference("00000000-0000-0000-0000-000000000701", "USER", null);

    var result =
        events.initialize(
            "SESSION",
            aggregateId,
            "inventory.session.started.v1",
            SESSION_TOPIC,
            mapper.createObjectNode().put("step", 0),
            UUID.randomUUID(),
            null,
            actor);

    assertThat(
            jdbc.queryForObject(
                "select actor_ref->>'subjectId' from domain_event where event_id=?",
                String.class,
                result.eventId()))
        .isEqualTo(actor.subjectId());
  }

  @Test
  void assetMembershipInboxDeduplicatesExactRedeliveryAndQuarantinesEventIdConflicts() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    OffsetDateTime recordedAt = OffsetDateTime.now(ZoneOffset.UTC);
    ObjectNode envelope = assetFact(eventId, assetId, warehouseId, correlationId, recordedAt, 7);
    byte[] body = envelope.toString().getBytes(StandardCharsets.UTF_8);
    byte[] key = assetId.toString().getBytes(StandardCharsets.UTF_8);

    assetInbox.initial(body, key);
    assetInbox.initial(body, key);

    verify(inventory)
        .reconcileAssetMembership(
            eq(assetId), isNull(), eq(correlationId), eq(eventId), eq(recordedAt));
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from inbox_message
                 where consumer_group=? and event_id=? and status='PROCESSED'
                """,
                Integer.class,
                InventoryAssetInboxProcessor.CONSUMER,
                eventId))
        .isOne();

    byte[] conflictingBody =
        envelope.put("aggregateVersion", 8).toString().getBytes(StandardCharsets.UTF_8);
    assetInbox.initial(conflictingBody, key);

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from sanitized_dead_letter
                 where source_event_id=? and message_sha256=? and failure_code='EVENT_ID_CONFLICT'
                """,
                Integer.class,
                eventId,
                InventoryEventChecksum.sha256(conflictingBody)))
        .isOne();
  }

  @Test
  void transientAssetFailuresKeepTheRetryEnvelopeAndTerminalizeAfterThreeRetries() {
    UUID eventId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    byte[] body =
        assetFact(
                eventId,
                assetId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.now(ZoneOffset.UTC),
                2)
            .toString()
            .getBytes(StandardCharsets.UTF_8);
    byte[] key = assetId.toString().getBytes(StandardCharsets.UTF_8);
    assertThat(assetRetries.scheduleInitial(body, key)).isTrue();
    doThrow(new IllegalStateException("asset membership unavailable"))
        .when(inventory)
        .reconcileAssetMembership(any(), isNull(), any(), any(), any());

    for (int failure = 0; failure < 3; failure++) {
      assertThatThrownBy(() -> assetInbox.retry(eventId))
          .isInstanceOf(IllegalStateException.class)
          .hasMessage("asset membership unavailable");
      assetRetries.failed(eventId);
    }

    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where event_id=?", String.class, eventId))
        .isEqualTo("DLT");
    assertThat(
            jdbc.queryForObject(
                "select attempt_count from inbox_message where event_id=?", Integer.class, eventId))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select next_attempt_at is null and dlt_at is not null from inbox_message where event_id=?",
                Boolean.class,
                eventId))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from sanitized_dead_letter
                 where source_event_id=? and message_sha256=? and failure_code='PROCESSING_FAILED'
                """,
                Integer.class,
                eventId,
                InventoryEventChecksum.sha256(body)))
        .isOne();
  }

  @Test
  void multiStreamAppendIsAtomicAndRecursiveSensitiveFieldsAreRejected() {
    UUID firstId = UUID.fromString("00000000-0000-0000-0000-000000000001");
    UUID secondId = UUID.fromString("00000000-0000-0000-0000-000000000002");
    UUID correlationId = UUID.randomUUID();
    ObjectNode payload = mapper.createObjectNode().put("state", "INITIAL");
    events.initialize(
        "FINDING",
        firstId,
        "inventory.finding.added.v1",
        SESSION_TOPIC,
        payload,
        correlationId,
        null,
        null);
    events.initialize(
        "FINDING",
        secondId,
        "inventory.finding.added.v1",
        SESSION_TOPIC,
        payload,
        correlationId,
        null,
        null);

    assertThatThrownBy(
            () ->
                events.appendAll(
                    List.of(
                        command(firstId, 0, correlationId),
                        command(secondId, 99, correlationId))))
        .hasMessageContaining("concurrently");
    assertThat(events.currentVersion("FINDING", firstId)).isZero();
    assertThat(events.currentVersion("FINDING", secondId)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isEqualTo(2);

    ObjectNode nested = mapper.createObjectNode();
    nested.putObject("safe").putArray("nested").addObject().put("signedUrl", "forbidden");
    assertThatThrownBy(
            () ->
                events.initialize(
                    "FINDING",
                    UUID.randomUUID(),
                    "inventory.finding.added.v1",
                    SESSION_TOPIC,
                    nested,
                    correlationId,
                    null,
                    null))
        .hasMessageContaining("forbidden");
  }

  @Test
  void businessOutboxAndDltRelayStopAtBudgetAndResumeOnlyAfterReviewedRecovery() {
    UUID aggregateId = UUID.randomUUID();
    var firstEvent =
        events.initialize(
        "SESSION",
        aggregateId,
        "inventory.session.started.v1",
        SESSION_TOPIC,
        mapper.createObjectNode().put("step", 0),
        UUID.randomUUID(),
        null,
        null);
    var secondEvent =
        events.append(
            "SESSION",
            aggregateId,
            0,
            "inventory.session.completed.v1",
            SESSION_TOPIC,
            mapper.createObjectNode().put("step", 1),
            UUID.randomUUID(),
            firstEvent.eventId(),
            null);
    StreamBridge bridge = mock(StreamBridge.class);
    when(bridge.send(anyString(), any(Message.class)))
        .thenReturn(false)
        .thenReturn(false)
        .thenThrow(new IllegalStateException("broker unavailable"));
    InventoryOutboxProperties properties =
        new InventoryOutboxProperties("inventory-event-test", Duration.ofSeconds(30), 3);
    InventoryOutboxRelay relay =
        new InventoryOutboxRelay(outbox, properties, deadLetters, bridge);

    for (int attempt = 0; attempt < 3; attempt++) {
      jdbc.update(
          "update outbox_event set next_attempt_at=clock_timestamp() where aggregate_id=?",
          aggregateId.toString());
      assertThat(relay.relayOne()).isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_event where event_id=?",
                String.class,
                firstEvent.eventId()))
        .isEqualTo("QUARANTINED");
    assertThat(
            jdbc.queryForMap(
                "select attempt_count,terminal_phase,terminal_reason from outbox_event where event_id=?",
                firstEvent.eventId()))
        .containsEntry("attempt_count", 3)
        .containsEntry("terminal_phase", "PUBLISH")
        .containsEntry("terminal_reason", "RETRY_BUDGET_EXHAUSTED");
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_event where event_id=?",
                String.class,
                secondEvent.eventId()))
        .isEqualTo("PENDING");
    assertThat(relay.relayOne()).isFalse();
    assertThat(jdbc.queryForObject("select count(*) from sanitized_dead_letter", Integer.class))
        .isZero();

    UUID reviewer = UUID.randomUUID();
    var outboxReceipt =
        eventingRecovery.requeueOutbox(
            firstEvent.eventId(),
            0L,
            reviewer,
            "broker recovered");
    assertThat(outboxReceipt.status()).isEqualTo("PENDING");
    assertThat(outboxReceipt.reviewVersion()).isOne();
    when(bridge.send(anyString(), any(Message.class))).thenReturn(true);
    assertThat(relay.relayOne()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_event where event_id=?",
                String.class,
                firstEvent.eventId()))
        .isEqualTo("PUBLISHED");
    assertThat(
            eventingRecovery.requeueOutbox(
                outboxReceipt.recordId(), 0L, reviewer, "broker recovered"))
        .isEqualTo(outboxReceipt);
    assertThatThrownBy(
            () ->
                eventingRecovery.requeueOutbox(
                    outboxReceipt.recordId(), 0L, reviewer, "different review"))
        .hasMessageContaining("bound to another request");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_eventing_recovery_review set review_reason='changed' where record_id=?",
                    outboxReceipt.recordId()))
        .hasMessageContaining("append-only");
    assertThat(relay.relayOne()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_event where event_id=?",
                String.class,
                secondEvent.eventId()))
        .isEqualTo("PUBLISHED");

    deadLetters.record("PROCESSING_FAILED", "e".repeat(64), SESSION_TOPIC, UUID.randomUUID());
    StreamBridge dltBridge = mock(StreamBridge.class);
    when(dltBridge.send(anyString(), any(Message.class)))
        .thenReturn(false)
        .thenThrow(new IllegalStateException("broker unavailable"))
        .thenReturn(false);
    InventoryDeadLetterRelay dltRelay =
        new InventoryDeadLetterRelay(deadLetterRelayStore, properties, dltBridge);
    for (int attempt = 0; attempt < 3; attempt++) {
      jdbc.update("update sanitized_dead_letter set next_attempt_at=clock_timestamp()");
      dltRelay.relayOne();
    }
    assertThat(
            jdbc.queryForObject("select status from sanitized_dead_letter", String.class))
        .isEqualTo("FAILED");
    assertThat(
            jdbc.queryForMap(
                "select attempt_count,terminal_phase,terminal_reason from sanitized_dead_letter"))
        .containsEntry("attempt_count", 3)
        .containsEntry("terminal_phase", "PUBLISH")
        .containsEntry("terminal_reason", "RETRY_BUDGET_EXHAUSTED");
    UUID dltId =
        jdbc.queryForObject("select dlt_id from sanitized_dead_letter", UUID.class);
    var dltReceipt =
        eventingRecovery.requeueDeadLetter(dltId, 0L, reviewer, "broker recovered");
    when(dltBridge.send(anyString(), any(Message.class))).thenReturn(true);
    dltRelay.relayOne();
    assertThat(
            jdbc.queryForObject("select status from sanitized_dead_letter", String.class))
        .isEqualTo("PUBLISHED");
    assertThat(
            eventingRecovery.requeueDeadLetter(dltId, 0L, reviewer, "broker recovered"))
        .isEqualTo(dltReceipt);
  }

  @Test
  void expiredClaimsAndExistingOverBudgetRowsBecomeTerminalWithoutAnotherPublish() {
    UUID aggregateId = UUID.randomUUID();
    events.initialize(
        "SESSION",
        aggregateId,
        "inventory.session.started.v1",
        SESSION_TOPIC,
        mapper.createObjectNode().put("step", 0),
        UUID.randomUUID(),
        null,
        null);

    assertThat(outbox.claim("first", Duration.ofSeconds(30), 2)).isPresent();
    jdbc.update("update outbox_event set lease_until=clock_timestamp()-interval '1 second'");
    assertThat(outbox.claim("second", Duration.ofSeconds(30), 2)).isPresent();
    jdbc.update("update outbox_event set lease_until=clock_timestamp()-interval '1 second'");
    assertThat(outbox.claim("third", Duration.ofSeconds(30), 2)).isEmpty();
    assertThat(jdbc.queryForObject("select status from outbox_event", String.class))
        .isEqualTo("QUARANTINED");
    assertThat(jdbc.queryForObject("select terminal_phase from outbox_event", String.class))
        .isEqualTo("LEASE");

    deadLetters.record("PROCESSING_FAILED", "f".repeat(64), SESSION_TOPIC, UUID.randomUUID());
    jdbc.update("update sanitized_dead_letter set attempt_count=2");
    assertThat(deadLetterRelayStore.claim("dlt", Duration.ofSeconds(30), 2)).isEmpty();
    assertThat(jdbc.queryForObject("select status from sanitized_dead_letter", String.class))
        .isEqualTo("FAILED");
    assertThat(jdbc.queryForObject("select terminal_phase from sanitized_dead_letter", String.class))
        .isEqualTo("PUBLISH");
  }

  @Test
  void checksumFailedDeadLetterCannotBeRecoveredWithoutAnIntactSafeBody() {
    deadLetters.record("PROCESSING_FAILED", "a".repeat(64), SESSION_TOPIC, UUID.randomUUID());
    jdbc.update("update sanitized_dead_letter set body_sha256=?", "0".repeat(64));
    InventoryOutboxProperties properties =
        new InventoryOutboxProperties("inventory-event-test", Duration.ofSeconds(30), 3);
    InventoryDeadLetterRelay relay =
        new InventoryDeadLetterRelay(deadLetterRelayStore, properties, mock(StreamBridge.class));

    relay.relayOne();
    UUID dltId =
        jdbc.queryForObject("select dlt_id from sanitized_dead_letter", UUID.class);
    assertThat(jdbc.queryForObject("select status from sanitized_dead_letter", String.class))
        .isEqualTo("FAILED");
    assertThatThrownBy(
            () -> eventingRecovery.requeueDeadLetter(dltId, 0L, UUID.randomUUID(), "reviewed"))
        .hasMessageContaining("checksum");
  }

  @Test
  void retryBudgetConfigurationRejectsNonPositiveValues() {
    assertThatThrownBy(
            () ->
                new InventoryOutboxProperties(
                    "inventory-event-test", Duration.ofSeconds(30), 0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("positive");
  }

  @Test
  void recoveryHttpBoundaryEnforcesReviewAuthValidationIntegrityAndCanonicalReceipt()
      throws Exception {
    UUID aggregateId = UUID.randomUUID();
    var event =
        events.initialize(
            "SESSION",
            aggregateId,
            "inventory.session.started.v1",
            SESSION_TOPIC,
            mapper.createObjectNode().put("step", 0),
            UUID.randomUUID(),
            null,
            null);
    jdbc.update(
        """
        update outbox_event set status='QUARANTINED',attempt_count=4,
          last_error_code='PUBLISH_FAILED',terminal_phase='PUBLISH',
          terminal_reason='RETRY_BUDGET_EXHAUSTED' where event_id=?
        """,
        event.eventId());
    String path = "/api/inventory/v1/operations/outbox/" + event.eventId() + "/requeue";
    String request = "{\"expectedReviewVersion\":0,\"reason\":\"broker reviewed\"}";

    assertThat(post(path, null, request).statusCode()).isEqualTo(401);
    assertThat(post(path, "manager-token", request).statusCode()).isEqualTo(403);
    assertThat(post(path, "admin-token", "{\"reason\":\"reviewed\"}").statusCode())
        .isEqualTo(422);
    assertThat(
            post(
                    path,
                    "admin-token",
                    "{\"expectedReviewVersion\":0,\"reason\":\"   \"}")
                .statusCode())
        .isEqualTo(422);
    var unicodeEvent =
        events.initialize(
            "SESSION",
            UUID.randomUUID(),
            "inventory.session.started.v1",
            SESSION_TOPIC,
            mapper.createObjectNode().put("step", 0),
            UUID.randomUUID(),
            null,
            null);
    jdbc.update(
        """
        update outbox_event set status='QUARANTINED',attempt_count=4,
          last_error_code='PUBLISH_FAILED',terminal_phase='PUBLISH',
          terminal_reason='RETRY_BUDGET_EXHAUSTED' where event_id=?
        """,
        unicodeEvent.eventId());
    String unicodePath =
        "/api/inventory/v1/operations/outbox/" + unicodeEvent.eventId() + "/requeue";
    String twoThousandEmoji = "😀".repeat(2000);
    assertThat(
            post(
                    unicodePath,
                    "admin-token",
                    "{\"expectedReviewVersion\":0,\"reason\":\""
                        + twoThousandEmoji
                        + "\"}")
                .statusCode())
        .isEqualTo(200);
    jdbc.update(
        """
        update outbox_event set status='QUARANTINED',attempt_count=4,
          last_error_code='PUBLISH_FAILED',terminal_phase='PUBLISH',
          terminal_reason='RETRY_BUDGET_EXHAUSTED' where event_id=?
        """,
        unicodeEvent.eventId());
    assertThat(
            post(
                    unicodePath,
                    "admin-token",
                    "{\"expectedReviewVersion\":1,\"reason\":\""
                        + "😀".repeat(2001)
                        + "\"}")
                .statusCode())
        .isEqualTo(422);

    HttpResponse<String> success = post(path, "admin-token", request);
    assertThat(success.statusCode()).isEqualTo(200);
    String receipt = success.body();
    assertRecoveryReceiptMatchesContract(receipt);

    InventoryOutboxStore.Claim claim =
        outbox.claim("http-recovery", Duration.ofSeconds(30), 4).orElseThrow();
    assertThat(claim.eventId()).isEqualTo(event.eventId());
    assertThat(outbox.published(claim)).isTrue();
    HttpResponse<String> replayResponse = post(path, "admin-token", request);
    assertThat(replayResponse.statusCode()).isEqualTo(200);
    String replay = replayResponse.body();
    assertThat(replay).isEqualTo(receipt);
    assertThat(
            post(
                    path,
                    "admin-token",
                    "{\"expectedReviewVersion\":0,\"reason\":\"changed\"}")
                .statusCode())
        .isEqualTo(409);
    assertThat(
            post(
                    path,
                    "admin-token",
                    "{\"expectedReviewVersion\":1,\"reason\":\"stale\"}")
                .statusCode())
        .isEqualTo(409);

    var corrupt =
        events.initialize(
            "SESSION",
            UUID.randomUUID(),
            "inventory.session.started.v1",
            SESSION_TOPIC,
            mapper.createObjectNode().put("step", 0),
            UUID.randomUUID(),
            null,
            null);
    jdbc.update(
        """
        update outbox_event set status='QUARANTINED',attempt_count=4,
          last_error_code='PUBLISH_FAILED',terminal_phase='PUBLISH',
          terminal_reason='RETRY_BUDGET_EXHAUSTED',envelope_sha256=? where event_id=?
        """,
        "0".repeat(64),
        corrupt.eventId());
    assertThat(
            post(
                    "/api/inventory/v1/operations/outbox/"
                        + corrupt.eventId()
                        + "/requeue",
                    "admin-token",
                    request)
                .statusCode())
        .isEqualTo(409);

    deadLetters.record("PROCESSING_FAILED", "b".repeat(64), SESSION_TOPIC, UUID.randomUUID());
    UUID dltId = jdbc.queryForObject("select dlt_id from sanitized_dead_letter", UUID.class);
    jdbc.update(
        """
        update sanitized_dead_letter set status='FAILED',attempt_count=4,
          last_error_code='PUBLISH_FAILED',terminal_phase='PUBLISH',
          terminal_reason='RETRY_BUDGET_EXHAUSTED' where dlt_id=?
        """,
        dltId);
    HttpResponse<String> dltSuccess =
        post(
            "/api/inventory/v1/operations/dead-letters/" + dltId + "/requeue",
            "wms-admin-token",
            request);
    assertThat(dltSuccess.statusCode()).isEqualTo(200);
    String dltReceipt = dltSuccess.body();
    assertRecoveryReceiptMatchesContract(dltReceipt);
  }

  @Test
  void mediaFactsAcceptCanonicalFolderPayloadDeduplicateExactBytesAndCommitEffectAtomically() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = seedFinding(warehouseId, inventoryId);
    UUID mediaId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    // The private upload-session creation is version 1. The first fact emitted on the public
    // media topic is therefore the finalized-upload snapshot at version 2.
    byte[] valid = mediaFact(eventId, mediaId, 2, findingId, warehouseId, "media.media.uploaded.v1");

    media.initial(valid);
    media.initial(valid);
    assertThat(jdbc.queryForObject("select count(*) from inbox_message", Integer.class)).isOne();
    assertThat(
            jdbc.queryForObject(
                "select last_aggregate_version from consumer_aggregate_checkpoint where aggregate_id=?",
                Long.class,
                mediaId.toString()))
        .isEqualTo(2L);
    assertThat(jdbc.queryForObject("select count(*) from inventory_media_fact_projection", Integer.class))
        .isOne();
    assertThat(mediaFacts.findByMediaIdAndGeneration(mediaId, 0L))
        .hasValueSatisfying(
            projection -> {
              assertThat(projection.getAggregateVersion()).isEqualTo(2L);
              assertThat(projection.getMediaStatus()).isEqualTo("PROCESSING");
            });

    media.initial(
        mediaFact(
            UUID.randomUUID(),
            mediaId,
            3,
            findingId,
            warehouseId,
            "media.media.ready.v1"));
    assertThat(mediaFacts.findByMediaIdAndGeneration(mediaId, 0L))
        .hasValueSatisfying(
            projection -> {
              assertThat(projection.getAggregateVersion()).isEqualTo(3L);
              assertThat(projection.getMediaStatus()).isEqualTo("READY");
            });

    byte[] differentExactBytes =
        (new String(valid, StandardCharsets.UTF_8) + " ").getBytes(StandardCharsets.UTF_8);
    media.initial(differentExactBytes);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from sanitized_dead_letter where failure_code='EVENT_ID_CONFLICT'",
                Integer.class))
        .isOne();

    UUID invalidEvent = UUID.randomUUID();
    media.initial(
        mediaFact(
            invalidEvent,
            UUID.randomUUID(),
            1,
            findingId,
            warehouseId,
            "media.processing.request.v1"));
    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where event_id=?", String.class, invalidEvent))
        .isEqualTo("DLT");

    UUID atomicMediaId = UUID.randomUUID();
    UUID atomicEventId = UUID.randomUUID();
    jdbc.execute(
        """
        create or replace function fail_inventory_media_effect() returns trigger language plpgsql
        as $$ begin raise exception 'forced effect failure'; end $$
        """);
    jdbc.execute(
        """
        create trigger fail_inventory_media_effect before insert on inventory_media_fact_projection
        for each row execute function fail_inventory_media_effect()
        """);
    try {
      assertThatThrownBy(
              () ->
                  media.initial(
                      mediaFact(
                          atomicEventId,
                          atomicMediaId,
                          2,
                          findingId,
                          warehouseId,
                          "media.media.uploaded.v1")))
          .hasMessageContaining("forced effect failure");
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from inbox_message where event_id=?",
                  Integer.class,
                  atomicEventId))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from consumer_aggregate_checkpoint where aggregate_id=?",
                  Integer.class,
                  atomicMediaId.toString()))
          .isZero();
    } finally {
      jdbc.execute("drop trigger fail_inventory_media_effect on inventory_media_fact_projection");
      jdbc.execute("drop function fail_inventory_media_effect()");
    }
  }

  @Test
  void mediaFactsAcceptSourceOwnedVersionGapsAndIgnoreStaleSnapshots() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = seedFinding(warehouseId, inventoryId);
    UUID mediaId = UUID.randomUUID();
    UUID uploadedEvent = UUID.randomUUID();
    UUID rotatedEvent = UUID.randomUUID();
    UUID staleEvent = UUID.randomUUID();
    int beforeDlt = jdbc.queryForObject("select count(*) from sanitized_dead_letter", Integer.class);

    media.initial(
        mediaFact(
            uploadedEvent,
            mediaId,
            2,
            findingId,
            warehouseId,
            "media.media.uploaded.v1"));
    media.initial(
        mediaFact(
            rotatedEvent,
            mediaId,
            5,
            findingId,
            warehouseId,
            "media.media.rotated.v1"));
    assertThat(
            jdbc.queryForObject(
                "select last_aggregate_version from consumer_aggregate_checkpoint where aggregate_id=?",
                Long.class,
                mediaId.toString()))
        .isEqualTo(5L);
    assertThat(jdbc.queryForObject("select count(*) from sanitized_dead_letter", Integer.class))
        .isEqualTo(beforeDlt);

    media.initial(
        mediaFact(
            staleEvent,
            mediaId,
            3,
            findingId,
            warehouseId,
            "media.media.ready.v1"));
    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where event_id=?", String.class, staleEvent))
        .isEqualTo("PROCESSED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from version_gap_quarantine where aggregate_id=?",
                Integer.class,
                mediaId.toString()))
        .isZero();
  }

  @Test
  void foreignOwnerMediaFactsBypassInventorySpecificValidation() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = seedFinding(warehouseId, inventoryId);
    UUID mediaId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();

    ObjectNode foreignOwner =
        (ObjectNode)
            mapper.readTree(
                mediaFact(
                    eventId,
                    mediaId,
                    2,
                    findingId,
                    warehouseId,
                    "media.media.uploaded.v1",
                    "LOGISTICS_RETURN"));
    // A logistics return identifies its owner differently. That is valid for its bounded context
    // and must not become an inventory DLT on the shared media topic.
    ((ObjectNode) foreignOwner.path("payload")).put("ownerId", "return-line-42");
    media.initial(mapper.writeValueAsBytes(foreignOwner));

    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where event_id=?", String.class, eventId))
        .isEqualTo("PROCESSED");
    assertThat(mediaFacts.findByMediaIdAndGeneration(mediaId, 0L)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from consumer_aggregate_checkpoint where aggregate_id=?",
                Integer.class,
                mediaId.toString()))
        .isZero();
  }

  @Test
  void transientMediaFailuresUseInitialPlusThreeRetriesThenSanitizedDlt() {
    byte[] bytes =
        mediaFact(
            UUID.randomUUID(),
            UUID.randomUUID(),
            1,
            UUID.randomUUID(),
            UUID.randomUUID(),
            "media.media.uploaded.v1");
    assertThat(mediaRetries.scheduleInitial(bytes)).isTrue();
    UUID eventId =
        jdbc.queryForObject(
            "select event_id from inbox_message where status='RETRY'", UUID.class);
    mediaRetries.failed(eventId);
    mediaRetries.failed(eventId);
    mediaRetries.failed(eventId);

    assertThat(
            jdbc.queryForObject(
                "select status from inbox_message where event_id=?", String.class, eventId))
        .isEqualTo("DLT");
    assertThat(
            jdbc.queryForObject(
                "select attempt_count from inbox_message where event_id=?", Integer.class, eventId))
        .isEqualTo(4);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from sanitized_dead_letter where failure_code='PROCESSING_FAILED'",
                Integer.class))
        .isOne();
  }

  private void assertRecoveryReceiptMatchesContract(String responseBody) throws Exception {
    com.fasterxml.jackson.databind.ObjectMapper json =
        new com.fasterxml.jackson.databind.ObjectMapper();
    Path contract =
        Path.of(System.getProperty("rwms.contracts.dir"), "openapi/inventory-service.yaml");
    java.util.Map<String, Object> yaml;
    try (var input = Files.newInputStream(contract)) {
      yaml = new Yaml().load(input);
    }
    com.fasterxml.jackson.databind.node.ObjectNode document = json.valueToTree(yaml);
    document.put("$schema", "https://json-schema.org/draft/2020-12/schema");
    document.put("$ref", "#/components/schemas/EventingRecoveryReceipt");
    var schema =
        JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(document);
    assertThat(schema.validate(json.readTree(responseBody))).isEmpty();
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

  private AppendCommand command(UUID aggregateId, long expectedVersion, UUID correlationId) {
    return new AppendCommand(
        "FINDING",
        aggregateId,
        expectedVersion,
        "inventory.finding.inspection-saved.v1",
        SESSION_TOPIC,
        mapper.createObjectNode().put("state", "CHANGED"),
        correlationId,
        null,
        null);
  }

  private UUID seedFinding(UUID warehouseId, UUID inventoryId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',?::jsonb,?,?,?)
        """,
        inventoryId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "0".repeat(64),
        "1".repeat(64),
        UUID.randomUUID(),
        actor(),
        now,
        now,
        now);
    UUID findingId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,asset_id,
          asset_version_snapshot,display_canonical_number,identity_match_key,
          passport_observation_state,equipment_observation_state,mutation_state,
          actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','MATCHED',?,0,'MEDIA','MEDIA',
          'ABSENT','ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        UUID.randomUUID(),
        actor(),
        now,
        now);
    return findingId;
  }

  /** Builds a contract-shaped asset fact for persistence and recovery-path assertions. */
  private ObjectNode assetFact(
      UUID eventId,
      UUID assetId,
      UUID warehouseId,
      UUID correlationId,
      OffsetDateTime recordedAt,
      long aggregateVersion) {
    ObjectNode envelope = mapper.createObjectNode();
    envelope.put("envelopeVersion", 2);
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", "asset.rental-item.status-changed.v1");
    envelope.put("eventVersion", 1);
    envelope.putNull("occurredAt");
    envelope.put("recordedAt", recordedAt.toString());
    envelope.put("producer", "asset-service");
    envelope.put("aggregateType", "RENTAL_ITEM");
    envelope.put("aggregateId", assetId.toString());
    envelope.put("aggregateVersion", aggregateVersion);
    envelope
        .putObject("correlation")
        .put("correlationId", correlationId.toString())
        .putNull("causationId");
    envelope.putNull("actorRef");
    envelope
        .putObject("payload")
        .put("rentalItemId", assetId.toString())
        .put("warehouseId", warehouseId.toString())
        .put("status", "AFTER_RENT")
        .put("numberSha256", "a".repeat(64));
    return envelope;
  }

  private byte[] mediaFact(
      UUID eventId,
      UUID mediaId,
      long version,
      UUID findingId,
      UUID warehouseId,
      String eventType) {
    return mediaFact(
        eventId,
        mediaId,
        version,
        findingId,
        warehouseId,
        eventType,
        "INVENTORY_FINDING");
  }

  private byte[] mediaFact(
      UUID eventId,
      UUID mediaId,
      long version,
      UUID findingId,
      UUID warehouseId,
      String eventType,
      String ownerType) {
    ObjectNode root = mapper.createObjectNode();
    root.put("envelopeVersion", 2);
    root.put("eventId", eventId.toString());
    root.put("eventType", eventType);
    root.put("eventVersion", 1);
    root.putNull("occurredAt");
    root.put("recordedAt", "2026-07-17T12:00:00Z");
    root.put("producer", "media-service");
    root.put("aggregateType", "MEDIA");
    root.put("aggregateId", mediaId.toString());
    root.put("aggregateVersion", version);
    ObjectNode correlation = root.putObject("correlation");
    correlation.put("correlationId", UUID.randomUUID().toString());
    correlation.putNull("causationId");
    root.putNull("actorRef");
    ObjectNode payload = root.putObject("payload");
    payload.put("mediaId", mediaId.toString());
    payload.put("folderId", UUID.randomUUID().toString());
    payload.put("ownerType", ownerType);
    payload.put("ownerId", findingId.toString());
    payload.put("warehouseId", warehouseId.toString());
    payload.put("kind", "IMAGE");
    payload.put(
        "status",
        switch (eventType) {
          case "media.media.ready.v1", "media.media.rotated.v1" -> "READY";
          case "media.media.failed.v1" -> "FAILED";
          case "media.media.deleted.v1" -> "DELETED";
          default -> "PROCESSING";
        });
    payload.put("generation", 0);
    payload.put("rotationDegrees", 0);
    try {
      return mapper.writeValueAsBytes(root);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private static String actor() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\",\"principalType\":\"USER\",\"profileRevision\":null}";
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }
}
