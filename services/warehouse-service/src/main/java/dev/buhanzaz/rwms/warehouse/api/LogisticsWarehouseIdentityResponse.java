package dev.buhanzaz.rwms.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Least-privilege warehouse identity exposed to logistics.
 *
 * @param id stable warehouse identity
 * @param version current aggregate version
 * @param active compatibility projection; directional admission requires the dedicated endpoint
 * @param name display name
 * @param city human-readable city
 * @param address optional human-readable warehouse address
 * @param latitude optional WGS84 latitude used as the logistics map source of truth
 * @param longitude optional WGS84 longitude used as the logistics map source of truth
 * @param timeZone current canonical IANA timezone
 * @param representative whether the warehouse has the representative characteristic
 */
public record LogisticsWarehouseIdentityResponse(
    UUID id,
    long version,
    boolean active,
    String name,
    String city,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String timeZone,
    boolean representative) {}
