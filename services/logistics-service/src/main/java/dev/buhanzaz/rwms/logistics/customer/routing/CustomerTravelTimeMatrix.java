package dev.buhanzaz.rwms.logistics.customer.routing;

import java.util.List;

/** Directed truck travel-time matrix returned by the configured Valhalla graph. */
public record CustomerTravelTimeMatrix(List<GeoPoint> points, List<List<Long>> seconds) {
  /** Latitude/longitude pair participating in a route-capacity calculation. */
  public record GeoPoint(double latitude, double longitude) {}

  /** Returns directed seconds between two indexed points. */
  public long travelSeconds(int from, int to) {
    return seconds.get(from).get(to);
  }
}
