package dev.buhanzaz.rwms.warehouse.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Timezone decision effective at a caller-supplied operation instant.
 *
 * @param warehouseId stable warehouse identity
 * @param timeZone canonical IANA timezone effective at the requested instant
 * @param effectiveFrom start instant of the returned immutable decision
 */
public record WarehouseTimeZoneAtResponse(
    UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {}
