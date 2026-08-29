package dev.buhanzaz.rwms.logistics.integration;

import static dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.*;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.FailurePolicy.DEFAULT;
import static dev.buhanzaz.rwms.logistics.integration.LogisticsOAuthHttpTransport.malformed;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/**
 * Private asset-service client for logistics effects, leases, holds, equipment movements and
 * least-privilege rental-item snapshots.
 *
 * <p>This client owns the validation that fences logistics writes against the asset-service
 * responses. It has no order-reservation or presentation command orchestration.
 */
final class LogisticsAssetOperationsDependencyClient {
  private static final String ASSET_CLIENT = "logistics-asset";
  private static final String ASSET_SCOPE = "asset.logistics";

  private final LogisticsOAuthHttpTransport transport;
  private final String assetBase;

  LogisticsAssetOperationsDependencyClient(
      LogisticsOAuthHttpTransport transport, String assetBase) {
    this.transport = transport;
    this.assetBase = assetBase;
  }

  RentalItemSnapshot readRentalItemSnapshot(UUID assetId) {
    RentalItemSnapshotResponse response =
        transport.get(
            assetBase + "/rental-items/" + assetId + "/snapshot",
            RentalItemSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return snapshot(response);
  }

  /** Reads the dedicated asset-owned input for one immutable public photo presentation. */
  CabinPhotoPresentationAssetSnapshot readCabinPhotoPresentationSnapshot(UUID assetId) {
    CabinPhotoPresentationSnapshotResponse response =
        transport.get(
            assetBase + "/rental-items/" + assetId + "/photo-presentation-snapshot",
            CabinPhotoPresentationSnapshotResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null
        || response.assetId() == null
        || response.version() < 0
        || response.warehouseId() == null
        || response.number() == null
        || response.number().isBlank()
        || response.characteristics() == null
        || response.characteristics().stream()
            .anyMatch(value -> value == null || value.isBlank())) {
      throw malformed("Asset-service returned an invalid photo-presentation snapshot");
    }
    return new CabinPhotoPresentationAssetSnapshot(
        response.assetId(),
        response.version(),
        response.warehouseId(),
        response.number(),
        response.dimensions(),
        response.finishing(),
        response.category(),
        List.copyOf(response.characteristics()),
        response.linoleum());
  }

  OperationLease acquireReturnLease(
      UUID idempotencyKey, UUID assetId, long expectedAssetVersion, UUID documentId, UUID lineId) {
    return acquireReturnLease(
        idempotencyKey, assetId, expectedAssetVersion, documentId, lineId, null);
  }

  OperationLease acquireReturnLease(
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

  OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId) {
    return acquireOperationLease(
        idempotencyKey, ownerType, assetId, expectedAssetVersion, documentId, lineId, null);
  }

  OperationLease acquireOperationLease(
      UUID idempotencyKey,
      LogisticsOwnerType ownerType,
      UUID assetId,
      long expectedAssetVersion,
      UUID documentId,
      UUID lineId,
      UUID rentalOrderId) {
    if (ownerType == null) throw malformed("Logistics operation-lease owner type is required");
    OperationLeaseResponse response =
        transport.post(
            assetBase + "/operation-leases",
            idempotencyKey,
            new AcquireLeaseRequest(
                assetId, ownerType.name(), documentId, lineId, expectedAssetVersion, rentalOrderId),
            OperationLeaseResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null) throw malformed("Asset-service returned an empty operation lease");
    return new OperationLease(
        response.leaseId(),
        response.version(),
        response.rentalItemId(),
        response.fencingToken(),
        response.state(),
        response.expiresAt());
  }

  RentalItemSnapshot applyReturnIntake(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId) {
    RentalItemSnapshotResponse response =
        transport.put(
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
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return snapshot(response);
  }

  RentalItemSnapshot settleReturn(
      UUID idempotencyKey,
      UUID assetId,
      long expectedAssetVersion,
      UUID leaseId,
      long fencingToken,
      UUID documentId,
      UUID lineId,
      boolean estimate) {
    RentalItemSnapshotResponse response =
        transport.put(
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
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return snapshot(response);
  }

  ReturnEquipmentReceipt receiveReturnEquipment(
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
        transport.post(
            assetBase + "/return-equipment-receipts",
            idempotencyKey,
            new ReturnEquipmentReceiptRequest(
                returnId,
                returnLineId,
                warehouseId,
                lines.stream()
                    .map(
                        line ->
                            new ReturnEquipmentReceiptLineRequest(
                                line.equipmentId(), line.quantity()))
                    .toList()),
            ReturnEquipmentReceiptResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
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
      UUID destinationWarehouseId,
      String transferAssetStatus) {
    if (action == null || ownerType == null) {
      throw malformed("Logistics asset effect action and owner type are required");
    }
    RentalItemSnapshotResponse response =
        transport.put(
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
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return snapshot(response);
  }

  OperationLease releaseOperationLease(
      UUID idempotencyKey,
      UUID leaseId,
      long expectedLeaseVersion,
      long fencingToken,
      LogisticsOwnerType ownerType,
      UUID documentId,
      UUID lineId) {
    OperationLeaseResponse response =
        transport.put(
            assetBase + "/operation-leases/" + leaseId + "/release",
            idempotencyKey,
            new LeaseCommandRequest(
                expectedLeaseVersion, fencingToken, ownerType.name(), documentId, lineId),
            OperationLeaseResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    if (response == null)
      throw malformed("Asset-service returned an empty released operation lease");
    return new OperationLease(
        response.leaseId(),
        response.version(),
        response.rentalItemId(),
        response.fencingToken(),
        response.state(),
        response.expiresAt());
  }

  EquipmentHold acquireEquipmentHold(
      UUID idempotencyKey,
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {
    EquipmentHoldResponse response =
        transport.post(
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
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return hold(response);
  }

  EquipmentHold commandEquipmentHold(
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
        transport.put(
            assetBase + "/equipment-holds/" + holdId + "/" + segment,
            idempotencyKey,
            new EquipmentHoldCommandRequest(expectedHoldVersion, shipmentId, shipmentLineId),
            EquipmentHoldResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return hold(response);
  }

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
      EquipmentMovementPurpose purpose) {
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
        null,
        null,
        null);
  }

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
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> units) {
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
        units,
        null);
  }

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
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {
    if (purpose == null) {
      throw malformed("Equipment movement reservation purpose is required");
    }
    EquipmentMovementReservationResponse response =
        transport.post(
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
                purpose,
                orderId,
                targetRentalItemId,
                units,
                replacementSourceReservationId),
            EquipmentMovementReservationResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return movementReservation(response);
  }

  EquipmentMovementReservation releaseEquipmentMovementReservation(
      UUID idempotencyKey,
      UUID reservationId,
      long expectedReservationVersion,
      UUID movementId,
      UUID lineId) {
    EquipmentMovementReservationResponse response =
        transport.put(
            assetBase + "/equipment-movement-reservations/" + reservationId + "/release",
            idempotencyKey,
            new ReleaseEquipmentMovementReservationRequest(
                expectedReservationVersion, movementId, lineId),
            EquipmentMovementReservationResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return movementReservation(response);
  }

  EquipmentMovementExecution executeEquipmentMovement(
      UUID idempotencyKey, UUID movementId, List<EquipmentMovementExecutionRequestLine> lines) {
    EquipmentMovementExecutionResponse response =
        transport.post(
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
            ASSET_SCOPE,
            "Dependency returned an empty response",
            DEFAULT);
    return movementExecution(response);
  }

  /** Calls the asset-owned all-or-nothing selected-cabin reservation boundary. */
  TransferUnitReservationReceipt reserveTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      UUID sourceWarehouseId,
      List<TransferUnitReservationRequestLine> lines) {
    TransferUnitReservationReceiptResponse response =
        transport.post(
            assetBase + "/transfer-unit-reservations",
            idempotencyKey,
            new ConfirmTransferUnitReservationsRequest(
                transferId,
                sourceWarehouseId,
                lines.stream()
                    .map(
                        line ->
                            new ConfirmTransferUnitReservationLine(
                                line.lineId(),
                                line.rentalItemId(),
                                line.expectedRentalItemVersion(),
                                line.rentalTypeId(),
                                line.dimensionId(),
                                line.finishingId(),
                                line.characteristicIds(),
                                line.linoleum()))
                    .toList()),
            TransferUnitReservationReceiptResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty transfer reservation receipt",
            DEFAULT);
    return transferReservationReceipt(response, transferId, lines, "ACTIVE");
  }

  /** Releases one exact active selected-cabin reservation batch before departure. */
  TransferUnitReservationReceipt releaseTransferUnits(
      UUID idempotencyKey,
      UUID transferId,
      List<TransferUnitReservationReleaseLine> lines) {
    TransferUnitReservationReceiptResponse response =
        transport.put(
            assetBase + "/transfer-unit-reservations/release",
            idempotencyKey,
            new ReleaseTransferUnitReservationsRequest(
                transferId,
                lines.stream()
                    .map(
                        line ->
                            new ReleaseTransferUnitReservationLine(
                                line.reservationId(),
                                line.lineId(),
                                line.rentalItemId(),
                                line.expectedReservationVersion()))
                    .toList()),
            TransferUnitReservationReceiptResponse.class,
            ASSET_CLIENT,
            ASSET_SCOPE,
            "Asset-service returned an empty transfer reservation release receipt",
            DEFAULT);
    return transferReleaseReceipt(response, transferId, lines);
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
        response.holdId(),
        response.version(),
        response.state(),
        response.expiresAt(),
        response.committedAt());
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
                    throw malformed(
                        "Asset-service returned an invalid equipment movement execution line");
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

  private static TransferUnitReservationReceipt transferReservationReceipt(
      TransferUnitReservationReceiptResponse response,
      UUID transferId,
      List<TransferUnitReservationRequestLine> requested,
      String expectedState) {
    if (response == null
        || response.transferId() == null
        || !response.transferId().equals(transferId)
        || response.lines() == null
        || response.lines().size() != requested.size()) {
      throw malformed("Asset-service returned an incomplete transfer reservation receipt");
    }
    java.util.Map<UUID, TransferUnitReservationRequestLine> expected =
        requested.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    TransferUnitReservationRequestLine::lineId, value -> value));
    List<TransferUnitReservationLineReceipt> receipts =
        response.lines().stream()
            .map(
                line -> {
                  TransferUnitReservationRequestLine request =
                      line == null ? null : expected.get(line.lineId());
                  if (request == null
                      || line.reservationId() == null
                      || line.version() < 0
                      || !request.rentalItemId().equals(line.rentalItemId())
                      || line.currentRentalItemVersion()
                          != Math.addExact(request.expectedRentalItemVersion(), 1)
                      || !expectedState.equals(line.state())) {
                    throw malformed(
                        "Asset-service returned a mismatched transfer reservation receipt");
                  }
                  return new TransferUnitReservationLineReceipt(
                      line.reservationId(),
                      line.version(),
                      line.lineId(),
                      line.rentalItemId(),
                      line.currentRentalItemVersion(),
                      line.state());
                })
            .toList();
    if (receipts.stream().map(TransferUnitReservationLineReceipt::lineId).distinct().count()
        != receipts.size()) {
      throw malformed("Asset-service returned duplicate transfer reservation lines");
    }
    return new TransferUnitReservationReceipt(transferId, receipts);
  }

  private static TransferUnitReservationReceipt transferReleaseReceipt(
      TransferUnitReservationReceiptResponse response,
      UUID transferId,
      List<TransferUnitReservationReleaseLine> requested) {
    if (response == null
        || response.transferId() == null
        || !response.transferId().equals(transferId)
        || response.lines() == null
        || response.lines().size() != requested.size()) {
      throw malformed("Asset-service returned an incomplete transfer reservation release receipt");
    }
    java.util.Map<UUID, TransferUnitReservationReleaseLine> expected =
        requested.stream()
            .collect(
                java.util.stream.Collectors.toMap(
                    TransferUnitReservationReleaseLine::lineId, value -> value));
    List<TransferUnitReservationLineReceipt> receipts =
        response.lines().stream()
            .map(
                line -> {
                  TransferUnitReservationReleaseLine request =
                      line == null ? null : expected.get(line.lineId());
                  if (request == null
                      || !request.reservationId().equals(line.reservationId())
                      || !request.rentalItemId().equals(line.rentalItemId())
                      || line.version() != Math.addExact(request.expectedReservationVersion(), 1)
                      || line.currentRentalItemVersion() < 0
                      || !"RELEASED".equals(line.state())) {
                    throw malformed(
                        "Asset-service returned a mismatched transfer reservation release receipt");
                  }
                  return new TransferUnitReservationLineReceipt(
                      line.reservationId(),
                      line.version(),
                      line.lineId(),
                      line.rentalItemId(),
                      line.currentRentalItemVersion(),
                      line.state());
                })
            .toList();
    return new TransferUnitReservationReceipt(transferId, receipts);
  }

  private static boolean sameReturnEquipmentReceiptLines(
      List<ReturnEquipmentReceiptLine> requested, List<ReturnEquipmentReceiptLine> received) {
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

  /** Asset-service wire line describing equipment currently attached to a rental item. */
  private record EquipmentContentResponse(UUID equipmentId, long quantity) {}

  /**
   * Versioned asset-service snapshot used to validate logistics effects against current cabin
   * status and equipment contents.
   */
  private record RentalItemSnapshotResponse(
      UUID assetId,
      long version,
      UUID warehouseId,
      String number,
      String status,
      List<EquipmentContentResponse> contents) {}

  /** Wire-only response for the dedicated asset photo-presentation snapshot endpoint. */
  private record CabinPhotoPresentationSnapshotResponse(
      UUID assetId,
      long version,
      UUID warehouseId,
      String number,
      String dimensions,
      String finishing,
      String category,
      List<String> characteristics,
      Boolean linoleum) {}

  /**
   * Lease-acquisition command that binds one logistics document line to an expected rental-item
   * version before any remote effect is attempted.
   */
  private record AcquireLeaseRequest(
      UUID rentalItemId,
      String ownerType,
      UUID documentId,
      UUID lineId,
      long expectedRentalItemVersion,
      UUID rentalOrderId) {}

  /** Asset-service lease grant carrying the fencing token and expiry required by later effects. */
  private record OperationLeaseResponse(
      UUID leaseId,
      long version,
      UUID rentalItemId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  /**
   * Fenced asset-effect command carrying optimistic version, lease identity, and owning logistics
   * document correlation.
   */
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

  /** Declared equipment quantity being returned from one cabin into warehouse stock. */
  private record ReturnEquipmentReceiptLineRequest(UUID equipmentId, long quantity) {}

  /**
   * Return-receipt command binding the equipment lines to their return document line and receiving
   * warehouse.
   */
  private record ReturnEquipmentReceiptRequest(
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLineRequest> lines) {}

  /**
   * Authoritative stock-balance result for one received equipment line, including the post-write
   * balance version and quantity.
   */
  private record ReturnEquipmentReceiptLineResponse(
      UUID receiptId,
      UUID equipmentId,
      long quantity,
      UUID stockBalanceId,
      long stockBalanceVersion,
      long stockQuantity) {}

  /**
   * Asset-service receipt result echoed with return correlations so replay and mismatch validation
   * remain possible at the boundary.
   */
  private record ReturnEquipmentReceiptResponse(
      UUID returnId,
      UUID returnLineId,
      UUID warehouseId,
      List<ReturnEquipmentReceiptLineResponse> lines) {}

  /**
   * Versioned lease command used to release or otherwise conclude a fenced operation without losing
   * its owner-document identity.
   */
  private record LeaseCommandRequest(
      long expectedVersion, long fencingToken, String ownerType, UUID documentId, UUID lineId) {}

  /**
   * Shipment equipment-hold command that reserves stock against its expected balance version and
   * owning shipment line.
   */
  private record AcquireEquipmentHoldRequest(
      UUID equipmentId,
      UUID warehouseId,
      UUID shipmentId,
      UUID shipmentLineId,
      long quantity,
      long expectedStockVersion) {}

  /** Versioned commit or release command for a previously acquired shipment equipment hold. */
  private record EquipmentHoldCommandRequest(
      long expectedVersion, UUID shipmentId, UUID shipmentLineId) {}

  /** Authoritative hold lifecycle snapshot returned after acquire, commit, or release. */
  private record EquipmentHoldResponse(
      UUID holdId,
      long version,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt) {}

  /**
   * Source-stock reservation command for one equipment movement line, fenced by the source balance
   * version and an explicit expiry.
   */
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
      EquipmentMovementPurpose purpose,
      UUID orderId,
      UUID targetRentalItemId,
      List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> units,
      UUID replacementSourceReservationId) {}

  /**
   * Compensating release command fenced by reservation version and correlated to the original
   * movement line.
   */
  private record ReleaseEquipmentMovementReservationRequest(
      long expectedReservationVersion, UUID movementId, UUID lineId) {}

  /**
   * Asset-service reservation truth used by logistics to track source location, quantity, version,
   * expiry, and execution state.
   */
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

  /**
   * Per-line execution command that fences a reservation and declares its destination stock
   * location.
   */
  private record ExecuteEquipmentMovementLineRequest(
      UUID reservationId,
      long expectedReservationVersion,
      UUID lineId,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind) {}

  /** Atomic asset-service execution request for all prepared lines of one logistics movement. */
  private record ExecuteEquipmentMovementRequest(
      UUID movementId, List<ExecuteEquipmentMovementLineRequest> lines) {}

  /** Authoritative stock-movement event returned after a reservation is executed. */
  private record EquipmentMovementEventResponse(
      UUID id,
      long version,
      UUID equipmentId,
      UUID sourceBalanceId,
      UUID targetBalanceId,
      long quantity,
      String kind,
      OffsetDateTime occurredAt) {}

  /** Execution result linking a logistics line and reservation version to its stock event. */
  private record EquipmentMovementExecutionLineResponse(
      UUID reservationId,
      long reservationVersion,
      UUID lineId,
      EquipmentMovementEventResponse movement) {}

  /** Batch execution result correlated to the originating logistics movement. */
  private record EquipmentMovementExecutionResponse(
      UUID movementId, List<EquipmentMovementExecutionLineResponse> lines) {}

  /** Wire request for one exact cabin and its catalog-driven transfer requirement. */
  private record ConfirmTransferUnitReservationLine(
      UUID lineId,
      UUID rentalItemId,
      long expectedRentalItemVersion,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      List<UUID> characteristicIds,
      Boolean linoleum) {}

  /** Asset-service all-or-nothing transfer cabin reservation command. */
  private record ConfirmTransferUnitReservationsRequest(
      UUID transferId,
      UUID sourceWarehouseId,
      List<ConfirmTransferUnitReservationLine> lines) {}

  /** Wire request for releasing one exact active transfer cabin reservation. */
  private record ReleaseTransferUnitReservationLine(
      UUID reservationId,
      UUID lineId,
      UUID rentalItemId,
      long expectedReservationVersion) {}

  /** Asset-service all-or-nothing transfer cabin release command. */
  private record ReleaseTransferUnitReservationsRequest(
      UUID transferId, List<ReleaseTransferUnitReservationLine> lines) {}

  /** One authoritative cabin reservation receipt returned by asset-service. */
  private record TransferUnitReservationLineReceiptResponse(
      UUID reservationId,
      long version,
      UUID lineId,
      UUID rentalItemId,
      long currentRentalItemVersion,
      String state) {}

  /** Deterministic batch receipt correlated to the existing logistics transfer. */
  private record TransferUnitReservationReceiptResponse(
      UUID transferId, List<TransferUnitReservationLineReceiptResponse> lines) {}
}
