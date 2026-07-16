package dev.buhanzaz.rwms.auth.eventing;

import dev.buhanzaz.rwms.auth.eventing.AuthReplayAuditStore.FailureCode;
import dev.buhanzaz.rwms.auth.eventing.AuthReplayAuditStore.Operation;
import dev.buhanzaz.rwms.auth.eventing.AuthReplayAuditStore.Status;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

@Service
@RequiredArgsConstructor
public class AuthReplayCoordinator {

    private final AuthReplayAuditStore audit;
    private final AuthReplayVerifier verifier;

    public AuthReplayVerifier.ReplayParityResult rebuild(
            UUID operationId, UUID actorSubjectId, String reason) {
        Operation operation = audit.start(
                operationId, actorSubjectId, reason, verifier.authoritativeSummary());
        if (operation.status() == Status.COMPLETED) {
            return result(operation);
        }
        if (operation.status() == Status.FAILED) {
            throw new OptimisticLockingFailureException("Failed replay operation cannot be repeated");
        }
        try {
            return verifier.rebuildShadowProjection(operation);
        } catch (RuntimeException exception) {
            audit.fail(operationId, classify(exception));
            throw exception;
        }
    }

    private static AuthReplayVerifier.ReplayParityResult result(Operation operation) {
        if (operation.after() == null) {
            throw new IllegalStateException("Completed replay audit has no parity result");
        }
        return new AuthReplayVerifier.ReplayParityResult(
                operation.after().aggregateCount(),
                operation.after().versionSum(),
                operation.after().canonicalChecksum());
    }

    private static FailureCode classify(RuntimeException exception) {
        String message = exception.getMessage() == null ? "" : exception.getMessage();
        if (message.contains("Blocked auth aggregates")) {
            return FailureCode.BLOCKED_AGGREGATE;
        }
        if (message.contains("parity mismatch")) {
            return FailureCode.PARITY_MISMATCH;
        }
        if (message.contains("stream")
                || message.contains("checksum")
                || message.contains("projection")
                || message.contains("corruption")) {
            return FailureCode.STREAM_VALIDATION_FAILED;
        }
        return FailureCode.REPLAY_FAILED;
    }
}
