package dev.buhanzaz.rwms.maintenance.service;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceInboundEffects;
import dev.buhanzaz.rwms.maintenance.eventing.transport.MaintenanceTransportTopics;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

class MaintenanceInboundDomainEffectsTest {
  private final MaintenanceApplicationService service = mock(MaintenanceApplicationService.class);
  private final MaintenanceRepairRepository repairs = mock(MaintenanceRepairRepository.class);
  private final RepairTaskEvidenceProjectionService taskEvidence =
      mock(RepairTaskEvidenceProjectionService.class);
  private final JdbcTemplate jdbc = mock(JdbcTemplate.class);
  private final ObjectMapper mapper = JsonMapper.builder().findAndAddModules().build();
  private final MaintenanceInboundDomainEffects effects =
      new MaintenanceInboundDomainEffects(service, repairs, taskEvidence, jdbc, mapper);

  @Test
  void appliesInventoryVisibilityAsAReversibleFenceNotAnOrdinaryRentalFact() {
    UUID id = UUID.randomUUID();
    UUID warehouse = UUID.randomUUID();
    var payload = mapper.createObjectNode();
    payload.put("rentalItemId", id.toString());
    payload.put("warehouseId", warehouse.toString());
    payload.put("status", "FREE");
    payload.put("numberSha256", "0".repeat(64));
    payload.put("inventoryId", UUID.randomUUID().toString());
    payload.put("isolated", true);
    effects.apply(new MaintenanceInboundEffects.InboundEvent(
        MaintenanceTransportTopics.RENTAL_ITEM, UUID.randomUUID(),
        "asset.rental-item.inventory-visibility-changed.v1", "RENTAL_ITEM", id.toString(), 1, payload), null);
    verify(service).applyInboundRentalItemVisibilityFact(id, warehouse, "FREE", 1, true);
    org.mockito.Mockito.verifyNoMoreInteractions(service);
    verifyNoInteractions(repairs, taskEvidence, jdbc);
  }

  @Test
  void ignoresCompletedTaskThatIsNotOwnedByMaintenance() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    MaintenanceInboundEffects.InboundEvent event = queueCompletion();
    MaintenanceInboundEffects.TaskCorrelation correlation = correlation(externalTaskId);
    when(repairs.findByExternalTaskId(externalTaskId)).thenReturn(Optional.empty());

    effects.apply(event, correlation);

    verify(repairs).findByExternalTaskId(externalTaskId);
    verifyNoInteractions(service, taskEvidence, jdbc);
  }

  @Test
  void appliesCompletedTaskWhenTheTaskBelongsToAMaintenanceRepair() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    MaintenanceInboundEffects.InboundEvent event = queueCompletion();
    MaintenanceInboundEffects.TaskCorrelation correlation = correlation(externalTaskId);
    when(repairs.findByExternalTaskId(externalTaskId))
        .thenReturn(Optional.of(mock(MaintenanceRepair.class)));

    effects.apply(event, correlation);

    verify(service)
        .applyInboundTaskOutcome(
            event.eventId(),
            event.eventType(),
            externalTaskId,
            correlation.queueEntryId(),
            event.aggregateVersion(),
            OffsetDateTime.parse("2026-07-26T12:00:00Z"));
  }

  @Test
  void appliesChangedBoardTaskScheduleWithoutWaitingForAQueueEntry() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    MaintenanceInboundEffects.InboundEvent event = boardTaskChanged();
    MaintenanceInboundEffects.TaskCorrelation correlation = new MaintenanceInboundEffects.TaskCorrelation(
        externalTaskId, UUID.randomUUID(), null, event.eventId(), null);
    when(repairs.findByExternalTaskId(externalTaskId))
        .thenReturn(Optional.of(mock(MaintenanceRepair.class)));

    effects.apply(event, correlation);

    verify(service)
        .applyInboundTaskSchedule(externalTaskId, LocalDate.of(2026, 7, 28), 7L);
  }

  private MaintenanceInboundEffects.InboundEvent queueCompletion() throws Exception {
    return new MaintenanceInboundEffects.InboundEvent(
        MaintenanceTransportTopics.QUEUE_ENTRY,
        UUID.randomUUID(),
        "task-board.queue-entry.completed.v1",
        "QUEUE_ENTRY",
        UUID.randomUUID().toString(),
        4,
        mapper.readTree("{\"doneAt\":\"2026-07-26T12:00:00Z\"}"));
  }

  private MaintenanceInboundEffects.InboundEvent boardTaskChanged() throws Exception {
    return new MaintenanceInboundEffects.InboundEvent(
        MaintenanceTransportTopics.BOARD_TASK,
        UUID.randomUUID(),
        "task-board.board-task.changed.v1",
        "BOARD_TASK",
        UUID.randomUUID().toString(),
        7,
        mapper.readTree("{\"scheduledDate\":\"2026-07-28\"}"));
  }

  private static MaintenanceInboundEffects.TaskCorrelation correlation(UUID externalTaskId) {
    return new MaintenanceInboundEffects.TaskCorrelation(
        externalTaskId, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
  }
}
