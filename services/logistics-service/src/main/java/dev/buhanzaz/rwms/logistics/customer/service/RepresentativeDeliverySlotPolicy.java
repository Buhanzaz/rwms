package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import java.time.LocalDate;
import java.time.LocalTime;
import org.springframework.stereotype.Component;

/**
 * Decides whether a CustomerApp slot may be exposed after local route-capacity evaluation.
 * Representative warehouses never promise a fixed arrival window. Their full-day offer requires
 * the same confirmed feasible route and capacity fact as an ordinary warehouse; support-network
 * topology is not a capacity reservation and is therefore never used to create a customer promise.
 */
@Component
class RepresentativeDeliverySlotPolicy {
  /**
   * Applies warehouse kind and the caller's confirmed route-capacity result to one slot candidate.
   * Date and window remain part of the shared policy boundary, but support calendars do not override
   * an infeasible capacity result.
   */
  Decision evaluate(
      WarehouseIdentity warehouse,
      LocalDate deliveryDate,
      CustomerDeliverySlotKind kind,
      LocalTime windowStart,
      LocalTime windowEnd,
      boolean localCapacityFeasible) {
    if (!warehouse.representative()) {
      return new Decision(localCapacityFeasible, false);
    }
    if (kind != CustomerDeliverySlotKind.DURING_DAY) {
      return Decision.rejected();
    }
    if (localCapacityFeasible) {
      return new Decision(true, false);
    }
    return Decision.rejected();
  }

  /**
   * Immutable outcome of the customer-slot policy. {@code flexibleSupport} remains false until a
   * durable confirmed external-resource flow can provide a real capacity token.
   */
  record Decision(boolean allowed, boolean flexibleSupport) {
    private static Decision rejected() {
      return new Decision(false, false);
    }
  }
}
