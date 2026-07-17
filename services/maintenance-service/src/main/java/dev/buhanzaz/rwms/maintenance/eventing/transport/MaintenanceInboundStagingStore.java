package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores only schema-validated, sanitized envelopes so transient DLTs can be reviewed and replayed. */
@Repository
public class MaintenanceInboundStagingStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public MaintenanceInboundStagingStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void stage(MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    int inserted =
        jdbc.update(
            """
            insert into maintenance_inbound_replay_message(
              event_id,source_topic,kafka_key,aggregate_type,aggregate_id,aggregate_version,event_type,
              envelope_body,message_sha256,state,staged_at,updated_at)
            values (?,?,?,?,?,?,?,?::jsonb,?,'STAGED',clock_timestamp(),clock_timestamp())
            on conflict (event_id) do nothing
            """,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.envelopeJson(),
            event.rawMessageSha256());
    if (inserted == 1) {
      return;
    }
    Integer matches =
        jdbc.queryForObject(
            """
            select count(*) from maintenance_inbound_replay_message
             where event_id=? and source_topic=? and kafka_key=? and aggregate_type=? and aggregate_id=?
               and aggregate_version=? and event_type=? and envelope_body=?::jsonb and message_sha256=?
            """,
            Integer.class,
            event.eventId(),
            event.sourceTopic(),
            event.aggregateId(),
            event.aggregateType(),
            event.aggregateId(),
            event.aggregateVersion(),
            event.eventType(),
            event.envelopeJson(),
            event.rawMessageSha256());
    if (matches == null || matches != 1) {
      throw new MaintenanceEventIdentityConflictException();
    }
  }

  @Transactional(readOnly = true)
  public Optional<MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent> load(UUID eventId) {
    return jdbc.query(
            """
            select source_topic,event_id,event_type,aggregate_type,aggregate_id,aggregate_version,
                   message_sha256,envelope_body::text
              from maintenance_inbound_replay_message where event_id=?
            """,
            (result, row) -> {
              String json = result.getString("envelope_body");
              try {
                JsonNode payload = mapper.readTree(json).required("payload");
                return new MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent(
                    result.getString("source_topic"),
                    result.getObject("event_id", UUID.class),
                    result.getString("event_type"),
                    result.getString("aggregate_type"),
                    result.getString("aggregate_id"),
                    result.getLong("aggregate_version"),
                    MaintenanceTransportTopics.requireInput(result.getString("source_topic"))
                        .actionableEventTypes()
                        .contains(result.getString("event_type")),
                    result.getString("message_sha256").trim(),
                    json,
                    payload.deepCopy());
              } catch (tools.jackson.core.JacksonException exception) {
                throw new IllegalStateException("Validated maintenance replay envelope cannot be parsed", exception);
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
        update maintenance_inbound_replay_message set state='DLT',updated_at=clock_timestamp()
         where event_id=? and state in ('STAGED','REPLAY_APPROVED')
        """,
        eventId);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public void markApplied(UUID eventId) {
    jdbc.update(
        """
        update maintenance_inbound_replay_message set state='APPLIED',updated_at=clock_timestamp()
         where event_id=? and state in ('STAGED','DLT','REPLAY_APPROVED','APPLIED')
        """,
        eventId);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean approveReplay(UUID eventId) {
    return jdbc.update(
            """
            update maintenance_inbound_replay_message set state='REPLAY_APPROVED',updated_at=clock_timestamp()
             where event_id=? and state='DLT'
            """,
            eventId)
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void rejectReplay(UUID eventId) {
    jdbc.update(
        """
        update maintenance_inbound_replay_message set state='REJECTED',updated_at=clock_timestamp()
         where event_id=? and state in ('DLT','REPLAY_APPROVED')
        """,
        eventId);
  }
}
