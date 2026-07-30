package dev.buhanzaz.rwms.logistics.inquiry.mapper;

import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalInquiryResponse;
import dev.buhanzaz.rwms.logistics.inquiry.api.RentalInquiryApiModels.RentalSettingsResponse;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalInquiry;
import dev.buhanzaz.rwms.logistics.inquiry.domain.RentalSettings;
import dev.buhanzaz.rwms.logistics.order.mapper.RentalOrderResponseMapper;
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
public interface RentalInquiryResponseMapper {
  RentalInquiryResponse toResponse(RentalInquiry inquiry);

  @Mapping(source = "updatedBySubjectId", target = "updatedBy")
  RentalSettingsResponse toResponse(RentalSettings settings);
}
