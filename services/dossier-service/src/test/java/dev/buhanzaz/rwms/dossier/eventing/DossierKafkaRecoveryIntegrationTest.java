package dev.buhanzaz.rwms.dossier.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.buhanzaz.rwms.dossier.domain.DossierInboxDecision;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxEvent;
import dev.buhanzaz.rwms.dossier.domain.DossierOutboxState;
import dev.buhanzaz.rwms.dossier.repository.DossierActivityRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierInboxRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierPartitionCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSourceFactRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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

/** Real Kafka/PostgreSQL proof for Stage 9 acknowledgement, dedupe and fail-closed recovery. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=true",
      "spring.cloud.stream.kafka.binder.auto-create-topics=true",
      "spring.cloud.stream.kafka.default.producer.sync=true",
      "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=1000",
      "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=3000",
      "spring.cloud.stream.kafka.binder.configuration.max.block.ms=3000",
      "spring.datasource.hikari.connection-timeout=1000",
      "AUTH_ISSUER=http://issuer.invalid",
      "AUTH_AUDIENCE=rwms-services",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DossierKafkaRecoveryIntegrationTest {
  private static final String SOURCE_TOPIC = "rwms.asset.rental-item.v1";
  private static final String OUTBOUND_TOPIC = "rwms.dossier.cabin-activity.v1";

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine").withDatabaseName("dossier_stage9_kafka");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("DOSSIER_DB_URL", POSTGRES::getJdbcUrl);
    registry.add("DOSSIER_DB_USERNAME", POSTGRES::getUsername);
    registry.add("DOSSIER_DB_PASSWORD", POSTGRES::getPassword);
    registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    registry.add(
        "DOSSIER_CURSOR_SECRET", () -> "stage-9-kafka-cursor-secret-at-least-32-bytes");
  }

  @Autowired DossierKafkaConsumerLifecycleRegistry consumers;
  @Autowired DossierKafkaConsumerRecoveryMonitor recovery;
  @Autowired DossierInboxRepository inboxes;
  @Autowired DossierActivityRepository activities;
  @Autowired DossierOutboxEventRepository outbox;
  @Autowired DossierPartitionCheckpointRepository partitions;
  @Autowired DossierSanitizedDeadLetterRepository deadLetters;
  @Autowired DossierSourceFactRepository sourceFacts;
  @Autowired DossierOutboxRelay outboxRelay;
  @Autowired DossierDeadLetterRelay deadLetterRelay;
  @Autowired HikariDataSource dataSource;
  @Autowired ObjectMapper mapper;

  @Test
  void realBrokerAcknowledgementDedupeOutageAndJpaRecoveryRemainLossless() throws Exception {
    await("dossier source consumer registration", consumers::registered);
    await("dossier source consumer running", consumers::allRunning);

    UUID cabinId = UUID.randomUUID();
    UUID firstEventId = UUID.randomUUID();
    byte[] first = asset(firstEventId, cabinId, 0, "asset.rental-item.created.v1");
    publish(SOURCE_TOPIC, cabinId, first);
    await("first inbox fact", () -> inboxDecision(firstEventId) == DossierInboxDecision.PROCESSED);
    publish(SOURCE_TOPIC, cabinId, first);
    await("duplicate source fact", () -> inboxes.findById(firstEventId).isPresent());
    assertThat(inboxes.findAll().stream().filter(value -> value.getEventId().equals(firstEventId)))
        .hasSize(1);
    assertThat(outbox.findAll()).hasSize(1);

    UUID firstOutboxId = outbox.findAll().getFirst().getEventId();
    await(
        "outbound broker acknowledgement",
        () -> {
          outboxRelay.relay();
          return outboxState(firstOutboxId) == DossierOutboxState.PUBLISHED;
        });
    ConsumerRecord<byte[], byte[]> firstPublished =
        consume(OUTBOUND_TOPIC, record -> hasEventId(record, firstOutboxId));
    assertThat(new String(firstPublished.key(), StandardCharsets.UTF_8))
        .isEqualTo(cabinId.toString());
    assertThat(DossierEventHash.sha256(firstPublished.value()))
        .isEqualTo(outbox.findById(firstOutboxId).orElseThrow().getPayloadSha256());

    UUID secondEventId = UUID.randomUUID();
    publish(
        SOURCE_TOPIC,
        cabinId,
        asset(secondEventId, cabinId, 1, "asset.rental-item.passport-changed.v1"));
    await("second pending outbox fact", () -> pendingOutbox(secondEventId) != null);
    UUID secondOutboxId = pendingOutbox(secondEventId).getEventId();

    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      await("Kafka broker unavailable", () -> !brokerHealthy());
      outboxRelay.relay();
      await(
          "unacknowledged outbox remains retryable",
          () -> outboxState(secondOutboxId) == DossierOutboxState.RETRY);
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }
    await("Kafka broker recovery", this::brokerHealthy);
    await(
        "retry is acknowledged after broker recovery",
        () -> {
          outboxRelay.relay();
          return outboxState(secondOutboxId) == DossierOutboxState.PUBLISHED;
        });
    consume(OUTBOUND_TOPIC, record -> hasEventId(record, secondOutboxId));

    UUID outageCabinId = UUID.randomUUID();
    UUID outageEventId = UUID.randomUUID();
    byte[] outageEvent =
        asset(outageEventId, outageCabinId, 0, "asset.rental-item.created.v1");
    long outageOffset;
    POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    dataSource.getHikariPoolMXBean().softEvictConnections();
    try {
      outageOffset = publish(SOURCE_TOPIC, outageCabinId, outageEvent);
      await("consumer stops while PostgreSQL is unavailable", () -> !consumers.allRunning());
    } finally {
      POSTGRES.getDockerClient().unpauseContainerCmd(POSTGRES.getContainerId()).exec();
      dataSource.getHikariPoolMXBean().softEvictConnections();
    }

    await(
        "local JPA recovery",
        () -> {
          try {
            return inboxes.count() >= 2;
          } catch (RuntimeException unavailable) {
            return false;
          }
        });
    recovery.restartWhenDatabaseIsHealthy();
    await("dossier consumer restart", consumers::allRunning);
    await(
        "uncommitted valid record is redelivered",
        () -> inboxDecision(outageEventId) == DossierInboxDecision.PROCESSED);
    assertExactlyOneProjectionEffect(outageEventId, outageCabinId, outageOffset);

    long duplicateOffset = publish(SOURCE_TOPIC, outageCabinId, outageEvent);
    await(
        "duplicate redelivery advances only the source checkpoint",
        () -> partitionOffset() >= duplicateOffset);
    assertExactlyOneProjectionEffect(outageEventId, outageCabinId, outageOffset);

    byte[] malformed =
        ("terminal-dlt-password=" + UUID.randomUUID() + "@example.test")
            .getBytes(StandardCharsets.UTF_8);
    String malformedHash = DossierEventHash.sha256(malformed);
    publish(SOURCE_TOPIC, UUID.randomUUID(), malformed);
    await("malformed source reaches sanitized DLT", () -> deadLetter(malformedHash) != null);
    var failure = deadLetter(malformedHash);
    assertThat(failure.getStatus()).isEqualTo(DossierOutboxState.PENDING);

    await(
        "sanitized DLT broker acknowledgement",
        () -> {
          deadLetterRelay.relay();
          return deadLetter(malformedHash).getStatus() == DossierOutboxState.PUBLISHED;
        });
    String safeBody =
        new String(
            consume(
                    failure.getDestination(),
                    record ->
                        new String(record.value(), StandardCharsets.UTF_8)
                            .contains(malformedHash))
                .value(),
            StandardCharsets.UTF_8);
    assertThat(safeBody)
        .contains(malformedHash, "INVALID_ENVELOPE")
        .doesNotContain("terminal-dlt-password", "example.test");
  }

  private void assertExactlyOneProjectionEffect(
      UUID eventId, UUID cabinId, long sourceOffset) {
    assertThat(inboxes.findAll().stream().filter(value -> value.getEventId().equals(eventId)))
        .singleElement()
        .satisfies(
            value -> assertThat(value.getDecision()).isEqualTo(DossierInboxDecision.PROCESSED));
    assertThat(sourceFacts.findAll().stream().filter(value -> value.getEventId().equals(eventId)))
        .singleElement()
        .satisfies(value -> assertThat(value.getSourceOffset()).isEqualTo(sourceOffset));
    assertThat(activities.findAll().stream().filter(value -> value.getSourceEventId().equals(eventId)))
        .singleElement()
        .satisfies(value -> assertThat(value.getCabinId()).isEqualTo(cabinId));
    assertThat(outbox.findAll().stream().filter(value -> value.getSourceEventId().equals(eventId)))
        .singleElement()
        .satisfies(value -> assertThat(value.getCabinId()).isEqualTo(cabinId));
    assertThat(partitionOffset()).isGreaterThanOrEqualTo(sourceOffset);
  }

  private long partitionOffset() {
    return partitions
        .findByConsumerGroupAndSourceTopicAndSourcePartition(
            DossierSourceTopics.CONSUMER_GROUP, SOURCE_TOPIC, 0)
        .orElseThrow()
        .getLastAcceptedOffset();
  }

  private DossierInboxDecision inboxDecision(UUID eventId) {
    return inboxes.findById(eventId).map(value -> value.getDecision()).orElse(null);
  }

  private DossierOutboxEvent pendingOutbox(UUID sourceEventId) {
    return outbox.findAll().stream()
        .filter(value -> value.getSourceEventId().equals(sourceEventId))
        .findFirst()
        .orElse(null);
  }

  private DossierOutboxState outboxState(UUID eventId) {
    return outbox.findById(eventId).map(DossierOutboxEvent::getStatus).orElse(null);
  }

  private boolean hasEventId(ConsumerRecord<byte[], byte[]> record, UUID eventId) {
    try {
      return eventId.toString().equals(mapper.readTree(record.value()).required("eventId").stringValue());
    } catch (RuntimeException invalidJson) {
      return false;
    }
  }

  private dev.buhanzaz.rwms.dossier.domain.DossierSanitizedDeadLetter deadLetter(
      String messageHash) {
    try {
      return deadLetters.findAll().stream()
          .filter(value -> value.getMessageSha256().equals(messageHash))
          .findFirst()
          .orElse(null);
    } catch (RuntimeException unavailable) {
      return null;
    }
  }

  private static byte[] asset(
      UUID eventId, UUID cabinId, long version, String eventType) {
    return """
        {"envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,"occurredAt":"2026-07-18T10:00:00Z","recordedAt":"2026-07-18T12:00:00Z","producer":"asset-service","aggregateType":"RENTAL_ITEM","aggregateId":"%s","aggregateVersion":%d,"correlation":{"correlationId":"50000000-0000-0000-0000-000000000001","causationId":null},"actorRef":null,"payload":{"rentalItemId":"%s","warehouseId":"30000000-0000-0000-0000-000000000001","status":"AVAILABLE","numberSha256":"aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa"}}
        """
        .formatted(eventId, eventType, cabinId, version, cabinId)
        .getBytes(StandardCharsets.UTF_8);
  }

  private long publish(String topic, UUID key, byte[] body) throws Exception {
    Properties configuration = new Properties();
    configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(configuration)) {
      return producer
          .send(
              new ProducerRecord<>(
                  topic, key.toString().getBytes(StandardCharsets.UTF_8), body))
          .get()
          .offset();
    }
  }

  private ConsumerRecord<byte[], byte[]> consume(
      String topic, Predicate<ConsumerRecord<byte[], byte[]>> matcher) {
    Properties configuration = new Properties();
    configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "dossier-stage9-audit-" + UUID.randomUUID());
    configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
      consumer.subscribe(List.of(topic));
      List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
      await(
          "Kafka record on " + topic,
          () -> {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            return records.stream().anyMatch(matcher);
          });
      return records.stream().filter(matcher).findFirst().orElseThrow();
    }
  }

  private boolean brokerHealthy() {
    Properties configuration = new Properties();
    configuration.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 500);
    configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 500);
    try (AdminClient admin = AdminClient.create(configuration)) {
      return !admin.describeCluster().nodes().get().isEmpty();
    } catch (Exception unavailable) {
      return false;
    }
  }

  private static void await(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(60).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) {
        return;
      }
      try {
        Thread.sleep(100);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Interrupted while waiting for " + description, exception);
      }
    }
    throw new AssertionError("Timed out waiting for " + description);
  }
}
