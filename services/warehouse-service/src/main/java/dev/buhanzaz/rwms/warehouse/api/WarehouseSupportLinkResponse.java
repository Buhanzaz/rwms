package dev.buhanzaz.rwms.warehouse.api;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.Set;
import java.util.UUID;

/**
 * Public administrative projection of one warehouse support edge.
 *
 * @param id stable support-link identity
 * @param version support-link optimistic version
 * @param supportWarehouseId resource-providing warehouse
 * @param servedWarehouseId warehouse being served
 * @param active whether planners may use the edge
 * @param priority positive planner preference
 * @param allowDrivers whether drivers may be provided
 * @param allowVehicles whether vehicles may be provided
 * @param allowInventory whether cabins or inventory may be provided
 * @param allowDirectFulfillment whether direct source-to-client fulfillment is allowed
 * @param allowInterwarehouseTransfer whether interwarehouse transfers are allowed
 * @param allowContractorFallback whether contractor fallback is allowed
 * @param allowedWeekdays recurring allowed weekdays
 * @param allowedDates explicit allowed dates
 * @param excludedDates explicit blocked dates
 * @param serviceStart optional inclusive daily start
 * @param serviceEnd optional exclusive daily end
 */
public record WarehouseSupportLinkResponse(
    UUID id,
    long version,
    UUID supportWarehouseId,
    UUID servedWarehouseId,
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
    LocalTime serviceEnd) {}
