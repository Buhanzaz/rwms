package dev.buhanzaz.rwms.warehouse.api;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Replacement input for one directed support edge.
 *
 * @param supportWarehouseId warehouse providing resources
 * @param active whether planners may use the edge
 * @param priority positive preference; lower values are preferred
 * @param allowDrivers whether drivers may be provided
 * @param allowVehicles whether vehicles may be provided
 * @param allowInventory whether cabins or inventory may be provided
 * @param allowDirectFulfillment whether inventory may go directly to a regional client
 * @param allowInterwarehouseTransfer whether interwarehouse transfers are allowed
 * @param allowContractorFallback whether a contractor may be proposed as fallback
 * @param allowedWeekdays recurring weekdays; empty means every weekday
 * @param allowedDates explicit one-day allowances
 * @param excludedDates date exceptions that always block the edge
 * @param serviceStart optional inclusive daily start
 * @param serviceEnd optional exclusive daily end
 */
public record WarehouseSupportLinkInput(
    @NotNull UUID supportWarehouseId,
    boolean active,
    @Min(1) int priority,
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

  /** Normalizes optional calendar collections to immutable empty sets. */
  public WarehouseSupportLinkInput {
    allowedWeekdays = allowedWeekdays == null ? Set.of() : Set.copyOf(allowedWeekdays);
    allowedDates = allowedDates == null ? Set.of() : Set.copyOf(allowedDates);
    excludedDates = excludedDates == null ? Set.of() : Set.copyOf(excludedDates);
  }
}
