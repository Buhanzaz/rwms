package dev.buhanzaz.rwms.logistics.order.mapper;

import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.AdditionalContactResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.ClientResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.DesiredDeliveryWindowResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderDesiredEquipmentResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderHistoryEventResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderRentalTermResponse;
import dev.buhanzaz.rwms.logistics.order.api.OrderApiModels.OrderSummaryResponse;
import dev.buhanzaz.rwms.logistics.order.domain.AdditionalContact;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderAuditEvent;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderEquipmentRequirement;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/**
 * Maps Rental Order Response Mapper at a logistics boundary without applying a domain transition.
 */
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
  @Mapping(source = "order.deliveryAddress", target = "deliveryAddress")
  @Mapping(source = "order.latitude", target = "latitude")
  @Mapping(source = "order.longitude", target = "longitude")
  @Mapping(source = "order.contactPhone", target = "contactPhone")
  @Mapping(source = "order.comment", target = "comment")
  @Mapping(source = "order.additionalContacts", target = "additionalContacts")
  @Mapping(source = "order.desiredDeliveryWindows", target = "desiredDeliveryWindows")
  @Mapping(source = "unitCount", target = "unitCount")
  @Mapping(source = "order.createdAt", target = "createdAt")
  @Mapping(source = "order.updatedAt", target = "updatedAt")
  OrderSummaryResponse toSummaryResponse(RentalOrder order, long unitCount);

  @Mapping(source = "eventType", target = "eventType")
  OrderHistoryEventResponse toHistoryResponse(OrderAuditEvent event);

  @Mapping(target = "reservationState", constant = "ACTIVE")
  OrderDesiredEquipmentResponse toDesiredEquipmentResponse(
      RentalOrderEquipmentRequirement requirement);

  OrderRentalTermResponse toRentalTermResponse(RentalOrderUnitTerm term);

  AdditionalContactResponse toAdditionalContactResponse(AdditionalContact contact);

  DesiredDeliveryWindowResponse toDesiredDeliveryWindowResponse(DesiredDeliveryWindow window);
}
