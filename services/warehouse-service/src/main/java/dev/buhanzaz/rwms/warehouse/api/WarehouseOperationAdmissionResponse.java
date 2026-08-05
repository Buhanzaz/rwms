package dev.buhanzaz.rwms.warehouse.api;

import java.util.UUID;

public record WarehouseOperationAdmissionResponse(
    UUID warehouseId,
    long warehouseVersion,
    String lifecycleState,
    String direction,
    boolean admitted) {}
