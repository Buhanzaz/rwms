package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceReconciliation;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairLogisticsPlanningMode;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceJsonbCanonicalizer;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceReconciliationRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.InventoryMaintenanceService;
import dev.buhanzaz.rwms.maintenance.service.InventoryPublicationReconciliationService;
import dev.buhanzaz.rwms.maintenance.service.InventoryPublicationSuccessorActivator;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.maintenance.task-reconciliation.initial-delay=1h",
    "rwms.maintenance.task-reconciliation.delay=1h",
    "AUTH_ISSUER=http://auth.test",
    "PANEL_ORIGIN=http://panel.test"
})
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class MaintenanceInventoryBoundaryIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired InventoryMaintenanceService inventory;
  @Autowired InventoryPublicationReconciliationService publications;
  @Autowired MaintenanceApplicationService maintenance;
  @Autowired InventoryPublicationSuccessorActivator successorActivator;
  @Autowired MaintenanceJsonbCanonicalizer canonicalizer;
  @Autowired ObjectMapper mapper;
  @Autowired RentalItemFactProjectionRepository rentalItems;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired RepairStageRepository repairStages;
  @Autowired MaintenanceReconciliationRepository inventoryReconciliations;
  @Autowired MediaFactProjectionRepository mediaFacts;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean MaintenanceDependencyGateway dependencies;

  private UUID warehouseId;
  private UUID catalogId;
  private UUID workNodeId;

  private static UUID stableKey(String operation, UUID inventoryId, UUID findingId) {
    return UUID.nameUUIDFromBytes(
        (operation + ":" + inventoryId + ":" + findingId)
            .getBytes(StandardCharsets.UTF_8));
  }

  @BeforeEach
  void reset() {
    jdbc.execute("""
        truncate table
          catalog_version,
          maintenance_repair,
          inventory_repair_source,
          inventory_publication_source,
          inventory_publication_source_operation,
          media_fact_projection,
          rental_item_fact_projection,
          integration_reconciliation,
          event_stream_head
        cascade
        """);
    warehouseId = UUID.randomUUID();
    catalogId = UUID.randomUUID();
    workNodeId = UUID.randomUUID();
    insertActiveCatalog(catalogId, workNodeId, "WORK_A");
    org.mockito.Mockito.reset(dependencies);
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(invocation -> {
          UUID requestedWarehouseId = invocation.getArgument(0);
          List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
              invocation.getArgument(1);
          return new MaintenanceDependencyGateway.RoutingPreflight(
              requestedWarehouseId,
              true,
              List.of(),
              List.of(),
              List.of(),
              requirements.stream()
                  .map(requirement -> new MaintenanceDependencyGateway.RoutingQueueSnapshot(
                      requirement.queueDefinitionId(),
                      requirement.queueDefinitionId(),
                      requirement.queueDefinitionId().toString(),
                      requirement.type()))
                  .toList());
        });
  }

  @Test
  void autoFreezeSurvivesCatalogActivationAndPermanentUpsertReplaysWithMaintenanceWork() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FreezeInventoryPlanRequest freeze = autoRequest(inventoryId, findingId, List.of());

    InventoryMaintenanceService.FreezeResult first = inventory.freeze(freeze);
    assertThat(first.replayed()).isFalse();
    assertThat(first.response().snapshot().catalogVersionId()).isEqualTo(catalogId);
    assertThat(first.response().snapshot().stages())
        .singleElement()
        .satisfies(stage -> {
          assertThat(stage.catalogNodeId()).isEqualTo(workNodeId);
          assertThat(stage.catalogNodeName()).isEqualTo("Repair work");
          assertThat(stage.routing().queueName()).isEqualTo("Repair");
          assertThat(stage.normativeDurationMinutes()).isEqualTo(45);
        });
    assertThat(first.response().snapshot().lines())
        .singleElement()
        .satisfies(line -> {
          assertThat(line.aggregationKind()).isEqualTo(InventoryPlanLineKind.CATALOG);
          assertThat(line.quantity()).isEqualTo("2.500000");
          assertThat(line.unitPriceMinor()).isEqualTo(12500);
          assertThat(line.normativeMinutes()).isEqualTo("45.000");
          assertThat(line.groupComment()).isEqualTo("group comment");
        });
    assertThatThrownBy(() -> jdbc.update("""
        update inventory_repair_source
        set plan_snapshot='{}'::jsonb
        where inventory_id=? and finding_id=?
        """, inventoryId, findingId))
        .hasMessageContaining("inventory repair plan/source fields are immutable");

    UUID laterCatalog = UUID.randomUUID();
    jdbc.update("update catalog_version set state='SUPERSEDED' where id=?", catalogId);
    insertActiveCatalog(laterCatalog, UUID.randomUUID(), "WORK_B");
    InventoryMaintenanceService.FreezeResult replayedFreeze = inventory.freeze(freeze);
    assertThat(replayedFreeze.replayed()).isTrue();
    assertThat(replayedFreeze.response()).isEqualTo(first.response());

    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, "FREE", 7));
    UpsertInventoryRepairRequest upsert = new UpsertInventoryRepairRequest(
        warehouseId, 3L, rentalItemId, 7L, LocalDate.of(2026, 7, 17),
        first.response().fingerprint(), first.response().snapshot());
    InventoryMaintenanceService.UpsertResult created = inventory.upsert(
        inventoryId, findingId, upsert);
    InventoryMaintenanceService.UpsertResult replayed = inventory.upsert(
        inventoryId, findingId, upsert);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.repairId()).isEqualTo(created.repairId());
    assertThat(replayed.source().sourceFingerprint()).isEqualTo(created.source().sourceFingerprint());
    assertThat(jdbc.queryForObject("""
        select version from inventory_repair_source
        where inventory_id=? and finding_id=?
        """, Long.class, inventoryId, findingId)).isEqualTo(1L);
    assertThat(jdbc.queryForObject(
        "select origin from maintenance_repair where id=?", String.class, created.repairId()))
        .isEqualTo(RepairOrigin.INVENTORY.name());
    assertThat(jdbc.queryForMap(
        "select priority,source_party from maintenance_repair where id=?", created.repairId()))
        .containsEntry("priority", 3)
        .containsEntry("source_party", "Инвентаризация");
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation
        where repair_id=? and dependency_type='ASSET' and operation_type='QUEUE_REPAIR'
        """, Integer.class, created.repairId())).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from repair_stage where repair_id=?", Integer.class, created.repairId()))
        .isOne();
    assertThat(
            inventoryReconciliations
                .findByDependencyTypeAndOperationTypeAndIdempotencyKey(
                    "ASSET",
                    "QUEUE_REPAIR",
                    stableKey("inventory-queue-repair", inventoryId, findingId))
                .map(MaintenanceReconciliation::getRepairId))
        .contains(created.repairId());

    UUID queueIdentity = jdbc.queryForObject("""
        select idempotency_key from integration_reconciliation
        where repair_id=? and dependency_type='ASSET' and operation_type='QUEUE_REPAIR'
        """, UUID.class, created.repairId());
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 7, warehouseId, "FREE"));
    when(dependencies.acquireLease(
        any(), eq(rentalItemId), eq(7L), eq("MAINTENANCE_REPAIR"),
        eq(created.repairId().toString())))
        .thenThrow(new IllegalStateException("asset dependency unavailable"));

    assertThat(maintenance.reconcileOneTask()).isTrue();
    MaintenanceRepair afterFailure = repairs.findById(created.repairId()).orElseThrow();
    assertThat(afterFailure.getExecutionState()).isEqualTo(RepairExecutionState.DRAFT);
    assertThat(afterFailure.getReconciliationState()).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, String.class, created.repairId())).isEqualTo("RETRY_PENDING");
    ArgumentCaptor<UUID> dependencyIdentity = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies).acquireLease(
        dependencyIdentity.capture(), eq(rentalItemId), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(created.repairId().toString()));

    jdbc.update("""
        update integration_reconciliation set next_attempt_at=clock_timestamp()
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, created.repairId());
    org.mockito.Mockito.reset(dependencies);
    when(dependencies.preflightMaintenanceRouting(eq(warehouseId), anyList()))
        .thenAnswer(invocation -> {
          List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
              invocation.getArgument(1);
          return new MaintenanceDependencyGateway.RoutingPreflight(
              warehouseId,
              true,
              List.of(),
              List.of(),
              List.of(),
              requirements.stream()
                  .map(requirement -> new MaintenanceDependencyGateway.RoutingQueueSnapshot(
                      requirement.queueDefinitionId(),
                      requirement.queueDefinitionId(),
                      requirement.queueDefinitionId().toString(),
                      requirement.type()))
                  .toList());
        });
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 7, warehouseId, "FREE"));
    when(dependencies.acquireLease(
        eq(dependencyIdentity.getValue()), eq(rentalItemId), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(created.repairId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId, 0, rentalItemId, "MAINTENANCE_REPAIR", created.repairId(), 11,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    when(dependencies.fencedStatus(
        any(), eq(rentalItemId), eq(warehouseId), eq(7L), eq(leaseId), eq(11L),
        eq("MAINTENANCE_REPAIR"), eq(created.repairId().toString()),
        eq("QUEUE_TO_REPAIR"), eq(false)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 8, warehouseId, "REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();
    MaintenanceRepair queued = repairs.findById(created.repairId()).orElseThrow();
    assertThat(queued.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(queued.getLeaseId()).isEqualTo(leaseId);
    assertThat(queued.getFencingToken()).isEqualTo(11L);
    assertThat(jdbc.queryForObject("""
        select idempotency_key from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, UUID.class, created.repairId())).isEqualTo(queueIdentity);
    verify(dependencies).acquireLease(
        eq(dependencyIdentity.getValue()), eq(rentalItemId), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(created.repairId().toString()));

    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 8, warehouseId, "DEMO-001", "REPAIR"));
    UUID queueEntryId = UUID.randomUUID();
    when(dependencies.registerTask(
        any(), eq(queued.getExternalTaskId()), eq(created.repairId()), eq(warehouseId),
        eq(rentalItemId), nullable(String.class),
        any(LocalDate.class), anyInt(), eq(6), anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            queued.getExternalTaskId(), 0, "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, queueEntryId, 0))));
    assertThat(maintenance.reconcileOneTask()).isTrue();
    RepairResponse synchronizedRepair = maintenance.repair(created.repairId(), warehouseId);
    assertThat(synchronizedRepair.lease().leaseId()).isEqualTo(leaseId);
    assertThat(synchronizedRepair.plan().stages()).singleElement().satisfies(stage -> {
      assertThat(stage.taskSync().generationState()).isEqualTo(GenerationState.GENERATED);
      assertThat(stage.taskSync().externalTaskId()).isEqualTo(queued.getExternalTaskId());
    });

    InventoryMaintenanceService.UpsertResult harmlessDuplicate = inventory.upsert(
        inventoryId, findingId, upsert);
    assertThat(harmlessDuplicate.replayed()).isTrue();
    assertThat(harmlessDuplicate.repairId()).isEqualTo(created.repairId());
    assertThat(jdbc.queryForObject("""
        select count(*) from integration_reconciliation where repair_id=?
        """, Integer.class, created.repairId())).isEqualTo(2);
    assertThat(maintenance.reconcileOneTask()).isFalse();

    UpsertInventoryRepairRequest changed = new UpsertInventoryRepairRequest(
        warehouseId, 3L, rentalItemId, 8L, LocalDate.of(2026, 7, 17),
        first.response().fingerprint(), first.response().snapshot());
    assertThatThrownBy(() -> inventory.upsert(inventoryId, findingId, changed))
        .isInstanceOf(MaintenanceConflictException.class);
  }

  @Test
  void autoFreezeUsesTheRouteInheritedThroughEveryCatalogGraphLevel() {
    UUID routedCategoryId = UUID.randomUUID();
    UUID intermediateNodeId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    jdbc.update(
        """
        update catalog_node
           set routing_queue_id=null,routing_queue_name=null,routing_queue_type=null
         where catalog_version_id=? and node_id=?
        """,
        catalogId,
        workNodeId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,unit,price_minor,duration_minutes,include_in_estimate,
          common_item,show_in_main_menu,routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,'CATEGORY','Категория маршрута',true,null,false,null,null,0,false,false,false,
                ?,'Внешние работы','REPAIR')
        """,
        UUID.randomUUID(),
        routedCategoryId,
        catalogId,
        queueId);
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,
          furniture_category,unit,price_minor,duration_minutes,include_in_estimate,
          common_item,show_in_main_menu)
        values (?,?,?,'SUBCATEGORY','Промежуточный блок',true,null,false,null,null,0,false,false,false)
        """,
        UUID.randomUUID(), intermediateNodeId, catalogId);
    jdbc.update(
        """
        insert into catalog_link(
          row_id,link_id,catalog_version_id,source_node_id,target_node_id,link_type,sort_order)
        values
          (?,?,?,?,?,'FOLLOW_UP',10),
          (?,?,?,?,?,'DEPENDENCY',20)
        """,
        UUID.randomUUID(), UUID.randomUUID(), catalogId, routedCategoryId, intermediateNodeId,
        UUID.randomUUID(), UUID.randomUUID(), catalogId, intermediateNodeId, workNodeId);

    FrozenInventoryPlanResponse frozen = inventory.freeze(
        autoRequest(UUID.randomUUID(), UUID.randomUUID(), List.of())).response();

    assertThat(frozen.snapshot().lines()).singleElement().satisfies(line -> {
      assertThat(line.routing()).isNotNull();
      assertThat(line.routing().queueId()).isEqualTo(queueId);
      assertThat(line.routing().queueName()).isEqualTo("Внешние работы");
    });
    assertThat(frozen.snapshot().stages()).singleElement().satisfies(stage -> {
      assertThat(stage.routing().queueId()).isEqualTo(queueId);
      assertThat(stage.routing().queueType()).isEqualTo("REPAIR");
    });
  }

  @Test
  void frozenInventoryPlanningCreatesTheSameCanonicalDriverTaskWithInventorySource() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    LocalDate scheduledDate = LocalDate.of(2026, 8, 5);
    FreezeInventoryPlanRequest freeze =
        new FreezeInventoryPlanRequest(
            warehouseId,
            inventoryId,
            findingId,
            3L,
            InventoryPlanMode.AUTO,
            List.of(
                catalogLine(
                    workNodeId, "1", null, List.of())),
            List.of(),
            List.of(),
            4,
            null,
            true,
            RepairLogisticsPlanningMode.FIXED_DATE,
            scheduledDate);

    FrozenInventoryPlanResponse frozen =
        inventory.freeze(freeze).response();

    assertThat(frozen.snapshot().logisticsPlanningMode())
        .isEqualTo(RepairLogisticsPlanningMode.FIXED_DATE);
    assertThat(frozen.snapshot().logisticsScheduledDate())
        .isEqualTo(scheduledDate);
    assertThat(frozen.snapshot().movementToRepair()).isTrue();

    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(
            rentalItemId, warehouseId, "FREE", 7));
    InventoryMaintenanceService.UpsertResult created =
        inventory.upsert(
            inventoryId,
            findingId,
            new UpsertInventoryRepairRequest(
                warehouseId,
                3L,
                rentalItemId,
                7L,
                LocalDate.of(2026, 8, 1),
                frozen.fingerprint(),
                frozen.snapshot()));
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId,
                7,
                warehouseId,
                "БТ-INV-1",
                "FREE"));
    when(
            dependencies.acquireLease(
                any(),
                eq(rentalItemId),
                eq(7L),
                eq("MAINTENANCE_REPAIR"),
                eq(created.repairId().toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0,
                rentalItemId,
                "MAINTENANCE_REPAIR",
                created.repairId(),
                11,
                OffsetDateTime.now(ZoneOffset.UTC)
                    .plusMinutes(15)));
    when(
            dependencies.fencedStatus(
                any(),
                eq(rentalItemId),
                eq(warehouseId),
                eq(7L),
                eq(leaseId),
                eq(11L),
                eq("MAINTENANCE_REPAIR"),
                eq(created.repairId().toString()),
                eq("QUEUE_TO_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId,
                8,
                warehouseId,
                "БТ-INV-1",
                "REPAIR"));
    assertThat(maintenance.reconcileOneTask()).isTrue();

    jdbc.update(
        """
        update integration_reconciliation
           set next_attempt_at=clock_timestamp() + interval '1 day'
         where repair_id=? and operation_type='REGISTER_TASK'
        """,
        created.repairId());
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
    assertThat(maintenance.reconcileOneTask()).isTrue();

    ArgumentCaptor<MaintenanceDependencyGateway.DriverTaskCommand>
        command =
            ArgumentCaptor.forClass(
                MaintenanceDependencyGateway.DriverTaskCommand.class);
    verify(dependencies).createDriverTask(any(), command.capture());
    assertThat(command.getValue())
        .satisfies(
            value -> {
              assertThat(value.sourceType())
                  .isEqualTo("INVENTORY");
              assertThat(value.sourceId()).isEqualTo(findingId);
              assertThat(value.repairId())
                  .isEqualTo(created.repairId());
              assertThat(value.planningMode())
                  .isEqualTo(
                      RepairLogisticsPlanningMode.FIXED_DATE);
              assertThat(value.scheduledDate())
                  .isEqualTo(scheduledDate);
            });
    assertThat(
            jdbc.queryForObject(
                """
                select logistics_planning_mode
                from maintenance_repair where id=?
                """,
                String.class,
                created.repairId()))
        .isEqualTo("FIXED_DATE");
    assertThat(
            jdbc.queryForObject(
                """
                select logistics_scheduled_date
                from maintenance_repair where id=?
                """,
                LocalDate.class,
                created.repairId()))
        .isEqualTo(scheduledDate);
  }

  @Test
  void jpaReconciliationFailureRollsBackRepairSourceEventAndCanRetry() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen =
        inventory.freeze(autoRequest(inventoryId, findingId, List.of())).response();
    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    UpsertInventoryRepairRequest upsert =
        new UpsertInventoryRepairRequest(
            warehouseId,
            3L,
            rentalItemId,
            7L,
            LocalDate.of(2026, 7, 17),
            frozen.fingerprint(),
            frozen.snapshot());

    jdbc.execute(
        """
        create or replace function fail_inventory_reconciliation() returns trigger language plpgsql
        as $$ begin raise exception 'forced inventory reconciliation failure'; end $$
        """);
    jdbc.execute(
        """
        create trigger fail_inventory_reconciliation before insert on integration_reconciliation
        for each row when (new.operation_type='QUEUE_REPAIR')
        execute function fail_inventory_reconciliation()
        """);
    try {
      assertThatThrownBy(() -> inventory.upsert(inventoryId, findingId, upsert))
          .hasMessageContaining("forced inventory reconciliation failure");
      assertThat(jdbc.queryForObject("select count(*) from maintenance_repair", Integer.class))
          .isZero();
      assertThat(
              jdbc.queryForObject(
                  "select count(*) from inventory_repair_source where repair_id is not null",
                  Integer.class))
          .isZero();
      assertThat(jdbc.queryForObject("select count(*) from domain_event", Integer.class)).isZero();
    } finally {
      jdbc.execute("drop trigger fail_inventory_reconciliation on integration_reconciliation");
      jdbc.execute("drop function fail_inventory_reconciliation()");
    }

    InventoryMaintenanceService.UpsertResult retried =
        inventory.upsert(inventoryId, findingId, upsert);
    assertThat(retried.replayed()).isFalse();
    assertThat(inventoryReconciliations.findAll())
        .filteredOn(value -> "ASSET".equals(value.getDependencyType()))
        .filteredOn(value -> "QUEUE_REPAIR".equals(value.getOperationType()))
        .singleElement()
        .extracting(MaintenanceReconciliation::getRepairId)
        .isEqualTo(retried.repairId());
  }

  @Test
  void manualFreezeValidatesExactOrderUnknownNodesAndInventoryOwnedMedia() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    mediaFacts.saveAndFlush(MediaFactProjection.create(
        mediaId, 2, "INVENTORY_FINDING", findingId, warehouseId, "READY", "{}", 4));
    FreezeInventoryPlanRequest manual = new FreezeInventoryPlanRequest(
        warehouseId, inventoryId, findingId, 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(
            workNodeId, "1", null, List.of(new MediaReferenceInput(mediaId, 2L)))),
        List.of(new InventoryPlanStageSelection(workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of(new MediaReferenceInput(mediaId, 2L)),
        5,
        mediaId);

    FrozenInventoryPlanResponse frozen = inventory.freeze(manual).response();
    assertThat(frozen.snapshot().mode()).isEqualTo(InventoryPlanMode.MANUAL);
    assertThat(frozen.snapshot().priority()).isEqualTo(5);
    assertThat(frozen.snapshot().coverMediaId()).isEqualTo(mediaId);
    assertThat(frozen.fingerprint()).matches("[0-9a-f]{64}");

    FreezeInventoryPlanRequest unknown = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(UUID.randomUUID(), "1", null, List.of())),
        List.of(), List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(unknown))
        .isInstanceOf(MaintenanceValidationException.class);

    FreezeInventoryPlanRequest wrongMedia = autoRequest(
        UUID.randomUUID(), UUID.randomUUID(), List.of(new MediaReferenceInput(mediaId, 2L)));
    assertThatThrownBy(() -> inventory.freeze(wrongMedia))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("media");

    FreezeInventoryPlanRequest manualLines = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 2L, InventoryPlanMode.MANUAL,
        List.of(
            manualLine(
                "  Шлифовка   Стола ", InventoryPlanLineType.WORK,
                " час ", "1.250", 15000L, "45.5"),
            manualLine(
                " Материал ", InventoryPlanLineType.MATERIAL,
                " шт ", "2", 250L, "0")),
        List.of(new InventoryPlanStageSelection(workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of(), 3, null);
    FrozenInventoryPlanResponse frozenManualLines = inventory.freeze(manualLines).response();
    InventoryPlanLineSnapshot manualWork = frozenManualLines.snapshot().lines().getFirst();
    assertThat(manualWork.aggregationKind()).isEqualTo(InventoryPlanLineKind.MANUAL);
    assertThat(manualWork.catalogVersionId()).isNull();
    assertThat(manualWork.catalogNodeId()).isNull();
    assertThat(manualWork.catalogNodeName()).isNull();
    assertThat(manualWork.routing()).isEqualTo(
        frozenManualLines.snapshot().stages().getFirst().routing());
    assertThat(manualWork.description()).isEqualTo("Шлифовка Стола");
    assertThat(manualWork.normalizedDescription()).isEqualTo("шлифовка стола");
    assertThat(manualWork.quantity()).isEqualTo("1.250000");
    assertThat(manualWork.unitPriceMinor()).isEqualTo(15000);
    assertThat(manualWork.normativeMinutes()).isEqualTo("45.500");
  }

  @Test
  void repeatedCatalogWorkCreatesSeparateRepairStagesWithoutDuplicatingLines() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FreezeInventoryPlanRequest request = new FreezeInventoryPlanRequest(
        warehouseId,
        inventoryId,
        findingId,
        1L,
        InventoryPlanMode.AUTO,
        List.of(
            catalogLine(workNodeId, "1", "Стена", List.of()),
            catalogLine(workNodeId, "1", "Потолок", List.of())),
        List.of(),
        List.of(),
        3,
        null);

    FrozenInventoryPlanResponse frozen = inventory.freeze(request).response();
    assertThat(frozen.snapshot().stages())
        .extracting(InventoryPlanStageSnapshot::catalogNodeId)
        .containsExactly(workNodeId, workNodeId);
    assertThat(frozen.snapshot().stages())
        .extracting(InventoryPlanStageSnapshot::order)
        .containsExactly(0, 1);

    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    InventoryMaintenanceService.UpsertResult created = inventory.upsert(
        inventoryId,
        findingId,
        new UpsertInventoryRepairRequest(
            warehouseId,
            1L,
            rentalItemId,
            7L,
            LocalDate.of(2026, 8, 3),
            frozen.fingerprint(),
            frozen.snapshot()));

    assertThat(
            jdbc.query(
                """
                select jsonb_array_length(work_lines)
                  from repair_stage
                 where repair_id=?
                 order by stage_no
                """,
                (row, ignored) -> row.getInt(1),
                created.repairId()))
        .containsExactly(1, 1);
    assertThat(
            jdbc.queryForObject(
                "select count(distinct primary_line_id) from repair_stage where repair_id=?",
                Integer.class,
                created.repairId()))
        .isEqualTo(2);
  }

  @Test
  void sourceRevisionStageKindsAndRoutingAreValidatedBeforeFreeze() {
    FreezeInventoryPlanRequest zeroRevision = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 0L, InventoryPlanMode.AUTO,
        List.of(catalogLine(workNodeId, "1", null, List.of())),
        List.of(), List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(zeroRevision))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("revision");

    UUID locationNode = insertMovementLocation("Только для логистики");
    FreezeInventoryPlanRequest locationAsRepairWork = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(workNodeId, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            locationNode, RepairStageKind.REPAIR_WORK, 0)),
        List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(locationAsRepairWork))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("REPAIR_WORK");

    UUID materialNode = insertCatalogNode("MATERIAL", true);
    FreezeInventoryPlanRequest materialAsWork = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(materialNode, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            materialNode, RepairStageKind.REPAIR_WORK, 0)),
        List.of(), 3, null);
    FrozenInventoryPlanSnapshot materialAsWorkSnapshot =
        inventory.freeze(materialAsWork).response().snapshot();
    assertThat(materialAsWorkSnapshot.lines())
        .singleElement()
        .satisfies(line -> {
          assertThat(line.catalogNodeId()).isEqualTo(materialNode);
          assertThat(line.type()).isEqualTo(InventoryPlanLineType.MATERIAL);
        });
    assertThat(materialAsWorkSnapshot.stages())
        .singleElement()
        .satisfies(stage -> {
          assertThat(stage.catalogNodeId()).isEqualTo(materialNode);
          assertThat(stage.kind()).isEqualTo(RepairStageKind.REPAIR_WORK);
        });

    FreezeInventoryPlanRequest materialOnlyWithMatchingMaterialRoute =
        new FreezeInventoryPlanRequest(
            warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L,
            InventoryPlanMode.MANUAL,
            List.of(
                catalogLine(materialNode, "1", null, List.of()),
                manualLine(materialNode,
                    "Manual material", InventoryPlanLineType.MATERIAL,
                    "pcs", "1", 100L, "0")),
            List.of(new InventoryPlanStageSelection(
                materialNode, RepairStageKind.REPAIR_WORK, 0)),
            List.of(), 3, null);
    FrozenInventoryPlanSnapshot materialOnly = inventory.freeze(
        materialOnlyWithMatchingMaterialRoute).response().snapshot();
    assertThat(materialOnly.lines())
        .allMatch(line -> line.type() == InventoryPlanLineType.MATERIAL);
    assertThat(materialOnly.stages()).singleElement().satisfies(stage -> {
      assertThat(stage.catalogNodeId()).isEqualTo(materialNode);
      assertThat(stage.kind()).isEqualTo(RepairStageKind.REPAIR_WORK);
    });

    FreezeInventoryPlanRequest manualRouteMismatch = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(manualLine(
            "Manual work", InventoryPlanLineType.WORK, "h", "1", 100L, "1")),
        List.of(new InventoryPlanStageSelection(
            materialNode, RepairStageKind.REPAIR_WORK, 0)),
        List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(manualRouteMismatch))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("selected manual");

    FreezeInventoryPlanRequest missingManualRoute = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(new InventoryPlanLineInput(
            InventoryPlanLineKind.MANUAL, null, null, "Manual work", InventoryPlanLineType.WORK,
            "h", "1", 100L, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(missingManualRoute))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("MANUAL line requires");

    UUID furnitureMaterial = insertFurnitureMaterial();
    FreezeInventoryPlanRequest furnitureOutsideEstimate = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(furnitureMaterial, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(furnitureOutsideEstimate))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("Furniture materials can only be used through an estimate");

    UUID unroutedWork = insertCatalogNode("WORK", false);
    FreezeInventoryPlanRequest unrouted = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.AUTO,
        List.of(catalogLine(unroutedWork, "1", null, List.of())),
        List.of(), List.of(), 3, null);
    assertThatThrownBy(() -> inventory.freeze(unrouted))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("routing");

    UUID unitlessWork = insertCatalogNode("WORK", true);
    jdbc.update("update catalog_node set unit=null where catalog_version_id=? and node_id=?",
        catalogId, unitlessWork);
    FreezeInventoryPlanRequest unitless = new FreezeInventoryPlanRequest(
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        InventoryPlanMode.AUTO,
        List.of(catalogLine(unitlessWork, "1", null, List.of())),
        List.of(),
        List.of(),
        3,
        null);
    assertThatThrownBy(() -> inventory.freeze(unitless))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("unit");

    for (InventoryPlanLineInput invalid : List.of(
        manualLine("Scale", InventoryPlanLineType.WORK, "h", "1.0001", 1L, "1"),
        manualLine("Negative", InventoryPlanLineType.WORK, "h", "1", -1L, "1"),
        manualLine(
            "Overflow", InventoryPlanLineType.WORK, "h", "99999999999999",
            Long.MAX_VALUE, "1"))) {
      FreezeInventoryPlanRequest rejected = new FreezeInventoryPlanRequest(
          warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
          List.of(invalid),
          List.of(new InventoryPlanStageSelection(
              workNodeId, RepairStageKind.REPAIR_WORK, 0)),
          List.of(), 3, null);
      assertThatThrownBy(() -> inventory.freeze(rejected))
          .isInstanceOf(MaintenanceValidationException.class);
    }
  }

  @Test
  void freezeFailsClosedWhenGlobalQueueIsNotConnectedToWarehouse() {
    when(
            dependencies.preflightMaintenanceRouting(
                eq(warehouseId), anyList()))
        .thenAnswer(
            invocation -> {
              List<MaintenanceDependencyGateway.RoutingQueueRequirement>
                  requirements = invocation.getArgument(1);
              return new MaintenanceDependencyGateway.RoutingPreflight(
                  warehouseId,
                  false,
                  List.of(),
                  List.of(
                      requirements.getFirst().queueDefinitionId()),
                  List.of(),
                  List.of());
            });

    assertThatThrownBy(
            () ->
                inventory.freeze(
                    autoRequest(
                        UUID.randomUUID(), UUID.randomUUID(), List.of())))
        .isInstanceOf(MaintenanceValidationException.class)
        .extracting(
            exception ->
                ((MaintenanceValidationException) exception).code())
        .isEqualTo("MAINTENANCE_ROUTING_INVALID");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_repair_source",
                Integer.class))
        .isZero();
  }

  @Test
  void upsertFailsClosedWhenFrozenPlanQueueWasDisconnectedBeforeRepairCreation() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen =
        inventory
            .freeze(autoRequest(inventoryId, findingId, List.of()))
            .response();
    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(
            rentalItemId, warehouseId, "FREE", 7));
    when(
            dependencies.preflightMaintenanceRouting(
                eq(warehouseId), anyList()))
        .thenAnswer(
            invocation -> {
              List<MaintenanceDependencyGateway.RoutingQueueRequirement>
                  requirements = invocation.getArgument(1);
              return new MaintenanceDependencyGateway.RoutingPreflight(
                  warehouseId,
                  false,
                  List.of(
                      requirements.getFirst().queueDefinitionId()),
                  List.of(),
                  List.of(),
                  List.of());
            });

    assertThatThrownBy(
            () ->
                inventory.upsert(
                    inventoryId,
                    findingId,
                    new UpsertInventoryRepairRequest(
                        warehouseId,
                        3L,
                        rentalItemId,
                        7L,
                        LocalDate.of(2026, 7, 17),
                        frozen.fingerprint(),
                        frozen.snapshot())))
        .isInstanceOf(MaintenanceValidationException.class)
        .extracting(
            exception ->
                ((MaintenanceValidationException) exception).code())
        .isEqualTo("MAINTENANCE_ROUTING_INVALID");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from maintenance_repair",
                Integer.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                """
                select repair_id from inventory_repair_source
                 where inventory_id=? and finding_id=?
                """,
                UUID.class,
                inventoryId,
                findingId))
        .isNull();
  }

  @Test
  void unsafeCurrentAssetStateBlocksFirstUpsertWithoutCreatingRepair() {
    for (String status : List.of("RENTED", "WAITING_ESTIMATE_CONFIRMATION")) {
      UUID inventoryId = UUID.randomUUID();
      UUID findingId = UUID.randomUUID();
      FrozenInventoryPlanResponse frozen = inventory.freeze(
          autoRequest(inventoryId, findingId, List.of())).response();
      UUID rentalItemId = UUID.randomUUID();
      rentalItems.saveAndFlush(RentalItemFactProjection.create(
          rentalItemId, warehouseId, status, 5));
      UpsertInventoryRepairRequest request = new UpsertInventoryRepairRequest(
          warehouseId, 3L, rentalItemId, 5L, LocalDate.of(2026, 7, 17),
          frozen.fingerprint(), frozen.snapshot());

      assertThatThrownBy(() -> inventory.upsert(inventoryId, findingId, request))
          .isInstanceOf(MaintenanceConflictException.class)
          .hasMessageContaining("unsafe");
    }
    assertThat(jdbc.queryForObject("select count(*) from maintenance_repair", Integer.class))
        .isZero();
  }

  @Test
  void invalidFreezeDoesNotPoisonSourceAndConcurrentValidRetriesConverge() throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FreezeInventoryPlanRequest invalid = new FreezeInventoryPlanRequest(
        warehouseId,
        inventoryId,
        findingId,
        1L,
        InventoryPlanMode.AUTO,
        List.of(catalogLine(UUID.randomUUID(), "1", null, List.of())),
        List.of(),
        List.of(),
        3,
        null);
    assertThatThrownBy(() -> inventory.freeze(invalid))
        .isInstanceOf(MaintenanceValidationException.class);

    FreezeInventoryPlanRequest valid = autoRequest(inventoryId, findingId, List.of());
    var executor = Executors.newFixedThreadPool(2);
    try {
      var first = executor.submit(() -> inventory.freeze(valid));
      var second = executor.submit(() -> inventory.freeze(valid));
      List<InventoryMaintenanceService.FreezeResult> results = List.of(
          first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));

      assertThat(results).extracting(result -> result.response().fingerprint())
          .containsOnly(results.getFirst().response().fingerprint());
      assertThat(results).extracting(InventoryMaintenanceService.FreezeResult::replayed)
          .containsExactlyInAnyOrder(false, true);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void afterRentPublicationCreatesOneDraftEstimateFromRawV1EvidenceWithoutRepairWork() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen = inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    rawSnapshot.put("movementToShipment", false);
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "AFTER_RENT", 7));

    InventoryPublicationFindingInput finding = publicationFinding(
        findingId,
        assetId,
        7L,
        rawSnapshot,
        1,
        4,
        false,
        null);
    assertThatThrownBy(() -> publications.preflight(
        new InventoryPublicationPreflightRequest(
            inventoryId,
            warehouseId,
            1L,
            finalPlanSha(1),
            List.of(publicationFinding(
                findingId, assetId, 7L, rawSnapshot, 2, 4, false, null)))))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("schema version 2");
    InventoryPublicationPreflightResponse preflight = publications.preflight(
        new InventoryPublicationPreflightRequest(
            inventoryId, warehouseId, 1L, finalPlanSha(1), List.of(finding)));
    assertThat(preflight.findings()).singleElement().satisfies(value -> {
      assertThat(value.targetKind()).isEqualTo(InventoryPublicationTargetKind.ESTIMATE);
      assertThat(value.candidates()).isEmpty();
    });

    InventoryPublicationApplyRequest request = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    InventoryPublicationReconciliationService.PublicationResult created = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), request);
    InventoryPublicationReconciliationService.PublicationResult replayed = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), request);

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(created.response().targetKind()).isEqualTo(InventoryPublicationTargetKind.ESTIMATE);
    assertThat(created.response().estimateId()).isNotNull();
    assertThat(created.response().repairId()).isNull();
    UUID estimateId = created.response().estimateId();
    assertThat(jdbc.queryForMap(
        "select state,priority,movement_to_repair,repair_id from maintenance_estimate where id=?",
        estimateId))
        .containsEntry("state", "DRAFT")
        .containsEntry("priority", 4)
        .containsEntry("movement_to_repair", false)
        .containsEntry("repair_id", null);
    assertThat(jdbc.queryForObject(
        "select count(*) from estimate_line where estimate_id=?", Integer.class, estimateId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from estimate_plan_stage where estimate_id=?", Integer.class, estimateId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from estimate_revision where estimate_id=?", Integer.class, estimateId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where dependency_type='ASSET' and operation_type='QUEUE_REPAIR'
        """, Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        """
        select jsonb_exists(plan_snapshot, 'movementToShipment')
          from inventory_publication_source
         where inventory_id=? and final_plan_version=1 and finding_id=?
        """, Boolean.class, inventoryId, findingId)).isTrue();

    assertThatThrownBy(() -> jdbc.update(
        """
        update inventory_publication_source set plan_snapshot='{}'::jsonb
         where inventory_id=? and final_plan_version=1 and finding_id=?
        """, inventoryId, findingId))
        .hasMessageContaining("inventory publication sources are immutable");

    InventoryPublicationApplyRequest unresolvedCreate = publicationApplyRequest(
        finding, 2L, InventoryPublicationStrategy.CREATE, null, null);
    assertThatThrownBy(
        () -> publications.apply(inventoryId, findingId, UUID.randomUUID(), unresolvedCreate))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("active maintenance target");

    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        finding,
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.ESTIMATE,
        estimateId);
    InventoryPublicationReconciliationService.PublicationResult replaced = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), replacement);
    assertThat(replaced.response().estimateId()).isNotEqualTo(estimateId);
    assertThat(jdbc.queryForObject(
        "select inventory_superseded_at is not null from maintenance_estimate where id=?",
        Boolean.class,
        estimateId)).isTrue();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_estimate", Integer.class)).isEqualTo(2);
    assertThat(replaced.response().source().supersededTargetKind())
        .isEqualTo(InventoryPublicationTargetKind.ESTIMATE);
    assertThat(replaced.response().source().supersededTargetId()).isEqualTo(estimateId);
  }

  @Test
  void nonAfterRentPublicationCreatesRepairWithFinalManagerChoicesAndQueueIntent() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen = inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 9));
    LocalDate movementDate = LocalDate.of(2026, 8, 4);
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId,
        assetId,
        9L,
        rawSnapshot,
        2,
        5,
        true,
        movementDate);

    InventoryPublicationReconciliationService.PublicationResult created = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null));

    assertThat(created.response().targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR);
    UUID repairId = created.response().repairId();
    assertThat(repairId).isNotNull();
    assertThat(created.response().estimateId()).isNull();
    assertThat(jdbc.queryForMap(
        """
        select origin,priority,movement_to_repair,logistics_planning_mode,logistics_scheduled_date
          from maintenance_repair where id=?
        """, repairId))
        .containsEntry("origin", RepairOrigin.INVENTORY.name())
        .containsEntry("priority", 5)
        .containsEntry("movement_to_repair", true)
        .containsEntry("logistics_planning_mode", RepairLogisticsPlanningMode.FIXED_DATE.name());
    assertThat(jdbc.queryForObject(
        "select logistics_scheduled_date from maintenance_repair where id=?",
        LocalDate.class,
        repairId)).isEqualTo(movementDate);
    assertThat(jdbc.queryForObject(
        "select count(*) from repair_stage where repair_id=?", Integer.class, repairId)).isOne();
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and dependency_type='ASSET' and operation_type='QUEUE_REPAIR'
        """, Integer.class, repairId)).isOne();
  }

  @Test
  void schemaTwoManualNullRoutesAdaptOnlyFromOneSelectedStageRouteAndPreserveRawEvidence()
      throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen = inventory.freeze(new FreezeInventoryPlanRequest(
        warehouseId,
        inventoryId,
        findingId,
        1L,
        InventoryPlanMode.MANUAL,
        List.of(
            manualLine(
                "Manual work", InventoryPlanLineType.WORK, "h", "1", 100L, "30"),
            manualLine(
                "Manual material", InventoryPlanLineType.MATERIAL, "pcs", "2", 50L, "0")),
        List.of(new InventoryPlanStageSelection(workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of(),
        3,
        null)).response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    rawSnapshot.withArray("lines").forEach(line -> ((ObjectNode) line).putNull("routing"));
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 7));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);

    InventoryPublicationPreflightResponse preflight = publications.preflight(
        new InventoryPublicationPreflightRequest(
            inventoryId, warehouseId, 1L, finalPlanSha(1), List.of(finding)));
    assertThat(preflight.findings()).singleElement().satisfies(value ->
        assertThat(value.targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR));

    InventoryPublicationReconciliationService.PublicationResult published = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null));
    assertThat(published.response().repairId()).isNotNull();
    String storedRaw = jdbc.queryForObject(
        """
        select plan_snapshot::text from inventory_publication_source
         where inventory_id=? and final_plan_version=? and finding_id=?
        """,
        String.class,
        inventoryId,
        1L,
        findingId);
    var storedSnapshot = mapper.readTree(storedRaw);
    assertThat(canonicalizer.sha256(storedSnapshot)).isEqualTo(finding.planFingerprintSha256());
    for (var storedLine : storedSnapshot.required("lines")) {
      assertThat(storedLine.required("routing").isNull()).isTrue();
    }

    ObjectNode ambiguousSnapshot = rawSnapshot.deepCopy();
    ObjectNode secondStage =
        ((ObjectNode) ambiguousSnapshot.withArray("stages").get(0)).deepCopy();
    secondStage.put("id", UUID.randomUUID().toString());
    secondStage.put("order", 1);
    ((ObjectNode) secondStage.required("routing"))
        .put("queueId", UUID.randomUUID().toString())
        .put("queueName", "Another repair queue")
        .put("queueType", "REPAIR");
    ambiguousSnapshot.withArray("stages").add(secondStage);
    InventoryPublicationFindingInput ambiguousFinding = publicationFinding(
        UUID.randomUUID(), assetId, 7L, ambiguousSnapshot, 2, 3, false, null);
    assertThatThrownBy(() -> publications.preflight(
        new InventoryPublicationPreflightRequest(
            UUID.randomUUID(), warehouseId, 1L, finalPlanSha(1), List.of(ambiguousFinding))))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("ambiguous");
  }

  @Test
  void queuedPrestartReplacementReplaysLostDriverResponseOutsideTransaction() {
    QueuedPrestartRepair queued = queuedPrestartRepair(false);
    InventoryPublicationFindingInput laterFinding = changedPublicationFinding(queued, "3.500000");
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        laterFinding,
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    AtomicReference<Integer> taskCalls = new AtomicReference<>(0);
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          int call = taskCalls.updateAndGet(value -> value + 1);
          return taskCancellation(
              call == 1
                  ? MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.CANCELLED
                  : MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.ALREADY_CANCELLED,
              queued.externalTaskId(),
              1L);
        });
    AtomicReference<Integer> driverReads = new AtomicReference<>(0);
    when(dependencies.maintenanceDriverTaskCompensation(
        eq(queued.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          int call = driverReads.updateAndGet(value -> value + 1);
          return driverCompensation(
              queued.repairId(),
              MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
              call == 1
                  ? MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.PENDING
                  : MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.CANCELLED);
        });
    when(dependencies.cancelMaintenanceDriverTaskCompensation(
        any(),
        eq(queued.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          throw new MaintenanceDependencyException(
              HttpStatus.SERVICE_UNAVAILABLE,
              "response lost after logistics committed its cancellation");
        });

    assertThatThrownBy(() -> publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertPrestartIntent(queued, "PREPARED", 1L);
    assertThat(publicationSourceCount(queued.inventoryId(), 2L, queued.findingId())).isZero();

    InventoryPublicationApplyRequest differentPayload = publicationApplyRequest(
        changedPublicationFinding(queued, "4.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    assertThatThrownBy(() -> publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), differentPayload))
        .isInstanceOf(MaintenanceConflictException.class)
        .isNotInstanceOf(MaintenanceDependencyException.class)
        .hasMessageContaining("already bound to different publication input");
    assertPrestartIntent(queued, "PREPARED", 1L);

    InventoryPublicationReconciliationService.PublicationResult applied = publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement);
    assertThat(applied.replayed()).isFalse();
    assertThat(applied.response().outcome()).isEqualTo(InventoryPublicationOutcome.CREATED);
    assertThat(applied.response().source().strategy()).isEqualTo(InventoryPublicationStrategy.REPLACE);
    assertThat(repairs.findById(queued.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, applied.response().repairId())).isOne();
    assertPrestartIntent(queued, "APPLIED", 2L);

    InventoryPublicationReconciliationService.PublicationResult replayed = publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement);
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(applied.response());
    assertThat(taskCalls.get()).isEqualTo(2);
    assertThat(driverReads.get()).isEqualTo(2);
    verify(dependencies, times(1)).cancelMaintenanceDriverTaskCompensation(
        any(),
        eq(queued.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR));
    verify(dependencies, never()).registerTask(
        any(), any(), any(), any(), any(), any(), any(), anyInt(), anyInt(), anyList());
    verify(dependencies, never()).createDriverTask(any(), any());
  }

  @Test
  void firstTaskVersionConflictAbortsOnlyTheUnpublishedPrestartIntent() {
    QueuedPrestartRepair queued = queuedPrestartRepair(false);
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        changedPublicationFinding(queued, "3.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return taskCancellation(
              MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.VERSION_CONFLICT,
              queued.externalTaskId(),
              1L);
        });

    assertThatThrownBy(() -> publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("Task-board version changed");
    assertThat(publicationSourceCount(queued.inventoryId(), 2L, queued.findingId())).isZero();
    assertThat(prestartIntentCount(queued.inventoryId(), 2L, queued.findingId())).isZero();
    assertThat(jdbc.queryForObject(
        """
        select count(*) from inventory_publication_source_operation
         where inventory_id=? and final_plan_version=2 and finding_id=?
        """, Integer.class, queued.inventoryId(), queued.findingId())).isZero();
    verify(dependencies, never()).maintenanceDriverTaskCompensation(
        any(), any(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.class));
  }

  @Test
  void postTaskCancellationReconciliationConflictRemainsRetryableAndGuarded() {
    QueuedPrestartRepair queued = queuedPrestartRepair(false);
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        changedPublicationFinding(queued, "3.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return taskCancellation(
              MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.CANCELLED,
              queued.externalTaskId(),
              1L);
        });
    when(dependencies.maintenanceDriverTaskCompensation(
        eq(queued.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return driverCompensation(
              queued.repairId(),
              MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
              MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome
                  .RECONCILIATION_REQUIRED);
        });

    assertThatThrownBy(() -> publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE));
    assertPrestartIntent(queued, "PREPARED", 1L);
    assertThat(publicationSourceCount(queued.inventoryId(), 2L, queued.findingId())).isZero();
    verify(dependencies, never()).cancelMaintenanceDriverTaskCompensation(
        any(), any(), any(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.class));
  }

  @Test
  void inboundDriverStartIsRetryableUntilCompletedOccupiedAllocationTruthExists() {
    QueuedPrestartRepair queued = queuedPrestartRepair(false);
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        changedPublicationFinding(queued, "3.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenReturn(taskCancellation(
            MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.CANCELLED,
            queued.externalTaskId(),
            1L));
    when(dependencies.maintenanceDriverTaskCompensation(
        eq(queued.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenReturn(driverCompensation(
            queued.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
            MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.STARTED));

    assertThatThrownBy(() -> publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
              assertThat(exception).hasMessageContaining("Inbound delivery has started");
            });
    assertPrestartIntent(queued, "PREPARED", 1L);
    assertThat(repairs.findById(queued.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
    verify(dependencies, never()).cancelMaintenanceDriverTaskCompensation(
        any(), any(), any(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.class));
  }

  @Test
  void taskStartAfterReplacePreflightFallsBackToResidualSuccessorWithoutDriverCancellation() {
    QueuedPrestartRepair queued = queuedPrestartRepair(false);
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        changedPublicationFinding(queued, "3.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return taskCancellation(
              MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.STARTED,
              queued.externalTaskId(),
              1L);
        });

    InventoryPublicationReconciliationService.PublicationResult result = publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement);
    assertThat(result.replayed()).isFalse();
    assertThat(result.response().source().strategy()).isEqualTo(InventoryPublicationStrategy.REPLACE);
    assertThat(result.response().outcome()).isEqualTo(InventoryPublicationOutcome.SUCCESSOR);
    assertThat(result.response().successor().state())
        .isEqualTo(InventoryPublicationSuccessorState.WAITING_PREDECESSOR);
    assertThat(result.response().delta().lines())
        .allMatch(line -> line.disposition()
            == InventoryPublicationDeltaDisposition.RETAINED_AFTER_DEDUCTION);
    assertPrestartIntent(queued, "APPLIED", 1L);
    verify(dependencies, never()).maintenanceDriverTaskCompensation(
        any(), any(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.class));
    verify(dependencies, never()).cancelMaintenanceDriverTaskCompensation(
        any(), any(), any(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.class));
  }

  @Test
  void taskCancellationEventBetweenRemoteGuardAndFinalizationIsAdoptedByTheSaga() {
    QueuedPrestartRepair queued = queuedPrestartRepair(true);
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        changedPublicationFinding(queued, "3.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          new TransactionTemplate(transactionManager).executeWithoutResult(status ->
              maintenance.applyInboundTaskOutcome(
                  UUID.randomUUID(),
                  "task-board.task.cancelled.v1",
                  queued.externalTaskId(),
                  queued.queueEntryId(),
                  1L,
                  OffsetDateTime.now(ZoneOffset.UTC)));
          return taskCancellation(
              MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.CANCELLED,
              queued.externalTaskId(),
              1L);
        });
    when(dependencies.maintenanceDriverTaskCompensation(
        eq(queued.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return driverCompensation(
              queued.repairId(),
              MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
              MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.CANCELLED);
        });

    InventoryPublicationReconciliationService.PublicationResult result = publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement);
    assertThat(result.response().outcome()).isEqualTo(InventoryPublicationOutcome.CREATED);
    assertThat(repairs.findById(queued.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForObject(
        """
        select state from repair_stage where external_queue_entry_id=?
        """, String.class, queued.queueEntryId())).isEqualTo("CANCELLED");
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='CANCELLED_PRIMARY_RECONCILIATION'
        """, Integer.class, queued.repairId())).isZero();
  }

  @Test
  void completionBetweenStartedGuardAndSuccessorInsertReleasesResidualImmediately() {
    QueuedPrestartRepair queued = queuedPrestartRepair(true);
    InventoryPublicationApplyRequest replacement = publicationApplyRequest(
        changedPublicationFinding(queued, "3.500000"),
        2L,
        InventoryPublicationStrategy.REPLACE,
        InventoryPublicationTargetKind.REPAIR,
        queued.repairId());
    UUID completionEventId = UUID.randomUUID();
    OffsetDateTime completionAt = OffsetDateTime.now(ZoneOffset.UTC);
    when(dependencies.cancelTaskIfPreStart(
        any(), eq(queued.externalTaskId()), eq(0L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          new TransactionTemplate(transactionManager).executeWithoutResult(status ->
              maintenance.applyInboundTaskOutcome(
                  completionEventId,
                  "task-board.task.completed.v1",
                  queued.externalTaskId(),
                  queued.queueEntryId(),
                  1L,
                  completionAt));
          return taskCancellation(
              MaintenanceDependencyGateway.PreStartTaskCancellationOutcome.STARTED,
              queued.externalTaskId(),
              1L);
        });

    InventoryPublicationReconciliationService.PublicationResult result = publications.apply(
        queued.inventoryId(), queued.findingId(), UUID.randomUUID(), replacement);
    assertThat(result.response().outcome()).isEqualTo(InventoryPublicationOutcome.SUCCESSOR);
    assertThat(result.response().successor()).satisfies(successor -> {
      assertThat(successor.state()).isEqualTo(InventoryPublicationSuccessorState.RELEASED);
      assertThat(successor.terminalFact())
          .isEqualTo(InventoryPublicationTerminalFact.TASK_BOARD_COMPLETION);
      assertThat(successor.terminalFactEventId()).isEqualTo(completionEventId);
    });
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, result.response().repairId())).isOne();
    assertPrestartIntent(queued, "APPLIED", 1L);
    verify(dependencies, never()).maintenanceDriverTaskCompensation(
        any(), any(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.class));
  }

  @Test
  void publicationMergeKeepsStartedWorkAndReleasesARealMaterialOnlySuccessor() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen = inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId,
        assetId,
        11L,
        rawSnapshot,
        2,
        3,
        false,
        null);

    UUID firstRepairId = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null))
        .response()
        .repairId();
    MaintenanceRepair started = repairs.findById(firstRepairId).orElseThrow();
    started.queueUnderExistingRepair();
    started.begin();
    repairs.saveAndFlush(started);
    RentalItemFactProjection activeAsset = rentalItems.findById(assetId).orElseThrow();
    activeAsset.apply(warehouseId, "REPAIR", 12L);
    rentalItems.saveAndFlush(activeAsset);

    InventoryPublicationFindingInput activeFinding = publicationFinding(
        findingId,
        assetId,
        12L,
        rawSnapshot,
        2,
        3,
        false,
        null);

    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            activeFinding,
            2L,
            InventoryPublicationStrategy.REPLACE,
            InventoryPublicationTargetKind.REPAIR,
            firstRepairId)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("REPLACE cannot alter started repair work");
    assertThat(jdbc.queryForObject(
        """
        select count(*) from inventory_publication_source_operation
         where inventory_id=? and final_plan_version=2 and finding_id=?
        """, Integer.class, inventoryId, findingId)).isZero();

    InventoryPublicationReconciliationService.PublicationResult matched = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            activeFinding,
            2L,
            InventoryPublicationStrategy.MERGE,
            InventoryPublicationTargetKind.REPAIR,
            firstRepairId));
    assertThat(matched.response().outcome()).isEqualTo(InventoryPublicationOutcome.MATCHED);
    assertThat(matched.response().targetId()).isNull();
    assertThat(matched.response().repairId()).isNull();
    assertThat(matched.response().delta().lines())
        .allMatch(line -> line.disposition()
            == InventoryPublicationDeltaDisposition.REMOVED_AS_ALREADY_PRESENT);

    UUID materialNodeId = insertCatalogNode("MATERIAL", true);
    FrozenInventoryPlanResponse materialPlan = inventory.freeze(new FreezeInventoryPlanRequest(
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        1L,
        InventoryPlanMode.MANUAL,
        List.of(
            catalogLine(workNodeId, "2.500", null, List.of()),
            catalogLine(materialNodeId, "3", null, List.of())),
        List.of(
            new InventoryPlanStageSelection(workNodeId, RepairStageKind.REPAIR_WORK, 0),
            new InventoryPlanStageSelection(materialNodeId, RepairStageKind.REPAIR_WORK, 1)),
        List.of(),
        3,
        null)).response();
    ObjectNode materialSnapshot = (ObjectNode) mapper.valueToTree(materialPlan.snapshot());
    InventoryPublicationFindingInput materialFinding = publicationFinding(
        findingId,
        assetId,
        12L,
        materialSnapshot,
        2,
        3,
        false,
        null);

    InventoryPublicationReconciliationService.PublicationResult successor = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            materialFinding,
            3L,
            InventoryPublicationStrategy.MERGE,
            InventoryPublicationTargetKind.REPAIR,
            firstRepairId));
    UUID successorId = successor.response().repairId();

    assertThat(successor.response().outcome()).isEqualTo(InventoryPublicationOutcome.SUCCESSOR);
    assertThat(successor.response().targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR);
    assertThat(successorId).isNotEqualTo(firstRepairId);
    assertThat(successor.response().successor())
        .satisfies(value -> {
          assertThat(value.predecessorRepairId()).isEqualTo(firstRepairId);
          assertThat(value.state()).isEqualTo(InventoryPublicationSuccessorState.WAITING_PREDECESSOR);
          assertThat(value.terminalFact()).isNull();
        });
    assertThat(successor.response().delta().lines()).anySatisfy(line -> {
      assertThat(line.lineType()).isEqualTo(InventoryPlanLineType.WORK);
      assertThat(line.disposition())
          .isEqualTo(InventoryPublicationDeltaDisposition.REMOVED_AS_ALREADY_PRESENT);
    });
    assertThat(successor.response().delta().lines()).anySatisfy(line -> {
      assertThat(line.lineType()).isEqualTo(InventoryPlanLineType.MATERIAL);
      assertThat(line.disposition()).isEqualTo(InventoryPublicationDeltaDisposition.RETAINED);
      assertThat(line.retainedQuantity()).isEqualTo("3");
    });
    assertThat(jdbc.queryForMap(
        """
        select jsonb_array_length(work_lines) as work_count,
               jsonb_array_length(material_lines) as material_count,
               primary_line_id
          from repair_stage
         where repair_id=?
        """, successorId))
        .containsEntry("work_count", 0)
        .containsEntry("material_count", 1)
        .containsEntry("primary_line_id", null);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, successorId)).isZero();

    InventoryPublicationReconciliationService.PublicationResult replayed = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            materialFinding,
            3L,
            InventoryPublicationStrategy.MERGE,
            InventoryPublicationTargetKind.REPAIR,
            firstRepairId));
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(successor.response());

    UUID terminalEventId = UUID.randomUUID();
    OffsetDateTime terminalAt = OffsetDateTime.now(ZoneOffset.UTC);
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      MaintenanceRepair predecessor = repairs.findById(firstRepairId).orElseThrow();
      predecessor.completeForAcceptance();
      repairs.saveAndFlush(predecessor);
      successorActivator.releaseAfterTaskBoardCompletion(predecessor, terminalEventId, terminalAt);
    });
    assertThat(jdbc.queryForMap(
        """
        select state,terminal_fact,terminal_fact_event_id
          from inventory_publication_successor
         where successor_repair_id=?
        """, successorId))
        .containsEntry("state", "RELEASED")
        .containsEntry("terminal_fact", "TASK_BOARD_COMPLETION")
        .containsEntry("terminal_fact_event_id", terminalEventId);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, successorId)).isOne();

    jdbc.update("""
        update integration_reconciliation
           set state='CONFIRMED'
         where repair_id<>? and state in ('PENDING','RETRY_PENDING','RECONCILIATION_REQUIRED')
        """, successorId);
    AtomicReference<List<MaintenanceDependencyGateway.TaskStage>> registeredStages =
        new AtomicReference<>();
    when(dependencies.getRentalItemSnapshot(assetId)).thenReturn(
        new MaintenanceDependencyGateway.AssetSnapshot(assetId, 12L, warehouseId, "A-101", "REPAIR"));
    when(dependencies.acquireLease(
        any(), eq(assetId), eq(12L), eq("MAINTENANCE_REPAIR"), eq(successorId.toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            UUID.randomUUID(), 0L, assetId, "MAINTENANCE_REPAIR", successorId, 1L,
            OffsetDateTime.now(ZoneOffset.UTC).plusHours(1)));
    when(dependencies.fencedStatus(
        any(), eq(assetId), eq(warehouseId), eq(12L), any(), anyLong(),
        eq("MAINTENANCE_REPAIR"), eq(successorId.toString()),
        eq("QUEUE_TO_REPAIR"), eq(false)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            assetId, 12L, warehouseId, "A-101", "REPAIR"));
    when(dependencies.registerTask(
        any(), any(), eq(successorId), eq(warehouseId), eq(assetId), eq("A-101"), any(),
        anyInt(), anyInt(), anyList()))
        .thenAnswer(invocation -> {
          UUID externalTaskId = invocation.getArgument(1);
          @SuppressWarnings("unchecked")
          List<MaintenanceDependencyGateway.TaskStage> stages = invocation.getArgument(9);
          registeredStages.set(stages);
          return new MaintenanceDependencyGateway.TaskSnapshot(
              externalTaskId,
              1L,
              "ACTIVE",
              List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(
                  1, UUID.randomUUID(), 1L)));
        });

    assertThat(maintenance.reconcileOneTask()).isTrue();
    for (int attempt = 0; attempt < 3 && registeredStages.get() == null; attempt++) {
      maintenance.reconcileOneTask();
    }
    assertThat(registeredStages.get()).singleElement().satisfies(stage -> {
      assertThat(stage.works()).isEmpty();
      assertThat(stage.materials()).singleElement().satisfies(material -> {
        assertThat(material.name()).isEqualTo("Catalog node");
        assertThat(material.quantity()).isEqualTo(3.0d);
        assertThat(material.unit()).isEqualTo("pcs");
      });
      assertThat(stage.plannedDurationMinutes()).isNull();
    });
  }

  @Test
  void externalCapitalInventorySuccessorWaitsForAcceptanceInsteadOfLocalCompletion() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 21L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 21L, rawSnapshot, 2, 3, false, null);
    UUID predecessorId = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null))
        .response()
        .repairId();

    MaintenanceRepair predecessor = repairs.findById(predecessorId).orElseThrow();
    predecessor.queueExternalCapitalUnderExistingRepair();
    repairs.saveAndFlush(predecessor);
    RentalItemFactProjection capitalAsset = rentalItems.findById(assetId).orElseThrow();
    capitalAsset.apply(warehouseId, "CAPITAL_REPAIR", 22L);
    rentalItems.saveAndFlush(capitalAsset);

    ObjectNode laterSnapshot = rawSnapshot.deepCopy();
    ((ObjectNode) laterSnapshot.withArray("lines").get(0)).put("quantity", "3.500000");
    InventoryPublicationFindingInput laterFinding = publicationFinding(
        findingId, assetId, 22L, laterSnapshot, 2, 3, false, null);
    when(dependencies.maintenanceDriverTaskCompensation(
        eq(predecessorId),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.CAPITAL_TO_PRODUCTION)))
        .thenReturn(new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
            predecessorId,
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.CAPITAL_TO_PRODUCTION,
            MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.STARTED,
            UUID.randomUUID(),
            0L,
            "STARTED",
            null,
            null,
            null,
            null,
            null));
    InventoryPublicationReconciliationService.PublicationResult successor = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            laterFinding,
            2L,
            InventoryPublicationStrategy.MERGE,
            InventoryPublicationTargetKind.REPAIR,
            predecessorId));
    UUID successorId = successor.response().repairId();
    assertThat(successor.response().outcome()).isEqualTo(InventoryPublicationOutcome.SUCCESSOR);
    assertThat(successor.response().successor().state())
        .isEqualTo(InventoryPublicationSuccessorState.WAITING_PREDECESSOR);

    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      MaintenanceRepair capitalPredecessor = repairs.findById(predecessorId).orElseThrow();
      successorActivator.releaseAfterTaskBoardCompletion(
          capitalPredecessor, UUID.randomUUID(), OffsetDateTime.now(ZoneOffset.UTC));
    });
    assertThat(jdbc.queryForObject(
        "select state from inventory_publication_successor where successor_repair_id=?",
        String.class,
        successorId)).isEqualTo("WAITING_PREDECESSOR");
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, successorId)).isZero();

    UUID acceptanceEventId = UUID.randomUUID();
    new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
      MaintenanceRepair capitalPredecessor = repairs.findById(predecessorId).orElseThrow();
      capitalPredecessor.accept("inventory acceptance", "{}");
      repairs.saveAndFlush(capitalPredecessor);
      successorActivator.releaseAfterAcceptance(
          capitalPredecessor, acceptanceEventId, OffsetDateTime.now(ZoneOffset.UTC));
    });
    assertThat(jdbc.queryForMap(
        """
        select state,terminal_fact,terminal_fact_event_id
          from inventory_publication_successor
         where successor_repair_id=?
        """, successorId))
        .containsEntry("state", "RELEASED")
        .containsEntry("terminal_fact", "REPAIR_ACCEPTANCE")
        .containsEntry("terminal_fact_event_id", acceptanceEventId);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and operation_type='QUEUE_REPAIR'
        """, Integer.class, successorId)).isOne();
  }

  @Test
  void replacementDoesNotSilentlyLeaveAnotherActiveRepairForTheSameCabin() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen = inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId,
        assetId,
        11L,
        rawSnapshot,
        2,
        3,
        false,
        null);

    UUID selectedRepairId = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null))
        .response()
        .repairId();
    MaintenanceRepair unrelatedActiveRepair = MaintenanceRepair.primary(
        warehouseId,
        assetId,
        11L,
        null,
        RepairOrigin.DIRECT_REPAIR,
        LocalDate.of(2026, 8, 5),
        "Direct maintenance",
        "{}");
    repairs.saveAndFlush(unrelatedActiveRepair);

    assertThatThrownBy(
        () -> publications.apply(
            inventoryId,
            findingId,
            UUID.randomUUID(),
            publicationApplyRequest(
                finding,
                2L,
                InventoryPublicationStrategy.REPLACE,
                InventoryPublicationTargetKind.REPAIR,
                selectedRepairId)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("only active maintenance target");
    assertThat(repairs.findById(selectedRepairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    assertThat(jdbc.queryForObject(
        """
        select count(*) from inventory_publication_source_operation
         where inventory_id=? and final_plan_version=2 and finding_id=?
        """,
        Integer.class,
        inventoryId,
        findingId)).isZero();
  }

  private QueuedPrestartRepair queuedPrestartRepair(boolean registerTaskBoardStage) {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput initialFinding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    UUID repairId = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            initialFinding, 1L, InventoryPublicationStrategy.CREATE, null, null))
        .response()
        .repairId();
    QueuedPrestartRepair queued = new TransactionTemplate(transactionManager).execute(status -> {
      MaintenanceRepair repair = repairs.findById(repairId).orElseThrow();
      RepairStage stage = repairStages.findAllByRepairIdOrderByStageNo(repairId).getFirst();
      repair.queueUnderExistingRepair();
      repair.markTaskGenerated(0L);
      UUID queueEntryId = null;
      if (registerTaskBoardStage) {
        stage.queued();
        queueEntryId = UUID.randomUUID();
        stage.confirmTaskBoardRegistration(queueEntryId, 0L);
        repairStages.saveAndFlush(stage);
      }
      MaintenanceRepair saved = repairs.saveAndFlush(repair);
      jdbc.update(
          """
          update event_stream_head
             set current_version=?
           where aggregate_type='REPAIR' and aggregate_id=?
          """,
          saved.getVersion(),
          saved.getId().toString());
      return new QueuedPrestartRepair(
          inventoryId,
          findingId,
          assetId,
          saved.getId(),
          saved.getExternalTaskId(),
          queueEntryId,
          rawSnapshot.deepCopy());
    });
    if (queued == null) throw new IllegalStateException("Queued pre-start test repair was not created");
    RentalItemFactProjection active = rentalItems.findById(assetId).orElseThrow();
    active.apply(warehouseId, "REPAIR", 12L);
    rentalItems.saveAndFlush(active);
    return queued;
  }

  private InventoryPublicationFindingInput changedPublicationFinding(
      QueuedPrestartRepair queued, String quantity) {
    ObjectNode changed = queued.rawSnapshot().deepCopy();
    ((ObjectNode) changed.withArray("lines").get(0)).put("quantity", quantity);
    return publicationFinding(
        queued.findingId(),
        queued.assetId(),
        12L,
        changed,
        2,
        3,
        false,
        null);
  }

  private void assertPrestartIntent(
      QueuedPrestartRepair queued, String phase, long remoteAttemptCount) {
    assertThat(jdbc.queryForMap(
        """
        select phase,remote_attempt_count
          from inventory_publication_prestart_replacement
         where inventory_id=? and final_plan_version=2 and finding_id=?
        """, queued.inventoryId(), queued.findingId()))
        .containsEntry("phase", phase)
        .containsEntry("remote_attempt_count", remoteAttemptCount);
  }

  private int prestartIntentCount(UUID inventoryId, long planVersion, UUID findingId) {
    return jdbc.queryForObject(
        """
        select count(*) from inventory_publication_prestart_replacement
         where inventory_id=? and final_plan_version=? and finding_id=?
        """, Integer.class, inventoryId, planVersion, findingId);
  }

  private int publicationSourceCount(UUID inventoryId, long planVersion, UUID findingId) {
    return jdbc.queryForObject(
        """
        select count(*) from inventory_publication_source
         where inventory_id=? and final_plan_version=? and finding_id=?
        """, Integer.class, inventoryId, planVersion, findingId);
  }

  private static void assertNoRemoteTransaction() {
    assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
  }

  private static MaintenanceDependencyGateway.PreStartTaskCancellation taskCancellation(
      MaintenanceDependencyGateway.PreStartTaskCancellationOutcome outcome,
      UUID externalTaskId,
      long taskVersion) {
    String state = switch (outcome) {
      case CANCELLED, ALREADY_CANCELLED -> "CANCELLED";
      case STARTED -> "IN_PROGRESS";
      case VERSION_CONFLICT -> "WAITING";
    };
    return new MaintenanceDependencyGateway.PreStartTaskCancellation(
        outcome, UUID.randomUUID(), externalTaskId, taskVersion, state, OffsetDateTime.now(ZoneOffset.UTC));
  }

  private static MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation driverCompensation(
      UUID repairId,
      MaintenanceDependencyGateway.MaintenanceDriverTaskKind kind,
      MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome outcome) {
    if (outcome == MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.ABSENT) {
      return new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
          repairId, kind, outcome, null, null, null, null, null, null, null, null);
    }
    return new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
        repairId,
        kind,
        outcome,
        UUID.randomUUID(),
        0L,
        outcome.name(),
        null,
        null,
        null,
        null,
        null);
  }

  private record QueuedPrestartRepair(
      UUID inventoryId,
      UUID findingId,
      UUID assetId,
      UUID repairId,
      UUID externalTaskId,
      UUID queueEntryId,
      ObjectNode rawSnapshot) {}

  private InventoryPublicationFindingInput publicationFinding(
      UUID findingId,
      UUID assetId,
      long assetVersion,
      ObjectNode rawSnapshot,
      int snapshotSchemaVersion,
      int priority,
      boolean movementToRepair,
      LocalDate movementScheduledDate) {
    return new InventoryPublicationFindingInput(
        findingId,
        1L,
        assetId,
        assetVersion,
        canonicalizer.sha256(rawSnapshot),
        priority,
        movementToRepair,
        movementScheduledDate,
        LocalDate.of(2026, 8, 5),
        rawSnapshot,
        List.of(),
        snapshotSchemaVersion);
  }

  private InventoryPublicationApplyRequest publicationApplyRequest(
      InventoryPublicationFindingInput finding,
      long finalPlanVersion,
      InventoryPublicationStrategy strategy,
      InventoryPublicationTargetKind selectedTargetKind,
      UUID selectedTargetId) {
    return new InventoryPublicationApplyRequest(
        warehouseId,
        finalPlanVersion,
        finalPlanSha(finalPlanVersion),
        finding.findingRevision(),
        finding.assetId(),
        finding.assetVersion(),
        finding.planFingerprintSha256(),
        finding.priority(),
        finding.movementToRepair(),
        finding.movementScheduledDate(),
        finding.repairScheduledDate(),
        finding.snapshot(),
        finding.media(),
        finding.snapshotSchemaVersion(),
        strategy,
        selectedTargetKind,
        selectedTargetId);
  }

  private static String finalPlanSha(long version) {
    return String.format("%064x", version);
  }

  private FreezeInventoryPlanRequest autoRequest(
      UUID inventoryId, UUID findingId, List<MediaReferenceInput> media) {
    return new FreezeInventoryPlanRequest(
        warehouseId, inventoryId, findingId, 3L, InventoryPlanMode.AUTO,
        List.of(catalogLine(workNodeId, "2.500", "  group   comment ", List.of())),
        List.of(), media, 3, null);
  }

  private static InventoryPlanLineInput catalogLine(
      UUID catalogNodeId,
      String quantity,
      String groupComment,
      List<MediaReferenceInput> media) {
    return new InventoryPlanLineInput(
        InventoryPlanLineKind.CATALOG, catalogNodeId, null, null, null, null,
        quantity, null, null, groupComment, media);
  }

  private InventoryPlanLineInput manualLine(
      String description,
      InventoryPlanLineType type,
      String unit,
      String quantity,
      long unitPriceMinor,
      String normativeMinutes) {
    return manualLine(
        workNodeId, description, type, unit, quantity, unitPriceMinor, normativeMinutes);
  }

  private static InventoryPlanLineInput manualLine(
      UUID routingCatalogNodeId,
      String description,
      InventoryPlanLineType type,
      String unit,
      String quantity,
      long unitPriceMinor,
      String normativeMinutes) {
    return new InventoryPlanLineInput(
        InventoryPlanLineKind.MANUAL, null, routingCatalogNodeId, description, type, unit, quantity,
        unitPriceMinor, normativeMinutes, null, List.of());
  }

  private void insertActiveCatalog(UUID versionId, UUID nodeId, String seed) {
    jdbc.update("""
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,activated_at,created_at,updated_at)
        values (?,0,?,'ACTIVE',?,1,0,'{}',clock_timestamp(),clock_timestamp(),clock_timestamp())
        """, versionId, warehouseId, UUID.nameUUIDFromBytes(seed.getBytes()).toString()
            .replace("-", "") + UUID.nameUUIDFromBytes((seed + "x").getBytes()).toString()
            .replace("-", "").substring(0, 32));
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,'WORK','Repair work',true,'pcs',12500,45,true,false,false,
          ?,'Repair','REPAIR')
        """, UUID.randomUUID(), nodeId, versionId, UUID.randomUUID());
  }

  private UUID insertCatalogNode(String nodeType, boolean routed) {
    UUID nodeId = UUID.randomUUID();
    UUID queueId = routed ? UUID.randomUUID() : null;
    int durationMinutes = "MATERIAL".equals(nodeType) ? 0 : 45;
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,?,'Catalog node',true,'pcs',12500,?,true,false,false,
          ?,?,?)
        """, UUID.randomUUID(), nodeId, catalogId,
        nodeType, durationMinutes, queueId, routed ? "Repair" : null, routed ? "REPAIR" : null);
    return nodeId;
  }

  private UUID insertMovementLocation(String name) {
    UUID nodeId = UUID.randomUUID();
    jdbc.update(
        """
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          routing_queue_id,routing_queue_name,routing_queue_type)
        values (?,?,?,'LOCATION',?,true,0,false,false,false,
          ?,?,'MOVEMENT')
        """,
        UUID.randomUUID(),
        nodeId,
        catalogId,
        name,
        UUID.randomUUID(),
        name);
    jdbc.update(
        """
        update catalog_version
           set node_count=node_count+1
         where id=?
        """,
        catalogId);
    return nodeId;
  }

  private UUID insertFurnitureMaterial() {
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,furniture_category,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu)
        values (?,?,?,'CATEGORY','Furniture',true,true,
          0,false,false,true)
        """, UUID.randomUUID(), categoryId, catalogId);
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,node_type,name,active,parent_node_id,unit,
          price_minor,duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          furniture_equipment_id,furniture_equipment_name)
        values (?,?,?,'MATERIAL','Furniture chair',true,?,'pcs',12500,
          0,true,false,false,?,?)
        """, UUID.randomUUID(), materialId, catalogId, categoryId, UUID.randomUUID(), "Chair");
    return materialId;
  }
}
