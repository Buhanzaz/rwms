package dev.buhanzaz.rwms.logistics.inquiry.eventing;


import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsTransportTopics;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
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
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.kafka.KafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;
import tools.jackson.databind.ObjectMapper;

/**
 * Real Kafka/PostgreSQL proof that committed rental-inquiry work survives an outage and retains one
 * stable dedupe identity across at-least-once delivery.
 */
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
      "rwms.logistics.rental-inquiry.outbox-delay=1h",
      "rwms.logistics.rental-inquiry.outbox-initial-delay=1h",
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
class RentalInquiryKafkaRecoveryIntegrationTest {
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

  @Autowired RentalInquiryBookedOutboxStore store;
  @Autowired RentalInquiryBookedOutboxRelay relay;
  @Autowired JdbcTemplate jdbc;
  @Autowired TransactionTemplate transactions;
  @Autowired ObjectMapper mapper;

  @BeforeEach
  void resetOutbox() {
    jdbc.execute("truncate table rental_inquiry_outbox");
  }

  @Test
  void committedEventSurvivesOutageWithStableAtLeastOnceDedupeIdentity() {
    UUID warmup = appendPendingEvent().eventId();
    relay.relay();
    assertThat(status(warmup)).isEqualTo("PUBLISHED");
    assertThat(consume(warmup, Duration.ofSeconds(15))).hasSize(1);

    AppendedEvent pending = appendPendingEvent();
    KAFKA.getDockerClient().pauseContainerCmd(KAFKA.getContainerId()).exec();
    try {
      await("Kafka broker unavailable", () -> !brokerHealthy());
      try {
        relay.relay();
      } catch (RuntimeException unavailable) {
        // The transactional relay deliberately leaves the committed row pending on broker failure.
      }
      assertThat(status(pending.eventId())).isEqualTo("PENDING");
      assertThat(attempts(pending.eventId())).isBetween(0, 1);
    } finally {
      KAFKA.getDockerClient().unpauseContainerCmd(KAFKA.getContainerId()).exec();
    }

    await("Kafka broker recovery", this::brokerHealthy);
    jdbc.update(
        "update rental_inquiry_outbox set next_attempt_at=clock_timestamp() where event_id=?",
        pending.eventId());
    await(
        "rental-inquiry outbox acknowledgement",
        () -> {
          if ("PUBLISHED".equals(status(pending.eventId()))) {
            return true;
          }
          relay.relay();
          return "PUBLISHED".equals(status(pending.eventId()));
        });

    List<ConsumerRecord<byte[], byte[]>> delivered =
        consume(pending.eventId(), Duration.ofSeconds(8));
    assertThat(delivered).hasSizeBetween(1, 2);
    String persistedPayload = persistedPayload(pending.eventId());
    byte[] expectedKey = pending.conversationId().toString().getBytes(StandardCharsets.UTF_8);
    byte[] expectedPayload = persistedPayload.getBytes(StandardCharsets.UTF_8);
    assertThat(delivered)
        .allSatisfy(
            record -> {
              assertThat(record.key()).containsExactly(expectedKey);
              assertThat(record.value()).containsExactly(expectedPayload);
              assertThat(eventId(record)).isEqualTo(pending.eventId());
            });
    assertThat(publishedRows(pending.eventId())).isEqualTo(1);

    List<String> deliveredOffsets = recordOffsets(delivered);
    relay.relay();
    List<ConsumerRecord<byte[], byte[]>> afterPublishedNoOp =
        consume(pending.eventId(), Duration.ofSeconds(8));
    assertThat(recordOffsets(afterPublishedNoOp)).containsExactlyElementsOf(deliveredOffsets);
  }

  private AppendedEvent appendPendingEvent() {
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
    UUID eventId =
        jdbc.queryForObject(
            "select event_id from rental_inquiry_outbox where order_id=?", UUID.class, orderId);
    return new AppendedEvent(eventId, conversationId);
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

  private String status(UUID eventId) {
    return jdbc.queryForObject(
        "select status from rental_inquiry_outbox where event_id=?", String.class, eventId);
  }

  private int attempts(UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "select attempt_count from rental_inquiry_outbox where event_id=?",
            Integer.class,
            eventId);
    return count == null ? -1 : count;
  }

  private int publishedRows(UUID eventId) {
    Integer count =
        jdbc.queryForObject(
            "select count(*) from rental_inquiry_outbox where event_id=? and status='PUBLISHED'",
            Integer.class,
            eventId);
    return count == null ? 0 : count;
  }

  private String persistedPayload(UUID eventId) {
    return jdbc.queryForObject(
        "select payload::text from rental_inquiry_outbox where event_id=?", String.class, eventId);
  }

  private static List<String> recordOffsets(List<ConsumerRecord<byte[], byte[]>> records) {
    return records.stream().map(record -> record.partition() + ":" + record.offset()).toList();
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

  private List<ConsumerRecord<byte[], byte[]>> consume(UUID eventId, Duration timeout) {
    Properties configuration = new Properties();
    configuration.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, KAFKA.getBootstrapServers());
    configuration.put(
        ConsumerConfig.GROUP_ID_CONFIG, "rental-inquiry-outbox-audit-" + UUID.randomUUID());
    configuration.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
    configuration.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    configuration.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, ByteArrayDeserializer.class);
    try (KafkaConsumer<byte[], byte[]> consumer = new KafkaConsumer<>(configuration)) {
      consumer.subscribe(List.of(LogisticsTransportTopics.RENTAL_INQUIRY));
      List<ConsumerRecord<byte[], byte[]>> matches = new ArrayList<>();
      long deadline = System.nanoTime() + timeout.toNanos();
      while (System.nanoTime() < deadline) {
        consumer
            .poll(Duration.ofMillis(250))
            .forEach(
                record -> {
                  if (eventId.equals(eventId(record))) {
                    matches.add(record);
                  }
                });
      }
      return matches;
    }
  }

  private UUID eventId(ConsumerRecord<byte[], byte[]> record) {
    try {
      return UUID.fromString(mapper.readTree(record.value()).required("eventId").stringValue());
    } catch (RuntimeException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Rental-inquiry Kafka event is not JSON", exception);
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

  /** IDs needed to verify the durable row and Kafka record without retaining a domain payload. */
  private record AppendedEvent(UUID eventId, UUID conversationId) {}
}
