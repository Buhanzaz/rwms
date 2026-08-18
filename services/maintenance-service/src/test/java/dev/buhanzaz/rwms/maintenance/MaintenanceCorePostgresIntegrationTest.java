package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.maintenance.api.ReplaceRepairCapacitySettingsRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.ApprovePropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.RecoverPropertyDispositionRequest;
import dev.buhanzaz.rwms.maintenance.disposition.api.PropertyDispositionApiModels.WriteOffRepairRequest;
import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionApplicationService;
import dev.buhanzaz.rwms.maintenance.disposition.application.PropertyDispositionProcessor;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionKind;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionSource;
import dev.buhanzaz.rwms.maintenance.disposition.domain.PropertyDispositionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairPlaceAllocationState;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairComplexity;
import dev.buhanzaz.rwms.maintenance.domain.FurnitureAccountingMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairReclassificationState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageState;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import dev.buhanzaz.rwms.maintenance.service.FurnitureEquipmentLinkProcessor;
import dev.buhanzaz.rwms.maintenance.service.FurnitureEquipmentLinkReviewService;
import dev.buhanzaz.rwms.maintenance.service.FurnitureEquipmentLinkStore;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceIdempotencyStore;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceNotFoundException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceReconciliationReviewService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceReconciliationStore;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException;
import dev.buhanzaz.rwms.maintenance.service.RepairCapacitySettingsService;
import dev.buhanzaz.rwms.maintenance.service.RepairPlaceService;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleReconciliationScheduler;
import dev.buhanzaz.rwms.maintenance.service.WarehouseLifecycleOperations;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkRecoveryService;
import dev.buhanzaz.rwms.maintenance.service.WarehouseOperationMarkStore;
import dev.buhanzaz.rwms.maintenance.service.WarehouseReadinessFenceStore;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.maintenance.task-reconciliation.initial-delay=1h",
    "rwms.maintenance.task-reconciliation.delay=1h",
    "rwms.maintenance.property-disposition.initial-delay=1h",
    "rwms.maintenance.property-disposition.delay=1h",
    "rwms.maintenance.warehouse-lifecycle.operation-mark-initial-delay=1h",
    "rwms.maintenance.warehouse-lifecycle.operation-mark-delay=1h",
    "rwms.maintenance.warehouse-lifecycle.readiness-initial-delay=1h",
    "rwms.maintenance.warehouse-lifecycle.readiness-delay=1h",
    "rwms.maintenance.furniture-equipment-link.initial-delay=1h",
    "rwms.maintenance.furniture-equipment-link.delay=1h",
    "AUTH_ISSUER=http://auth.test",
    "PANEL_ORIGIN=http://panel.test"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceCorePostgresIntegrationTest {
  private static final UUID REVIEWED_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceApplicationService service;
  @Autowired PropertyDispositionApplicationService dispositions;
  @Autowired PropertyDispositionProcessor dispositionProcessor;
  @Autowired MaintenanceIdempotencyStore idempotency;
  @Autowired RentalItemFactProjectionRepository rentalItemFacts;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired MediaFactProjectionRepository mediaFacts;
  @Autowired MaintenanceMediaReferenceRepository mediaReferences;
  @Autowired MaintenanceReconciliationStore reconciliations;
  @Autowired RepairCapacitySettingsService repairCapacitySettings;
  @Autowired RepairPlaceService repairPlaces;
  @Autowired WarehouseOperationMarkStore warehouseOperationMarks;
  @Autowired WarehouseLifecycleReconciliationScheduler warehouseLifecycleScheduler;
  @Autowired WarehouseLifecycleOperations warehouseLifecycle;
  @Autowired WarehouseReadinessFenceStore warehouseReadinessFences;
  @Autowired FurnitureEquipmentLinkStore furnitureEquipmentLinks;
  @Autowired FurnitureEquipmentLinkProcessor furnitureEquipmentLinkProcessor;
  @Autowired FurnitureEquipmentLinkReviewService furnitureEquipmentLinkReviews;
  @Autowired
  dev.buhanzaz.rwms.maintenance.service.RepairComplexitySettingsService
      repairComplexitySettings;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean MaintenanceDependencyGateway dependencies;
  @MockitoSpyBean MaintenanceEventFactFactory eventFacts;

  @BeforeEach
  void resetDatabaseAndDependencies() {
    jdbc.execute("""
        truncate table
          catalog_version,
          maintenance_estimate,
          maintenance_repair,
          media_fact_projection,
          rental_item_fact_projection,
          operation_lease_fact_projection,
          repair_capacity_settings,
          repair_complexity_settings,
          repair_place_allocation,
          maintenance_idempotency_record,
          integration_reconciliation,
          property_disposition_processing_attempt,
          property_disposition_processing_claim,
          property_disposition_contents_snapshot_line,
          property_disposition_decision,
          furniture_equipment_link_review_audit,
          furniture_equipment_link_intent,
          warehouse_operation_mark_recovery_audit,
          warehouse_operation_mark_outbox,
          warehouse_readiness_fence,
          event_stream_head
        cascade
        """);
    reset(dependencies);
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(invocation -> {
          List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
              invocation.getArgument(1);
          return new MaintenanceDependencyGateway.RoutingPreflight(
              invocation.getArgument(0),
              true,
              List.of(),
              List.of(),
              List.of(),
              requirements.stream()
                  .map(value -> new MaintenanceDependencyGateway.RoutingQueueSnapshot(
                      value.queueDefinitionId(),
                      value.queueDefinitionId(),
                      value.queueDefinitionId().toString(),
                      value.type()))
                  .toList());
        });
    when(dependencies.preflightCatalogRouting(anyList()))
        .thenAnswer(invocation -> {
          List<MaintenanceDependencyGateway.CatalogRoutingQueueRequirement> requirements =
              invocation.getArgument(0);
          return new MaintenanceDependencyGateway.CatalogRoutingPreflight(
              true,
              List.of(),
              List.of(),
              requirements.stream()
                  .map(value -> new MaintenanceDependencyGateway.QueueDefinitionSnapshot(
                      value.queueDefinitionId(),
                      value.queueDefinitionId().toString(),
                      value.type()))
                  .toList());
        });
    clearInvocations(eventFacts);
  }

  @Test
  void warehouseOperationClaimCommitsBeforeRemoteCallAndExpiredLeaseIsFenced() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).withNano(0);
    new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> warehouseOperationMarks.enqueue(warehouseId, operationId, occurredAt));
    when(dependencies.productionReady()).thenReturn(true);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              assertThat(jdbc.queryForObject(
                      """
                      select state from warehouse_operation_mark_outbox
                       where warehouse_id=? and operation_id=?
                      """,
                      String.class,
                      warehouseId,
                      operationId))
                  .isEqualTo("IN_FLIGHT");
              return null;
            })
        .when(dependencies)
        .markWarehouseOperation(warehouseId, operationId, occurredAt);

    warehouseLifecycleScheduler.reconcileOperationMarks();

    assertThat(jdbc.queryForObject(
            """
            select state from warehouse_operation_mark_outbox
             where warehouse_id=? and operation_id=?
            """,
            String.class,
            warehouseId,
            operationId))
        .isEqualTo("CONFIRMED");

    UUID recoveredOperation = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> warehouseOperationMarks.enqueue(warehouseId, recoveredOperation, occurredAt));
    WarehouseOperationMarkStore.WorkItem first =
        warehouseOperationMarks.claimNextDue(Duration.ofMinutes(2)).orElseThrow();
    jdbc.update(
        """
        update warehouse_operation_mark_outbox set claim_until=clock_timestamp()-interval '1 second'
         where warehouse_id=? and operation_id=?
        """,
        warehouseId,
        recoveredOperation);
    WarehouseOperationMarkStore.WorkItem reclaimed =
        warehouseOperationMarks.claimNextDue(Duration.ofMinutes(2)).orElseThrow();
    assertThat(reclaimed.claimToken()).isNotEqualTo(first.claimToken());
    assertThatThrownBy(() -> warehouseOperationMarks.confirmed(first))
        .isInstanceOf(MaintenanceConflictException.class);
    warehouseOperationMarks.confirmed(reclaimed);
  }

  @Test
  void quarantinedWarehouseOperationSurvivesRestartAndReviewedRecoveryIsReplaySafe() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(10).withNano(0);
    new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> warehouseOperationMarks.enqueue(warehouseId, operationId, occurredAt));
    for (int attempt = 0; attempt < WarehouseOperationMarkStore.MAX_ATTEMPTS; attempt++) {
      jdbc.update(
          """
          update warehouse_operation_mark_outbox set next_attempt_at=clock_timestamp()-interval '1 second'
           where warehouse_id=? and operation_id=?
          """,
          warehouseId,
          operationId);
      WarehouseOperationMarkStore.WorkItem work =
          warehouseOperationMarks.claimNextDue(Duration.ofMinutes(2)).orElseThrow();
      warehouseOperationMarks.failed(work, new IllegalStateException("warehouse unavailable"));
    }
    assertThat(jdbc.queryForObject(
            """
            select state from warehouse_operation_mark_outbox
             where warehouse_id=? and operation_id=?
            """,
            String.class,
            warehouseId,
            operationId))
        .isEqualTo("QUARANTINED");

    UUID reviewer = UUID.randomUUID();
    WarehouseOperationMarkRecoveryService restartedBoundary =
        new WarehouseOperationMarkRecoveryService(warehouseOperationMarks);
    var recovered = restartedBoundary.recover(
        warehouseId, operationId, 0, reviewer, "warehouse-service incident resolved");
    assertThat(recovered.state()).isEqualTo("PENDING");
    assertThat(recovered.recoveryVersion()).isOne();
    assertThat(recovered.replayed()).isFalse();
    var replay = restartedBoundary.recover(
        warehouseId, operationId, 0, reviewer, "warehouse-service incident resolved");
    assertThat(replay.replayed()).isTrue();
    assertThatThrownBy(
            () ->
                restartedBoundary.recover(
                    warehouseId, operationId, 0, reviewer, "different reviewed reason"))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("another reviewed command");
    assertThat(jdbc.queryForObject(
            """
            select count(*) from warehouse_operation_mark_recovery_audit
             where warehouse_id=? and operation_id=?
            """,
            Integer.class,
            warehouseId,
            operationId))
        .isOne();
    assertThatThrownBy(
            () ->
                jdbc.update(
                    """
                    update warehouse_operation_mark_recovery_audit set reason='rewritten'
                     where warehouse_id=? and operation_id=?
                    """,
                    warehouseId,
                    operationId))
        .hasMessageContaining("immutable");
  }

  @Test
  void allWarehousesUseOneCatalogAndCanonicalGlobalQueueRouting() {
    UUID spbWarehouseId = UUID.randomUUID();
    UUID anotherWarehouseId = UUID.randomUUID();
    var spbCatalog =
        service.createCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateCatalogRequest(spbWarehouseId));

    var repeatedCreate =
        service.createCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateCatalogRequest(anotherWarehouseId));

    assertThat(repeatedCreate.replayed()).isTrue();
    assertThat(repeatedCreate.response().id()).isEqualTo(spbCatalog.response().id());
    assertThat(repeatedCreate.response().warehouseId()).isEqualTo(spbWarehouseId);
    assertThat(service.catalogVersions(spbWarehouseId))
        .extracting(CatalogVersionResponse::id)
        .containsExactly(spbCatalog.response().id());
    assertThat(service.catalogVersions(anotherWarehouseId))
        .extracting(CatalogVersionResponse::id)
        .containsExactly(spbCatalog.response().id());

    UUID localQueueId = UUID.randomUUID();
    RoutingSnapshot localRouting =
        new RoutingSnapshot(localQueueId, "EXTERNAL_WORKS", "REPAIR");
    CatalogNodeInput routedWork =
        new CatalogNodeInput(
            UUID.randomUUID(),
            CatalogNodeType.WORK,
            "External finishing",
            true,
            null,
            false,
            null,
            "piece",
            "100.00",
            15,
            true,
            false,
            true,
            null,
            null,
            new CatalogRoutingInput(localRouting.queueId(), localRouting.queueType()),
            null,
            null,
            false,
            null);

    CatalogVersionResponse changed =
        service.changeCatalog(
            spbCatalog.response().id(),
            spbWarehouseId,
            new ChangeCatalogRequest(
                spbCatalog.response().version(), List.of(routedWork), List.of()));

    assertThat(changed.id()).isEqualTo(spbCatalog.response().id());
    assertThat(changed.warehouseId()).isEqualTo(spbWarehouseId);
    verify(dependencies)
        .preflightCatalogRouting(
            argThat(
                requirements ->
                    requirements.size() == 1
                        && requirements.getFirst().queueDefinitionId().equals(localQueueId)
                        && requirements.getFirst().type().equals("REPAIR")));
  }

  @Test
  void directRepairUsesTheRouteInheritedThroughTheWholeCatalogGraph() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    UUID routedCategoryId = UUID.randomUUID();
    UUID intermediateNodeId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    RoutingSnapshot routing = new RoutingSnapshot(queueId, "Внешние работы", "REPAIR");

    jdbc.update(
        """
        update catalog_node
           set routing_queue_id=null,routing_queue_name=null,routing_queue_type=null
         where catalog_version_id=? and node_id=?
        """,
        work.catalogVersionId(), work.nodeId());
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,unit,price_minor,duration_minutes,include_in_estimate,
          common_item,show_in_main_menu,routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,'CATEGORY','Маршрут работ',true,null,false,null,null,0,false,false,false,
                ?,?,?)
        """,
        UUID.randomUUID(),
        routedCategoryId,
        work.catalogVersionId(),
        queueId,
        routing.queueName(),
        routing.queueType());
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,unit,price_minor,duration_minutes,include_in_estimate,
          common_item,show_in_main_menu)
        values (?,?,?,'SUBCATEGORY','Промежуточный блок',true,null,false,null,null,0,false,false,false)
        """,
        UUID.randomUUID(), intermediateNodeId, work.catalogVersionId());
    jdbc.update(
        """
        insert into catalog_link(
          row_id,link_id,catalog_version_id,source_node_id,target_node_id,link_type,sort_order)
        values
          (?,?,?,?,?,'FOLLOW_UP',10),
          (?,?,?,?,?,'DEPENDENCY',20)
        """,
        UUID.randomUUID(), UUID.randomUUID(), work.catalogVersionId(), routedCategoryId,
        intermediateNodeId,
        UUID.randomUUID(), UUID.randomUUID(), work.catalogVersionId(), intermediateNodeId,
        work.nodeId());

    UUID lineId = UUID.randomUUID();
    CatalogNodeSnapshot submitted = new CatalogNodeSnapshot(
        work.catalogVersionId(),
        work.nodeId(),
        CatalogNodeType.WORK,
        work.name(),
        "шт",
        "100.00",
        15,
        routing,
        null,
        false,
        null);
    CreateDirectRepairRequest request = new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 8, 1),
        null,
        List.of(new EstimateLineInput(
            lineId,
            submitted,
            EstimateLineType.WORK,
            work.name(),
            "шт",
            "1",
            "100.00",
            15,
            null,
            List.of())),
        List.of(new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            routing,
            List.of(lineId),
            lineId,
            "",
            null)),
        List.of());

    RepairResponse created = service.createDirectRepair(
        UUID.randomUUID(), UUID.randomUUID(), request).response();

    assertThat(created.plan().stages()).singleElement().satisfies(stage -> {
      assertThat(stage.routing().queueId()).isEqualTo(queueId);
      assertThat(stage.routing().queueName()).isEqualTo("Внешние работы");
      assertThat(stage.routing().queueType()).isEqualTo("REPAIR");
    });
  }

  @Test
  void inboundMovementKeepsRepairWorkOffTheBoardUntilDeliveryOccupiesARepairPlace() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.of(2026, 8, 4);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(
            rentalItemId, warehouseId, "FREE", 7));
    CreateDirectRepairRequest base =
        directRepairRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 8, 1),
            null);
    RepairResponse created =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateDirectRepairRequest(
                    warehouseId,
                    rentalItemId,
                    base.dispatchDate(),
                    base.sourceParty(),
                    base.lines(),
                    base.plan(),
                    base.mediaReferences(),
                    base.coverMediaId()))
            .response();
    RepairFixture fixture =
        new RepairFixture(
            created.id(),
            created
                .plan()
                .stages()
                .getFirst()
                .taskSync()
                .externalTaskId(),
            warehouseId,
            rentalItemId);
    repairCapacitySettings.replace(
        warehouseId,
        new ReplaceRepairCapacitySettingsRequest(0L, 6, 11));

    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.id(),
        new QueueRepairRequest(
            created.version(),
            2,
            true,
            RepairLogisticsPlanningMode.FIXED_DATE,
            scheduledDate));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=?
                  and dependency_type='TASK_BOARD'
                  and operation_type='REGISTER_TASK'
                """,
                Integer.class,
                created.id()))
        .as(
            jdbc.queryForList(
                    """
                    select dependency_type,operation_type,state,last_error_code,
                           response_snapshot::text
                    from integration_reconciliation where repair_id=?
                    order by created_at
                    """,
                    created.id())
                .toString())
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=?
                  and dependency_type='LOGISTICS'
                  and operation_type='CREATE_DRIVER_TASK'
                """,
                Integer.class,
                created.id()))
        .isOne();
    assertThat(
            jdbc.queryForList(
                """
                select stage_kind,state,task_generation_state
                from repair_stage where repair_id=?
                order by stage_no
                """,
                created.id()))
        .extracting(
            row -> row.get("stage_kind"),
            row -> row.get("state"),
            row -> row.get("task_generation_state"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(
                "REPAIR_WORK", "QUEUED", "PENDING_GENERATION"));

    UUID driverTaskId = UUID.randomUUID();
    when(
            dependencies.createDriverTask(
                any(),
                any(
                    MaintenanceDependencyGateway.DriverTaskCommand
                        .class)))
        .thenAnswer(
            invocation -> {
              MaintenanceDependencyGateway.DriverTaskCommand command =
                  invocation.getArgument(1);
              return new MaintenanceDependencyGateway.DriverTaskSnapshot(
                  driverTaskId,
                  0,
                  command.warehouseId(),
                  command.cabinId(),
                  command.repairId(),
                  command.sourceType(),
                  command.sourceId(),
                  command.kind(),
                  command.planningMode(),
                  command.scheduledDate(),
                  command.priority(),
                  "SCHEDULED");
            });
    deferOtherReconciliations(created.id(), "CREATE_DRIVER_TASK");
    assertThat(service.reconcileOneTask()).isTrue();
    ArgumentCaptor<MaintenanceDependencyGateway.DriverTaskCommand>
        driverCommand =
            ArgumentCaptor.forClass(
                MaintenanceDependencyGateway.DriverTaskCommand.class);
    verify(dependencies)
        .createDriverTask(any(), driverCommand.capture());
    assertThat(driverCommand.getValue())
        .satisfies(
            command -> {
              assertThat(command.repairId()).isEqualTo(created.id());
              assertThat(command.sourceType()).isEqualTo("REPAIR");
              assertThat(command.sourceId()).isEqualTo(created.id());
              assertThat(command.kind())
                  .isEqualTo("DELIVER_TO_REPAIR");
              assertThat(command.planningMode())
                  .isEqualTo(
                      RepairLogisticsPlanningMode.FIXED_DATE);
              assertThat(command.scheduledDate())
                  .isEqualTo(scheduledDate);
              assertThat(command.priority()).isEqualTo(2);
              assertThat(command.activateNow()).isFalse();
            });
    assertThat(
            jdbc.queryForObject(
                """
                select state from integration_reconciliation
                where repair_id=? and operation_type='CREATE_DRIVER_TASK'
                """,
                String.class,
                created.id()))
        .isEqualTo("CONFIRMED");

    service.activateQueuedRepairAfterDelivery(warehouseId, created.id());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=?
                  and dependency_type='TASK_BOARD'
                  and operation_type='REGISTER_TASK'
                """,
                Integer.class,
                created.id()))
        .isZero();

    RepairPlaceService.TransitionResult reserved =
        repairPlaces.reserve(warehouseId, created.id(), 0, UUID.randomUUID());
    assertThat(reserved.response().state()).isEqualTo(RepairPlaceAllocationState.RESERVED);
    assertThat(reserved.response().rentalItemId()).isEqualTo(rentalItemId);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=?
                  and dependency_type='TASK_BOARD'
                  and operation_type='REGISTER_TASK'
                """,
                Integer.class,
                created.id()))
        .isZero();

    RepairPlaceService.TransitionResult occupied =
        repairPlaces.occupy(
            warehouseId,
            created.id(),
            reserved.response().version(),
            UUID.randomUUID());
    assertThat(occupied.response().state()).isEqualTo(RepairPlaceAllocationState.OCCUPIED);
    assertThat(occupied.response().rentalItemId()).isEqualTo(rentalItemId);
    service.activateQueuedRepairAfterDelivery(warehouseId, created.id());
    service.activateQueuedRepairAfterDelivery(warehouseId, created.id());

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=?
                  and dependency_type='TASK_BOARD'
                  and operation_type='REGISTER_TASK'
                """,
                Integer.class,
                created.id()))
        .isOne();

    UUID queueEntryId = UUID.randomUUID();
    when(
            dependencies.registerTask(
                any(),
                eq(fixture.externalTaskId()),
                eq(fixture.repairId()),
                eq(warehouseId),
                eq(rentalItemId),
                eq("БТ-42"),
                eq(LocalDate.now(ZoneId.of("Europe/Moscow"))),
                eq(1),
                eq(6),
                anyList()))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(),
                0,
                "ACTIVE",
                List.of(
                    new MaintenanceDependencyGateway.TaskStageSnapshot(
                        0, queueEntryId, 0))));
    deferOtherReconciliations(created.id(), "REGISTER_TASK");
    assertThat(service.reconcileOneTask()).isTrue();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaintenanceDependencyGateway.TaskStage>>
        taskStagesCaptor =
            ArgumentCaptor.forClass(List.class);
    verify(dependencies)
        .registerTask(
            any(),
            eq(fixture.externalTaskId()),
            eq(fixture.repairId()),
            eq(warehouseId),
            eq(rentalItemId),
            eq("БТ-42"),
            eq(LocalDate.now(ZoneId.of("Europe/Moscow"))),
            eq(1),
            eq(6),
            taskStagesCaptor.capture());
    assertThat(
            jdbc.queryForObject(
                "select priority from maintenance_repair where id=?",
                Integer.class,
                created.id()))
        .isEqualTo(2);
    assertThat(taskStagesCaptor.getValue())
        .singleElement()
        .satisfies(
            stage -> {
              assertThat(stage.kind())
                  .isEqualTo(RepairStageKind.REPAIR_WORK);
              assertThat(stage.order()).isZero();
            });

    applyTaskOutcome(
        fixture,
        queueEntryId,
        "task-board.queue-entry.completed.v1");

    assertThat(
            jdbc.queryForObject(
                """
                select state from repair_place_allocation
                where repair_id=?
                """,
                String.class,
                created.id()))
        .isEqualTo("READY_TO_RELEASE");
  }

  @Test
  void globalCatalogWorkResolvesItsDefinitionAtRepairWarehouse() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));

    UUID lineId = UUID.randomUUID();
    CatalogNodeSnapshot catalogSnapshot =
        new CatalogNodeSnapshot(
            work.catalogVersionId(),
            work.nodeId(),
            CatalogNodeType.WORK,
            "Проверочная работа",
            "шт",
            "100.00",
            15,
            work.routing(),
            null,
            false,
            null);
    RoutingSnapshot targetWarehouseRouting = work.routing();
    CreateDirectRepairRequest request =
        new CreateDirectRepairRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 26),
            null,
            List.of(
                new EstimateLineInput(
                    lineId,
                    catalogSnapshot,
                    EstimateLineType.WORK,
                    "Проверочная работа",
                    "шт",
                    "1",
                    "100.00",
                    15,
                    null,
                    List.of())),
            List.of(
                new PlanStageInput(
                    UUID.randomUUID(),
                    RepairStageKind.REPAIR_WORK,
                    0,
                    targetWarehouseRouting,
                    null)),
            List.of());

    var created =
        service.createDirectRepair(UUID.randomUUID(), UUID.randomUUID(), request);

    assertThat(created.response().warehouseId()).isEqualTo(warehouseId);
    assertThat(created.response().plan().stages().getFirst().routing())
        .isEqualTo(targetWarehouseRouting);
  }

  @Test
  void catalogLineCanonicalizesUnitFromActiveCatalogAndRejectsSubmittedTypeMismatch() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    CatalogNodeSnapshot forgedSnapshot = new CatalogNodeSnapshot(
        work.catalogVersionId(),
        work.nodeId(),
        CatalogNodeType.MATERIAL,
        "Поддельная позиция",
        "подделка",
        "0.00",
        0,
        null,
        null,
        false,
        null);
    UUID acceptedLineId = UUID.randomUUID();
    EstimateLineInput accepted = new EstimateLineInput(
        acceptedLineId,
        forgedSnapshot,
        EstimateLineType.WORK,
        "Проверочная работа",
        "переданная единица",
        "1",
        "100.00",
        null,
        null,
        List.of());
    PlanStageInput acceptedStage = new PlanStageInput(
        UUID.randomUUID(),
        RepairStageKind.REPAIR_WORK,
        0,
        work.routing(),
        List.of(acceptedLineId),
        acceptedLineId,
        "",
        null);

    var created = service.createDirectRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateDirectRepairRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 26),
            null,
            List.of(accepted),
            List.of(acceptedStage),
            List.of()));
    assertThat(created.response().plan().stages().getFirst().workLines())
        .singleElement()
        .satisfies(line -> {
          assertThat(line.lineType()).isEqualTo(EstimateLineType.WORK);
          assertThat(line.unit()).isEqualTo("шт");
        });

    UUID mismatchedLineId = UUID.randomUUID();
    EstimateLineInput mismatched = new EstimateLineInput(
        mismatchedLineId,
        forgedSnapshot,
        EstimateLineType.MATERIAL,
        "Проверочная работа",
        "шт",
        "1",
        "100.00",
        null,
        null,
        List.of());
    PlanStageInput mismatchedStage = new PlanStageInput(
        UUID.randomUUID(),
        RepairStageKind.REPAIR_WORK,
        0,
        work.routing(),
        List.of(mismatchedLineId),
        null,
        "",
        null);
    assertThatThrownBy(
            () ->
                service.createDirectRepair(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new CreateDirectRepairRequest(
                        warehouseId,
                        rentalItemId,
                        LocalDate.of(2026, 7, 26),
                        null,
                        List.of(mismatched),
                        List.of(mismatchedStage),
                        List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("type must match");
  }

  @Test
  void directRepairAcceptsAnIndependentlyRoutedMaterialOnlyStage() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    TestCatalogWork route = ensureTestCatalogWork(warehouseId);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    UUID materialLineId = UUID.randomUUID();
    EstimateLineInput material =
        new EstimateLineInput(
            materialLineId,
            null,
            EstimateLineType.MATERIAL,
            "Герметик",
            "туба",
            "2",
            "350.00",
            0,
            null,
            List.of());
    PlanStageInput materialStage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            route.routing(),
            List.of(materialLineId),
            null,
            "",
            null);

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateDirectRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 27),
                null,
                List.of(material),
                List.of(materialStage),
                List.of()));

    assertThat(created.response().plan().stages().getFirst().workLines()).isEmpty();
    assertThat(created.response().plan().stages().getFirst().materialLines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.id()).isEqualTo(materialLineId);
              assertThat(line.lineType()).isEqualTo(EstimateLineType.MATERIAL);
            });
    assertThat(created.response().plan().stages().getFirst().primaryLineId()).isNull();
  }

  @ParameterizedTest
  @CsvSource({
      "60, LIGHT",
      "61, MEDIUM",
      "180, MEDIUM",
      "181, COMPLEX",
      "360, COMPLEX",
      "361, CAPITAL"
  })
  void repairComplexityUsesContinuousInclusiveWarehouseBoundaries(
      int plannedMinutes, RepairComplexity expected) {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));

    RepairResponse created =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                customWorkRepairRequest(
                    warehouseId, rentalItemId, plannedMinutes))
            .response();

    assertThat(created.complexity().type()).isEqualTo(expected);
    assertThat(created.complexity().plannedMinutes())
        .isEqualTo(Integer.toString(plannedMinutes));
    assertThat(created.complexity().forcedCapital()).isFalse();
  }

  @Test
  void repairComplexityIsRecalculatedFromCurrentWarehouseThresholdsOnRead() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    RepairResponse created =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                customWorkRepairRequest(warehouseId, rentalItemId, 120))
            .response();
    assertThat(created.complexity().type()).isEqualTo(RepairComplexity.MEDIUM);

    repairComplexitySettings.replace(
        warehouseId,
        new dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexitySettingsRequest(
            0L, 30, 60, 90));

    assertThat(service.repair(created.id(), warehouseId).complexity().type())
        .isEqualTo(RepairComplexity.CAPITAL);
  }

  @Test
  void removingForcedCapitalWorkFromDraftReturnsToTimeBasedComplexity() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    TestCatalogWork normalWork = ensureTestCatalogWork(warehouseId);
    TestCatalogWork forcedWork =
        insertTestCatalogWork(normalWork, "Замена несущей конструкции", true);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));

    RepairResponse forced =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                catalogWorkRepairRequest(warehouseId, rentalItemId, forcedWork))
            .response();
    assertThat(forced.complexity().type()).isEqualTo(RepairComplexity.CAPITAL);
    assertThat(forced.complexity().forcedCapital()).isTrue();

    CreateDirectRepairRequest normalPlan =
        catalogWorkRepairRequest(warehouseId, rentalItemId, normalWork);
    RepairResponse recalculated =
        service.updateRepairPlan(
            forced.id(),
            new UpdateRepairPlanRequest(
                forced.version(),
                normalPlan.lines(),
                normalPlan.plan(),
                List.of(),
                null));

    assertThat(recalculated.complexity().type()).isEqualTo(RepairComplexity.LIGHT);
    assertThat(recalculated.complexity().forcedCapital()).isFalse();
    assertThatThrownBy(() -> service.activeCapitalRepair(recalculated.id()))
        .isInstanceOf(MaintenanceNotFoundException.class)
        .hasMessageContaining("Active capital repair not found");
  }

  @Test
  void repairPlaceLifecycleIsCapacityBoundIdempotentAndEnrichedForLogistics() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    RepairResponse repair =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                customWorkRepairRequest(warehouseId, rentalItemId, 45))
            .response();

    UUID reserveKey = UUID.randomUUID();
    RepairPlaceService.TransitionResult reserved =
        repairPlaces.reserve(warehouseId, repair.id(), 0, reserveKey);
    assertThat(reserved.replayed()).isFalse();
    assertThat(reserved.response().state()).isEqualTo(RepairPlaceAllocationState.RESERVED);
    assertThat(reserved.response().rentalItemId()).isEqualTo(rentalItemId);
    assertThat(repairPlaces.reserve(warehouseId, repair.id(), 0, reserveKey).replayed())
        .isTrue();

    var projection = repairPlaces.logisticsProjection(warehouseId);
    assertThat(projection.repairPlaceCount()).isEqualTo(6);
    assertThat(projection.reservedCount()).isOne();
    assertThat(projection.availableCount()).isEqualTo(5);
    assertThat(projection.allocations())
        .singleElement()
        .satisfies(
            allocation -> {
              assertThat(allocation.repairId()).isEqualTo(repair.id());
              assertThat(allocation.rentalItemId()).isEqualTo(rentalItemId);
            });

    var occupied =
        repairPlaces.occupy(
            warehouseId,
            repair.id(),
            reserved.response().version(),
            UUID.randomUUID());
    var ready =
        repairPlaces.readyToRelease(
            warehouseId,
            repair.id(),
            occupied.response().version(),
            UUID.randomUUID());
    var released =
        repairPlaces.release(
            warehouseId,
            repair.id(),
            ready.response().version(),
            UUID.randomUUID());

    assertThat(released.response().state()).isEqualTo(RepairPlaceAllocationState.RELEASED);
    assertThat(released.response().rentalItemId()).isEqualTo(rentalItemId);
    assertThat(repairPlaces.logisticsProjection(warehouseId).allocations()).isEmpty();
    assertThat(repairPlaces.projection(warehouseId).allocations())
        .singleElement()
        .extracting(value -> value.state())
        .isEqualTo(RepairPlaceAllocationState.RELEASED);

    var nextCycle =
        repairPlaces.reserve(warehouseId, repair.id(), 0, UUID.randomUUID());
    assertThat(nextCycle.response().id()).isNotEqualTo(released.response().id());
    assertThat(repairPlaces.logisticsProjection(warehouseId).allocations())
        .singleElement()
        .extracting(value -> value.state())
        .isEqualTo(RepairPlaceAllocationState.RESERVED);
    assertThat(repairPlaces.projection(warehouseId).allocations())
        .extracting(value -> value.state())
        .containsExactly(
            RepairPlaceAllocationState.RELEASED,
            RepairPlaceAllocationState.RESERVED);
  }

  @Test
  void readyRemovalPairsWithExactlyOneInboundReservationAtFullCapacity() {
    UUID warehouseId = UUID.randomUUID();
    repairCapacitySettings.replace(
        warehouseId, new ReplaceRepairCapacitySettingsRequest(0L, 1, 5));
    UUID readyCabinId = UUID.randomUUID();
    UUID inboundCabinId = UUID.randomUUID();
    rentalItemFacts.saveAllAndFlush(
        List.of(
            RentalItemFactProjection.create(readyCabinId, warehouseId, "FREE", 1),
            RentalItemFactProjection.create(inboundCabinId, warehouseId, "FREE", 1)));
    RepairResponse readyRepair =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                customWorkRepairRequest(warehouseId, readyCabinId, 30))
            .response();
    RepairResponse inboundRepair =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                customWorkRepairRequest(warehouseId, inboundCabinId, 30))
            .response();
    var readyReservation =
        repairPlaces.reserve(warehouseId, readyRepair.id(), 0, UUID.randomUUID());
    var occupied =
        repairPlaces.occupy(
            warehouseId,
            readyRepair.id(),
            readyReservation.response().version(),
            UUID.randomUUID());
    repairPlaces.readyToRelease(
        warehouseId,
        readyRepair.id(),
        occupied.response().version(),
        UUID.randomUUID());

    var paired =
        repairPlaces.reserve(warehouseId, inboundRepair.id(), 0, UUID.randomUUID());

    assertThat(paired.response().state()).isEqualTo(RepairPlaceAllocationState.RESERVED);
    var projection = repairPlaces.logisticsProjection(warehouseId);
    assertThat(projection.readyToReleaseCount()).isOne();
    assertThat(projection.reservedCount()).isOne();
    assertThat(projection.availableCount()).isZero();
    assertThat(projection.overCapacity()).isFalse();
    UUID overflowCabinId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(overflowCabinId, warehouseId, "FREE", 1));
    RepairResponse overflowRepair =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                customWorkRepairRequest(warehouseId, overflowCabinId, 30))
            .response();
    assertThatThrownBy(
            () ->
                repairPlaces.reserve(
                    warehouseId,
                    overflowRepair.id(),
                    0,
                    UUID.randomUUID()))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("no available or paired repair place");
  }

  @Test
  void queuedRepairWithdrawsOrdinaryTaskAndEntersImmediateCapitalAcceptanceIdempotently() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    TestCatalogWork normalWork = ensureTestCatalogWork(warehouseId);
    TestCatalogWork forcedWork =
        insertTestCatalogWork(normalWork, "Замена несущей конструкции", true);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));

    RepairResponse created =
        service
            .createDirectRepair(
                UUID.randomUUID(),
                UUID.randomUUID(),
                catalogWorkRepairRequest(
                    warehouseId, rentalItemId, normalWork))
            .response();
    RepairFixture fixture =
        new RepairFixture(
            created.id(),
            created.plan().stages().getFirst().taskSync().externalTaskId(),
            warehouseId,
            rentalItemId);
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new QueueRepairRequest(created.version(), 3));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(fixture);
    var queued = repairs.findById(fixture.repairId()).orElseThrow();
    assertThat(queued.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(queued.getTaskBoardVersion()).isNotNull();

    CreateDirectRepairRequest forcedPlan =
        catalogWorkRepairRequest(warehouseId, rentalItemId, forcedWork);
    RepairResponse forced =
        service.updateRepairPlan(
            fixture.repairId(),
            new UpdateRepairPlanRequest(
                queued.getVersion(),
                forcedPlan.lines(),
                forcedPlan.plan(),
                List.of(),
                null));
    assertThat(forced.complexity().type()).isEqualTo(RepairComplexity.CAPITAL);
    assertThat(forced.complexity().forcedCapital()).isTrue();

    var beforeCapitalSync = repairs.findById(fixture.repairId()).orElseThrow();
    when(
            dependencies.cancelTask(
                any(),
                eq(fixture.externalTaskId()),
                eq(beforeCapitalSync.getTaskBoardVersion())))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(),
                beforeCapitalSync.getTaskBoardVersion() + 1,
                "CANCELLED",
                List.of()));
    deferOtherReconciliations(
        fixture.repairId(), "SYNC_REPAIR_COMPLEXITY_STATUS");
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId,
                beforeCapitalSync.getRentalItemVersionSnapshot(),
                warehouseId,
                "БТ-42",
                "REPAIR"));
    when(
            dependencies.fencedStatus(
                any(),
                eq(rentalItemId),
                eq(warehouseId),
                eq(beforeCapitalSync.getRentalItemVersionSnapshot()),
                eq(beforeCapitalSync.getLeaseId()),
                eq(beforeCapitalSync.getFencingToken()),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString()),
                eq("QUEUE_TO_CAPITAL_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId,
                beforeCapitalSync.getRentalItemVersionSnapshot() + 1,
                warehouseId,
                "БТ-42",
                "CAPITAL_REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(
            jdbc.queryForMap(
                """
                select state,last_error_code,response_snapshot::text as response_snapshot
                from integration_reconciliation
                where repair_id=? and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'
                order by created_at desc limit 1
                """,
                fixture.repairId()))
        .containsEntry("state", "CONFIRMED")
        .containsEntry("last_error_code", null);
    var externalCapital = repairs.findById(fixture.repairId()).orElseThrow();
    assertThat(externalCapital.getExecutionState())
        .isEqualTo(RepairExecutionState.COMPLETED);
    assertThat(externalCapital.getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(externalCapital.getReclassificationState().name())
        .isEqualTo("EXTERNAL_CAPITAL");
    assertThat(externalCapital.getTaskGenerationState()).isEqualTo("NOT_REQUIRED");
    assertThat(service.activeCapitalRepairs(warehouseId))
        .extracting(RepairResponse::id)
        .containsExactly(fixture.repairId());
    assertThat(service.activeCapitalRepair(fixture.repairId()).id())
        .isEqualTo(fixture.repairId());
    assertThat(service.acceptance(warehouseId))
        .extracting(AcceptanceProjection::repairId)
        .contains(fixture.repairId());

    jdbc.update(
        """
        update integration_reconciliation
           set state='RETRY_PENDING', next_attempt_at=clock_timestamp()
         where repair_id=? and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'
        """,
        fixture.repairId());
    clearInvocations(dependencies);
    long capitalAssetVersion =
        repairs
            .findById(fixture.repairId())
            .orElseThrow()
            .getRentalItemVersionSnapshot();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId,
                capitalAssetVersion,
                warehouseId,
                "БТ-42",
                "CAPITAL_REPAIR"));
    assertThat(service.reconcileOneTask()).isTrue();
    verify(dependencies, never())
        .fencedStatus(
            any(),
            any(),
            any(),
            anyLong(),
            any(),
            anyLong(),
            anyString(),
            anyString(),
            anyString(),
            anyBoolean());
    CreateDirectRepairRequest normalPlan =
        catalogWorkRepairRequest(warehouseId, rentalItemId, normalWork);
    assertThatThrownBy(
            () ->
                service.updateRepairPlan(
                    fixture.repairId(),
                    new UpdateRepairPlanRequest(
                        externalCapital.getVersion(),
                        normalPlan.lines(),
                        normalPlan.plan(),
                        List.of(),
                        null)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("cannot be amended");
  }

  @Test
  void transferArrivalFailsClosedWhenTargetWarehouseLacksRequiredGlobalQueue() {
    RegisteredRepairFixture registered = createRegisteredPrimaryRepair();
    RepairFixture fixture = registered.repair();
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    UUID requiredQueueDefinitionId =
        service
            .repair(fixture.repairId(), fixture.warehouseId())
            .plan()
            .stages()
            .getFirst()
            .routing()
            .queueId();
    TransferRepairRequest transfer =
        new TransferRepairRequest(
            fixture.rentalItemId(),
            fixture.warehouseId(),
            targetWarehouseId);

    var prepared =
        service.prepareTransferDeparture(
            transferId, lineId, UUID.randomUUID(), transfer);
    assertThat(prepared.response().activeRepairId())
        .isEqualTo(fixture.repairId());
    assertThat(
            repairs
                .findById(fixture.repairId())
                .orElseThrow()
                .getTransferState())
        .isEqualTo("DEPARTURE_PREPARED");

    when(dependencies.queueCapabilities(targetWarehouseId))
        .thenReturn(
            new MaintenanceDependencyGateway.QueueCapabilities(
                targetWarehouseId, List.of()));
    when(
            dependencies.preflightMaintenanceRouting(
                eq(targetWarehouseId), anyList()))
        .thenAnswer(
            invocation ->
                new MaintenanceDependencyGateway.RoutingPreflight(
                    targetWarehouseId,
                    false,
                    List.of(requiredQueueDefinitionId),
                    List.of(),
                    List.of(),
                    List.of()));

    var preflight =
        service.transferArrivalPreflight(
            transferId, lineId, transfer);
    assertThat(preflight.activeRepairId()).isEqualTo(fixture.repairId());
    assertThat(preflight.priorityRequired()).isTrue();
    assertThat(preflight.missingQueueDefinitionIds())
        .containsExactly(requiredQueueDefinitionId);

    clearInvocations(dependencies);
    assertThatThrownBy(
            () ->
                service.completeTransferArrival(
                    transferId,
                    lineId,
                    UUID.randomUUID(),
                    new CompleteTransferRepairRequest(
                        fixture.rentalItemId(),
                        repairs
                                .findById(fixture.repairId())
                                .orElseThrow()
                                .getRentalItemVersionSnapshot()
                            + 1,
                        fixture.warehouseId(),
                        targetWarehouseId,
                        4)))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(
            exception ->
                ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_TARGET_QUEUE_MISSING");

    verify(dependencies).queueCapabilities(targetWarehouseId);
    verify(dependencies)
        .preflightMaintenanceRouting(
            eq(targetWarehouseId),
            argThat(
                requirements ->
                    requirements.size() == 1
                        && requirements
                            .getFirst()
                            .queueDefinitionId()
                            .equals(requiredQueueDefinitionId)));
    verify(dependencies, never()).getTask(any());
    verify(dependencies, never())
        .relocateTask(any(), any(), anyLong(), any());
    verify(dependencies, never()).getRentalItemSnapshot(any());
    verify(dependencies, never())
        .acquireLease(any(), any(), anyLong(), anyString(), anyString());
    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getWarehouseId())
                  .isEqualTo(fixture.warehouseId());
              assertThat(repair.getTransferTargetWarehouseId())
                  .isEqualTo(targetWarehouseId);
              assertThat(repair.getTransferState())
                  .isEqualTo("DEPARTURE_PREPARED");
            });
  }

  @Test
  void transferArrivalWithdrawsOrdinaryTaskWhenTargetThresholdMakesRepairCapital() {
    RegisteredRepairFixture registered = createRegisteredPrimaryRepair();
    RepairFixture fixture = registered.repair();
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    TransferRepairRequest transfer =
        new TransferRepairRequest(
            fixture.rentalItemId(),
            fixture.warehouseId(),
            targetWarehouseId);
    repairComplexitySettings.replace(
        targetWarehouseId,
        new dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexitySettingsRequest(
            0L, 1, 2, 3));
    when(dependencies.queueCapabilities(targetWarehouseId))
        .thenReturn(
            new MaintenanceDependencyGateway.QueueCapabilities(
                targetWarehouseId, List.of()));

    service.prepareTransferDeparture(
        transferId, lineId, UUID.randomUUID(), transfer);

    TransferRepairArrivalPreflightResponse preflight =
        service.transferArrivalPreflight(transferId, lineId, transfer);
    assertThat(preflight.activeRepairId()).isEqualTo(fixture.repairId());
    assertThat(preflight.missingQueueDefinitionIds()).isEmpty();
    verify(dependencies, never())
        .preflightMaintenanceRouting(eq(targetWarehouseId), anyList());

    var repairBeforeArrival =
        repairs.findById(fixture.repairId()).orElseThrow();
    long taskVersion = repairBeforeArrival.getTaskBoardVersion();
    long targetAssetVersion =
        repairBeforeArrival.getRentalItemVersionSnapshot() + 1;
    UUID targetLeaseId = UUID.randomUUID();
    when(dependencies.getTask(fixture.externalTaskId()))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(), taskVersion, "ACTIVE", List.of()));
    when(
            dependencies.cancelTask(
                any(), eq(fixture.externalTaskId()), eq(taskVersion)))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(), taskVersion + 1, "CANCELLED", List.of()));
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                fixture.rentalItemId(),
                targetAssetVersion,
                targetWarehouseId,
                "БТ-42",
                "REPAIR"));
    when(
            dependencies.acquireLease(
                any(),
                eq(fixture.rentalItemId()),
                eq(targetAssetVersion),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                targetLeaseId,
                0,
                fixture.rentalItemId(),
                "MAINTENANCE_REPAIR",
                fixture.repairId(),
                31,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(
            dependencies.fencedStatus(
                any(),
                eq(fixture.rentalItemId()),
                eq(targetWarehouseId),
                eq(targetAssetVersion),
                eq(targetLeaseId),
                eq(31L),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString()),
                eq("QUEUE_TO_CAPITAL_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                fixture.rentalItemId(),
                targetAssetVersion + 1,
                targetWarehouseId,
                "БТ-42",
                "CAPITAL_REPAIR"));

    CompleteTransferRepairResponse completed =
        service
            .completeTransferArrival(
                transferId,
                lineId,
                UUID.randomUUID(),
                new CompleteTransferRepairRequest(
                    fixture.rentalItemId(),
                    targetAssetVersion,
                    fixture.warehouseId(),
                    targetWarehouseId,
                    4))
            .response();

    assertThat(completed.activeRepairId()).isEqualTo(fixture.repairId());
    assertThat(completed.warehouseId()).isEqualTo(targetWarehouseId);
    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getWarehouseId()).isEqualTo(targetWarehouseId);
              assertThat(value.getPriority()).isEqualTo(4);
              assertThat(value.getExecutionState())
                  .isEqualTo(RepairExecutionState.COMPLETED);
              assertThat(value.getAcceptanceState())
                  .isEqualTo(RepairAcceptanceState.PENDING);
              assertThat(value.getReclassificationState())
                  .isEqualTo(RepairReclassificationState.EXTERNAL_CAPITAL);
              assertThat(value.getTaskBoardVersion()).isNull();
            });
    assertThat(
            jdbc.queryForMap(
                """
                select state, task_generation_state
                  from repair_stage
                 where repair_id=?
                """,
                fixture.repairId()))
        .containsEntry("state", "DONE")
        .containsEntry("task_generation_state", "NOT_REQUIRED");
    assertThat(
            service
                .activeCapitalRepair(fixture.repairId())
                .complexity()
                .type())
        .isEqualTo(RepairComplexity.CAPITAL);
    verify(dependencies)
        .cancelTask(any(), eq(fixture.externalTaskId()), eq(taskVersion));
    verify(dependencies, never())
        .relocateTask(any(), any(), anyLong(), any());
  }

  @Test
  void transferArrivalReplaysEveryRemoteEffectAfterPostRemoteLocalRollback() {
    RegisteredRepairFixture registered = createRegisteredPrimaryRepair();
    RepairFixture fixture = registered.repair();
    UUID transferId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    UUID targetWarehouseId = UUID.randomUUID();
    repairComplexitySettings.replace(
        targetWarehouseId,
        new dev.buhanzaz.rwms.maintenance.api.ReplaceRepairComplexitySettingsRequest(
            0L, 1, 2, 3));
    when(dependencies.queueCapabilities(targetWarehouseId))
        .thenReturn(
            new MaintenanceDependencyGateway.QueueCapabilities(targetWarehouseId, List.of()));
    service.prepareTransferDeparture(
        transferId,
        lineId,
        UUID.randomUUID(),
        new TransferRepairRequest(
            fixture.rentalItemId(), fixture.warehouseId(), targetWarehouseId));

    var repairBeforeArrival = repairs.findById(fixture.repairId()).orElseThrow();
    long taskVersion = repairBeforeArrival.getTaskBoardVersion();
    long targetAssetVersion = repairBeforeArrival.getRentalItemVersionSnapshot() + 1;
    UUID targetLeaseId = UUID.randomUUID();
    MaintenanceDependencyGateway.TaskSnapshot taskBefore =
        new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(), taskVersion, "ACTIVE", List.of());
    MaintenanceDependencyGateway.TaskSnapshot taskAfter =
        new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(), taskVersion + 1, "CANCELLED", List.of());
    MaintenanceDependencyGateway.AssetSnapshot assetBefore =
        new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(),
            targetAssetVersion,
            targetWarehouseId,
            "БТ-42",
            "REPAIR");
    MaintenanceDependencyGateway.AssetSnapshot assetAfter =
        new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(),
            targetAssetVersion + 1,
            targetWarehouseId,
            "БТ-42",
            "CAPITAL_REPAIR");
    MaintenanceDependencyGateway.LeaseSnapshot lease =
        new MaintenanceDependencyGateway.LeaseSnapshot(
            targetLeaseId,
            0,
            fixture.rentalItemId(),
            "MAINTENANCE_REPAIR",
            fixture.repairId(),
            31,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
    when(dependencies.getTask(fixture.externalTaskId())).thenReturn(taskBefore, taskAfter);
    when(dependencies.cancelTask(any(), eq(fixture.externalTaskId()), eq(taskVersion)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return taskAfter;
            });
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(assetBefore, assetAfter);
    when(
            dependencies.acquireLease(
                any(),
                eq(fixture.rentalItemId()),
                eq(targetAssetVersion),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString())))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return lease;
            });
    when(
            dependencies.fencedStatus(
                any(),
                eq(fixture.rentalItemId()),
                eq(targetWarehouseId),
                eq(targetAssetVersion),
                eq(targetLeaseId),
                eq(31L),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString()),
                eq("QUEUE_TO_CAPITAL_REPAIR"),
                eq(false)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return assetAfter;
            });

    CompleteTransferRepairRequest request =
        new CompleteTransferRepairRequest(
            fixture.rentalItemId(),
            targetAssetVersion,
            fixture.warehouseId(),
            targetWarehouseId,
            4);
    UUID key = UUID.randomUUID();
    doThrow(new IllegalStateException("synthetic post-remote local failure"))
        .doCallRealMethod()
        .when(eventFacts)
        .repairPayload(eq(MaintenanceEventType.REPAIR_TRANSFERRED), any(), anyList());

    assertThatThrownBy(
            () -> service.completeTransferArrival(transferId, lineId, key, request))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("synthetic post-remote local failure");
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getWarehouseId())
        .isEqualTo(fixture.warehouseId());

    MaintenanceApplicationService.CreateResult<CompleteTransferRepairResponse> completed =
        service.completeTransferArrival(transferId, lineId, key, request);
    assertThat(completed.replayed()).isFalse();
    assertThat(completed.response().warehouseId()).isEqualTo(targetWarehouseId);

    ArgumentCaptor<UUID> cancelKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .cancelTask(cancelKeys.capture(), eq(fixture.externalTaskId()), eq(taskVersion));
    assertThat(cancelKeys.getAllValues())
        .hasSize(2)
        .allMatch(cancelKeys.getAllValues().getFirst()::equals);
    ArgumentCaptor<UUID> leaseKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .acquireLease(
            leaseKeys.capture(),
            eq(fixture.rentalItemId()),
            eq(targetAssetVersion),
            eq("MAINTENANCE_REPAIR"),
            eq(fixture.repairId().toString()));
    assertThat(leaseKeys.getAllValues())
        .hasSize(2)
        .allMatch(leaseKeys.getAllValues().getFirst()::equals);
    ArgumentCaptor<UUID> statusKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .fencedStatus(
            statusKeys.capture(),
            eq(fixture.rentalItemId()),
            eq(targetWarehouseId),
            eq(targetAssetVersion),
            eq(targetLeaseId),
            eq(31L),
            eq("MAINTENANCE_REPAIR"),
            eq(fixture.repairId().toString()),
            eq("QUEUE_TO_CAPITAL_REPAIR"),
            eq(false));
    assertThat(statusKeys.getAllValues())
        .hasSize(2)
        .allMatch(statusKeys.getAllValues().getFirst()::equals);

    clearInvocations(dependencies);
    assertThat(service.completeTransferArrival(transferId, lineId, key, request).replayed()).isTrue();
    verifyNoInteractions(dependencies);
  }

  @Test
  void forcedCapitalReworkSynchronizesThroughThePrimaryLifecycleOwner() {
    RepairFixture source = createQueuedPendingAcceptanceRepair();
    var sourceBeforeRework =
        repairs.findById(source.repairId()).orElseThrow();
    TestCatalogWork forcedWork =
        insertTestCatalogWork(
            ensureTestCatalogWork(source.warehouseId()),
            "Замена несущего каркаса",
            true);
    CreateDirectRepairRequest forcedContent =
        catalogWorkRepairRequest(
            source.warehouseId(), source.rentalItemId(), forcedWork);
    RepairResponse child =
        service
            .createRework(
                UUID.randomUUID(),
                UUID.randomUUID(),
                source.repairId(),
                new CreateReworkRequest(
                    sourceBeforeRework.getVersion(),
                    "Капитальная доработка",
                    forcedContent.lines(),
                    forcedContent.plan(),
                    List.of()))
            .response();
    assertThat(child.complexity().type())
        .isEqualTo(RepairComplexity.CAPITAL);
    assertThat(child.complexity().forcedCapital()).isTrue();

    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        child.id(),
        new QueueRepairRequest(child.version(), 5));
    var primaryOwner =
        repairs.findById(source.repairId()).orElseThrow();
    var queuedChild = repairs.findById(child.id()).orElseThrow();
    assertThat(queuedChild.getExecutionState())
        .isEqualTo(RepairExecutionState.COMPLETED);
    assertThat(queuedChild.getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(queuedChild.getReclassificationState())
        .isEqualTo(RepairReclassificationState.EXTERNAL_CAPITAL);
    assertThat(primaryOwner.getLeaseId()).isNotNull();
    deferOtherReconciliations(
        child.id(), "SYNC_REPAIR_COMPLEXITY_STATUS");

    long currentAssetVersion =
        Math.max(
            primaryOwner.getRentalItemVersionSnapshot(),
            queuedChild.getRentalItemVersionSnapshot());
    clearInvocations(dependencies);
    when(dependencies.getRentalItemSnapshot(source.rentalItemId()))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                source.rentalItemId(),
                currentAssetVersion,
                source.warehouseId(),
                "БТ-42",
                "REPAIR"));
    when(
            dependencies.fencedStatus(
                any(),
                eq(source.rentalItemId()),
                eq(source.warehouseId()),
                eq(currentAssetVersion),
                eq(primaryOwner.getLeaseId()),
                eq(primaryOwner.getFencingToken()),
                eq("MAINTENANCE_REPAIR"),
                eq(source.repairId().toString()),
                eq("QUEUE_TO_CAPITAL_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                source.rentalItemId(),
                currentAssetVersion + 1,
                source.warehouseId(),
                "БТ-42",
                "CAPITAL_REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    verify(dependencies)
        .fencedStatus(
            any(),
            eq(source.rentalItemId()),
            eq(source.warehouseId()),
            eq(currentAssetVersion),
            eq(primaryOwner.getLeaseId()),
            eq(primaryOwner.getFencingToken()),
            eq("MAINTENANCE_REPAIR"),
            eq(source.repairId().toString()),
            eq("QUEUE_TO_CAPITAL_REPAIR"),
            eq(false));
    assertThat(
            jdbc.queryForMap(
                """
                select state, last_error_code, attempt_count
                  from integration_reconciliation
                 where repair_id=? and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'
                """,
                child.id()))
        .containsEntry("state", "CONFIRMED");
    assertThat(repairs.findById(source.repairId()).orElseThrow())
        .satisfies(
            value ->
                assertThat(value.getRentalItemVersionSnapshot())
                    .isEqualTo(currentAssetVersion + 1));
    assertThat(repairs.findById(child.id()).orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getRentalItemVersionSnapshot())
                  .isEqualTo(currentAssetVersion + 1);
              assertThat(value.getLeaseId())
                  .isEqualTo(primaryOwner.getLeaseId());
            });
  }

  @Test
  void activationDependencyFailureLeavesTheDraftAndEventStreamUntouched() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "7".repeat(64));
    CatalogVersionResponse changed =
        service.changeCatalog(
            catalogId,
            new ChangeCatalogRequest(
                0L,
                List.of(
                    new CatalogNodeInput(
                        UUID.randomUUID(),
                        CatalogNodeType.WORK,
                        "Routed work",
                        true,
                        null,
                        false,
                        null,
                        "шт",
                        "100.00",
                        15,
                        true,
                        false,
                        true,
                        null,
                        null,
                        new CatalogRoutingInput(UUID.randomUUID(), "REPAIR"),
                        null,
                        null,
                        false,
                        null)),
                List.of()));
    when(dependencies.preflightCatalogRouting(anyList()))
        .thenThrow(new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "task-board unavailable"));

    assertThatThrownBy(() -> service.activateCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            catalogId,
            new VersionCommand(changed.version())))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(jdbc.queryForMap("""
        select state,version from catalog_version where id=?
        """, catalogId))
        .containsEntry("state", "DRAFT")
        .containsEntry("version", changed.version());
    assertThat(jdbc.queryForObject("""
        select current_version from event_stream_head
        where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """, Long.class, catalogId.toString())).isEqualTo(changed.version());
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_idempotency_record", Integer.class)).isZero();
  }

  @Test
  void catalogDisplayColorRoundTripsThroughTheServiceAndDatabase() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "e".repeat(64));
    UUID nodeId = UUID.randomUUID();
    CatalogNodeInput coloredCategory = new CatalogNodeInput(
        nodeId,
        CatalogNodeType.CATEGORY,
        "Exterior",
        true,
        null,
        false,
        null,
        null,
        null,
        0,
        false,
        false,
        true,
        null,
        null,
        null,
        null,
        "#3a7bc2",
        false,
        null);

    service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(0L, List.of(coloredCategory), List.of()));

    assertThat(service.catalogNodes(catalogId))
        .singleElement()
        .extracting(CatalogNodeResponse::displayColor)
        .isEqualTo("#3A7BC2");
    assertThat(jdbc.queryForObject(
        "select display_color from catalog_node where catalog_version_id=? and node_id=?",
        String.class,
        catalogId,
        nodeId)).isEqualTo("#3A7BC2");
  }

  @Test
  void catalogRoutingRegistrationAndSupersededCleanupAreDurableAndOrdered() {
    UUID warehouseId = UUID.randomUUID();
    UUID firstCatalogId = insertDraftCatalog(warehouseId, "8".repeat(64));
    UUID nodeId = UUID.randomUUID();
    UUID queueId = UUID.fromString("019f21ed-eb53-782d-a73f-a24357e262b2");
    RoutingSnapshot routing =
        new RoutingSnapshot(queueId, "SANITARY_DISINFECTION", "HOLDING");
    CatalogNodeInput routedNode = new CatalogNodeInput(
        nodeId,
        CatalogNodeType.WORK,
        "Routed work",
        true,
        null,
        false,
        null,
        "piece",
        "100.00",
        15,
        true,
        false,
        true,
        null,
        null,
        new CatalogRoutingInput(routing.queueId(), routing.queueType()),
        null,
        null,
        false,
        null);
    CatalogVersionResponse changed = service.changeCatalog(
        firstCatalogId,
        new ChangeCatalogRequest(0L, List.of(routedNode), List.of()));
    when(dependencies.registerCatalogPosition(eq(queueId), anyString()))
        .thenAnswer(invocation -> {
          String externalReferenceId = invocation.getArgument(1);
          return new MaintenanceDependencyGateway.CatalogPositionReference(
              UUID.nameUUIDFromBytes(
                  externalReferenceId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              0,
              queueId,
              "CATALOG_POSITION",
              externalReferenceId);
        });

    CatalogVersionResponse firstActive = service.activateCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            firstCatalogId,
            new VersionCommand(changed.version()))
        .response();
    assertThat(firstActive.routingSync()).isNotNull();
    assertThat(firstActive.routingSync().state()).isEqualTo(DeliveryState.PENDING);
    assertThat(firstActive.routingSync().registrationsRequired()).isOne();
    assertThat(firstActive.routingSync().registrationsConfirmed()).isZero();
    assertThat(service.reconcileOneTask()).isTrue();
    CatalogRoutingSyncSnapshot firstDelivered =
        service.catalogVersion(firstCatalogId).routingSync();
    assertThat(firstDelivered.state()).isEqualTo(DeliveryState.DELIVERED);
    assertThat(firstDelivered.registrationsConfirmed()).isOne();

    UUID replacementCatalogId = insertDraftCatalog(warehouseId, "9".repeat(64));
    CatalogVersionResponse replacementDraft = service.changeCatalog(
        replacementCatalogId,
        new ChangeCatalogRequest(0L, List.of(routedNode), List.of()));
    CatalogVersionResponse replacement = service.activateCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            replacementCatalogId,
            new VersionCommand(replacementDraft.version()))
        .response();
    String oldExternalReference = "catalog:" + firstCatalogId + ":" + nodeId;
    String newExternalReference = "catalog:" + replacement.id() + ":" + nodeId;
    assertThat(replacement.routingSync().state()).isEqualTo(DeliveryState.PENDING);
    assertThat(service.catalogVersion(firstCatalogId).routingSync().cleanupRequired()).isOne();

    clearInvocations(dependencies);
    jdbc.update("""
        update integration_reconciliation
        set next_attempt_at=clock_timestamp() - interval '2 seconds'
        where operation_type='DELETE_CATALOG_POSITION' and catalog_version_id=?
        """, firstCatalogId);
    jdbc.update("""
        update integration_reconciliation
        set next_attempt_at=clock_timestamp() + interval '1 hour'
        where operation_type='REGISTER_CATALOG_POSITION' and catalog_version_id=?
        """, replacement.id());
    assertThat(service.reconcileOneTask()).isTrue();
    verify(dependencies, never()).deleteCatalogPosition(anyString(), anyLong());
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where operation_type='DELETE_CATALOG_POSITION' and catalog_version_id=?
        """, String.class, firstCatalogId)).isEqualTo("PENDING");

    jdbc.update("""
        update integration_reconciliation
        set next_attempt_at=clock_timestamp() - interval '1 second'
        where operation_type='REGISTER_CATALOG_POSITION' and catalog_version_id=?
        """, replacement.id());
    assertThat(service.reconcileOneTask()).isTrue();
    verify(dependencies).registerCatalogPosition(queueId, newExternalReference);
    verify(dependencies, never()).deleteCatalogPosition(anyString(), anyLong());

    jdbc.update("""
        update integration_reconciliation
        set next_attempt_at=clock_timestamp() - interval '1 second'
        where operation_type='DELETE_CATALOG_POSITION' and catalog_version_id=?
        """, firstCatalogId);
    assertThat(service.reconcileOneTask()).isTrue();
    verify(dependencies).deleteCatalogPosition(oldExternalReference, 0L);
    verify(dependencies, never()).registerCatalogPosition(queueId, oldExternalReference);
    assertThat(service.catalogVersion(replacement.id()).routingSync().state())
        .isEqualTo(DeliveryState.DELIVERED);
    CatalogRoutingSyncSnapshot supersededTruth =
        service.catalogVersion(firstCatalogId).routingSync();
    assertThat(supersededTruth.state()).isEqualTo(DeliveryState.DELIVERED);
    assertThat(supersededTruth.cleanupRequired()).isOne();
    assertThat(supersededTruth.cleanupConfirmed()).isOne();
  }

  @Test
  void latestCatalogRouteSupersedesAQuarantinedRegistrationForTheSameNode() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "c".repeat(64));
    UUID nodeId = UUID.randomUUID();
    String externalReference = "catalog:" + catalogId + ":" + nodeId;
    TransactionTemplate transactions = new TransactionTemplate(transactionManager);

    transactions.executeWithoutResult(status ->
        reconciliations.enqueueCatalogPosition(
            "REGISTER_CATALOG_POSITION",
            UUID.randomUUID(),
            catalogId,
            nodeId,
            UUID.randomUUID(),
            externalReference,
            List.of()));
    jdbc.update("""
        update integration_reconciliation
           set state='QUARANTINED',
               attempt_count=4,
               created_at=clock_timestamp() - interval '1 minute',
               updated_at=clock_timestamp() - interval '1 minute'
         where catalog_version_id=?
           and catalog_node_id=?
           and operation_type='REGISTER_CATALOG_POSITION'
        """, catalogId, nodeId);

    UUID currentQueueId = UUID.randomUUID();
    transactions.executeWithoutResult(status ->
        reconciliations.enqueueCatalogPosition(
            "REGISTER_CATALOG_POSITION",
            UUID.randomUUID(),
            catalogId,
            nodeId,
            currentQueueId,
            externalReference,
            List.of()));
    transactions.executeWithoutResult(status -> {
      MaintenanceReconciliationStore.WorkItem current =
          reconciliations.lockNextDue().orElseThrow();
      assertThat(current.catalogQueueId()).isEqualTo(currentQueueId);
      reconciliations.confirmed(current, Map.of("queueId", currentQueueId));
    });

    MaintenanceReconciliationStore.CatalogRoutingTruth truth =
        transactions.execute(status -> reconciliations.catalogRoutingTruth(catalogId).orElseThrow());
    assertThat(truth.state()).isEqualTo("DELIVERED");
    assertThat(truth.registrationsRequired()).isOne();
    assertThat(truth.registrationsConfirmed()).isOne();
  }

  @Test
  void concurrentFirstUseOfOneIdempotencyKeyExecutesOneMutation() throws Exception {
    UUID subject = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    String hash = "a".repeat(64);
    AtomicInteger mutations = new AtomicInteger();
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Boolean>> calls = java.util.stream.IntStream.range(0, 2)
          .mapToObj(index -> (Callable<Boolean>) () -> {
            start.await();
            return new TransactionTemplate(transactionManager).execute(status -> {
              var replay = idempotency.replay(subject, "test.concurrent", key, hash);
              if (replay.isPresent()) return true;
              mutations.incrementAndGet();
              if (index == 0) {
                try {
                  Thread.sleep(100);
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                  throw new IllegalStateException(exception);
                }
              }
              idempotency.store(
                  subject, "test.concurrent", key, hash, 200, Map.of("result", "created"));
              return false;
            });
          })
          .toList();
      List<Future<Boolean>> futures = calls.stream().map(executor::submit).toList();
      start.countDown();

      List<Boolean> results = new ArrayList<>();
      for (Future<Boolean> future : futures) results.add(future.get());
      assertThat(results).containsExactlyInAnyOrder(false, true);
      assertThat(mutations).hasValue(1);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void concurrentCatalogActivationKeepsOneGlobalActiveVersionAcrossWarehouses()
      throws Exception {
    UUID first =
        insertDraftCatalog(UUID.randomUUID(), "1".repeat(64));
    UUID second =
        insertDraftCatalog(UUID.randomUUID(), "2".repeat(64));
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Void>> calls = List.of(
          () -> activateAfter(start, first),
          () -> activateAfter(start, second));
      List<Future<Void>> futures = calls.stream().map(executor::submit).toList();
      start.countDown();
      for (Future<Void> future : futures) future.get();

      assertThat(jdbc.queryForObject("""
          select count(*) from catalog_version
          where state='ACTIVE'
          """, Integer.class)).isOne();
      assertThat(jdbc.queryForList("""
          select state from catalog_version order by id
          """, String.class))
          .containsExactlyInAnyOrder("ACTIVE", "SUPERSEDED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void remoteSuccessPrecedesOnlyRetrySafeTransactionalApply() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new QueueRepairRequest(0L, 1));

    verify(dependencies).preflightMaintenanceRouting(eq(fixture.warehouseId()), anyList());
    verify(dependencies, times(2)).productionReady();
    verifyNoMoreInteractions(dependencies);
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getPriority()).isEqualTo(1);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR' and state='PENDING'
        """, Integer.class, fixture.repairId())).isOne();

    reset(dependencies);
    stubQueueDependencies(fixture);
    doThrow(new IllegalStateException("synthetic post-remote local failure"))
        .doCallRealMethod()
        .when(eventFacts)
        .repairPayload(eq(MaintenanceEventType.REPAIR_QUEUED), any(), anyList());

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    assertThat(jdbc.queryForObject("""
        select attempt_count from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, fixture.repairId())).isOne();

    jdbc.update("""
        update integration_reconciliation set next_attempt_at=clock_timestamp()
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, fixture.repairId());
    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
              assertThat(repair.getPriority()).isEqualTo(1);
            });

    ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2)).acquireLease(
        keys.capture(), eq(fixture.rentalItemId()), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString()));
    assertThat(keys.getAllValues()).hasSize(2).allMatch(keys.getAllValues().getFirst()::equals);
  }

  @Test
  void reconciliationCallsAssetAndTaskBoardOutsideTheLocalTransaction() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new QueueRepairRequest(0L, 2));

    UUID leaseId = UUID.randomUUID();
    AtomicInteger rentalSnapshotCalls = new AtomicInteger();
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              int call = rentalSnapshotCalls.incrementAndGet();
              return new MaintenanceDependencyGateway.AssetSnapshot(
                  fixture.rentalItemId(),
                  call == 1 ? 7 : 8,
                  fixture.warehouseId(),
                  "БТ-42",
                  call == 1 ? "FREE" : "REPAIR");
            });
    when(
            dependencies.acquireLease(
                any(),
                eq(fixture.rentalItemId()),
                eq(7L),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString())))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new MaintenanceDependencyGateway.LeaseSnapshot(
                  leaseId,
                  0,
                  fixture.rentalItemId(),
                  "MAINTENANCE_REPAIR",
                  fixture.repairId(),
                  11,
                  OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15));
            });
    when(
            dependencies.fencedStatus(
                any(),
                eq(fixture.rentalItemId()),
                eq(fixture.warehouseId()),
                eq(7L),
                eq(leaseId),
                eq(11L),
                eq("MAINTENANCE_REPAIR"),
                eq(fixture.repairId().toString()),
                eq("QUEUE_TO_REPAIR"),
                eq(false)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new MaintenanceDependencyGateway.AssetSnapshot(
                  fixture.rentalItemId(), 8, fixture.warehouseId(), "БТ-42", "REPAIR");
            });

    assertThat(service.reconcileOneTask()).isTrue();

    UUID queueEntryId = UUID.randomUUID();
    when(
            dependencies.registerTask(
                any(),
                eq(fixture.externalTaskId()),
                eq(fixture.repairId()),
                eq(fixture.warehouseId()),
                eq(fixture.rentalItemId()),
                eq("БТ-42"),
                any(LocalDate.class),
                eq(2),
                eq(6),
                anyList()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new MaintenanceDependencyGateway.TaskSnapshot(
                  fixture.externalTaskId(),
                  0,
                  "ACTIVE",
                  List.of(
                      new MaintenanceDependencyGateway.TaskStageSnapshot(0, queueEntryId, 0)));
            });
    deferOtherReconciliations(fixture.repairId(), "REGISTER_TASK");

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(rentalSnapshotCalls.get()).isEqualTo(2);
  }

  @Test
  void firstPrimaryRepairStillAcquiresLeaseFencesAssetAndRegistersTask() {
    RepairFixture first = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), first.repairId(), new VersionCommand(0L));
    stubQueueDependencies(first);

    assertThat(service.reconcileOneTask()).isTrue();
    var queued = repairs.findById(first.repairId()).orElseThrow();
    assertThat(queued.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(queued.getLeaseId()).isNotNull();
    assertThat(queued.getLeaseReconciliationState()).isEqualTo("ACTIVE");

    registerQueuedRepair(first);
    assertThat(repairs.findById(first.repairId()).orElseThrow().getTaskGenerationState())
        .isEqualTo("GENERATED");

    verify(dependencies).acquireLease(
        any(), eq(first.rentalItemId()), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(first.repairId().toString()));
    verify(dependencies).fencedStatus(
        any(), eq(first.rentalItemId()), eq(first.warehouseId()), eq(7L),
        eq(queued.getLeaseId()), eq(queued.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(first.repairId().toString()), eq("QUEUE_TO_REPAIR"), eq(false));
    verify(dependencies).registerTask(
        any(), eq(first.externalTaskId()), eq(first.repairId()), eq(first.warehouseId()),
        eq(first.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList());
  }

  @Test
  void queuedRepairPlanCanBeChangedBeforeStartAndUpdatesItsRegisteredTask() {
    RegisteredRepairFixture registered = createRegisteredPrimaryRepair();
    RepairFixture fixture = registered.repair();
    var before = repairs.findById(fixture.repairId()).orElseThrow();
    long taskBoardVersion = before.getTaskBoardVersion();
    CreateDirectRepairRequest changedContent =
        directRepairRequest(
            fixture.warehouseId(),
            fixture.rentalItemId(),
            LocalDate.of(2026, 7, 18),
            "Исправленный план");

    clearInvocations(dependencies);
    RepairResponse changed =
        service.updateRepairPlan(
            fixture.repairId(),
            new UpdateRepairPlanRequest(
                before.getVersion(),
                changedContent.lines(),
                changedContent.plan(),
                List.of(),
                null));

    assertThat(changed.executionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(changed.version()).isEqualTo(before.getVersion() + 1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=? and dependency_type='TASK_BOARD'
                  and operation_type='UPDATE_TASK' and state='PENDING'
                """,
                Integer.class,
                fixture.repairId()))
        .isOne();
    verify(dependencies)
        .preflightMaintenanceRouting(eq(fixture.warehouseId()), anyList());
    verify(dependencies).productionReady();
    verifyNoMoreInteractions(dependencies);

    when(dependencies.updatePreStartTask(
            any(),
            eq(fixture.externalTaskId()),
            eq(taskBoardVersion),
            eq("БТ-42"),
            anyList()))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(),
                taskBoardVersion + 1,
                "ACTIVE",
                List.of(
                    new MaintenanceDependencyGateway.TaskStageSnapshot(
                        0, registered.queueEntryId(), 0))));
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                fixture.rentalItemId(),
                before.getRentalItemVersionSnapshot(),
                fixture.warehouseId(),
                "БТ-42",
                "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select state from integration_reconciliation
                where repair_id=? and dependency_type='ASSET'
                  and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'
                """,
                String.class,
                fixture.repairId()))
        .isEqualTo("CONFIRMED");
    assertThat(service.reconcileOneTask()).isTrue();

    verify(dependencies)
        .updatePreStartTask(
            any(),
            eq(fixture.externalTaskId()),
            eq(taskBoardVersion),
            eq("БТ-42"),
            anyList());
    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(
            saved -> {
              assertThat(saved.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
              assertThat(saved.getTaskBoardVersion()).isEqualTo(taskBoardVersion + 1);
              assertThat(saved.getTaskGenerationState()).isEqualTo("GENERATED");
            });
  }

  @Test
  void emptyDirectRepairFreesTheCabinWithoutCreatingAWorkerTask() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateDirectRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 17),
                null,
                List.of(),
                List.of(),
                List.of()));

    var queued =
        service.queueRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            created.response().id(),
            new QueueRepairRequest(0L, 3));

    assertThat(queued.response().repair().executionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(queued.response().repair().plan().stages()).isEmpty();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=? and operation_type='EMPTY_REPAIR_TO_FREE' and state='PENDING'
                """,
                Integer.class,
                created.response().id()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=? and dependency_type='TASK_BOARD'
                """,
                Integer.class,
                created.response().id()))
        .isZero();

    UUID leaseId = UUID.randomUUID();
    when(
            dependencies.acquireLease(
                any(),
                eq(rentalItemId),
                eq(7L),
                eq("MAINTENANCE_REPAIR"),
                eq(created.response().id().toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0,
                rentalItemId,
                "MAINTENANCE_REPAIR",
                created.response().id(),
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
                eq(created.response().id().toString()),
                eq("EMPTY_REPAIR_TO_FREE"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 8, warehouseId, "БТ-42", "FREE"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(created.response().id()).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.CANCELLED);
              assertThat(repair.getRentalItemVersionSnapshot()).isEqualTo(8);
              assertThat(repair.getReconciliationState()).isEqualTo("RECONCILED");
              assertThat(repair.getDeliveryState()).isEqualTo("DELIVERED");
            });
    verify(dependencies)
        .releaseLease(
            any(),
            eq(leaseId),
            eq(0L),
            eq(11L),
            eq("MAINTENANCE_REPAIR"),
            eq(created.response().id().toString()));
    verify(dependencies, never())
        .registerTask(
            any(),
            any(),
            any(),
            any(),
            any(),
            nullable(String.class),
            any(LocalDate.class),
            anyInt(),
            anyInt(),
            anyList());
  }

  @ParameterizedTest
  @CsvSource({
      "BOOKED, 3, 5",
      "REPAIR, 3, 5",
      "WAITING_REPAIR_CHECK, 3, 5",
      "WRITTEN_OFF, 3, 5",
      "CAPITAL_REPAIR, 4, 6",
      "WAITING_ESTIMATE_CONFIRMATION, 3, 5",
      "SALE, 3, 5",
      "USED_SALE, 3, 5",
      "RESERVED, 3, 5",
      "FREE, 3, 5",
      "WAREHOUSE, 3, 5",
      "OWN_NEEDS, 3, 5",
      "IN_TRANSFER, 3, 5"
  })
  void eligibleRentalItemRepairKeepsSelectedPriorityAndRegistersTask(
      String sourceStatus, int priority, int dailyCapacity) {
    RepairFixture repair = createDirectRepair(sourceStatus, 0);
    repairCapacitySettings.replace(
        repair.warehouseId(),
        new ReplaceRepairCapacitySettingsRequest(0L, dailyCapacity, 5));
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        repair.repairId(),
        new QueueRepairRequest(0L, priority));
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(repair.rentalItemId()))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                repair.rentalItemId(), 0, repair.warehouseId(), "БТ-42", sourceStatus),
            new MaintenanceDependencyGateway.AssetSnapshot(
                repair.rentalItemId(), 1, repair.warehouseId(), "БТ-42", "REPAIR"));
    when(dependencies.acquireLease(
        any(), eq(repair.rentalItemId()), eq(0L),
        eq("MAINTENANCE_REPAIR"), eq(repair.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId, 0, repair.rentalItemId(), "MAINTENANCE_REPAIR", repair.repairId(), 11,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(dependencies.fencedStatus(
        any(), eq(repair.rentalItemId()), eq(repair.warehouseId()), eq(0L),
        eq(leaseId), eq(11L), eq("MAINTENANCE_REPAIR"),
        eq(repair.repairId().toString()), eq("QUEUE_TO_REPAIR"), eq(false)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            repair.rentalItemId(), 1, repair.warehouseId(), "БТ-42", "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(repair.repairId()).orElseThrow())
        .satisfies(queued -> {
          assertThat(queued.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
          assertThat(queued.getPriority()).isEqualTo(priority);
          assertThat(queued.getLeaseId()).isEqualTo(leaseId);
        });

    UUID entryId = UUID.randomUUID();
    when(dependencies.registerTask(
        any(),
        eq(repair.externalTaskId()),
        eq(repair.repairId()),
        eq(repair.warehouseId()),
        eq(repair.rentalItemId()),
        eq("БТ-42"),
        any(LocalDate.class),
        eq(priority),
        eq(dailyCapacity),
        anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            repair.externalTaskId(), 0, "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, entryId, 0))));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(repair.repairId()).orElseThrow().getTaskGenerationState())
        .isEqualTo("GENERATED");
    verify(dependencies).registerTask(
        any(),
        eq(repair.externalTaskId()),
        eq(repair.repairId()),
        eq(repair.warehouseId()),
        eq(repair.rentalItemId()),
        eq("БТ-42"),
        any(LocalDate.class),
        eq(priority),
        eq(dailyCapacity),
        anyList());
  }

  @ParameterizedTest
  @CsvSource({"RENTED", "AFTER_RENT"})
  void directRepairRejectsTheTwoUnavailableRentalItemStatuses(String sourceStatus) {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, sourceStatus, 0));

    assertThatThrownBy(() -> service.createDirectRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        directRepairRequest(warehouseId, rentalItemId, LocalDate.of(2026, 7, 17), null)))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("RENTED or AFTER_RENT");
    verifyNoInteractions(dependencies);
  }

  @Test
  void workerTaskSnapshotUsesCanonicalLinesWithTimeCommentsMaterialsAndMedia() {
    RepairFixture fixture = createDirectRepair();
    UUID repairMediaId = UUID.randomUUID();
    UUID workMediaId = UUID.randomUUID();
    OffsetDateTime capturedAt = OffsetDateTime.parse("2026-07-23T10:15:30+03:00");
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      service.applyInboundMediaFact(
          repairMediaId,
          1,
          "MAINTENANCE_REPAIR",
          fixture.repairId(),
          fixture.warehouseId(),
          "READY",
          "{\"contentType\":\"image/jpeg\",\"capturedAt\":\"2026-07-23T10:15:30+03:00\"}",
          1);
      service.applyInboundMediaFact(
          workMediaId,
          2,
          "MAINTENANCE_REPAIR",
          fixture.repairId(),
          fixture.warehouseId(),
          "READY",
          "{\"contentType\":\"image/png\"}",
          2);
    });
    CreateDirectRepairRequest content = workerTaskContent(
        fixture.warehouseId(),
        fixture.rentalItemId(),
        workMediaId);
    service.updateRepairPlan(
        fixture.repairId(),
        new UpdateRepairPlanRequest(
            0L,
            content.lines(),
            content.plan(),
            List.of(new MediaReferenceInput(repairMediaId, 1L)),
            repairMediaId));
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(1L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    UUID entryId = UUID.randomUUID();
    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaintenanceDependencyGateway.TaskStage>> stages =
        ArgumentCaptor.forClass(List.class);
    when(dependencies.registerTask(
        any(),
        eq(fixture.externalTaskId()),
        eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()),
        eq("БТ-42"),
        any(LocalDate.class),
        anyInt(),
        eq(6),
        stages.capture()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(),
            0,
            "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, entryId, 0))));

    assertThat(service.reconcileOneTask()).isTrue();

    MaintenanceDependencyGateway.TaskStage snapshot = stages.getValue().getFirst();
    assertThat(snapshot.plannedDurationMinutes()).isEqualTo(38);
    assertThat(snapshot.works())
        .singleElement()
        .satisfies(
            work -> {
              assertThat(work.id()).isNotNull();
              assertThat(work.name()).isEqualTo("Замена профлиста");
              assertThat(work.quantity()).isEqualTo(2.5);
              assertThat(work.unit()).isEqualTo("шт");
              assertThat(work.durationMinutes()).isEqualTo(15);
              assertThat(work.comment()).isEqualTo("Проверить внешний угол");
              assertThat(work.sourceMediaIds()).containsExactly(workMediaId);
            });
    assertThat(snapshot.materials())
        .singleElement()
        .satisfies(
            material -> {
              assertThat(material.id()).isNotNull();
              assertThat(material.name()).isEqualTo("Профлист");
              assertThat(material.quantity()).isEqualTo(3.0);
              assertThat(material.unit()).isEqualTo("лист");
            });
    assertThat(snapshot.comments())
        .extracting(MaintenanceDependencyGateway.TaskComment::text)
        .containsExactly("Проверить внешний угол", "Срочно");
    assertThat(snapshot.sourceMedia())
        .extracting(MaintenanceDependencyGateway.TaskSourceMedia::mediaId)
        .containsExactlyInAnyOrder(repairMediaId, workMediaId);
    assertThat(snapshot.sourceMedia())
        .filteredOn(media -> media.mediaId().equals(repairMediaId))
        .singleElement()
        .satisfies(
            media -> {
              assertThat(media.contentType()).isEqualTo("image/jpeg");
              assertThat(media.capturedAt()).isEqualTo(capturedAt);
            });
  }

  @Test
  void customRepairWorkUsesItsExplicitQueueAndPublishesWorkerContent() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    ensureTestCatalogWork(warehouseId);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    RoutingSnapshot routing =
        new RoutingSnapshot(UUID.randomUUID(), "CUSTOM_EXTERNAL", "REPAIR");
    UUID lineId = UUID.randomUUID();
    EstimateLineInput line =
        new EstimateLineInput(
            lineId,
            null,
            EstimateLineType.WORK,
            "Нестандартная герметизация",
            "пог. м",
            "2",
            "0.00",
            17,
            "Проверить шов по всей длине",
            List.of());
    PlanStageInput stage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            routing,
            List.of(lineId),
            lineId,
            "",
            null);

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateDirectRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 26),
                null,
                List.of(line),
                List.of(stage),
                List.of()));

    assertThat(created.response().plan().stages())
        .singleElement()
        .satisfies(
            saved -> {
              assertThat(saved.routing()).isEqualTo(routing);
              assertThat(saved.workLines())
                  .singleElement()
                  .satisfies(
                      work -> {
                        assertThat(work.catalogSnapshot()).isNull();
                        assertThat(work.lineType()).isEqualTo(EstimateLineType.WORK);
                        assertThat(work.description())
                            .isEqualTo("Нестандартная герметизация");
                        assertThat(work.unit()).isEqualTo("пог. м");
                        assertThat(work.quantity()).isEqualTo("2");
                        assertThat(work.normativeMinutes()).isEqualTo(17);
                        assertThat(work.comment())
                            .isEqualTo("Проверить шов по всей длине");
                      });
            });

    RepairFixture fixture =
        new RepairFixture(
            created.response().id(),
            created.response().plan().stages().getFirst().taskSync().externalTaskId(),
            warehouseId,
            rentalItemId);
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaintenanceDependencyGateway.TaskStage>> stages =
        ArgumentCaptor.forClass(List.class);
    when(dependencies.registerTask(
            any(),
            eq(fixture.externalTaskId()),
            eq(fixture.repairId()),
            eq(warehouseId),
            eq(rentalItemId),
            eq("БТ-42"),
            any(LocalDate.class),
            anyInt(),
            eq(6),
            stages.capture()))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(),
                0,
                "ACTIVE",
                List.of(
                    new MaintenanceDependencyGateway.TaskStageSnapshot(
                        0, UUID.randomUUID(), 0))));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(stages.getValue())
        .singleElement()
        .satisfies(
            published -> {
              assertThat(published.plannedDurationMinutes()).isEqualTo(34);
              assertThat(published.works())
                  .singleElement()
                  .satisfies(
                      work -> {
                        assertThat(work.id()).isNotNull();
                        assertThat(work.name())
                            .isEqualTo("Нестандартная герметизация");
                        assertThat(work.quantity()).isEqualTo(2.0);
                        assertThat(work.unit()).isEqualTo("пог. м");
                        assertThat(work.durationMinutes()).isEqualTo(17);
                        assertThat(work.comment())
                            .isEqualTo("Проверить шов по всей длине");
                      });
            });
  }

  @Test
  void customMaterialStaysMaterialUsesItsOwnUnitAndOnlyPreflightsTheStageQueue() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    CreateDirectRepairRequest base = directRepairRequest(
        warehouseId, rentalItemId, LocalDate.of(2026, 7, 26), null);
    EstimateLineInput catalogWork = base.lines().getFirst();
    PlanStageInput baseStage = base.plan().getFirst();
    UUID materialLineId = UUID.randomUUID();
    EstimateLineInput customMaterial = new EstimateLineInput(
        materialLineId,
        null,
        EstimateLineType.MATERIAL,
        "Герметик",
        "туба",
        "3",
        "100.00",
        120,
        "Нанести после очистки",
        List.of());
    PlanStageInput combinedStage = new PlanStageInput(
        baseStage.id(),
        RepairStageKind.REPAIR_WORK,
        0,
        baseStage.routing(),
        List.of(catalogWork.id(), materialLineId),
        catalogWork.id(),
        "",
        null);
    CreateDirectRepairRequest combined = new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 7, 26),
        null,
        List.of(catalogWork, customMaterial),
        List.of(combinedStage),
        List.of());

    PlanStageInput materialOnlyStage = new PlanStageInput(
        UUID.randomUUID(),
        RepairStageKind.REPAIR_WORK,
        0,
        baseStage.routing(),
        List.of(materialLineId),
        null,
        "",
        null);
    var materialOnly =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateDirectRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 26),
                null,
                List.of(customMaterial),
                List.of(materialOnlyStage),
                List.of()));
    assertThat(materialOnly.response().plan().stages().getFirst().workLines()).isEmpty();
    assertThat(materialOnly.response().plan().stages().getFirst().materialLines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.lineType()).isEqualTo(EstimateLineType.MATERIAL);
              assertThat(line.unit()).isEqualTo("туба");
              assertThat(line.normativeMinutes()).isZero();
            });

    clearInvocations(dependencies);
    var created = service.createDirectRepair(UUID.randomUUID(), UUID.randomUUID(), combined);
    RepairStageResponse stage = service.repair(created.response().id()).plan().stages().getFirst();
    assertThat(stage.workLines()).singleElement().satisfies(
        line -> assertThat(line.lineType()).isEqualTo(EstimateLineType.WORK));
    assertThat(stage.materialLines()).singleElement().satisfies(
        line -> {
          assertThat(line.catalogSnapshot()).isNull();
          assertThat(line.lineType()).isEqualTo(EstimateLineType.MATERIAL);
          assertThat(line.unit()).isEqualTo("туба");
          assertThat(line.normativeMinutes()).isZero();
        });
    verify(dependencies)
        .preflightMaintenanceRouting(
            eq(warehouseId),
            argThat(
                requirements ->
                    requirements.size() == 1
                        && requirements.getFirst().queueDefinitionId()
                            .equals(baseStage.routing().queueId())
                        && requirements.getFirst().type()
                            .equals(baseStage.routing().queueType())));

    RepairFixture fixture = new RepairFixture(
        created.response().id(),
        stage.taskSync().externalTaskId(),
        warehouseId,
        rentalItemId);
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaintenanceDependencyGateway.TaskStage>> stages =
        ArgumentCaptor.forClass(List.class);
    when(dependencies.registerTask(
            any(),
            eq(fixture.externalTaskId()),
            eq(fixture.repairId()),
            eq(warehouseId),
            eq(rentalItemId),
            eq("БТ-42"),
            any(LocalDate.class),
            anyInt(),
            eq(6),
            stages.capture()))
        .thenReturn(
            new MaintenanceDependencyGateway.TaskSnapshot(
                fixture.externalTaskId(),
                0,
                "ACTIVE",
                List.of(
                    new MaintenanceDependencyGateway.TaskStageSnapshot(
                        0, UUID.randomUUID(), 0))));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(stages.getValue()).singleElement().satisfies(
        published -> {
          assertThat(published.title()).contains("Материалы: Герметик — 3 туба");
          assertThat(published.materials()).singleElement().satisfies(
              material -> {
                assertThat(material.id()).isNotNull();
                assertThat(material.unit()).isEqualTo("туба");
              });
        });
  }

  @Test
  void estimatePersistsCustomWorkRoutingAndRejectsMovementQueues() {
    UUID warehouseId = UUID.randomUUID();
    ensureTestCatalogWork(warehouseId);
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "AFTER_RENT", 7));
    UUID lineId = UUID.randomUUID();
    EstimateLineInput customLine =
        new EstimateLineInput(
            lineId,
            null,
            EstimateLineType.WORK,
            "Локальная доработка",
            "час",
            "1",
            "0.00",
            25,
            "Комментарий мастера",
            List.of());
    RoutingSnapshot holding =
        new RoutingSnapshot(UUID.randomUUID(), "CUSTOM_HOLDING", "HOLDING");
    PlanStageInput customStage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            holding,
            List.of(lineId),
            lineId,
            "",
            null);

    EstimateLineInput missingUnit = new EstimateLineInput(
        lineId,
        null,
        EstimateLineType.WORK,
        "Локальная доработка",
        " ",
        "1",
        "0.00",
        25,
        "Комментарий мастера",
        List.of());
    assertThatThrownBy(
            () ->
                service.createEstimate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new CreateEstimateRequest(
                        warehouseId,
                        rentalItemId,
                        LocalDate.of(2026, 7, 26),
                        null,
                        List.of(missingUnit),
                        List.of(customStage),
                        List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("unit");

    EstimateLineInput missingTime =
        new EstimateLineInput(
            lineId,
            null,
            EstimateLineType.WORK,
            "Локальная доработка",
            "час",
            "1",
            "0.00",
            null,
            "Комментарий мастера",
            List.of());
    assertThatThrownBy(
            () ->
                service.createEstimate(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new CreateEstimateRequest(
                        warehouseId,
                        rentalItemId,
                        LocalDate.of(2026, 7, 26),
                        null,
                        List.of(missingTime),
                        List.of(customStage),
                        List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("execution time");

    clearInvocations(dependencies);
    var created =
        service.createEstimate(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateEstimateRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 26),
                null,
                List.of(customLine),
                List.of(customStage),
                List.of()));

    assertThat(created.response().revisions().getFirst().lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.catalogSnapshot()).isNull();
              assertThat(line.lineType()).isEqualTo(EstimateLineType.WORK);
              assertThat(line.unit()).isEqualTo("час");
              assertThat(line.normativeMinutes()).isEqualTo(25);
            });
    assertThat(jdbc.queryForObject(
        "select unit from estimate_line where estimate_id=? and line_id=?",
        String.class,
        created.response().id(),
        lineId)).isEqualTo("час");
    assertThat(created.response().revisions().getFirst().plan())
        .singleElement()
        .satisfies(saved -> assertThat(saved.routing()).isEqualTo(holding));
    verify(dependencies)
        .preflightMaintenanceRouting(
            eq(warehouseId),
            argThat(
                requirements ->
                    requirements.size() == 1
                        && requirements.getFirst().queueDefinitionId().equals(holding.queueId())
                        && requirements.getFirst().type().equals("HOLDING")));

    RoutingSnapshot movement =
        new RoutingSnapshot(UUID.randomUUID(), "MOVEMENT", "MOVEMENT");
    PlanStageInput invalidStage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            movement,
            List.of(lineId),
            lineId,
            "",
            null);
    assertThatThrownBy(
            () ->
                service.updateEstimate(
                    created.response().id(),
                    new UpdateEstimateRequest(
                        created.response().version(),
                        LocalDate.of(2026, 7, 26),
                        null,
                        List.of(customLine),
                        List.of(invalidStage),
                        List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("repair or holding queue");
  }

  @Test
  void firstPrimaryRepairAdoptsAnExistingRepairStatusWithoutALifecycleOwner() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "REPAIR", 7));
    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            directRepairRequest(
                warehouseId,
                rentalItemId,
                LocalDate.of(2026, 7, 17),
                null));
    RepairFixture repair =
        new RepairFixture(
            created.response().id(),
            created.response().plan().stages().getFirst().taskSync().externalTaskId(),
            warehouseId,
            rentalItemId);
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), repair.repairId(), new QueueRepairRequest(0L, 5));
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 7, warehouseId, "БТ-42", "REPAIR"));
    when(dependencies.acquireLease(
            any(),
            eq(rentalItemId),
            eq(7L),
            eq("MAINTENANCE_REPAIR"),
            eq(repair.repairId().toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0,
                rentalItemId,
                "MAINTENANCE_REPAIR",
                repair.repairId(),
                11,
                OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(dependencies.fencedStatus(
            any(),
            eq(rentalItemId),
            eq(warehouseId),
            eq(7L),
            eq(leaseId),
            eq(11L),
            eq("MAINTENANCE_REPAIR"),
            eq(repair.repairId().toString()),
            eq("QUEUE_TO_REPAIR"),
            eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 7, warehouseId, "БТ-42", "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(repairs.findById(repair.repairId()).orElseThrow())
        .satisfies(
            queued -> {
              assertThat(queued.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
              assertThat(queued.getPriority()).isEqualTo(5);
              assertThat(queued.getRentalItemVersionSnapshot()).isEqualTo(7);
              assertThat(queued.getLeaseId()).isEqualTo(leaseId);
              assertThat(queued.getLeaseReconciliationState()).isEqualTo("ACTIVE");
            });
    verify(dependencies)
        .fencedStatus(
            any(),
            eq(rentalItemId),
            eq(warehouseId),
            eq(7L),
            eq(leaseId),
            eq(11L),
            eq("MAINTENANCE_REPAIR"),
            eq(repair.repairId().toString()),
            eq("QUEUE_TO_REPAIR"),
            eq(false));
  }

  @Test
  void secondPrimaryRepairQueuesAndRegistersWithoutTakingOverTheFirstLease() {
    RepairFixture first = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), first.repairId(), new VersionCommand(0L));
    stubQueueDependencies(first);
    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(first);
    var firstBefore = repairs.findById(first.repairId()).orElseThrow();
    UUID firstLeaseId = firstBefore.getLeaseId();
    long firstLeaseVersion = firstBefore.getLeaseVersion();
    long firstFencingToken = firstBefore.getFencingToken();
    OffsetDateTime firstLeaseExpiresAt = firstBefore.getLeaseExpiresAt();
    clearInvocations(dependencies);

    var second =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            directRepairRequest(
                first.warehouseId(),
                first.rentalItemId(),
                LocalDate.of(2026, 7, 18),
                null));
    RepairFixture secondFixture = new RepairFixture(
        second.response().id(),
        second.response().plan().stages().getFirst().taskSync().externalTaskId(),
        first.warehouseId(),
        first.rentalItemId());
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), secondFixture.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(first.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            first.rentalItemId(), 8, first.warehouseId(), "БТ-42", "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    var secondQueued = repairs.findById(secondFixture.repairId()).orElseThrow();
    assertThat(secondQueued.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(secondQueued.getRentalItemVersionSnapshot()).isEqualTo(8);
    assertThat(secondQueued.getLeaseId()).isNull();
    assertThat(secondQueued.getLeaseVersion()).isNull();
    assertThat(secondQueued.getFencingToken()).isNull();
    assertThat(secondQueued.getLeaseExpiresAt()).isNull();
    assertThat(secondQueued.getLeaseReconciliationState()).isEqualTo("NOT_REQUIRED");
    assertThat(jdbc.queryForObject("""
        select count(*) from repair_stage
        where repair_id=? and state='QUEUED'
        """, Integer.class, secondFixture.repairId())).isOne();

    registerQueuedRepair(secondFixture);
    assertThat(repairs.findById(secondFixture.repairId()).orElseThrow().getTaskGenerationState())
        .isEqualTo("GENERATED");
    assertThat(repairs.findById(first.repairId()).orElseThrow())
        .satisfies(owner -> {
          assertThat(owner.getLeaseId()).isEqualTo(firstLeaseId);
          assertThat(owner.getLeaseVersion()).isEqualTo(firstLeaseVersion);
          assertThat(owner.getFencingToken()).isEqualTo(firstFencingToken);
          assertThat(owner.getLeaseExpiresAt()).isEqualTo(firstLeaseExpiresAt);
          assertThat(owner.getLeaseReconciliationState()).isEqualTo("ACTIVE");
        });

    verify(dependencies, times(2)).getRentalItemSnapshot(first.rentalItemId());
    verify(dependencies, never()).acquireLease(
        any(), eq(first.rentalItemId()), anyLong(),
        anyString(), eq(secondFixture.repairId().toString()));
    verify(dependencies, never()).renewLease(
        any(), any(), anyLong(), anyLong(),
        anyString(), eq(secondFixture.repairId().toString()));
    verify(dependencies, never()).fencedStatus(
        any(), eq(first.rentalItemId()), eq(first.warehouseId()), anyLong(),
        any(), anyLong(), anyString(), eq(secondFixture.repairId().toString()),
        anyString(), anyBoolean());
    verify(dependencies, never()).releaseLease(
        any(), any(), anyLong(), anyLong(),
        anyString(), eq(secondFixture.repairId().toString()));
    verify(dependencies).registerTask(
        any(), eq(secondFixture.externalTaskId()), eq(secondFixture.repairId()),
        eq(first.warehouseId()),
        eq(first.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList());
  }

  @Test
  void repeatedSecondRepairQueueCommandIsIdempotent() {
    RepairFixture first = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), first.repairId(), new VersionCommand(0L));
    stubQueueDependencies(first);
    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(first);
    clearInvocations(dependencies);

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            directRepairRequest(
                first.warehouseId(),
                first.rentalItemId(),
                LocalDate.of(2026, 7, 18),
                null));
    RepairFixture second = new RepairFixture(
        created.response().id(),
        created.response().plan().stages().getFirst().taskSync().externalTaskId(),
        first.warehouseId(),
        first.rentalItemId());
    UUID subjectId = UUID.randomUUID();
    UUID key = UUID.randomUUID();

    assertThat(service.queueRepair(
        subjectId, key, second.repairId(), new VersionCommand(0L)).replayed()).isFalse();
    assertThat(service.queueRepair(
        subjectId, key, second.repairId(), new VersionCommand(0L)).replayed()).isTrue();
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, second.repairId())).isOne();

    when(dependencies.getRentalItemSnapshot(first.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            first.rentalItemId(), 8, first.warehouseId(), "БТ-42", "REPAIR"));
    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(second);

    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where repair_id=? and operation_type='REGISTER_TASK'
        """, Integer.class, second.repairId())).isOne();
    verify(dependencies, times(1)).registerTask(
        any(), eq(second.externalTaskId()), eq(second.repairId()), eq(first.warehouseId()),
        eq(first.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList());
    verify(dependencies, never()).acquireLease(
        any(), eq(first.rentalItemId()), anyLong(),
        anyString(), eq(second.repairId().toString()));
    verify(dependencies, never()).fencedStatus(
        any(), eq(first.rentalItemId()), eq(first.warehouseId()), anyLong(),
        any(), anyLong(), anyString(), eq(second.repairId().toString()),
        anyString(), anyBoolean());
  }

  @Test
  void reconciliationRequiredLifecycleOwnerQueuesSecondRepairWithoutLeaseCalls() {
    RepairFixture first = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), first.repairId(), new VersionCommand(0L));
    stubQueueDependencies(first);
    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(first);
    jdbc.update("""
        update maintenance_repair
        set lease_reconciliation_state='RECONCILIATION_REQUIRED'
        where id=?
        """, first.repairId());
    clearInvocations(dependencies);

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            directRepairRequest(
                first.warehouseId(),
                first.rentalItemId(),
                LocalDate.of(2026, 7, 18),
                null));
    RepairFixture second = new RepairFixture(
        created.response().id(),
        created.response().plan().stages().getFirst().taskSync().externalTaskId(),
        first.warehouseId(),
        first.rentalItemId());
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), second.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(first.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            first.rentalItemId(), 8, first.warehouseId(), "БТ-42", "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(second);

    assertThat(repairs.findById(second.repairId()).orElseThrow())
        .satisfies(repair -> {
          assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
          assertThat(repair.getLeaseId()).isNull();
          assertThat(repair.getLeaseReconciliationState()).isEqualTo("NOT_REQUIRED");
          assertThat(repair.getTaskGenerationState()).isEqualTo("GENERATED");
        });
    assertThat(repairs.findById(first.repairId()).orElseThrow().getLeaseReconciliationState())
        .isEqualTo("RECONCILIATION_REQUIRED");
    verify(dependencies, times(2)).getRentalItemSnapshot(first.rentalItemId());
    verify(dependencies, never()).acquireLease(
        any(), eq(first.rentalItemId()), anyLong(),
        anyString(), eq(second.repairId().toString()));
    verify(dependencies, never()).renewLease(
        any(), any(), anyLong(), anyLong(),
        anyString(), eq(second.repairId().toString()));
    verify(dependencies, never()).fencedStatus(
        any(), eq(first.rentalItemId()), eq(first.warehouseId()), anyLong(),
        any(), anyLong(), anyString(), eq(second.repairId().toString()),
        anyString(), anyBoolean());
    verify(dependencies, never()).releaseLease(
        any(), any(), anyLong(), anyLong(),
        anyString(), eq(second.repairId().toString()));
  }

  @Test
  void authenticatedRetryQueuesAQuarantinedSecondRepairWithoutLeaseCalls() {
    RepairFixture first = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), first.repairId(), new VersionCommand(0L));
    stubQueueDependencies(first);
    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(first);
    jdbc.update("""
        update maintenance_repair
        set lease_reconciliation_state='RELEASED'
        where id=?
        """, first.repairId());
    clearInvocations(dependencies);

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            directRepairRequest(
                first.warehouseId(),
                first.rentalItemId(),
                LocalDate.of(2026, 7, 18),
                null));
    RepairFixture second = new RepairFixture(
        created.response().id(),
        created.response().plan().stages().getFirst().taskSync().externalTaskId(),
        first.warehouseId(),
        first.rentalItemId());
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), second.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(first.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            first.rentalItemId(), 8, first.warehouseId(), "БТ-42", "REPAIR"));

    for (int attempt = 0; attempt < 4; attempt++) {
      if (attempt > 0) makeReconciliationDue(second.repairId(), "QUEUE_REPAIR");
      assertThat(service.reconcileOneTask()).isTrue();
    }
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, String.class, second.repairId())).isEqualTo("QUARANTINED");

    jdbc.update("""
        update maintenance_repair
        set lease_reconciliation_state='RECONCILIATION_REQUIRED'
        where id=?
        """, first.repairId());
    UUID reviewer = UUID.randomUUID();
    long currentVersion = service.repair(second.repairId(), second.warehouseId()).version();
    service.queueRepair(
        reviewer,
        UUID.randomUUID(),
        second.repairId(),
        new QueueRepairRequest(currentVersion, 3));

    assertThat(jdbc.queryForMap("""
        select state,attempt_count,review_version,review_subject_id,review_reason
        from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, second.repairId()))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("review_version", 1L)
        .containsEntry("review_subject_id", reviewer)
        .containsEntry(
            "review_reason",
            "Authenticated repair queue retry after canonical asset revalidation");

    assertThat(service.reconcileOneTask()).isTrue();
    registerQueuedRepair(second);
    assertThat(repairs.findById(second.repairId()).orElseThrow())
        .satisfies(repair -> {
          assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
          assertThat(repair.getLeaseId()).isNull();
          assertThat(repair.getLeaseReconciliationState()).isEqualTo("NOT_REQUIRED");
          assertThat(repair.getTaskGenerationState()).isEqualTo("GENERATED");
        });
    verify(dependencies, never()).acquireLease(
        any(), eq(first.rentalItemId()), anyLong(),
        anyString(), eq(second.repairId().toString()));
    verify(dependencies, never()).renewLease(
        any(), any(), anyLong(), anyLong(),
        anyString(), eq(second.repairId().toString()));
    verify(dependencies, never()).fencedStatus(
        any(), eq(first.rentalItemId()), eq(first.warehouseId()), anyLong(),
        any(), anyLong(), anyString(), eq(second.repairId().toString()),
        anyString(), anyBoolean());
    verify(dependencies, never()).releaseLease(
        any(), any(), anyLong(), anyLong(),
        anyString(), eq(second.repairId().toString()));
  }

  @Test
  void queueRepairUsesTheCanonicalAssetVersionWhenTheMaintenanceSnapshotIsStale() {
    RepairFixture fixture = createDirectRepair(1);
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 2, fixture.warehouseId(), "FREE"));
    when(dependencies.acquireLease(
        any(), eq(fixture.rentalItemId()), eq(2L),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId, 0, fixture.rentalItemId(), "MAINTENANCE_REPAIR", fixture.repairId(), 31,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(dependencies.fencedStatus(
        any(), eq(fixture.rentalItemId()), eq(fixture.warehouseId()), eq(2L),
        eq(leaseId), eq(31L), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString()), eq("QUEUE_TO_REPAIR"), eq(false)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 3, fixture.warehouseId(), "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();

    var repair = repairs.findById(fixture.repairId()).orElseThrow();
    assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(repair.getRentalItemVersionSnapshot()).isEqualTo(3);
    // Asset events remain the sole writer for this projection; the command cannot lower it.
    assertThat(rentalItemFacts.findById(fixture.rentalItemId()).orElseThrow())
        .satisfies(projection -> {
          assertThat(projection.getAggregateVersion()).isOne();
          assertThat(projection.getAssetStatus()).isEqualTo("FREE");
        });
    var ordered = inOrder(dependencies);
    ordered.verify(dependencies).getRentalItemSnapshot(fixture.rentalItemId());
    ordered.verify(dependencies).acquireLease(
        any(), eq(fixture.rentalItemId()), eq(2L),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString()));
    ordered.verify(dependencies).fencedStatus(
        any(), eq(fixture.rentalItemId()), eq(fixture.warehouseId()), eq(2L),
        eq(leaseId), eq(31L), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString()), eq("QUEUE_TO_REPAIR"), eq(false));
  }

  @Test
  void estimateFurnitureUsesCanonicalCatalogLinkInTheFencedAssetCommand() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "f".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID chairMaterialId = UUID.randomUUID();
    UUID tableMaterialId = UUID.randomUUID();
    UUID workNodeId = UUID.randomUUID();
    RoutingSnapshot repairRouting =
        new RoutingSnapshot(UUID.randomUUID(), "REPAIR", "REPAIR");
    UUID chairEquipmentId = UUID.fromString("52000000-0000-4000-8000-000000000001");
    UUID tableEquipmentId = UUID.fromString("52000000-0000-4000-8000-000000000002");
    when(dependencies.ensureFurnitureEquipment(chairMaterialId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            chairEquipmentId, "Chair"));
    when(dependencies.ensureFurnitureEquipment(tableMaterialId, "Table"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            tableEquipmentId, "Table"));
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true,
        null, null, null, null, null, false, null);
    CatalogNodeInput chair = new CatalogNodeInput(
        chairMaterialId, CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(chairEquipmentId, "Chair"),
        "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);
    CatalogNodeInput table = new CatalogNodeInput(
        tableMaterialId, CatalogNodeType.MATERIAL, "Table", true, categoryId,
        false, new FurnitureEquipmentReference(tableEquipmentId, "Table"),
        "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);
    CatalogNodeInput work = new CatalogNodeInput(
        workNodeId, CatalogNodeType.WORK, "Repair furniture", true, null,
        false, null, "piece", "100.00", 15, true, false, true,
        null, null, new CatalogRoutingInput(repairRouting.queueId(), repairRouting.queueType()),
        null, null, false, null);
    CatalogVersionResponse changed = service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(0L, List.of(category, chair, table, work), List.of()));
    service.activateCatalog(
        UUID.randomUUID(), UUID.randomUUID(), catalogId,
        new VersionCommand(changed.version()));
    reconcileCatalogRoute(repairRouting.queueId());
    clearInvocations(dependencies);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "AFTER_RENT", 7));

    CatalogNodeSnapshot forgedChairSnapshot = new CatalogNodeSnapshot(
        catalogId,
        chairMaterialId,
        CatalogNodeType.MATERIAL,
        "Forged furniture",
        "piece",
        "1.00",
        0,
        null,
        new FurnitureEquipmentReference(UUID.randomUUID(), "Forged"),
        false,
        null);
    CatalogNodeSnapshot forgedTableSnapshot = new CatalogNodeSnapshot(
        catalogId,
        tableMaterialId,
        CatalogNodeType.MATERIAL,
        "Forged table",
        "piece",
        "1.00",
        0,
        null,
        new FurnitureEquipmentReference(UUID.randomUUID(), "Forged table"),
        false,
        null);
    CatalogNodeSnapshot workSnapshot = new CatalogNodeSnapshot(
        catalogId,
        workNodeId,
        CatalogNodeType.WORK,
        "Repair furniture",
        "piece",
        "100.00",
        15,
        repairRouting,
        null,
        false,
        null);

    assertThatThrownBy(() -> service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            List.of(new EstimateLineInput(
                UUID.randomUUID(), forgedChairSnapshot, EstimateLineType.MATERIAL,
                "Replace chairs", "piece", "1.5", "100.00", 0, null, List.of())),
            List.of(stage()),
            List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("Furniture quantity must be a whole number");

    List<EstimateLineInput> lines = List.of(
        new EstimateLineInput(
            UUID.randomUUID(), forgedTableSnapshot, EstimateLineType.MATERIAL,
            "Replace table", "piece", "1", "100.00", 0, null, List.of()),
        new EstimateLineInput(
            UUID.randomUUID(), forgedChairSnapshot, EstimateLineType.MATERIAL,
            "Replace chairs", "piece", "2", "100.00", 0, null, List.of()),
        new EstimateLineInput(
            UUID.randomUUID(), forgedTableSnapshot, EstimateLineType.MATERIAL,
            "Replace more tables", "piece", "3", "100.00", 0, null, List.of()),
        new EstimateLineInput(
            UUID.randomUUID(), workSnapshot, EstimateLineType.WORK,
            "Repair furniture", "piece", "1", "100.00", 15, null, List.of()));
    PlanStageInput planStage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            repairRouting,
            lines.stream().map(EstimateLineInput::id).toList(),
            lines.getLast().id(),
            "",
            null);
    MaintenanceDependencyGateway.PropertyAssetSnapshot noRecordedCabin =
        new MaintenanceDependencyGateway.PropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            "C-1",
            warehouseId,
            7,
            "AFTER_RENT",
            null,
            null,
            List.of(),
            false,
            false,
            false,
            true);
    MaintenanceDependencyGateway.PropertyAssetSnapshot recordedCabin =
        new MaintenanceDependencyGateway.PropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            "C-1",
            warehouseId,
            7,
            "AFTER_RENT",
            null,
            null,
            List.of(
                new MaintenanceDependencyGateway.PropertyAssetContentSnapshot(
                    chairEquipmentId, "Chair", null, 3, 2),
                new MaintenanceDependencyGateway.PropertyAssetContentSnapshot(
                    tableEquipmentId, "Table", null, 5, 4)),
            false,
            false,
            false,
            true);
    var created = service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            lines,
            List.of(planStage),
            List.of()));
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            warehouseId))
        .thenReturn(noRecordedCabin);
    assertThatThrownBy(() -> service.completeEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.response().id(),
        new CompleteEstimateRequest(created.response().version(), 2)))
        .isInstanceOf(MaintenanceValidationException.class)
        .satisfies(error -> assertThat(((MaintenanceValidationException) error).code())
            .isEqualTo("MAINTENANCE_UNACCOUNTED_FURNITURE_CONFIRMATION_REQUIRED"));

    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            warehouseId))
        .thenReturn(recordedCabin);
    var completed = service.completeEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.response().id(),
        new CompleteEstimateRequest(created.response().version(), 2));
    assertThat(completed.response().repair().priority()).isEqualTo(2);
    UUID repairId = completed.response().repair().id();
    UUID estimateId = created.response().id();

    var unaccountedCreated = service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            lines,
            List.of(planStage),
            List.of()));
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            warehouseId))
        .thenReturn(noRecordedCabin);
    var unaccountedCompleted = service.completeEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        unaccountedCreated.response().id(),
        new CompleteEstimateRequest(
            unaccountedCreated.response().version(), 2, false, null, null, true));
    assertThat(
            repairs.findById(unaccountedCompleted.response().repair().id()).orElseThrow()
                .getFurnitureAccountingMode())
        .isEqualTo(FurnitureAccountingMode.UNACCOUNTED_CABIN_CONTENTS);
    assertThat(dispositions.list(
        warehouseId,
        PropertyDispositionKind.WRITE_OFF,
        PropertyDispositionState.PENDING_APPROVAL,
        0,
        20).items()).isEmpty();

    List<EstimateLineInput> changedFurniture = new ArrayList<>(lines);
    EstimateLineInput first = changedFurniture.getFirst();
    changedFurniture.set(0, new EstimateLineInput(
        first.id(), first.catalogSnapshot(), first.lineType(), first.description(), first.unit(), "2",
        first.unitPrice(), first.normativeMinutes(), first.comment(), first.mediaReferences()));
    assertThatThrownBy(() -> service.amendEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.response().id(),
        new AmendEstimateRequest(
            completed.response().estimate().version(),
            completed.response().repair().version(),
            LocalDate.of(2026, 7, 19),
            "Furniture aggregate must stay stable",
            null,
            changedFurniture,
            List.of(planStage),
            List.of())))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("Furniture quantities cannot change");

    List<EstimateLineInput> overflowingFurniture = new ArrayList<>(lines);
    EstimateLineInput firstTable = overflowingFurniture.getFirst();
    EstimateLineInput lastTable = overflowingFurniture.get(2);
    overflowingFurniture.set(0, new EstimateLineInput(
        firstTable.id(), firstTable.catalogSnapshot(), firstTable.lineType(), firstTable.description(),
        firstTable.unit(), Long.toString(Long.MAX_VALUE), firstTable.unitPrice(),
        firstTable.normativeMinutes(), firstTable.comment(), firstTable.mediaReferences()));
    overflowingFurniture.set(2, new EstimateLineInput(
        lastTable.id(), lastTable.catalogSnapshot(), lastTable.lineType(), lastTable.description(),
        lastTable.unit(), "1", lastTable.unitPrice(), lastTable.normativeMinutes(),
        lastTable.comment(), lastTable.mediaReferences()));
    assertThatThrownBy(() -> service.amendEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        estimateId,
        new AmendEstimateRequest(
            completed.response().estimate().version(),
            completed.response().repair().version(),
            LocalDate.of(2026, 7, 19),
            "Overflow must be a validation error",
            null,
            overflowingFurniture,
            List.of(planStage),
            List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("supported range");

    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 7, warehouseId, "AFTER_RENT"));
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            warehouseId))
        .thenReturn(recordedCabin);
    when(dependencies.acquireLease(
        any(), eq(rentalItemId), eq(7L), eq("MAINTENANCE_ESTIMATE"), eq(estimateId.toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId, 0, rentalItemId, "MAINTENANCE_ESTIMATE", estimateId, 17,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    List<MaintenanceDependencyGateway.FurniturePendingReturn> pendingReturns = List.of(
        new MaintenanceDependencyGateway.FurniturePendingReturn(chairEquipmentId, 3, 2),
        new MaintenanceDependencyGateway.FurniturePendingReturn(tableEquipmentId, 5, 4));
    when(dependencies.fencedStatus(
        any(), eq(rentalItemId), eq(warehouseId), eq(7L), eq(leaseId), eq(17L),
        eq("MAINTENANCE_ESTIMATE"), eq(estimateId.toString()), eq("QUEUE_TO_REPAIR"),
        eq(false), eq(pendingReturns)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 8, warehouseId, "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();

    verify(dependencies).fencedStatus(
        any(), eq(rentalItemId), eq(warehouseId), eq(7L), eq(leaseId), eq(17L),
        eq("MAINTENANCE_ESTIMATE"), eq(estimateId.toString()), eq("QUEUE_TO_REPAIR"),
        eq(false), eq(pendingReturns));
    assertThat(repairs.findById(repairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, String.class, repairId)).isEqualTo("CONFIRMED");
  }

  @Test
  void directRepairFurnitureUsesTheSameVersionFencedPendingReturnCommand() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "a".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(materialId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            equipmentId, "Chair"));
    RoutingSnapshot routing = new RoutingSnapshot(UUID.randomUUID(), "REPAIR", "REPAIR");
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true,
        null, null, null, null, null, false, null);
    CatalogNodeInput material = new CatalogNodeInput(
        materialId, CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "Chair"),
        "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);
    CatalogNodeInput work = new CatalogNodeInput(
        workId, CatalogNodeType.WORK, "Repair chair", true, null,
        false, null, "piece", "100.00", 15, true, false, true,
        null, null, new CatalogRoutingInput(routing.queueId(), routing.queueType()),
        null, null, false, null);
    CatalogVersionResponse changed = service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(0L, List.of(category, material, work), List.of()));
    service.activateCatalog(
        UUID.randomUUID(), UUID.randomUUID(), catalogId,
        new VersionCommand(changed.version()));
    reconcileCatalogRoute(routing.queueId());
    clearInvocations(dependencies);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));

    UUID materialLineId = UUID.randomUUID();
    UUID workLineId = UUID.randomUUID();
    EstimateLineInput materialLine = new EstimateLineInput(
        materialLineId,
        new CatalogNodeSnapshot(
            catalogId, materialId, CatalogNodeType.MATERIAL, "Chair", "piece", "100.00",
            0, null, new FurnitureEquipmentReference(equipmentId, "Chair"), false, null),
        EstimateLineType.MATERIAL,
        "Replace chairs",
        "piece",
        "2",
        "100.00",
        0,
        null,
        List.of());
    EstimateLineInput workLine = new EstimateLineInput(
        workLineId,
        new CatalogNodeSnapshot(
            catalogId, workId, CatalogNodeType.WORK, "Repair chair", "piece", "100.00",
            15, routing, null, false, null),
        EstimateLineType.WORK,
        "Repair chair",
        "piece",
        "1",
        "100.00",
        15,
        null,
        List.of());
    PlanStageInput plan = new PlanStageInput(
        UUID.randomUUID(),
        RepairStageKind.REPAIR_WORK,
        0,
        routing,
        List.of(materialLineId, workLineId),
        workLineId,
        "",
        null);
    RepairResponse created = service.createDirectRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateDirectRepairRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 8, 5),
            null,
            List.of(materialLine, workLine),
            List.of(plan),
            List.of()))
        .response();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), created.id(), new VersionCommand(created.version()));

    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 7, warehouseId, "FREE"));
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            warehouseId))
        .thenReturn(new MaintenanceDependencyGateway.PropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            "C-1",
            warehouseId,
            7,
            "FREE",
            null,
            null,
            List.of(new MaintenanceDependencyGateway.PropertyAssetContentSnapshot(
                equipmentId, "Chair", null, 11, 3)),
            false,
            false,
            false,
            true));
    when(dependencies.acquireLease(
            any(), eq(rentalItemId), eq(7L), eq("MAINTENANCE_REPAIR"), eq(created.id().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId,
            0,
            rentalItemId,
            "MAINTENANCE_REPAIR",
            created.id(),
            17,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    List<MaintenanceDependencyGateway.FurniturePendingReturn> pendingReturns = List.of(
        new MaintenanceDependencyGateway.FurniturePendingReturn(equipmentId, 11, 2));
    when(dependencies.fencedStatus(
            any(),
            eq(rentalItemId),
            eq(warehouseId),
            eq(7L),
            eq(leaseId),
            eq(17L),
            eq("MAINTENANCE_REPAIR"),
            eq(created.id().toString()),
            eq("QUEUE_TO_REPAIR"),
            eq(false),
            eq(pendingReturns)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 8, warehouseId, "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();

    verify(dependencies).fencedStatus(
        any(),
        eq(rentalItemId),
        eq(warehouseId),
        eq(7L),
        eq(leaseId),
        eq(17L),
        eq("MAINTENANCE_REPAIR"),
        eq(created.id().toString()),
        eq("QUEUE_TO_REPAIR"),
        eq(false),
        eq(pendingReturns));
    assertThat(repairs.findById(created.id()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
  }

  @Test
  void completedRepairCustodyCreatesOneReviewedEquipmentDecisionAndUsesTheCustodyFence() {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    var repair = repairs.findById(fixture.repairId()).orElseThrow();
    UUID custodyClaimId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim claim =
        new MaintenanceDependencyGateway.MaintenanceFurnitureCustodyClaim(
            custodyClaimId,
            4,
            "MAINTENANCE_REPAIR",
            fixture.repairId(),
            fixture.rentalItemId(),
            fixture.warehouseId(),
            equipmentId,
            UUID.randomUUID(),
            11,
            3,
            1,
            0,
            0,
            2,
            2,
            OffsetDateTime.now(ZoneOffset.UTC).minusHours(1));

    assertThat(dispositions.materializeFurnitureCustody(
            repair, Map.of(equipmentId, "Chair"), List.of(claim)))
        .isOne();
    assertThat(dispositions.materializeFurnitureCustody(
            repair, Map.of(equipmentId, "Chair"), List.of(claim)))
        .isZero();

    var pendingPage = dispositions.list(
        fixture.warehouseId(), PropertyDispositionKind.WRITE_OFF,
        PropertyDispositionState.PENDING_APPROVAL, 0, 20);
    assertThat(pendingPage.items()).singleElement().satisfies(decision -> {
      assertThat(decision.assetId()).isEqualTo(equipmentId);
      assertThat(decision.quantity()).isEqualTo(2);
      assertThat(decision.source()).isEqualTo(PropertyDispositionSource.REPAIR);
      assertThat(decision.maintenanceCustodyClaimId()).isEqualTo(custodyClaimId);
      assertThat(decision.maintenanceCustodyVersion()).isEqualTo(4);
      assertThat(decision.expectedAssetVersion()).isNull();
      assertThat(decision.expectedSourceBalanceVersion()).isNull();
      assertThat(decision.reviewedBy()).isNull();
    });
    var pending = pendingPage.items().getFirst();

    var approved = dispositions.approve(
        pending.id(),
        fixture.warehouseId(),
        new ApprovePropertyDispositionRequest(pending.version(), "approved"));
    assertThat(approved.state()).isEqualTo(PropertyDispositionState.APPROVED);
    when(dependencies.preparePropertyDisposition(
            any(),
            eq(approved.id()),
            argThat(preparation ->
                preparation.assetKind()
                    == MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT
                    && preparation.assetId().equals(equipmentId)
                    && preparation.expectedAssetVersion() == null
                    && preparation.expectedSourceBalanceVersion() == null
                    && preparation.quantity() == 2
                    && preparation.maintenanceCustodyClaimId().equals(custodyClaimId)
                    && preparation.maintenanceCustodyVersion() == 4)))
        .thenReturn(new MaintenanceDependencyGateway.PropertyDispositionFence(
            approved.id(),
            "PREPARED",
            "0".repeat(64),
            fixture.warehouseId(),
            MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT,
            equipmentId,
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            custodyClaimId,
            4L,
            List.of(),
            Instant.now(),
            null));

    dispositionProcessor.processOne();

    assertThat(dispositions.get(approved.id(), fixture.warehouseId()).state())
        .isEqualTo(PropertyDispositionState.EFFECT_PENDING);
    verify(dependencies).preparePropertyDisposition(
        any(), eq(approved.id()), any(MaintenanceDependencyGateway.PropertyDispositionPreparation.class));
    verify(dependencies, never()).applyPropertyDisposition(any(), any(), any());

    jdbc.update(
        """
        update property_disposition_processing_claim
           set status='QUARANTINED',failure_count=4,claim_token=null,lease_owner=null,lease_until=null
         where decision_id=?
        """,
        approved.id());
    var effectPending = dispositions.get(approved.id(), fixture.warehouseId());
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT,
            equipmentId,
            fixture.warehouseId()))
        .thenReturn(new MaintenanceDependencyGateway.PropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.EQUIPMENT,
            equipmentId,
            "Chair",
            fixture.warehouseId(),
            5,
            null,
            0L,
            0L,
            List.of(),
            false,
            false,
            false,
            true));

    var recovered = dispositions.recover(
        approved.id(),
        fixture.warehouseId(),
        new RecoverPropertyDispositionRequest(
            effectPending.version(), 0L, "Reviewed processor recovery"));

    assertThat(recovered.state()).isEqualTo(PropertyDispositionState.EFFECT_PENDING);
    assertThat(recovered.recoveryVersion()).isOne();
    assertThat(jdbc.queryForMap(
            """
            select status,failure_count from property_disposition_processing_claim
             where decision_id=?
            """,
            approved.id()))
        .containsEntry("status", "PENDING")
        .containsEntry("failure_count", 0);
  }

  @Test
  void estimateRejectsUnlinkedFurnitureFromAnAlreadyActiveCatalog() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "e".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(materialId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            equipmentId, "Chair"));
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true,
        null, null, null, null, null, false, null);
    CatalogNodeInput material = new CatalogNodeInput(
        materialId, CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, null, "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);
    service.changeCatalog(
        catalogId, new ChangeCatalogRequest(0L, List.of(category, material), List.of()));
    jdbc.update("""
        update catalog_node
        set furniture_equipment_id=null,
            furniture_equipment_name=null
        where catalog_version_id=? and node_id=?
        """, catalogId, materialId);
    jdbc.update("update catalog_version set state='ACTIVE' where id=?", catalogId);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "AFTER_RENT", 7));
    CatalogNodeSnapshot submitted = new CatalogNodeSnapshot(
        catalogId, materialId, CatalogNodeType.MATERIAL, "Chair", "piece",
        "100.00", 0, null, null, false, null);

    assertThatThrownBy(() -> service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            List.of(new EstimateLineInput(
                UUID.randomUUID(), submitted, EstimateLineType.MATERIAL, "Replace chair", "piece",
                "1", "100.00", 0, null, List.of())),
            List.of(stage()),
            List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("Furniture material must be linked");
  }

  @Test
  void furnitureAutoLinkCommitsEveryIntentBeforeRemoteAndRetriesOnlyThePartialFailure() {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "a".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID chairNodeId = UUID.randomUUID();
    UUID tableNodeId = UUID.randomUUID();
    UUID chairEquipmentId = UUID.randomUUID();
    UUID tableEquipmentId = UUID.randomUUID();
    List<CatalogNodeInput> nodes = List.of(
        furnitureCategory(categoryId),
        furnitureMaterial(chairNodeId, categoryId, "Chair", null),
        furnitureMaterial(tableNodeId, categoryId, "Table", null));
    AtomicInteger tableAttempts = new AtomicInteger();
    when(dependencies.ensureFurnitureEquipment(chairNodeId, "Chair"))
        .thenAnswer(invocation -> {
          assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
          assertThat(jdbc.queryForObject(
              "select count(*) from furniture_equipment_link_intent where node_id in (?,?)",
              Integer.class,
              chairNodeId,
              tableNodeId)).isEqualTo(2);
          return new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
              chairEquipmentId, "Chair");
        });
    when(dependencies.ensureFurnitureEquipment(tableNodeId, "Table"))
        .thenAnswer(invocation -> {
          if (tableAttempts.incrementAndGet() == 1) {
            throw new MaintenanceDependencyException(
                org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
                "asset temporarily unavailable");
          }
          return new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
              tableEquipmentId, "Table");
        });
    when(dependencies.furnitureEquipmentSnapshots(List.of(chairNodeId, tableNodeId)))
        .thenReturn(List.of(
            new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
                chairNodeId, chairEquipmentId, "Chair", 0, null),
            new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
                tableNodeId, tableEquipmentId, "Table", 0, null)));

    ChangeCatalogRequest exactRequest =
        new ChangeCatalogRequest(0L, nodes, List.of());
    assertThatThrownBy(() -> service.changeCatalog(catalogId, exactRequest))
        .isInstanceOf(MaintenanceDependencyException.class);

    assertThat(jdbc.queryForMap(
            "select state,equipment_id from furniture_equipment_link_intent where node_id=?",
            chairNodeId))
        .containsEntry("state", "CONFIRMED")
        .containsEntry("equipment_id", chairEquipmentId);
    assertThat(jdbc.queryForMap(
            "select state,attempt_count from furniture_equipment_link_intent where node_id=?",
            tableNodeId))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("attempt_count", 1);
    assertThat(service.catalogVersion(catalogId).version()).isZero();

    var changed = service.changeCatalog(catalogId, exactRequest);
    assertThat(changed.version()).isOne();
    assertThat(service.catalogNodes(catalogId))
        .filteredOn(node -> node.nodeType() == CatalogNodeType.MATERIAL)
        .extracting(node -> node.furnitureEquipment().equipmentId())
        .containsExactlyInAnyOrder(chairEquipmentId, tableEquipmentId);
    verify(dependencies, times(1)).ensureFurnitureEquipment(chairNodeId, "Chair");
    verify(dependencies, times(2)).ensureFurnitureEquipment(tableNodeId, "Table");
    verify(dependencies).furnitureEquipmentSnapshots(List.of(chairNodeId, tableNodeId));

    // A late replay after generic request replay retention uses the durable confirmed mapping.
    var lateReplay = service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(changed.version(), nodes, List.of()));
    assertThat(lateReplay.version()).isEqualTo(changed.version());
    verifyNoMoreInteractions(dependencies);
  }

  @Test
  void recoveredLegacyFurnitureIntentMakesCatalogSettingsReadableAfterAssetReturnsSameBinding() {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "f".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID legacyEquipmentId = UUID.randomUUID();
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,furniture_equipment_id,furniture_equipment_name,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu)
        values (?,?,?,'CATEGORY','Furniture',true,null,false,null,null,null,null,0,false,false,false),
               (?,?,?,'MATERIAL','Chair',true,?,false,?,?,'piece',10000,0,true,false,false)
        """,
        UUID.randomUUID(),
        categoryId,
        catalogId,
        UUID.randomUUID(),
        materialId,
        catalogId,
        categoryId,
        legacyEquipmentId,
        "Chair");
    jdbc.update("update catalog_version set node_count=2 where id=?", catalogId);
    jdbc.update("""
        insert into furniture_equipment_link_intent(
          node_id,warehouse_id,source_catalog_version_id,source_catalog_expected_version,
          requested_name,requested_equipment_version,requested_maximum_per_cabin,state,
          attempt_count,next_attempt_at,review_version,created_at,updated_at)
        values (?,?,?,?,?,null,null,'PENDING',0,clock_timestamp(),0,clock_timestamp(),clock_timestamp())
        """,
        materialId,
        warehouseId,
        catalogId,
        0L,
        "Chair");
    when(dependencies.ensureFurnitureEquipment(materialId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            legacyEquipmentId, "Chair"));
    when(dependencies.furnitureEquipmentSnapshots(List.of(materialId)))
        .thenReturn(List.of(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            materialId, legacyEquipmentId, "Chair", 9, 4)));

    assertThat(furnitureEquipmentLinkProcessor.processExact(materialId)).isTrue();

    assertThat(service.catalogNodes(catalogId))
        .filteredOn(node -> node.id().equals(materialId))
        .singleElement()
        .satisfies(node -> {
          assertThat(node.furnitureEquipment().equipmentId()).isEqualTo(legacyEquipmentId);
          assertThat(node.furnitureEquipment().equipmentVersion()).isEqualTo(9);
          assertThat(node.furnitureEquipment().maximumPerCabin()).isEqualTo(4);
        });
    verify(dependencies).ensureFurnitureEquipment(materialId, "Chair");
    verify(dependencies).furnitureEquipmentSnapshots(List.of(materialId));
  }

  @Test
  void confirmedFurnitureNodeRejectsRenameAndCallerSuppliedRemapWithoutRemoteMutation() {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "b".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(nodeId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            equipmentId, "Chair"));
    CatalogNodeInput category = furnitureCategory(categoryId);
    CatalogNodeInput chair = furnitureMaterial(nodeId, categoryId, "Chair", null);
    var changed = service.changeCatalog(
        catalogId, new ChangeCatalogRequest(0L, List.of(category, chair), List.of()));

    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            changed.version(),
            List.of(category, furnitureMaterial(nodeId, categoryId, "Seat", null)),
            List.of())))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("another immutable equipment name");
    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            changed.version(),
            List.of(
                category,
                furnitureMaterial(
                    nodeId,
                    categoryId,
                    "Chair",
                    new FurnitureEquipmentReference(UUID.randomUUID(), "Chair"))),
            List.of())))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("another asset equipment mapping");
    verify(dependencies, times(1)).ensureFurnitureEquipment(nodeId, "Chair");
  }

  @Test
  void remoteFurnitureConfirmationRemainsDurableWhenTheCatalogCommitLosesItsVersionRace() {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "c".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID nodeId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    List<CatalogNodeInput> nodes = List.of(
        furnitureCategory(categoryId),
        furnitureMaterial(nodeId, categoryId, "Chair", null));
    when(dependencies.ensureFurnitureEquipment(nodeId, "Chair"))
        .thenAnswer(invocation -> {
          assertThat(jdbc.queryForObject(
              "select state from furniture_equipment_link_intent where node_id=?",
              String.class,
              nodeId)).isEqualTo("IN_FLIGHT");
          jdbc.update("update catalog_version set version=version+1 where id=?", catalogId);
          return new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
              equipmentId, "Chair");
        });
    ChangeCatalogRequest request = new ChangeCatalogRequest(0L, nodes, List.of());

    assertThatThrownBy(() -> service.changeCatalog(catalogId, request))
        .isInstanceOf(MaintenanceConflictException.class);
    assertThat(jdbc.queryForMap(
            "select state,equipment_id from furniture_equipment_link_intent where node_id=?",
            nodeId))
        .containsEntry("state", "CONFIRMED")
        .containsEntry("equipment_id", equipmentId);
    assertThatThrownBy(() -> service.changeCatalog(catalogId, request))
        .isInstanceOf(MaintenanceConflictException.class);
    verify(dependencies, times(1)).ensureFurnitureEquipment(nodeId, "Chair");
  }

  @Test
  void furnitureLinkRetryAndAbandonAreVersionFencedReplaySafeAndImmutablyAudited() {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "d".repeat(64));
    UUID nodeId = UUID.randomUUID();
    furnitureEquipmentLinks.prepareAll(
        warehouseId,
        catalogId,
        0,
        List.of(new FurnitureEquipmentLinkStore.LinkRequirement(nodeId, "Chair")));
    exhaustFurnitureLink(nodeId);
    UUID reviewer = UUID.randomUUID();

    var retried = furnitureEquipmentLinkReviews.review(
        warehouseId,
        nodeId,
        0,
        dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels
            .FurnitureEquipmentLinkReviewAction.RETRY,
        reviewer,
        "Asset mapping was reviewed and corrected");
    assertThat(retried.response().state().name()).isEqualTo("PENDING");
    assertThat(retried.response().reviewVersion()).isOne();
    assertThat(retried.replayed()).isFalse();
    assertThat(furnitureEquipmentLinkReviews.review(
            warehouseId,
            nodeId,
            0,
            dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels
                .FurnitureEquipmentLinkReviewAction.RETRY,
            reviewer,
            "Asset mapping was reviewed and corrected").replayed())
        .isTrue();

    exhaustFurnitureLink(nodeId);
    var abandoned = furnitureEquipmentLinkReviews.review(
        warehouseId,
        nodeId,
        1,
        dev.buhanzaz.rwms.maintenance.api.FurnitureEquipmentLinkApiModels
            .FurnitureEquipmentLinkReviewAction.ABANDON,
        reviewer,
        "Catalog node will be replaced with a new stable UUID");
    assertThat(abandoned.response().state().name()).isEqualTo("ABANDONED");
    assertThat(abandoned.response().reviewVersion()).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "select count(*) from furniture_equipment_link_review_audit where node_id=?",
        Integer.class,
        nodeId)).isEqualTo(2);
    assertThatThrownBy(() -> jdbc.update(
        "update furniture_equipment_link_review_audit set reason='rewritten' where node_id=?",
        nodeId)).hasMessageContaining("immutable");
  }

  @Test
  void operationAdmittedBeforeDrainingCannotCommitAfterTheLocalReadinessFence() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "f".repeat(64));
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(
            warehouseId,
            MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING))
        .thenReturn(new MaintenanceDependencyGateway.WarehouseOperationAdmission(
            warehouseId,
            6,
            MaintenanceDependencyGateway.WarehouseLifecycleState.ACTIVE,
            MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING,
            true));
    CountDownLatch admitted = new CountDownLatch(1);
    CountDownLatch commit = new CountDownLatch(1);
    try (var executor = Executors.newSingleThreadExecutor()) {
      Future<Throwable> lateOperation = executor.submit(() -> {
        warehouseLifecycle.requireIncoming(warehouseId);
        admitted.countDown();
        if (!commit.await(10, TimeUnit.SECONDS)) {
          return new AssertionError("readiness fence was not installed");
        }
        try {
          new TransactionTemplate(transactionManager).executeWithoutResult(ignored -> jdbc.update(
              """
              insert into maintenance_estimate(
                id,version,warehouse_id,rental_item_id,rental_item_version_snapshot,
                catalog_version_id,state,revision,priority,movement_to_repair,actor_ref,
                created_at,updated_at)
              values (?,0,?,?,0,?,'DRAFT',1,3,false,'{}'::jsonb,
                      clock_timestamp(),clock_timestamp())
              """,
              UUID.randomUUID(),
              warehouseId,
              UUID.randomUUID(),
              catalogId));
          return null;
        } catch (Throwable failure) {
          return failure;
        }
      });
      assertThat(admitted.await(10, TimeUnit.SECONDS)).isTrue();
      assertThat(warehouseReadinessFences.begin(warehouseId, 7).state())
          .isEqualTo(WarehouseReadinessFenceStore.BeginState.FENCED);
      commit.countDown();
      assertThat(lateOperation.get(10, TimeUnit.SECONDS))
          .isInstanceOf(DataIntegrityViolationException.class)
          .hasMessageContaining("readiness fence");
    }
    UUID terminalEstimateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into maintenance_estimate(
          id,version,warehouse_id,rental_item_id,rental_item_version_snapshot,
          catalog_version_id,state,revision,priority,movement_to_repair,actor_ref,
          created_at,updated_at)
        values (?,0,?,?,0,?,'COMPLETED',1,3,false,'{}'::jsonb,
                clock_timestamp(),clock_timestamp())
        """,
        terminalEstimateId,
        warehouseId,
        UUID.randomUUID(),
        catalogId);
    assertThatThrownBy(() -> jdbc.update(
            "update maintenance_estimate set state='DRAFT' where id=?",
            terminalEstimateId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("readiness fence");
    assertThatThrownBy(() -> jdbc.update(
            """
            insert into integration_reconciliation(
              id,dependency_type,operation_type,idempotency_key,state,attempt_count,
              next_attempt_at,response_snapshot,review_version,created_at,updated_at,
              catalog_version_id)
            values (?,'ASSET','TEST_READINESS',?,'PENDING',0,clock_timestamp(),'{}',0,
                    clock_timestamp(),clock_timestamp(),?)
            """,
            UUID.randomUUID(),
            UUID.randomUUID(),
            catalogId))
        .isInstanceOf(DataIntegrityViolationException.class)
        .hasMessageContaining("readiness fence");
    verify(dependencies).warehouseAdmission(
        warehouseId,
        MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING);
  }

  @Test
  void readinessReleaseCannotReleaseAReplacementFenceAndPendingOutboxesRemainBlockers() {
    UUID warehouseId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime occurredAt = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1);
    new TransactionTemplate(transactionManager).executeWithoutResult(
        ignored -> warehouseOperationMarks.enqueue(warehouseId, operationId, occurredAt));
    assertThat(warehouseReadinessFences.begin(warehouseId, 3).state())
        .isEqualTo(WarehouseReadinessFenceStore.BeginState.BLOCKED);
    jdbc.update(
        "update warehouse_operation_mark_outbox set state='CONFIRMED' where warehouse_id=?",
        warehouseId);

    assertThat(warehouseReadinessFences.begin(warehouseId, 3).state())
        .isEqualTo(WarehouseReadinessFenceStore.BeginState.FENCED);
    assertThat(warehouseReadinessFences.release(warehouseId, 3, "REMOTE_409")).isTrue();
    assertThat(warehouseReadinessFences.begin(warehouseId, 4).state())
        .isEqualTo(WarehouseReadinessFenceStore.BeginState.FENCED);
    assertThat(warehouseReadinessFences.release(warehouseId, 3, "LATE_REMOTE_409")).isFalse();
    assertThat(warehouseReadinessFences.active(warehouseId)).get()
        .extracting(WarehouseReadinessFenceStore.FenceSnapshot::warehouseVersion)
        .isEqualTo(4L);
  }

  @Test
  void catalogRejectsConflictingSnapshotsForOneFurnitureEquipmentId() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "c".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true,
        null, null, null, null, null, false, null);
    CatalogNodeInput chair = new CatalogNodeInput(
        UUID.randomUUID(), CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "Chair"),
        "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);
    CatalogNodeInput conflicting = new CatalogNodeInput(
        UUID.randomUUID(), CatalogNodeType.MATERIAL, "Seat", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "Seat"),
        "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);

    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            0L, List.of(category, chair, conflicting), List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("One furniture equipment ID must use one canonical name");
  }

  @Test
  void catalogRejectsDuplicateWorkOrMaterialNamesUnderOneParent() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "d".repeat(64));
    UUID parentId = UUID.randomUUID();
    CatalogNodeInput parent = new CatalogNodeInput(
        parentId, CatalogNodeType.SUBCATEGORY, "External finish", true, null,
        false, null, null, null, 0, false, false, false,
        null, null, null, null, null, false, null);
    CatalogNodeInput first = new CatalogNodeInput(
        UUID.randomUUID(), CatalogNodeType.WORK, "Replace profile sheet", true, parentId,
        false, null, "piece", "750.00", 60, true, false, true,
        null, null, null, null, null, false, null);
    CatalogNodeInput duplicate = new CatalogNodeInput(
        UUID.randomUUID(), CatalogNodeType.WORK, "  Replace   profile sheet ", true, parentId,
        false, null, "piece", "750.00", 60, true, false, true,
        null, null, null, null, null, false, null);

    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(0L, List.of(parent, first, duplicate), List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("Duplicate work/material name under the same catalog parent");
  }

  @Test
  void furnitureSnapshotCannotQueueWithoutTheCanonicalEquipmentLink() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "d".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID workId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment(materialId, "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            equipmentId, "Chair"));
    RoutingSnapshot routing =
        new RoutingSnapshot(UUID.randomUUID(), "REPAIR", "REPAIR");
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true,
        null, null, null, null, null, false, null);
    CatalogNodeInput material = new CatalogNodeInput(
        materialId, CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "Chair"),
        "piece", "100.00", 0, true, false, false,
        null, null, null, null, null, false, null);
    CatalogNodeInput work = new CatalogNodeInput(
        workId, CatalogNodeType.WORK, "Repair chair", true, null,
        false, null, "piece", "100.00", 15, true, false, true,
        null, null, new CatalogRoutingInput(routing.queueId(), routing.queueType()),
        null, null, false, null);
    CatalogVersionResponse changed = service.changeCatalog(
        catalogId, new ChangeCatalogRequest(0L, List.of(category, material, work), List.of()));
    service.activateCatalog(
        UUID.randomUUID(), UUID.randomUUID(), catalogId,
        new VersionCommand(changed.version()));
    reconcileCatalogRoute(routing.queueId());
    clearInvocations(dependencies);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "AFTER_RENT", 7));
    CatalogNodeSnapshot submitted = new CatalogNodeSnapshot(
        catalogId, materialId, CatalogNodeType.MATERIAL, "Chair", "piece",
        "100.00", 0, null, null, false, null);
    CatalogNodeSnapshot submittedWork = new CatalogNodeSnapshot(
        catalogId, workId, CatalogNodeType.WORK, "Repair chair", "piece",
        "100.00", 15, routing, null, false, null);
    UUID materialLineId = UUID.randomUUID();
    UUID workLineId = UUID.randomUUID();
    var created = service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            List.of(
                new EstimateLineInput(
                    materialLineId, submitted, EstimateLineType.MATERIAL, "Replace chair", "piece",
                    "1", "100.00", 0, null, List.of()),
                new EstimateLineInput(
                    workLineId, submittedWork, EstimateLineType.WORK, "Repair chair", "piece",
                    "1", "100.00", 15, null, List.of())),
            List.of(
                new PlanStageInput(
                    UUID.randomUUID(),
                    RepairStageKind.REPAIR_WORK,
                    0,
                    routing,
                    List.of(materialLineId, workLineId),
                    workLineId,
                    "",
                    null)),
            List.of()));
    when(dependencies.getPropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            warehouseId))
        .thenReturn(new MaintenanceDependencyGateway.PropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            rentalItemId,
            "C-1",
            warehouseId,
            7,
            "AFTER_RENT",
            null,
            null,
            List.of(new MaintenanceDependencyGateway.PropertyAssetContentSnapshot(
                equipmentId, "Chair", null, 3, 1)),
            false,
            false,
            false,
            true));
    var completed = service.completeEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.response().id(),
        new CompleteEstimateRequest(created.response().version()));
    UUID repairId = completed.response().repair().id();
    jdbc.update("""
        update catalog_node
        set furniture_equipment_id=null,
            furniture_equipment_name=null
        where catalog_version_id=? and node_id=?
        """, catalogId, materialId);
    jdbc.update("""
        update estimate_line
        set catalog_snapshot = jsonb_set(
          catalog_snapshot, '{furnitureEquipment}', 'null'::jsonb, false)
        where estimate_id=?
        """, created.response().id());

    assertThat(service.estimate(created.response().id())
        .revisions().getFirst().lines().getFirst().catalogSnapshot().furnitureEquipment())
        .isNull();
    clearInvocations(dependencies);
    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(repairs.findById(repairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    assertThat(jdbc.queryForMap("""
        select state,last_error_code from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, repairId))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("last_error_code", "MaintenanceValidationException");
    verifyNoMoreInteractions(dependencies);
  }

  @Test
  void queueRepairFailsClosedWhenCanonicalAssetTruthIsOlder() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 6, fixture.warehouseId(), "FREE"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    verify(dependencies, never()).acquireLease(
        any(), eq(fixture.rentalItemId()), anyLong(), anyString(), anyString());
  }

  @Test
  void queueRepairFailsClosedWhenCanonicalAssetStatusIsUnsafe() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 8, fixture.warehouseId(), "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    verify(dependencies, never()).acquireLease(
        any(), eq(fixture.rentalItemId()), anyLong(), anyString(), anyString());
  }

  @Test
  void queueRepairFailsClosedWhenCanonicalAssetWarehouseDiffers() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 8, UUID.randomUUID(), "FREE"));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    verify(dependencies, never()).acquireLease(
        any(), eq(fixture.rentalItemId()), anyLong(), anyString(), anyString());
  }

  @Test
  void failedTaskRegistrationPersistsRetryAndThenConfirmsWithTheStableKey() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    UUID stableKey = jdbc.queryForObject("""
        select idempotency_key from integration_reconciliation
        where repair_id=? and operation_type='REGISTER_TASK'
        """, UUID.class, fixture.repairId());
    when(dependencies.registerTask(
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList()))
        .thenThrow(new IllegalStateException("task-board unavailable"));

    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(jdbc.queryForMap("""
        select state,attempt_count,last_error_code from integration_reconciliation
        where repair_id=? and operation_type='REGISTER_TASK'
        """, fixture.repairId()))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("attempt_count", 1)
        .containsEntry("last_error_code", "IllegalStateException");
    assertThat(jdbc.queryForObject("""
        select aggregate_version from aggregate_snapshot
        where aggregate_type='REPAIR' and aggregate_id=?
        order by aggregate_version desc limit 1
        """, Long.class, fixture.repairId().toString()))
        .isEqualTo(repairs.findById(fixture.repairId()).orElseThrow().getVersion());

    makeReconciliationDue(fixture.repairId(), "REGISTER_TASK");
    UUID entryId = UUID.randomUUID();
    when(dependencies.registerTask(
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(), 0, "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, entryId, 0))));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where repair_id=? and operation_type='REGISTER_TASK'
        """, String.class, fixture.repairId())).isEqualTo("CONFIRMED");
    verify(dependencies, times(2)).registerTask(
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList());
  }

  @Test
  void repeatedTaskRegistrationFailuresAdvanceTheRepairSnapshotAndThenQuarantine() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    long queuedVersion = repairs.findById(fixture.repairId()).orElseThrow().getVersion();

    UUID stableKey = jdbc.queryForObject("""
        select idempotency_key from integration_reconciliation
        where repair_id=? and operation_type='REGISTER_TASK'
        """, UUID.class, fixture.repairId());
    when(dependencies.registerTask(
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList()))
        .thenThrow(new IllegalStateException("task-board unavailable with private details"));

    for (int attempt = 1; attempt <= 4; attempt++) {
      if (attempt > 1) makeReconciliationDue(fixture.repairId(), "REGISTER_TASK");
      assertThat(service.reconcileOneTask()).isTrue();

      String expectedState = attempt == 4 ? "QUARANTINED" : "RETRY_PENDING";
      assertThat(jdbc.queryForMap("""
          select state,attempt_count,last_error_code,response_snapshot::text as response_snapshot
          from integration_reconciliation
          where repair_id=? and operation_type='REGISTER_TASK'
          """, fixture.repairId()))
          .containsEntry("state", expectedState)
          .containsEntry("attempt_count", attempt)
          .containsEntry("last_error_code", "IllegalStateException")
          .satisfies(row -> assertThat(row.get("response_snapshot").toString())
              .doesNotContain("private details"));

      var repair = repairs.findById(fixture.repairId()).orElseThrow();
      assertThat(repair.getVersion()).isEqualTo(queuedVersion + attempt);
      assertThat(repair.getDeliveryAttempts()).isEqualTo(attempt);
      assertThat(jdbc.queryForMap("""
          select aggregate_type,aggregate_id,aggregate_version,
                 state->>'id' as state_id,(state->>'version')::bigint as state_version
          from aggregate_snapshot
          where aggregate_type='REPAIR' and aggregate_id=?
          order by aggregate_version desc limit 1
          """, fixture.repairId().toString()))
          .containsEntry("aggregate_type", "REPAIR")
          .containsEntry("aggregate_id", fixture.repairId().toString())
          .containsEntry("aggregate_version", repair.getVersion())
          .containsEntry("state_id", fixture.repairId().toString())
          .containsEntry("state_version", repair.getVersion());
    }

    assertThat(service.reconcileOneTask()).isFalse();
    verify(dependencies, times(4)).registerTask(
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList());
  }

  @Test
  void staleLeaseExpirationCannotOverrideNewerActiveLeaseFact() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    service.reconcileOneTask();
    var queued = repairs.findById(fixture.repairId()).orElseThrow();

    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundLeaseFact(
            queued.getLeaseId(), fixture.rentalItemId(), queued.getFencingToken(), "ACTIVE", 2));
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundLeaseFact(
            queued.getLeaseId(), fixture.rentalItemId(), queued.getFencingToken(), "EXPIRED", 1));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getLeaseReconciliationState())
        .isEqualTo("ACTIVE");

    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundLeaseFact(
            queued.getLeaseId(), fixture.rentalItemId(), queued.getFencingToken(), "EXPIRED", 3));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getLeaseReconciliationState())
        .isEqualTo("RECONCILIATION_REQUIRED");
  }

  @Test
  void readyMediaGenerationZeroPersistsAndAttachesWhileNegativeIsRejected() {
    UUID mediaId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundMediaFact(
            mediaId, 0, "MAINTENANCE_REPAIR", repairId, warehouseId,
            "READY", "{}", 4));

    MediaFactProjection fact = mediaFacts.findById(mediaId).orElseThrow();
    assertThat(fact.getGeneration()).isZero();
    mediaReferences.saveAndFlush(new MaintenanceMediaReference(
        "REPAIR", repairId, mediaId, 0, "MAINTENANCE_REPAIR", warehouseId, "{}"));
    assertThat(mediaReferences
        .findAllByAggregateTypeAndAggregateIdOrderByMediaId("REPAIR", repairId))
        .singleElement()
        .extracting(MaintenanceMediaReference::getGeneration)
        .isEqualTo(0L);

    assertThatThrownBy(() -> MediaFactProjection.create(
        UUID.randomUUID(), -1, "MAINTENANCE_REPAIR", repairId, warehouseId,
        "READY", "{}", 0))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new MaintenanceMediaReference(
        "REPAIR", repairId, UUID.randomUUID(), -1,
        "MAINTENANCE_REPAIR", warehouseId, "{}"))
        .isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void draftRepairMediaReplacementUsesCasAndAdvancesItsOwnerProof() {
    RepairFixture fixture = createDirectRepair();
    CreateDirectRepairRequest content =
        directRepairRequest(
            fixture.warehouseId(),
            fixture.rentalItemId(),
            LocalDate.of(2026, 7, 17),
            null);
    UUID mediaId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundMediaFact(
            mediaId,
            1,
            "MAINTENANCE_REPAIR",
            fixture.repairId(),
            fixture.warehouseId(),
            "READY",
            "{}",
            1));

    RepairResponse changed = service.updateRepairPlan(
        fixture.repairId(),
        new UpdateRepairPlanRequest(
            0L,
            content.lines(),
            content.plan(),
            List.of(new MediaReferenceInput(mediaId, 1L)),
            mediaId));

    assertThat(changed.version()).isOne();
    assertThat(changed.mediaReferences())
        .containsExactly(new MediaReferenceInput(mediaId, 1L));
    assertThat(mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(
        "REPAIR", fixture.repairId()))
        .singleElement()
        .satisfies(reference -> {
          assertThat(reference.getOwnerType()).isEqualTo("MAINTENANCE_REPAIR");
          assertThat(reference.getWarehouseId()).isEqualTo(fixture.warehouseId());
        });
    assertThat(jdbc.queryForList("""
        select media_owner_revision,media_aggregate_version,state
        from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_REPAIR'
          and media_owner_id=?
        order by media_owner_revision
        """, fixture.repairId()))
        .containsExactly(
            Map.of("media_owner_revision", 0L, "media_aggregate_version", 0L, "state", "PENDING"),
            Map.of("media_owner_revision", 1L, "media_aggregate_version", 1L, "state", "PENDING"));

    assertThatThrownBy(() -> service.updateRepairPlan(
        fixture.repairId(),
        new UpdateRepairPlanRequest(0L, List.of(), List.of(stage()), List.of(), null)))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_VERSION_CONFLICT");
    assertThat(service.repair(fixture.repairId()).mediaReferences())
        .containsExactly(new MediaReferenceInput(mediaId, 1L));
  }

  @Test
  void repairPhotosBelongToOneWorkAndMaterialsNeverCarryPhotosOrComments() {
    RepairFixture fixture = createDirectRepair();
    CreateDirectRepairRequest content =
        directRepairRequest(
            fixture.warehouseId(),
            fixture.rentalItemId(),
            LocalDate.of(2026, 7, 17),
            null);
    UUID mediaId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundMediaFact(
            mediaId,
            1,
            "MAINTENANCE_REPAIR",
            fixture.repairId(),
            fixture.warehouseId(),
            "READY",
            "{}",
            1));
    MediaReferenceInput reference = new MediaReferenceInput(mediaId, 1L);
    EstimateLineInput work = content.lines().stream()
        .filter(line -> line.lineType() == EstimateLineType.WORK)
        .findFirst()
        .orElseThrow();
    EstimateLineInput firstWork = new EstimateLineInput(
        work.id(), work.catalogSnapshot(), work.lineType(), work.description(), work.unit(),
        work.quantity(), work.unitPrice(), work.normativeMinutes(), work.comment(),
        List.of(reference));
    EstimateLineInput secondWork = new EstimateLineInput(
        UUID.randomUUID(), work.catalogSnapshot(), work.lineType(), work.description(), work.unit(),
        work.quantity(), work.unitPrice(), work.normativeMinutes(), work.comment(),
        List.of(reference));

    assertThatThrownBy(() -> service.updateRepairPlan(
        fixture.repairId(),
        new UpdateRepairPlanRequest(
            0L, List.of(firstWork, secondWork), content.plan(), List.of(), null)))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("cannot be assigned to multiple work lines");

    EstimateLineInput materialWithLegacyFields = new EstimateLineInput(
        UUID.randomUUID(), null, EstimateLineType.MATERIAL, "Материал",
        "шт", "1", "10.00", 0,
        "legacy material comment", List.of(reference));
    assertThatThrownBy(() -> service.updateRepairPlan(
        fixture.repairId(),
        new UpdateRepairPlanRequest(
            0L, List.of(work, materialWithLegacyFields), content.plan(), List.of(), null)))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("Photos can only be assigned to work lines");
  }

  @Test
  void acceptancePersistsOnlyAcceptanceOwnedMediaAndReplaysIdempotently() {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    UUID wrongMediaId = UUID.randomUUID();
    UUID acceptanceMediaId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      service.applyInboundMediaFact(
          wrongMediaId,
          2,
          "MAINTENANCE_REPAIR",
          fixture.repairId(),
          fixture.warehouseId(),
          "READY",
          "{}",
          2);
      service.applyInboundMediaFact(
          acceptanceMediaId,
          3,
          "MAINTENANCE_ACCEPTANCE",
          fixture.repairId(),
          fixture.warehouseId(),
          "READY",
          "{}",
          3);
    });
    long expectedVersion = repairs.findById(fixture.repairId()).orElseThrow().getVersion();

    assertThatThrownBy(() -> service.accept(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new RepairDecisionRequest(expectedVersion, "no photo", List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .extracting(exception -> ((MaintenanceValidationException) exception).code())
        .isEqualTo("MAINTENANCE_VALIDATION_FAILED");
    assertThatThrownBy(() -> service.accept(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new RepairDecisionRequest(
            expectedVersion,
            "reviewed",
            List.of(new MediaReferenceInput(wrongMediaId, 2L)))))
        .isInstanceOf(MaintenanceValidationException.class)
        .extracting(exception -> ((MaintenanceValidationException) exception).code())
        .isEqualTo("MAINTENANCE_MEDIA_NOT_READY");
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);

    UUID subjectId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    RepairDecisionRequest request = new RepairDecisionRequest(
        expectedVersion,
        "reviewed",
        List.of(new MediaReferenceInput(acceptanceMediaId, 3L)));
    var first = service.accept(subjectId, key, fixture.repairId(), request);
    var replay = service.accept(subjectId, key, fixture.repairId(), request);

    assertThat(first.replayed()).isFalse();
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(first.response());
    assertThat(mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(
        "ACCEPTANCE", fixture.repairId()))
        .singleElement()
        .satisfies(reference -> {
          assertThat(reference.getMediaId()).isEqualTo(acceptanceMediaId);
          assertThat(reference.getOwnerType()).isEqualTo("MAINTENANCE_ACCEPTANCE");
          assertThat(reference.getWarehouseId()).isEqualTo(fixture.warehouseId());
        });
    assertThat(mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(
        "REPAIR", fixture.repairId())).isEmpty();
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_ACCEPTANCE'
          and media_owner_id=?
        """, Integer.class, fixture.repairId())).isEqualTo(2);
  }

  @Test
  void linkedMaterialCharacteristicIsAppliedOnlyAfterSuccessfulAcceptanceAndReplayIsIdempotent() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID characteristicId = UUID.randomUUID();
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    TestCatalogMaterial material =
        insertCharacteristicMaterial(
            work, "Дверь железная", characteristicId, "Железная дверь");
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    RepairFixture fixture =
        createQueuedPendingAcceptanceRepair(
            workAndMaterialRepairRequest(
                warehouseId, rentalItemId, work, material));
    long expectedVersion =
        repairs.findById(fixture.repairId()).orElseThrow().getVersion();

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                 where repair_id=? and operation_type='APPLY_CHARACTERISTIC'
                """,
                Integer.class,
                fixture.repairId()))
        .isZero();
    verify(dependencies, never())
        .applyCabinCharacteristic(any(), any(), any());

    assertThatThrownBy(
            () ->
                service.accept(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.repairId(),
                    new RepairDecisionRequest(
                        expectedVersion, "Без подтверждающего фото", List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .extracting(
            exception ->
                ((MaintenanceValidationException) exception).code())
        .isEqualTo("MAINTENANCE_VALIDATION_FAILED");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                 where repair_id=? and operation_type='APPLY_CHARACTERISTIC'
                """,
                Integer.class,
                fixture.repairId()))
        .isZero();

    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    RepairDecisionRequest decision =
        acceptanceDecision(
            fixture, expectedVersion, "Материал и результат приняты");
    var accepted =
        service.accept(
            subjectId, idempotencyKey, fixture.repairId(), decision);
    var replayed =
        service.accept(
            subjectId, idempotencyKey, fixture.repairId(), decision);

    assertThat(accepted.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(accepted.response());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                 where repair_id=? and operation_type='APPLY_CHARACTERISTIC'
                """,
                Integer.class,
                fixture.repairId()))
        .isOne();

    deferOtherReconciliations(fixture.repairId(), "APPLY_CHARACTERISTIC");
    when(
            dependencies.applyCabinCharacteristic(
                any(), eq(rentalItemId), eq(characteristicId)))
        .thenReturn(
            new MaintenanceDependencyGateway.AppliedCabinCharacteristic(
                rentalItemId, characteristicId, true, 10));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select state from integration_reconciliation
                 where repair_id=? and operation_type='APPLY_CHARACTERISTIC'
                """,
                String.class,
                fixture.repairId()))
        .isEqualTo("CONFIRMED");
    assertThat(service.reconcileOneTask()).isFalse();
    verify(dependencies, times(1))
        .applyCabinCharacteristic(
            any(), eq(rentalItemId), eq(characteristicId));
  }

  @Test
  void acceptanceWaitsForEveryRepairSubtaskAndPersistsAfterTheLastOneCompletes() {
    MultiStageRepairFixture fixture = createRegisteredTwoStageRepair();

    applyTaskOutcome(
        fixture.repair(),
        fixture.queueEntryIds().getFirst(),
        "task-board.queue-entry.completed.v1");
    assertThat(service.repair(fixture.repair().repairId()))
        .satisfies(repair -> {
          assertThat(repair.executionState()).isEqualTo(RepairExecutionState.IN_PROGRESS);
          assertThat(repair.acceptanceState()).isEqualTo(RepairAcceptanceState.NOT_READY);
        });
    assertThat(service.acceptance(fixture.repair().warehouseId()))
        .extracting(AcceptanceProjection::repairId)
        .doesNotContain(fixture.repair().repairId());

    applyTaskOutcome(
        fixture.repair(),
        fixture.queueEntryIds().get(1),
        "task-board.queue-entry.completed.v1");
    stubPendingAcceptanceAsset(fixture.repair());
    assertThat(service.reconcileOneTask()).isTrue();
    RepairResponse pending = service.repair(fixture.repair().repairId());
    assertThat(pending.acceptanceState()).isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(pending.plan().stages())
        .allMatch(stage -> stage.state() == RepairStageState.DONE);

    service.accept(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repair().repairId(),
        acceptanceDecision(
            fixture.repair(), pending.version(), "Все подзадачи проверены"));

    assertThat(service.repair(fixture.repair().repairId()).acceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(service.acceptance(fixture.repair().warehouseId()))
        .extracting(AcceptanceProjection::repairId)
        .doesNotContain(fixture.repair().repairId());
  }

  @Test
  void completedReworkReturnsSourceToPendingAndAcceptCascadesWithoutDuplicateStatus() {
    ReworkFixture fixture = createCompletedRework();

    assertThat(service.acceptance(fixture.source().warehouseId()))
        .extracting(AcceptanceProjection::repairId)
        .contains(fixture.child().repairId())
        .doesNotContain(fixture.source().repairId());

    long childVersion = repairs.findById(fixture.child().repairId()).orElseThrow().getVersion();
    service.accept(
        UUID.randomUUID(), UUID.randomUUID(), fixture.child().repairId(),
        acceptanceDecision(fixture.child(), childVersion, "accepted after rework"));

    assertThat(service.repair(fixture.child().repairId()).acceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(service.repair(fixture.source().repairId()).acceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(service.acceptance(fixture.source().warehouseId()))
        .extracting(AcceptanceProjection::repairId)
        .doesNotContain(fixture.child().repairId(), fixture.source().repairId());
    assertSinglePendingAcceptanceIntent(fixture.source().repairId());
  }

  @Test
  void reworkWithoutNewPhotosReadsSourcePhotosWithoutCopyingTheirOwnership() {
    RepairFixture source = createQueuedPendingAcceptanceRepair();
    UUID sourceMediaId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundMediaFact(
            sourceMediaId,
            4,
            "MAINTENANCE_REPAIR",
            source.repairId(),
            source.warehouseId(),
            "READY",
            "{}",
            4));
    mediaReferences.saveAndFlush(new MaintenanceMediaReference(
        "REPAIR",
        source.repairId(),
        sourceMediaId,
        4,
        "MAINTENANCE_REPAIR",
        source.warehouseId(),
        "{}"));

    RepairFixture child = createDraftRework(source);

    assertThat(service.repair(child.repairId()).mediaReferences())
        .containsExactly(new MediaReferenceInput(sourceMediaId, 4L));
    assertThat(mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(
        "REPAIR", child.repairId())).isEmpty();
    assertThat(mediaReferences.findAllByAggregateTypeAndAggregateIdOrderByMediaId(
        "REPAIR", source.repairId()))
        .singleElement()
        .extracting(MaintenanceMediaReference::getMediaId)
        .isEqualTo(sourceMediaId);
  }

  @Test
  void repeatReworkUsesLatestCompletedCandidateAndPreservesItsLineage() {
    ReworkFixture completed = createCompletedRework();
    ReworkCandidatesResponse candidates =
        service.reworkCandidates(
            completed.child().repairId(), completed.child().warehouseId());
    ReworkCandidateLine candidate = candidates.items().stream()
        .filter(item -> item.sourceRepairId().equals(completed.child().repairId()))
        .findFirst()
        .orElseThrow();
    UUID repeatedLineId = UUID.randomUUID();
    UUID stageId = UUID.randomUUID();
    RoutingSnapshot routing = candidate.line().catalogSnapshot().routing();
    CreateReworkRequest request = new CreateReworkRequest(
        service.repair(completed.child().repairId()).version(),
        "Повторить некачественно выполненную работу",
        List.of(new RepeatReworkLineInput(
            repeatedLineId,
            ReworkLineDisposition.REPEAT,
            candidate.sourceRepairId(),
            candidate.sourceLineId(),
            "2",
            "Переделать ещё раз")),
        List.of(new PlanStageInput(
            stageId,
            RepairStageKind.REPAIR_WORK,
            0,
            routing,
            List.of(repeatedLineId),
            repeatedLineId,
            "",
            null)),
        List.of(),
        null);

    RepairResponse repeated = service.createRework(
        UUID.randomUUID(),
        UUID.randomUUID(),
        completed.child().repairId(),
        request).response();
    EstimateLineResponse repeatedLine = repeated.plan().stages().getFirst().workLines().getFirst();

    assertThat(repeatedLine.id()).isEqualTo(repeatedLineId);
    assertThat(repeatedLine.disposition()).isEqualTo(ReworkLineDisposition.REPEAT);
    assertThat(repeatedLine.sourceRepairId()).isEqualTo(candidate.sourceRepairId());
    assertThat(repeatedLine.sourceLineId()).isEqualTo(candidate.sourceLineId());
    assertThat(repeatedLine.lineageRootLineId()).isEqualTo(candidate.lineageRootLineId());
    assertThat(repeatedLine.catalogSnapshot()).isEqualTo(candidate.line().catalogSnapshot());
    assertThat(repeatedLine.description()).isEqualTo(candidate.line().description());
    assertThat(repeatedLine.unitPrice()).isEqualTo(candidate.line().unitPrice());
    assertThat(repeatedLine.mediaReferences()).isEqualTo(candidate.line().mediaReferences());
    assertThat(repeatedLine.quantity()).isEqualTo("2");
    assertThat(repeatedLine.comment()).isEqualTo("Переделать ещё раз");
  }

  @Test
  void completedReworkWriteOffWaitsForApprovalAndConfirmedAssetEffectBeforeCascading() {
    ReworkFixture fixture = createCompletedRework();

    long childVersion = repairs.findById(fixture.child().repairId()).orElseThrow().getVersion();
    var source = repairs.findById(fixture.source().repairId()).orElseThrow();
    when(dependencies.getPropertyAssetSnapshot(
        eq(MaintenanceDependencyGateway.PropertyAssetKind.CABIN),
        eq(fixture.source().rentalItemId()),
        eq(fixture.source().warehouseId())))
        .thenReturn(new MaintenanceDependencyGateway.PropertyAssetSnapshot(
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            fixture.source().rentalItemId(),
            "БТ-42",
            fixture.source().warehouseId(),
            source.getRentalItemVersionSnapshot(),
            "REPAIR",
            null,
            null,
            List.of(),
            false,
            false,
            true,
            true));

    UUID dispositionSubjectId = UUID.randomUUID();
    UUID dispositionKey = UUID.randomUUID();
    WriteOffRepairRequest writeOffRequest =
        new WriteOffRepairRequest(childVersion, "not repairable", "reviewed", null);
    var requestedResult = dispositions.createRepairWriteOff(
        dispositionSubjectId,
        dispositionKey,
        fixture.child().repairId(),
        fixture.child().warehouseId(),
        writeOffRequest);
    var requested = requestedResult.response();

    reset(dependencies);
    var replayedRequest = dispositions.createRepairWriteOff(
        dispositionSubjectId,
        dispositionKey,
        fixture.child().repairId(),
        fixture.child().warehouseId(),
        writeOffRequest);
    assertThat(requestedResult.replayed()).isFalse();
    assertThat(replayedRequest.replayed()).isTrue();
    assertThat(replayedRequest.response()).isEqualTo(requested);
    verifyNoInteractions(dependencies);

    assertThat(requested.state()).isEqualTo(PropertyDispositionState.PENDING_APPROVAL);
    assertThat(repairs.findById(fixture.child().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(repairs.findById(fixture.source().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);

    var approved = dispositions.approve(
        requested.id(),
        fixture.child().warehouseId(),
        new ApprovePropertyDispositionRequest(requested.version(), "approved"));
    assertThat(approved.state()).isEqualTo(PropertyDispositionState.APPROVED);
    assertThat(repairs.findById(fixture.child().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);

    when(dependencies.preparePropertyDisposition(
        any(), eq(approved.id()), any(MaintenanceDependencyGateway.PropertyDispositionPreparation.class)))
        .thenReturn(new MaintenanceDependencyGateway.PropertyDispositionFence(
            approved.id(),
            "PREPARED",
            "0".repeat(64),
            fixture.source().warehouseId(),
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            fixture.source().rentalItemId(),
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            List.of(),
            Instant.now(),
            null));
    UUID effectId = UUID.randomUUID();
    when(dependencies.applyPropertyDisposition(any(), eq(approved.id()), isNull()))
        .thenReturn(new MaintenanceDependencyGateway.PropertyDispositionEffect(
            effectId,
            approved.id(),
            MaintenanceDependencyGateway.PropertyAssetKind.CABIN,
            fixture.source().rentalItemId(),
            MaintenanceDependencyGateway.PropertyDispositionKind.WRITE_OFF,
            source.getRentalItemVersionSnapshot() + 1,
            Instant.now()));

    dispositionProcessor.processOne();
    assertThat(dispositions.get(approved.id(), fixture.source().warehouseId()).state())
        .isEqualTo(PropertyDispositionState.EFFECT_PENDING);
    dispositionProcessor.processOne();
    assertThat(dispositions.get(approved.id(), fixture.source().warehouseId()).state())
        .isEqualTo(PropertyDispositionState.EFFECTIVE);
    dispositionProcessor.processOne();

    assertThat(repairs.findById(fixture.child().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.WRITTEN_OFF);
    assertThat(repairs.findById(fixture.source().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.WRITTEN_OFF);
    verify(dependencies).preparePropertyDisposition(
        any(), eq(approved.id()), any(MaintenanceDependencyGateway.PropertyDispositionPreparation.class));
    verify(dependencies).applyPropertyDisposition(any(), eq(approved.id()), isNull());
    verify(dependencies).releaseLease(
        any(),
        eq(source.getLeaseId()),
        eq(source.getLeaseVersion()),
        eq(source.getFencingToken()),
        eq("MAINTENANCE_REPAIR"),
        eq(fixture.source().repairId().toString()));
    assertSinglePendingAcceptanceIntent(fixture.source().repairId());
  }

  @Test
  void cancelledReworkReturnsSourceToPendingAndRetainsActiveRootLease() {
    QueuedReworkFixture fixture = createQueuedRework();
    clearInvocations(dependencies);

    applyTaskOutcome(
        fixture.child(), fixture.queueEntryId(), "task-board.queue-entry.cancelled.v1");

    var child = repairs.findById(fixture.child().repairId()).orElseThrow();
    var source = repairs.findById(fixture.source().repairId()).orElseThrow();
    assertThat(child.getExecutionState()).isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(source.getAcceptanceState()).isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(child.getLeaseReconciliationState()).isEqualTo("ACTIVE");
    assertThat(source.getLeaseReconciliationState()).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where operation_type='CANCELLED_PRIMARY_RECONCILIATION'
        """, Integer.class)).isZero();
    verifyNoInteractions(dependencies);
  }

  @Test
  void cancelledNoLeaseSecondaryRepairCompletesLocallyAndRetainsTheFirstLease() {
    RegisteredRepairFixture first = createRegisteredPrimaryRepair();
    var firstBefore = repairs.findById(first.repair().repairId()).orElseThrow();
    UUID firstLeaseId = firstBefore.getLeaseId();
    long firstLeaseVersion = firstBefore.getLeaseVersion();
    long firstFencingToken = firstBefore.getFencingToken();
    OffsetDateTime firstLeaseExpiresAt = firstBefore.getLeaseExpiresAt();

    var created =
        service.createDirectRepair(
            UUID.randomUUID(),
            UUID.randomUUID(),
            directRepairRequest(
                first.repair().warehouseId(),
                first.repair().rentalItemId(),
                LocalDate.of(2026, 7, 18),
                null));
    RepairFixture second = new RepairFixture(
        created.response().id(),
        created.response().plan().stages().getFirst().taskSync().externalTaskId(),
        first.repair().warehouseId(),
        first.repair().rentalItemId());
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), second.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(second.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            second.rentalItemId(), 8, second.warehouseId(), "БТ-42", "REPAIR"));
    assertThat(service.reconcileOneTask()).isTrue();
    RegisteredRepairFixture registeredSecond = registerQueuedRepair(second);
    UUID eventId = UUID.randomUUID();
    clearInvocations(dependencies);

    applyTaskOutcome(
        second,
        registeredSecond.queueEntryId(),
        "task-board.queue-entry.cancelled.v1",
        eventId);
    applyTaskOutcome(
        second,
        registeredSecond.queueEntryId(),
        "task-board.queue-entry.cancelled.v1",
        eventId);

    assertThat(repairs.findById(second.repairId()).orElseThrow())
        .satisfies(cancelled -> {
          assertThat(cancelled.getExecutionState()).isEqualTo(RepairExecutionState.CANCELLED);
          assertThat(cancelled.getLeaseId()).isNull();
          assertThat(cancelled.getLeaseVersion()).isNull();
          assertThat(cancelled.getFencingToken()).isNull();
          assertThat(cancelled.getLeaseExpiresAt()).isNull();
          assertThat(cancelled.getLeaseReconciliationState()).isEqualTo("NOT_REQUIRED");
          assertThat(cancelled.getReconciliationState()).isEqualTo("RECONCILED");
          assertThat(cancelled.getDeliveryState()).isEqualTo("DELIVERED");
        });
    assertThat(repairs.findById(first.repair().repairId()).orElseThrow())
        .satisfies(owner -> {
          assertThat(owner.getLeaseId()).isEqualTo(firstLeaseId);
          assertThat(owner.getLeaseVersion()).isEqualTo(firstLeaseVersion);
          assertThat(owner.getFencingToken()).isEqualTo(firstFencingToken);
          assertThat(owner.getLeaseExpiresAt()).isEqualTo(firstLeaseExpiresAt);
          assertThat(owner.getLeaseReconciliationState()).isEqualTo("ACTIVE");
        });
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where repair_id=? and operation_type='CANCELLED_PRIMARY_RECONCILIATION'
        """, Integer.class, second.repairId())).isZero();
    verifyNoInteractions(dependencies);
  }

  @Test
  void cancelledPrimaryCreatesStableManualReconciliationAndRetainsRenewableLease() {
    RegisteredRepairFixture fixture = createRegisteredPrimaryRepair();
    UUID eventId = UUID.randomUUID();
    clearInvocations(dependencies);

    applyTaskOutcome(
        fixture.repair(), fixture.queueEntryId(),
        "task-board.queue-entry.cancelled.v1", eventId);
    applyTaskOutcome(
        fixture.repair(), fixture.queueEntryId(),
        "task-board.queue-entry.cancelled.v1", eventId);

    var cancelled = repairs.findById(fixture.repair().repairId()).orElseThrow();
    assertThat(cancelled.getExecutionState()).isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(cancelled.getLeaseReconciliationState()).isEqualTo("ACTIVE");
    assertThat(cancelled.getReconciliationState()).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(jdbc.queryForList("""
        select state,idempotency_key,review_version from integration_reconciliation
        where repair_id=? and operation_type='CANCELLED_PRIMARY_RECONCILIATION'
        """, fixture.repair().repairId()))
        .singleElement()
        .satisfies(row -> {
          assertThat(row.get("state")).isEqualTo("RECONCILIATION_REQUIRED");
          assertThat(row.get("idempotency_key")).isNotNull();
          assertThat(((Number) row.get("review_version")).longValue()).isZero();
        });
    assertThat(service.reconcileOneTask()).isFalse();
    verifyNoInteractions(dependencies);
  }

  @Test
  void dueLeaseRenewalIsSelectedAndEnqueuedThroughJpaBoundary() {
    RegisteredRepairFixture fixture = createRegisteredPrimaryRepair();
    setLeaseExpiry(fixture.repair().repairId(), "4 minutes");

    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(
            jdbc.queryForList(
                """
                select repair_id,state,attempt_count from integration_reconciliation
                where operation_type='RENEW_LEASE'
                """))
        .singleElement()
        .satisfies(
            row ->
                assertThat(row)
                    .containsEntry("repair_id", fixture.repair().repairId())
                    .containsEntry("state", "PENDING")
                    .containsEntry("attempt_count", 0));
  }

  @Test
  void dueLeaseRenewalSkipsQuarantinedRepairAndEnqueuesTheNextCandidate() {
    RegisteredRepairFixture quarantinedRepair = createRegisteredPrimaryRepair();
    RegisteredRepairFixture eligibleRepair = createRegisteredPrimaryRepair();
    setLeaseExpiry(quarantinedRepair.repair().repairId(), "1 minute");
    setLeaseExpiry(eligibleRepair.repair().repairId(), "2 minutes");

    assertThat(service.reconcileOneTask()).isTrue();
    UUID reconciliationId = jdbc.queryForObject("""
        select id from integration_reconciliation
        where repair_id=? and operation_type='RENEW_LEASE'
        """, UUID.class, quarantinedRepair.repair().repairId());
    for (int attempt = 0; attempt < 4; attempt++) {
      if (attempt > 0) {
        makeReconciliationDue(quarantinedRepair.repair().repairId(), "RENEW_LEASE");
      }
      new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
        MaintenanceReconciliationStore.WorkItem work =
            reconciliations.lockNextDue().orElseThrow();
        assertThat(work.id()).isEqualTo(reconciliationId);
        reconciliations.failed(work, new IllegalStateException("asset lease unavailable"));
      });
    }
    Map<String, Object> quarantinedBeforeSelection = jdbc.queryForMap("""
        select id,state,attempt_count,idempotency_key,review_version,
               review_subject_id,review_reason,reviewed_at,updated_at
        from integration_reconciliation where id=?
        """, reconciliationId);
    assertThat(quarantinedBeforeSelection)
        .containsEntry("state", "QUARANTINED")
        .containsEntry("attempt_count", 4)
        .containsEntry("review_version", 0L)
        .containsEntry("review_subject_id", null)
        .containsEntry("review_reason", null)
        .containsEntry("reviewed_at", null);

    clearInvocations(dependencies);
    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(jdbc.queryForMap("""
        select id,state,attempt_count,idempotency_key,review_version,
               review_subject_id,review_reason,reviewed_at,updated_at
        from integration_reconciliation where id=?
        """, reconciliationId)).containsAllEntriesOf(quarantinedBeforeSelection);
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where repair_id=? and operation_type='RENEW_LEASE'
        """, Integer.class, quarantinedRepair.repair().repairId())).isOne();
    assertThat(jdbc.queryForList("""
        select repair_id,state,attempt_count from integration_reconciliation
        where operation_type='RENEW_LEASE' order by repair_id
        """))
        .hasSize(2)
        .anySatisfy(row -> assertThat(row)
            .containsEntry("repair_id", eligibleRepair.repair().repairId())
            .containsEntry("state", "PENDING")
            .containsEntry("attempt_count", 0));
    verifyNoInteractions(dependencies);
  }

  @Test
  void reworkQueueRefreshesAValidNearExpiryRootLeaseBeforeQueuing() {
    RepairFixture source = createQueuedPendingAcceptanceRepair();
    RepairFixture child = createDraftRework(source);
    var rootBefore = repairs.findById(source.repairId()).orElseThrow();

    setLeaseExpiry(source.repairId(), "4 minutes 59 seconds");
    OffsetDateTime renewedExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    when(dependencies.renewLease(
        any(), eq(rootBefore.getLeaseId()), eq(rootBefore.getLeaseVersion()),
        eq(rootBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(source.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            rootBefore.getLeaseId(),
            rootBefore.getLeaseVersion() + 1,
            source.rentalItemId(),
            "MAINTENANCE_REPAIR",
            source.repairId(),
            rootBefore.getFencingToken(),
            renewedExpiresAt));

    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), child.repairId(), new VersionCommand(0L));

    assertThat(repairs.findById(child.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
    assertThat(repairs.findById(source.repairId()).orElseThrow())
        .satisfies(root -> {
          assertThat(root.getAcceptanceState()).isEqualTo(RepairAcceptanceState.IN_REWORK);
          assertThat(root.getLeaseVersion()).isEqualTo(rootBefore.getLeaseVersion() + 1);
          assertThat(root.getFencingToken()).isEqualTo(rootBefore.getFencingToken());
          assertThat(root.getLeaseExpiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        });
    assertThat(repairs.findById(child.repairId()).orElseThrow())
        .satisfies(queued -> {
          assertThat(queued.getLeaseId()).isEqualTo(rootBefore.getLeaseId());
          assertThat(queued.getLeaseVersion()).isEqualTo(rootBefore.getLeaseVersion() + 1);
          assertThat(queued.getFencingToken()).isEqualTo(rootBefore.getFencingToken());
          assertThat(queued.getLeaseExpiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        });
    verify(dependencies).renewLease(
        any(), eq(rootBefore.getLeaseId()), eq(rootBefore.getLeaseVersion()),
        eq(rootBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(source.repairId().toString()));
  }

  @Test
  void acceptanceRefreshesAValidNearExpiryLeaseBeforeRecordingTheDecision() {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    var repairBefore = repairs.findById(fixture.repairId()).orElseThrow();
    long version = repairBefore.getVersion();
    setLeaseExpiry(fixture.repairId(), "4 minutes 59 seconds");
    OffsetDateTime renewedExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    when(dependencies.renewLease(
        any(), eq(repairBefore.getLeaseId()), eq(repairBefore.getLeaseVersion()),
        eq(repairBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            repairBefore.getLeaseId(),
            repairBefore.getLeaseVersion() + 1,
            fixture.rentalItemId(),
            "MAINTENANCE_REPAIR",
            fixture.repairId(),
            repairBefore.getFencingToken(),
            renewedExpiresAt));

    service.accept(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(),
        acceptanceDecision(fixture, version, "refreshed lease"));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(accepted -> {
          assertThat(accepted.getAcceptanceState()).isEqualTo(RepairAcceptanceState.ACCEPTED);
          assertThat(accepted.getLeaseId()).isEqualTo(repairBefore.getLeaseId());
          assertThat(accepted.getLeaseVersion()).isEqualTo(repairBefore.getLeaseVersion() + 1);
          assertThat(accepted.getFencingToken()).isEqualTo(repairBefore.getFencingToken());
          assertThat(accepted.getLeaseExpiresAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        });
    verify(dependencies).renewLease(
        any(), eq(repairBefore.getLeaseId()), eq(repairBefore.getLeaseVersion()),
        eq(repairBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString()));
  }

  @Test
  void acceptanceReacquiresAnExpiredLeaseBeforeRecordingTheDecision() {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    var repairBefore = repairs.findById(fixture.repairId()).orElseThrow();
    long version = repairBefore.getVersion();
    setLeaseExpiry(fixture.repairId(), "-1 second");
    UUID replacementLeaseId = UUID.randomUUID();
    long replacementFence = repairBefore.getFencingToken() + 1;
    OffsetDateTime replacementExpiresAt =
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    when(dependencies.acquireLease(
        any(), eq(fixture.rentalItemId()), eq(repairBefore.getRentalItemVersionSnapshot()),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            replacementLeaseId,
            0,
            fixture.rentalItemId(),
            "MAINTENANCE_REPAIR",
            fixture.repairId(),
            replacementFence,
            replacementExpiresAt));

    service.accept(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(),
        acceptanceDecision(fixture, version, "reacquired expired lease"));

    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(accepted -> {
          assertThat(accepted.getAcceptanceState()).isEqualTo(RepairAcceptanceState.ACCEPTED);
          assertThat(accepted.getLeaseId()).isEqualTo(replacementLeaseId);
          assertThat(accepted.getLeaseVersion()).isZero();
          assertThat(accepted.getFencingToken()).isEqualTo(replacementFence);
          assertThat(accepted.getLeaseExpiresAt())
              .isAfter(OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
        });
    verify(dependencies).acquireLease(
        any(), eq(fixture.rentalItemId()), eq(repairBefore.getRentalItemVersionSnapshot()),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString()));
    verify(dependencies, never()).renewLease(
        any(), any(), anyLong(), anyLong(), anyString(), anyString());
  }

  @Test
  void acceptanceProjectionDoesNotOfferAParentBlockedByActiveDraftRework() {
    RepairFixture source = createQueuedPendingAcceptanceRepair();
    createDraftRework(source);

    assertThat(service.acceptance(source.warehouseId()))
        .noneMatch(item -> item.repairId().equals(source.repairId()));
  }

  @Test
  void acceptanceRejectsAbsentOrUnrenewableLease() {
    RepairFixture absent = createQueuedPendingAcceptanceRepair();
    long absentVersion = repairs.findById(absent.repairId()).orElseThrow().getVersion();
    jdbc.update(
        """
        update maintenance_repair
        set lease_id=null,
            lease_version=null,
            fencing_token=null,
            lease_expires_at=null,
            lease_reconciliation_state='NOT_REQUIRED'
        where id=?
        """,
        absent.repairId());
    clearInvocations(dependencies);

    assertThatThrownBy(
            () ->
                service.accept(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    absent.repairId(),
                    acceptanceDecision(absent, absentVersion, "missing lease")))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_LEASE_CONFLICT");
    verifyNoInteractions(dependencies);

    RepairFixture unrenewable = createQueuedPendingAcceptanceRepair();
    var repairBefore = repairs.findById(unrenewable.repairId()).orElseThrow();
    setLeaseExpiry(unrenewable.repairId(), "4 minutes 59 seconds");
    clearInvocations(dependencies);
    when(dependencies.renewLease(
        any(), eq(repairBefore.getLeaseId()), eq(repairBefore.getLeaseVersion()),
        eq(repairBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(unrenewable.repairId().toString())))
        .thenThrow(
            new MaintenanceDependencyException(
                org.springframework.http.HttpStatus.CONFLICT, "asset lease is no longer renewable"));

    assertThatThrownBy(
            () ->
                service.accept(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    unrenewable.repairId(),
                    acceptanceDecision(
                        unrenewable, repairBefore.getVersion(), "lost lease")))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception ->
                assertThat(exception.status()).isEqualTo(org.springframework.http.HttpStatus.CONFLICT));
    assertThat(repairs.findById(unrenewable.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
  }

  @Test
  void acceptanceRejectsLeaseRenewalThatChangesTheFenceOrDoesNotAdvanceTheVersion() {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    var repairBefore = repairs.findById(fixture.repairId()).orElseThrow();
    setLeaseExpiry(fixture.repairId(), "4 minutes 59 seconds");
    OffsetDateTime renewedExpiresAt = OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15);
    when(dependencies.renewLease(
        any(), eq(repairBefore.getLeaseId()), eq(repairBefore.getLeaseVersion()),
        eq(repairBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                repairBefore.getLeaseId(),
                repairBefore.getLeaseVersion() + 1,
                fixture.rentalItemId(),
                "MAINTENANCE_REPAIR",
                fixture.repairId(),
                repairBefore.getFencingToken() + 1,
                renewedExpiresAt),
            new MaintenanceDependencyGateway.LeaseSnapshot(
                repairBefore.getLeaseId(),
                repairBefore.getLeaseVersion(),
                fixture.rentalItemId(),
                "MAINTENANCE_REPAIR",
                fixture.repairId(),
                repairBefore.getFencingToken(),
                renewedExpiresAt));

    for (int attempt = 0; attempt < 2; attempt++) {
      assertThatThrownBy(
              () ->
                  service.accept(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      fixture.repairId(),
                      acceptanceDecision(
                          fixture, repairBefore.getVersion(), "invalid renewal")))
          .isInstanceOf(MaintenanceDependencyException.class)
          .hasMessageContaining("preserve the current fence and version");
    }

    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(repair -> {
          assertThat(repair.getAcceptanceState()).isEqualTo(RepairAcceptanceState.PENDING);
          assertThat(repair.getLeaseVersion()).isEqualTo(repairBefore.getLeaseVersion());
          assertThat(repair.getFencingToken()).isEqualTo(repairBefore.getFencingToken());
        });
    verify(dependencies, times(2)).renewLease(
        any(), eq(repairBefore.getLeaseId()), eq(repairBefore.getLeaseVersion()),
        eq(repairBefore.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString()));
  }

  @Test
  void conflictingStageDeadlinesAreRejectedBeforeRepairCommit() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, "FREE", 7));
    OffsetDateTime first = OffsetDateTime.parse("2026-07-18T10:00:00+03:00");
    OffsetDateTime second = first.plusHours(1);

    assertThatThrownBy(() -> service.createDirectRepair(
        UUID.randomUUID(), UUID.randomUUID(),
        new CreateDirectRepairRequest(
            warehouseId, rentalItemId, LocalDate.of(2026, 7, 17), null,
            List.of(stage(0, first), stage(1, second)), List.of())))
        .isInstanceOf(dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class);
    assertThat(repairs.count()).isZero();
  }

  @Test
  void estimatesRequireAnAfterRentRentalItemAndDirectRepairsExcludeIt() {
    UUID warehouseId = UUID.randomUUID();
    UUID estimateRentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        estimateRentalItemId, warehouseId, "FREE", 7));

    assertThatThrownBy(() -> service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            estimateRentalItemId,
            LocalDate.of(2026, 7, 17),
            null,
            List.of(),
            List.of(),
            List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("must have AFTER_RENT");

    UUID directRepairRentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        directRepairRentalItemId, warehouseId, "AFTER_RENT", 7));

    assertThatThrownBy(() -> service.createDirectRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateDirectRepairRequest(
            warehouseId,
            directRepairRentalItemId,
            LocalDate.of(2026, 7, 17),
            null,
            List.of(stage()),
            List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("must not have RENTED or AFTER_RENT");
  }

  @Test
  void fourthFailureSurvivesReviewBoundaryRestartAndResumesWithOriginalStableKey() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    UUID stableKey = jdbc.queryForObject("""
        select idempotency_key from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, UUID.class, fixture.repairId());
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 7, fixture.warehouseId(), "FREE"));
    when(dependencies.acquireLease(any(), any(), anyLong(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("asset unavailable"));

    for (int attempt = 0; attempt < 4; attempt++) {
      if (attempt > 0) makeReconciliationDue(fixture.repairId(), "QUEUE_REPAIR");
      assertThat(service.reconcileOneTask()).isTrue();
    }
    UUID reconciliationId = jdbc.queryForObject("""
        select id from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR' and state='QUARANTINED'
        """, UUID.class, fixture.repairId());

    assertThatThrownBy(() -> new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        reconciliations.enqueue(
            fixture.repairId(), "ASSET", "QUEUE_REPAIR", stableKey,
            Map.of("repairId", fixture.repairId().toString()))))
        .isInstanceOf(MaintenanceConflictException.class);

    UUID reviewer = UUID.randomUUID();
    var restartedReviewBoundary = new MaintenanceReconciliationReviewService(reconciliations);
    var resumed = new TransactionTemplate(transactionManager).execute(status ->
        restartedReviewBoundary.resumeQuarantined(
            reconciliationId, 0, reviewer, "asset outage resolved"));
    assertThat(resumed).isNotNull();
    assertThat(resumed.idempotencyKey()).isEqualTo(stableKey);
    assertThat(resumed.reviewVersion()).isOne();
    assertThat(resumed.reviewSubjectId()).isEqualTo(reviewer);
    assertThat(resumed.reviewedAt()).isNotNull();
    assertThatThrownBy(() -> new TransactionTemplate(transactionManager).execute(status ->
        restartedReviewBoundary.resumeQuarantined(
            reconciliationId, 0, reviewer, "stale duplicate review")))
        .isInstanceOf(MaintenanceConflictException.class);

    reset(dependencies);
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation where id=?
        """, String.class, reconciliationId)).isEqualTo("CONFIRMED");
    assertThat(jdbc.queryForObject("""
        select idempotency_key from integration_reconciliation where id=?
        """, UUID.class, reconciliationId)).isEqualTo(stableKey);
  }

  @Test
  void authenticatedQueueRetryReviewsAndResumesItsExactQuarantinedWork() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                fixture.rentalItemId(), 7, fixture.warehouseId(), "FREE"));
    when(dependencies.acquireLease(any(), any(), anyLong(), anyString(), anyString()))
        .thenThrow(new IllegalStateException("asset unavailable"));

    for (int attempt = 0; attempt < 4; attempt++) {
      if (attempt > 0) makeReconciliationDue(fixture.repairId(), "QUEUE_REPAIR");
      assertThat(service.reconcileOneTask()).isTrue();
    }
    UUID reviewer = UUID.randomUUID();
    long currentVersion = service.repair(fixture.repairId(), fixture.warehouseId()).version();

    service.queueRepair(
        reviewer,
        UUID.randomUUID(),
        fixture.repairId(),
        new QueueRepairRequest(currentVersion, 3));

    assertThat(
            jdbc.queryForMap(
                """
                select state,attempt_count,review_version,review_subject_id,review_reason
                from integration_reconciliation
                where repair_id=? and operation_type='QUEUE_REPAIR'
                """,
                fixture.repairId()))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("review_version", 1L)
        .containsEntry("review_subject_id", reviewer)
        .containsEntry(
            "review_reason",
            "Authenticated repair queue retry after canonical asset revalidation");

    reset(dependencies);
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(
            jdbc.queryForObject(
                """
                select state from integration_reconciliation
                where repair_id=? and operation_type='QUEUE_REPAIR'
                """,
                String.class,
                fixture.repairId()))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void managedInboundDeliveryRetryResumesOnlyItsStableQuarantinedIntent() {
    RepairFixture fixture = createQuarantinedInboundDelivery();
    var before = repairs.findById(fixture.repairId()).orElseThrow();
    var beforeSnapshot = service.repair(fixture.repairId(), fixture.warehouseId());
    var workLinesBefore = beforeSnapshot.plan().stages().stream()
        .flatMap(stage -> stage.workLines().stream())
        .toList();
    var materialLinesBefore = beforeSnapshot.plan().stages().stream()
        .flatMap(stage -> stage.materialLines().stream())
        .toList();
    UUID reconciliationId = jdbc.queryForObject(
        """
        select id from integration_reconciliation
        where repair_id=? and dependency_type='LOGISTICS' and operation_type='CREATE_DRIVER_TASK'
        """,
        UUID.class,
        fixture.repairId());
    UUID stableKey = jdbc.queryForObject(
        """
        select idempotency_key from integration_reconciliation
        where id=?
        """,
        UUID.class,
        reconciliationId);
    long eventCountBefore = jdbc.queryForObject(
        """
        select count(*) from domain_event
        where aggregate_type='REPAIR' and aggregate_id=?
        """,
        Long.class,
        fixture.repairId().toString());
    UUID subjectId = UUID.randomUUID();
    UUID commandKey = UUID.randomUUID();
    RetryInboundDeliveryRequest request = new RetryInboundDeliveryRequest(
        before.getVersion(), RepairLogisticsPlanningMode.AUTO, null, "logistics confirmed no driver task");

    clearInvocations(dependencies);
    when(
            dependencies.maintenanceDriverTaskCompensation(
                fixture.repairId(),
                MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR))
        .thenAnswer(
            ignored -> {
              assertThat(
                      org.springframework.transaction.support.TransactionSynchronizationManager
                          .isActualTransactionActive())
                  .isFalse();
              return driverTaskCompensation(
                  fixture.repairId(),
                  MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.ABSENT);
            });

    var recovered = service.retryInboundDelivery(
        subjectId, commandKey, fixture.repairId(), fixture.warehouseId(), request);

    assertThat(recovered.replayed()).isFalse();
    assertThat(recovered.response().repair().version()).isEqualTo(before.getVersion() + 1);
    assertThat(recovered.response().repair().priority()).isEqualTo(beforeSnapshot.priority());
    assertThat(recovered.response().repair().logisticsPlanningMode())
        .isEqualTo(RepairLogisticsPlanningMode.AUTO);
    assertThat(recovered.response().repair().logisticsScheduledDate()).isNull();
    assertThat(recovered.response().delivery().state()).isEqualTo(DeliveryState.RETRY_PENDING);
    assertThat(recovered.response().affectedSourceRepairs()).isEmpty();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(repair -> {
          assertThat(repair.getPriority()).isEqualTo(beforeSnapshot.priority());
          assertThat(repair.getLogisticsPlanningMode()).isEqualTo(RepairLogisticsPlanningMode.AUTO);
          assertThat(repair.getLogisticsScheduledDate()).isNull();
          assertThat(repair.getDeliveryState()).isEqualTo("RETRY_PENDING");
          assertThat(repair.getTaskGenerationState()).isEqualTo("PENDING_GENERATION");
          assertThat(repair.getReconciliationState()).isEqualTo("RECONCILIATION_REQUIRED");
        });
    var persistedAfter = service.repair(fixture.repairId(), fixture.warehouseId());
    assertThat(persistedAfter.priority()).isEqualTo(beforeSnapshot.priority());
    assertThat(persistedAfter.plan().stages().stream()
            .flatMap(stage -> stage.workLines().stream())
            .toList())
        .containsExactlyElementsOf(workLinesBefore);
    assertThat(persistedAfter.plan().stages().stream()
            .flatMap(stage -> stage.materialLines().stream())
            .toList())
        .containsExactlyElementsOf(materialLinesBefore);
    assertThat(
            jdbc.queryForMap(
                """
                select state,attempt_count,idempotency_key,review_version,review_subject_id,review_reason
                from integration_reconciliation where id=?
                """,
                reconciliationId))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("idempotency_key", stableKey)
        .containsEntry("review_version", 1L)
        .containsEntry("review_subject_id", subjectId)
        .containsEntry("review_reason", "logistics confirmed no driver task");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from integration_reconciliation
                where repair_id=? and dependency_type='LOGISTICS'
                  and operation_type='CREATE_DRIVER_TASK' and idempotency_key=?
                """,
                Integer.class,
                fixture.repairId(),
                stableKey))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select event_type from domain_event
                where aggregate_type='REPAIR' and aggregate_id=?
                order by aggregate_version desc limit 1
                """,
                String.class,
                fixture.repairId().toString()))
        .isEqualTo(MaintenanceEventType.REPAIR_PLAN_CHANGED.value());
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                where aggregate_type='REPAIR' and aggregate_id=?
                """,
                Long.class,
                fixture.repairId().toString()))
        .isEqualTo(eventCountBefore + 1);
    verify(dependencies)
        .maintenanceDriverTaskCompensation(
            fixture.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR);
    verify(dependencies, never()).cancelMaintenanceDriverTaskCompensation(any(), any(), any());

    clearInvocations(dependencies);
    var replay = service.retryInboundDelivery(
        subjectId, commandKey, fixture.repairId(), fixture.warehouseId(), request);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(recovered.response());
    verifyNoInteractions(dependencies);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                where aggregate_type='REPAIR' and aggregate_id=?
                """,
                Long.class,
                fixture.repairId().toString()))
        .isEqualTo(eventCountBefore + 1);
  }

  @Test
  void inboundDeliveryRetryFailsClosedForAnyRemoteTaskAndInvalidLocalState() {
    RepairFixture fixture = createQuarantinedInboundDelivery();
    var quarantined = repairs.findById(fixture.repairId()).orElseThrow();
    RetryInboundDeliveryRequest request = new RetryInboundDeliveryRequest(
        quarantined.getVersion(), RepairLogisticsPlanningMode.AUTO, null, "retry after verification");

    for (MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome outcome : List.of(
        MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.PENDING,
        MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.CANCELLED,
        MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.STARTED,
        MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.COMPLETED,
        MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.RECONCILIATION_REQUIRED)) {
      reset(dependencies);
      when(
              dependencies.maintenanceDriverTaskCompensation(
                  fixture.repairId(),
                  MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR))
          .thenReturn(driverTaskCompensation(fixture.repairId(), outcome));

      assertThatThrownBy(
              () ->
                  service.retryInboundDelivery(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      fixture.repairId(),
                      fixture.warehouseId(),
                      request))
          .isInstanceOf(MaintenanceConflictException.class);
    }

    assertThat(
            jdbc.queryForMap(
                """
                select state,attempt_count from integration_reconciliation
                where repair_id=? and dependency_type='LOGISTICS' and operation_type='CREATE_DRIVER_TASK'
                """,
                fixture.repairId()))
        .containsEntry("state", "QUARANTINED")
        .containsEntry("attempt_count", 4);

    jdbc.update(
        """
        update integration_reconciliation
           set state='RETRY_PENDING',attempt_count=0
         where repair_id=? and dependency_type='LOGISTICS' and operation_type='CREATE_DRIVER_TASK'
        """,
        fixture.repairId());
    reset(dependencies);
    when(
            dependencies.maintenanceDriverTaskCompensation(
                fixture.repairId(),
                MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(
            driverTaskCompensation(
                fixture.repairId(),
                MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.ABSENT));

    assertThatThrownBy(
            () ->
                service.retryInboundDelivery(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.repairId(),
                    fixture.warehouseId(),
                    request))
        .isInstanceOf(MaintenanceConflictException.class);

    jdbc.update(
        """
        update integration_reconciliation
           set state='QUARANTINED',attempt_count=4
         where repair_id=? and dependency_type='LOGISTICS' and operation_type='CREATE_DRIVER_TASK'
        """,
        fixture.repairId());
    jdbc.update(
        "update maintenance_repair set execution_state='IN_PROGRESS' where id=?",
        fixture.repairId());

    assertThatThrownBy(
            () ->
                service.retryInboundDelivery(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    fixture.repairId(),
                    fixture.warehouseId(),
                    request))
        .isInstanceOf(MaintenanceConflictException.class);
    assertThat(
            jdbc.queryForMap(
                """
                select state,attempt_count from integration_reconciliation
                where repair_id=? and dependency_type='LOGISTICS' and operation_type='CREATE_DRIVER_TASK'
                """,
                fixture.repairId()))
        .containsEntry("state", "QUARANTINED")
        .containsEntry("attempt_count", 4);
  }

  @Test
  void concurrentReconciliationClaimsSkipLockedWorkWithoutBlocking() throws Exception {
    RepairFixture fixture = createDirectRepair();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                reconciliations.enqueue(
                    fixture.repairId(),
                    "ASSET",
                    "QUEUE_REPAIR",
                    UUID.randomUUID(),
                    Map.of("repairId", fixture.repairId().toString())));
    CountDownLatch claimed = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      Future<Optional<MaintenanceReconciliationStore.WorkItem>> first =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(
                          status -> {
                            Optional<MaintenanceReconciliationStore.WorkItem> item =
                                reconciliations.lockNextDue();
                            claimed.countDown();
                            try {
                              release.await();
                            } catch (InterruptedException exception) {
                              Thread.currentThread().interrupt();
                              throw new IllegalStateException(exception);
                            }
                            status.setRollbackOnly();
                            return item;
                          }));
      assertThat(claimed.await(5, TimeUnit.SECONDS)).isTrue();

      Future<Optional<MaintenanceReconciliationStore.WorkItem>> concurrent =
          executor.submit(
              () ->
                  new TransactionTemplate(transactionManager)
                      .execute(status -> reconciliations.lockNextDue()));

      assertThat(concurrent.get(2, TimeUnit.SECONDS)).isEmpty();
      release.countDown();
      assertThat(first.get(5, TimeUnit.SECONDS)).isPresent();
    } finally {
      release.countDown();
      executor.shutdownNow();
    }
  }

  @Test
  void reconciliationTransitionRollbackAndStableKeyConflictPreserveStoredWork() {
    RepairFixture fixture = createDirectRepair();
    RepairFixture other = createDirectRepair();
    UUID stableKey = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status ->
                reconciliations.enqueue(
                    fixture.repairId(),
                    "ASSET",
                    "QUEUE_REPAIR",
                    stableKey,
                    Map.of("repairId", fixture.repairId().toString())));

    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              MaintenanceReconciliationStore.WorkItem claimed =
                  reconciliations.lockNextDue().orElseThrow();
              reconciliations.confirmed(claimed, Map.of("result", "confirmed"));
              status.setRollbackOnly();
            });

    assertThat(
            jdbc.queryForMap(
                """
                select state,attempt_count from integration_reconciliation
                where idempotency_key=?
                """,
                stableKey))
        .containsEntry("state", "PENDING")
        .containsEntry("attempt_count", 0);
    assertThatThrownBy(
            () ->
                new TransactionTemplate(transactionManager)
                    .executeWithoutResult(
                        status ->
                            reconciliations.enqueue(
                                other.repairId(),
                                "ASSET",
                                "QUEUE_REPAIR",
                                stableKey,
                                Map.of("repairId", other.repairId().toString()))))
        .isInstanceOf(MaintenanceConflictException.class);
    assertThat(
            jdbc.queryForObject(
                """
                select repair_id from integration_reconciliation where idempotency_key=?
                """,
                UUID.class,
                stableKey))
        .isEqualTo(fixture.repairId());
  }

  @Test
  void terminalDecisionAndDraftReworkRaceCannotBothCommit() throws Exception {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    long expectedVersion = repairs.findById(fixture.repairId()).orElseThrow().getVersion();
    CreateReworkRequest reworkRequest =
        reworkRequest(fixture, expectedVersion, "rework");
    RepairDecisionRequest acceptanceRequest =
        acceptanceDecision(fixture, expectedVersion, "accepted");
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger committed = new AtomicInteger();
    List<Throwable> failures = java.util.Collections.synchronizedList(new ArrayList<>());
    var executor = Executors.newFixedThreadPool(2);
    try {
      List<Callable<Void>> calls = List.of(
          () -> {
            start.await();
            try {
              service.createRework(
                  UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(),
                  reworkRequest);
              committed.incrementAndGet();
            } catch (RuntimeException exception) {
              failures.add(exception);
            }
            return null;
          },
          () -> {
            start.await();
            try {
              service.accept(
                  UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(),
                  acceptanceRequest);
              committed.incrementAndGet();
            } catch (RuntimeException exception) {
              failures.add(exception);
            }
            return null;
          });
      List<Future<Void>> futures = calls.stream().map(executor::submit).toList();
      start.countDown();
      for (Future<Void> future : futures) future.get();

      assertThat(committed).hasValue(1);
      assertThat(failures).hasSize(1)
          .allMatch(value -> value instanceof MaintenanceConflictException
              || value instanceof IllegalStateException);
      var source = repairs.findById(fixture.repairId()).orElseThrow();
      int draftReworks = jdbc.queryForObject("""
          select count(*) from maintenance_repair
          where source_repair_id=? and execution_state='DRAFT'
          """, Integer.class, fixture.repairId());
      assertThat((source.getAcceptanceState() == RepairAcceptanceState.ACCEPTED)
          ^ (draftReworks == 1)).isTrue();
    } finally {
      executor.shutdownNow();
    }
  }

  private RepairFixture createQueuedPendingAcceptanceRepair() {
    RegisteredRepairFixture registered = createRegisteredPrimaryRepair();
    RepairFixture fixture = registered.repair();
    applyTaskOutcome(
        fixture, registered.queueEntryId(), "task-board.queue-entry.completed.v1");
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    stubPendingAcceptanceAsset(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getLeaseReconciliationState())
        .isEqualTo("ACTIVE");
    return fixture;
  }

  private RepairFixture createQueuedPendingAcceptanceRepair(
      CreateDirectRepairRequest request) {
    RepairResponse created =
        service
            .createDirectRepair(
                UUID.randomUUID(), UUID.randomUUID(), request)
            .response();
    RepairFixture fixture =
        new RepairFixture(
            created.id(),
            created.plan().stages().getFirst().taskSync().externalTaskId(),
            request.warehouseId(),
            request.rentalItemId());
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new VersionCommand(created.version()));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    RegisteredRepairFixture registered = registerQueuedRepair(fixture);
    applyTaskOutcome(
        fixture,
        registered.queueEntryId(),
        "task-board.queue-entry.completed.v1");
    assertThat(
            repairs
                .findById(fixture.repairId())
                .orElseThrow()
                .getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    stubPendingAcceptanceAsset(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    return fixture;
  }

  private RegisteredRepairFixture createRegisteredPrimaryRepair() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    return registerQueuedRepair(fixture);
  }

  private MultiStageRepairFixture createRegisteredTwoStageRepair() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, "FREE", 7));
    CreateDirectRepairRequest request = twoStageDirectRepairRequest(
        warehouseId, rentalItemId);
    RepairResponse created = service.createDirectRepair(
        UUID.randomUUID(), UUID.randomUUID(), request).response();
    RepairFixture fixture = new RepairFixture(
        created.id(),
        created.plan().stages().getFirst().taskSync().externalTaskId(),
        warehouseId,
        rentalItemId);
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    List<UUID> queueEntryIds = List.of(UUID.randomUUID(), UUID.randomUUID());
    when(dependencies.registerTask(
        any(),
        eq(fixture.externalTaskId()),
        eq(fixture.repairId()),
        eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()),
        nullable(String.class),
        any(LocalDate.class),
        anyInt(),
        eq(6),
        anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(),
            0,
            "ACTIVE",
            List.of(
                new MaintenanceDependencyGateway.TaskStageSnapshot(
                    0, queueEntryIds.getFirst(), 0),
                new MaintenanceDependencyGateway.TaskStageSnapshot(
                    1, queueEntryIds.get(1), 0))));
    assertThat(service.reconcileOneTask()).isTrue();
    return new MultiStageRepairFixture(fixture, queueEntryIds);
  }

  private RegisteredRepairFixture registerQueuedRepair(RepairFixture fixture) {
    UUID entryId = UUID.randomUUID();
    when(dependencies.registerTask(any(), eq(fixture.externalTaskId()), eq(fixture.repairId()),
        eq(fixture.warehouseId()), eq(fixture.rentalItemId()), nullable(String.class),
        any(LocalDate.class), anyInt(),
        eq(6), anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(), 0, "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, entryId, 0))));
    UUID registeredEntryId = null;
    for (int attempt = 0; attempt < 3 && registeredEntryId == null; attempt++) {
      assertThat(service.reconcileOneTask()).isTrue();
      registeredEntryId = service.repair(fixture.repairId())
          .plan().stages().getFirst().taskSync().taskBoardEntryId();
    }
    assertThat(registeredEntryId).isEqualTo(entryId);
    return new RegisteredRepairFixture(fixture, entryId);
  }

  private RepairFixture createDraftRework(RepairFixture source) {
    long sourceVersion = repairs.findById(source.repairId()).orElseThrow().getVersion();
    var created = service.createRework(
        UUID.randomUUID(), UUID.randomUUID(), source.repairId(),
        reworkRequest(source, sourceVersion, "corrective work"));
    return new RepairFixture(
        created.response().id(),
        created.response().plan().stages().getFirst().taskSync().externalTaskId(),
        source.warehouseId(), source.rentalItemId());
  }

  private QueuedReworkFixture createQueuedRework() {
    RepairFixture source = createQueuedPendingAcceptanceRepair();
    RepairFixture child = createDraftRework(source);
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), child.repairId(), new VersionCommand(0L));
    RegisteredRepairFixture registered = registerQueuedRepair(child);
    assertThat(repairs.findById(source.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.IN_REWORK);
    return new QueuedReworkFixture(source, child, registered.queueEntryId());
  }

  private ReworkFixture createCompletedRework() {
    QueuedReworkFixture queued = createQueuedRework();
    clearInvocations(dependencies);

    applyTaskOutcome(
        queued.child(), queued.queueEntryId(), "task-board.queue-entry.completed.v1");

    assertThat(repairs.findById(queued.child().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    assertThat(repairs.findById(queued.source().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);
    assertSinglePendingAcceptanceIntent(queued.source().repairId());
    verifyNoInteractions(dependencies);
    return new ReworkFixture(queued.source(), queued.child());
  }

  private void applyTaskOutcome(
      RepairFixture fixture, UUID queueEntryId, String eventType) {
    applyTaskOutcome(fixture, queueEntryId, eventType, UUID.randomUUID());
  }

  private void applyTaskOutcome(
      RepairFixture fixture, UUID queueEntryId, String eventType, UUID eventId) {
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundTaskOutcome(
            eventId, eventType, fixture.externalTaskId(), queueEntryId, 1,
            OffsetDateTime.now(ZoneOffset.UTC)));
  }

  private void stubPendingAcceptanceAsset(RepairFixture fixture) {
    var repair = repairs.findById(fixture.repairId()).orElseThrow();
    when(dependencies.fencedStatus(
        any(), eq(fixture.rentalItemId()), eq(fixture.warehouseId()),
        eq(repair.getRentalItemVersionSnapshot()), eq(repair.getLeaseId()),
        eq(repair.getFencingToken()), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString()), eq("PENDING_ACCEPTANCE"), eq(false)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), repair.getRentalItemVersionSnapshot() + 1,
            fixture.warehouseId(), "WAITING_REPAIR_CHECK"));
  }

  private void assertSinglePendingAcceptanceIntent(UUID sourceId) {
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where operation_type='PENDING_ACCEPTANCE'
        """, Integer.class)).isOne();
    assertThat(jdbc.queryForObject("""
        select repair_id from integration_reconciliation
        where operation_type='PENDING_ACCEPTANCE'
        """, UUID.class)).isEqualTo(sourceId);
  }

  private void setLeaseExpiry(UUID repairId, String interval) {
    jdbc.update("""
        update maintenance_repair
        set lease_expires_at=clock_timestamp() + ?::interval
        where id=?
        """, interval, repairId);
  }

  private void makeReconciliationDue(UUID repairId, String operation) {
    jdbc.update("""
        update integration_reconciliation set next_attempt_at=clock_timestamp()
        where repair_id=? and operation_type=?
        """, repairId, operation);
  }

  private RepairFixture createDirectRepair() {
    return createDirectRepair(7);
  }

  private RepairFixture createDirectRepair(long rentalItemVersion) {
    return createDirectRepair("FREE", rentalItemVersion);
  }

  private RepairFixture createDirectRepair(String status, long rentalItemVersion) {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, status, rentalItemVersion));
    var result = service.createDirectRepair(
        UUID.randomUUID(), UUID.randomUUID(),
        directRepairRequest(
            warehouseId, rentalItemId, LocalDate.of(2026, 7, 17), null));
    return new RepairFixture(
        result.response().id(), result.response().plan().stages().getFirst().taskSync().externalTaskId(),
        warehouseId, rentalItemId);
  }

  private RepairFixture createQuarantinedInboundDelivery() {
    RepairFixture fixture = createDirectRepair();
    long version = service.repair(fixture.repairId(), fixture.warehouseId()).version();
    service.queueRepair(
        UUID.randomUUID(),
        UUID.randomUUID(),
        fixture.repairId(),
        new QueueRepairRequest(
            version,
            2,
            true,
            RepairLogisticsPlanningMode.FIXED_DATE,
            LocalDate.of(2026, 8, 6)));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();

    reset(dependencies);
    when(
            dependencies.createDriverTask(
                any(), any(MaintenanceDependencyGateway.DriverTaskCommand.class)))
        .thenThrow(new IllegalStateException("driver intake unavailable"));
    for (int attempt = 0; attempt < 4; attempt++) {
      if (attempt > 0) makeReconciliationDue(fixture.repairId(), "CREATE_DRIVER_TASK");
      deferOtherReconciliations(fixture.repairId(), "CREATE_DRIVER_TASK");
      assertThat(service.reconcileOneTask()).isTrue();
    }
    assertThat(repairs.findById(fixture.repairId()).orElseThrow())
        .satisfies(repair -> {
          assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
          assertThat(repair.getDeliveryState()).isEqualTo("QUARANTINED");
          assertThat(repair.getTaskGenerationState()).isEqualTo("FAILED");
          assertThat(repair.getReconciliationState()).isEqualTo("RECONCILIATION_REQUIRED");
        });
    return fixture;
  }

  private static MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation
      driverTaskCompensation(
          UUID repairId,
          MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome outcome) {
    if (outcome == MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.ABSENT) {
      return new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
          repairId,
          MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
          outcome,
          null,
          null,
          null,
          null,
          null,
          null,
          null,
          null);
    }
    return new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
        repairId,
        MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
        outcome,
        UUID.randomUUID(),
        0L,
        "SCHEDULED",
        null,
        null,
        null,
        null,
        null);
  }

  private void stubQueueDependencies(RepairFixture fixture) {
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 7, fixture.warehouseId(), "БТ-42", "FREE"));
    when(dependencies.acquireLease(
        any(), eq(fixture.rentalItemId()), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId, 0, fixture.rentalItemId(), "MAINTENANCE_REPAIR", fixture.repairId(), 11,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(dependencies.fencedStatus(
        any(), eq(fixture.rentalItemId()), eq(fixture.warehouseId()), eq(7L),
        eq(leaseId), eq(11L), eq("MAINTENANCE_REPAIR"),
        eq(fixture.repairId().toString()), eq("QUEUE_TO_REPAIR"), eq(false)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 8, fixture.warehouseId(), "БТ-42", "REPAIR"));
  }

  private UUID insertDraftCatalog(UUID warehouseId, String sourceSha256) {
    UUID id = UUID.randomUUID();
    jdbc.update("""
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,created_at,updated_at)
        values (?,0,?,'DRAFT',?,0,0,?::jsonb::text,clock_timestamp(),clock_timestamp())
        """, id, warehouseId, sourceSha256, """
        {"valid":true,"errorCount":0,"warningCount":0,
         "reportSha256":"0000000000000000000000000000000000000000000000000000000000000000"}
        """);
    jdbc.update("""
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('CATALOG_VERSION',?,0,?,clock_timestamp())
        """, id.toString(), UUID.randomUUID());
    return id;
  }

  private CreateDirectRepairRequest directRepairRequest(
      UUID warehouseId,
      UUID rentalItemId,
      LocalDate dispatchDate,
      String sourceParty) {
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    UUID lineId = UUID.randomUUID();
    CatalogNodeSnapshot snapshot =
        new CatalogNodeSnapshot(
            work.catalogVersionId(),
            work.nodeId(),
            CatalogNodeType.WORK,
            "Проверочная работа",
            "шт",
            "100.00",
            15,
            work.routing(),
            null,
            false,
            null);
    EstimateLineInput line =
        new EstimateLineInput(
            lineId,
            snapshot,
            EstimateLineType.WORK,
            "Проверочная работа",
            "шт",
            "1",
            "100.00",
            15,
            null,
            List.of());
    PlanStageInput plan =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            work.routing(),
            List.of(lineId),
            lineId,
            "",
            null);
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        dispatchDate,
        sourceParty,
        List.of(line),
        List.of(plan),
        List.of());
  }

  private CreateDirectRepairRequest customWorkRepairRequest(
      UUID warehouseId, UUID rentalItemId, int normativeMinutes) {
    TestCatalogWork route = ensureTestCatalogWork(warehouseId);
    UUID lineId = UUID.randomUUID();
    EstimateLineInput line =
        new EstimateLineInput(
            lineId,
            null,
            EstimateLineType.WORK,
            "Пользовательская работа",
            "шт",
            "1",
            "100.00",
            normativeMinutes,
            null,
            List.of());
    PlanStageInput stage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            route.routing(),
            List.of(lineId),
            lineId,
            "",
            null);
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 7, 17),
        null,
        List.of(line),
        List.of(stage),
        List.of());
  }

  private CreateDirectRepairRequest catalogWorkRepairRequest(
      UUID warehouseId, UUID rentalItemId, TestCatalogWork work) {
    UUID lineId = UUID.randomUUID();
    CatalogNodeSnapshot snapshot =
        new CatalogNodeSnapshot(
            work.catalogVersionId(),
            work.nodeId(),
            CatalogNodeType.WORK,
            work.name(),
            "шт",
            "100.00",
            15,
            work.routing(),
            null,
            work.forcesCapitalRepair(),
            null);
    EstimateLineInput line =
        new EstimateLineInput(
            lineId,
            snapshot,
            EstimateLineType.WORK,
            work.name(),
            "шт",
            "1",
            "100.00",
            15,
            null,
            List.of());
    PlanStageInput stage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            work.routing(),
            List.of(lineId),
            lineId,
            "",
            null);
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 7, 17),
        null,
        List.of(line),
        List.of(stage),
        List.of());
  }

  private CreateDirectRepairRequest workAndMaterialRepairRequest(
      UUID warehouseId,
      UUID rentalItemId,
      TestCatalogWork work,
      TestCatalogMaterial material) {
    UUID workLineId = UUID.randomUUID();
    UUID materialLineId = UUID.randomUUID();
    CatalogNodeSnapshot workSnapshot =
        new CatalogNodeSnapshot(
            work.catalogVersionId(),
            work.nodeId(),
            CatalogNodeType.WORK,
            work.name(),
            "шт",
            "100.00",
            15,
            work.routing(),
            null,
            work.forcesCapitalRepair(),
            null);
    CatalogNodeSnapshot materialSnapshot =
        new CatalogNodeSnapshot(
            material.catalogVersionId(),
            material.nodeId(),
            CatalogNodeType.MATERIAL,
            material.name(),
            "шт",
            "250.00",
            0,
            material.routing(),
            null,
            false,
            material.characteristic());
    EstimateLineInput workLine =
        new EstimateLineInput(
            workLineId,
            workSnapshot,
            EstimateLineType.WORK,
            work.name(),
            "шт",
            "1",
            "100.00",
            15,
            null,
            List.of());
    EstimateLineInput materialLine =
        new EstimateLineInput(
            materialLineId,
            materialSnapshot,
            EstimateLineType.MATERIAL,
            material.name(),
            "шт",
            "1",
            "250.00",
            0,
            null,
            List.of());
    PlanStageInput stage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            work.routing(),
            List.of(workLineId, materialLineId),
            workLineId,
            "",
            null);
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 7, 17),
        null,
        List.of(workLine, materialLine),
        List.of(stage),
        List.of());
  }

  private CreateDirectRepairRequest twoStageDirectRepairRequest(
      UUID warehouseId, UUID rentalItemId) {
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    CatalogNodeSnapshot snapshot =
        new CatalogNodeSnapshot(
            work.catalogVersionId(),
            work.nodeId(),
            CatalogNodeType.WORK,
            "Проверочная работа",
            "шт",
            "100.00",
            15,
            work.routing(),
            null,
            false,
            null);
    UUID firstLineId = UUID.randomUUID();
    UUID secondLineId = UUID.randomUUID();
    EstimateLineInput firstLine =
        new EstimateLineInput(
            firstLineId,
            snapshot,
            EstimateLineType.WORK,
            "Первая работа",
            "шт",
            "1",
            "100.00",
            15,
            null,
            List.of());
    EstimateLineInput secondLine =
        new EstimateLineInput(
            secondLineId,
            snapshot,
            EstimateLineType.WORK,
            "Вторая работа",
            "шт",
            "1",
            "100.00",
            15,
            null,
            List.of());
    PlanStageInput firstStage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            0,
            work.routing(),
            List.of(firstLineId),
            firstLineId,
            "",
            null);
    PlanStageInput secondStage =
        new PlanStageInput(
            UUID.randomUUID(),
            RepairStageKind.REPAIR_WORK,
            1,
            work.routing(),
            List.of(secondLineId),
            secondLineId,
            "",
            null);
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 7, 17),
        null,
        List.of(firstLine, secondLine),
        List.of(firstStage, secondStage),
        List.of());
  }

  private CreateDirectRepairRequest workerTaskContent(
      UUID warehouseId, UUID rentalItemId, UUID workMediaId) {
    TestCatalogWork work = ensureTestCatalogWork(warehouseId);
    TestCatalogMaterial material = ensureTestCatalogMaterial(work);
    UUID workLineId = UUID.randomUUID();
    UUID materialLineId = UUID.randomUUID();
    CatalogNodeSnapshot workSnapshot = new CatalogNodeSnapshot(
        work.catalogVersionId(),
        work.nodeId(),
        CatalogNodeType.WORK,
        "Проверочная работа",
        "шт",
        "100.00",
        15,
        work.routing(),
        null,
        false,
        null);
    CatalogNodeSnapshot materialSnapshot = new CatalogNodeSnapshot(
        material.catalogVersionId(),
        material.nodeId(),
        CatalogNodeType.MATERIAL,
        "Профлист",
        "лист",
        "250.00",
        0,
        material.routing(),
        null,
        false,
        null);
    EstimateLineInput workLine = new EstimateLineInput(
        workLineId,
        workSnapshot,
        EstimateLineType.WORK,
        "Замена профлиста",
        "шт",
        "2.5",
        "100.00",
        15,
        "Проверить внешний угол",
        List.of(new MediaReferenceInput(workMediaId, 2L)));
    EstimateLineInput materialLine = new EstimateLineInput(
        materialLineId,
        materialSnapshot,
        EstimateLineType.MATERIAL,
        "Профлист",
        "лист",
        "3",
        "250.00",
        0,
        "Принять по количеству",
        List.of());
    PlanStageInput plan = new PlanStageInput(
        UUID.randomUUID(),
        RepairStageKind.REPAIR_WORK,
        0,
        work.routing(),
        List.of(workLineId, materialLineId),
        workLineId,
        "Срочно",
        null);
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 7, 17),
        null,
        List.of(workLine, materialLine),
        List.of(plan),
        List.of());
  }

  private CreateReworkRequest reworkRequest(
      RepairFixture source, long expectedVersion, String reason) {
    CreateDirectRepairRequest content =
        directRepairRequest(
            source.warehouseId(),
            source.rentalItemId(),
            LocalDate.of(2026, 7, 17),
            null);
    return new CreateReworkRequest(
        expectedVersion,
        reason,
        content.lines(),
        content.plan(),
        List.of());
  }

  private TestCatalogWork ensureTestCatalogWork(UUID warehouseId) {
    List<Map<String, Object>> existing =
        jdbc.queryForList(
            """
            select version.id catalog_version_id,node.node_id,node.name,
                   node.routing_queue_id,node.routing_queue_name,
                   node.routing_queue_type,node.forces_capital_repair
             from catalog_version version
              join catalog_node node on node.catalog_version_id=version.id
             where version.state='ACTIVE'
               and node.node_type='WORK' and node.active
             order by node.name,node.node_id
             limit 1
            """);
    if (!existing.isEmpty()) {
      Map<String, Object> value = existing.getFirst();
      return new TestCatalogWork(
          (UUID) value.get("catalog_version_id"),
          (UUID) value.get("node_id"),
          (String) value.get("name"),
          new RoutingSnapshot(
              (UUID) value.get("routing_queue_id"),
              (String) value.get("routing_queue_name"),
              (String) value.get("routing_queue_type")),
          (Boolean) value.get("forces_capital_repair"));
    }

    UUID catalogVersionId =
        jdbc.query(
                """
                select id
                  from catalog_version
                 where state='ACTIVE'
                 limit 1
                """,
                (result, row) -> result.getObject(1, UUID.class))
            .stream()
            .findFirst()
            .orElseGet(
                () -> {
                  UUID id = UUID.randomUUID();
                  jdbc.update(
                      """
                      insert into catalog_version(
                        id,version,warehouse_id,state,source_sha256,node_count,link_count,
                        validation_report,activated_at,created_at,updated_at)
                      values (?,0,?,'ACTIVE',?,0,0,?,clock_timestamp(),
                              clock_timestamp(),clock_timestamp())
                      """,
                      id,
                      warehouseId,
                      "e".repeat(64),
                      """
                      {"valid":true,"errorCount":0,"warningCount":0,
                       "reportSha256":"0000000000000000000000000000000000000000000000000000000000000000"}
                      """);
                  return id;
                });
    UUID nodeId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,furniture_equipment_id,furniture_equipment_name,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,canvas_x,canvas_y,
          routing_queue_id,routing_queue_name,routing_queue_type,comment)
        values (?,?,?,'WORK','Проверочная работа',true,null,
                false,null,null,'шт',10000,15,true,false,true,null,null,?,
                'Repair','REPAIR',null)
        """,
        UUID.randomUUID(),
        nodeId,
        catalogVersionId,
        queueId);
    jdbc.update(
        "update catalog_version set node_count=node_count+1 where id=?",
        catalogVersionId);
    return new TestCatalogWork(
        catalogVersionId,
        nodeId,
        "Проверочная работа",
        new RoutingSnapshot(queueId, "Repair", "REPAIR"),
        false);
  }

  private TestCatalogWork insertTestCatalogWork(
      TestCatalogWork base, String name, boolean forcesCapitalRepair) {
    UUID nodeId = UUID.randomUUID();
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,furniture_equipment_id,furniture_equipment_name,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,canvas_x,canvas_y,
          routing_queue_id,routing_queue_name,routing_queue_type,comment,forces_capital_repair)
        values (?,?,?,'WORK',?,true,null,
                false,null,null,'шт',10000,15,true,false,true,null,null,?,
                ?,?,null,?)
        """,
        UUID.randomUUID(),
        nodeId,
        base.catalogVersionId(),
        name,
        base.routing().queueId(),
        base.routing().queueName(),
        base.routing().queueType(),
        forcesCapitalRepair);
    jdbc.update(
        "update catalog_version set node_count=node_count+1 where id=?",
        base.catalogVersionId());
    return new TestCatalogWork(
        base.catalogVersionId(),
        nodeId,
        name,
        base.routing(),
        forcesCapitalRepair);
  }

  private TestCatalogMaterial ensureTestCatalogMaterial(TestCatalogWork work) {
    UUID nodeId = UUID.randomUUID();
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,furniture_equipment_id,furniture_equipment_name,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,canvas_x,canvas_y,
          routing_queue_id,routing_queue_name,routing_queue_type,comment)
        values (?,?,?,'MATERIAL','Профлист',true,null,
                false,null,null,'лист',25000,0,true,false,true,null,null,?,
                ?,?,null)
        """,
        UUID.randomUUID(),
        nodeId,
        work.catalogVersionId(),
        work.routing().queueId(),
        work.routing().queueName(),
        work.routing().queueType());
    jdbc.update(
        "update catalog_version set node_count=node_count+1 where id=?",
        work.catalogVersionId());
    return new TestCatalogMaterial(
        work.catalogVersionId(),
        nodeId,
        "Профлист",
        work.routing(),
        null);
  }

  private TestCatalogMaterial insertCharacteristicMaterial(
      TestCatalogWork work,
      String name,
      UUID characteristicId,
      String characteristicName) {
    UUID nodeId = UUID.randomUUID();
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,furniture_equipment_id,furniture_equipment_name,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,canvas_x,canvas_y,
          routing_queue_id,routing_queue_name,routing_queue_type,comment,
          characteristic_id,characteristic_name)
        values (?,?,?,'MATERIAL',?,true,null,
                false,null,null,'шт',25000,0,true,false,true,null,null,?,
                ?,?,null,?,?)
        """,
        UUID.randomUUID(),
        nodeId,
        work.catalogVersionId(),
        name,
        work.routing().queueId(),
        work.routing().queueName(),
        work.routing().queueType(),
        characteristicId,
        characteristicName);
    jdbc.update(
        "update catalog_version set node_count=node_count+1 where id=?",
        work.catalogVersionId());
    return new TestCatalogMaterial(
        work.catalogVersionId(),
        nodeId,
        name,
        work.routing(),
        new CabinCharacteristicReference(
            characteristicId, characteristicName));
  }

  private static CatalogNodeInput furnitureCategory(UUID categoryId) {
    return new CatalogNodeInput(
        categoryId,
        CatalogNodeType.CATEGORY,
        "Furniture",
        true,
        null,
        true,
        null,
        null,
        null,
        0,
        false,
        false,
        true,
        null,
        null,
        null,
        null,
        null,
        false,
        null);
  }

  private static CatalogNodeInput furnitureMaterial(
      UUID nodeId,
      UUID categoryId,
      String name,
      FurnitureEquipmentReference equipment) {
    return new CatalogNodeInput(
        nodeId,
        CatalogNodeType.MATERIAL,
        name,
        true,
        categoryId,
        false,
        equipment,
        "piece",
        "100.00",
        0,
        true,
        false,
        false,
        null,
        null,
        null,
        null,
        null,
        false,
        null);
  }

  private void exhaustFurnitureLink(UUID nodeId) {
    for (int attempt = 0; attempt < FurnitureEquipmentLinkStore.MAX_ATTEMPTS; attempt++) {
      FurnitureEquipmentLinkStore.WorkItem work = furnitureEquipmentLinks
          .claimExact(nodeId, Duration.ofMinutes(2))
          .orElseThrow();
      furnitureEquipmentLinks.failed(
          work,
          new MaintenanceDependencyException(
              org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
              "asset unavailable"));
    }
    assertThat(furnitureEquipmentLinks.require(nodeId).state()).isEqualTo("REVIEW_REQUIRED");
  }

  private void reconcileCatalogRoute(UUID queueId) {
    when(dependencies.registerCatalogPosition(eq(queueId), anyString()))
        .thenAnswer(invocation -> {
          String externalReference = invocation.getArgument(1);
          return new MaintenanceDependencyGateway.CatalogPositionReference(
              UUID.nameUUIDFromBytes(
                  externalReference.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
              0L,
              queueId,
              "CATALOG_POSITION",
              externalReference);
        });
    assertThat(service.reconcileOneTask()).isTrue();
  }

  private void deferOtherReconciliations(UUID repairId, String operation) {
    jdbc.update(
        """
        update integration_reconciliation
           set next_attempt_at=clock_timestamp() + interval '1 day'
         where not (repair_id=? and operation_type=?)
           and state in ('PENDING','RETRY_PENDING')
        """,
        repairId,
        operation);
  }

  private Void activateAfter(CountDownLatch start, UUID catalogId) throws Exception {
    start.await();
    service.activateCatalog(
        UUID.randomUUID(), UUID.randomUUID(), catalogId, new VersionCommand(0L));
    return null;
  }

  private static PlanStageInput stage() {
    return stage(0, null);
  }

  private RepairDecisionRequest acceptanceDecision(
      RepairFixture fixture, long expectedVersion, String comment) {
    UUID mediaId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status ->
        service.applyInboundMediaFact(
            mediaId,
            1,
            "MAINTENANCE_ACCEPTANCE",
            fixture.repairId(),
            fixture.warehouseId(),
            "READY",
            "{}",
            1));
    return new RepairDecisionRequest(
        expectedVersion,
        comment,
        List.of(new MediaReferenceInput(mediaId, 1L)));
  }

  private static PlanStageInput stage(int order, OffsetDateTime taskDeadline) {
    return new PlanStageInput(
        UUID.randomUUID(), RepairStageKind.REPAIR_WORK, order,
        new RoutingSnapshot(UUID.randomUUID(), "REPAIR", "REPAIR"), taskDeadline);
  }

  private record RepairFixture(
      UUID repairId,
      UUID externalTaskId,
      UUID warehouseId,
      UUID rentalItemId) {}
  private record TestCatalogWork(
      UUID catalogVersionId,
      UUID nodeId,
      String name,
      RoutingSnapshot routing,
      boolean forcesCapitalRepair) {}
  private record TestCatalogMaterial(
      UUID catalogVersionId,
      UUID nodeId,
      String name,
      RoutingSnapshot routing,
      CabinCharacteristicReference characteristic) {}
  private record RegisteredRepairFixture(RepairFixture repair, UUID queueEntryId) {}
  private record MultiStageRepairFixture(
      RepairFixture repair, List<UUID> queueEntryIds) {}
  private record QueuedReworkFixture(
      RepairFixture source, RepairFixture child, UUID queueEntryId) {}
  private record ReworkFixture(RepairFixture source, RepairFixture child) {}
}
