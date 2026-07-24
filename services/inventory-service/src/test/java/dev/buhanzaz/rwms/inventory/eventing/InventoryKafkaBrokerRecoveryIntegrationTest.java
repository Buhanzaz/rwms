package dev.buhanzaz.rwms.inventory.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.inventory.InventoryServiceApplication;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.ProducerConfig;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
import org.apache.kafka.common.serialization.ByteArraySerializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=true",
      "rwms.inventory.dependencies.enabled=false",
      "spring.cloud.function.definition=inventoryMediaFacts",
      "spring.cloud.stream.function.autodetect=false",
      "spring.cloud.stream.kafka.binder.auto-create-topics=true",
      "spring.cloud.stream.kafka.default.producer.sync=true",
      "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=1000",
      "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=3000",
      "spring.cloud.stream.kafka.binder.configuration.max.block.ms=3000"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryKafkaBrokerRecoveryIntegrationTest {
  private static final String OWNER_TOPIC = "rwms.inventory.session.v1";
  private static final String DLT_TOPIC = "rwms.inventory.dlt.v1";
  private static final String MEDIA_TOPIC = "stage7-inventory-media-" + UUID.randomUUID();
  private static final String MEDIA_GROUP = "stage7-inventory-media-group-" + UUID.randomUUID();
  private static final String CONSUMER = "inventory-service-media-inbox-v1";

  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  @Autowired InventoryEventStore events;
  @Autowired InventoryOutboxRelay outboxRelay;
  @Autowired InventoryDeadLetterRelay deadLetterRelay;
  @Autowired InventoryMediaRecoveryWorker mediaRecoveryWorker;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    registry.add(
        "spring.cloud.stream.bindings.inventoryMediaFacts-in-0.destination", () -> MEDIA_TOPIC);
    registry.add(
        "spring.cloud.stream.bindings.inventoryMediaFacts-in-0.group", () -> MEDIA_GROUP);
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
  void ownerProofOutboxRemainsPendingDuringOutageThenPublishesExactKeyAndBodyAfterAck()
      throws Exception {
    UUID findingId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    ObjectNode payload = mapper.createObjectNode();
    payload.put("ownerType", "INVENTORY_FINDING");
    payload.put("ownerId", findingId.toString());
    payload.put("warehouseId", warehouseId.toString());
    payload.put("ownerRevision", 0);
    payload.put("active", true);
    var event =
        events.initialize(
            "FINDING",
            findingId,
            "inventory.finding.owner-proof.v1",
            OWNER_TOPIC,
            payload,
            UUID.randomUUID(),
            null,
            null);
    String exactBody =
        jdbc.queryForObject(
            "select envelope_body::text from outbox_event where event_id=?",
            String.class,
            event.eventId());
    assertThat(exactBody).isNotNull();

    pauseKafka();
    try {
      await("Kafka broker becomes unavailable", () -> !brokerAvailable());
      assertThat(outboxRelay.relayOne()).isFalse();
      assertThat(outboxStatus(event.eventId())).isEqualTo("PENDING");
      assertThat(outboxAttempts(event.eventId())).isOne();
    } finally {
      resumeKafka();
    }

    await("Kafka broker becomes available", this::brokerAvailable);
    await(
        "owner-proof outbox acknowledgement after broker recovery",
        () -> {
          jdbc.update(
              "update outbox_event set next_attempt_at=clock_timestamp() where event_id=?",
              event.eventId());
          return outboxRelay.relayOne() && "PUBLISHED".equals(outboxStatus(event.eventId()));
        });

    ConsumerRecord<byte[], byte[]> delivered =
        consume(
            OWNER_TOPIC,
            record ->
                event.eventId().toString().equals(eventId(record.value())));
    assertThat(new String(delivered.key(), StandardCharsets.UTF_8))
        .isEqualTo(findingId.toString());
    assertThat(new String(delivered.value(), StandardCharsets.UTF_8)).isEqualTo(exactBody);
    assertThat(mapper.readTree(delivered.value()).required("eventType").asText())
        .isEqualTo("inventory.finding.owner-proof.v1");
  }

  @Test
  void mediaConsumerPreservesOrderDuplicateGapDltAndBrokerRecovery() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = seedFinding(warehouseId, UUID.randomUUID());
    UUID mediaId = UUID.randomUUID();
    UUID uploadedEvent = UUID.randomUUID();
    byte[] uploaded =
        mediaFact(
            uploadedEvent,
            mediaId,
            1,
            findingId,
            warehouseId,
            "media.media.uploaded.v1");
    publish(MEDIA_TOPIC, mediaId.toString().getBytes(StandardCharsets.UTF_8), uploaded);
    await("media version 1 applied", () -> checkpoint(mediaId) == 1);

    publish(MEDIA_TOPIC, mediaId.toString().getBytes(StandardCharsets.UTF_8), uploaded);
    UUID readyEvent = UUID.randomUUID();
    publish(
        MEDIA_TOPIC,
        mediaId.toString().getBytes(StandardCharsets.UTF_8),
        mediaFact(
            readyEvent, mediaId, 2, findingId, warehouseId, "media.media.ready.v1"));
    await("ordered version 2 applied after duplicate", () -> checkpoint(mediaId) == 2);
    assertThat(inboxCount(uploadedEvent)).isOne();
    assertThat(inboxCount(readyEvent)).isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_media_fact_projection where media_id=?",
                Integer.class,
                mediaId))
        .isOne();

    UUID gapEvent = UUID.randomUUID();
    publish(
        MEDIA_TOPIC,
        mediaId.toString().getBytes(StandardCharsets.UTF_8),
        mediaFact(
            gapEvent, mediaId, 4, findingId, warehouseId, "media.media.rotated.v1"));
    await(
        "media version gap quarantined",
        () ->
            jdbc.queryForObject(
                    "select count(*) from version_gap_quarantine where aggregate_id=? and expected_version=3 and received_version=4 and status='OPEN'",
                    Integer.class,
                    mediaId.toString())
                == 1);
    assertThat(checkpointBlocked(mediaId)).isTrue();

    UUID laterEvent = UUID.randomUUID();
    publish(
        MEDIA_TOPIC,
        mediaId.toString().getBytes(StandardCharsets.UTF_8),
        mediaFact(
            laterEvent, mediaId, 5, findingId, warehouseId, "media.media.rotated.v1"));
    await("later fact remains quarantined", () -> "QUARANTINED".equals(inboxStatus(laterEvent)));

    byte[] invalid =
        ("password=never-publish inventory-kafka-" + UUID.randomUUID() + "@example.test")
            .getBytes(StandardCharsets.UTF_8);
    String invalidHash = InventoryEventChecksum.sha256(invalid);
    publish(
        MEDIA_TOPIC,
        UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
        invalid);
    await(
        "sanitized invalid-message DLT staged",
        () ->
            jdbc.queryForObject(
                    "select count(*) from sanitized_dead_letter where message_sha256=? and failure_code='VALIDATION_REJECTED' and status='PENDING'",
                    Integer.class,
                    invalidHash)
                == 1);

    pauseKafka();
    try {
      await("Kafka broker becomes unavailable for DLT", () -> !brokerAvailable());
      deadLetterRelay.relayOne();
      assertThat(dltStatus(invalidHash)).isEqualTo("PENDING");
      assertThat(dltAttempts(invalidHash)).isOne();
    } finally {
      resumeKafka();
    }

    await("Kafka broker recovers for DLT", this::brokerAvailable);
    await(
        "DLT acknowledged after broker recovery",
        () -> {
          jdbc.update(
              "update sanitized_dead_letter set next_attempt_at=clock_timestamp() where message_sha256=?",
              invalidHash);
          deadLetterRelay.relayOne();
          return "PUBLISHED".equals(dltStatus(invalidHash));
        });
    String safeBody =
        new String(
            consume(
                    DLT_TOPIC,
                    record ->
                        new String(record.value(), StandardCharsets.UTF_8).contains(invalidHash))
                .value(),
            StandardCharsets.UTF_8);
    assertThat(safeBody)
        .contains(invalidHash, "VALIDATION_REJECTED")
        .doesNotContain("password", "never-publish", "example.test");

    UUID recoveredMediaId = UUID.randomUUID();
    publish(
        MEDIA_TOPIC,
        recoveredMediaId.toString().getBytes(StandardCharsets.UTF_8),
        mediaFact(
            UUID.randomUUID(),
            recoveredMediaId,
            1,
            findingId,
            warehouseId,
            "media.media.uploaded.v1"));
    await("media consumer resumes after broker recovery", () -> checkpoint(recoveredMediaId) == 1);
  }

  @Test
  void mediaConsumerRetriesTransientFailureFourTimesThenCreatesExactSanitizedDlt()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = seedFinding(warehouseId, UUID.randomUUID());
    UUID mediaId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    byte[] body =
        mediaFact(
            eventId,
            mediaId,
            1,
            findingId,
            warehouseId,
            "media.media.uploaded.v1");
    String bodyHash = InventoryEventChecksum.sha256(body);
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
      publish(MEDIA_TOPIC, mediaId.toString().getBytes(StandardCharsets.UTF_8), body);
      await(
          "real Kafka delivery schedules the first processing retry",
          () -> "RETRY".equals(inboxStatus(eventId)) && inboxAttempts(eventId) == 1);

      for (int expectedAttempts = 2; expectedAttempts <= 3; expectedAttempts++) {
        makeRetryDue(eventId);
        mediaRecoveryWorker.retryOne();
        assertThat(inboxStatus(eventId)).isEqualTo("RETRY");
        assertThat(inboxAttempts(eventId)).isEqualTo(expectedAttempts);
      }

      makeRetryDue(eventId);
      mediaRecoveryWorker.retryOne();
      assertThat(inboxStatus(eventId)).isEqualTo("DLT");
      assertThat(inboxAttempts(eventId)).isEqualTo(4);
      assertThat(
              jdbc.queryForObject(
                  """
                  select count(*) from sanitized_dead_letter
                   where source_topic='rwms.media.media.v1' and source_event_id=?
                     and message_sha256=? and failure_code='PROCESSING_FAILED'
                     and status='PENDING' and attempt_count=0
                  """,
                  Integer.class,
                  eventId,
                  bodyHash))
          .isOne();
      String safeBody =
          jdbc.queryForObject(
              "select safe_body::text from sanitized_dead_letter where source_event_id=?",
              String.class,
              eventId);
      assertThat(safeBody).isNotNull();
      JsonNode safe = mapper.readTree(safeBody);
      assertThat(safe.propertyNames())
          .containsExactlyInAnyOrder("failureCode", "messageSha256", "recordedAt");
      assertThat(safe.required("failureCode").asText()).isEqualTo("PROCESSING_FAILED");
      assertThat(safe.required("messageSha256").asText()).isEqualTo(bodyHash);
      assertThat(safeBody)
          .doesNotContain(
              mediaId.toString(), findingId.toString(), warehouseId.toString(), "ownerId");
    } finally {
      jdbc.execute("drop trigger fail_inventory_media_effect on inventory_media_fact_projection");
      jdbc.execute("drop function fail_inventory_media_effect()");
    }

    UUID recoveredMediaId = UUID.randomUUID();
    publish(
        MEDIA_TOPIC,
        recoveredMediaId.toString().getBytes(StandardCharsets.UTF_8),
        mediaFact(
            UUID.randomUUID(),
            recoveredMediaId,
            1,
            findingId,
            warehouseId,
            "media.media.uploaded.v1"));
    await(
        "media consumer recovers after the forced failure is removed",
        () -> checkpoint(recoveredMediaId) == 1);
  }

  private int inboxCount(UUID eventId) {
    Integer value =
        jdbc.queryForObject(
            "select count(*) from inbox_message where consumer_group=? and event_id=?",
            Integer.class,
            CONSUMER,
            eventId);
    return value == null ? 0 : value;
  }

  private String inboxStatus(UUID eventId) {
    return jdbc.queryForObject(
        "select status from inbox_message where consumer_group=? and event_id=?",
        String.class,
        CONSUMER,
        eventId);
  }

  private int inboxAttempts(UUID eventId) {
    return jdbc.queryForObject(
        "select attempt_count from inbox_message where consumer_group=? and event_id=?",
        Integer.class,
        CONSUMER,
        eventId);
  }

  private void makeRetryDue(UUID eventId) {
    assertThat(
            jdbc.update(
                "update inbox_message set next_attempt_at=clock_timestamp() where consumer_group=? and event_id=? and status='RETRY'",
                CONSUMER,
                eventId))
        .isOne();
  }

  private long checkpoint(UUID mediaId) {
    Long value =
        jdbc.queryForObject(
            "select coalesce(max(last_aggregate_version),-2) from consumer_aggregate_checkpoint where consumer_group=? and aggregate_id=?",
            Long.class,
            CONSUMER,
            mediaId.toString());
    return value == null ? -2 : value;
  }

  private boolean checkpointBlocked(UUID mediaId) {
    return Boolean.TRUE.equals(
        jdbc.queryForObject(
            "select blocked from consumer_aggregate_checkpoint where consumer_group=? and aggregate_id=?",
            Boolean.class,
            CONSUMER,
            mediaId.toString()));
  }

  private String outboxStatus(UUID eventId) {
    return jdbc.queryForObject(
        "select status from outbox_event where event_id=?", String.class, eventId);
  }

  private int outboxAttempts(UUID eventId) {
    return jdbc.queryForObject(
        "select attempt_count from outbox_event where event_id=?", Integer.class, eventId);
  }

  private String dltStatus(String hash) {
    return jdbc.queryForObject(
        "select status from sanitized_dead_letter where message_sha256=?", String.class, hash);
  }

  private int dltAttempts(String hash) {
    return jdbc.queryForObject(
        "select attempt_count from sanitized_dead_letter where message_sha256=?",
        Integer.class,
        hash);
  }

  private boolean brokerAvailable() {
    Properties configuration = new Properties();
    configuration.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
    configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1000);
    try (AdminClient admin = AdminClient.create(configuration)) {
      admin.describeCluster().nodes().get(2, TimeUnit.SECONDS);
      return true;
    } catch (Exception exception) {
      return false;
    }
  }

  private void pauseKafka() {
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
  }

  private void resumeKafka() {
    KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
  }

  private void publish(String topic, byte[] key, byte[] body) throws Exception {
    Properties configuration = new Properties();
    configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ProducerConfig.ACKS_CONFIG, "all");
    configuration.put(ProducerConfig.ENABLE_IDEMPOTENCE_CONFIG, true);
    configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(configuration)) {
      producer.send(new ProducerRecord<>(topic, key, body)).get(10, TimeUnit.SECONDS);
    }
  }

  private ConsumerRecord<byte[], byte[]> consume(
      String topic, Predicate<ConsumerRecord<byte[], byte[]>> expected) {
    Properties configuration = new Properties();
    configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "stage7-inventory-observer-" + UUID.randomUUID());
    configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
      consumer.subscribe(List.of(topic));
      List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
      await(
          "expected Kafka record on " + topic,
          () -> {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            return records.stream().anyMatch(expected);
          });
      return records.stream().filter(expected).findFirst().orElseThrow();
    }
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

  private byte[] mediaFact(
      UUID eventId,
      UUID mediaId,
      long version,
      UUID findingId,
      UUID warehouseId,
      String eventType) {
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
    payload.put("ownerType", "INVENTORY_FINDING");
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

  private String eventId(byte[] body) {
    try {
      JsonNode root = mapper.readTree(body);
      return root.required("eventId").asText();
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Kafka record is not an event envelope", exception);
    }
  }

  private static String actor() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\",\"principalType\":\"USER\",\"profileRevision\":null}";
  }

  private static void await(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
    RuntimeException lastFailure = null;
    while (System.nanoTime() < deadline) {
      try {
        if (condition.getAsBoolean()) return;
        lastFailure = null;
      } catch (RuntimeException unavailable) {
        lastFailure = unavailable;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for " + description, exception);
      }
    }
    throw new AssertionError("Timed out waiting for " + description, lastFailure);
  }
}
