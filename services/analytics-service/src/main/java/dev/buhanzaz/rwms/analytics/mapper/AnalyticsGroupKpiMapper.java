package dev.buhanzaz.rwms.analytics.mapper;

import dev.buhanzaz.rwms.analytics.api.AnalyticsApiModels;
import dev.buhanzaz.rwms.analytics.service.AnalyticsQueryService;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

/** Maps calculated analytics read-model values to public API records without mutating a persistence entity. */
@Mapper(componentModel = "spring", unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface AnalyticsGroupKpiMapper {
  AnalyticsApiModels.GroupKpi toApi(AnalyticsQueryService.GroupResult source);
}
