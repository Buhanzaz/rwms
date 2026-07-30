package dev.buhanzaz.rwms.asset.mapper;

import dev.buhanzaz.rwms.asset.api.RentalItemHtmlImportApiModels.HtmlImportSummaryResponse;
import dev.buhanzaz.rwms.asset.domain.RentalItemHtmlImport;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = "spring",
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RentalItemHtmlImportMapper {
  HtmlImportSummaryResponse toSummary(RentalItemHtmlImport value);
}
