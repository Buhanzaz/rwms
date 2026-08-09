package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

/**
 * Least-privilege warehouse identity exposed to logistics.
 *
 * @param id stable warehouse identity
 * @param version current aggregate version
 * @param active compatibility projection; directional admission requires the dedicated endpoint
 * @param name display name
 * @param city human-readable city
 * @param timeZone current canonical IANA timezone
 */
public record LogisticsWarehouseIdentityResponse(
    UUID id,
    long version,
    boolean active,
    String name,
    String city,
    String timeZone) {}
