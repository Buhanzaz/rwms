package dev.buhanzaz.rwms.auth.eventing;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class AuthShadowReconciler {

    static final String SHADOW_PROJECTION = "auth-kafka-shadow-v1";

    private final JdbcTemplate jdbc;
    private final AuthReplayVerifier replayVerifier;
    private final AuthEventingMetrics metrics;

    @Transactional
    public Result reconcile(
            AuthAggregateType aggregateType,
            UUID aggregateId,
            long expectedCheckpointVersion,
            String reason,
            UUID resolvedBySubjectId) {
        String normalizedReason = reason == null ? "" : reason.trim();
        if (normalizedReason.isEmpty() || normalizedReason.length() > 500) {
            throw new IllegalArgumentException("A reconciliation reason is required");
        }

        Checkpoint checkpoint = lockCheckpoint(aggregateType, aggregateId);
        if (!checkpoint.blocked()
                || !"AGGREGATE_VERSION_GAP".equals(checkpoint.quarantineReason())
                || checkpoint.version() != expectedCheckpointVersion) {
            throw new OptimisticLockingFailureException(
                    "Auth shadow checkpoint is not the expected blocked version");
        }

        Head head = lockHead(aggregateType, aggregateId);
        AuthReplayVerifier.ReplayResult replay = replayVerifier.verify(aggregateType, aggregateId);
        if (replay.version() != head.version()) {
            throw new IllegalStateException("Auth authoritative replay changed during reconciliation");
        }
        Tail tail = authoritativeTail(aggregateType, aggregateId, head);

        int resolved = jdbc.update(
                """
                update version_gap_quarantine
                   set status = 'RESOLVED', resolved_at = clock_timestamp(),
                       resolution_reason = ?, resolved_by_subject_id = ?
                 where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                   and status = 'OPEN' and expected_version = ?
                """,
                normalizedReason,
                resolvedBySubjectId,
                AuthInboxProcessor.CONSUMER_GROUP,
                aggregateType.name(),
                aggregateId.toString(),
                expectedCheckpointVersion + 1);
        if (resolved != 1) {
            throw new OptimisticLockingFailureException(
                    "Auth version-gap quarantine is not eligible for reconciliation");
        }

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
                aggregateType.name(),
                aggregateId.toString(),
                tail.version(),
                tail.payloadSha256());
        int unblocked = jdbc.update(
                """
                update consumer_aggregate_checkpoint
                   set last_event_id = ?, last_aggregate_version = ?, blocked = false,
                       quarantine_reason = null, updated_at = clock_timestamp()
                 where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                   and blocked and last_aggregate_version = ?
                """,
                tail.eventId(),
                tail.version(),
                AuthInboxProcessor.CONSUMER_GROUP,
                aggregateType.name(),
                aggregateId.toString(),
                expectedCheckpointVersion);
        if (unblocked != 1) {
            throw new OptimisticLockingFailureException("Auth shadow checkpoint changed during reconciliation");
        }
        metrics.shadowReconciled();
        return new Result(aggregateType, aggregateId, tail.version(), tail.eventId(), resolved);
    }

    private Checkpoint lockCheckpoint(AuthAggregateType aggregateType, UUID aggregateId) {
        return jdbc.query(
                        """
                        select last_aggregate_version, blocked, quarantine_reason
                          from consumer_aggregate_checkpoint
                         where consumer_group = ? and aggregate_type = ? and aggregate_id = ?
                         for update
                        """,
                        (result, row) -> new Checkpoint(
                                result.getLong("last_aggregate_version"),
                                result.getBoolean("blocked"),
                                result.getString("quarantine_reason")),
                        AuthInboxProcessor.CONSUMER_GROUP,
                        aggregateType.name(),
                        aggregateId.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new OptimisticLockingFailureException(
                        "Auth shadow checkpoint does not exist"));
    }

    private Head lockHead(AuthAggregateType aggregateType, UUID aggregateId) {
        return jdbc.query(
                        """
                        select current_version, last_event_id
                          from event_stream_head
                         where aggregate_type = ? and aggregate_id = ?
                         for update
                        """,
                        (result, row) -> new Head(
                                result.getLong("current_version"),
                                result.getObject("last_event_id", UUID.class)),
                        aggregateType.name(),
                        aggregateId.toString())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException("Auth authoritative stream does not exist"));
    }

    private Tail authoritativeTail(AuthAggregateType aggregateType, UUID aggregateId, Head head) {
        return jdbc.query(
                        """
                        select event_id, aggregate_version, payload_sha256
                          from domain_event
                         where aggregate_type = ? and aggregate_id = ? and aggregate_version = ?
                           and event_id = ?
                        """,
                        (result, row) -> new Tail(
                                result.getObject("event_id", UUID.class),
                                result.getLong("aggregate_version"),
                                result.getString("payload_sha256").trim()),
                        aggregateType.name(),
                        aggregateId.toString(),
                        head.version(),
                        head.eventId())
                .stream()
                .findFirst()
                .orElseThrow(() -> new IllegalStateException(
                        "Auth authoritative stream head has no matching fact"));
    }

    private record Checkpoint(long version, boolean blocked, String quarantineReason) {}

    private record Head(long version, UUID eventId) {}

    private record Tail(UUID eventId, long version, String payloadSha256) {}

    public record Result(
            AuthAggregateType aggregateType,
            UUID aggregateId,
            long version,
            UUID lastEventId,
            int resolvedQuarantines) {}
}
