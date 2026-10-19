package dev.buhanzaz.rwms.warehouse.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Immutable acknowledgement of one owner's drain confirmation.
 *
 * @param warehouseId stable warehouse identity
 * @param warehouseVersion aggregate version at the confirmation
 * @param lifecycleState lifecycle state, always {@code DRAINING} when initially recorded
 * @param readinessOwner owner inferred from the authenticated service credential
 * @param confirmedAt database timestamp at which the evidence was recorded
 */
public record WarehouseLifecycleReadinessConfirmationResponse(
    UUID warehouseId,
    long warehouseVersion,
    String lifecycleState,
    String readinessOwner,
    OffsetDateTime confirmedAt) {}
