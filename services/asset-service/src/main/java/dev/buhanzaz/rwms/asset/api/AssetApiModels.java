package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public final class AssetApiModels {
  private AssetApiModels() {}

  public record CreateRentalItemRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Size(max = 128) String number,
      @Size(max = 255) String rentalType,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String finishing,
      @Size(max = 255) String category,
      @Size(max = 2000) String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<@NotBlank @Size(max = 128) String> tags) {}

  public record UpdatePassportRequest(
      @NotNull @Min(0) Long expectedVersion,
      @Size(max = 255) String rentalType,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String finishing,
      @Size(max = 255) String category,
      @Size(max = 2000) String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<@NotBlank @Size(max = 128) String> tags) {}

  public record UpdateStatusRequest(@NotNull @Min(0) Long expectedVersion, @NotNull RentalItemStatus status) {}
  public record UpdateWarehouseRequest(@NotNull @Min(0) Long expectedVersion, @NotNull UUID warehouseId) {}
  public record UpdateGeneralCommentRequest(@NotNull @Min(0) Long expectedVersion, @Size(max = 4000) String comment) {}
  public record AddManualNoteRequest(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 4000) String text) {}

  public record RentalItemResponse(
      UUID id, long version, UUID warehouseId, String number, RentalItemStatus status,
      String rentalType, String dimensions, String finishing, String category, String characteristics,
      Boolean linoleum, String generalComment, Map<String, Object> passport, List<String> tags,
      List<EquipmentContentResponse> contents, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  public record RentalItemPage(List<RentalItemResponse> content, long page, long size, long totalElements, long totalPages) {}
  /** Text remains in the service-local append-only store and is never a Kafka fact. */
  public record ManualNoteResponse(UUID id, UUID rentalItemId, String text, OffsetDateTime createdAt) {}

  public record CreateEquipmentRequest(@NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 255) String name,
      @NotNull EquipmentCategory category, @Size(max = 2000) String comment) {}
  public record UpdateEquipmentRequest(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 64) String code,
      @NotBlank @Size(max = 255) String name, @NotNull EquipmentCategory category, boolean active, @Size(max = 2000) String comment) {}
  public record EquipmentResponse(UUID id, long version, String code, String name, EquipmentCategory category,
      boolean active, String comment, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  public record EquipmentContentResponse(UUID equipmentId, String equipmentCode, long quantity, BalanceLocationKind locationKind) {}
  public record EquipmentBalanceResponse(UUID id, long version, UUID equipmentId, UUID warehouseId, UUID rentalItemId,
      BalanceLocationKind locationKind, long quantity, long activeHeldQuantity, long availableStock) {}
  public record EquipmentTotalsResponse(UUID equipmentId, UUID warehouseId, long totalQuantity, long stockQuantity,
      long nonRentedCabinQuantity, long rentedCabinQuantity, long writtenOffQuantity, long lostQuantity, long activeHeldQuantity,
      long availableStock, List<EquipmentBalanceResponse> balances) {}
  public record EquipmentWarehouseResponse(EquipmentResponse equipment, EquipmentTotalsResponse totals) {}

  public record TransferEquipmentRequest(
      @NotNull UUID equipmentId,
      @NotNull UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      @NotNull BalanceLocationKind sourceLocationKind,
      @NotNull @Min(0) Long sourceExpectedVersion,
      @NotNull UUID targetWarehouseId,
      UUID targetRentalItemId,
      @NotNull BalanceLocationKind targetLocationKind,
      @NotNull @Min(0) Long targetExpectedVersion,
      @NotNull @Min(1) Long quantity) {}

  public record DispositionEquipmentRequest(
      @NotNull UUID equipmentId,
      @NotNull UUID warehouseId,
      UUID sourceRentalItemId,
      @NotNull BalanceLocationKind sourceLocationKind,
      @NotNull @Min(0) Long sourceExpectedVersion,
      @NotNull @Min(1) Long quantity,
      @NotNull Disposition disposition) {}
  public enum Disposition { WRITE_OFF, LOSS }
  public record MovementResponse(UUID id, long version, UUID equipmentId, UUID sourceBalanceId, UUID targetBalanceId,
      long quantity, String kind, OffsetDateTime occurredAt) {}
  public record EquipmentDispositionResponse(MovementResponse movement, String equipmentCode, String equipmentName) {}

  public record AcquireEquipmentHoldRequest(@NotNull UUID equipmentId, @NotNull UUID warehouseId,
      @NotBlank @Size(max = 64) String ownerType, @NotBlank @Size(max = 128) String ownerId,
      @NotNull @Min(1) Long quantity, @NotNull @Min(0) Long expectedStockVersion) {}
  public record RenewEquipmentHoldRequest(@NotNull @Min(0) Long expectedVersion) {}
  public record CommitEquipmentHoldRequest(@NotNull @Min(0) Long expectedVersion) {}
  public record ReleaseEquipmentHoldRequest(@NotNull @Min(0) Long expectedVersion) {}
  public record EquipmentHoldResponse(UUID id, long version, UUID equipmentId, UUID warehouseId, String ownerType,
      String ownerId, long quantity, String state, OffsetDateTime expiresAt, OffsetDateTime committedAt) {}

  public record AcquireOperationLeaseRequest(@NotNull UUID rentalItemId, @NotBlank @Size(max = 64) String ownerType,
      @NotBlank @Size(max = 128) String ownerId, @NotNull @Min(0) Long expectedRentalItemVersion) {}
  public record RenewOperationLeaseRequest(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Long fencingToken) {}
  public record ReleaseOperationLeaseRequest(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Long fencingToken) {}
  public record OperationLeaseResponse(UUID id, long version, UUID rentalItemId, String ownerType, String ownerId,
      long fencingToken, String state, OffsetDateTime expiresAt) {}
  public record FencedStatusRequest(@NotNull @Min(0) Long expectedVersion, @NotNull RentalItemStatus status,
      @NotNull UUID leaseId, @NotNull @Min(1) Long fencingToken) {}

  public record ClassifierRequest(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 32) String type,
      UUID parentId, @NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 255) String name,
      boolean active, @Min(0) Integer sortOrder) {}
  public record CreateClassifierRequest(@NotBlank @Size(max = 32) String type, UUID parentId,
      @NotBlank @Size(max = 64) String code, @NotBlank @Size(max = 255) String name, boolean active, @Min(0) Integer sortOrder) {}
  public record ClassifierResponse(UUID id, long version, String type, UUID parentId, String code, String name,
      boolean active, Integer sortOrder) {}
}
