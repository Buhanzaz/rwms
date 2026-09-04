package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.Pageable;

/** Verifies that the scheduled driver recovery pass cannot materialize an unbounded backlog. */
class DriverTaskRelayTest {
  @Test
  void expiryUsesWarehouseCalendarAndRunsBeforeOrdinaryProcessing() {
    var tasks = mock(DriverLogisticsTaskRepository.class);
    var processor = mock(DriverTaskProcessor.class);
    var scheduler = mock(DriverQueueScheduler.class);
    var dependencies = mock(LogisticsDependencyGateway.class);
    UUID warehouse = UUID.randomUUID();
    UUID task = UUID.randomUUID();
    var capturedDate = new java.util.concurrent.atomic.AtomicReference<java.time.LocalDate>();
    when(dependencies.listWarehouseIdentities())
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.WarehouseIdentity(
                    warehouse, 0, true, "Pacific/Kiritimati")));
    when(dependencies.warehouseTimeZoneAt(org.mockito.ArgumentMatchers.eq(warehouse), any()))
        .thenAnswer(
            invocation -> {
              java.time.OffsetDateTime at = invocation.getArgument(1);
              capturedDate.set(
                  at.atZoneSameInstant(java.time.ZoneId.of("Pacific/Kiritimati")).toLocalDate());
              return new LogisticsDependencyGateway.WarehouseTimeZone(
                  warehouse, "Pacific/Kiritimati", at);
            });
    when(tasks.findOverdueTripIds(org.mockito.ArgumentMatchers.eq(warehouse), any(), any()))
        .thenReturn(List.of(task));
    when(tasks.findDueIds(any(), any(), any())).thenReturn(List.of(task));
    new DriverTaskRelay(tasks, processor, scheduler, dependencies).relay();
    var order = org.mockito.Mockito.inOrder(processor, scheduler);
    order.verify(processor).expireTrip(task, capturedDate.get());
    order.verify(processor).processUntilIdle(task);
    order.verify(scheduler).reconcileAndPromote(warehouse);
  }

  @Test
  void relayRequestsOnlyOneBoundedPageOfDueTasks() {
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
    DriverQueueScheduler scheduler = mock(DriverQueueScheduler.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(tasks.findDueIds(any(), any(), any())).thenReturn(List.of());
    when(dependencies.listWarehouseIdentities()).thenReturn(List.of());
    DriverTaskRelay relay = new DriverTaskRelay(tasks, processor, scheduler, dependencies);

    relay.relay();

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(tasks).findDueIds(any(), any(), page.capture());
    assertThat(page.getValue().getPageNumber()).isZero();
    assertThat(page.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  void reconciliationRequestsOneBoundedPageAndRechecksEveryReturnedTask() {
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
    DriverQueueScheduler scheduler = mock(DriverQueueScheduler.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    UUID taskId = UUID.randomUUID();
    when(tasks.findDueIds(any(), any(), any())).thenReturn(List.of(taskId));
    DriverTaskRelay relay = new DriverTaskRelay(tasks, processor, scheduler, dependencies);

    relay.reconcile();

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(tasks).findDueIds(any(), any(), page.capture());
    verify(processor).reconcileFromTaskBoard(taskId);
    assertThat(page.getValue().getPageNumber()).isZero();
    assertThat(page.getValue().getPageSize()).isEqualTo(100);
  }

  @Test
  void reconciliationRotatesPastAFullPageAndResetsAfterTheBacklogEnd() {
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    DriverTaskProcessor processor = mock(DriverTaskProcessor.class);
    DriverQueueScheduler scheduler = mock(DriverQueueScheduler.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    List<UUID> fullPage =
        java.util.stream.IntStream.range(0, 100).mapToObj(ignored -> UUID.randomUUID()).toList();
    when(tasks.findDueIds(any(), any(), any())).thenReturn(fullPage, List.of(), List.of());
    DriverTaskRelay relay = new DriverTaskRelay(tasks, processor, scheduler, dependencies);

    relay.reconcile();
    relay.reconcile();
    relay.reconcile();

    ArgumentCaptor<Pageable> page = ArgumentCaptor.forClass(Pageable.class);
    verify(tasks, times(3)).findDueIds(any(), any(), page.capture());
    assertThat(page.getAllValues()).extracting(Pageable::getPageNumber).containsExactly(0, 1, 0);
    assertThat(page.getAllValues()).allSatisfy(value -> assertThat(value.getPageSize()).isEqualTo(100));
  }
}
