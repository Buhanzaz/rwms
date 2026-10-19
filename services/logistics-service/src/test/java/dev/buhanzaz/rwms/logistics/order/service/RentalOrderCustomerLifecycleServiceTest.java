package dev.buhanzaz.rwms.logistics.order.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;

/** Covers the narrow customer-owned SAVED order boundary used by post-checkout commands. */
class RentalOrderCustomerLifecycleServiceTest {
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000901");
  private static final UUID ORDER = UUID.fromString("00000000-0000-0000-0000-000000000902");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000903");

  @Test
  void customerRescheduleChangesOnlyTheDesiredDateAndAppendsAudit() {
    Fixture fixture = fixture();
    LocalDate previousDate = LocalDate.of(2026, 9, 2);
    LocalDate newDate = LocalDate.of(2026, 9, 5);
    when(fixture.order().getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(previousDate, previousDate)));
    when(fixture.order().replaceClientDesiredDeliveryWindows(any())).thenReturn(true);

    var result = fixture.service().reschedule(customer(), ORDER, newDate);

    assertThat(result.orderId()).isEqualTo(ORDER);
    assertThat(result.version()).isEqualTo(7);
    verify(fixture.order())
        .replaceClientDesiredDeliveryWindows(
            List.of(DesiredDeliveryWindow.create(newDate, newDate)));
    verify(fixture.orders()).persist(fixture.order());
    verify(fixture.audit()).append(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void sameDaySlotSwapStillAdvancesOrderFenceForAStalePlannerResult() {
    Fixture fixture = fixture();
    LocalDate deliveryDate = LocalDate.of(2026, 9, 5);
    when(fixture.order().getDesiredDeliveryWindows())
        .thenReturn(List.of(DesiredDeliveryWindow.create(deliveryDate, deliveryDate)));
    when(fixture.order().replaceClientDesiredDeliveryWindows(any())).thenReturn(false);

    fixture.service().reschedule(customer(), ORDER, deliveryDate);

    verify(fixture.order()).markCustomerBookingRescheduled();
    verify(fixture.orders()).persist(fixture.order());
    verify(fixture.audit()).append(any(), any(), any(), any(), any(), any(), any());
  }

  @Test
  void sharedEditabilityGuardBlocksStartedWorkWithoutMutation() {
    Fixture fixture = fixture();
    doThrow(
            new OrderProblemException(
                HttpStatus.CONFLICT,
                "CUSTOMER_BOOKING_NOT_EDITABLE",
                "Бронирование нельзя изменить после начала отгрузки"))
        .when(fixture.editability())
        .requireEditable(customer(), fixture.order());

    assertThatThrownBy(
            () -> fixture.service().reschedule(customer(), ORDER, LocalDate.of(2026, 9, 5)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem ->
                assertThat(problem.getMessage())
                    .isEqualTo("Бронирование нельзя изменить после начала отгрузки"));
    verify(fixture.order(), never()).replaceClientDesiredDeliveryWindows(any());
    verify(fixture.orders(), never()).persist(any());
  }

  @Test
  void nonCustomerActorCannotDiscoverTheOrderThroughThisBoundary() {
    Fixture fixture = fixture();
    OrderActor manager =
        new OrderActor(
            SUBJECT,
            "WAREHOUSE_MANAGER",
            "Менеджер",
            Set.of(WAREHOUSE),
            Set.of(WAREHOUSE),
            false,
            true,
            true,
            true);

    assertThatThrownBy(() -> fixture.service().requireCancellation(manager, ORDER))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.status()).isEqualTo(HttpStatus.NOT_FOUND));
    verify(fixture.orders(), never()).lockedOrder(any(OrderActor.class), any(UUID.class));
  }

  private static Fixture fixture() {
    RentalOrderCommandStore orders = mock(RentalOrderCommandStore.class);
    RentalOrderEditabilityService editability = mock(RentalOrderEditabilityService.class);
    OrderAuditService audit = mock(OrderAuditService.class);
    RentalOrder order = mock(RentalOrder.class);
    when(orders.lockedOrder(customer(), ORDER)).thenReturn(order);
    when(order.getId()).thenReturn(ORDER);
    when(order.getVersion()).thenReturn(7L);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE);
    when(order.getDeliveryAddress()).thenReturn("Великий Новгород, тестовый адрес");
    when(order.getLatitude()).thenReturn(new BigDecimal("58.521475"));
    when(order.getLongitude()).thenReturn(new BigDecimal("31.275475"));
    return new Fixture(
        new RentalOrderCustomerLifecycleService(orders, editability, audit),
        orders,
        editability,
        audit,
        order);
  }

  private static OrderActor customer() {
    return new OrderActor(
        SUBJECT,
        "CUSTOMER",
        "Клиент",
        Set.of(WAREHOUSE),
        Set.of(WAREHOUSE),
        false,
        false,
        false,
        false);
  }

  /** Fixed collaborator graph for an order-bound lifecycle command. */
  private record Fixture(
      RentalOrderCustomerLifecycleService service,
      RentalOrderCommandStore orders,
      RentalOrderEditabilityService editability,
      OrderAuditService audit,
      RentalOrder order) {}
}
