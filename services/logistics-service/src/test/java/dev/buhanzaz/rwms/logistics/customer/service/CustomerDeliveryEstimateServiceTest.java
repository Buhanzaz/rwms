package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityShift;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityJob;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityTaskType;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.WarehouseCapacityShiftRepository;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerVehicleRouteProfile;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies that catalogue guidance skips route-capacity days rejected by current workload. */
class CustomerDeliveryEstimateServiceTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("c89b65f1-2891-4176-bd88-1d231e869a25");

  @Test
  void returnsFourEarliestFeasibleDatesAfterASaturatedDay() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    CustomerDeliverySlotStore slots = mock(CustomerDeliverySlotStore.class);
    WarehouseCapacityJobRepository jobs = mock(WarehouseCapacityJobRepository.class);
    WarehouseCapacityShiftRepository shifts = mock(WarehouseCapacityShiftRepository.class);
    ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    RepresentativeDeliverySlotPolicy representativePolicy =
        mock(RepresentativeDeliverySlotPolicy.class);
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    CustomerDeliveryProperties properties = properties();
    WarehouseCapacityShift shift = mock(WarehouseCapacityShift.class);
    when(shift.getShiftStart()).thenReturn(LocalTime.of(8, 0));
    when(shift.getShiftEnd()).thenReturn(LocalTime.of(20, 0));
    when(shift.getBreakMinutes()).thenReturn(30);
    when(shift.getCabinCapacity()).thenReturn(1);
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(
            new WarehouseIdentity(
                WAREHOUSE,
                0,
                true,
                "Москва Север",
                "Москва",
                "Складская улица",
                BigDecimal.valueOf(55.75),
                BigDecimal.valueOf(37.61),
                "Europe/Moscow",
                false));
    when(slots.workload(eq(WAREHOUSE), any(), any())).thenReturn(List.of());
    List<WarehouseCapacityJob> publishedJobs = capacityJobs(31);
    when(jobs.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(publishedJobs);
    when(shifts.findCapacityShifts(eq(WAREHOUSE), any())).thenReturn(List.of(shift));
    when(driverTasks.countWholeDayDeliveryReservations(eq(WAREHOUSE), any())).thenReturn(1L);
    CustomerTravelTimeMatrix matrix = matrix(33);
    when(travelTimes.matrix(
            anyList(),
            any(),
            any(),
            any(CustomerDeliveryProperties.Validated.class),
            any(CustomerVehicleRouteProfile.class)))
        .thenReturn(matrix);
    when(capacity.evaluate(same(matrix), anyList(), any(), anyList(), anyInt()))
        .thenReturn(
            new CapacityDecision(false, 0),
            new CapacityDecision(true, 0),
            new CapacityDecision(true, 0),
            new CapacityDecision(true, 0),
            new CapacityDecision(true, 0));
    CustomerDeliveryEstimateService service =
        new CustomerDeliveryEstimateService(
            properties,
            dependencies,
            slots,
            jobs,
            shifts,
            travelTimes,
            capacity,
            representativePolicy,
            driverTasks,
            Clock.fixed(Instant.parse("2026-08-26T08:00:00Z"), ZoneOffset.UTC));

    assertThat(service.estimatedDates(WAREHOUSE))
        .containsExactly(
            LocalDate.of(2026, 8, 28),
            LocalDate.of(2026, 8, 29),
            LocalDate.of(2026, 8, 30),
            LocalDate.of(2026, 8, 31));
    verify(driverTasks)
        .countWholeDayDeliveryReservations(WAREHOUSE, LocalDate.of(2026, 8, 27));
  }

  @Test
  void representativeGuidanceKeepsOnlyDaysWithConfirmedLocalCapacity() {
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    CustomerDeliverySlotStore slots = mock(CustomerDeliverySlotStore.class);
    WarehouseCapacityJobRepository jobs = mock(WarehouseCapacityJobRepository.class);
    WarehouseCapacityShiftRepository shifts = mock(WarehouseCapacityShiftRepository.class);
    ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    RepresentativeDeliverySlotPolicy representativePolicy =
        new RepresentativeDeliverySlotPolicy();
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    WarehouseCapacityShift shift = mock(WarehouseCapacityShift.class);
    when(shift.getShiftStart()).thenReturn(LocalTime.of(8, 0));
    when(shift.getShiftEnd()).thenReturn(LocalTime.of(20, 0));
    when(shift.getBreakMinutes()).thenReturn(30);
    when(shift.getCabinCapacity()).thenReturn(1);
    WarehouseIdentity representative =
        new WarehouseIdentity(
            WAREHOUSE,
            0,
            true,
            "Москва Представительство",
            "Москва",
            "Складская улица",
            BigDecimal.valueOf(55.75),
            BigDecimal.valueOf(37.61),
            "Europe/Moscow",
            true);
    when(dependencies.readWarehouseIdentity(WAREHOUSE)).thenReturn(representative);
    when(slots.workload(eq(WAREHOUSE), any(), any())).thenReturn(List.of());
    when(jobs.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(List.of());
    when(shifts.findCapacityShifts(eq(WAREHOUSE), any())).thenReturn(List.of(shift));
    CustomerTravelTimeMatrix matrix = matrix(2);
    when(travelTimes.matrix(
            anyList(),
            any(),
            any(),
            any(CustomerDeliveryProperties.Validated.class),
            any(CustomerVehicleRouteProfile.class)))
        .thenReturn(matrix);
    when(capacity.evaluate(same(matrix), anyList(), any(), anyList(), anyInt()))
        .thenReturn(
            new CapacityDecision(false, 0),
            new CapacityDecision(true, 0),
            new CapacityDecision(false, 0),
            new CapacityDecision(false, 0),
            new CapacityDecision(true, 0));
    CustomerDeliveryEstimateService service =
        new CustomerDeliveryEstimateService(
            properties(),
            dependencies,
            slots,
            jobs,
            shifts,
            travelTimes,
            capacity,
            representativePolicy,
            driverTasks,
            Clock.fixed(Instant.parse("2026-08-26T08:00:00Z"), ZoneOffset.UTC));

    assertThat(service.estimatedDates(WAREHOUSE))
        .containsExactly(LocalDate.of(2026, 8, 28), LocalDate.of(2026, 8, 31));
  }

  private static CustomerDeliveryProperties properties() {
    return new CustomerDeliveryProperties(
        true,
        List.of(
            new CustomerDeliveryProperties.Depot(
                true,
                WAREHOUSE.toString(),
                BigDecimal.valueOf(55.75),
                BigDecimal.valueOf(37.61))),
        "http://127.0.0.1:8002",
        "test-routing-data-v1",
        Duration.ofMinutes(15),
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        30,
        60,
        1,
        5,
        Duration.ofMinutes(10),
        Duration.ofMinutes(10),
        1.15,
        5,
        LocalTime.of(8, 0),
        LocalTime.of(9, 0),
        LocalTime.of(18, 0),
        LocalTime.of(20, 0),
        180,
        60,
        30,
        45,
        30,
        CustomerDeliveryProperties.DeliveryWindowSemantics.START_WITHIN_SLOT,
        CustomerDeliveryProperties.PickupPolicy.RETURN_LEG_ONLY,
        4.0,
        2.5,
        10.0,
        12.0,
        8.0,
        2,
        4.0,
        2.5,
        12.0,
        20.0,
        8.0,
        3);
  }

  private static List<WarehouseCapacityJob> capacityJobs(int count) {
    return java.util.stream.IntStream.range(0, count)
        .mapToObj(
            index -> {
              WarehouseCapacityJob job = mock(WarehouseCapacityJob.class);
              when(job.getSourceJobId())
                  .thenReturn(new UUID(0L, Integer.toUnsignedLong(index + 1)));
              when(job.getTaskType()).thenReturn(WarehouseCapacityTaskType.DELIVERY);
              when(job.getDeliveryDate()).thenReturn(LocalDate.of(2026, 8, 27));
              when(job.getLatitude())
                  .thenReturn(BigDecimal.valueOf(55.70 + index / 10_000.0));
              when(job.getLongitude())
                  .thenReturn(BigDecimal.valueOf(37.60 + index / 10_000.0));
              when(job.getCabinCount()).thenReturn(1);
              when(job.getWindowStart()).thenReturn(LocalTime.of(9, 0));
              when(job.getWindowEnd()).thenReturn(LocalTime.of(18, 0));
              when(job.getServiceMinutes()).thenReturn(30);
              when(job.isTrailerAccessAllowed()).thenReturn(true);
              when(job.getPriority()).thenReturn(index);
              when(job.isMandatory()).thenReturn(true);
              return job;
            })
        .toList();
  }

  private static CustomerTravelTimeMatrix matrix(int pointCount) {
    List<GeoPoint> points =
        java.util.stream.IntStream.range(0, pointCount)
            .mapToObj(index -> new GeoPoint(55.70 + index / 10_000.0, 37.60))
            .toList();
    List<List<Long>> seconds =
        java.util.stream.IntStream.range(0, pointCount)
            .mapToObj(
                from ->
                    java.util.stream.IntStream.range(0, pointCount)
                        .mapToObj(to -> from == to ? 0L : 1_800L)
                        .toList())
            .toList();
    return new CustomerTravelTimeMatrix(points, seconds);
  }
}
