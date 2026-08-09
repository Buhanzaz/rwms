package dev.buhanzaz.rwms.logistics.mapper;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.TransferFurnitureTaskStatusView;
import dev.buhanzaz.rwms.logistics.domain.TransferFurnitureMovementTask;
import dev.buhanzaz.rwms.logistics.equipment.domain.EquipmentMovementTask;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps furniture-movement task persistence state to API responses without changing logistics state.
 */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TransferFurnitureTaskResponseMapper {
  @Mapping(target = "rentalItemId", source = "link.rentalItemId")
  @Mapping(target = "unitNumber", source = "link.unitNumber")
  @Mapping(target = "taskId", source = "link.equipmentMovementTaskId")
  @Mapping(target = "externalTaskId", source = "task.externalTaskId")
  @Mapping(target = "taskBoardTaskId", source = "task.taskBoardTaskId")
  @Mapping(target = "taskState", source = "task.state")
  @Mapping(target = "lineCount", source = "link.lineCount")
  TransferFurnitureTaskStatusView toStatusView(
      TransferFurnitureMovementTask link, EquipmentMovementTask task);
}
