package dev.buhanzaz.rwms.asset.operations.outbox;

import java.time.Instant;
import java.util.UUID;

/** Immutable accepted requeue receipt; {@code state} describes the requested requeue command. */
public record AssetOutboxRequeueResponse(
    UUID eventId,
    String aggregateType,
    String aggregateId,
    long aggregateVersion,
    String state,
    long reviewVersion,
    Instant reviewedAt) {}
