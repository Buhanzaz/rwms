package dev.buhanzaz.rwms.logistics.customer.routing;

import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import java.time.LocalTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import org.springframework.stereotype.Component;

/**
 * Evaluates a complete local delivery day over the directed Valhalla truck-time matrix. Drivers
 * may visit several customer points in different arrival windows and return to the depot to reload
 * before another route cycle. Search is memoized and fails closed after a deterministic state
 * ceiling; it never materializes stop permutations or reports speculative capacity.
 */
@Component
public class CustomerRouteCapacityPlanner {
  private static final int DEPOT_INDEX = 0;
  private static final int MAX_ROUTABLE_CABINS = 128;
  private static final int MAX_SEARCH_STATES = 200_000;
  private static final Comparator<DriverState> DRIVER_ORDER =
      Comparator.comparingLong(DriverState::availableAt)
          .thenComparingInt(DriverState::matrixIndex)
          .thenComparingInt(DriverState::cabinsOnTruck)
          .thenComparingInt(DriverState::cabinCapacity)
          .thenComparingLong(DriverState::deadline);

  /** One existing or candidate delivery demand at a matrix point and hard arrival window. */
  public record DeliveryJob(
      int matrixIndex,
      int cabinCount,
      LocalTime windowStart,
      LocalTime windowEnd,
      int serviceMinutes,
      boolean candidate,
      boolean trailerAccessAllowed) {}

  /** One anonymous active shift with its exact local availability and transport capability. */
  public record CapacityShift(
      LocalTime shiftStart,
      LocalTime shiftEnd,
      int breakMinutes,
      int cabinCapacity) {}

  /** Feasibility result and additional candidate-point cabins supported by the same day. */
  public record CapacityDecision(boolean feasible, int capacityRemaining) {}

  /**
   * Evaluates the complete day against the published simulator shifts after reserving whole shifts
   * for active transport work whose exact service window is not known to RWMS. An empty shift set
   * fails closed. Capacity probing uses at most seven memoized searches for the supported
   * 128-cabin safety ceiling and therefore remains bounded for API traffic.
   */
  public CapacityDecision evaluate(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      CustomerDeliveryProperties.Validated configuration,
      List<CapacityShift> shifts,
      int reservedDrivers) {
    validateInputs(matrix, jobs, configuration, shifts, reservedDrivers);
    DeliveryJob candidate = jobs.stream().filter(DeliveryJob::candidate).findFirst().orElseThrow();
    long oneWaySeconds =
        adjustedTravelSeconds(
            matrix.travelSeconds(DEPOT_INDEX, candidate.matrixIndex()), configuration);
    if (oneWaySeconds < 0) {
      return new CapacityDecision(false, 0);
    }
    int conservativeTripCapacity =
        jobs.stream().allMatch(DeliveryJob::trailerAccessAllowed) ? 2 : 1;
    List<CapacityShift> availableShifts =
        availableShifts(shifts, reservedDrivers, conservativeTripCapacity, configuration);
    if (availableShifts.isEmpty()
        || !isFeasible(matrix, jobs, configuration, availableShifts)) {
      return new CapacityDecision(false, 0);
    }

    int originalCandidateCabins = candidate.cabinCount();
    int upperCandidateCabins =
        Math.min(MAX_ROUTABLE_CABINS, maximumFleetCabins(configuration, availableShifts));
    int low = originalCandidateCabins;
    int high = Math.max(low, upperCandidateCabins);
    while (low < high) {
      int probe = low + (high - low + 1) / 2;
      if (isFeasible(
          matrix,
          withCandidateCabins(jobs, probe),
          configuration,
          availableShifts)) {
        low = probe;
      } else {
        high = probe - 1;
      }
    }
    return new CapacityDecision(true, low - originalCandidateCabins);
  }

