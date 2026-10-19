package dev.buhanzaz.rwms.taskboard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.CreateBoardTaskRequest;
import dev.buhanzaz.rwms.taskboard.domain.BoardTask;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventSourcing;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardProjectionWriter;
import dev.buhanzaz.rwms.taskboard.eventing.WorkerFeedRevisionStore;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.repository.TaskSyncSourceRepository;
import dev.buhanzaz.rwms.taskboard.repository.WorkQueueClassBindingRepository;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/** Regression coverage for warehouse-local fallback dates at a UTC calendar boundary. */
class TaskBoardExternalRegistrationBusinessDateTest {
  private static final UUID WAREHOUSE_ID =
      UUID.fromString("11111111-1111-4111-8111-111111111111");
  private static final Instant NOW = Instant.parse("2026-08-31T20:30:00Z");

  private final WarehouseTimeZoneGateway timeZones = mock(WarehouseTimeZoneGateway.class);
  private TaskBoardExternalRegistrationService service;

  @BeforeEach
  void setUp() {
    when(timeZones.timeZoneAt(WAREHOUSE_ID, NOW))
        .thenReturn(
            new WarehouseTimeZoneGateway.TimeZoneDecision(
                ZoneId.of("Asia/Novosibirsk"), Instant.EPOCH));
    @SuppressWarnings("unchecked")
    ObjectProvider<Clock> clocks = mock(ObjectProvider.class);
    when(clocks.getIfAvailable(any(Supplier.class)))
        .thenReturn(Clock.fixed(NOW, ZoneOffset.UTC));
    service =
        new TaskBoardExternalRegistrationService(
            mock(BoardTaskRepository.class),
            mock(QueueEntryRepository.class),
            mock(RegistryService.class),
            mock(TaskSyncSourceRepository.class),
            mock(TaskBoardRoutePayloadCodec.class),
            mock(TaskBoardQueuePositionCoordinator.class),
            mock(TaskBoardProjectionWriter.class),
            mock(TaskBoardEventSourcing.class),
            mock(GroupKpiEvidenceService.class),
            mock(WarehouseLifecycleFence.class),
            mock(PlatformTransactionManager.class),
            mock(WorkQueueClassBindingRepository.class),
            mock(WorkforceService.class),
            mock(WorkerInvalidationHub.class),
            mock(WorkerFeedRevisionStore.class),
            mock(DriverTaskAudienceService.class),
            mock(BoardTaskRegistrationAssembler.class),
            mock(TaskBoardEntryOwnerProofService.class),
            mock(JdbcTemplate.class),
            timeZones,
            clocks);
  }

  @Test
  void usesWarehouseLocalDateForMissingScheduleAndCurrentVisibility() {
    CreateBoardTaskRequest request =
        new CreateBoardTaskRequest(null, "Задание", null, null, 30, null, List.of(), null, 3);

    assertThat(service.scheduleDate(WAREHOUSE_ID, request)).isEqualTo("2026-09-01");

    BoardTask task = new BoardTask();
    task.setWarehouseId(WAREHOUSE_ID);
    task.setScheduledDate(LocalDate.parse("2026-09-01"));
    task.setLane(TaskLane.SCHEDULED);
    assertThat(service.isVisibleToday(task)).isTrue();
  }

  @Test
  void convertsDeadlineInstantToTheWarehouseCalendarAndKeepsExplicitDate() {
    CreateBoardTaskRequest deadlineFallback =
        new CreateBoardTaskRequest(
            null,
            "Задание",
            null,
            null,
            30,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            List.of(),
            null,
            3);
    CreateBoardTaskRequest explicit =
        new CreateBoardTaskRequest(
            null,
            "Задание",
            null,
            null,
            30,
            OffsetDateTime.ofInstant(NOW, ZoneOffset.UTC),
            List.of(),
            LocalDate.parse("2026-09-03"),
            3);

    assertThat(service.scheduleDate(WAREHOUSE_ID, deadlineFallback)).isEqualTo("2026-09-01");
    assertThat(service.scheduleDate(WAREHOUSE_ID, explicit)).isEqualTo("2026-09-03");
  }
}
