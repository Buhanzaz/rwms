package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.CabinPricingCatalogResponse;
import dev.buhanzaz.rwms.asset.api.CabinPricingReferencesResponse;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.RentalItem;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps asset classification identities without looking up or calculating rental prices. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CabinPricingReferenceMapper {
  CabinPricingCatalogResponse.Value toValue(CabinCatalogItem item);

  @Mapping(target = "rentalItemId", source = "id")
  @Mapping(target = "rentalItemVersion", source = "version")
  CabinPricingReferencesResponse.Reference toReference(RentalItem item);
}
