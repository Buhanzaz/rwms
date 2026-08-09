package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

/**
 * Active-only private metadata projection for inventory commands.
 *
 * @param id stable warehouse identity
 * @param version current aggregate version
 * @param active always true for this route
 * @param timeZone current canonical IANA timezone
 */
public record InventoryWarehouseMetadataResponse(
    UUID id, long version, boolean active, String timeZone) {}
