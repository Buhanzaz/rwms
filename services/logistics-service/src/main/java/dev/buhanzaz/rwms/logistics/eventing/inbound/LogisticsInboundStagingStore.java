package dev.buhanzaz.rwms.logistics.eventing.inbound;

import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores only validated, source-sanitized envelopes for reviewed recovery and replay. */
@Repository
@RequiredArgsConstructor
public class LogisticsInboundStagingStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void stage(LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    int inserted =
        jdbc.update(
            """
            insert into logistics_inbound_replay_message(
              event_id,source_topic,kafka_key,aggregate_type,aggregate_id,aggregate_version,event_type,
              recorded_at,envelope_body,message_sha256,state,staged_at,updated_at)
            values (?,?,?,?,?,?,?,?,?::jsonb,?,'STAGED',clock_timestamp(),clock_timestamp())
            on conflict (event_id) do nothing
            """,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.recordedAt(),
            event.envelopeJson(),
            event.rawMessageSha256());
    if (inserted == 1) {
      return;
    }
    requireSameEvent(event);
  }

  @Transactional(readOnly = true)
  public Optional<LogisticsInboundEnvelopeValidator.ValidatedInboundEvent> load(UUID eventId) {
    return jdbc
        .query(
            """
            select source_topic,event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                   recorded_at,message_sha256,envelope_body::text
              from logistics_inbound_replay_message
             where event_id=?
            """,
            (result, row) -> {
              String envelope = result.getString("envelope_body");
              try {
                JsonNode payload = mapper.readTree(envelope).required("payload");
                return new LogisticsInboundEnvelopeValidator.ValidatedInboundEvent(
                    result.getString("source_topic"),
                    result.getObject("event_id", UUID.class),
                    result.getString("event_type"),
                    result.getString("aggregate_type"),
                    result.getString("aggregate_id"),
                    result.getLong("aggregate_version"),
                    result.getObject("recorded_at", OffsetDateTime.class),
                    result.getString("message_sha256").trim(),
                    envelope,
                    payload.deepCopy());
              } catch (JacksonException exception) {
                throw new IllegalStateException("Validated logistics replay envelope cannot be parsed", exception);
              }
            },
            eventId)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void markDlt(UUID eventId) {
    jdbc.update(
        """
        update logistics_inbound_replay_message
           set state='DLT',updated_at=clock_timestamp()
         where event_id=? and state in ('STAGED','REPLAY_APPROVED')
        """,
        eventId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markApplied(UUID eventId) {
    jdbc.update(
        """
        update logistics_inbound_replay_message
           set state='APPLIED',updated_at=clock_timestamp()
         where event_id=? and state in ('STAGED','DLT','REPLAY_APPROVED','APPLIED')
        """,
        eventId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean approveReplay(UUID eventId) {
    return jdbc.update(
            """
            update logistics_inbound_replay_message
               set state='REPLAY_APPROVED',updated_at=clock_timestamp()
             where event_id=? and state='DLT'
            """,
            eventId)
        == 1;
  }

  private void requireSameEvent(LogisticsInboundEnvelopeValidator.ValidatedInboundEvent event) {
    Integer matches =
        jdbc.queryForObject(
            """
            select count(*)
              from logistics_inbound_replay_message
             where event_id=? and source_topic=? and kafka_key=? and aggregate_type=? and aggregate_id=?
               and aggregate_version=? and event_type=? and recorded_at=? and envelope_body=?::jsonb
               and message_sha256=?
            """,
            Integer.class,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.recordedAt(),
            event.envelopeJson(),
            event.rawMessageSha256());
    if (matches == null || matches != 1) {
      throw new LogisticsInboundEventIdentityConflictException();
    }
  }
}
