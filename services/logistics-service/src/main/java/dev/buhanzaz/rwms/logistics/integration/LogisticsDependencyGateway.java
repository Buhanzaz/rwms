package dev.buhanzaz.rwms.logistics.integration;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskWorkerContent;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Source-compatible composition of the narrow owner-specific private dependency ports used by
 * existing logistics workflows. Incoming user credentials never cross this boundary, and all
 * transport records remain nested here so established callers retain their public type names.
 */
public interface LogisticsDependencyGateway
    extends LogisticsWarehouseDependencyPort,
        LogisticsAssetOperationsDependencyPort,
        LogisticsMaintenanceDependencyPort,
        LogisticsMediaDependencyPort,
        LogisticsTaskBoardDependencyPort,
        LogisticsOrderPresentationDependencyPort {
  /** Reports whether the configured dependency transport can execute production workflows. */
  default boolean productionReady() {
    return true;
  }

  /** Active or historical warehouse identity and owner-held display metadata. */
  record WarehouseIdentity(
      UUID id,
      long version,
      boolean active,
      String name,
      String city,
      String address,
      BigDecimal latitude,
      BigDecimal longitude,
      String timeZone,
      boolean representative) {
    public WarehouseIdentity(UUID id, long version, boolean active, String timeZone) {
      this(id, version, active, "", "", null, null, null, timeZone, false);
    }

    public WarehouseIdentity(
        UUID id, long version, boolean active, String name, String city, String timeZone) {
      this(id, version, active, name, city, null, null, null, timeZone, false);
    }

    /** Preserves the pre-coordinate identity constructor for unchanged call sites. */
    public WarehouseIdentity(
        UUID id,
        long version,
        boolean active,
        String name,
        String city,
        String address,
        String timeZone) {
      this(id, version, active, name, city, address, null, null, timeZone, false);
    }
  }

  /** One directed support edge whose calendar was already filtered by warehouse-service. */
  record WarehouseSupportLink(
      UUID id,
      long version,
      WarehouseIdentity supportWarehouse,
      WarehouseIdentity servedWarehouse,
      int priority,
      boolean allowDrivers,
      boolean allowVehicles,
      boolean allowInventory,
      boolean allowDirectFulfillment,
      boolean allowInterwarehouseTransfer,
      boolean allowContractorFallback,
      Set<DayOfWeek> allowedWeekdays,
      Set<LocalDate> allowedDates,
      Set<LocalDate> excludedDates,
      LocalTime serviceStart,
      LocalTime serviceEnd) {}

  /**
   * Least-privilege task-board worker availability used by transfer and route planning.
   * Operational placement remains task-board-owned; logistics retains no mutable copy.
   */
  record WarehouseDriverIdentity(
      UUID workerId,
      String displayName,
      String employmentType,
      String phone,
      UUID operationalWarehouseId,
      OffsetDateTime availableFrom,
      OffsetDateTime availableUntil,
      String availabilityKind) {
    /** Preserves source compatibility for call sites that need only a resolved display identity. */
    public WarehouseDriverIdentity(UUID workerId, String displayName) {
      this(workerId, displayName, "STAFF", null, null, null, null, "HOME");
    }
  }

  /** Task-board-owned operational placement history linked to one logistics transfer. */
  record WorkerOperationalAssignment(
      UUID assignmentId,
      long version,
      UUID transferId,
      UUID workerId,
      UUID homeWarehouseId,
      UUID sourceWarehouseId,
      UUID destinationWarehouseId,
      String mode,
      String status,
      OffsetDateTime travelStartsAt,
      OffsetDateTime effectiveFrom,
      OffsetDateTime effectiveUntil) {}

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

  /**
   * Least-privilege asset-owned input for one immutable public cabin photo presentation. Warehouse
   * and version are private creation fences and are never mapped to the anonymous response.
   */
  record CabinPhotoPresentationAssetSnapshot(
      UUID assetId,
      long version,
      UUID warehouseId,
      String number,
      String dimensions,
      String finishing,
      String category,
      List<String> characteristics,
      Boolean linoleum) {}

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
    MAINTENANCE_DISPOSITION,
    TRANSFER_REBALANCE
  }

  enum AssetEffect {
    SHIPMENT_CONFIRM,
    TRANSFER_DEPART,
    TRANSFER_ARRIVE
  }

  record TransferRepairDeparture(
      UUID activeRepairId, Long activeRepairVersion, String assetStatus) {}

  /** Stable maintenance result for exactly one imported rental shipment. */
  record HistoricalShipmentRepairClosure(
      UUID shipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      String rentalItemStatus,
      List<UUID> closedRepairIds,
      String outcome) {}

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

  /** Exact READY avatar reference validated by media-service for one customer profile. */
  record CustomerProfileMediaValidation(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      MediaReference reference) {}

  /** Active profile-avatar media proof bound to one CustomerApp subject. */
  record CustomerProfileMediaOwnerProof(
      UUID profileId,
      UUID warehouseId,
      UUID authorizedSubjectId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {}

  /** Active media owner revision, optionally restricted to one CustomerApp shipment subject. */
  record MediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      UUID authorizedSubjectId,
      boolean active) {
    /** Creates an unrestricted proof for the non-customer owner types. */
    public MediaOwnerProof(
        LogisticsOwnerType ownerType,
        UUID documentId,
        UUID lineId,
        UUID warehouseId,
        long ownerRevision,
        long aggregateVersion,
        UUID proofEventId,
        boolean active) {
      this(
          ownerType,
          documentId,
          lineId,
          warehouseId,
          ownerRevision,
          aggregateVersion,
          proofEventId,
          null,
          active);
    }
  }

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

  /** Exact cabin requirement sent to the asset-owned transfer reservation boundary. */
  record TransferUnitReservationRequestLine(
      UUID lineId,
      UUID rentalItemId,
      long expectedRentalItemVersion,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds,
      Boolean linoleum) {}

  /** Exact active reservation identity used by pre-departure compensation. */
  record TransferUnitReservationReleaseLine(
      UUID reservationId,
      UUID lineId,
      UUID rentalItemId,
      long expectedReservationVersion) {}

  /** One asset-owned transfer reservation row and the cabin revision after that effect. */
  record TransferUnitReservationLineReceipt(
      UUID reservationId,
      long version,
      UUID lineId,
      UUID rentalItemId,
      long currentRentalItemVersion,
      String state) {}

  /** Deterministic all-or-nothing cabin reservation receipt for one transfer. */
  record TransferUnitReservationReceipt(
      UUID transferId, List<TransferUnitReservationLineReceipt> lines) {}

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
      Integer maximumPerCabin,
      long availableStock,
      List<EquipmentBalanceAvailability> balances) {
    /** Preserves callers that only need the historical aggregate availability fields. */
    public EquipmentWarehouseAvailability(
        UUID equipmentId,
        String equipmentName,
        boolean active,
        long availableQuantity,
        Integer maximumPerCabin) {
      this(
          equipmentId,
          equipmentName,
          active,
          availableQuantity,
          maximumPerCabin,
          availableQuantity,
          List.of());
    }
  }

  /** Exact asset balance candidate used to fence a loose-furniture hold. */
  record EquipmentBalanceAvailability(
      UUID balanceId,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      String locationKind,
      long quantity,
      long activeHeldQuantity,
      boolean allocatable,
      long availableStock) {}

  /** One active order equipment allocation, partitioned by its physical warehouse source. */
  record OrderEquipmentReservation(
      UUID warehouseId,
      UUID equipmentId,
      String equipmentName,
      long quantity,
      long availableQuantity,
      Integer maximumPerCabin) {
    public OrderEquipmentReservation(
        UUID equipmentId, String equipmentName, long quantity, long availableQuantity) {
      this(null, equipmentId, equipmentName, quantity, availableQuantity, null);
    }

    public OrderEquipmentReservation(
        UUID equipmentId,
        String equipmentName,
        long quantity,
        long availableQuantity,
        Integer maximumPerCabin) {
      this(null, equipmentId, equipmentName, quantity, availableQuantity, maximumPerCabin);
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

  /** One bounded READY/current-generation media reference from the active cabin gallery. */
  record CabinMediaPhoto(
      UUID mediaId, long generation, int sortOrder, List<String> availableVariants) {}

  /** Complete logical photo count plus the bounded READY photo metadata returned by media-service. */
  record CabinMediaSnapshot(UUID cabinId, long photoCount, List<CabinMediaPhoto> photos) {}

  /** Sanitized private media bytes and their validated response content type. */
  record MediaContent(byte[] bytes, String contentType) {}

}
