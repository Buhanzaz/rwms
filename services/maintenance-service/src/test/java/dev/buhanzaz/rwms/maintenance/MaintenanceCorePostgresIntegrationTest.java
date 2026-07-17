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
import dev.buhanzaz.rwms.maintenance.service.MaintenanceConflictException;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceIdempotencyStore;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceReconciliationReviewService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceReconciliationStore;
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
    clearInvocations(eventFacts);
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
    UUID warehouseId = UUID.randomUUID();
    UUID rentalItemId = UUID.randomUUID();
    rentalItemFacts.saveAndFlush(RentalItemFactProjection.create(
        rentalItemId, warehouseId, "FREE", 7));
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
