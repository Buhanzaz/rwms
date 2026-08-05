package dev.buhanzaz.rwms.taskboard.eventing;

import dev.buhanzaz.rwms.taskboard.domain.WarehouseMetadata;
import dev.buhanzaz.rwms.taskboard.repository.WarehouseMetadataRepository;
import dev.buhanzaz.rwms.taskboard.service.GlobalQueueProjectionService;
import dev.buhanzaz.rwms.taskboard.service.WarehouseTimeZoneGateway;
import java.time.ZoneId;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
@RequiredArgsConstructor
public class WarehouseMetadataEventProcessor {
  private static final Set<String> EVENT_TYPES =
      Set.of(
          "warehouse.warehouse.created.v1",
          "warehouse.warehouse.changed.v1",
          "warehouse.warehouse.deactivated.v1");

  private final ObjectMapper objectMapper;
  private final JdbcTemplate jdbc;
  private final WarehouseMetadataRepository warehouses;
  private final GlobalQueueProjectionService globalQueues;
  private final WarehouseTimeZoneGateway timeZones;

  @Transactional
  public void process(byte[] body) {
    JsonNode envelope = read(body);
    validateEnvelope(envelope);
    UUID eventId = uuid(envelope, "eventId");
    long aggregateVersion = envelope.required("aggregateVersion").longValue();
    String hash = TaskBoardEventStore.sha256(body);
    var existingHashes =
        jdbc.queryForList(
            "select event_hash from warehouse_event_inbox where event_id=?",
            String.class,
            eventId);
    if (!existingHashes.isEmpty()) {
      if (!existingHashes.getFirst().equals(hash)) {
        throw new IllegalArgumentException("Повторное использование warehouse eventId");
      }
      return;
    }

    JsonNode payload = envelope.required("payload");
    UUID warehouseId = uuid(payload, "warehouseId");
    if (!warehouseId.equals(uuid(envelope, "aggregateId"))) {
      throw new IllegalArgumentException("warehouseId не совпадает с aggregateId");
    }
    String timeZone = text(payload, "timeZone");
    ZoneId.of(timeZone);
    boolean active = payload.required("active").booleanValue();

    WarehouseMetadata projection = warehouses.findById(warehouseId).orElse(null);
    if (projection == null) {
      warehouses.save(WarehouseMetadata.fromFact(warehouseId, aggregateVersion, timeZone, active));
    } else if (projection.getSourceVersion() < aggregateVersion) {
      projection.applyFact(aggregateVersion, timeZone, active);
      warehouses.save(projection);
    }
    // Every active warehouse receives the same GENERAL task-board standard.
    // Existing physical work_queue UUIDs are preserved by the projection service.
    globalQueues.synchronizeWarehouse(warehouseId);
    // Scheduled timezone decisions remain warehouse-owned. This delivery is only a hint to drop
    // the bounded HTTP cache; old events without timeZoneDecision retain the same behavior.
    timeZones.invalidate(warehouseId);
    jdbc.update(
        """
        insert into warehouse_event_inbox(event_id,event_hash,aggregate_version,processed_at)
        values (?,?,?,clock_timestamp())
        """,
        eventId,
        hash,
        aggregateVersion);
  }

  private void validateEnvelope(JsonNode envelope) {
    if (!envelope.isObject()
        || envelope.required("envelopeVersion").intValue() != 2
        || envelope.required("eventVersion").intValue() != 1
        || !"warehouse-service".equals(text(envelope, "producer"))
        || !"WAREHOUSE".equals(text(envelope, "aggregateType"))
        || !EVENT_TYPES.contains(text(envelope, "eventType"))
        || envelope.required("aggregateVersion").longValue() < 0
        || !envelope.required("payload").isObject()) {
      throw new IllegalArgumentException("Некорректный warehouse event");
    }
  }

  private JsonNode read(byte[] body) {
    try {
      return objectMapper.readTree(body);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Warehouse event не является JSON", exception);
    }
  }

  private UUID uuid(JsonNode node, String field) {
    return UUID.fromString(text(node, field));
  }

  private String text(JsonNode node, String field) {
    JsonNode value = node.required(field);
    if (!value.isTextual() || value.textValue().isBlank()) {
      throw new IllegalArgumentException(field + " обязателен");
    }
    return value.textValue();
  }
}
