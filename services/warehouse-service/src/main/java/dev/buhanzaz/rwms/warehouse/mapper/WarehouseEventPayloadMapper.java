package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import dev.buhanzaz.rwms.warehouse.eventing.WarehouseEventPayload;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;

@Mapper
public interface WarehouseEventPayloadMapper {
  @Mapping(target = "warehouseId", source = "id")
  WarehouseEventPayload toPayload(Warehouse warehouse);
}
