package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventPayload;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the warehouse aggregate to the explicitly sanitized event payload boundary. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WarehouseEventPayloadMapper {
  @Mapping(target = "warehouseId", source = "warehouse.id")
  @Mapping(target = "timeZone", source = "effectiveTimeZone")
  @Mapping(target = "timeZoneDecision", ignore = true)
  WarehouseEventPayload toPayload(Warehouse warehouse, String effectiveTimeZone);
}
