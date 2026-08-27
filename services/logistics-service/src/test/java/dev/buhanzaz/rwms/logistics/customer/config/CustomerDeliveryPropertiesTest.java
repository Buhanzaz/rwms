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
    return new CustomerDeliveryProperties(
        true,
        depots,
        "http://127.0.0.1:8002",
        Duration.ofSeconds(1),
        Duration.ofSeconds(2),
        2,
        2,
        30,
        LocalTime.of(9, 0),
        LocalTime.of(18, 0),
        Duration.ofHours(2),
        4,
        30,
        2,
        31,
        Duration.ofMinutes(10),
        Duration.ofMinutes(20),
        4.0,
        2.5,
        12.0,
        20.0,
        8.0,
        3);
  }
}
