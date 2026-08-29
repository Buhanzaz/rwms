package dev.buhanzaz.rwms.warehouse.mapper;

import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseIdentityResponse;
import dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseSupportLinkResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseSupportLinkResponse;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseSupportLink;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps support-link persistence state to public and logistics-specific read contracts. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WarehouseSupportLinkResponseMapper {
  WarehouseSupportLinkResponse toResponse(WarehouseSupportLink link);

  @Mapping(target = "id", source = "link.id")
  @Mapping(target = "version", source = "link.version")
  @Mapping(target = "supportWarehouse", source = "supportWarehouse")
  @Mapping(target = "servedWarehouse", source = "servedWarehouse")
  LogisticsWarehouseSupportLinkResponse toLogisticsResponse(
      WarehouseSupportLink link,
      LogisticsWarehouseIdentityResponse supportWarehouse,
      LogisticsWarehouseIdentityResponse servedWarehouse);
}
