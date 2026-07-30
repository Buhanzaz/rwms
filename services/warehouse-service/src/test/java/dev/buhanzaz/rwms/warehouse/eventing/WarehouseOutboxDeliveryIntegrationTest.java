package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaOutboundEventPublisher;
import dev.buhanzaz.rwms.warehouse.WarehouseServiceApplication;
import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(classes = WarehouseServiceApplication.class)
@ActiveProfiles({"dev", "test"})
class WarehouseOutboxDeliveryIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired WarehouseService service;
  @Autowired WarehouseKafkaOutboxStore store;
  @Autowired WarehouseOutboxProperties properties;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper objectMapper;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("rwms.platform.kafka.enabled", () -> "false");
    properties.add("spring.security.oauth2.resourceserver.jwt.jwk-set-uri", () -> "http://127.0.0.1:65535/jwks");
  }

  @BeforeEach
  void clearServiceOwnedTestRows() {
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from outbox_event");
    jdbc.update(
        "delete from warehouse where id not in ('00000000-0000-0000-0000-000000000001', '00000000-0000-0000-0000-000000000002')");
    properties.setInstanceId("warehouse-outbox-test");
  }

  @Test
  void relayPreservesPerWarehouseOrderAndOnlyPublishesApprovedSanitizedPayload() throws Exception {
    WarehouseResponse created = create("Sensitive warehouse name");
    WarehouseResponse changed =
        service.replace(
            created.id(),
            new ReplaceWarehouseRequest(
                created.version(), "Changed name", "Москва", "hidden address", "Europe/Moscow", true, 3));
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    WarehouseKafkaOutboxRelay relay = new WarehouseKafkaOutboxRelay(store, properties, publisher);

    assertThat(relay.relayOne()).isTrue();
    assertThat(relay.relayOne()).isTrue();

    ArgumentCaptor<byte[]> messages = ArgumentCaptor.forClass(byte[].class);
    verify(publisher, times(2))
        .publishSerializedV2(eq("rwms.warehouse.warehouse.v1"), messages.capture());
    JsonNode first = objectMapper.readTree(messages.getAllValues().getFirst());
    JsonNode second = objectMapper.readTree(messages.getAllValues().get(1));
    assertThat(first.get("aggregateId").stringValue()).isEqualTo(created.id().toString());
    assertThat(first.get("aggregateVersion").longValue()).isEqualTo(created.version());
    assertThat(second.get("aggregateVersion").longValue()).isEqualTo(changed.version());
    assertThat(first.get("eventType").stringValue()).isEqualTo(WarehouseEventType.CREATED.value());
    assertThat(second.get("eventType").stringValue()).isEqualTo(WarehouseEventType.CHANGED.value());
    assertThat(fields(first.get("payload")))
        .containsExactlyInAnyOrder("warehouseId", "timeZone", "active", "sortOrder");
    assertThat(first.toString()).doesNotContain("Sensitive warehouse name", "hidden address", "Москва");
    assertThat(
            jdbc.queryForList(
                "select status from outbox_event where aggregate_id=? order by aggregate_version",
                String.class,
                created.id().toString()))
        .containsExactly("PUBLISHED", "PUBLISHED");
  }

  @Test
  void boundedFailureMovesTheEventToDltAndOperatorRequeueIsCompareAndSet() {
    WarehouseResponse created = create("Retry warehouse");
    UUID eventId =
        jdbc.queryForObject(
            "select event_id from outbox_event where aggregate_id=?", UUID.class, created.id().toString());
    RwmsKafkaOutboundEventPublisher publisher = mock(RwmsKafkaOutboundEventPublisher.class);
    doThrow(new IllegalStateException("broker unavailable"))
        .when(publisher)
        .publishSerializedV2(eq("rwms.warehouse.warehouse.v1"), any(byte[].class));
    WarehouseKafkaOutboxRelay relay = new WarehouseKafkaOutboxRelay(store, properties, publisher);

    for (int attempt = 0; attempt < 4; attempt++) {
      jdbc.update("update outbox_event set next_attempt_at=clock_timestamp() where event_id=?", eventId);
      assertThat(relay.relayOne()).isFalse();
    }

    assertThat(jdbc.queryForObject("select status from outbox_event where event_id=?", String.class, eventId))
        .isEqualTo("DLT");
    assertThat(store.requeue(eventId, 4)).isTrue();
    assertThat(store.requeue(eventId, 4)).isFalse();
    assertThat(jdbc.queryForObject("select status from outbox_event where event_id=?", String.class, eventId))
        .isEqualTo("PENDING");
  }

  private WarehouseResponse create(String name) {
    return service
        .create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateWarehouseRequest(name, "Москва", "hidden address", "Europe/Moscow", null))
        .response();
  }

  private static Set<String> fields(JsonNode node) {
    return new HashSet<>(node.propertyNames());
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }
}
