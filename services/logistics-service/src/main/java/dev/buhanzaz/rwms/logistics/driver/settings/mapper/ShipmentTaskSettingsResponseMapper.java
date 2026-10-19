package dev.buhanzaz.rwms.logistics.driver.settings.mapper;

import dev.buhanzaz.rwms.logistics.driver.settings.api.ShipmentTaskSettingsApiModels.ShipmentTaskSettingsResponse;
import dev.buhanzaz.rwms.logistics.driver.settings.domain.ShipmentTaskSettings;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the local warehouse policy aggregate to its public settings representation. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ShipmentTaskSettingsResponseMapper {
  @Mapping(source = "updatedBySubjectId", target = "updatedBy")
  ShipmentTaskSettingsResponse toResponse(ShipmentTaskSettings settings);
}
