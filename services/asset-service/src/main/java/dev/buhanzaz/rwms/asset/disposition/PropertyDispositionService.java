package dev.buhanzaz.rwms.asset.disposition;

import static dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MovementResponse;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyAssetKind;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionContentsMode;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionFenceState;
import dev.buhanzaz.rwms.asset.disposition.PropertyDispositionApiModels.PropertyDispositionKind;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetNotFoundException;
import dev.buhanzaz.rwms.asset.service.MaintenanceFurnitureCustodyService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.SerializationFeature;

/**
 * Asset's half of an approved maintenance disposition.  It deliberately has
 * no remote maintenance or logistics calls: the caller supplies the approved
 * immutable plan, and worker execution is proven against asset's own ledger.
 */
@Service
public class PropertyDispositionService {
  private static final String DISPOSITION_HOLD_OWNER = "MAINTENANCE_PROPERTY_DISPOSITION";
  private static final String DISPOSITION_MOVEMENT_HOLD_OWNER =
      "MAINTENANCE_DISPOSITION_MOVEMENT";
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;
  private final TransactionTemplate transactions;
  private final WarehouseRegistryClient warehouses;
  private final RentalItemRepository rentalItems;
  private final PropertyDispositionFenceRepository fences;
  private final PropertyDispositionFenceContentRepository contents;
  private final PropertyDispositionEffectRepository effects;
  private final AssetEventStore events;
  private final MaintenanceFurnitureCustodyService furnitureCustody;

  public PropertyDispositionService(
      JdbcTemplate jdbc,
      ObjectMapper mapper,
      PlatformTransactionManager transactionManager,
      WarehouseRegistryClient warehouses,
      RentalItemRepository rentalItems,
      PropertyDispositionFenceRepository fences,
      PropertyDispositionFenceContentRepository contents,
      PropertyDispositionEffectRepository effects,
      AssetEventStore events,
      MaintenanceFurnitureCustodyService furnitureCustody) {
    this.jdbc = jdbc;
    this.mapper = mapper;
    this.transactions = new TransactionTemplate(transactionManager);
    this.warehouses = warehouses;
    this.rentalItems = rentalItems;
    this.fences = fences;
    this.contents = contents;
    this.effects = effects;
    this.events = events;
    this.furnitureCustody = furnitureCustody;
  }

  public MaintenancePropertyAssetSnapshot snapshot(
      PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    requireIdentity(assetKind, assetId, warehouseId);
    return required(
        transactions.execute(
            ignored ->
                assetKind == PropertyAssetKind.CABIN
                    ? cabinSnapshot(assetId, warehouseId)
                    : equipmentSnapshot(assetId, warehouseId)));
  }

  /** The warehouse registry call intentionally happens before the local transaction. */
  public CommandResult<MaintenancePropertyDispositionFence> prepare(
      UUID maintenanceSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      PrepareMaintenancePropertyDispositionRequest request) {
    requireCommandIdentity(maintenanceSubjectId, decisionId, transportIdempotencyKey);
    NormalizedPrepare normalized = normalize(request);
    String fingerprint = prepareFingerprint(decisionId, normalized);
    CommandResult<MaintenancePropertyDispositionFence> replay =
        existingPreparationReplay(decisionId, fingerprint);
    if (replay != null) return replay;
    // The remote lookup is intentionally outside the local asset transaction. A permanent replay
    // above must remain available even if the warehouse has subsequently been deactivated.
    warehouses.requireOutgoing(normalized.warehouseId());
    return required(
        transactions.execute(
            ignored -> prepareInTransaction(maintenanceSubjectId, decisionId, transportIdempotencyKey, normalized)));
  }

  public CommandResult<MaintenancePropertyDispositionEffect> apply(
      UUID maintenanceSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      ApplyMaintenancePropertyDispositionRequest request) {
    requireCommandIdentity(maintenanceSubjectId, decisionId, transportIdempotencyKey);
    if (request == null) {
      throw new IllegalArgumentException("Property disposition apply request is required");
    }
    return required(
        transactions.execute(
            ignored -> applyInTransaction(maintenanceSubjectId, decisionId, transportIdempotencyKey, request)));
  }

  private CommandResult<MaintenancePropertyDispositionFence> prepareInTransaction(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      NormalizedPrepare request) {
    advisoryLock("asset-property-disposition:" + decisionId);
    String fingerprint = prepareFingerprint(decisionId, request);
    Optional<PropertyDispositionFence> existing = fences.findByDecisionIdForUpdate(decisionId);
    if (existing.isPresent()) {
      if (!existing.get().getRequestSha256().equals(fingerprint)) {
        throw new AssetConflictException("Property disposition decision is already bound to another request");
      }
      return new CommandResult<>(fenceResponse(existing.get()), true);
    }

    return request.assetKind() == PropertyAssetKind.CABIN
        ? prepareCabin(actorSubjectId, decisionId, transportIdempotencyKey, fingerprint, request)
        : prepareEquipment(actorSubjectId, decisionId, transportIdempotencyKey, fingerprint, request);
  }

  private CommandResult<MaintenancePropertyDispositionFence> existingPreparationReplay(
      UUID decisionId, String fingerprint) {
    return transactions.execute(
        ignored -> {
          advisoryLock("asset-property-disposition:" + decisionId);
          Optional<PropertyDispositionFence> existing = fences.findByDecisionIdForUpdate(decisionId);
          if (existing.isEmpty()) return null;
          if (!existing.get().getRequestSha256().equals(fingerprint)) {
            throw new AssetConflictException(
                "Property disposition decision is already bound to another request");
          }
          return new CommandResult<>(fenceResponse(existing.get()), true);
        });
  }

  private CommandResult<MaintenancePropertyDispositionFence> prepareCabin(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      String fingerprint,
      NormalizedPrepare request) {
    advisoryLocks(List.of(rentalItemLockKey(request.assetId()), leaseLockKey(request.assetId())));
    RentalItem cabin = rentalItems.findByIdForUpdate(request.assetId())
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!request.warehouseId().equals(cabin.getWarehouseId())) {
      throw new AssetConflictException("Cabin does not belong to the requested warehouse");
    }
    assertVersion(cabin.getVersion(), request.expectedAssetVersion(), "Rental item changed concurrently");
    assertCabinStatusAllowsDisposition(cabin);
    assertNoActiveCabinReservation(cabin.getId());
    assertLeaseProof(cabin.getId(), request.authorizedMaintenanceLease());
    assertNoPreparedFence(PropertyAssetKind.CABIN, cabin.getId(), request.warehouseId());

    List<ContentBalanceRow> actual = cabinContentsForUpdate(cabin.getId(), request.warehouseId());
    advisoryLocks(
        actual.stream()
            .map(value -> balanceLockKey(value.equipmentId(), value.warehouseId(), value.rentalItemId(), value.locationKind()))
            .toList());
    actual = cabinContentsForUpdate(cabin.getId(), request.warehouseId());
    assertNoForeignActiveHolds(actual.stream().map(ContentBalanceRow::id).toList(), decisionId);
    assertCabinContentPlan(actual, request);

