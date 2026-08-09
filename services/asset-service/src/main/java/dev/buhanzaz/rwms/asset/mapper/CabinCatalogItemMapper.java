package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinCatalogValueResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CabinTypeDimensionResponse;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogItem;
import dev.buhanzaz.rwms.asset.domain.CabinTypeDimension;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * MapStruct mapper for cabin catalog item boundary representations.
 * It maps data without performing a domain transition.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CabinCatalogItemMapper {
  CabinCatalogItemResponse toResponse(CabinCatalogItem item);

  CabinCatalogValueResponse toValue(CabinCatalogItem item);

  @Mapping(target = "typeId", source = "cabinTypeId")
  CabinTypeDimensionResponse toTypeDimension(CabinTypeDimension link);
}
