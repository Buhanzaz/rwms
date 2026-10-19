package dev.buhanzaz.rwms.logistics.customer.mapper;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerNotificationResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerNotification;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps subject-owned inbox facts without exposing the customer subject binding. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerNotificationResponseMapper {
  CustomerNotificationResponse toResponse(CustomerNotification notification);
}
