package dev.buhanzaz.rwms.logistics.planning.mapper;

import dev.buhanzaz.rwms.logistics.planning.api.VehicleOperationalAssignmentPlanningApiModels.PlanningVehicleOperationalAssignment;
import dev.buhanzaz.rwms.logistics.vehicle.domain.VehicleOperationalAssignmentSnapshot;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps logistics-owned vehicle chain snapshots to the frozen private planning record. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface VehicleOperationalAssignmentPlanningMapper {
  PlanningVehicleOperationalAssignment toResponse(
      VehicleOperationalAssignmentSnapshot assignment);
}
