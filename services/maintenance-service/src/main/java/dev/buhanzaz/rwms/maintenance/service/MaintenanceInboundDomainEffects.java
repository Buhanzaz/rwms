package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceInboundEffects;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Applies validated inbound facts inside the inbox/checkpoint PostgreSQL transaction. */
@Component
public class MaintenanceInboundDomainEffects implements MaintenanceInboundEffects {
  private final MaintenanceApplicationService service;
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MaintenanceInboundDomainEffects(
      MaintenanceApplicationService service, JdbcTemplate jdbc, ObjectMapper mapper) {
    this.service = service;
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Override
  public void apply(InboundEvent event, TaskCorrelation correlation) {
    switch (event.aggregateType()) {
      case "BOARD_TASK", "QUEUE_ENTRY" -> applyTask(event, correlation);
      case "MEDIA" -> applyMedia(event);
      case "RENTAL_ITEM" -> applyRentalItem(event);
      case "OPERATION_LEASE" -> applyLease(event);
      default -> throw new IllegalArgumentException(
          "Unsupported maintenance inbound aggregate " + event.aggregateType());
    }
  }

  private void applyTask(InboundEvent event, TaskCorrelation correlation) {
    if (!correlation.complete()) return;
    QueueFact queue = "QUEUE_ENTRY".equals(event.aggregateType())
        ? new QueueFact(
            event.eventId(), event.eventType(), event.aggregateVersion(),
            nullableTime(event.payload(), "doneAt"))
        : loadQueueFact(correlation.queueEntryEventId());
    service.applyInboundTaskOutcome(
        queue.eventId(), queue.eventType(), correlation.externalTaskId(),
        correlation.queueEntryId(), queue.aggregateVersion(), queue.occurredAt());
  }

  private QueueFact loadQueueFact(UUID eventId) {
    return jdbc.query("""
        select event_id,event_type,aggregate_version,
          envelope_body->'payload'->>'doneAt' as done_at
        from maintenance_inbound_replay_message
        where event_id=? and aggregate_type='QUEUE_ENTRY'
        """, (resultSet, rowNumber) -> new QueueFact(
            resultSet.getObject("event_id", UUID.class),
            resultSet.getString("event_type"),
            resultSet.getLong("aggregate_version"),
            resultSet.getString("done_at") == null
                ? null : OffsetDateTime.parse(resultSet.getString("done_at"))),
        eventId).stream().findFirst().orElseThrow(() ->
            new IllegalStateException("Correlated queue-entry fact is missing"));
  }

  private void applyMedia(InboundEvent event) {
    JsonNode payload = event.payload();
    service.applyInboundMediaFact(
        UUID.fromString(event.aggregateId()),
        payload.required("generation").longValue(),
        payload.required("ownerType").stringValue(),
        UUID.fromString(payload.required("ownerId").stringValue()),
        UUID.fromString(payload.required("warehouseId").stringValue()),
        payload.required("status").stringValue(),
        write(Map.of(
            "kind", payload.required("kind").stringValue(),
            "rotationDegrees", payload.required("rotationDegrees").intValue())),
        event.aggregateVersion());
  }

  private void applyRentalItem(InboundEvent event) {
    JsonNode payload = event.payload();
    JsonNode warehouse = payload.get("warehouseId");
    JsonNode status = payload.get("status");
    service.applyInboundRentalItemFact(
        UUID.fromString(event.aggregateId()),
        warehouse == null ? null : UUID.fromString(warehouse.stringValue()),
        status == null ? null : status.stringValue(),
        event.aggregateVersion());
  }

  private void applyLease(InboundEvent event) {
    JsonNode payload = event.payload();
    service.applyInboundLeaseFact(
        UUID.fromString(event.aggregateId()),
        UUID.fromString(payload.required("rentalItemId").stringValue()),
        payload.required("fencingToken").longValue(),
        payload.required("state").stringValue(),
        event.aggregateVersion());
  }

  private static OffsetDateTime nullableTime(JsonNode payload, String field) {
    JsonNode value = payload.required(field);
    return value.isNull() ? null : OffsetDateTime.parse(value.stringValue());
  }

  private String write(Object value) {
    try {
      return mapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Inbound safe metadata cannot be serialized", exception);
    }
  }

  private record QueueFact(
      UUID eventId,
      String eventType,
      long aggregateVersion,
      OffsetDateTime occurredAt) {}
}
