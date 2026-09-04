package dev.buhanzaz.rwms.logistics.customer.mapper;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeAlertResponse;
import dev.buhanzaz.rwms.logistics.customer.api.CustomerBookingChangeApiModels.CustomerBookingChangeQuoteResponse;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingChangeCharge;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerBookingMutation;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerChangeSettlement;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import org.mapstruct.InjectionStrategy;
import org.mapstruct.Mapper;
import org.mapstruct.Mapping;
import org.mapstruct.MappingConstants;
import org.mapstruct.ReportingPolicy;

/** Maps approved quote facts only, without implementing consent, waiver or settlement policy. */
@Mapper(
    componentModel = MappingConstants.ComponentModel.SPRING,
    injectionStrategy = InjectionStrategy.CONSTRUCTOR,
    unmappedTargetPolicy = ReportingPolicy.ERROR)
public interface CustomerBookingChangeResponseMapper {
  @Mapping(target = "quoteId", source = "charge.id")
  @Mapping(target = "testPaymentAvailable", source = "testPaymentAvailable")
  @Mapping(target = "amountRubles", source = "amountRubles")
  CustomerBookingChangeQuoteResponse toResponse(
      CustomerBookingChangeCharge charge, boolean testPaymentAvailable, Long amountRubles);

  @Mapping(target = "mutationId", source = "mutation.id")
  @Mapping(target = "version", source = "mutation.version")
  @Mapping(target = "bookingId", source = "mutation.bookingId")
  @Mapping(target = "orderId", source = "mutation.orderId")
  @Mapping(target = "warehouseId", source = "original.warehouseId")
  @Mapping(target = "occurredAt", source = "mutation.completedAt")
  @Mapping(target = "previousDeliveryDate", source = "original.deliveryDate")
  @Mapping(target = "newDeliveryDate", source = "replacement.deliveryDate")
  @Mapping(target = "deliveryAddress", source = "original.deliveryAddress")
  CustomerBookingChangeAlertResponse toAlert(
      CustomerBookingMutation mutation,
      CustomerDeliverySlot original,
      CustomerDeliverySlot replacement,
      Long feeRubles,
      CustomerChangeSettlement settlement,
      boolean canOpenOrder);
}
