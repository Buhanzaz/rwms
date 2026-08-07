package dev.buhanzaz.rwms.logistics.integration;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
  private static final String WAREHOUSE_TIME_ZONE_CLIENT = "logistics-warehouse-timezone";
  private static final String WAREHOUSE_OPERATION_CLIENT = "logistics-warehouse-operation";
  private static final String WAREHOUSE_LIFECYCLE_READ_CLIENT =
      "logistics-warehouse-lifecycle-read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_CLIENT =
      "logistics-warehouse-lifecycle-confirm";
  private static final String MAINTENANCE_CLIENT = "logistics-maintenance";
  private static final String MEDIA_CLIENT = "logistics-media";
  private static final String TASK_BOARD_CLIENT = "logistics-task-board";
  private static final String ASSET_SCOPE = "asset.logistics";
  private static final String WAREHOUSE_SCOPE = "warehouse.logistics";
  private static final String WAREHOUSE_TIME_ZONE_SCOPE = "warehouse.timezone.read";
  private static final String WAREHOUSE_OPERATION_SCOPE = "warehouse.operation.mark";
  private static final String WAREHOUSE_LIFECYCLE_READ_SCOPE = "warehouse.lifecycle.read";
  private static final String WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE =
      "warehouse.lifecycle.confirm";
  private static final String MAINTENANCE_SCOPE = "maintenance.logistics";
  private static final String MEDIA_SCOPE = "media.logistics";
  private static final String TASK_BOARD_SCOPE = "task-board.logistics";
  private static final Set<String> REPAIR_STAGE_STATES =
      Set.of("PLANNED", "QUEUED", "IN_PROGRESS", "DONE", "CANCELLED");

  private final RestClient client;
  private final OAuth2AuthorizedClientManager authorizedClients;
  private final String assetBase;
  private final String warehouseBase;
  private final String warehouseInternalBase;
  private final String maintenanceBase;
  private final String mediaBase;
  private final String taskBoardBase;
  private final String taskBoardEquipmentMovementBase;
  private final String taskBoardTaskBase;
  private final String taskBoardDriverBase;
  private final String taskBoardDriverTaskBase;

  HttpLogisticsDependencyGateway(
      RestClient client,
      OAuth2AuthorizedClientManager authorizedClients,
      LogisticsDependencyProperties.Validated properties) {
    this.client = client;
    this.authorizedClients = authorizedClients;
    assetBase = strip(properties.assetBaseUrl().toString()) + "/api/internal/asset/v1/logistics";
    warehouseBase =
        strip(properties.warehouseBaseUrl().toString()) + "/api/internal/warehouse/v1/warehouses/logistics";
    warehouseInternalBase =
        strip(properties.warehouseBaseUrl().toString()) + "/api/internal/warehouse/v1";
    maintenanceBase =
        strip(properties.maintenanceBaseUrl().toString()) + "/api/internal/maintenance/v1/logistics";
    mediaBase = strip(properties.mediaBaseUrl().toString()) + "/api/internal/media/v1";
    taskBoardBase = strip(properties.taskBoardBaseUrl().toString());
    taskBoardEquipmentMovementBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/equipment-movement-tasks";
    taskBoardTaskBase =
        taskBoardBase + "/api/internal/task-board/v1/tasks";
    taskBoardDriverBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/warehouses";
    taskBoardDriverTaskBase =
        taskBoardBase + "/api/internal/task-board/v1/logistics/tasks";
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
    return new WarehouseIdentity(
        response.id(),
        response.version(),
        response.active(),
        response.name(),
        response.city(),
        response.timeZone());
  }

  @Override
  public WarehouseOperationAdmission warehouseAdmission(
      UUID warehouseId, WarehouseOperationDirection direction) {
    if (warehouseId == null || direction == null) {
      throw new IllegalArgumentException("Warehouse admission identity is required");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseInternalBase + "/warehouses/" + warehouseId + "/admission")
            .queryParam("direction", direction.name())
            .build()
            .encode()
            .toUriString();
    WarehouseOperationAdmission response =
        get(
            uri,
            WarehouseOperationAdmission.class,
            WAREHOUSE_LIFECYCLE_READ_CLIENT,
            WAREHOUSE_LIFECYCLE_READ_SCOPE);
    boolean expectedAdmission =
        response.lifecycleState() == WarehouseLifecycleState.ACTIVE
            || (response.lifecycleState() == WarehouseLifecycleState.DRAINING
                && direction == WarehouseOperationDirection.OUTGOING);
    if (!warehouseId.equals(response.warehouseId())
        || response.direction() != direction
        || response.admitted() != expectedAdmission) {
      throw malformed("Warehouse-service returned mismatched admission truth");
    }
    return response;
  }

  @Override
  public WarehouseLifecycleReadinessWorkPage warehouseLifecycleReadinessWork(
      UUID after, int limit) {
    if (limit < 1 || limit > 500) {
      throw new IllegalArgumentException("Warehouse readiness page size must be from 1 to 500");
    }
    UriComponentsBuilder uri =
        UriComponentsBuilder.fromUriString(
                warehouseInternalBase + "/lifecycle/readiness-work")
            .queryParam("limit", limit);
    if (after != null) uri.queryParam("after", after);
    WarehouseLifecycleReadinessWorkPage response =
        get(
            uri.build().encode().toUriString(),
            WarehouseLifecycleReadinessWorkPage.class,
            WAREHOUSE_LIFECYCLE_READ_CLIENT,
            WAREHOUSE_LIFECYCLE_READ_SCOPE);
    if (response.items().stream()
            .map(WarehouseLifecycleReadinessWork::warehouseId)
            .distinct()
            .count()
        != response.items().size()) {
      throw malformed("Warehouse-service returned duplicate readiness work");
    }
    if (response.nextAfter() != null
        && (response.items().isEmpty()
            || !response.nextAfter().equals(response.items().getLast().warehouseId()))) {
      throw malformed("Warehouse-service returned an invalid readiness cursor");
    }
    return response;
  }

  @Override
  public WarehouseLifecycleReadinessConfirmation confirmWarehouseLifecycleReadiness(
      UUID warehouseId, long expectedVersion) {
    if (warehouseId == null || expectedVersion < 0) {
      throw new IllegalArgumentException("Warehouse readiness fence is invalid");
    }
    WarehouseLifecycleReadinessConfirmation response =
        postWithoutIdempotency(
            warehouseInternalBase + "/warehouses/" + warehouseId + "/lifecycle-readiness",
            new WarehouseLifecycleReadinessRequest(expectedVersion),
            WarehouseLifecycleReadinessConfirmation.class,
            WAREHOUSE_LIFECYCLE_CONFIRM_CLIENT,
            WAREHOUSE_LIFECYCLE_CONFIRM_SCOPE);
    if (!warehouseId.equals(response.warehouseId())
        || response.warehouseVersion() < expectedVersion) {
      throw malformed("Warehouse-service returned mismatched logistics readiness confirmation");
    }
    return response;
  }

  @Override
  public WarehouseTimeZone warehouseTimeZoneAt(UUID warehouseId, OffsetDateTime at) {
    if (warehouseId == null || at == null) {
      throw new IllegalArgumentException("Warehouse timezone lookup identity is required");
    }
    String uri =
        UriComponentsBuilder.fromUriString(
                warehouseInternalBase + "/warehouses/" + warehouseId + "/time-zone")
            .queryParam("at", at)
            .build()
            .encode()
            .toUriString();
    WarehouseTimeZone response =
        get(
            uri,
            WarehouseTimeZone.class,
            WAREHOUSE_TIME_ZONE_CLIENT,
            WAREHOUSE_TIME_ZONE_SCOPE);
    if (!warehouseId.equals(response.warehouseId()) || response.effectiveFrom().isAfter(at)) {
      throw malformed("Warehouse-service returned mismatched effective timezone truth");
    }
    return response;
  }

  @Override
  public void markWarehouseOperation(
      UUID warehouseId, UUID operationId, OffsetDateTime occurredAt) {
    if (warehouseId == null || operationId == null || occurredAt == null) {
      throw new IllegalArgumentException("Warehouse operation mark identity is required");
    }
    try {
      client
          .post()
          .uri(warehouseInternalBase + "/warehouses/" + warehouseId + "/operation-marks")
          .header(
              HttpHeaders.AUTHORIZATION,
              bearer(WAREHOUSE_OPERATION_CLIENT, WAREHOUSE_OPERATION_SCOPE))
          .body(new WarehouseOperationMarkRequest(operationId, occurredAt))
          .retrieve()
          .toBodilessEntity();
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
  }

  @Override
  public List<WarehouseIdentity> listWarehouseIdentities() {
    try {
      List<WarehouseIdentityResponse> response =
          client
              .get()
              .uri(warehouseBase)
              .header(
                  HttpHeaders.AUTHORIZATION,
                  bearer(WAREHOUSE_CLIENT, WAREHOUSE_SCOPE))
              .retrieve()
              .body(new ParameterizedTypeReference<>() {});
      if (response == null) {
        throw malformed("Warehouse-service returned an empty identity list");
      }
      return response.stream()
          .map(
              value ->
                  new WarehouseIdentity(
                      value.id(),
                      value.version(),
                      value.active(),
                      value.name(),
                      value.city(),
                      value.timeZone()))
          .toList();
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
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
                null,
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
      boolean estimate) {
    RentalItemSnapshotResponse response =
        put(
            assetBase + "/rental-items/" + assetId + "/effects",
            idempotencyKey,
            new FencedEffectRequest(
                expectedAssetVersion,
                // The asset action label is retained for stable replay of already queued return
                // effects; it represents the estimate-needed branch, not a client-selected list.
                estimate ? "RETURN_SETTLE_SHORTAGE" : "RETURN_SETTLE_FREE",
                leaseId,
                fencingToken,
                LogisticsOwnerType.LOGISTICS_RETURN.name(),
                documentId,
                lineId,
                null,
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
        destinationWarehouseId,
        null);
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
      UUID destinationWarehouseId,
      String transferAssetStatus) {
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
                destinationWarehouseId,
                transferAssetStatus),
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return snapshot(response);
  }

  @Override
  public TransferRepairDeparture prepareTransferDeparture(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    TransferRepairDepartureResponse response =
        post(
            maintenanceTransferLineBase(transferId, lineId) + "/prepare-departure",
            idempotencyKey,
            new TransferRepairContextRequest(
                rentalItemId, sourceWarehouseId, targetWarehouseId),
            TransferRepairDepartureResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    return transferRepairDeparture(response);
  }

  @Override
  public TransferRepairArrivalPreflight preflightTransferArrival(
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      UUID sourceWarehouseId,
      UUID targetWarehouseId) {
    TransferRepairArrivalPreflightResponse response =
        postWithoutIdempotency(
            maintenanceTransferLineBase(transferId, lineId) + "/arrival-preflight",
            new TransferRepairContextRequest(
                rentalItemId, sourceWarehouseId, targetWarehouseId),
            TransferRepairArrivalPreflightResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    return transferRepairArrivalPreflight(response);
  }

  @Override
  public TransferRepairArrivalCompletion completeTransferArrival(
      UUID idempotencyKey,
      UUID transferId,
      UUID lineId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {
    TransferRepairArrivalCompletionResponse response =
        post(
            maintenanceTransferLineBase(transferId, lineId) + "/complete-arrival",
            idempotencyKey,
            new CompleteTransferRepairArrivalRequest(
                rentalItemId,
                rentalItemVersion,
                sourceWarehouseId,
                targetWarehouseId,
                priority),
            TransferRepairArrivalCompletionResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    return transferRepairArrivalCompletion(response);
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
  public ReturnEstimateSource upsertReturnEstimateSource(
      UUID returnId,
      UUID lineId,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReference> mediaReferences) {
    ReturnEstimateSourceResponse response =
        putWithoutIdempotency(
            maintenanceBase + "/returns/" + returnId + "/lines/" + lineId + "/estimate-source",
            new UpsertReturnEstimateSourceRequest(
                warehouseId,
                rentalItemId,
                rentalItemVersion,
                dispatchDate,
                mediaReferences.stream()
                    .map(
                        reference ->
                            new MediaReferenceRequest(
                                reference.mediaId(), reference.generation()))
                    .toList()),
            ReturnEstimateSourceResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    if (response == null) {
      throw malformed("Maintenance-service returned an empty return estimate source");
    }
    return new ReturnEstimateSource(
        response.returnId(),
        response.lineId(),
        response.sourceVersion(),
        response.warehouseId(),
        response.rentalItemId(),
        response.rentalItemVersion(),
        response.estimateId(),
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
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose) {
    if (purpose == null) {
      throw malformed("Equipment movement reservation purpose is required");
    }
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
                reservedUntil,
                purpose),
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
    if (plannedDurationMinutes == null || plannedDurationMinutes < 1) {
      throw new IllegalArgumentException(
          "Equipment movement task requires a positive planned duration");
    }
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
                                operation.equipmentId(),
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
  public WarehouseDriverQueue readWarehouseDriverQueue(UUID warehouseId) {
    WarehouseQueueCapabilitiesResponse response = readWarehouseQueueCapabilities(warehouseId);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.movementQueueDefinitions() == null
        || response.movementQueueDefinitions().size() != 1) {
      throw malformed(
          "Task-board must expose exactly one active warehouse driver queue");
    }
    MovementQueueCapabilityResponse queue = response.movementQueueDefinitions().getFirst();
    if (queue.queueDefinitionId() == null || queue.workQueueId() == null) {
      throw malformed("Task-board returned an invalid warehouse driver queue");
    }
    return new WarehouseDriverQueue(
        warehouseId, queue.queueDefinitionId(), queue.workQueueId());
  }

  @Override
  public boolean isWarehouseDriverQueueAvailable(UUID warehouseId) {
    WarehouseQueueCapabilitiesResponse response = readWarehouseQueueCapabilities(warehouseId);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.movementQueueDefinitions() == null) {
      throw malformed("Task-board returned invalid warehouse queue capabilities");
    }
    int queueCount = response.movementQueueDefinitions().size();
    if (queueCount > 1) {
      throw malformed("Task-board exposes more than one active warehouse driver queue");
    }
    return queueCount == 1;
  }

  private WarehouseQueueCapabilitiesResponse readWarehouseQueueCapabilities(UUID warehouseId) {
    return get(
        taskBoardBase
            + "/api/internal/task-board/v1/warehouses/"
            + warehouseId
            + "/queue-capabilities",
        WarehouseQueueCapabilitiesResponse.class,
        TASK_BOARD_CLIENT,
        TASK_BOARD_SCOPE);
  }

  @Override
  public DriverBoardTask registerDriverTask(
      UUID warehouseId,
      UUID externalTaskId,
      UUID sourceId,
      String title,
      String unitNumber,
      String description,
      UUID queueDefinitionId,
      LocalDate scheduledDate,
      int priority) {
    DriverBoardTaskResponse response =
        postWithoutIdempotency(
            taskBoardTaskBase,
            new RegisterDriverTaskRequest(
                warehouseId,
                externalTaskId,
                title,
                unitNumber,
                description,
                null,
                null,
                List.of(new DriverRouteStepRequest(queueDefinitionId, description, null)),
                scheduledDate,
                priority,
                null,
                new DriverTaskSourceRequest("LOGISTICS_DRIVER_TASK", sourceId),
                "SCHEDULED"),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    return driverBoardTask(response);
  }

  @Override
  public DriverBoardTask readDriverTask(UUID externalTaskId) {
    return driverBoardTask(
        get(
            taskBoardTaskBase + "/" + externalTaskId,
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE));
  }

  @Override
  public DriverBoardTask cancelDriverTask(
      UUID externalTaskId, long expectedTaskVersion) {
    CancelDriverTaskResponse cancelled =
        postWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId + "/cancel",
            new CancelDriverTaskRequest(
                expectedTaskVersion,
                "Капитальный ремонт возвращён в отдельную очередь"),
            CancelDriverTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    if (cancelled == null
        || cancelled.externalTaskId() == null
        || !externalTaskId.equals(cancelled.externalTaskId())
        || cancelled.taskVersion() < expectedTaskVersion
        || !"CANCELLED".equals(cancelled.status())) {
      throw malformed("Task-board returned an invalid cancelled driver task");
    }
    return readDriverTask(externalTaskId);
  }

  @Override
  public DriverTaskPreStartCancellation cancelDriverTaskIfPreStart(
      UUID externalTaskId, long expectedTaskVersion, String reason) {
    if (externalTaskId == null
        || expectedTaskVersion < 0
        || reason == null
        || reason.isBlank()
        || reason.length() > 1_000) {
      throw new IllegalArgumentException("Invalid pre-start driver task cancellation command");
    }
    return preStartDriverTaskCancellation(
        postWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId + "/cancel-if-pre-start",
            new CancelDriverTaskRequest(expectedTaskVersion, reason),
            PreStartDriverTaskCancellationResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE),
        externalTaskId);
  }

  @Override
  public DriverBoardTask setDriverTaskLane(
      UUID externalTaskId, long expectedTaskVersion, String lane) {
    return driverBoardTask(
        postWithoutIdempotency(
            taskBoardTaskBase + "/" + externalTaskId + "/lane",
            new SetDriverTaskLaneRequest(expectedTaskVersion, lane),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE));
  }

  @Override
  public DriverBoardSnapshot readDriverBoard(UUID warehouseId) {
    DriverBoardSnapshotResponse response =
        get(
            taskBoardDriverBase + "/" + warehouseId + "/board",
            DriverBoardSnapshotResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.queueId() == null
        || response.queueVersion() < 0
        || response.current() == null
        || response.dates() == null) {
      throw malformed("Task-board returned an invalid driver board");
    }
    return new DriverBoardSnapshot(
        warehouseId,
        response.queueId(),
        response.queueVersion(),
        response.current().stream()
            .map(entry -> driverBoardEntry(entry, warehouseId))
            .toList(),
        response.dates().stream()
            .map(
                column -> {
                  if (column.date() == null || column.entries() == null) {
                    throw malformed("Task-board returned an invalid driver date column");
                  }
                  return new DriverBoardDateColumn(
                      column.date(),
                      column.entries().stream()
                          .map(entry -> driverBoardEntry(entry, warehouseId))
                          .toList());
                })
            .toList());
  }

  @Override
  public DriverBoardTask moveDriverTask(
      UUID externalTaskId,
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex) {
    return driverBoardTask(
        postWithoutIdempotency(
            taskBoardDriverTaskBase + "/" + externalTaskId + "/move",
            new MoveDriverTaskRequest(
                expectedTaskVersion,
                expectedEntryVersion,
                targetLane,
                targetDate,
                targetIndex),
            DriverBoardTaskResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE));
  }

  @Override
  public DriverCompletionEvidence readDriverCompletionEvidence(UUID externalTaskId) {
    DriverCompletionEvidenceResponse response =
        get(
            taskBoardTaskBase + "/" + externalTaskId + "/completion-evidence",
            DriverCompletionEvidenceResponse.class,
            TASK_BOARD_CLIENT,
            TASK_BOARD_SCOPE);
    if (response.externalTaskId() == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskId() == null
        || response.entryId() == null
        || response.evidenceId() == null
        || response.mediaId() == null
        || response.mediaGeneration() < 1
        || response.warehouseId() == null
        || response.recordedAt() == null) {
      throw malformed("Task-board returned invalid driver completion evidence");
    }
    return new DriverCompletionEvidence(
        response.externalTaskId(),
        response.taskId(),
        response.entryId(),
        response.evidenceId(),
        response.mediaId(),
        response.mediaGeneration(),
        response.warehouseId(),
        response.recordedAt());
  }

  @Override
  public CapitalRepairPage readCapitalRepairs(UUID warehouseId, int page, int size) {
    String uri =
        UriComponentsBuilder.fromUriString(maintenanceBase + "/repairs/capital")
            .queryParam("warehouseId", warehouseId)
            .queryParam("page", page)
            .queryParam("size", size)
            .build()
            .encode()
            .toUriString();
    CapitalRepairPageResponse response =
        get(uri, CapitalRepairPageResponse.class, MAINTENANCE_CLIENT, MAINTENANCE_SCOPE);
    if (response.items() == null
        || response.page() != page
        || response.size() != size
        || response.totalElements() < 0) {
      throw malformed("Maintenance-service returned invalid capital-repair page");
    }
    return new CapitalRepairPage(
        response.items().stream()
            .map(HttpLogisticsDependencyGateway::capitalRepair)
            .toList(),
        response.page(),
        response.size(),
        response.totalElements());
  }

  @Override
  public CapitalRepair readCapitalRepair(UUID repairId) {
    return capitalRepair(
        get(
            maintenanceBase + "/repairs/capital/" + repairId,
            CapitalRepairResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE));
  }

  @Override
  public RepairPlaceProjection readRepairPlaces(UUID warehouseId) {
    RepairPlaceProjectionResponse response =
        get(
            maintenanceBase + "/repair-places/" + warehouseId,
            RepairPlaceProjectionResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    if (response.warehouseId() == null
        || !warehouseId.equals(response.warehouseId())
        || response.repairPlaceCount() < 1
        || response.automaticRefillDelayMinutes() < 1
        || response.automaticRefillDelayMinutes() > 1_440
        || response.availableCount() < 0
        || response.allocations() == null) {
      throw malformed("Maintenance-service returned invalid repair-place projection");
    }
    return new RepairPlaceProjection(
        warehouseId,
        response.repairPlaceCount(),
        response.automaticRefillDelayMinutes(),
        response.reservedCount(),
        response.occupiedCount(),
        response.readyToReleaseCount(),
        response.availableCount(),
        response.overCapacity(),
        response.allocations().stream()
            .map(HttpLogisticsDependencyGateway::repairPlaceProjectionAllocation)
            .toList());
  }

  @Override
  public RepairPlaceAllocation transitionRepairPlace(
      UUID idempotencyKey,
      UUID warehouseId,
      UUID repairId,
      long expectedVersion,
      String transition) {
    if (!Set.of("reserve", "occupy", "release").contains(transition)) {
      throw new IllegalArgumentException("Unsupported repair-place transition");
    }
    RepairPlaceAllocationResponse response =
        post(
            maintenanceBase
                + "/repair-places/"
                + warehouseId
                + "/allocations/"
                + repairId
                + "/"
                + transition,
            idempotencyKey,
            new RepairPlaceTransitionRequest(expectedVersion),
            RepairPlaceAllocationResponse.class,
            MAINTENANCE_CLIENT,
            MAINTENANCE_SCOPE);
    RepairPlaceAllocation allocation = repairPlaceAllocation(response);
    if (!warehouseId.equals(allocation.warehouseId())
        || !repairId.equals(allocation.repairId())) {
      throw malformed("Maintenance-service returned a mismatched repair-place allocation");
    }
    return allocation;
  }

  @Override
  public CabinCoverChange setCabinCoverFromTaskEvidence(
      UUID idempotencyKey,
      UUID cabinId,
      UUID taskBoardEntryId,
      UUID evidenceMediaId) {
    CabinCoverChangeResponse response =
        post(
            mediaBase + "/logistics/cabins/" + cabinId + "/cover-from-task-evidence",
            idempotencyKey,
            new SetCabinCoverFromTaskEvidenceRequest(taskBoardEntryId, evidenceMediaId),
            CabinCoverChangeResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE);
    if (response.cabinId() == null
        || !cabinId.equals(response.cabinId())
        || response.warehouseId() == null
        || response.coverMediaId() == null
        || !evidenceMediaId.equals(response.coverMediaId())
        || response.generation() < 1
        || response.taskBoardEntryId() == null
        || !taskBoardEntryId.equals(response.taskBoardEntryId())
        || response.version() < 0
        || response.changedAt() == null) {
      throw malformed("Media-service returned a mismatched cabin cover");
    }
    return new CabinCoverChange(
        response.cabinId(),
        response.warehouseId(),
        response.coverMediaId(),
        response.generation(),
        response.taskBoardEntryId(),
        response.version(),
        response.changedAt());
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
      OffsetDateTime draftReservationExpiresAt,
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
                draftReservationExpiresAt,
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

  @Override
  public CabinFacets readAvailableCabinFacets(UUID warehouseId, UUID holdScopeId) {
    String uri =
        UriComponentsBuilder.fromUriString(assetBase + "/cabin-facets")
            .queryParam("warehouseId", warehouseId)
            .queryParam("holdScopeId", holdScopeId)
            .build()
            .encode()
            .toUriString();
    CabinFacetsResponse response =
        get(uri, CabinFacetsResponse.class, ASSET_CLIENT, ASSET_SCOPE);
    return new CabinFacets(
        response.warehouseId(),
        List.copyOf(response.cabinTypes()),
        List.copyOf(response.finishes()),
        List.copyOf(response.dimensions()),
        List.copyOf(response.categories()));
  }

  @Override
  public CabinSearchResult searchAvailableCabins(
      UUID warehouseId,
      UUID holdScopeId,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      List<CabinSearchGroup> groups) {
    CabinSearchResponse response =
        postWithoutIdempotency(
            assetBase + "/cabin-searches",
            new CabinSearchRequest(
                warehouseId,
                holdScopeId,
                expiresAt,
                actorSubjectId,
                actorRole,
                groups.stream()
                    .map(
                        group ->
                            new CabinSearchGroupRequest(
                                group.cabinType(),
                                group.finish(),
                                group.dimensions(),
                                group.category(),
                                group.characteristics(),
                                group.linoleum(),
                                group.quantity()))
                    .toList()),
            CabinSearchResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    if (response == null
        || response.warehouseId() == null
        || response.expiresAt() == null
        || response.groups() == null) {
      throw malformed("Asset-service returned an invalid cabin search result");
    }
    return new CabinSearchResult(
        response.warehouseId(),
        response.expiresAt(),
        response.groups().stream()
            .map(
                group ->
                    new CabinSearchGroupResult(
                        new CabinSearchGroup(
                            group.group().cabinType(),
                            group.group().finish(),
                            group.group().dimensions(),
                            group.group().category(),
                            group.group().characteristics(),
                            group.group().linoleum(),
                            group.group().quantity()),
                        group.cabins().stream()
                            .map(HttpLogisticsDependencyGateway::availableCabin)
                            .toList()))
            .toList());
  }

  @Override
  public List<AvailableCabin> readCabinSnapshots(
      UUID warehouseId, List<UUID> rentalItemIds) {
    CabinSnapshotsResponse response =
        postWithoutIdempotency(
            assetBase + "/cabin-snapshots",
            new CabinIdsRequest(warehouseId, rentalItemIds),
            CabinSnapshotsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return response.items().stream()
        .map(HttpLogisticsDependencyGateway::availableCabin)
        .toList();
  }

  @Override
  public CabinAvailability readCabinAvailability(
      UUID warehouseId, List<UUID> rentalItemIds) {
    CabinAvailabilityResponse response =
        postWithoutIdempotency(
            assetBase + "/cabin-availability",
            new CabinIdsRequest(warehouseId, rentalItemIds),
            CabinAvailabilityResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return new CabinAvailability(
        response.warehouseId(),
        response.items().stream()
            .map(
                item ->
                    new CabinAvailabilityItem(
                        item.rentalItemId(), item.available(), item.reason()))
            .toList());
  }

  @Override
  public PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole) {
    return replacePresentationHolds(
        idempotencyKey,
        presentationId,
        warehouseId,
        rentalItemIds,
        expiresAt,
        actorSubjectId,
        actorRole,
        null);
  }

  @Override
  public PresentationHolds replacePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {
    PresentationHoldsResponse response =
        put(
            assetBase + "/presentations/" + presentationId + "/holds",
            idempotencyKey,
            new ReplacePresentationHoldsRequest(
                warehouseId,
                rentalItemIds,
                expiresAt,
                actorSubjectId,
                actorRole,
                sourceHoldScopeId),
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return presentationHolds(response);
  }

  @Override
  public PresentationHolds readPresentationHolds(UUID presentationId) {
    return readPresentationHolds(presentationId, null, null);
  }

  @Override
  public PresentationHolds readPresentationHolds(
      UUID presentationId, UUID actorSubjectId, String actorRole) {
    String query =
        actorSubjectId == null
            ? ""
            : "?actorSubjectId=" + actorSubjectId + "&actorRole=" + actorRole;
    PresentationHoldsResponse response =
        get(
            assetBase + "/presentations/" + presentationId + "/holds" + query,
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return presentationHolds(response);
  }

  @Override
  public PresentationHolds releasePresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID actorSubjectId,
      String actorRole) {
    PresentationHoldsResponse response =
        post(
            assetBase + "/presentations/" + presentationId + "/holds/release",
            idempotencyKey,
            new OrderActorRequest(actorSubjectId, actorRole),
            PresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return presentationHolds(response);
  }

  @Override
  public ConvertedPresentationHolds convertPresentationHolds(
      UUID idempotencyKey,
      UUID presentationId,
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {
    ConvertedPresentationHoldsResponse response =
        post(
            assetBase + "/presentations/" + presentationId + "/holds/convert",
            idempotencyKey,
            new ConvertPresentationHoldsRequest(
                orderId,
                warehouseId,
                selectedRentalItemIds,
                clientId,
                tenantSnapshot,
                actorSubjectId,
                actorRole),
            ConvertedPresentationHoldsResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE);
    return new ConvertedPresentationHolds(
        response.presentationId(),
        response.orderId(),
        response.reservations().stream()
            .map(HttpLogisticsDependencyGateway::orderReservation)
            .toList(),
        List.copyOf(response.releasedRentalItemIds()));
  }

  @Override
  public List<CabinMediaSnapshot> readCabinMediaSnapshots(
      UUID warehouseId, List<UUID> cabinIds) {
    CabinMediaSnapshotsResponse response =
        postWithoutIdempotency(
            mediaBase + "/logistics/cabin-presentations/snapshots",
            new CabinMediaSnapshotsRequest(warehouseId, cabinIds),
            CabinMediaSnapshotsResponse.class,
            MEDIA_CLIENT,
            MEDIA_SCOPE);
    return response.items().stream()
        .map(
            item ->
                new CabinMediaSnapshot(
                    item.cabinId(),
                    item.photos().stream()
                        .map(
                            photo ->
                                new CabinMediaPhoto(
                                    photo.mediaId(),
                                    photo.generation(),
                                    photo.sortOrder(),
                                    List.copyOf(photo.availableVariants())))
                        .toList()))
        .toList();
  }

  @Override
  public MediaContent readCabinPresentationMedia(
      UUID warehouseId,
      UUID cabinId,
      UUID mediaId,
      long generation,
      String variant) {
    String uri =
        UriComponentsBuilder.fromUriString(
                mediaBase
                    + "/logistics/cabin-presentations/assets/"
                    + mediaId
                    + "/variants/"
                    + variant
                    + "/content")
            .queryParam("warehouseId", warehouseId)
            .queryParam("cabinId", cabinId)
            .queryParam("generation", generation)
            .build()
            .encode()
            .toUriString();
    try {
      ResponseEntity<byte[]> response =
          client
              .get()
              .uri(uri)
              .header(HttpHeaders.AUTHORIZATION, bearer(MEDIA_CLIENT, MEDIA_SCOPE))
              .retrieve()
              .toEntity(byte[].class);
      if (response.getBody() == null) {
        throw malformed("Media-service returned empty presentation content");
      }
      String contentType =
          response.getHeaders().getContentType() == null
              ? "application/octet-stream"
              : response.getHeaders().getContentType().toString();
      return new MediaContent(response.getBody(), contentType);
    } catch (RuntimeException exception) {
      throw dependencyFailure(exception);
    }
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
        response.number(),
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

  private static EquipmentMovementReservation movementReservation(
      EquipmentMovementReservationResponse response) {
    if (response == null
        || response.reservationId() == null
        || response.version() < 0
        || !"LOGISTICS_EQUIPMENT_MOVEMENT".equals(response.ownerType())
        || response.movementId() == null
        || response.lineId() == null
        || response.equipmentId() == null
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

  private static DriverBoardTask driverBoardTask(DriverBoardTaskResponse response) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.warehouseId() == null
        || response.externalTaskId() == null
        || response.status() == null
        || response.scheduledDate() == null
        || response.lane() == null
        || response.priority() < 1
        || response.priority() > 5
        || response.route() == null
        || response.route().size() != 1) {
      throw malformed("Task-board returned an invalid driver task");
    }
    DriverRouteStepResponse entry = response.route().getFirst();
    if (entry.entryId() == null
        || entry.entryVersion() < 0
        || entry.status() == null
        || entry.queuePosition() < 0) {
      throw malformed("Task-board returned an invalid driver task route");
    }
    return new DriverBoardTask(
        response.taskId(),
        response.taskVersion(),
        response.warehouseId(),
        response.externalTaskId(),
        response.title(),
        response.unitNumber(),
        entry.taskText(),
        response.status(),
        response.scheduledDate(),
        response.lane(),
        response.priority(),
        response.pinned(),
        response.doneAt(),
        entry.entryId(),
        entry.entryVersion(),
        entry.status(),
        entry.queuePosition());
  }

  private static DriverTaskPreStartCancellation preStartDriverTaskCancellation(
      PreStartDriverTaskCancellationResponse response, UUID externalTaskId) {
    if (response == null
        || response.outcome() == null
        || response.taskId() == null
        || response.externalTaskId() == null
        || !externalTaskId.equals(response.externalTaskId())
        || response.taskVersion() < 0
        || response.status() == null) {
      throw malformed("Task-board returned an invalid pre-start cancellation outcome");
    }
    DriverTaskPreStartCancellationOutcome outcome;
    try {
      outcome = DriverTaskPreStartCancellationOutcome.valueOf(response.outcome());
    } catch (IllegalArgumentException exception) {
      throw malformed("Task-board returned an unknown pre-start cancellation outcome");
    }
    boolean cancelled =
        outcome == DriverTaskPreStartCancellationOutcome.CANCELLED
            || outcome == DriverTaskPreStartCancellationOutcome.ALREADY_CANCELLED;
    if ((cancelled && (!"CANCELLED".equals(response.status()) || response.cancelledAt() == null))
        || (outcome == DriverTaskPreStartCancellationOutcome.STARTED
            && (!("ACTIVE".equals(response.status()) || "DONE".equals(response.status()))
                || response.cancelledAt() != null))
        || (outcome == DriverTaskPreStartCancellationOutcome.VERSION_CONFLICT
            && (!"ACTIVE".equals(response.status()) || response.cancelledAt() != null))) {
      throw malformed("Task-board returned an inconsistent pre-start cancellation outcome");
    }
    return new DriverTaskPreStartCancellation(
        outcome,
        response.taskId(),
        response.externalTaskId(),
        response.taskVersion(),
        response.status(),
        response.cancelledAt());
  }

  private static DriverBoardTask driverBoardEntry(
      DriverBoardEntryResponse response, UUID warehouseId) {
    if (response == null
        || response.taskId() == null
        || response.taskVersion() < 0
        || response.externalTaskId() == null
        || response.taskStatus() == null
        || response.scheduledDate() == null
        || response.lane() == null
        || response.priority() < 1
        || response.priority() > 5
        || response.id() == null
        || response.version() < 0
        || response.status() == null
        || response.queuePosition() < 0) {
      throw malformed("Task-board returned an invalid driver board entry");
    }
    return new DriverBoardTask(
        response.taskId(),
        response.taskVersion(),
        warehouseId,
        response.externalTaskId(),
        response.title(),
        response.unitNumber(),
        response.taskText(),
        response.taskStatus(),
        response.scheduledDate(),
        response.lane(),
        response.priority(),
        response.pinned(),
        response.doneAt(),
        response.id(),
        response.version(),
        response.status(),
        response.queuePosition());
  }

  private static RepairPlaceAllocation repairPlaceAllocation(
      RepairPlaceAllocationResponse response) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.warehouseId() == null
        || response.repairId() == null
        || response.rentalItemId() == null
        || response.state() == null
        || response.createdAt() == null
        || response.updatedAt() == null) {
      throw malformed("Maintenance-service returned invalid repair-place allocation");
    }
    return new RepairPlaceAllocation(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.repairId(),
        response.rentalItemId(),
        response.state(),
        null,
        null,
        3,
        response.createdAt(),
        response.updatedAt());
  }

  private static RepairPlaceAllocation repairPlaceProjectionAllocation(
      RepairPlaceProjectionAllocationResponse response) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.warehouseId() == null
        || response.repairId() == null
        || response.rentalItemId() == null
        || response.state() == null
        || (response.repairStageName() == null) != (response.repairStageState() == null)
        || (response.repairStageState() != null
            && !REPAIR_STAGE_STATES.contains(response.repairStageState()))
        || response.priority() < 1
        || response.priority() > 5
        || response.createdAt() == null
        || response.updatedAt() == null) {
      throw malformed("Maintenance-service returned invalid repair-place projection allocation");
    }
    return new RepairPlaceAllocation(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.repairId(),
        response.rentalItemId(),
        response.state(),
        response.repairStageName(),
        response.repairStageState(),
        response.priority(),
        response.createdAt(),
        response.updatedAt());
  }

  private static CapitalRepair capitalRepair(CapitalRepairResponse response) {
    if (response == null
        || response.repairId() == null
        || response.rentalItemId() == null
        || response.warehouseId() == null
        || response.priority() < 1
        || response.priority() > 5
        || response.complexity() == null
        || response.version() < 0) {
      throw malformed("Maintenance-service returned invalid capital repair");
    }
    RepairComplexitySnapshotResponse complexity = response.complexity();
    if (!"CAPITAL".equals(complexity.type())
        || complexity.name() == null
        || complexity.name().isBlank()
        || complexity.color() == null
        || !complexity.color().matches("^#[0-9A-F]{6}$")
        || complexity.plannedMinutes() == null
        || complexity.plannedMinutes().isBlank()) {
      throw malformed("Maintenance-service returned invalid capital complexity");
    }
    return new CapitalRepair(
        response.repairId(),
        response.rentalItemId(),
        response.warehouseId(),
        response.priority(),
        new RepairComplexitySnapshot(
            complexity.type(),
            complexity.name(),
            complexity.color(),
            complexity.plannedMinutes(),
            complexity.forcedCapital()),
        response.version());
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

  private static AvailableCabin availableCabin(AvailableCabinResponse response) {
    if (response == null
        || response.id() == null
        || response.version() < 0
        || response.warehouseId() == null
        || !isAvailableCabinStatus(response.status())
        || response.number() == null
        || response.passport() == null
        || response.tags() == null
        || response.updatedAt() == null) {
      throw malformed("Asset-service returned an invalid available cabin");
    }
    return new AvailableCabin(
        response.id(),
        response.version(),
        response.warehouseId(),
        response.status(),
        response.number(),
        response.rentalType(),
        response.dimensions(),
        response.finishing(),
        response.category(),
        response.characteristics(),
        response.linoleum(),
        Collections.unmodifiableMap(new LinkedHashMap<>(response.passport())),
        List.copyOf(response.tags()),
        response.updatedAt());
  }

  private static boolean isAvailableCabinStatus(String status) {
    return "FREE".equals(status);
  }

  private static PresentationHolds presentationHolds(
      PresentationHoldsResponse response) {
    if (response == null || response.presentationId() == null || response.holds() == null) {
      throw malformed("Asset-service returned invalid presentation holds");
    }
    return new PresentationHolds(
        response.presentationId(),
        response.expiresAt(),
        response.holds().stream()
            .map(
                hold ->
                    new PresentationHold(
                        hold.holdId(),
                        hold.version(),
                        hold.presentationId(),
                        hold.rentalItemId(),
                        hold.warehouseId(),
                        hold.state(),
                        hold.expiresAt(),
                        hold.orderId(),
                        hold.createdAt(),
                        hold.endedAt()))
            .toList());
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

  private String maintenanceTransferLineBase(UUID transferId, UUID lineId) {
    if (transferId == null || lineId == null) {
      throw malformed("Transfer repair context identifiers are required");
    }
    return maintenanceBase + "/transfers/" + transferId + "/lines/" + lineId;
  }

  private static TransferRepairDeparture transferRepairDeparture(
      TransferRepairDepartureResponse response) {
    if (response == null
        || (!"FREE".equals(response.assetStatus()) && !"REPAIR".equals(response.assetStatus()))
        || ("FREE".equals(response.assetStatus())
            && (response.activeRepairId() != null || response.activeRepairVersion() != null))
        || ("REPAIR".equals(response.assetStatus())
            && (response.activeRepairId() == null
                || response.activeRepairVersion() == null
                || response.activeRepairVersion() < 0))) {
      throw malformed("Maintenance-service returned invalid transfer departure truth");
    }
    return new TransferRepairDeparture(
        response.activeRepairId(), response.activeRepairVersion(), response.assetStatus());
  }

  private static TransferRepairArrivalPreflight transferRepairArrivalPreflight(
      TransferRepairArrivalPreflightResponse response) {
    if (response == null
        || response.missingQueueDefinitionIds() == null
        || response.missingQueueDefinitionIds().stream().anyMatch(java.util.Objects::isNull)
        || response.missingQueueDefinitionIds().size()
            != Set.copyOf(response.missingQueueDefinitionIds()).size()
        || (response.activeRepairId() == null
            && (response.priorityRequired()
                || !response.missingQueueDefinitionIds().isEmpty()))
        || (response.activeRepairId() != null && !response.priorityRequired())) {
      throw malformed("Maintenance-service returned invalid transfer arrival preflight");
    }
    return new TransferRepairArrivalPreflight(
        response.activeRepairId(),
        response.priorityRequired(),
        List.copyOf(response.missingQueueDefinitionIds()));
  }

  private static TransferRepairArrivalCompletion transferRepairArrivalCompletion(
      TransferRepairArrivalCompletionResponse response) {
    if (response == null
        || response.activeRepairId() == null
        || response.repairVersion() == null
        || response.repairVersion() < 0
        || response.warehouseId() == null) {
      throw malformed("Maintenance-service returned invalid transfer arrival completion");
    }
    return new TransferRepairArrivalCompletion(
        response.activeRepairId(), response.repairVersion(), response.warehouseId());
  }

  private static String strip(String value) {
    return value.endsWith("/") ? value.substring(0, value.length() - 1) : value;
  }

  private record WarehouseIdentityResponse(
      UUID id,
      long version,
      boolean active,
      String name,
      String city,
      String timeZone) {}

  private record WarehouseLifecycleReadinessRequest(long expectedVersion) {}

  private record WarehouseOperationMarkRequest(UUID operationId, OffsetDateTime occurredAt) {}

  private record EquipmentContentResponse(UUID equipmentId, long quantity) {}

  private record RentalItemSnapshotResponse(
      UUID assetId,
      long version,
      UUID warehouseId,
      String number,
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
      UUID destinationWarehouseId,
      String transferAssetStatus) {}

  private record TransferRepairContextRequest(
      UUID rentalItemId, UUID sourceWarehouseId, UUID targetWarehouseId) {}

  private record CompleteTransferRepairArrivalRequest(
      UUID rentalItemId,
      long rentalItemVersion,
      UUID sourceWarehouseId,
      UUID targetWarehouseId,
      Integer priority) {}

  private record TransferRepairDepartureResponse(
      UUID activeRepairId, Long activeRepairVersion, String assetStatus) {}

  private record TransferRepairArrivalPreflightResponse(
      UUID activeRepairId,
      boolean priorityRequired,
      List<UUID> missingQueueDefinitionIds) {}

  private record TransferRepairArrivalCompletionResponse(
      UUID activeRepairId, Long repairVersion, UUID warehouseId) {}

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

  private record UpsertReturnEstimateSourceRequest(
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      LocalDate dispatchDate,
      List<MediaReferenceRequest> mediaReferences) {}

  private record ReturnEstimateSourceResponse(
      UUID returnId,
      UUID lineId,
      long sourceVersion,
      UUID warehouseId,
      UUID rentalItemId,
      long rentalItemVersion,
      UUID estimateId,
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

  private record AcquireEquipmentMovementReservationRequest(
      UUID movementId,
      UUID lineId,
      UUID equipmentId,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      long expectedSourceBalanceVersion,
      long quantity,
      OffsetDateTime reservedUntil,
      EquipmentMovementPurpose purpose) {}

  private record ReleaseEquipmentMovementReservationRequest(
      long expectedReservationVersion, UUID movementId, UUID lineId) {}

  private record EquipmentMovementReservationResponse(
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
      String direction, UUID equipmentId, String equipmentName, long quantity) {}

  private record RegisterEquipmentMovementTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      String unitNumber,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<EquipmentMovementOperationRequest> operations) {}

  private record CancelEquipmentMovementTaskRequest(long expectedTaskVersion) {}

  private record CancelDriverTaskRequest(long expectedTaskVersion, String reason) {}

  private record CancelDriverTaskResponse(
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}

  private record PreStartDriverTaskCancellationResponse(
      String outcome,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      String status,
      OffsetDateTime cancelledAt) {}

  private record EquipmentMovementBoardTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String status,
      OffsetDateTime doneAt) {}

  private record MovementQueueCapabilityResponse(
      UUID queueDefinitionId, UUID workQueueId) {}

  private record WarehouseQueueCapabilitiesResponse(
      UUID warehouseId,
      List<MovementQueueCapabilityResponse> movementQueueDefinitions) {}

  private record DriverTaskSourceRequest(String type, UUID sourceId) {}

  private record DriverRouteStepRequest(
      UUID queueDefinitionId, String taskText, Integer plannedDurationMinutes) {}

  private record RegisterDriverTaskRequest(
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      List<DriverRouteStepRequest> route,
      LocalDate scheduledDate,
      Integer priority,
      Integer dailyCapacity,
      DriverTaskSourceRequest source,
      String lane) {}

  private record SetDriverTaskLaneRequest(long expectedTaskVersion, String lane) {}

  private record DriverRouteStepResponse(
      UUID entryId,
      long entryVersion,
      UUID queueDefinitionId,
      UUID workQueueId,
      String queueName,
      int routeIndex,
      int queuePosition,
      String entryType,
      String status,
      String taskText,
      Integer plannedDurationMinutes) {}

  private record DriverBoardTaskResponse(
      UUID taskId,
      long taskVersion,
      UUID warehouseId,
      UUID externalTaskId,
      String title,
      String unitNumber,
      String description,
      String status,
      Integer plannedDurationMinutes,
      OffsetDateTime deadlineAt,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      OffsetDateTime doneAt,
      List<DriverRouteStepResponse> route) {}

  private record DriverBoardEntryResponse(
      UUID id,
      long version,
      UUID taskId,
      UUID externalTaskId,
      long taskVersion,
      UUID warehouseId,
      String title,
      String unitNumber,
      String taskText,
      String taskStatus,
      LocalDate scheduledDate,
      String lane,
      int priority,
      boolean pinned,
      int queuePosition,
      String status,
      OffsetDateTime doneAt) {}

  private record DriverBoardDateColumnResponse(
      LocalDate date, List<DriverBoardEntryResponse> entries) {}

  private record DriverBoardSnapshotResponse(
      UUID warehouseId,
      UUID queueId,
      long queueVersion,
      List<DriverBoardEntryResponse> current,
      List<DriverBoardDateColumnResponse> dates) {}

  private record MoveDriverTaskRequest(
      long expectedTaskVersion,
      long expectedEntryVersion,
      String targetLane,
      LocalDate targetDate,
      int targetIndex) {}

  private record DriverCompletionEvidenceResponse(
      UUID externalTaskId,
      UUID taskId,
      UUID entryId,
      UUID evidenceId,
      UUID mediaId,
      long mediaGeneration,
      UUID warehouseId,
      OffsetDateTime recordedAt) {}

  private record RepairPlaceTransitionRequest(long expectedVersion) {}

  private record RepairPlaceAllocationResponse(
      UUID id,
      long version,
      UUID warehouseId,
      UUID repairId,
      UUID rentalItemId,
      String state,
      OffsetDateTime createdAt,
      OffsetDateTime updatedAt) {}

  private record RepairPlaceProjectionAllocationResponse(
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

  private record RepairPlaceProjectionResponse(
      UUID warehouseId,
      int repairPlaceCount,
      int automaticRefillDelayMinutes,
      long reservedCount,
      long occupiedCount,
      long readyToReleaseCount,
      long availableCount,
      boolean overCapacity,
      List<RepairPlaceProjectionAllocationResponse> allocations) {}

  private record RepairComplexitySnapshotResponse(
      String type,
      String name,
      String color,
      String plannedMinutes,
      boolean forcedCapital) {}

  private record CapitalRepairResponse(
      UUID repairId,
      UUID rentalItemId,
      UUID warehouseId,
      int priority,
      RepairComplexitySnapshotResponse complexity,
      long version) {}

  private record CapitalRepairPageResponse(
      List<CapitalRepairResponse> items, int page, int size, long totalElements) {}

  private record SetCabinCoverFromTaskEvidenceRequest(
      UUID taskBoardEntryId, UUID evidenceMediaId) {}

  private record CabinCoverChangeResponse(
      UUID cabinId,
      UUID warehouseId,
      UUID coverMediaId,
      long generation,
      UUID taskBoardEntryId,
      long version,
      OffsetDateTime changedAt) {}

  private record ReserveOrderUnitRequest(
      UUID warehouseId,
      UUID rentalItemId,
      UUID clientId,
      String tenantSnapshot,
      OffsetDateTime draftReservationExpiresAt,
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

  private record CabinFacetsResponse(
      UUID warehouseId,
      List<String> cabinTypes,
      List<String> finishes,
      List<String> dimensions,
      List<String> categories) {}

  private record CabinSearchGroupRequest(
      String cabinType,
      String finish,
      String dimensions,
      String category,
      String characteristics,
      Boolean linoleum,
      int quantity) {}

  private record CabinSearchRequest(
      UUID warehouseId,
      UUID holdScopeId,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      List<CabinSearchGroupRequest> groups) {}

  private record AvailableCabinResponse(
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

  private record CabinSearchGroupResponse(
      CabinSearchGroupRequest group, List<AvailableCabinResponse> cabins) {}

  private record CabinSearchResponse(
      UUID warehouseId,
      OffsetDateTime expiresAt,
      List<CabinSearchGroupResponse> groups) {}

  private record CabinIdsRequest(UUID warehouseId, List<UUID> rentalItemIds) {}

  private record CabinSnapshotsResponse(
      UUID warehouseId, List<AvailableCabinResponse> items) {}

  private record CabinAvailabilityItemResponse(
      UUID rentalItemId, boolean available, String reason) {}

  private record CabinAvailabilityResponse(
      UUID warehouseId, List<CabinAvailabilityItemResponse> items) {}

  private record ReplacePresentationHoldsRequest(
      UUID warehouseId,
      List<UUID> rentalItemIds,
      OffsetDateTime expiresAt,
      UUID actorSubjectId,
      String actorRole,
      UUID sourceHoldScopeId) {}

  private record PresentationHoldResponse(
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

  private record PresentationHoldsResponse(
      UUID presentationId,
      OffsetDateTime expiresAt,
      List<PresentationHoldResponse> holds) {}

  private record ConvertPresentationHoldsRequest(
      UUID orderId,
      UUID warehouseId,
      List<UUID> selectedRentalItemIds,
      UUID clientId,
      String tenantSnapshot,
      UUID actorSubjectId,
      String actorRole) {}

  private record ConvertedPresentationHoldsResponse(
      UUID presentationId,
      UUID orderId,
      List<OrderUnitReservationResponse> reservations,
      List<UUID> releasedRentalItemIds) {}

  private record CabinMediaSnapshotsRequest(
      UUID warehouseId, List<UUID> cabinIds) {}

  private record CabinMediaPhotoResponse(
      UUID mediaId,
      long generation,
      int sortOrder,
      List<String> availableVariants) {}

  private record CabinMediaSnapshotResponse(
      UUID cabinId, List<CabinMediaPhotoResponse> photos) {}

  private record CabinMediaSnapshotsResponse(
      List<CabinMediaSnapshotResponse> items) {}

}
