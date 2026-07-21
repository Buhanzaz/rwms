package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.FurnitureEquipmentReference;
import dev.buhanzaz.rwms.maintenance.domain.CatalogNode;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CatalogFurnitureReferenceMapper {
  @Mapping(target = "equipmentId", source = "furnitureEquipmentId")
  @Mapping(target = "equipmentCode", source = "furnitureEquipmentCode")
  @Mapping(target = "equipmentName", source = "furnitureEquipmentName")
  FurnitureEquipmentReference toReference(CatalogNode node);
}
