package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.OperationLease;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHoldState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemCharacteristic;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.domain.TransferUnitReservation;
import dev.buhanzaz.rwms.asset.domain.TransferUnitReservationState;
import dev.buhanzaz.rwms.asset.integration.warehouse.WarehouseRegistryClient;
import dev.buhanzaz.rwms.asset.mapper.TransferUnitReservationResponseMapper;
import dev.buhanzaz.rwms.asset.repository.CabinCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.CabinTypeDimensionRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemCharacteristicRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.repository.TransferUnitReservationRepository;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.JsonNode;

/**
 * Owns atomic cabin reservation, release and departure consumption for inter-warehouse transfers.
 *
 * <p>Every command first locks rental items in UUID order. That shared asset identity lock
 * serializes order, presentation, lease and transfer claims before the RESERVED availability fence
 * changes. Reservation rows retain immutable ownership after their one terminal transition.
 */
@Service
public class TransferUnitReservationService {
  private static final String CONFIRM_SCOPE = "logistics.transfer-unit-reservation.confirm";
  private static final String RELEASE_SCOPE = "logistics.transfer-unit-reservation.release";
  private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

  private final TransferUnitReservationRepository reservations;
  private final RentalItemRepository rentalItems;
  private final RentalItemCharacteristicRepository rentalItemCharacteristics;
  private final CabinCatalogItemRepository catalog;
  private final CabinTypeDimensionRepository typeDimensions;
  private final OrderUnitReservationRepository orderReservations;
  private final PresentationUnitHoldRepository presentationHolds;
  private final AssetRentalItemService rentals;
  private final AssetLeaseService leases;
  private final WarehouseRegistryClient warehouses;
  private final AssetIdempotencyStore idempotency;
  private final AssetJsonCodec json;
  private final TransferUnitReservationResponseMapper responses;

  public TransferUnitReservationService(
      TransferUnitReservationRepository reservations,
      RentalItemRepository rentalItems,
      RentalItemCharacteristicRepository rentalItemCharacteristics,
      CabinCatalogItemRepository catalog,
      CabinTypeDimensionRepository typeDimensions,
      OrderUnitReservationRepository orderReservations,
      PresentationUnitHoldRepository presentationHolds,
      AssetRentalItemService rentals,
      AssetLeaseService leases,
      WarehouseRegistryClient warehouses,
      AssetIdempotencyStore idempotency,
      AssetJsonCodec json,
      TransferUnitReservationResponseMapper responses) {
    this.reservations = reservations;
    this.rentalItems = rentalItems;
    this.rentalItemCharacteristics = rentalItemCharacteristics;
    this.catalog = catalog;
    this.typeDimensions = typeDimensions;
    this.orderReservations = orderReservations;
    this.presentationHolds = presentationHolds;
    this.rentals = rentals;
    this.leases = leases;
    this.warehouses = warehouses;
    this.idempotency = idempotency;
    this.json = json;
    this.responses = responses;
  }

