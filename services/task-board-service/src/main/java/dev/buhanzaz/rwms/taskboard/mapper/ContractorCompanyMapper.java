package dev.buhanzaz.rwms.taskboard.mapper;

import dev.buhanzaz.rwms.taskboard.api.ContractorCompanyApiModels.ContractorCompanyResponse;
import dev.buhanzaz.rwms.taskboard.domain.ContractorCompany;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Explicit read-only company boundary mapping; authorization and changes remain service-owned. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface ContractorCompanyMapper {
  @Mapping(target = "companyId", source = "id")
  @Mapping(target = "homeWarehouseId", source = "warehouseId")
  ContractorCompanyResponse response(ContractorCompany company);
}
