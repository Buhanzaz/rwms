package dev.buhanzaz.rwms.logistics.eventing.inbound;

import static org.assertj.core.api.Assertions.assertThat;

import com.zaxxer.hikari.HikariDataSource;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.eventing.LogisticsSanitizedDltRelay;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import java.util.function.Predicate;
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

/** Real-broker proof that inbound logistics facts remain deduplicated, replayable and sanitized. */
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
      "spring.datasource.hikari.connection-timeout=1000",
      "rwms.logistics.eventing.outbox.relay-delay=1h",
      "rwms.logistics.eventing.outbox.relay-initial-delay=1h",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "AUTH_ISSUER=http://issuer.invalid",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class LogisticsKafkaInboundRecoveryIntegrationTest {
  @Container
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Container
  static final KafkaContainer KAFKA =
      new KafkaContainer(DockerImageName.parse("apache/kafka:4.3.1"));

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.cloud.stream.kafka.binder.brokers", KAFKA::getBootstrapServers);
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired HikariDataSource dataSource;
  @Autowired LogisticsSanitizedDltRelay deadLetters;
  @Autowired LogisticsKafkaConsumerLifecycleRegistry consumers;
  @Autowired LogisticsKafkaConsumerRecoveryMonitor recovery;

  @Test
  @Order(1)
  void brokerDeliveryProvesDedupeGapRetryAndHashOnlyDlt() throws Exception {
    await("logistics inbound consumer registration", consumers::registered);
    await("logistics inbound consumer running", consumers::allRunning);

    UUID assetId = UUID.randomUUID();
    UUID deliveredEvent = UUID.randomUUID();
    byte[] delivered =
        LogisticsInboundEnvelopeValidatorTest.rentalEvent(deliveredEvent, assetId, 0, "FREE");
    publish(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        assetId.toString().getBytes(StandardCharsets.UTF_8),
        delivered);
    await("committed logistics inbox fact", () -> "PROCESSED".equals(inboxStatus(deliveredEvent)));
    publish(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        assetId.toString().getBytes(StandardCharsets.UTF_8),
        delivered);
    await("duplicate source fact remains harmless", () -> inboxCount(deliveredEvent) == 1);

    UUID gapEvent = UUID.randomUUID();
    byte[] gap =
        LogisticsInboundEnvelopeValidatorTest.rentalEvent(gapEvent, assetId, 2, "IN_TRANSFER");
    publish(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        assetId.toString().getBytes(StandardCharsets.UTF_8),
        gap);
    await(
        "aggregate version gap quarantine",
        () ->
            jdbc.queryForObject(
                    "select count(*) from version_gap_quarantine where received_event_id=? and status='OPEN'",
                    Integer.class,
                    gapEvent)
                == 1);
    assertThat(inboxStatus(gapEvent)).isEqualTo("QUARANTINED");

    byte[] invalid =
        ("password=never-publish logistics-kafka-" + UUID.randomUUID() + "@example.test")
            .getBytes(StandardCharsets.UTF_8);
    String invalidHash = LogisticsEventStore.sha256(invalid);
    publish(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
        invalid);
    await("sanitized validation DLT", () -> "PENDING".equals(dltStatus(invalidHash)));
    publishDltAndAssertSanitized(invalidHash, invalid);

    UUID retryAsset = UUID.randomUUID();
    UUID retryEvent = UUID.randomUUID();
    byte[] retry =
        LogisticsInboundEnvelopeValidatorTest.rentalEvent(retryEvent, retryAsset, 0, "FREE");
    jdbc.execute(
        """
        create or replace function fail_logistics_inbound_observation() returns trigger language plpgsql
        as $$ begin raise exception 'forced logistics inbound effect failure'; end $$
        """);
    jdbc.execute(
        """
        create trigger fail_logistics_inbound_observation before insert on logistics_inbound_observation
        for each row execute function fail_logistics_inbound_observation()
        """);
    try {
      publish(
          LogisticsInboundTransportTopics.RENTAL_ITEM,
          retryAsset.toString().getBytes(StandardCharsets.UTF_8),
          retry);
      await("bounded processing retries reach a durable DLT", () -> "DLT".equals(inboxStatus(retryEvent)));
      assertThat(inboxAttempts(retryEvent)).isEqualTo(3);
      String retryHash = LogisticsEventStore.sha256(retry);
      await("processing failure DLT", () -> "PENDING".equals(dltStatus(retryHash)));
      publishDltAndAssertSanitized(retryHash, retry);
    } finally {
      jdbc.execute("drop trigger fail_logistics_inbound_observation on logistics_inbound_observation");
      jdbc.execute("drop function fail_logistics_inbound_observation()");
    }

    UUID recoveredAsset = UUID.randomUUID();
    UUID recoveredEvent = UUID.randomUUID();
    publish(
        LogisticsInboundTransportTopics.RENTAL_ITEM,
        recoveredAsset.toString().getBytes(StandardCharsets.UTF_8),
        LogisticsInboundEnvelopeValidatorTest.rentalEvent(recoveredEvent, recoveredAsset, 0, "FREE"));
    await(
        "consumer remains available after a bounded DLT",
        () -> "PROCESSED".equals(inboxStatus(recoveredEvent)));
  }

  @Test
  @Order(2)
  void databaseOutageStopsTheConsumerUntilLocalRecoveryThenReprocessesTheRecord() throws Exception {
    await("logistics inbound consumer running before database outage", consumers::allRunning);
    byte[] malformed =
        ("database-outage-password=" + UUID.randomUUID() + "@example.test")
            .getBytes(StandardCharsets.UTF_8);
    String messageHash = LogisticsEventStore.sha256(malformed);

    POSTGRES.getDockerClient().pauseContainerCmd(POSTGRES.getContainerId()).exec();
    dataSource.getHikariPoolMXBean().softEvictConnections();
    try {
      publish(
          LogisticsInboundTransportTopics.RENTAL_ITEM,
          UUID.randomUUID().toString().getBytes(StandardCharsets.UTF_8),
          malformed);
      await("consumer stops fail-closed while PostgreSQL is unavailable", () -> !consumers.allRunning());
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
    recovery.restartWhenDatabaseIsHealthy();
    await("logistics inbound consumer restarted", consumers::allRunning);
    await("uncommitted source record reprocessed", () -> "PENDING".equals(dltStatus(messageHash)));
    String safeBody =
        jdbc.queryForObject(
            "select safe_body::text from sanitized_dead_letter where message_sha256=?",
            String.class,
            messageHash);
    assertThat(safeBody)
        .contains(messageHash, "VALIDATION_REJECTED")
        .doesNotContain("database-outage-password", "example.test");
  }

  private void publishDltAndAssertSanitized(String messageHash, byte[] raw) throws Exception {
    await(
        "sanitized DLT broker acknowledgement",
        () -> {
          deadLetters.relayOne();
          return "PUBLISHED".equals(dltStatus(messageHash));
        });
    String safeBody =
        new String(
            consume(
                    LogisticsInboundTransportTopics.SANITIZED_DLT,
                    record ->
                        new String(record.value(), StandardCharsets.UTF_8).contains(messageHash))
                .value(),
            StandardCharsets.UTF_8);
    assertThat(safeBody)
        .contains(messageHash)
        .doesNotContain(new String(raw, StandardCharsets.UTF_8));
  }

  private int inboxCount(UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from inbox_message where consumer_group=? and event_id=?",
            Integer.class,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            eventId);
    return count == null ? 0 : count;
  }

  private String inboxStatus(UUID eventId) {
    return jdbc
        .query(
            "select status from inbox_message where consumer_group=? and event_id=?",
            (result, row) -> result.getString(1),
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            eventId)
        .stream()
        .findFirst()
        .orElse(null);
  }

  private int inboxAttempts(UUID eventId) {
    Integer attempts =
        jdbc.queryForObject(
            "select attempt_count from inbox_message where consumer_group=? and event_id=?",
            Integer.class,
            LogisticsInboundTransportTopics.CONSUMER_GROUP,
            eventId);
    return attempts == null ? -1 : attempts;
  }

  private String dltStatus(String messageHash) {
    return jdbc
        .query(
            "select status from sanitized_dead_letter where message_sha256=?",
            (result, row) -> result.getString(1),
            messageHash)
        .stream()
        .findFirst()
        .orElse(null);
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

  private ConsumerRecord<byte[], byte[]> consume(
      String topic, Predicate<ConsumerRecord<byte[], byte[]>> matcher) {
    Properties configuration = new Properties();
    configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "logistics-inbound-audit-" + UUID.randomUUID());
    configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
      consumer.subscribe(List.of(topic));
      java.util.ArrayList<ConsumerRecord<byte[], byte[]>> records = new java.util.ArrayList<>();
      await(
          "Kafka record on " + topic,
          () -> {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            return records.stream().anyMatch(matcher);
          });
      return records.stream().filter(matcher).findFirst().orElseThrow();
    }
  }

  private static void await(String description, BooleanSupplier condition) {
    long deadline = System.nanoTime() + Duration.ofSeconds(45).toNanos();
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
