package dev.buhanzaz.rwms.logistics.customer.domain;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies the persisted CustomerApp delivery-ring invariant at the domain boundary. */
class CustomerDeliverySlotTest {

  @Test
  void acceptsOnlyTheFourSupportedHourlyTravelZones() {
    assertDoesNotThrow(() -> offer(1));
    assertDoesNotThrow(() -> offer(4));
    assertThrows(IllegalArgumentException.class, () -> offer(0));
    assertThrows(IllegalArgumentException.class, () -> offer(5));
  }

  private static CustomerDeliverySlot offer(int travelZoneHours) {
    return CustomerDeliverySlot.offer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        LocalDate.of(2026, 8, 28),
        LocalTime.of(9, 0),
        LocalTime.of(12, 0),
        "Москва, Тестовая улица, 1",
        new BigDecimal("55.750000"),
        new BigDecimal("37.610000"),
        1,
        3_600,
        travelZoneHours,
        0,
        OffsetDateTime.parse("2026-08-27T09:00:00Z"));
  }
}
