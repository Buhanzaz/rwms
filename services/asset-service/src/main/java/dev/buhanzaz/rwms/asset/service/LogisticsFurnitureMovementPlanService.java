package dev.buhanzaz.rwms.asset.service;

import static dev.buhanzaz.rwms.asset.api.LogisticsFurnitureMovementApiModels.*;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentBalanceResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservationState;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.EquipmentCatalogItemRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computes the exact physical delta between a cabin's current furniture and a requested complete
 * composition. Logistics owns task orchestration; asset remains the owner of available balances
 * and of the source selection.
 */
@Service
@RequiredArgsConstructor
public class LogisticsFurnitureMovementPlanService {
  private static final Set<RentalItemStatus> EDITABLE_STATUSES =
      Set.of(
          RentalItemStatus.BOOKED,
          RentalItemStatus.REPAIR,
          RentalItemStatus.WAITING_REPAIR_CHECK,
          RentalItemStatus.CAPITAL_REPAIR,
          RentalItemStatus.AFTER_RENT,
          RentalItemStatus.SALE,
          RentalItemStatus.USED_SALE,
          RentalItemStatus.RESERVED,
          RentalItemStatus.FREE,
          RentalItemStatus.WAREHOUSE,
          RentalItemStatus.OWN_NEEDS);

  private final RentalItemRepository rentalItems;
  private final OrderUnitReservationRepository orderReservations;
  private final EquipmentCatalogItemRepository equipmentCatalog;
  private final AssetService assets;

