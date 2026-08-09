package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnPickupRequest;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import dev.buhanzaz.rwms.logistics.service.ReturnRegistrationProcessor;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
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
 * Exercises bounded, PostgreSQL-backed external-attempt leases through the actual JPA repository.
 * In particular, these tests prove the generated pessimistic JPQL claim query skips another
 * transaction's oldest lock instead of waiting for the locked row or returning it twice.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "rwms.logistics.owner-proof.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsExternalAttemptClaimServiceIntegrationTest {
  private static final String PAGE_PREFIX = "PAGE_";
  private static final String LOCK_PREFIX = "LOCK_";
  private static final String RACE_PREFIX = "RACE_";
  private static final String LEASE_PREFIX = "LEASE_";
  private static final String STARVATION_PREFIX = "STARVATION_";
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000002001");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000002002");
  private static final UUID ASSET = UUID.fromString("00000000-0000-0000-0000-000000002003");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired JdbcTemplate jdbc;
  @Autowired DataSource dataSource;
  @Autowired LogisticsExternalAttemptClaimService claims;
  @Autowired LogisticsDocumentService documents;
  @Autowired ReturnRegistrationProcessor registrationProcessor;

  @MockitoBean LogisticsDependencyGateway dependencies;

  private ExecutorService executor;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
    executor = Executors.newFixedThreadPool(4);
    org.mockito.Mockito.reset(dependencies);
  }

  @AfterEach
  void stopWorkers() {
    executor.shutdownNow();
  }

  @Test
  void limitsATenThousandRowDueSetToAStableClaimPage() {
    UUID documentId = insertDocument();
    OffsetDateTime base = OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5);
    List<AttemptSeed> seeds = new ArrayList<>();
    for (int index = 0; index < 10_000; index++) {
      // PostgreSQL persists timestamptz at microsecond precision, so every stable test key is 1 ms
      // apart.
      seeds.add(
          new AttemptSeed(
              documentId,
              PAGE_PREFIX + String.format("%05d", index),
              base.plusNanos(index * 1_000_000L)));
    }
    insertAttempts(seeds);

    List<LogisticsExternalAttemptClaimService.Claim> first =
        claims.claimDueByOperationPrefix(
            LogisticsExternalAttemptClaimService.Owner.SHIPMENT, PAGE_PREFIX, 10_000);

    assertThat(first).hasSize(64);
    assertThat(operationTypes(first)).containsExactlyElementsOf(pageOperations(0));
    first.forEach(claims::defer);

    List<LogisticsExternalAttemptClaimService.Claim> second =
        claims.claimDueByOperationPrefix(
            LogisticsExternalAttemptClaimService.Owner.SHIPMENT, PAGE_PREFIX, 10_000);

    assertThat(second).hasSize(64);
    assertThat(operationTypes(second)).containsExactlyElementsOf(pageOperations(64));
    assertThat(operationIds(first)).doesNotContainAnyElementsOf(operationIds(second));
  }

  @Test
  void literalOperationPrefixCannotClaimAnUnderscoreWildcardNeighbor() {
    UUID documentId = insertDocument();
    AttemptSeed shipment = new AttemptSeed(documentId, "SHIPMENT_000", dueTime(0));
    AttemptSeed otherNamespace = new AttemptSeed(documentId, "SHIPMENTX000", dueTime(1));
    insertAttempts(List.of(shipment, otherNamespace));

    List<LogisticsExternalAttemptClaimService.Claim> claimed =
        claims.claimDueByOperationPrefix(
            LogisticsExternalAttemptClaimService.Owner.SHIPMENT, "SHIPMENT_", 2);

    assertThat(claimed).extracting(LogisticsExternalAttemptClaimService.Claim::attemptId)
        .containsExactly(shipment.attemptId());
  }

  @Test
  void skipsAnExternallyHeldOldestRowWithinTheBoundedClaimTimeoutThenClaimsItAfterRelease()
      throws Exception {
    UUID documentId = insertDocument();
    AttemptSeed oldest = new AttemptSeed(documentId, LOCK_PREFIX + "000", dueTime(0));
    AttemptSeed next = new AttemptSeed(documentId, LOCK_PREFIX + "001", dueTime(1));
    insertAttempts(List.of(oldest, next));

    CountDownLatch locked = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    Future<?> holder =
        executor.submit(
            () -> {
              try (Connection connection = dataSource.getConnection()) {
                connection.setAutoCommit(false);
                try (PreparedStatement statement =
                    connection.prepareStatement(
                        "select id from logistics_external_attempt where id=? for update")) {
                  statement.setObject(1, oldest.attemptId());
                  statement.executeQuery();
                  locked.countDown();
                  if (!release.await(5, TimeUnit.SECONDS)) {
                    throw new AssertionError("Test did not release the held attempt row");
                  }
                  connection.commit();
                }
              }
              return null;
            });
    assertThat(locked.await(2, TimeUnit.SECONDS)).isTrue();

    LogisticsExternalAttemptClaimService.Claim whileLocked =
        executor
            .submit(
                () ->
                    claims
                        .claimDueByOperationPrefix(
                            LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LOCK_PREFIX, 1)
                        .getFirst())
            .get(1, TimeUnit.SECONDS);

    assertThat(whileLocked.attemptId()).isEqualTo(next.attemptId());
    release.countDown();
    holder.get(2, TimeUnit.SECONDS);
    claims.defer(whileLocked);

    LogisticsExternalAttemptClaimService.Claim afterRelease =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LOCK_PREFIX, 1)
            .getFirst();

    assertThat(afterRelease.attemptId()).isEqualTo(oldest.attemptId());
    assertThat(afterRelease.operationId()).isNotEqualTo(whileLocked.operationId());
  }

  @Test
  void concurrentClaimersNeverReceiveTheSameAttemptAndAStaleLeaseCannotComplete() throws Exception {
    UUID documentId = insertDocument();
    insertAttempts(
        List.of(
            new AttemptSeed(documentId, RACE_PREFIX + "000", dueTime(0)),
            new AttemptSeed(documentId, RACE_PREFIX + "001", dueTime(1))));

    Future<LogisticsExternalAttemptClaimService.Claim> firstFuture =
        executor.submit(
            () ->
                claims
                    .claimDueByOperationPrefix(
                        LogisticsExternalAttemptClaimService.Owner.SHIPMENT, RACE_PREFIX, 1)
                    .getFirst());
    Future<LogisticsExternalAttemptClaimService.Claim> secondFuture =
        executor.submit(
            () ->
                claims
                    .claimDueByOperationPrefix(
                        LogisticsExternalAttemptClaimService.Owner.SHIPMENT, RACE_PREFIX, 1)
                    .getFirst());

    LogisticsExternalAttemptClaimService.Claim first = firstFuture.get(2, TimeUnit.SECONDS);
    LogisticsExternalAttemptClaimService.Claim second = secondFuture.get(2, TimeUnit.SECONDS);

    assertThat(first.attemptId()).isNotEqualTo(second.attemptId());
    assertThat(first.operationId()).isNotEqualTo(second.operationId());
    assertThat(operationIds(List.of(first, second))).hasSize(2);
  }

  @Test
  void expiredLeaseGetsANewerFenceAndRejectsTheStaleWorkerWithoutChangingItsResult() {
    UUID documentId = insertDocument();
    AttemptSeed attempt = new AttemptSeed(documentId, LEASE_PREFIX + "000", dueTime(0));
    insertAttempts(List.of(attempt));

    LogisticsExternalAttemptClaimService.Claim first =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LEASE_PREFIX, 1)
            .getFirst();
    jdbc.update(
        "update logistics_external_attempt set lease_expires_at=clock_timestamp() - interval '1 second' where id=?",
        first.attemptId());

    LogisticsExternalAttemptClaimService.Claim second =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LEASE_PREFIX, 1)
            .getFirst();

    assertThat(second.leaseFence()).isGreaterThan(first.leaseFence());
    assertThat(second.leaseToken()).isNotEqualTo(first.leaseToken());
    assertThat(second.rowVersion()).isGreaterThan(first.rowVersion());
    assertThatThrownBy(() -> claims.requireCurrentAttempt(first))
        .isInstanceOf(LogisticsExternalAttemptClaimService.StaleClaimException.class);
    assertThat(
            jdbc.queryForObject(
                "select result from logistics_external_attempt where id=?", String.class, first.attemptId()))
        .isEqualTo("PENDING");
    assertThat(claims.requireCurrentAttempt(second).getOperationId()).isEqualTo(second.operationId());
  }

  @Test
  void databaseTimestampDefinesDueSelectionLeaseExpiryCurrentValidationAndDefer() {
    UUID documentId = insertDocument();
    AttemptSeed attempt = new AttemptSeed(documentId, LEASE_PREFIX + "DB_TIME", dueTime(0));
    insertAttempts(List.of(attempt));
    jdbc.update(
        "update logistics_external_attempt set next_attempt_at=clock_timestamp() + interval '1 minute' where id=?",
        attempt.attemptId());

    assertThat(
            claims.claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LEASE_PREFIX, 1))
        .isEmpty();
    jdbc.update(
        "update logistics_external_attempt set next_attempt_at=clock_timestamp() where id=?",
        attempt.attemptId());

    LogisticsExternalAttemptClaimService.Claim claim =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LEASE_PREFIX, 1)
            .getFirst();
    assertThat(
            jdbc.queryForObject(
                "select lease_expires_at > clock_timestamp() from logistics_external_attempt where id=?",
                Boolean.class,
                claim.attemptId()))
        .isTrue();
    assertThat(claims.requireCurrentAttempt(claim).getId()).isEqualTo(claim.attemptId());

    claims.defer(claim);

    assertThat(
            jdbc.queryForObject(
                """
                select lease_token is null
                  and lease_expires_at is null
                  and next_attempt_at > clock_timestamp()
                from logistics_external_attempt
                where id=?
                """,
                Boolean.class,
                claim.attemptId()))
        .isTrue();
  }

  @Test
  void claimCarriesThePersistedPostFlushVersionAndRejectsThePreClaimVersion() {
    UUID documentId = insertDocument();
    AttemptSeed attempt = new AttemptSeed(documentId, LEASE_PREFIX + "VERSION", dueTime(0));
    insertAttempts(List.of(attempt));
    long preClaimVersion =
        jdbc.queryForObject(
            "select row_version from logistics_external_attempt where id=?", Long.class, attempt.attemptId());

    LogisticsExternalAttemptClaimService.Claim claim =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, LEASE_PREFIX, 1)
            .getFirst();

    long persistedVersion =
        jdbc.queryForObject(
            "select row_version from logistics_external_attempt where id=?", Long.class, claim.attemptId());
    assertThat(claim.rowVersion()).isEqualTo(persistedVersion);
    assertThat(claim.rowVersion()).isGreaterThan(preClaimVersion);
    LogisticsExternalAttemptClaimService.Claim preFlushCapability =
        new LogisticsExternalAttemptClaimService.Claim(
            claim.owner(),
            claim.attemptId(),
            claim.operationId(),
            claim.leaseToken(),
            claim.leaseFence(),
            preClaimVersion,
            claim.requestSha256());

    assertThatThrownBy(() -> claims.requireCurrentAttempt(preFlushCapability))
        .isInstanceOf(LogisticsExternalAttemptClaimService.StaleClaimException.class);
    LogisticsExternalAttemptClaimService.Claim wrongRequestCapability =
        new LogisticsExternalAttemptClaimService.Claim(
            claim.owner(),
            claim.attemptId(),
            claim.operationId(),
            claim.leaseToken(),
            claim.leaseFence(),
            claim.rowVersion(),
            "b".repeat(64));

    assertThatThrownBy(() -> claims.requireCurrentAttempt(wrongRequestCapability))
        .isInstanceOf(LogisticsExternalAttemptClaimService.StaleClaimException.class);
    assertThat(claims.requireCurrentAttempt(claim).getRowVersion()).isEqualTo(persistedVersion);
  }

  @Test
  void deferOfAnOldestBlockedClaimAllowsANewerAttemptToMakeProgress() {
    UUID documentId = insertDocument();
    AttemptSeed oldest = new AttemptSeed(documentId, STARVATION_PREFIX + "000", dueTime(0));
    AttemptSeed newer = new AttemptSeed(documentId, STARVATION_PREFIX + "001", dueTime(1));
    insertAttempts(List.of(oldest, newer));

    LogisticsExternalAttemptClaimService.Claim blocked =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, STARVATION_PREFIX, 1)
            .getFirst();
    claims.defer(blocked);

    LogisticsExternalAttemptClaimService.Claim progressed =
        claims
            .claimDueByOperationPrefix(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, STARVATION_PREFIX, 1)
            .getFirst();

    assertThat(blocked.attemptId()).isEqualTo(oldest.attemptId());
    assertThat(progressed.attemptId()).isEqualTo(newer.attemptId());
  }

  @Test
  void slowDependencyDoesNotHoldTheClaimRowTransactionOpen() throws Exception {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 4, "Tenant"))));
    UUID documentId = created.response().id();
    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        UUID.randomUUID(),
        documentId,
        created.response().version(),
        new ReturnPickupRequest("Driver", LocalDate.parse("2026-08-01")));
    LogisticsExternalAttemptClaimService.Claim claim =
        claims
            .claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                List.of("RETURN_WAREHOUSE_IDENTITY"))
            .orElseThrow();

    CountDownLatch dependencyStarted = new CountDownLatch(1);
    CountDownLatch releaseDependency = new CountDownLatch(1);
    org.mockito.Mockito.when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenAnswer(
            ignored -> {
              dependencyStarted.countDown();
              if (!releaseDependency.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release the dependency");
              }
              return new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 0, true, "UTC");
            });

    Future<?> worker = executor.submit(() -> registrationProcessor.process(claim));
    assertThat(dependencyStarted.await(2, TimeUnit.SECONDS)).isTrue();

    try (Connection connection = dataSource.getConnection();
        PreparedStatement statement =
            connection.prepareStatement(
                "select id from logistics_external_attempt where id=? for update nowait")) {
      connection.setAutoCommit(false);
      statement.setObject(1, claim.attemptId());
      assertThat(statement.executeQuery().next()).isTrue();
      connection.rollback();
    } finally {
      releaseDependency.countDown();
    }
    worker.get(2, TimeUnit.SECONDS);
  }

  @Test
  void staleAndDuplicateProcessorCompletionCannotOverwriteTheCurrentFencedLease() {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 4, "Tenant"))));
    UUID documentId = created.response().id();
    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        UUID.randomUUID(),
        documentId,
        created.response().version(),
        new ReturnPickupRequest("Driver", LocalDate.parse("2026-08-01")));
    LogisticsExternalAttemptClaimService.Claim stale =
        claims
            .claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                List.of("RETURN_WAREHOUSE_IDENTITY"))
            .orElseThrow();
    jdbc.update(
        "update logistics_external_attempt set lease_expires_at=clock_timestamp() - interval '1 second' where id=?",
        stale.attemptId());
    LogisticsExternalAttemptClaimService.Claim current =
        claims
            .claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                List.of("RETURN_WAREHOUSE_IDENTITY"))
            .orElseThrow();
    assertThat(current.rowVersion()).isGreaterThan(stale.rowVersion());
    assertThat(current.leaseFence()).isGreaterThan(stale.leaseFence());
    org.mockito.Mockito.reset(dependencies);
    org.mockito.Mockito.when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 0, true, "UTC"));

    registrationProcessor.process(stale);
    org.mockito.Mockito.verifyNoInteractions(dependencies);
    assertThat(
            jdbc.queryForObject(
                "select result from logistics_external_attempt where id=?", String.class, stale.attemptId()))
        .isEqualTo("PENDING");

    registrationProcessor.process(current);
    registrationProcessor.process(current);

    org.mockito.Mockito.verify(dependencies, org.mockito.Mockito.times(1))
        .readWarehouseIdentity(WAREHOUSE);
    assertThat(
            jdbc.queryForObject(
                "select result from logistics_external_attempt where id=?", String.class, current.attemptId()))
        .isEqualTo("CONFIRMED");
  }

  @Test
  void staleDependencyFailureCannotRecordARetryAgainstANewerFencedLease() throws Exception {
    var created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateReturnRequest(
                WAREHOUSE, List.of(new ReturnLineRequest(ASSET, 4, "Tenant"))));
    UUID documentId = created.response().id();
    documents.registerReturn(
        SUBJECT,
        UUID.randomUUID(),
        UUID.randomUUID(),
        documentId,
        created.response().version(),
        new ReturnPickupRequest("Driver", LocalDate.parse("2026-08-01")));
    LogisticsExternalAttemptClaimService.Claim stale =
        claims
            .claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                List.of("RETURN_WAREHOUSE_IDENTITY"))
            .orElseThrow();
    CountDownLatch dependencyStarted = new CountDownLatch(1);
    CountDownLatch releaseFailure = new CountDownLatch(1);
    org.mockito.Mockito.when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenAnswer(
            ignored -> {
              dependencyStarted.countDown();
              if (!releaseFailure.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Test did not release the dependency failure");
              }
              throw new LogisticsDependencyException(
                  LogisticsDependencyException.FailureKind.TRANSIENT, "timeout");
            });
    Future<?> worker = executor.submit(() -> registrationProcessor.process(stale));
    assertThat(dependencyStarted.await(2, TimeUnit.SECONDS)).isTrue();
    jdbc.update(
        "update logistics_external_attempt set lease_expires_at=clock_timestamp() - interval '1 second' where id=?",
        stale.attemptId());
    LogisticsExternalAttemptClaimService.Claim current =
        claims
            .claimNext(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                List.of("RETURN_WAREHOUSE_IDENTITY"))
            .orElseThrow();

    releaseFailure.countDown();
    worker.get(2, TimeUnit.SECONDS);

    assertThat(
            jdbc.queryForObject(
                "select result from logistics_external_attempt where id=?", String.class, stale.attemptId()))
        .isEqualTo("PENDING");
    assertThat(
            jdbc.queryForObject(
                "select retry_count from logistics_external_attempt where id=?", Integer.class, stale.attemptId()))
        .isZero();
    assertThat(claims.requireCurrentAttempt(current).getLeaseFence())
        .isEqualTo(current.leaseFence());
  }

  private UUID insertDocument() {
    UUID documentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,requested_by_subject_id,correlation_id,
          created_at,updated_at)
        values (?,0,'RETURN','REGISTERING',?,?,?,clock_timestamp(),clock_timestamp())
        """,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID());
    return documentId;
  }

  private void insertAttempts(List<AttemptSeed> attempts) {
    jdbc.batchUpdate(
        """
        insert into logistics_external_attempt(
          id,document_id,line_id,operation_id,target_service,operation_type,request_sha256,
          result,retry_count,next_attempt_at,correlation_id,created_at)
        values (?,?,null,?,'ASSET',?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          'PENDING',0,?,?,?)
        """,
        attempts,
        500,
        (statement, attempt) -> {
          statement.setObject(1, attempt.attemptId());
          statement.setObject(2, attempt.documentId());
          statement.setObject(3, attempt.operationId());
          statement.setString(4, attempt.operationType());
          statement.setObject(5, attempt.dueAt());
          statement.setObject(6, UUID.randomUUID());
          statement.setObject(7, attempt.dueAt());
        });
  }

  private List<String> operationTypes(List<LogisticsExternalAttemptClaimService.Claim> claims) {
    return claims.stream()
        .map(
            claim ->
                jdbc.queryForObject(
                    "select operation_type from logistics_external_attempt where id=?",
                    String.class,
                    claim.attemptId()))
        .toList();
  }

  private static Set<UUID> operationIds(List<LogisticsExternalAttemptClaimService.Claim> claims) {
    Set<UUID> operationIds = new HashSet<>();
    for (LogisticsExternalAttemptClaimService.Claim claim : claims) {
      operationIds.add(claim.operationId());
    }
    return operationIds;
  }

  private static List<String> pageOperations(int first) {
    List<String> operations = new ArrayList<>();
    for (int index = first; index < first + 64; index++) {
      operations.add(PAGE_PREFIX + String.format("%05d", index));
    }
    return operations;
  }

  private static OffsetDateTime dueTime(int sequence) {
    return OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(5).plusNanos(sequence * 1_000_000L);
  }

  /** Minimal immutable source row used to seed a durable claim candidate. */
  private record AttemptSeed(UUID documentId, String operationType, OffsetDateTime dueAt) {
    UUID attemptId() {
      return UUID.nameUUIDFromBytes((documentId + ":" + operationType).getBytes());
    }

    UUID operationId() {
      return UUID.nameUUIDFromBytes(("operation:" + documentId + ":" + operationType).getBytes());
    }
  }
}