    OffsetDateTime preparedAt = databaseNow();
    List<HeldContent> held = new ArrayList<>();
    for (ContentBalanceRow line : actual) {
      UUID holdId = createCommittedDispositionHold(
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
    fences.saveAndFlush(fence);

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
    contents.saveAllAndFlush(lines);
    writeAudit(
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
    return new CommandResult<>(fenceResponse(fence), false);
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
    advisoryLock(balanceLockKey(request.assetId(), request.warehouseId(), null, BalanceLocationKind.STOCK));
    StockEquipmentRow stock = stockEquipmentForUpdate(request.assetId(), request.warehouseId());
    assertVersion(
        stock.balanceVersion(),
        request.expectedSourceBalanceVersion(),
        "Equipment stock balance changed concurrently");
    if (stock.quantity() < request.quantity()) {
      throw new AssetConflictException("Property disposition exceeds current stock quantity");
    }
    assertNoActiveEquipmentReservation(stock.equipmentId(), stock.warehouseId());
    assertNoForeignActiveHolds(List.of(stock.balanceId()), decisionId);
    assertNoPreparedFence(PropertyAssetKind.EQUIPMENT, stock.equipmentId(), stock.warehouseId());

    UUID holdId = createCommittedDispositionHold(
        decisionId, stock.equipmentId(), stock.warehouseId(), stock.balanceId(), request.quantity());
    OffsetDateTime preparedAt = databaseNow();
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
    fences.saveAndFlush(fence);
    writeAudit(
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
    return new CommandResult<>(fenceResponse(fence), false);
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
    OffsetDateTime preparedAt = databaseNow();
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
    fences.saveAndFlush(fence);
    writeAudit(
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
    return new CommandResult<>(fenceResponse(fence), false);
  }

  private CommandResult<MaintenancePropertyDispositionEffect> applyInTransaction(
      UUID actorSubjectId,
      UUID decisionId,
      UUID transportIdempotencyKey,
      ApplyMaintenancePropertyDispositionRequest request) {
    advisoryLock("asset-property-disposition:" + decisionId);
    PropertyDispositionFence fence = fences.findByDecisionIdForUpdate(decisionId)
        .orElseThrow(() -> new AssetNotFoundException("Property disposition fence was not found"));
    if (fence.getState() == PropertyDispositionFenceState.APPLIED) {
      if (!fence.hasMatchingApplyTask(request.completedMovementTaskId())) {
        throw new AssetConflictException("Property disposition effect is already bound to another movement task");
      }
      return new CommandResult<>(effectResponse(fence), true);
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
    List<PropertyDispositionFenceContent> plan = contents.findAllByDecisionIdOrderByEquipmentIdAsc(fence.getDecisionId());
    long selectedQuantity = plan.stream().mapToLong(PropertyDispositionFenceContent::getMoveQuantity).sum();
    if (selectedQuantity == 0 && request.completedMovementTaskId() != null) {
      throw new AssetConflictException("An empty cabin return plan cannot reference a movement task");
    }
    if (selectedQuantity > 0 && request.completedMovementTaskId() == null) {
      throw new AssetConflictException("Selected cabin contents require a completed movement task");
    }

    advisoryLocks(
        List.of(rentalItemLockKey(fence.getAssetId()), leaseLockKey(fence.getAssetId())));
    RentalItem cabin = rentalItems.findByIdForUpdate(fence.getAssetId())
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!fence.getWarehouseId().equals(cabin.getWarehouseId())) {
      throw new AssetConflictException("Cabin warehouse changed after property disposition preparation");
    }
    assertVersion(
        cabin.getVersion(), fence.getExpectedAssetVersion(), "Cabin changed after property disposition preparation");
    assertCabinStatusAllowsDisposition(cabin);
    assertNoActiveCabinReservation(cabin.getId());
    assertFenceLeaseAllowsApply(cabin.getId(), fenceLeaseProof(fence));

    advisoryLocks(
        plan.stream()
            .flatMap(
                line ->
                    List.of(
                            balanceLockKey(
                                line.getEquipmentId(),
                                fence.getWarehouseId(),
                                fence.getAssetId(),
                                cabinBalanceKind(cabin)),
                            balanceLockKey(
                                line.getEquipmentId(),
                                fence.getWarehouseId(),
                                null,
                                terminalBalanceKind(fence.getDisposition())))
                        .stream())
            .toList());
    assertNoForeignActiveHolds(
        plan.stream().map(PropertyDispositionFenceContent::getSourceBalanceId).toList(),
        fence.getDecisionId());

    List<PreparedSource> sources = new ArrayList<>();
    for (PropertyDispositionFenceContent line : plan) {
      BalanceRow source = balanceForUpdate(line.getSourceBalanceId());
      assertCabinSourceMatchesFence(source, fence, line, cabinBalanceKind(cabin));
      assertDispositionHoldCommitted(line.getHoldId(), fence.getDecisionId());
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
            moveToTerminal(
                actorSubjectId,
                source.balance(),
                source.line().getDispositionQuantity(),
                terminalBalanceKind(fence.getDisposition())));
      }
    }
    for (PreparedSource source : sources) {
      releaseDispositionHold(source.line().getHoldId(), fence.getDecisionId());
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

    OffsetDateTime appliedAt = databaseNow();
    MaintenancePropertyDispositionEffect response = new MaintenancePropertyDispositionEffect(
        UUID.randomUUID(),
        fence.getDecisionId(),
        PropertyAssetKind.CABIN,
        fence.getAssetId(),
        fence.getDisposition(),
        saved.getVersion(),
        List.copyOf(terminalMovements),
        appliedAt);
    recordEffectAndCompleteFence(fence, request.completedMovementTaskId(), response);
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
    writeAudit(fence.getDecisionId(), "APPLIED", actorSubjectId, fence.getRequestSha256(), auditBody);
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
    advisoryLocks(
        List.of(
            balanceLockKey(fence.getAssetId(), fence.getWarehouseId(), null, BalanceLocationKind.STOCK),
            balanceLockKey(
                fence.getAssetId(),
                fence.getWarehouseId(),
                null,
                terminalBalanceKind(fence.getDisposition()))));
    BalanceRow source = balanceForUpdate(fence.getSourceBalanceId());
    if (!source.equipmentId().equals(fence.getAssetId())
        || !source.warehouseId().equals(fence.getWarehouseId())
        || source.rentalItemId() != null
        || source.locationKind() != BalanceLocationKind.STOCK) {
      throw new AssetConflictException("Stock equipment source changed after property disposition preparation");
    }
    assertVersion(
        source.version(),
        fence.getExpectedSourceBalanceVersion(),
        "Stock equipment changed after property disposition preparation");
    if (source.quantity() < fence.getQuantity()) {
      throw new AssetConflictException("Stock equipment quantity changed after property disposition preparation");
    }
    assertNoActiveEquipmentReservation(source.equipmentId(), source.warehouseId());
    assertNoForeignActiveHolds(List.of(source.id()), fence.getDecisionId());
    assertDispositionHoldCommitted(fence.getPrimaryHoldId(), fence.getDecisionId());

    MovementResponse movement = moveToTerminal(
        actorSubjectId,
        source,
        fence.getQuantity(),
        terminalBalanceKind(fence.getDisposition()));
    releaseDispositionHold(fence.getPrimaryHoldId(), fence.getDecisionId());
    OffsetDateTime appliedAt = databaseNow();
    BalanceRow sourceAfter = balanceRead(source.id());
    MaintenancePropertyDispositionEffect response = new MaintenancePropertyDispositionEffect(
        UUID.randomUUID(),
        fence.getDecisionId(),
        PropertyAssetKind.EQUIPMENT,
        fence.getAssetId(),
        fence.getDisposition(),
        sourceAfter.version(),
        List.of(movement),
        appliedAt);
    recordEffectAndCompleteFence(fence, null, response);
    writeAudit(
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
    recordEffectAndCompleteFence(fence, null, response);
    writeAudit(
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

  private void recordEffectAndCompleteFence(
      PropertyDispositionFence fence,
      UUID completedMovementTaskId,
      MaintenancePropertyDispositionEffect response) {
    String responseBody = canonicalJson(response);
    effects.saveAndFlush(
        PropertyDispositionEffect.record(
            response.effectId(),
            response.decisionId(),
            responseBody,
            AssetChecksum.sha256(responseBody.getBytes(StandardCharsets.UTF_8)),
            response.appliedAt()));
    fence.apply(response.effectId(), completedMovementTaskId, response.appliedAt());
    fences.saveAndFlush(fence);
  }

  private MaintenancePropertyAssetSnapshot cabinSnapshot(UUID rentalItemId, UUID warehouseId) {
    RentalItem cabin = rentalItems.findById(rentalItemId)
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!warehouseId.equals(cabin.getWarehouseId())) {
      throw new AssetNotFoundException("Rental item was not found in this warehouse");
    }
    List<ContentBalanceRow> currentContents = cabinContents(rentalItemId, warehouseId);
    boolean activeReservation = hasActiveCabinReservation(rentalItemId);
    boolean activeHold = hasActiveHold(currentContents.stream().map(ContentBalanceRow::id).toList());
    boolean activeLease = hasActiveLease(rentalItemId);
    boolean preparedFence = hasPreparedFence(PropertyAssetKind.CABIN, rentalItemId, warehouseId);
    boolean allowed = !activeReservation
        && !activeHold
        && !activeLease
        && !preparedFence
        && cabinStatusAllowsDisposition(cabin);
    return new MaintenancePropertyAssetSnapshot(
        PropertyAssetKind.CABIN,
        cabin.getId(),
        cabin.getNumber(),
        warehouseId,
        cabin.getVersion(),
        null,
        cabin.getStatus(),
        null,
        currentContents.stream()
            .map(
                line ->
                    new MaintenancePropertyContentSnapshot(
                        line.equipmentId(), line.equipmentName(), null, line.version(), line.quantity()))
            .toList(),
        activeReservation,
        activeHold,
        activeLease,
        allowed);
  }

  private MaintenancePropertyAssetSnapshot equipmentSnapshot(UUID equipmentId, UUID warehouseId) {
    StockEquipmentRow stock = stockEquipment(equipmentId, warehouseId);
    boolean activeReservation = hasActiveEquipmentReservation(equipmentId, warehouseId);
    boolean activeHold = hasActiveHold(List.of(stock.balanceId()));
    boolean preparedFence = hasPreparedFence(PropertyAssetKind.EQUIPMENT, equipmentId, warehouseId);
    return new MaintenancePropertyAssetSnapshot(
        PropertyAssetKind.EQUIPMENT,
        equipmentId,
        stock.equipmentName(),
        warehouseId,
        stock.catalogVersion(),
        stock.balanceVersion(),
        null,
        stock.quantity(),
        List.of(),
        activeReservation,
        activeHold,
        false,
        stock.quantity() > 0 && !activeReservation && !activeHold && !preparedFence);
  }

  private void verifyExecutedMoves(
      PropertyDispositionFence fence, List<PreparedSource> sources) {
    for (PreparedSource source : sources) {
      long expected = source.line().getMoveQuantity();
      List<ExecutedMovementProof> proofs = executedMovementProofs(
          fence.getDecisionId(), source.line().getEquipmentId(), source.line().getSourceBalanceId());
      long actual = proofs.stream().mapToLong(ExecutedMovementProof::quantity).sum();
      if (actual != expected) {
        throw new AssetConflictException(
            "Selected cabin contents have not been fully executed by the logistics movement");
      }
      if (proofs.stream().anyMatch(proof -> proof.quantity() < 1)) {
        throw new AssetConflictException("Executed logistics disposition proof is malformed");
      }
    }
  }

  private List<ExecutedMovementProof> executedMovementProofs(
      UUID decisionId, UUID equipmentId, UUID sourceBalanceId) {
    List<ExecutedMovementProof> proofs = jdbc.query(
        """
        select hold.id as reservation_id,movement.id as movement_id,movement.quantity
        from equipment_allocation_hold hold
        join equipment_movement movement on movement.origin_reservation_id=hold.id
        join equipment_balance target on target.id=movement.target_balance_id
        where hold.owner_type=?
          and split_part(hold.owner_id, ':', 1)=?
          and hold.state='EXECUTED'
          and hold.equipment_id=?
          and hold.source_balance_id=?
          and movement.equipment_id=hold.equipment_id
          and movement.source_balance_id=hold.source_balance_id
          and movement.quantity=hold.quantity
          and target.warehouse_id=hold.warehouse_id
          and target.rental_item_id is null
          and target.location_kind='STOCK'
        order by hold.id
        for update of hold,movement,target
        """,
        (rs, row) -> new ExecutedMovementProof(
            rs.getObject("reservation_id", UUID.class),
            rs.getObject("movement_id", UUID.class),
            rs.getLong("quantity")),
        DISPOSITION_MOVEMENT_HOLD_OWNER,
        decisionId.toString(),
        equipmentId,
        sourceBalanceId);
    Set<UUID> distinctReservations = new HashSet<>();
    if (proofs.stream().anyMatch(proof -> !distinctReservations.add(proof.reservationId()))) {
      throw new AssetConflictException("Executed logistics disposition proof is duplicated");
    }
    return proofs;
  }

  private MovementResponse moveToTerminal(
      UUID actorSubjectId,
      BalanceRow source,
      long quantity,
      BalanceLocationKind terminalKind) {
    if (quantity < 1 || source.quantity() < quantity) {
      throw new AssetConflictException("Property disposition cannot consume the source balance");
    }
    BalanceRow target = terminalBalanceForUpdate(
        source.equipmentId(), source.warehouseId(), terminalKind);
    long sourceStreamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, source.id());
    if (sourceStreamVersion != source.version()) {
      throw new AssetConflictException("Source equipment balance event stream changed concurrently");
    }
    long targetStreamVersion = events.lockCurrentVersion(AssetAggregateType.EQUIPMENT_BALANCE, target.id());
    if (targetStreamVersion != target.version()) {
      throw new AssetConflictException("Terminal equipment balance event stream changed concurrently");
    }
    decrementBalance(source, quantity, sourceStreamVersion);
    incrementBalance(target, quantity, targetStreamVersion);
    BalanceRow sourceAfter = balanceForUpdate(source.id());
    BalanceRow targetAfter = balanceForUpdate(target.id());
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        source.id(),
        sourceStreamVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(sourceAfter),
        balanceFact(sourceAfter));
    events.append(
        AssetAggregateType.EQUIPMENT_BALANCE,
        target.id(),
        targetStreamVersion,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(targetAfter),
        balanceFact(targetAfter));

    UUID movementId = UUID.randomUUID();
    String movementKind = terminalKind == BalanceLocationKind.WRITTEN_OFF ? "WRITE_OFF" : "LOSS";
    jdbc.update(
        """
        insert into equipment_movement(
          id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,
          occurred_at,actor_subject_id,origin_reservation_id)
        values (?,0,?,?,?,?,?,clock_timestamp(),?,null)
        """,
        movementId,
        source.equipmentId(),
        source.id(),
        target.id(),
        quantity,
        movementKind,
        actorSubjectId);
    jdbc.update(
        """
        insert into equipment_movement_ledger(movement_id,line_no,balance_id,quantity_delta,recorded_at)
        values (?,1,?,-?,clock_timestamp()), (?,2,?,?,clock_timestamp())
        """,
        movementId,
        source.id(),
        quantity,
        movementId,
        target.id(),
        quantity);
    MovementResponse response = movementResponse(movementId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_MOVEMENT,
        movementId,
        0,
        terminalKind == BalanceLocationKind.WRITTEN_OFF
            ? AssetEventType.EQUIPMENT_WRITTEN_OFF
            : AssetEventType.EQUIPMENT_LOST,
        movementFact(response),
        movementFact(response));
    return response;
  }

  private BalanceRow terminalBalanceForUpdate(
      UUID equipmentId, UUID warehouseId, BalanceLocationKind terminalKind) {
    Optional<BalanceRow> current = findBalanceForUpdate(equipmentId, warehouseId, null, terminalKind);
    if (current.isPresent()) {
      return current.get();
    }
    UUID id = UUID.randomUUID();
    jdbc.update(
        """
        insert into equipment_balance(
          id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,created_at,updated_at)
        values (?,0,?,?,?,?,0,clock_timestamp(),clock_timestamp())
        """,
        id,
        equipmentId,
        warehouseId,
        null,
        terminalKind.name());
    BalanceRow created = balanceForUpdate(id);
    events.initialize(
        AssetAggregateType.EQUIPMENT_BALANCE,
        id,
        0,
        AssetEventType.EQUIPMENT_BALANCE_CHANGED,
        balanceFact(created),
        balanceFact(created));
    return created;
  }

  private UUID createCommittedDispositionHold(
      UUID decisionId,
      UUID equipmentId,
      UUID warehouseId,
      UUID sourceBalanceId,
      long quantity) {
    UUID holdId = UUID.randomUUID();
    UUID idempotencyKey = UUID.nameUUIDFromBytes(
        ("property-disposition-hold:" + decisionId + ':' + equipmentId + ':' + sourceBalanceId)
            .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into equipment_allocation_hold(
          id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,state,
          idempotency_key,expires_at,committed_at,created_at,updated_at)
        values (?,0,?,?,?,?,?,?,'COMMITTED',?,clock_timestamp() + interval '100 years',clock_timestamp(),clock_timestamp(),clock_timestamp())
        """,
        holdId,
        equipmentId,
        warehouseId,
        sourceBalanceId,
        DISPOSITION_HOLD_OWNER,
        decisionId.toString(),
        quantity,
        idempotencyKey);
    HoldRow created = holdForUpdate(holdId);
    events.initialize(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        holdId,
        0,
        AssetEventType.EQUIPMENT_HOLD_COMMITTED,
        holdFact(created),
        holdSnapshot(created));
    return holdId;
  }

  private void releaseDispositionHold(UUID holdId, UUID decisionId) {
    HoldRow current = holdForUpdate(holdId);
    if (!DISPOSITION_HOLD_OWNER.equals(current.ownerType())
        || !decisionId.toString().equals(current.ownerId())
        || !"COMMITTED".equals(current.state())) {
      throw new AssetConflictException("Property disposition hold is no longer committed");
    }
    int changed = jdbc.update(
        """
        update equipment_allocation_hold
        set version=version+1,state='RELEASED',released_at=clock_timestamp(),updated_at=clock_timestamp()
        where id=? and version=? and state='COMMITTED'
        """,
        holdId,
        current.version());
    if (changed != 1) {
      throw new AssetConflictException("Property disposition hold changed concurrently");
    }
    HoldRow updated = holdForUpdate(holdId);
    events.append(
        AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD,
        holdId,
        current.version(),
        AssetEventType.EQUIPMENT_HOLD_RELEASED,
        holdFact(updated),
        holdSnapshot(updated));
  }

  private void assertCabinContentPlan(
      List<ContentBalanceRow> actual, NormalizedPrepare request) {
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
    Map<UUID, ContentBalanceRow> actualByEquipment = new HashMap<>();
    for (ContentBalanceRow line : actual) {
      if (actualByEquipment.put(line.equipmentId(), line) != null) {
        throw new IllegalStateException("Cabin has duplicate canonical equipment balances");
      }
    }
    for (NormalizedContent line : request.contents()) {
      ContentBalanceRow current = actualByEquipment.get(line.equipmentId());
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

  private void assertCabinSourceMatchesFence(
      BalanceRow source,
      PropertyDispositionFence fence,
      PropertyDispositionFenceContent line,
      BalanceLocationKind expectedKind) {
    if (!source.equipmentId().equals(line.getEquipmentId())
        || !source.warehouseId().equals(fence.getWarehouseId())
        || !fence.getAssetId().equals(source.rentalItemId())
        || source.locationKind() != expectedKind) {
      throw new AssetConflictException("Cabin content source changed after property disposition preparation");
    }
  }

  private void assertDispositionHoldCommitted(UUID holdId, UUID decisionId) {
    HoldRow hold = holdForUpdate(holdId);
    if (!DISPOSITION_HOLD_OWNER.equals(hold.ownerType())
        || !decisionId.toString().equals(hold.ownerId())
        || !"COMMITTED".equals(hold.state())) {
      throw new AssetConflictException("Property disposition fence no longer owns its source hold");
    }
  }

  private void assertNoPreparedFence(PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    if (fences
        .findByPhysicalAssetForUpdate(
            assetKind, assetId, warehouseId, PropertyDispositionFenceState.PREPARED)
        .isPresent()) {
      throw new AssetConflictException("Property asset already has a prepared disposition fence");
    }
  }

  private void assertCabinStatusAllowsDisposition(RentalItem cabin) {
    if (!cabinStatusAllowsDisposition(cabin)) {
      throw new AssetConflictException("Cabin is rented, reserved, transferred, or terminal");
    }
  }

  private static boolean cabinStatusAllowsDisposition(RentalItem cabin) {
    RentalItemStatus status = cabin.getStatus();
    return !status.isTerminalDispositionStatus()
        && status != RentalItemStatus.RENTED
        && status != RentalItemStatus.BOOKED
        && status != RentalItemStatus.RESERVED
        && status != RentalItemStatus.IN_TRANSFER
        && cabin.getTransferOriginStatus() == null;
  }

  private void assertNoActiveCabinReservation(UUID rentalItemId) {
    if (hasActiveCabinReservation(rentalItemId)) {
      throw new AssetConflictException("Cabin has an active rental, order, or presentation reservation");
    }
  }

  private boolean hasActiveCabinReservation(UUID rentalItemId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from order_unit_reservation
          where rental_item_id=? and state='ACTIVE'
          union all
          select 1 from presentation_unit_hold
          where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
        )
        """,
        Boolean.class,
        rentalItemId,
        rentalItemId);
    return Boolean.TRUE.equals(active);
  }

  private void assertNoActiveEquipmentReservation(UUID equipmentId, UUID warehouseId) {
    if (hasActiveEquipmentReservation(equipmentId, warehouseId)) {
      throw new AssetConflictException("Equipment has an active order reservation");
    }
  }

  private boolean hasActiveEquipmentReservation(UUID equipmentId, UUID warehouseId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from order_equipment_reservation
          where equipment_id=? and warehouse_id=? and state='ACTIVE'
        )
        """,
        Boolean.class,
        equipmentId,
        warehouseId);
    return Boolean.TRUE.equals(active);
  }

  private void assertNoForeignActiveHolds(Collection<UUID> sourceBalanceIds, UUID decisionId) {
    if (sourceBalanceIds.isEmpty()) return;
    String placeholders = String.join(",", java.util.Collections.nCopies(sourceBalanceIds.size(), "?"));
    List<UUID> conflicting = jdbc.query(
        """
        select id
        from equipment_allocation_hold
        where source_balance_id in (%s)
          and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
          and not (owner_type=? and owner_id=?)
        for update
        """.formatted(placeholders),
        (rs, row) -> rs.getObject("id", UUID.class),
        concat(sourceBalanceIds, DISPOSITION_HOLD_OWNER, decisionId.toString()));
    if (!conflicting.isEmpty()) {
      throw new AssetConflictException("Property disposition source has an active equipment hold");
    }
  }

  private boolean hasActiveHold(Collection<UUID> sourceBalanceIds) {
    if (sourceBalanceIds.isEmpty()) return false;
    String placeholders = String.join(",", java.util.Collections.nCopies(sourceBalanceIds.size(), "?"));
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1
          from equipment_allocation_hold
          where source_balance_id in (%s)
            and (state='COMMITTED' or (state='ACTIVE' and expires_at>clock_timestamp()))
        )
        """.formatted(placeholders),
        Boolean.class,
        sourceBalanceIds.toArray());
    return Boolean.TRUE.equals(active);
  }

  private void assertLeaseProof(
      UUID rentalItemId, MaintenancePropertyDispositionLeaseProof proof) {
    LeaseRow active = activeLeaseForUpdate(rentalItemId).orElse(null);
    if (active == null) {
      if (proof != null) {
        throw new AssetConflictException("Provided maintenance lease is no longer active");
      }
      return;
    }
    if (proof == null
        || !active.id().equals(proof.leaseId())
        || active.fencingToken() != proof.fencingToken()
        || !active.ownerType().equals(proof.ownerType().name())
        || !active.ownerId().equals(proof.ownerId().toString())
        || !isMaintenanceLeaseOwner(active.ownerType())) {
      throw new AssetConflictException("Cabin has an active operation lease owned by another workflow");
    }
  }

  /**
   * A prepared fence may outlive the maintenance operation lease that fenced its preparation.  A
   * later lease is still a competing workflow and must block APPLY; the absence of the original
   * lease is safe because this fence owns the cabin and content holds until terminalization.
   */
  private void assertFenceLeaseAllowsApply(
      UUID rentalItemId, MaintenancePropertyDispositionLeaseProof preparedProof) {
    LeaseRow active = activeLeaseForUpdate(rentalItemId).orElse(null);
    if (active == null) return;
    if (preparedProof == null
        || !active.id().equals(preparedProof.leaseId())
        || active.fencingToken() != preparedProof.fencingToken()
        || !active.ownerType().equals(preparedProof.ownerType().name())
        || !active.ownerId().equals(preparedProof.ownerId().toString())
        || !isMaintenanceLeaseOwner(active.ownerType())) {
      throw new AssetConflictException("Cabin acquired a conflicting operation lease after preparation");
    }
  }

  private Optional<LeaseRow> activeLeaseForUpdate(UUID rentalItemId) {
    List<LeaseRow> rows = jdbc.query(
        """
        select id,version,rental_item_id,owner_type,owner_id,fencing_token,state,expires_at
        from operation_lease
        where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
        order by fencing_token,id
        for update
        """,
        (rs, row) -> new LeaseRow(
            rs.getObject("id", UUID.class),
            rs.getLong("version"),
            rs.getObject("rental_item_id", UUID.class),
            rs.getString("owner_type"),
            rs.getString("owner_id"),
            rs.getLong("fencing_token"),
            rs.getString("state"),
            rs.getObject("expires_at", OffsetDateTime.class)),
        rentalItemId);
    if (rows.size() > 1) {
      throw new IllegalStateException("Active operation lease uniqueness is corrupted");
    }
    return rows.stream().findFirst();
  }

  private boolean hasActiveLease(UUID rentalItemId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from operation_lease
          where rental_item_id=? and state='ACTIVE' and expires_at>clock_timestamp()
        )
        """,
        Boolean.class,
        rentalItemId);
    return Boolean.TRUE.equals(active);
  }

  private static boolean isMaintenanceLeaseOwner(String ownerType) {
    return MaintenanceLeaseOwnerType.MAINTENANCE_ESTIMATE.name().equals(ownerType)
        || MaintenanceLeaseOwnerType.MAINTENANCE_REPAIR.name().equals(ownerType);
  }

  private MaintenancePropertyDispositionLeaseProof fenceLeaseProof(PropertyDispositionFence fence) {
    if (fence.getMaintenanceLeaseId() == null) return null;
    return new MaintenancePropertyDispositionLeaseProof(
        fence.getMaintenanceLeaseId(),
        fence.getMaintenanceLeaseFencingToken(),
        MaintenanceLeaseOwnerType.valueOf(fence.getMaintenanceLeaseOwnerType()),
        fence.getMaintenanceLeaseOwnerId());
  }

  private boolean hasPreparedFence(PropertyAssetKind assetKind, UUID assetId, UUID warehouseId) {
    Boolean active = jdbc.queryForObject(
        """
        select exists(
          select 1 from property_disposition_fence
          where asset_kind=? and asset_id=? and warehouse_id=? and state='PREPARED'
        )
        """,
        Boolean.class,
        assetKind.name(),
        assetId,
        warehouseId);
    return Boolean.TRUE.equals(active);
  }

  private List<ContentBalanceRow> cabinContents(UUID rentalItemId, UUID warehouseId) {
    return jdbc.query(
        """
        select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,balance.rental_item_id,
               balance.location_kind,balance.quantity,catalog.name
        from equipment_balance balance
        join equipment_catalog_item catalog on catalog.id=balance.equipment_id
        where balance.rental_item_id=? and balance.warehouse_id=?
          and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
          and balance.quantity>0
        order by balance.equipment_id,balance.id
        """,
        (rs, row) -> contentBalanceRow(rs),
        rentalItemId,
        warehouseId);
  }

  private List<ContentBalanceRow> cabinContentsForUpdate(UUID rentalItemId, UUID warehouseId) {
    return jdbc.query(
        """
        select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,balance.rental_item_id,
               balance.location_kind,balance.quantity,catalog.name
        from equipment_balance balance
        join equipment_catalog_item catalog on catalog.id=balance.equipment_id
        where balance.rental_item_id=? and balance.warehouse_id=?
          and balance.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
          and balance.quantity>0
        order by balance.equipment_id,balance.id
        for update of balance
        """,
        (rs, row) -> contentBalanceRow(rs),
        rentalItemId,
        warehouseId);
  }

  private StockEquipmentRow stockEquipment(UUID equipmentId, UUID warehouseId) {
    return jdbc.query(
            """
            select balance.id as balance_id,balance.version as balance_version,balance.equipment_id,
                   balance.warehouse_id,balance.quantity,catalog.name,catalog.version as catalog_version
            from equipment_balance balance
            join equipment_catalog_item catalog on catalog.id=balance.equipment_id
            where balance.equipment_id=? and balance.warehouse_id=?
              and balance.rental_item_id is null and balance.location_kind='STOCK'
            """,
            (rs, row) -> stockEquipmentRow(rs),
            equipmentId,
            warehouseId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment stock balance was not found"));
  }

  private StockEquipmentRow stockEquipmentForUpdate(UUID equipmentId, UUID warehouseId) {
    return jdbc.query(
            """
            select balance.id as balance_id,balance.version as balance_version,balance.equipment_id,
                   balance.warehouse_id,balance.quantity,catalog.name,catalog.version as catalog_version
            from equipment_balance balance
            join equipment_catalog_item catalog on catalog.id=balance.equipment_id
            where balance.equipment_id=? and balance.warehouse_id=?
              and balance.rental_item_id is null and balance.location_kind='STOCK'
            for update of balance
            """,
            (rs, row) -> stockEquipmentRow(rs),
            equipmentId,
            warehouseId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment stock balance was not found"));
  }

  private BalanceRow balanceForUpdate(UUID id) {
    return jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance where id=? for update
            """,
            (rs, row) -> balanceRow(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  private BalanceRow balanceRead(UUID id) {
    return jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity
            from equipment_balance where id=?
            """,
            (rs, row) -> balanceRow(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment balance was not found"));
  }

  private Optional<BalanceRow> findBalanceForUpdate(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind) {
    String rentalPredicate = rentalItemId == null ? "balance.rental_item_id is null" : "balance.rental_item_id=?";
    List<Object> arguments = new ArrayList<>(List.of(equipmentId, warehouseId, locationKind.name()));
    if (rentalItemId != null) arguments.add(rentalItemId);
    return jdbc.query(
            """
            select balance.id,balance.version,balance.equipment_id,balance.warehouse_id,
                   balance.rental_item_id,balance.location_kind,balance.quantity
            from equipment_balance balance
            where balance.equipment_id=? and balance.warehouse_id=? and balance.location_kind=?
              and %s
            for update
            """.formatted(rentalPredicate),
            (rs, row) -> balanceRow(rs),
            arguments.toArray())
        .stream()
        .findFirst();
  }

  private HoldRow holdForUpdate(UUID id) {
    return jdbc.query(
            """
            select id,version,equipment_id,warehouse_id,source_balance_id,owner_type,owner_id,quantity,
                   state,expires_at,committed_at,executed_at
            from equipment_allocation_hold where id=? for update
            """,
            (rs, row) -> holdRow(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Equipment hold was not found"));
  }

  private MovementResponse movementResponse(UUID movementId) {
    return jdbc.query(
            """
            select id,version,equipment_id,source_balance_id,target_balance_id,quantity,movement_kind,occurred_at
            from equipment_movement where id=?
            """,
            (rs, row) -> new MovementResponse(
                rs.getObject("id", UUID.class),
                rs.getLong("version"),
                rs.getObject("equipment_id", UUID.class),
                rs.getObject("source_balance_id", UUID.class),
                rs.getObject("target_balance_id", UUID.class),
                rs.getLong("quantity"),
                rs.getString("movement_kind"),
                rs.getObject("occurred_at", OffsetDateTime.class)),
            movementId)
        .stream()
        .findFirst()
        .orElseThrow(() -> new IllegalStateException("Property disposition movement is missing"));
  }

  private void decrementBalance(BalanceRow row, long amount, long expectedVersion) {
    int changed = jdbc.update(
        """
        update equipment_balance
        set quantity=quantity-?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=? and quantity>=?
        """,
        amount,
        row.id(),
        expectedVersion,
        amount);
    if (changed != 1) {
      throw new AssetConflictException("Source equipment balance changed concurrently");
    }
  }

  private void incrementBalance(BalanceRow row, long amount, long expectedVersion) {
    int changed = jdbc.update(
        """
        update equipment_balance
        set quantity=quantity+?,version=version+1,updated_at=clock_timestamp()
        where id=? and version=?
        """,
        amount,
        row.id(),
        expectedVersion);
    if (changed != 1) {
      throw new AssetConflictException("Terminal equipment balance changed concurrently");
    }
  }

  private MaintenancePropertyDispositionFence fenceResponse(PropertyDispositionFence fence) {
    List<MaintenancePropertyDispositionPreparedContent> lines = contents
        .findAllByDecisionIdOrderByEquipmentIdAsc(fence.getDecisionId())
        .stream()
        .map(
            line ->
                new MaintenancePropertyDispositionPreparedContent(
                    line.getEquipmentId(),
                    line.getSourceBalanceId(),
                    line.getExpectedBalanceVersion(),
                    line.getCurrentQuantity(),
                    line.getMoveQuantity(),
                    line.getDispositionQuantity()))
        .toList();
    return new MaintenancePropertyDispositionFence(
        fence.getDecisionId(),
        fence.getState(),
        fence.getRequestSha256(),
        fence.getWarehouseId(),
        fence.getAssetKind(),
        fence.getAssetId(),
        fence.getDisposition(),
        fence.getMaintenanceCustodyClaimId(),
        fence.getMaintenanceCustodyVersion(),
        lines,
        fence.getPreparedAt(),
        fence.getAppliedAt());
  }

  private MaintenancePropertyDispositionEffect effectResponse(PropertyDispositionFence fence) {
    PropertyDispositionEffect effect = effects.findByDecisionId(fence.getDecisionId())
        .orElseThrow(() -> new IllegalStateException("Applied property disposition lacks its immutable effect"));
    if (!Objects.equals(effect.getEffectId(), fence.getEffectId())
        || !effect.getResponseSha256().equals(AssetChecksum.sha256(effect.getResponseBody().getBytes(StandardCharsets.UTF_8)))) {
      throw new IllegalStateException("Stored property disposition effect is corrupt");
    }
    try {
      return mapper.readValue(effect.getResponseBody(), MaintenancePropertyDispositionEffect.class);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored property disposition effect is unreadable", exception);
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
      if (request.maintenanceCustodyClaimId() != null
          || request.maintenanceCustodyVersion() != null) {
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
      boolean custody = request.maintenanceCustodyClaimId() != null
          || request.maintenanceCustodyVersion() != null;
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
    return hash(
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

  private String hash(Object value) {
    try {
      return AssetChecksum.sha256(
          mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsBytes(value));
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Property disposition request cannot be fingerprinted", exception);
    }
  }

  private void writeAudit(
      UUID decisionId,
      String eventType,
      UUID actorSubjectId,
      String requestSha256,
      Map<String, ?> eventBody) {
    String body = canonicalJson(eventBody);
    jdbc.update(
        """
        insert into property_disposition_audit_event(
          event_id,decision_id,event_type,actor_subject_id,request_sha256,event_body,event_sha256,occurred_at)
        values (?,?,?,?,?,?::jsonb,?,clock_timestamp())
        """,
        UUID.randomUUID(),
        decisionId,
        eventType,
        actorSubjectId,
        requestSha256,
        body,
        AssetChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)));
  }

  private String canonicalJson(Object value) {
    try {
      String raw = mapper.writer().with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS).writeValueAsString(value);
      String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, raw);
      if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize property disposition JSON");
      return canonical;
    } catch (JacksonException exception) {
      throw new IllegalStateException("Property disposition JSON cannot be serialized", exception);
    }
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime timestamp = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    if (timestamp == null) throw new IllegalStateException("PostgreSQL did not return a timestamp");
    return timestamp;
  }

  private static Map<String, ?> rentalFact(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rentalItemId", item.getId().toString());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("status", item.getStatus().name());
    value.put("numberSha256", AssetChecksum.sha256(item.getNumber().getBytes(StandardCharsets.UTF_8)));
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
            (rs, row) -> rs.getObject("characteristic_id", UUID.class).toString(),
            item.getId()));
    value.put("linoleum", item.getLinoleum());
    value.put("generalComment", item.getGeneralComment());
    value.put("passport", jsonMap(item.getPassportJson()));
    value.put("tags", jsonList(item.getTagsJson()));
    return value;
  }

  private static Map<String, ?> balanceFact(BalanceRow row) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("balanceId", row.id().toString());
    value.put("equipmentId", row.equipmentId().toString());
    value.put("warehouseId", row.warehouseId().toString());
    value.put("rentalItemId", nullableUuid(row.rentalItemId()));
    value.put("locationKind", row.locationKind().name());
    value.put("quantity", row.quantity());
    return value;
  }

  private Map<String, ?> movementFact(MovementResponse value) {
    MovementContext context = movementContext(value.id());
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("movementId", value.id().toString());
    fact.put("equipmentId", value.equipmentId().toString());
    fact.put("sourceBalanceId", value.sourceBalanceId().toString());
    fact.put("targetBalanceId", value.targetBalanceId().toString());
    fact.put("quantity", value.quantity());
    fact.put("movementKind", value.kind());
    if (context.exact()) {
      fact.put("equipmentCategory", context.equipmentCategory());
      fact.put("sourceWarehouseId", context.sourceWarehouseId().toString());
      fact.put("sourceRentalItemId", nullableUuid(context.sourceRentalItemId()));
      fact.put("sourceLocationKind", context.sourceLocationKind());
      fact.put("targetWarehouseId", context.targetWarehouseId().toString());
      fact.put("targetRentalItemId", nullableUuid(context.targetRentalItemId()));
      fact.put("targetLocationKind", context.targetLocationKind());
    }
    return fact;
  }

  private MovementContext movementContext(UUID movementId) {
    return jdbc.query(
            """
            select equipment_category_snapshot,
                   source_warehouse_id,source_rental_item_id,source_location_kind,
                   target_warehouse_id,target_rental_item_id,target_location_kind,capture_origin
            from equipment_movement_context
            where movement_id=?
            """,
            (rs, row) ->
                new MovementContext(
                    rs.getString("equipment_category_snapshot"),
                    rs.getObject("source_warehouse_id", UUID.class),
                    rs.getObject("source_rental_item_id", UUID.class),
                    rs.getString("source_location_kind"),
                    rs.getObject("target_warehouse_id", UUID.class),
                    rs.getObject("target_rental_item_id", UUID.class),
                    rs.getString("target_location_kind"),
                    "AT_MOVEMENT".equals(rs.getString("capture_origin"))),
            movementId)
        .stream()
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalStateException(
                    "Equipment movement context is missing for " + movementId));
  }

  private static Map<String, ?> holdFact(HoldRow value) {
    return Map.of(
        "holdId", value.id().toString(),
        "equipmentId", value.equipmentId().toString(),
        "warehouseId", value.warehouseId().toString(),
        "quantity", value.quantity(),
        "state", value.state());
  }

  private static Map<String, ?> holdSnapshot(HoldRow value) {
    Map<String, Object> snapshot = new LinkedHashMap<>();
    snapshot.put("holdId", value.id().toString());
    snapshot.put("version", value.version());
    snapshot.put("equipmentId", value.equipmentId().toString());
    snapshot.put("warehouseId", value.warehouseId().toString());
    snapshot.put("ownerType", value.ownerType());
    snapshot.put("ownerId", value.ownerId());
    snapshot.put("sourceBalanceId", nullableUuid(value.sourceBalanceId()));
    snapshot.put("quantity", value.quantity());
    snapshot.put("state", value.state());
    snapshot.put("expiresAt", value.expiresAt().toString());
    snapshot.put("committedAt", value.committedAt() == null ? null : value.committedAt().toString());
    snapshot.put("executedAt", value.executedAt() == null ? null : value.executedAt().toString());
    return snapshot;
  }

  private Map<String, Object> jsonMap(String value) {
    try {
      return mapper.readValue(value, new TypeReference<Map<String, Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental-item passport is invalid", exception);
    }
  }

  private List<Object> jsonList(String value) {
    try {
      return mapper.readValue(value, new TypeReference<List<Object>>() {});
    } catch (JacksonException exception) {
      throw new IllegalStateException("Stored rental-item tags are invalid", exception);
    }
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

  private static String nullableUuid(UUID value) {
    return value == null ? null : value.toString();
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

  private static BalanceLocationKind cabinBalanceKind(RentalItem cabin) {
    return cabin.getStatus() == RentalItemStatus.RENTED
        ? BalanceLocationKind.CABIN_RENTED
        : BalanceLocationKind.CABIN_NON_RENTED;
  }

  private static void assertVersion(long actual, Long expected, String message) {
    if (expected == null || actual != expected) {
      throw new AssetConflictException(message);
    }
  }

  private static void requireIdentity(PropertyAssetKind kind, UUID assetId, UUID warehouseId) {
    if (kind == null || assetId == null || warehouseId == null) {
      throw new IllegalArgumentException("Property asset identity is required");
    }
  }

  private static void requireCommandIdentity(UUID subjectId, UUID decisionId, UUID idempotencyKey) {
    if (subjectId == null || decisionId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Property disposition command identity is required");
    }
  }

  private void advisoryLocks(Collection<String> values) {
    values.stream().filter(Objects::nonNull).distinct().sorted().forEach(this::advisoryLock);
  }

  private void advisoryLock(String value) {
    jdbc.query("select pg_advisory_xact_lock(hashtextextended(?, 0))", rs -> {}, value);
  }

  private static String rentalItemLockKey(UUID rentalItemId) {
    return "asset-rental-item:" + rentalItemId;
  }

  private static String leaseLockKey(UUID rentalItemId) {
    return "lease:" + rentalItemId;
  }

  private static String balanceLockKey(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind kind) {
    return "asset-balance:"
        + equipmentId
        + ':'
        + warehouseId
        + ':'
        + (rentalItemId == null ? "-" : rentalItemId)
        + ':'
        + kind.name();
  }

  private static Object[] concat(Collection<UUID> ids, Object... trailing) {
    List<Object> result = new ArrayList<>(ids.size() + trailing.length);
    result.addAll(ids);
    java.util.Collections.addAll(result, trailing);
    return result.toArray();
  }

  private static <T> T required(T value) {
    if (value == null) throw new IllegalStateException("Property disposition transaction did not return a result");
    return value;
  }

  private static ContentBalanceRow contentBalanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ContentBalanceRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getString("name"),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(rs.getString("location_kind")),
        rs.getLong("quantity"));
  }

  private static StockEquipmentRow stockEquipmentRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new StockEquipmentRow(
        rs.getObject("balance_id", UUID.class),
        rs.getLong("balance_version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getLong("quantity"),
        rs.getString("name"),
        rs.getLong("catalog_version"));
  }

  private static BalanceRow balanceRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new BalanceRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("rental_item_id", UUID.class),
        BalanceLocationKind.valueOf(rs.getString("location_kind")),
        rs.getLong("quantity"));
  }

  private static HoldRow holdRow(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new HoldRow(
        rs.getObject("id", UUID.class),
        rs.getLong("version"),
        rs.getObject("equipment_id", UUID.class),
        rs.getObject("warehouse_id", UUID.class),
        rs.getObject("source_balance_id", UUID.class),
        rs.getString("owner_type"),
        rs.getString("owner_id"),
        rs.getLong("quantity"),
        rs.getString("state"),
        rs.getObject("expires_at", OffsetDateTime.class),
        rs.getObject("committed_at", OffsetDateTime.class),
        rs.getObject("executed_at", OffsetDateTime.class));
  }

  private record NormalizedContent(
      UUID equipmentId, long expectedBalanceVersion, long currentQuantity, long moveQuantity) {}

  private record NormalizedPrepare(
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
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

  private record PrepareFingerprint(
      UUID decisionId,
      UUID warehouseId,
      PropertyAssetKind assetKind,
      UUID assetId,
      PropertyDispositionKind disposition,
      Long expectedAssetVersion,
      Long expectedSourceBalanceVersion,
      Long quantity,
      UUID maintenanceCustodyClaimId,
      Long maintenanceCustodyVersion,
      PropertyDispositionContentsMode contentsMode,
      List<NormalizedContent> contents,
      MaintenancePropertyDispositionLeaseProof authorizedMaintenanceLease) {}

  private record ContentBalanceRow(
      UUID id,
      long version,
      UUID equipmentId,
      String equipmentName,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {}

  private record StockEquipmentRow(
      UUID balanceId,
      long balanceVersion,
      UUID equipmentId,
      UUID warehouseId,
      long quantity,
      String equipmentName,
      long catalogVersion) {}

  private record BalanceRow(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {}

  private record HeldContent(ContentBalanceRow line, UUID holdId) {}

  private record PreparedSource(PropertyDispositionFenceContent line, BalanceRow balance) {}

  private record LeaseRow(
      UUID id,
      long version,
      UUID rentalItemId,
      String ownerType,
      String ownerId,
      long fencingToken,
      String state,
      OffsetDateTime expiresAt) {}

  private record HoldRow(
      UUID id,
      long version,
      UUID equipmentId,
      UUID warehouseId,
      UUID sourceBalanceId,
      String ownerType,
      String ownerId,
      long quantity,
      String state,
      OffsetDateTime expiresAt,
      OffsetDateTime committedAt,
      OffsetDateTime executedAt) {}

  private record ExecutedMovementProof(UUID reservationId, UUID movementId, long quantity) {}

  private record MovementContext(
      String equipmentCategory,
      UUID sourceWarehouseId,
      UUID sourceRentalItemId,
      String sourceLocationKind,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      String targetLocationKind,
      boolean exact) {}
}
