package dev.buhanzaz.rwms.logistics.customer.capacity.mapper;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps anonymous capacity persistence state to its narrow planner integration response. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface WarehouseCapacitySnapshotResponseMapper {
  @Mapping(target = "jobCount", expression = "java(snapshot.getJobs().size())")
  @Mapping(target = "shiftCount", expression = "java(snapshot.getShifts().size())")
  @Mapping(target = "priceZoneCount", expression = "java(snapshot.getPriceZones().size())")
  @Mapping(
      target = "restrictionZoneCount",
      expression = "java(snapshot.getRestrictionZones().size())")
  PlanningCapacitySnapshotResponse toResponse(
      WarehouseCapacitySnapshot snapshot, boolean replayed);

  /** Restores the exact original response for a successful delayed command replay. */
  @Mapping(target = "version", source = "receipt.snapshotVersion")
  @Mapping(target = "updatedAt", source = "receipt.responseUpdatedAt")
  PlanningCapacitySnapshotResponse toResponse(
      WarehouseCapacityCommandReceipt receipt, boolean replayed);
}
