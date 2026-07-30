package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseIdentityResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WarehouseResponseMapper {
  WarehouseResponse toResponse(Warehouse warehouse);

  LogisticsWarehouseIdentityResponse toLogisticsIdentity(Warehouse warehouse);
}
