package dev.buhanzaz.rwms.inventory.service;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FrozenStatistics;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import dev.buhanzaz.rwms.inventory.domain.InventorySession;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

class InventorySessionFactPayloadTest {
  private static final com.fasterxml.jackson.databind.ObjectMapper CONTRACT_JSON =
      new com.fasterxml.jackson.databind.ObjectMapper();
  private static final JsonSchemaFactory SCHEMAS =
      JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012);
  private final ObjectMapper mapper = new ObjectMapper();
  private final InventorySessionFactPayload payloadBuilder = new InventorySessionFactPayload(mapper);

  @Test
  void preservesTerminalAndLineFreeStatisticsFacts() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    InventorySession session = Mockito.mock(InventorySession.class);
    when(session.getId()).thenReturn(inventoryId);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(session.getRevision()).thenReturn(9L);
    when(session.getLifecycle()).thenReturn(SessionLifecycle.COMPLETED);
    when(session.getBusinessDate()).thenReturn(LocalDate.of(2026, 9, 6));
    when(session.getExpectedPopulationCount()).thenReturn(4);
    when(session.getCompletedAt()).thenReturn(completedAt);
    FrozenStatistics statistics =
        new FrozenStatistics(
            4, 3, 1, 2, 1, 1, 0, 1, 5, 2, 11L, 12L, 23L, 0, "180", 42L, List.of());

    JsonNode payload = payloadBuilder.build(session, 5, statistics);

    assertThat(payload.path("terminalAt").asText()).isEqualTo(completedAt.toString());
    assertThat(payload.path("statistics").path("grandTotalMinor").longValue()).isEqualTo(23L);
    assertThat(payload.path("statistics").has("aggregateLines")).isFalse();
  }

  @Test
  void preservesNullTerminalAndStatisticsFacts() {
    InventorySession session = Mockito.mock(InventorySession.class);
    when(session.getId()).thenReturn(UUID.randomUUID());
    when(session.getWarehouseId()).thenReturn(UUID.randomUUID());
    when(session.getRevision()).thenReturn(0L);
    when(session.getLifecycle()).thenReturn(SessionLifecycle.ACTIVE);
    when(session.getBusinessDate()).thenReturn(LocalDate.of(2026, 9, 6));
    when(session.getExpectedPopulationCount()).thenReturn(0);

    JsonNode payload = payloadBuilder.build(session, 0, null);

    assertThat(payload.path("terminalAt").isNull()).isTrue();
    assertThat(payload.path("statistics").isNull()).isTrue();
  }

  @Test
  void validatesStartedCompletedAndCancelledSessionEventPayloads() throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    FrozenStatistics statistics =
        new FrozenStatistics(
            4, 3, 1, 2, 1, 1, 0, 1, 5, 2, 11L, 12L, 23L, 0, "180", 42L, List.of());
    InventorySession started =
        session(inventoryId, warehouseId, SessionLifecycle.ACTIVE, null, null);
    OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC).withNano(0);
    InventorySession completed =
        session(inventoryId, warehouseId, SessionLifecycle.COMPLETED, completedAt, null);
    OffsetDateTime cancelledAt = completedAt.plusMinutes(1);
    InventorySession cancelled =
        session(inventoryId, warehouseId, SessionLifecycle.CANCELLED, null, cancelledAt);
    JsonNode cancelledPayload = payloadBuilder.build(cancelled, 5, null);
    JsonSchema schema =
        SCHEMAS.getSchema(
            CONTRACT_JSON.readTree(
                Files.readString(
                    Path.of(
                        System.getProperty("rwms.contracts.dir"),
                        "events/inventory/inventory-events-v1.schema.json"))));

    assertThat(
            schema.validate(
                envelope("inventory.session.started.v1", payloadBuilder.build(started, 5, null))))
        .isEmpty();
    assertThat(
            schema.validate(
                envelope("inventory.session.completed.v1", payloadBuilder.build(completed, 5, statistics))))
        .isEmpty();
    assertThat(
            schema.validate(
                envelope("inventory.session.cancelled.v1", cancelledPayload)))
        .isEmpty();
    assertThat(cancelledPayload.path("terminalAt").asText()).isEqualTo(cancelledAt.toString());
  }

  private InventorySession session(
      UUID inventoryId,
      UUID warehouseId,
      SessionLifecycle lifecycle,
      OffsetDateTime completedAt,
      OffsetDateTime cancelledAt) {
    InventorySession session = Mockito.mock(InventorySession.class);
    when(session.getId()).thenReturn(inventoryId);
    when(session.getWarehouseId()).thenReturn(warehouseId);
    when(session.getRevision()).thenReturn(9L);
    when(session.getLifecycle()).thenReturn(lifecycle);
    when(session.getBusinessDate()).thenReturn(LocalDate.of(2026, 9, 6));
    when(session.getExpectedPopulationCount()).thenReturn(4);
    when(session.getCompletedAt()).thenReturn(completedAt);
    when(session.getCancelledAt()).thenReturn(cancelledAt);
    return session;
  }

  private com.fasterxml.jackson.databind.JsonNode envelope(String eventType, JsonNode payload)
      throws Exception {
    com.fasterxml.jackson.databind.node.ObjectNode envelope = CONTRACT_JSON.createObjectNode();
    String inventoryId = payload.required("inventoryId").asText();
    envelope.put("envelopeVersion", 2);
    envelope.put("eventId", UUID.randomUUID().toString());
    envelope.put("eventType", eventType);
    envelope.put("eventVersion", 1);
    envelope.putNull("occurredAt");
    envelope.put("recordedAt", "2026-09-06T12:00:00Z");
    envelope.put("producer", "inventory-service");
    envelope.put("aggregateType", "SESSION");
    envelope.put("aggregateId", inventoryId);
    envelope.put("aggregateVersion", 9);
    envelope
        .putObject("correlation")
        .put("correlationId", UUID.randomUUID().toString())
        .putNull("causationId");
    envelope.putNull("actorRef");
    envelope.set("payload", CONTRACT_JSON.readTree(payload.toString()));
    return envelope;
  }
}
