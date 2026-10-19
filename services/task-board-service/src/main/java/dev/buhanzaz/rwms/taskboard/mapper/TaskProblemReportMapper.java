package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.ProblemReportApiModels.TaskProblemReportSummary;
import dev.buhanzaz.rwms.taskboard.domain.TaskProblemReport;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Read-only mapping of the immutable problem-report aggregate to its transport summary. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface TaskProblemReportMapper {
  @Mapping(target = "reportId", source = "id")
  TaskProblemReportSummary summary(TaskProblemReport report);
}
