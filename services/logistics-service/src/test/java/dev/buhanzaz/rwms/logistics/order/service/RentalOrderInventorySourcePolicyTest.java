package dev.buhanzaz.rwms.logistics.order.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.security.OrderActor;
import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.security.access.AccessDeniedException;

/** Verifies directed support-link and warehouse-scope fences for a physical order source. */
class RentalOrderInventorySourcePolicyTest {
  private final LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
  private final RentalOrderInventorySourcePolicy policy =
      new RentalOrderInventorySourcePolicy(dependencies);

  @Test
  void sameWarehouseRemainsCompatibleWithoutSupportLookup() {
    UUID warehouseId = UUID.randomUUID();
    RentalOrder order = order(warehouseId);

    UUID source =
        policy.requireWritableShipmentSource(
            actor(Set.of(warehouseId)), order, null, LocalDate.of(2026, 9, 14));

    assertThat(source).isEqualTo(warehouseId);
    verify(dependencies, never()).listWarehouseSupportLinks(any(), any());
  }

  @Test
  void activeInventoryDirectLinkAllowsIndependentSourceWithoutChangingOrderWarehouse() {
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    RentalOrder order = order(serviceWarehouseId);
    LogisticsDependencyGateway.WarehouseIdentity served =
        warehouse(serviceWarehouseId, "Europe/Moscow");
    LogisticsDependencyGateway.WarehouseIdentity source =
        warehouse(sourceWarehouseId, "Europe/Moscow");
    when(dependencies.readWarehouseIdentity(serviceWarehouseId)).thenReturn(served);
    when(dependencies.listWarehouseSupportLinks(any(), any()))
        .thenReturn(List.of(link(source, served, true, true)));

    UUID selected =
        policy.requireWritableShipmentSource(
            actor(Set.of(serviceWarehouseId, sourceWarehouseId)),
            order,
            sourceWarehouseId,
            LocalDate.of(2026, 9, 14));

    assertThat(selected).isEqualTo(sourceWarehouseId);
    assertThat(order.getWarehouseId()).isEqualTo(serviceWarehouseId);
  }

  @Test
  void sourceWithoutBothCapabilitiesFailsClosed() {
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    RentalOrder order = order(serviceWarehouseId);
    LogisticsDependencyGateway.WarehouseIdentity served =
        warehouse(serviceWarehouseId, "Europe/Moscow");
    LogisticsDependencyGateway.WarehouseIdentity source =
        warehouse(sourceWarehouseId, "Europe/Moscow");
    when(dependencies.readWarehouseIdentity(serviceWarehouseId)).thenReturn(served);
    when(dependencies.listWarehouseSupportLinks(any(), any()))
        .thenReturn(List.of(link(source, served, true, false)));

    assertThatThrownBy(
            () ->
                policy.requireWritableShipmentSource(
                    actor(Set.of(serviceWarehouseId, sourceWarehouseId)),
                    order,
                    sourceWarehouseId,
                    LocalDate.of(2026, 9, 14)))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("DIRECT_SOURCE_NOT_ALLOWED"));
  }

  @Test
  void replacementSourceUsesTheSameDirectedInventoryAndDirectFulfillmentFence() {
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    when(dependencies.listWarehouseSupportLinks(any(), any()))
        .thenReturn(
            List.of(
                link(
                    warehouse(sourceWarehouseId, "Europe/Moscow"),
                    warehouse(serviceWarehouseId, "Europe/Moscow"),
                    true,
                    true)));

    UUID selected =
        policy.requireWritableReplacementSource(
            actor(Set.of(serviceWarehouseId, sourceWarehouseId)),
            serviceWarehouseId,
            sourceWarehouseId);

    assertThat(selected).isEqualTo(sourceWarehouseId);
  }

  @Test
  void replacementSourceWithoutLinkOrWarehouseScopeFailsBeforeAnyAssetCommandCanRun() {
    UUID serviceWarehouseId = UUID.randomUUID();
    UUID sourceWarehouseId = UUID.randomUUID();
    when(dependencies.listWarehouseSupportLinks(any(), any())).thenReturn(List.of());

    assertThatThrownBy(
            () ->
                policy.requireWritableReplacementSource(
                    actor(Set.of(serviceWarehouseId, sourceWarehouseId)),
                    serviceWarehouseId,
                    sourceWarehouseId))
        .isInstanceOfSatisfying(
            OrderProblemException.class,
            problem -> assertThat(problem.code()).isEqualTo("DIRECT_SOURCE_NOT_ALLOWED"));

    assertThatThrownBy(
            () ->
                policy.requireWritableReplacementSource(
                    actor(Set.of(serviceWarehouseId)),
                    serviceWarehouseId,
                    sourceWarehouseId))
        .isInstanceOf(AccessDeniedException.class);
  }

  private static RentalOrder order(UUID warehouseId) {
    RentalOrder order = mock(RentalOrder.class);
    when(order.getWarehouseId()).thenReturn(warehouseId);
    return order;
  }

  private static OrderActor actor(Set<UUID> warehouses) {
    return new OrderActor(
        UUID.randomUUID(),
        "WMS_ADMIN",
        "Логист",
        warehouses,
        warehouses,
        false,
        false,
        true,
        true);
  }

  private static LogisticsDependencyGateway.WarehouseIdentity warehouse(
      UUID warehouseId, String timeZone) {
    return new LogisticsDependencyGateway.WarehouseIdentity(
        warehouseId, 1, true, "Склад", "Город", null, null, null, timeZone, false);
  }

  private static LogisticsDependencyGateway.WarehouseSupportLink link(
      LogisticsDependencyGateway.WarehouseIdentity source,
      LogisticsDependencyGateway.WarehouseIdentity served,
      boolean allowInventory,
      boolean allowDirectFulfillment) {
    return new LogisticsDependencyGateway.WarehouseSupportLink(
        UUID.randomUUID(),
        1,
        source,
        served,
        1,
        true,
        true,
        allowInventory,
        allowDirectFulfillment,
        true,
        true,
        Set.of(),
        Set.of(),
        Set.of(),
        null,
        null);
  }
}
