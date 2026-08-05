package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventPayload.TimeZoneDecision;
import dev.buhanzaz.rwms.warehouse.mapper.WarehouseEventPayloadMapper;
import dev.buhanzaz.rwms.warehouse.service.WarehouseChecksum;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

@Service
public class WarehouseOutboxWriter {
  private static final String PRODUCER = "warehouse-service";
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final WarehouseEventPayloadMapper payloadMapper;
  private final WarehouseEventPayloadPolicy payloadPolicy;
  private final WarehouseCorrelationContextProvider correlations;
  private final WarehouseActorReferenceProvider actors;

  public WarehouseOutboxWriter(
      JdbcTemplate jdbc,
      ObjectMapper objectMapper,
      WarehouseEventPayloadMapper payloadMapper,
      WarehouseEventPayloadPolicy payloadPolicy,
      WarehouseCorrelationContextProvider correlations,
      WarehouseActorReferenceProvider actors) {
    this.jdbc = jdbc;
    this.objectMapper = objectMapper;
    this.payloadMapper = payloadMapper;
    this.payloadPolicy = payloadPolicy;
    this.correlations = correlations;
    this.actors = actors;
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(Warehouse warehouse, WarehouseEventType eventType, String effectiveTimeZone) {
    append(warehouse, eventType, effectiveTimeZone, null);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      Warehouse warehouse,
      WarehouseEventType eventType,
      String effectiveTimeZone,
      TimeZoneDecision timeZoneDecision) {
    WarehouseEventPayload mapped = payloadMapper.toPayload(warehouse, effectiveTimeZone);
    WarehouseEventPayload payload =
        new WarehouseEventPayload(
            mapped.warehouseId(),
            mapped.timeZone(),
            mapped.active(),
            mapped.sortOrder(),
            timeZoneDecision);
    payloadPolicy.validateAndConvert(eventType, payload);
    OffsetDateTime recordedAt = databaseNow();
    UUID eventId = UUID.randomUUID();
    DomainEventEnvelopeV2<WarehouseEventPayload> envelope =
        new DomainEventEnvelopeV2<>(
            2,
            eventId,
            eventType.value(),
            1,
            recordedAt.toInstant(),
            recordedAt.toInstant(),
            PRODUCER,
            WarehouseAggregateType.WAREHOUSE.name(),
            warehouse.getId().toString(),
            warehouse.getVersion(),
            correlations.current(),
            actors.current(),
            payload);
    String envelopeBody = canonicalJson(write(envelope));
    String checksum = WarehouseChecksum.sha256(envelopeBody.getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into outbox_event(
            event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
            topic,occurred_at,recorded_at,envelope_body,envelope_sha256,status,attempt_count,
            next_attempt_at,created_at)
        values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
        """,
        eventId,
        WarehouseAggregateType.WAREHOUSE.name(),
        warehouse.getId().toString(),
        warehouse.getVersion(),
        eventType.value(),
        WarehouseAggregateType.WAREHOUSE.topic(),
        recordedAt,
        recordedAt,
        envelopeBody,
        checksum,
        recordedAt,
        recordedAt);
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize warehouse event JSON");
    return canonical;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Warehouse event cannot be serialized", exception);
    }
  }
}
