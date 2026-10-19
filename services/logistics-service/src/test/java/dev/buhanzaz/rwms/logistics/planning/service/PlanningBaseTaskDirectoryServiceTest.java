package dev.buhanzaz.rwms.logistics.planning.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskAudienceMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskState;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.WarehouseTimeZone;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.domain.PageRequest;

/** Verifies warehouse/status isolation of the read-only existing base-task projection. */
class PlanningBaseTaskDirectoryServiceTest {
  @Test
  void queriesOnlyScheduledSharedBaseKindsWithoutClaimingOrCreatingAnything() {
    UUID warehouseId = UUID.randomUUID();
    OffsetDateTime availableAt = OffsetDateTime.parse("2026-09-01T22:30:00Z");
    DriverLogisticsTaskRepository tasks = mock(DriverLogisticsTaskRepository.class);
    LogisticsDependencyGateway dependencies = mock(LogisticsDependencyGateway.class);
    when(dependencies.warehouseTimeZoneAt(warehouseId, availableAt))
        .thenReturn(new WarehouseTimeZone(warehouseId, "Europe/Moscow", availableAt.minusDays(1)));
    DriverLogisticsTask task = mock(DriverLogisticsTask.class);
    UUID taskId = UUID.randomUUID();
    UUID externalTaskId = UUID.randomUUID();
    when(task.getId()).thenReturn(taskId);
    when(task.getExternalTaskId()).thenReturn(externalTaskId);
    when(task.getKind()).thenReturn(DriverTaskKind.DELIVER_TO_REPAIR);
    when(task.getUnitNumber()).thenReturn("БЫТ-501");
    when(task.getComment()).thenReturn("  Доставить\nв ремонт\u0000  без повреждений  ");
    when(task.getScheduledDate()).thenReturn(LocalDate.of(2026, 9, 2));
    when(task.getPriority()).thenReturn(2);
    when(task.getState()).thenReturn(DriverTaskState.SCHEDULED);
    when(tasks.findPlanningBaseTaskCandidates(
            eq(warehouseId),
            eq(LocalDate.of(2026, 9, 2)),
            eq(DriverTaskAudienceMode.WAREHOUSE_DRIVERS),
            org.mockito.ArgumentMatchers.anyCollection(),
            eq(DriverTaskState.SCHEDULED),
            eq(PageRequest.of(0, 25))))
        .thenReturn(List.of(task));

    var result =
        new PlanningBaseTaskDirectoryService(tasks, dependencies)
            .candidates(warehouseId, availableAt, 25);

    assertThat(result)
        .singleElement()
        .satisfies(
            candidate -> {
              assertThat(candidate.taskId()).isEqualTo(taskId);
              assertThat(candidate.externalTaskId()).isEqualTo(externalTaskId);
              assertThat(candidate.state()).isEqualTo(DriverTaskState.SCHEDULED);
              assertThat(candidate.summary()).isEqualTo("Доставить в ремонт без повреждений");
            });
    @SuppressWarnings("unchecked")
    ArgumentCaptor<Collection<DriverTaskKind>> kinds = ArgumentCaptor.forClass(Collection.class);
    verify(tasks)
        .findPlanningBaseTaskCandidates(
            eq(warehouseId),
            eq(LocalDate.of(2026, 9, 2)),
            eq(DriverTaskAudienceMode.WAREHOUSE_DRIVERS),
            kinds.capture(),
            eq(DriverTaskState.SCHEDULED),
            eq(PageRequest.of(0, 25)));
    assertThat(kinds.getValue())
        .containsExactlyInAnyOrderElementsOf(
            Set.of(
                DriverTaskKind.GENERAL_MOVEMENT,
                DriverTaskKind.DELIVER_TO_REPAIR,
                DriverTaskKind.REMOVE_FROM_REPAIR,
                DriverTaskKind.CAPITAL_TO_PRODUCTION,
                DriverTaskKind.TRANSFER));
  }
}
