package dev.buhanzaz.rwms.maintenance.integration;

import static dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.*;

import java.time.OffsetDateTime;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/** Owns maintenance's private warehouse admission, readiness, timezone, and mark calls. */
final class MaintenanceWarehouseHttpClient {
  private static final String WAREHOUSE_ADMISSION_CLIENT = "maintenance-warehouse-admission";
  private static final String WAREHOUSE_READINESS_CLIENT = "maintenance-warehouse-readiness";
  private static final String WAREHOUSE_READINESS_CONFIRM_CLIENT =
      "maintenance-warehouse-readiness-confirm";
  private static final String WAREHOUSE_TIMEZONE_CLIENT = "maintenance-warehouse-timezone";
  private static final String WAREHOUSE_OPERATION_MARK_CLIENT =
      "maintenance-warehouse-operation-mark";
  private static final String WAREHOUSE_LIFECYCLE_READ_SCOPE = "warehouse.lifecycle.read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE =
      "warehouse.lifecycle.confirm";
  private static final String WAREHOUSE_TIMEZONE_SCOPE = "warehouse.timezone.read";
  private static final String WAREHOUSE_OPERATION_MARK_SCOPE = "warehouse.operation.mark";

  private final MaintenanceHttpTransport transport;
  private final String warehouseInternalBase;

  MaintenanceWarehouseHttpClient(
      MaintenanceHttpTransport transport, MaintenanceDependencyProperties.Validated properties) {
    this.transport = transport;
    warehouseInternalBase = MaintenanceHttpTransport.strip(properties.warehouseBaseUrl().toString())
        + "/api/internal/warehouse/v1";
  }

  WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    if (warehouseId == null || direction == null) {
      throw new IllegalArgumentException("Warehouse admission identity is required");
    }
    try {
      WarehouseOperationAdmission response = transport.client().get()
          .uri(
              warehouseInternalBase
                  + "/warehouses/"
                  + warehouseId
                  + "/admission?direction={direction}",
              direction.name())
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(WAREHOUSE_ADMISSION_CLIENT, WAREHOUSE_LIFECYCLE_READ_SCOPE))
          .retrieve()
          .body(WarehouseOperationAdmission.class);
      if (response == null
          || !warehouseId.equals(response.warehouseId())
          || direction != response.direction()) {
        throw MaintenanceHttpTransport.malformed(
            "Warehouse-service returned mismatched admission truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(UUID after, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Warehouse readiness page size is invalid");
    }
    try {
      String uri = warehouseInternalBase + "/lifecycle/readiness-work?limit=" + limit;
      if (after != null) {
        uri += "&after=" + after;
      }
      WarehouseLifecycleReadinessWorkPage response = transport.client().get()
          .uri(uri)
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(WAREHOUSE_READINESS_CLIENT, WAREHOUSE_LIFECYCLE_READ_SCOPE))
          .retrieve()
          .body(WarehouseLifecycleReadinessWorkPage.class);
      if (response == null
          || response.items().stream()
              .map(WarehouseLifecycleReadinessWork::warehouseId)
              .distinct()
              .count()
              != response.items().size()
          || (after != null && after.equals(response.nextAfter()))) {
        throw MaintenanceHttpTransport.malformed(
            "Warehouse-service returned malformed readiness work");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    if (warehouseId == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness identity is required");
    }
    try {
      WarehouseLifecycleReadinessConfirmation response = transport.client().post()
          .uri(warehouseInternalBase + "/warehouses/" + warehouseId + "/lifecycle-readiness")
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(
                  WAREHOUSE_READINESS_CONFIRM_CLIENT, WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE))
          .body(new WarehouseLifecycleReadinessRequest(expectedVersion))
          .retrieve()
          .body(WarehouseLifecycleReadinessConfirmation.class);
      if (response == null
          || !warehouseId.equals(response.warehouseId())
          || response.warehouseVersion() != expectedVersion) {
        throw MaintenanceHttpTransport.malformed(
            "Warehouse-service returned mismatched readiness confirmation");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at) {
    if (warehouseId == null || at == null) {
      throw new IllegalArgumentException("Warehouse timezone lookup identity is required");
    }
    try {
      WarehouseTimeZone response = transport.client().get()
          .uri(
              warehouseInternalBase + "/warehouses/" + warehouseId + "/time-zone?at={at}", at)
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(WAREHOUSE_TIMEZONE_CLIENT, WAREHOUSE_TIMEZONE_SCOPE))
          .retrieve()
          .body(WarehouseTimeZone.class);
      if (response == null
          || !warehouseId.equals(response.warehouseId())
          || response.effectiveFrom().isAfter(at)) {
        throw MaintenanceHttpTransport.malformed(
            "Warehouse-service returned mismatched timezone truth");
      }
      return response;
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  void markWarehouseOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    if (warehouseId == null || operationId == null || occurredAt == null) {
      throw new IllegalArgumentException("Warehouse operation mark identity is required");
    }
    try {
      transport.client().post()
          .uri(warehouseInternalBase + "/warehouses/" + warehouseId + "/operation-marks")
          .header(
              HttpHeaders.AUTHORIZATION,
              transport.bearer(WAREHOUSE_OPERATION_MARK_CLIENT, WAREHOUSE_OPERATION_MARK_SCOPE))
          .body(new WarehouseOperationMarkRequest(operationId, occurredAt))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  /**
   * Confirms lifecycle readiness only if warehouse-service still owns the version observed by the
   * maintenance preflight.
   */
  private record WarehouseLifecycleReadinessRequest(long expectedVersion) {}

  /** Records one idempotent maintenance operation occurrence in the owning warehouse timeline. */
  private record WarehouseOperationMarkRequest(UUID operationId, OffsetDateTime occurredAt) {}
}
