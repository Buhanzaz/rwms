package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns maintenance-typed leases, fenced rental state effects and characteristic application.
 *
 * <p>Maintenance authorization and recovery are mediated by the caller's service credentials and
 * the idempotency store. This class validates the maintenance owner before it invokes the shared
 * rental aggregate mutation, keeping lease fencing and status changes in one transaction.
 */
@Service
final class AssetMaintenanceService {
  private final AssetRentalItemService rentals;
  private final AssetRentalProjectionService projections;
  private final AssetLeaseService leases;
  private final AssetEquipmentCatalogService catalog;
  private final MaintenanceFurnitureCustodyService furnitureCustody;
  private final CabinCompositionService cabinComposition;
  private final RentalItemRepository rentalItems;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final AssetJsonCodec json;

  AssetMaintenanceService(
      AssetRentalItemService rentals,
      AssetRentalProjectionService projections,
      AssetLeaseService leases,
      AssetEquipmentCatalogService catalog,
      MaintenanceFurnitureCustodyService furnitureCustody,
      CabinCompositionService cabinComposition,
      RentalItemRepository rentalItems,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      AssetJsonCodec json) {
    this.rentals = rentals;
    this.projections = projections;
    this.leases = leases;
    this.catalog = catalog;
    this.furnitureCustody = furnitureCustody;
    this.cabinComposition = cabinComposition;
    this.rentalItems = rentalItems;
    this.events = events;
    this.idempotency = idempotency;
    this.json = json;
  }

  AssetService.CreateResult<MaintenanceFurnitureEquipmentResponse> ensureFurnitureEquipment(
      UUID subjectId, UUID key, EnsureMaintenanceFurnitureEquipmentRequest request) {
    return catalog.ensureMaintenanceFurniture(subjectId, key, request);
  }

  List<MaintenanceFurnitureEquipmentResponse> furnitureEquipmentSnapshots(
      MaintenanceFurnitureEquipmentSnapshotRequest request) {
    return catalog.maintenanceFurnitureReferences(request.externalReferenceIds());
  }