  /** Confirms all selected cabins or rolls the complete transfer reservation batch back. */
  @Transactional
  public AssetService.CreateResult<TransferUnitReservationReceipt> confirm(
      UUID subjectId,
      UUID idempotencyKey,
      ConfirmTransferUnitReservationsRequest request) {
    requireCommandIdentity(subjectId, idempotencyKey);
    List<ConfirmTransferUnitReservationLine> lines = validConfirmLines(request);
    String requestHash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, CONFIRM_SCOPE, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), TransferUnitReservationReceipt.class), true);
    }

    warehouses.requireOutgoing(request.sourceWarehouseId());
    Map<UUID, RentalItem> items = lockRentalItems(lines.stream().map(
        ConfirmTransferUnitReservationLine::rentalItemId).toList());
    assertNoIncompatibleCustody(items.keySet());
    Map<UUID, Set<UUID>> characteristics = characteristics(items.keySet());
    Map<UUID, CabinCatalogItem> catalogItems = catalogItems(lines, items, characteristics);

    List<TransferUnitReservation> created = new ArrayList<>();
    for (ConfirmTransferUnitReservationLine line : lines) {
      RentalItem item = items.get(line.rentalItemId());
      assertConfirmable(request.sourceWarehouseId(), line, item);
      assertComposition(line, item, characteristics.getOrDefault(item.getId(), Set.of()), catalogItems);
      RentalItemResponse reserved =
          rentals.changeStatusLocked(
              item.getId(), line.expectedRentalItemVersion(), RentalItemStatus.RESERVED, true);
      created.add(
          reservations.saveAndFlush(
              TransferUnitReservation.confirm(
                  request.transferId(),
                  line.lineId(),
                  item.getId(),
                  request.sourceWarehouseId(),
                  reserved.version(),
                  subjectId,
                  idempotencyKey)));
    }

    TransferUnitReservationReceipt receipt =
        receipt(request.transferId(), created, currentVersions(created));
    idempotency.store(subjectId, CONFIRM_SCOPE, idempotencyKey, requestHash, 201, receipt);
    return new AssetService.CreateResult<>(receipt, false);
  }

  /** Releases only exact active owners; terminal exact rows remain immutable replay evidence. */
  @Transactional
  public AssetService.CreateResult<TransferUnitReservationReceipt> release(
      UUID subjectId,
      UUID idempotencyKey,
      ReleaseTransferUnitReservationsRequest request) {
    requireCommandIdentity(subjectId, idempotencyKey);
    List<ReleaseTransferUnitReservationLine> lines = validReleaseLines(request);
    String requestHash = json.hash(request);
    Optional<JsonNode> replay =
        idempotency.replay(subjectId, RELEASE_SCOPE, idempotencyKey, requestHash);
    if (replay.isPresent()) {
      return new AssetService.CreateResult<>(
          json.read(replay.get(), TransferUnitReservationReceipt.class), true);
    }

    Map<UUID, RentalItem> items =
        lockRentalItems(lines.stream().map(ReleaseTransferUnitReservationLine::rentalItemId).toList());
    Map<UUID, TransferUnitReservation> locked =
        reservations
            .findAllByIdInForUpdate(
                lines.stream().map(ReleaseTransferUnitReservationLine::reservationId).toList())
            .stream()
            .collect(Collectors.toMap(TransferUnitReservation::getId, Function.identity()));
    if (locked.size() != lines.size()) {
      throw new AssetNotFoundException("Transfer unit reservation was not found");
    }

    List<TransferUnitReservation> result = new ArrayList<>();
    for (ReleaseTransferUnitReservationLine line : lines) {
      TransferUnitReservation reservation = locked.get(line.reservationId());
      RentalItem item = items.get(line.rentalItemId());
      assertReleaseOwner(request.transferId(), line, reservation);
      AssetLeaseService.assertVersion(
          reservation.getVersion(), line.expectedReservationVersion());
      if (reservation.getState() == TransferUnitReservationState.CONSUMED) {
        throw new AssetConflictException(
            "Consumed transfer unit reservation cannot be released after departure");
      }
      if (reservation.getState() == TransferUnitReservationState.ACTIVE) {
        if (item.getStatus() != RentalItemStatus.RESERVED) {
          throw new AssetConflictException(
              "Transfer-reserved rental item no longer has RESERVED status");
        }
        RentalItemResponse released =
            rentals.changeStatusLocked(
                item.getId(), item.getVersion(), RentalItemStatus.FREE, true);
        reservation.release(
            request.transferId(),
            line.lineId(),
            line.rentalItemId(),
            released.version(),
            subjectId,
            idempotencyKey);
        reservation = reservations.saveAndFlush(reservation);
      }
      result.add(reservation);
    }

    TransferUnitReservationReceipt receipt =
        receipt(request.transferId(), result, currentVersions(result));
    idempotency.store(subjectId, RELEASE_SCOPE, idempotencyKey, requestHash, 200, receipt);
    return new AssetService.CreateResult<>(receipt, false);
  }

  /**
   * Converts the exact active reservation back to FREE inside the caller's fenced departure
   * transaction, then marks its history CONSUMED. The caller already owns the rental-item lock.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  RentalItem consumeForDeparture(
      UUID subjectId,
      UUID idempotencyKey,
      RentalItem item,
      UUID transferId,
      UUID lineId) {
    requireCommandIdentity(subjectId, idempotencyKey);
    Objects.requireNonNull(item, "item");
    TransferUnitReservation reservation =
        reservations
            .findOwnedForUpdate(transferId, lineId, item.getId())
            .orElseThrow(
                () ->
                    new AssetConflictException(
                        "Rental item is not reserved by this transfer document line"));
    if (reservation.getState() != TransferUnitReservationState.ACTIVE) {
      throw new AssetConflictException("Transfer unit reservation is no longer active");
    }
    if (item.getStatus() != RentalItemStatus.RESERVED) {
      throw new AssetConflictException("Transfer unit reservation does not fence RESERVED stock");
    }
    RentalItemResponse released =
        rentals.changeStatusLocked(
            item.getId(), item.getVersion(), RentalItemStatus.FREE, true);
    reservation.consume(
        transferId,
        lineId,
        item.getId(),
        released.version(),
        subjectId,
        idempotencyKey);
    reservations.saveAndFlush(reservation);
    return rentals.require(item.getId());
  }

  private void assertNoIncompatibleCustody(Collection<UUID> rentalItemIds) {
    List<UUID> sortedIds = rentalItemIds.stream().sorted(UUID_ORDER).toList();
    if (!reservations
        .findAllByRentalItemIdsAndStateForUpdate(
            sortedIds, TransferUnitReservationState.ACTIVE)
        .isEmpty()) {
      throw new AssetConflictException("Rental item is reserved by another transfer");
    }
    if (!orderReservations
        .findAllByRentalItemIdsAndStateForUpdate(sortedIds, OrderUnitReservationState.ACTIVE)
        .isEmpty()) {
      throw new AssetConflictException("Rental item is reserved by a rental order");
    }
    OffsetDateTime timestamp = OffsetDateTime.now(ZoneOffset.UTC);
    boolean held =
        presentationHolds
            .findAllByRentalItemIdsAndStateForUpdate(
                sortedIds, PresentationUnitHoldState.ACTIVE)
            .stream()
            .anyMatch(hold -> hold.getExpiresAt().isAfter(timestamp));
    if (held) {
      throw new AssetConflictException("Rental item has an active photo presentation hold");
    }
    for (UUID rentalItemId : sortedIds) {
      leases.expire(rentalItemId);
      List<OperationLease> activeLeases = leases.activeForUpdate(rentalItemId);
      if (!activeLeases.isEmpty()) {
        throw new AssetConflictException("Rental item has an active operation lease");
      }
      if (reservations.existsLiveInventoryCapture(rentalItemId)) {
        throw new AssetConflictException("Rental item belongs to an active inventory capture");
      }
      if (reservations.existsPreparedDisposition(rentalItemId)) {
        throw new AssetConflictException("Rental item has a prepared maintenance disposition");
      }
    }
  }

  private Map<UUID, RentalItem> lockRentalItems(Collection<UUID> ids) {
    List<UUID> sortedIds = ids.stream().distinct().sorted(UUID_ORDER).toList();
    List<RentalItem> locked = rentalItems.findAllByIdInForUpdate(sortedIds);
    if (locked.size() != sortedIds.size()) {
      throw new AssetNotFoundException("Rental item was not found");
    }
    return locked.stream()
        .collect(
            Collectors.toMap(
                RentalItem::getId,
                Function.identity(),
                (left, right) -> left,
                LinkedHashMap::new));
  }

  private Map<UUID, Set<UUID>> characteristics(Collection<UUID> rentalItemIds) {
    Map<UUID, Set<UUID>> result = new HashMap<>();
    for (RentalItemCharacteristic link :
        rentalItemCharacteristics
            .findAllByRentalItemIdInOrderByRentalItemIdAscSortOrderAscIdAsc(rentalItemIds)) {
      result
          .computeIfAbsent(link.getRentalItemId(), ignored -> new LinkedHashSet<>())
          .add(link.getCharacteristicId());
    }
    return result;
  }

  private Map<UUID, CabinCatalogItem> catalogItems(
      List<ConfirmTransferUnitReservationLine> lines,
      Map<UUID, RentalItem> items,
      Map<UUID, Set<UUID>> characteristics) {
    Set<UUID> ids = new HashSet<>();
    for (ConfirmTransferUnitReservationLine line : lines) {
      ids.add(line.rentalTypeId());
      add(ids, line.dimensionId());
      add(ids, line.finishingId());
      ids.addAll(line.characteristicIds());
      RentalItem item = items.get(line.rentalItemId());
      add(ids, item.getRentalTypeId());
      add(ids, item.getDimensionId());
      add(ids, item.getFinishingId());
      ids.addAll(characteristics.getOrDefault(item.getId(), Set.of()));
    }
    return catalog.findAllByIdIn(ids).stream()
        .collect(Collectors.toMap(CabinCatalogItem::getId, Function.identity()));
  }

  private void assertConfirmable(
      UUID sourceWarehouseId,
      ConfirmTransferUnitReservationLine line,
      RentalItem item) {
    if (!sourceWarehouseId.equals(item.getWarehouseId())) {
      throw new AssetConflictException(
          "Rental item is not physically located at the transfer source warehouse");
    }
    if (item.getStatus() != RentalItemStatus.FREE) {
      throw new AssetConflictException("Only a FREE rental item can be transfer-reserved");
    }
    AssetLeaseService.assertVersion(item.getVersion(), line.expectedRentalItemVersion());
  }

  private void assertComposition(
      ConfirmTransferUnitReservationLine line,
      RentalItem item,
      Set<UUID> actualCharacteristicIds,
      Map<UUID, CabinCatalogItem> catalogItems) {
    requireActiveCatalog(item.getRentalTypeId(), CabinCatalogKind.TYPE, catalogItems, "rental item type");
    if (item.getDimensionId() != null) {
      requireActiveCatalog(
          item.getDimensionId(), CabinCatalogKind.DIMENSION, catalogItems, "rental item dimension");
      if (!typeDimensions.existsByCabinTypeIdAndDimensionId(
          item.getRentalTypeId(), item.getDimensionId())) {
        throw new AssetConflictException(
            "Rental item type and dimension are not an active catalog combination");
      }
    }
    if (item.getFinishingId() != null) {
      requireActiveCatalog(
          item.getFinishingId(), CabinCatalogKind.FINISHING, catalogItems, "rental item finishing");
    }
    for (UUID characteristicId : actualCharacteristicIds) {
      requireActiveCatalog(
          characteristicId,
          CabinCatalogKind.CHARACTERISTIC,
          catalogItems,
          "rental item characteristic");
    }

    requireActiveCatalog(
        line.rentalTypeId(), CabinCatalogKind.TYPE, catalogItems, "required rental item type");
    if (line.dimensionId() != null) {
      requireActiveCatalog(
          line.dimensionId(),
          CabinCatalogKind.DIMENSION,
          catalogItems,
          "required rental item dimension");
    }
    if (line.finishingId() != null) {
      requireActiveCatalog(
          line.finishingId(),
          CabinCatalogKind.FINISHING,
          catalogItems,
          "required rental item finishing");
    }
    for (UUID characteristicId : line.characteristicIds()) {
      requireActiveCatalog(
          characteristicId,
          CabinCatalogKind.CHARACTERISTIC,
          catalogItems,
          "required rental item characteristic");
    }

    if (!Objects.equals(item.getRentalTypeId(), line.rentalTypeId())) {
      throw new AssetConflictException("Rental item type does not match the transfer requirement");
    }
    if (line.dimensionId() != null
        && !Objects.equals(item.getDimensionId(), line.dimensionId())) {
      throw new AssetConflictException(
          "Rental item dimension does not match the transfer requirement");
    }
    if (line.finishingId() != null
        && !Objects.equals(item.getFinishingId(), line.finishingId())) {
      throw new AssetConflictException(
          "Rental item finishing does not match the transfer requirement");
    }
    if (!actualCharacteristicIds.equals(Set.copyOf(line.characteristicIds()))) {
      throw new AssetConflictException(
          "Rental item characteristics do not match the transfer requirement");
    }
    if (line.linoleum() != null && !Objects.equals(item.getLinoleum(), line.linoleum())) {
      throw new AssetConflictException(
          "Rental item linoleum option does not match the transfer requirement");
    }
  }

  private static void requireActiveCatalog(
      UUID id,
      CabinCatalogKind expectedKind,
      Map<UUID, CabinCatalogItem> catalogItems,
      String label) {
    CabinCatalogItem item = id == null ? null : catalogItems.get(id);
    if (item == null) {
      throw new AssetConflictException(label + " was not found in the cabin catalog");
    }
    if (item.getKind() != expectedKind || !item.isActive()) {
      throw new AssetConflictException(label + " is inactive or has an incompatible catalog kind");
    }
  }

  private static List<ConfirmTransferUnitReservationLine> validConfirmLines(
      ConfirmTransferUnitReservationsRequest request) {
    if (request == null
        || request.transferId() == null
        || request.sourceWarehouseId() == null
        || request.lines() == null
        || request.lines().isEmpty()
        || request.lines().size() > 100) {
      throw new IllegalArgumentException("Transfer unit reservation batch is invalid");
    }
    Set<UUID> lineIds = new HashSet<>();
    Set<UUID> rentalItemIds = new HashSet<>();
    for (ConfirmTransferUnitReservationLine line : request.lines()) {
      if (line == null
          || line.lineId() == null
          || line.rentalItemId() == null
          || line.expectedRentalItemVersion() < 0
          || line.rentalTypeId() == null
          || line.characteristicIds() == null
          || line.characteristicIds().size() > 100
          || line.characteristicIds().stream().anyMatch(Objects::isNull)
          || line.characteristicIds().size() != new HashSet<>(line.characteristicIds()).size()
          || !lineIds.add(line.lineId())
          || !rentalItemIds.add(line.rentalItemId())) {
        throw new IllegalArgumentException("Transfer unit reservation line is invalid or duplicated");
      }
    }
    return request.lines().stream()
        .sorted(
            Comparator.comparing(
                    ConfirmTransferUnitReservationLine::rentalItemId, UUID_ORDER)
                .thenComparing(ConfirmTransferUnitReservationLine::lineId, UUID_ORDER))
        .toList();
  }

  private static List<ReleaseTransferUnitReservationLine> validReleaseLines(
      ReleaseTransferUnitReservationsRequest request) {
    if (request == null
        || request.transferId() == null
        || request.lines() == null
        || request.lines().isEmpty()
        || request.lines().size() > 100) {
      throw new IllegalArgumentException("Transfer unit reservation release batch is invalid");
    }
    Set<UUID> reservationIds = new HashSet<>();
    Set<UUID> lineIds = new HashSet<>();
    Set<UUID> rentalItemIds = new HashSet<>();
    for (ReleaseTransferUnitReservationLine line : request.lines()) {
      if (line == null
          || line.reservationId() == null
          || line.lineId() == null
          || line.rentalItemId() == null
          || line.expectedReservationVersion() < 0
          || !reservationIds.add(line.reservationId())
          || !lineIds.add(line.lineId())
          || !rentalItemIds.add(line.rentalItemId())) {
        throw new IllegalArgumentException("Transfer unit reservation release line is invalid or duplicated");
      }
    }
    return request.lines().stream()
        .sorted(
            Comparator.comparing(
                    ReleaseTransferUnitReservationLine::rentalItemId, UUID_ORDER)
                .thenComparing(ReleaseTransferUnitReservationLine::reservationId, UUID_ORDER))
        .toList();
  }

  private static void assertReleaseOwner(
      UUID transferId,
      ReleaseTransferUnitReservationLine line,
      TransferUnitReservation reservation) {
    if (reservation == null
        || !reservation.isOwnedBy(transferId, line.lineId(), line.rentalItemId())) {
      throw new AssetConflictException(
          "Transfer unit reservation belongs to another transfer document line");
    }
  }

  private TransferUnitReservationReceipt receipt(
      UUID transferId,
      Collection<TransferUnitReservation> reservations,
      Map<UUID, Long> currentVersions) {
    List<TransferUnitReservationLineReceipt> lines =
        reservations.stream()
            .sorted(Comparator.comparing(TransferUnitReservation::getLineId, UUID_ORDER))
            .map(
                reservation ->
                    responses.toReceipt(
                        reservation, currentVersions.get(reservation.getRentalItemId())))
            .toList();
    return new TransferUnitReservationReceipt(transferId, lines);
  }

  private Map<UUID, Long> currentVersions(
      Collection<TransferUnitReservation> reservationRows) {
    List<UUID> ids =
        reservationRows.stream()
            .map(TransferUnitReservation::getRentalItemId)
            .distinct()
            .sorted(UUID_ORDER)
            .toList();
    Map<UUID, Long> result = new HashMap<>();
    for (RentalItem item : rentalItems.findAllById(ids)) {
      result.put(item.getId(), item.getVersion());
    }
    if (result.size() != ids.size()) {
      throw new AssetNotFoundException("Rental item was not found");
    }
    return Map.copyOf(result);
  }

  private static void requireCommandIdentity(UUID subjectId, UUID idempotencyKey) {
    if (subjectId == null || idempotencyKey == null) {
      throw new IllegalArgumentException("Subject and Idempotency-Key are required");
    }
  }

  private static void add(Set<UUID> values, UUID value) {
    if (value != null) values.add(value);
  }
}
