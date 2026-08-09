package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.*;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import tools.jackson.databind.JsonNode;

/**
 * Owns rental-item reads, interactive commands, notes and canonical status mutations.
 *
 * <p>Lease, logistics and maintenance use cases enter through their own collaborators, then call
 * the explicitly fenced package methods here for the persisted rental aggregate. This service
 * never creates a lease or applies a cross-domain effect itself.
 */
@Service
final class AssetRentalItemService {
  private final RentalItemRepository rentalItems;
  private final JdbcTemplate jdbc;
  private final AssetEventStore events;
  private final AssetIdempotencyStore idempotency;
  private final WarehouseRegistryClient warehouses;
  private final CabinCompositionService cabinComposition;
  private final AssetRentalProjectionService projections;
  private final AssetLeaseService leases;
  private final AssetEquipmentLedgerService ledger;
  private final AssetJsonCodec json;

  AssetRentalItemService(
      RentalItemRepository rentalItems,
      JdbcTemplate jdbc,
      AssetEventStore events,
      AssetIdempotencyStore idempotency,
      WarehouseRegistryClient warehouses,
      CabinCompositionService cabinComposition,
      AssetRentalProjectionService projections,
      AssetLeaseService leases,
      AssetEquipmentLedgerService ledger,
      AssetJsonCodec json) {
    this.rentalItems = rentalItems;
    this.jdbc = jdbc;
    this.events = events;
    this.idempotency = idempotency;
    this.warehouses = warehouses;
    this.cabinComposition = cabinComposition;
    this.projections = projections;
    this.leases = leases;
    this.ledger = ledger;
    this.json = json;
  }

  RentalItemPage list(
      UUID warehouseId,
      int page,
      int size,
      String search,
      java.util.Set<RentalItemStatus> excludedStatuses) {
    return projections.list(warehouseId, page, size, search, excludedStatuses);
  }

  RentalItemResponse rentalItem(UUID id) {
    return projections.response(id);
  }

  RentalItem require(UUID id) {
    return projections.require(id);
  }

  List<CabinCatalogValueResponse> maintenanceCabinCharacteristics() {
    return projections.maintenanceCabinCharacteristics();
  }

