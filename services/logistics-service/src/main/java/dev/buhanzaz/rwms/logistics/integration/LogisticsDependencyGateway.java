package dev.buhanzaz.rwms.logistics.integration;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned private transport boundary for logistics dependency operations. Incoming user
 * credentials never cross this interface.
 */
public interface LogisticsDependencyGateway {
  WarehouseIdentity readWarehouseIdentity(UUID warehouseId);

  /** Exact owner-side admission truth for a new physical warehouse operation. */
  WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction);

  /** Durable warehouse-service worklist for logistics-owned draining readiness. */
  WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(UUID after, int limit);

  WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion);

  WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at);

  /** Idempotent immutable marker that makes a warehouse's operated boundary durable. */
  void markWarehouseOperation(UUID warehouseId, UUID operationId, OffsetDateTime occurredAt);

  default List<WarehouseIdentity> listWarehouseIdentities() {
    throw unavailable("Warehouse identity listing is not configured");
  }

  default boolean productionReady() {
    return true;
  }

  RentalItemSnapshot readRentalItemSnapshot(UUID assetId);

  OperationLease acquireReturnLease(
      UUID idempotencyKey, UUID assetId, long expectedAssetVersion, UUID documentId, UUID lineId);

  default OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireReturnLease(idempotencyKey, assetId, expectedAssetVersion, documentId, lineId);
  }

  OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId);

  default OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireOperationLease(
        idempotencyKey, ownerType, assetId, expectedAssetVersion, documentId, lineId);
  }

  RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId);

  RentalItemSnapshot settleReturn(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId,
      boolean estimate);

  default ReturnEquipmentReceipt receiveReturnEquipment(
      UUID idempotencyKey,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {
    throw new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION,
        "Return equipment receipts are not configured");
  }

  RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId);

  default RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId,
      String transferAssetStatus) {
    return applyFencedEffect(
        idempotencyKey,
        action,
        assetId,
        expectedAssetVersion,
        leaseId,
        fencingToken,
        ownerType,
        documentId,
        lineId,
        destinationWarehouseId);
  }

  default TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    throw unavailable("Maintenance transfer departure preparation is not configured");
  }

  default TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    throw unavailable("Maintenance transfer arrival preflight is not configured");
  }

  default TransferRepairArrivalCompletion completeTransferArrival(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {
    throw unavailable("Maintenance transfer arrival completion is not configured");
  }

  OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId);

  MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references);

  MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active);

  ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReference> mediaReferences);

  EquipmentHold acquireEquipmentHold(
      UUID idempotencyKey,
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion);

  EquipmentHold commandEquipmentHold(
      UUID idempotencyKey,
      EquipmentHoldAction action,
      UUID holdId,
      long expectedHoldVersion,
      UUID shipmentId,
      UUID shipmentLineId);

  EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose);

  /**
   * Acquires one movement line with optional authoritative same-order redistribution context.
   * Legacy stock and free-cabin sources use the context-free overload.
   */
  default EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units) {
    if (orderId == null && targetRentalItemId == null && units == null) {
      return acquireEquipmentMovementReservation(
          idempotencyKey,
          movementId,
          lineId,
          equipmentId,
          sourceWarehouseId,
          sourceRentalItemId,
          sourceLocationKind,
          expectedSourceBalanceVersion,
          quantity,
          reservedUntil,
          purpose);
    }
    throw unavailable("Order-context equipment movement reservation is not configured");
  }

  /**
   * Replays a replacement-owned movement reservation pre-held by the atomic unit swap. The released
   * source reservation identifies the intentionally no-longer-active old order cabin; ordinary
   * same-order redistribution keeps using the overload without that identity.
   */
  default EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {
    if (replacementSourceReservationId == null) {
      return acquireEquipmentMovementReservation(
          idempotencyKey,
          movementId,
          lineId,
          equipmentId,
          sourceWarehouseId,
          sourceRentalItemId,
          sourceLocationKind,
          expectedSourceBalanceVersion,
          quantity,
          reservedUntil,
          purpose,
          orderId,
          targetRentalItemId,
          units);
    }
    throw unavailable("Replacement equipment movement reservation replay is not configured");
  }

  EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId);

  EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines);

  EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperation> operations);

  EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId);

  EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion);

  default WarehouseDriverQueue readWarehouseDriverQueue(UUID warehouseId) {
    throw unavailable("Warehouse driver queue lookup is not configured");
  }

  /**
   * Reports whether the warehouse has exactly one active, visible driver queue.
   *
   * <p>A warehouse is allowed to operate without an in-house driver queue. Callers that reconcile
   * driver work must treat that configuration as an unavailable logistics lane rather than
   * repeatedly trying to read or populate a board that cannot exist.
   */
  default boolean isWarehouseDriverQueueAvailable(UUID warehouseId) {
    return true;
  }

  default DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority) {
    return registerDriverTask(
        warehouseId,
        externalTaskId,
        sourceId,
        title,
        unitNumber,
        description,
        queueDefinitionId,
        scheduledDate,
        priority,
        new DriverTaskAudience(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null));
  }

  default DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority,
      DriverTaskAudience driverAudience) {
    throw unavailable("Driver task registration is not configured");
  }

  default DriverBoardTask readDriverTask(UUID externalTaskId) {
    throw unavailable("Driver task lookup is not configured");
  }

  default DriverBoardTask cancelDriverTask(UUID externalTaskId, long expectedTaskVersion) {
    throw unavailable("Driver task cancellation is not configured");
  }

  /**
   * Atomically cancels a source-owned driver task only if task-board still sees every route entry
   * as waiting. Unlike {@link #cancelDriverTask(UUID, long)}, this command never interrupts work
   * that has started while logistics was preparing the compensation.
   */
  default DriverTaskPreStartCancellation cancelDriverTaskIfPreStart(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    throw unavailable("Pre-start driver task cancellation is not configured");
  }

  default DriverBoardTask setDriverTaskLane(
      UUID externalTaskId, long expectedTaskVersion, String lane) {
    throw unavailable("Driver task lane transition is not configured");
  }

  default DriverBoardSnapshot readDriverBoard(UUID warehouseId) {
    throw unavailable("Driver board lookup is not configured");
  }

  default DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex) {
    return moveDriverTask(
        externalTaskId,
        expectedTaskVersion,
        expectedEntryVersion,
        targetLane,
        targetDate,
        targetIndex,
        null);
  }

  default DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex,
      DriverTaskAudience targetDriverAudience) {
    throw unavailable("Driver task movement is not configured");
  }

  default DriverCompletionEvidence readDriverCompletionEvidence(UUID externalTaskId) {
    throw unavailable("Driver completion evidence lookup is not configured");
  }

  default CapitalRepairPage readCapitalRepairs(UUID warehouseId, int page, int size) {
    throw unavailable("Capital-repair projection is not configured");
  }

  default CapitalRepair readCapitalRepair(UUID repairId) {
    throw unavailable("Capital-repair lookup is not configured");
  }

  default RepairPlaceProjection readRepairPlaces(UUID warehouseId) {
    throw unavailable("Repair-place projection is not configured");
  }

  default RepairPlaceAllocation transitionRepairPlace(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition) {
    throw unavailable("Repair-place transition is not configured");
  }

  default CabinCoverChange setCabinCoverFromTaskEvidence(
      UUID idempotencyKey, UUID cabinId, UUID taskBoardEntryId, UUID evidenceMediaId) {
    throw unavailable("Cabin cover transition is not configured");
  }

  OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search);

  List<OrderUnitReservation> readOrderUnits(UUID orderId);

  OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
      UUID actorSubjectId,
      String actorRole);

  OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey, UUID orderId, UUID unitId, UUID actorSubjectId, String actorRole);

  List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole);

  /** Live asset-owned equipment availability for one warehouse. */
  List<EquipmentWarehouseAvailability> readLogisticsEquipmentAvailability(UUID warehouseId);

  List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units);

  OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID replacementForRentalItemId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderUnitEquipmentRequirements> units);

  /**
   * Atomically swaps an ordered set of active order cabins while preserving the order-wide
   * furniture reservations and optionally pre-holding each exact existing movement task.
   */
  OrderUnitsReplacementReceipt replaceOrderUnits(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units,
      List<OrderUnitReplacement> replacements);

  CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId, UUID rentalItemId, List<CabinFurnitureRequirement> requirements);

  default CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    throw unavailable("Cabin facets are not configured");
  }

  /** Reads a bounded cabin fact page without acquiring or renewing any hold. */
  default CabinCatalogPage readCabinCatalog(UUID warehouseId, String query, int page, int size) {
    throw unavailable("Cabin catalog is not configured");
  }

  /**
   * Sends the exact prepared asset request with its derived downstream idempotency key.
   * Implementations must not deserialize and rebuild {@code exactRequestBody} before sending it.
   */
  default CabinSearchResult searchAvailableCabins(
      UUID downstreamIdempotencyKey, String exactRequestBody) {
    throw unavailable("Cabin search is not configured");
  }

  default List<AvailableCabin> readCabinSnapshots(UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin snapshots are not configured");
  }

  default CabinAvailability readCabinAvailability(UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin availability is not configured");
  }

  default PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable("Presentation holds are not configured");
  }

  /** Replays an already-frozen asset replace-holds JSON body without rebuilding it. */
  default PresentationHolds replacePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    throw unavailable("Exact presentation hold replacement is not configured");
  }

  default PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {
    if (sourceHoldScopeId == null) {
      return replacePresentationHolds(
          idempotencyKey,
          presentationId,
          warehouseId,
          rentalItemIds,
          expiresAt,
          actorSubjectId,
          actorRole);
    }
    throw unavailable("Presentation hold transfer is not configured");
  }

  default PresentationHolds readPresentationHolds(UUID presentationId) {
    throw unavailable("Presentation holds are not configured");
  }

  default PresentationHolds readPresentationHolds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    return readPresentationHolds(presentationId);
  }

  default PresentationHolds releasePresentationHolds(
      UUID idempotencyKey, UUID presentationId, UUID actorSubjectId, String actorRole) {
    throw unavailable("Presentation holds are not configured");
  }

  /** Replays an already-frozen asset release-holds JSON body without rebuilding it. */
  default PresentationHolds releasePresentationHoldsExact(
      UUID idempotencyKey, UUID presentationId, String exactRequestBody) {
    throw unavailable("Exact presentation hold release is not configured");
  }

  default ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {
    return convertPresentationHolds(
        idempotencyKey,
        presentationId,
        orderId,
        warehouseId,
        selectedRentalItemIds,
        clientId,
        tenantSnapshot,
        actorSubjectId,
        actorRole,
        null);
  }

  /**
   * Atomically converts presentation holds and replaces the complete order furniture composition. A
   * non-null {@code units} value is authoritative for every resulting active order cabin.
   */
  default ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole,
      List<OrderUnitEquipmentRequirements> units) {
    throw unavailable("Presentation hold conversion is not configured");
  }

  default List<CabinMediaSnapshot> readCabinMediaSnapshots(UUID warehouseId, List<UUID> cabinIds) {
    throw unavailable("Presentation media snapshots are not configured");
  }

  default MediaContent readCabinPresentationMedia(
      UUID warehouseId, UUID cabinId, UUID mediaId, long generation, String variant) {
    throw unavailable("Presentation media content is not configured");
  }

  record WarehouseIdentity(
      UUID id, long version, boolean active, String name, String city, String timeZone) {
    public WarehouseIdentity(UUID id, long version, boolean active, String timeZone) {
      this(id, version, active, "", "", timeZone);
    }
  }

  enum WarehouseOperationDirection {
    INCOMING,
    OUTGOING
  }

  enum WarehouseLifecycleState {
    ACTIVE,
    DRAINING,
    INACTIVE
  }

  record WarehouseOperationAdmission(
      UUID warehouseId,
      long warehouseVersion,
      WarehouseLifecycleState lifecycleState,
      WarehouseOperationDirection direction,
      boolean admitted) {
    public WarehouseOperationAdmission {
      if (warehouseId == null
          || warehouseVersion < 0
          || lifecycleState == null
          || direction == null) {
        throw new IllegalArgumentException("Warehouse admission truth is invalid");
      }
    }
  }

  record WarehouseLifecycleReadinessWork(
      UUID warehouseId, long warehouseVersion, WarehouseLifecycleState lifecycleState) {
    public WarehouseLifecycleReadinessWork {
      if (warehouseId == null
          || warehouseVersion < 0
          || lifecycleState != WarehouseLifecycleState.DRAINING) {
        throw new IllegalArgumentException("Warehouse readiness work is invalid");
      }
    }
  }

  record WarehouseLifecycleReadinessWorkPage(
      List<WarehouseLifecycleReadinessWork> items, UUID nextAfter) {
    public WarehouseLifecycleReadinessWorkPage {
      if (items == null || items.stream().anyMatch(java.util.Objects::isNull)) {
        throw new IllegalArgumentException("Warehouse readiness page is invalid");
      }
      items = List.copyOf(items);
    }
  }

  record WarehouseLifecycleReadinessConfirmation(
      UUID warehouseId,
      long warehouseVersion,
      WarehouseLifecycleState lifecycleState,
      String readinessOwner,
      OffsetDateTime confirmedAt) {
    public WarehouseLifecycleReadinessConfirmation {
      if (warehouseId == null
          || warehouseVersion < 0
          || lifecycleState != WarehouseLifecycleState.DRAINING
          || !"LOGISTICS".equals(readinessOwner)
          || confirmedAt == null) {
        throw new IllegalArgumentException("Warehouse readiness confirmation is invalid");
      }
    }
  }

  record WarehouseTimeZone(UUID warehouseId, String timeZone, OffsetDateTime effectiveFrom) {
    public WarehouseTimeZone {
      if (warehouseId == null
          || timeZone == null
          || timeZone.isBlank()
          || timeZone.length() > 64
          || effectiveFrom == null) {
        throw new IllegalArgumentException("Warehouse timezone truth is invalid");
      }
      timeZone = timeZone.trim();
      java.time.ZoneId.of(timeZone);
    }
  }

  record EquipmentContent(UUID equipmentId, long quantity) {}

  record RentalItemSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      String number,
      String status,
      List<EquipmentContent> contents) {
    public RentalItemSnapshot(
        UUID assetId,
        long version,
        UUID warehouseId,
        String status,
        List<EquipmentContent> contents) {
      this(assetId, version, warehouseId, null, status, contents);
    }
  }

  record OperationLease(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  enum LogisticsOwnerType {
    LOGISTICS_RETURN,
    LOGISTICS_SHIPMENT,
    LOGISTICS_TRANSFER
  }

  enum EquipmentMovementPurpose {
    ALLOCATABLE_REBALANCE,
    MAINTENANCE_DISPOSITION
  }

  enum AssetEffect {
    SHIPMENT_CONFIRM,
    TRANSFER_DEPART,
    TRANSFER_ARRIVE
  }

  record TransferRepairDeparture(
      UUID activeRepairId, Long activeRepairVersion, String assetStatus) {}

  record TransferRepairArrivalPreflight(
      UUID activeRepairId, boolean priorityRequired, List<UUID> missingQueueDefinitionIds) {}

  record TransferRepairArrivalCompletion(
      UUID activeRepairId, Long repairVersion, UUID warehouseId) {}

  record MediaReference(UUID mediaId, long generation) {}

  record MediaValidation(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {}

  record MediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {}

  record ReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
      String snapshotSha256,
      OffsetDateTime receivedAt) {}

  record ReturnEquipmentReceiptLine(
      UUID receiptId,
      UUID equipmentId,
      long quantity,
      UUID stockBalanceId,
      long stockBalanceVersion,
      long stockQuantity) {}

  record ReturnEquipmentReceipt(
      UUID returnId, UUID returnLineId, UUID warehouseId, List<ReturnEquipmentReceiptLine> lines) {}

  enum EquipmentHoldAction {
    COMMIT,
    RELEASE
  }

  record EquipmentHold(
      UUID holdId,
      long version,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt) {}

  record EquipmentMovementReservation(
      UUID reservationId,
      long version,
      String ownerType,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long quantity,
      String state,
      OffsetDateTime reservedUntil,
      OffsetDateTime executedAt) {}

  record EquipmentMovementExecutionRequestLine(
      UUID reservationId,
      long expectedReservationVersion,
      UUID lineId,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind) {}

  record EquipmentMovementEvent(
      UUID id,
      long version,
      UUID equipmentId,
      UUID sourceBalanceId,
      UUID targetBalanceId,
      long quantity,
      String kind,
      OffsetDateTime occurredAt) {}

  record EquipmentMovementExecutionLine(
      UUID reservationId, long reservationVersion, UUID lineId, EquipmentMovementEvent movement) {}

  record EquipmentMovementExecution(UUID movementId, List<EquipmentMovementExecutionLine> lines) {}

  record EquipmentMovementOperation(
      String direction, UUID equipmentId, String equipmentName, long quantity) {}

  record EquipmentMovementBoardTask(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}

  /** Task-board queue identities resolved for one warehouse driver audience. */
  record WarehouseDriverQueue(UUID warehouseId, UUID queueDefinitionId, UUID workQueueId) {}

  /** Task-board audience transported without introducing a worker aggregate in logistics. */
  record DriverTaskAudience(DriverTaskAudienceMode mode, UUID workerId, String workerName) {}

  record DriverBoardTask(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String taskText,
      DriverTaskAudience driverAudience,
      String status,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      OffsetDateTime doneAt,
      UUID entryId,
      long entryVersion,
      String entryStatus,
      int queuePosition) {
    public DriverBoardTask(
        UUID taskId,
        long taskVersion,
        UUID warehouseId,
        UUID externalTaskId,
        String title,
        String unitNumber,
        String taskText,
        String status,
        LocalDate scheduledDate,
        String lane,
        int priority,
        boolean pinned,
        OffsetDateTime doneAt,
        UUID entryId,
        long entryVersion,
        String entryStatus,
        int queuePosition) {
      this(
          taskId,
          taskVersion,
          warehouseId,
          externalTaskId,
          title,
          unitNumber,
          taskText,
          new DriverTaskAudience(DriverTaskAudienceMode.WAREHOUSE_DRIVERS, null, null),
          status,
          scheduledDate,
          lane,
          priority,
          pinned,
          doneAt,
          entryId,
          entryVersion,
          entryStatus,
          queuePosition);
    }
  }

  enum DriverTaskPreStartCancellationOutcome {
    CANCELLED,
    ALREADY_CANCELLED,
    STARTED,
    VERSION_CONFLICT
  }

  record DriverTaskPreStartCancellation(
      DriverTaskPreStartCancellationOutcome outcome,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}

  record DriverBoardDateColumn(LocalDate date, List<DriverBoardTask> tasks) {}

  record DriverBoardSnapshot(
      UUID warehouseId,
      UUID queueId,
      long queueVersion,
      List<DriverBoardTask> current,
      List<DriverBoardDateColumn> dates) {}

  record RepairComplexitySnapshot(
      String type, String name, String color, String plannedMinutes, boolean forcedCapital) {}

  record CapitalRepair(
      UUID repairId,
      UUID rentalItemId,
      UUID warehouseId,
      int priority,
      RepairComplexitySnapshot complexity,
      long version) {}

  /** Bounded maintenance-owned capital-repair page used by the driver scheduler. */
  record CapitalRepairPage(List<CapitalRepair> items, int page, int size, long totalElements) {}

  record DriverCompletionEvidence(
      UUID externalTaskId,
      UUID taskId,
      UUID entryId,
      UUID evidenceId,
      UUID mediaId,
      long mediaGeneration,
      UUID warehouseId,
      OffsetDateTime recordedAt) {}

  record RepairPlaceAllocation(
      UUID id,
      long version,
      UUID warehouseId,
      UUID repairId,
      UUID rentalItemId,
      String state,
      String repairStageName,
      String repairStageState,
      int priority,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  record RepairPlaceProjection(
      UUID warehouseId,
      int repairPlaceCount,
      int automaticRefillDelayMinutes,
      long reservedCount,
      long occupiedCount,
      long readyToReleaseCount,
      long availableCount,
      boolean overCapacity,
      List<RepairPlaceAllocation> allocations) {}

  record CabinCoverChange(
      UUID cabinId,
      UUID warehouseId,
      UUID coverMediaId,
      long generation,
      UUID taskBoardEntryId,
      long version,
      OffsetDateTime changedAt) {}

  record OrderEquipmentContent(
      UUID equipmentId, String equipmentName, long quantity, String locationKind) {}

  record OrderRentalItem(
      UUID id,
      long version,
      UUID warehouseId,
      String number,
      String status,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      List<String> tags,
      List<OrderEquipmentContent> contents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  record OrderUnitReservation(
      UUID reservationId,
      long reservationVersion,
      UUID orderId,
      UUID unitId,
      UUID warehouseId,
      String state,
      UUID addedBySubjectId,
      String addedByRole,
      OffsetDateTime createdAt,
      OffsetDateTime releasedAt,
      boolean replayed,
      OrderRentalItem unit) {}

  /** Candidate cabin paired with its current order-reservation identity when already selected. */
  record OrderUnitCandidate(UUID reservationId, boolean added, OrderRentalItem unit) {}

  record OrderUnitCandidatePage(
      List<OrderUnitCandidate> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  record OrderEquipmentRequirement(UUID equipmentId, long quantity) {}

  /** Complete desired furniture composition for one order cabin. */
  record OrderUnitEquipmentRequirements(
      UUID rentalItemId, List<OrderEquipmentRequirement> requirements) {}

  /** Live warehouse availability of one equipment catalogue position. */
  record EquipmentWarehouseAvailability(
      UUID equipmentId,
      String equipmentName,
      boolean active,
      long availableQuantity,
      Integer maximumPerCabin) {}

  record OrderEquipmentReservation(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity,
      Integer maximumPerCabin) {
    public OrderEquipmentReservation(
        UUID equipmentId, String equipmentName, long quantity, long availableQuantity) {
      this(equipmentId, equipmentName, quantity, availableQuantity, null);
    }
  }

  record OrderFurnitureMovementPlanLine(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      long quantity) {}

  record OrderFurnitureMovementPlan(
      UUID orderId, UUID unitId, String unitNumber, List<OrderFurnitureMovementPlanLine> lines) {}

  /** One exact source-fenced line associated with an existing movement task. */
  record OrderUnitReplacementMovementLine(
      UUID lineId,
      UUID equipmentId,
      UUID sourceBalanceId,
      long expectedSourceBalanceVersion,
      UUID targetRentalItemId,
      long quantity) {}

  /** Existing movement task supplied to the atomic cabin replacement command. */
  record OrderUnitReplacementMovement(
      UUID movementId,
      OffsetDateTime reservedUntil,
      List<OrderUnitReplacementMovementLine> lines) {}

  /** One ordered old-to-new cabin mapping and its optional exact physical furniture move. */
  record OrderUnitReplacement(
      UUID rentalItemId, UUID replacementRentalItemId, OrderUnitReplacementMovement movement) {}

  /** One pair receipt in an atomic same-order cabin replacement batch. */
  record OrderUnitReplacementReceipt(
      OrderUnitReservation releasedReservation,
      OrderUnitReservation replacementReservation,
      List<EquipmentMovementReservation> movementReservations,
      boolean contentReady) {}

  /** Complete atomic batch receipt preserving request pair order. */
  record OrderUnitsReplacementReceipt(
      List<OrderUnitReplacementReceipt> replacements, boolean replayed) {}

  record CabinFurnitureRequirement(UUID equipmentId, long quantity) {}

  record CabinFurnitureMovementPlanLine(
      UUID equipmentId,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      long quantity) {}

  record CabinFurnitureMovementPlan(
      UUID rentalItemId, String unitNumber, List<CabinFurnitureMovementPlanLine> lines) {}

  /** Exact available asset facet values and type-dimension relations for one warehouse. */
  record CabinFacets(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories,
      List<String> characteristics,
      List<CabinTypeDimensionRelation> typeDimensions) {}

  /** Exact dimension values that currently relate to one cabin type. */
  record CabinTypeDimensionRelation(String cabinType, List<String> dimensions) {}

  record CabinSearchGroup(
      String cabinType,
      String finish,
      String dimensions,
      String category,
      String characteristics,
      Boolean linoleum,
      int quantity) {}

  /** Owner-side hold behavior for an inquiry cabin search. */
  enum CabinSearchResultMode {
    APPEND,
    REPLACE
  }

  /** Immutable wire command frozen before a rental-inquiry search calls asset-service. */
  record CabinSearchCommand(
      UUID warehouseId,
      UUID holdScopeId,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      CabinSearchResultMode resultMode,
      List<CabinSearchGroup> groups) {}

  record AvailableCabin(
      UUID id,
      long version,
      UUID warehouseId,
      String status,
      String number,
      String rentalType,
      String dimensions,
      String finishing,
      String category,
      String characteristics,
      Boolean linoleum,
      Map<String, Object> passport,
      List<String> tags,
      List<OrderEquipmentContent> contents,
      OffsetDateTime updatedAt) {
    public AvailableCabin(
        UUID id,
        long version,
        UUID warehouseId,
        String status,
        String number,
        String rentalType,
        String dimensions,
        String finishing,
        String category,
        String characteristics,
        Boolean linoleum,
        Map<String, Object> passport,
        List<String> tags,
        OffsetDateTime updatedAt) {
      this(
          id,
          version,
          warehouseId,
          status,
          number,
          rentalType,
          dimensions,
          finishing,
          category,
          characteristics,
          linoleum,
          passport,
          tags,
          List.of(),
          updatedAt);
    }
  }

  /** Facts-only cabin page returned by asset-service catalog lookup. */
  record CabinCatalogPage(
      UUID warehouseId,
      List<AvailableCabin> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  /** One search group and the asset-owned cabin snapshots held for that result. */
  record CabinSearchGroupResult(CabinSearchGroup group, List<AvailableCabin> cabins) {}

  record CabinSearchResult(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  /** One asset-owned cabin availability decision and optional sanitized reason. */
  record CabinAvailabilityItem(UUID rentalItemId, boolean available, String reason) {}

  /** Warehouse-scoped availability decisions for the exact requested cabin identifiers. */
  record CabinAvailability(UUID warehouseId, List<CabinAvailabilityItem> items) {}

  record PresentationHold(
      UUID holdId,
      long version,
      UUID presentationId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      OffsetDateTime expiresAt,
      UUID orderId,
      OffsetDateTime createdAt,
      OffsetDateTime endedAt) {}

  /**
   * Authoritative presentation hold receipt. Cabins are populated by hold replacement and are
   * captured under the same asset locks as the hold set; read and release receipts may omit them.
   */
  record PresentationHolds(
      UUID presentationId,
      OffsetDateTime expiresAt,
      List<PresentationHold> holds,
      List<AvailableCabin> cabins) {
    public PresentationHolds(
        UUID presentationId, OffsetDateTime expiresAt, List<PresentationHold> holds) {
      this(presentationId, expiresAt, holds, List.of());
    }
  }

  record ConvertedPresentationHolds(
      UUID presentationId,
      UUID orderId,
      List<OrderUnitReservation> reservations,
      List<UUID> releasedRentalItemIds,
      List<OrderEquipmentReservation> equipmentReservations) {
    public ConvertedPresentationHolds(
        UUID presentationId,
        UUID orderId,
        List<OrderUnitReservation> reservations,
        List<UUID> releasedRentalItemIds) {
      this(presentationId, orderId, reservations, releasedRentalItemIds, List.of());
    }
  }

  record CabinMediaPhoto(
      UUID mediaId, long generation, int sortOrder, List<String> availableVariants) {}

  record CabinMediaSnapshot(UUID cabinId, List<CabinMediaPhoto> photos) {}

  record MediaContent(byte[] bytes, String contentType) {}

  private static LogisticsDependencyException unavailable(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }
}
