package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import dev.buhanzaz.rwms.logistics.order.security.OrderAuthorizer;
import dev.buhanzaz.rwms.logistics.repository.ShipmentFurnitureMovementTaskRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;

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
  void scheduledWaitingShipmentIsReplaceableButNotEditableAndStartedShipmentIsNeither() {
    RentalOrder order = mock(RentalOrder.class);
    when(order.getId()).thenReturn(ORDER_ID);
    when(order.getStatus()).thenReturn(RentalOrderStatus.SAVED);
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
