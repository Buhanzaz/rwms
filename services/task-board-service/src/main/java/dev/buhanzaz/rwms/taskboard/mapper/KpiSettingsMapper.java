package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiPaletteDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiPaletteRangeDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiWorkBreakDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.KpiWorkScheduleDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.RepairComplexityThresholdsDto;
import dev.buhanzaz.rwms.taskboard.api.KpiSettingsApiModels.WarehouseKpiSettingsResponse;
import dev.buhanzaz.rwms.taskboard.domain.KpiPalette;
import dev.buhanzaz.rwms.taskboard.domain.KpiPaletteRange;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkBreakInterval;
import dev.buhanzaz.rwms.taskboard.domain.KpiWorkScheduleRevision;
import dev.buhanzaz.rwms.taskboard.domain.WarehouseKpiSettings;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.BeanMapping;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface KpiSettingsMapper {
  @Mapping(target = "repairComplexity", source = "warehouseKpiSettings")
  WarehouseKpiSettingsResponse toWarehouseKpiSettingsResponse(
      WarehouseKpiSettings warehouseKpiSettings);

  @BeanMapping(ignoreByDefault = true)
  @Mapping(target = "lightBoundaryMinutes", source = "repairLightBoundaryMinutes")
  @Mapping(target = "mediumBoundaryMinutes", source = "repairMediumBoundaryMinutes")
  @Mapping(target = "complexBoundaryMinutes", source = "repairComplexBoundaryMinutes")
  RepairComplexityThresholdsDto toRepairComplexityThresholdsDto(
      WarehouseKpiSettings warehouseKpiSettings);

  KpiPaletteDto toKpiPaletteDto(KpiPalette kpiPalette);

  KpiPaletteRangeDto toKpiPaletteRangeDto(KpiPaletteRange kpiPaletteRange);

  KpiWorkScheduleDto toKpiWorkScheduleDto(KpiWorkScheduleRevision kpiWorkScheduleRevision);

  KpiWorkBreakDto toKpiWorkBreakDto(KpiWorkBreakInterval kpiWorkBreakInterval);
}
