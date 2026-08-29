package dev.buhanzaz.rwms.logistics.customer.routing;

import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import java.util.Locale;

/** Immutable truck-routing profile for one conservative trip configuration and load. */
public record CustomerVehicleRouteProfile(
    double heightMeters,
    double widthMeters,
    double lengthMeters,
    double weightTons,
    double axleLoadTons,
    int axleCount,
    boolean trailerAttached,
    int loadCabins,
    String routingProfileHash) {
  public CustomerVehicleRouteProfile {
    if (heightMeters <= 0
        || widthMeters <= 0
        || lengthMeters <= 0
        || weightTons <= 0
        || axleLoadTons <= 0
        || axleCount < 2
        || loadCabins < 0
        || loadCabins > 2
        || routingProfileHash == null
        || routingProfileHash.isBlank()) {
      throw new IllegalArgumentException("Customer truck routing profile is invalid");
    }
  }

  /** Selects the no-trailer or maximum truck-and-trailer profile for a complete trip. */
  public static CustomerVehicleRouteProfile forTripCapacity(
      CustomerDeliveryProperties.Validated configuration, int tripCapacity) {
    if (configuration == null || tripCapacity < 1 || tripCapacity > 2) {
      throw new IllegalArgumentException("Customer trip capacity is invalid");
    }
    boolean trailer = tripCapacity == 2;
    double height =
        trailer ? configuration.truckHeightMeters() : configuration.soloTruckHeightMeters();
    double width =
        trailer ? configuration.truckWidthMeters() : configuration.soloTruckWidthMeters();
    double length =
        trailer ? configuration.truckLengthMeters() : configuration.soloTruckLengthMeters();
    double weight =
        trailer ? configuration.truckWeightTons() : configuration.soloTruckWeightTons();
    double axleLoad =
        trailer ? configuration.truckAxleLoadTons() : configuration.soloTruckAxleLoadTons();
    int axleCount =
        trailer ? configuration.truckAxleCount() : configuration.soloTruckAxleCount();
    String hash =
        String.format(
            Locale.ROOT,
            "truck-v2:h=%.3f:w=%.3f:l=%.3f:t=%.3f:a=%.3f:n=%d:trailer=%s:load=%d",
            height,
            width,
            length,
            weight,
            axleLoad,
            axleCount,
            trailer,
            tripCapacity);
    return new CustomerVehicleRouteProfile(
        height,
        width,
        length,
        weight,
        axleLoad,
        axleCount,
        trailer,
        tripCapacity,
        hash);
  }
}
