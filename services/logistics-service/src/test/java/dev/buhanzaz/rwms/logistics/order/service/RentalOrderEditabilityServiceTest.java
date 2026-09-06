package dev.buhanzaz.rwms.logistics.order.service;


import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentSource;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentState;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

/** Verifies that cabin replacement permission is server-derived and independent from edits. */
class RentalOrderEditabilityServiceTest {
  private static final UUID ORDER_ID = UUID.randomUUID();
  private static final UUID WAREHOUSE_ID = UUID.randomUUID();
  private static final UUID ACTOR_ID = UUID.randomUUID();

  private final OrderAuthorizer access = mock(OrderAuthorizer.class);
  private final LogisticsDocumentService documents = mock(LogisticsDocumentService.class);
  private final ShipmentFurnitureMovementTaskRepository furnitureTasks =
      mock(ShipmentFurnitureMovementTaskRepository.class);
  private final RentalOrderEditabilityService service =
      new RentalOrderEditabilityService(access, documents, furnitureTasks);

  @Test
  void draftEditingAndReplacementRemainAvailableBeforeSavedOrderRequiresPayment() {
    OrderAuthorizer realAccess = new OrderAuthorizer(new MockEnvironment(), false);
    RentalOrderEditabilityService realService =
        new RentalOrderEditabilityService(realAccess, documents, furnitureTasks);
    OrderActor actor =
        new OrderActor(
            ACTOR_ID,
            "WAREHOUSE_MANAGER",
            "Manager",
            Set.of(WAREHOUSE_ID),
            Set.of(WAREHOUSE_ID),
            false,
            true,
            true,
            true);
    RentalOrder order =
        RentalOrder.create(
            "EDIT-PAYMENT",
            mock(OrderClient.class),
            ACTOR_ID,
            "Manager",
            ACTOR_ID,
            "Manager",
            "WAREHOUSE_MANAGER",
            "+79991234567",
            null,
            UUID.randomUUID(),
            "a".repeat(64));
    order.selectWarehouse(WAREHOUSE_ID);
    order.replaceClientDeliveryDetails("Delivery address", null, null, List.of());
    var unit = mock(LogisticsDependencyGateway.OrderUnitReservation.class);
    UUID unitId = UUID.randomUUID();
    when(unit.unitId()).thenReturn(unitId);
    when(documents.hasRentalOrderReplaceableUnit(order.getId(), Set.of(unitId))).thenReturn(true);
    when(documents.isRentalOrderShipmentDraftEditable(order.getId())).thenReturn(true);
    when(documents.lockRentalOrderShipmentDraftForOrderEditing(order.getId())).thenReturn(true);

    assertThat(order.getPaymentState()).isNull();
    realService.requireEditable(actor, order);
    assertThat(realService.canEdit(actor, order)).isTrue();
    assertThat(realService.canReplaceUnits(actor, order, List.of(unit))).isTrue();

    order.saveForFulfillment();
    assertThat(realService.canEdit(actor, order)).isFalse();
    assertThat(realService.canReplaceUnits(actor, order, List.of(unit))).isFalse();
    assertThatThrownBy(() -> realService.requireEditable(actor, order))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("ORDER_PAYMENT_PENDING"));

    OffsetDateTime paymentStartedAt = OffsetDateTime.now();
    order.startPaymentReservation(paymentStartedAt);
    assertThat(realService.canEdit(actor, order)).isFalse();
    assertThat(realService.canReplaceUnits(actor, order, List.of(unit))).isFalse();
    assertThatThrownBy(() -> realService.requireEditable(actor, order))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            error -> assertThat(error.code()).isEqualTo("ORDER_PAYMENT_PENDING"));

    order.confirmPayment(
        RentalOrderPaymentSource.MANAGER_CONFIRMATION, ACTOR_ID, paymentStartedAt.plusSeconds(1));
    realService.requireEditable(actor, order);
    assertThat(realService.canEdit(actor, order)).isTrue();
    assertThat(realService.canReplaceUnits(actor, order, List.of(unit))).isTrue();
  }

  @Test
  void scheduledWaitingShipmentIsReplaceableButNotEditableAndStartedShipmentIsNeither() {
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getPaymentState()).thenReturn(RentalOrderPaymentState.CONFIRMED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    OrderActor warehouseManager =
        new OrderActor(
            ACTOR_ID,
            "WAREHOUSE_MANAGER",
            "Руководитель склада",
            Set.of(WAREHOUSE_ID),
            Set.of(WAREHOUSE_ID),
            false,
            true,
            true,
            true);
    when(access.canEdit(warehouseManager, order)).thenReturn(true);
    when(access.isVisible(warehouseManager, order)).thenReturn(true);
    when(access.canEditWarehouse(warehouseManager, WAREHOUSE_ID)).thenReturn(true);
    when(documents.isRentalOrderShipmentDraftEditable(ORDER_ID)).thenReturn(false);
    LogisticsDependencyGateway.OrderUnitReservation activeUnit =
        mock(LogisticsDependencyGateway.OrderUnitReservation.class);
    when(activeUnit.unitId()).thenReturn(UUID.randomUUID());
    when(documents.hasRentalOrderReplaceableUnit(
            org.mockito.ArgumentMatchers.eq(ORDER_ID), org.mockito.ArgumentMatchers.anySet()))
        .thenReturn(true, false);

    assertThat(service.canEdit(warehouseManager, order)).isFalse();
    assertThat(service.canReplaceUnits(warehouseManager, order, List.of(activeUnit))).isTrue();
    assertThat(service.canReplaceUnits(warehouseManager, order, List.of(activeUnit))).isFalse();
  }

  @Test
  void rentalManagerCannotReplaceEvenBeforeShipmentStart() {
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
    when(order.getWarehouseId()).thenReturn(WAREHOUSE_ID);
    OrderActor rentalManager =
        new OrderActor(
            ACTOR_ID,
            "RENTAL_MANAGER",
            "Менеджер",
            Set.of(WAREHOUSE_ID),
            Set.of(WAREHOUSE_ID),
            false,
            false,
            true,
            true);

    assertThat(
            service.canReplaceUnits(
                rentalManager,
                order,
                List.of(mock(LogisticsDependencyGateway.OrderUnitReservation.class))))
        .isFalse();
  }

  @Test
  void orderWithoutActiveCabinsDoesNotOfferReplacement() {
    RentalOrder order = mock(RentalOrder.class);
    OrderActor warehouseManager =
        new OrderActor(
            ACTOR_ID,
            "WAREHOUSE_MANAGER",
            "Руководитель склада",
            Set.of(WAREHOUSE_ID),
            Set.of(WAREHOUSE_ID),
            false,
            true,
            true,
            true);

    assertThat(service.canReplaceUnits(warehouseManager, order, List.of())).isFalse();
    org.mockito.Mockito.verifyNoInteractions(access, documents, furnitureTasks);
  }
}
