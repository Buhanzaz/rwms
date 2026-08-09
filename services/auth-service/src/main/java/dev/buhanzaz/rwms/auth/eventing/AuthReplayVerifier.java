package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.domain.PrincipalType;
import dev.buhanzaz.rwms.auth.repository.AuthSubjectRepository;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies authoritative auth event streams and rebuilds a separate replay shadow checkpoint.
 *
 * <p>Verification checks stream continuity, canonical payload checksums, safe event schemas,
 * stream-head alignment, and parity with the live auth projection. A rebuild locks all stream
 * heads, requires canonical outbox coverage, writes only the replay projection, and records a
 * canonical parity checksum; it never changes domain facts or business aggregates.
 */
@Service
@RequiredArgsConstructor
public class AuthReplayVerifier {

    static final String REPLAY_PROJECTION = "auth-replay-shadow-v1";

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;
    private final AuthEventPayloadPolicy payloadPolicy;
    private final AuthSubjectRepository subjects;
    private final AuthEventFactFactory facts;
    private final AuthEventingMetrics metrics;
    private final AuthReplayAuditStore replayAudit;

    /**
     * Replays and validates one authoritative aggregate stream against its live projection.
     *
     * @param aggregateType aggregate family to verify
     * @param aggregateId aggregate identifier
     * @return verified stream tail and live-projection presence
     */
    @Transactional(readOnly = true)
    public ReplayResult verify(AuthAggregateType aggregateType, UUID aggregateId) {
        metrics.replayAttempted();
        try {
            return verifyAuthoritativeStream(aggregateType, aggregateId);
        } catch (RuntimeException exception) {
            metrics.replayFailed();
            throw exception;
        }
    }

    /**
     * Summarizes all current authoritative stream tails without modifying them.
     *
     * @return aggregate count, version sum, and checksum used to fence a controlled replay
     */
    @Transactional(readOnly = true)
    public AuthReplayAuditStore.Summary authoritativeSummary() {
        return summary(loadStreamIdentities(false));
    }

    /**
     * Rebuilds the replay shadow checkpoint from locked authoritative stream heads.
     *
     * <p>The supplied audit operation's before-summary must still match the locked heads. On
     * success, every replay checkpoint matches its canonical event tail and the audit operation is
     * completed in the same transaction.
     *
     * @param operation started replay audit operation
     * @return payload-free parity result for the rebuilt shadow projection
     */
    @Transactional
    public ReplayParityResult rebuildShadowProjection(AuthReplayAuditStore.Operation operation) {
        List<StreamIdentity> streams = jdbc.query(
                """
                select aggregate_type, aggregate_id, current_version, last_event_id
                  from event_stream_head
                 order by aggregate_type, aggregate_id
                 for update
                """,
                (result, row) -> new StreamIdentity(
                        AuthAggregateType.valueOf(result.getString("aggregate_type")),
                        UUID.fromString(result.getString("aggregate_id")),
                        result.getLong("current_version"),
                        result.getObject("last_event_id", UUID.class)));
        AuthReplayAuditStore.Summary lockedBefore = summary(streams);
        if (!lockedBefore.equals(operation.before())) {
            throw new IllegalStateException("Auth event streams changed after replay audit started");
        }
        requireCanonicalOutboxCoverage();
        Integer orphanedShadow = jdbc.queryForObject(
                """
                select count(*)
                  from projection_checkpoint checkpoint
                  left join event_stream_head head
                    on head.aggregate_type=checkpoint.aggregate_type
                   and head.aggregate_id=checkpoint.aggregate_id
                 where checkpoint.projection_name=? and head.aggregate_id is null
                """,
                Integer.class,
                REPLAY_PROJECTION);
        if (orphanedShadow != null && orphanedShadow > 0) {
            throw new IllegalStateException("Auth shadow projection contains an unknown aggregate");
        }

        StringBuilder parityMaterial = new StringBuilder();
        for (StreamIdentity stream : streams) {
            ReplayResult replay = verify(stream.aggregateType(), stream.aggregateId());
            if (replay.version() != stream.version()) {
                throw new IllegalStateException("Auth replay version differs from locked stream head");
            }
            TailHash tail = tailHash(stream);
            jdbc.update(
                    """
                    insert into projection_checkpoint(
                        projection_name, aggregate_type, aggregate_id,
                        aggregate_version, projection_sha256, updated_at)
                    values (?, ?, ?, ?, ?, clock_timestamp())
                    on conflict (projection_name, aggregate_type, aggregate_id)
                    do update set aggregate_version=excluded.aggregate_version,
                                  projection_sha256=excluded.projection_sha256,
                                  updated_at=excluded.updated_at
                    """,
                    REPLAY_PROJECTION,
                    stream.aggregateType().name(),
                    stream.aggregateId().toString(),
                    stream.version(),
                    tail.payloadSha256());
            parityMaterial
                    .append(stream.aggregateType().name())
                    .append('\u001f')
                    .append(stream.aggregateId())
                    .append('\u001f')
                    .append(stream.version())
                    .append('\u001f')
                    .append(tail.payloadSha256())
                    .append('\n');
        }
        assertShadowParity(streams.size());
        ReplayParityResult result = new ReplayParityResult(
                streams.size(), streams.stream().mapToLong(StreamIdentity::version).sum(),
                sha256(parityMaterial.toString()));
        replayAudit.complete(
                operation.operationId(),
                new AuthReplayAuditStore.Summary(
                        result.aggregateCount(), result.versionSum(), result.canonicalChecksum()));
        return result;
    }

