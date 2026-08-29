package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.domain.TransferPlanState;
import dev.buhanzaz.rwms.logistics.domain.TransferReservationReadiness;
import dev.buhanzaz.rwms.logistics.domain.TransferResourceRepositionMode;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import tools.jackson.databind.JsonNode;

/**
 * Defines transport models for logistics HTTP endpoints; these values are not persistence entities.
 */
public final class LogisticsApiModels {
  private LogisticsApiModels() {}

  public record CreateReturnRequest(
      @NotNull UUID warehouseId,
      UUID clientId,
      @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnLineRequest> lines) {
    public CreateReturnRequest(
        UUID warehouseId, UUID clientId, String driverSnapshot, List<ReturnLineRequest> lines) {
      this(warehouseId, clientId, driverSnapshot, null, lines);
    }

    public CreateReturnRequest(UUID warehouseId, UUID clientId, List<ReturnLineRequest> lines) {
      this(warehouseId, clientId, null, null, lines);
    }

    public CreateReturnRequest(UUID warehouseId, List<ReturnLineRequest> lines) {
      this(warehouseId, null, null, null, lines);
    }
  }

  public record ReturnLineRequest(
      @NotNull UUID assetId,
      @Min(0) long assetVersion,
      @NotBlank @Size(max = 512) String tenantSnapshot,
      UUID rentalOrderId) {
    public ReturnLineRequest(UUID assetId, long assetVersion, String tenantSnapshot) {
      this(assetId, assetVersion, tenantSnapshot, null);
    }
  }

  public record CreateShipmentRequest(
      @NotNull UUID warehouseId,
      UUID clientId,
      UUID rentalOrderId,
      @NotBlank @Size(max = 512) String partySnapshot,
      @NotBlank @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull @Size(min = 1, max = 100) List<@Valid ShipmentLineRequest> lines) {
    public CreateShipmentRequest(
        UUID warehouseId,
        UUID clientId,
        UUID rentalOrderId,
        String partySnapshot,
        String driverSnapshot,
        List<ShipmentLineRequest> lines) {
      this(warehouseId, clientId, rentalOrderId, partySnapshot, driverSnapshot, null, lines);
    }

    public CreateShipmentRequest(
        UUID warehouseId,
        String partySnapshot,
        String driverSnapshot,
        List<ShipmentLineRequest> lines) {
      this(warehouseId, null, null, partySnapshot, driverSnapshot, null, lines);
    }
  }

  /** Direction of one imported rental fact that never creates driver work. */
  public enum HistoricalRentalMovementKind {
    SHIPMENT,
    RETURN
  }

