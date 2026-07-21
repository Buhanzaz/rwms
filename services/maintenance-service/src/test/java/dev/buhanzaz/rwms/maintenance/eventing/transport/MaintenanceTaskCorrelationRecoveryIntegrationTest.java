package dev.buhanzaz.rwms.maintenance.eventing.transport;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.CreateDirectRepairRequest;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.PlanStageInput;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.RoutingSnapshot;
import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.VersionCommand;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.maintenance.dependencies.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceTaskCorrelationRecoveryIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired MaintenanceApplicationService service;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired RepairStageRepository stages;
  @Autowired RentalItemFactProjectionRepository rentalItemFacts;
  @Autowired MaintenanceInboundEnvelopeValidator validator;
  @Autowired MaintenanceInboundStagingStore staging;
  @Autowired MaintenanceInboxProcessor inbox;
  @Autowired MaintenanceInboundEffects productionEffects;
  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean MaintenanceDependencyGateway dependencies;

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table maintenance_inbound_correlation,inbox_message,version_gap_quarantine,
          consumer_aggregate_checkpoint,sanitized_dead_letter,maintenance_inbound_replay_message,
          maintenance_idempotency_record,integration_reconciliation,rental_item_fact_projection,
          operation_lease_fact_projection,maintenance_repair,outbox_event,aggregate_snapshot,
          projection_checkpoint,domain_event,event_stream_head cascade
        """);
    reset(dependencies);
  }

  @Test
  void bothArrivalOrdersDuplicatesAndFreshProcessorConvergeThroughRealDomainEffects() {
    RepairFixture boardFirst = createRegisteredRepair(1);
    RepairFixture queueFirst = createRegisteredRepair(2);
    UUID boardFirstTaskId = UUID.randomUUID();
    var boardMapping = boardTask(boardFirstTaskId, boardFirst.externalTaskId(), 0);
    var boardFirstCompletion =
        queueCompletion(boardFirst.queueEntryIds().getFirst(), boardFirstTaskId, 0);

    stageAndProcess(inbox, boardMapping);
    stageAndProcess(inbox, boardFirstCompletion);
    assertThat(inbox.process(boardFirstCompletion))
        .isEqualTo(MaintenanceInboxProcessor.Outcome.DUPLICATE);
    assertCompletedRepair(boardFirst, List.of(boardFirstCompletion.eventId()));

    UUID queueFirstTaskId = UUID.randomUUID();
    var firstStageCompletion =
        queueCompletion(queueFirst.queueEntryIds().get(0), queueFirstTaskId, 0);
    var secondStageCompletion =
        queueCompletion(queueFirst.queueEntryIds().get(1), queueFirstTaskId, 1);
    stageAndProcess(inbox, secondStageCompletion);
    stageAndProcess(inbox, firstStageCompletion);

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from maintenance_inbound_correlation
                 where board_task_id=? and state='PENDING'
                """,
                Integer.class,
                queueFirstTaskId))
        .isEqualTo(2);
    assertThat(stages.findAllByRepairIdOrderByStageNo(queueFirst.repairId()))
        .extracting(value -> value.getState())
        .containsExactly(RepairStageState.QUEUED, RepairStageState.QUEUED);

    var recoveredBoardMapping = boardTask(queueFirstTaskId, queueFirst.externalTaskId(), 0);
    staging.stage(recoveredBoardMapping);
    MaintenanceInboxProcessor freshProcessor =
        new MaintenanceInboxProcessor(jdbc, productionEffects, staging);
    var recoveredOutcome =
        new TransactionTemplate(transactionManager)
            .execute(status -> freshProcessor.process(recoveredBoardMapping));
    assertThat(recoveredOutcome).isEqualTo(MaintenanceInboxProcessor.Outcome.PROCESSED);
    assertThat(inbox.process(firstStageCompletion))
        .isEqualTo(MaintenanceInboxProcessor.Outcome.DUPLICATE);

    assertCompletedRepair(
        queueFirst, List.of(firstStageCompletion.eventId(), secondStageCompletion.eventId()));
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from maintenance_inbound_correlation
                 where board_task_id=? and state='APPLIED'
                """,
                Integer.class,
                queueFirstTaskId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                 where repair_id=? and operation_type='PENDING_ACCEPTANCE'
                """,
                Integer.class,
                queueFirst.repairId()))
        .isOne();
  }

  @Test
  void partialRentalItemEventAdvancesProjectionAndRepairQueuesAgainstTheLatestVersion() {
    UUID rentalItemId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    stageAndProcess(
        inbox,
        rentalItemState(
            rentalItemId, warehouseId, "NEW", 0, "asset.rental-item.created.v1"));
    stageAndProcess(
        inbox,
        rentalItemState(
            rentalItemId, warehouseId, "FREE", 1, "asset.rental-item.status-changed.v1"));
    stageAndProcess(inbox, rentalItemComment(rentalItemId, 2));

    RentalItemFactProjection fact = rentalItemFacts.findById(rentalItemId).orElseThrow();
    assertThat(fact.getAggregateVersion()).isEqualTo(2);
    assertThat(fact.getWarehouseId()).isEqualTo(warehouseId);
    assertThat(fact.getAssetStatus()).isEqualTo("FREE");

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateDirectRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 19),
                null,
                List.of(
                    new PlanStageInput(
                        UUID.randomUUID(),
                        RepairStageKind.REPAIR_WORK,
                        0,
                        new RoutingSnapshot(UUID.randomUUID(), "REPAIR", "REPAIR"),
                        null)),
                List.of()));
    UUID repairId = created.response().id();
    assertThat(repairs.findById(repairId).orElseThrow().getRentalItemVersionSnapshot())
        .isEqualTo(2);

    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 2, warehouseId, "FREE"));
    when(
            dependencies.acquireLease(
                any(), eq(rentalItemId), eq(2L), eq("MAINTENANCE_REPAIR"), eq(repairId.toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0,
                rentalItemId,
                "MAINTENANCE_REPAIR",
                repairId,
                21,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(
            dependencies.fencedStatus(
                any(),
                eq(rentalItemId),
                eq(warehouseId),
                eq(2L),
                eq(leaseId),
                eq(21L),
                eq("MAINTENANCE_REPAIR"),
                eq(repairId.toString()),
                eq("QUEUE_TO_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 3, warehouseId, "REPAIR"));

    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), repairId, new VersionCommand(0L));
    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(repairs.findById(repairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
    verify(dependencies)
        .acquireLease(
            any(), eq(rentalItemId), eq(2L), eq("MAINTENANCE_REPAIR"), eq(repairId.toString()));
    assertThat(
            jdbc.queryForObject(
                """
                select state from integration_reconciliation
                where repair_id=? and operation_type='QUEUE_REPAIR'
                """,
                String.class,
                repairId))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void partialRentalItemEventWithoutExistingProjectionFailsClosed() {
    UUID rentalItemId = UUID.randomUUID();
    var event = rentalItemComment(rentalItemId, 0);
    staging.stage(event);

    assertThatThrownBy(() -> inbox.process(event))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Partial rental-item fact cannot initialize the maintenance projection");

    assertThat(rentalItemFacts.findById(rentalItemId)).isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inbox_message where event_id=?", Integer.class, event.eventId()))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from consumer_aggregate_checkpoint
                where aggregate_type='RENTAL_ITEM' and aggregate_id=?
                """,
                Integer.class,
                rentalItemId.toString()))
        .isZero();
  }

  private RepairFixture createRegisteredRepair(int stageCount) {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    List<PlanStageInput> plan = new ArrayList<>();
    for (int index = 0; index < stageCount; index++) {
      plan.add(
          new PlanStageInput(
              UUID.randomUUID(),
              RepairStageKind.REPAIR_WORK,
              index,
              new RoutingSnapshot(UUID.randomUUID(), "REPAIR-" + index, "REPAIR"),
              null));
    }
    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateDirectRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 17),
                null,
                plan,
                List.of()));
    UUID repairId = created.response().id();
    UUID externalTaskId = created.response().plan().stages().getFirst().taskSync().externalTaskId();
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 7, warehouseId, "FREE"));
    when(
            dependencies.acquireLease(
                any(),
                eq(rentalItemId),
                eq(7L),
                eq("MAINTENANCE_REPAIR"),
                eq(repairId.toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0,
                rentalItemId,
                "MAINTENANCE_REPAIR",
                repairId,
                11,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(
            dependencies.fencedStatus(
                any(),
                eq(rentalItemId),
                eq(warehouseId),
                eq(7L),
                eq(leaseId),
                eq(11L),
                eq("MAINTENANCE_REPAIR"),
                eq(repairId.toString()),
                eq("QUEUE_TO_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 8, warehouseId, "REPAIR"));
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), repairId, new VersionCommand(0L));
    assertThat(service.reconcileOneTask()).isTrue();

    List<UUID> entryIds = new ArrayList<>();
    List<MaintenanceDependencyGateway.TaskStageSnapshot> snapshots = new ArrayList<>();
    for (int index = 0; index < stageCount; index++) {
      UUID entryId = UUID.randomUUID();
      entryIds.add(entryId);
      snapshots.add(new MaintenanceDependencyGateway.TaskStageSnapshot(index, entryId, 0));
    }
    when(
            dependencies.registerTask(
                any(), eq(externalTaskId), eq(warehouseId), eq(rentalItemId), anyList()))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                externalTaskId, 0, "ACTIVE", snapshots));
    assertThat(service.reconcileOneTask()).isTrue();
    return new RepairFixture(repairId, externalTaskId, entryIds);
  }

  private void assertCompletedRepair(RepairFixture fixture, List<UUID> completionEventIds) {
    var repair = repairs.findById(fixture.repairId()).orElseThrow();
    assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.COMPLETED);
    assertThat(repair.getAcceptanceState()).isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(stages.findAllByRepairIdOrderByStageNo(fixture.repairId()))
        .extracting(value -> value.getState())
        .containsOnly(RepairStageState.DONE);
    assertThat(stages.findAllByRepairIdOrderByStageNo(fixture.repairId()))
        .extracting(value -> value.getCompletedEventId())
        .containsExactlyElementsOf(completionEventIds);
  }

  private void stageAndProcess(
      MaintenanceInboxProcessor processor,
      MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent event) {
    staging.stage(event);
    assertThat(processor.process(event)).isEqualTo(MaintenanceInboxProcessor.Outcome.PROCESSED);
  }

  private MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent boardTask(
      UUID boardTaskId, UUID externalTaskId, long version) {
    byte[] raw =
        json(
            """
            {
              "envelopeVersion":2,"eventId":"%s","eventType":"task-board.board-task.created.v1",
              "eventVersion":1,"occurredAt":"2026-07-17T00:00:00Z",
              "recordedAt":"2026-07-17T00:00:00Z","producer":"task-board-service",
              "aggregateType":"BOARD_TASK","aggregateId":"%s","aggregateVersion":%d,
              "correlation":{"correlationId":"%s","causationId":null},"actorRef":null,
              "payload":{"boardTaskId":"%s","warehouseId":"%s","externalTaskId":"%s",
              "status":"ACTIVE","plannedDurationMinutes":20,"deadlineAt":null,"doneAt":null,
              "deleted":false}
            }
            """
                .formatted(
                    UUID.randomUUID(),
                    boardTaskId,
                    version,
                    UUID.randomUUID(),
                    boardTaskId,
                    UUID.randomUUID(),
                    externalTaskId));
    return validator.validate(
        MaintenanceTransportTopics.BOARD_TASK,
        boardTaskId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent queueCompletion(
      UUID queueEntryId, UUID boardTaskId, int routeIndex) {
    byte[] raw =
        json(
            """
            {
              "envelopeVersion":2,"eventId":"%s",
              "eventType":"task-board.queue-entry.completed.v1","eventVersion":1,
              "occurredAt":"2026-07-17T00:00:00Z","recordedAt":"2026-07-17T00:00:00Z",
              "producer":"task-board-service","aggregateType":"QUEUE_ENTRY",
              "aggregateId":"%s","aggregateVersion":0,
              "correlation":{"correlationId":"%s","causationId":null},"actorRef":null,
              "payload":{"queueEntryId":"%s","taskId":"%s","queueId":"%s",
              "queueCode":"REPAIR-%d","routeIndex":%d,"queuePosition":0,"entryType":"REAL",
              "status":"DONE","plannedDurationMinutes":10,"activeStartedAt":null,"pausedAt":null,
              "doneAt":"2026-07-17T00:00:00Z","activeWorkSeconds":10,"pauseOrigin":null,
              "assignments":[],"timeEvents":[],"interruptions":[],"deleted":false}
            }
            """
                .formatted(
                    UUID.randomUUID(),
                    queueEntryId,
                    UUID.randomUUID(),
                    queueEntryId,
                    boardTaskId,
                    UUID.randomUUID(),
                    routeIndex,
                    routeIndex));
    return validator.validate(
        MaintenanceTransportTopics.QUEUE_ENTRY,
        queueEntryId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent rentalItemState(
      UUID rentalItemId,
      UUID warehouseId,
      String status,
      long version,
      String eventType) {
    byte[] raw =
        json(
            """
            {
              "envelopeVersion":2,"eventId":"%s","eventType":"%s","eventVersion":1,
              "occurredAt":"2026-07-19T00:00:00Z","recordedAt":"2026-07-19T00:00:00Z",
              "producer":"asset-service","aggregateType":"RENTAL_ITEM","aggregateId":"%s",
              "aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},
              "actorRef":null,"payload":{"rentalItemId":"%s","warehouseId":"%s",
              "status":"%s","numberSha256":"%s"}
            }
            """
                .formatted(
                    UUID.randomUUID(),
                    eventType,
                    rentalItemId,
                    version,
                    UUID.randomUUID(),
                    rentalItemId,
                    warehouseId,
                    status,
                    "0".repeat(64)));
    return validator.validate(
        MaintenanceTransportTopics.RENTAL_ITEM,
        rentalItemId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private MaintenanceInboundEnvelopeValidator.ValidatedInboundEvent rentalItemComment(
      UUID rentalItemId, long version) {
    byte[] raw =
        json(
            """
            {
              "envelopeVersion":2,"eventId":"%s",
              "eventType":"asset.rental-item.general-comment-changed.v1","eventVersion":1,
              "occurredAt":"2026-07-19T00:00:00Z","recordedAt":"2026-07-19T00:00:00Z",
              "producer":"asset-service","aggregateType":"RENTAL_ITEM","aggregateId":"%s",
              "aggregateVersion":%d,"correlation":{"correlationId":"%s","causationId":null},
              "actorRef":null,"payload":{"rentalItemId":"%s","commentRevision":1}
            }
            """
                .formatted(
                    UUID.randomUUID(), rentalItemId, version, UUID.randomUUID(), rentalItemId));
    return validator.validate(
        MaintenanceTransportTopics.RENTAL_ITEM,
        rentalItemId.toString().getBytes(StandardCharsets.UTF_8),
        raw);
  }

  private byte[] json(String value) {
    try {
      return mapper.writeValueAsBytes(mapper.readTree(value));
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException(exception);
    }
  }

  private record RepairFixture(UUID repairId, UUID externalTaskId, List<UUID> queueEntryIds) {}
}
