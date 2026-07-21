package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentContentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderEquipmentContent;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderRentalItem;
import dev.buhanzaz.rwms.asset.api.OrderAssetApiModels.OrderUnitReservationView;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

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

  OrderEquipmentContent toOrderEquipmentContent(EquipmentContentResponse response);
}
