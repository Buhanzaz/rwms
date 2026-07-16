package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.domain.EquipmentCatalogItem;
import org.mapstruct.Mapper;

@Mapper
public interface EquipmentCatalogItemMapper {
  EquipmentResponse toResponse(EquipmentCatalogItem equipmentCatalogItem);
}
