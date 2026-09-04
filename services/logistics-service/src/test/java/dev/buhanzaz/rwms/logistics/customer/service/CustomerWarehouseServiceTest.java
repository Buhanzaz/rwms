package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.CustomerWarehouseResponse;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties.Validated;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import dev.buhanzaz.rwms.logistics.order.service.OrderProblemException;
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
  private static final UUID REPRESENTATIVE_WAREHOUSE_ID =
      UUID.fromString("2496028a-3b85-4d08-8541-a6f78900df50");
  private static final UUID TECHNICAL_WAREHOUSE_ID =
      UUID.fromString("f41e1a08-d114-4fb9-8cbe-646dd5190ea8");

  @Test
  void exposesConfiguredOrdinaryAndCanonicalRepresentativeWarehouseCoordinates() {
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
                    "Колпино, Сапёрный переулок, 6",
                    new BigDecimal("59.763900"),
                    new BigDecimal("30.471900"),
                    "Europe/Moscow",
                    false),
                new WarehouseIdentity(
                    REPRESENTATIVE_WAREHOUSE_ID,
                    2,
                    true,
                    "Великий Новгород",
                    "Великий Новгород",
                    "Большая Санкт-Петербургская улица, 82А",
                    new BigDecimal("58.573100"),
                    new BigDecimal("31.269200"),
                    "Europe/Moscow",
                    true),
                new WarehouseIdentity(
                    TECHNICAL_WAREHOUSE_ID,
                    1,
                    true,
                    "Техническая площадка",
                    null,
                    null,
                    new BigDecimal("59.900000"),
                    new BigDecimal("30.300000"),
                    "Europe/Moscow",
                    false)));

    List<CustomerWarehouseResponse> response =
        new CustomerWarehouseService(properties, dependencies).list();

    assertThat(response).extracting(CustomerWarehouseResponse::id)
        .containsExactly(WAREHOUSE_ID, REPRESENTATIVE_WAREHOUSE_ID);
    assertThat(response.getFirst().depotLatitude()).isEqualByComparingTo("59.763900");
    assertThat(response.getFirst().depotLongitude()).isEqualByComparingTo("30.471900");
    assertThat(response.get(1).address()).isEqualTo("Большая Санкт-Петербургская улица, 82А");
  }

  @Test
  void usesRepresentativeWarehouseCoordinatesForRouteConfiguration() {
    CustomerDeliveryProperties properties = mock(CustomerDeliveryProperties.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    WarehouseIdentity representative =
        new WarehouseIdentity(
            REPRESENTATIVE_WAREHOUSE_ID,
            2,
            true,
            "Великий Новгород",
            "Великий Новгород",
            null,
            new BigDecimal("58.573100"),
            new BigDecimal("31.269200"),
            "Europe/Moscow",
            true);
    when(properties.validatedDepots()).thenReturn(Map.of(WAREHOUSE_ID, depot()));
    when(dependencies.readWarehouseIdentity(REPRESENTATIVE_WAREHOUSE_ID))
        .thenReturn(representative);
    when(properties.validated(
            REPRESENTATIVE_WAREHOUSE_ID,
            representative.latitude(),
            representative.longitude()))
        .thenReturn(depot());

    new CustomerWarehouseService(properties, dependencies)
        .validated(REPRESENTATIVE_WAREHOUSE_ID);

    verify(properties)
        .validated(
            REPRESENTATIVE_WAREHOUSE_ID,
            representative.latitude(),
            representative.longitude());
  }

  @Test
  void omitsRepresentativeWarehouseWithoutCoordinates() {
    CustomerDeliveryProperties properties = mock(CustomerDeliveryProperties.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(properties.validatedDepots()).thenReturn(Map.of(WAREHOUSE_ID, depot()));
    when(dependencies.listWarehouseIdentities())
        .thenReturn(
            List.of(
                new WarehouseIdentity(
                    REPRESENTATIVE_WAREHOUSE_ID,
                    2,
                    true,
                    "Великий Новгород",
                    "Великий Новгород",
                    null,
                    null,
                    null,
                    "Europe/Moscow",
                    true)));

    assertThat(new CustomerWarehouseService(properties, dependencies).list()).isEmpty();
  }

  @Test
  void omitsAndRejectsWarehouseAtNullIsland() {
    CustomerDeliveryProperties properties = mock(CustomerDeliveryProperties.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    UUID zeroAxisWarehouseId = UUID.randomUUID();
    WarehouseIdentity nullIsland =
        new WarehouseIdentity(
            REPRESENTATIVE_WAREHOUSE_ID,
            2,
            true,
            "Региональный склад",
            "Город",
            null,
            BigDecimal.ZERO,
            BigDecimal.ZERO,
            "Europe/Moscow",
            true);
    WarehouseIdentity zeroAxis =
        new WarehouseIdentity(
            zeroAxisWarehouseId,
            1,
            true,
            "Склад на экваторе",
            "Город",
            null,
            BigDecimal.ZERO,
            new BigDecimal("31.200000"),
            "Europe/Moscow",
            true);
    when(properties.validatedDepots()).thenReturn(Map.of(WAREHOUSE_ID, depot()));
    when(dependencies.listWarehouseIdentities()).thenReturn(List.of(nullIsland, zeroAxis));
    when(dependencies.readWarehouseIdentity(REPRESENTATIVE_WAREHOUSE_ID))
        .thenReturn(nullIsland);
    CustomerWarehouseService service =
        new CustomerWarehouseService(properties, dependencies);

    assertThat(service.list())
        .extracting(CustomerWarehouseResponse::id)
        .containsExactly(zeroAxisWarehouseId);
    assertThatThrownBy(() -> service.required(REPRESENTATIVE_WAREHOUSE_ID))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("CUSTOMER_WAREHOUSE_NOT_FOUND"));
  }

  private static Validated depot() {
    return new Validated(
        WAREHOUSE_ID,
        new BigDecimal("59.763806"),
        new BigDecimal("30.471798"),
        URI.create("http://127.0.0.1:8002"),
        "test-routing-data-v1",
        Duration.ofMinutes(15),
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
