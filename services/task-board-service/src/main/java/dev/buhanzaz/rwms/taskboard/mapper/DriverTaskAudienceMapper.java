package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.DriverTaskAudienceDto;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the persisted non-PII driver planning snapshot to its transport representation. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DriverTaskAudienceMapper {
  /** Maps a task whose audience mode is known to be present. */
  @Mapping(target = "mode", source = "driverAudienceMode")
  @Mapping(target = "workerId", source = "plannedDriverWorkerId")
  @Mapping(target = "workerName", source = "plannedDriverNameSnapshot")
  DriverTaskAudienceDto toDto(BoardTask task);
}
