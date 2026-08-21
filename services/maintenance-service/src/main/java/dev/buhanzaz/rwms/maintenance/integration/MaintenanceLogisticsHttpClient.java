package dev.buhanzaz.rwms.maintenance.integration;

import static dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.springframework.http.HttpHeaders;

/**
 * Owns maintenance's private logistics task calls and validates logistics-owned movement and
 * driver-task recovery truth.
 */
final class MaintenanceLogisticsHttpClient {
  private static final String LOGISTICS_CLIENT = "maintenance-logistics";
  private static final String LOGISTICS_SCOPE = "logistics.maintenance";

  private final MaintenanceHttpTransport transport;
  private final String driverTaskIntakeUrl;
  private final String propertyEquipmentMovementTaskUrl;
  private final String returnArrivalUrl;

  MaintenanceLogisticsHttpClient(
      MaintenanceHttpTransport transport, MaintenanceDependencyProperties.Validated properties) {
    this.transport = transport;
    driverTaskIntakeUrl = MaintenanceHttpTransport.strip(properties.logisticsBaseUrl().toString())
        + "/api/internal/logistics/v1/maintenance/driver-tasks";
    propertyEquipmentMovementTaskUrl =
        MaintenanceHttpTransport.strip(properties.logisticsBaseUrl().toString())
            + "/api/internal/logistics/v1/maintenance/equipment-movement-tasks";
    returnArrivalUrl =
        MaintenanceHttpTransport.strip(properties.logisticsBaseUrl().toString())
            + "/api/internal/logistics/v1/maintenance/return-arrivals/{rentalItemId}"
            + "?warehouseId={warehouseId}";
  }

