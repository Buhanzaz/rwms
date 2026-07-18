package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * A non-authoritative, deterministic observation projection. It never changes
 * source-owned asset, task, maintenance, or media state.
 */
@Repository
@RequiredArgsConstructor
public class LogisticsInboundObservationStore {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.MANDATORY)
  public void apply(LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    jdbc.update(
        """
        insert into logistics_inbound_observation(
          consumer_group,event_id,source_topic,aggregate_type,aggregate_id,aggregate_version,event_type,
          payload_sha256,recorded_at)
        values (?,?,?,?,?,?,?,?,?)
        on conflict (consumer_group,event_id) do nothing
        """,
        LogisticsInboundTransportTopics.CONSUMER_GROUP,
        event.eventId(),
        event.sourceTopic(),
        event.aggregateType(),
        event.aggregateId(),
        event.aggregateVersion(),
        event.eventType(),
        event.rawMessageSha256(),
        event.recordedAt());
  }

  @Transactional(readOnly = true)
  public List<Observation> liveProjection() {
    return jdbc.query(
        """
        select event_id,source_topic,aggregate_type,aggregate_id,aggregate_version,event_type,
               payload_sha256,recorded_at
          from logistics_inbound_observation
         where consumer_group=?
         order by source_topic,aggregate_type,aggregate_id,aggregate_version,event_id
        """,
        (result, row) ->
            new Observation(
                result.getObject("event_id", UUID.class),
                result.getString("source_topic"),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getLong("aggregate_version"),
                result.getString("event_type"),
                result.getString("payload_sha256").trim(),
                result.getObject("recorded_at", OffsetDateTime.class)),
        LogisticsInboundTransportTopics.CONSUMER_GROUP);
  }

  @Transactional(readOnly = true)
  public List<Observation> shadowReplay() {
    return jdbc.query(
        """
        select event_id,source_topic,aggregate_type,aggregate_id,aggregate_version,event_type,
               message_sha256,recorded_at
          from logistics_inbound_replay_message
         where state='APPLIED'
         order by source_topic,aggregate_type,aggregate_id,aggregate_version,event_id
        """,
        (result, row) ->
            new Observation(
                result.getObject("event_id", UUID.class),
                result.getString("source_topic"),
                result.getString("aggregate_type"),
                result.getString("aggregate_id"),
                result.getLong("aggregate_version"),
                result.getString("event_type"),
                result.getString("message_sha256").trim(),
                result.getObject("recorded_at", OffsetDateTime.class)));
  }

  public record Observation(
      UUID eventId,
      String sourceTopic,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String payloadSha256,
      OffsetDateTime recordedAt) {}
}