  private static void validateInputs(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      CustomerDeliveryProperties.Validated configuration,
      List<CapacityShift> shifts,
      int reservedDrivers) {
    if (matrix == null || configuration == null || jobs == null || jobs.isEmpty()) {
      throw new IllegalArgumentException("Delivery matrix, configuration and jobs are required");
    }
    if (jobs.stream().filter(DeliveryJob::candidate).count() != 1) {
      throw new IllegalArgumentException("Exactly one candidate delivery job is required");
    }
    if (shifts == null || reservedDrivers < 0 || reservedDrivers > shifts.size()) {
      throw new IllegalArgumentException("Reserved driver count is invalid");
    }
    for (CapacityShift shift : shifts) {
      if (shift == null
          || shift.shiftStart() == null
          || shift.shiftEnd() == null
          || !shift.shiftStart().isBefore(shift.shiftEnd())
          || shift.breakMinutes() < 0
          || shift.breakMinutes()
              >= java.time.Duration.between(shift.shiftStart(), shift.shiftEnd()).toMinutes()
          || shift.cabinCapacity() < 1
          || shift.cabinCapacity() > 2) {
        throw new IllegalArgumentException("Capacity shift is invalid");
      }
    }
    for (DeliveryJob job : jobs) {
      if (job.matrixIndex() <= DEPOT_INDEX
          || job.matrixIndex() >= matrix.points().size()
          || job.cabinCount() < 1
          || job.windowStart() == null
          || job.windowEnd() == null
          || job.serviceMinutes() < 1
          || !job.windowStart().isBefore(job.windowEnd())) {
        throw new IllegalArgumentException("Delivery job is invalid");
      }
    }
  }

  private static int maximumFleetCabins(
      CustomerDeliveryProperties.Validated configuration, List<CapacityShift> shifts) {
    long serviceSeconds = configuration.serviceMinutes() * 60L;
    long maximum = 0;
    for (CapacityShift shift : shifts) {
      long operationalSeconds =
          effectiveDeadline(shift, configuration)
              - Math.max(
                  shift.shiftStart().toSecondOfDay(),
                  configuration.driverWorkStart().toSecondOfDay());
      long maximumVisits = operationalSeconds / serviceSeconds + 1L;
      maximum +=
          Math.min(MAX_ROUTABLE_CABINS, maximumVisits)
              * Math.min(MAX_ROUTABLE_CABINS, shift.cabinCapacity());
    }
    return (int) Math.min(MAX_ROUTABLE_CABINS, maximum);
  }

  private static List<DeliveryJob> withCandidateCabins(
      List<DeliveryJob> jobs, int candidateCabins) {
    return jobs.stream()
        .map(
            job ->
                job.candidate()
                    ? new DeliveryJob(
                        job.matrixIndex(),
                        candidateCabins,
                        job.windowStart(),
                        job.windowEnd(),
                        job.serviceMinutes(),
                        true,
                        job.trailerAccessAllowed())
                    : job)
        .toList();
  }

  private static boolean isFeasible(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      CustomerDeliveryProperties.Validated configuration,
      List<CapacityShift> shifts) {
    long totalCabins = jobs.stream().mapToLong(DeliveryJob::cabinCount).sum();
    if (totalCabins > MAX_ROUTABLE_CABINS) return false;
    long minimumServiceSeconds =
        jobs.stream().mapToLong(job -> job.serviceMinutes() * 60L).min().orElseThrow();
    long maximumVisits =
        shifts.stream()
            .mapToLong(
                shift ->
                    (effectiveDeadline(shift, configuration)
                                - Math.max(
                                    shift.shiftStart().toSecondOfDay(),
                                    configuration.driverWorkStart().toSecondOfDay()))
                            / minimumServiceSeconds
                        + 1L)
            .sum();
    long minimumVisits =
        jobs.stream()
            .mapToLong(
                job ->
                    (job.cabinCount()
                            + shifts.stream().mapToInt(CapacityShift::cabinCapacity).max().orElse(1)
                            - 1L)
                        / shifts.stream().mapToInt(CapacityShift::cabinCapacity).max().orElse(1))
            .sum();
    if (minimumVisits > maximumVisits) return false;

    int[] remaining = jobs.stream().mapToInt(DeliveryJob::cabinCount).toArray();
    List<DriverState> drivers = new ArrayList<>(shifts.size());
    for (CapacityShift shift : shifts) {
      long loadedAt =
          Math.max(
                  shift.shiftStart().toSecondOfDay(),
                  configuration.driverWorkStart().toSecondOfDay())
              + configuration.warehouseLoadMinutes(shift.cabinCapacity()) * 60L;
      drivers.add(
          new DriverState(
              loadedAt,
              DEPOT_INDEX,
              shift.cabinCapacity(),
              shift.cabinCapacity(),
              effectiveDeadline(shift, configuration)));
    }
    drivers.sort(DRIVER_ORDER);
    SearchBudget budget = new SearchBudget(MAX_SEARCH_STATES);
    Set<SearchKey> failed = new HashSet<>();
    return search(
        matrix,
        jobs,
        remaining,
        List.copyOf(drivers),
        configuration,
        failed,
        budget);
  }

