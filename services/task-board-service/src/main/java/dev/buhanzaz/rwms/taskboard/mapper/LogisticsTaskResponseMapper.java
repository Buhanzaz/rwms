package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.LogisticsTaskSnapshot;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps an entity read to the deliberately narrow Stage 8 task snapshot. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LogisticsTaskResponseMapper {
  @Mapping(target = "taskId", source = "id")
  @Mapping(target = "taskVersion", source = "version")
  LogisticsTaskSnapshot toLogisticsTaskSnapshot(BoardTask task);
}
