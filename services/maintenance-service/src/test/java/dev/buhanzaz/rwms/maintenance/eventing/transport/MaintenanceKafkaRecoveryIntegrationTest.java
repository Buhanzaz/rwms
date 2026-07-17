package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceChecksum;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
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
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=true",
      "spring.cloud.stream.kafka.binder.auto-create-topics=true",
      "spring.cloud.stream.kafka.default.producer.sync=true",
      "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=1000",
      "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=3000",
      "spring.cloud.stream.kafka.binder.configuration.max.block.ms=3000",
      "spring.task.scheduling.enabled=false",
      "rwms.maintenance.eventing.outbox.relay-delay=1h",
      "rwms.maintenance.eventing.outbox.relay-initial-delay=1h",
      "rwms.maintenance.dependencies.enabled=false"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class MaintenanceKafkaRecoveryIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add(
        "spring.datasource.url",
        () -> POSTGRES.getJdbcUrl() + "&connectTimeout=2&socketTimeout=2");
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MaintenanceKafkaOutboxRelay outbox;
  @Autowired MaintenanceSanitizedDltPublisher deadLetterPublisher;
  @Autowired MaintenanceSanitizedDltRelay deadLetterRelay;
  @Autowired MaintenanceKafkaConsumerLifecycleRegistry consumers;
  @Autowired MaintenanceKafkaConsumerRecoveryMonitor consumerRecovery;
  @Autowired HikariDataSource dataSource;

  @Test
  @Order(1)
  void brokerRecoveryPreservesAckOrderingKeyDedupeGapAndSanitizedDlt() throws Exception {
    UUID firstAggregate = UUID.randomUUID();
    UUID firstEvent = insertCatalogOutbox(firstAggregate, 0);
    assertThat(outbox.relayOne()).isTrue();
    assertThat(outboxState(firstEvent)).isEqualTo("PUBLISHED");

    ConsumerRecord<byte[], byte[]> delivered =
        consume(
                MaintenanceTransportTopics.CATALOG,
                record -> eventId(record).equals(firstEvent.toString()))
            .getFirst();
    assertThat(new String(delivered.key(), StandardCharsets.UTF_8))
        .isEqualTo(firstAggregate.toString());

    UUID outageAggregate = UUID.randomUUID();
    UUID outageEvent = insertCatalogOutbox(outageAggregate, 0);
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      await("Kafka broker unavailable", () -> !brokerAvailable());
      assertThat(outbox.relayOne()).isFalse();
      assertThat(outboxState(outageEvent)).isEqualTo("PENDING");
      assertThat(
              jdbc.queryForObject(
                  "select attempt_count from outbox_event where event_id=?",
                  Integer.class,
                  outageEvent))
          .isOne();
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
    await("Kafka broker recovery", this::brokerAvailable);
    jdbc.update(
        "update outbox_event set next_attempt_at=clock_timestamp() where event_id=?",
        outageEvent);
    await("outbox acknowledged after broker recovery", () -> outbox.relayOne());
    assertThat(outboxState(outageEvent)).isEqualTo("PUBLISHED");

    UUID mediaId = UUID.randomUUID();
    byte[] versionZero = mediaEnvelope(mediaId, UUID.randomUUID(), 0, "media.media.uploaded.v1");
    UUID versionZeroEvent = eventUuid(versionZero);
    publish(MaintenanceTransportTopics.MEDIA, mediaId.toString().getBytes(StandardCharsets.UTF_8), versionZero);
    await("media event committed to inbox", () -> inboxCount(versionZeroEvent) == 1);
    publish(MaintenanceTransportTopics.MEDIA, mediaId.toString().getBytes(StandardCharsets.UTF_8), versionZero);
    await("duplicate remains one inbox row", () -> inboxCount(versionZeroEvent) == 1);

    byte[] gap = mediaEnvelope(mediaId, UUID.randomUUID(), 2, "media.media.ready.v1");
    publish(MaintenanceTransportTopics.MEDIA, mediaId.toString().getBytes(StandardCharsets.UTF_8), gap);
    await(
        "aggregate version gap quarantined",
        () ->
            jdbc.queryForObject(
                    "select count(*) from version_gap_quarantine where aggregate_id=? and status='OPEN'",
                    Integer.class,
                    mediaId.toString())
                == 1);

    byte[] invalid = "password=never-publish kafka-canary@example.test".getBytes(StandardCharsets.UTF_8);
    String invalidHash = MaintenanceChecksum.sha256(invalid);
    publish(MaintenanceTransportTopics.MEDIA, mediaId.toString().getBytes(StandardCharsets.UTF_8), invalid);
    await(
        "validation DLT staged",
        () ->
            jdbc.queryForObject(
                    """
                    select count(*) from sanitized_dead_letter
                     where message_sha256=? and failure_code='VALIDATION_REJECTED'
                    """,
                    Integer.class,
                    invalidHash)
                == 1);
    await(
        "validation DLT broker acknowledgement",
        () -> {
          deadLetterRelay.relayOne();
          return "PUBLISHED"
              .equals(
                  jdbc.queryForObject(
                      "select status from sanitized_dead_letter where message_sha256=?",
                      String.class,
                      invalidHash));
        });
    String safeBody =
        new String(
            consume(
                    MaintenanceTransportTopics.SANITIZED_DLT,
                    record ->
                        new String(record.value(), StandardCharsets.UTF_8).contains(invalidHash))
                .getFirst()
                .value(),
            StandardCharsets.UTF_8);
    assertThat(safeBody)
        .contains(invalidHash, "VALIDATION_REJECTED")
        .doesNotContain("password", "never-publish", "kafka-canary@example.test");
  }

  @Test
  @Order(2)
  void sanitizedDltBrokerOutageRetainsRemainingBoundedAttempts() {
    byte[] rejected =
        ("dlt-outage-password=" + UUID.randomUUID() + "@example.test")
            .getBytes(StandardCharsets.UTF_8);
    String hash = MaintenanceChecksum.sha256(rejected);
    deadLetterPublisher.publish(rejected, "VALIDATION_REJECTED", MaintenanceTransportTopics.MEDIA, null);

    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      assertThat(deadLetterRelay.relayOne()).isFalse();
      assertThat(dltState(hash)).isEqualTo("PENDING");
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
    await("Kafka broker recovery for DLT", this::brokerAvailable);
    jdbc.update(
        "update sanitized_dead_letter set next_attempt_at=clock_timestamp() where message_sha256=?",
        hash);
    await("DLT acknowledged after outage", () -> deadLetterRelay.relayOne());
    assertThat(dltState(hash)).isEqualTo("PUBLISHED");
  }

  @Test
  @Order(3)
  void databaseOutageStopsWithoutCommitThenRecoveryRestartsAndReprocesses() throws Exception {
    await("maintenance consumer registered", consumers::registered);
    await("maintenance consumer running", consumers::allRunning);
    byte[] malformed =
        ("db-outage-password=" + UUID.randomUUID() + "@example.test")
            .getBytes(StandardCharsets.UTF_8);
    String hash = MaintenanceChecksum.sha256(malformed);

    POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    dataSource.getHikariPoolMXBean().softEvictConnections();
    try {
      publish(
          MaintenanceTransportTopics.MEDIA,
          UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
          malformed);
      await("consumer stops fail-closed while DLT database is down", () -> !consumers.allRunning());
    } finally {
      POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
      dataSource.getHikariPoolMXBean().softEvictConnections();
    }

    await(
        "PostgreSQL recovery",
        () -> {
          try {
            return Integer.valueOf(1).equals(jdbc.queryForObject("select 1", Integer.class));
          } catch (RuntimeException unavailable) {
            return false;
          }
        });
    consumerRecovery.restartWhenDatabaseIsHealthy();
    await("maintenance consumer restarted", consumers::allRunning);
    await(
        "uncommitted malformed record reprocessed into sanitized DLT",
        () ->
            jdbc.queryForObject(
                    "select count(*) from sanitized_dead_letter where message_sha256=?",
                    Integer.class,
                    hash)
                == 1);
    assertThat(
            jdbc.queryForObject(
                "select safe_body::text from sanitized_dead_letter where message_sha256=?",
                String.class,
                hash))
        .doesNotContain("password", "db-outage", "example.test");
  }

  private UUID insertCatalogOutbox(UUID aggregateId, long version) {
    UUID eventId = UUID.randomUUID();
    UUID correlationId = UUID.randomUUID();
    Instant recordedAt = Instant.now();
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("catalogVersionId", aggregateId.toString());
    payload.put("warehouseId", UUID.randomUUID().toString());
    payload.put("lifecycle", "DRAFT");
    payload.put("sourceSha256", "1".repeat(64));
    payload.put("nodeCount", 0);
    payload.put("linkCount", 0);
    payload.put("validationReportSha256", "2".repeat(64));
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("envelopeVersion", 2);
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", "maintenance.catalog-version.imported.v1");
    envelope.put("eventVersion", 1);
    envelope.put("occurredAt", recordedAt.toString());
    envelope.put("recordedAt", recordedAt.toString());
    envelope.put("producer", "maintenance-service");
    envelope.put("aggregateType", "CATALOG_VERSION");
    envelope.put("aggregateId", aggregateId.toString());
    envelope.put("aggregateVersion", version);
    Map<String, Object> correlation = new LinkedHashMap<>();
    correlation.put("correlationId", correlationId.toString());
    correlation.put("causationId", null);
    envelope.put("correlation", correlation);
    envelope.put("actorRef", null);
    envelope.put("payload", payload);
    String envelopeJson = canonical(write(envelope));
    String envelopeHash =
        MaintenanceChecksum.sha256(envelopeJson.getBytes(StandardCharsets.UTF_8));
    String localHash = MaintenanceChecksum.sha256("{}".getBytes(StandardCharsets.UTF_8));

    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,?,?,clock_timestamp())
        """,
        aggregateId.toString(),
        version,
        eventId);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,actor_ref,payload,payload_sha256,baseline)
        values (?,'CATALOG_VERSION',?,?,'maintenance.catalog-version.imported.v1',1,
          ?,?,?,null,'{}'::jsonb,?,false)
        """,
        eventId,
        aggregateId.toString(),
        version,
        OffsetDateTime.parse(recordedAt.toString()),
        OffsetDateTime.parse(recordedAt.toString()),
        correlationId,
        localHash);
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
          envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'CATALOG_VERSION',?,?,'maintenance.catalog-version.imported.v1',
          'rwms.maintenance.catalog-version.v1',?::jsonb,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        """,
        eventId,
        aggregateId.toString(),
        version,
        envelopeJson,
        envelopeHash);
    return eventId;
  }

  private byte[] mediaEnvelope(UUID mediaId, UUID eventId, long version, String eventType) {
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("envelopeVersion", 2);
    envelope.put("eventId", eventId.toString());
    envelope.put("eventType", eventType);
    envelope.put("eventVersion", 1);
    envelope.put("occurredAt", Instant.now().toString());
    envelope.put("recordedAt", Instant.now().toString());
    envelope.put("producer", "media-service");
    envelope.put("aggregateType", "MEDIA");
    envelope.put("aggregateId", mediaId.toString());
    envelope.put("aggregateVersion", version);
    Map<String, Object> correlation = new LinkedHashMap<>();
    correlation.put("correlationId", UUID.randomUUID().toString());
    correlation.put("causationId", null);
    envelope.put("correlation", correlation);
    envelope.put("actorRef", null);
    envelope.put(
        "payload",
        Map.of(
            "mediaId",
            mediaId.toString(),
            "ownerType",
            "MAINTENANCE_REPAIR",
            "ownerId",
            UUID.randomUUID().toString(),
            "warehouseId",
            UUID.randomUUID().toString(),
            "kind",
            "IMAGE",
            "status",
            "READY",
            "generation",
            0,
            "rotationDegrees",
            0));
    return writeBytes(envelope);
  }

  private String canonical(String json) {
    return jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private byte[] writeBytes(Object value) {
    try {
      return mapper.writeValueAsBytes(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private UUID eventUuid(byte[] body) {
    try {
      return UUID.fromString(mapper.readTree(body).required("eventId").stringValue());
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private String eventId(ConsumerRecord<byte[], byte[]> record) {
    try {
      return mapper.readTree(record.value()).required("eventId").stringValue();
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private int inboxCount(UUID eventId) {
    return jdbc.queryForObject(
        "select count(*) from inbox_message where consumer_group=? and event_id=?",
        Integer.class,
        MaintenanceTransportTopics.CONSUMER_GROUP,
        eventId);
  }

  private String outboxState(UUID eventId) {
    return jdbc.queryForObject(
        "select status from outbox_event where event_id=?", String.class, eventId);
  }

  private String dltState(String hash) {
    return jdbc.queryForObject(
        "select status from sanitized_dead_letter where message_sha256=?", String.class, hash);
  }

  private boolean brokerAvailable() {
    Properties properties = new Properties();
    properties.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (AdminClient admin = AdminClient.create(properties)) {
      admin.describeCluster().nodes().get();
      return true;
    } catch (Exception unavailable) {
      return false;
    }
  }

  private void publish(String topic, byte[] key, byte[] body) throws Exception {
    Properties properties = new Properties();
    properties.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    properties.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    properties.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    properties.put(ProducerConfig.ACKS_CONFIG, "all");
    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(properties)) {
      producer.send(new ProducerRecord<>(topic, key, body)).get();
    }
  }

  private List<ConsumerRecord<byte[], byte[]>> consume(
      String topic, java.util.function.Predicate<ConsumerRecord<byte[], byte[]>> match) {
    Properties properties = new Properties();
    properties.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    properties.put(ConsumerConfig.GROUP_ID_CONFIG, "maintenance-audit-" + UUID.randomUUID());
    properties.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    properties.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    properties.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(properties)) {
      consumer.subscribe(List.of(topic));
      List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
      await(
          "matching Kafka record on " + topic,
          () -> {
            consumer.poll(Duration.ofMillis(250)).forEach(record -> {
              if (match.test(record)) {
                records.add(record);
              }
            });
            return !records.isEmpty();
          });
      return List.copyOf(records);
    }
  }

  private static void await(String description, BooleanSupplier condition) {
    Instant deadline = Instant.now().plusSeconds(45);
    while (Instant.now().isBefore(deadline)) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(200);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for " + description, exception);
      }
    }
    throw new AssertionError("Timed out waiting for " + description);
  }

  @TestConfiguration(proxyBeanMethods = false)
  static class EffectsConfiguration {
    @Bean
    @Primary
    MaintenanceInboundEffects kafkaTestEffects() {
      return (event, correlation) -> {};
    }
  }
}
