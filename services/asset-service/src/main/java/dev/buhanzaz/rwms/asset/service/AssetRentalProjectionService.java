package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.AssetApiModels.ActiveOrderReservationResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.ManualNoteResponse;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemPage;
import static dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;

import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.mapper.OrderAssetResponseMapper;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;

/**
 * Builds asset-owned rental-item read models and replay facts from persisted state.
 *
 * <p>This collaborator does not mutate rental, equipment, reservation, lease or event state. Its
 * mapping is shared by command owners so every public read and emitted rental snapshot has the
 * same authoritative shape.
 */
@Service
final class AssetRentalProjectionService {
  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository orderUnitReservations;
  private final JdbcTemplate jdbc;
  private final CabinCompositionService cabinComposition;
  private final OrderAssetResponseMapper orderAssetMapper;
  private final AssetJsonCodec json;

  AssetRentalProjectionService(
      RentalItemRepository rentalItems,
      OrderUnitReservationRepository orderUnitReservations,
      JdbcTemplate jdbc,
      CabinCompositionService cabinComposition,
      OrderAssetResponseMapper orderAssetMapper,
      AssetJsonCodec json) {
    this.rentalItems = rentalItems;
    this.orderUnitReservations = orderUnitReservations;
    this.jdbc = jdbc;
    this.cabinComposition = cabinComposition;
    this.orderAssetMapper = orderAssetMapper;
    this.json = json;
  }

