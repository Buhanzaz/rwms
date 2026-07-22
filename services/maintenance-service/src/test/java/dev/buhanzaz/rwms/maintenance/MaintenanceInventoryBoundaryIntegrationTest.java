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
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceReconciliationRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.InventoryMaintenanceService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
  @Autowired MaintenanceApplicationService maintenance;
  @Autowired RentalItemFactProjectionRepository rentalItems;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired MaintenanceReconciliationRepository inventoryReconciliations;
  @Autowired MediaFactProjectionRepository mediaFacts;
  @Autowired JdbcTemplate jdbc;

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
          assertThat(stage.catalogNodeCode()).isEqualTo("WORK_A");
          assertThat(stage.routing().queueCode()).isEqualTo("REPAIR");
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

    UUID queueEntryId = UUID.randomUUID();
    when(dependencies.registerTask(
        any(), eq(queued.getExternalTaskId()), eq(warehouseId), eq(rentalItemId), anyList()))
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
        List.of());

    FrozenInventoryPlanResponse frozen = inventory.freeze(manual).response();
    assertThat(frozen.snapshot().mode()).isEqualTo(InventoryPlanMode.MANUAL);
    assertThat(frozen.fingerprint()).matches("[0-9a-f]{64}");

    FreezeInventoryPlanRequest unknown = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(UUID.randomUUID(), "1", null, List.of())),
        List.of(), List.of());
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
        List.of());
    InventoryPlanLineSnapshot manualWork = inventory.freeze(manualLines).response()
        .snapshot().lines().getFirst();
    assertThat(manualWork.aggregationKind()).isEqualTo(InventoryPlanLineKind.MANUAL);
    assertThat(manualWork.catalogNodeId()).isNull();
    assertThat(manualWork.description()).isEqualTo("Шлифовка Стола");
    assertThat(manualWork.normalizedDescription()).isEqualTo("шлифовка стола");
    assertThat(manualWork.quantity()).isEqualTo("1.250000");
    assertThat(manualWork.unitPriceMinor()).isEqualTo(15000);
    assertThat(manualWork.normativeMinutes()).isEqualTo("45.500");
  }

  @Test
  void sourceRevisionStageKindsAndRoutingAreValidatedBeforeFreeze() {
    FreezeInventoryPlanRequest zeroRevision = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 0L, InventoryPlanMode.AUTO,
        List.of(catalogLine(workNodeId, "1", null, List.of())),
        List.of(), List.of());
    assertThatThrownBy(() -> inventory.freeze(zeroRevision))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("revision");

    FreezeInventoryPlanRequest workAsLocation = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(workNodeId, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            workNodeId, RepairStageKind.MOVE_TO_REPAIR, 0)),
        List.of());
    assertThatThrownBy(() -> inventory.freeze(workAsLocation))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("LOCATION");

    UUID materialNode = insertCatalogNode("MATERIAL", true);
    FreezeInventoryPlanRequest materialAsWork = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(materialNode, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            materialNode, RepairStageKind.REPAIR_WORK, 0)),
        List.of());
    assertThatThrownBy(() -> inventory.freeze(materialAsWork))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("WORK");

    FreezeInventoryPlanRequest materialOnlyWithSeparateWorkRoute =
        new FreezeInventoryPlanRequest(
            warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L,
            InventoryPlanMode.MANUAL,
            List.of(
                catalogLine(materialNode, "1", null, List.of()),
                manualLine(
                    "Manual material", InventoryPlanLineType.MATERIAL,
                    "pcs", "1", 100L, "0")),
            List.of(new InventoryPlanStageSelection(
                workNodeId, RepairStageKind.REPAIR_WORK, 0)),
            List.of());
    FrozenInventoryPlanSnapshot materialOnly = inventory.freeze(
        materialOnlyWithSeparateWorkRoute).response().snapshot();
    assertThat(materialOnly.lines())
        .allMatch(line -> line.type() == InventoryPlanLineType.MATERIAL);
    assertThat(materialOnly.stages()).singleElement().satisfies(stage -> {
      assertThat(stage.catalogNodeId()).isEqualTo(workNodeId);
      assertThat(stage.kind()).isEqualTo(RepairStageKind.REPAIR_WORK);
    });

    UUID furnitureMaterial = insertFurnitureMaterial();
    FreezeInventoryPlanRequest furnitureOutsideEstimate = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.MANUAL,
        List.of(catalogLine(furnitureMaterial, "1", null, List.of())),
        List.of(new InventoryPlanStageSelection(
            workNodeId, RepairStageKind.REPAIR_WORK, 0)),
        List.of());
    assertThatThrownBy(() -> inventory.freeze(furnitureOutsideEstimate))
        .isInstanceOf(MaintenanceValidationException.class)
        .hasMessageContaining("Furniture materials can only be used through an estimate");

    UUID unroutedWork = insertCatalogNode("WORK", false);
    FreezeInventoryPlanRequest unrouted = new FreezeInventoryPlanRequest(
        warehouseId, UUID.randomUUID(), UUID.randomUUID(), 1L, InventoryPlanMode.AUTO,
        List.of(catalogLine(unroutedWork, "1", null, List.of())),
        List.of(), List.of());
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
        List.of());
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
          List.of());
      assertThatThrownBy(() -> inventory.freeze(rejected))
          .isInstanceOf(MaintenanceValidationException.class);
    }
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
        List.of());
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

  private FreezeInventoryPlanRequest autoRequest(
      UUID inventoryId, UUID findingId, List<MediaReferenceInput> media) {
    return new FreezeInventoryPlanRequest(
        warehouseId, inventoryId, findingId, 3L, InventoryPlanMode.AUTO,
        List.of(catalogLine(workNodeId, "2.500", "  group   comment ", List.of())),
        List.of(), media);
  }

  private static InventoryPlanLineInput catalogLine(
      UUID catalogNodeId,
      String quantity,
      String groupComment,
      List<MediaReferenceInput> media) {
    return new InventoryPlanLineInput(
        InventoryPlanLineKind.CATALOG, catalogNodeId, null, null, null,
        quantity, null, null, groupComment, media);
  }

  private static InventoryPlanLineInput manualLine(
      String description,
      InventoryPlanLineType type,
      String unit,
      String quantity,
      long unitPriceMinor,
      String normativeMinutes) {
    return new InventoryPlanLineInput(
        InventoryPlanLineKind.MANUAL, null, description, type, unit, quantity,
        unitPriceMinor, normativeMinutes, null, List.of());
  }

  private void insertActiveCatalog(UUID versionId, UUID nodeId, String code) {
    jdbc.update("""
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,activated_at,created_at,updated_at)
        values (?,0,?,'ACTIVE',?,1,0,'{}',clock_timestamp(),clock_timestamp(),clock_timestamp())
        """, versionId, warehouseId, UUID.nameUUIDFromBytes(code.getBytes()).toString()
            .replace("-", "") + UUID.nameUUIDFromBytes((code + "x").getBytes()).toString()
            .replace("-", "").substring(0, 32));
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          routing_queue_id,routing_queue_code,routing_queue_kind,opaque_references)
        values (?,?,?,?,'WORK','Repair work',true,'pcs',12500,45,true,false,false,
          ?,'REPAIR','REPAIR','[]')
        """, UUID.randomUUID(), nodeId, versionId, code, UUID.randomUUID());
  }

  private UUID insertCatalogNode(String nodeType, boolean routed) {
    UUID nodeId = UUID.randomUUID();
    UUID queueId = routed ? UUID.randomUUID() : null;
    String queueCode = routed ? "REPAIR" : null;
    String queueKind = routed ? "REPAIR" : null;
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,unit,price_minor,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          routing_queue_id,routing_queue_code,routing_queue_kind,opaque_references)
        values (?,?,?, ?,?,'Catalog node',true,'pcs',12500,45,true,false,false,
          ?,?,?,'[]')
        """, UUID.randomUUID(), nodeId, catalogId,
        ("NODE_" + nodeId.toString().substring(0, 8)).toUpperCase(Locale.ROOT),
        nodeType, queueId, queueCode, queueKind);
    return nodeId;
  }

  private UUID insertFurnitureMaterial() {
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,furniture_category,
          duration_minutes,include_in_estimate,common_item,show_in_main_menu,opaque_references)
        values (?,?,?,'FURNITURE','CATEGORY','Furniture',true,true,
          0,false,false,true,'[]')
        """, UUID.randomUUID(), categoryId, catalogId);
    jdbc.update("""
        insert into catalog_node(
          row_id,node_id,catalog_version_id,code,node_type,name,active,parent_node_id,unit,
          price_minor,duration_minutes,include_in_estimate,common_item,show_in_main_menu,
          opaque_references)
        values (?,?,?,'FURNITURE_CHAIR','MATERIAL','Furniture chair',true,?,'pcs',12500,
          0,true,false,false,'[]')
        """, UUID.randomUUID(), materialId, catalogId, categoryId);
    return materialId;
  }
}
