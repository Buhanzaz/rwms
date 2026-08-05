package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionPermit;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleFence.AdmissionTarget;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.ReadinessWork;
import dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleIntentStore.ReadinessReservation;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.InOrder;

class TaskBoardWarehouseLifecycleFenceTest {
  private final WarehouseLifecycleIntentStore intents = mock(WarehouseLifecycleIntentStore.class);
  private final WarehouseLifecycleGateway lifecycle = mock(WarehouseLifecycleGateway.class);
  private final TaskBoardWarehouseLifecycleFence fence =
      new TaskBoardWarehouseLifecycleFence(intents, lifecycle);

  @Test
  void activeTaskOrUnfinishedEntryBlocksReadinessConfirmation() {
    ReadinessWork work = new ReadinessWork(UUID.randomUUID(), 4L, "DRAINING");
    when(intents.reserveReadinessIfClear(work)).thenReturn(null);

    boolean confirmed = fence.confirmReadinessIfNoLiveWork(work);

    assertThat(confirmed).isFalse();
    verify(lifecycle, never()).confirmReadiness(any(), any(Long.class));
  }

  @Test
  void emptyTaskBoardOwnerStateCommitsBarrierBeforeRemoteConfirmation() {
    ReadinessWork work = new ReadinessWork(UUID.randomUUID(), 7L, "DRAINING");
    ReadinessReservation reservation =
        new ReadinessReservation(UUID.randomUUID(), work.warehouseId(), work.warehouseVersion());
    when(intents.reserveReadinessIfClear(work)).thenReturn(reservation);

    boolean confirmed = fence.confirmReadinessIfNoLiveWork(work);

    assertThat(confirmed).isTrue();
    InOrder order = inOrder(intents, lifecycle);
    order.verify(intents).reserveReadinessIfClear(work);
    order.verify(lifecycle).confirmReadiness(work.warehouseId(), work.warehouseVersion());
    order.verify(intents).completeReadiness(reservation);
  }

  @Test
  void failedReadinessConfirmationRemovesTheCommittedBarrier() {
    ReadinessWork work = new ReadinessWork(UUID.randomUUID(), 7L, "DRAINING");
    ReadinessReservation reservation =
        new ReadinessReservation(UUID.randomUUID(), work.warehouseId(), work.warehouseVersion());
    RuntimeException failure = new ExternalServiceException("unavailable", null);
    when(intents.reserveReadinessIfClear(work)).thenReturn(reservation);
    org.mockito.Mockito.doThrow(failure)
        .when(lifecycle)
        .confirmReadiness(work.warehouseId(), work.warehouseVersion());

    org.assertj.core.api.Assertions.assertThatThrownBy(() -> fence.confirmReadinessIfNoLiveWork(work))
        .isSameAs(failure);

    verify(intents).abandonReadiness(reservation);
  }

  @Test
  void relocationReservesBothWarehousesBeforeRemoteAdmissionAndMarksThemAdmitted() {
    UUID source = UUID.randomUUID();
    UUID target = UUID.randomUUID();
    AdmissionPermit permit =
        new AdmissionPermit(
            UUID.randomUUID(),
            List.of(
                new AdmissionTarget(source, OperationDirection.OUTGOING),
                new AdmissionTarget(target, OperationDirection.INCOMING)));
    when(intents.reserveAdmission(any())).thenReturn(permit);

    AdmissionPermit actual = fence.acquireRelocation(source, target);

    assertThat(actual).isSameAs(permit);
    InOrder order = inOrder(intents, lifecycle);
    order.verify(intents).reserveAdmission(
        List.of(
            new AdmissionTarget(source, OperationDirection.OUTGOING),
            new AdmissionTarget(target, OperationDirection.INCOMING)));
    order.verify(lifecycle).requireAdmission(source, OperationDirection.OUTGOING);
    order.verify(lifecycle).requireAdmission(target, OperationDirection.INCOMING);
    order.verify(intents).markAdmitted(permit);
  }

  @Test
  void rejectedAdmissionAbandonsItsReservation() {
    UUID warehouseId = UUID.randomUUID();
    AdmissionPermit permit =
        new AdmissionPermit(
            UUID.randomUUID(), List.of(new AdmissionTarget(warehouseId, OperationDirection.INCOMING)));
    RuntimeException failure = new ConflictException("not admitted");
    when(intents.reserveAdmission(any())).thenReturn(permit);
    org.mockito.Mockito.doThrow(failure)
        .when(lifecycle)
        .requireAdmission(warehouseId, OperationDirection.INCOMING);

    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> fence.acquireAdmission(warehouseId, OperationDirection.INCOMING))
        .isSameAs(failure);

    verify(intents).abandonAdmission(permit);
    verify(intents, never()).markAdmitted(permit);
  }
}
