package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;

/** Consumer-owned dedupe/checkpoint state. It makes duplicate or gap delivery harmless. */
@Service
public class AssetInboxProcessor {
  private static final String GROUP = "asset-service-inbox-v1";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final AssetEventPayloadPolicy policy;
  private final AssetSanitizedDltPublisher deadLetters;

  public AssetInboxProcessor(
      JdbcTemplate jdbc, ObjectMapper mapper, AssetEventPayloadPolicy policy, AssetSanitizedDltPublisher deadLetters) {
    this.jdbc = jdbc;
    this.mapper = mapper.rebuild().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION).build();
    this.policy = policy;
    this.deadLetters = deadLetters;
  }

  @Transactional
  public Outcome process(byte[] raw) {
    return process(raw, null);
  }

  @Transactional
  public Outcome process(byte[] raw, AssetAggregateType expectedType) {
    Parsed value = parse(raw);
    if (expectedType != null && value.type() != expectedType) {
      throw new AssetEventValidationException("Asset inbound aggregate family does not match its topic");
    }
    int inserted = jdbc.update("""
        insert into inbox_message(consumer_group,event_id,aggregate_type,aggregate_id,aggregate_version,payload_sha256,status,attempt_count,received_at)
        values (?, ?, ?, ?, ?, ?, 'RECEIVED', 0, clock_timestamp()) on conflict do nothing
        """, GROUP, value.eventId(), value.type().name(), value.aggregateId().toString(), value.aggregateVersion(), value.payloadHash());
    if (inserted == 0) return Outcome.DUPLICATE;
    jdbc.update("""
        insert into consumer_aggregate_checkpoint(consumer_group,aggregate_type,aggregate_id,last_event_id,last_aggregate_version,blocked,updated_at)
        values (?, ?, ?, null, -1, false, clock_timestamp()) on conflict do nothing
        """, GROUP, value.type().name(), value.aggregateId().toString());
    Checkpoint checkpoint = jdbc.queryForObject("""
        select last_event_id,last_aggregate_version,blocked from consumer_aggregate_checkpoint
        where consumer_group=? and aggregate_type=? and aggregate_id=? for update
        """, (rs, row) -> new Checkpoint(rs.getObject("last_event_id", UUID.class), rs.getLong("last_aggregate_version"), rs.getBoolean("blocked")),
        GROUP, value.type().name(), value.aggregateId().toString());
    if (checkpoint == null || checkpoint.blocked()) {
      mark(value.eventId(), "QUARANTINED", "AGGREGATE_BLOCKED");
      return Outcome.QUARANTINED;
    }
    if (value.aggregateVersion() <= checkpoint.version()) {
      mark(value.eventId(), "PROCESSED", null);
      return Outcome.DUPLICATE;
    }
    if (value.aggregateVersion() != checkpoint.version() + 1) {
      mark(value.eventId(), "QUARANTINED", "AGGREGATE_VERSION_GAP");
      jdbc.update("update consumer_aggregate_checkpoint set blocked=true,quarantine_reason='AGGREGATE_VERSION_GAP',updated_at=clock_timestamp() where consumer_group=? and aggregate_type=? and aggregate_id=?",
          GROUP, value.type().name(), value.aggregateId().toString());
      jdbc.update("""
          insert into version_gap_quarantine(quarantine_id,consumer_group,aggregate_type,aggregate_id,expected_version,received_version,received_event_id,payload_sha256,reason_code,status,detected_at)
          values (?, ?, ?, ?, ?, ?, ?, ?, 'AGGREGATE_VERSION_GAP', 'OPEN', clock_timestamp()) on conflict do nothing
          """, UUID.randomUUID(), GROUP, value.type().name(), value.aggregateId().toString(), checkpoint.version() + 1,
          value.aggregateVersion(), value.eventId(), value.payloadHash());
      deadLetters.publishHash(value.payloadHash(), "VERSION_GAP");
      return Outcome.QUARANTINED;
    }
    // There is intentionally no foreign-domain side effect in Stage 5. The
    // checkpoint is the durable, replayable effect for subscribed facts.
    mark(value.eventId(), "PROCESSED", null);
    jdbc.update("""
        update consumer_aggregate_checkpoint set last_event_id=?,last_aggregate_version=?,updated_at=clock_timestamp()
        where consumer_group=? and aggregate_type=? and aggregate_id=?
        """, value.eventId(), value.aggregateVersion(), GROUP, value.type().name(), value.aggregateId().toString());
    return Outcome.PROCESSED;
  }

  private Parsed parse(byte[] raw) {
    try {
      JsonNode node = mapper.readTree(raw);
      DomainEventEnvelopeV2<Map<String, Object>> envelope = mapper.readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {}).readValue(node);
      AssetAggregateType type = AssetAggregateType.valueOf(envelope.aggregateType());
      UUID id = UUID.fromString(envelope.aggregateId());
      policy.validateNode(envelope.eventType(), type, id, node.get("payload"));
      return new Parsed(envelope.eventId(), type, id, envelope.aggregateVersion(), AssetChecksum.sha256(mapper.writeValueAsBytes(node.get("payload"))));
    } catch (AssetEventValidationException exception) {
      throw exception;
    } catch (RuntimeException exception) {
      throw new AssetEventValidationException("Asset inbound event is invalid", exception);
    }
  }

  private void mark(UUID eventId, String status, String reason) {
    jdbc.update("update inbox_message set status=?,processed_at=case when ?='PROCESSED' then clock_timestamp() else null end,quarantine_reason=? where consumer_group=? and event_id=?",
        status, status, reason, GROUP, eventId);
  }

  private record Checkpoint(UUID eventId, long version, boolean blocked) {}
  private record Parsed(UUID eventId, AssetAggregateType type, UUID aggregateId, long aggregateVersion, String payloadHash) {}
  public enum Outcome { PROCESSED, DUPLICATE, QUARANTINED }
}
