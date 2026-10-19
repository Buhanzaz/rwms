package dev.buhanzaz.rwms.logistics.customer.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Verifies fail-closed validation and independent selection of multiple customer depots. */
class CustomerDeliveryPropertiesTest {
  private static final UUID MOSCOW =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID SAINT_PETERSBURG =
      UUID.fromString("c89b65f1-2891-4176-bd88-1d231e869a25");

  @Test
  void exposesEveryEnabledDepotWithItsOwnCoordinates() {
    CustomerDeliveryProperties properties =
        properties(
            List.of(
                depot(MOSCOW, 55.757469, 37.399528),
                depot(SAINT_PETERSBURG, 59.763806, 30.471798),
                new CustomerDeliveryProperties.Depot(
                    false, "not-a-uuid", BigDecimal.ZERO, BigDecimal.ZERO)));

    assertThat(properties.validatedDepots()).containsOnlyKeys(MOSCOW, SAINT_PETERSBURG);
    assertThat(properties.validated(SAINT_PETERSBURG).depotLatitude())
        .isEqualByComparingTo("59.763806");
    assertThat(properties.validated(MOSCOW).depotLongitude())
        .isEqualByComparingTo("37.399528");
  }

  @Test
  void rejectsDuplicateWarehouseIdentity() {
    CustomerDeliveryProperties properties =
        properties(List.of(depot(MOSCOW, 55.75, 37.39), depot(MOSCOW, 55.76, 37.40)));

    assertThatThrownBy(properties::validatedDepots)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("unique");
  }

  @Test
  void rejectsWarehouseWithoutConfiguredDepot() {
    CustomerDeliveryProperties properties =
        properties(List.of(depot(MOSCOW, 55.75, 37.39)));

    assertThatThrownBy(() -> properties.validated(SAINT_PETERSBURG))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("not configured");
  }

  @Test
  void buildsRepresentativeRouteOriginFromCanonicalWarehouseCoordinates() {
    CustomerDeliveryProperties properties =
        properties(List.of(depot(MOSCOW, 55.75, 37.39)));

    CustomerDeliveryProperties.Validated representative =
        properties.validated(
            SAINT_PETERSBURG,
            new BigDecimal("58.573100"),
            new BigDecimal("31.269200"));

    assertThat(representative.warehouseId()).isEqualTo(SAINT_PETERSBURG);
    assertThat(representative.depotLatitude()).isEqualByComparingTo("58.573100");
    assertThat(representative.depotLongitude()).isEqualByComparingTo("31.269200");
  }

  @Test
  void rejectsNullIslandForConfiguredAndCanonicalWarehouseOrigins() {
    CustomerDeliveryProperties configured =
        properties(List.of(depot(MOSCOW, 0, 0)));
    CustomerDeliveryProperties canonical =
        properties(List.of(depot(MOSCOW, 55.75, 37.39)));

    assertThatThrownBy(configured::validatedDepots)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0,0");
    assertThatThrownBy(
            () -> canonical.validated(SAINT_PETERSBURG, BigDecimal.ZERO, BigDecimal.ZERO))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("0,0");
  }

  @Test
  void acceptsRouteOriginsOnOneZeroAxis() {
    CustomerDeliveryProperties configured =
        properties(List.of(depot(MOSCOW, 0, 37.39)));

    assertThat(configured.validated(MOSCOW).depotLatitude()).isEqualByComparingTo("0");
    assertThat(
            configured
                .validated(SAINT_PETERSBURG, new BigDecimal("58.573100"), BigDecimal.ZERO)
                .depotLongitude())
        .isEqualByComparingTo("0");
  }

  @Test
  void validatesAndNormalizesRoutingCacheIdentity() {
    CustomerDeliveryProperties properties =
        properties(
            List.of(depot(MOSCOW, 55.75, 37.39)),
            "  graph-2026-08-31  ",
            Duration.ofMinutes(15));

    CustomerDeliveryProperties.Validated validated = properties.validated(MOSCOW);

    assertThat(validated.routingDataVersion()).isEqualTo("graph-2026-08-31");
    assertThat(validated.routingCacheTtl()).isEqualTo(Duration.ofMinutes(15));
  }

  @Test
  void rejectsBlankRoutingDataVersion() {
    CustomerDeliveryProperties properties =
        properties(List.of(depot(MOSCOW, 55.75, 37.39)), "  ", Duration.ofMinutes(15));

    assertThatThrownBy(properties::validatedDepots)
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("routing-data-version");
  }

  @Test
  void rejectsNonPositiveOrUnboundedRoutingCacheTtl() {
    List<CustomerDeliveryProperties.Depot> depots =
        List.of(depot(MOSCOW, 55.75, 37.39));

    assertThatThrownBy(
            () -> properties(depots, "graph-v1", Duration.ZERO).validatedDepots())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("routing-cache-ttl must be positive");
    assertThatThrownBy(
            () ->
                properties(depots, "graph-v1", Duration.ofHours(24).plusNanos(1))
                    .validatedDepots())
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("must not exceed 24 hours");
  }

  private static CustomerDeliveryProperties.Depot depot(
      UUID warehouseId, double latitude, double longitude) {
    return new CustomerDeliveryProperties.Depot(
        true,
        warehouseId.toString(),
        BigDecimal.valueOf(latitude),
        BigDecimal.valueOf(longitude));
  }

  private static CustomerDeliveryProperties properties(
      List<CustomerDeliveryProperties.Depot> depots) {
    return properties(depots, "test-routing-data-v1", Duration.ofMinutes(15));
  }

  private static CustomerDeliveryProperties properties(
      List<CustomerDeliveryProperties.Depot> depots,
      String routingDataVersion,
      Duration routingCacheTtl) {
    return new CustomerDeliveryProperties(
        true,
        depots,
        "http://127.0.0.1:8002",
        routingDataVersion,
        routingCacheTtl,
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        30,
        60,
        2,
        31,
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
}
