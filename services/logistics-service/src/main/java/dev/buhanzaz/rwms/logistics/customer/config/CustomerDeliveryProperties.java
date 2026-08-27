package dev.buhanzaz.rwms.logistics.customer.config;

import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Validated multi-depot CustomerApp delivery capacity. Every enabled warehouse has explicit depot
 * coordinates, so a missing production setup disables that warehouse instead of silently routing
 * from a fabricated point.
 */
@ConfigurationProperties("rwms.logistics.customer.delivery")
public record CustomerDeliveryProperties(
    boolean enabled,
    List<Depot> depots,
    String valhallaBaseUrl,
    Duration connectTimeout,
    Duration readTimeout,
    int driverCount,
    int truckCabinCapacity,
    int depotReloadMinutes,
    LocalTime workdayStart,
    LocalTime workdayEnd,
    Duration maxOvertime,
    int maxTravelZoneHours,
    int serviceMinutes,
    int earliestDeliveryDays,
    int bookingHorizonDays,
    Duration offerLifetime,
    Duration holdLifetime,
    double truckHeightMeters,
    double truckWidthMeters,
    double truckLengthMeters,
    double truckWeightTons,
    double truckAxleLoadTons,
    int truckAxleCount) {

  /** Returns all enabled depot profiles after validating shared and warehouse-local facts. */
  public Map<UUID, Validated> validatedDepots() {
    if (!enabled) throw new IllegalStateException("Customer delivery slots are disabled");
    URI valhalla;
    try {
      valhalla = URI.create(required(valhallaBaseUrl, "valhalla-base-url"));
      if (!valhalla.isAbsolute() || valhalla.getHost() == null) throw new IllegalArgumentException();
    } catch (IllegalArgumentException exception) {
      throw new IllegalStateException("valhalla-base-url must be an absolute URI", exception);
    }
    if (driverCount < 1
        || truckCabinCapacity < 1
        || depotReloadMinutes < 1
        || workdayStart == null
        || workdayEnd == null
        || !workdayStart.isBefore(workdayEnd)
        || workdayStart.isAfter(LocalTime.of(9, 0))
        || workdayEnd.isBefore(LocalTime.of(18, 0))
        || maxTravelZoneHours < 1
        || maxTravelZoneHours > 4
        || serviceMinutes < 1
        || earliestDeliveryDays < 1
        || bookingHorizonDays < earliestDeliveryDays
        || bookingHorizonDays > 90
        || truckHeightMeters <= 0
        || truckWidthMeters <= 0
        || truckLengthMeters <= 0
        || truckWeightTons <= 0
        || truckAxleLoadTons <= 0
        || truckAxleCount < 2) {
      throw new IllegalStateException("Customer delivery capacity configuration is invalid");
    }
    Duration validatedOvertime = nonNegative(maxOvertime, "max-overtime");
    if (validatedOvertime.compareTo(Duration.ofDays(1)) >= 0
        || workdayEnd.toSecondOfDay() + validatedOvertime.toSeconds() >= 24L * 60L * 60L) {
      throw new IllegalStateException("Customer delivery overtime must end within the local date");
    }
    List<Depot> source = depots == null ? List.of() : depots;
    Map<UUID, Validated> result = new LinkedHashMap<>();
    for (Depot depot : source) {
      if (depot == null || !depot.enabled()) continue;
      UUID warehouseId = depot.validatedWarehouseId();
      if (result.containsKey(warehouseId)) {
        throw new IllegalStateException("Customer delivery warehouse IDs must be unique");
      }
      result.put(
          warehouseId,
          new Validated(
              warehouseId,
              depot.validatedLatitude(),
              depot.validatedLongitude(),
              valhalla,
              positive(connectTimeout, "connect-timeout"),
              positive(readTimeout, "read-timeout"),
              driverCount,
              truckCabinCapacity,
              depotReloadMinutes,
              workdayStart,
              workdayEnd,
              validatedOvertime,
              maxTravelZoneHours,
              serviceMinutes,
              earliestDeliveryDays,
              bookingHorizonDays,
              positive(offerLifetime, "offer-lifetime"),
              positive(holdLifetime, "hold-lifetime"),
              truckHeightMeters,
              truckWidthMeters,
              truckLengthMeters,
              truckWeightTons,
              truckAxleLoadTons,
              truckAxleCount));
    }
    if (result.isEmpty()) {
      throw new IllegalStateException("At least one customer delivery depot must be enabled");
    }
    return Map.copyOf(result);
  }

  /** Returns one fail-closed warehouse profile from the configured depot registry. */
  public Validated validated(UUID warehouseId) {
    if (warehouseId == null) throw new IllegalStateException("warehouse-id is required");
    Validated result = validatedDepots().get(warehouseId);
    if (result == null) {
      throw new IllegalStateException("Customer delivery warehouse is not configured");
    }
    return result;
  }

  private static String required(String value, String name) {
    if (value == null || value.isBlank()) throw new IllegalStateException(name + " is required");
    return value.trim();
  }

  private static Duration positive(Duration value, String name) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalStateException(name + " must be positive");
    }
    return value;
  }

  private static Duration nonNegative(Duration value, String name) {
    if (value == null || value.isNegative()) {
      throw new IllegalStateException(name + " must not be negative");
    }
    return value;
  }

  /** One independently enabled physical depot in the customer-delivery registry. */
  public record Depot(
      boolean enabled,
      String warehouseId,
      BigDecimal depotLatitude,
      BigDecimal depotLongitude) {

    private UUID validatedWarehouseId() {
      try {
        return UUID.fromString(required(warehouseId, "warehouse-id"));
      } catch (IllegalArgumentException exception) {
        throw new IllegalStateException("warehouse-id must be a UUID", exception);
      }
    }

    private BigDecimal validatedLatitude() {
      if (depotLatitude == null
          || depotLatitude.compareTo(BigDecimal.valueOf(-90)) < 0
          || depotLatitude.compareTo(BigDecimal.valueOf(90)) > 0) {
        throw new IllegalStateException("Depot latitude is invalid");
      }
      return depotLatitude;
    }

    private BigDecimal validatedLongitude() {
      if (depotLongitude == null
          || depotLongitude.compareTo(BigDecimal.valueOf(-180)) < 0
          || depotLongitude.compareTo(BigDecimal.valueOf(180)) > 0) {
        throw new IllegalStateException("Depot longitude is invalid");
      }
      return depotLongitude;
    }
  }

  /** Fully validated immutable delivery runtime configuration for one warehouse. */
  public record Validated(
      UUID warehouseId,
      BigDecimal depotLatitude,
      BigDecimal depotLongitude,
      URI valhallaBaseUrl,
      Duration connectTimeout,
      Duration readTimeout,
      int driverCount,
      int truckCabinCapacity,
      int depotReloadMinutes,
      LocalTime workdayStart,
      LocalTime workdayEnd,
      Duration maxOvertime,
      int maxTravelZoneHours,
      int serviceMinutes,
      int earliestDeliveryDays,
      int bookingHorizonDays,
      Duration offerLifetime,
      Duration holdLifetime,
      double truckHeightMeters,
      double truckWidthMeters,
      double truckLengthMeters,
      double truckWeightTons,
      double truckAxleLoadTons,
      int truckAxleCount) {}
}
