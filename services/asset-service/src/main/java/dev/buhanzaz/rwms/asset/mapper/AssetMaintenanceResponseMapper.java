package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceFurnitureEquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.MaintenanceRentalItemSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps only the fields approved for the maintenance private read boundary. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AssetMaintenanceResponseMapper {
  MaintenanceRentalItemSnapshot toMaintenanceSnapshot(RentalItemResponse response);

  @Mapping(target = "equipmentId", source = "id")
  @Mapping(target = "equipmentCode", source = "code")
  @Mapping(target = "equipmentName", source = "name")
  MaintenanceFurnitureEquipmentResponse toMaintenanceFurnitureEquipment(
      EquipmentResponse response);
}
