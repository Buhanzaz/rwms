package dev.buhanzaz.rwms.inventory.service;

import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Validated event identity shared by every line of one normal-return inspection import. */
record NormalReturnImportContext(
    UUID returnId,
    long documentVersion,
    UUID estimateId,
    UUID sourceEventId,
    UUID correlationId,
    OpaqueActorReference actor,
    OffsetDateTime eventOccurredAt,
    OffsetDateTime arrivedAt,
    OffsetDateTime completedAt,
    String terminalState,
    String proofSha256) {
  NormalReturnImportContext {
    if (returnId == null
        || documentVersion < 0
        || sourceEventId == null
        || correlationId == null
        || eventOccurredAt == null
        || arrivedAt == null
        || completedAt == null
        || completedAt.isBefore(arrivedAt)
        || !java.util.Set.of("ACCEPTED", "ESTIMATE_REQUESTED").contains(terminalState)
        || proofSha256 == null
        || !proofSha256.matches("^[0-9a-f]{64}$")) {
      throw new IllegalArgumentException("Normal-return import context is incomplete");
    }
  }
}
