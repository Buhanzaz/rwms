package dev.buhanzaz.rwms.logistics.customer.capacity.mapper;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacityCommandReceipt;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.ScenarioCapacitySnapshot;
import dev.buhanzaz.rwms.logistics.planning.api.PlanningIntegrationApiModels.PlanningCapacitySnapshotResponse;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
/** Maps anonymous capacity persistence state to its narrow planner integration response. */
public interface ScenarioCapacitySnapshotResponseMapper {
  @Mapping(target = "jobCount", expression = "java(snapshot.getJobs().size())")
  PlanningCapacitySnapshotResponse toResponse(
      ScenarioCapacitySnapshot snapshot, boolean replayed);

  /** Restores the exact original response for a successful delayed command replay. */
  @Mapping(target = "version", source = "receipt.snapshotVersion")
  @Mapping(target = "updatedAt", source = "receipt.responseUpdatedAt")
  PlanningCapacitySnapshotResponse toResponse(
      ScenarioCapacityCommandReceipt receipt, boolean replayed);
}
