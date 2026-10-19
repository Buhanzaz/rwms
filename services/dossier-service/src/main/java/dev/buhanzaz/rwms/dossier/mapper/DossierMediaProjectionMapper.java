package dev.buhanzaz.rwms.dossier.mapper;

import dev.buhanzaz.rwms.dossier.api.DossierApiModels;
import dev.buhanzaz.rwms.dossier.domain.DossierMediaProjection;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps the persisted media read projection to its sanitized dossier API DTO. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface DossierMediaProjectionMapper {
  @Mapping(target = "findingId", source = "inventoryFindingId")
  @Mapping(target = "generation", source = "mediaGeneration")
  DossierApiModels.MediaProjection toResponse(DossierMediaProjection projection);
}
