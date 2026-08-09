package dev.buhanzaz.rwms.inventory.eventing;

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
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.binding.BindingService;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.messaging.Message;
import org.springframework.scheduling.annotation.ScheduledAnnotationBeanPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryEventingIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final String SESSION_TOPIC = "rwms.inventory.session.v1";

  static {
    POSTGRES.start();
  }

  @Autowired InventoryEventStore events;
  @Autowired InventoryOutboxStore outbox;
  @Autowired InventoryDeadLetterStore deadLetters;
  @Autowired InventoryMediaInboxProcessor media;
  @Autowired InventoryMediaFactProjectionRepository mediaFacts;
  @Autowired InventoryMediaRetryStore mediaRetries;
  @Autowired InventoryAssetInboxProcessor assetInbox;
  @Autowired InventoryAssetRetryStore assetRetries;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired ApplicationContext context;
  @Autowired BindingService bindingService;
  @MockitoBean InventoryApplicationService inventory;

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
  void businessOutboxAndDltRelayRetryBrokerOutageIndefinitelyAndPublishOnlyAfterAck() {
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
    StreamBridge bridge = mock(StreamBridge.class);
    when(bridge.send(anyString(), any(Message.class))).thenReturn(false);
    InventoryOutboxProperties properties =
        new InventoryOutboxProperties("inventory-event-test", Duration.ofSeconds(30));
    InventoryOutboxRelay relay =
        new InventoryOutboxRelay(outbox, properties, deadLetters, bridge);

    for (int attempt = 0; attempt < 6; attempt++) {
      jdbc.update(
          "update outbox_event set next_attempt_at=clock_timestamp() where aggregate_id=?",
          aggregateId.toString());
      assertThat(relay.relayOne()).isFalse();
    }
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_event where aggregate_id=?",
                String.class,
                aggregateId.toString()))
        .isEqualTo("PENDING");
    assertThat(jdbc.queryForObject("select count(*) from sanitized_dead_letter", Integer.class))
        .isZero();
    when(bridge.send(anyString(), any(Message.class))).thenReturn(true);
    jdbc.update(
        "update outbox_event set next_attempt_at=clock_timestamp() where aggregate_id=?",
        aggregateId.toString());
    assertThat(relay.relayOne()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select status from outbox_event where aggregate_id=?",
                String.class,
                aggregateId.toString()))
        .isEqualTo("PUBLISHED");

    deadLetters.record("PROCESSING_FAILED", "e".repeat(64), SESSION_TOPIC, UUID.randomUUID());
    StreamBridge dltBridge = mock(StreamBridge.class);
    when(dltBridge.send(anyString(), any(Message.class))).thenReturn(false);
    InventoryDeadLetterRelay dltRelay =
        new InventoryDeadLetterRelay(jdbc, properties, dltBridge);
    for (int attempt = 0; attempt < 6; attempt++) {
      jdbc.update("update sanitized_dead_letter set next_attempt_at=clock_timestamp()");
      dltRelay.relayOne();
    }
    assertThat(
            jdbc.queryForObject("select status from sanitized_dead_letter", String.class))
        .isEqualTo("PENDING");
    when(dltBridge.send(anyString(), any(Message.class))).thenReturn(true);
    jdbc.update("update sanitized_dead_letter set next_attempt_at=clock_timestamp()");
    dltRelay.relayOne();
    assertThat(
            jdbc.queryForObject("select status from sanitized_dead_letter", String.class))
        .isEqualTo("PUBLISHED");
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
