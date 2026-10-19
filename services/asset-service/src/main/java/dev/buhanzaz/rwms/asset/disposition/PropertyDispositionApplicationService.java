package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.ApplyMaintenancePropertyDispositionRequest;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.CommandResult;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.MaintenancePropertyDispositionEffect;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionFenceState;
import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import dev.buhanzaz.rwms.asset.service.MaintenanceFurnitureCustodyService;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Owns the APPLY lifecycle for a prepared property-disposition fence and its permanent effect
 * replay.
 *
 * <p>All fence, source-balance, hold, movement-proof, custody, and rental-status operations run
 * in the original default transaction template. It performs no remote calls.
 */
@Service
final class PropertyDispositionApplicationService {
  private final TransactionTemplate transactions;
  private final RentalItemRepository rentalItems;
  private final PropertyDispositionDecisionStore decisions;
  private final PropertyDispositionLedgerService ledger;
  private final PropertyDispositionEligibilityService eligibility;
  private final PropertyDispositionCodec codec;
  private final MaintenanceFurnitureCustodyService furnitureCustody;
  private final AssetEventStore events;
  private final JdbcTemplate jdbc;

  PropertyDispositionApplicationService(
      PlatformTransactionManager transactionManager,
      RentalItemRepository rentalItems,
      PropertyDispositionDecisionStore decisions,
      PropertyDispositionLedgerService ledger,
      PropertyDispositionEligibilityService eligibility,
      PropertyDispositionCodec codec,
      MaintenanceFurnitureCustodyService furnitureCustody,
      AssetEventStore events,
      JdbcTemplate jdbc) {
    this.transactions = new TransactionTemplate(transactionManager);
    this.rentalItems = rentalItems;
    this.decisions = decisions;
    this.ledger = ledger;
    this.eligibility = eligibility;
    this.codec = codec;
    this.furnitureCustody = furnitureCustody;
    this.events = events;
    this.jdbc = jdbc;
  }

  /** Applies exactly one already prepared decision, or returns its permanent matching replay. */
  CommandResult<MaintenancePropertyDispositionEffect> apply(
      UUID maintenanceSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      ApplyMaintenancePropertyDispositionRequest request) {
    eligibility.requireCommandIdentity(maintenanceSubjectId, decisionId, transportIdempotencyKey);
    if (request == null) {
      throw new IllegalArgumentException("Property disposition apply request is required");
    }
    return required(
        transactions.execute(
            ignored ->
                applyInTransaction(maintenanceSubjectId, decisionId, transportIdempotencyKey, request)));
  }

  private CommandResult<MaintenancePropertyDispositionEffect> applyInTransaction(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      ApplyMaintenancePropertyDispositionRequest request) {
    decisions.lockDecision(decisionId);
    PropertyDispositionFence fence = decisions
        .findForDecisionUpdate(decisionId)
        .orElseThrow(() -> new AssetNotFoundException("Property disposition fence was not found"));
    if (fence.getState() == PropertyDispositionFenceState.APPLIED) {
      if (!fence.hasMatchingApplyTask(request.completedMovementTaskId())) {
        throw new AssetConflictException("Property disposition effect is already bound to another movement task");
      }
      return new CommandResult<>(decisions.effectResponse(fence), true);
    }
    if (fence.getState() != PropertyDispositionFenceState.PREPARED) {
      throw new AssetConflictException("Property disposition fence is not prepared");
    }
    return fence.getAssetKind() == PropertyAssetKind.CABIN
        ? applyCabin(actorSubjectId, transportIdempotencyKey, fence, request)
        : applyEquipment(actorSubjectId, transportIdempotencyKey, fence, request);
  }

  private CommandResult<MaintenancePropertyDispositionEffect> applyCabin(
      UUID actorSubjectId,
      UUID transportIdempotencyKey,
      PropertyDispositionFence fence,
      ApplyMaintenancePropertyDispositionRequest request) {
    List<PropertyDispositionFenceContent> plan = decisions.preparedContents(fence.getDecisionId());
    long selectedQuantity = plan.stream().mapToLong(PropertyDispositionFenceContent::getMoveQuantity).sum();
    if (selectedQuantity == 0 && request.completedMovementTaskId() != null) {
      throw new AssetConflictException("An empty cabin return plan cannot reference a movement task");
    }
    if (selectedQuantity > 0 && request.completedMovementTaskId() == null) {
      throw new AssetConflictException("Selected cabin contents require a completed movement task");
    }

    ledger.lockCabinAndLease(fence.getAssetId());
    RentalItem cabin = rentalItems
        .findByIdForUpdate(fence.getAssetId())
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!fence.getWarehouseId().equals(cabin.getWarehouseId())) {
      throw new AssetConflictException("Cabin warehouse changed after property disposition preparation");
    }
    eligibility.assertVersion(
        cabin.getVersion(),
        fence.getExpectedAssetVersion(),
        "Cabin changed after property disposition preparation");
    eligibility.assertCabinStatusAllowsDisposition(cabin);
    eligibility.assertNoActiveCabinReservation(cabin.getId());
    eligibility.assertFenceLeaseAllowsApply(cabin, eligibility.fenceLeaseProof(fence));

