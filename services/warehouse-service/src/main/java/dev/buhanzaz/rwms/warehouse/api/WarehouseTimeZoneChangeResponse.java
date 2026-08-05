package dev.buhanzaz.rwms.warehouse.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WarehouseTimeZoneChangeResponse(
    UUID warehouseId, long warehouseVersion, String timeZone, OffsetDateTime effectiveFrom) {}
