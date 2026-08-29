package dev.buhanzaz.rwms.logistics.customer.routing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityShift;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.DeliveryJob;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers bounded whole-day multi-driver assignment using directed truck travel times. */
class CustomerRouteCapacityPlannerTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000303");
  private static final LocalTime MORNING_START = LocalTime.of(9, 0);
  private static final LocalTime MORNING_END = LocalTime.of(12, 0);
  private static final LocalTime MIDDAY_START = LocalTime.of(12, 0);
  private static final LocalTime MIDDAY_END = LocalTime.of(15, 0);
  private static final LocalTime EVENING_START = LocalTime.of(15, 0);
  private static final LocalTime EVENING_END = LocalTime.of(18, 0);

  private final CustomerRouteCapacityPlanner planner = new CustomerRouteCapacityPlanner();

  @Test
  void distributesDistantMorningPointsAcrossTwoDrivers() {
    var decision =
        evaluateConfigured(
            uniformMatrix(2, 5_400L, 10_000L),
            List.of(
                job(1, 1, MORNING_START, MORNING_END, false),
                job(2, 1, MORNING_START, MORNING_END, true)),
            2,
            1,
            0);

    assertThat(decision.feasible()).isTrue();
  }

  @Test
  void distributesDistantMorningPointsAcrossPublishedDriverShifts() {
    var jobs =
        List.of(
            job(1, 1, MORNING_START, MORNING_END, false),
            job(2, 1, MORNING_START, MORNING_END, true));
    var oneDriver =
        planner.evaluate(
            uniformMatrix(2, 5_400L, 10_000L),
            jobs,
            configuration(),
            List.of(new CapacityShift(LocalTime.of(9, 0), LocalTime.of(18, 0), 30, 1)),
            0);
    var twoDrivers =
        planner.evaluate(
            uniformMatrix(2, 5_400L, 10_000L),
            jobs,
            configuration(),
            List.of(
                new CapacityShift(LocalTime.of(9, 0), LocalTime.of(18, 0), 30, 1),
                new CapacityShift(LocalTime.of(9, 0), LocalTime.of(18, 0), 30, 1)),
            0);

    assertThat(oneDriver.feasible()).isFalse();
    assertThat(twoDrivers.feasible()).isTrue();
  }

  @Test
  void failsClosedWithoutPublishedDriverShifts() {
    var decision =
        planner.evaluate(
            twoPointMatrix(600L, 600L),
            List.of(job(1, 1, MORNING_START, MORNING_END, true)),
            configuration(),
            List.of(),
            0);

    assertThat(decision.feasible()).isFalse();
    assertThat(decision.capacityRemaining()).isZero();
  }

  @Test
  void servesDifferentArrivalWindowsOnOneDriverDay() {
    var decision =
        evaluateConfigured(
            uniformMatrix(2, 600L, 600L),
            List.of(
                job(1, 1, MORNING_START, MORNING_END, false),
                job(2, 1, MIDDAY_START, MIDDAY_END, true)),
            1,
            2,
            0);

    assertThat(decision.feasible()).isTrue();
  }

  @Test
  void insertsNextDeliveryFromTheDriversLastDeliveryFront() {
    var matrix =
        new CustomerTravelTimeMatrix(
            List.of(
                new GeoPoint(55.75, 37.61),
                new GeoPoint(56.75, 38.61),
                new GeoPoint(56.76, 38.62)),
            List.of(
                List.of(0L, 3_600L, 3_600L),
                List.of(3_600L, 0L, 600L),
                List.of(3_600L, 600L, 0L)));

    var decision =
        evaluateConfigured(
            matrix,
            List.of(
                job(1, 1, MORNING_START, MORNING_END, false),
                job(2, 1, MORNING_START, MORNING_END, true)),
            1,
            2,
            0);

    assertThat(decision.feasible()).isTrue();
  }

  @Test
  void returnsToDepotToReloadBeforeAnotherCustomerPoint() {
    var decision =
        evaluateConfigured(
            uniformMatrix(2, 600L, 600L),
            List.of(
                job(1, 1, MORNING_START, MORNING_END, false),
                job(2, 1, MORNING_START, MORNING_END, true)),
            1,
            1,
            0);

    assertThat(decision.feasible()).isTrue();
  }

  @Test
  void splitsTwoCabinDeliveryAcrossSoloTruckTripsWhenTrailerCannotEnter() {
    var decision =
        planner.evaluate(
            twoPointMatrix(600L, 600L),
            List.of(
                new DeliveryJob(
                    1, 2, MORNING_START, MORNING_END, 30, true, false)),
            configuration(),
            List.of(new CapacityShift(LocalTime.of(8, 0), LocalTime.of(20, 0), 0, 2)),
            0);

    assertThat(decision.feasible()).isTrue();
  }

  @Test
  void includesDepotReloadTimeAtTheArrivalWindowBoundary() {
    var decision =
        evaluateConfigured(
            uniformMatrix(2, 2_401L, 2_401L),
            List.of(
                job(1, 1, MORNING_START, MORNING_END, false),
                job(2, 1, MORNING_START, MORNING_END, true)),
            1,
            1,
            0);

    assertThat(decision.feasible()).isFalse();
  }

  @Test
  void rejectsCrossWindowConflictForTheOnlyDriver() {
    var decision =
        evaluateConfigured(
            uniformMatrix(2, 7_200L, 7_200L),
            List.of(
                job(1, 1, MORNING_START, MORNING_END, false),
                job(2, 1, MIDDAY_START, MIDDAY_END, true)),
            1,
            1,
            0);

    assertThat(decision.feasible()).isFalse();
    assertThat(decision.capacityRemaining()).isZero();
  }

  @Test
  void acceptsFourHourTravelZoneAtBoundary() {
    var decision =
        evaluateConfigured(
            twoPointMatrix(14_400L, 14_400L),
            List.of(job(1, 1, EVENING_START, EVENING_END, true)),
            1,
            2,
            0);

    assertThat(decision.feasible()).isTrue();
    assertThat(decision.capacityRemaining()).isEqualTo(1);
  }

  @Test
  void acceptsTravelOutsideTheFormerZoneLimitWhenTheScheduleStillFits() {
    var decision =
        evaluateConfigured(
            twoPointMatrix(14_401L, 14_400L),
            List.of(job(1, 1, EVENING_START, EVENING_END, true)),
            1,
            1,
            0);

    assertThat(decision.feasible()).isTrue();
  }

  @Test
  void rejectsCandidateWhenReturnLegToDepotIsUnreachable() {
    var decision =
        evaluateConfigured(
            twoPointMatrix(600L, -1L),
            List.of(job(1, 1, MORNING_START, MORNING_END, true)),
            1,
            2,
            0);

    assertThat(decision.feasible()).isFalse();
  }

  @Test
  void rejectsReturnAfterConfiguredOvertimeDeadline() {
    var decision =
        evaluateConfigured(
            twoPointMatrix(14_400L, 16_201L),
            List.of(job(1, 1, EVENING_START, EVENING_END, true)),
            1,
            1,
            0);

    assertThat(decision.feasible()).isFalse();
  }

  @Test
  void reservesWholeDriversForExistingDateOnlyTransportTasks() {
    var oneDriverFree =
        evaluateConfigured(
            twoPointMatrix(600L, 600L),
            List.of(job(1, 1, MORNING_START, MORNING_END, true)),
            2,
            1,
            1);
    var noDriverFree =
        evaluateConfigured(
            twoPointMatrix(600L, 600L),
            List.of(job(1, 1, MORNING_START, MORNING_END, true)),
            2,
            1,
            2);

    assertThat(oneDriverFree.feasible()).isTrue();
    assertThat(noDriverFree.feasible()).isFalse();
  }

  @Test
  void failsClosedWhenDemandExceedsBoundedSearchEnvelope() {
    var decision =
        planner.evaluate(
            twoPointMatrix(60L, 60L),
            List.of(job(1, 129, MORNING_START, MORNING_END, true)),
            configuration(),
            java.util.stream.IntStream.range(0, 8)
                .mapToObj(
                    ignored ->
                        new CapacityShift(
                            LocalTime.of(9, 0), LocalTime.of(18, 0), 30, 2))
                .toList(),
            0);

    assertThat(decision.feasible()).isFalse();
    assertThat(decision.capacityRemaining()).isZero();
  }

  @Test
  void rejectsTravelMultiplierBelowTheConservativeFloor() {
    assertThatThrownBy(() -> properties(0.99).validated(WAREHOUSE))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Customer delivery capacity configuration is invalid");
  }

  private static DeliveryJob job(
      int matrixIndex,
      int cabinCount,
      LocalTime windowStart,
      LocalTime windowEnd,
      boolean candidate) {
    return new DeliveryJob(
        matrixIndex, cabinCount, windowStart, windowEnd, 30, candidate, true);
  }

  private static CustomerTravelTimeMatrix twoPointMatrix(long outbound, long inbound) {
    return new CustomerTravelTimeMatrix(
        List.of(new GeoPoint(55.75, 37.61), new GeoPoint(55.80, 37.70)),
        List.of(List.of(0L, outbound), List.of(inbound, 0L)));
  }

  private static CustomerTravelTimeMatrix uniformMatrix(
      int customerPoints, long depotLegSeconds, long customerLegSeconds) {
    List<GeoPoint> points = new ArrayList<>();
    for (int index = 0; index <= customerPoints; index++) {
      points.add(new GeoPoint(55.75 + index * 0.01, 37.61 + index * 0.01));
    }
    List<List<Long>> seconds = new ArrayList<>();
    for (int from = 0; from <= customerPoints; from++) {
      List<Long> row = new ArrayList<>();
      for (int to = 0; to <= customerPoints; to++) {
        if (from == to) row.add(0L);
        else if (from == 0 || to == 0) row.add(depotLegSeconds);
        else row.add(customerLegSeconds);
      }
      seconds.add(List.copyOf(row));
    }
    return new CustomerTravelTimeMatrix(List.copyOf(points), List.copyOf(seconds));
  }

  private CustomerRouteCapacityPlanner.CapacityDecision evaluateConfigured(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      int driverCount,
      int cabinCapacity,
      int reservedDrivers) {
    List<CapacityShift> shifts =
        java.util.stream.IntStream.range(0, driverCount)
            .mapToObj(
                ignored ->
                    new CapacityShift(
                        LocalTime.of(9, 0), LocalTime.of(20, 0), 0, cabinCapacity))
            .toList();
    return planner.evaluate(matrix, jobs, configuration(), shifts, reservedDrivers);
  }

  private static CustomerDeliveryProperties.Validated configuration() {
    return properties(1.0).validated(WAREHOUSE);
  }

  private static CustomerDeliveryProperties properties(double travelTimeMultiplier) {
    return new CustomerDeliveryProperties(
        true,
        List.of(
            new CustomerDeliveryProperties.Depot(
                true,
                WAREHOUSE.toString(),
                BigDecimal.valueOf(55.75),
                BigDecimal.valueOf(37.61))),
        "http://127.0.0.1:8002",
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        30,
        30,
        2,
        31,
        Duration.ofMinutes(10),
        Duration.ofMinutes(20),
        travelTimeMultiplier,
        0,
        LocalTime.of(8, 0),
        LocalTime.of(9, 0),
        LocalTime.of(18, 0),
        LocalTime.of(20, 0),
        180,
        60,
        30,
        30,
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
}
