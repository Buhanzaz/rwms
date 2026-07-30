package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.RepairCapacitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.domain.RepairCapacitySettings;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RepairCapacitySettingsResponseMapper {
  RepairCapacitySettingsResponse toResponse(RepairCapacitySettings source);
}
