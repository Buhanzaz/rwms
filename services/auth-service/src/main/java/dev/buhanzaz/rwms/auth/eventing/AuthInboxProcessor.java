package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@Service
public class AuthInboxProcessor {

    static final String CONSUMER_GROUP = "auth-shadow-v1";
    private static final String SHADOW_PROJECTION = "auth-kafka-shadow-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final ObjectMapper strictObjectMapper;
    private final AuthEventPayloadPolicy payloadPolicy;
    private final AuthEventingMetrics metrics;

    public AuthInboxProcessor(
            JdbcTemplate jdbc,
            ObjectMapper objectMapper,
            AuthEventPayloadPolicy payloadPolicy,
            AuthEventingMetrics metrics) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
        this.strictObjectMapper = objectMapper
                .rebuild()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .build();
        this.payloadPolicy = payloadPolicy;
        this.metrics = metrics;
    }

    @Transactional
    public void process(byte[] serializedEnvelope, AuthAggregateType expectedAggregateType) {
        DomainEventEnvelopeV2<Map<String, Object>> envelope =
                readAndValidate(serializedEnvelope, expectedAggregateType);
        String aggregateId = UUID.fromString(envelope.aggregateId()).toString();
        String canonicalEnvelope = canonicalJson(write(envelope));
        String messageHash = AuthEventStore.sha256(canonicalEnvelope.getBytes(StandardCharsets.UTF_8));
        requireAuthoritativeEnvelope(envelope, expectedAggregateType, canonicalEnvelope, messageHash);
        int inserted = jdbc.update(
                """
                insert into inbox_message(
                    consumer_group, event_id, aggregate_type, aggregate_id,
                    aggregate_version, payload_sha256, status, attempt_count, received_at)
                values (?, ?, ?, ?, ?, ?, 'RECEIVED', 0, clock_timestamp())
                on conflict (consumer_group, event_id) do nothing
                """,
                CONSUMER_GROUP,
                envelope.eventId(),
                envelope.aggregateType(),
                aggregateId,
                envelope.aggregateVersion(),
                messageHash);
        if (inserted == 0) {
            return;
        }

        seedBaselineCheckpointIfRequired(envelope, aggregateId);
        Checkpoint checkpoint = lockCheckpoint(envelope.aggregateType(), aggregateId);
        if (checkpoint.blocked()) {
            quarantineInbox(envelope.eventId(), "AGGREGATE_BLOCKED");
            metrics.inboxQuarantined();
            return;
        }
        long expectedVersion = checkpoint.version() + 1;
        if (envelope.aggregateVersion() > expectedVersion) {
            quarantineGap(envelope, aggregateId, expectedVersion, messageHash);
            metrics.inboxQuarantined();
            return;
        }
        if (envelope.aggregateVersion() < expectedVersion) {
            markProcessed(envelope.eventId());
            metrics.inboxProcessed();
            return;
        }

        String payloadJson = canonicalJson(write(envelope.payload()));
        String projectionHash = AuthEventStore.sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
        jdbc.update(
                """
                insert into projection_checkpoint(
                    projection_name, aggregate_type, aggregate_id,
                    aggregate_version, projection_sha256, updated_at)
                values (?, ?, ?, ?, ?, clock_timestamp())
                on conflict (projection_name, aggregate_type, aggregate_id)
                do update set aggregate_version = excluded.aggregate_version,
                              projection_sha256 = excluded.projection_sha256,
                              updated_at = excluded.updated_at
                """,
                SHADOW_PROJECTION,
                envelope.aggregateType(),
                aggregateId,
                envelope.aggregateVersion(),
                projectionHash);
        jdbc.update(
                """
                update consumer_aggregate_checkpoint
                   set last_event_id = ?, last_aggregate_version = ?, updated_at = clock_timestamp()
                 where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                """,
                envelope.eventId(),
                envelope.aggregateVersion(),
                CONSUMER_GROUP,
                envelope.aggregateType(),
                aggregateId);
        markProcessed(envelope.eventId());
        metrics.inboxProcessed();
    }

    private void markProcessed(UUID eventId) {
        jdbc.update(
                """
                update inbox_message
                   set status = 'PROCESSED', processed_at = clock_timestamp()
                 where consumer_group = ? and event_id = ?
                """,
                CONSUMER_GROUP,
                eventId);
    }

    private void requireAuthoritativeEnvelope(
            DomainEventEnvelopeV2<Map<String, Object>> envelope,
            AuthAggregateType aggregateType,
            String canonicalEnvelope,
            String envelopeHash) {
        Integer matching = jdbc.queryForObject(
                """
                select count(*)
                  from outbox_event outbox
                  join domain_event event on event.event_id = outbox.event_id
                 where outbox.event_id = ?
                   and outbox.aggregate_type = ?
                   and outbox.aggregate_id = ?
                   and outbox.aggregate_version = ?
                   and outbox.event_type = ?
                   and outbox.topic = ?
                   and outbox.envelope_body = ?::jsonb
                   and outbox.envelope_sha256 = ?
                   and event.aggregate_type = outbox.aggregate_type
                   and event.aggregate_id = outbox.aggregate_id
                   and event.aggregate_version = outbox.aggregate_version
                   and event.event_type = outbox.event_type
                   and outbox.envelope_sha256=encode(
                       sha256(convert_to(outbox.envelope_body::text, 'UTF8')), 'hex')
                   and event.payload_sha256=encode(
                       sha256(convert_to(event.payload::text, 'UTF8')), 'hex')
                   and case
                       when jsonb_exists_all(outbox.envelope_body, array[
                           'envelopeVersion', 'eventId', 'eventType', 'eventVersion',
                           'occurredAt', 'recordedAt', 'producer', 'aggregateType',
                           'aggregateId', 'aggregateVersion', 'correlation', 'actorRef', 'payload'])
                        and outbox.envelope_body - array[
                           'envelopeVersion', 'eventId', 'eventType', 'eventVersion',
                           'occurredAt', 'recordedAt', 'producer', 'aggregateType',
                           'aggregateId', 'aggregateVersion', 'correlation', 'actorRef', 'payload'] = '{}'::jsonb
                        and jsonb_typeof(outbox.envelope_body->'correlation')='object'
                       then outbox.envelope_body->>'envelopeVersion'='2'
                        and outbox.envelope_body->>'eventId'=event.event_id::text
                        and outbox.envelope_body->>'eventType'=event.event_type
                        and outbox.envelope_body->>'eventVersion'=event.event_version::text
                        and (outbox.envelope_body->>'occurredAt')::timestamptz
                            is not distinct from event.occurred_at
                        and (outbox.envelope_body->>'recordedAt')::timestamptz=event.recorded_at
                        and outbox.envelope_body->>'producer'='auth-service'
                        and outbox.envelope_body->>'aggregateType'=event.aggregate_type
                        and outbox.envelope_body->>'aggregateId'=event.aggregate_id
                        and outbox.envelope_body->>'aggregateVersion'=event.aggregate_version::text
                        and jsonb_exists_all(
                            outbox.envelope_body->'correlation',
                            array['correlationId', 'causationId'])
                        and (outbox.envelope_body->'correlation') - array['correlationId', 'causationId']='{}'::jsonb
                        and outbox.envelope_body->'correlation'->>'correlationId'=event.correlation_id::text
                        and outbox.envelope_body->'correlation'->>'causationId'
                            is not distinct from event.causation_id::text
                        and outbox.envelope_body->'actorRef'=coalesce(event.actor_ref, 'null'::jsonb)
                        and outbox.envelope_body->'payload'=event.payload
                       else false
                   end
                """,
                Integer.class,
                envelope.eventId(),
                envelope.aggregateType(),
                envelope.aggregateId(),
                envelope.aggregateVersion(),
                envelope.eventType(),
                aggregateType.topic(),
                canonicalEnvelope,
                envelopeHash);
        if (matching == null || matching != 1) {
            throw new AuthEventValidationException();
        }
    }

    private void seedBaselineCheckpointIfRequired(
            DomainEventEnvelopeV2<Map<String, Object>> envelope, String aggregateId) {
        Integer existing = jdbc.queryForObject(
                """
                select count(*) from consumer_aggregate_checkpoint
                 where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                """,
                Integer.class,
                CONSUMER_GROUP,
                envelope.aggregateType(),
                aggregateId);
        if (existing != null && existing > 0) {
            return;
        }
        var baseline = jdbc.query(
                        """
                        select event_id, aggregate_version
                          from domain_event
                         where aggregate_type = ? and aggregate_id = ? and baseline
                         order by aggregate_version desc limit 1
                        """,
                        (result, row) -> new Baseline(
                                result.getObject("event_id", UUID.class),
                                result.getLong("aggregate_version")),
                        envelope.aggregateType(),
                        aggregateId)
                .stream()
                .findFirst();
        if (baseline.isPresent()) {
            jdbc.update(
                    """
                    insert into consumer_aggregate_checkpoint(
                        consumer_group, aggregate_type, aggregate_id, last_event_id,
                        last_aggregate_version, blocked, updated_at)
                    values (?, ?, ?, ?, ?, false, clock_timestamp())
                    on conflict do nothing
                    """,
                    CONSUMER_GROUP,
                    envelope.aggregateType(),
                    aggregateId,
                    baseline.get().eventId(),
                    baseline.get().version());
            return;
        }
        jdbc.update(
                """
                insert into consumer_aggregate_checkpoint(
                    consumer_group, aggregate_type, aggregate_id, last_event_id,
                    last_aggregate_version, blocked, updated_at)
                values (?, ?, ?, null, -1, false, clock_timestamp())
                on conflict do nothing
                """,
                CONSUMER_GROUP,
                envelope.aggregateType(),
                aggregateId);
    }

    private Checkpoint lockCheckpoint(String aggregateType, String aggregateId) {
        return jdbc.queryForObject(
                """
                select last_aggregate_version, blocked
                  from consumer_aggregate_checkpoint
                 where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                 for update
                """,
                (result, row) -> new Checkpoint(result.getLong(1), result.getBoolean(2)),
                CONSUMER_GROUP,
                aggregateType,
                aggregateId);
    }

    private void quarantineGap(
            DomainEventEnvelopeV2<Map<String, Object>> envelope,
            String aggregateId,
            long expectedVersion,
            String payloadHash) {
        blockAggregate(envelope.aggregateType(), aggregateId, "AGGREGATE_VERSION_GAP");
        jdbc.update(
                """
                insert into version_gap_quarantine(
                    quarantine_id, consumer_group, aggregate_type, aggregate_id,
                    expected_version, received_version, received_event_id,
                    payload_sha256, reason_code, status, detected_at)
                values (?, ?, ?, ?, ?, ?, ?, ?, 'AGGREGATE_VERSION_GAP', 'OPEN', clock_timestamp())
                on conflict (consumer_group, received_event_id) do nothing
                """,
                UUID.randomUUID(),
                CONSUMER_GROUP,
                envelope.aggregateType(),
                aggregateId,
                expectedVersion,
                envelope.aggregateVersion(),
                envelope.eventId(),
                payloadHash);
        quarantineInbox(envelope.eventId(), "AGGREGATE_VERSION_GAP");
    }

    private void blockAggregate(String aggregateType, String aggregateId, String reason) {
        jdbc.update(
                """
                update consumer_aggregate_checkpoint
                   set blocked = true, quarantine_reason = ?, updated_at = clock_timestamp()
                 where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                """,
                reason,
                CONSUMER_GROUP,
                aggregateType,
                aggregateId);
    }

    private void quarantineInbox(UUID eventId, String reason) {
        jdbc.update(
                """
                update inbox_message
                   set status = 'QUARANTINED', quarantine_reason = ?, processed_at = clock_timestamp()
                 where consumer_group = ? and event_id = ?
                """,
                reason,
                CONSUMER_GROUP,
                eventId);
    }

    private DomainEventEnvelopeV2<Map<String, Object>> read(byte[] value) {
        try {
            return strictObjectMapper
                    .readerFor(new TypeReference<DomainEventEnvelopeV2<Map<String, Object>>>() {})
                    .readValue(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new AuthEventValidationException();
        }
    }

    private DomainEventEnvelopeV2<Map<String, Object>> readAndValidate(
            byte[] value, AuthAggregateType expectedAggregateType) {
        try {
            DomainEventEnvelopeV2<Map<String, Object>> envelope = read(value);
            if (!expectedAggregateType.name().equals(envelope.aggregateType())) {
                throw new IllegalArgumentException();
            }
            if (!"auth-service".equals(envelope.producer())) {
                throw new IllegalArgumentException();
            }
            payloadPolicy.requireAggregateType(envelope.eventType(), expectedAggregateType);
            UUID.fromString(envelope.aggregateId());
            JsonNode payload = payloadNode(envelope.payload());
            payloadPolicy.validateNode(envelope.eventType(), payload);
            payloadPolicy.requireAggregateIdentity(
                    payload, UUID.fromString(envelope.aggregateId()));
            return envelope;
        } catch (AuthEventValidationException exception) {
            throw exception;
        } catch (RuntimeException exception) {
            throw new AuthEventValidationException();
        }
    }

    private JsonNode payloadNode(Map<String, Object> payload) {
        try {
            return objectMapper.readTree(objectMapper.writeValueAsBytes(payload));
        } catch (tools.jackson.core.JacksonException exception) {
            throw new AuthEventValidationException();
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new AuthEventValidationException();
        }
    }

    private String canonicalJson(String value) {
        String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
        if (canonical == null) {
            throw new IllegalStateException("PostgreSQL did not canonicalize auth consumer JSON");
        }
        return canonical;
    }

    private record Baseline(UUID eventId, long version) {}

    private record Checkpoint(long version, boolean blocked) {}
}