    private void requireCanonicalOutboxCoverage() {
        Integer corruptFacts = jdbc.queryForObject(
                """
                select count(*)
                  from domain_event event
                 where not event.baseline
                   and not exists (
                       select 1
                         from outbox_event outbox
                        where outbox.event_id=event.event_id
                          and outbox.aggregate_type=event.aggregate_type
                          and outbox.aggregate_id=event.aggregate_id
                          and outbox.aggregate_version=event.aggregate_version
                          and outbox.event_type=event.event_type
                          and outbox.topic=case event.aggregate_type
                              when 'USER_AUTHORIZATION' then 'rwms.auth.user-authorization.v1'
                              when 'WORKER_ACCESS' then 'rwms.auth.worker-access.v1'
                          end
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
                               and (outbox.envelope_body->'correlation')
                                   - array['correlationId', 'causationId']='{}'::jsonb
                               and outbox.envelope_body->'correlation'->>'correlationId'=event.correlation_id::text
                               and outbox.envelope_body->'correlation'->>'causationId'
                                   is not distinct from event.causation_id::text
                               and outbox.envelope_body->'actorRef'=coalesce(event.actor_ref, 'null'::jsonb)
                               and outbox.envelope_body->'payload'=event.payload
                              else false
                          end)
                """,
                Integer.class);
        if (corruptFacts == null || corruptFacts > 0) {
            throw new IllegalStateException(
                    "Auth replay corruption: non-baseline domain event has no canonical outbox fact");
        }
    }

    private List<StreamIdentity> loadStreamIdentities(boolean forUpdate) {
        String lock = forUpdate ? " for update" : "";
        return jdbc.query(
                """
                select aggregate_type, aggregate_id, current_version, last_event_id
                  from event_stream_head
                 order by aggregate_type, aggregate_id
                """ + lock,
                (result, row) -> new StreamIdentity(
                        AuthAggregateType.valueOf(result.getString("aggregate_type")),
                        UUID.fromString(result.getString("aggregate_id")),
                        result.getLong("current_version"),
                        result.getObject("last_event_id", UUID.class)));
    }

    private AuthReplayAuditStore.Summary summary(List<StreamIdentity> streams) {
        StringBuilder material = new StringBuilder();
        for (StreamIdentity stream : streams) {
            material.append(stream.aggregateType().name())
                    .append('\u001f')
                    .append(stream.aggregateId())
                    .append('\u001f')
                    .append(stream.version())
                    .append('\u001f')
                    .append(tailHash(stream).payloadSha256())
                    .append('\n');
        }
        return new AuthReplayAuditStore.Summary(
                streams.size(),
                streams.stream().mapToLong(StreamIdentity::version).sum(),
                sha256(material.toString()));
    }

