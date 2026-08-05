package dev.buhanzaz.rwms.warehouse.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WarehouseTimeZoneAtResponse(
    UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {}
