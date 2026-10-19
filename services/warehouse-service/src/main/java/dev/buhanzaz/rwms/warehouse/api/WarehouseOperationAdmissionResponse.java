package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

/**
 * Exact authorization-neutral lifecycle decision for a proposed operation direction.
 *
 * @param warehouseId stable warehouse identity
 * @param warehouseVersion version used to make the decision
 * @param lifecycleState current lifecycle state
 * @param direction requested {@code INCOMING} or {@code OUTGOING} direction
 * @param admitted whether that direction is currently allowed
 */
public record WarehouseOperationAdmissionResponse(
    UUID warehouseId,
    long warehouseVersion,
    String lifecycleState,
    String direction,
    boolean admitted) {}
