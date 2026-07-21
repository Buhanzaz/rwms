package dev.buhanzaz.rwms.maintenance;

import static dev.buhanzaz.rwms.maintenance.api.MaintenanceApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceMediaReference;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.maintenance.domain.MediaFactProjection;
import dev.buhanzaz.rwms.maintenance.domain.RepairAcceptanceState;
import dev.buhanzaz.rwms.maintenance.domain.RepairExecutionState;
import dev.buhanzaz.rwms.maintenance.domain.RepairStageKind;
import dev.buhanzaz.rwms.maintenance.domain.RentalItemFactProjection;
import dev.buhanzaz.rwms.maintenance.eventing.MaintenanceEventFactFactory;
import dev.buhanzaz.rwms.maintenance.integration.MaintenanceDependencyGateway;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceRepairRepository;
import dev.buhanzaz.rwms.maintenance.repository.MaintenanceMediaReferenceRepository;
import dev.buhanzaz.rwms.maintenance.repository.MediaFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.repository.RentalItemFactProjectionRepository;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceCatalogImportValidationException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceDependencyException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceIdempotencyStore;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceReconciliationReviewService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceReconciliationStore;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class MaintenanceCorePostgresIntegrationTest {
  private static final UUID REVIEWED_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MaintenanceApplicationService service;
  @Autowired MaintenanceIdempotencyStore idempotency;
  @Autowired RentalItemFactProjectionRepository rentalItemFacts;
  @Autowired MaintenanceRepairRepository repairs;
  @Autowired MediaFactProjectionRepository mediaFacts;
  @Autowired MaintenanceMediaReferenceRepository mediaReferences;
  @Autowired MaintenanceReconciliationStore reconciliations;
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
          maintenance_idempotency_record,
          integration_reconciliation,
          event_stream_head
        cascade
        """);
    reset(dependencies);
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(invocation -> new MaintenanceDependencyGateway.RoutingPreflight(
            invocation.getArgument(0), true, List.of(), List.of()));
    clearInvocations(eventFacts);
  }

  @Test
  void reviewedBootstrapPreflightFailureCommitsNoLocalCatalogState() {
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(invocation -> {
          UUID warehouseId = invocation.getArgument(0);
          List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
              invocation.getArgument(1);
          return new MaintenanceDependencyGateway.RoutingPreflight(
              warehouseId, false, List.of(requirements.getFirst().queueId()), List.of());
        });

    assertThatThrownBy(() -> service.bootstrapCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BootstrapCatalogRequest(REVIEWED_WAREHOUSE_ID)))
        .isInstanceOfSatisfying(
            MaintenanceCatalogImportValidationException.class,
            exception -> assertThat(exception.violations())
                .extracting(dev.buhanzaz.rwms.platform.contracts.FieldViolation::code)
                .containsExactly("MAINTENANCE_ROUTING_QUEUE_MISSING"));

    @SuppressWarnings("unchecked")
    ArgumentCaptor<List<MaintenanceDependencyGateway.RoutingQueueRequirement>> requirements =
        ArgumentCaptor.forClass(List.class);
    verify(dependencies).preflightMaintenanceRouting(
        eq(REVIEWED_WAREHOUSE_ID),
        requirements.capture());
    assertThat(requirements.getValue())
        .hasSize(6)
        .doesNotHaveDuplicates();
    assertThat(jdbc.queryForObject("select count(*) from catalog_version", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from catalog_node", Integer.class)).isZero();
    assertThat(jdbc.queryForObject("select count(*) from catalog_link", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_idempotency_record", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation", Integer.class)).isZero();
  }

  @Test
  void reviewedBootstrapRejectsMismatchedQueueBeforeCommit() {
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenAnswer(invocation -> {
          UUID warehouseId = invocation.getArgument(0);
          List<MaintenanceDependencyGateway.RoutingQueueRequirement> requirements =
              invocation.getArgument(1);
          return new MaintenanceDependencyGateway.RoutingPreflight(
              warehouseId,
              false,
              List.of(),
              List.of(new MaintenanceDependencyGateway.RoutingMismatch(
                  requirements.getFirst().queueId(), List.of("CODE", "TYPE"))));
        });

    assertThatThrownBy(() -> service.bootstrapCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new BootstrapCatalogRequest(REVIEWED_WAREHOUSE_ID)))
        .isInstanceOfSatisfying(
            MaintenanceCatalogImportValidationException.class,
            exception -> assertThat(exception.violations())
                .extracting(dev.buhanzaz.rwms.platform.contracts.FieldViolation::code)
                .containsExactly("MAINTENANCE_ROUTING_QUEUE_MISMATCH"));

    assertThat(jdbc.queryForObject("select count(*) from catalog_version", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation", Integer.class)).isZero();
  }

  @Test
  void activationDependencyFailureLeavesTheDraftAndEventStreamUntouched() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "7".repeat(64));
    when(dependencies.preflightMaintenanceRouting(any(UUID.class), anyList()))
        .thenThrow(new MaintenanceDependencyException(
            org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE,
            "task-board unavailable"));

    assertThatThrownBy(() -> service.activateCatalog(
            UUID.randomUUID(),
            UUID.randomUUID(),
            catalogId,
            new VersionCommand(0L)))
        .isInstanceOfSatisfying(
            MaintenanceDependencyException.class,
            exception -> assertThat(exception.status())
                .isEqualTo(org.springframework.http.HttpStatus.SERVICE_UNAVAILABLE));

    assertThat(jdbc.queryForMap("""
        select state,version from catalog_version where id=?
        """, catalogId))
        .containsEntry("state", "DRAFT")
        .containsEntry("version", 0L);
    assertThat(jdbc.queryForObject("""
        select current_version from event_stream_head
        where aggregate_type='CATALOG_VERSION' and aggregate_id=?
        """, Long.class, catalogId.toString())).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from integration_reconciliation", Integer.class)).isZero();
    assertThat(jdbc.queryForObject(
        "select count(*) from maintenance_idempotency_record", Integer.class)).isZero();
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
        "ROUTED_WORK",
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
        false,
        routing,
        List.of(),
        null,
        List.of());
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
    assertThat(service.catalogVersion(replacement.id()).routingSync().state())
        .isEqualTo(DeliveryState.DELIVERED);
    CatalogRoutingSyncSnapshot supersededTruth =
        service.catalogVersion(firstCatalogId).routingSync();
    assertThat(supersededTruth.state()).isEqualTo(DeliveryState.DELIVERED);
    assertThat(supersededTruth.cleanupRequired()).isOne();
    assertThat(supersededTruth.cleanupConfirmed()).isOne();
  }

  @Test
  void catalogMediaOwnerIsStablePerWarehouseForSharedLogicalNodeAndRevokesIndependently() {
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();
    UUID sharedNodeId = UUID.randomUUID();
    UUID firstCatalogId = insertDraftCatalog(firstWarehouseId, "a".repeat(64));
    UUID secondCatalogId = insertDraftCatalog(secondWarehouseId, "b".repeat(64));

    CatalogVersionResponse first = service.changeCatalog(
        firstCatalogId,
        new ChangeCatalogRequest(
            0L, List.of(catalogWork(sharedNodeId, "First", List.of())), List.of()));
    CatalogVersionResponse second = service.changeCatalog(
        secondCatalogId,
        new ChangeCatalogRequest(
            0L, List.of(catalogWork(sharedNodeId, "Second", List.of())), List.of()));

    UUID firstOwnerId = jdbc.queryForObject("""
        select media_owner_id from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_source_id=? and media_owner_revision=0
        """, UUID.class, firstCatalogId);
    UUID secondOwnerId = jdbc.queryForObject("""
        select media_owner_id from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_source_id=? and media_owner_revision=0
        """, UUID.class, secondCatalogId);
    assertThat(firstOwnerId).isNotEqualTo(secondOwnerId);

    UUID firstMediaId = UUID.randomUUID();
    UUID secondMediaId = UUID.randomUUID();
    mediaFacts.saveAllAndFlush(List.of(
        MediaFactProjection.create(
            firstMediaId,
            1,
            "MAINTENANCE_CATALOG_NODE",
            firstOwnerId,
            firstWarehouseId,
            "READY",
            "{}",
            1),
        MediaFactProjection.create(
            secondMediaId,
            1,
            "MAINTENANCE_CATALOG_NODE",
            secondOwnerId,
            secondWarehouseId,
            "READY",
            "{}",
            1)));

    CatalogVersionResponse firstWithMedia = service.changeCatalog(
        firstCatalogId,
        new ChangeCatalogRequest(
            first.version(),
            List.of(catalogWork(
                sharedNodeId, "First with media", List.of(new MediaReferenceInput(firstMediaId, 1L)))),
            List.of()));
    service.changeCatalog(
        secondCatalogId,
        new ChangeCatalogRequest(
            second.version(),
            List.of(catalogWork(
                sharedNodeId, "Second with media", List.of(new MediaReferenceInput(secondMediaId, 1L)))),
            List.of()));

    assertThat(jdbc.queryForObject("""
        select count(*) from maintenance_media_reference
        where aggregate_type='CATALOG_NODE'
          and owner_type='MAINTENANCE_CATALOG_NODE'
          and media_id in (?,?)
        """, Integer.class, firstMediaId, secondMediaId)).isEqualTo(2);
    assertThat(jdbc.queryForList("""
        select distinct media_owner_id from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_source_id in (?,?)
        """, UUID.class, firstCatalogId, secondCatalogId))
        .containsExactlyInAnyOrder(firstOwnerId, secondOwnerId);

    UUID replacementNodeId = UUID.randomUUID();
    service.changeCatalog(
        firstCatalogId,
        new ChangeCatalogRequest(
            firstWithMedia.version(),
            List.of(catalogWork(replacementNodeId, "Replacement", List.of())),
            List.of()));

    assertThat(jdbc.queryForObject("""
        select media_active from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_owner_id=?
        order by media_owner_revision desc limit 1
        """, Boolean.class, firstOwnerId)).isFalse();
    assertThat(jdbc.queryForObject("""
        select media_active from integration_reconciliation
        where dependency_type='MEDIA'
          and media_owner_type='MAINTENANCE_CATALOG_NODE'
          and media_owner_id=?
        order by media_owner_revision desc limit 1
        """, Boolean.class, secondOwnerId)).isTrue();
    assertThat(service.catalogNodes(secondCatalogId))
        .singleElement()
        .satisfies(node -> {
          assertThat(node.id()).isEqualTo(sharedNodeId);
          assertThat(node.mediaOwnerId()).isEqualTo(secondOwnerId);
        });
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
  void concurrentCatalogActivationReselectsUnderOneWarehouseLock() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID first = insertDraftCatalog(warehouseId, "1".repeat(64));
    UUID second = insertDraftCatalog(warehouseId, "2".repeat(64));
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
          where warehouse_id=? and state='ACTIVE'
          """, Integer.class, warehouseId)).isOne();
      assertThat(jdbc.queryForList("""
          select state from catalog_version where warehouse_id=? order by id
          """, String.class, warehouseId))
          .containsExactlyInAnyOrder("ACTIVE", "SUPERSEDED");
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void remoteSuccessPrecedesOnlyRetrySafeTransactionalApply() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));

    verifyNoInteractions(dependencies);
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
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);

    ArgumentCaptor<UUID> keys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2)).acquireLease(
        keys.capture(), eq(fixture.rentalItemId()), eq(7L),
        eq("MAINTENANCE_REPAIR"), eq(fixture.repairId().toString()));
    assertThat(keys.getAllValues()).hasSize(2).allMatch(keys.getAllValues().getFirst()::equals);
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
    UUID chairEquipmentId = UUID.fromString("52000000-0000-4000-8000-000000000001");
    UUID tableEquipmentId = UUID.fromString("52000000-0000-4000-8000-000000000002");
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, "FURNITURE", CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true, false,
        null, List.of(), null, List.of());
    CatalogNodeInput chair = new CatalogNodeInput(
        chairMaterialId, "CHAIR", CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(chairEquipmentId, "CHAIR", "Chair"),
        "piece", "100.00", 0, true, false, false, false,
        null, List.of(), null, List.of());
    CatalogNodeInput table = new CatalogNodeInput(
        tableMaterialId, "TABLE", CatalogNodeType.MATERIAL, "Table", true, categoryId,
        false, new FurnitureEquipmentReference(tableEquipmentId, "TABLE", "Table"),
        "piece", "100.00", 0, true, false, false, false,
        null, List.of(), null, List.of());
    CatalogVersionResponse changed = service.changeCatalog(
        catalogId, new ChangeCatalogRequest(0L, List.of(category, chair, table), List.of()));
    service.activateCatalog(
        UUID.randomUUID(), UUID.randomUUID(), catalogId,
        new VersionCommand(changed.version()));
    clearInvocations(dependencies);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));

    CatalogNodeSnapshot forgedChairSnapshot = new CatalogNodeSnapshot(
        catalogId,
        chairMaterialId,
        "FORGED",
        CatalogNodeType.MATERIAL,
        "Forged furniture",
        "piece",
        "1.00",
        0,
        null,
        new FurnitureEquipmentReference(UUID.randomUUID(), "FORGED", "Forged"));
    CatalogNodeSnapshot forgedTableSnapshot = new CatalogNodeSnapshot(
        catalogId,
        tableMaterialId,
        "FORGED_TABLE",
        CatalogNodeType.MATERIAL,
        "Forged table",
        "piece",
        "1.00",
        0,
        null,
        new FurnitureEquipmentReference(UUID.randomUUID(), "FORGED_TABLE", "Forged table"));
    PlanStageInput planStage = stage();

    assertThatThrownBy(() -> service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            List.of(new EstimateLineInput(
                UUID.randomUUID(), forgedChairSnapshot, "Replace chairs", "1.5", "100.00",
                null, List.of())),
            List.of(planStage),
            List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("Furniture quantity must be a whole number");

    List<EstimateLineInput> lines = List.of(
        new EstimateLineInput(
            UUID.randomUUID(), forgedTableSnapshot, "Replace table", "1", "100.00",
            null, List.of()),
        new EstimateLineInput(
            UUID.randomUUID(), forgedChairSnapshot, "Replace chairs", "2", "100.00",
            null, List.of()),
        new EstimateLineInput(
            UUID.randomUUID(), forgedTableSnapshot, "Replace more tables", "3", "100.00",
            null, List.of()));
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
    var completed = service.completeEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.response().id(),
        new CompleteEstimateRequest(created.response().version()));
    UUID repairId = completed.response().repair().id();
    UUID estimateId = created.response().id();

    List<EstimateLineInput> changedFurniture = new ArrayList<>(lines);
    EstimateLineInput first = changedFurniture.getFirst();
    changedFurniture.set(0, new EstimateLineInput(
        first.id(), first.catalogSnapshot(), first.description(), "2", first.unitPrice(),
        first.comment(), first.mediaReferences()));
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
    EstimateLineInput lastTable = overflowingFurniture.getLast();
    overflowingFurniture.set(0, new EstimateLineInput(
        firstTable.id(), firstTable.catalogSnapshot(), firstTable.description(),
        Long.toString(Long.MAX_VALUE), firstTable.unitPrice(), firstTable.comment(),
        firstTable.mediaReferences()));
    overflowingFurniture.set(2, new EstimateLineInput(
        lastTable.id(), lastTable.catalogSnapshot(), lastTable.description(), "1",
        lastTable.unitPrice(), lastTable.comment(), lastTable.mediaReferences()));
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
            rentalItemId, 7, warehouseId, "FREE"));
    when(dependencies.acquireLease(
        any(), eq(rentalItemId), eq(7L), eq("MAINTENANCE_ESTIMATE"), eq(estimateId.toString())))
        .thenReturn(new MaintenanceDependencyGateway.LeaseSnapshot(
            leaseId, 0, rentalItemId, "MAINTENANCE_ESTIMATE", estimateId, 17,
            OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(15)));
    List<MaintenanceDependencyGateway.FurnitureLoss> losses = List.of(
        new MaintenanceDependencyGateway.FurnitureLoss(chairEquipmentId, "CHAIR", 2),
        new MaintenanceDependencyGateway.FurnitureLoss(tableEquipmentId, "TABLE", 4));
    when(dependencies.fencedStatus(
        any(), eq(rentalItemId), eq(warehouseId), eq(7L), eq(leaseId), eq(17L),
        eq("MAINTENANCE_ESTIMATE"), eq(estimateId.toString()), eq("QUEUE_TO_REPAIR"),
        eq(false), eq(estimateId), eq(losses)))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            rentalItemId, 8, warehouseId, "REPAIR"));

    assertThat(service.reconcileOneTask()).isTrue();

    verify(dependencies).fencedStatus(
        any(), eq(rentalItemId), eq(warehouseId), eq(7L), eq(leaseId), eq(17L),
        eq("MAINTENANCE_ESTIMATE"), eq(estimateId.toString()), eq("QUEUE_TO_REPAIR"),
        eq(false), eq(estimateId), eq(losses));
    assertThat(repairs.findById(repairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, String.class, repairId)).isEqualTo("CONFIRMED");
  }

  @Test
  void estimateRejectsUnlinkedFurnitureFromAnAlreadyActiveLegacyCatalog() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "e".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    when(dependencies.ensureFurnitureEquipment("CHAIR", "Chair"))
        .thenReturn(new MaintenanceDependencyGateway.FurnitureEquipmentSnapshot(
            equipmentId, "CHAIR", "Chair"));
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, "FURNITURE", CatalogNodeType.CATEGORY, "Furniture", true, null,
        false, null, null, null, 0, false, false, true, false,
        null, List.of(), null, List.of());
    CatalogNodeInput material = new CatalogNodeInput(
        materialId, "CHAIR", CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, null, "piece", "100.00", 0, true, false, false, false,
        null, List.of(), null, List.of());
    service.changeCatalog(
        catalogId, new ChangeCatalogRequest(0L, List.of(category, material), List.of()));
    // V4 backfills the FURNITURE marker on catalogs that were active before mappings existed.
    jdbc.update("""
        update catalog_node
        set furniture_equipment_id=null,
            furniture_equipment_code=null,
            furniture_equipment_name=null
        where catalog_version_id=? and node_id=?
        """, catalogId, materialId);
    jdbc.update("update catalog_version set state='ACTIVE' where id=?", catalogId);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    CatalogNodeSnapshot submitted = new CatalogNodeSnapshot(
        catalogId, materialId, "CHAIR", CatalogNodeType.MATERIAL, "Chair", "piece",
        "100.00", 0, null, null);

    assertThatThrownBy(() -> service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            List.of(new EstimateLineInput(
                UUID.randomUUID(), submitted, "Replace chair", "1", "100.00", null,
                List.of())),
            List.of(stage()),
            List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("Furniture material must be linked");
  }

  @Test
  void catalogRejectsConflictingSnapshotsForOneFurnitureEquipmentId() {
    UUID catalogId = insertDraftCatalog(UUID.randomUUID(), "c".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, "FURNITURE", CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true, false,
        null, List.of(), null, List.of());
    CatalogNodeInput chair = new CatalogNodeInput(
        UUID.randomUUID(), "CHAIR", CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "CHAIR", "Chair"),
        "piece", "100.00", 0, true, false, false, false,
        null, List.of(), null, List.of());
    CatalogNodeInput conflicting = new CatalogNodeInput(
        UUID.randomUUID(), "SEAT", CatalogNodeType.MATERIAL, "Seat", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "SEAT", "Seat"),
        "piece", "100.00", 0, true, false, false, false,
        null, List.of(), null, List.of());

    assertThatThrownBy(() -> service.changeCatalog(
        catalogId,
        new ChangeCatalogRequest(
            0L, List.of(category, chair, conflicting), List.of())))
        .isInstanceOf(
            dev.buhanzaz.rwms.maintenance.service.MaintenanceValidationException.class)
        .hasMessageContaining("one canonical code and name");
  }

  @Test
  void preV4FurnitureSnapshotCannotQueueWithoutTheCanonicalEquipmentLink() {
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    UUID catalogId = insertDraftCatalog(warehouseId, "d".repeat(64));
    UUID categoryId = UUID.randomUUID();
    UUID materialId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    CatalogNodeInput category = new CatalogNodeInput(
        categoryId, "FURNITURE", CatalogNodeType.CATEGORY, "Furniture", true, null,
        true, null, null, null, 0, false, false, true, false,
        null, List.of(), null, List.of());
    CatalogNodeInput material = new CatalogNodeInput(
        materialId, "CHAIR", CatalogNodeType.MATERIAL, "Chair", true, categoryId,
        false, new FurnitureEquipmentReference(equipmentId, "CHAIR", "Chair"),
        "piece", "100.00", 0, true, false, false, false,
        null, List.of(), null, List.of());
    CatalogVersionResponse changed = service.changeCatalog(
        catalogId, new ChangeCatalogRequest(0L, List.of(category, material), List.of()));
    service.activateCatalog(
        UUID.randomUUID(), UUID.randomUUID(), catalogId,
        new VersionCommand(changed.version()));
    clearInvocations(dependencies);
    rentalItemFacts.saveAndFlush(
        RentalItemFactProjection.create(rentalItemId, warehouseId, "FREE", 7));
    CatalogNodeSnapshot submitted = new CatalogNodeSnapshot(
        catalogId, materialId, "CHAIR", CatalogNodeType.MATERIAL, "Chair", "piece",
        "100.00", 0, null, null);
    var created = service.createEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEstimateRequest(
            warehouseId,
            rentalItemId,
            LocalDate.of(2026, 7, 19),
            null,
            List.of(new EstimateLineInput(
                UUID.randomUUID(), submitted, "Replace chair", "1", "100.00", null,
                List.of())),
            List.of(stage()),
            List.of()));
    var completed = service.completeEstimate(
        UUID.randomUUID(),
        UUID.randomUUID(),
        created.response().id(),
        new CompleteEstimateRequest(created.response().version()));
    UUID repairId = completed.response().repair().id();
    jdbc.update("""
        update catalog_node
        set furniture_equipment_id=null,
            furniture_equipment_code=null,
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
    assertThat(service.reconcileOneTask()).isTrue();

    assertThat(repairs.findById(repairId).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
    assertThat(jdbc.queryForMap("""
        select state,last_error_code from integration_reconciliation
        where repair_id=? and operation_type='QUEUE_REPAIR'
        """, repairId))
        .containsEntry("state", "RETRY_PENDING")
        .containsEntry("last_error_code", "MaintenanceValidationException");
    verifyNoInteractions(dependencies);
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
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), anyList()))
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
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(), 0, "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, entryId, 0))));

    assertThat(service.reconcileOneTask()).isTrue();
    assertThat(jdbc.queryForObject("""
        select state from integration_reconciliation
        where repair_id=? and operation_type='REGISTER_TASK'
        """, String.class, fixture.repairId())).isEqualTo("CONFIRMED");
    verify(dependencies, times(2)).registerTask(
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), anyList());
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
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), anyList()))
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
        eq(stableKey), eq(fixture.externalTaskId()), eq(fixture.warehouseId()),
        eq(fixture.rentalItemId()), anyList());
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
            0L, List.of(stage()), List.of(new MediaReferenceInput(mediaId, 1L))));

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
        new UpdateRepairPlanRequest(0L, List.of(stage()), List.of())))
        .isInstanceOf(MaintenanceConflictException.class)
        .extracting(exception -> ((MaintenanceConflictException) exception).code())
        .isEqualTo("MAINTENANCE_VERSION_CONFLICT");
    assertThat(service.repair(fixture.repairId()).mediaReferences())
        .containsExactly(new MediaReferenceInput(mediaId, 1L));
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
  void completedReworkReturnsSourceToPendingAndAcceptCascadesWithoutDuplicateStatus() {
    ReworkFixture fixture = createCompletedRework();

    long childVersion = repairs.findById(fixture.child().repairId()).orElseThrow().getVersion();
    service.accept(
        UUID.randomUUID(), UUID.randomUUID(), fixture.child().repairId(),
        new RepairDecisionRequest(childVersion, "accepted after rework"));

    assertThat(repairs.findById(fixture.child().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertThat(repairs.findById(fixture.source().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
    assertSinglePendingAcceptanceIntent(fixture.source().repairId());
  }

  @Test
  void completedReworkReturnsSourceToPendingAndWriteOffCascadesWithoutDuplicateStatus() {
    ReworkFixture fixture = createCompletedRework();

    long childVersion = repairs.findById(fixture.child().repairId()).orElseThrow().getVersion();
    service.writeOff(
        UUID.randomUUID(), UUID.randomUUID(), fixture.child().repairId(),
        new WriteOffRepairRequest(childVersion, "not repairable", "reviewed"));

    assertThat(repairs.findById(fixture.child().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.WRITTEN_OFF);
    assertThat(repairs.findById(fixture.source().repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.WRITTEN_OFF);
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
  void reworkQueueRejectsExpiredAndFourMinuteLeaseButAcceptsFiveMinutePlus() {
    RepairFixture source = createQueuedPendingAcceptanceRepair();
    RepairFixture child = createDraftRework(source);

    setLeaseExpiry(source.repairId(), "-1 second");
    assertQueueLeaseConflict(child);
    setLeaseExpiry(source.repairId(), "4 minutes 59 seconds");
    assertQueueLeaseConflict(child);
    setLeaseExpiry(source.repairId(), "6 minutes");

    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), child.repairId(), new VersionCommand(0L));

    assertThat(repairs.findById(child.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.QUEUED);
    assertThat(repairs.findById(source.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.IN_REWORK);
  }

  @Test
  void terminalDecisionDoesNotCommitUntilLeaseHasFiveMinuteWindow() {
    RepairFixture fixture = createQueuedPendingAcceptanceRepair();
    long version = repairs.findById(fixture.repairId()).orElseThrow().getVersion();
    setLeaseExpiry(fixture.repairId(), "4 minutes 59 seconds");

    assertThatThrownBy(() -> service.accept(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(),
        new RepairDecisionRequest(version, "too close")))
        .isInstanceOf(MaintenanceConflictException.class);
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.PENDING);

    setLeaseExpiry(fixture.repairId(), "6 minutes");
    service.accept(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(),
        new RepairDecisionRequest(version, "fresh lease"));
    assertThat(repairs.findById(fixture.repairId()).orElseThrow().getAcceptanceState())
        .isEqualTo(RepairAcceptanceState.ACCEPTED);
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
                  new CreateReworkRequest(
                      expectedVersion, "rework", List.of(stage()), List.of()));
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
                  new RepairDecisionRequest(expectedVersion, "accepted"));
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

  private RegisteredRepairFixture createRegisteredPrimaryRepair() {
    RepairFixture fixture = createDirectRepair();
    service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), fixture.repairId(), new VersionCommand(0L));
    stubQueueDependencies(fixture);
    assertThat(service.reconcileOneTask()).isTrue();
    return registerQueuedRepair(fixture);
  }

  private RegisteredRepairFixture registerQueuedRepair(RepairFixture fixture) {
    UUID entryId = UUID.randomUUID();
    when(dependencies.registerTask(any(), eq(fixture.externalTaskId()),
        eq(fixture.warehouseId()), eq(fixture.rentalItemId()), anyList()))
        .thenReturn(new MaintenanceDependencyGateway.TaskSnapshot(
            fixture.externalTaskId(), 0, "ACTIVE",
            List.of(new MaintenanceDependencyGateway.TaskStageSnapshot(0, entryId, 0))));
    assertThat(service.reconcileOneTask()).isTrue();
    return new RegisteredRepairFixture(fixture, entryId);
  }

  private RepairFixture createDraftRework(RepairFixture source) {
    long sourceVersion = repairs.findById(source.repairId()).orElseThrow().getVersion();
    var created = service.createRework(
        UUID.randomUUID(), UUID.randomUUID(), source.repairId(),
        new CreateReworkRequest(sourceVersion, "corrective work", List.of(stage()), List.of()));
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

  private void assertQueueLeaseConflict(RepairFixture child) {
    assertThatThrownBy(() -> service.queueRepair(
        UUID.randomUUID(), UUID.randomUUID(), child.repairId(), new VersionCommand(0L)))
        .isInstanceOf(MaintenanceConflictException.class);
    assertThat(repairs.findById(child.repairId()).orElseThrow().getExecutionState())
        .isEqualTo(RepairExecutionState.DRAFT);
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
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, "FREE", rentalItemVersion));
    var result = service.createDirectRepair(
        UUID.randomUUID(), UUID.randomUUID(),
        new CreateDirectRepairRequest(
            warehouseId, rentalItemId, LocalDate.of(2026, 7, 17), null,
            List.of(stage()), List.of()));
    return new RepairFixture(
        result.response().id(), result.response().plan().stages().getFirst().taskSync().externalTaskId(),
        warehouseId, rentalItemId);
  }

  private void stubQueueDependencies(RepairFixture fixture) {
    UUID leaseId = UUID.randomUUID();
    when(dependencies.getRentalItemSnapshot(fixture.rentalItemId()))
        .thenReturn(new MaintenanceDependencyGateway.AssetSnapshot(
            fixture.rentalItemId(), 7, fixture.warehouseId(), "FREE"));
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
            fixture.rentalItemId(), 8, fixture.warehouseId(), "REPAIR"));
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

  private static CatalogNodeInput catalogWork(
      UUID nodeId, String comment, List<MediaReferenceInput> mediaReferences) {
    return new CatalogNodeInput(
        nodeId,
        "SHARED_WORK",
        CatalogNodeType.WORK,
        "Shared work",
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
        false,
        null,
        List.of(),
        comment,
        mediaReferences);
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
  private record RegisteredRepairFixture(RepairFixture repair, UUID queueEntryId) {}
  private record QueuedReworkFixture(
      RepairFixture source, RepairFixture child, UUID queueEntryId) {}
  private record ReworkFixture(RepairFixture source, RepairFixture child) {}
}
