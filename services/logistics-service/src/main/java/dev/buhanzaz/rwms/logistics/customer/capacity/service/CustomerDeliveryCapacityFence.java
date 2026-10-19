package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Serializes every logistics-local write that can change CustomerApp delivery capacity for one
 * warehouse day. The shared advisory-lock namespace makes a slot hold, planner replacement and
 * whole-day driver reservation observe one deterministic transaction order.
 */
@Component
@RequiredArgsConstructor
public class CustomerDeliveryCapacityFence {
  private final LogisticsTransactionLock transactionLock;

  /** Acquires the capacity fence for one exact warehouse-local delivery day. */
  public void acquireDay(UUID warehouseId, LocalDate deliveryDate) {
    transactionLock.acquire(dayKey(warehouseId, deliveryDate));
  }

  /** Acquires the warehouse-wide planner-capacity fence. */
  public void acquireWarehouseCapacity(UUID warehouseId) {
    transactionLock.acquire(warehouseCapacityKey(warehouseId));
  }

  /** Acquires both capacity inputs in deterministic key order for the final slot transaction. */
  public void acquireDayAndWarehouseCapacity(UUID warehouseId, LocalDate deliveryDate) {
    transactionLock.acquireAll(
        List.of(dayKey(warehouseId, deliveryDate), warehouseCapacityKey(warehouseId)));
  }

  /**
   * Acquires a day fence only for transport kinds counted as conservative whole-day delivery
   * reservations.
   */
  public void acquireTaskDay(UUID warehouseId, LocalDate scheduledDate, DriverTaskKind kind) {
    Objects.requireNonNull(warehouseId, "Warehouse is required");
    Objects.requireNonNull(scheduledDate, "Scheduled date is required");
    Objects.requireNonNull(kind, "Driver task kind is required");
    if (kind == DriverTaskKind.SHIPMENT || kind == DriverTaskKind.TRANSFER) {
      acquireDay(warehouseId, scheduledDate);
    }
  }

  private static String dayKey(UUID warehouseId, LocalDate deliveryDate) {
    return "customer-delivery-slot:"
        + Objects.requireNonNull(warehouseId, "Warehouse is required")
        + ":"
        + Objects.requireNonNull(deliveryDate, "Delivery date is required");
  }

  private static String warehouseCapacityKey(UUID warehouseId) {
    return "customer-warehouse-capacity:"
        + Objects.requireNonNull(warehouseId, "Warehouse is required");
  }
}
