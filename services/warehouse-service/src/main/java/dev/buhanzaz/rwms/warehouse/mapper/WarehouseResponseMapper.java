package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import org.mapstruct.Mapper;

@Mapper
public interface WarehouseResponseMapper {
  WarehouseResponse toResponse(Warehouse warehouse);
}
