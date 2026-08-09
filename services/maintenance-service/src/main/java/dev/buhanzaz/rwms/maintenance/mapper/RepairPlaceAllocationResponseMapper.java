package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.LogisticsRepairPlaceProjectionAllocationResponse;
import dev.buhanzaz.rwms.maintenance.api.RepairPlaceAllocationResponse;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocation;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import java.util.UUID;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps RepairPlaceAllocationResponse at the maintenance boundary without applying a domain transition. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RepairPlaceAllocationResponseMapper {
  RepairPlaceAllocationResponse toResponse(RepairPlaceAllocation source);

  @Mapping(target = "rentalItemId", source = "rentalItemId")
  LogisticsRepairPlaceAllocationResponse toLogisticsResponse(
      RepairPlaceAllocation source, UUID rentalItemId);

  @Mapping(target = "rentalItemId", source = "rentalItemId")
  @Mapping(target = "repairStageName", source = "repairStageName")
  @Mapping(target = "repairStageState", source = "repairStageState")
  @Mapping(target = "priority", source = "priority")
  LogisticsRepairPlaceProjectionAllocationResponse toLogisticsProjectionResponse(
      RepairPlaceAllocation source,
      UUID rentalItemId,
      String repairStageName,
      RepairStageState repairStageState,
      int priority);
}
