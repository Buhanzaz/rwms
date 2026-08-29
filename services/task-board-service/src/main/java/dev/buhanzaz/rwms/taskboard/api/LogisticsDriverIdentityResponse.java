package dev.buhanzaz.rwms.taskboard.api;

import dev.buhanzaz.rwms.taskboard.domain.WorkerEmploymentType;
import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Least-privilege worker identity exposed only to the logistics-service planning boundary.
 *
 * @param workerId stable task-board worker identity
 * @param displayName current operator-facing worker name
 * @param employmentType staff or time-bounded contractor profile
 * @param phone contractor contact number; absent for staff
 * @param operationalWarehouseId warehouse where this resource is available
 * @param availableFrom earliest availability represented by this projection
 * @param availableUntil exclusive availability end when bounded
 * @param availabilityKind home, active transfer assignment, or planned incoming availability
 */
public record LogisticsDriverIdentityResponse(
    UUID workerId,
    String displayName,
    WorkerEmploymentType employmentType,
    String phone,
    UUID operationalWarehouseId,
    OffsetDateTime availableFrom,
    OffsetDateTime availableUntil,
    LogisticsDriverAvailabilityKind availabilityKind) {}
