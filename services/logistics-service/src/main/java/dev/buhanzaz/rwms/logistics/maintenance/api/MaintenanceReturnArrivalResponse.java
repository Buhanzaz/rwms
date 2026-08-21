package dev.buhanzaz.rwms.logistics.maintenance.api;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Minimal logistics-owned physical return-arrival evidence exposed to maintenance-service. */
public record MaintenanceReturnArrivalResponse(
    UUID warehouseId,
    UUID rentalItemId,
    UUID returnDocumentId,
    OffsetDateTime arrivedAt) {}
