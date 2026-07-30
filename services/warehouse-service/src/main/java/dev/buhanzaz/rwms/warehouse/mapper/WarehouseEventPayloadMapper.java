package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventPayload;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WarehouseEventPayloadMapper {
  @Mapping(target = "warehouseId", source = "id")
  WarehouseEventPayload toPayload(Warehouse warehouse);
}
