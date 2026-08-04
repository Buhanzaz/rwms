package dev.buhanzaz.rwms.asset.operations.outbox;

import java.time.Instant;
import java.util.UUID;

/** Current read truth after a requeue or an exact idempotent retry. */
public record AssetOutboxRequeueResponse(
    UUID eventId,
    long reviewVersion,
    String status,
    int attemptCount,
    String lastErrorCode,
    Instant reviewedAt) {}
