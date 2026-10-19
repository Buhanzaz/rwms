package dev.buhanzaz.rwms.inventory.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.domain.InventoryStartOperation;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureLossIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySessionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventorySourceAttachmentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryStartOperationRepository;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;

class InventoryWarehouseLifecycleReconcilerTest {
  private InventoryDependencyGateway dependencies;
  private InventorySessionRepository sessions;
  private InventoryStartOperationRepository startOperations;
  private InventoryWarehouseLifecycleReconciler reconciler;

  @BeforeEach
  void setUp() {
    dependencies = mock(InventoryDependencyGateway.class);
    sessions = mock(InventorySessionRepository.class);
    startOperations = mock(InventoryStartOperationRepository.class);
    reconciler =
        new InventoryWarehouseLifecycleReconciler(
            dependencies,
            sessions,
            startOperations,
            mock(InventoryPublicationIntentRepository.class),
            mock(InventoryFurnitureReconciliationIntentRepository.class),
            mock(InventoryFurnitureLossIntentRepository.class),
            mock(InventoryFindingRepository.class),
            mock(InventorySourceAttachmentRepository.class));
    when(dependencies.productionReady()).thenReturn(true);
    when(sessions.findByWarehouseIdAndLifecycle(any(UUID.class), eq(SessionLifecycle.ACTIVE)))
        .thenReturn(java.util.Optional.empty());
    when(startOperations.findAll(any(Pageable.class))).thenReturn(Page.empty());
    when(sessions.findByWarehouseId(any(UUID.class), any(Pageable.class)))
        .thenReturn(Page.empty());
  }

  @Test
  void pagesDurableWorkAndConfirmsEveryWarehouseWithoutLocalBlockers() {
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    when(dependencies.warehouseLifecycleReadinessWork(null, 100))
        .thenReturn(page(first, 3, first));
    when(dependencies.warehouseLifecycleReadinessWork(first, 100))
        .thenReturn(page(second, 8, null));
    when(dependencies.confirmWarehouseLifecycleReadiness(first, 3))
        .thenReturn(confirmation(first, 3));
    when(dependencies.confirmWarehouseLifecycleReadiness(second, 8))
        .thenReturn(confirmation(second, 8));

    reconciler.reconcile();

    verify(dependencies).confirmWarehouseLifecycleReadiness(first, 3);
    verify(dependencies).confirmWarehouseLifecycleReadiness(second, 8);
    verify(startOperations).findAll(any(Pageable.class));
  }

  @Test
  void localLiveWorkPreventsReadinessConfirmation() {
    UUID warehouseId = UUID.randomUUID();
    when(dependencies.warehouseLifecycleReadinessWork(null, 100))
        .thenReturn(page(warehouseId, 5, null));
    when(sessions.findByWarehouseIdAndLifecycle(warehouseId, SessionLifecycle.ACTIVE))
        .thenReturn(
            java.util.Optional.of(
                mock(dev.buhanzaz.rwms.inventory.domain.InventorySession.class)));

    reconciler.reconcile();

    verify(dependencies, never())
        .confirmWarehouseLifecycleReadiness(any(UUID.class), anyLong());
  }

  @Test
  void staleConfirmationFenceIsRetriedFromFreshWorklistOnNextCycle() {
    UUID warehouseId = UUID.randomUUID();
    when(dependencies.warehouseLifecycleReadinessWork(null, 100))
        .thenReturn(page(warehouseId, 5, null), page(warehouseId, 6, null));
    when(dependencies.confirmWarehouseLifecycleReadiness(warehouseId, 5))
        .thenThrow(InventoryException.conflict("Warehouse version changed"));
    when(dependencies.confirmWarehouseLifecycleReadiness(warehouseId, 6))
        .thenReturn(confirmation(warehouseId, 6));

    reconciler.reconcile();
    reconciler.reconcile();

    verify(dependencies).confirmWarehouseLifecycleReadiness(warehouseId, 5);
    verify(dependencies).confirmWarehouseLifecycleReadiness(warehouseId, 6);
    verify(dependencies, times(2)).warehouseLifecycleReadinessWork(null, 100);
  }

  @Test
  void unfinishedStartOperationBlocksReadinessWithoutLowLevelSql() {
    UUID warehouseId = UUID.randomUUID();
    when(dependencies.warehouseLifecycleReadinessWork(null, 100))
        .thenReturn(page(warehouseId, 5, null));
    InventoryStartOperation operation =
        InventoryStartOperation.request(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            "a".repeat(64),
            warehouseId,
            OffsetDateTime.parse("2026-09-02T09:00:00Z"));
    when(startOperations.findAll(any(Pageable.class)))
        .thenReturn(new PageImpl<>(List.of(operation)));

    reconciler.reconcile();

    verify(dependencies, never())
        .confirmWarehouseLifecycleReadiness(any(UUID.class), anyLong());
  }

  private InventoryDependencyGateway.WarehouseLifecycleReadinessWorkPage page(
      UUID warehouseId, long version, UUID nextAfter) {
    return new InventoryDependencyGateway.WarehouseLifecycleReadinessWorkPage(
        List.of(
            new InventoryDependencyGateway.WarehouseLifecycleReadinessWork(
                warehouseId, version, "DRAINING")),
        nextAfter);
  }

  private InventoryDependencyGateway.WarehouseLifecycleReadinessConfirmation confirmation(
      UUID warehouseId, long version) {
    return new InventoryDependencyGateway.WarehouseLifecycleReadinessConfirmation(
        warehouseId,
        version,
        "DRAINING",
        "INVENTORY",
        OffsetDateTime.parse("2026-09-02T09:00:00Z"));
  }
}
