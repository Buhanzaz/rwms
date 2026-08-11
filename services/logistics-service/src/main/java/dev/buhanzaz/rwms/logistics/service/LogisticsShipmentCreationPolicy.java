package dev.buhanzaz.rwms.logistics.service;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateShipmentRequest;
import dev.buhanzaz.rwms.logistics.driver.settings.service.ShipmentTaskSettingsService;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Applies the warehouse grouping limit and optional rental-order binding before a shipment is
 * materialized.
 */
@Service
@RequiredArgsConstructor
class LogisticsShipmentCreationPolicy {
  private final ShipmentTaskSettingsService shipmentTaskSettings;
  private final LogisticsRentalOrderBindingPolicy rentalOrderBinding;

  /** Enforces the current warehouse-local grouping limit before admission is consumed. */
  void requireWithinTaskLimit(CreateShipmentRequest request, java.util.UUID subjectId) {
    shipmentTaskSettings.requireWithinLimit(
        request.warehouseId(), request.lines().size(), subjectId);
  }

  /** Validates and returns the optional rental order after warehouse admission succeeds. */
  RentalOrder validateRentalBinding(CreateShipmentRequest request) {
    return rentalOrderBinding.validateShipmentBinding(request);
  }
}