    private ReplayResult verifyAuthoritativeStream(
            AuthAggregateType aggregateType, UUID aggregateId) {
        List<StoredFact> stored = jdbc.query(
                """
                select event_id, aggregate_version, event_type, payload::text,
                       payload_sha256, baseline
                  from domain_event
                 where aggregate_type = ? and aggregate_id = ?
                 order by aggregate_version
                """,
                (result, row) -> new StoredFact(
                        result.getObject("event_id", UUID.class),
                        result.getLong("aggregate_version"),
                        result.getString("event_type"),
                        result.getString("payload"),
                        result.getString("payload_sha256").trim(),
                        result.getBoolean("baseline")),
                aggregateType.name(),
                aggregateId.toString());
        if (stored.isEmpty()) {
            throw new IllegalStateException("Auth event stream has no facts");
        }
        long previous = stored.getFirst().baseline() ? stored.getFirst().version() : -1;
        if (!stored.getFirst().baseline() && stored.getFirst().version() != 0) {
            throw new IllegalStateException("New auth stream must start at version zero");
        }
        for (int index = 0; index < stored.size(); index++) {
            StoredFact fact = stored.get(index);
            if (index > 0 && fact.version() != previous + 1) {
                throw new IllegalStateException("Auth event stream contains a version gap");
            }
            JsonNode payload = read(fact.payload());
            String validationType = fact.baseline()
                    ? (aggregateType == AuthAggregateType.USER_AUTHORIZATION
                            ? AuthEventTypes.USER_CREATED
                            : AuthEventTypes.WORKER_CONFIGURED)
                    : fact.eventType();
            payloadPolicy.requireAggregateType(validationType, aggregateType);
            payloadPolicy.validateNode(validationType, payload);
            payloadPolicy.requireAggregateIdentity(payload, aggregateId);
            if (!AuthEventStore.sha256(fact.payload().getBytes(StandardCharsets.UTF_8))
                    .equals(fact.payloadSha256())) {
                throw new IllegalStateException("Auth event payload checksum mismatch");
            }
            previous = fact.version();
        }
        StoredFact last = stored.getLast();
        Head head = jdbc.queryForObject(
                """
                select current_version, last_event_id from event_stream_head
                 where aggregate_type = ? and aggregate_id = ?
                """,
                (result, row) -> new Head(result.getLong(1), result.getObject(2, UUID.class)),
                aggregateType.name(),
                aggregateId.toString());
        if (head == null || head.version() != last.version() || !head.eventId().equals(last.eventId())) {
            throw new IllegalStateException("Auth stream head does not match replay tail");
        }
        boolean projectionPresent = subjects.findById(aggregateId).isPresent();
        if (projectionPresent) {
            var subject = subjects.findById(aggregateId).orElseThrow();
            Object projection = subject.getPrincipalType() == PrincipalType.USER
                    ? facts.userAuthorization(subject)
                    : facts.workerAccess(subject);
            String projectionJson = canonicalJson(write(projection));
            if (!projectionJson.equals(last.payload())) {
                throw new IllegalStateException("Auth replay tail does not match live projection");
            }
        } else if (!AuthEventTypes.WORKER_DELETED.equals(last.eventType())) {
            throw new IllegalStateException("Auth live projection is missing before a terminal delete fact");
        }
        return new ReplayResult(aggregateType, aggregateId, head.version(), stored.size(), projectionPresent);
    }

    private JsonNode read(String value) {
        try {
            return objectMapper.readTree(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Stored auth event payload is invalid JSON");
        }
    }

    private TailHash tailHash(StreamIdentity stream) {
        return jdbc.query(
                        """
                        select payload_sha256 from domain_event
                         where aggregate_type=? and aggregate_id=?
                           and aggregate_version=? and event_id=?
                        """,
                        (result, row) -> new TailHash(result.getString("payload_sha256").trim()),
                        stream.aggregateType().name(),
                        stream.aggregateId().toString(),
                        stream.version(),
                        stream.eventId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Auth locked stream head has no canonical tail"));
    }

    private void assertShadowParity(int expectedCount) {
        Integer matching = jdbc.queryForObject(
                """
                select count(*)
                  from projection_checkpoint shadow
                  join event_stream_head head
                    on head.aggregate_type=shadow.aggregate_type
                   and head.aggregate_id=shadow.aggregate_id
                  join domain_event tail
                    on tail.aggregate_type=head.aggregate_type
                   and tail.aggregate_id=head.aggregate_id
                   and tail.aggregate_version=head.current_version
                   and tail.event_id=head.last_event_id
                 where shadow.projection_name=?
                   and shadow.aggregate_version=head.current_version
                   and shadow.projection_sha256=tail.payload_sha256
                """,
                Integer.class,
                REPLAY_PROJECTION);
        Integer shadowCount = jdbc.queryForObject(
                "select count(*) from projection_checkpoint where projection_name=?",
                Integer.class,
                REPLAY_PROJECTION);
        if (matching == null
                || matching != expectedCount
                || shadowCount == null
                || shadowCount != expectedCount) {
            throw new IllegalStateException("Auth shadow replay parity mismatch");
        }
    }

    private String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
        }
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Auth live projection cannot be serialized");
        }
    }

    private String canonicalJson(String value) {
        String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
        if (canonical == null) {
            throw new IllegalStateException("PostgreSQL did not canonicalize replay JSON");
        }
        return canonical;
    }

    private record StoredFact(
            UUID eventId,
            long version,
            String eventType,
            String payload,
            String payloadSha256,
            boolean baseline) {}

    private record Head(long version, UUID eventId) {}

    private record StreamIdentity(
            AuthAggregateType aggregateType, UUID aggregateId, long version, UUID eventId) {}

    private record TailHash(String payloadSha256) {}

    /**
     * Verified state of one authoritative stream.
     *
     * @param aggregateType verified aggregate family
     * @param aggregateId verified aggregate identifier
     * @param version canonical stream-tail version
     * @param factCount number of stored facts examined
     * @param liveProjectionPresent whether the live aggregate still exists
     */
    public record ReplayResult(
            AuthAggregateType aggregateType,
            UUID aggregateId,
            long version,
            int factCount,
            boolean liveProjectionPresent) {}

    /**
     * Payload-free parity summary for a completed replay shadow rebuild.
     *
     * @param aggregateCount number of rebuilt streams
     * @param versionSum sum of rebuilt stream versions
     * @param canonicalChecksum checksum of canonical stream-tail material
     */
    public record ReplayParityResult(int aggregateCount, long versionSum, String canonicalChecksum) {}
}
