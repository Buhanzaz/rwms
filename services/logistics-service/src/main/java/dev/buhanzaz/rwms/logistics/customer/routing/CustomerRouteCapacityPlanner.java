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
          .thenComparingInt(DriverState::cabinsOnTruck);

  /** One existing or candidate delivery demand at a matrix point and hard arrival window. */
  public record DeliveryJob(
      int matrixIndex,
      int cabinCount,
      LocalTime windowStart,
      LocalTime windowEnd,
      int serviceMinutes,
      boolean candidate) {}

  /** Feasibility result and additional candidate-point cabins supported by the same day. */
  public record CapacityDecision(boolean feasible, int capacityRemaining) {}

  /** Evaluates the complete day without external whole-driver reservations. */
  public CapacityDecision evaluate(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      CustomerDeliveryProperties.Validated configuration) {
    return evaluate(matrix, jobs, configuration, 0);
  }

  /**
   * Evaluates the complete day after reserving whole drivers for active transport work whose exact
   * service window is not known to RWMS. Capacity probing uses at most seven memoized searches for
   * the supported 128-cabin safety ceiling and therefore remains bounded for API traffic.
   */
  public CapacityDecision evaluate(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      CustomerDeliveryProperties.Validated configuration,
      int reservedDrivers) {
    validateInputs(matrix, jobs, configuration, reservedDrivers);
    DeliveryJob candidate = jobs.stream().filter(DeliveryJob::candidate).findFirst().orElseThrow();
    long oneWaySeconds = matrix.travelSeconds(DEPOT_INDEX, candidate.matrixIndex());
    if (oneWaySeconds < 0 || travelZoneHours(oneWaySeconds) > configuration.maxTravelZoneHours()) {
      return new CapacityDecision(false, 0);
    }
    int availableDrivers = configuration.driverCount() - reservedDrivers;
    if (availableDrivers == 0 || !isFeasible(matrix, jobs, configuration, availableDrivers)) {
      return new CapacityDecision(false, 0);
    }

    int originalCandidateCabins = candidate.cabinCount();
    int upperCandidateCabins =
        Math.min(MAX_ROUTABLE_CABINS, maximumFleetCabins(configuration, availableDrivers));
    int low = originalCandidateCabins;
    int high = Math.max(low, upperCandidateCabins);
    while (low < high) {
      int probe = low + (high - low + 1) / 2;
      if (isFeasible(
          matrix,
          withCandidateCabins(jobs, probe),
          configuration,
          availableDrivers)) {
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
      int reservedDrivers) {
    if (matrix == null || configuration == null || jobs == null || jobs.isEmpty()) {
      throw new IllegalArgumentException("Delivery matrix, configuration and jobs are required");
    }
    if (jobs.stream().filter(DeliveryJob::candidate).count() != 1) {
      throw new IllegalArgumentException("Exactly one candidate delivery job is required");
    }
    if (reservedDrivers < 0 || reservedDrivers > configuration.driverCount()) {
      throw new IllegalArgumentException("Reserved driver count is invalid");
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

  private static int travelZoneHours(long oneWaySeconds) {
    return (int) Math.max(1L, (oneWaySeconds + 3_599L) / 3_600L);
  }

  private static int maximumFleetCabins(
      CustomerDeliveryProperties.Validated configuration, int availableDrivers) {
    long operationalSeconds =
        deadline(configuration) - configuration.workdayStart().toSecondOfDay();
    long serviceSeconds = configuration.serviceMinutes() * 60L;
    long maximumVisitsPerDriver = operationalSeconds / serviceSeconds + 1L;
    long maximum =
        Math.min(MAX_ROUTABLE_CABINS, maximumVisitsPerDriver)
            * Math.min(MAX_ROUTABLE_CABINS, availableDrivers)
            * Math.min(MAX_ROUTABLE_CABINS, configuration.truckCabinCapacity());
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
                        true)
                    : job)
        .toList();
  }

  private static boolean isFeasible(
      CustomerTravelTimeMatrix matrix,
      List<DeliveryJob> jobs,
      CustomerDeliveryProperties.Validated configuration,
      int availableDrivers) {
    long totalCabins = jobs.stream().mapToLong(DeliveryJob::cabinCount).sum();
    if (totalCabins > MAX_ROUTABLE_CABINS) return false;
    long minimumServiceSeconds =
        jobs.stream().mapToLong(job -> job.serviceMinutes() * 60L).min().orElseThrow();
    long maximumVisits =
        (long) availableDrivers
            * (deadline(configuration) - configuration.workdayStart().toSecondOfDay())
            / minimumServiceSeconds
            + availableDrivers;
    long minimumVisits =
        jobs.stream()
            .mapToLong(
                job ->
                    (job.cabinCount() + configuration.truckCabinCapacity() - 1L)
                        / configuration.truckCabinCapacity())
            .sum();
    if (minimumVisits > maximumVisits) return false;

    int[] remaining = jobs.stream().mapToInt(DeliveryJob::cabinCount).toArray();
    List<DriverState> drivers = new ArrayList<>(availableDrivers);
    for (int index = 0; index < availableDrivers; index++) {
      drivers.add(
          new DriverState(
              configuration.workdayStart().toSecondOfDay(),
              DEPOT_INDEX,
              configuration.truckCabinCapacity()));
    }
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
      return canReturnAllDrivers(matrix, drivers, deadline(configuration));
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
      long depotLeg = matrix.travelSeconds(departurePoint, DEPOT_INDEX);
      if (depotLeg < 0) return false;
      departure += depotLeg + configuration.depotReloadMinutes() * 60L;
      departurePoint = DEPOT_INDEX;
      cabinsOnTruck = configuration.truckCabinCapacity();
    }
    if (cabinsOnTruck < 1) return false;
    long customerLeg = matrix.travelSeconds(departurePoint, job.matrixIndex());
    if (customerLeg < 0) return false;
    long serviceStart =
        Math.max(departure + customerLeg, job.windowStart().toSecondOfDay());
    if (serviceStart > job.windowEnd().toSecondOfDay()) return false;
    long serviceEnd = serviceStart + job.serviceMinutes() * 60L;
    long returnLeg = matrix.travelSeconds(job.matrixIndex(), DEPOT_INDEX);
    if (returnLeg < 0 || serviceEnd + returnLeg > deadline(configuration)) return false;

    int maximumQuantity = Math.min(cabinsOnTruck, remaining[jobIndex]);
    for (int quantity = maximumQuantity; quantity >= 1; quantity--) {
      int[] nextRemaining = remaining.clone();
      nextRemaining[jobIndex] -= quantity;
      List<DriverState> nextDrivers = new ArrayList<>(drivers);
      nextDrivers.set(
          driverIndex,
          new DriverState(serviceEnd, job.matrixIndex(), cabinsOnTruck - quantity));
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
      CustomerTravelTimeMatrix matrix, List<DriverState> drivers, long deadline) {
    for (DriverState driver : drivers) {
      if (driver.matrixIndex() == DEPOT_INDEX) continue;
      long returnLeg = matrix.travelSeconds(driver.matrixIndex(), DEPOT_INDEX);
      if (returnLeg < 0 || driver.availableAt() + returnLeg > deadline) return false;
    }
    return true;
  }

  private static long deadline(CustomerDeliveryProperties.Validated configuration) {
    return configuration.workdayEnd().toSecondOfDay() + configuration.maxOvertime().toSeconds();
  }

  private static List<Integer> toList(int[] values) {
    return Arrays.stream(values).boxed().toList();
  }

  /** Canonical per-driver scheduling state; driver identity is intentionally irrelevant. */
  private record DriverState(long availableAt, int matrixIndex, int cabinsOnTruck) {}

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
