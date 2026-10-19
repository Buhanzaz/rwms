package dev.buhanzaz.rwms.logistics.mapper;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ShipmentFurnitureTaskView;
import dev.buhanzaz.rwms.logistics.domain.ShipmentFurnitureMovementTask;
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
public interface ShipmentFurnitureTaskResponseMapper {
  @Mapping(target = "taskId", source = "equipmentMovementTaskId")
  ShipmentFurnitureTaskView toView(ShipmentFurnitureMovementTask task);
}
