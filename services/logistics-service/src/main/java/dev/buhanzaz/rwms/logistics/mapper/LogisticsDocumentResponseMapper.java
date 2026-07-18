package dev.buhanzaz.rwms.logistics.mapper;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsDocumentSummary;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.LogisticsLineView;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Read boundary only: commands never map directly into JPA entities. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface LogisticsDocumentResponseMapper {
  LogisticsDocumentSummary toSummary(LogisticsDocument document);

  LogisticsLineView toLineView(LogisticsDocumentLine line);

  List<LogisticsLineView> toLineViews(List<LogisticsDocumentLine> lines);
}