  /**
   * Acquires maintenance custody only after the rental lock proves there is no active order
   * reservation and no live replacement furniture source hold. This makes the recognizable booked
   * replacement conflict and the post-movement retry one atomic pre-start fence.
   */
  AssetService.CreateResult<OperationLeaseResponse> acquireLease(
      UUID subjectId, UUID key, AcquireMaintenanceOperationLeaseRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "maintenance.operation-lease.acquire", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), OperationLeaseResponse.class), true);
    }
    leases.lockRentalItemAndLease(request.rentalItemId());
    leases.assertNoActiveOrderReservation(
        request.rentalItemId(),
        "BOOKED_UNIT_REPLACEMENT_REQUIRED",
        "Забронированную бытовку необходимо заменить в заказе перед складской операцией");
    leases.assertNoPendingReplacementFurnitureMovement(request.rentalItemId());
    RentalItem item = rentals.require(request.rentalItemId());
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    AssetLeaseService.assertRentalItemAllowsLeaseEffects(item);
    leases.expire(request.rentalItemId());
    if (!leases.activeForUpdate(request.rentalItemId()).isEmpty()) {
      throw new AssetConflictException(
          "Rental item already has an active operation lease; reacquisition is forbidden");
    }
    OperationLeaseResponse response =
        leases.acquire(request.rentalItemId(), request.ownerType().name(), request.ownerId().toString(), key);
    idempotency.store(subjectId, "maintenance.operation-lease.acquire", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  AssetService.CreateResult<OperationLeaseResponse> renewLease(
      UUID subjectId, UUID key, UUID id, RenewMaintenanceOperationLeaseRequest request) {
    OperationLease current = leases.requireForUpdate(id);
    assertOwner(current, request.ownerType(), request.ownerId());
    String hash = json.hash(new MaintenanceLeaseCommand<>(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "maintenance.operation-lease.renew", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), OperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = leases.renew(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "maintenance.operation-lease.renew", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  AssetService.CreateResult<OperationLeaseResponse> releaseLease(
      UUID subjectId, UUID key, UUID id, ReleaseMaintenanceOperationLeaseRequest request) {
    OperationLease current = leases.requireForUpdate(id);
    assertOwner(current, request.ownerType(), request.ownerId());
    String hash = json.hash(new MaintenanceLeaseCommand<>(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "maintenance.operation-lease.release", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), OperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = leases.release(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "maintenance.operation-lease.release", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  AssetService.CreateResult<RentalItemResponse> fencedStatus(
      UUID subjectId, UUID key, UUID id, MaintenanceFencedStatusRequest request) {
    leases.lockRentalItem(id);
    leases.assertNoActiveOrderReservation(
        id,
        "BOOKED_UNIT_REPLACEMENT_REQUIRED",
        "Забронированную бытовку необходимо заменить в заказе перед складской операцией");
    OperationLease lease = leases.validate(id, request.leaseId(), request.fencingToken());
    assertOwner(lease, request.ownerType(), request.ownerId());
    String hash = json.hash(new MaintenanceLeaseCommand<>(id, request));
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, "maintenance.rental-item.fenced-status", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), RentalItemResponse.class), true);
    }
    RentalItem current = rentals.require(id);
    if (current.getStatus() == RentalItemStatus.WRITTEN_OFF
        && request.action() == MaintenanceStatusAction.WRITE_OFF) {
      RentalItemResponse response = projections.response(current);
      idempotency.store(
          subjectId, "maintenance.rental-item.fenced-status", key, hash, 200, response);
      return new AssetService.CreateResult<>(response, false);
    }
    AssetLeaseService.assertRentalItemAllowsLeaseEffects(current);
    List<MaintenanceFurniturePendingReturn> pendingReturns = furniturePendingReturns(request);
    AssetLeaseService.assertVersion(current.getVersion(), request.expectedVersion());
    RentalItemStatus target =
        MaintenanceAssetTransitionPolicy.target(
            current.getStatus(),
            request.action(),
            request.ownerType(),
            request.ownerId(),
            request.linkedReturnEstimateId());
    furnitureCustody.selectFromCabin(
        subjectId,
        key,
        hash,
        current,
        request.ownerType(),
        request.ownerId(),
        pendingReturns);
    RentalItemResponse updated =
        rentals.changeStatusLocked(id, request.expectedVersion(), target, true);
    idempotency.store(subjectId, "maintenance.rental-item.fenced-status", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  /**
   * Appends an accepted maintenance characteristic without replacing current characteristics.
   * Aggregate lock, relation uniqueness and subject-bound idempotency make repeated acceptance
   * safe.
   */
  AssetService.CreateResult<MaintenanceCharacteristicApplicationResponse> applyCharacteristic(
      UUID subjectId, UUID key, UUID rentalItemId, UUID characteristicId) {
    MaintenanceCharacteristicCommand command =
        new MaintenanceCharacteristicCommand(rentalItemId, characteristicId);
    String requestHash = json.hash(command);
    Optional<JsonNode> replay =
        idempotency.replay(
            subjectId, "maintenance.rental-item.characteristic.apply", key, requestHash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), MaintenanceCharacteristicApplicationResponse.class), true);
    }
    leases.lockRentalItem(rentalItemId);
    RentalItem item = rentals.requireForUpdate(rentalItemId);
    AssetLeaseService.assertRentalItemAllowsLeaseEffects(item);
    long expectedVersion = item.getVersion();
    boolean added = cabinComposition.appendRentalItemCharacteristic(rentalItemId, characteristicId);
    RentalItem current = item;
    if (added) {
      item.touchActivity();
      current = rentalItems.saveAndFlush(item);
      events.append(
          AssetAggregateType.RENTAL_ITEM,
          current.getId(),
          expectedVersion,
          AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED,
          projections.fact(current),
          projections.snapshot(current));
    }
    MaintenanceCharacteristicApplicationResponse response =
        new MaintenanceCharacteristicApplicationResponse(
            rentalItemId, characteristicId, added, current.getVersion());
    idempotency.store(
        subjectId,
        "maintenance.rental-item.characteristic.apply",
        key,
        requestHash,
        200,
        response);
    return new AssetService.CreateResult<>(response, false);
  }

  private List<MaintenanceFurniturePendingReturn> furniturePendingReturns(
      MaintenanceFencedStatusRequest request) {
    if (request.furniturePendingReturns() == null) {
      throw new IllegalArgumentException("furniturePendingReturns is required");
    }
    Map<UUID, MaintenanceFurniturePendingReturn> unique = new LinkedHashMap<>();
    for (MaintenanceFurniturePendingReturn line : request.furniturePendingReturns()) {
      if (line == null || line.equipmentId() == null) {
        throw new IllegalArgumentException("Furniture pending-return equipmentId is required");
      }
      if (line.expectedSourceBalanceVersion() == null || line.expectedSourceBalanceVersion() < 0) {
        throw new IllegalArgumentException(
            "Furniture pending-return expectedSourceBalanceVersion is required");
      }
      if (line.quantity() < 1) {
        throw new IllegalArgumentException("Furniture pending-return quantity must be positive");
      }
      if (unique.putIfAbsent(line.equipmentId(), line) != null) {
        throw new IllegalArgumentException("Furniture pending returns must contain unique equipment items");
      }
    }
    if (!unique.isEmpty()
        && request.action() != MaintenanceStatusAction.QUEUE_FOR_REPAIR
        && request.action() != MaintenanceStatusAction.QUEUE_FOR_CAPITAL_REPAIR) {
      throw new AssetConflictException(
          "Furniture pending returns require a maintenance-owned queue action");
    }
    return unique.values().stream()
        .sorted(Comparator.comparing(line -> line.equipmentId().toString()))
        .toList();
  }

  private static void assertOwner(
      OperationLease lease, MaintenanceLeaseOwnerType ownerType, UUID ownerId) {
    if (ownerType == null || ownerId == null || !lease.isOwnedBy(ownerType.name(), ownerId.toString())) {
      throw new AssetConflictException("Operation lease belongs to another maintenance owner");
    }
  }

  /** Resource-bound maintenance lease material used for deterministic idempotency hashing. */
  private record MaintenanceLeaseCommand<T>(UUID resourceId, T request) {}

  /**
   * Canonical rental-item and characteristic identity used to fence replay of maintenance-applied
   * catalog changes.
   */
  private record MaintenanceCharacteristicCommand(UUID rentalItemId, UUID characteristicId) {}
}
