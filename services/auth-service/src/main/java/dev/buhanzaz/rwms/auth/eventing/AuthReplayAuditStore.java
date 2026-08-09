package dev.buhanzaz.rwms.auth.eventing;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Stores durable, operator-attributable audit state for controlled auth shadow replays.
 *
 * <p>An operation ID is idempotent only for the same actor and normalized reason. Its before and
 * after summaries make the replay result inspectable without storing event payloads or profile
 * data in the audit record.
 */
@Repository
@RequiredArgsConstructor
public class AuthReplayAuditStore {

    private final JdbcTemplate jdbc;

    /**
     * Starts or resumes an idempotent replay audit operation.
     *
     * @param operationId caller-supplied operation identity
     * @param actorSubjectId operator responsible for the action
     * @param reason bounded, nonblank operator reason
     * @param before authoritative stream summary observed before replay
     * @return persisted operation, including a prior completed operation with the same owner
     * @throws OptimisticLockingFailureException when the ID belongs to another actor or reason
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Operation start(UUID operationId, UUID actorSubjectId, String reason, Summary before) {
        String normalizedReason = requireReason(reason);
        jdbc.update(
                """
                insert into replay_operation_audit(
                    operation_id, actor_subject_id, reason, status, started_at,
                    before_aggregate_count, before_version_sum, before_checksum)
                values (?, ?, ?, 'STARTED', clock_timestamp(), ?, ?, ?)
                on conflict (operation_id) do nothing
                """,
                operationId,
                actorSubjectId,
                normalizedReason,
                before.aggregateCount(),
                before.versionSum(),
                before.canonicalChecksum());
        Operation operation = require(operationId);
        if (!operation.actorSubjectId().equals(actorSubjectId)
                || !operation.reason().equals(normalizedReason)) {
            throw new OptimisticLockingFailureException(
                    "Replay operation id is already owned by another operator request");
        }
        return operation;
    }

    /**
     * Finds a replay operation without changing its lifecycle state.
     *
     * @param operationId operation identity
     * @return existing operation, if any
     */
    @Transactional(readOnly = true)
    public Optional<Operation> find(UUID operationId) {
        return jdbc.query(
                        """
                        select operation_id, actor_subject_id, reason, status,
                               before_aggregate_count, before_version_sum, before_checksum,
                               after_aggregate_count, after_version_sum, after_checksum,
                               failure_code
                          from replay_operation_audit
                         where operation_id=?
                        """,
                        (result, row) -> new Operation(
                                result.getObject("operation_id", UUID.class),
                                result.getObject("actor_subject_id", UUID.class),
                                result.getString("reason"),
                                Status.valueOf(result.getString("status")),
                                new Summary(
                                        result.getInt("before_aggregate_count"),
                                        result.getLong("before_version_sum"),
                                        result.getString("before_checksum").trim()),
                                result.getObject("after_aggregate_count") == null
                                        ? null
                                        : new Summary(
                                                result.getInt("after_aggregate_count"),
                                                result.getLong("after_version_sum"),
                                                result.getString("after_checksum").trim()),
                                result.getString("failure_code")),
                        operationId)
                .stream()
                .findFirst();
    }

    /**
     * Completes a still-started replay with its verified parity summary.
     *
     * @param operationId operation to complete
     * @param after aggregate count, version sum, and canonical parity checksum
     * @throws OptimisticLockingFailureException when the operation is no longer started
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void complete(UUID operationId, Summary after) {
        if (jdbc.update(
                        """
                        update replay_operation_audit
                           set status='COMPLETED', completed_at=clock_timestamp(),
                               after_aggregate_count=?, after_version_sum=?, after_checksum=?
                         where operation_id=? and status='STARTED'
                        """,
                        after.aggregateCount(),
                        after.versionSum(),
                        after.canonicalChecksum(),
                        operationId)
                != 1) {
            throw new OptimisticLockingFailureException(
                    "Replay operation is no longer eligible for completion");
        }
    }

    /**
     * Marks a still-started replay as failed in an independent transaction.
     *
     * @param operationId operation to mark failed
     * @param failureCode stable classification safe for audit storage
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void fail(UUID operationId, FailureCode failureCode) {
        jdbc.update(
                """
                update replay_operation_audit
                   set status='FAILED', completed_at=clock_timestamp(), failure_code=?
                 where operation_id=? and status='STARTED'
                """,
                failureCode.name(),
                operationId);
    }

    private Operation require(UUID operationId) {
        return find(operationId)
                .orElseThrow(() -> new IllegalStateException("Replay operation audit was not persisted"));
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.trim().isEmpty() || reason.trim().length() > 500) {
            throw new IllegalArgumentException("A controlled auth replay reason is required");
        }
        return reason.trim();
    }

    /** Lifecycle state of a controlled replay audit operation. */
    public enum Status {
        /** Operation has been accepted and has not yet settled. */
        STARTED,
        /** Replay completed with a verified parity summary. */
        COMPLETED,
        /** Replay stopped with a durable safe failure classification. */
        FAILED
    }

    /** Safe classifications persisted when a controlled replay fails. */
    public enum FailureCode {
        /** Replay could not proceed because an aggregate remained blocked. */
        BLOCKED_AGGREGATE,
        /** Stream structure, checksum, or projection validation failed. */
        STREAM_VALIDATION_FAILED,
        /** Rebuilt shadow checkpoints did not match authoritative tails. */
        PARITY_MISMATCH,
        /** Failure was not covered by a more specific safe classification. */
        REPLAY_FAILED
    }

    /**
     * Stable, payload-free summary used to fence and verify a replay.
     *
     * @param aggregateCount number of aggregate streams
     * @param versionSum sum of current stream versions
     * @param canonicalChecksum checksum over the canonical stream-tail material
     */
    public record Summary(int aggregateCount, long versionSum, String canonicalChecksum) {}

    /**
     * Durable audit view of a controlled replay request and result.
     *
     * @param operationId operation identity
     * @param actorSubjectId responsible operator
     * @param reason bounded operator-supplied reason
     * @param status lifecycle state
     * @param before authoritative summary before replay
     * @param after verified parity summary after successful replay, if available
     * @param failureCode safe failure classification, if failed
     */
    public record Operation(
            UUID operationId,
            UUID actorSubjectId,
            String reason,
            Status status,
            Summary before,
            Summary after,
            String failureCode) {}
}
