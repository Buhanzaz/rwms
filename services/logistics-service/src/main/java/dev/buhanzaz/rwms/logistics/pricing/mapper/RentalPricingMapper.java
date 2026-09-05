package dev.buhanzaz.rwms.logistics.pricing.mapper;

import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingRate;
import dev.buhanzaz.rwms.logistics.pricing.domain.RentalPricingSettings;
import dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingSnapshot;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Read-only projection; tariff mutations and version ownership remain in the aggregate/store. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RentalPricingMapper {
  RentalPricingSnapshot toSnapshot(RentalPricingSettings settings);

  RentalPricingSnapshot.Rate toRate(RentalPricingRate rate);
}
