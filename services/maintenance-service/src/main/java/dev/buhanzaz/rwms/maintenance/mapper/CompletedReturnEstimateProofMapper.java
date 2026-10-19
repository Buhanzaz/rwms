package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.CompletedReturnEstimateProofResponse;
import dev.buhanzaz.rwms.maintenance.domain.LogisticsReturnShortage;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps validated return-source and estimate facts to the private inventory proof response. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CompletedReturnEstimateProofMapper {
  @Mapping(target = "estimateId", source = "estimate.id")
  @Mapping(target = "estimateVersion", source = "estimate.version")
  @Mapping(target = "estimateRevision", source = "estimate.revision")
  @Mapping(target = "returnId", source = "source.id.returnId")
  @Mapping(target = "lineId", source = "source.id.lineId")
  @Mapping(target = "warehouseId", source = "estimate.warehouseId")
  @Mapping(target = "assetId", source = "estimate.rentalItemId")
  @Mapping(target = "assetVersion", source = "source.rentalItemVersionSnapshot")
  @Mapping(target = "arrivedAt", source = "source.arrivedAt")
  @Mapping(target = "completedAt", source = "estimate.completedAt")
  @Mapping(target = "completionKind", expression = "java(lineCount == 0 ? \"EMPTY\" : \"NON_EMPTY\")")
  @Mapping(target = "repairId", source = "estimate.repairId")
  CompletedReturnEstimateProofResponse toResponse(
      MaintenanceEstimate estimate, LogisticsReturnShortage source, long lineCount);
}