  /**
   * Creates one real rental shipment or return from a verified historical operation date.
   *
   * <p>The client is a logistics-owned counterparty reference; the caller supplies the latest
   * cabin version observed in the warehouse card. A historical shipment may retain one selected
   * driver's opaque identity and display snapshot, while an unknown driver remains a null pair and
   * no driver task is created.
   */
  public record CreateHistoricalRentalMovementRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID rentalItemId,
      @NotNull @Min(0) Long expectedRentalItemVersion,
      @NotNull UUID clientId,
      @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull HistoricalRentalMovementKind kind,
      @NotNull LocalDate occurredOn) {}

  /** Version-fenced correction of one already recorded historical rental shipment and its driver. */
  public record UpdateHistoricalRentalShipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID rentalItemId,
      @NotNull UUID clientId,
      @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull LocalDate occurredOn) {}

  public record ShipmentPlanRequest(
      @NotBlank @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull LocalDate scheduledDate) {

    public ShipmentPlanRequest(String driverSnapshot, LocalDate scheduledDate) {
      this(driverSnapshot, null, scheduledDate);
    }
  }

  public record ShipmentFurnitureTaskView(
      UUID rentalItemId, String unitNumber, UUID taskId, int lineCount) {}

  public record ShipmentFurnitureTaskResult(
      UUID shipmentId, long shipmentVersion, List<ShipmentFurnitureTaskView> tasks) {}

  public enum ShipmentFurnitureReadinessState {
    NOT_REQUIRED,
    READY,
    REQUIRES_TASK_CREATION,
    AWAITING_TASK_COMPLETION,
    BLOCKED
  }

  public record ShipmentFurnitureTaskStatusView(
      UUID rentalItemId,
      String unitNumber,
      UUID taskId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      EquipmentMovementTaskState taskState,
      int lineCount,
      boolean movementTaskCreated,
      boolean movementTaskCompleted,
      boolean contentReady) {}

  public record ShipmentFurnitureReadinessView(
      UUID shipmentId,
      long shipmentVersion,
      ShipmentFurnitureReadinessState state,
      List<ShipmentFurnitureTaskStatusView> tasks) {}

  public record ReturnPickupRequest(
      @NotBlank @Size(max = 512) String driverSnapshot,
      UUID driverWorkerId,
      @NotNull LocalDate scheduledDate) {

    public ReturnPickupRequest(String driverSnapshot, LocalDate scheduledDate) {
      this(driverSnapshot, null, scheduledDate);
    }
  }

  public record EquipmentAllocationRequest(
      @NotNull UUID equipmentId, @Min(1) long quantity, @Min(0) long expectedStockVersion) {}

  public record ShipmentLineRequest(
      @NotNull UUID assetId,
      @Min(0) long assetVersion,
      @NotNull @Size(max = 100) List<@Valid EquipmentAllocationRequest> allocations) {
    public ShipmentLineRequest(UUID assetId, long assetVersion) {
      this(assetId, assetVersion, List.of());
    }
  }

  /** One desired furniture total. Omitting a current item removes it from the selected cabin. */
  public record CabinFurnitureRequirement(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  public record CreateCabinFurnitureTaskRequest(
      @NotNull UUID warehouseId,
      @NotNull LocalDate scheduledDate,
      @NotNull @Size(max = 100) List<@NotNull @Valid CabinFurnitureRequirement> contents) {
    public CreateCabinFurnitureTaskRequest {
      contents = contents == null ? List.of() : contents;
    }
  }

  public record CabinFurnitureTaskResult(
      UUID rentalItemId, String unitNumber, UUID taskId, int lineCount) {}

  public record CreateTransferRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID destinationWarehouseId,
      @NotNull LocalDate scheduledDate,
      @Size(max = 100) List<@Valid TransferLineRequest> lines,
      @NotNull @Size(max = 100)
          List<@NotNull @Valid TransferFurnitureReplacementRequest> furnitureReplacements,
      @Valid TransferPlanRequest plan) {
    public CreateTransferRequest {
      lines = lines == null ? List.of() : lines;
      furnitureReplacements = furnitureReplacements == null ? List.of() : furnitureReplacements;
    }

    /** Retains the original concrete-line Java client constructor and HTTP payload semantics. */
    public CreateTransferRequest(
        UUID warehouseId,
        UUID destinationWarehouseId,
        LocalDate scheduledDate,
        List<TransferLineRequest> lines,
        List<TransferFurnitureReplacementRequest> furnitureReplacements) {
      this(
          warehouseId,
          destinationWarehouseId,
          scheduledDate,
          lines,
          furnitureReplacements,
          null);
    }
  }

  public record TransferLineRequest(@NotNull UUID assetId, @Min(0) long assetVersion) {}

  /** Complete editable planning detail for an interwarehouse transfer draft. */
  public record TransferPlanRequest(
      OffsetDateTime plannedDepartureAt,
      OffsetDateTime plannedArrivalAt,
      @Size(max = 2_000) String logisticsComment,
      UUID tripDriverId,
      UUID tripVehicleId,
      @Valid TransferResourceRepositionRequest driverReposition,
      @Valid TransferResourceRepositionRequest vehicleReposition,
      @NotNull @Size(max = 100) List<@NotNull @Valid TransferCabinGroupRequest> cabinGroups,
      @NotNull @Size(max = 100)
          List<@NotNull @Valid TransferLooseFurnitureRequest> looseFurniture) {
    public TransferPlanRequest {
      cabinGroups = cabinGroups == null ? List.of() : cabinGroups;
      looseFurniture = looseFurniture == null ? List.of() : looseFurniture;
    }
  }

  /** Version-independent post-arrival intent for one driver or vehicle. */
  public record TransferResourceRepositionRequest(
      UUID resourceId, @NotNull TransferResourceRepositionMode mode, OffsetDateTime until) {}

  /** One catalog-driven cabin requirement group with optional concrete allocation. */
  public record TransferCabinGroupRequest(
      @NotNull UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      @NotNull @Size(max = 100) List<@NotNull UUID> characteristicIds,
      Boolean linoleum,
      @NotNull @Min(1) Integer quantity,
      @NotNull @Size(max = 100)
          List<@NotNull @Valid TransferFurniturePerCabinRequest> furniturePerCabin,
      @NotNull @Size(max = 100)
          List<@NotNull @Valid TransferCabinAllocationRequest> allocatedCabins) {
    public TransferCabinGroupRequest {
      characteristicIds = characteristicIds == null ? List.of() : characteristicIds;
      furniturePerCabin = furniturePerCabin == null ? List.of() : furniturePerCabin;
      allocatedCabins = allocatedCabins == null ? List.of() : allocatedCabins;
    }
  }

  /** Furniture quantity required on each cabin of one group. */
  public record TransferFurniturePerCabinRequest(
      @NotNull UUID furnitureCatalogItemId, @NotNull @Min(1) Long quantityPerCabin) {}

  /** Concrete cabin selected from the source warehouse for one requirement group. */
  public record TransferCabinAllocationRequest(
      @NotNull UUID assetId, @NotNull @Min(0) Long assetVersion) {}

  /** Furniture transported as independent cargo rather than cabin composition. */
  public record TransferLooseFurnitureRequest(
      @NotNull UUID furnitureCatalogItemId, @NotNull @Min(1) Long quantity) {}

  /** Complete replacement of an editable transfer plan and its warehouse-local schedule date. */
  public record UpdateTransferPlanRequest(
      @NotNull LocalDate scheduledDate, @NotNull @Valid TransferPlanRequest plan) {}

  /** Read view of a transfer plan, including calculated totals and explicit reservation readiness. */
  public record TransferPlanView(
      UUID transferId,
      long documentVersion,
      LogisticsDocumentState documentState,
      UUID planId,
      Long planVersion,
      TransferPlanState state,
      TransferReservationReadiness reservationReadiness,
      String readinessDetail,
      boolean legacyCompatible,
      LocalDate scheduledDate,
      OffsetDateTime plannedDepartureAt,
      OffsetDateTime plannedArrivalAt,
      String logisticsComment,
      UUID tripDriverId,
      UUID tripVehicleId,
      TransferResourceIntentView driverReposition,
      TransferResourceIntentView vehicleReposition,
      List<TransferCabinGroupView> cabinGroups,
      List<TransferLooseFurnitureView> looseFurniture,
      int totalCabinCount,
      List<TransferFurnitureTotalView> furnitureTotals) {}

  /** Read view of one post-arrival driver or vehicle assignment intent. */
  public record TransferResourceIntentView(
      UUID resourceId, TransferResourceRepositionMode mode, OffsetDateTime until) {}

  /** Read view of one ordered cabin requirement group. */
  public record TransferCabinGroupView(
      UUID groupId,
      int position,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds,
      Boolean linoleum,
      int quantity,
      List<TransferFurniturePerCabinView> furniturePerCabin,
      List<TransferCabinAllocationView> allocatedCabins) {}

  /** Calculated per-cabin and whole-group furniture quantity. */
  public record TransferFurniturePerCabinView(
      UUID furnitureCatalogItemId, long quantityPerCabin, long totalQuantity) {}

  /** Concrete allocated cabin and the asset version selected by the operator. */
  public record TransferCabinAllocationView(UUID assetId, long assetVersion) {}

  /** Independent furniture cargo read view. */
  public record TransferLooseFurnitureView(UUID furnitureCatalogItemId, long quantity) {}

  /** Combined required furniture total without interpreting attached physical cabin contents. */
  public record TransferFurnitureTotalView(
      UUID furnitureCatalogItemId,
      long cabinRequirementQuantity,
      long looseQuantity,
      long totalQuantity) {}

  public enum TransferFurnitureReadinessState {
    NOT_REQUIRED,
    READY,
    AWAITING_TASK_COMPLETION,
    BLOCKED
  }

  public record TransferFurnitureTaskStatusView(
      UUID rentalItemId,
      String unitNumber,
      UUID taskId,
      UUID externalTaskId,
      UUID taskBoardTaskId,
      EquipmentMovementTaskState taskState,
      int lineCount) {}

  public record TransferFurnitureReadinessView(
      UUID transferId,
      long transferVersion,
      TransferFurnitureReadinessState state,
      List<TransferFurnitureTaskStatusView> tasks) {}

  /** The selected complete furniture composition for one cabin in this transfer. */
  public record TransferFurnitureReplacementRequest(
      @NotNull UUID assetId,
      @NotNull @Size(max = 100) List<@NotNull @Valid CabinFurnitureRequirement> contents) {
    public TransferFurnitureReplacementRequest {
      contents = contents == null ? List.of() : contents;
    }
  }

  public record ArriveTransferLineRequest(
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> references,
      @Min(1) @Max(5) Integer priority) {}

  public record TransferArrivalPreflightView(
      UUID transferId,
      UUID lineId,
      UUID activeRepairId,
      boolean priorityRequired,
      List<UUID> missingQueueDefinitionIds) {}

  public record MediaReferenceInput(@NotNull UUID mediaId, @Min(1) long generation) {}

  public record ReturnAdditionalEquipmentRequest(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  public record ReturnMediaLineRequest(
      @NotNull UUID lineId,
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> references,
      @NotNull @AssertTrue Boolean equipmentConfirmed,
      @NotNull @Size(max = 100) List<@Valid ReturnAdditionalEquipmentRequest> additionalEquipment) {
    public ReturnMediaLineRequest {
      additionalEquipment = additionalEquipment == null ? List.of() : additionalEquipment;
    }
  }

  public record AcceptReturnRequest(
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnMediaLineRequest> lines) {}

  public record ReturnEstimateLineRequest(
      @NotNull UUID lineId,
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> references) {}

  public record StartReturnEstimatesRequest(
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnEstimateLineRequest> lines) {}

  public record ReconcileRequest(@NotBlank @Size(max = 500) String reason) {}

  public record LogisticsDocumentSummary(
      UUID id,
      long version,
      LogisticsDocumentType documentType,
      LogisticsDocumentState state,
      UUID warehouseId,
      UUID destinationWarehouseId,
      String partySnapshot,
      String driverSnapshot,
      UUID driverWorkerId,
      UUID clientId,
      boolean historicalRentalImport,
      UUID equipmentMovementTaskId,
      LocalDate scheduledDate,
      UUID rentalOrderId,
      UUID rentalShipmentId,
      UUID inventorySourceId,
      UUID inventorySourceFindingId,
      String inventorySourceDispositionKind,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record LogisticsLineView(
      UUID id,
      long version,
      int lineNumber,
      UUID assetId,
      long assetVersion,
      LogisticsLineState state,
      String tenantSnapshot,
      UUID rentalOrderId,
      UUID inventorySourceWarehouseId,
      JsonNode inventoryShipmentFurniture) {}

  public record LogisticsDocumentView(
      UUID id,
      long version,
      LogisticsDocumentType documentType,
      LogisticsDocumentState state,
      UUID warehouseId,
      UUID destinationWarehouseId,
      String partySnapshot,
      String driverSnapshot,
      UUID driverWorkerId,
      UUID clientId,
      boolean historicalRentalImport,
      UUID equipmentMovementTaskId,
      LocalDate scheduledDate,
      UUID rentalOrderId,
      UUID rentalShipmentId,
      UUID inventorySourceId,
      UUID inventorySourceFindingId,
      String inventorySourceDispositionKind,
      List<LogisticsLineView> lines,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
}