    BalanceLocationKind cabinKind = eligibility.cabinBalanceKind(cabin);
    BalanceLocationKind terminalKind = terminalBalanceKind(fence.getDisposition());
    ledger.lockCabinSourcesAndTerminals(
        plan, fence.getWarehouseId(), fence.getAssetId(), cabinKind, terminalKind);
    ledger.assertNoForeignActiveHolds(
        plan.stream().map(PropertyDispositionFenceContent::getSourceBalanceId).toList(),
        fence.getDecisionId());

    List<PreparedSource> sources = new ArrayList<>();
    for (PropertyDispositionFenceContent line : plan) {
      PropertyDispositionLedgerService.BalanceRow source = ledger.balanceForUpdate(line.getSourceBalanceId());
      assertCabinSourceMatchesFence(source, fence, line, cabinKind);
      ledger.assertDispositionHoldCommitted(line.getHoldId(), fence.getDecisionId());
      sources.add(new PreparedSource(line, source));
    }
    verifyExecutedMoves(fence, sources);
    for (PreparedSource source : sources) {
      if (source.balance().quantity() != source.line().getDispositionQuantity()) {
        throw new AssetConflictException("Cabin content changed after property disposition preparation");
      }
    }

    List<MovementResponse> terminalMovements = new ArrayList<>();
    for (PreparedSource source : sources) {
      if (source.line().getDispositionQuantity() > 0) {
        terminalMovements.add(
            ledger.moveToTerminal(
                actorSubjectId,
                source.balance(),
                source.line().getDispositionQuantity(),
                terminalKind));
      }
    }
    for (PreparedSource source : sources) {
      ledger.releaseDispositionHold(source.line().getHoldId(), fence.getDecisionId());
    }

