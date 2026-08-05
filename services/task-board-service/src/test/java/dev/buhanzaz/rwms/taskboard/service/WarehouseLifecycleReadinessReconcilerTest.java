package dev.buhanzaz.rwms.taskboard.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWorkPage;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class WarehouseLifecycleReadinessReconcilerTest {
  private final WarehouseLifecycleGateway lifecycle = mock(WarehouseLifecycleGateway.class);
  private final WarehouseLifecycleFence fence = mock(WarehouseLifecycleFence.class);
  private final WarehouseLifecycleReadinessReconciler reconciler =
      new WarehouseLifecycleReadinessReconciler(lifecycle, fence);

  @Test
  void pagesBacklogAndLetsFenceSkipLiveWorkWhileConfirmingEmptyOwner() {
    ReadinessWork blocked = work();
    ReadinessWork empty = work();
    ReadinessWork later = work();
    when(lifecycle.readinessWork(null, 100))
        .thenReturn(new ReadinessWorkPage(List.of(blocked, empty), empty.warehouseId()));
    when(lifecycle.readinessWork(empty.warehouseId(), 100))
        .thenReturn(new ReadinessWorkPage(List.of(later), null));
    when(fence.confirmReadinessIfNoLiveWork(blocked)).thenReturn(false);
    when(fence.confirmReadinessIfNoLiveWork(empty)).thenReturn(true);
    when(fence.confirmReadinessIfNoLiveWork(later)).thenReturn(true);

    reconciler.reconcile();

    verify(lifecycle).readinessWork(null, 100);
    verify(lifecycle).readinessWork(empty.warehouseId(), 100);
    verify(fence).confirmReadinessIfNoLiveWork(blocked);
    verify(fence).confirmReadinessIfNoLiveWork(empty);
    verify(fence).confirmReadinessIfNoLiveWork(later);
  }

  @Test
  void confirmationConflictIsNotRetriedUntilTheNextFullCycle() {
    ReadinessWork current = work();
    when(lifecycle.readinessWork(null, 100))
        .thenReturn(new ReadinessWorkPage(List.of(current), null));
    when(fence.confirmReadinessIfNoLiveWork(current))
        .thenThrow(new WarehouseLifecycleReadinessConflictException("stale", null))
        .thenReturn(true);

    reconciler.reconcile();
    verify(fence).confirmReadinessIfNoLiveWork(current);

    reconciler.reconcile();
    verify(fence, org.mockito.Mockito.times(2)).confirmReadinessIfNoLiveWork(current);
  }

  @Test
  void failedPageLoadFailsClosedWithoutAttemptingConfirmation() {
    when(lifecycle.readinessWork(null, 100))
        .thenThrow(new ExternalServiceException("warehouse unavailable", null));

    reconciler.reconcile();

    verify(fence, never()).confirmReadinessIfNoLiveWork(any());
  }

  private static ReadinessWork work() {
    return new ReadinessWork(UUID.randomUUID(), 1L, "DRAINING");
  }
}
