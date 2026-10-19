package dev.buhanzaz.rwms.logistics.inquiry.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliveryEstimateService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies that presentation date selection follows current logistics guidance. */
class ClientDeliveryDatePolicyTest {
  @Test
  void exposesCapacityGuidanceAndRejectsDatesOutsideIt() {
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime instant = OffsetDateTime.parse("2026-08-23T21:30:00Z");
    LocalDate first = LocalDate.of(2026, 8, 27);
    LocalDate second = LocalDate.of(2026, 8, 29);
    CustomerDeliveryEstimateService estimate = mock(CustomerDeliveryEstimateService.class);
    when(estimate.estimatedDates(warehouseId, instant))
        .thenReturn(java.util.List.of(first, second));
    ClientDeliveryDatePolicy policy = new ClientDeliveryDatePolicy(estimate);

    assertThat(policy.requestableDates(warehouseId, instant)).containsExactly(first, second);
    assertThat(
            policy.containsAll(
                warehouseId, instant, java.util.List.of(LocalDate.of(2026, 8, 28))))
        .isFalse();
    assertThat(policy.containsAll(warehouseId, instant, java.util.List.of(first, second))).isTrue();
  }
}
