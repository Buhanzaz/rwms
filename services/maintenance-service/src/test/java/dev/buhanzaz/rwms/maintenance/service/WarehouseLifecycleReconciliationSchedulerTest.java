package dev.buhanzaz.rwms.maintenance.service;

import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;
import org.springframework.http.HttpStatus;

class WarehouseLifecycleReconciliationSchedulerTest {
  private final MaintenanceDependencyGateway dependencies =
      mock(MaintenanceDependencyGateway.class);
  private final WarehouseOperationMarkStore marks = mock(WarehouseOperationMarkStore.class);
  private final WarehouseReadinessFenceStore readinessFences =
      mock(WarehouseReadinessFenceStore.class);
  private final WarehouseLifecycleReconciliationScheduler scheduler =
      new WarehouseLifecycleReconciliationScheduler(dependencies, marks, readinessFences);

  @Test
  void claimsBeforeRemoteCallAndConfirmsThroughSeparateStoreBoundary() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID claimToken = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    WarehouseOperationMarkStore.WorkItem work = new WarehouseOperationMarkStore.WorkItem(
        operationId,
        warehouseId,
        occurredAt,
        0,
        claimToken,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(2));
    when(dependencies.productionReady()).thenReturn(true);
    when(marks.claimNextDue(Duration.ofMinutes(2)))
        .thenReturn(Optional.of(work), Optional.empty());

    scheduler.reconcileOperationMarks();

    InOrder order = inOrder(marks, dependencies);
    order.verify(marks).claimNextDue(Duration.ofMinutes(2));
    order.verify(dependencies).markWarehouseOperation(warehouseId, operationId, occurredAt);
    order.verify(marks).confirmed(work);
    order.verify(marks).claimNextDue(Duration.ofMinutes(2));
  }

  @Test
  void confirmsOnlyWorkThatAcquiredTheAtomicLocalFence() {
    UUID ready = UUID.randomUUID();
    UUID blocked = UUID.randomUUID();
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseLifecycleReadinessWork(null, 100))
        .thenReturn(new MaintenanceDependencyGateway.WarehouseLifecycleReadinessWorkPage(
            List.of(
                work(ready, 7),
                work(blocked, 9)),
            null));
    when(readinessFences.begin(ready, 7)).thenReturn(
        new WarehouseReadinessFenceStore.BeginResult(
            WarehouseReadinessFenceStore.BeginState.FENCED, null));
    when(readinessFences.begin(blocked, 9)).thenReturn(
        new WarehouseReadinessFenceStore.BeginResult(
            WarehouseReadinessFenceStore.BeginState.BLOCKED, null));

    scheduler.reconcileReadiness();

    verify(dependencies).confirmWarehouseLifecycleReadiness(ready, 7);
    verify(readinessFences).seal(ready, 7);
    verify(dependencies, never()).confirmWarehouseLifecycleReadiness(blocked, 9);
  }

  @Test
  void deterministicRemoteVersionRejectionReleasesOnlyTheMatchingFence() {
    UUID warehouseId = UUID.randomUUID();
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseLifecycleReadinessWork(null, 100))
        .thenReturn(new MaintenanceDependencyGateway.WarehouseLifecycleReadinessWorkPage(
            List.of(work(warehouseId, 11)), null));
    when(readinessFences.begin(warehouseId, 11)).thenReturn(
        new WarehouseReadinessFenceStore.BeginResult(
            WarehouseReadinessFenceStore.BeginState.FENCED, null));
    when(dependencies.confirmWarehouseLifecycleReadiness(warehouseId, 11))
        .thenThrow(new MaintenanceDependencyException(HttpStatus.CONFLICT, "stale version"));

    scheduler.reconcileReadiness();

    verify(readinessFences).release(warehouseId, 11, "REMOTE_409");
    verify(readinessFences, never()).seal(warehouseId, 11);
  }

  @Test
  void disabledDependenciesDoNotAcknowledgeDurableReadinessWork() {
    when(dependencies.productionReady()).thenReturn(false);

    scheduler.reconcileReadiness();

    verify(dependencies, never()).warehouseLifecycleReadinessWork(null, 100);
  }

  private static MaintenanceDependencyGateway.WarehouseLifecycleReadinessWork work(
      UUID warehouseId, long version) {
    return new MaintenanceDependencyGateway.WarehouseLifecycleReadinessWork(
        warehouseId,
        version,
        MaintenanceDependencyGateway.WarehouseLifecycleState.DRAINING);
  }
}
