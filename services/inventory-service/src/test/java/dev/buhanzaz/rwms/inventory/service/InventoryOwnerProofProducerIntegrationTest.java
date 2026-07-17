package dev.buhanzaz.rwms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.InventoryServiceApplication;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.eventing.InventoryDeadLetterStore;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.eventing.InventoryOutboxProperties;
import dev.buhanzaz.rwms.inventory.eventing.InventoryOutboxRelay;
import dev.buhanzaz.rwms.inventory.eventing.InventoryOutboxStore;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cloud.stream.function.StreamBridge;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.Message;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryOwnerProofProducerIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final String SESSION_TOPIC = "rwms.inventory.session.v1";
  private static final String ACTOR =
      "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
          + "\"principalType\":\"USER\",\"profileRevision\":null}";

  static {
    POSTGRES.start();
  }

  @Autowired InventoryApplicationService service;
  @Autowired InventoryEventStore events;
  @Autowired InventoryOutboxStore outbox;
  @Autowired InventoryDeadLetterStore deadLetters;
  @Autowired InventoryFindingRepository findings;
  @Autowired InventorySessionRepository sessions;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

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
        truncate table inventory_session,domain_event,event_stream_head,outbox_event,
          sanitized_dead_letter restart identity cascade
        """);
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void emitsExactSharedFindingSequenceAndRelaysCanonicalBytesWithFindingKey() throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventorySession session = sessions.findById(inventoryId).orElseThrow();
    InventoryFinding finding =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                UUID.randomUUID(),
                0L,
                "AB-12",
                "AB12",
                ReconciliationState.MATCHED,
                ACTOR));

    service.appendFindingFacts(finding, session, null, "inventory.finding.added.v1");
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.EXPLICIT_EMPTY,
        "{}",
        ObservationPresence.ABSENT,
        null,
        null,
        ACTOR);
    finding = findings.saveAndFlush(finding);
    service.appendFindingFacts(finding, session, null, "inventory.finding.inspection-saved.v1");
    finding.transitionOwnerProof(false);
    finding = findings.saveAndFlush(finding);
    service.appendOwnerProof(finding, warehouseId, null);

    List<InventoryEventStore.StoredEvent> stream = events.readStream("FINDING", finding.getId());
    assertThat(stream)
        .extracting(
            InventoryEventStore.StoredEvent::aggregateVersion,
            InventoryEventStore.StoredEvent::eventType)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(0L, "inventory.finding.added.v1"),
            org.assertj.core.groups.Tuple.tuple(1L, "inventory.finding.owner-proof.v1"),
            org.assertj.core.groups.Tuple.tuple(2L, "inventory.finding.inspection-saved.v1"),
            org.assertj.core.groups.Tuple.tuple(3L, "inventory.finding.owner-proof.v1"),
            org.assertj.core.groups.Tuple.tuple(4L, "inventory.finding.owner-proof.v1"));
    assertOwnerProof(stream.get(1).payload(), finding.getId(), warehouseId, 0, true);
    assertThat(stream.get(3).payload()).isEqualTo(stream.get(1).payload());
    assertOwnerProof(stream.get(4).payload(), finding.getId(), warehouseId, 1, false);

    List<EnvelopeRow> expected =
        jdbc.query(
            """
            select aggregate_version,event_type,envelope_body::text
              from outbox_event where aggregate_type='FINDING' and aggregate_id=?
             order by aggregate_version
            """,
            (resultSet, rowNumber) ->
                new EnvelopeRow(
                    resultSet.getLong("aggregate_version"),
                    resultSet.getString("event_type"),
                    resultSet.getString("envelope_body")),
            finding.getId().toString());
    StreamBridge bridge = mock(StreamBridge.class);
    when(bridge.send(anyString(), any(Message.class))).thenReturn(true);
    InventoryOutboxRelay relay =
        new InventoryOutboxRelay(
            outbox,
            new InventoryOutboxProperties("owner-proof-test", Duration.ofSeconds(30)),
            deadLetters,
            bridge);
    for (int index = 0; index < expected.size(); index++) {
      assertThat(relay.relayOne()).isTrue();
    }

    ArgumentCaptor<String> destinations = ArgumentCaptor.forClass(String.class);
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Message<byte[]>> messages =
        (ArgumentCaptor<Message<byte[]>>) (ArgumentCaptor<?>) ArgumentCaptor.forClass(Message.class);
    verify(bridge, times(expected.size())).send(destinations.capture(), messages.capture());
    assertThat(destinations.getAllValues()).containsOnly(SESSION_TOPIC);
    for (int index = 0; index < expected.size(); index++) {
      EnvelopeRow row = expected.get(index);
      Message<byte[]> message = messages.getAllValues().get(index);
      assertThat(message.getPayload()).isEqualTo(row.body().getBytes(StandardCharsets.UTF_8));
      assertThat(message.getHeaders().get(KafkaHeaders.KEY, byte[].class))
          .isEqualTo(finding.getId().toString().getBytes(StandardCharsets.UTF_8));
      JsonNode envelope = mapper.readTree(message.getPayload());
      assertThat(envelope.required("aggregateType").asText()).isEqualTo("FINDING");
      assertThat(envelope.required("aggregateId").asText()).isEqualTo(finding.getId().toString());
      assertThat(envelope.required("aggregateVersion").asLong()).isEqualTo(row.version());
      assertThat(envelope.required("eventType").asText()).isEqualTo(row.eventType());
    }
  }

  private void seedSession(UUID inventoryId, UUID warehouseId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    UUID operationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,?::jsonb,?,?,?)
        """,
        inventoryId,
        warehouseId,
        operationId,
        operationId,
        "0".repeat(64),
        "1".repeat(64),
        UUID.randomUUID(),
        ACTOR,
        now,
        now,
        now);
  }

  private void assertOwnerProof(
      JsonNode payload,
      UUID findingId,
      UUID warehouseId,
      long ownerRevision,
      boolean active) {
    assertThat(payload.size()).isEqualTo(5);
    assertThat(payload.required("ownerType").asText()).isEqualTo("INVENTORY_FINDING");
    assertThat(payload.required("ownerId").asText()).isEqualTo(findingId.toString());
    assertThat(payload.required("warehouseId").asText()).isEqualTo(warehouseId.toString());
    assertThat(payload.required("ownerRevision").asLong()).isEqualTo(ownerRevision);
    assertThat(payload.required("active").asBoolean()).isEqualTo(active);
  }

  private record EnvelopeRow(long version, String eventType, String body) {}
}
