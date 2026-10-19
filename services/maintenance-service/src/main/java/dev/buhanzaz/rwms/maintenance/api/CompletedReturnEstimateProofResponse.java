package dev.buhanzaz.rwms.maintenance.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable completion proof for one maintenance estimate created from a logistics return line. */
public record CompletedReturnEstimateProofResponse(
    UUID estimateId,
    long estimateVersion,
    int estimateRevision,
    UUID returnId,
    UUID lineId,
    UUID warehouseId,
    UUID assetId,
    long assetVersion,
    OffsetDateTime arrivedAt,
    OffsetDateTime completedAt,
    String completionKind,
    UUID repairId) {}
