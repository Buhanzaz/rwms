package dev.buhanzaz.rwms.logistics.inquiry.mapper;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalBookingAlertCabin;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalBookingAlertResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.PresentationBooking;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.AvailableCabin;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    uses = RentalOrderResponseMapper.class,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RentalBookingAlertResponseMapper {
  @Mapping(source = "booking.id", target = "bookingId")
  @Mapping(source = "booking.version", target = "version")
  @Mapping(source = "inquiry.id", target = "inquiryId")
  @Mapping(source = "booking.orderId", target = "orderId")
  @Mapping(source = "inquiry.client", target = "client")
  @Mapping(source = "booking.completedAt", target = "confirmedAt")
  @Mapping(source = "cabins", target = "cabins")
  RentalBookingAlertResponse toResponse(
      PresentationBooking booking,
      RentalInquiry inquiry,
      List<RentalBookingAlertCabin> cabins);

  RentalBookingAlertCabin toCabin(AvailableCabin cabin);
}
