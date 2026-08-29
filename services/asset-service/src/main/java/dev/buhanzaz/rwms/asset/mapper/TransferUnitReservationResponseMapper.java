package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.TransferUnitReservationApiModels.TransferUnitReservationLineReceipt;
import dev.buhanzaz.rwms.asset.domain.TransferUnitReservation;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps transfer-owned cabin reservation history to the private logistics receipt. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TransferUnitReservationResponseMapper {
  /** Maps one immutable owner row together with the current fenced rental-item revision. */
  @Mapping(target = "reservationId", source = "reservation.id")
  TransferUnitReservationLineReceipt toReceipt(
      TransferUnitReservation reservation, long currentRentalItemVersion);
}
