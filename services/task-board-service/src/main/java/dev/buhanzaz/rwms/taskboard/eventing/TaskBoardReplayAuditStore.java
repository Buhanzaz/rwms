package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
@RequiredArgsConstructor
public class TaskBoardReplayAuditStore {
  private final JdbcTemplate jdbc;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Operation start(UUID operationId, UUID actorSubjectId, String reason, Summary before) {
    String normalizedReason = requireReason(reason);
    jdbc.update(
        """
        insert into replay_operation_audit(
          operation_id,actor_subject_id,reason,status,started_at,
          before_aggregate_count,before_version_sum,before_checksum)
        values (?,?,?,'STARTED',clock_timestamp(),?,?,?)
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

  @Transactional(readOnly = true)
  public Optional<Operation> find(UUID operationId) {
    return jdbc.query(
            """
            select operation_id,actor_subject_id,reason,status,
                   before_aggregate_count,before_version_sum,before_checksum,
                   after_aggregate_count,after_version_sum,after_checksum,failure_code
              from replay_operation_audit where operation_id=?
            """,
            (result, row) ->
                new Operation(
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

  @Transactional(propagation = Propagation.MANDATORY)
  public void complete(UUID operationId, Summary after) {
    int changed =
        jdbc.update(
            """
            update replay_operation_audit
               set status='COMPLETED',completed_at=clock_timestamp(),
                   after_aggregate_count=?,after_version_sum=?,after_checksum=?
             where operation_id=? and status='STARTED'
            """,
            after.aggregateCount(),
            after.versionSum(),
            after.canonicalChecksum(),
            operationId);
    if (changed != 1) {
      throw new OptimisticLockingFailureException(
          "Replay operation is no longer eligible for completion");
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void fail(UUID operationId, FailureCode failureCode) {
    jdbc.update(
        """
        update replay_operation_audit
           set status='FAILED',completed_at=clock_timestamp(),failure_code=?
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
      throw new IllegalArgumentException("A controlled task-board replay reason is required");
    }
    return reason.trim();
  }

  public enum Status {
    STARTED,
    COMPLETED,
    FAILED
  }

  public enum FailureCode {
    BLOCKED_AGGREGATE,
    STREAM_VALIDATION_FAILED,
    PARITY_MISMATCH,
    REPLAY_FAILED
  }

  public record Summary(int aggregateCount, long versionSum, String canonicalChecksum) {}

  public record Operation(
      UUID operationId,
      UUID actorSubjectId,
      String reason,
      Status status,
      Summary before,
      Summary after,
      String failureCode) {}
}
