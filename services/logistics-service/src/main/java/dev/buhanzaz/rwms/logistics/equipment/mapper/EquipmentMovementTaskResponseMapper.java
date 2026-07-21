package dev.buhanzaz.rwms.logistics.equipment.mapper;

import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskLineResponse;
import dev.buhanzaz.rwms.logistics.equipment.api.EquipmentMovementTaskApiModels.EquipmentMovementTaskResponse;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTaskLine;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.ReportingPolicy;

@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EquipmentMovementTaskResponseMapper {
  @Mapping(target = "lines", ignore = true)
  EquipmentMovementTaskResponse toResponse(EquipmentMovementTask source);

  EquipmentMovementTaskLineResponse toLineResponse(EquipmentMovementTaskLine source);
}
