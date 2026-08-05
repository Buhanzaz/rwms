package dev.buhanzaz.rwms.warehouse.api;

import java.time.OffsetDateTime;
import java.util.UUID;

public record WarehouseLifecycleReadinessConfirmationResponse(
    UUID warehouseId,
    long warehouseVersion,
    String lifecycleState,
    String readinessOwner,
    OffsetDateTime confirmedAt) {}
