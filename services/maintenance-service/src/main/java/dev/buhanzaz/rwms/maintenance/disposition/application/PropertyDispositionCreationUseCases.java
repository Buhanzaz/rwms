package dev.buhanzaz.rwms.maintenance.disposition.application;

import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionLineInput;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CabinContentsDispositionPlanInput;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateCabinPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateEquipmentPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateInventoryLossDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreatePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.CreateResult;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.WriteOffRepairRequest;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionAssetKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentSnapshotLineDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecision;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionDecisionDraft;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.repository.PropertyDispositionDecisionRepository;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleOperations;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;

/**
 * Creates manual, repair-derived, and inventory-derived property disposition decisions.
 *
 * <p>Every remote warehouse admission and asset snapshot is deliberately completed before the
 * final local transaction acquires its advisory and aggregate locks.
 */
@Service
final class PropertyDispositionCreationUseCases {
  private final PropertyDispositionDecisionRepository decisions;
  private final PropertyDispositionCommandBoundary commands;
  private final PropertyDispositionDecisionPersistence persistence;
  private final PropertyDispositionReadProjection readProjection;
  private final PropertyDispositionRepairChain repairChain;
  private final PropertyDispositionActorCodec actors;
  private final MaintenanceDependencyGateway dependencies;
  private final WarehouseLifecycleOperations warehouseLifecycle;

  PropertyDispositionCreationUseCases(
      PropertyDispositionDecisionRepository decisions,
      PropertyDispositionCommandBoundary commands,
      PropertyDispositionDecisionPersistence persistence,
      PropertyDispositionReadProjection readProjection,
      PropertyDispositionRepairChain repairChain,
      PropertyDispositionActorCodec actors,
      MaintenanceDependencyGateway dependencies,
      WarehouseLifecycleOperations warehouseLifecycle) {
    this.decisions = decisions;
    this.commands = commands;
    this.persistence = persistence;
    this.readProjection = readProjection;
    this.repairChain = repairChain;
    this.actors = actors;
    this.dependencies = dependencies;
    this.warehouseLifecycle = warehouseLifecycle;
  }

