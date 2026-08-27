package dev.buhanzaz.rwms.logistics.customer.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.same;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.customer.api.CustomerApiModels.DeliverySlotSearchRequest;
import dev.buhanzaz.rwms.logistics.customer.capacity.repository.ScenarioCapacityJobRepository;
import dev.buhanzaz.rwms.logistics.customer.config.CustomerDeliveryProperties;
import dev.buhanzaz.rwms.logistics.customer.domain.CustomerRentalSession;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerRouteCapacityPlanner.CapacityDecision;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix;
import dev.buhanzaz.rwms.logistics.customer.routing.CustomerTravelTimeMatrix.GeoPoint;
import dev.buhanzaz.rwms.logistics.customer.routing.ValhallaCustomerTravelTimeClient;
import dev.buhanzaz.rwms.logistics.customer.security.CustomerIdentity;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseIdentity;
import java.math.BigDecimal;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** Covers delivery-priority workload selection before CustomerApp slot routing. */
class CustomerDeliverySlotServiceTest {
  private static final UUID SUBJECT =
      UUID.fromString("00000000-0000-0000-0000-000000000501");
  private static final UUID INQUIRY =
      UUID.fromString("00000000-0000-0000-0000-000000000502");
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000503");
  private static final Clock CLOCK =
      Clock.fixed(Instant.parse("2026-08-27T06:00:00Z"), ZoneOffset.UTC);

  @Test
  void reservesDriversForDeliveriesButLeavesReturnsForBackhaul() {
    CustomerRentalService rentals = mock(CustomerRentalService.class);
    CustomerWarehouseService warehouses = mock(CustomerWarehouseService.class);
    CustomerRentalSessionStore sessions = mock(CustomerRentalSessionStore.class);
    CustomerDeliverySlotStore slotStore = mock(CustomerDeliverySlotStore.class);
    CustomerDeliverySlotHoldStore holdStore = mock(CustomerDeliverySlotHoldStore.class);
    ScenarioCapacityJobRepository generated = mock(ScenarioCapacityJobRepository.class);
    ValhallaCustomerTravelTimeClient travelTimes =
        mock(ValhallaCustomerTravelTimeClient.class);
    CustomerRouteCapacityPlanner capacity = mock(CustomerRouteCapacityPlanner.class);
    DriverLogisticsTaskRepository driverTasks = mock(DriverLogisticsTaskRepository.class);
    CustomerDeliverySlotService service =
        new CustomerDeliverySlotService(
            rentals,
            warehouses,
            sessions,
            slotStore,
            holdStore,
            generated,
            travelTimes,
            capacity,
            driverTasks,
            CLOCK);
    CustomerRentalSession session = CustomerRentalSession.create(INQUIRY, SUBJECT, WAREHOUSE);
    CustomerDeliveryProperties.Validated configuration = configuration();
    CustomerTravelTimeMatrix matrix =
        new CustomerTravelTimeMatrix(
            List.of(new GeoPoint(55.75, 37.61), new GeoPoint(55.80, 37.70)),
            List.of(List.of(0L, 600L), List.of(600L, 0L)));
    when(sessions.required(SUBJECT, INQUIRY)).thenReturn(session);
    when(warehouses.validated(WAREHOUSE)).thenReturn(configuration);
    when(warehouses.required(WAREHOUSE))
        .thenReturn(new WarehouseIdentity(WAREHOUSE, 0, true, "Europe/Moscow"));
    when(rentals.selectedCabinIds(any(), eq(INQUIRY))).thenReturn(List.of(UUID.randomUUID()));
    when(slotStore.workload(eq(WAREHOUSE), any(), any())).thenReturn(List.of());
    when(generated.findCapacityWorkload(eq(WAREHOUSE), any())).thenReturn(List.of());
    when(travelTimes.matrix(anyList(), any(), same(configuration))).thenReturn(matrix);
    when(capacity.evaluate(same(matrix), anyList(), same(configuration), anyInt()))
        .thenReturn(new CapacityDecision(true, 0));
    when(slotStore.replaceOffers(eq(SUBJECT), eq(INQUIRY), anyList()))
        .thenAnswer(invocation -> invocation.getArgument(2));

    service.search(
        new CustomerIdentity(SUBJECT, "customer"),
        new DeliverySlotSearchRequest(
            INQUIRY, "Москва", BigDecimal.valueOf(55.80), BigDecimal.valueOf(37.70)));

    verify(driverTasks, times(1))
        .countWholeDayDeliveryReservations(eq(WAREHOUSE), any());
    verify(travelTimes, times(1)).matrix(anyList(), any(), same(configuration));
  }

  private static CustomerDeliveryProperties.Validated configuration() {
    return new CustomerDeliveryProperties(
            true,
            List.of(
                new CustomerDeliveryProperties.Depot(
                    true,
                    WAREHOUSE.toString(),
                    BigDecimal.valueOf(55.75),
                    BigDecimal.valueOf(37.61))),
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
            1,
            1,
            Duration.ofMinutes(10),
            Duration.ofMinutes(20),
            4.0,
            2.5,
            12.0,
            20.0,
            8.0,
            3)
        .validated(WAREHOUSE);
  }
}
