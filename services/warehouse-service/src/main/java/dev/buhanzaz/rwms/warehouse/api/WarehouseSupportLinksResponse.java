package dev.buhanzaz.rwms.warehouse.api;

import java.util.List;
import java.util.UUID;

/**
 * Version-fenced support collection of one served warehouse.
 *
 * @param servedWarehouseId warehouse being served
 * @param warehouseVersion current aggregate version for the next replacement
 * @param links configured directed edges
 */
public record WarehouseSupportLinksResponse(
    UUID servedWarehouseId, long warehouseVersion, List<WarehouseSupportLinkResponse> links) {}
