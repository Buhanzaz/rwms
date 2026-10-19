package dev.buhanzaz.rwms.logistics.inquiry.service;

import dev.buhanzaz.rwms.logistics.customer.service.CustomerDeliveryEstimateService;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Exposes the capacity-aware, non-binding delivery-day guidance a presentation client may select.
 * Address routing and capacity reservation still happen only in the authenticated delivery flow.
 */
@Component
@RequiredArgsConstructor
public class ClientDeliveryDatePolicy {
  private final CustomerDeliveryEstimateService deliveryEstimate;

  /** Returns up to four currently estimated warehouse-local dates for one presentation. */
  public List<LocalDate> requestableDates(UUID warehouseId, OffsetDateTime at) {
    return deliveryEstimate.estimatedDates(warehouseId, at);
  }

  /** Returns whether every independently selected day belongs to the current requestable horizon. */
  public boolean containsAll(
      UUID warehouseId, OffsetDateTime at, List<LocalDate> selectedDates) {
    if (selectedDates == null || selectedDates.isEmpty()) return false;
    return requestableDates(warehouseId, at).containsAll(selectedDates);
  }
}
