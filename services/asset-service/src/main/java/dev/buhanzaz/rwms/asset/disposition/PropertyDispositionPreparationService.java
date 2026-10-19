package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.CommandResult;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionFence;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionLeaseProof;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PrepareMaintenancePropertyContentRequest;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PrepareMaintenancePropertyDispositionRequest;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionContentsMode;

import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import dev.buhanzaz.rwms.asset.service.MaintenanceFurnitureCustodyService;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns PREPARE normalization, permanent replay fencing, and the durable cabin, stock, and
 * furniture-custody preparation paths.
 *
 * <p>The warehouse registry call remains outside the original default local transaction. All
 * fence, hold, version, and lease operations remain inside the same transaction template that
 * previously enclosed the application-service operation.
 */
@Service
final class PropertyDispositionPreparationService {
  private final TransactionTemplate transactions;
  private final WarehouseRegistryClient warehouses;
  private final RentalItemRepository rentalItems;
  private final PropertyDispositionDecisionStore decisions;
  private final PropertyDispositionLedgerService ledger;
  private final PropertyDispositionEligibilityService eligibility;
  private final PropertyDispositionCodec codec;
  private final MaintenanceFurnitureCustodyService furnitureCustody;

  PropertyDispositionPreparationService(
      PlatformTransactionManager transactionManager,
      WarehouseRegistryClient warehouses,
      RentalItemRepository rentalItems,
      PropertyDispositionDecisionStore decisions,
      PropertyDispositionLedgerService ledger,
      PropertyDispositionEligibilityService eligibility,
      PropertyDispositionCodec codec,
      MaintenanceFurnitureCustodyService furnitureCustody) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.warehouses = warehouses;
    this.rentalItems = rentalItems;
    this.decisions = decisions;
    this.ledger = ledger;
    this.eligibility = eligibility;
    this.codec = codec;
    this.furnitureCustody = furnitureCustody;
  }

  /**
   * Persists an immutable PREPARED decision fence, or returns its permanent replay before the
   * warehouse registry is contacted.
   */
  CommandResult<MaintenancePropertyDispositionFence> prepare(
      UUID maintenanceSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      PrepareMaintenancePropertyDispositionRequest request) {
    eligibility.requireCommandIdentity(maintenanceSubjectId, decisionId, transportIdempotencyKey);
    NormalizedPrepare normalized = normalize(request);
    String fingerprint = prepareFingerprint(decisionId, normalized);
    CommandResult<MaintenancePropertyDispositionFence> replay =
        existingPreparationReplay(decisionId, fingerprint);
    if (replay != null) {
      return replay;
    }
    // The remote lookup is intentionally outside the local asset transaction. A permanent replay
    // above must remain available even if the warehouse has subsequently been deactivated.
    warehouses.requireOutgoing(normalized.warehouseId());
    return required(
        transactions.execute(
            ignored ->
                prepareInTransaction(
                    maintenanceSubjectId, decisionId, transportIdempotencyKey, normalized)));
  }

  private CommandResult<MaintenancePropertyDispositionFence> prepareInTransaction(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      NormalizedPrepare request) {
    decisions.lockDecision(decisionId);
    String fingerprint = prepareFingerprint(decisionId, request);
    Optional<PropertyDispositionFence> existing = decisions.findForDecisionUpdate(decisionId);
    if (existing.isPresent()) {
      if (!existing.get().getRequestSha256().equals(fingerprint)) {
        throw new AssetConflictException("Property disposition decision is already bound to another request");
      }
      return new CommandResult<>(decisions.fenceResponse(existing.get()), true);
    }

    return request.assetKind() == PropertyAssetKind.CABIN
        ? prepareCabin(actorSubjectId, decisionId, transportIdempotencyKey, fingerprint, request)
        : prepareEquipment(actorSubjectId, decisionId, transportIdempotencyKey, fingerprint, request);
  }

  private CommandResult<MaintenancePropertyDispositionFence> existingPreparationReplay(
      UUID decisionId, String fingerprint) {
    return transactions.execute(
        ignored -> {
          decisions.lockDecision(decisionId);
          Optional<PropertyDispositionFence> existing = decisions.findForDecisionUpdate(decisionId);
          if (existing.isEmpty()) {
            return null;
          }
          if (!existing.get().getRequestSha256().equals(fingerprint)) {
            throw new AssetConflictException(
                "Property disposition decision is already bound to another request");
          }
          return new CommandResult<>(decisions.fenceResponse(existing.get()), true);
        });
  }

  private CommandResult<MaintenancePropertyDispositionFence> prepareCabin(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      String fingerprint,
      NormalizedPrepare request) {
    ledger.lockCabinAndLease(request.assetId());
    var cabin = rentalItems
        .findByIdForUpdate(request.assetId())
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!request.warehouseId().equals(cabin.getWarehouseId())) {
      throw new AssetConflictException("Cabin does not belong to the requested warehouse");
    }
    eligibility.assertVersion(cabin.getVersion(), request.expectedAssetVersion(), "Rental item changed concurrently");
    eligibility.assertCabinStatusAllowsDisposition(cabin);
    eligibility.assertNoActiveCabinReservation(cabin.getId());
    eligibility.assertLeaseProof(cabin, request.authorizedMaintenanceLease());
    decisions.assertNoPreparedFence(PropertyAssetKind.CABIN, cabin.getId(), request.warehouseId());

    List<PropertyDispositionLedgerService.ContentBalanceRow> actual =
        ledger.cabinContentsForUpdate(cabin.getId(), request.warehouseId());
    ledger.lockCabinContents(actual);
    actual = ledger.cabinContentsForUpdate(cabin.getId(), request.warehouseId());
    ledger.assertNoForeignActiveHolds(actual.stream().map(value -> value.id()).toList(), decisionId);
    assertCabinContentPlan(actual, request);

    OffsetDateTime preparedAt = ledger.databaseNow();
    List<HeldContent> held = new ArrayList<>();
    for (PropertyDispositionLedgerService.ContentBalanceRow line : actual) {
      UUID holdId = ledger.createCommittedDispositionHold(
          decisionId, line.equipmentId(), line.warehouseId(), line.id(), line.quantity());
      held.add(new HeldContent(line, holdId));
    }

    PropertyDispositionFence fence = PropertyDispositionFence.prepare(
        decisionId,
        fingerprint,
        request.warehouseId(),
        PropertyAssetKind.CABIN,
        cabin.getId(),
        request.disposition(),
        request.expectedAssetVersion(),
        null,
        null,
        null,
        null,
        null,
        request.contentsMode(),
        leaseId(request.authorizedMaintenanceLease()),
        fencingToken(request.authorizedMaintenanceLease()),
        leaseOwnerType(request.authorizedMaintenanceLease()),
        leaseOwnerId(request.authorizedMaintenanceLease()),
        null,
        preparedAt);
    decisions.savePreparedFence(fence);

    Map<UUID, NormalizedContent> byEquipment = request.contentsByEquipment();
    List<PropertyDispositionFenceContent> lines = held.stream()
        .map(
            heldLine -> {
              NormalizedContent requested = byEquipment.get(heldLine.line().equipmentId());
              return PropertyDispositionFenceContent.create(
                  decisionId,
                  heldLine.line().equipmentId(),
                  heldLine.line().id(),
                  heldLine.line().version(),
                  heldLine.line().quantity(),
                  requested.moveQuantity(),
                  heldLine.holdId());
            })
        .toList();
    decisions.savePreparedContents(lines);
    decisions.writeAudit(
        decisionId,
        "PREPARED",
        actorSubjectId,
        fingerprint,
        Map.of(
            "decisionId", decisionId.toString(),
            "assetKind", PropertyAssetKind.CABIN.name(),
            "assetId", cabin.getId().toString(),
            "warehouseId", request.warehouseId().toString(),
            "disposition", request.disposition().name(),
            "transportIdempotencyKey", transportIdempotencyKey.toString(),
            "contents", preparedContentFacts(lines)));
    return new CommandResult<>(decisions.fenceResponse(fence), false);
  }

  private CommandResult<MaintenancePropertyDispositionFence> prepareEquipment(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      String fingerprint,
      NormalizedPrepare request) {
    if (request.maintenanceCustodyClaimId() != null) {
      return prepareCustodyEquipment(
          actorSubjectId, decisionId, transportIdempotencyKey, fingerprint, request);
    }
    ledger.lockStockBalance(request.assetId(), request.warehouseId());
    PropertyDispositionLedgerService.StockEquipmentRow stock =
        ledger.stockEquipmentForUpdate(request.assetId(), request.warehouseId());
    eligibility.assertVersion(
        stock.balanceVersion(),
        request.expectedSourceBalanceVersion(),
        "Equipment stock balance changed concurrently");
    if (stock.quantity() < request.quantity()) {
      throw new AssetConflictException("Property disposition exceeds current stock quantity");
    }
    eligibility.assertNoActiveEquipmentReservation(stock.equipmentId(), stock.warehouseId());
    ledger.assertNoForeignActiveHolds(List.of(stock.balanceId()), decisionId);
    decisions.assertNoPreparedFence(PropertyAssetKind.EQUIPMENT, stock.equipmentId(), stock.warehouseId());

    UUID holdId = ledger.createCommittedDispositionHold(
        decisionId, stock.equipmentId(), stock.warehouseId(), stock.balanceId(), request.quantity());
    OffsetDateTime preparedAt = ledger.databaseNow();
    PropertyDispositionFence fence = PropertyDispositionFence.prepare(
        decisionId,
        fingerprint,
        request.warehouseId(),
        PropertyAssetKind.EQUIPMENT,
        stock.equipmentId(),
        request.disposition(),
        null,
        stock.balanceId(),
        request.expectedSourceBalanceVersion(),
        request.quantity(),
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        holdId,
        preparedAt);
    decisions.savePreparedFence(fence);
    decisions.writeAudit(
        decisionId,
        "PREPARED",
        actorSubjectId,
        fingerprint,
        Map.of(
            "decisionId", decisionId.toString(),
            "assetKind", PropertyAssetKind.EQUIPMENT.name(),
            "assetId", stock.equipmentId().toString(),
            "warehouseId", stock.warehouseId().toString(),
            "sourceBalanceId", stock.balanceId().toString(),
            "quantity", request.quantity(),
            "disposition", request.disposition().name(),
            "transportIdempotencyKey", transportIdempotencyKey.toString()));
    return new CommandResult<>(decisions.fenceResponse(fence), false);
  }

  private CommandResult<MaintenancePropertyDispositionFence> prepareCustodyEquipment(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      String fingerprint,
      NormalizedPrepare request) {
    MaintenanceFurnitureCustodyService.CustodyPreparation custody =
        furnitureCustody.prepareDisposition(
            actorSubjectId,
            decisionId,
            request.maintenanceCustodyClaimId(),
            request.maintenanceCustodyVersion(),
            request.warehouseId(),
            request.assetId(),
            request.quantity(),
            fingerprint);
    OffsetDateTime preparedAt = ledger.databaseNow();
    PropertyDispositionFence fence = PropertyDispositionFence.prepare(
        decisionId,
        fingerprint,
        request.warehouseId(),
        PropertyAssetKind.EQUIPMENT,
        request.assetId(),
        request.disposition(),
        null,
        null,
        null,
        request.quantity(),
        custody.claimId(),
        custody.custodyVersion(),
        null,
        null,
        null,
        null,
        null,
        null,
        preparedAt);
    decisions.savePreparedFence(fence);
    decisions.writeAudit(
        decisionId,
        "PREPARED",
        actorSubjectId,
        fingerprint,
        Map.of(
            "decisionId", decisionId.toString(),
            "assetKind", PropertyAssetKind.EQUIPMENT.name(),
            "assetId", request.assetId().toString(),
            "warehouseId", request.warehouseId().toString(),
            "maintenanceCustodyClaimId", custody.claimId().toString(),
            "maintenanceCustodyVersion", custody.custodyVersion(),
            "quantity", request.quantity(),
            "disposition", request.disposition().name(),
            "transportIdempotencyKey", transportIdempotencyKey.toString()));
    return new CommandResult<>(decisions.fenceResponse(fence), false);
  }

  private void assertCabinContentPlan(
      List<PropertyDispositionLedgerService.ContentBalanceRow> actual, NormalizedPrepare request) {
    if (actual.isEmpty()) {
      if (!request.contents().isEmpty() || request.contentsMode() != null) {
        throw new AssetConflictException("An empty cabin cannot carry a disposition content plan");
      }
      return;
    }
    if (request.contentsMode() == null) {
      throw new AssetConflictException("A non-empty cabin requires a disposition content mode");
    }
    if (actual.size() != request.contents().size()) {
      throw new AssetConflictException("Cabin content plan does not match current contents");
    }
    Map<UUID, PropertyDispositionLedgerService.ContentBalanceRow> actualByEquipment = new HashMap<>();
    for (PropertyDispositionLedgerService.ContentBalanceRow line : actual) {
      if (actualByEquipment.put(line.equipmentId(), line) != null) {
        throw new IllegalStateException("Cabin has duplicate canonical equipment balances");
      }
    }
    for (NormalizedContent line : request.contents()) {
      PropertyDispositionLedgerService.ContentBalanceRow current = actualByEquipment.get(line.equipmentId());
      if (current == null
          || current.version() != line.expectedBalanceVersion()
          || current.quantity() != line.currentQuantity()
          || line.moveQuantity() > current.quantity()) {
        throw new AssetConflictException("Cabin content plan changed concurrently");
      }
      if (request.contentsMode() == PropertyDispositionContentsMode.DISPOSE_WITH_CABIN
          && line.moveQuantity() != 0) {
        throw new AssetConflictException("Dispose-with-cabin content plan cannot select a stock return");
      }
    }
  }

  private NormalizedPrepare normalize(PrepareMaintenancePropertyDispositionRequest request) {
    if (request == null
        || request.warehouseId() == null
        || request.assetKind() == null
        || request.assetId() == null
        || request.disposition() == null
        || request.contents() == null) {
      throw new IllegalArgumentException("Property disposition preparation is incomplete");
    }
    List<NormalizedContent> normalizedContents = new ArrayList<>();
    Set<UUID> equipmentIds = new HashSet<>();
    for (PrepareMaintenancePropertyContentRequest line : request.contents()) {
      if (line == null
          || line.equipmentId() == null
          || line.expectedBalanceVersion() == null
          || line.expectedBalanceVersion() < 0
          || line.currentQuantity() == null
          || line.currentQuantity() < 1
          || line.moveQuantity() == null
          || line.moveQuantity() < 0
          || line.moveQuantity() > line.currentQuantity()
          || !equipmentIds.add(line.equipmentId())) {
        throw new IllegalArgumentException("Property disposition content plan is invalid");
      }
      normalizedContents.add(
          new NormalizedContent(
              line.equipmentId(),
              line.expectedBalanceVersion(),
              line.currentQuantity(),
              line.moveQuantity()));
    }
    normalizedContents.sort(Comparator.comparing(value -> value.equipmentId().toString()));
    if (request.assetKind() == PropertyAssetKind.CABIN) {
      if (request.expectedAssetVersion() == null
          || request.expectedAssetVersion() < 0
          || request.expectedSourceBalanceVersion() != null
          || request.quantity() != null) {
        throw new IllegalArgumentException("Cabin disposition requires only the rental-item version");
      }
      if (request.maintenanceCustodyClaimId() != null || request.maintenanceCustodyVersion() != null) {
        throw new IllegalArgumentException("Cabin disposition cannot reference furniture custody");
      }
      if (normalizedContents.isEmpty()) {
        if (request.contentsMode() != null) {
          throw new IllegalArgumentException("An empty cabin disposition cannot declare a content mode");
        }
      } else if (request.contentsMode() == null) {
        throw new IllegalArgumentException("A non-empty cabin disposition requires a content mode");
      }
      if (request.contentsMode() == PropertyDispositionContentsMode.DISPOSE_WITH_CABIN
          && normalizedContents.stream().anyMatch(value -> value.moveQuantity() != 0)) {
        throw new IllegalArgumentException("Dispose-with-cabin plan cannot select a stock return");
      }
    } else {
      boolean custody =
          request.maintenanceCustodyClaimId() != null || request.maintenanceCustodyVersion() != null;
      if (request.expectedAssetVersion() != null
          || request.quantity() == null
          || request.quantity() < 1
          || request.contentsMode() != null
          || !normalizedContents.isEmpty()
          || request.authorizedMaintenanceLease() != null) {
        throw new IllegalArgumentException("Equipment disposition shape is invalid");
      }
      if (custody) {
        if (request.maintenanceCustodyClaimId() == null
            || request.maintenanceCustodyVersion() == null
            || request.maintenanceCustodyVersion() < 0
            || request.expectedSourceBalanceVersion() != null) {
          throw new IllegalArgumentException("Custody equipment disposition shape is invalid");
        }
      } else if (request.expectedSourceBalanceVersion() == null
          || request.expectedSourceBalanceVersion() < 0) {
        throw new IllegalArgumentException("Stock equipment disposition shape is invalid");
      }
    }
    MaintenancePropertyDispositionLeaseProof proof = request.authorizedMaintenanceLease();
    if (proof != null
        && (proof.leaseId() == null
            || proof.fencingToken() == null
            || proof.fencingToken() < 1
            || proof.ownerType() == null
            || proof.ownerId() == null)) {
      throw new IllegalArgumentException("Maintenance lease proof is incomplete");
    }
    return new NormalizedPrepare(
        request.warehouseId(),
        request.assetKind(),
        request.assetId(),
        request.disposition(),
        request.expectedAssetVersion(),
        request.expectedSourceBalanceVersion(),
        request.quantity(),
        request.maintenanceCustodyClaimId(),
        request.maintenanceCustodyVersion(),
        request.contentsMode(),
        List.copyOf(normalizedContents),
        proof);
  }

  private String prepareFingerprint(UUID decisionId, NormalizedPrepare request) {
    return codec.hash(
        new PrepareFingerprint(
            decisionId,
            request.warehouseId(),
            request.assetKind(),
            request.assetId(),
            request.disposition(),
            request.expectedAssetVersion(),
            request.expectedSourceBalanceVersion(),
            request.quantity(),
            request.maintenanceCustodyClaimId(),
            request.maintenanceCustodyVersion(),
            request.contentsMode(),
            request.contents(),
            request.authorizedMaintenanceLease()));
  }

  private static List<Map<String, Object>> preparedContentFacts(
      List<PropertyDispositionFenceContent> lines) {
    return lines.stream()
        .map(
            line ->
                Map.<String, Object>of(
                    "equipmentId", line.getEquipmentId().toString(),
                    "sourceBalanceId", line.getSourceBalanceId().toString(),
                    "expectedBalanceVersion", line.getExpectedBalanceVersion(),
                    "currentQuantity", line.getCurrentQuantity(),
                    "moveQuantity", line.getMoveQuantity(),
                    "dispositionQuantity", line.getDispositionQuantity()))
        .toList();
  }

  private static UUID leaseId(MaintenancePropertyDispositionLeaseProof proof) {
    return proof == null ? null : proof.leaseId();
  }

  private static Long fencingToken(MaintenancePropertyDispositionLeaseProof proof) {
    return proof == null ? null : proof.fencingToken();
  }

  private static String leaseOwnerType(MaintenancePropertyDispositionLeaseProof proof) {
    return proof == null ? null : proof.ownerType().name();
  }

  private static UUID leaseOwnerId(MaintenancePropertyDispositionLeaseProof proof) {
    return proof == null ? null : proof.ownerId();
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw new IllegalStateException("Property disposition transaction did not return a result");
    }
    return value;
  }

  /**
   * Canonical per-equipment quantities and expected balance version used by preparation checks and
   * fingerprinting.
   */
  private record NormalizedContent(
      UUID equipmentId, long expectedBalanceVersion, long currentQuantity, long moveQuantity) {}

  /**
   * Fully normalized prepare command, including the maintenance lease proof authorized for the
   * requested disposition.
   */
  private record NormalizedPrepare(
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionApiModels.PropertyDispositionKind disposition,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      PropertyDispositionContentsMode contentsMode,
      List<NormalizedContent> contents,
      MaintenancePropertyDispositionLeaseProof authorizedMaintenanceLease) {
    Map<UUID, NormalizedContent> contentsByEquipment() {
      Map<UUID, NormalizedContent> result = new HashMap<>();
      contents.forEach(value -> result.put(value.equipmentId(), value));
      return result;
    }
  }

  /**
   * Stable semantic request material hashed to distinguish a valid idempotent replay from a
   * conflicting reuse of the decision key.
   */
  private record PrepareFingerprint(
      UUID decisionId,
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionApiModels.PropertyDispositionKind disposition,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      PropertyDispositionContentsMode contentsMode,
      List<NormalizedContent> contents,
      MaintenancePropertyDispositionLeaseProof authorizedMaintenanceLease) {}

  /**
   * Associates one frozen content line with the allocation hold created to reserve its movement.
   */
  private record HeldContent(PropertyDispositionLedgerService.ContentBalanceRow line, UUID holdId) {}
}
