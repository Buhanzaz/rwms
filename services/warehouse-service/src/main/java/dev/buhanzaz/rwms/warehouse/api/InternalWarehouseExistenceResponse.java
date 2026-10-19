package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

/**
 * Minimal private existence projection for an internal validation client.
 *
 * @param id stable warehouse identity
 * @param version current aggregate version
 * @param active true only while the warehouse accepts incoming work
 */
public record InternalWarehouseExistenceResponse(UUID id, long version, boolean active) {}
