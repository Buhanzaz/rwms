package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlot;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerDeliverySlotKind;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityShift;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.DeliveryJob;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerVehicleRouteProfile;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import java.time.Clock;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * Produces non-binding delivery-day guidance from the same simulator shifts, generated jobs,
 * durable customer workload and truck route-capacity planner used by exact address-based slots.
 * The depot is used as the provisional destination, so a returned date is an optimistic planning
 * signal rather than an address or route promise.
 */
@Service
@RequiredArgsConstructor
public class CustomerDeliveryEstimateService {
  private static final int MAX_ESTIMATED_DATES = 4;
  private static final int MAX_MATRIX_POINTS = 32;

  private final CustomerDeliveryProperties properties;
  private final LogisticsDependencyGateway dependencies;
  private final CustomerDeliverySlotStore slotStore;
  private final WarehouseCapacityJobRepository warehouseCapacityJobs;
  private final WarehouseCapacityShiftRepository warehouseCapacityShifts;
  private final ValhallaCustomerTravelTimeClient travelTimes;
  private final CustomerRouteCapacityPlanner capacity;
  private final RepresentativeDeliverySlotPolicy representativePolicy;
  private final DriverLogisticsTaskRepository driverTasks;
  private final Clock clock;

  /**
   * Returns up to four earliest warehouse-local days with provisional capacity for one cabin.
   * Representative warehouses additionally require an eligible owner-held support-calendar day.
   */
  public List<LocalDate> estimatedDates(UUID warehouseId) {
    return estimatedDates(warehouseId, now());
  }

  /** Returns guidance at the supplied authoritative instant for presentation consistency. */
  public List<LocalDate> estimatedDates(UUID warehouseId, OffsetDateTime at) {
    WarehouseIdentity warehouse = dependencies.readWarehouseIdentity(warehouseId);
    if (!eligible(warehouse, warehouseId)) return List.of();
    CustomerDeliveryProperties.Validated configuration = configuration(warehouse);
    OffsetDateTime now =
        at.withOffsetSameInstant(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
    LocalDate today = now.toInstant().atZone(ZoneId.of(warehouse.timeZone())).toLocalDate();
    LocalDate first = today.plusDays(configuration.earliestDeliveryDays());
    LocalDate last = today.plusDays(configuration.bookingHorizonDays());
    List<LocalDate> result = new ArrayList<>(MAX_ESTIMATED_DATES);
    for (LocalDate date = first;
        !date.isAfter(last) && result.size() < MAX_ESTIMATED_DATES;
        date = date.plusDays(1)) {
      boolean localCapacity = hasLocalCapacity(warehouse, configuration, date, now);
      if (warehouse.representative()) {
        RepresentativeDeliverySlotPolicy.Decision supportDay =
            representativePolicy.evaluate(
                warehouse,
                date,
                CustomerDeliverySlotKind.DURING_DAY,
                configuration.customerDeliveryStart(),
                configuration.customerDeliveryEnd(),
                false);
        if (supportDay.allowed() && (localCapacity || supportDay.flexibleSupport())) {
          result.add(date);
        }
      } else if (localCapacity) {
        result.add(date);
      }
    }
    return List.copyOf(result);
  }

  private CustomerDeliveryProperties.Validated configuration(WarehouseIdentity warehouse) {
    Map<UUID, CustomerDeliveryProperties.Validated> configured = properties.validatedDepots();
    if (!warehouse.representative() && !configured.containsKey(warehouse.id())) {
      throw new IllegalStateException("Customer delivery warehouse is not configured");
    }
    return properties.validated(warehouse.id(), warehouse.latitude(), warehouse.longitude());
  }

  private boolean hasLocalCapacity(
      WarehouseIdentity warehouse,
      CustomerDeliveryProperties.Validated configuration,
      LocalDate date,
      OffsetDateTime now) {
    List<CustomerDeliverySlot> existing = slotStore.workload(warehouse.id(), date, now);
    List<WarehouseCapacityJob> generated =
        warehouseCapacityJobs.findCapacityWorkload(warehouse.id(), date).stream()
            .filter(job -> job.getTaskType() == WarehouseCapacityTaskType.DELIVERY)
            .toList();
    List<WarehouseCapacityShift> shifts =
        warehouseCapacityShifts.findCapacityShifts(warehouse.id(), date);
    if (shifts.isEmpty()) return false;

    List<GeoPoint> points = new ArrayList<>(existing.size() + generated.size() + 2);
    GeoPoint depot =
        new GeoPoint(
            configuration.depotLatitude().doubleValue(),
            configuration.depotLongitude().doubleValue());
    points.add(depot);
    existing.forEach(
        slot ->
            points.add(
                new GeoPoint(
                    slot.getLatitude().doubleValue(), slot.getLongitude().doubleValue())));
    generated.forEach(
        job ->
            points.add(
                new GeoPoint(
                    job.getLatitude().doubleValue(), job.getLongitude().doubleValue())));
    int candidateIndex = points.size();
    points.add(depot);
    if (points.size() > MAX_MATRIX_POINTS) return false;

    List<DeliveryJob> jobs = new ArrayList<>(existing.size() + generated.size() + 1);
    for (int index = 0; index < existing.size(); index++) {
      CustomerDeliverySlot slot = existing.get(index);
      jobs.add(
          new DeliveryJob(
              index + 1,
              slot.getCabinCount(),
              slot.getWindowStart(),
              slot.getWindowEnd(),
              configuration.serviceMinutes(),
              false,
              slot.getSiteCabinCapacity() >= 2));
    }
    for (int index = 0; index < generated.size(); index++) {
      WarehouseCapacityJob job = generated.get(index);
      jobs.add(
          new DeliveryJob(
              existing.size() + index + 1,
              job.getCabinCount(),
              job.getWindowStart(),
              job.getWindowEnd(),
              job.getServiceMinutes(),
              false,
              job.isTrailerAccessAllowed()));
    }
    jobs.add(
        new DeliveryJob(
            candidateIndex,
            1,
            configuration.customerDeliveryStart(),
            configuration.customerDeliveryEnd(),
            configuration.serviceMinutes(),
            true,
            false));
    List<CapacityShift> capacityShifts =
        shifts.stream()
            .map(
                shift ->
                    new CapacityShift(
                        shift.getShiftStart(),
                        shift.getShiftEnd(),
                        shift.getBreakMinutes(),
                        shift.getCabinCapacity()))
            .toList();
    int reservedDrivers =
        (int)
            Math.min(
                capacityShifts.size(),
                driverTasks.countWholeDayDeliveryReservations(warehouse.id(), date));
    return capacity
        .evaluate(
            travelTimes.matrix(
                points,
                date,
                configuration.customerDeliveryStart(),
                configuration,
                CustomerVehicleRouteProfile.forTripCapacity(configuration, 1)),
            jobs,
            configuration,
            capacityShifts,
            reservedDrivers)
        .feasible();
  }

  private static boolean eligible(WarehouseIdentity warehouse, UUID warehouseId) {
    return warehouse != null
        && warehouseId.equals(warehouse.id())
        && warehouse.active()
        && warehouse.latitude() != null
        && warehouse.longitude() != null
        && warehouse.timeZone() != null
        && !warehouse.timeZone().isBlank();
  }

  private OffsetDateTime now() {
    return OffsetDateTime.now(clock)
        .withOffsetSameInstant(ZoneOffset.UTC)
        .truncatedTo(ChronoUnit.MICROS);
  }
}
