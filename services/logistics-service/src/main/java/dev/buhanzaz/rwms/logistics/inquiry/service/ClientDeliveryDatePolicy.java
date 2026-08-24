package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.service.LogisticsWarehouseLifecycle;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.stream.LongStream;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Computes the warehouse-local calendar days an ordinary client may request for initial delivery.
 * Today and tomorrow are intentionally locked; the fifth day after confirmation is the deadline.
 */
@Component
@RequiredArgsConstructor
public class ClientDeliveryDatePolicy {
  private static final long FIRST_REQUESTABLE_DAY_OFFSET = 2;
  private static final long LAST_REQUESTABLE_DAY_OFFSET = 5;

  private final LogisticsWarehouseLifecycle warehouseLifecycle;

  /** Returns the four requestable calendar days for one warehouse and authoritative instant. */
  public List<LocalDate> requestableDates(UUID warehouseId, OffsetDateTime at) {
    LocalDate today = warehouseLifecycle.localDateAt(warehouseId, at);
    return LongStream.rangeClosed(FIRST_REQUESTABLE_DAY_OFFSET, LAST_REQUESTABLE_DAY_OFFSET)
        .mapToObj(today::plusDays)
        .toList();
  }

  /** Returns whether every independently selected day belongs to the current requestable horizon. */
  public boolean containsAll(
      UUID warehouseId, OffsetDateTime at, List<LocalDate> selectedDates) {
    if (selectedDates == null || selectedDates.isEmpty()) return false;
    return requestableDates(warehouseId, at).containsAll(selectedDates);
  }
}
