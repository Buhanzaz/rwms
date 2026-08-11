package dev.buhanzaz.rwms.logistics.order.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import java.time.LocalDate;
import java.util.Objects;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Client-requested inclusive delivery date range. It is advisory and never fences the
 * logistics-assigned shipment schedule.
 *
 * <p>The mapped legacy time columns are retained only to read pre-date-only rows without a data
 * rewrite. They are deliberately not exposed by this value or any public response, and newly
 * created values leave them null.
 */
@Embeddable
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class DesiredDeliveryWindow {
  @Getter
  @Column(name = "start_date", nullable = false)
  private LocalDate startDate;

  @Getter
  @Column(name = "end_date", nullable = false)
  private LocalDate endDate;

  @Column(name = "time_from")
  private java.time.LocalTime legacyTimeFrom;

  @Column(name = "time_to")
  private java.time.LocalTime legacyTimeTo;

  /** Creates one validated advisory receiving date range without a client time interval. */
  public static DesiredDeliveryWindow create(LocalDate startDate, LocalDate endDate) {
    DesiredDeliveryWindow window = new DesiredDeliveryWindow();
    window.startDate = Objects.requireNonNull(startDate, "startDate");
    window.endDate = Objects.requireNonNull(endDate, "endDate");
    if (endDate.isBefore(startDate)) {
      throw new IllegalArgumentException("desired delivery window end date precedes start date");
    }
    return window;
  }

  @Override
  public final boolean equals(Object other) {
    if (this == other) return true;
    if (!(other instanceof DesiredDeliveryWindow window)) return false;
    return Objects.equals(startDate, window.startDate)
        && Objects.equals(endDate, window.endDate);
  }

  @Override
  public final int hashCode() {
    return Objects.hash(startDate, endDate);
  }
}
