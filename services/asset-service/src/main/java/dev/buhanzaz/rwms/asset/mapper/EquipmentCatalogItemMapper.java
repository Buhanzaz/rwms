package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EquipmentCatalogItemMapper {
  EquipmentResponse toResponse(EquipmentCatalogItem equipmentCatalogItem);
}
