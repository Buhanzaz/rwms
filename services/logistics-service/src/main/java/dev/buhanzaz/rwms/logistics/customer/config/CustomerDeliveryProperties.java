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
    int depotReloadMinutes,
    int serviceMinutes,
    int earliestDeliveryDays,
    int bookingHorizonDays,
    Duration offerLifetime,
    Duration holdLifetime,
    double travelTimeMultiplier,
    int fixedTravelBufferMinutes,
    LocalTime driverWorkStart,
    LocalTime customerDeliveryStart,
    LocalTime customerDeliveryEnd,
    LocalTime driverShiftEnd,
    int deliverySlotMinutes,
    int pickupServiceMinutes,
    int warehouseLoadOneMinutes,
    int warehouseLoadTwoMinutes,
    int warehouseUnloadMinutes,
    DeliveryWindowSemantics deliveryWindowSemantics,
    PickupPolicy pickupPolicy,
    double soloTruckHeightMeters,
    double soloTruckWidthMeters,
    double soloTruckLengthMeters,
    double soloTruckWeightTons,
    double soloTruckAxleLoadTons,
    int soloTruckAxleCount,
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
    if (depotReloadMinutes < 1
        || serviceMinutes < 1
        || pickupServiceMinutes < 1
        || warehouseLoadOneMinutes < 1
        || warehouseLoadTwoMinutes < warehouseLoadOneMinutes
        || warehouseUnloadMinutes < 1
        || travelTimeMultiplier < 1.0
        || !Double.isFinite(travelTimeMultiplier)
        || fixedTravelBufferMinutes < 0
        || driverWorkStart == null
        || customerDeliveryStart == null
        || customerDeliveryEnd == null
        || driverShiftEnd == null
        || !driverWorkStart.isBefore(customerDeliveryStart)
        || !customerDeliveryStart.isBefore(customerDeliveryEnd)
        || !customerDeliveryEnd.isBefore(driverShiftEnd)
        || deliverySlotMinutes < 1
        || java.time.Duration.between(customerDeliveryStart, customerDeliveryEnd).toMinutes()
                % deliverySlotMinutes
            != 0
        || deliveryWindowSemantics != DeliveryWindowSemantics.START_WITHIN_SLOT
        || pickupPolicy != PickupPolicy.RETURN_LEG_ONLY
        || earliestDeliveryDays < 1
        || bookingHorizonDays < earliestDeliveryDays
        || bookingHorizonDays > 90
        || soloTruckHeightMeters <= 0
        || soloTruckWidthMeters <= 0
        || soloTruckLengthMeters <= 0
        || soloTruckWeightTons <= 0
        || soloTruckAxleLoadTons <= 0
        || soloTruckAxleCount < 2
        || truckHeightMeters <= 0
        || truckWidthMeters <= 0
        || truckLengthMeters <= 0
        || truckWeightTons <= 0
        || truckAxleLoadTons <= 0
        || truckAxleCount < 2) {
      throw new IllegalStateException("Customer delivery capacity configuration is invalid");
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
              depotReloadMinutes,
              serviceMinutes,
              earliestDeliveryDays,
              bookingHorizonDays,
              positive(offerLifetime, "offer-lifetime"),
              positive(holdLifetime, "hold-lifetime"),
              travelTimeMultiplier,
              fixedTravelBufferMinutes,
              driverWorkStart,
              customerDeliveryStart,
              customerDeliveryEnd,
              driverShiftEnd,
              deliverySlotMinutes,
              pickupServiceMinutes,
              warehouseLoadOneMinutes,
              warehouseLoadTwoMinutes,
              warehouseUnloadMinutes,
              deliveryWindowSemantics,
              pickupPolicy,
              soloTruckHeightMeters,
              soloTruckWidthMeters,
              soloTruckLengthMeters,
              soloTruckWeightTons,
              soloTruckAxleLoadTons,
              soloTruckAxleCount,
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

  /** Delivery-window policy currently promised by CustomerApp. */
  public enum DeliveryWindowSemantics {
    START_WITHIN_SLOT
  }

  /** Pickup placement policy that protects all outbound deliveries. */
  public enum PickupPolicy {
    RETURN_LEG_ONLY
  }

  /** Fully validated immutable delivery runtime configuration for one warehouse. */
  public record Validated(
      UUID warehouseId,
      BigDecimal depotLatitude,
      BigDecimal depotLongitude,
      URI valhallaBaseUrl,
      Duration connectTimeout,
      Duration readTimeout,
      int depotReloadMinutes,
      int serviceMinutes,
      int earliestDeliveryDays,
      int bookingHorizonDays,
      Duration offerLifetime,
      Duration holdLifetime,
      double travelTimeMultiplier,
      int fixedTravelBufferMinutes,
      LocalTime driverWorkStart,
      LocalTime customerDeliveryStart,
      LocalTime customerDeliveryEnd,
      LocalTime driverShiftEnd,
      int deliverySlotMinutes,
      int pickupServiceMinutes,
      int warehouseLoadOneMinutes,
      int warehouseLoadTwoMinutes,
      int warehouseUnloadMinutes,
      DeliveryWindowSemantics deliveryWindowSemantics,
      PickupPolicy pickupPolicy,
      double soloTruckHeightMeters,
      double soloTruckWidthMeters,
      double soloTruckLengthMeters,
      double soloTruckWeightTons,
      double soloTruckAxleLoadTons,
      int soloTruckAxleCount,
      double truckHeightMeters,
      double truckWidthMeters,
      double truckLengthMeters,
      double truckWeightTons,
      double truckAxleLoadTons,
      int truckAxleCount) {

    /** Returns the load duration for a conservative one- or two-cabin trip. */
    public int warehouseLoadMinutes(int cabinCapacity) {
      return cabinCapacity > 1 ? warehouseLoadTwoMinutes : warehouseLoadOneMinutes;
    }
  }
}
