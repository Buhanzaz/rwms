package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.CabinStatusColorsResponse;
import dev.buhanzaz.rwms.asset.domain.CabinStatusColors;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Read-only boundary mapping; commands and version checks remain in the owning service. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CabinStatusColorsMapper {
  CabinStatusColorsResponse toResponse(CabinStatusColors colors);
}
