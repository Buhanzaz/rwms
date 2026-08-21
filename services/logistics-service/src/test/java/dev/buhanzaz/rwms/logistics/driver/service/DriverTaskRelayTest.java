package dev.buhanzaz.rwms.logistics.driver.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.util.List;
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
}
