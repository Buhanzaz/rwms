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
