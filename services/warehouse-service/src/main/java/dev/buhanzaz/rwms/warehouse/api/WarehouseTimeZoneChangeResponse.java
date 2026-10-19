package dev.buhanzaz.rwms.warehouse.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Acknowledgement of an appended future-effective timezone decision.
 *
 * @param warehouseId stable warehouse identity
 * @param warehouseVersion aggregate version after the decision was recorded
 * @param timeZone canonical IANA timezone to become effective
 * @param effectiveFrom future start instant of the decision
 */
public record WarehouseTimeZoneChangeResponse(
    UUID warehouseId, long warehouseVersion, String timeZone, OffsetDateTime effectiveFrom) {}
