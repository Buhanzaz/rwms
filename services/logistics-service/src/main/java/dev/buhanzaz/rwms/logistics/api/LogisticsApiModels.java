package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
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

public final class LogisticsApiModels {
  private LogisticsApiModels() {}

  public record CreateReturnRequest(
      @NotNull UUID warehouseId,
      UUID clientId,
      @Size(max = 512) String driverSnapshot,
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnLineRequest> lines) {
    public CreateReturnRequest(
        UUID warehouseId, UUID clientId, List<ReturnLineRequest> lines) {
      this(warehouseId, clientId, null, lines);
    }

    public CreateReturnRequest(UUID warehouseId, List<ReturnLineRequest> lines) {
      this(warehouseId, null, null, lines);
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
      @NotNull @Size(min = 1, max = 100) List<@Valid ShipmentLineRequest> lines) {
    public CreateShipmentRequest(
        UUID warehouseId,
        String partySnapshot,
        String driverSnapshot,
        List<ShipmentLineRequest> lines) {
      this(warehouseId, null, null, partySnapshot, driverSnapshot, lines);
    }
  }

  public record ShipmentPlanRequest(
      @NotBlank @Size(max = 512) String driverSnapshot,
      @NotNull LocalDate scheduledDate) {}

  public record ShipmentFurnitureTaskView(
      UUID rentalItemId, String unitNumber, UUID taskId, int lineCount) {}

  public record ShipmentFurnitureTaskResult(
      UUID shipmentId,
      long shipmentVersion,
      List<ShipmentFurnitureTaskView> tasks) {}

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
      int lineCount) {}

  public record ShipmentFurnitureReadinessView(
      UUID shipmentId,
      long shipmentVersion,
      ShipmentFurnitureReadinessState state,
      List<ShipmentFurnitureTaskStatusView> tasks) {}

  public record ReturnPickupRequest(
      @NotBlank @Size(max = 512) String driverSnapshot,
      @NotNull LocalDate scheduledDate) {}

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
      @NotNull
          @Size(max = 100)
          List<@NotNull @Valid CabinFurnitureRequirement> contents) {
    public CreateCabinFurnitureTaskRequest {
      contents = contents == null ? List.of() : contents;
    }
  }

  public record CabinFurnitureTaskResult(
      UUID rentalItemId, String unitNumber, UUID taskId, int lineCount) {}

  public record CreateTransferRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID destinationWarehouseId,
      @Size(max = 512) String driverSnapshot,
      @NotNull LocalDate scheduledDate,
      @NotNull @Size(min = 1, max = 100) List<@Valid TransferLineRequest> lines,
      @NotNull
          @Size(max = 100)
          List<@NotNull @Valid TransferFurnitureReplacementRequest> furnitureReplacements) {
    public CreateTransferRequest {
      furnitureReplacements = furnitureReplacements == null ? List.of() : furnitureReplacements;
    }
  }

  public record TransferLineRequest(@NotNull UUID assetId, @Min(0) long assetVersion) {}

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
      @NotNull
          @Size(max = 100)
          List<@NotNull @Valid CabinFurnitureRequirement> contents) {
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
      @NotNull @Size(max = 100) List<@Valid ReturnAdditionalEquipmentRequest>
          additionalEquipment) {
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
      UUID clientId,
      UUID equipmentMovementTaskId,
      LocalDate scheduledDate,
      OffsetDateTime scheduledAt,
      UUID rentalOrderId,
      UUID rentalShipmentId,
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
      UUID rentalOrderId) {}

  public record LogisticsDocumentView(
      UUID id,
      long version,
      LogisticsDocumentType documentType,
      LogisticsDocumentState state,
      UUID warehouseId,
      UUID destinationWarehouseId,
      String partySnapshot,
      String driverSnapshot,
      UUID clientId,
      UUID equipmentMovementTaskId,
      LocalDate scheduledDate,
      OffsetDateTime scheduledAt,
      UUID rentalOrderId,
      UUID rentalShipmentId,
      List<LogisticsLineView> lines,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
}