  AssetService.CreateResult<RentalItemResponse> create(
      UUID subjectId, UUID key, CreateRentalItemRequest request) {
    warehouses.requireIncoming(request.warehouseId());
    String hash = json.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "rental-item.create", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), RentalItemResponse.class), true);
    }
    CabinCompositionService.CabinSelection selection =
        cabinComposition.requireSelection(
            request.rentalTypeId(),
            request.dimensionId(),
            request.finishingId(),
            request.characteristicIds());
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    RentalItem candidate =
        RentalItem.create(
            request.warehouseId(),
            request.number(),
            selection.rentalTypeId(),
            selection.dimensionId(),
            selection.finishingId(),
            category.id(),
            category.name(),
            request.linoleum(),
            json.jsonObject(request.passport()),
            json.jsonArray(request.tags()));
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKey(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey())) {
      throw new AssetConflictException("Rental item number identity is already used in this warehouse");
    }
    RentalItem persisted = rentalItems.saveAndFlush(candidate);
    cabinComposition.replaceRentalItemCharacteristics(persisted.getId(), selection.characteristicIds());
    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        persisted.getId(),
        persisted.getVersion(),
        AssetEventType.RENTAL_ITEM_CREATED,
        projections.fact(persisted),
        projections.snapshot(persisted));
    RentalItemResponse response = projections.response(persisted);
    idempotency.store(subjectId, "rental-item.create", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  /**
   * Creates one cabin from a reviewed HTML import rather than from the strict public create flow.
   * Legacy rows may lack type, dimensions or category after importer validation has resolved all
   * supplied identifiers.
   */
  RentalItemResponse createFromHtmlImport(
      UUID warehouseId,
      String number,
      RentalItemStatus status,
      UUID rentalTypeId,
      UUID dimensionId,
      UUID finishingId,
      UUID categoryId,
      String category,
      List<UUID> characteristicIds,
      Boolean linoleum,
      Map<String, Object> passport) {
    if (warehouseId == null || status == null || characteristicIds == null) {
      throw new IllegalArgumentException("HTML import cabin identity is incomplete");
    }
    if (!isHtmlImportManualStatus(status)) {
      throw new IllegalArgumentException(
          "HTML import can only create SALE, USED_SALE, FREE, WAREHOUSE, or OWN_NEEDS cabins");
    }
    if (finishingId == null) {
      throw new IllegalArgumentException("HTML import finishing is required");
    }
    warehouses.requireIncoming(warehouseId);
    List<UUID> characteristics = List.copyOf(characteristicIds);
    RentalItem candidate =
        RentalItem.createFromHtmlImport(
            warehouseId,
            number,
            status,
            rentalTypeId,
            dimensionId,
            finishingId,
            categoryId,
            category,
            linoleum,
            json.jsonObject(passport),
            json.jsonArray(List.of()));
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKey(
        candidate.getWarehouseId(), candidate.getIdentityMatchKey())) {
      throw new AssetConflictException("Rental item number identity is already used in this warehouse");
    }
    RentalItem persisted = rentalItems.saveAndFlush(candidate);
    cabinComposition.replaceRentalItemCharacteristics(persisted.getId(), characteristics);
    events.initialize(
        AssetAggregateType.RENTAL_ITEM,
        persisted.getId(),
        persisted.getVersion(),
        AssetEventType.RENTAL_ITEM_CREATED,
        projections.fact(persisted),
        projections.snapshot(persisted));
    return projections.response(persisted);
  }

  RentalItemResponse updatePassport(UUID id, UpdatePassportRequest request) {
    leases.assertNoActive(id);
    RentalItem item = projections.require(id);
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedVersion());
    CabinCompositionService.CabinSelection selection =
        cabinComposition.requireSelection(
            request.rentalTypeId(),
            request.dimensionId(),
            request.finishingId(),
            request.characteristicIds());
    CabinCompositionService.CategorySelection category =
        cabinComposition.requireCategory(request.category());
    boolean passportChanged =
        item.changePassport(
            selection.rentalTypeId(),
            selection.dimensionId(),
            selection.finishingId(),
            category.id(),
            category.name(),
            request.linoleum(),
            json.jsonObject(request.passport()),
            json.jsonArray(request.tags()));
    boolean characteristicsChanged =
        cabinComposition.replaceRentalItemCharacteristics(item.getId(), selection.characteristicIds());
    if (!passportChanged && !characteristicsChanged) {
      return projections.response(item);
    }
    if (characteristicsChanged && !passportChanged) {
      item.touchActivity();
    }
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        request.expectedVersion(),
        AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED,
        projections.fact(saved),
        projections.snapshot(saved));
    return projections.response(saved);
  }

  RentalItemResponse updateStatus(UUID id, UpdateStatusRequest request) {
    return changeStatus(id, request.expectedVersion(), request.status(), false);
  }

  RentalItemResponse fencedStatus(UUID id, FencedStatusRequest request) {
    leases.lockRentalItem(id);
    leases.validate(id, request.leaseId(), request.fencingToken());
    return changeStatusLocked(id, request.expectedVersion(), request.status(), true);
  }

  RentalItemResponse updateGeneralComment(UUID id, UpdateGeneralCommentRequest request) {
    leases.assertNoActive(id);
    RentalItem item = projections.require(id);
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedVersion());
    if (!item.changeGeneralComment(request.comment())) {
      return projections.response(item);
    }
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        request.expectedVersion(),
        AssetEventType.RENTAL_ITEM_GENERAL_COMMENT_CHANGED,
        Map.of("rentalItemId", saved.getId().toString(), "commentRevision", saved.getVersion()),
        projections.snapshot(saved));
    return projections.response(saved);
  }

  AssetService.CreateResult<ManualNoteResponse> addManualNote(
      UUID subjectId, UUID key, UUID id, AddManualNoteRequest request) {
    String hash = json.hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "rental-item.manual-note", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), ManualNoteResponse.class), true);
    }
    leases.assertNoActive(id);
    RentalItem item = projections.require(id);
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedVersion());
    UUID noteId = UUID.randomUUID();
    item.touchActivity();
    RentalItem saved = rentalItems.saveAndFlush(item);
    jdbc.update(
        "insert into rental_item_note(id,rental_item_id,actor_subject_id,note_text,created_at) values (?, ?, ?, ?, clock_timestamp())",
        noteId,
        id,
        subjectId,
        request.text().trim());
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        id,
        request.expectedVersion(),
        AssetEventType.RENTAL_ITEM_MANUAL_NOTE_ADDED,
        Map.of("rentalItemId", id.toString(), "noteId", noteId.toString()),
        projections.snapshot(saved));
    ManualNoteResponse response = projections.manualNote(noteId);
    idempotency.store(subjectId, "rental-item.manual-note", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  List<ManualNoteResponse> manualNotes(UUID rentalItemId) {
    projections.require(rentalItemId);
    return projections.manualNotes(rentalItemId);
  }

  /**
   * Acquires or renews the generic operation lease after the caller's idempotency replay check.
   * The typed logistics and maintenance lease APIs use stricter owners in their own collaborators.
   */
  AssetService.CreateResult<OperationLeaseResponse> acquireLease(
      UUID subjectId, UUID key, AcquireOperationLeaseRequest request) {
    String hash = json.hash(request);
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.acquire", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), OperationLeaseResponse.class), true);
    }
    leases.lockRentalItemAndLease(request.rentalItemId());
    leases.assertNoActiveOrderReservation(
        request.rentalItemId(), "Reserved order unit cannot acquire an operation lease");
    RentalItem item = projections.require(request.rentalItemId());
    AssetLeaseService.assertVersion(item.getVersion(), request.expectedRentalItemVersion());
    AssetLeaseService.assertRentalItemAllowsLeaseEffects(item);
    leases.expire(request.rentalItemId());
    String ownerType = canonicalOwnerType(request.ownerType());
    String ownerId = canonicalOwnerId(request.ownerId());
    List<dev.buhanzaz.rwms.asset.domain.OperationLease> active =
        leases.activeForUpdate(request.rentalItemId());
    if (!active.isEmpty()) {
      dev.buhanzaz.rwms.asset.domain.OperationLease current = active.getFirst();
      if (!current.isOwnedBy(ownerType, ownerId)) {
        throw new AssetConflictException("Rental item already has an active operation lease");
      }
      OperationLeaseResponse renewed = leases.renewExisting(current);
      idempotency.store(subjectId, "operation-lease.acquire", key, hash, 200, renewed);
      return new AssetService.CreateResult<>(renewed, false);
    }
    OperationLeaseResponse response = leases.acquire(request.rentalItemId(), ownerType, ownerId, key);
    idempotency.store(subjectId, "operation-lease.acquire", key, hash, 201, response);
    return new AssetService.CreateResult<>(response, false);
  }

  AssetService.CreateResult<OperationLeaseResponse> renewLease(
      UUID subjectId, UUID key, UUID id, RenewOperationLeaseRequest request) {
    String hash = json.hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.renew", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), OperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = leases.renew(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "operation-lease.renew", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  AssetService.CreateResult<OperationLeaseResponse> releaseLease(
      UUID subjectId, UUID key, UUID id, ReleaseOperationLeaseRequest request) {
    String hash = json.hash(new ResourceCommand<>(id, request));
    Optional<JsonNode> replay = idempotency.replay(subjectId, "operation-lease.release", key, hash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(json.read(replay.get(), OperationLeaseResponse.class), true);
    }
    OperationLeaseResponse updated = leases.release(id, request.expectedVersion(), request.fencingToken());
    idempotency.store(subjectId, "operation-lease.release", key, hash, 200, updated);
    return new AssetService.CreateResult<>(updated, false);
  }

  /** Joins an order command to the canonical rental-item/lease lock order. */
  void lockOrderRentalItemForOrder(UUID rentalItemId) {
    leases.assertNoActive(rentalItemId);
  }

  /** Applies the asset-owned order booking state after the caller joined the canonical locks. */
  RentalItemResponse bookOrderRentalItem(UUID rentalItemId) {
    RentalItem item =
        rentalItems
            .findByIdForUpdate(rentalItemId)
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (item.getStatus() == RentalItemStatus.BOOKED) {
      return projections.response(item);
    }
    if (item.getStatus() != RentalItemStatus.FREE) {
      throw new AssetConflictException("Only a free rental item can be booked for an order");
    }
    return changeOrderBookingStatus(item, RentalItemStatus.BOOKED);
  }

  /** Releases only the order-owned booking state; later lifecycle states remain fenced. */
  RentalItemResponse releaseOrderBooking(UUID rentalItemId) {
    RentalItem item =
        rentalItems
            .findByIdForUpdate(rentalItemId)
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (item.getStatus() != RentalItemStatus.BOOKED) {
      return projections.response(item);
    }
    return changeOrderBookingStatus(item, RentalItemStatus.FREE);
  }

  void assertNumberAvailableInWarehouse(RentalItem item, UUID warehouseId) {
    if (rentalItems.existsByWarehouseIdAndIdentityMatchKeyAndIdNot(
        warehouseId, item.getIdentityMatchKey(), item.getId())) {
      throw new AssetConflictException(
          "Rental item number identity is already used in the destination warehouse");
    }
  }

  RentalItem requireForUpdate(UUID id) {
    return rentalItems
        .findByIdForUpdate(id)
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
  }

  RentalItemResponse changeStatusLocked(
      UUID id, Long expectedVersion, RentalItemStatus status, boolean fenced) {
    RentalItem item = projections.require(id);
    if (fenced && item.getStatus().isTerminalDispositionStatus() && status == item.getStatus()) {
      return projections.response(item);
    }
    AssetLeaseService.assertVersion(item.getVersion(), expectedVersion);
    if (fenced) {
      AssetLeaseService.assertRentalItemAllowsLeaseEffects(item);
    }
    RentalItemStatus previous = item.getStatus();
    boolean changed = fenced ? item.changeStatusUnderLease(status) : item.changeStatus(status);
    if (!changed) {
      return projections.response(item);
    }
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        expectedVersion,
        AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
        projections.fact(saved),
        projections.snapshot(saved));
    ledger.reclassifyCabinBalances(saved, previous);
    return projections.response(saved);
  }

  private RentalItemResponse changeStatus(
      UUID id, Long expectedVersion, RentalItemStatus status, boolean fenced) {
    leases.lockRentalItem(id);
    if (!fenced) {
      leases.assertNoActive(id);
      if (status != null) {
        leases.assertNoActiveOrderReservation(
            id, "Reserved order unit cannot change to an incompatible status");
      }
    }
    return changeStatusLocked(id, expectedVersion, status, fenced);
  }

  private RentalItemResponse changeOrderBookingStatus(RentalItem item, RentalItemStatus targetStatus) {
    long expectedVersion = item.getVersion();
    RentalItemStatus previous = item.getStatus();
    item.changeStatusUnderLease(targetStatus);
    RentalItem saved = rentalItems.saveAndFlush(item);
    events.append(
        AssetAggregateType.RENTAL_ITEM,
        saved.getId(),
        expectedVersion,
        AssetEventType.RENTAL_ITEM_STATUS_CHANGED,
        projections.fact(saved),
        projections.snapshot(saved));
    ledger.reclassifyCabinBalances(saved, previous);
    return projections.response(saved);
  }

  static boolean isHtmlImportManualStatus(RentalItemStatus status) {
    return status == RentalItemStatus.SALE
        || status == RentalItemStatus.USED_SALE
        || status == RentalItemStatus.FREE
        || status == RentalItemStatus.WAREHOUSE
        || status == RentalItemStatus.OWN_NEEDS;
  }

  private static String canonicalOwnerType(String value) {
    String result = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!result.matches("^[A-Z][A-Z0-9_]{0,63}$")) {
      throw new IllegalArgumentException("ownerType has invalid format");
    }
    return result;
  }

  private static String canonicalOwnerId(String value) {
    String result = value == null ? "" : value.trim();
    if (result.isEmpty() || result.length() > 128) {
      throw new IllegalArgumentException("ownerId has invalid format");
    }
    return result;
  }

  /** Canonical resource-and-payload envelope used to hash idempotent rental-item commands. */
  private record ResourceCommand<T>(UUID resourceId, T request) {}
}
