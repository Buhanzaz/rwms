package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityPriceZone;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionKind;
import dev.buhanzaz.rwms.logistics.customer.capacity.domain.WarehouseCapacityRestrictionZone;
import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/** Proves that tariff polygons classify money only and resolve overlaps deterministically. */
class CustomerDeliveryPriceClassifierTest {
  private final CustomerDeliveryPriceClassifier classifier =
      new CustomerDeliveryPriceClassifier(new ObjectMapper());

  @Test
  void selectsSmallestCoveringZone() {
    WarehouseCapacityPriceZone compact =
        zone(uuid(1), 1_000, square(30.0, 59.0, 30.2, 59.2));
    WarehouseCapacityPriceZone broad =
        zone(uuid(2), 2_000, square(29.0, 58.0, 31.0, 60.0));

    var quote =
        classifier.classify(
            List.of(broad, compact), new BigDecimal("59.10"), new BigDecimal("30.10"));

    assertThat(quote.deliveryPriceRubles()).isEqualTo(1_000);
    assertThat(quote.priceZoneId()).isEqualTo(uuid(1));
  }

  @Test
  void selectsLowestSourceUuidWhenCoveringAreasAreEqual() {
    WarehouseCapacityPriceZone second =
        zone(uuid(2), 1_000, square(30.0, 59.0, 30.2, 59.2));
    WarehouseCapacityPriceZone first =
        zone(uuid(1), 1_500, square(30.0, 59.0, 30.2, 59.2));

    var quote =
        classifier.classify(
            List.of(second, first), new BigDecimal("59.10"), new BigDecimal("30.10"));

    assertThat(quote.deliveryPriceRubles()).isEqualTo(1_500);
    assertThat(quote.priceZoneId()).isEqualTo(uuid(1));
  }

  @Test
  void pointInsidePolygonHoleHasNoTariff() {
    WarehouseCapacityPriceZone zone =
        zone(
            uuid(3),
            1_000,
            """
            {"type":"MultiPolygon","coordinates":[[
              [[30.0,59.0],[31.0,59.0],[31.0,60.0],[30.0,60.0],[30.0,59.0]],
              [[30.4,59.4],[30.6,59.4],[30.6,59.6],[30.4,59.6],[30.4,59.4]]
            ]]}
            """);

    var quote =
        classifier.classify(
            List.of(zone), new BigDecimal("59.50"), new BigDecimal("30.50"));

    assertThat(quote.deliveryPriceRubles()).isNull();
    assertThat(quote.priceZoneId()).isNull();
  }

  @Test
  void pointOutsideEveryZoneHasNoSpecialPrice() {
    var quote =
        classifier.classify(
            List.of(zone(uuid(4), 1_000, square(30.0, 59.0, 31.0, 60.0))),
            new BigDecimal("55.75"),
            new BigDecimal("37.61"));

    assertThat(quote.deliveryPriceRubles()).isNull();
    assertThat(quote.priceZoneId()).isNull();
  }

  @Test
  void overlappingRestrictionsComposeWithoutChangingDeterministicSpecialPrice() {
    WarehouseCapacityPriceZone special =
        zone(uuid(5), 7_000, square(30.0, 59.0, 31.0, 60.0));
    WarehouseCapacityRestrictionZone forbidden =
        restriction(
            uuid(6),
            WarehouseCapacityRestrictionKind.FORBIDDEN,
            square(30.0, 59.0, 31.0, 60.0));
    WarehouseCapacityRestrictionZone noTrailer =
        restriction(
            uuid(7),
            WarehouseCapacityRestrictionKind.NO_TRAILER,
            square(30.0, 59.0, 31.0, 60.0));

    var policy =
        classifier.classifyPolicy(
            List.of(special),
            List.of(noTrailer, forbidden),
            new BigDecimal("59.50"),
            new BigDecimal("30.50"));

    assertThat(policy.forbidden()).isTrue();
    assertThat(policy.trailerAccessAllowed()).isFalse();
    assertThat(policy.specialPrice().deliveryPriceRubles()).isEqualTo(7_000);
    assertThat(policy.specialPrice().priceZoneId()).isEqualTo(uuid(5));
  }

  @Test
  void restrictionBoundaryIsCoveredButPolygonHoleRemainsUnrestricted() {
    WarehouseCapacityRestrictionZone restriction =
        restriction(
            uuid(8),
            WarehouseCapacityRestrictionKind.NO_TRAILER,
            """
            {"type":"MultiPolygon","coordinates":[[
              [[30.0,59.0],[31.0,59.0],[31.0,60.0],[30.0,60.0],[30.0,59.0]],
              [[30.4,59.4],[30.6,59.4],[30.6,59.6],[30.4,59.6],[30.4,59.4]]
            ]]}
            """);

    var boundary =
        classifier.classifyPolicy(
            List.of(), List.of(restriction), new BigDecimal("59.00"), new BigDecimal("30.50"));
    var hole =
        classifier.classifyPolicy(
            List.of(), List.of(restriction), new BigDecimal("59.50"), new BigDecimal("30.50"));

    assertThat(boundary.trailerAccessAllowed()).isFalse();
    assertThat(hole.trailerAccessAllowed()).isTrue();
  }

  private static WarehouseCapacityPriceZone zone(
      UUID sourceZoneId, long deliveryPriceRubles, String geometry) {
    WarehouseCapacityPriceZone zone = mock(WarehouseCapacityPriceZone.class);
    when(zone.getSourceZoneId()).thenReturn(sourceZoneId);
    when(zone.getDeliveryPriceRubles()).thenReturn(deliveryPriceRubles);
    when(zone.getGeometryJson()).thenReturn(geometry);
    return zone;
  }

  private static WarehouseCapacityRestrictionZone restriction(
      UUID sourceZoneId, WarehouseCapacityRestrictionKind kind, String geometry) {
    WarehouseCapacityRestrictionZone zone = mock(WarehouseCapacityRestrictionZone.class);
    when(zone.getSourceZoneId()).thenReturn(sourceZoneId);
    when(zone.getKind()).thenReturn(kind);
    when(zone.getGeometryJson()).thenReturn(geometry);
    return zone;
  }

  private static UUID uuid(int suffix) {
    return UUID.fromString("00000000-0000-0000-0000-%012d".formatted(suffix));
  }

  private static String square(double minLon, double minLat, double maxLon, double maxLat) {
    return """
        {"type":"MultiPolygon","coordinates":[[[
          [%s,%s],[%s,%s],[%s,%s],[%s,%s],[%s,%s]
        ]]]}
        """
        .formatted(
            minLon,
            minLat,
            maxLon,
            minLat,
            maxLon,
            maxLat,
            minLon,
            maxLat,
            minLon,
            minLat);
  }
}
