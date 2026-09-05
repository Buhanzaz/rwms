package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.RentalItemReserveSnapshot.OrderReservation;
import dev.buhanzaz.rwms.asset.api.RentalItemReserveSnapshot.SelectionHold;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps immutable read facts only; lifecycle filtering and authorization remain outside the mapper.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RentalItemReserveSnapshotMapper {
  @Mapping(target = "holdId", source = "id")
  @Mapping(target = "holdScopeId", source = "presentationId")
  @Mapping(target = "actorSubjectId", source = "createdBySubjectId")
  @Mapping(target = "actorRole", source = "createdByRole")
  SelectionHold toSelectionHold(PresentationUnitHold hold);

  @Mapping(target = "reservationId", source = "id")
  @Mapping(target = "actorSubjectId", source = "addedBySubjectId")
  @Mapping(target = "actorRole", source = "addedByRole")
  @Mapping(target = "draftExpiresAt", source = "draftReservationExpiresAt")
  OrderReservation toOrderReservation(OrderUnitReservation reservation);
}
