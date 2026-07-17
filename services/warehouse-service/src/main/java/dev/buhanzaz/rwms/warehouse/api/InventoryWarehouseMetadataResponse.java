package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

public record InventoryWarehouseMetadataResponse(
    UUID id, long version, boolean active, String timeZone) {}
