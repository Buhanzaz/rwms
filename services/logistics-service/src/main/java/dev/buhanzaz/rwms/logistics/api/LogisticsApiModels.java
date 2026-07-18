package dev.buhanzaz.rwms.logistics.api;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public final class LogisticsApiModels {
  private LogisticsApiModels() {}

  public record CreateReturnRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnLineRequest> lines) {}

  public record ReturnLineRequest(
      @NotNull UUID assetId,
      @Min(0) long assetVersion,
      @NotBlank @Size(max = 512) String tenantSnapshot) {}

  public record CreateShipmentRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Size(max = 512) String partySnapshot,
      @NotBlank @Size(max = 512) String driverSnapshot,
      @NotNull @Size(min = 1, max = 100) List<@Valid ShipmentLineRequest> lines) {}

  public record ShipmentPlanRequest(
      @NotBlank @Size(max = 512) String partySnapshot,
      @NotBlank @Size(max = 512) String driverSnapshot,
      @NotNull @Size(min = 1, max = 100) List<@Valid ShipmentLineRequest> lines) {}

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

  public record CreateTransferRequest(
      @NotNull UUID warehouseId,
      @NotNull UUID destinationWarehouseId,
      @NotNull @Size(min = 1, max = 100) List<@Valid TransferLineRequest> lines) {}

  public record TransferLineRequest(@NotNull UUID assetId, @Min(0) long assetVersion) {}

  public record ArriveTransferLineRequest(
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> references) {}

  public record MediaReferenceInput(@NotNull UUID mediaId, @Min(1) long generation) {}

  public record ReturnMediaLineRequest(
      @NotNull UUID lineId,
      @NotNull @Size(min = 1, max = 20) List<@Valid MediaReferenceInput> references) {}

  public record AcceptReturnRequest(
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnMediaLineRequest> lines) {}

  public record EquipmentShortageRequest(@NotNull UUID equipmentId, @Min(1) long missingQuantity) {}

  public record ReturnShortageLineRequest(
      @NotNull UUID lineId,
      @NotNull @Size(min = 1, max = 100) List<@Valid EquipmentShortageRequest> shortages) {}

  public record RequestReturnEstimateRequest(
      @NotNull @Size(min = 1, max = 100) List<@Valid ReturnShortageLineRequest> lines) {}

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
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record LogisticsLineView(
      UUID id,
      long version,
      int lineNumber,
      UUID assetId,
      long assetVersion,
      LogisticsLineState state,
      String tenantSnapshot) {}

  public record LogisticsDocumentView(
      UUID id,
      long version,
      LogisticsDocumentType documentType,
      LogisticsDocumentState state,
      UUID warehouseId,
      UUID destinationWarehouseId,
      String partySnapshot,
      String driverSnapshot,
      List<LogisticsLineView> lines,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}
}
