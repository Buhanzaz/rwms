package dev.buhanzaz.rwms.warehouse.api;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Canonical public warehouse representation.
 *
 * <p>{@code active} is a compatibility projection that is true only in {@code ACTIVE}; callers
 * needing directional admission must use the dedicated internal admission API instead.
 *
 * @param id stable integration identity
 * @param version optimistic-concurrency version
 * @param name normalized display name
 * @param city human-readable city
 * @param address optional human-readable address
 * @param latitude optional WGS84 latitude
 * @param longitude optional WGS84 longitude
 * @param timeZone currently effective canonical IANA timezone
 * @param active compatibility projection for incoming-work admission
 * @param lifecycleState one-way lifecycle: {@code ACTIVE}, {@code DRAINING}, or {@code INACTIVE}
 * @param sortOrder optional non-negative directory ordering value
 * @param representative whether the warehouse has the representative characteristic
 */
public record WarehouseResponse(
    UUID id,
    long version,
    String name,
    String city,
    String address,
    BigDecimal latitude,
    BigDecimal longitude,
    String timeZone,
    boolean active,
    String lifecycleState,
    Integer sortOrder,
    boolean representative) {}
