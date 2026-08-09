package dev.buhanzaz.rwms.asset.api;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * HTTP transport model container for asset.
 * Its records are boundary representations, not persistence entities.
 */
public final class AssetApiModels {
  private AssetApiModels() {}

  public record CreateRentalItemRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Size(max = 128) String number,
      @NotNull UUID rentalTypeId,
      @NotNull UUID dimensionId,
      @NotNull UUID finishingId,
      @Size(max = 255) String category,
      @NotNull @Size(max = 100) List<@NotNull UUID> characteristicIds,
      @NotNull Boolean linoleum,
      Map<String, Object> passport,
      List<@NotBlank @Size(max = 128) String> tags) {}

  /** UUID travels in the API; the panel renders only {@link #name()}. */
  public record CabinCatalogValueResponse(UUID id, String name) {}

  public record CabinCatalogItemResponse(
      UUID id,
      long version,
      CabinCatalogKind kind,
      String name,
      boolean active,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record CabinTypeDimensionResponse(UUID typeId, UUID dimensionId, int sortOrder) {}

  public record CabinSettingsResponse(
      List<CabinCatalogItemResponse> types,
      List<CabinCatalogItemResponse> dimensions,
      List<CabinCatalogItemResponse> finishings,
      List<CabinCatalogItemResponse> categories,
      List<CabinCatalogItemResponse> characteristics,
      List<CabinTypeDimensionResponse> typeDimensions) {
    public CabinSettingsResponse(
        List<CabinCatalogItemResponse> types,
        List<CabinCatalogItemResponse> dimensions,
        List<CabinCatalogItemResponse> finishings,
        List<CabinCatalogItemResponse> characteristics,
        List<CabinTypeDimensionResponse> typeDimensions) {
      this(types, dimensions, finishings, List.of(), characteristics, typeDimensions);
    }
  }

  public record CreateCabinCatalogItemRequest(
      @NotNull CabinCatalogKind kind,
      @NotBlank @Size(max = 255) String name) {}

  public record UpdateCabinCatalogItemRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 255) String name,
      boolean active) {}

  public record ReplaceCabinTypeDimensionsRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Size(max = 100) List<@NotNull UUID> dimensionIds) {}

  public record RentalItemCreationOptionsResponse(
      String newCategory,
      List<String> usedCategories,
      List<CabinCatalogValueResponse> rentalTypes,
      List<CabinCatalogValueResponse> dimensions,
      List<CabinCatalogValueResponse> finishings,
      List<CabinCatalogValueResponse> categories,
      List<CabinCatalogValueResponse> characteristics,
      List<CabinTypeDimensionResponse> typeDimensions) {
    public RentalItemCreationOptionsResponse(
        String newCategory,
        List<String> usedCategories,
        List<CabinCatalogValueResponse> rentalTypes,
        List<CabinCatalogValueResponse> dimensions,
        List<CabinCatalogValueResponse> finishings,
        List<CabinCatalogValueResponse> characteristics,
        List<CabinTypeDimensionResponse> typeDimensions) {
      this(
          newCategory,
          usedCategories,
          rentalTypes,
          dimensions,
          finishings,
          List.of(),
          characteristics,
          typeDimensions);
    }
  }

  public record UpdatePassportRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID rentalTypeId,
      @NotNull UUID dimensionId,
      @NotNull UUID finishingId,
      @Size(max = 255) String category,
      @NotNull @Size(max = 100) List<@NotNull UUID> characteristicIds,
      @NotNull Boolean linoleum,
      Map<String, Object> passport,
      List<@NotBlank @Size(max = 128) String> tags) {}

  public record UpdateStatusRequest(@NotNull @Min(0) Long expectedVersion, @NotNull RentalItemStatus status) {}
  public record UpdateGeneralCommentRequest(@NotNull @Min(0) Long expectedVersion, @Size(max = 4000) String comment) {}
  public record AddManualNoteRequest(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 4000) String text) {}

  public record RentalItemResponse(
      UUID id, long version, UUID warehouseId, String number, RentalItemStatus status,
      UUID rentalTypeId, String rentalType,
      UUID dimensionId, String dimensions,
      UUID finishingId, String finishing,
      String category, List<CabinCatalogValueResponse> characteristics,
      Boolean linoleum, String generalComment, Map<String, Object> passport, List<String> tags,
      List<EquipmentContentResponse> contents,
      ActiveOrderReservationResponse activeOrderReservation,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  public record ActiveOrderReservationResponse(
      UUID reservationId,
      UUID orderId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime reservedAt) {}

  public record RentalItemPage(List<RentalItemResponse> content, long page, long size, long totalElements, long totalPages) {}
  /** Text remains in the service-local append-only store and is never a Kafka fact. */
  public record ManualNoteResponse(UUID id, UUID rentalItemId, String text, OffsetDateTime createdAt) {}

  public record CreateEquipmentRequest(
      @NotBlank @Size(max = 255) String name,
      @NotNull EquipmentCategory category,
      @Size(max = 2000) String comment) {}
  public record UpdateEquipmentRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotBlank @Size(max = 255) String name,
      @NotNull EquipmentCategory category,
      boolean active,
      @Size(max = 2000) String comment) {}
  public record EquipmentResponse(UUID id, long version, String name, EquipmentCategory category,
      boolean active, String comment, OffsetDateTime createdAt, OffsetDateTime updatedAt) {}

  public record EquipmentContentResponse(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      BalanceLocationKind locationKind) {}
  public record EquipmentBalanceResponse(UUID id, long version, UUID equipmentId, UUID warehouseId, UUID rentalItemId,
      BalanceLocationKind locationKind, long quantity, long activeHeldQuantity, boolean allocatable,
      long availableStock) {}
  public record EquipmentTotalsResponse(UUID equipmentId, UUID warehouseId, long totalQuantity, long stockQuantity,
      long nonRentedCabinQuantity, long rentedCabinQuantity, long writtenOffQuantity, long lostQuantity, long activeHeldQuantity,
      long reservedQuantity, long availableQuantity, long availableStock, List<EquipmentBalanceResponse> balances) {}
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
  public record EquipmentDispositionResponse(MovementResponse movement, String equipmentName) {}

  public record AcquireEquipmentHoldRequest(@NotNull UUID equipmentId, @NotNull UUID warehouseId,
      @NotBlank @Size(max = 64) String ownerType, @NotBlank @Size(max = 128) String ownerId,
      @NotNull @Min(1) Long quantity, @NotNull @Min(0) Long expectedStockVersion) {}
  public record RenewEquipmentHoldRequest(@NotNull @Min(0) Long expectedVersion) {}
  public record CommitEquipmentHoldRequest(@NotNull @Min(0) Long expectedVersion) {}
  public record ReleaseEquipmentHoldRequest(@NotNull @Min(0) Long expectedVersion) {}
  public record EquipmentHoldResponse(UUID id, long version, UUID equipmentId, UUID warehouseId, String ownerType,
      String ownerId, UUID sourceBalanceId, long quantity, String state, OffsetDateTime expiresAt,
      OffsetDateTime committedAt, OffsetDateTime executedAt) {}

  public record AcquireOperationLeaseRequest(@NotNull UUID rentalItemId, @NotBlank @Size(max = 64) String ownerType,
      @NotBlank @Size(max = 128) String ownerId, @NotNull @Min(0) Long expectedRentalItemVersion) {}
  public record RenewOperationLeaseRequest(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Long fencingToken) {}
  public record ReleaseOperationLeaseRequest(@NotNull @Min(0) Long expectedVersion, @NotNull @Min(1) Long fencingToken) {}
  public record OperationLeaseResponse(UUID id, long version, UUID rentalItemId, String ownerType, String ownerId,
      long fencingToken, String state, OffsetDateTime expiresAt) {}
  public record FencedStatusRequest(@NotNull @Min(0) Long expectedVersion, @NotNull RentalItemStatus status,
      @NotNull UUID leaseId, @NotNull @Min(1) Long fencingToken) {}

  public enum MaintenanceLeaseOwnerType { MAINTENANCE_ESTIMATE, MAINTENANCE_REPAIR }
  public enum MaintenanceStatusAction {
    QUEUE_FOR_REPAIR,
    QUEUE_FOR_CAPITAL_REPAIR,
    COMPLETE_EMPTY_ESTIMATE,
    COMPLETE_EMPTY_REPAIR,
    MARK_PENDING_ACCEPTANCE,
    ACCEPT_REPAIR,
    WRITE_OFF
  }
  /** Read projection exposes the canonical cabin number but excludes passport, comments and equipment. */
  public record MaintenanceRentalItemSnapshot(
      UUID id, long version, UUID warehouseId, String number, RentalItemStatus status) {}
  public record EnsureMaintenanceFurnitureEquipmentRequest(
      @NotNull UUID externalReferenceId,
      @NotBlank @Size(max = 255) String equipmentName) {}
  public record MaintenanceFurnitureEquipmentResponse(
      UUID externalReferenceId, UUID equipmentId, String equipmentName) {}
  public record AcquireMaintenanceOperationLeaseRequest(
      @NotNull UUID rentalItemId,
      @NotNull MaintenanceLeaseOwnerType ownerType,
      @NotNull UUID ownerId,
      @NotNull @Min(0) Long expectedRentalItemVersion) {}
  public record RenewMaintenanceOperationLeaseRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) Long fencingToken,
      @NotNull MaintenanceLeaseOwnerType ownerType,
      @NotNull UUID ownerId) {}
  public record ReleaseMaintenanceOperationLeaseRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) Long fencingToken,
      @NotNull MaintenanceLeaseOwnerType ownerType,
      @NotNull UUID ownerId) {}
  /**
   * Exact cabin balance selected by a maintenance estimate or direct repair.
   * The selection removes the quantity from the cabin into asset's durable
   * pending-return custody ledger; it is deliberately not a terminal loss.
   */
  public record MaintenanceFurniturePendingReturn(
      @NotNull UUID equipmentId,
      @NotNull @Min(0) Long expectedSourceBalanceVersion,
      @Min(1) long quantity) {}
  public record MaintenanceFencedStatusRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull MaintenanceStatusAction action,
      @NotNull UUID leaseId,
      @NotNull @Min(1) Long fencingToken,
      @NotNull MaintenanceLeaseOwnerType ownerType,
      @NotNull UUID ownerId,
      UUID linkedReturnEstimateId,
      @NotNull @Valid List<@Valid MaintenanceFurniturePendingReturn> furniturePendingReturns) {
    public MaintenanceFencedStatusRequest(
        Long expectedVersion,
        MaintenanceStatusAction action,
        UUID leaseId,
        Long fencingToken,
        MaintenanceLeaseOwnerType ownerType,
        UUID ownerId,
        UUID linkedReturnEstimateId) {
      this(
          expectedVersion,
          action,
          leaseId,
          fencingToken,
          ownerType,
          ownerId,
          linkedReturnEstimateId,
          List.of());
    }
  }

  /**
   * Asset-owned custody truth linked to its maintenance owner. Quantities are
   * removed from a cabin before repair work, but become terminal only through
   * a separately approved property disposition.
   */
  public record MaintenanceFurnitureCustodyClaim(
      UUID id,
      long custodyVersion,
      MaintenanceLeaseOwnerType ownerType,
      UUID ownerId,
      UUID rentalItemId,
      UUID warehouseId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long sourceBalanceVersion,
      long quantity,
      long returnedToStockQuantity,
      long preparedDispositionQuantity,
      long terminalDispositionQuantity,
      long unresolvedQuantity,
      long availableForDispositionQuantity,
      OffsetDateTime selectedAt) {}

  public record ReturnMaintenanceFurnitureCustodyToStockRequest(
      @NotNull @Min(0) Long expectedCustodyVersion,
      @NotNull @Min(0) Long expectedStockBalanceVersion,
      @Min(1) long quantity,
      @NotNull UUID returnReferenceId) {}

  public record MaintenanceFurnitureCustodyReturnReceipt(
      UUID claimId,
      long custodyVersion,
      UUID returnReferenceId,
      UUID stockBalanceId,
      long stockBalanceVersion,
      long stockQuantity,
      long quantity,
      OffsetDateTime returnedAt) {}

  /**
   * The only operation-lease owners accepted from logistics. The service
   * derives the persisted owner ID from documentId and lineId rather than
   * trusting an arbitrary owner string.
   */
  public enum LogisticsLeaseOwnerType {
    LOGISTICS_RETURN,
    LOGISTICS_SHIPMENT,
    LOGISTICS_TRANSFER
  }

  /**
   * A closed canonical-effect vocabulary. Logistics cannot submit a target
   * rental status directly.
   */
  public enum LogisticsRentalItemAction {
    RETURN_INTAKE,
    RETURN_SETTLE_FREE,
    RETURN_SETTLE_SHORTAGE,
    SHIPMENT_CONFIRM,
    TRANSFER_DEPART,
    TRANSFER_ARRIVE
  }

  /** Closed transfer-restoration vocabulary; other statuses never cross this boundary. */
  public enum TransferAssetStatus {
    FREE,
    REPAIR
  }

  /** Narrow logistics projection; the number is the canonical driver-card label. */
  public record LogisticsRentalItemSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      List<LogisticsEquipmentContentSnapshot> contents) {}
  public record LogisticsEquipmentContentSnapshot(UUID equipmentId, long quantity) {}

  public record AcquireLogisticsOperationLeaseRequest(
      @NotNull UUID rentalItemId,
      @NotNull LogisticsLeaseOwnerType ownerType,
      @NotNull UUID documentId,
      @NotNull UUID lineId,
      @NotNull @Min(0) Long expectedRentalItemVersion,
      UUID rentalOrderId) {
    public AcquireLogisticsOperationLeaseRequest(
        UUID rentalItemId,
        LogisticsLeaseOwnerType ownerType,
        UUID documentId,
        UUID lineId,
        Long expectedRentalItemVersion) {
      this(
          rentalItemId,
          ownerType,
          documentId,
          lineId,
          expectedRentalItemVersion,
          null);
    }
  }
  public record LogisticsLeaseCommandRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull @Min(1) Long fencingToken,
      @NotNull LogisticsLeaseOwnerType ownerType,
      @NotNull UUID documentId,
      @NotNull UUID lineId) {}
  public record LogisticsOperationLeaseResponse(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  /**
   * Immutable physical receipt for furniture found in addition to the cabin's
   * canonical contents during a completed rental return.
   */
  public record LogisticsReturnEquipmentReceiptRequest(
      @NotNull UUID returnId,
      @NotNull UUID returnLineId,
      @NotNull UUID warehouseId,
      @NotEmpty @Size(max = 100) List<@NotNull @Valid LogisticsReturnEquipmentReceiptLine> lines) {}

  public record LogisticsReturnEquipmentReceiptLine(
      @NotNull UUID equipmentId, @NotNull @Min(1) Long quantity) {}

  public record LogisticsReturnEquipmentReceiptLineResponse(
      UUID receiptId,
      UUID equipmentId,
      long quantity,
      UUID stockBalanceId,
      long stockBalanceVersion,
      long stockQuantity) {}

  public record LogisticsReturnEquipmentReceiptResponse(
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<LogisticsReturnEquipmentReceiptLineResponse> lines) {}

  public record LogisticsFencedEffectRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull LogisticsRentalItemAction action,
      @NotNull UUID leaseId,
      @NotNull @Min(1) Long fencingToken,
      @NotNull LogisticsLeaseOwnerType ownerType,
      @NotNull UUID documentId,
      @NotNull UUID lineId,
      UUID destinationWarehouseId,
      TransferAssetStatus transferAssetStatus) {
    public LogisticsFencedEffectRequest(
        Long expectedVersion,
        LogisticsRentalItemAction action,
        UUID leaseId,
        Long fencingToken,
        LogisticsLeaseOwnerType ownerType,
        UUID documentId,
        UUID lineId,
        UUID destinationWarehouseId) {
      this(
          expectedVersion,
          action,
          leaseId,
          fencingToken,
          ownerType,
          documentId,
          lineId,
          destinationWarehouseId,
          null);
    }
  }

  public record MaintenanceCharacteristicApplicationResponse(
      UUID rentalItemId,
      UUID characteristicId,
      boolean added,
      long rentalItemVersion) {}

  /**
   * Equipment reservation belongs to a shipment line only. Asset-service
   * keeps the physical balance and ledger mutation authoritative.
   */
  public record AcquireLogisticsEquipmentHoldRequest(
      @NotNull UUID equipmentId,
      @NotNull UUID warehouseId,
      @NotNull UUID shipmentId,
      @NotNull UUID shipmentLineId,
      @NotNull @Min(1) Long quantity,
      @NotNull @Min(0) Long expectedStockVersion) {}
  public record LogisticsEquipmentHoldCommandRequest(
      @NotNull @Min(0) Long expectedVersion,
      @NotNull UUID shipmentId,
      @NotNull UUID shipmentLineId) {}
  public record LogisticsEquipmentHoldResponse(
      UUID holdId,
      long version,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt) {}

  /**
   * Equipment movement reservations are a closed logistics-owned hold type.
   * The caller never selects another owner type; asset derives it from the
   * movement and line identifiers supplied on every command.
   */
  public enum LogisticsEquipmentMovementReservationOwnerType {
    LOGISTICS_EQUIPMENT_MOVEMENT,
    MAINTENANCE_DISPOSITION_MOVEMENT
  }

  public enum LogisticsEquipmentMovementPurpose {
    ALLOCATABLE_REBALANCE,
    MAINTENANCE_DISPOSITION
  }

  /**
   * Reserves one exact physical source balance until the task deadline. The
   * deadline is not a service TTL and therefore is never silently extended.
   */
  public record AcquireLogisticsEquipmentMovementReservationRequest(
      @NotNull UUID movementId,
      @NotNull UUID lineId,
      @NotNull LogisticsEquipmentMovementPurpose purpose,
      @NotNull UUID equipmentId,
      @NotNull UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      @NotNull BalanceLocationKind sourceLocationKind,
      @NotNull @Min(0) Long expectedSourceBalanceVersion,
      @NotNull @Min(1) Long quantity,
      @NotNull OffsetDateTime reservedUntil) {}

  /** Exact owner and reservation-version CAS used when cancelling a planned move. */
  public record LogisticsEquipmentMovementReservationCommandRequest(
      @NotNull @Min(0) Long expectedReservationVersion,
      @NotNull UUID movementId,
      @NotNull UUID lineId) {}

  /**
   * The target deliberately has no version precondition: it may legitimately
   * receive independent stock while a worker is carrying out this task. The
   * source is fenced by the active reservation and its reservation CAS.
   */
  public record ExecuteLogisticsEquipmentMovementReservationsRequest(
      @NotNull UUID movementId,
      @NotEmpty List<@NotNull @Valid ExecuteLogisticsEquipmentMovementReservationLine> lines) {}

  public record ExecuteLogisticsEquipmentMovementReservationLine(
      @NotNull UUID reservationId,
      @NotNull @Min(0) Long expectedReservationVersion,
      @NotNull UUID lineId,
      @NotNull UUID targetWarehouseId,
      UUID targetRentalItemId,
      @NotNull BalanceLocationKind targetLocationKind) {}

  public record LogisticsEquipmentMovementReservationResponse(
      UUID reservationId,
      long version,
      LogisticsEquipmentMovementReservationOwnerType ownerType,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      BalanceLocationKind sourceLocationKind,
      long quantity,
      String state,
      OffsetDateTime reservedUntil,
      OffsetDateTime executedAt) {}

  public record LogisticsEquipmentMovementExecutionLine(
      UUID reservationId,
      long reservationVersion,
      UUID lineId,
      MovementResponse movement) {}

  public record LogisticsEquipmentMovementExecutionResponse(
      UUID movementId,
      List<LogisticsEquipmentMovementExecutionLine> lines) {}

  public record InventoryCaptureRequest(
      @NotNull UUID operationId,
      @NotNull @Positive Long technicalAttempt,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String requestFingerprint,
      @NotNull UUID warehouseId) {}
  public record InventoryCaptureResponse(
      UUID captureId,
      UUID operationId,
      long technicalAttempt,
      UUID warehouseId,
      long totalCount,
      String membershipDigest,
      OffsetDateTime createdAt,
      OffsetDateTime expiresAt) {}
  public record InventoryCaptureMember(
      long sequence,
      UUID assetId,
      long version,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      Map<String, Object> passportSnapshot,
      List<EquipmentContentResponse> contentsSnapshot) {}
  public record InventoryCapturePage(
      UUID captureId,
      UUID operationId,
      long technicalAttempt,
      UUID warehouseId,
      long totalCount,
      String membershipDigest,
      String nextCursor,
      List<InventoryCaptureMember> content) {}

  public record InventoryNumberResolutionRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Size(max = 128) String number) {}
  public record InventoryAssetSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String tenantSnapshot) {}
  /** Current inventory-only projection; local comments and notes are deliberately absent. */
  public record InventoryAssetCurrentSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String tenantSnapshot,
      Map<String, Object> passportSnapshot,
      List<EquipmentContentResponse> contentsSnapshot) {}
  public record InventoryNumberResolutionResponse(
      String displayCanonicalNumber,
      String identityMatchKey,
      boolean found,
      InventoryAssetSnapshot asset) {}

  public record InventoryValidationRequest(
      @NotEmpty @Size(max = 5000) List<@NotNull UUID> assetIds) {}
  public record InventoryValidationItem(
      UUID assetId,
      boolean found,
      Long version,
      UUID warehouseId,
      RentalItemStatus status,
      String displayCanonicalNumber,
      String identityMatchKey,
      String tenantSnapshot,
      Map<String, Object> passportSnapshot,
      List<EquipmentContentResponse> contentsSnapshot) {}
  public record InventoryValidationResponse(
      OffsetDateTime validatedAt,
      String validationDigest,
      List<InventoryValidationItem> assets) {}

  /** Current active furniture and the selected cabin quantities in one deterministic snapshot. */
  public record InventoryFurnitureSnapshotRequest(
      @NotNull UUID warehouseId,
      @NotNull @Size(max = 5000) List<@NotNull UUID> assetIds) {}
  public record InventoryFurnitureSnapshot(
      UUID warehouseId,
      String snapshotSha256,
      List<InventoryFurnitureSnapshotItem> items) {}
  public record InventoryFurnitureSnapshotItem(
      UUID equipmentId,
      long catalogVersion,
      String equipmentName,
      long currentStockQuantity,
      Long stockBalanceVersion,
      List<InventoryFurnitureSnapshotCabin> cabins) {}
  public record InventoryFurnitureSnapshotCabin(
      UUID assetId,
      long assetVersion,
      String displayCanonicalNumber,
      RentalItemStatus status,
      long currentQuantity) {}

  /** Absolute reviewed count for every current active furniture position and selected cabin. */
  public record InventoryFurnitureReconciliationRequest(
      @NotNull UUID warehouseId,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String expectedSnapshotSha256,
      @NotBlank @Pattern(regexp = "^[0-9a-f]{64}$") String reviewSha256,
      @NotNull @Size(max = 1000)
      List<@NotNull @Valid InventoryFurnitureReconciliationItem> items) {}
  public record InventoryFurnitureReconciliationItem(
      @NotNull UUID equipmentId,
      @NotNull @Min(0) Long catalogVersion,
      @NotNull @Min(0) Long stockQuantity,
      @NotNull @Size(max = 5000)
      List<@NotNull @Valid InventoryFurnitureReconciliationCabin> cabins) {}
  public record InventoryFurnitureReconciliationCabin(
      @NotNull UUID assetId,
      @NotNull @Min(0) Long quantity) {}

  public record InventorySourceAssetRequest(
      @NotNull UUID inventoryId,
      @NotNull UUID findingId,
      @NotNull UUID warehouseId,
      @NotBlank @Size(max = 128) String number,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      @Size(max = 255) String category,
      @Size(max = 100) List<@NotNull UUID> characteristicIds,
      @NotNull Boolean linoleum,
      Map<String, Object> passport,
      List<@NotBlank @Size(max = 128) String> tags,
      @Size(max = 255) String rentalType,
      @Size(max = 255) String dimensions,
      @Size(max = 255) String finishing,
      @Size(max = 26000) String characteristics) {

    /**
     * Canonical UUID callers retain their existing constructor. Inventory's manager compatibility
     * payload uses the four trailing human-readable fields instead and is resolved only by the
     * asset-owned catalogue boundary.
     */
    public InventorySourceAssetRequest(
        UUID inventoryId,
        UUID findingId,
        UUID warehouseId,
        String number,
        UUID rentalTypeId,
        UUID dimensionId,
        UUID finishingId,
        String category,
        List<UUID> characteristicIds,
        Boolean linoleum,
        Map<String, Object> passport,
        List<String> tags) {
      this(
          inventoryId,
          findingId,
          warehouseId,
          number,
          rentalTypeId,
          dimensionId,
          finishingId,
          category,
          characteristicIds,
          linoleum,
          passport,
          tags,
          null,
          null,
          null,
          null);
    }
  }
  public record InventorySourceAssetResponse(
      UUID inventoryId,
      UUID findingId,
      InventoryAssetSnapshot asset) {}

  public record ClassifierRequest(@NotNull @Min(0) Long expectedVersion, @NotBlank @Size(max = 32) String type,
      UUID parentId, @NotBlank @Size(max = 255) String name,
      boolean active, @Min(0) Integer sortOrder) {}
  public record CreateClassifierRequest(@NotBlank @Size(max = 32) String type, UUID parentId,
      @NotBlank @Size(max = 255) String name, boolean active, @Min(0) Integer sortOrder) {}
  public record ClassifierResponse(UUID id, long version, String type, UUID parentId, String name,
      boolean active, Integer sortOrder) {}
}