  private static boolean search(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      int[] remaining,
      List<DriverState> drivers,
      CustomerDeliveryProperties.Validated configuration,
      Set<SearchKey> failed,
      SearchBudget budget) {
    if (Arrays.stream(remaining).allMatch(count -> count == 0)) {
      return canReturnAllDrivers(matrix, drivers, configuration);
    }
    if (!budget.tryVisit()) return false;
    SearchKey key = new SearchKey(toList(remaining), drivers);
    if (failed.contains(key)) return false;

    List<Integer> jobOrder = new ArrayList<>();
    for (int index = 0; index < jobs.size(); index++) {
      if (remaining[index] > 0) jobOrder.add(index);
    }
    jobOrder.sort(
        Comparator.comparing((Integer index) -> jobs.get(index).windowEnd())
            .thenComparing(index -> jobs.get(index).windowStart())
            .thenComparingInt(index -> jobs.get(index).matrixIndex()));

    for (int jobIndex : jobOrder) {
      DeliveryJob job = jobs.get(jobIndex);
      DriverState previousDriver = null;
      for (int driverIndex = 0; driverIndex < drivers.size(); driverIndex++) {
        DriverState driver = drivers.get(driverIndex);
        if (driver.equals(previousDriver)) continue;
        previousDriver = driver;
        if (tryDelivery(
            matrix,
            jobs,
            remaining,
            drivers,
            configuration,
            failed,
            budget,
            jobIndex,
            driverIndex,
            job,
            driver,
            false)) {
          return true;
        }
        if (driver.matrixIndex() != DEPOT_INDEX
            && tryDelivery(
                matrix,
                jobs,
                remaining,
                drivers,
                configuration,
                failed,
                budget,
                jobIndex,
                driverIndex,
                job,
                driver,
                true)) {
          return true;
        }
      }
    }
    failed.add(key);
    return false;
  }

  private static boolean tryDelivery(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      int[] remaining,
      List<DriverState> drivers,
      CustomerDeliveryProperties.Validated configuration,
      Set<SearchKey> failed,
      SearchBudget budget,
      int jobIndex,
      int driverIndex,
      DeliveryJob job,
      DriverState driver,
      boolean reloadAtDepot) {
    long departure = driver.availableAt();
    int departurePoint = driver.matrixIndex();
    int cabinsOnTruck = driver.cabinsOnTruck();
    if (reloadAtDepot) {
      long depotLeg =
          adjustedTravelSeconds(
              matrix.travelSeconds(departurePoint, DEPOT_INDEX), configuration);
      if (depotLeg < 0) return false;
      departure +=
          depotLeg + configuration.warehouseLoadMinutes(driver.cabinCapacity()) * 60L;
      departurePoint = DEPOT_INDEX;
      cabinsOnTruck = driver.cabinCapacity();
    }
    if (cabinsOnTruck < 1) return false;
    long customerLeg =
        adjustedTravelSeconds(
            matrix.travelSeconds(departurePoint, job.matrixIndex()), configuration);
    if (customerLeg < 0) return false;
    long serviceStart =
        Math.max(departure + customerLeg, job.windowStart().toSecondOfDay());
    if (serviceStart > job.windowEnd().toSecondOfDay()) return false;
    long serviceEnd = serviceStart + job.serviceMinutes() * 60L;
    long returnLeg =
        adjustedTravelSeconds(
            matrix.travelSeconds(job.matrixIndex(), DEPOT_INDEX), configuration);
    if (returnLeg < 0 || serviceEnd + returnLeg > driver.deadline()) return false;

    int maximumQuantity = Math.min(cabinsOnTruck, remaining[jobIndex]);
    for (int quantity = maximumQuantity; quantity >= 1; quantity--) {
      int[] nextRemaining = remaining.clone();
      nextRemaining[jobIndex] -= quantity;
      List<DriverState> nextDrivers = new ArrayList<>(drivers);
      nextDrivers.set(
          driverIndex,
          new DriverState(
              serviceEnd,
              job.matrixIndex(),
              cabinsOnTruck - quantity,
              driver.cabinCapacity(),
              driver.deadline()));
      nextDrivers.sort(DRIVER_ORDER);
      if (search(
          matrix,
          jobs,
          nextRemaining,
          List.copyOf(nextDrivers),
          configuration,
          failed,
          budget)) {
        return true;
      }
    }
    return false;
  }

