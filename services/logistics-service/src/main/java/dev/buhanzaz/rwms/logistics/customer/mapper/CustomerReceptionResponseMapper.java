package dev.buhanzaz.rwms.logistics.customer.mapper;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinAcceptanceResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerCabinProblemResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerProblemMediaReference;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinAcceptance;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerCabinProblem;
import java.util.List;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps logistics-owned customer reception facts to their least-privilege public responses. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerReceptionResponseMapper {
  /** Maps one immutable acceptance without exposing stored signature coordinates. */
  @Mapping(target = "acceptanceId", source = "acceptance.id")
  CustomerCabinAcceptanceResponse toResponse(CustomerCabinAcceptance acceptance);

  /** Maps one problem together with its already-decoded immutable media references. */
  @Mapping(target = "problemId", source = "problem.id")
  @Mapping(target = "mediaReferences", source = "mediaReferences")
  CustomerCabinProblemResponse toResponse(
      CustomerCabinProblem problem, List<CustomerProblemMediaReference> mediaReferences);
}
