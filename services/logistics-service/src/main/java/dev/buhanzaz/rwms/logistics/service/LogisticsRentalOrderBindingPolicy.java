package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Verifies the service-local rental-order truth that may bind an otherwise independent return or
 * shipment document before its fenced external workflow starts.
 */
@Service
@RequiredArgsConstructor
class LogisticsRentalOrderBindingPolicy {
  private final RentalOrderRepository rentalOrders;

  RentalOrder validateReturnBinding(CreateReturnRequest request) {
    boolean hasBinding =
        request.clientId() != null
            || request.lines().stream().anyMatch(line -> line.rentalOrderId() != null);
    if (!hasBinding) return null;
    if (request.clientId() == null) {
      throw new IllegalArgumentException("clientId is required for a bound rental return");
    }

    RentalOrder first = null;
    for (var line : request.lines()) {
      if (line.rentalOrderId() == null) {
        throw new IllegalArgumentException(
            "rentalOrderId is required for every bound return line");
      }
      RentalOrder order =
          requireActiveRentalOrder(line.rentalOrderId(), request.clientId(), request.warehouseId());
      if (first == null) first = order;
    }
    return first;
  }

  RentalOrder validateShipmentBinding(CreateShipmentRequest request) {
    boolean hasBinding = request.clientId() != null || request.rentalOrderId() != null;
    if (!hasBinding) return null;
    if (request.clientId() == null || request.rentalOrderId() == null) {
      throw new IllegalArgumentException(
          "clientId and rentalOrderId are required for a bound rental shipment");
    }
    return requireActiveRentalOrder(request.rentalOrderId(), request.clientId(), request.warehouseId());
  }

  private RentalOrder requireActiveRentalOrder(UUID rentalOrderId, UUID clientId, UUID warehouseId) {
    RentalOrder order =
        rentalOrders
            .findWithClientById(rentalOrderId)
            .orElseThrow(() -> new LogisticsConflictException("Rental order was not found"));
    if (order.getStatus() != RentalOrderStatus.DRAFT
        || !clientId.equals(order.getClient().getId())
        || !warehouseId.equals(order.getWarehouseId())) {
      throw new LogisticsConflictException(
          "Rental order does not belong to the selected client and warehouse");
    }
    return order;
  }
}
