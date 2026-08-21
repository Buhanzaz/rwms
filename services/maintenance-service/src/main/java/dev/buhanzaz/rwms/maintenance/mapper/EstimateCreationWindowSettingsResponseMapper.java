package dev.buhanzaz.rwms.maintenance.mapper;

import dev.buhanzaz.rwms.maintenance.api.EstimateCreationWindowSettingsResponse;
import dev.buhanzaz.rwms.maintenance.domain.EstimateCreationWindowSettings;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the setting aggregate to its public response without applying business transitions. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface EstimateCreationWindowSettingsResponseMapper {
  EstimateCreationWindowSettingsResponse toResponse(EstimateCreationWindowSettings source);
}
