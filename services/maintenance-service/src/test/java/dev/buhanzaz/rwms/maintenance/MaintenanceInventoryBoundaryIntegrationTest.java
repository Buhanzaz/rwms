package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcome;
import dev.buhanzaz.rwms.maintenance.domain.InventoryAuthoritativeOutcomeReceipt;
import dev.buhanzaz.rwms.maintenance.domain.InventoryPublicationSourceId;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceReconciliation;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
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
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeReceiptRepository;
import dev.buhanzaz.rwms.maintenance.repository.InventoryAuthoritativeOutcomeRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceReconciliationRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.InventoryMaintenanceService;
import dev.buhanzaz.rwms.maintenance.service.InventoryAuthoritativeOutcomeService;
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
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
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
  @Autowired InventoryAuthoritativeOutcomeService authoritativeOutcomes;
  @Autowired InventoryPublicationReconciliationService publications;
  @Autowired MaintenanceApplicationService maintenance;
  @Autowired InventoryPublicationSuccessorActivator successorActivator;
  @Autowired MaintenanceJsonbCanonicalizer canonicalizer;
  @Autowired ObjectMapper mapper;
  @Autowired InventoryAuthoritativeOutcomeRepository authoritativeOutcomeRepository;
  @Autowired InventoryAuthoritativeOutcomeReceiptRepository authoritativeOutcomeReceipts;
  @Autowired RentalItemFactProjectionRepository rentalItems;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired MaintenanceEstimateRepository estimates;
  @Autowired RepairStageRepository repairStages;
  @Autowired MaintenanceReconciliationRepository inventoryReconciliations;
  @Autowired MediaFactProjectionRepository mediaFacts;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;

  @MockitoBean MaintenanceDependencyGateway dependencies;

  private UUID warehouseId;
  private UUID catalogId;
  private UUID workNodeId;

  @Test
  void inventoryIsolationProjectionPersistsAndRollsBackWithItsInboundTransaction() {
    UUID assetId = UUID.randomUUID();
    TransactionTemplate transaction = new TransactionTemplate(transactionManager);
    transaction.executeWithoutResult(status ->
        maintenance.applyInboundRentalItemVisibilityFact(assetId, warehouseId, "FREE", 1, true));
    assertThat(rentalItems.findById(assetId).orElseThrow().isInventoryIsolated()).isTrue();

    transaction.executeWithoutResult(status ->
        maintenance.applyInboundRentalItemFact(assetId, warehouseId, "FREE", 2));
    assertThat(rentalItems.findById(assetId).orElseThrow().isInventoryIsolated()).isTrue();
    transaction.executeWithoutResult(status -> {
      maintenance.applyInboundRentalItemVisibilityFact(assetId, warehouseId, "FREE", 3, false);
      status.setRollbackOnly();
    });
    assertThat(rentalItems.findById(assetId).orElseThrow().isInventoryIsolated()).isTrue();
    assertThat(rentalItems.findById(assetId).orElseThrow().getAggregateVersion()).isEqualTo(2);

    transaction.executeWithoutResult(status ->
        maintenance.applyInboundRentalItemVisibilityFact(assetId, warehouseId, "FREE", 3, false));
    transaction.executeWithoutResult(status ->
        maintenance.applyInboundRentalItemVisibilityFact(assetId, warehouseId, "FREE", 1, true));
    assertThat(rentalItems.findById(assetId).orElseThrow().isInventoryIsolated()).isFalse();
    assertThat(rentalItems.findById(assetId).orElseThrow().getAggregateVersion()).isEqualTo(3);
  }

  private static UUID stableKey(String operation, UUID inventoryId, UUID findingId) {
    return UUID.nameUUIDFromBytes(
        (operation + ":" + inventoryId + ":" + findingId)
            .getBytes(StandardCharsets.UTF_8));
  }

  /** Mirrors the authoritative outcome effect identity for exact reconciliation assertions. */
  private static UUID authoritativeEffectKey(
      String operation,
      UUID inventoryId,
      long finalPlanVersion,
      UUID findingId,
      UUID targetId) {
    return UUID.nameUUIDFromBytes(
        (operation
                + ":"
                + inventoryId
                + ":"
                + finalPlanVersion
                + ":"
                + findingId
                + ":"
                + targetId)
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
        any(LocalDate.class), anyInt(), anyList()))
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
  void inventoryCatalogCapitalKeepsItsSelectedMovementWhileRemainingOutsideAcceptance() {
    jdbc.update(
        "update catalog_node set forces_capital_repair=true where node_id=?", workNodeId);
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozen =
        inventory.freeze(autoRequest(inventoryId, findingId, List.of())).response();
    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    InventoryPublicationFindingInput finding =
        publicationFinding(
            findingId,
            rentalItemId,
            7L,
            rawSnapshot,
            2,
            3,
            true,
            LocalDate.of(2026, 8, 21));
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId =
        publications.apply(inventoryId, findingId, UUID.randomUUID(), request).response().repairId();

    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 7, warehouseId, "БТ-КР-ДВ", "FREE"));
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
                eq("QUEUE_TO_CAPITAL_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 8, warehouseId, "БТ-КР-ДВ", "CAPITAL_REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();

    assertThat(
            jdbc.queryForMap(
                """
                select execution_state,acceptance_state,reclassification_state,movement_to_repair
                  from maintenance_repair
                 where id=?
                """,
                repairId))
        .containsEntry("execution_state", "QUEUED")
        .containsEntry("acceptance_state", "NOT_READY")
        .containsEntry("reclassification_state", "EXTERNAL_CAPITAL")
        .containsEntry("movement_to_repair", true);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from integration_reconciliation
                 where repair_id=? and dependency_type='LOGISTICS'
                   and operation_type='CREATE_DRIVER_TASK'
                   and response_snapshot->>'kind'='CAPITAL_TO_PRODUCTION'
                """,
                Integer.class,
                repairId))
        .isOne();
    assertThat(maintenance.acceptance(warehouseId, null, null, 0, 200).items())
        .extracting(AcceptanceProjection::repairId)
        .doesNotContain(repairId);
  }

  @Test
  void inventoryCapitalOutcomeRemainsOutsideAcceptanceUntilCapitalWorkCompletes() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FreezeInventoryPlanRequest ordinary = autoRequest(inventoryId, findingId, List.of());
    FreezeInventoryPlanRequest capital =
        new FreezeInventoryPlanRequest(
            ordinary.warehouseId(),
            ordinary.inventoryId(),
            ordinary.findingId(),
            ordinary.sourceRevision(),
            ordinary.mode(),
            ordinary.lines(),
            ordinary.plan(),
            ordinary.mediaReferences(),
            ordinary.priority(),
            ordinary.coverMediaId(),
            false,
            null,
            null,
            true);
    FrozenInventoryPlanResponse frozen = inventory.freeze(capital).response();
    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    InventoryPublicationFindingInput finding =
        publicationFinding(findingId, rentalItemId, 7L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId =
        publications.apply(inventoryId, findingId, UUID.randomUUID(), request).response().repairId();

    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(rentalItemId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 7, warehouseId, "БТ-КР", "FREE"));
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
                eq("QUEUE_TO_CAPITAL_REPAIR"),
                eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                rentalItemId, 8, warehouseId, "БТ-КР", "CAPITAL_REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();

    assertThat(
            jdbc.queryForMap(
                """
                select execution_state, acceptance_state, reclassification_state,
                       task_generation_state
                  from maintenance_repair
                 where id=?
                """,
                repairId))
        .containsEntry("execution_state", "QUEUED")
        .containsEntry("acceptance_state", "NOT_READY")
        .containsEntry("reclassification_state", "EXTERNAL_CAPITAL")
        .containsEntry("task_generation_state", "NOT_REQUIRED");
    assertThat(
            jdbc.queryForMap(
                """
                select state, task_generation_state
                  from repair_stage
                 where repair_id=?
                """,
                repairId))
        .containsEntry("state", "QUEUED")
        .containsEntry("task_generation_state", "NOT_REQUIRED");
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from integration_reconciliation
                 where repair_id=? and dependency_type='TASK_BOARD'
                """,
                Integer.class,
                repairId))
        .isZero();
    assertThat(maintenance.acceptance(warehouseId, null, null, 0, 200).items())
        .extracting(AcceptanceProjection::repairId)
        .doesNotContain(repairId);

    jdbc.update(
        "update maintenance_repair set execution_state='COMPLETED', acceptance_state='PENDING' where id=?",
        repairId);
    jdbc.update(
        "update repair_stage set state='DONE', completed_at=clock_timestamp() where repair_id=?",
        repairId);

    InventoryPublicationReconciliationService.PublicationResult reasserted =
        publications.apply(inventoryId, findingId, UUID.randomUUID(), request);

    assertThat(reasserted.replayed()).isTrue();
    assertThat(reasserted.response().repairId()).isEqualTo(repairId);
    assertThat(
            jdbc.queryForMap(
                """
                select execution_state, acceptance_state, reclassification_state
                  from maintenance_repair
                 where id=?
                """,
                repairId))
        .containsEntry("execution_state", "QUEUED")
        .containsEntry("acceptance_state", "NOT_READY")
        .containsEntry("reclassification_state", "EXTERNAL_CAPITAL");
    assertThat(
            jdbc.queryForMap(
                "select state, completed_at from repair_stage where repair_id=?",
                repairId))
        .containsEntry("state", "QUEUED")
        .containsEntry("completed_at", null);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from integration_reconciliation
                 where repair_id=? and dependency_type='ASSET'
                """,
                Integer.class,
                repairId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from integration_reconciliation
                 where repair_id=? and dependency_type='LOGISTICS'
                """,
                Integer.class,
                repairId))
        .isZero();
  }

  @Test
  void supplementedInspectionCreatesANewImmutableSourceRevisionWithoutOverwritingTheFirst() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    FreezeInventoryPlanRequest initialRequest = autoRequest(inventoryId, findingId, List.of());
    FrozenInventoryPlanResponse initial = inventory.freeze(initialRequest).response();
    FreezeInventoryPlanRequest supplementedRequest = new FreezeInventoryPlanRequest(
        warehouseId,
        inventoryId,
        findingId,
        4L,
        initialRequest.mode(),
        initialRequest.lines(),
        initialRequest.plan(),
        initialRequest.mediaReferences(),
        initialRequest.priority(),
        initialRequest.coverMediaId());

    InventoryMaintenanceService.FreezeResult supplemented = inventory.freeze(supplementedRequest);
    assertThat(supplemented.replayed()).isFalse();
    assertThat(supplemented.response().sourceRevision()).isEqualTo(4L);
    assertThat(inventory.freeze(supplementedRequest).replayed()).isTrue();
    assertThat(jdbc.queryForList("""
        select source_revision from inventory_repair_source
        where inventory_id=? and finding_id=?
        order by source_revision
        """, Long.class, inventoryId, findingId)).containsExactly(3L, 4L);

    UUID rentalItemId = UUID.randomUUID();
    rentalItems.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, "FREE", 7));
    InventoryMaintenanceService.UpsertResult created = inventory.upsert(
        inventoryId,
        findingId,
        new UpsertInventoryRepairRequest(
            warehouseId,
            4L,
            rentalItemId,
            7L,
            LocalDate.of(2026, 7, 17),
            supplemented.response().fingerprint(),
            supplemented.response().snapshot()));

    assertThat(created.replayed()).isFalse();
    assertThat(created.source().sourceRevision()).isEqualTo(4L);
    assertThat(jdbc.queryForObject("""
        select repair_id from inventory_repair_source
        where inventory_id=? and finding_id=? and source_revision=3
        """, UUID.class, inventoryId, findingId)).isNull();
    assertThat(jdbc.queryForObject("""
        select repair_id from inventory_repair_source
        where inventory_id=? and finding_id=? and source_revision=4
        """, UUID.class, inventoryId, findingId)).isEqualTo(created.repairId());
    assertThat(initial.sourceRevision()).isEqualTo(3L);
  }

  @Test
  void inventoryAdmissionAndRoutingPreflightsDoNotRunWithMaintenanceTransactions() {
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(any(UUID.class), any()))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          UUID requestedWarehouseId = invocation.getArgument(0);
          MaintenanceDependencyGateway.WarehouseOperationDirection direction =
              invocation.getArgument(1);
          return new MaintenanceDependencyGateway.WarehouseOperationAdmission(
              requestedWarehouseId,
              1L,
              MaintenanceDependencyGateway.WarehouseLifecycleState.ACTIVE,
              direction,
              true);
        });
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
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

    UUID upsertInventoryId = UUID.randomUUID();
    UUID upsertFindingId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozenUpsert = inventory.freeze(
        autoRequest(upsertInventoryId, upsertFindingId, List.of())).response();
    UUID upsertAssetId = UUID.randomUUID();
    rentalItems.saveAndFlush(RentalItemFactProjection.create(
        upsertAssetId, warehouseId, "FREE", 7L));
    InventoryMaintenanceService.UpsertResult upsert = inventory.upsert(
        upsertInventoryId,
        upsertFindingId,
        new UpsertInventoryRepairRequest(
            warehouseId,
            3L,
            upsertAssetId,
            7L,
            LocalDate.of(2026, 8, 5),
            frozenUpsert.fingerprint(),
            frozenUpsert.snapshot()));

    assertThat(jdbc.queryForObject(
        """
        select count(*) from warehouse_operation_mark_outbox
         where warehouse_id=? and operation_id=?
        """, Integer.class, warehouseId, upsert.repairId())).isOne();

    UUID publicationInventoryId = UUID.randomUUID();
    UUID publicationFindingId = UUID.randomUUID();
    FrozenInventoryPlanResponse frozenPublication = inventory.freeze(
        autoRequest(publicationInventoryId, publicationFindingId, List.of())).response();
    UUID publicationAssetId = UUID.randomUUID();
    rentalItems.saveAndFlush(RentalItemFactProjection.create(
        publicationAssetId, warehouseId, "FREE", 11L));
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozenPublication.snapshot());
    InventoryPublicationFindingInput finding = publicationFinding(
        publicationFindingId, publicationAssetId, 11L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationReconciliationService.PublicationResult publication = publications.apply(
        publicationInventoryId,
        publicationFindingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null));

    assertThat(jdbc.queryForObject(
        """
        select count(*) from warehouse_operation_mark_outbox
         where warehouse_id=? and operation_id=?
        """, Integer.class, warehouseId, publication.response().repairId())).isOne();
    verify(dependencies, times(2)).warehouseAdmission(
        eq(warehouseId),
        eq(MaintenanceDependencyGateway.WarehouseOperationDirection.INCOMING));
  }

  @Test
  void inventoryBoundariesRejectCallerTransactionsBeforeAnyGatewayCall() {
    TransactionTemplate callerTransaction = new TransactionTemplate(transactionManager);

    callerTransaction.executeWithoutResult(status ->
        assertThatThrownBy(() -> inventory.freeze(autoRequest(UUID.randomUUID(), UUID.randomUUID(), List.of())))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inside a caller transaction"));
    callerTransaction.executeWithoutResult(status ->
        assertThatThrownBy(() -> publications.apply(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), null))
            .isInstanceOf(IllegalStateException.class)
            .hasMessageContaining("inside a caller transaction"));

    verifyNoInteractions(dependencies);
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

    saveAuthoritativeWorkOutcome(
        new InventoryPublicationSourceId(inventoryId, 1L, findingId),
        rentalItemId,
        created.repairId(),
        3L,
        finalPlanSha(1L),
        finalPlanSha(101L));
    saveAuthoritativeWorkOutcome(
        new InventoryPublicationSourceId(inventoryId, 2L, findingId),
        rentalItemId,
        created.repairId(),
        3L,
        finalPlanSha(2L),
        finalPlanSha(102L));
    assertThat(
            authoritativeOutcomeRepository
                .findNewestAppliedByTargetRepairId(created.repairId())
                .orElseThrow()
                .getId()
                .getFinalPlanVersion())
        .isEqualTo(2L);

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
  void repeatedCatalogWorkCreatesOnePhysicalQueueStageWithEveryLine() {
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
        .containsExactly(workNodeId);
    assertThat(frozen.snapshot().stages())
        .extracting(InventoryPlanStageSnapshot::order)
        .containsExactly(0);

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
        .containsExactly(2);
    assertThat(
            jdbc.queryForObject(
                "select count(distinct primary_line_id) from repair_stage where repair_id=?",
                Integer.class,
                created.repairId()))
        .isEqualTo(1);
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
  void emptyPublicationPreflightPreservesFinalPlanIdentityWithoutMaintenanceCandidates() {
    UUID inventoryId = UUID.randomUUID();
    long finalPlanVersion = 7L;
    String finalPlanSha256 = finalPlanSha(finalPlanVersion);

    InventoryPublicationPreflightResponse preflight = publications.preflight(
        new InventoryPublicationPreflightRequest(
            inventoryId,
            warehouseId,
            finalPlanVersion,
            finalPlanSha256,
            List.of()));

    assertThat(preflight.inventoryId()).isEqualTo(inventoryId);
    assertThat(preflight.finalPlanVersion()).isEqualTo(finalPlanVersion);
    assertThat(preflight.finalPlanSha256()).isEqualTo(finalPlanSha256);
    assertThat(preflight.findings()).isEmpty();
  }

  @Test
  void authoritativeNoWorkCancelsStartedTaskDriverLeaseAndEveryLocalPredecessor() {
    AuthoritativeActiveRepair active = authoritativeActiveRepair(true, true);
    MaintenanceEstimate estimate = estimates.saveAndFlush(MaintenanceEstimate.create(
        warehouseId,
        active.assetId(),
        active.assetVersion(),
        catalogId,
        LocalDate.of(2026, 8, 5),
        "Before inventory",
        "Preserved predecessor",
        "{}"));
    insertEventHead("ESTIMATE", estimate.getId(), estimate.getVersion());
    UUID driverTaskId = UUID.randomUUID();

    when(dependencies.getTask(active.externalTaskId()))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.TaskSnapshot(
              active.externalTaskId(), 4L, "IN_PROGRESS", List.of());
        });
    when(dependencies.cancelTask(any(), eq(active.externalTaskId()), eq(4L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.TaskSnapshot(
              active.externalTaskId(), 5L, "CANCELLED", List.of());
        });
    when(dependencies.maintenanceDriverTaskCompensation(
            active.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return driverCompensation(
              active.repairId(),
              MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
              MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.PENDING);
        });
    when(dependencies.cancelMaintenanceDriverTaskCompensation(
            any(),
            eq(active.repairId()),
            eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
              active.repairId(),
              MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
              MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.CANCELLED,
              driverTaskId,
              1L,
              "CANCELLED",
              null,
              null,
              null,
              null,
              null);
        });
    doAnswer(invocation -> {
      assertNoRemoteTransaction();
      return null;
    }).when(dependencies).releaseLease(
        any(),
        eq(active.leaseId()),
        eq(0L),
        eq(13L),
        eq("MAINTENANCE_REPAIR"),
        eq(active.repairId().toString()));

    InventoryNoWorkOutcomeResult result = authoritativeOutcomes.applyNoWork(
        active.inventoryId(),
        active.findingId(),
        UUID.randomUUID(),
        noWorkRequest(active, 2L, active.completedAt()));

    assertThat(result.replay()).isFalse();
    assertThat(result.supersededEstimateIds()).containsExactly(estimate.getId());
    assertThat(result.supersededRepairIds()).containsExactly(active.repairId());
    assertThat(result.cancelledExternalTaskIds()).containsExactly(active.externalTaskId());
    assertThat(result.cancelledDriverTaskIds()).containsExactly(driverTaskId);
    assertThat(result.releasedLeaseIds()).containsExactly(active.leaseId());
    assertThat(jdbc.queryForMap(
        "select execution_state,acceptance_state,lease_reconciliation_state "
            + "from maintenance_repair where id=?",
        active.repairId()))
        .containsEntry("execution_state", "CANCELLED")
        .containsEntry("acceptance_state", "NOT_READY")
        .containsEntry("lease_reconciliation_state", "RELEASED");
    assertThat(jdbc.queryForObject(
        "select inventory_superseded_at is not null from maintenance_estimate where id=?",
        Boolean.class,
        estimate.getId())).isTrue();
    assertThat(jdbc.queryForObject(
        "select state from repair_place_allocation where repair_id=?",
        String.class,
        active.repairId())).isEqualTo("RELEASED");
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        active.assetId())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where id=?", Integer.class, active.repairId()))
        .isOne();
    verify(dependencies).cancelTask(any(), eq(active.externalTaskId()), eq(4L));
    verify(dependencies).cancelMaintenanceDriverTaskCompensation(
        any(),
        eq(active.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR));
    verify(dependencies).releaseLease(
        any(),
        eq(active.leaseId()),
        eq(0L),
        eq(13L),
        eq("MAINTENANCE_REPAIR"),
        eq(active.repairId().toString()));
  }

  @Test
  void correctedWorkRouteSettlesActivePredecessorBeforeMaterializingCapitalReplacement()
      throws Exception {
    AuthoritativeActiveRepair active = authoritativeActiveRepair(true, false);
    String originalSnapshot = jdbc.queryForObject(
        "select request_snapshot::text from inventory_authoritative_outcome "
            + "where inventory_id=? and final_plan_version=1 and finding_id=?",
        String.class,
        active.inventoryId(),
        active.findingId());
    InventoryPublicationApplyRequest original =
        mapper.readValue(originalSnapshot, InventoryPublicationApplyRequest.class);
    ObjectNode capitalSnapshot = ((ObjectNode) original.snapshot()).deepCopy();
    capitalSnapshot.put("forceCapitalRepair", true);
    InventoryPublicationApplyRequest corrected = new InventoryPublicationApplyRequest(
        original.warehouseId(),
        2L,
        finalPlanSha(2L),
        original.findingRevision() + 1,
        original.assetId(),
        active.assetVersion(),
        active.assetVersion(),
        original.inventoryCompletedAt(),
        canonicalizer.sha256(capitalSnapshot),
        5,
        false,
        null,
        original.repairScheduledDate().plusDays(1),
        capitalSnapshot,
        original.media(),
        original.snapshotSchemaVersion(),
        InventoryPublicationStrategy.MERGE,
        InventoryPublicationTargetKind.REPAIR,
        active.repairId(),
        true);
    UUID driverTaskId = UUID.randomUUID();

    when(dependencies.getTask(active.externalTaskId()))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.TaskSnapshot(
              active.externalTaskId(), 4L, "IN_PROGRESS", List.of());
        });
    when(dependencies.cancelTask(any(), eq(active.externalTaskId()), eq(4L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.TaskSnapshot(
              active.externalTaskId(), 5L, "CANCELLED", List.of());
        });
    when(dependencies.maintenanceDriverTaskCompensation(
            active.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR))
        .thenReturn(driverCompensation(
            active.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
            MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.PENDING));
    when(dependencies.cancelMaintenanceDriverTaskCompensation(
            any(),
            eq(active.repairId()),
            eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenReturn(new MaintenanceDependencyGateway.MaintenanceDriverTaskCompensation(
            active.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
            MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.CANCELLED,
            driverTaskId,
            1L,
            "CANCELLED",
            null,
            null,
            null,
            null,
            null));
    doAnswer(invocation -> {
      assertNoRemoteTransaction();
      return null;
    }).when(dependencies).releaseLease(
        any(),
        eq(active.leaseId()),
        eq(0L),
        eq(13L),
        eq("MAINTENANCE_REPAIR"),
        eq(active.repairId().toString()));
    UUID key = UUID.randomUUID();

    InventoryPublicationReconciliationService.PublicationResult result = publications.apply(
        active.inventoryId(), active.findingId(), key, corrected);

    UUID correctedRepairId = result.response().repairId();
    assertThat(result.replayed()).isFalse();
    assertThat(correctedRepairId).isNotEqualTo(active.repairId());
    assertThat(jdbc.queryForMap(
            "select execution_state,lease_reconciliation_state from maintenance_repair where id=?",
            active.repairId()))
        .containsEntry("execution_state", "CANCELLED")
        .containsEntry("lease_reconciliation_state", "RELEASED");
    assertThat(jdbc.queryForMap(
            "select execution_state,priority,movement_to_repair,force_capital_repair "
                + "from maintenance_repair where id=?",
            correctedRepairId))
        .containsEntry("execution_state", "DRAFT")
        .containsEntry("priority", 5)
        .containsEntry("movement_to_repair", false)
        .containsEntry("force_capital_repair", true);
    assertThat(jdbc.queryForMap(
            "select task_outcome,driver_outcome,lease_released,local_superseded "
                + "from inventory_authoritative_outcome_target "
                + "where inventory_id=? and final_plan_version=2 and finding_id=? and target_id=?",
            active.inventoryId(),
            active.findingId(),
            active.repairId()))
        .containsEntry("task_outcome", "CANCELLED")
        .containsEntry("driver_outcome", "CANCELLED")
        .containsEntry("lease_released", true)
        .containsEntry("local_superseded", true);
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        active.assetId())).isOne();

    InventoryPublicationReconciliationService.PublicationResult replay = publications.apply(
        active.inventoryId(), active.findingId(), key, corrected);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response().repairId()).isEqualTo(correctedRepairId);
    verify(dependencies).cancelTask(any(), eq(active.externalTaskId()), eq(4L));
    verify(dependencies).cancelMaintenanceDriverTaskCompensation(
        any(),
        eq(active.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR));
    verify(dependencies).releaseLease(
        any(),
        eq(active.leaseId()),
        eq(0L),
        eq(13L),
        eq("MAINTENANCE_REPAIR"),
        eq(active.repairId().toString()));
  }

  @ParameterizedTest(name = "remote task exists: {0}")
  @ValueSource(booleans = {true, false})
  void authoritativeWorkProbesOrphanedTaskBeforeReplacingRepair(boolean remoteTaskExists)
      throws Exception {
    AuthoritativeActiveRepair active = authoritativeActiveRepair(false, false);
    jdbc.update(
        "update maintenance_repair set task_board_version=null, "
            + "task_generation_state='PENDING_GENERATION', delivery_state='RETRY_PENDING' "
            + "where id=?",
        active.repairId());
    jdbc.update(
        "update repair_stage set external_queue_entry_id=null, task_board_version=null, "
            + "task_generation_state='PENDING_GENERATION', delivery_state='RETRY_PENDING' "
            + "where repair_id=?",
        active.repairId());
    assertThat(jdbc.queryForObject(
        "select task_board_version from maintenance_repair where id=?",
        Long.class,
        active.repairId())).isNull();

    String originalSnapshot = jdbc.queryForObject(
        "select request_snapshot::text from inventory_authoritative_outcome "
            + "where inventory_id=? and final_plan_version=1 and finding_id=?",
        String.class,
        active.inventoryId(),
        active.findingId());
    InventoryPublicationApplyRequest original =
        mapper.readValue(originalSnapshot, InventoryPublicationApplyRequest.class);
    ObjectNode changedSnapshot = ((ObjectNode) original.snapshot()).deepCopy();
    ((ObjectNode) changedSnapshot.withArray("lines").get(0)).put("quantity", "7.500000");
    InventoryPublicationApplyRequest corrected = new InventoryPublicationApplyRequest(
        original.warehouseId(),
        2L,
        finalPlanSha(2L),
        original.findingRevision() + 1,
        original.assetId(),
        active.assetVersion(),
        active.assetVersion(),
        original.inventoryCompletedAt(),
        canonicalizer.sha256(changedSnapshot),
        5,
        false,
        null,
        original.repairScheduledDate(),
        changedSnapshot,
        original.media(),
        original.snapshotSchemaVersion(),
        InventoryPublicationStrategy.MERGE,
        InventoryPublicationTargetKind.REPAIR,
        active.repairId(),
        false);

    if (remoteTaskExists) {
      when(dependencies.getTask(active.externalTaskId()))
          .thenAnswer(invocation -> {
            assertNoRemoteTransaction();
            return new MaintenanceDependencyGateway.TaskSnapshot(
                active.externalTaskId(), 19L, "ACTIVE", List.of());
          });
      when(dependencies.cancelTask(any(), eq(active.externalTaskId()), eq(19L)))
          .thenAnswer(invocation -> {
            assertNoRemoteTransaction();
            return new MaintenanceDependencyGateway.TaskSnapshot(
                active.externalTaskId(), 20L, "CANCELLED", List.of());
          });
    } else {
      when(dependencies.getTask(active.externalTaskId()))
          .thenAnswer(invocation -> {
            assertNoRemoteTransaction();
            throw new MaintenanceDependencyException(
                HttpStatus.NOT_FOUND, "task does not exist");
          });
    }
    doAnswer(invocation -> {
      assertNoRemoteTransaction();
      return null;
    }).when(dependencies).releaseLease(
        any(),
        eq(active.leaseId()),
        eq(0L),
        eq(13L),
        eq("MAINTENANCE_REPAIR"),
        eq(active.repairId().toString()));

    InventoryPublicationReconciliationService.PublicationResult replaced = publications.apply(
        active.inventoryId(), active.findingId(), UUID.randomUUID(), corrected);

    assertThat(replaced.response().repairId()).isNotEqualTo(active.repairId());
    assertThat(jdbc.queryForMap(
            "select execution_state,task_generation_state from maintenance_repair where id=?",
            active.repairId()))
        .containsEntry("execution_state", "CANCELLED")
        .containsEntry("task_generation_state", "NOT_REQUIRED");
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' "
            + "and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        active.assetId())).isOne();
    assertThat(jdbc.queryForMap(
            "select task_external_id,task_expected_version,task_attempt_count,task_outcome,"
                + "local_superseded from inventory_authoritative_outcome_target "
                + "where inventory_id=? and final_plan_version=2 and finding_id=? "
                + "and target_id=?",
            active.inventoryId(),
            active.findingId(),
            active.repairId()))
        .containsEntry("task_external_id", active.externalTaskId())
        .containsEntry("task_expected_version", remoteTaskExists ? 20L : 0L)
        .containsEntry("task_attempt_count", remoteTaskExists ? 1L : 0L)
        .containsEntry("task_outcome", remoteTaskExists ? "CANCELLED" : "NOT_FOUND")
        .containsEntry("local_superseded", true);
    verify(dependencies).getTask(active.externalTaskId());
    if (remoteTaskExists) {
      verify(dependencies).cancelTask(any(), eq(active.externalTaskId()), eq(19L));
    } else {
      verify(dependencies, never()).cancelTask(any(), any(), anyLong());
    }
    verify(dependencies).releaseLease(
        any(),
        eq(active.leaseId()),
        eq(0L),
        eq(13L),
        eq("MAINTENANCE_REPAIR"),
        eq(active.repairId().toString()));
  }

  @Test
  void authoritativeNoWorkRecoversLostRemoteResponseWithTheSameDurableAttempts() {
    AuthoritativeActiveRepair active = authoritativeActiveRepair(true, false);
    UUID key = UUID.randomUUID();
    InventoryNoWorkOutcomeRequest request =
        noWorkRequest(active, 2L, active.completedAt());
    AtomicReference<Integer> driverReads = new AtomicReference<>(0);

    when(dependencies.getTask(active.externalTaskId()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            active.externalTaskId(), 0L, "WAITING", List.of()));
    when(dependencies.cancelTask(any(), eq(active.externalTaskId()), eq(0L)))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            active.externalTaskId(), 1L, "CANCELLED", List.of()));
    when(dependencies.maintenanceDriverTaskCompensation(
            active.repairId(),
            MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          int read = driverReads.updateAndGet(value -> value + 1);
          return driverCompensation(
              active.repairId(),
              MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR,
              read == 1
                  ? MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.PENDING
                  : MaintenanceDependencyGateway.MaintenanceDriverTaskCompensationOutcome.CANCELLED);
        });
    when(dependencies.cancelMaintenanceDriverTaskCompensation(
            any(),
            eq(active.repairId()),
            eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR)))
        .thenThrow(new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE,
            "response lost after logistics committed cancellation"));

    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        active.inventoryId(), active.findingId(), key, request))
        .isInstanceOf(MaintenanceDependencyException.class);
    assertThat(jdbc.queryForObject(
        "select execution_state from maintenance_repair where id=?",
        String.class,
        active.repairId())).isEqualTo("QUEUED");
    assertThat(jdbc.queryForMap(
        "select task_attempt_count,driver_attempt_count,lease_attempt_count "
            + "from inventory_authoritative_outcome_target where target_id=?",
        active.repairId()))
        .containsEntry("task_attempt_count", 1L)
        .containsEntry("driver_attempt_count", 1L)
        .containsEntry("lease_attempt_count", 0L);

    InventoryNoWorkOutcomeResult recovered = authoritativeOutcomes.applyNoWork(
        active.inventoryId(), active.findingId(), key, request);

    assertThat(recovered.replay()).isFalse();
    assertThat(recovered.supersededRepairIds()).containsExactly(active.repairId());
    assertThat(jdbc.queryForMap(
        "select task_attempt_count,driver_attempt_count,lease_attempt_count,local_superseded "
            + "from inventory_authoritative_outcome_target where target_id=?",
        active.repairId()))
        .containsEntry("task_attempt_count", 1L)
        .containsEntry("driver_attempt_count", 2L)
        .containsEntry("lease_attempt_count", 1L)
        .containsEntry("local_superseded", true);
    verify(dependencies, times(1)).cancelTask(any(), eq(active.externalTaskId()), eq(0L));
    verify(dependencies, times(1)).cancelMaintenanceDriverTaskCompensation(
        any(),
        eq(active.repairId()),
        eq(MaintenanceDependencyGateway.MaintenanceDriverTaskKind.DELIVER_TO_REPAIR));
  }

  @Test
  void authoritativeWatermarkAndReceiptRejectMismatchesAndReplayTheLatestSource() {
    UUID assetId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 7L));
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    OffsetDateTime completedAt =
        OffsetDateTime.of(2026, 8, 19, 8, 0, 0, 0, ZoneOffset.UTC);
    InventoryNoWorkOutcomeRequest request = noWorkRequest(
        assetId, 7L, 1L, completedAt);

    InventoryNoWorkOutcomeResult first =
        authoritativeOutcomes.applyNoWork(inventoryId, findingId, key, request);
    InventoryNoWorkOutcomeResult sameKey =
        authoritativeOutcomes.applyNoWork(inventoryId, findingId, key, request);
    InventoryNoWorkOutcomeResult newKey =
        authoritativeOutcomes.applyNoWork(inventoryId, findingId, UUID.randomUUID(), request);

    assertThat(first.replay()).isFalse();
    assertThat(sameKey.replay()).isTrue();
    assertThat(newKey.replay()).isFalse();
    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        inventoryId,
        findingId,
        key,
        new InventoryNoWorkOutcomeRequest(
            warehouseId,
            assetId,
            completedAt,
            1L,
            "f".repeat(64),
            1L,
            7L,
            "FREE")))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("Idempotency-Key");
    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        noWorkRequest(assetId, 7L, 1L, completedAt.minusSeconds(1))))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("older completed inventory");
    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        noWorkRequest(assetId, 7L, 1L, completedAt)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("same completion time");
    InventoryNoWorkOutcomeRequest correctedRequest = new InventoryNoWorkOutcomeRequest(
        request.warehouseId(),
        request.assetId(),
        request.inventoryCompletedAt(),
        2L,
        "e".repeat(64),
        request.findingRevision(),
        request.authoritativeAssetVersion(),
        request.desiredStatus());
    InventoryNoWorkOutcomeResult corrected = authoritativeOutcomes.applyNoWork(
        inventoryId, findingId, UUID.randomUUID(), correctedRequest);
    assertThat(corrected.replay()).isFalse();
    assertThat(jdbc.queryForMap(
            "select final_plan_version, final_plan_sha256 "
                + "from inventory_authoritative_outcome_watermark where asset_id=?",
            assetId))
        .containsEntry("final_plan_version", 2L)
        .containsEntry("final_plan_sha256", "e".repeat(64));
    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        request))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("advance strictly");
    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        new InventoryNoWorkOutcomeRequest(
            request.warehouseId(),
            request.assetId(),
            request.inventoryCompletedAt().plusSeconds(1),
            3L,
            finalPlanSha(3L),
            request.findingRevision(),
            request.authoritativeAssetVersion(),
            request.desiredStatus())))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("completion time");
    InventoryNoWorkOutcomeResult newer = authoritativeOutcomes.applyNoWork(
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        noWorkRequest(assetId, 7L, 1L, completedAt.plusSeconds(1)));
    assertThat(newer.replay()).isFalse();
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome_watermark where asset_id=?",
        Integer.class,
        assetId)).isOne();
  }

  @Test
  void correctedPlanCanChangeWorkToNoWorkAndBackWithoutLeavingTwoActiveRepairs() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest original = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID originalRepairId = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), original).response().repairId();
    stubTaskNotFound(repairs.findById(originalRepairId).orElseThrow().getExternalTaskId());
    InventoryNoWorkOutcomeRequest noWork = new InventoryNoWorkOutcomeRequest(
        warehouseId,
        assetId,
        original.inventoryCompletedAt(),
        2L,
        finalPlanSha(2L),
        original.findingRevision() + 1,
        11L,
        "FREE");

    InventoryNoWorkOutcomeResult cleared = authoritativeOutcomes.applyNoWork(
        inventoryId, findingId, UUID.randomUUID(), noWork);

    assertThat(cleared.replay()).isFalse();
    assertThat(cleared.supersededRepairIds()).containsExactly(originalRepairId);
    assertThat(repairs.findById(originalRepairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForObject(
        "select target_repair_id from inventory_authoritative_outcome "
            + "where inventory_id=? and final_plan_version=2 and finding_id=?",
        UUID.class,
        inventoryId,
        findingId)).isNull();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isZero();

    InventoryPublicationApplyRequest restoredWork = new InventoryPublicationApplyRequest(
        original.warehouseId(),
        3L,
        finalPlanSha(3L),
        original.findingRevision() + 2,
        original.assetId(),
        original.assetVersion(),
        original.authoritativeAssetVersion(),
        original.inventoryCompletedAt(),
        original.planFingerprintSha256(),
        original.priority(),
        original.movementToRepair(),
        original.movementScheduledDate(),
        original.repairScheduledDate(),
        original.snapshot(),
        original.media(),
        original.snapshotSchemaVersion(),
        original.strategy(),
        original.selectedTargetKind(),
        original.selectedTargetId(),
        original.forceCapitalRepair());
    UUID restoredKey = UUID.randomUUID();

    InventoryPublicationReconciliationService.PublicationResult restored = publications.apply(
        inventoryId, findingId, restoredKey, restoredWork);

    assertThat(restored.replayed()).isFalse();
    assertThat(restored.response().repairId()).isNotEqualTo(originalRepairId);
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();
    assertThat(publications.apply(inventoryId, findingId, restoredKey, restoredWork).replayed())
        .isTrue();
  }

  @Test
  void authoritativeNoWorkNewKeyReassertsNewPredecessorWhileSameKeyStaysFrozen() {
    UUID assetId = UUID.randomUUID();
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 7L));
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID firstKey = UUID.randomUUID();
    InventoryNoWorkOutcomeRequest request = noWorkRequest(
        assetId,
        7L,
        1L,
        OffsetDateTime.of(2026, 8, 19, 8, 30, 0, 0, ZoneOffset.UTC));

    authoritativeOutcomes.applyNoWork(inventoryId, findingId, firstKey, request);
    MaintenanceRepair concurrent = directDraftRepair(assetId, 7L, "After first FREE outcome");
    stubTaskNotFound(concurrent.getExternalTaskId());

    InventoryNoWorkOutcomeResult sameKey =
        authoritativeOutcomes.applyNoWork(inventoryId, findingId, firstKey, request);

    assertThat(sameKey.replay()).isTrue();
    assertThat(repairs.findById(concurrent.getId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);

    InventoryNoWorkOutcomeResult reasserted = authoritativeOutcomes.applyNoWork(
        inventoryId, findingId, UUID.randomUUID(), request);

    assertThat(reasserted.replay()).isFalse();
    assertThat(reasserted.supersededRepairIds()).containsExactly(concurrent.getId());
    assertThat(repairs.findById(concurrent.getId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome where inventory_id=?",
        Integer.class,
        inventoryId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome_receipt where inventory_id=?",
        Integer.class,
        inventoryId)).isEqualTo(2);
  }

  @Test
  void authoritativeWorkFullyReplacesMultipleActiveTargetsAndKeepsFullFrozenPlan() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    UUID firstRepairId = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null))
        .response()
        .repairId();
    MaintenanceRepair secondRepair = repairs.saveAndFlush(MaintenanceRepair.primary(
        warehouseId,
        assetId,
        11L,
        null,
        RepairOrigin.DIRECT_REPAIR,
        LocalDate.of(2026, 8, 5),
        "Concurrent predecessor",
        "{}"));
    insertEventHead("REPAIR", secondRepair.getId(), secondRepair.getVersion());
    stubTaskNotFound(repairs.findById(firstRepairId).orElseThrow().getExternalTaskId());
    stubTaskNotFound(secondRepair.getExternalTaskId());
    MaintenanceEstimate estimate = estimates.saveAndFlush(MaintenanceEstimate.create(
        warehouseId,
        assetId,
        11L,
        catalogId,
        LocalDate.of(2026, 8, 5),
        "Concurrent estimate",
        null,
        "{}"));
    insertEventHead("ESTIMATE", estimate.getId(), estimate.getVersion());

    ObjectNode changed = rawSnapshot.deepCopy();
    ((ObjectNode) changed.withArray("lines").get(0)).put("quantity", "7.500000");
    InventoryPublicationFindingInput latest = publicationFinding(
        findingId, assetId, 11L, changed, 2, 5, false, null);
    InventoryPublicationReconciliationService.PublicationResult replaced = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            latest,
            2L,
            InventoryPublicationStrategy.MERGE,
            InventoryPublicationTargetKind.REPAIR,
            firstRepairId));

    assertThat(replaced.response().outcome()).isEqualTo(InventoryPublicationOutcome.CREATED);
    assertThat(replaced.response().repairId())
        .isNotIn(firstRepairId, secondRepair.getId());
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where id in (?,?) and execution_state='CANCELLED'",
        Integer.class,
        firstRepairId,
        secondRepair.getId())).isEqualTo(2);
    assertThat(jdbc.queryForObject(
        "select inventory_superseded_at is not null from maintenance_estimate where id=?",
        Boolean.class,
        estimate.getId())).isTrue();
    assertThat(jdbc.queryForObject(
        "select work_lines->0->>'quantity' from repair_stage where repair_id=?",
        String.class,
        replaced.response().repairId())).isEqualTo("7.5");
    assertThat(replaced.response().delta().lines())
        .allMatch(line -> line.disposition() == InventoryPublicationDeltaDisposition.RETAINED);
  }

  @Test
  void authoritativeWorkReassertsEquivalentCorrectionsAndReplacesChangedContent() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest request = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID firstKey = UUID.randomUUID();
    InventoryPublicationReconciliationService.PublicationResult first = publications.apply(
        inventoryId, findingId, firstKey, request);
    UUID exactRepairId = first.response().repairId();
    MaintenanceRepair firstConcurrent =
        directDraftRepair(assetId, 11L, "After first WORK outcome");
    stubTaskNotFound(repairs.findById(exactRepairId).orElseThrow().getExternalTaskId());
    stubTaskNotFound(firstConcurrent.getExternalTaskId());
    UUID secondKey = UUID.randomUUID();

    InventoryPublicationReconciliationService.PublicationResult reasserted = publications.apply(
        inventoryId, findingId, secondKey, request);

    assertThat(reasserted.replayed()).isTrue();
    assertThat(reasserted.response().repairId()).isEqualTo(exactRepairId);
    assertThat(repairs.findById(exactRepairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    assertThat(repairs.findById(firstConcurrent.getId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_publication_source where inventory_id=? "
            + "and final_plan_version=1 and finding_id=?",
        Integer.class,
        inventoryId,
        findingId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where repair_id=? "
            + "and dependency_type='ASSET' and operation_type='QUEUE_REPAIR'",
        Integer.class,
        exactRepairId)).isOne();

    MaintenanceRepair secondConcurrent =
        directDraftRepair(assetId, 11L, "After first same-source reassertion");
    stubTaskNotFound(secondConcurrent.getExternalTaskId());
    InventoryPublicationReconciliationService.PublicationResult frozen = publications.apply(
        inventoryId, findingId, secondKey, request);

    assertThat(frozen.replayed()).isTrue();
    assertThat(repairs.findById(secondConcurrent.getId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);

    InventoryPublicationReconciliationService.PublicationResult recovered = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), request);

    assertThat(recovered.replayed()).isTrue();
    assertThat(recovered.response().repairId()).isEqualTo(exactRepairId);
    assertThat(repairs.findById(secondConcurrent.getId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome_receipt where inventory_id=?",
        Integer.class,
        inventoryId)).isEqualTo(3);

    InventoryPublicationApplyRequest correctedRequest = new InventoryPublicationApplyRequest(
        request.warehouseId(),
        2L,
        finalPlanSha(2L),
        request.findingRevision(),
        request.assetId(),
        request.assetVersion(),
        request.authoritativeAssetVersion(),
        request.inventoryCompletedAt(),
        request.planFingerprintSha256(),
        request.priority(),
        request.movementToRepair(),
        request.movementScheduledDate(),
        request.repairScheduledDate(),
        request.snapshot(),
        request.media(),
        request.snapshotSchemaVersion(),
        request.strategy(),
        request.selectedTargetKind(),
        request.selectedTargetId(),
        request.forceCapitalRepair());
    InventoryPublicationReconciliationService.PublicationResult corrected = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), correctedRequest);

    assertThat(corrected.replayed()).isFalse();
    assertThat(corrected.response().repairId()).isEqualTo(exactRepairId);
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();
    assertThat(jdbc.queryForMap(
            "select final_plan_version, target_repair_id "
                + "from inventory_authoritative_outcome where inventory_id=? "
                + "and final_plan_version=2 and finding_id=?",
            inventoryId,
            findingId))
        .containsEntry("final_plan_version", 2L)
        .containsEntry("target_repair_id", exactRepairId);
    InventoryPublicationReconciliationService.PublicationResult correctedReassertion =
        publications.apply(inventoryId, findingId, UUID.randomUUID(), correctedRequest);
    assertThat(correctedReassertion.replayed()).isTrue();
    assertThat(correctedReassertion.response().repairId()).isEqualTo(exactRepairId);
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_publication_source where repair_id=?",
        Integer.class,
        exactRepairId)).isOne();

    InventoryPublicationApplyRequest changedCorrection = new InventoryPublicationApplyRequest(
        correctedRequest.warehouseId(),
        3L,
        finalPlanSha(3L),
        correctedRequest.findingRevision(),
        correctedRequest.assetId(),
        correctedRequest.assetVersion(),
        correctedRequest.authoritativeAssetVersion(),
        correctedRequest.inventoryCompletedAt(),
        correctedRequest.planFingerprintSha256(),
        5,
        correctedRequest.movementToRepair(),
        correctedRequest.movementScheduledDate(),
        correctedRequest.repairScheduledDate(),
        correctedRequest.snapshot(),
        correctedRequest.media(),
        correctedRequest.snapshotSchemaVersion(),
        correctedRequest.strategy(),
        correctedRequest.selectedTargetKind(),
        correctedRequest.selectedTargetId(),
        correctedRequest.forceCapitalRepair());
    UUID changedKey = UUID.randomUUID();
    InventoryPublicationReconciliationService.PublicationResult changed = publications.apply(
        inventoryId, findingId, changedKey, changedCorrection);

    assertThat(changed.replayed()).isFalse();
    assertThat(changed.response().repairId()).isNotEqualTo(exactRepairId);
    assertThat(repairs.findById(exactRepairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.CANCELLED);
    assertThat(jdbc.queryForMap(
            "select priority,execution_state from maintenance_repair where id=?",
            changed.response().repairId()))
        .containsEntry("priority", 5)
        .containsEntry("execution_state", "DRAFT");
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();
    InventoryPublicationReconciliationService.PublicationResult changedReplay = publications.apply(
        inventoryId, findingId, changedKey, changedCorrection);
    assertThat(changedReplay.replayed()).isTrue();
    assertThat(changedReplay.response().repairId()).isEqualTo(changed.response().repairId());
  }

  @Test
  void equivalentCorrectionRecoversCancelledRetainedTaskAfterEffectsSettled() throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest original = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    InventoryPublicationReconciliationService.PublicationResult first = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), original);
    UUID historicalRepairId = first.response().repairId();
    MaintenanceRepair historical = new TransactionTemplate(transactionManager).execute(status -> {
      MaintenanceRepair locked = repairs.findById(historicalRepairId).orElseThrow();
      List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(historicalRepairId);
      stages.forEach(RepairStage::supersedeForAuthoritativeInventory);
      locked.supersedeForAuthoritativeInventory();
      repairStages.saveAllAndFlush(stages);
      return repairs.saveAndFlush(locked);
    });
    assertThat(historical).isNotNull();
    jdbc.update(
        "update event_stream_head set current_version=? where aggregate_type='REPAIR' "
            + "and aggregate_id=?",
        historical.getVersion(),
        historicalRepairId.toString());
    MaintenanceRepair currentRepair = confirmedQueuedInventoryRepair(
        assetId, 11L, "Receipt-bound current repair");
    UUID cancelledExternalTaskId = currentRepair.getExternalTaskId();
    InventoryPublicationApplyRequest corrected = new InventoryPublicationApplyRequest(
        original.warehouseId(),
        2L,
        finalPlanSha(2L),
        original.findingRevision(),
        original.assetId(),
        original.assetVersion(),
        original.authoritativeAssetVersion(),
        original.inventoryCompletedAt(),
        original.planFingerprintSha256(),
        original.priority(),
        original.movementToRepair(),
        original.movementScheduledDate(),
        original.repairScheduledDate(),
        original.snapshot(),
        original.media(),
        original.snapshotSchemaVersion(),
        original.strategy(),
        original.selectedTargetKind(),
        original.selectedTargetId(),
        original.forceCapitalRepair());
    UUID correctionKey = UUID.randomUUID();
    when(dependencies.getTask(cancelledExternalTaskId))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.TaskSnapshot(
              cancelledExternalTaskId, 3L, "ACTIVE", List.of());
        });
    when(dependencies.cancelTask(any(), eq(cancelledExternalTaskId), eq(3L)))
        .thenAnswer(invocation -> {
          assertNoRemoteTransaction();
          return new MaintenanceDependencyGateway.TaskSnapshot(
              cancelledExternalTaskId, 4L, "CANCELLED", List.of());
        });
    doAnswer(invocation -> {
      assertNoRemoteTransaction();
      return null;
    }).when(dependencies).releaseLease(
        any(),
        eq(currentRepair.getLeaseId()),
        eq(0L),
        eq(17L),
        eq("MAINTENANCE_REPAIR"),
        eq(currentRepair.getId().toString()));
    when(dependencies.preflightMaintenanceRouting(eq(warehouseId), anyList()))
        .thenThrow(new MaintenanceDependencyException(
            HttpStatus.SERVICE_UNAVAILABLE, "routing unavailable after effects settled"));

    assertThatThrownBy(() -> publications.apply(
        inventoryId, findingId, correctionKey, corrected))
        .isInstanceOf(MaintenanceDependencyException.class)
        .hasMessageContaining("routing unavailable");
    assertThat(jdbc.queryForMap(
            "select phase,target_repair_id from inventory_authoritative_outcome "
                + "where inventory_id=? and final_plan_version=2 and finding_id=?",
            inventoryId,
            findingId))
        .containsEntry("phase", "EFFECTS_SETTLED")
        .containsEntry("target_repair_id", null);
    assertThat(jdbc.queryForMap(
            "select target_id,task_external_id,task_outcome,lease_released,local_superseded "
                + "from inventory_authoritative_outcome_target "
                + "where inventory_id=? and final_plan_version=2 and finding_id=?",
            inventoryId,
            findingId))
        .containsEntry("target_id", currentRepair.getId())
        .containsEntry("task_external_id", cancelledExternalTaskId)
        .containsEntry("task_outcome", "CANCELLED")
        .containsEntry("lease_released", true)
        .containsEntry("local_superseded", false);

    String previousRequestSha256 = jdbc.queryForObject(
        "select request_sha256 from inventory_authoritative_outcome "
            + "where inventory_id=? and final_plan_version=1 and finding_id=?",
        String.class,
        inventoryId,
        findingId);
    InventoryPublicationApplyResult firstResponse = first.response();
    InventoryPublicationApplyResult replacementResponse = new InventoryPublicationApplyResult(
        firstResponse.source(),
        firstResponse.outcome(),
        InventoryPublicationTargetKind.REPAIR,
        currentRepair.getId(),
        null,
        currentRepair.getId(),
        firstResponse.successor(),
        firstResponse.delta());
    InventoryAuthoritativeOutcomeReceipt replacementReceipt =
        InventoryAuthoritativeOutcomeReceipt.register(
            UUID.randomUUID(),
            new InventoryPublicationSourceId(inventoryId, 1L, findingId),
            previousRequestSha256);
    replacementReceipt.complete(mapper.writeValueAsString(replacementResponse));
    authoritativeOutcomeReceipts.saveAndFlush(replacementReceipt);
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

    InventoryPublicationReconciliationService.PublicationResult recovered = publications.apply(
        inventoryId, findingId, correctionKey, corrected);

    assertThat(recovered.replayed()).isFalse();
    assertThat(recovered.response().repairId()).isEqualTo(currentRepair.getId());
    assertThat(jdbc.queryForMap(
            "select phase,target_repair_id from inventory_authoritative_outcome "
                + "where inventory_id=? and final_plan_version=2 and finding_id=?",
            inventoryId,
            findingId))
        .containsEntry("phase", "APPLIED")
        .containsEntry("target_repair_id", currentRepair.getId());
    assertThat(jdbc.queryForObject(
        "select local_superseded from inventory_authoritative_outcome_target "
            + "where inventory_id=? and final_plan_version=2 and finding_id=? "
            + "and target_id=?",
        Boolean.class,
        inventoryId,
        findingId,
        currentRepair.getId())).isTrue();
    MaintenanceRepair rotated = repairs.findById(currentRepair.getId()).orElseThrow();
    UUID expectedReplacementTaskId = UUID.nameUUIDFromBytes(
        ("inventory-cancelled-task-replacement:"
                + currentRepair.getId()
                + ":"
                + cancelledExternalTaskId)
            .getBytes(StandardCharsets.UTF_8));
    assertThat(rotated.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
    assertThat(rotated.getExternalTaskId()).isEqualTo(expectedReplacementTaskId);
    assertThat(rotated.getTaskBoardVersion()).isNull();
    assertThat(rotated.getTaskGenerationState()).isEqualTo("PENDING_GENERATION");
    assertThat(rotated.getDeliveryState()).isEqualTo("RETRY_PENDING");
    assertThat(rotated.getLeaseReconciliationState()).isEqualTo("RECONCILIATION_REQUIRED");
    assertThat(jdbc.queryForMap(
            "select state,external_queue_entry_id,task_board_version,"
                + "task_generation_state,delivery_state from repair_stage where repair_id=?",
            currentRepair.getId()))
        .containsEntry("state", "QUEUED")
        .containsEntry("external_queue_entry_id", null)
        .containsEntry("task_board_version", null)
        .containsEntry("task_generation_state", "PENDING_GENERATION")
        .containsEntry("delivery_state", "RETRY_PENDING");
    assertThat(jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='REPAIR' and aggregate_id=? "
            + "and event_type='maintenance.repair.plan-changed.v1'",
        Integer.class,
        currentRepair.getId().toString())).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where repair_id=? "
            + "and dependency_type='ASSET' and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'",
        Integer.class,
        currentRepair.getId())).isOne();
    assertThat(jdbc.queryForObject(
        "select response_snapshot->>'reacquireReleasedLease' "
            + "from integration_reconciliation where repair_id=? "
            + "and dependency_type='ASSET' and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'",
        String.class,
        currentRepair.getId())).isEqualTo("true");
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where repair_id=? "
            + "and dependency_type='TASK_BOARD' and operation_type='REGISTER_TASK'",
        Integer.class,
        currentRepair.getId())).isOne();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair where rental_item_id=? "
            + "and execution_state<>'CANCELLED' and acceptance_state not in ('ACCEPTED','WRITTEN_OFF')",
        Integer.class,
        assetId)).isOne();

    jdbc.update(
        "update integration_reconciliation set next_attempt_at='9999-12-31T23:59:59Z' "
            + "where dependency_type<>'MEDIA' and not (repair_id=? "
            + "and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS')",
        currentRepair.getId());
    UUID replacementLeaseId = UUID.randomUUID();
    OffsetDateTime replacementLeaseExpiry =
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(2).withNano(0);
    when(dependencies.getRentalItemSnapshot(assetId))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            assetId, 11L, warehouseId, "RECOVERED-001", "REPAIR"));
    when(dependencies.acquireLease(
            any(),
            eq(assetId),
            eq(11L),
            eq("MAINTENANCE_REPAIR"),
            eq(currentRepair.getId().toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            replacementLeaseId,
            0L,
            assetId,
            "MAINTENANCE_REPAIR",
            currentRepair.getId(),
            23L,
            replacementLeaseExpiry));

    assertThat(maintenance.reconcileOneTask()).isTrue();

    MaintenanceRepair leaseRecovered = repairs.findById(currentRepair.getId()).orElseThrow();
    assertThat(leaseRecovered.getLeaseId()).isEqualTo(replacementLeaseId);
    assertThat(leaseRecovered.getLeaseVersion()).isZero();
    assertThat(leaseRecovered.getFencingToken()).isEqualTo(23L);
    assertThat(leaseRecovered.getLeaseExpiresAt()).isEqualTo(replacementLeaseExpiry);
    assertThat(leaseRecovered.getLeaseReconciliationState()).isEqualTo("ACTIVE");
    assertThat(jdbc.queryForObject(
        "select state from integration_reconciliation where repair_id=? "
            + "and dependency_type='ASSET' and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS'",
        String.class,
        currentRepair.getId())).isEqualTo("CONFIRMED");

    jdbc.update(
        "update integration_reconciliation set next_attempt_at=clock_timestamp() "
            + "where repair_id=? and operation_type='REGISTER_TASK'",
        currentRepair.getId());
    UUID replacementQueueEntryId = UUID.randomUUID();
    when(dependencies.registerTask(
            any(),
            eq(expectedReplacementTaskId),
            eq(currentRepair.getId()),
            eq(warehouseId),
            eq(assetId),
            eq("RECOVERED-001"),
            any(LocalDate.class),
            anyInt(),
            anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            expectedReplacementTaskId,
            0L,
            "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(
                0, replacementQueueEntryId, 0L))));

    assertThat(maintenance.reconcileOneTask()).isTrue();

    MaintenanceRepair registered = repairs.findById(currentRepair.getId()).orElseThrow();
    assertThat(registered.getExternalTaskId()).isEqualTo(expectedReplacementTaskId);
    assertThat(registered.getTaskBoardVersion()).isZero();
    assertThat(registered.getTaskGenerationState()).isEqualTo("GENERATED");
    assertThat(repairStages.findAllByRepairIdOrderByStageNo(currentRepair.getId()))
        .singleElement()
        .satisfies(stage -> {
          assertThat(stage.getState().name()).isEqualTo("QUEUED");
          assertThat(stage.getExternalQueueEntryId()).isEqualTo(replacementQueueEntryId);
          assertThat(stage.getTaskBoardVersion()).isZero();
          assertThat(stage.getTaskGenerationState()).isEqualTo("GENERATED");
        });

    assertThat(publications.apply(inventoryId, findingId, correctionKey, corrected).replayed())
        .isTrue();
    assertThat(repairs.findById(currentRepair.getId()).orElseThrow().getExternalTaskId())
        .isEqualTo(expectedReplacementTaskId);
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where repair_id=? "
            + "and dependency_type='TASK_BOARD' and operation_type='REGISTER_TASK'",
        Integer.class,
        currentRepair.getId())).isOne();
    verify(dependencies, times(1)).registerTask(
        any(),
        eq(expectedReplacementTaskId),
        eq(currentRepair.getId()),
        eq(warehouseId),
        eq(assetId),
        eq("RECOVERED-001"),
        any(LocalDate.class),
        anyInt(),
        anyList());
    verify(dependencies, times(1)).releaseLease(
        any(),
        eq(currentRepair.getLeaseId()),
        eq(0L),
        eq(17L),
        eq("MAINTENANCE_REPAIR"),
        eq(currentRepair.getId().toString()));
    verify(dependencies, times(1)).acquireLease(
        any(),
        eq(assetId),
        eq(11L),
        eq("MAINTENANCE_REPAIR"),
        eq(currentRepair.getId().toString()));

    jdbc.update(
        "update maintenance_repair set lease_reconciliation_state='RECONCILIATION_REQUIRED', "
            + "reconciliation_state='RECONCILIATION_REQUIRED' where id=?",
        currentRepair.getId());
    UUID laterReassertionKey = UUID.randomUUID();
    assertThat(
            publications
                .apply(inventoryId, findingId, laterReassertionKey, corrected)
                .response()
                .repairId())
        .isEqualTo(currentRepair.getId());
    assertThat(
            jdbc.queryForObject(
                "select response_snapshot->>'reacquireReleasedLease' "
                    + "from integration_reconciliation where repair_id=? "
                    + "and dependency_type='ASSET' "
                    + "and operation_type='SYNC_REPAIR_COMPLEXITY_STATUS' "
                    + "order by created_at desc limit 1",
                String.class,
                currentRepair.getId()))
        .isEqualTo("true");

    UUID laterReplacementLeaseId = UUID.randomUUID();
    OffsetDateTime laterReplacementLeaseExpiry =
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(3).withNano(0);
    when(dependencies.getRentalItemSnapshot(assetId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 11L, warehouseId, "RECOVERED-001", "REPAIR"));
    when(dependencies.acquireLease(
            any(),
            eq(assetId),
            eq(11L),
            eq("MAINTENANCE_REPAIR"),
            eq(currentRepair.getId().toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                laterReplacementLeaseId,
                0L,
                assetId,
                "MAINTENANCE_REPAIR",
                currentRepair.getId(),
                24L,
                laterReplacementLeaseExpiry));

    assertThat(maintenance.reconcileOneTask()).isTrue();

    assertThat(repairs.findById(currentRepair.getId()).orElseThrow())
        .satisfies(
            recoveredAgain -> {
              assertThat(recoveredAgain.getLeaseId()).isEqualTo(laterReplacementLeaseId);
              assertThat(recoveredAgain.getFencingToken()).isEqualTo(24L);
              assertThat(recoveredAgain.getLeaseExpiresAt())
                  .isEqualTo(laterReplacementLeaseExpiry);
              assertThat(recoveredAgain.getLeaseReconciliationState()).isEqualTo("ACTIVE");
              assertThat(recoveredAgain.getReconciliationState()).isEqualTo("RECONCILED");
            });
    verify(dependencies, times(2))
        .acquireLease(
            any(),
            eq(assetId),
            eq(11L),
            eq("MAINTENANCE_REPAIR"),
            eq(currentRepair.getId().toString()));
  }

  @Test
  void equivalentCorrectionLeavesUncancelledRetainedTaskMappingUnchanged() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest original = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), original).response().repairId();
    MaintenanceRepair confirmed = confirmInventoryTask(repairId, 7L, false);
    RepairStage confirmedStage =
        repairStages.findAllByRepairIdOrderByStageNo(repairId).getFirst();

    InventoryPublicationReconciliationService.PublicationResult corrected = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        correctedPlanVersion(original, 2L));

    assertThat(corrected.response().repairId()).isEqualTo(repairId);
    MaintenanceRepair retained = repairs.findById(repairId).orElseThrow();
    assertThat(retained.getExternalTaskId()).isEqualTo(confirmed.getExternalTaskId());
    assertThat(retained.getTaskBoardVersion()).isEqualTo(7L);
    assertThat(retained.getTaskGenerationState()).isEqualTo("GENERATED");
    assertThat(repairStages.findAllByRepairIdOrderByStageNo(repairId))
        .singleElement()
        .satisfies(stage -> {
          assertThat(stage.getState().name()).isEqualTo("QUEUED");
          assertThat(stage.getExternalQueueEntryId())
              .isEqualTo(confirmedStage.getExternalQueueEntryId());
          assertThat(stage.getTaskBoardVersion()).isEqualTo(7L);
          assertThat(stage.getTaskGenerationState()).isEqualTo("GENERATED");
        });
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome_target where inventory_id=? "
            + "and final_plan_version=2 and finding_id=?",
        Integer.class,
        inventoryId,
        findingId)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where repair_id=? "
            + "and dependency_type='TASK_BOARD' and operation_type='REGISTER_TASK'",
        Integer.class,
        repairId)).isZero();
    verify(dependencies, never()).getTask(any());
  }

  @Test
  void equivalentCorrectionDoesNotReopenLocallyCompletedTaskWork() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 11L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest original = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), original).response().repairId();
    MaintenanceRepair completed = confirmInventoryTask(repairId, 9L, true);

    InventoryPublicationReconciliationService.PublicationResult corrected = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        correctedPlanVersion(original, 2L));

    assertThat(corrected.response().repairId()).isEqualTo(repairId);
    MaintenanceRepair retained = repairs.findById(repairId).orElseThrow();
    assertThat(retained.getExternalTaskId()).isEqualTo(completed.getExternalTaskId());
    assertThat(retained.getExecutionState()).isEqualTo(RepairExecutionState.COMPLETED);
    assertThat(retained.getAcceptanceState().name()).isEqualTo("PENDING");
    assertThat(repairStages.findAllByRepairIdOrderByStageNo(repairId))
        .singleElement()
        .satisfies(stage -> {
          assertThat(stage.getState().name()).isEqualTo("DONE");
          assertThat(stage.getExternalQueueEntryId()).isNotNull();
          assertThat(stage.getTaskBoardVersion()).isEqualTo(10L);
        });
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation where repair_id=? "
            + "and dependency_type='TASK_BOARD' and operation_type='REGISTER_TASK'",
        Integer.class,
        repairId)).isZero();
    verify(dependencies, never()).getTask(any());
  }

  @Test
  void repairSourceResolvesDirectOutcomeAndNewestReceiptBoundReplacement() {
    UUID directAssetId = UUID.randomUUID();
    MaintenanceRepair directRepair =
        directDraftRepair(directAssetId, 11L, "Direct authoritative repair");
    InventoryPublicationSourceId directSource =
        new InventoryPublicationSourceId(UUID.randomUUID(), 3L, UUID.randomUUID());
    String directPlanSha256 = "1".repeat(64);
    String directRequestSha256 = "2".repeat(64);
    saveAuthoritativeWorkOutcome(
        directSource,
        directAssetId,
        directRepair.getId(),
        4L,
        directPlanSha256,
        directRequestSha256);

    UUID replacementAssetId = UUID.randomUUID();
    MaintenanceRepair oldTarget =
        directDraftRepair(replacementAssetId, 12L, "Old receipt target");
    MaintenanceRepair newestTarget =
        directDraftRepair(replacementAssetId, 12L, "Newest receipt target");
    MaintenanceRepair replacement =
        directDraftRepair(replacementAssetId, 12L, "Receipt-bound replacement");
    InventoryPublicationSourceId oldSource =
        new InventoryPublicationSourceId(UUID.randomUUID(), 7L, UUID.randomUUID());
    InventoryPublicationSourceId newestSource =
        new InventoryPublicationSourceId(UUID.randomUUID(), 8L, UUID.randomUUID());
    String oldRequestSha256 = "3".repeat(64);
    String newestPlanSha256 = "4".repeat(64);
    String newestRequestSha256 = "5".repeat(64);
    saveAuthoritativeWorkOutcome(
        oldSource,
        replacementAssetId,
        oldTarget.getId(),
        6L,
        "6".repeat(64),
        oldRequestSha256);
    saveAuthoritativeWorkOutcome(
        newestSource,
        replacementAssetId,
        newestTarget.getId(),
        9L,
        newestPlanSha256,
        newestRequestSha256);

    UUID oldReceiptKey = UUID.randomUUID();
    UUID newestReceiptKey = UUID.randomUUID();
    String replacementSnapshot =
        "{\"repairId\":\"" + replacement.getId() + "\"}";
    InventoryAuthoritativeOutcomeReceipt oldReceipt =
        InventoryAuthoritativeOutcomeReceipt.register(
            oldReceiptKey, oldSource, oldRequestSha256);
    oldReceipt.complete(replacementSnapshot);
    authoritativeOutcomeReceipts.saveAndFlush(oldReceipt);
    InventoryAuthoritativeOutcomeReceipt newestReceipt =
        InventoryAuthoritativeOutcomeReceipt.register(
            newestReceiptKey, newestSource, newestRequestSha256);
    newestReceipt.complete(replacementSnapshot);
    authoritativeOutcomeReceipts.saveAndFlush(newestReceipt);
    jdbc.update(
        "update inventory_authoritative_outcome_receipt set completed_at=? where idempotency_key=?",
        OffsetDateTime.of(2026, 8, 19, 9, 0, 0, 0, ZoneOffset.UTC),
        oldReceiptKey);
    jdbc.update(
        "update inventory_authoritative_outcome_receipt set completed_at=? where idempotency_key=?",
        OffsetDateTime.of(2026, 8, 19, 10, 0, 0, 0, ZoneOffset.UTC),
        newestReceiptKey);

    assertThat(maintenance.repair(directRepair.getId()).inventorySource())
        .isEqualTo(
            new InventorySourceReference(
                directSource.getInventoryId(),
                directSource.getFindingId(),
                4L,
                directPlanSha256,
                directRequestSha256));
    assertThat(maintenance.repair(replacement.getId()).inventorySource())
        .isEqualTo(
            new InventorySourceReference(
                newestSource.getInventoryId(),
                newestSource.getFindingId(),
                9L,
                newestPlanSha256,
                newestRequestSha256));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_repair_source where repair_id in (?,?)",
                Integer.class,
                directRepair.getId(),
                replacement.getId()))
        .isZero();
  }

  @Test
  void terminalSelectedRepairAndWarehouseVersionGuardsRollbackBeforeAnyEffect() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 7L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);
    UUID repairId = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 1L, InventoryPublicationStrategy.CREATE, null, null))
        .response()
        .repairId();
    MaintenanceRepair terminal = repairs.findById(repairId).orElseThrow();
    terminal.writeOff("terminal truth", "{}");
    terminal = repairs.saveAndFlush(terminal);
    jdbc.update(
        "update event_stream_head set current_version=? where aggregate_type='REPAIR' "
            + "and aggregate_id=?",
        terminal.getVersion(),
        repairId.toString());
    clearInvocations(dependencies);

    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding,
            2L,
            InventoryPublicationStrategy.REPLACE,
            InventoryPublicationTargetKind.REPAIR,
            repairId)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("Terminal accepted or written-off");
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome "
            + "where inventory_id=? and final_plan_version=2 and finding_id=?",
        Integer.class,
        inventoryId,
        findingId)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_publication_source_operation "
            + "where inventory_id=? and final_plan_version=2 and finding_id=?",
        Integer.class,
        inventoryId,
        findingId)).isZero();
    verifyNoInteractions(dependencies);

    UUID noWorkInventoryId = UUID.randomUUID();
    UUID noWorkFindingId = UUID.randomUUID();
    assertThatThrownBy(() -> authoritativeOutcomes.applyNoWork(
        noWorkInventoryId,
        noWorkFindingId,
        UUID.randomUUID(),
        new InventoryNoWorkOutcomeRequest(
            UUID.randomUUID(),
            assetId,
            OffsetDateTime.of(2026, 8, 20, 8, 0, 0, 0, ZoneOffset.UTC),
            1L,
            finalPlanSha(1L),
            1L,
            6L,
            "FREE")))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("warehouse/version");
    assertThat(jdbc.queryForObject(
        "select count(*) from inventory_authoritative_outcome where inventory_id=?",
        Integer.class,
        noWorkInventoryId)).isZero();
  }

  @Test
  void exactV1AggregateMediaDuplicationAdaptsWithoutRewritingEvidence() throws Exception {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    MediaReferenceInput media = new MediaReferenceInput(mediaId, 3L);
    mediaFacts.saveAndFlush(MediaFactProjection.create(
        mediaId, 3, "INVENTORY_FINDING", findingId, warehouseId, "READY", "{}", 5));
    FrozenInventoryPlanResponse frozen = inventory.freeze(new FreezeInventoryPlanRequest(
        warehouseId,
        inventoryId,
        findingId,
        1L,
        InventoryPlanMode.MANUAL,
        List.of(
            manualLine(
                "Legacy work", InventoryPlanLineType.WORK, "h", "1", 100L, "30"),
            manualLine(
                "Legacy material", InventoryPlanLineType.MATERIAL, "pcs", "2", 50L, "0")),
        List.of(new InventoryPlanStageSelection(workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of(media),
        3,
        mediaId)).response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    rawSnapshot.put("movementToShipment", false);
    var aggregateMedia = rawSnapshot.required("mediaReferences");
    rawSnapshot.withArray("lines").forEach(
        line -> ((ObjectNode) line).set("mediaReferences", aggregateMedia.deepCopy()));
    String rawFingerprint = canonicalizer.sha256(rawSnapshot);
    InventoryPublicationFindingInput finding = new InventoryPublicationFindingInput(
        findingId,
        1L,
        assetId,
        7L,
        rawFingerprint,
        3,
        false,
        null,
        LocalDate.of(2026, 8, 5),
        rawSnapshot,
        List.of(media),
        1);
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 7));

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
    UUID repairId = published.response().repairId();

    assertThat(jdbc.queryForObject(
        """
        select count(*) from maintenance_media_reference
         where aggregate_type='REPAIR' and aggregate_id=? and media_id=?
        """, Integer.class, repairId, mediaId)).isOne();
    String workLines = jdbc.queryForObject(
        "select work_lines::text from repair_stage where repair_id=?",
        String.class,
        repairId);
    String materialLines = jdbc.queryForObject(
        "select material_lines::text from repair_stage where repair_id=?",
        String.class,
        repairId);
    assertThat(mapper.readTree(workLines)).singleElement().satisfies(line ->
        assertThat(line.required("mediaReferences")).isEmpty());
    assertThat(mapper.readTree(materialLines)).singleElement().satisfies(line ->
        assertThat(line.required("mediaReferences")).isEmpty());
    String storedRaw = jdbc.queryForObject(
        """
        select plan_snapshot::text from inventory_publication_source
         where inventory_id=? and final_plan_version=1 and finding_id=?
        """,
        String.class,
        inventoryId,
        findingId);
    assertThat(jdbc.queryForObject(
        """
        select plan_snapshot = ?::jsonb from inventory_publication_source
         where inventory_id=? and final_plan_version=1 and finding_id=?
        """,
        Boolean.class,
        mapper.writeValueAsString(rawSnapshot),
        inventoryId,
        findingId)).isTrue();
    assertThat(jdbc.queryForObject(
        """
        select plan_fingerprint_sha256 from inventory_publication_source
         where inventory_id=? and final_plan_version=1 and finding_id=?
        """,
        String.class,
        inventoryId,
        findingId)).isEqualTo(rawFingerprint);
    assertThat(canonicalizer.sha256(mapper.readTree(storedRaw))).isEqualTo(rawFingerprint);

    ObjectNode schemaTwoSnapshot = rawSnapshot.deepCopy();
    schemaTwoSnapshot.remove("movementToShipment");
    InventoryPublicationFindingInput schemaTwoFinding = new InventoryPublicationFindingInput(
        findingId,
        1L,
        assetId,
        7L,
        canonicalizer.sha256(schemaTwoSnapshot),
        3,
        false,
        null,
        LocalDate.of(2026, 8, 5),
        schemaTwoSnapshot,
        List.of(media),
        2);
    assertThatThrownBy(() -> publications.preflight(new InventoryPublicationPreflightRequest(
        UUID.randomUUID(), warehouseId, 1L, finalPlanSha(1), List.of(schemaTwoFinding))))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("Inventory photos can only be assigned to work lines");

    ObjectNode partialV1Snapshot = rawSnapshot.deepCopy();
    ((ObjectNode) partialV1Snapshot.withArray("lines").get(0)).putArray("mediaReferences");
    InventoryPublicationFindingInput partialV1Finding = new InventoryPublicationFindingInput(
        findingId,
        1L,
        assetId,
        7L,
        canonicalizer.sha256(partialV1Snapshot),
        3,
        false,
        null,
        LocalDate.of(2026, 8, 5),
        partialV1Snapshot,
        List.of(media),
        1);
    assertThatThrownBy(() -> publications.preflight(new InventoryPublicationPreflightRequest(
        UUID.randomUUID(), warehouseId, 1L, finalPlanSha(1), List.of(partialV1Finding))))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("Inventory photos can only be assigned to work lines");
  }

  @Test
  void afterRentPublicationCreatesRepairFromRawV1EvidenceAndRetainsItForEquivalentCorrection() {
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
      assertThat(value.targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR);
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
    assertThat(created.response().targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR);
    assertThat(created.response().estimateId()).isNull();
    assertThat(created.response().repairId()).isNotNull();
    UUID repairId = created.response().repairId();
    assertThat(jdbc.queryForMap(
        "select execution_state,priority,movement_to_repair from maintenance_repair where id=?",
        repairId))
        .containsEntry("execution_state", "DRAFT")
        .containsEntry("priority", 4)
        .containsEntry("movement_to_repair", false);
    assertThat(jdbc.queryForObject(
        "select count(*) from repair_stage where repair_id=?", Integer.class, repairId)).isOne();
    assertThat(jdbc.queryForObject(
        """
        select count(*) from integration_reconciliation
         where repair_id=? and dependency_type='ASSET' and operation_type='QUEUE_REPAIR'
        """, Integer.class, repairId)).isOne();
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

    InventoryPublicationApplyRequest authoritativeCreate = publicationApplyRequest(
        finding, 2L, InventoryPublicationStrategy.CREATE, null, null);
    InventoryPublicationReconciliationService.PublicationResult replaced = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), authoritativeCreate);
    assertThat(replaced.response().repairId()).isEqualTo(repairId);
    assertThat(jdbc.queryForObject(
        "select execution_state from maintenance_repair where id=?", String.class, repairId))
        .isEqualTo("DRAFT");
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_repair", Integer.class)).isOne();
    assertThat(replaced.response().source().strategy())
        .isEqualTo(InventoryPublicationStrategy.CREATE);
    assertThat(replaced.response().source().supersededTargetKind()).isNull();
    assertThat(replaced.response().source().supersededTargetId()).isNull();
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

  @ParameterizedTest(name = "authoritative inventory accepts non-terminal asset status {0}")
  @ValueSource(strings = {
      "BOOKED",
      "USED_SALE",
      "RESERVED",
      "RENTED",
      "IN_TRANSFER",
      "REPAIR",
      "CAPITAL_REPAIR",
      "WAREHOUSE",
      "OWN_NEEDS"
  })
  void authoritativePublicationAcceptsEveryNonTerminalOperationalStatus(String assetStatus) {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, assetStatus, 7L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);

    InventoryPublicationPreflightResponse preflight = publications.preflight(
        new InventoryPublicationPreflightRequest(
            inventoryId, warehouseId, 1L, finalPlanSha(1L), List.of(finding)));
    assertThat(preflight.findings()).singleElement().satisfies(value ->
        assertThat(value.targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR));

    InventoryPublicationReconciliationService.PublicationResult published = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 7L, 1L, InventoryPublicationStrategy.CREATE, null, null));

    assertThat(published.response().targetKind()).isEqualTo(InventoryPublicationTargetKind.REPAIR);
    assertThat(repairs.findById(published.response().repairId()).orElseThrow()
        .getRentalItemVersionSnapshot()).isEqualTo(7L);
  }

  @Test
  void laggingAssetProjectionWithinAuthorityFenceCreatesRepairAtAuthoritativeVersion() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "BOOKED", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);
    publications.preflight(new InventoryPublicationPreflightRequest(
        inventoryId, warehouseId, 1L, finalPlanSha(1L), List.of(finding)));
    asset.apply(warehouseId, "REPAIR", 8L);
    rentalItems.saveAndFlush(asset);

    InventoryPublicationReconciliationService.PublicationResult published = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 10L, 1L, InventoryPublicationStrategy.CREATE, null, null));

    assertThat(repairs.findById(published.response().repairId()).orElseThrow()
        .getRentalItemVersionSnapshot()).isEqualTo(10L);
    assertThat(jdbc.queryForObject(
        """
        select asset_version_snapshot from inventory_publication_source
         where inventory_id=? and final_plan_version=1 and finding_id=?
        """,
        Long.class,
        inventoryId,
        findingId)).isEqualTo(7L);

    InventoryPublicationReconciliationService.PublicationResult replayed = publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 11L, 1L, InventoryPublicationStrategy.CREATE, null, null));
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(published.response());
    assertThat(repairs.findById(published.response().repairId()).orElseThrow()
        .getRentalItemVersionSnapshot()).isEqualTo(10L);
  }

  @Test
  void initialProjectionNewerThanAuthoritativeOutcomeIsRejected() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "REPAIR", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);

    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 10L, 1L, InventoryPublicationStrategy.CREATE, null, null)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("outside the completed inventory authority fence");
  }

  @Test
  void appliedWorkReassertionRecoversCapitalEffectsPastItsOriginalAssetFence() {
    jdbc.update(
        "update catalog_node set forces_capital_repair=true where node_id=?", workNodeId);
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot =
        (ObjectNode)
            mapper.valueToTree(
                inventory
                    .freeze(autoRequest(inventoryId, findingId, List.of()))
                    .response()
                    .snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "REPAIR", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding =
        publicationFinding(
            findingId,
            assetId,
            7L,
            rawSnapshot,
            2,
            3,
            true,
            LocalDate.of(2026, 8, 21));
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 7L, 1L, InventoryPublicationStrategy.CREATE, null, null);
    assertThat(request.forceCapitalRepair()).isFalse();
    UUID repairId =
        publications
            .apply(inventoryId, findingId, UUID.randomUUID(), request)
            .response()
            .repairId();
    assertThat(
            jdbc.queryForMap(
                "select desired_status,authoritative_asset_version "
                    + "from inventory_authoritative_outcome where inventory_id=? "
                    + "and final_plan_version=1 and finding_id=?",
                inventoryId,
                findingId))
        .containsEntry("desired_status", "REPAIR")
        .containsEntry("authoritative_asset_version", 7L);

    UUID leaseId = UUID.randomUUID();
    OffsetDateTime leaseExpiry =
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
    when(dependencies.getRentalItemSnapshot(assetId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 7L, warehouseId, "CAP-RECOVERY-001", "REPAIR"));
    when(dependencies.acquireLease(
            any(),
            eq(assetId),
            eq(7L),
            eq("MAINTENANCE_REPAIR"),
            eq(repairId.toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0L,
                assetId,
                "MAINTENANCE_REPAIR",
                repairId,
                19L,
                leaseExpiry));
    when(dependencies.fencedStatus(
            any(),
            eq(assetId),
            eq(warehouseId),
            eq(7L),
            eq(leaseId),
            eq(19L),
            eq("MAINTENANCE_REPAIR"),
            eq(repairId.toString()),
            eq("QUEUE_TO_CAPITAL_REPAIR"),
            eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 8L, warehouseId, "CAP-RECOVERY-001", "CAPITAL_REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(repairId).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
              assertThat(repair.getReclassificationState().name()).isEqualTo("EXTERNAL_CAPITAL");
              assertThat(repair.getRentalItemVersionSnapshot()).isEqualTo(8L);
            });

    jdbc.update(
        "update integration_reconciliation set next_attempt_at='9999-12-31T23:59:59Z' "
            + "where repair_id=? and state<>'CONFIRMED'",
        repairId);
    asset.apply(warehouseId, "CAPITAL_REPAIR", 8L);
    rentalItems.saveAndFlush(asset);
    UUID reassertionKey = UUID.randomUUID();
    String coordinatorBefore =
        jdbc.queryForObject(
            "select request_sha256 || ':' || authoritative_asset_version || ':' "
                + "|| target_repair_id || ':' || response_snapshot::text "
                + "from inventory_authoritative_outcome where inventory_id=? "
                + "and final_plan_version=1 and finding_id=?",
            String.class,
            inventoryId,
            findingId);

    clearInvocations(dependencies);
    InventoryPublicationReconciliationService.PublicationResult recovered =
        publications.apply(inventoryId, findingId, reassertionKey, request);

    assertThat(recovered.replayed()).isTrue();
    assertThat(recovered.response().repairId()).isEqualTo(repairId);
    assertThat(
            jdbc.queryForObject(
                "select request_sha256 || ':' || authoritative_asset_version || ':' "
                    + "|| target_repair_id || ':' || response_snapshot::text "
                    + "from inventory_authoritative_outcome where inventory_id=? "
                    + "and final_plan_version=1 and finding_id=?",
                String.class,
                inventoryId,
                findingId))
        .isEqualTo(coordinatorBefore);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_authoritative_outcome_receipt "
                    + "where inventory_id=? and final_plan_version=1 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isEqualTo(2);

    UUID statusKey =
        authoritativeEffectKey(
            "reassert-repair-status:" + reassertionKey,
            inventoryId,
            1L,
            findingId,
            repairId);
    UUID driverKey =
        authoritativeEffectKey(
            "reassert-repair-driver:" + reassertionKey,
            inventoryId,
            1L,
            findingId,
            repairId);
    assertThat(
            jdbc.queryForList(
                "select operation_type from integration_reconciliation "
                    + "where idempotency_key in (?,?) order by operation_type",
                String.class,
                statusKey,
                driverKey))
        .containsExactly("CREATE_DRIVER_TASK", "SYNC_REPAIR_COMPLEXITY_STATUS");

    jdbc.update(
        "update integration_reconciliation set next_attempt_at=case "
            + "when idempotency_key=? then clock_timestamp() "
            + "else '9999-12-31T23:59:59Z' end where state<>'CONFIRMED'",
        statusKey);
    when(dependencies.getRentalItemSnapshot(assetId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 8L, warehouseId, "CAP-RECOVERY-001", "CAPITAL_REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select state from integration_reconciliation where idempotency_key=?",
                String.class,
                statusKey))
        .isEqualTo("CONFIRMED");
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

    jdbc.update(
        "update integration_reconciliation set next_attempt_at=case "
            + "when idempotency_key=? then clock_timestamp() "
            + "else '9999-12-31T23:59:59Z' end where state<>'CONFIRMED'",
        driverKey);
    UUID driverTaskId = UUID.randomUUID();
    when(dependencies.createDriverTask(
            eq(driverKey), any(MaintenanceDependencyGateway.DriverTaskCommand.class)))
        .thenAnswer(
            invocation -> {
              MaintenanceDependencyGateway.DriverTaskCommand command =
                  invocation.getArgument(1);
              return new MaintenanceDependencyGateway.DriverTaskSnapshot(
                  driverTaskId,
                  0L,
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
    assertThat(
            jdbc.queryForObject(
                "select state from integration_reconciliation where idempotency_key=?",
                String.class,
                driverKey))
        .isEqualTo("CONFIRMED");
    ArgumentCaptor<MaintenanceDependencyGateway.DriverTaskCommand> command =
        ArgumentCaptor.forClass(MaintenanceDependencyGateway.DriverTaskCommand.class);
    verify(dependencies).createDriverTask(eq(driverKey), command.capture());
    assertThat(command.getValue().kind()).isEqualTo("CAPITAL_TO_PRODUCTION");
    assertThat(command.getValue().repairId()).isEqualTo(repairId);
    assertThat(rentalItems.findById(assetId).orElseThrow())
        .satisfies(
            projected -> {
              assertThat(projected.getAssetStatus()).isEqualTo("CAPITAL_REPAIR");
              assertThat(projected.getAggregateVersion()).isEqualTo(8L);
            });
  }

  @Test
  void appliedRetainedSourceWorkReassertionRecoversCapitalEffectsPastItsOriginalAssetFence() {
    jdbc.update(
        "update catalog_node set forces_capital_repair=true where node_id=?", workNodeId);
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot =
        (ObjectNode)
            mapper.valueToTree(
                inventory
                    .freeze(autoRequest(inventoryId, findingId, List.of()))
                    .response()
                    .snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "REPAIR", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding =
        publicationFinding(
            findingId,
            assetId,
            7L,
            rawSnapshot,
            2,
            3,
            true,
            LocalDate.of(2026, 8, 21));
    InventoryPublicationApplyRequest versionTwo =
        publicationApplyRequest(
            finding, 7L, 2L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId =
        publications
            .apply(inventoryId, findingId, UUID.randomUUID(), versionTwo)
            .response()
            .repairId();
    InventoryPublicationApplyRequest versionThree = correctedPlanVersion(versionTwo, 3L);

    InventoryPublicationReconciliationService.PublicationResult corrected =
        publications.apply(inventoryId, findingId, UUID.randomUUID(), versionThree);

    assertThat(corrected.replayed()).isFalse();
    assertThat(corrected.response().repairId()).isEqualTo(repairId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_publication_source where inventory_id=? "
                    + "and final_plan_version=2 and finding_id=? and repair_id=?",
                Integer.class,
                inventoryId,
                findingId,
                repairId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_publication_source where inventory_id=? "
                    + "and final_plan_version=3 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isZero();
    assertThat(
            jdbc.queryForMap(
                "select outcome.phase,outcome.desired_status,outcome.authoritative_asset_version,"
                    + "outcome.request_sha256=operation.request_sha256 as operation_matches,"
                    + "outcome.request_sha256=watermark.request_sha256 as watermark_matches "
                    + "from inventory_authoritative_outcome outcome "
                    + "join inventory_publication_source_operation operation "
                    + "using (inventory_id,final_plan_version,finding_id) "
                    + "join inventory_authoritative_outcome_watermark watermark "
                    + "on watermark.asset_id=outcome.asset_id "
                    + "where outcome.inventory_id=? and outcome.final_plan_version=3 "
                    + "and outcome.finding_id=?",
                inventoryId,
                findingId))
        .containsEntry("phase", "APPLIED")
        .containsEntry("desired_status", "REPAIR")
        .containsEntry("authoritative_asset_version", 7L)
        .containsEntry("operation_matches", true)
        .containsEntry("watermark_matches", true);

    jdbc.update(
        "update integration_reconciliation set next_attempt_at=case "
            + "when repair_id=? and operation_type='QUEUE_REPAIR' then clock_timestamp() "
            + "else '9999-12-31T23:59:59Z' end where state<>'CONFIRMED'",
        repairId);
    UUID leaseId = UUID.randomUUID();
    OffsetDateTime leaseExpiry =
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).withNano(0);
    when(dependencies.getRentalItemSnapshot(assetId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 7L, warehouseId, "CAP-RETAINED-001", "REPAIR"));
    when(dependencies.acquireLease(
            any(),
            eq(assetId),
            eq(7L),
            eq("MAINTENANCE_REPAIR"),
            eq(repairId.toString())))
        .thenReturn(
            new MaintenanceDependencyGateway.LeaseSnapshot(
                leaseId,
                0L,
                assetId,
                "MAINTENANCE_REPAIR",
                repairId,
                23L,
                leaseExpiry));
    when(dependencies.fencedStatus(
            any(),
            eq(assetId),
            eq(warehouseId),
            eq(7L),
            eq(leaseId),
            eq(23L),
            eq("MAINTENANCE_REPAIR"),
            eq(repairId.toString()),
            eq("QUEUE_TO_CAPITAL_REPAIR"),
            eq(false)))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 8L, warehouseId, "CAP-RETAINED-001", "CAPITAL_REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();
    assertThat(repairs.findById(repairId).orElseThrow())
        .satisfies(
            repair -> {
              assertThat(repair.getExecutionState()).isEqualTo(RepairExecutionState.QUEUED);
              assertThat(repair.getReclassificationState().name()).isEqualTo("EXTERNAL_CAPITAL");
              assertThat(repair.getRentalItemVersionSnapshot()).isEqualTo(8L);
            });

    jdbc.update(
        "update integration_reconciliation set next_attempt_at='9999-12-31T23:59:59Z' "
            + "where repair_id=? and state<>'CONFIRMED'",
        repairId);
    asset.apply(warehouseId, "CAPITAL_REPAIR", 8L);
    rentalItems.saveAndFlush(asset);
    UUID reassertionKey = UUID.randomUUID();
    String coordinatorBefore =
        jdbc.queryForObject(
            "select request_sha256 || ':' || authoritative_asset_version || ':' "
                + "|| target_repair_id || ':' || response_snapshot::text "
                + "from inventory_authoritative_outcome where inventory_id=? "
                + "and final_plan_version=3 and finding_id=?",
            String.class,
            inventoryId,
            findingId);

    clearInvocations(dependencies);
    InventoryPublicationReconciliationService.PublicationResult recovered =
        publications.apply(inventoryId, findingId, reassertionKey, versionThree);

    assertThat(recovered.replayed()).isTrue();
    assertThat(recovered.response().repairId()).isEqualTo(repairId);
    assertThat(
            jdbc.queryForObject(
                "select request_sha256 || ':' || authoritative_asset_version || ':' "
                    + "|| target_repair_id || ':' || response_snapshot::text "
                    + "from inventory_authoritative_outcome where inventory_id=? "
                    + "and final_plan_version=3 and finding_id=?",
                String.class,
                inventoryId,
                findingId))
        .isEqualTo(coordinatorBefore);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_publication_source where inventory_id=? "
                    + "and final_plan_version=3 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_authoritative_outcome_receipt "
                    + "where inventory_id=? and final_plan_version=3 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isEqualTo(2);

    UUID statusKey =
        authoritativeEffectKey(
            "reassert-repair-status:" + reassertionKey,
            inventoryId,
            3L,
            findingId,
            repairId);
    UUID driverKey =
        authoritativeEffectKey(
            "reassert-repair-driver:" + reassertionKey,
            inventoryId,
            3L,
            findingId,
            repairId);
    assertThat(
            jdbc.queryForMap(
                "select status.operation_type as status_operation,"
                    + "driver.operation_type as driver_operation,"
                    + "driver.response_snapshot->>'kind' as driver_kind "
                    + "from integration_reconciliation status "
                    + "join integration_reconciliation driver on driver.idempotency_key=? "
                    + "where status.idempotency_key=?",
                driverKey,
                statusKey))
        .containsEntry("status_operation", "SYNC_REPAIR_COMPLEXITY_STATUS")
        .containsEntry("driver_operation", "CREATE_DRIVER_TASK")
        .containsEntry("driver_kind", "CAPITAL_TO_PRODUCTION");

    jdbc.update(
        "update integration_reconciliation set next_attempt_at=case "
            + "when idempotency_key=? then clock_timestamp() "
            + "else '9999-12-31T23:59:59Z' end where state<>'CONFIRMED'",
        statusKey);
    when(dependencies.getRentalItemSnapshot(assetId))
        .thenReturn(
            new MaintenanceDependencyGateway.AssetSnapshot(
                assetId, 8L, warehouseId, "CAP-RETAINED-001", "CAPITAL_REPAIR"));

    assertThat(maintenance.reconcileOneTask()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select state from integration_reconciliation where idempotency_key=?",
                String.class,
                statusKey))
        .isEqualTo("CONFIRMED");
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
    assertThat(rentalItems.findById(assetId).orElseThrow())
        .satisfies(
            projected -> {
              assertThat(projected.getAssetStatus()).isEqualTo("CAPITAL_REPAIR");
              assertThat(projected.getAggregateVersion()).isEqualTo(8L);
            });
  }

  @Test
  void appliedWorkWithoutCurrentOrRetainedSourceRejectsAndRollsBackItsReceipt() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot =
        (ObjectNode)
            mapper.valueToTree(
                inventory
                    .freeze(autoRequest(inventoryId, findingId, List.of()))
                    .response()
                    .snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "REPAIR", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding =
        publicationFinding(findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 7L, 3L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId =
        publications
            .apply(inventoryId, findingId, UUID.randomUUID(), request)
            .response()
            .repairId();
    jdbc.update(
        "update maintenance_repair set reclassification_state='EXTERNAL_CAPITAL',"
            + "rental_item_version_snapshot=8 where id=?",
        repairId);
    jdbc.update(
        "delete from inventory_publication_source where inventory_id=? "
            + "and final_plan_version=3 and finding_id=?",
        inventoryId,
        findingId);
    asset.apply(warehouseId, "CAPITAL_REPAIR", 8L);
    rentalItems.saveAndFlush(asset);

    assertThat(
            jdbc.queryForMap(
                "select phase,target_repair_id from inventory_authoritative_outcome "
                    + "where inventory_id=? and final_plan_version=3 and finding_id=?",
                inventoryId,
                findingId))
        .containsEntry("phase", "APPLIED")
        .containsEntry("target_repair_id", repairId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_publication_source where repair_id=?",
                Integer.class,
                repairId))
        .isZero();
    UUID rejectedKey = UUID.randomUUID();

    assertThatThrownBy(
            () -> publications.apply(inventoryId, findingId, rejectedKey, request))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("has no immutable source response");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_authoritative_outcome_receipt "
                    + "where inventory_id=? and final_plan_version=3 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_authoritative_outcome_receipt "
                    + "where idempotency_key=?",
                Integer.class,
                rejectedKey))
        .isZero();
  }

  @ParameterizedTest(name = "applied work reassertion rejects advanced status {0}")
  @ValueSource(strings = {"FREE", "RENTED", "BOOKED"})
  void appliedWorkReassertionRejectsIncompatibleAdvancedAssetStatus(String assetStatus) {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot =
        (ObjectNode)
            mapper.valueToTree(
                inventory
                    .freeze(autoRequest(inventoryId, findingId, List.of()))
                    .response()
                    .snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding =
        publicationFinding(findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 7L, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId =
        publications
            .apply(inventoryId, findingId, UUID.randomUUID(), request)
            .response()
            .repairId();
    asset.apply(warehouseId, assetStatus, 8L);
    rentalItems.saveAndFlush(asset);

    assertThatThrownBy(
            () -> publications.apply(inventoryId, findingId, UUID.randomUUID(), request))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("outside the applied inventory repair reassertion fence");
    InventoryPublicationApplyRequest raisedTechnicalFence =
        publicationApplyRequest(
            finding, 8L, 1L, InventoryPublicationStrategy.CREATE, null, null);
    assertThatThrownBy(
            () ->
                publications.apply(
                    inventoryId, findingId, UUID.randomUUID(), raisedTechnicalFence))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("outside the applied inventory repair reassertion fence");
    assertThat(
            jdbc.queryForObject(
                "select target_repair_id from inventory_authoritative_outcome "
                    + "where inventory_id=? and final_plan_version=1 and finding_id=?",
                UUID.class,
                inventoryId,
                findingId))
        .isEqualTo(repairId);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_authoritative_outcome_receipt "
                    + "where inventory_id=? and final_plan_version=1 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isOne();
  }

  @Test
  void appliedOrdinaryWorkReassertionRejectsCapitalTruthWithoutBoundPromotion() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot =
        (ObjectNode)
            mapper.valueToTree(
                inventory
                    .freeze(autoRequest(inventoryId, findingId, List.of()))
                    .response()
                    .snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "REPAIR", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding =
        publicationFinding(findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 7L, 1L, InventoryPublicationStrategy.CREATE, null, null);
    publications.apply(inventoryId, findingId, UUID.randomUUID(), request);
    asset.apply(warehouseId, "CAPITAL_REPAIR", 8L);
    rentalItems.saveAndFlush(asset);

    assertThatThrownBy(
            () -> publications.apply(inventoryId, findingId, UUID.randomUUID(), request))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("inventory repair target does not match current asset truth");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_authoritative_outcome_receipt "
                    + "where inventory_id=? and final_plan_version=1 and finding_id=?",
                Integer.class,
                inventoryId,
                findingId))
        .isOne();
  }

  @Test
  void appliedCapitalWorkReassertionRejectsAdvancedOrdinaryRepairStatus() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    FreezeInventoryPlanRequest ordinary = autoRequest(inventoryId, findingId, List.of());
    FrozenInventoryPlanResponse frozen =
        inventory
            .freeze(
                new FreezeInventoryPlanRequest(
                    ordinary.warehouseId(),
                    ordinary.inventoryId(),
                    ordinary.findingId(),
                    ordinary.sourceRevision(),
                    ordinary.mode(),
                    ordinary.lines(),
                    ordinary.plan(),
                    ordinary.mediaReferences(),
                    ordinary.priority(),
                    ordinary.coverMediaId(),
                    false,
                    null,
                    null,
                    true))
            .response();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(frozen.snapshot());
    RentalItemFactProjection asset =
        RentalItemFactProjection.create(assetId, warehouseId, "REPAIR", 7L);
    rentalItems.saveAndFlush(asset);
    InventoryPublicationFindingInput finding =
        publicationFinding(findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);
    InventoryPublicationApplyRequest request =
        publicationApplyRequest(
            finding, 7L, 1L, InventoryPublicationStrategy.CREATE, null, null);
    publications.apply(inventoryId, findingId, UUID.randomUUID(), request);
    assertThat(
            jdbc.queryForObject(
                "select desired_status from inventory_authoritative_outcome "
                    + "where inventory_id=? and final_plan_version=1 and finding_id=?",
                String.class,
                inventoryId,
                findingId))
        .isEqualTo("CAPITAL_REPAIR");
    asset.apply(warehouseId, "REPAIR", 8L);
    rentalItems.saveAndFlush(asset);

    assertThatThrownBy(
            () -> publications.apply(inventoryId, findingId, UUID.randomUUID(), request))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("outside the applied inventory repair reassertion fence");
  }

  @Test
  void projectionOlderThanFrozenObservationIsRejected() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "BOOKED", 6L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);

    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 10L, 1L, InventoryPublicationStrategy.CREATE, null, null)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("outside the completed inventory authority fence");
  }

  @Test
  void projectionWarehouseMismatchAndBackwardAuthorityAreRejected() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, UUID.randomUUID(), "REPAIR", 7L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);

    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 10L, 1L, InventoryPublicationStrategy.CREATE, null, null)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("outside the completed inventory authority fence");

    RentalItemFactProjection asset = rentalItems.findById(assetId).orElseThrow();
    asset.apply(warehouseId, "REPAIR", 8L);
    rentalItems.saveAndFlush(asset);
    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 6L, 1L, InventoryPublicationStrategy.CREATE, null, null)))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("must not predate");
  }

  @ParameterizedTest(name = "terminal asset status {0} cannot be revived by inventory")
  @ValueSource(strings = {"LOST", "WRITTEN_OFF"})
  void terminalAssetStatusIsRejectedByPreflightAndApply(String assetStatus) {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, assetStatus, 7L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId, assetId, 7L, rawSnapshot, 2, 3, false, null);

    assertThatThrownBy(() -> publications.preflight(new InventoryPublicationPreflightRequest(
        inventoryId, warehouseId, 1L, finalPlanSha(1L), List.of(finding))))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("Terminal rental-item status");
    assertThatThrownBy(() -> publications.apply(
        inventoryId,
        findingId,
        UUID.randomUUID(),
        publicationApplyRequest(
            finding, 7L, 1L, InventoryPublicationStrategy.CREATE, null, null)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("Terminal rental-item status");
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

  private AuthoritativeActiveRepair authoritativeActiveRepair(
      boolean movementToRepair, boolean createRepairPlace) {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    ObjectNode rawSnapshot = (ObjectNode) mapper.valueToTree(inventory.freeze(
        autoRequest(inventoryId, findingId, List.of())).response().snapshot());
    rentalItems.saveAndFlush(
        RentalItemFactProjection.create(assetId, warehouseId, "FREE", 11L));
    InventoryPublicationFindingInput finding = publicationFinding(
        findingId,
        assetId,
        11L,
        rawSnapshot,
        2,
        3,
        movementToRepair,
        movementToRepair ? LocalDate.of(2026, 8, 21) : null);
    InventoryPublicationApplyRequest request = publicationApplyRequest(
        finding, 1L, InventoryPublicationStrategy.CREATE, null, null);
    UUID repairId = publications.apply(
        inventoryId, findingId, UUID.randomUUID(), request).response().repairId();
    UUID leaseId = UUID.randomUUID();
    MaintenanceRepair queued = new TransactionTemplate(transactionManager).execute(status -> {
      MaintenanceRepair repair = repairs.findById(repairId).orElseThrow();
      repair.queue(
          leaseId,
          0L,
          13L,
          OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
      repair.markTaskGenerated(0L);
      RepairStage stage = repairStages.findAllByRepairIdOrderByStageNo(repairId).getFirst();
      stage.queued();
      repairStages.saveAndFlush(stage);
      return repairs.saveAndFlush(repair);
    });
    if (queued == null) {
      throw new IllegalStateException("Authoritative active repair fixture was not queued");
    }
    jdbc.update(
        "update event_stream_head set current_version=? where aggregate_type='REPAIR' "
            + "and aggregate_id=?",
        queued.getVersion(),
        repairId.toString());
    if (createRepairPlace) {
      jdbc.update(
          "insert into repair_place_allocation(id,version,warehouse_id,repair_id,state,created_at,updated_at) "
              + "values (?,0,?,?,'RESERVED',clock_timestamp(),clock_timestamp())",
          UUID.randomUUID(),
          warehouseId,
          repairId);
    }
    RentalItemFactProjection projected = rentalItems.findById(assetId).orElseThrow();
    projected.apply(warehouseId, "REPAIR", 12L);
    rentalItems.saveAndFlush(projected);
    return new AuthoritativeActiveRepair(
        inventoryId,
        findingId,
        assetId,
        12L,
        repairId,
        queued.getExternalTaskId(),
        leaseId,
        request.inventoryCompletedAt());
  }

  private InventoryNoWorkOutcomeRequest noWorkRequest(
      AuthoritativeActiveRepair active, long finalPlanVersion, OffsetDateTime completedAt) {
    return noWorkRequest(
        active.assetId(), active.assetVersion(), finalPlanVersion, completedAt);
  }

  private InventoryNoWorkOutcomeRequest noWorkRequest(
      UUID assetId,
      long authoritativeAssetVersion,
      long finalPlanVersion,
      OffsetDateTime completedAt) {
    return new InventoryNoWorkOutcomeRequest(
        warehouseId,
        assetId,
        completedAt,
        finalPlanVersion,
        finalPlanSha(finalPlanVersion),
        1L,
        authoritativeAssetVersion,
        "FREE");
  }

  private void insertEventHead(String aggregateType, UUID aggregateId, long currentVersion) {
    jdbc.update(
        "insert into event_stream_head(aggregate_type,aggregate_id,current_version,last_event_id,updated_at) "
            + "values (?,?,?,?,clock_timestamp())",
        aggregateType,
        aggregateId.toString(),
        currentVersion,
        UUID.randomUUID());
  }

  private MaintenanceRepair directDraftRepair(
      UUID assetId, long assetVersion, String comment) {
    MaintenanceRepair repair = repairs.saveAndFlush(MaintenanceRepair.primary(
        warehouseId,
        assetId,
        assetVersion,
        null,
        RepairOrigin.DIRECT_REPAIR,
        LocalDate.of(2026, 8, 5),
        comment,
        "{}"));
    insertEventHead("REPAIR", repair.getId(), repair.getVersion());
    return repair;
  }

  /** Makes a local pre-registration task identity resolve like task-board's ordinary 404 truth. */
  private void stubTaskNotFound(UUID externalTaskId) {
    when(dependencies.getTask(externalTaskId))
        .thenThrow(new MaintenanceDependencyException(
            HttpStatus.NOT_FOUND, "task does not exist"));
  }

  /** Creates an inventory-owned queued repair with one durably confirmed task-board mapping. */
  private MaintenanceRepair confirmedQueuedInventoryRepair(
      UUID assetId, long assetVersion, String sourceParty) {
    MaintenanceRepair repair = MaintenanceRepair.primary(
        warehouseId,
        assetId,
        assetVersion,
        null,
        RepairOrigin.INVENTORY,
        LocalDate.of(2026, 8, 5),
        sourceParty,
        "{}");
    repair.queue(
        UUID.randomUUID(),
        0L,
        17L,
        OffsetDateTime.now(ZoneOffset.UTC).plusHours(1));
    repair.markTaskGenerated(3L);
    MaintenanceRepair saved = repairs.saveAndFlush(repair);
    RepairStage stage = new RepairStage(
        UUID.randomUUID(),
        saved.getId(),
        0,
        RepairStageKind.REPAIR_WORK,
        workNodeId,
        "Repair",
        "REPAIR",
        "[]",
        "[]",
        null,
        "",
        null);
    stage.queued();
    stage.confirmTaskBoardRegistration(UUID.randomUUID(), 3L);
    repairStages.saveAndFlush(stage);
    insertEventHead("REPAIR", saved.getId(), saved.getVersion());
    return saved;
  }

  /** Confirms the current task mapping and optionally records completed local stage evidence. */
  private MaintenanceRepair confirmInventoryTask(
      UUID repairId, long taskBoardVersion, boolean completed) {
    MaintenanceRepair saved = new TransactionTemplate(transactionManager).execute(status -> {
      MaintenanceRepair repair = repairs.findById(repairId).orElseThrow();
      repair.queueUnderExistingRepair();
      repair.markTaskGenerated(taskBoardVersion);
      List<RepairStage> stages = repairStages.findAllByRepairIdOrderByStageNo(repairId);
      for (RepairStage stage : stages) {
        stage.queued();
        stage.confirmTaskBoardRegistration(UUID.randomUUID(), taskBoardVersion);
        if (completed) {
          stage.completed(
              UUID.randomUUID(),
              Math.addExact(taskBoardVersion, 1L),
              OffsetDateTime.now(ZoneOffset.UTC));
        }
      }
      if (completed) {
        repair.applyStageCompletion(true);
      }
      repairStages.saveAllAndFlush(stages);
      return repairs.saveAndFlush(repair);
    });
    if (saved == null) {
      throw new IllegalStateException("Inventory task fixture transaction returned no repair");
    }
    jdbc.update(
        "update event_stream_head set current_version=? where aggregate_type='REPAIR' "
            + "and aggregate_id=?",
        saved.getVersion(),
        repairId.toString());
    return saved;
  }

  private void saveAuthoritativeWorkOutcome(
      InventoryPublicationSourceId sourceId,
      UUID assetId,
      UUID targetRepairId,
      long findingRevision,
      String finalPlanSha256,
      String requestSha256) {
    InventoryAuthoritativeOutcome outcome =
        InventoryAuthoritativeOutcome.prepare(
            sourceId,
            requestSha256,
            "{}",
            warehouseId,
            assetId,
            OffsetDateTime.of(2026, 8, 19, 8, 0, 0, 0, ZoneOffset.UTC),
            finalPlanSha256,
            findingRevision,
            12L,
            "REPAIR",
            "WORK");
    outcome.markEffectsSettled();
    outcome.attachTarget(targetRepairId);
    outcome.apply("{\"repairId\":\"" + targetRepairId + "\"}");
    authoritativeOutcomeRepository.saveAndFlush(outcome);
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

  private record AuthoritativeActiveRepair(
      UUID inventoryId,
      UUID findingId,
      UUID assetId,
      long assetVersion,
      UUID repairId,
      UUID externalTaskId,
      UUID leaseId,
      OffsetDateTime completedAt) {}

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
        snapshotSchemaVersion,
        rawSnapshot.path("forceCapitalRepair").asBoolean(false));
  }

  private InventoryPublicationApplyRequest publicationApplyRequest(
      InventoryPublicationFindingInput finding,
      long finalPlanVersion,
      InventoryPublicationStrategy strategy,
      InventoryPublicationTargetKind selectedTargetKind,
      UUID selectedTargetId) {
    return publicationApplyRequest(
        finding,
        finding.assetVersion(),
        finalPlanVersion,
        strategy,
        selectedTargetKind,
        selectedTargetId);
  }

  private InventoryPublicationApplyRequest publicationApplyRequest(
      InventoryPublicationFindingInput finding,
      long authoritativeAssetVersion,
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
        authoritativeAssetVersion,
        OffsetDateTime.of(2026, 8, 19, 10, 0, 1, 0, ZoneOffset.UTC),
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
        selectedTargetId,
        finding.forceCapitalRepair());
  }

  /** Advances only immutable final-plan identity while retaining the exact finding content. */
  private static InventoryPublicationApplyRequest correctedPlanVersion(
      InventoryPublicationApplyRequest request, long finalPlanVersion) {
    return new InventoryPublicationApplyRequest(
        request.warehouseId(),
        finalPlanVersion,
        finalPlanSha(finalPlanVersion),
        request.findingRevision(),
        request.assetId(),
        request.assetVersion(),
        request.authoritativeAssetVersion(),
        request.inventoryCompletedAt(),
        request.planFingerprintSha256(),
        request.priority(),
        request.movementToRepair(),
        request.movementScheduledDate(),
        request.repairScheduledDate(),
        request.snapshot(),
        request.media(),
        request.snapshotSchemaVersion(),
        request.strategy(),
        request.selectedTargetKind(),
        request.selectedTargetId(),
        request.forceCapitalRepair());
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
