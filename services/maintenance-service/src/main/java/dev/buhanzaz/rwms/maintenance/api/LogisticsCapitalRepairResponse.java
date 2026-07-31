package dev.buhanzaz.rwms.maintenance.api;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.RepairComplexitySnapshot;
import java.util.UUID;

/** Minimal calculated capital-repair projection needed by the logistics board. */
public record LogisticsCapitalRepairResponse(
    UUID repairId,
    UUID rentalItemId,
    UUID warehouseId,
    int priority,
    RepairComplexitySnapshot complexity,
    long version) {}
