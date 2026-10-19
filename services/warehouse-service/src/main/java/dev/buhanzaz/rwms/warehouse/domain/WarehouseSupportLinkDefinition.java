package dev.buhanzaz.rwms.warehouse.domain;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable policy of one directed warehouse-support link.
 *
 * <p>An empty weekday set means every weekday. Explicit allowed dates grant a one-day exception;
 * excluded dates always win. A daily service window is either absent or an inclusive-start,
 * exclusive-end pair.
 *
 * @param active whether planners may use the link
 * @param priority positive planner preference; lower values are preferred
 * @param allowDrivers whether the source may provide drivers
 * @param allowVehicles whether the source may provide vehicles
 * @param allowInventory whether the source may provide cabins or other inventory
 * @param allowDirectFulfillment whether source inventory may go directly to a served-region client
 * @param allowInterwarehouseTransfer whether a physical interwarehouse transfer may be created
 * @param allowContractorFallback whether the served warehouse may fall back to a contractor
 * @param allowedWeekdays recurring allowed weekdays; empty means every weekday
 * @param allowedDates explicit allowed service dates
 * @param excludedDates dates that are unavailable regardless of other allowances
 * @param serviceStart optional inclusive daily start
 * @param serviceEnd optional exclusive daily end
 */
public record WarehouseSupportLinkDefinition(
    boolean active,
    int priority,
    boolean allowDrivers,
    boolean allowVehicles,
    boolean allowInventory,
    boolean allowDirectFulfillment,
    boolean allowInterwarehouseTransfer,
    boolean allowContractorFallback,
    Set<DayOfWeek> allowedWeekdays,
    Set<LocalDate> allowedDates,
    Set<LocalDate> excludedDates,
    LocalTime serviceStart,
    LocalTime serviceEnd) {

  /** Normalizes collections and enforces the policy's time and priority invariants. */
  public WarehouseSupportLinkDefinition {
    if (priority <= 0) throw new IllegalArgumentException("priority must be positive");
    allowedWeekdays = immutableSet(allowedWeekdays, "allowedWeekdays");
    allowedDates = immutableSet(allowedDates, "allowedDates");
    excludedDates = immutableSet(excludedDates, "excludedDates");
    if ((serviceStart == null) != (serviceEnd == null)) {
      throw new IllegalArgumentException("serviceStart and serviceEnd must be supplied together");
    }
    if (serviceStart != null && !serviceStart.isBefore(serviceEnd)) {
      throw new IllegalArgumentException("serviceStart must be before serviceEnd");
    }
  }

  /**
   * Tests the calendar policy at the served warehouse's local date and time.
   *
   * @param date served-warehouse local date
   * @param time served-warehouse local time
   * @return whether the active link permits service at that instant
   */
  public boolean allows(LocalDate date, LocalTime time) {
    Objects.requireNonNull(date, "date is required");
    Objects.requireNonNull(time, "time is required");
    if (!active || excludedDates.contains(date)) return false;
    if (serviceStart != null && (time.isBefore(serviceStart) || !time.isBefore(serviceEnd))) {
      return false;
    }
    return allowedDates.contains(date)
        || allowedWeekdays.isEmpty()
        || allowedWeekdays.contains(date.getDayOfWeek());
  }

  private static <T> Set<T> immutableSet(Set<T> values, String field) {
    if (values == null || values.isEmpty()) return Set.of();
    if (values.stream().anyMatch(Objects::isNull)) {
      throw new IllegalArgumentException(field + " must not contain null");
    }
    return Set.copyOf(new LinkedHashSet<>(values));
  }
}
