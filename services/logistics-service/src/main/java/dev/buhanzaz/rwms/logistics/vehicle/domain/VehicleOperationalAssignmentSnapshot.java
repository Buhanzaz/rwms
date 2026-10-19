package dev.buhanzaz.rwms.logistics.vehicle.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Immutable logistics-owned facts for one vehicle reservation and its operational history. */
public record VehicleOperationalAssignmentSnapshot(
    UUID assignmentId,
    long version,
    UUID transferId,
    UUID vehicleId,
    UUID sourceWarehouseId,
    UUID destinationWarehouseId,
    VehicleOperationalAssignmentMode mode,
    VehicleOperationalAssignmentStatus status,
    OffsetDateTime travelStartsAt,
    OffsetDateTime effectiveFrom,
    OffsetDateTime effectiveUntil,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt) {}
