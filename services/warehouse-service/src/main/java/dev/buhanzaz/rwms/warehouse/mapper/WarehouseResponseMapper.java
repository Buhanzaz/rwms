package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseIdentityResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.domain.Warehouse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps warehouse state and the effective timezone to public and logistics-owned read contracts. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WarehouseResponseMapper {
  @Mapping(target = "timeZone", source = "effectiveTimeZone")
  @Mapping(target = "lifecycleState", expression = "java(warehouse.getLifecycleState().name())")
  WarehouseResponse toResponse(Warehouse warehouse, String effectiveTimeZone);

  @Mapping(target = "timeZone", source = "effectiveTimeZone")
  LogisticsWarehouseIdentityResponse toLogisticsIdentity(
      Warehouse warehouse, String effectiveTimeZone);
}
