package dev.buhanzaz.rwms.maintenance.service;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import org.springframework.stereotype.Service;

/** Enforces the creation-only estimate deadline from logistics-owned physical arrival evidence. */
@Service
public final class EstimateCreationWindowPolicy {
  private final EstimateCreationWindowSettingsService settings;
  private final WarehouseLifecycleOperations warehouseLifecycle;
  private final MaintenanceDependencyGateway dependencies;

  EstimateCreationWindowPolicy(
      EstimateCreationWindowSettingsService settings,
      WarehouseLifecycleOperations warehouseLifecycle,
      MaintenanceDependencyGateway dependencies) {
    this.settings = settings;
    this.warehouseLifecycle = warehouseLifecycle;
    this.dependencies = dependencies;
  }

  /** Loads logistics-owned evidence for a manager command and enforces it outside a DB transaction. */
  void requireManualCreationOpen(UUID warehouseId, UUID rentalItemId) {
    MaintenanceDependencyGateway.ReturnArrival arrival =
        dependencies.returnArrival(warehouseId, rentalItemId);
    if (!warehouseId.equals(arrival.warehouseId())
        || !rentalItemId.equals(arrival.rentalItemId())) {
      throw new MaintenanceDependencyException(
          org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
          "Logistics-service returned another return arrival");
    }
    requireCreationOpen(warehouseId, arrival.arrivedAt(), now());
  }

  /** Enforces an immutable arrival timestamp supplied by the logistics-owned automatic source. */
  void requireTrustedCreationOpen(UUID warehouseId, OffsetDateTime arrivedAt) {
    requireCreationOpen(warehouseId, arrivedAt, now());
  }

  /** Evaluates the inclusive warehouse-local deadline using one captured current instant. */
  void requireCreationOpen(
      UUID warehouseId, OffsetDateTime arrivedAt, OffsetDateTime evaluatedAt) {
    if (warehouseId == null || arrivedAt == null || evaluatedAt == null) {
      throw new IllegalArgumentException("Estimate creation window evidence is required");
    }
    if (arrivedAt.isAfter(evaluatedAt)) {
      throw new MaintenanceValidationException(
          "MAINTENANCE_VALIDATION_FAILED",
          "Return arrival evidence cannot be later than the estimate command");
    }
    LocalDate arrivalDate = warehouseLifecycle.localDateAt(warehouseId, arrivedAt);
    LocalDate today = warehouseLifecycle.localDateAt(warehouseId, evaluatedAt);
    LocalDate deadline = arrivalDate.plusDays(settings.effectiveDays());
    if (today.isAfter(deadline)) {
      throw new MaintenanceValidationException(
          "ESTIMATE_CREATION_WINDOW_EXPIRED",
          "The warehouse-local deadline for creating a new estimate has expired");
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
