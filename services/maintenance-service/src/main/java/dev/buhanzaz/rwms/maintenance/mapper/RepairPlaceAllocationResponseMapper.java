package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocation;
import java.util.UUID;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RepairPlaceAllocationResponseMapper {
  RepairPlaceAllocationResponse toResponse(RepairPlaceAllocation source);

  @Mapping(target = "rentalItemId", source = "rentalItemId")
  LogisticsRepairPlaceAllocationResponse toLogisticsResponse(
      RepairPlaceAllocation source, UUID rentalItemId);
}
