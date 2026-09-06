package dev.buhanzaz.rwms.inventory.eventing;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Durable receipt for a reviewed terminal delivery recovery. */
public record InventoryEventingRecoveryResponse(
    String recordKind,
    UUID recordId,
    String status,
    long reviewVersion,
    OffsetDateTime reviewedAt) {}
