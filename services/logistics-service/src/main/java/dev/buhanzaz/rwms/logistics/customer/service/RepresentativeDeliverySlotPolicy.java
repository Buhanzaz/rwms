package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseSupportLink;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Decides whether a CustomerApp slot may be exposed after local route-capacity evaluation.
 * Representative warehouses never promise a fixed arrival window. Their full-day offer may fall
 * back to an eligible incoming support edge, but that fallback creates neither a driver shift nor
 * an external resource reservation.
 */
@Component
@RequiredArgsConstructor
class RepresentativeDeliverySlotPolicy {
  private final LogisticsDependencyGateway dependencies;

  /**
   * Applies warehouse kind, local capacity and current owner-held support topology to one candidate.
   * Dependency failure is fail-closed because an unverified edge cannot support a customer promise.
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
    try {
      boolean supported =
          dependencies.listWarehouseSupportNetwork(warehouse.id()).stream()
              .anyMatch(
                  link ->
                      isDirectIncoming(link, warehouse.id())
                          && permitsResources(link)
                          && permitsDate(link, deliveryDate)
                          && overlaps(link, windowStart, windowEnd));
      return new Decision(supported, supported);
    } catch (LogisticsDependencyException exception) {
      return Decision.rejected();
    }
  }

  private static boolean isDirectIncoming(WarehouseSupportLink link, UUID servedWarehouseId) {
    return link != null
        && link.supportWarehouse() != null
        && link.servedWarehouse() != null
        && link.supportWarehouse().active()
        && link.servedWarehouse().active()
        && servedWarehouseId.equals(link.servedWarehouse().id())
        && !servedWarehouseId.equals(link.supportWarehouse().id());
  }

  private static boolean permitsResources(WarehouseSupportLink link) {
    return (link.allowDrivers() && link.allowVehicles()) || link.allowContractorFallback();
  }

  private static boolean permitsDate(WarehouseSupportLink link, LocalDate deliveryDate) {
    if (link.excludedDates().contains(deliveryDate)) return false;
    return link.allowedDates().contains(deliveryDate)
        || link.allowedWeekdays().isEmpty()
        || link.allowedWeekdays().contains(deliveryDate.getDayOfWeek());
  }

  private static boolean overlaps(
      WarehouseSupportLink link, LocalTime windowStart, LocalTime windowEnd) {
    if (link.serviceStart() == null) return true;
    return link.serviceStart().isBefore(windowEnd) && windowStart.isBefore(link.serviceEnd());
  }

  /**
   * Immutable outcome that distinguishes ordinary local capacity from a flexible support-backed
   * full-day candidate. The distinction prevents reporting speculative remaining local capacity.
   */
  record Decision(boolean allowed, boolean flexibleSupport) {
    private static Decision rejected() {
      return new Decision(false, false);
    }
  }
}
