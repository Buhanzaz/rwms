package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.AdminClientConfig;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.ByteArrayDeserializer;
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
import tools.jackson.databind.ObjectMapper;

/** Real-broker proof that a business outbox row is acknowledged only after Kafka recovers. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=true",
      "spring.cloud.function.definition=",
      "spring.cloud.stream.kafka.binder.auto-create-topics=true",
      "spring.cloud.stream.kafka.default.producer.sync=true",
      "spring.cloud.stream.kafka.binder.configuration.request.timeout.ms=1000",
      "spring.cloud.stream.kafka.binder.configuration.delivery.timeout.ms=3000",
      "spring.cloud.stream.kafka.binder.configuration.max.block.ms=3000",
      "spring.task.scheduling.enabled=false",
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
class LogisticsKafkaOutboxRecoveryIntegrationTest {
  private static final String TOPIC = "rwms.logistics.return.v1";
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000911");
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000912");
  private static final UUID CORRELATION =
      UUID.fromString("00000000-0000-0000-0000-000000000913");

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

  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsKafkaOutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
  }

  @Test
  void brokerOutageLeavesTheCommittedFactPendingThenRecoveryPublishesAndAcknowledgesIt()
      throws Exception {
    createReturn();
    assertThat(relay.relayOne()).isTrue();

    UUID documentId = createReturn();
    UUID eventId =
        jdbc.queryForObject(
            "select event_id from outbox_event where aggregate_id=?",
            UUID.class,
            documentId.toString());

    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      await("Kafka broker unavailable", () -> !brokerHealthy());
      await(
          "committed outbox fact retained for retry",
          () -> {
            jdbc.update(
                """
                update outbox_event set next_attempt_at=clock_timestamp()
                where event_id=? and status='PENDING'
                """,
                eventId);
            relay.relayOne();
            return "PENDING".equals(outboxStatus(eventId)) && outboxAttempts(eventId) > 0;
          });
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    assertThat(
            jdbc.queryForMap(
                "select status,attempt_count,published_at from outbox_event where event_id=?",
                eventId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 1)
        .containsEntry("published_at", null);

    await("Kafka broker recovery", this::brokerHealthy);
    await(
        "business outbox acknowledgement after broker recovery",
        () -> {
          if ("PUBLISHED".equals(outboxStatus(eventId))) {
            return true;
          }
          jdbc.update(
              """
              update outbox_event set next_attempt_at=clock_timestamp()
              where event_id=? and status='PENDING'
              """,
              eventId);
          relay.relayOne();
          return "PUBLISHED".equals(outboxStatus(eventId));
        });

    assertThat(outboxStatus(eventId)).isEqualTo("PUBLISHED");
    assertThat(outboxAttempts(eventId)).isBetween(1, 3);
    ConsumerRecord<byte[], byte[]> delivered = consume(eventId);
    assertThat(new String(delivered.key(), StandardCharsets.UTF_8))
        .isEqualTo(documentId.toString());
    assertThat(mapper.readTree(delivered.value()).required("eventId").stringValue())
        .isEqualTo(eventId.toString());
  }

  private String outboxStatus(UUID eventId) {
    return jdbc.queryForObject(
        "select status from outbox_event where event_id=?", String.class, eventId);
  }

  private int outboxAttempts(UUID eventId) {
    Integer attempts =
        jdbc.queryForObject(
            "select attempt_count from outbox_event where event_id=?", Integer.class, eventId);
    return attempts == null ? -1 : attempts;
  }

  private UUID createReturn() {
    return documents
        .createReturn(
            SUBJECT,
            UUID.randomUUID(),
            CORRELATION,
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(
                    new ReturnLineRequest(
                        UUID.randomUUID(), 1, "Kafka recovery tenant snapshot"))))
        .response()
        .id();
  }

  private boolean brokerHealthy() {
    Properties configuration = new Properties();
    configuration.put(AdminClientConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(AdminClientConfig.REQUEST_TIMEOUT_MS_CONFIG, 1000);
    configuration.put(AdminClientConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 1000);
    try (AdminClient admin = AdminClient.create(configuration)) {
      return !admin.describeCluster().nodes().get().isEmpty();
    } catch (Exception unavailable) {
      return false;
    }
  }

  private ConsumerRecord<byte[], byte[]> consume(UUID eventId) {
    Properties configuration = new Properties();
    configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(ConsumerConfig.GROUP_ID_CONFIG, "logistics-outbox-audit-" + UUID.randomUUID());
    configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
      consumer.subscribe(List.of(TOPIC));
      List<ConsumerRecord<byte[], byte[]>> records = new ArrayList<>();
      await(
          "business outbox event " + eventId,
          () -> {
            consumer.poll(Duration.ofMillis(250)).forEach(records::add);
            return records.stream().anyMatch(record -> eventId(record).equals(eventId));
          });
      return records.stream().filter(record -> eventId(record).equals(eventId)).findFirst().orElseThrow();
    }
  }

  private UUID eventId(ConsumerRecord<byte[], byte[]> record) {
    try {
      return UUID.fromString(mapper.readTree(record.value()).required("eventId").stringValue());
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Kafka event is not JSON", exception);
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
