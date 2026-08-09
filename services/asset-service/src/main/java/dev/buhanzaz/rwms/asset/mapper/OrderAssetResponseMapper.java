package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.ActiveOrderReservationResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentContent;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentReservationView;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderRentalItem;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReservationView;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import java.util.List;
import java.util.stream.Collectors;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct mapper for order asset response boundary representations.
 * It maps data without performing a domain transition.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface OrderAssetResponseMapper {
  @Mapping(target = "reservationId", source = "reservation.id")
  @Mapping(target = "reservationVersion", source = "reservation.version")
  @Mapping(target = "orderId", source = "reservation.orderId")
  @Mapping(target = "rentalItemId", source = "reservation.rentalItemId")
  @Mapping(target = "warehouseId", source = "reservation.warehouseId")
  @Mapping(target = "state", source = "reservation.state")
  @Mapping(target = "addedBySubjectId", source = "reservation.addedBySubjectId")
  @Mapping(target = "addedByRole", source = "reservation.addedByRole")
  @Mapping(target = "createdAt", source = "reservation.createdAt")
  @Mapping(target = "releasedAt", source = "reservation.releasedAt")
  @Mapping(target = "replayed", source = "replayed")
  @Mapping(target = "unit", source = "unit")
  OrderUnitReservationView toReservation(
      OrderUnitReservation reservation, boolean replayed, OrderRentalItem unit);

  OrderRentalItem toOrderRentalItem(RentalItemResponse response);

  /** The existing logistics order boundary remains textual until its own contract cutover. */
  default String map(List<CabinCatalogValueResponse> values) {
    if (values == null || values.isEmpty()) return null;
    return values.stream().map(CabinCatalogValueResponse::name).collect(Collectors.joining(", "));
  }

  OrderEquipmentContent toOrderEquipmentContent(EquipmentContentResponse response);

  @Mapping(target = "equipmentId", source = "reservation.equipmentId")
  @Mapping(target = "equipmentName", source = "equipment.name")
  @Mapping(target = "quantity", source = "reservation.quantity")
  @Mapping(target = "availableQuantity", source = "availableQuantity")
  OrderEquipmentReservationView toOrderEquipmentReservation(
      OrderEquipmentReservation reservation,
      EquipmentCatalogItem equipment,
      long availableQuantity);

  @Mapping(target = "reservationId", source = "id")
  @Mapping(target = "reservedAt", source = "createdAt")
  ActiveOrderReservationResponse toActiveOrderReservation(OrderUnitReservation reservation);
}
