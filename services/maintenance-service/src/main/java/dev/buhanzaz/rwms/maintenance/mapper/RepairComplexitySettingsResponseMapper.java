package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.RepairComplexitySettingsResponse;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexitySettings;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Maps the global thresholds to their public versioned representation. */
@Mapper(
    componentModel = "spring",
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RepairComplexitySettingsResponseMapper {
  RepairComplexitySettingsResponse toResponse(RepairComplexitySettings settings);
}
