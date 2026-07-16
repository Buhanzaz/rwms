package dev.buhanzaz.rwms.warehouse.eventing;

import java.util.UUID;

public record WarehouseEventPayload(
    UUID warehouseId, String code, String timeZone, boolean active, Integer sortOrder) {}