  ReturnArrival returnArrival(UUID warehouseId, UUID rentalItemId) {
    if (warehouseId == null || rentalItemId == null) {
      throw new IllegalArgumentException("Return arrival identity is required");
    }
    try {
      ReturnArrivalResponse response =
          transport
              .client()
              .get()
              .uri(returnArrivalUrl, rentalItemId, warehouseId)
              .header(
                  HttpHeaders.AUTHORIZATION,
                  transport.bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
              .retrieve()
              .body(ReturnArrivalResponse.class);
      if (response == null
          || !warehouseId.equals(response.warehouseId())
          || !rentalItemId.equals(response.rentalItemId())
          || response.returnDocumentId() == null
          || response.arrivedAt() == null) {
        throw MaintenanceHttpTransport.malformed(
            "Logistics-service returned malformed return arrival truth");
      }
      return new ReturnArrival(
          response.warehouseId(),
          response.rentalItemId(),
          response.returnDocumentId(),
          response.arrivedAt());
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  PropertyEquipmentMovementTask createPropertyEquipmentMovementTask(
      UUID key, PropertyEquipmentMovementCommand command) {
    if (key == null || command == null) {
      throw new IllegalArgumentException("Property equipment movement identity is required");
    }
    PropertyEquipmentMovementTaskResponse response = transport.post(
        propertyEquipmentMovementTaskUrl,
        key,
        new CreatePropertyEquipmentMovementTaskRequest(
            command.decisionId(),
            command.warehouseId(),
            command.unitNumber(),
            command.plannedDurationMinutes(),
            command.deadlineAt(),
            command.lines().stream()
                .map(
                    line ->
                        new PropertyEquipmentMovementLineRequest(
                            line.equipmentId(),
                            line.sourceRentalItemId(),
                            line.expectedSourceBalanceVersion(),
                            line.quantity()))
                .toList()),
        PropertyEquipmentMovementTaskResponse.class,
        LOGISTICS_CLIENT,
        LOGISTICS_SCOPE);
    return propertyEquipmentMovementTask(response, command.warehouseId());
  }

  PropertyEquipmentMovementTask getPropertyEquipmentMovementTask(UUID taskId) {
    if (taskId == null) {
      throw new IllegalArgumentException("Property equipment movement task ID is required");
    }
    try {
      PropertyEquipmentMovementTaskResponse response = transport.client().get()
          .uri(propertyEquipmentMovementTaskUrl + "/" + taskId)
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
          .retrieve()
          .body(PropertyEquipmentMovementTaskResponse.class);
      if (response == null || !taskId.equals(response.id())) {
        throw MaintenanceHttpTransport.malformed(
            "Logistics-service returned another property equipment movement task");
      }
      return propertyEquipmentMovementTask(response, response.warehouseId());
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  /**
   * Accepts logistics' effective schedule at or after an immutable fixed request. The
   * warehouse-local upper fence is deliberately applied by the reconciliation owner immediately
   * before it confirms the durable work item.
   */
  DriverTaskSnapshot createDriverTask(UUID key, DriverTaskCommand command) {
    DriverTaskResponse response = transport.post(
        driverTaskIntakeUrl,
        key,
        command,
        DriverTaskResponse.class,
        LOGISTICS_CLIENT,
        LOGISTICS_SCOPE);
    if (response.id() == null
        || response.version() < 0
        || !command.warehouseId().equals(response.warehouseId())
        || !command.cabinId().equals(response.cabinId())
        || !command.repairId().equals(response.repairId())
        || !command.sourceType().equals(response.sourceType())
        || !command.sourceId().equals(response.sourceId())
        || !command.kind().equals(response.kind())
        || command.planningMode() != response.planningMode()
        || response.scheduledDate() == null
        || (command.planningMode()
                    == dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode.FIXED_DATE
            && response.scheduledDate().isBefore(command.scheduledDate()))
        || command.priority() != response.priority()
        || response.state() == null
        || response.state().isBlank()) {
      throw MaintenanceHttpTransport.malformed(
          "Logistics-service returned another driver-task truth");
    }
    return new DriverTaskSnapshot(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.cabinId(),
        response.repairId(),
        response.sourceType(),
        response.sourceId(),
        response.kind(),
        response.planningMode(),
        response.scheduledDate(),
        response.priority(),
        response.state());
  }

  MaintenanceDriverTaskCompensation maintenanceDriverTaskCompensation(
      UUID repairId, MaintenanceDriverTaskKind kind) {
    try {
      MaintenanceDriverTaskCompensationResponse response = transport.client().get()
          .uri(
              driverTaskIntakeUrl + "/repairs/{repairId}?kind={kind}",
              repairId,
              kind.name())
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
          .retrieve()
          .body(MaintenanceDriverTaskCompensationResponse.class);
      return compensation(response, repairId, kind);
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  MaintenanceDriverTaskCompensation cancelMaintenanceDriverTaskCompensation(
      UUID key, UUID repairId, MaintenanceDriverTaskKind kind) {
    try {
      MaintenanceDriverTaskCompensationResponse response = transport.client().post()
          .uri(
              driverTaskIntakeUrl + "/repairs/{repairId}/cancel?kind={kind}",
              repairId,
              kind.name())
          .header("Idempotency-Key", key.toString())
          .header(HttpHeaders.AUTHORIZATION, transport.bearer(LOGISTICS_CLIENT, LOGISTICS_SCOPE))
          .retrieve()
          .body(MaintenanceDriverTaskCompensationResponse.class);
      return compensation(response, repairId, kind);
    } catch (RuntimeException exception) {
      throw transport.dependencyFailure(exception);
    }
  }

  private static MaintenanceDriverTaskCompensation compensation(
      MaintenanceDriverTaskCompensationResponse response,
      UUID repairId,
      MaintenanceDriverTaskKind kind) {
    if (response == null
        || !repairId.equals(response.repairId())
        || response.kind() == null
        || response.outcome() == null) {
      throw MaintenanceHttpTransport.malformed(
          "Logistics-service returned malformed driver-task compensation truth");
    }
    try {
      if (kind != MaintenanceDriverTaskKind.valueOf(response.kind())) {
        throw MaintenanceHttpTransport.malformed(
            "Logistics-service returned another driver-task compensation kind");
      }
      return new MaintenanceDriverTaskCompensation(
          response.repairId(),
          kind,
          MaintenanceDriverTaskCompensationOutcome.valueOf(response.outcome()),
          response.taskId(),
          response.taskVersion(),
          response.state(),
          response.externalTaskId(),
          response.taskBoardTaskId(),
          response.taskBoardTaskVersion(),
          response.repairPlaceAllocationId(),
          response.repairPlaceAllocationVersion());
    } catch (IllegalArgumentException exception) {
      throw MaintenanceHttpTransport.malformed(
          "Logistics-service returned unknown driver-task compensation truth");
    }
  }

  private static PropertyEquipmentMovementTask propertyEquipmentMovementTask(
      PropertyEquipmentMovementTaskResponse response, UUID expectedWarehouseId) {
    if (response == null
        || response.id() == null
        || response.warehouseId() == null
        || !expectedWarehouseId.equals(response.warehouseId())
        || response.ownerType() == null
        || response.ownerType().isBlank()
        || response.ownerId() == null
        || response.state() == null
        || response.state().isBlank()) {
      throw MaintenanceHttpTransport.malformed(
          "Logistics-service returned malformed equipment movement task truth");
    }
    return new PropertyEquipmentMovementTask(
        response.id(),
        response.warehouseId(),
        response.ownerType(),
        response.ownerId(),
        response.state(),
        response.terminalState(),
        response.taskBoardDoneAt());
  }

  /**
   * Freezes one furniture movement line against its source cabin and observed asset balance
   * version before logistics creates physical work.
   */
  private record PropertyEquipmentMovementLineRequest(
      UUID equipmentId,
      UUID sourceRentalItemId,
      long expectedSourceBalanceVersion,
      long quantity) {}

  /**
   * Immutable logistics command derived from a maintenance disposition decision and its planned
   * furniture movement lines.
   */
  private record CreatePropertyEquipmentMovementTaskRequest(
      UUID decisionId,
      UUID warehouseId,
      String unitNumber,
      int plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<PropertyEquipmentMovementLineRequest> lines) {}

  /**
   * Logistics-owned movement-task snapshot, including task-board linkage and terminal failure
   * evidence used by maintenance reconciliation.
   */
  private record PropertyEquipmentMovementTaskResponse(
      UUID id,
      Long version,
      UUID warehouseId,
      String ownerType,
      UUID ownerId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      OffsetDateTime taskBoardDoneAt,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      String state,
      String terminalState,
      String failureCode,
      List<Object> lines,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  /**
   * Logistics-owned driver-task state returned after maintenance requests a repair transport
   * operation.
   */
  private record DriverTaskResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID cabinId,
      UUID repairId,
      String sourceType,
      UUID sourceId,
      String kind,
      dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode planningMode,
      LocalDate scheduledDate,
      int priority,
      String state) {}

  /**
   * Complete compensation receipt spanning the logistics task, its task-board projection and any
   * repair-place allocation so maintenance can recover a lost response idempotently.
   */
  private record MaintenanceDriverTaskCompensationResponse(
      UUID repairId,
      String kind,
      String outcome,
      UUID taskId,
      Long taskVersion,
      String state,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      Long taskBoardTaskVersion,
      UUID taskBoardEntryId,
      Long taskBoardEntryVersion,
      String taskBoardEntryStatus,
      String taskBoardStatus,
      String taskBoardLane,
      OffsetDateTime taskBoardDoneAt,
      UUID repairPlaceAllocationId,
      Long repairPlaceAllocationVersion) {}

  /** Private logistics response containing no customer or party data. */
  private record ReturnArrivalResponse(
      UUID warehouseId,
      UUID rentalItemId,
      UUID returnDocumentId,
      OffsetDateTime arrivedAt) {}
}
