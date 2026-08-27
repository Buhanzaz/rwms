package dev.buhanzaz.rwms.logistics.customer.capacity.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.repository.LogisticsTransactionLock;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the shared advisory-lock namespace for every delivery-capacity writer. */
class CustomerDeliveryCapacityFenceTest {
  private final LogisticsTransactionLock locks = mock(LogisticsTransactionLock.class);
  private final CustomerDeliveryCapacityFence fence = new CustomerDeliveryCapacityFence(locks);

  @Test
  void finalHoldAcquiresTheExactDayAndScenarioKeysTogether() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 8, 29);

    fence.acquireDayAndScenario(warehouseId, date);

    verify(locks)
        .acquireAll(
            List.of(
                "customer-delivery-slot:" + warehouseId + ":" + date,
                "customer-scenario-capacity:" + warehouseId));
  }

  @Test
  void onlyWholeDayDeliveryTaskKindsAcquireTheDayFence() {
    UUID warehouseId = UUID.randomUUID();
    LocalDate date = LocalDate.of(2026, 8, 29);

    fence.acquireTaskDay(warehouseId, date, DriverTaskKind.SHIPMENT);
    fence.acquireTaskDay(warehouseId, date, DriverTaskKind.TRANSFER);
    fence.acquireTaskDay(warehouseId, date, DriverTaskKind.RETURN);

    verify(locks, org.mockito.Mockito.times(2))
        .acquire("customer-delivery-slot:" + warehouseId + ":" + date);
    verify(locks, never()).acquire("customer-scenario-capacity:" + warehouseId);
  }
}
