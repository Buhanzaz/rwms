package dev.buhanzaz.rwms.logistics.customer.service;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionKind;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Classifies a customer point against versioned special-price and exceptional route polygons using
 * one strict GeoJSON parser and one deterministic boundary policy.
 */
@Component
@RequiredArgsConstructor
public class CustomerDeliveryPriceClassifier {
  private final ObjectMapper json;

  /** Selects the smallest covering geometry, then the stable source-zone UUID. */
  public PriceQuote classify(
      List<WarehouseCapacityPriceZone> zones, BigDecimal latitude, BigDecimal longitude) {
    if (zones == null || latitude == null || longitude == null) {
      throw new IllegalArgumentException("Tariff classification inputs are required");
    }
    Point point = new Point(longitude.doubleValue(), latitude.doubleValue());
    return zones.stream()
        .map(zone -> candidate(zone, point))
        .filter(java.util.Objects::nonNull)
        .sorted(
            Comparator.comparingDouble(PriceCandidate::area)
                .thenComparing(candidate -> candidate.zone().getSourceZoneId()))
        .map(
            candidate ->
                new PriceQuote(
                    candidate.zone().getDeliveryPriceRubles(),
                    candidate.zone().getSourceZoneId()))
        .findFirst()
        .orElseGet(() -> new PriceQuote(null, null));
  }

  /** Resolves forbidden, trailer-free, and special-price policy for one customer coordinate. */
  public DeliveryPolicy classifyPolicy(
      List<WarehouseCapacityPriceZone> priceZones,
      List<WarehouseCapacityRestrictionZone> restrictionZones,
      BigDecimal latitude,
      BigDecimal longitude) {
    if (restrictionZones == null) {
      throw new IllegalArgumentException("Restriction classification inputs are required");
    }
    Point point = requiredPoint(latitude, longitude);
    boolean forbidden = false;
    boolean noTrailer = false;
    for (WarehouseCapacityRestrictionZone zone : restrictionZones) {
      if (!parse(zone.getGeometryJson()).contains(point)) continue;
      if (zone.getKind() == WarehouseCapacityRestrictionKind.FORBIDDEN) forbidden = true;
      if (zone.getKind() == WarehouseCapacityRestrictionKind.NO_TRAILER) noTrailer = true;
    }
    return new DeliveryPolicy(classify(priceZones, latitude, longitude), forbidden, !noTrailer);
  }

  private PriceCandidate candidate(WarehouseCapacityPriceZone zone, Point point) {
    Geometry geometry = parse(zone.getGeometryJson());
    return geometry.contains(point) ? new PriceCandidate(zone, geometry.area()) : null;
  }

  private static Point requiredPoint(BigDecimal latitude, BigDecimal longitude) {
    if (latitude == null || longitude == null) {
      throw new IllegalArgumentException("Delivery coordinate is required");
    }
    return new Point(longitude.doubleValue(), latitude.doubleValue());
  }

  private Geometry parse(String geometryJson) {
    try {
      JsonNode root = json.readTree(geometryJson);
      if (root == null
          || !"MultiPolygon".equals(root.path("type").asText())
          || !root.path("coordinates").isArray()
          || root.path("coordinates").isEmpty()) {
        throw new IllegalArgumentException("Delivery-zone geometry is not a GeoJSON MultiPolygon");
      }
      List<Polygon> polygons = new ArrayList<>();
      for (JsonNode polygonNode : root.path("coordinates")) {
        if (!polygonNode.isArray() || polygonNode.isEmpty()) {
          throw new IllegalArgumentException("Delivery-zone polygon is empty");
        }
        List<Ring> rings = new ArrayList<>();
        for (JsonNode ringNode : polygonNode) {
          if (!ringNode.isArray() || ringNode.size() < 4) {
            throw new IllegalArgumentException("Delivery-zone polygon ring is invalid");
          }
          List<Point> points = new ArrayList<>();
          for (JsonNode position : ringNode) {
            if (!position.isArray()
                || position.size() != 2
                || !position.get(0).isNumber()
                || !position.get(1).isNumber()) {
              throw new IllegalArgumentException("Delivery-zone polygon position is invalid");
            }
            double longitude = position.get(0).asDouble();
            double latitude = position.get(1).asDouble();
            if (!Double.isFinite(longitude)
                || !Double.isFinite(latitude)
                || longitude < -180
                || longitude > 180
                || latitude < -90
                || latitude > 90) {
              throw new IllegalArgumentException("Delivery-zone polygon coordinate is invalid");
            }
            points.add(new Point(longitude, latitude));
          }
          if (!points.getFirst().equals(points.getLast())) {
            throw new IllegalArgumentException("Delivery-zone polygon ring is not closed");
          }
          rings.add(new Ring(List.copyOf(points)));
        }
        polygons.add(new Polygon(List.copyOf(rings)));
      }
      return new Geometry(List.copyOf(polygons));
    } catch (IllegalArgumentException exception) {
      throw exception;
    } catch (Exception exception) {
      throw new IllegalStateException("Persisted delivery-zone geometry cannot be parsed", exception);
    }
  }