  @Transactional
  public CabinFurnitureMovementPlan plan(
      UUID rentalItemId, CabinFurnitureMovementPlanRequest request) {
    if (rentalItemId == null || request == null) {
      throw new IllegalArgumentException("Cabin furniture movement plan is invalid");
    }
    Map<UUID, Long> desired = requirementsByEquipment(request.requirements());

    try {
      assets.lockOrderRentalItemForOrder(rentalItemId);
    } catch (AssetConflictException exception) {
      throw conflict("Бытовка временно недоступна для изменения наполнения");
    }
    RentalItem unit =
        rentalItems
            .findByIdForUpdate(rentalItemId)
            .orElseThrow(() -> new AssetNotFoundException("Rental item was not found"));
    if (!request.warehouseId().equals(unit.getWarehouseId())) {
      throw conflict("Бытовка находится на другом складе");
    }
    if (!EDITABLE_STATUSES.contains(unit.getStatus())) {
      throw conflict("Наполнение этой бытовки нельзя изменить в её текущем статусе");
    }
    if (orderReservations
        .findByRentalItemIdAndState(unit.getId(), OrderUnitReservationState.ACTIVE)
        .isPresent()) {
      throw conflict("Бытовка зарезервирована заказом и недоступна для изменения наполнения");
    }

    List<EquipmentContentResponse> currentContents = assets.rentalItem(unit.getId()).contents();
    Set<UUID> equipmentIds = new java.util.LinkedHashSet<>(desired.keySet());
    currentContents.forEach(content -> equipmentIds.add(content.equipmentId()));
    Map<UUID, EquipmentCatalogItem> catalog = catalogItems(equipmentIds);
    validateDesiredFurniture(desired, catalog);

    Map<UUID, Long> actual =
        currentContents.stream()
            .filter(content -> catalog.get(content.equipmentId()).getCategory() == EquipmentCategory.FURNITURE)
            .collect(
                Collectors.toMap(
                    EquipmentContentResponse::equipmentId,
                    EquipmentContentResponse::quantity,
                    Math::addExact,
                    LinkedHashMap::new));
    Set<UUID> furnitureIds = new java.util.LinkedHashSet<>();
    furnitureIds.addAll(desired.keySet());
    furnitureIds.addAll(actual.keySet());

    List<CabinFurnitureMovementPlanLine> lines = new ArrayList<>();
    for (UUID equipmentId : furnitureIds.stream().sorted().toList()) {
      long desiredQuantity = desired.getOrDefault(equipmentId, 0L);
      long actualQuantity = actual.getOrDefault(equipmentId, 0L);
      if (desiredQuantity == actualQuantity) {
        continue;
      }

      EquipmentCatalogItem equipment = catalog.get(equipmentId);
      var totals = assets.equipmentTotals(equipmentId, request.warehouseId());
      if (desiredQuantity > actualQuantity) {
        long outstanding = Math.subtractExact(desiredQuantity, actualQuantity);
        for (EquipmentBalanceResponse source : eligibleSources(totals.balances(), unit.getId())) {
          if (outstanding == 0) {
            break;
          }
          long quantity = Math.min(outstanding, source.availableStock());
          if (quantity < 1) {
            continue;
          }
          lines.add(
              movementLine(
                  equipment,
                  source,
                  request.warehouseId(),
                  unit.getId(),
                  BalanceLocationKind.CABIN_NON_RENTED,
                  quantity));
          outstanding = Math.subtractExact(outstanding, quantity);
        }
        if (outstanding > 0) {
          throw conflict("Недостаточно доступного дополнительного оборудования");
        }
      } else {
        EquipmentBalanceResponse source =
            totals.balances().stream()
                .filter(
                    balance ->
                        unit.getId().equals(balance.rentalItemId())
                            && balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
                .findFirst()
                .orElseThrow(() -> conflict("Текущее наполнение бытовки изменилось"));
        lines.add(
            movementLine(
                equipment,
                source,
                request.warehouseId(),
                null,
                BalanceLocationKind.STOCK,
                Math.subtractExact(actualQuantity, desiredQuantity)));
      }
    }
    return new CabinFurnitureMovementPlan(unit.getId(), unit.getNumber(), List.copyOf(lines));
  }

  private List<EquipmentBalanceResponse> eligibleSources(
      List<EquipmentBalanceResponse> balances, UUID targetRentalItemId) {
    Set<UUID> candidateCabins =
        balances.stream()
            .filter(balance -> balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED)
            .map(EquipmentBalanceResponse::rentalItemId)
            .filter(Objects::nonNull)
            .filter(id -> !id.equals(targetRentalItemId))
            .collect(Collectors.toSet());
    Set<UUID> reservedCabins =
        candidateCabins.isEmpty()
            ? Set.of()
            : orderReservations
                .findAllByRentalItemIdInAndState(
                    List.copyOf(candidateCabins), OrderUnitReservationState.ACTIVE)
                .stream()
                .map(OrderUnitReservation::getRentalItemId)
                .collect(Collectors.toSet());
    return balances.stream()
        .filter(balance -> balance.availableStock() > 0)
        .filter(
            balance ->
                balance.locationKind() == BalanceLocationKind.STOCK
                    || (balance.locationKind() == BalanceLocationKind.CABIN_NON_RENTED
                        && balance.rentalItemId() != null
                        && !balance.rentalItemId().equals(targetRentalItemId)
                        && !reservedCabins.contains(balance.rentalItemId())))
        .sorted(
            Comparator.comparingInt(
                    (EquipmentBalanceResponse balance) ->
                        balance.locationKind() == BalanceLocationKind.STOCK ? 0 : 1)
                .thenComparing(
                    balance ->
                        balance.rentalItemId() == null ? "" : balance.rentalItemId().toString()))
        .toList();
  }

  private static CabinFurnitureMovementPlanLine movementLine(
      EquipmentCatalogItem equipment,
      EquipmentBalanceResponse source,
      UUID targetWarehouseId,
      UUID targetRentalItemId,
      BalanceLocationKind targetLocationKind,
      long quantity) {
    return new CabinFurnitureMovementPlanLine(
        equipment.getId(),
        equipment.getName(),
        source.id(),
        source.warehouseId(),
        source.rentalItemId(),
        source.locationKind(),
        source.version(),
        targetWarehouseId,
        targetRentalItemId,
        targetLocationKind,
        quantity);
  }

  private Map<UUID, EquipmentCatalogItem> catalogItems(Set<UUID> equipmentIds) {
    if (equipmentIds.isEmpty()) {
      return Map.of();
    }
    Map<UUID, EquipmentCatalogItem> catalog =
        equipmentCatalog.findAllById(equipmentIds).stream()
            .collect(
                Collectors.toMap(
                    EquipmentCatalogItem::getId,
                    Function.identity(),
                    (left, right) -> left,
                    LinkedHashMap::new));
    if (catalog.size() != equipmentIds.size()) {
      throw new AssetNotFoundException("Equipment catalog item was not found");
    }
    return catalog;
  }

  private static Map<UUID, Long> requirementsByEquipment(
      List<CabinFurnitureRequirement> requirements) {
    if (requirements == null) {
      throw new IllegalArgumentException("Furniture requirements are required");
    }
    Map<UUID, Long> values = new LinkedHashMap<>();
    for (CabinFurnitureRequirement requirement : requirements) {
      if (requirement == null
          || requirement.equipmentId() == null
          || requirement.quantity() == null
          || requirement.quantity() < 1
          || values.putIfAbsent(requirement.equipmentId(), requirement.quantity()) != null) {
        throw new IllegalArgumentException("Furniture requirement is invalid");
      }
    }
    return values;
  }

  private static void validateDesiredFurniture(
      Map<UUID, Long> desired, Map<UUID, EquipmentCatalogItem> catalog) {
    for (UUID equipmentId : desired.keySet()) {
      EquipmentCatalogItem equipment = catalog.get(equipmentId);
      if (equipment == null
          || equipment.getCategory() != EquipmentCategory.FURNITURE
          || !equipment.isActive()) {
        throw conflict("Можно выбрать только активную мебель из дополнительного оборудования");
      }
    }
  }

  private static AssetConflictException conflict(String message) {
    return new AssetConflictException(message);
  }
}
