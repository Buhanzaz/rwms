package dev.buhanzaz.rwms.taskboard.eventing;

import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/** Coordinates reviewed replay while preserving aggregate order, idempotency, and audit state. */
@Service
@RequiredArgsConstructor
public class TaskBoardReplayCoordinator {
  private final TaskBoardReplayAuditStore audit;
  private final TaskBoardReplayVerifier verifier;

  public TaskBoardReplayVerifier.ReplayParityResult rebuild(
      UUID operationId, UUID actorSubjectId, String reason) {
    var operation =
        audit.start(operationId, actorSubjectId, reason, verifier.authoritativeSummary());
    if (operation.status() == TaskBoardReplayAuditStore.Status.COMPLETED) {
      return completedResult(operation);
    }
    if (operation.status() == TaskBoardReplayAuditStore.Status.FAILED) {
      throw new OptimisticLockingFailureException("Failed replay operation cannot be repeated");
    }
    try {
      return verifier.rebuildShadowProjection(operation);
    } catch (RuntimeException exception) {
      audit.fail(operationId, classify(exception));
      throw exception;
    }
  }

  private static TaskBoardReplayVerifier.ReplayParityResult completedResult(
      TaskBoardReplayAuditStore.Operation operation) {
    if (operation.after() == null) {
      throw new IllegalStateException("Completed replay audit has no parity result");
    }
    return new TaskBoardReplayVerifier.ReplayParityResult(
        operation.after().aggregateCount(),
        operation.after().versionSum(),
        operation.after().canonicalChecksum());
  }

  private static TaskBoardReplayAuditStore.FailureCode classify(RuntimeException exception) {
    String message = exception.getMessage() == null ? "" : exception.getMessage();
    if (message.contains("blocked aggregate")) {
      return TaskBoardReplayAuditStore.FailureCode.BLOCKED_AGGREGATE;
    }
    if (message.contains("parity")) {
      return TaskBoardReplayAuditStore.FailureCode.PARITY_MISMATCH;
    }
    if (message.contains("stream")
        || message.contains("checksum")
        || message.contains("projection")
        || message.contains("canonical outbox")) {
      return TaskBoardReplayAuditStore.FailureCode.STREAM_VALIDATION_FAILED;
    }
    return TaskBoardReplayAuditStore.FailureCode.REPLAY_FAILED;
  }
}