  RentalItemPage list(
      UUID warehouseId,
      int page,
      int size,
      String search,
      java.util.Set<RentalItemStatus> excludedStatuses) {
    if (page < 0 || size < 1 || size > 200) {
      throw new IllegalArgumentException("Invalid page request");
    }
    String needle = search == null ? "" : search.trim().toUpperCase(java.util.Locale.ROOT);
    java.util.Set<RentalItemStatus> exclusions =
        excludedStatuses == null ? java.util.Set.of() : java.util.Set.copyOf(excludedStatuses);
    PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.ASC, "number", "id"));
    var result =
        exclusions.isEmpty()
            ? rentalItems.findPublicPage(warehouseId, needle, pageable)
            : rentalItems.findPublicPageExcludingStatuses(warehouseId, exclusions, needle, pageable);
    List<RentalItem> values = result.getContent();
    Map<UUID, CabinCompositionService.CabinComposition> compositions =
        cabinComposition.compositionsFor(values);
    Map<UUID, List<EquipmentContentResponse>> contentByRentalItem =
        contentsFor(values.stream().map(RentalItem::getId).toList());
    Map<UUID, ActiveOrderReservationResponse> activeReservations =
        values.isEmpty()
            ? Map.of()
            : orderUnitReservations
                .findAllByRentalItemIdInAndState(
                    values.stream().map(RentalItem::getId).toList(),
                    OrderUnitReservationState.ACTIVE)
                .stream()
                .collect(
                    java.util.stream.Collectors.toMap(
                        OrderUnitReservation::getRentalItemId,
                        orderAssetMapper::toActiveOrderReservation));
    List<RentalItemResponse> content =
        values.stream()
            .map(
                item ->
                    response(
                        item,
                        compositions.get(item.getId()),
                        contentByRentalItem.getOrDefault(item.getId(), List.of()),
                        activeReservations.get(item.getId())))
            .toList();
    return new RentalItemPage(
        content,
        result.getNumber(),
        result.getSize(),
        result.getTotalElements(),
        result.getTotalPages());
  }

  RentalItem require(UUID id) {
    return rentalItems
        .findById(id)
        .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
  }

  RentalItemResponse response(UUID id) {
    return response(require(id));
  }

  RentalItemResponse response(RentalItem item) {
    return response(item, cabinComposition.compositionsFor(List.of(item)).get(item.getId()));
  }

  RentalItemResponse response(
      RentalItem item, CabinCompositionService.CabinComposition composition) {
    ActiveOrderReservationResponse activeOrderReservation =
        orderUnitReservations
            .findByRentalItemIdAndState(item.getId(), OrderUnitReservationState.ACTIVE)
            .map(orderAssetMapper::toActiveOrderReservation)
            .orElse(null);
    return response(item, composition, contents(item.getId()), activeOrderReservation);
  }

  List<CabinCatalogValueResponse> maintenanceCabinCharacteristics() {
    return cabinComposition.activeCharacteristics();
  }

  List<ManualNoteResponse> manualNotes(UUID rentalItemId) {
    return jdbc.query(
        "select id,rental_item_id,note_text,created_at from rental_item_note where rental_item_id=? order by created_at,id",
        (rs, row) -> manualNote(rs),
        rentalItemId);
  }

  ManualNoteResponse manualNote(UUID id) {
    return jdbc.query(
            "select id,rental_item_id,note_text,created_at from rental_item_note where id=?",
            (rs, row) -> manualNote(rs),
            id)
        .stream()
        .findFirst()
        .orElseThrow(() -> new AssetNotFoundException("Rental-item note was not found"));
  }

  List<EquipmentContentResponse> contents(UUID rentalItemId) {
    return jdbc.query(
        """
        select b.equipment_id,c.name,b.quantity,b.location_kind from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id=? and b.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED') and b.quantity>0 order by c.name,c.id
        """,
        (rs, row) ->
            new EquipmentContentResponse(
                rs.getObject("equipment_id", UUID.class),
                rs.getString("name"),
                rs.getLong("quantity"),
                BalanceLocationKind.valueOf(rs.getString("location_kind"))),
        rentalItemId);
  }

  Map<UUID, List<EquipmentContentResponse>> contentsFor(Collection<UUID> rentalItemIds) {
    if (rentalItemIds == null || rentalItemIds.isEmpty()) {
      return Map.of();
    }
    List<UUID> ids =
        rentalItemIds.stream().filter(java.util.Objects::nonNull).distinct().toList();
    if (ids.isEmpty()) {
      return Map.of();
    }
    String placeholders = String.join(",", java.util.Collections.nCopies(ids.size(), "?"));
    Map<UUID, List<EquipmentContentResponse>> result = new LinkedHashMap<>();
    jdbc.query(
        """
        select b.rental_item_id,b.equipment_id,c.name,b.quantity,b.location_kind
        from equipment_balance b
        join equipment_catalog_item c on c.id=b.equipment_id
        where b.rental_item_id in (%s)
          and b.location_kind in ('CABIN_NON_RENTED','CABIN_RENTED')
          and b.quantity>0
        order by b.rental_item_id,c.name,c.id
        """.formatted(placeholders),
        rs -> {
          UUID rentalItemId = rs.getObject("rental_item_id", UUID.class);
          result
              .computeIfAbsent(rentalItemId, ignored -> new ArrayList<>())
              .add(
                  new EquipmentContentResponse(
                      rs.getObject("equipment_id", UUID.class),
                      rs.getString("name"),
                      rs.getLong("quantity"),
                      BalanceLocationKind.valueOf(rs.getString("location_kind"))));
        },
        ids.toArray());
    return result.entrySet().stream()
        .collect(
            java.util.stream.Collectors.toUnmodifiableMap(
                Map.Entry::getKey, entry -> List.copyOf(entry.getValue())));
  }

  Map<String, ?> fact(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    value.put("rentalItemId", item.getId().toString());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("status", item.getStatus().name());
    value.put(
        "numberSha256",
        AssetChecksum.sha256(item.getNumber().getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    if (item.getTransferOriginStatus() != null) {
      value.put("transferAssetStatus", item.getTransferOriginStatus().name());
    }
    return Map.copyOf(value);
  }

  /** Full service-local state for deterministic replay; never serialized into a Kafka envelope. */
  Map<String, ?> snapshot(RentalItem item) {
    Map<String, Object> value = new LinkedHashMap<>();
    CabinCompositionService.CabinComposition composition =
        cabinComposition.compositionsFor(List.of(item)).get(item.getId());
    value.put("rentalItemId", item.getId().toString());
    value.put("version", item.getVersion());
    value.put("warehouseId", item.getWarehouseId().toString());
    value.put("number", item.getNumber());
    value.put("status", item.getStatus().name());
    value.put(
        "transferOriginStatus",
        item.getTransferOriginStatus() == null ? null : item.getTransferOriginStatus().name());
    value.put("rentalTypeId", item.getRentalTypeId() == null ? null : item.getRentalTypeId().toString());
    value.put("dimensionId", item.getDimensionId() == null ? null : item.getDimensionId().toString());
    value.put("finishingId", item.getFinishingId() == null ? null : item.getFinishingId().toString());
    value.put("category", item.getCategory());
    value.put(
        "characteristicIds",
        composition == null
            ? List.of()
            : composition.characteristics().stream()
                .map(CabinCatalogValueResponse::id)
                .map(UUID::toString)
                .toList());
    value.put("linoleum", item.getLinoleum());
    value.put("generalComment", item.getGeneralComment());
    value.put("passport", json.map(item.getPassportJson()));
    value.put("tags", json.strings(item.getTagsJson()));
    return value;
  }

  private RentalItemResponse response(
      RentalItem item,
      CabinCompositionService.CabinComposition composition,
      List<EquipmentContentResponse> equipmentContents,
      ActiveOrderReservationResponse activeOrderReservation) {
    return new RentalItemResponse(
        item.getId(),
        item.getVersion(),
        item.getWarehouseId(),
        item.getNumber(),
        item.getStatus(),
        item.getRentalTypeId(),
        composition == null || composition.rentalType() == null
            ? null
            : composition.rentalType().name(),
        item.getDimensionId(),
        composition == null || composition.dimensions() == null ? null : composition.dimensions().name(),
        item.getFinishingId(),
        composition == null || composition.finishing() == null ? null : composition.finishing().name(),
        item.getCategory(),
        composition == null ? List.of() : composition.characteristics(),
        item.getLinoleum(),
        item.getGeneralComment(),
        json.map(item.getPassportJson()),
        json.strings(item.getTagsJson()),
        equipmentContents,
        activeOrderReservation,
        item.getCreatedAt(),
        item.getUpdatedAt());
  }

  private static ManualNoteResponse manualNote(java.sql.ResultSet rs) throws java.sql.SQLException {
    return new ManualNoteResponse(
        rs.getObject("id", UUID.class),
        rs.getObject("rental_item_id", UUID.class),
        rs.getString("note_text"),
        rs.getObject("created_at", OffsetDateTime.class));
  }
}