  CreateResult createManual(
      UUID subjectId, UUID idempotencyKey, CreatePropertyDispositionRequest request) {
    require(subjectId, "Disposition subject is required");
    require(idempotencyKey, "Disposition idempotency key is required");
    require(request, "Disposition request is required");
    String requestHash = commands.hash(request);
    CreateResult replay = commands.execute(
        () -> {
          PropertyDispositionDecision existing = decisions
              .findByInitiatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey)
              .orElse(null);
          if (existing == null) {
            return null;
          }
          if (!existing.matchesManualReplay(subjectId, idempotencyKey, requestHash)) {
            throw conflict(
                "Idempotency key is already bound to a different property disposition request");
          }
          return new CreateResult(
              readProjection.response(existing, repairChain.repairChain(existing.getRootRepairId())),
              true);
        });
    if (replay != null) {
      return replay;
    }
    warehouseLifecycle.requireOutgoing(request.warehouseId());
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            gatewayAssetKind(request.assetKind()), request.assetId(), request.warehouseId());
    return commands.requiredResult(
        commands.execute(
            () -> createManualInTransaction(subjectId, idempotencyKey, request, snapshot)));
  }

  CreateResult createRepairWriteOff(
      UUID subjectId,
      UUID idempotencyKey,
      UUID repairId,
      UUID warehouseId,
      WriteOffRepairRequest request) {
    require(subjectId, "Disposition subject is required");
    require(idempotencyKey, "Disposition idempotency key is required");
    require(repairId, "Repair ID is required");
    require(warehouseId, "Warehouse ID is required");
    require(request, "Repair write-off request is required");
    MaintenanceRepair initial = repairChain.requireByIdAndWarehouseId(repairId, warehouseId);
    UUID rootRepairId = PropertyDispositionRepairChain.rootId(initial);
    CreateResult replay = commands.execute(
        () -> decisions
            .findByAssetKindAndRootRepairId(PropertyDispositionAssetKind.CABIN, rootRepairId)
            .map(
                existing ->
                    new CreateResult(
                        readProjection.response(existing, repairChain.repairChain(rootRepairId)), true))
            .orElse(null));
    if (replay != null) {
      return replay;
    }
    if (request.expectedVersion() == null || request.expectedVersion() != initial.getVersion()) {
      throw versionConflict("Repair version is stale");
    }
    MaintenanceRepair root = repairChain.requireById(rootRepairId);
    warehouseLifecycle.requireOutgoing(warehouseId);
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            root.getRentalItemId(),
            warehouseId);
    return commands.requiredResult(
        commands.execute(
            () ->
                createRepairWriteOffInTransaction(
                    subjectId, idempotencyKey, repairId, warehouseId, request, snapshot)));
  }

  CreateResult createInventoryLoss(
      UUID idempotencyKey, CreateInventoryLossDispositionRequest request) {
    require(idempotencyKey, "Inventory disposition idempotency key is required");
    require(request, "Inventory disposition request is required");
    String requestHash = commands.hash(request);
    CreateResult replay = commands.execute(
        () -> {
          PropertyDispositionDecision existing = decisions
              .findBySourceAndInventoryIdAndFindingId(
                  PropertyDispositionSource.INVENTORY,
                  request.inventorySessionId(),
                  request.findingId())
              .orElse(null);
          if (existing == null) {
            return null;
          }
          if (!existing.getRequestSha256().equals(requestHash)) {
            throw conflict("Inventory finding is already bound to a different loss disposition");
          }
          return new CreateResult(readProjection.response(existing, List.of()), true);
        });
    if (replay != null) {
      return replay;
    }
    warehouseLifecycle.requireOutgoing(request.warehouseId());
    MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot =
        dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT,
            request.equipmentId(),
            request.warehouseId());
    return commands.requiredResult(
        commands.execute(() -> createInventoryLossInTransaction(idempotencyKey, request, snapshot)));
  }

  private CreateResult createManualInTransaction(
      UUID subjectId,
      UUID idempotencyKey,
      CreatePropertyDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    commands.advisoryLock("property-disposition:manual:" + subjectId + ':' + idempotencyKey);
    String requestHash = commands.hash(request);
    PropertyDispositionDecision replay = decisions
        .findByInitiatedBySubjectIdAndIdempotencyKey(subjectId, idempotencyKey)
        .orElse(null);
    if (replay != null) {
      if (!replay.matchesManualReplay(subjectId, idempotencyKey, requestHash)) {
        throw conflict("Idempotency key is already bound to a different property disposition request");
      }
      return new CreateResult(
          readProjection.response(replay, repairChain.repairChain(replay.getRootRepairId())), true);
    }
    validateManualSnapshot(request, snapshot);
    PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
        manualDraft(subjectId, idempotencyKey, requestHash, request, snapshot));
    return created(decision);
  }

  private CreateResult createRepairWriteOffInTransaction(
      UUID subjectId,
      UUID idempotencyKey,
      UUID repairId,
      UUID warehouseId,
      WriteOffRepairRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    MaintenanceRepair requested = repairChain.requireByIdAndWarehouseId(repairId, warehouseId);
    UUID rootRepairId = PropertyDispositionRepairChain.rootId(requested);
    commands.advisoryLock("property-disposition:repair-root:" + rootRepairId);
    PropertyDispositionDecision existing = decisions
        .findByAssetKindAndRootRepairId(PropertyDispositionAssetKind.CABIN, rootRepairId)
        .orElse(null);
    if (existing != null) {
      return new CreateResult(
          readProjection.response(existing, repairChain.repairChain(rootRepairId)), true);
    }
    if (request.expectedVersion() == null || requested.getVersion() != request.expectedVersion()) {
      throw versionConflict("Repair version is stale");
    }
    List<MaintenanceRepair> chain = repairChain.repairChain(rootRepairId);
    MaintenanceRepair root = chain.stream()
        .filter(value -> value.getId().equals(rootRepairId))
        .findFirst()
        .orElseThrow(() -> new MaintenanceNotFoundException("Root repair not found"));
    repairChain.validateCanPropose(requested, root, chain);
    validateCabinSnapshot(snapshot, root.getRentalItemId(), warehouseId, request.contentsPlan(), true);
    String requestHash = commands.hash(
        new RepairRequestFingerprint(
            repairId,
            warehouseId,
            request.expectedVersion(),
            request.reason(),
            request.comment(),
            request.contentsPlan()));
    PropertyDispositionSource source = root.getEstimateId() == null
        ? PropertyDispositionSource.REPAIR
        : PropertyDispositionSource.ESTIMATE;
    PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            warehouseId,
            PropertyDispositionAssetKind.CABIN,
            root.getRentalItemId(),
            snapshot.assetDisplayName(),
            PropertyDispositionKind.WRITE_OFF,
            source,
            snapshot.version(),
            null,
            null,
            null,
            null,
            combinedReason(request.reason(), request.comment()),
            null,
            repairId,
            rootRepairId,
            null,
            null,
            subjectId,
            idempotencyKey,
            requestHash,
            actors.actorJson(),
            contentsMode(request.contentsPlan()),
            cabinDraftLines(snapshot, request.contentsPlan())));
    return created(decision);
  }

  private CreateResult createInventoryLossInTransaction(
      UUID ignoredIdempotencyKey,
      CreateInventoryLossDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    commands.advisoryLock(
        "property-disposition:inventory:" + request.inventorySessionId() + ':' + request.findingId());
    String requestHash = commands.hash(request);
    PropertyDispositionDecision existing = decisions
        .findBySourceAndInventoryIdAndFindingId(
            PropertyDispositionSource.INVENTORY, request.inventorySessionId(), request.findingId())
        .orElse(null);
    if (existing != null) {
      if (!existing.getRequestSha256().equals(requestHash)) {
        throw conflict("Inventory finding is already bound to a different loss disposition");
      }
      return new CreateResult(readProjection.response(existing, List.of()), true);
    }
    validateEquipmentSnapshot(
        snapshot,
        request.equipmentId(),
        request.warehouseId(),
        request.expectedAssetVersion(),
        request.expectedSourceBalanceVersion(),
        request.quantity());
    PropertyDispositionDecision decision = PropertyDispositionDecision.initiate(
        new PropertyDispositionDecisionDraft(
            request.warehouseId(),
            PropertyDispositionAssetKind.EQUIPMENT,
            request.equipmentId(),
            request.equipmentName().trim(),
            PropertyDispositionKind.LOSS,
            PropertyDispositionSource.INVENTORY,
            request.expectedAssetVersion(),
            request.expectedSourceBalanceVersion(),
            request.quantity(),
            null,
            null,
            request.reason(),
            request.evidenceLink(),
            null,
            null,
            request.inventorySessionId(),
            request.findingId(),
            null,
            null,
            requestHash,
            actors.systemActorJson(),
            null,
            List.of()));
    return created(decision);
  }

  private CreateResult created(PropertyDispositionDecision decision) {
    PropertyDispositionDecision saved = persistence.persistRequested(decision);
    return new CreateResult(
        readProjection.response(saved, repairChain.repairChain(saved.getRootRepairId())), false);
  }

  private PropertyDispositionDecisionDraft manualDraft(
      UUID subjectId,
      UUID idempotencyKey,
      String requestHash,
      CreatePropertyDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (request instanceof CreateCabinPropertyDispositionRequest cabin) {
      return new PropertyDispositionDecisionDraft(
          cabin.warehouseId(),
          PropertyDispositionAssetKind.CABIN,
          cabin.assetId(),
          snapshot.assetDisplayName(),
          cabin.disposition(),
          PropertyDispositionSource.MANUAL,
          cabin.expectedAssetVersion(),
          null,
          null,
          null,
          null,
          cabin.reason(),
          cabin.evidenceLink(),
          null,
          null,
          null,
          null,
          subjectId,
          idempotencyKey,
          requestHash,
          actors.actorJson(),
          contentsMode(cabin.contentsPlan()),
          cabinDraftLines(snapshot, cabin.contentsPlan()));
    }
    CreateEquipmentPropertyDispositionRequest equipment =
        (CreateEquipmentPropertyDispositionRequest) request;
    return new PropertyDispositionDecisionDraft(
        equipment.warehouseId(),
        PropertyDispositionAssetKind.EQUIPMENT,
        equipment.assetId(),
        snapshot.assetDisplayName(),
        equipment.disposition(),
        PropertyDispositionSource.MANUAL,
        equipment.expectedAssetVersion(),
        equipment.expectedSourceBalanceVersion(),
        equipment.quantity(),
        null,
        null,
        equipment.reason(),
        equipment.evidenceLink(),
        null,
        null,
        null,
        null,
        subjectId,
        idempotencyKey,
        requestHash,
        actors.actorJson(),
        null,
        List.of());
  }

  private void validateManualSnapshot(
      CreatePropertyDispositionRequest request,
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot) {
    if (request instanceof CreateCabinPropertyDispositionRequest cabin) {
      validateCabinSnapshot(snapshot, cabin.assetId(), cabin.warehouseId(), cabin.contentsPlan(), false);
      if (snapshot.version() != cabin.expectedAssetVersion()) {
        throw versionConflict("Cabin asset version is stale");
      }
      return;
    }
    if (!(request instanceof CreateEquipmentPropertyDispositionRequest equipment)) {
      throw new IllegalArgumentException("Unsupported property disposition request");
    }
    validateEquipmentSnapshot(
        snapshot,
        equipment.assetId(),
        equipment.warehouseId(),
        equipment.expectedAssetVersion(),
        equipment.expectedSourceBalanceVersion(),
        equipment.quantity());
  }

  private void validateCabinSnapshot(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot,
      UUID assetId,
      UUID warehouseId,
      CabinContentsDispositionPlanInput plan,
      boolean authorizedRepairLease) {
    if (snapshot.assetKind() != MaintenanceDependencyGateway.PropertyAssetKind.CABIN
        || !assetId.equals(snapshot.assetId())
        || !warehouseId.equals(snapshot.warehouseId())) {
      throw conflict("Cabin snapshot does not match the disposition identity");
    }
    requireDispositionAllowed(snapshot, authorizedRepairLease);
    if (snapshot.contents().isEmpty()) {
      if (plan != null) {
        throw conflict("An empty cabin must not receive a contents disposition plan");
      }
      return;
    }
    if (plan == null) {
      throw conflict("A non-empty cabin requires an explicit contents disposition plan");
    }
    Map<UUID, MaintenanceDependencyGateway.PropertyAssetContentSnapshot> observed = snapshot.contents()
        .stream()
        .collect(
            Collectors.toMap(
                MaintenanceDependencyGateway.PropertyAssetContentSnapshot::equipmentId,
                value -> value));
    Map<UUID, CabinContentsDispositionLineInput> submitted = plan.lines().stream()
        .collect(
            Collectors.toMap(
                CabinContentsDispositionLineInput::equipmentId,
                value -> value,
                (left, right) -> {
                  throw conflict("Cabin contents plan has duplicate equipment rows");
                }));
    if (!observed.keySet().equals(submitted.keySet())) {
      throw conflict("Cabin contents plan must cover exactly the current non-zero cabin contents");
    }
    for (Map.Entry<UUID, MaintenanceDependencyGateway.PropertyAssetContentSnapshot> entry
        : observed.entrySet()) {
      CabinContentsDispositionLineInput line = submitted.get(entry.getKey());
      if (line.expectedBalanceVersion() != entry.getValue().balanceVersion()
          || line.moveToStockQuantity() > entry.getValue().quantity()) {
        throw versionConflict("Cabin contents balance changed while the disposition was prepared");
      }
      if (plan.mode()
              == dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode
                  .DISPOSE_WITH_CABIN
          && line.moveToStockQuantity() != 0) {
        throw conflict("Dispose-with-cabin mode cannot assign furniture to stock movement");
      }
    }
  }

  private void validateEquipmentSnapshot(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot,
      UUID assetId,
      UUID warehouseId,
      long expectedAssetVersion,
      long expectedSourceBalanceVersion,
      long quantity) {
    if (snapshot.assetKind() != MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT
        || !assetId.equals(snapshot.assetId())
        || !warehouseId.equals(snapshot.warehouseId())) {
      throw conflict("Equipment snapshot does not match the disposition identity");
    }
    requireDispositionAllowed(snapshot, false);
    if (snapshot.version() != expectedAssetVersion
        || snapshot.sourceBalanceVersion() == null
        || snapshot.sourceBalanceVersion() != expectedSourceBalanceVersion) {
      throw versionConflict("Equipment asset or STOCK balance version is stale");
    }
    if (snapshot.quantity() == null || quantity < 1 || quantity > snapshot.quantity()) {
      throw conflict("Equipment disposition quantity exceeds the current STOCK balance");
    }
  }

  private static void requireDispositionAllowed(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot, boolean authorizedRepairLease) {
    if (!snapshot.dispositionAllowed()
        || snapshot.activeReservation()
        || snapshot.activeHold()
        || (!authorizedRepairLease && snapshot.activeLease())) {
      throw conflict("Property disposition is blocked by active asset operational state");
    }
  }

  private List<PropertyDispositionContentSnapshotLineDraft> cabinDraftLines(
      MaintenanceDependencyGateway.PropertyAssetSnapshot snapshot,
      CabinContentsDispositionPlanInput plan) {
    if (plan == null) {
      return List.of();
    }
    Map<UUID, CabinContentsDispositionLineInput> inputs = plan.lines().stream()
        .collect(Collectors.toMap(CabinContentsDispositionLineInput::equipmentId, value -> value));
    return snapshot.contents().stream()
        .sorted(Comparator.comparing(line -> line.equipmentId().toString()))
        .map(
            value -> {
              CabinContentsDispositionLineInput input = inputs.get(value.equipmentId());
              return new PropertyDispositionContentSnapshotLineDraft(
                  value.equipmentId(),
                  value.equipmentName(),
                  value.equipmentFormat(),
                  value.quantity(),
                  input.moveToStockQuantity(),
                  value.balanceVersion());
            })
        .toList();
  }

  private static dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionContentsMode
      contentsMode(CabinContentsDispositionPlanInput plan) {
    return plan == null ? null : plan.mode();
  }

  private static MaintenanceDependencyGateway.PropertyAssetKind gatewayAssetKind(
      PropertyDispositionAssetKind value) {
    return MaintenanceDependencyGateway.PropertyAssetKind.valueOf(value.name());
  }

  private static String combinedReason(String reason, String comment) {
    String normalizedReason = reason == null ? null : reason.trim();
    String normalizedComment = comment == null ? null : comment.trim();
    if (normalizedComment == null || normalizedComment.isEmpty()) {
      return normalizedReason;
    }
    String combined = normalizedReason + "\n" + normalizedComment;
    if (combined.length() > 2000) {
      throw new IllegalArgumentException("Repair disposition reason and comment exceed 2000 characters");
    }
    return combined;
  }

  private static void require(Object value, String message) {
    if (value == null) {
      throw new IllegalArgumentException(message);
    }
  }

  private static MaintenanceConflictException versionConflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_VERSION_CONFLICT", message);
  }

  private static MaintenanceConflictException conflict(String message) {
    return new MaintenanceConflictException("MAINTENANCE_STATE_CONFLICT", message);
  }

  /** Stable semantic fields for the repair write-off replay hash. */
  private record RepairRequestFingerprint(
      UUID repairId,
      UUID warehouseId,
      Long expectedVersion,
      String reason,
      String comment,
      CabinContentsDispositionPlanInput contentsPlan) {}
}
