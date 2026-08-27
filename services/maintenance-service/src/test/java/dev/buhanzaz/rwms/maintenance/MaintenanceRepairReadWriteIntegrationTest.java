package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEstimate;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceRepair;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairOrigin;
import dev.buhanzaz.rwms.maintenance.domain.RepairStage;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceEstimateRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.RepairStageRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

/**
 * Verifies database-bounded repair reads and the serialized direct-repair creation invariant
 * against the production PostgreSQL schema.
 */
@SpringBootTest(
    properties = {
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
/**
 * Verifies repair read/write filtering, creation fencing, and actionable acceptance against a
 * real PostgreSQL schema.
 */
class MaintenanceRepairReadWriteIntegrationTest {
  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceApplicationService service;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired RepairStageRepository repairStages;
  @Autowired MaintenanceEstimateRepository estimates;
  @Autowired RentalItemFactProjectionRepository rentalItemFacts;
  @Autowired JdbcTemplate jdbc;

  @MockitoBean MaintenanceDependencyGateway dependencies;

  @BeforeEach
  void resetDatabaseAndDependencies() {
    jdbc.execute(
        """
        truncate table
          catalog_version,
          maintenance_estimate,
          maintenance_repair,
          rental_item_fact_projection,
          maintenance_idempotency_record,
          integration_reconciliation,
          event_stream_head
        cascade
        """);
    reset(dependencies);
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(
            invocation ->
                new MaintenanceDependencyGateway.RoutingPreflight(
                    invocation.getArgument(0), true, List.of(), List.of(), List.of(), List.of()));
  }

  @Test
  void acceptanceFiltersAndPaginatesInDatabaseWithExactRepairLookup() {
    UUID warehouseId = UUID.randomUUID();
    MaintenanceRepair pending = pendingRepair(warehouseId, RepairOrigin.DIRECT_REPAIR);
    MaintenanceRepair inRework = pendingRepair(warehouseId, RepairOrigin.DIRECT_REPAIR);
    jdbc.update(
        "update maintenance_repair set acceptance_state='IN_REWORK' where id=?",
        inRework.getId());

    MaintenanceRepair falseInventory = pendingRepair(warehouseId, RepairOrigin.INVENTORY);
    repairStages.saveAndFlush(historicalStage(falseInventory));

    MaintenanceRepair completedInventory = pendingRepair(warehouseId, RepairOrigin.INVENTORY);
    repairStages.saveAndFlush(completedTaskBoardStage(completedInventory));

    MaintenanceRepair blockedSource = pendingRepair(warehouseId, RepairOrigin.DIRECT_REPAIR);
    MaintenanceRepair currentBlockedSource = repairs.findById(blockedSource.getId()).orElseThrow();
    repairs.saveAndFlush(MaintenanceRepair.rework(currentBlockedSource, "active child", "{}"));

    PageResponse<AcceptanceProjection> first =
        service.acceptance(warehouseId, null, null, 0, 2);
    PageResponse<AcceptanceProjection> second =
        service.acceptance(warehouseId, null, null, 1, 2);
    Set<UUID> actionable = new HashSet<>();
    first.items().forEach(item -> actionable.add(item.repairId()));
    second.items().forEach(item -> actionable.add(item.repairId()));

    assertThat(first.totalElements()).isEqualTo(3);
    assertThat(first.items()).hasSize(2);
    assertThat(second.totalElements()).isEqualTo(3);
    assertThat(second.items()).hasSize(1);
    assertThat(actionable)
        .containsExactlyInAnyOrder(pending.getId(), inRework.getId(), completedInventory.getId())
        .doesNotContain(falseInventory.getId(), blockedSource.getId());

    PageResponse<AcceptanceProjection> pendingOnly =
        service.acceptance(warehouseId, RepairAcceptanceState.PENDING, null, 0, 200);
    assertThat(pendingOnly.items())
        .extracting(AcceptanceProjection::repairId)
        .containsExactlyInAnyOrder(pending.getId(), completedInventory.getId());

    PageResponse<AcceptanceProjection> exact =
        service.acceptance(warehouseId, null, completedInventory.getId(), 0, 200);
    assertThat(exact.totalElements()).isOne();
    assertThat(exact.items())
        .extracting(AcceptanceProjection::repairId)
        .containsExactly(completedInventory.getId());
  }

  @Test
  void directRepairConcurrentLostResponseRetryReplaysAndSecondRootConflicts() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    activeCatalog(warehouseId);
    CountDownLatch remotePreflights = new CountDownLatch(2);
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(
            invocation -> {
              remotePreflights.countDown();
              if (!remotePreflights.await(15, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Concurrent preflights did not rendezvous");
              }
              return new MaintenanceDependencyGateway.RoutingPreflight(
                  invocation.getArgument(0), true, List.of(), List.of(), List.of(), List.of());
            });
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    CreateDirectRepairRequest request = routedDirectRequest(warehouseId, rentalItemId);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch start = new CountDownLatch(1);
    var executor = Executors.newFixedThreadPool(2);
    try {
      Future<MaintenanceApplicationService.CreateResult<RepairResponse>> first =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return service.createDirectRepair(subjectId, idempotencyKey, request);
              });
      Future<MaintenanceApplicationService.CreateResult<RepairResponse>> second =
          executor.submit(
              () -> {
                ready.countDown();
                start.await();
                return service.createDirectRepair(subjectId, idempotencyKey, request);
              });
      assertThat(ready.await(15, TimeUnit.SECONDS)).isTrue();
      start.countDown();
      assertThat(remotePreflights.await(15, TimeUnit.SECONDS)).isTrue();

      List<MaintenanceApplicationService.CreateResult<RepairResponse>> results =
          List.of(first.get(30, TimeUnit.SECONDS), second.get(30, TimeUnit.SECONDS));
      assertThat(results).extracting(MaintenanceApplicationService.CreateResult::replayed)
          .containsExactlyInAnyOrder(false, true);
      assertThat(results.getFirst().response().id()).isEqualTo(results.getLast().response().id());
    } finally {
      start.countDown();
      executor.shutdownNow();
      assertThat(executor.awaitTermination(15, TimeUnit.SECONDS)).isTrue();
    }

    assertThat(
            jdbc.queryForObject(
                "select count(*) from maintenance_repair where rental_item_id=?",
                Integer.class,
                rentalItemId))
        .isOne();
    assertThatThrownBy(
            () ->
                service.createDirectRepair(
                    subjectId, UUID.randomUUID(), emptyDirectRequest(warehouseId, rentalItemId)))
        .isInstanceOf(MaintenanceConflictException.class)
        .hasMessageContaining("active primary repair chain");
  }

  @Test
  void repairPageAppliesExactEstimateFilterBeforeHydration() {
    UUID warehouseId = UUID.randomUUID();
    UUID catalogId = activeCatalog(warehouseId);
    MaintenanceEstimate selected = estimate(warehouseId, catalogId);
    MaintenanceEstimate other = estimate(warehouseId, catalogId);
    MaintenanceRepair selectedRepair = estimateRepair(selected);
    estimateRepair(other);

    PageResponse<RepairResponse> result =
        service.repairs(warehouseId, null, null, null, selected.getId(), null, 0, 200);

    assertThat(result.totalElements()).isOne();
    assertThat(result.items())
        .extracting(RepairResponse::id)
        .containsExactly(selectedRepair.getId());
    assertThat(
            jdbc.queryForList(
                """
                select indexname from pg_indexes
                 where schemaname='public'
                   and indexname in ('idx_repair_source_rework','idx_repair_active_primary_item')
                 order by indexname
                """,
                String.class))
        .containsExactly("idx_repair_active_primary_item", "idx_repair_source_rework");
  }

  private MaintenanceRepair pendingRepair(UUID warehouseId, RepairOrigin origin) {
    MaintenanceRepair repair =
        repairs.saveAndFlush(
            MaintenanceRepair.primary(
                warehouseId,
                UUID.randomUUID(),
                1,
                null,
                origin,
                LocalDate.of(2026, 8, 27),
                null,
                "{}"));
    jdbc.update(
        """
        update maintenance_repair
           set execution_state='COMPLETED', acceptance_state='PENDING', task_board_version=7
         where id=?
        """,
        repair.getId());
    return repair;
  }

  private RepairStage historicalStage(MaintenanceRepair repair) {
    RepairStage stage = registeredStage(repair);
    stage.closeForHistoricalShipment(false);
    return stage;
  }

  private RepairStage completedTaskBoardStage(MaintenanceRepair repair) {
    RepairStage stage = registeredStage(repair);
    stage.completed(UUID.randomUUID(), 2, OffsetDateTime.now(ZoneOffset.UTC));
    return stage;
  }

  private RepairStage registeredStage(MaintenanceRepair repair) {
    RepairStage stage =
        new RepairStage(
            UUID.randomUUID(),
            repair.getId(),
            0,
            RepairStageKind.REPAIR_WORK,
            UUID.randomUUID(),
            "Repair",
            "REPAIR",
            null);
    stage.queued();
    stage.confirmTaskBoardRegistration(UUID.randomUUID(), 1);
    return stage;
  }

  private MaintenanceEstimate estimate(UUID warehouseId, UUID catalogId) {
    return estimates.saveAndFlush(
        MaintenanceEstimate.create(
            warehouseId,
            UUID.randomUUID(),
            1,
            catalogId,
            LocalDate.of(2026, 8, 27),
            null,
            null,
            "{}"));
  }

  private MaintenanceRepair estimateRepair(MaintenanceEstimate estimate) {
    return repairs.saveAndFlush(
        MaintenanceRepair.primary(
            estimate.getWarehouseId(),
            estimate.getRentalItemId(),
            estimate.getRentalItemVersionSnapshot(),
            estimate.getId(),
            RepairOrigin.ESTIMATE,
            estimate.getDispatchDate(),
            null,
            "{}"));
  }

  private UUID activeCatalog(UUID warehouseId) {
    UUID catalogId = UUID.randomUUID();
    jdbc.update(
        """
        insert into catalog_version(
          id,version,warehouse_id,state,source_sha256,node_count,link_count,
          validation_report,activated_at,created_at,updated_at)
        values (?,0,?,'ACTIVE',?,0,0,?::jsonb::text,clock_timestamp(),
                clock_timestamp(),clock_timestamp())
        """,
        catalogId,
        warehouseId,
        "e".repeat(64),
        """
        {"valid":true,"errorCount":0,"warningCount":0,
         "reportSha256":"0000000000000000000000000000000000000000000000000000000000000000"}
        """);
    return catalogId;
  }

  private static CreateDirectRepairRequest emptyDirectRequest(
      UUID warehouseId, UUID rentalItemId) {
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 8, 27),
        null,
        List.of(),
        List.of(),
        List.of());
  }

  private static CreateDirectRepairRequest routedDirectRequest(
      UUID warehouseId, UUID rentalItemId) {
    UUID lineId = UUID.randomUUID();
    RoutingSnapshot routing = new RoutingSnapshot(UUID.randomUUID(), "Repair", "REPAIR");
    EstimateLineInput line =
        new EstimateLineInput(
            lineId,
            null,
            EstimateLineType.WORK,
            "Concurrent repair",
            "item",
            "1",
            "1.00",
            1,
            null,
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
    return new CreateDirectRepairRequest(
        warehouseId,
        rentalItemId,
        LocalDate.of(2026, 8, 27),
        null,
        List.of(line),
        List.of(stage),
        List.of());
  }
}
