package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiPaletteDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiPaletteRangeDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiWorkBreakDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiWorkScheduleDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.WarehouseKpiSettingsResponse;
import dev.buhanzaz.rwms.taskboard.domain.KpiPalette;
import dev.buhanzaz.rwms.taskboard.domain.KpiPaletteRange;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.WarehouseKpiSettings;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps KPI setting entities to read-only API responses without owning settings transitions. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface KpiSettingsMapper {
  WarehouseKpiSettingsResponse toWarehouseKpiSettingsResponse(
      WarehouseKpiSettings warehouseKpiSettings);

  KpiPaletteDto toKpiPaletteDto(KpiPalette kpiPalette);

  KpiPaletteRangeDto toKpiPaletteRangeDto(KpiPaletteRange kpiPaletteRange);

  KpiWorkScheduleDto toKpiWorkScheduleDto(KpiWorkScheduleRevision kpiWorkScheduleRevision);

  KpiWorkBreakDto toKpiWorkBreakDto(KpiWorkBreakInterval kpiWorkBreakInterval);
}