    long expectedCabinVersion = cabin.getVersion();
    cabin.applyPropertyDisposition(terminalRentalStatus(fence.getDisposition()));
    RentalItem saved = rentalItems.saveAndFlush(cabin);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        expectedCabinVersion,
        AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
        rentalFact(saved),
        rentalSnapshot(saved));

    OffsetDateTime appliedAt = ledger.databaseNow();
    MaintenancePropertyDispositionEffect response = new MaintenancePropertyDispositionEffect(
        UUID.randomUUID(),
        fence.getDecisionId(),
        PropertyAssetKind.CABIN,
        fence.getAssetId(),
        fence.getDisposition(),
        saved.getVersion(),
        List.copyOf(terminalMovements),
        appliedAt);
    decisions.completeEffect(fence, request.completedMovementTaskId(), response);
    Map<String, Object> auditBody = new LinkedHashMap<>();
    auditBody.put("decisionId", fence.getDecisionId().toString());
    auditBody.put("effectId", response.effectId().toString());
    auditBody.put("assetKind", PropertyAssetKind.CABIN.name());
    auditBody.put("assetId", fence.getAssetId().toString());
    auditBody.put("disposition", fence.getDisposition().name());
    auditBody.put("completedMovementTaskId", nullableUuid(request.completedMovementTaskId()));
    auditBody.put("transportIdempotencyKey", transportIdempotencyKey.toString());
    auditBody.put(
        "terminalMovementIds", terminalMovements.stream().map(value -> value.id().toString()).toList());
    decisions.writeAudit(fence.getDecisionId(), "APPLIED", actorSubjectId, fence.getRequestSha256(), auditBody);
    return new CommandResult<>(response, false);
  }

  private CommandResult<MaintenancePropertyDispositionEffect> applyEquipment(
      UUID actorSubjectId,
      UUID transportIdempotencyKey,
      PropertyDispositionFence fence,
      ApplyMaintenancePropertyDispositionRequest request) {
    if (fence.getMaintenanceCustodyClaimId() != null) {
      return applyCustodyEquipment(actorSubjectId, transportIdempotencyKey, fence, request);
    }
    if (request.completedMovementTaskId() != null) {
      throw new AssetConflictException("Stock equipment disposition cannot reference a movement task");
    }
    BalanceLocationKind terminalKind = terminalBalanceKind(fence.getDisposition());
    ledger.lockStockAndTerminal(fence.getAssetId(), fence.getWarehouseId(), terminalKind);
    PropertyDispositionLedgerService.BalanceRow source = ledger.balanceForUpdate(fence.getSourceBalanceId());
    if (!source.equipmentId().equals(fence.getAssetId())
        || !source.warehouseId().equals(fence.getWarehouseId())
        || source.rentalItemId() != null
        || source.locationKind() != BalanceLocationKind.STOCK) {
      throw new AssetConflictException("Stock equipment source changed after property disposition preparation");
    }
    eligibility.assertVersion(
        source.version(),
        fence.getExpectedSourceBalanceVersion(),
        "Stock equipment changed after property disposition preparation");
    if (source.quantity() < fence.getQuantity()) {
      throw new AssetConflictException(
          "Stock equipment quantity changed after property disposition preparation");
    }
    eligibility.assertNoActiveEquipmentReservation(source.equipmentId(), source.warehouseId());
    ledger.assertNoForeignActiveHolds(List.of(source.id()), fence.getDecisionId());
    ledger.assertDispositionHoldCommitted(fence.getPrimaryHoldId(), fence.getDecisionId());

    MovementResponse movement = ledger.moveToTerminal(
        actorSubjectId, source, fence.getQuantity(), terminalKind);
    ledger.releaseDispositionHold(fence.getPrimaryHoldId(), fence.getDecisionId());
    OffsetDateTime appliedAt = ledger.databaseNow();
    PropertyDispositionLedgerService.BalanceRow sourceAfter = ledger.balanceRead(source.id());
    MaintenancePropertyDispositionEffect response = new MaintenancePropertyDispositionEffect(
        UUID.randomUUID(),
        fence.getDecisionId(),
        PropertyAssetKind.EQUIPMENT,
        fence.getAssetId(),
        fence.getDisposition(),
        sourceAfter.version(),
        List.of(movement),
        appliedAt);
    decisions.completeEffect(fence, null, response);
    decisions.writeAudit(
        fence.getDecisionId(),
        "APPLIED",
        actorSubjectId,
        fence.getRequestSha256(),
        Map.of(
            "decisionId", fence.getDecisionId().toString(),
            "effectId", response.effectId().toString(),
            "assetKind", PropertyAssetKind.EQUIPMENT.name(),
            "assetId", fence.getAssetId().toString(),
            "disposition", fence.getDisposition().name(),
            "transportIdempotencyKey", transportIdempotencyKey.toString(),
            "terminalMovementIds", List.of(movement.id().toString())));
    return new CommandResult<>(response, false);
  }

  private CommandResult<MaintenancePropertyDispositionEffect> applyCustodyEquipment(
      UUID actorSubjectId,
      UUID transportIdempotencyKey,
      PropertyDispositionFence fence,
      ApplyMaintenancePropertyDispositionRequest request) {
    if (request.completedMovementTaskId() != null) {
      throw new AssetConflictException("Custody equipment disposition cannot reference a movement task");
    }
    MaintenanceFurnitureCustodyService.CustodyTerminalApplication terminal =
        furnitureCustody.applyDisposition(
            actorSubjectId,
            fence.getDecisionId(),
            fence.getMaintenanceCustodyClaimId(),
            fence.getWarehouseId(),
            fence.getAssetId(),
            fence.getQuantity(),
            terminalBalanceKind(fence.getDisposition()),
            fence.getRequestSha256());
    MaintenancePropertyDispositionEffect response = new MaintenancePropertyDispositionEffect(
        UUID.randomUUID(),
        fence.getDecisionId(),
        PropertyAssetKind.EQUIPMENT,
        fence.getAssetId(),
        fence.getDisposition(),
        terminal.terminalBalanceVersion(),
        List.of(),
        terminal.appliedAt());
    decisions.completeEffect(fence, null, response);
    decisions.writeAudit(
        fence.getDecisionId(),
        "APPLIED",
        actorSubjectId,
        fence.getRequestSha256(),
        Map.of(
            "decisionId", fence.getDecisionId().toString(),
            "effectId", response.effectId().toString(),
            "assetKind", PropertyAssetKind.EQUIPMENT.name(),
            "assetId", fence.getAssetId().toString(),
            "maintenanceCustodyClaimId", fence.getMaintenanceCustodyClaimId().toString(),
            "custodyVersion", terminal.custodyVersion(),
            "terminalBalanceId", terminal.terminalBalanceId().toString(),
            "disposition", fence.getDisposition().name(),
            "transportIdempotencyKey", transportIdempotencyKey.toString(),
            "terminalMovementIds", List.of()));
    return new CommandResult<>(response, false);
  }

  private void assertCabinSourceMatchesFence(
      PropertyDispositionLedgerService.BalanceRow source,
      PropertyDispositionFence fence,
      PropertyDispositionFenceContent line,
      BalanceLocationKind expectedKind) {
    if (!source.equipmentId().equals(line.getEquipmentId())
        || !source.warehouseId().equals(fence.getWarehouseId())
        || !fence.getAssetId().equals(source.rentalItemId())
        || source.locationKind() != expectedKind) {
      throw new AssetConflictException(
          "Cabin content source changed after property disposition preparation");
    }
  }

  private void verifyExecutedMoves(PropertyDispositionFence fence, List<PreparedSource> sources) {
    for (PreparedSource source : sources) {
      long expected = source.line().getMoveQuantity();
      List<PropertyDispositionLedgerService.ExecutedMovementProof> proofs = ledger.executedMovementProofs(
          fence.getDecisionId(), source.line().getEquipmentId(), source.line().getSourceBalanceId());
      long actual = proofs.stream().mapToLong(value -> value.quantity()).sum();
      if (actual != expected) {
        throw new AssetConflictException(
            "Selected cabin contents have not been fully executed by the logistics movement");
      }
      if (proofs.stream().anyMatch(proof -> proof.quantity() < 1)) {
        throw new AssetConflictException("Executed logistics disposition proof is malformed");
      }
    }
  }

  private static Map<String, ?> rentalFact(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rentalItemId", item.getId().toString());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("status", item.getStatus().name());
    value.put(
        "numberSha256",
        dev.buhanzaz.rwms.asset.service.AssetChecksum.sha256(
            item.getNumber().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if (item.getTransferOriginStatus() != null) {
      value.put("transferAssetStatus", item.getTransferOriginStatus().name());
    }
    return Map.copyOf(value);
  }

  private Map<String, ?> rentalSnapshot(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rentalItemId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("number", item.getNumber());
    value.put("status", item.getStatus().name());
    value.put(
        "transferOriginStatus",
        item.getTransferOriginStatus() == null ? null : item.getTransferOriginStatus().name());
    value.put("rentalTypeId", nullableUuid(item.getRentalTypeId()));
    value.put("dimensionId", nullableUuid(item.getDimensionId()));
    value.put("finishingId", nullableUuid(item.getFinishingId()));
    value.put("category", item.getCategory());
    value.put(
        "characteristicIds",
        jdbc.query(
            "select characteristic_id from rental_item_characteristic where rental_item_id=? order by characteristic_id",
            (resultSet, row) -> resultSet.getObject("characteristic_id", UUID.class).toString(),
            item.getId()));
    value.put("linoleum", item.getLinoleum());
    value.put("generalComment", item.getGeneralComment());
    value.put("passport", codec.map(item.getPassportJson()));
    value.put("tags", codec.list(item.getTagsJson()));
    return value;
  }

  private static RentalItemStatus terminalRentalStatus(PropertyDispositionKind disposition) {
    return disposition == PropertyDispositionKind.WRITE_OFF
        ? RentalItemStatus.WRITTEN_OFF
        : RentalItemStatus.LOST;
  }

  private static BalanceLocationKind terminalBalanceKind(PropertyDispositionKind disposition) {
    return disposition == PropertyDispositionKind.WRITE_OFF
        ? BalanceLocationKind.WRITTEN_OFF
        : BalanceLocationKind.LOST;
  }

  private static String nullableUuid(UUID value) {
    return value == null ? null : value.toString();
  }

  private static <T> T required(T value) {
    if (value == null) {
      throw new IllegalStateException("Property disposition transaction did not return a result");
    }
    return value;
  }

  /** Couples a prepared fence-content line with the locked balance revalidated during apply. */
  private record PreparedSource(
      PropertyDispositionFenceContent line, PropertyDispositionLedgerService.BalanceRow balance) {}
}
