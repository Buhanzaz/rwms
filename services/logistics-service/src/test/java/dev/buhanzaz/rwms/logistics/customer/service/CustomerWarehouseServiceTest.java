package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerWarehouseResponse;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties.Validated;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.time.LocalTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

/** Covers the depot-coordinate boundary consumed by the CustomerApp delivery map. */
class CustomerWarehouseServiceTest {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("c89b65f1-2891-4176-bd88-1d231e869a25");

  @Test
  void exposesConfiguredDepotCoordinatesForEveryVisibleWarehouse() {
    CustomerDeliveryProperties properties = mock(CustomerDeliveryProperties.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    Validated depot = depot();
    when(properties.validatedDepots()).thenReturn(Map.of(WAREHOUSE_ID, depot));
    when(dependencies.listWarehouseIdentities())
        .thenReturn(
            List.of(
                new WarehouseIdentity(
                    WAREHOUSE_ID,
                    3,
                    true,
                    "СПБ",
                    "Санкт-Петербург",
                    "Europe/Moscow")));

    List<CustomerWarehouseResponse> response =
        new CustomerWarehouseService(properties, dependencies).list();

    assertThat(response).hasSize(1);
    assertThat(response.getFirst().depotLatitude()).isEqualByComparingTo("59.763806");
    assertThat(response.getFirst().depotLongitude()).isEqualByComparingTo("30.471798");
  }

  private static Validated depot() {
    return new Validated(
        WAREHOUSE_ID,
        new BigDecimal("59.763806"),
        new BigDecimal("30.471798"),
        URI.create("http://127.0.0.1:8002"),
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
        2.55,
        10.5,
        12.0,
        10.0,
        2,
        4.0,
        2.55,
        12.0,
        20.0,
        10.0,
        2);
  }
}
