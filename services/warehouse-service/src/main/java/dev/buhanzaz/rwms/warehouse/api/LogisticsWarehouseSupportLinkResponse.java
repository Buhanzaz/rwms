package dev.buhanzaz.rwms.warehouse.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Least-privilege active support edge exposed to logistics with both owner-held coordinates.
 *
 * @param id stable link identity retained by route decisions and audit
 * @param version current link version
 * @param supportWarehouse source identity and coordinates
 * @param servedWarehouse target identity and coordinates
 * @param priority planner preference
 * @param allowDrivers whether drivers may be supplied
 * @param allowVehicles whether vehicles may be supplied
 * @param allowInventory whether cabins or inventory may be supplied
 * @param allowDirectFulfillment whether direct source-to-client fulfillment is allowed
 * @param allowInterwarehouseTransfer whether interwarehouse transfers are allowed
 * @param allowContractorFallback whether contractor fallback is allowed
 * @param allowedWeekdays recurring allowed weekdays
 * @param allowedDates explicit allowed dates
 * @param excludedDates explicit blocked dates
 * @param serviceStart optional inclusive daily start
 * @param serviceEnd optional exclusive daily end
 */
public record LogisticsWarehouseSupportLinkResponse(
    UUID id,
    long version,
    LogisticsWarehouseIdentityResponse supportWarehouse,
    LogisticsWarehouseIdentityResponse servedWarehouse,
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
    LocalTime serviceEnd) {}
