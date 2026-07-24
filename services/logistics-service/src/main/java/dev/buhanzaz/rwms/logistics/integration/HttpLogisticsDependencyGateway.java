package dev.buhanzaz.rwms.logistics.integration;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.OAuth2AuthorizeRequest;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * Direct client-credentials transport for only the approved Stage 8 private
 * boundaries. It intentionally has no access to the incoming user JWT.
 */
final class HttpLogisticsDependencyGateway implements LogisticsDependencyGateway {
  private static final String ASSET_CLIENT = "logistics-asset";
  private static final String WAREHOUSE_CLIENT = "logistics-warehouse";
  private static final String MAINTENANCE_CLIENT = "logistics-maintenance";
  private static final String MEDIA_CLIENT = "logistics-media";
  private static final String TASK_BOARD_CLIENT = "logistics-task-board";
  private static final String ASSET_SCOPE = "asset.logistics";
  private static final String WAREHOUSE_SCOPE = "warehouse.logistics";
  private static final String MAINTENANCE_SCOPE = "maintenance.logistics";
  private static final String MEDIA_SCOPE = "media.logistics";
  private static final String TASK_BOARD_SCOPE = "task-board.logistics";

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String assetBase;
  private final String warehouseBase;
  private final String maintenanceBase;
  private final String mediaBase;
  private final String taskBoardBase;
  private final String taskBoardEquipmentMovementBase;

  HttpLogisticsDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      LogisticsDependencyProperties.Validated properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    assetBase = strip(properties.assetBaseUrl().toString()) + "/api/internal/asset/v1/logistics";
    warehouseBase =
        strip(properties.warehouseBaseUrl().toString()) + "/api/internal/warehouse/v1/warehouses/logistics";
    maintenanceBase =
        strip(properties.maintenanceBaseUrl().toString()) + "/api/internal/maintenance/v1/logistics/returns";
    mediaBase = strip(properties.mediaBaseUrl().toString()) + "/api/internal/media/v1";
    taskBoardBase =
        strip(properties.taskBoardBaseUrl().toString())
            + "/api/internal/task-board/v1/logistics/preparation-tasks";
    taskBoardEquipmentMovementBase =
        strip(properties.taskBoardBaseUrl().toString())
            + "/api/internal/task-board/v1/logistics/equipment-movement-tasks";
  }

  @Override
  public WarehouseIdentity readWarehouseIdentity(UUID warehouseId) {
    WarehouseIdentityResponse response =
        get(
            warehouseBase + "/" + warehouseId + "/identity",
            WarehouseIdentityResponse.class,
            WAREHOUSE_CLIENT,
            WAREHOUSE_SCOPE);
    if (response == null) throw malformed("Warehouse-service returned an empty identity");
    return new WarehouseIdentity(response.id(), response.version(), response.active(), response.timeZone());
  }

  @Override
  public RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    RentalItemSnapshotResponse response =
        get(
            assetBase + "/rental-items/" + assetId + "/snapshot",
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    return acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId, null);
  }

  @Override
  public OperationLease acquireReturnLease(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    return acquireOperationLease(
        idempotencyKey,
        LogisticsOwnerType.LOGISTICS_RETURN,
        assetId,
        expectedAssetVersion,
        documentId,
        lineId,
        rentalOrderId);
  }

  @Override
  public OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    return acquireOperationLease(
        idempotencyKey,
        ownerType,
        assetId,
        expectedAssetVersion,
        documentId,
        lineId,
        null);
  }

  @Override
  public OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    if (ownerType == null) throw malformed("Logistics operation-lease owner type is required");
    OperationLeaseResponse response =
        post(
            assetBase + "/operation-leases",
            idempotencyKey,
            new AcquireLeaseRequest(
                assetId,
                ownerType.name(),
                documentId,
                lineId,
                expectedAssetVersion,
                rentalOrderId),
            OperationLeaseResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response == null) throw malformed("Asset-service returned an empty operation lease");
    return new OperationLease(
        response.leaseId(),
        response.version(),
        response.rentalItemId(),
        response.fencingToken(),
        response.state(),
        response.expiresAt());
  }

  @Override
  public RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId) {
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                "RETURN_INTAKE",
                leaseId,
                fencingToken,
                "LOGISTICS_RETURN",
                documentId,
                lineId,
                null),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public RentalItemSnapshot settleReturn(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId,
      boolean shortage) {
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                shortage ? "RETURN_SETTLE_SHORTAGE" : "RETURN_SETTLE_FREE",
                leaseId,
                fencingToken,
                LogisticsOwnerType.LOGISTICS_RETURN.name(),
                documentId,
                lineId,
                null),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public ReturnEquipmentReceipt receiveReturnEquipment(
      UUID idempotencyKey,
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLine> lines) {
    if (returnId == null
        || returnLineId == null
        || warehouseId == null
        || lines == null
        || lines.isEmpty()) {
      throw malformed("Return equipment receipt is invalid");
    }
    ReturnEquipmentReceiptResponse response =
        post(
            assetBase + "/return-equipment-receipts",
            idempotencyKey,
            new ReturnEquipmentReceiptRequest(
                returnId,
                returnLineId,
                warehouseId,
                lines.stream()
                    .map(line -> new ReturnEquipmentReceiptLineRequest(line.equipmentId(), line.quantity()))
                    .toList()),
            ReturnEquipmentReceiptResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response == null
        || !returnId.equals(response.returnId())
        || !returnLineId.equals(response.returnLineId())
        || !warehouseId.equals(response.warehouseId())
        || response.lines() == null) {
      throw malformed("Asset-service returned an invalid return equipment receipt");
    }
    List<ReturnEquipmentReceiptLine> received =
        response.lines().stream()
            .map(
                line ->
                    new ReturnEquipmentReceiptLine(
                        line.receiptId(),
                        line.equipmentId(),
                        line.quantity(),
                        line.stockBalanceId(),
                        line.stockBalanceVersion(),
                        line.stockQuantity()))
            .toList();
    if (!sameReturnEquipmentReceiptLines(lines, received)) {
      throw malformed("Asset-service returned mismatched return equipment receipt lines");
    }
    return new ReturnEquipmentReceipt(returnId, returnLineId, warehouseId, received);
  }

  @Override
  public RentalItemSnapshot applyFencedEffect(
      UUID idempotencyKey,
      AssetEffect action,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId) {
    if (action == null || ownerType == null) {
      throw malformed("Logistics asset effect action and owner type are required");
    }
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                action.name(),
                leaseId,
                fencingToken,
                ownerType.name(),
                documentId,
                lineId,
                destinationWarehouseId),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    OperationLeaseResponse response =
        put(
            assetBase + "/operation-leases/" + leaseId + "/release",
            idempotencyKey,
            new LeaseCommandRequest(
                expectedLeaseVersion, fencingToken, ownerType.name(), documentId, lineId),
            OperationLeaseResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response == null) throw malformed("Asset-service returned an empty released operation lease");
    return new OperationLease(
        response.leaseId(),
        response.version(),
        response.rentalItemId(),
        response.fencingToken(),
        response.state(),
        response.expiresAt());
  }

  @Override
  public MediaValidation validateMediaReferences(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReference> references) {
    MediaValidationResponse response =
        postWithoutIdempotency(
            mediaBase + "/logistics/references/validate",
            new MediaValidationRequest(
                ownerType.name(),
                documentId,
                lineId,
                warehouseId,
                references.stream().map(reference -> new MediaReferenceRequest(reference.mediaId(), reference.generation())).toList()),
            MediaValidationResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE);
    if (response == null
        || ownerType.name() == null
        || !ownerType.name().equals(response.ownerType())
        || !documentId.equals(response.documentId())
        || !lineId.equals(response.lineId())
        || !warehouseId.equals(response.warehouseId())
        || response.references() == null) {
      throw malformed("Media-service returned an invalid logistics validation");
    }
    List<MediaReference> validated =
        response.references().stream()
            .map(reference -> new MediaReference(reference.mediaId(), reference.generation()))
            .toList();
    if (!Set.copyOf(validated).equals(Set.copyOf(references))) {
      throw malformed("Media-service returned mismatched logistics references");
    }
    return new MediaValidation(ownerType, documentId, lineId, warehouseId, validated);
  }

  @Override
  public MediaOwnerProof upsertMediaOwnerProof(
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {
    if (ownerType == null
        || ownerType == LogisticsOwnerType.LOGISTICS_SHIPMENT
        || documentId == null
        || lineId == null
        || warehouseId == null
        || ownerRevision < 0
        || aggregateVersion < 0
        || proofEventId == null) {
      throw malformed("Logistics media owner proof is invalid");
    }
    MediaOwnerProofResponse response =
        postWithoutIdempotency(
            mediaBase + "/owner-proofs",
            new MediaOwnerProofRequest(
                ownerType.name(),
                documentId,
                lineId,
                warehouseId,
                ownerRevision,
                aggregateVersion,
                proofEventId,
                active),
            MediaOwnerProofResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE);
    if (!ownerType.name().equals(response.ownerType())
        || !documentId.equals(response.documentId())
        || !lineId.equals(response.lineId())
        || !warehouseId.equals(response.warehouseId())
        || response.ownerRevision() == null
        || response.aggregateVersion() == null
        || response.active() == null
        || ownerRevision != response.ownerRevision()
        || aggregateVersion != response.aggregateVersion()
        || !proofEventId.equals(response.proofEventId())
        || active != response.active()) {
      throw malformed("Media-service returned a mismatched logistics owner proof");
    }
    return new MediaOwnerProof(
        ownerType,
        documentId,
        lineId,
        warehouseId,
        ownerRevision,
        aggregateVersion,
        proofEventId,
        active);
  }

  @Override
  public ReturnShortageSource upsertReturnShortage(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<EquipmentShortage> shortages) {
    ReturnShortageSourceResponse response =
        putWithoutIdempotency(
            maintenanceBase + "/" + returnId + "/lines/" + lineId + "/shortage",
            new UpsertReturnShortageRequest(
                warehouseId,
                rentalItemId,
                rentalItemVersion,
                shortages.stream().map(value -> new EquipmentShortageRequest(value.equipmentId(), value.missingQuantity())).toList()),
            ReturnShortageSourceResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    if (response == null || response.shortages() == null) {
      throw malformed("Maintenance-service returned an empty shortage source");
    }
    return new ReturnShortageSource(
        response.returnId(),
        response.lineId(),
        response.sourceVersion(),
        response.warehouseId(),
        response.rentalItemId(),
        response.rentalItemVersion(),
        response.shortages().stream().map(value -> new EquipmentShortage(value.equipmentId(), value.missingQuantity())).toList(),
        response.snapshotSha256(),
        response.receivedAt());
  }

  @Override
  public EquipmentHold acquireEquipmentHold(
      UUID idempotencyKey,
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {
    EquipmentHoldResponse response =
        post(
            assetBase + "/equipment-holds",
            idempotencyKey,
            new AcquireEquipmentHoldRequest(
                equipmentId,
                warehouseId,
                shipmentId,
                shipmentLineId,
                quantity,
                expectedStockVersion),
            EquipmentHoldResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return hold(response);
  }

  @Override
  public EquipmentHold commandEquipmentHold(
      UUID idempotencyKey,
      EquipmentHoldAction action,
      UUID holdId,
      long expectedHoldVersion,
      UUID shipmentId,
      UUID shipmentLineId) {
    if (action == null || holdId == null) {
      throw malformed("Logistics equipment-hold action and identifier are required");
    }
    String segment = action == EquipmentHoldAction.COMMIT ? "commit" : "release";
    EquipmentHoldResponse response =
        put(
            assetBase + "/equipment-holds/" + holdId + "/" + segment,
            idempotencyKey,
            new EquipmentHoldCommandRequest(expectedHoldVersion, shipmentId, shipmentLineId),
            EquipmentHoldResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return hold(response);
  }

  @Override
  public PreparationTask registerPreparationTask(
      UUID warehouseId, UUID externalTaskId, Integer plannedDurationMinutes, OffsetDateTime deadlineAt) {
    PreparationTaskResponse response =
        postWithoutIdempotency(
            taskBoardBase,
            new RegisterPreparationTaskRequest(
                warehouseId, externalTaskId, plannedDurationMinutes, deadlineAt),
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  @Override
  public PreparationTask readPreparationTask(UUID externalTaskId) {
    PreparationTaskResponse response =
        get(
            taskBoardBase + "/" + externalTaskId,
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  @Override
  public PreparationTask completePreparationTask(
      UUID externalTaskId, long expectedTaskVersion) {
    PreparationTaskResponse response =
        postWithoutIdempotency(
            taskBoardBase + "/" + externalTaskId + "/complete",
            new CompletePreparationTaskRequest(expectedTaskVersion),
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  @Override
  public PreparationTask cancelPreparationTask(UUID externalTaskId, long expectedTaskVersion) {
    PreparationTaskResponse response =
        postWithoutIdempotency(
            taskBoardBase + "/" + externalTaskId + "/cancel",
            new CancelPreparationTaskRequest(expectedTaskVersion),
            PreparationTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return task(response);
  }

  @Override
  public EquipmentMovementReservation acquireEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil) {
    EquipmentMovementReservationResponse response =
        post(
            assetBase + "/equipment-movement-reservations",
            idempotencyKey,
            new AcquireEquipmentMovementReservationRequest(
                movementId,
                lineId,
                equipmentId,
                sourceWarehouseId,
                sourceRentalItemId,
                sourceLocationKind,
                expectedSourceBalanceVersion,
                quantity,
                reservedUntil),
            EquipmentMovementReservationResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return movementReservation(response);
  }

  @Override
  public EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId) {
    EquipmentMovementReservationResponse response =
        put(
            assetBase + "/equipment-movement-reservations/" + reservationId + "/release",
            idempotencyKey,
            new ReleaseEquipmentMovementReservationRequest(
                expectedReservationVersion, movementId, lineId),
            EquipmentMovementReservationResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return movementReservation(response);
  }

  @Override
  public EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines) {
    EquipmentMovementExecutionResponse response =
        post(
            assetBase + "/equipment-movement-reservations/execute",
            idempotencyKey,
            new ExecuteEquipmentMovementRequest(
                movementId,
                lines.stream()
                    .map(
                        line ->
                            new ExecuteEquipmentMovementLineRequest(
                                line.reservationId(),
                                line.expectedReservationVersion(),
                                line.lineId(),
                                line.targetWarehouseId(),
                                line.targetRentalItemId(),
                                line.targetLocationKind()))
                    .toList()),
            EquipmentMovementExecutionResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return movementExecution(response);
  }

  @Override
  public EquipmentMovementBoardTask registerEquipmentMovementTask(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperation> operations) {
    EquipmentMovementBoardTaskResponse response =
        postWithoutIdempotency(
            taskBoardEquipmentMovementBase,
            new RegisterEquipmentMovementTaskRequest(
                warehouseId,
                externalTaskId,
                unitNumber,
                plannedDurationMinutes,
                deadlineAt,
                operations.stream()
                    .map(
                        operation ->
                            new EquipmentMovementOperationRequest(
                                operation.direction(),
                                operation.equipmentCode(),
                                operation.equipmentName(),
                                operation.quantity()))
                    .toList()),
            EquipmentMovementBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return movementBoardTask(response);
  }

  @Override
  public EquipmentMovementBoardTask readEquipmentMovementTask(UUID externalTaskId) {
    EquipmentMovementBoardTaskResponse response =
        get(
            taskBoardEquipmentMovementBase + "/" + externalTaskId,
            EquipmentMovementBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return movementBoardTask(response);
  }

  @Override
  public EquipmentMovementBoardTask cancelEquipmentMovementTask(
      UUID externalTaskId, long expectedTaskVersion) {
    EquipmentMovementBoardTaskResponse response =
        postWithoutIdempotency(
            taskBoardEquipmentMovementBase + "/" + externalTaskId + "/cancel",
            new CancelEquipmentMovementTaskRequest(expectedTaskVersion),
            EquipmentMovementBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return movementBoardTask(response);
  }

  @Override
  public OrderUnitCandidatePage readOrderUnitCandidates(
      UUID orderId, UUID warehouseId, int page, int size, String search) {
    String uri =
        UriComponentsBuilder.fromUriString(assetBase + "/orders/{orderId}/unit-candidates")
            .queryParam("warehouseId", warehouseId)
            .queryParam("page", page)
            .queryParam("size", size)
            .queryParam("search", search == null ? "" : search)
            .buildAndExpand(orderId)
            .encode()
            .toUriString();
    OrderUnitCandidatePageResponse response =
        get(uri, OrderUnitCandidatePageResponse.class, ASSET_CLIENT, ASSET_SCOPE);
    if (response.content() == null) {
      throw malformed("Asset-service returned an invalid order-unit page");
    }
    return new OrderUnitCandidatePage(
        response.content().stream()
            .map(
                candidate ->
                    new OrderUnitCandidate(
                        candidate.reservationId(),
                        candidate.added(),
                        orderRentalItem(candidate.unit())))
            .toList(),
        response.page(),
        response.size(),
        response.totalElements(),
        response.totalPages());
  }

  @Override
  public List<OrderUnitReservation> readOrderUnits(UUID orderId) {
    return getOrderList(assetBase + "/orders/" + orderId + "/units").stream()
        .map(HttpLogisticsDependencyGateway::orderReservation)
        .toList();
  }

  @Override
  public OrderUnitReservation reserveOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {
    return orderReservation(
        postOrder(
            assetBase + "/orders/" + orderId + "/units",
            idempotencyKey,
            new ReserveOrderUnitRequest(
                warehouseId,
                unitId,
                clientId,
                tenantSnapshot,
                actorSubjectId,
                actorRole),
            OrderUnitReservationResponse.class));
  }

  @Override
  public OrderUnitReservation releaseOrderUnit(
      UUID idempotencyKey,
      UUID orderId,
      UUID unitId,
      UUID actorSubjectId,
      String actorRole) {
    return orderReservation(
        postOrder(
            assetBase + "/orders/" + orderId + "/units/" + unitId + "/release",
            idempotencyKey,
            new OrderActorRequest(actorSubjectId, actorRole),
            OrderUnitReservationResponse.class));
  }

  @Override
  public List<OrderUnitReservation> releaseAllOrderUnits(
      UUID idempotencyKey, UUID orderId, UUID actorSubjectId, String actorRole) {
    return postOrderList(
            assetBase + "/orders/" + orderId + "/units/release-all",
            idempotencyKey,
            new OrderActorRequest(actorSubjectId, actorRole))
        .stream()
        .map(HttpLogisticsDependencyGateway::orderReservation)
        .toList();
  }

  @Override
  public List<OrderEquipmentReservation> replaceOrderEquipmentReservations(
      UUID idempotencyKey,
      UUID orderId,
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderEquipmentRequirement> requirements) {
    List<OrderEquipmentReservationResponse> response =
        putOrderEquipmentReservationList(
            assetBase + "/orders/" + orderId + "/equipment-reservations",
            idempotencyKey,
            new ReplaceOrderEquipmentReservationsRequest(
                warehouseId,
                actorSubjectId,
                actorRole,
                requirements == null
                    ? null
                    : requirements.stream()
                        .map(
                            requirement ->
                                new OrderEquipmentRequirementRequest(
                                    requirement.equipmentId(), requirement.quantity()))
                        .toList()));
    return orderEquipmentReservations(response);
  }

  @Override
  public OrderFurnitureMovementPlan planOrderFurnitureMovements(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      List<OrderEquipmentRequirement> unitRequirements,
      List<OrderEquipmentRequirement> orderRequirements) {
    OrderFurnitureMovementPlanResponse response =
        postOrderWithoutIdempotency(
            assetBase + "/orders/" + orderId + "/equipment-movement-plan",
            new OrderFurnitureMovementPlanRequest(
                warehouseId,
                unitId,
                orderEquipmentRequirementRequests(unitRequirements),
                orderEquipmentRequirementRequests(orderRequirements)),
            OrderFurnitureMovementPlanResponse.class);
    return orderFurnitureMovementPlan(response);
  }

  @Override
  public CabinFurnitureMovementPlan planCabinFurnitureMovements(
      UUID warehouseId,
      UUID rentalItemId,
      List<CabinFurnitureRequirement> requirements) {
    CabinFurnitureMovementPlanResponse response =
        postWithoutIdempotency(
            assetBase + "/rental-items/" + rentalItemId + "/furniture-movement-plan",
            new CabinFurnitureMovementPlanRequest(
                warehouseId, cabinFurnitureRequirementRequests(requirements)),
            CabinFurnitureMovementPlanResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return cabinFurnitureMovementPlan(response);
  }

  private <T> T get(String uri, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private List<OrderUnitReservationResponse> getOrderList(String uri) {
    try {
      List<OrderUnitReservationResponse> response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      if (response == null) throw malformed("Asset-service returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw orderDependencyFailure(exception);
    }
  }

  private <T> T postOrder(String uri, UUID key, Object body, Class<T> type) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Asset-service returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw orderDependencyFailure(exception);
    }
  }

  private List<OrderUnitReservationResponse> postOrderList(
      String uri, UUID key, Object body) {
    try {
      List<OrderUnitReservationResponse> response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .body(body)
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      if (response == null) throw malformed("Asset-service returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw orderDependencyFailure(exception);
    }
  }

  private List<OrderEquipmentReservationResponse> putOrderEquipmentReservationList(
      String uri, UUID key, Object body) {
    try {
      List<OrderEquipmentReservationResponse> response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .body(body)
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      if (response == null) throw malformed("Asset-service returned an empty order equipment response");
      return response;
    } catch (RuntimeException exception) {
      throw orderDependencyFailure(exception);
    }
  }

  private <T> T postOrderWithoutIdempotency(String uri, Object body, Class<T> type) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(ASSET_CLIENT, ASSET_SCOPE))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Asset-service returned an empty order movement plan");
      return response;
    } catch (RuntimeException exception) {
      throw orderDependencyFailure(exception);
    }
  }

  private <T> T post(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T postWithoutIdempotency(
      String uri, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .post()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T put(
      String uri, UUID key, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header("Idempotency-Key", key.toString())
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private <T> T putWithoutIdempotency(
      String uri, Object body, Class<T> type, String registration, String scope) {
    try {
      T response =
          client
              .put()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(registration, scope))
              .body(body)
              .retrieve()
              .body(type);
      if (response == null) throw malformed("Dependency returned an empty response");
      return response;
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  private String bearer(String registration, String requiredScope) {
    var request =
        OAuth2AuthorizeRequest.withClientRegistrationId(registration)
            .principal("logistics-service:" + registration)
            .build();
    var authorized = authorizedClients.authorize(request);
    if (authorized == null
        || authorized.getAccessToken() == null
        || !authorized.getAccessToken().getScopes().equals(Set.of(requiredScope))) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.CONFIGURATION,
          "Dependency token does not have its one exact approved scope");
    }
    return "Bearer " + authorized.getAccessToken().getTokenValue();
  }

  private static RentalItemSnapshot snapshot(RentalItemSnapshotResponse response) {
    if (response == null || response.contents() == null) {
      throw malformed("Asset-service returned an empty rental-item snapshot");
    }
    return new RentalItemSnapshot(
        response.assetId(),
        response.version(),
        response.warehouseId(),
        response.status(),
        response.contents().stream()
            .map(content -> new EquipmentContent(content.equipmentId(), content.quantity()))
            .toList());
  }

  private static EquipmentHold hold(EquipmentHoldResponse response) {
    if (response == null
        || response.holdId() == null
        || response.version() < 0
        || response.state() == null
        || response.expiresAt() == null) {
      throw malformed("Asset-service returned an invalid logistics equipment hold");
    }
    return new EquipmentHold(
        response.holdId(), response.version(), response.state(), response.expiresAt(), response.committedAt());
  }

  private static PreparationTask task(PreparationTaskResponse response) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || response.externalTaskId() == null
        || response.status() == null) {
      throw malformed("Task-board returned an invalid logistics preparation task");
    }
    return new PreparationTask(
        response.taskId(),
        response.taskVersion(),
        response.warehouseId(),
        response.externalTaskId(),
        response.status(),
        response.doneAt());
  }

  private static EquipmentMovementReservation movementReservation(
      EquipmentMovementReservationResponse response) {
    if (response == null
        || response.reservationId() == null
        || response.version() < 0
        || !"LOGISTICS_EQUIPMENT_MOVEMENT".equals(response.ownerType())
        || response.movementId() == null
        || response.lineId() == null
        || response.equipmentId() == null
        || response.equipmentCode() == null
        || response.equipmentCode().isBlank()
        || response.equipmentName() == null
        || response.equipmentName().isBlank()
        || response.sourceBalanceId() == null
        || response.sourceWarehouseId() == null
        || response.sourceLocationKind() == null
        || response.quantity() < 1
        || response.state() == null
        || response.reservedUntil() == null) {
      throw malformed("Asset-service returned an invalid equipment movement reservation");
    }
    return new EquipmentMovementReservation(
        response.reservationId(),
        response.version(),
        response.ownerType(),
        response.movementId(),
        response.lineId(),
        response.equipmentId(),
        response.equipmentCode(),
        response.equipmentName(),
        response.sourceBalanceId(),
        response.sourceWarehouseId(),
        response.sourceRentalItemId(),
        response.sourceLocationKind(),
        response.quantity(),
        response.state(),
        response.reservedUntil(),
        response.executedAt());
  }

  private static EquipmentMovementExecution movementExecution(
      EquipmentMovementExecutionResponse response) {
    if (response == null || response.movementId() == null || response.lines() == null) {
      throw malformed("Asset-service returned an invalid equipment movement execution");
    }
    List<EquipmentMovementExecutionLine> lines =
        response.lines().stream()
            .map(
                line -> {
                  if (line == null
                      || line.reservationId() == null
                      || line.reservationVersion() < 0
                      || line.lineId() == null
                      || line.movement() == null) {
                    throw malformed("Asset-service returned an invalid equipment movement execution line");
                  }
                  EquipmentMovementEventResponse movement = line.movement();
                  if (movement.id() == null
                      || movement.version() < 0
                      || movement.equipmentId() == null
                      || movement.sourceBalanceId() == null
                      || movement.targetBalanceId() == null
                      || movement.quantity() < 1
                      || movement.kind() == null
                      || movement.occurredAt() == null) {
                    throw malformed("Asset-service returned an invalid equipment movement event");
                  }
                  return new EquipmentMovementExecutionLine(
                      line.reservationId(),
                      line.reservationVersion(),
                      line.lineId(),
                      new EquipmentMovementEvent(
                          movement.id(),
                          movement.version(),
                          movement.equipmentId(),
                          movement.sourceBalanceId(),
                          movement.targetBalanceId(),
                          movement.quantity(),
                          movement.kind(),
                          movement.occurredAt()));
                })
            .toList();
    return new EquipmentMovementExecution(response.movementId(), lines);
  }

  private static EquipmentMovementBoardTask movementBoardTask(
      EquipmentMovementBoardTaskResponse response) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || response.externalTaskId() == null
        || response.status() == null) {
      throw malformed("Task-board returned an invalid equipment movement task");
    }
    return new EquipmentMovementBoardTask(
        response.taskId(),
        response.taskVersion(),
        response.warehouseId(),
        response.externalTaskId(),
        response.status(),
        response.doneAt());
  }

  private static boolean sameReturnEquipmentReceiptLines(
      List<ReturnEquipmentReceiptLine> requested,
      List<ReturnEquipmentReceiptLine> received) {
    if (requested.size() != received.size()) return false;
    java.util.Map<UUID, Long> expected = new java.util.HashMap<>();
    for (ReturnEquipmentReceiptLine line : requested) {
      if (line == null
          || line.equipmentId() == null
          || line.quantity() < 1
          || expected.put(line.equipmentId(), line.quantity()) != null) {
        return false;
      }
    }
    java.util.Map<UUID, Long> actual = new java.util.HashMap<>();
    for (ReturnEquipmentReceiptLine line : received) {
      if (line == null
          || line.receiptId() == null
          || line.equipmentId() == null
          || line.quantity() < 1
          || line.stockBalanceId() == null
          || line.stockBalanceVersion() < 0
          || line.stockQuantity() < line.quantity()
          || actual.put(line.equipmentId(), line.quantity()) != null) {
        return false;
      }
    }
    return expected.equals(actual);
  }

  private static OrderUnitReservation orderReservation(
      OrderUnitReservationResponse response) {
    if (response == null || response.unit() == null) {
      throw malformed("Asset-service returned an invalid order-unit reservation");
    }
    return new OrderUnitReservation(
        response.reservationId(),
        response.reservationVersion(),
        response.orderId(),
        response.rentalItemId(),
        response.warehouseId(),
        response.state(),
        response.addedBySubjectId(),
        response.addedByRole(),
        response.createdAt(),
        response.releasedAt(),
        response.replayed(),
        orderRentalItem(response.unit()));
  }

  private static OrderRentalItem orderRentalItem(OrderRentalItemResponse response) {
    if (response == null || response.contents() == null || response.tags() == null) {
      throw malformed("Asset-service returned an invalid order rental item");
    }
    return new OrderRentalItem(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.number(),
        response.status(),
        response.rentalType(),
        response.dimensions(),
        response.finishing(),
        response.category(),
        response.characteristics(),
        response.linoleum(),
        List.copyOf(response.tags()),
        response.contents().stream()
            .map(
                content ->
                    new OrderEquipmentContent(
                        content.equipmentId(),
                        content.equipmentCode(),
                        content.equipmentName(),
                        content.quantity(),
                        content.locationKind()))
            .toList(),
        response.createdAt(),
        response.updatedAt());
  }

  private static List<OrderEquipmentRequirementRequest> orderEquipmentRequirementRequests(
      List<OrderEquipmentRequirement> requirements) {
    if (requirements == null) {
      throw malformed("Order equipment requirements are required");
    }
    return requirements.stream()
        .map(
            requirement -> {
              if (requirement == null) {
                throw malformed("Order equipment requirement is invalid");
              }
              return new OrderEquipmentRequirementRequest(
                  requirement.equipmentId(), requirement.quantity());
            })
        .toList();
  }

  private static List<OrderEquipmentReservation> orderEquipmentReservations(
      List<OrderEquipmentReservationResponse> response) {
    if (response == null) {
      throw malformed("Asset-service returned an empty order equipment reservation list");
    }
    java.util.HashSet<UUID> ids = new java.util.HashSet<>();
    List<OrderEquipmentReservation> values = new java.util.ArrayList<>(response.size());
    for (OrderEquipmentReservationResponse value : response) {
      if (value == null
          || value.equipmentId() == null
          || value.equipmentCode() == null
          || value.equipmentCode().isBlank()
          || value.equipmentName() == null
          || value.equipmentName().isBlank()
          || value.quantity() < 1
          || value.availableQuantity() < 0
          || !ids.add(value.equipmentId())) {
        throw malformed("Asset-service returned an invalid order equipment reservation");
      }
      values.add(
          new OrderEquipmentReservation(
              value.equipmentId(),
              value.equipmentCode(),
              value.equipmentName(),
              value.quantity(),
              value.availableQuantity()));
    }
    return List.copyOf(values);
  }

  private static OrderFurnitureMovementPlan orderFurnitureMovementPlan(
      OrderFurnitureMovementPlanResponse response) {
    if (response == null
        || response.orderId() == null
        || response.rentalItemId() == null
        || response.unitNumber() == null
        || response.lines() == null) {
      throw malformed("Asset-service returned an invalid order furniture movement plan");
    }
    List<OrderFurnitureMovementPlanLine> lines = new java.util.ArrayList<>(response.lines().size());
    for (OrderFurnitureMovementPlanLineResponse line : response.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.equipmentCode() == null
          || line.equipmentName() == null
          || line.sourceBalanceId() == null
          || line.sourceWarehouseId() == null
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.targetWarehouseId() == null
          || line.targetLocationKind() == null
          || line.quantity() < 1) {
        throw malformed("Asset-service returned an invalid order furniture movement line");
      }
      lines.add(
          new OrderFurnitureMovementPlanLine(
              line.equipmentId(),
              line.equipmentCode(),
              line.equipmentName(),
              line.sourceBalanceId(),
              line.sourceWarehouseId(),
              line.sourceRentalItemId(),
              line.sourceLocationKind(),
              line.expectedSourceBalanceVersion(),
              line.targetWarehouseId(),
              line.targetRentalItemId(),
              line.targetLocationKind(),
              line.quantity()));
    }
    return new OrderFurnitureMovementPlan(
        response.orderId(), response.rentalItemId(), response.unitNumber(), List.copyOf(lines));
  }

  private static List<CabinFurnitureRequirementRequest> cabinFurnitureRequirementRequests(
      List<CabinFurnitureRequirement> requirements) {
    if (requirements == null) {
      throw malformed("Cabin furniture requirements are required");
    }
    return requirements.stream()
        .map(
            requirement -> {
              if (requirement == null) {
                throw malformed("Cabin furniture requirement is invalid");
              }
              return new CabinFurnitureRequirementRequest(
                  requirement.equipmentId(), requirement.quantity());
            })
        .toList();
  }

  private static CabinFurnitureMovementPlan cabinFurnitureMovementPlan(
      CabinFurnitureMovementPlanResponse response) {
    if (response == null
        || response.rentalItemId() == null
        || response.unitNumber() == null
        || response.unitNumber().isBlank()
        || response.lines() == null) {
      throw malformed("Asset-service returned an invalid cabin furniture movement plan");
    }
    List<CabinFurnitureMovementPlanLine> lines = new java.util.ArrayList<>(response.lines().size());
    for (CabinFurnitureMovementPlanLineResponse line : response.lines()) {
      if (line == null
          || line.equipmentId() == null
          || line.equipmentCode() == null
          || line.equipmentCode().isBlank()
          || line.equipmentName() == null
          || line.equipmentName().isBlank()
          || line.sourceBalanceId() == null
          || line.sourceWarehouseId() == null
          || line.sourceLocationKind() == null
          || line.expectedSourceBalanceVersion() < 0
          || line.targetWarehouseId() == null
          || line.targetLocationKind() == null
          || line.quantity() < 1) {
        throw malformed("Asset-service returned an invalid cabin furniture movement line");
      }
      lines.add(
          new CabinFurnitureMovementPlanLine(
              line.equipmentId(),
              line.equipmentCode(),
              line.equipmentName(),
              line.sourceBalanceId(),
              line.sourceWarehouseId(),
              line.sourceRentalItemId(),
              line.sourceLocationKind(),
              line.expectedSourceBalanceVersion(),
              line.targetWarehouseId(),
              line.targetRentalItemId(),
              line.targetLocationKind(),
              line.quantity()));
    }
    return new CabinFurnitureMovementPlan(
        response.rentalItemId(), response.unitNumber(), List.copyOf(lines));
  }

  private static LogisticsDependencyException dependencyFailure(RuntimeException exception) {
    if (exception instanceof LogisticsDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      if (status != null
          && status.is4xxClientError()
          && status != HttpStatus.TOO_MANY_REQUESTS) {
        return new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            "Dependency rejected the logistics command",
            exception);
      }
    }
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Dependency outcome is unknown",
        exception);
  }

  private static LogisticsDependencyException orderDependencyFailure(
      RuntimeException exception) {
    if (exception instanceof LogisticsDependencyException known) return known;
    if (exception instanceof RestClientResponseException response) {
      HttpStatus status = HttpStatus.resolve(response.getStatusCode().value());
      if (status != null
          && status.is4xxClientError()
          && status != HttpStatus.TOO_MANY_REQUESTS) {
        return new LogisticsDependencyException(
            LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
            safeOrderDependencyCode(response.getResponseBodyAsString()),
            "Asset-service rejected the order command",
            exception);
      }
    }
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Asset-service order command outcome is unknown",
        exception);
  }

  private static String safeOrderDependencyCode(String body) {
    if (body == null) return null;
    for (String code :
        List.of(
            "UNIT_ALREADY_RESERVED",
            "UNIT_WAREHOUSE_MISMATCH",
            "UNIT_NOT_AVAILABLE",
            "UNIT_NOT_EDITABLE",
            "EQUIPMENT_QUANTITY_CONFLICT",
            "INSUFFICIENT_STOCK",
            "INSUFFICIENT_EQUIPMENT",
            "INSUFFICIENT_EQUIPMENT_SOURCE",
            "EQUIPMENT_NOT_AVAILABLE",
            "ORDER_RESERVATION_MISMATCH",
            "ORDER_WAREHOUSE_MISMATCH",
            "ASSET_NOT_FOUND")) {
      if (body.contains("\"code\":\"" + code + "\"")) return code;
    }
    return null;
  }

  private static LogisticsDependencyException malformed(String message) {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.CONFIGURATION, message);
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private record WarehouseIdentityResponse(UUID id, long version, boolean active, String timeZone) {}

  private record EquipmentContentResponse(UUID equipmentId, long quantity) {}

  private record RentalItemSnapshotResponse(
      UUID assetId,
      long version,
      UUID warehouseId,
      String status,
      List<EquipmentContentResponse> contents) {}

  private record AcquireLeaseRequest(
      UUID rentalItemId,
      String ownerType,
      UUID documentId,
      UUID lineId,
      long expectedRentalItemVersion,
      UUID rentalOrderId) {}

  private record OperationLeaseResponse(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  private record FencedEffectRequest(
      long expectedVersion,
      String action,
      UUID leaseId,
      long fencingToken,
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID destinationWarehouseId) {}

  private record ReturnEquipmentReceiptLineRequest(UUID equipmentId, long quantity) {}

  private record ReturnEquipmentReceiptRequest(
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLineRequest> lines) {}

  private record ReturnEquipmentReceiptLineResponse(
      UUID receiptId,
      UUID equipmentId,
      long quantity,
      UUID stockBalanceId,
      long stockBalanceVersion,
      long stockQuantity) {}

  private record ReturnEquipmentReceiptResponse(
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLineResponse> lines) {}

  private record LeaseCommandRequest(
      long expectedVersion,
      long fencingToken,
      String ownerType,
      UUID documentId,
      UUID lineId) {}

  private record MediaReferenceRequest(UUID mediaId, long generation) {}

  private record MediaValidationRequest(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReferenceRequest> references) {}

  private record MediaValidationResponse(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      List<MediaReferenceRequest> references) {}

  private record MediaOwnerProofRequest(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      long ownerRevision,
      long aggregateVersion,
      UUID proofEventId,
      boolean active) {}

  private record MediaOwnerProofResponse(
      String ownerType,
      UUID documentId,
      UUID lineId,
      UUID warehouseId,
      Long ownerRevision,
      Long aggregateVersion,
      UUID proofEventId,
      Boolean active) {}

  private record EquipmentShortageRequest(UUID equipmentId, long missingQuantity) {}

  private record UpsertReturnShortageRequest(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<EquipmentShortageRequest> shortages) {}

  private record ReturnShortageSourceResponse(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      List<EquipmentShortageRequest> shortages,
      String snapshotSha256,
      OffsetDateTime receivedAt) {}

  private record AcquireEquipmentHoldRequest(
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {}

  private record EquipmentHoldCommandRequest(
      long expectedVersion, UUID shipmentId, UUID shipmentLineId) {}

  private record EquipmentHoldResponse(
      UUID holdId,
      long version,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt) {}

  private record RegisterPreparationTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt) {}

  private record CancelPreparationTaskRequest(long expectedTaskVersion) {}

  private record CompletePreparationTaskRequest(long expectedTaskVersion) {}

  private record PreparationTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}

  private record AcquireEquipmentMovementReservationRequest(
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil) {}

  private record ReleaseEquipmentMovementReservationRequest(
      long expectedReservationVersion, UUID movementId, UUID lineId) {}

  private record EquipmentMovementReservationResponse(
      UUID reservationId,
      long version,
      String ownerType,
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      String equipmentCode,
      String equipmentName,
      UUID sourceBalanceId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long quantity,
      String state,
      OffsetDateTime reservedUntil,
      OffsetDateTime executedAt) {}

  private record ExecuteEquipmentMovementLineRequest(
      UUID reservationId,
      long expectedReservationVersion,
      UUID lineId,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind) {}

  private record ExecuteEquipmentMovementRequest(
      UUID movementId, List<ExecuteEquipmentMovementLineRequest> lines) {}

  private record EquipmentMovementEventResponse(
      UUID id,
      long version,
      UUID equipmentId,
      UUID sourceBalanceId,
      UUID targetBalanceId,
      long quantity,
      String kind,
      OffsetDateTime occurredAt) {}

  private record EquipmentMovementExecutionLineResponse(
      UUID reservationId,
      long reservationVersion,
      UUID lineId,
      EquipmentMovementEventResponse movement) {}

  private record EquipmentMovementExecutionResponse(
      UUID movementId, List<EquipmentMovementExecutionLineResponse> lines) {}

  private record EquipmentMovementOperationRequest(
      String direction, String equipmentCode, String equipmentName, long quantity) {}

  private record RegisterEquipmentMovementTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperationRequest> operations) {}

  private record CancelEquipmentMovementTaskRequest(long expectedTaskVersion) {}

  private record EquipmentMovementBoardTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}

  private record ReserveOrderUnitRequest(
      UUID warehouseId,
      UUID rentalItemId,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {}

  private record OrderActorRequest(UUID actorSubjectId, String actorRole) {}

  private record OrderEquipmentRequirementRequest(UUID equipmentId, long quantity) {}

  private record ReplaceOrderEquipmentReservationsRequest(
      UUID warehouseId,
      UUID actorSubjectId,
      String actorRole,
      List<OrderEquipmentRequirementRequest> requirements) {}

  private record OrderEquipmentReservationResponse(
      UUID equipmentId,
      String equipmentCode,
      String equipmentName,
      long quantity,
      long availableQuantity) {}

  private record OrderFurnitureMovementPlanRequest(
      UUID warehouseId,
      UUID rentalItemId,
      List<OrderEquipmentRequirementRequest> requirements,
      List<OrderEquipmentRequirementRequest> orderRequirements) {}

  private record OrderFurnitureMovementPlanLineResponse(
      UUID equipmentId,
      String equipmentCode,
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

  private record OrderFurnitureMovementPlanResponse(
      UUID orderId,
      UUID rentalItemId,
      String unitNumber,
      List<OrderFurnitureMovementPlanLineResponse> lines) {}

  private record CabinFurnitureRequirementRequest(UUID equipmentId, long quantity) {}

  private record CabinFurnitureMovementPlanRequest(
      UUID warehouseId, List<CabinFurnitureRequirementRequest> requirements) {}

  private record CabinFurnitureMovementPlanLineResponse(
      UUID equipmentId,
      String equipmentCode,
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

  private record CabinFurnitureMovementPlanResponse(
      UUID rentalItemId,
      String unitNumber,
      List<CabinFurnitureMovementPlanLineResponse> lines) {}

  private record OrderEquipmentContentResponse(
      UUID equipmentId,
      String equipmentCode,
      String equipmentName,
      long quantity,
      String locationKind) {}

  private record OrderRentalItemResponse(
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
      List<OrderEquipmentContentResponse> contents,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  private record OrderUnitReservationResponse(
      UUID reservationId,
      long reservationVersion,
      UUID orderId,
      UUID rentalItemId,
      UUID warehouseId,
      String state,
      UUID addedBySubjectId,
      String addedByRole,
      OffsetDateTime createdAt,
      OffsetDateTime releasedAt,
      boolean replayed,
      OrderRentalItemResponse unit) {}

  private record OrderUnitCandidateResponse(
      UUID reservationId, boolean added, OrderRentalItemResponse unit) {}

  private record OrderUnitCandidatePageResponse(
      List<OrderUnitCandidateResponse> content,
      long page,
      long size,
      long totalElements,
      long totalPages) {}

}
