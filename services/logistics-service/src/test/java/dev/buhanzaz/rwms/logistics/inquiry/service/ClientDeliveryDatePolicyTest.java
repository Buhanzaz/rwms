package dev.buhanzaz.rwms.logistics.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the warehouse-local client horizon that locks today and tomorrow. */
class ClientDeliveryDatePolicyTest {
  @Test
  void exposesOnlyDaysTwoThroughFiveAndRejectsTomorrow() {
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime instant = OffsetDateTime.parse("2026-08-23T21:30:00Z");
    LocalDate warehouseToday = LocalDate.of(2026, 8, 24);
    LogisticsWarehouseLifecycle lifecycle = mock(LogisticsWarehouseLifecycle.class);
    when(lifecycle.localDateAt(warehouseId, instant)).thenReturn(warehouseToday);
    ClientDeliveryDatePolicy policy = new ClientDeliveryDatePolicy(lifecycle);

    assertThat(policy.requestableDates(warehouseId, instant))
        .containsExactly(
            LocalDate.of(2026, 8, 26),
            LocalDate.of(2026, 8, 27),
            LocalDate.of(2026, 8, 28),
            LocalDate.of(2026, 8, 29));
    assertThat(
            policy.containsAll(
                warehouseId, instant, java.util.List.of(warehouseToday.plusDays(1))))
        .isFalse();
    assertThat(
            policy.containsAll(
                warehouseId,
                instant,
                java.util.List.of(warehouseToday.plusDays(2), warehouseToday.plusDays(5))))
        .isTrue();
  }
}