  /** Price result presented to CustomerApp; both values are null outside configured polygons. */
  public record PriceQuote(Long deliveryPriceRubles, UUID priceZoneId) {
    public PriceQuote {
      if ((deliveryPriceRubles == null) != (priceZoneId == null)
          || (deliveryPriceRubles != null && deliveryPriceRubles < 0)) {
        throw new IllegalArgumentException("Tariff quote is invalid");
      }
    }
  }

  /** Combined exceptional policy evaluated alongside an ordinary route-time tariff. */
  public record DeliveryPolicy(
      PriceQuote specialPrice, boolean forbidden, boolean trailerAccessAllowed) {
    public DeliveryPolicy {
      if (specialPrice == null) {
        throw new IllegalArgumentException("Special-price classification is required");
      }
    }
  }

  /** One longitude/latitude coordinate in the tariff geometry plane. */
  private record Point(double x, double y) {}

  /** Point position relative to one closed linear ring. */
  private enum RingLocation {
    OUTSIDE,
    INSIDE,
    BOUNDARY
  }

  /** One closed GeoJSON linear ring with deterministic cover and area operations. */
  private record Ring(List<Point> points) {
    private RingLocation locate(Point point) {
      boolean inside = false;
      for (int current = 0, previous = points.size() - 1;
          current < points.size();
          previous = current++) {
        Point a = points.get(previous);
        Point b = points.get(current);
        if (onSegment(point, a, b)) return RingLocation.BOUNDARY;
        boolean crosses =
            (a.y() > point.y()) != (b.y() > point.y())
                && point.x()
                    < (b.x() - a.x()) * (point.y() - a.y()) / (b.y() - a.y()) + a.x();
        if (crosses) inside = !inside;
      }
      return inside ? RingLocation.INSIDE : RingLocation.OUTSIDE;
    }

    private double area() {
      double twiceArea = 0;
      for (int current = 0, previous = points.size() - 1;
          current < points.size();
          previous = current++) {
        Point a = points.get(previous);
        Point b = points.get(current);
        twiceArea += a.x() * b.y() - b.x() * a.y();
      }
      return Math.abs(twiceArea) / 2.0;
    }

    private static boolean onSegment(Point point, Point a, Point b) {
      double cross =
          (point.y() - a.y()) * (b.x() - a.x())
              - (point.x() - a.x()) * (b.y() - a.y());
      if (Math.abs(cross) > 1e-10) return false;
      return point.x() >= Math.min(a.x(), b.x()) - 1e-10
          && point.x() <= Math.max(a.x(), b.x()) + 1e-10
          && point.y() >= Math.min(a.y(), b.y()) - 1e-10
          && point.y() <= Math.max(a.y(), b.y()) + 1e-10;
    }
  }

  /** One polygon whose first ring is exterior and remaining rings are holes. */
  private record Polygon(List<Ring> rings) {
    private boolean contains(Point point) {
      RingLocation exterior = rings.getFirst().locate(point);
      if (exterior == RingLocation.OUTSIDE) return false;
      if (exterior == RingLocation.BOUNDARY) return true;
      for (int index = 1; index < rings.size(); index++) {
        RingLocation hole = rings.get(index).locate(point);
        if (hole == RingLocation.BOUNDARY) return true;
        if (hole == RingLocation.INSIDE) return false;
      }
      return true;
    }

    private double area() {
      double area = rings.getFirst().area();
      for (int index = 1; index < rings.size(); index++) area -= rings.get(index).area();
      return Math.max(0, area);
    }
  }

  /** One MultiPolygon with aggregate area used only as an overlap tie-breaker. */
  private record Geometry(List<Polygon> polygons) {
    private boolean contains(Point point) {
      return polygons.stream().anyMatch(polygon -> polygon.contains(point));
    }

    private double area() {
      return polygons.stream().mapToDouble(Polygon::area).sum();
    }
  }

  /** Internal covering-zone candidate before deterministic overlap resolution. */
  private record PriceCandidate(WarehouseCapacityPriceZone zone, double area) {}
}
