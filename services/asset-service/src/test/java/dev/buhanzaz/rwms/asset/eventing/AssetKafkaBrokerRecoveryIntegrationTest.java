package dev.buhanzaz.rwms.asset.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.service.AssetService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
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

/** Real Kafka proof for outbox acknowledgement, duplicate/gap inbox recovery and sanitized DLT delivery. */
@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=true",
    "spring.cloud.stream.kafka.binder.auto-create-topics=true",
    "spring.cloud.stream.kafka.default.producer.sync=true",
    "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=1000",
    "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=3000",
    "spring.cloud.stream.kafka.binder.configuration.max.block.ms=3000",
    "spring.task.scheduling.enabled=false",
    "rwms.asset.eventing.outbox.relay-delay=1h",
    "rwms.asset.eventing.outbox.relay-initial-delay=1h"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class AssetKafkaBrokerRecoveryIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  @Container static final KafkaContainer KAFKA = new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @Autowired AssetService service;
  @Autowired AssetKafkaOutboxRelay outbox;
  @Autowired AssetSanitizedDltRelay dlt;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void brokerOutageRecoveryPreservesOutboxAckDuplicateGapAndSanitizedDltInvariants() throws Exception {
    jdbc.update("""
        update outbox_event set next_attempt_at=clock_timestamp()+interval '1 day'
        where status='PENDING'
        """);
    UUID subjectId = UUID.randomUUID();
    var created = service.createRentalItem(
        subjectId,
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            UUID.randomUUID(),
            "kafka-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(),
            false,
            java.util.Map.of(),
            List.of()))
        .response();
    UUID eventId = jdbc.queryForObject(
        "select event_id from outbox_event where aggregate_type='RENTAL_ITEM' and aggregate_id=?", UUID.class,
        created.id().toString());
    assertThat(eventId).isNotNull();

    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      await("Kafka broker becomes unavailable", () -> !brokerAvailable());
      assertThat(outbox.relayOne()).isFalse();
      assertThat(jdbc.queryForObject("select status from outbox_event where event_id=?", String.class, eventId)).isEqualTo("PENDING");
      assertThat(jdbc.queryForObject("select attempt_count from outbox_event where event_id=?", Integer.class, eventId)).isEqualTo(1);
      assertThat(jdbc.queryForObject("select published_at from outbox_event where event_id=?", java.time.OffsetDateTime.class, eventId)).isNull();
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    await("Kafka broker becomes available", this::brokerAvailable);
    jdbc.update("update outbox_event set next_attempt_at=clock_timestamp() where event_id=?", eventId);
    await("outbox acknowledgement after broker recovery", () -> outbox.relayOne()
        && "PUBLISHED".equals(jdbc.queryForObject("select status from outbox_event where event_id=?", String.class, eventId)));
    assertThat(jdbc.queryForObject("select published_at from outbox_event where event_id=?", java.time.OffsetDateTime.class, eventId)).isNotNull();

    byte[] envelope = jdbc.queryForObject("select envelope_body::text from outbox_event where event_id=?", String.class, eventId)
        .getBytes(StandardCharsets.UTF_8);
    assertThat(envelope).isNotNull();
    // The production consumer uses Kafka's default 45-second group session.
    // Allow a full broker pause/unpause rebalance plus bounded transport retry.
    await("self-consumed committed fact", Duration.ofSeconds(90), () -> inboxCount(eventId) == 1);
    publish(AssetAggregateType.RENTAL_ITEM.topic(), created.id().toString().getBytes(StandardCharsets.UTF_8), envelope);
    await("duplicate delivery remains one inbox effect", () -> inboxCount(eventId) == 1);

    JsonNode gap = mapper.readTree(envelope);
    ((tools.jackson.databind.node.ObjectNode) gap).put("eventId", UUID.randomUUID().toString());
    ((tools.jackson.databind.node.ObjectNode) gap).put("aggregateVersion", 2);
    publish(AssetAggregateType.RENTAL_ITEM.topic(), created.id().toString().getBytes(StandardCharsets.UTF_8), mapper.writeValueAsBytes(gap));
    await("aggregate version gap quarantine", () -> jdbc.queryForObject(
        "select count(*) from version_gap_quarantine where aggregate_id=? and status='OPEN'", Integer.class,
        created.id().toString()) == 1);
    assertThat(jdbc.queryForObject(
        "select blocked from consumer_aggregate_checkpoint where consumer_group=? and aggregate_type='RENTAL_ITEM' and aggregate_id=?",
        Boolean.class, AssetAggregateType.CONSUMER_GROUP, created.id().toString())).isTrue();

    byte[] invalid = "private@example.test password=never-publish".getBytes(StandardCharsets.UTF_8);
    publish(AssetAggregateType.RENTAL_ITEM.topic(), created.id().toString().getBytes(StandardCharsets.UTF_8), invalid);
    await("sanitized validation DLT staged", () -> jdbc.queryForObject(
        "select count(*) from sanitized_dead_letter where failure_code='VALIDATION_REJECTED'", Integer.class) == 1);
    await("sanitized validation DLT acknowledgement", () -> {
      dlt.relayOne();
      return jdbc.queryForObject(
          "select count(*) from sanitized_dead_letter where failure_code='VALIDATION_REJECTED' and status='PUBLISHED'", Integer.class) == 1;
    });
    String safeDlt = consume(AssetAggregateType.SANITIZED_DLT_TOPIC, 2).stream()
        .map(ConsumerRecord::value)
        .map(value -> new String(value, StandardCharsets.UTF_8))
        .filter(value -> value.contains("VALIDATION_REJECTED"))
        .findFirst()
        .orElseThrow();
    assertThat(safeDlt).doesNotContain("private@example.test", "password=never-publish").contains("VALIDATION_REJECTED");
  }

  private int inboxCount(UUID eventId) {
    Integer value = jdbc.queryForObject("select count(*) from inbox_message where consumer_group=? and event_id=?", Integer.class,
        AssetAggregateType.CONSUMER_GROUP, eventId);
    return value == null ? 0 : value;
  }

  private boolean brokerAvailable() {
    Properties configuration = new Properties();
    configuration.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    try (AdminClient admin = AdminClient.create(configuration)) {
      admin.describeCluster().nodes().get(5, TimeUnit.SECONDS);
      return true;
    } catch (Exception exception) {
      return false;
    }
  }

  private void publish(String topic, byte[] key, byte[] body) throws Exception {
    Properties configuration = new Properties();
    configuration.put(ProducerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ProducerConfig.KEY_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    configuration.put(ProducerConfig.VALUE_SERIALIZER_CLASS_CONFIG, ByteArraySerializer.class);
    try (KafkaProducer<byte[], byte[]> producer = new KafkaProducer<>(configuration)) {
      producer.send(new ProducerRecord<>(topic, key, body)).get();
    }
  }

  private List<ConsumerRecord<byte[], byte[]>> consume(String topic, int expected) {
    Properties configuration = new Properties();
    configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "asset-recovery-audit-" + UUID.randomUUID());
    configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
      consumer.subscribe(List.of(topic));
      java.util.ArrayList<ConsumerRecord<byte[], byte[]>> records = new java.util.ArrayList<>();
      await("Kafka record on " + topic, () -> {
        consumer.poll(Duration.ofMillis(250)).forEach(records::add);
        return records.size() >= expected;
      });
      return List.copyOf(records);
    }
  }

  private static void await(String description, BooleanSupplier condition) {
    await(description, Duration.ofSeconds(30), condition);
  }

  private static void await(String description, Duration timeout, BooleanSupplier condition) {
    long deadline = System.nanoTime() + timeout.toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
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
