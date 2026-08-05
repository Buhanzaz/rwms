package dev.buhanzaz.rwms.logistics.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Versioned, local transport boundary for the small Stage 8 private APIs.
 * Incoming user credentials never cross this interface.
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
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId);

  default OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId);
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
        idempotencyKey,
        ownerType,
        assetId,
        expectedAssetVersion,
        documentId,
        lineId);
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
      boolean shortage);

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

  ReturnShortageSource upsertReturnShortage(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReference> mediaReferences,
      List<EquipmentShortage> shortages);

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
    throw unavailable("Driver task registration is not configured");
  }

  default DriverBoardTask readDriverTask(UUID externalTaskId) {
    throw unavailable("Driver task lookup is not configured");
  }

  default DriverBoardTask cancelDriverTask(
      UUID externalTaskId, long expectedTaskVersion) {
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
      UUID idempotencyKey,
      UUID cabinId,
      UUID taskBoardEntryId,
      UUID evidenceMediaId) {
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
      UUID idempotencyKey,
      UUID orderId,
      UUID unitId,
      UUID actorSubjectId,
      String actorRole);

  List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole);

  List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderEquipmentRequirement> requirements);

  OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderEquipmentRequirement> orderRequirements);

  CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId,
      UUID rentalItemId,
      List<CabinFurnitureRequirement> requirements);

  default CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    throw unavailable("Cabin facets are not configured");
  }

  default CabinSearchResult searchAvailableCabins(
      UUID warehouseId,
      UUID holdScopeId,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      List<CabinSearchGroup> groups) {
    throw unavailable("Cabin search is not configured");
  }

  default List<AvailableCabin> readCabinSnapshots(
      UUID warehouseId, List<UUID> rentalItemIds) {
    throw unavailable("Cabin snapshots are not configured");
  }

  default CabinAvailability readCabinAvailability(
      UUID warehouseId, List<UUID> rentalItemIds) {
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
      UUID idempotencyKey,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole) {
    throw unavailable("Presentation holds are not configured");
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
    throw unavailable("Presentation hold conversion is not configured");
  }

  default List<CabinMediaSnapshot> readCabinMediaSnapshots(
      UUID warehouseId, List<UUID> cabinIds) {
    throw unavailable("Presentation media snapshots are not configured");
  }

  default MediaContent readCabinPresentationMedia(
      UUID warehouseId,
      UUID cabinId,
      UUID mediaId,
      long generation,
      String variant) {
    throw unavailable("Presentation media content is not configured");
  }

  record WarehouseIdentity(
      UUID id,
      long version,
      boolean active,
      String name,
      String city,
      String timeZone) {
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
      UUID activeRepairId,
      boolean priorityRequired,
      List<UUID> missingQueueDefinitionIds) {}

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

  record EquipmentShortage(UUID equipmentId, long missingQuantity) {}

  record ReturnShortageSource(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
      List<EquipmentShortage> shortages,
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
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {}

  enum EquipmentHoldAction {
    COMMIT,
    RELEASE
  }

  record EquipmentHold(
      UUID holdId, long version, String state, OffsetDateTime expiresAt, OffsetDateTime committedAt) {}

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
      UUID reservationId,
      long reservationVersion,
      UUID lineId,
      EquipmentMovementEvent movement) {}

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

  record WarehouseDriverQueue(
      UUID warehouseId, UUID queueDefinitionId, UUID workQueueId) {}

  record DriverBoardTask(
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
      int queuePosition) {}

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
      String type,
      String name,
      String color,
      String plannedMinutes,
      boolean forcedCapital) {}

  record CapitalRepair(
      UUID repairId,
      UUID rentalItemId,
      UUID warehouseId,
      int priority,
      RepairComplexitySnapshot complexity,
      long version) {}

  record CapitalRepairPage(
      List<CapitalRepair> items, int page, int size, long totalElements) {}

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
      UUID equipmentId,
      String equipmentName,
      long quantity,
      String locationKind) {}

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

  record OrderUnitCandidate(
      UUID reservationId, boolean added, OrderRentalItem unit) {}

  record OrderUnitCandidatePage(
      List<OrderUnitCandidate> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

  record OrderEquipmentRequirement(UUID equipmentId, long quantity) {}

  record OrderEquipmentReservation(
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity) {}

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
      UUID orderId,
      UUID unitId,
      String unitNumber,
      List<OrderFurnitureMovementPlanLine> lines) {}

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

  record CabinFacets(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories) {}

  record CabinSearchGroup(
      String cabinType,
      String finish,
      String dimensions,
      String category,
      String characteristics,
      Boolean linoleum,
      int quantity) {}

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
      OffsetDateTime updatedAt) {}

  record CabinSearchGroupResult(
      CabinSearchGroup group, List<AvailableCabin> cabins) {}

  record CabinSearchResult(
      UUID warehouseId, OffsetDateTime expiresAt, List<CabinSearchGroupResult> groups) {}

  record CabinAvailabilityItem(UUID rentalItemId, boolean available, String reason) {}

  record CabinAvailability(
      UUID warehouseId, List<CabinAvailabilityItem> items) {}

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

  record PresentationHolds(
      UUID presentationId, OffsetDateTime expiresAt, List<PresentationHold> holds) {}

  record ConvertedPresentationHolds(
      UUID presentationId,
      UUID orderId,
      List<OrderUnitReservation> reservations,
      List<UUID> releasedRentalItemIds) {}

  record CabinMediaPhoto(
      UUID mediaId, long generation, int sortOrder, List<String> availableVariants) {}

  record CabinMediaSnapshot(UUID cabinId, List<CabinMediaPhoto> photos) {}

  record MediaContent(byte[] bytes, String contentType) {}

  private static LogisticsDependencyException unavailable(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

}
