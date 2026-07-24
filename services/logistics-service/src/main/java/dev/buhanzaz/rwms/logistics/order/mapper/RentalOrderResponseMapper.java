package dev.buhanzaz.rwms.logistics.order.mapper;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderSummaryResponse;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEvent;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface RentalOrderResponseMapper {
  @Mapping(source = "clientType", target = "type")
  ClientResponse toClientResponse(OrderClient client);

  @Mapping(source = "order.id", target = "id")
  @Mapping(source = "order.version", target = "version")
  @Mapping(source = "order.orderNumber", target = "number")
  @Mapping(source = "order.status", target = "status")
  @Mapping(source = "order.client", target = "client")
  @Mapping(source = "order.managerId", target = "managerId")
  @Mapping(source = "order.managerDisplayName", target = "managerDisplayName")
  @Mapping(source = "order.createdBySubjectId", target = "createdBy")
  @Mapping(source = "order.createdByDisplayName", target = "createdByDisplayName")
  @Mapping(source = "order.warehouseId", target = "warehouseId")
  @Mapping(source = "unitCount", target = "unitCount")
  @Mapping(source = "order.createdAt", target = "createdAt")
  @Mapping(source = "order.updatedAt", target = "updatedAt")
  OrderSummaryResponse toSummaryResponse(RentalOrder order, long unitCount);

  @Mapping(source = "eventType", target = "eventType")
  OrderHistoryEventResponse toHistoryResponse(OrderAuditEvent event);

  OrderDesiredEquipmentResponse toDesiredEquipmentResponse(
      RentalOrderEquipmentRequirement requirement);
}