  private static boolean canReturnAllDrivers(
      CustomerTravelTimeMatrix matrix,
      List<DriverState> drivers,
      CustomerDeliveryProperties.Validated configuration) {
    for (DriverState driver : drivers) {
      if (driver.matrixIndex() == DEPOT_INDEX) continue;
      long returnLeg =
          adjustedTravelSeconds(
              matrix.travelSeconds(driver.matrixIndex(), DEPOT_INDEX), configuration);
      if (returnLeg < 0 || driver.availableAt() + returnLeg > driver.deadline()) return false;
    }
    return true;
  }

  private static long effectiveDeadline(
      CapacityShift shift, CustomerDeliveryProperties.Validated configuration) {
    return Math.min(
            shift.shiftEnd().toSecondOfDay(), configuration.driverShiftEnd().toSecondOfDay())
        - shift.breakMinutes() * 60L;
  }

  private static List<CapacityShift> availableShifts(
      List<CapacityShift> shifts,
      int reservedDrivers,
      int conservativeTripCapacity,
      CustomerDeliveryProperties.Validated configuration) {
    List<CapacityShift> constrained =
        shifts.stream()
            .map(
                shift ->
                    new CapacityShift(
                        shift.shiftStart(),
                        shift.shiftEnd(),
                        shift.breakMinutes(),
                        Math.min(shift.cabinCapacity(), conservativeTripCapacity)))
            .toList();
    Comparator<CapacityShift> strongestFirst =
        Comparator.comparingLong(
                (CapacityShift shift) ->
                    (effectiveDeadline(shift, configuration)
                            - Math.max(
                                shift.shiftStart().toSecondOfDay(),
                                configuration.driverWorkStart().toSecondOfDay()))
                        * shift.cabinCapacity())
            .reversed()
            .thenComparing(CapacityShift::shiftStart)
            .thenComparing(CapacityShift::shiftEnd);
    return constrained.stream().sorted(strongestFirst).skip(reservedDrivers).toList();
  }

  private static long adjustedTravelSeconds(
      long rawSeconds, CustomerDeliveryProperties.Validated configuration) {
    if (rawSeconds < 0) return -1;
    if (rawSeconds == 0) return 0;
    return (long) Math.ceil(rawSeconds * configuration.travelTimeMultiplier())
        + configuration.fixedTravelBufferMinutes() * 60L;
  }

  private static List<Integer> toList(int[] values) {
    return Arrays.stream(values).boxed().toList();
  }

  /** Canonical per-driver scheduling state; driver identity is intentionally irrelevant. */
  private record DriverState(
      long availableAt,
      int matrixIndex,
      int cabinsOnTruck,
      int cabinCapacity,
      long deadline) {}

  /** Memoization key for all remaining demands and symmetrically sorted driver states. */
  private record SearchKey(List<Integer> remaining, List<DriverState> drivers) {}

  /** Per-probe state ceiling that turns excess combinatorics into a fail-closed decision. */
  private static final class SearchBudget {
    private final int maximumStates;
    private int visitedStates;

    private SearchBudget(int maximumStates) {
      this.maximumStates = maximumStates;
    }

    private boolean tryVisit() {
      if (visitedStates >= maximumStates) return false;
      visitedStates++;
      return true;
    }
  }
}
