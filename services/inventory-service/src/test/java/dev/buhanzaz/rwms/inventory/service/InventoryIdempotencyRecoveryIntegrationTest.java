package dev.buhanzaz.rwms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.nullable;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.NumberResolutionView;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionPreviewRequest;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FurnitureReviewView;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PrepareFinalPlanRequest;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RevisionExpectation;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SaveFurnitureReviewRequest;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.StartFurnitureReviewRequest;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.StartSessionRequest;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanScheduleMode;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false",
      "rwms.inventory.idempotency.lease-duration=PT0.2S",
      "rwms.inventory.capture-release-recovery-delay-ms=600000"
    })
@ActiveProfiles("test")
class InventoryIdempotencyRecoveryIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired InventoryIdempotencyService idempotency;
  @Autowired InventoryApplicationService application;
  @Autowired InventoryCanonicalJsonPort canonicalJson;
  @Autowired ObjectMapper mapper;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean InventoryDependencyGateway dependencies;
  @MockitoBean InventoryEventStore events;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @BeforeEach
  void clearRows() {
    reset(dependencies, events);
    jdbc.execute(
        "truncate table inventory_session,inventory_idempotency_record,inventory_start_operation restart identity cascade");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void applicationReplayKeepsExactResolutionAfterSessionBecomesTerminal() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    UUID idempotencyKey = UUID.randomUUID();
    var request = new dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ResolveNumberRequest(0, "AA-01");
    when(dependencies.resolveNumber(warehouseId, "AA-01"))
        .thenReturn(
            new InventoryDependencyGateway.NumberResolution(
                "AA-01",
                "AA01",
                true,
                new InventoryDependencyGateway.AssetSnapshot(
                    UUID.randomUUID(),
                    8,
                    UUID.randomUUID(),
                    "WAREHOUSE",
                    "AA-01",
                    "AA01")));
    when(events.initialize(
            anyString(),
            any(UUID.class),
            anyString(),
            anyString(),
            any(),
            nullable(UUID.class),
            nullable(UUID.class),
            any()))
        .thenAnswer(
            invocation ->
                new InventoryEventStore.AppendResult(UUID.randomUUID(), 0, "e".repeat(64)));

    NumberResolutionView original =
        application.resolveNumber(jwt(), inventoryId, idempotencyKey, request);

    assertThat(original.outcome()).isEqualTo("CROSS_WAREHOUSE_CONFLICT");
    String exactJson =
        jdbc.queryForObject(
            """
            select response_body::text from inventory_idempotency_record
             where subject_id=? and command_scope=? and idempotency_key=?
            """,
            String.class,
            UUID.fromString(jwt().getSubject()),
            "session.resolve-number",
            idempotencyKey);
    assertThat(exactJson)
        .contains("\"outcome\": \"CROSS_WAREHOUSE_CONFLICT\"")
        .doesNotContain("MATCHED");
    jdbc.update(
        """
        update inventory_session
           set lifecycle='CANCELLED',cancelled_by_actor_ref=?::jsonb,
               cancellation_reason='after resolution',cancelled_at=started_at,
               updated_at=started_at
         where id=?
        """,
        actorJson(),
        inventoryId);

    NumberResolutionView replay =
        application.resolveNumber(jwt(), inventoryId, idempotencyKey, request);
    JsonNode originalJson = mapper.valueToTree(original);
    JsonNode replayJson = mapper.valueToTree(replay);
    assertThat(replayJson).isEqualTo(originalJson);
    verify(dependencies, times(1)).resolveNumber(warehouseId, "AA-01");
  }

  @Test
  void completionStatisticsUseTheValidatedRegistryProjection() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID currentWarehouseId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    seedFinding(inventoryId, findingId, assetId);
    InventoryDependencyGateway.ValidationItem item =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            2L,
            currentWarehouseId,
            "WAREHOUSE",
            "AA-01",
            "AA01",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> items = List.of(item);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC), canonicalJson.sha256(items), items));

    long furnitureSessionRevision =
        confirmEmptyFurnitureReview(
            inventoryId, warehouseId, List.of(new RevisionExpectation(findingId, 0)));
    when(dependencies.preflightReconciliation(any(), any()))
        .thenAnswer(
            invocation -> {
              JsonNode request = invocation.getArgument(1);
              var response = mapper.createObjectNode();
              response.put("inventoryId", request.required("inventoryId").asText());
              response.put("finalPlanVersion", request.required("finalPlanVersion").asLong());
              response.put("finalPlanSha256", request.required("finalPlanSha256").asText());
              var findings = response.putArray("findings");
              for (JsonNode finding : request.required("findings")) {
                findings
                    .addObject()
                    .put("findingId", finding.required("findingId").asText())
                    .putArray("candidates");
              }
              return response;
            });
    var finalPlan =
        application.prepareFinalPlan(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new PrepareFinalPlanRequest(
                furnitureSessionRevision,
                0,
                FinalPlanScheduleMode.AUTO,
                FinalPlanScheduleMode.AUTO));
    var preview =
        application.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureSessionRevision,
                finalPlan.finalPlanVersion(),
                finalPlan.finalPlanSha256(),
                List.of(new RevisionExpectation(findingId, 0))));

    assertThat(preview.statistics().missingCount()).isZero();
    assertThat(preview.statistics().conflictCount()).isZero();
    assertThat(preview.risks())
        .extracting(dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionRisk::code)
        .containsExactly("NOT_INSPECTED");
    assertThat(preview.validatedFindings())
        .singleElement()
        .satisfies(
            finding -> {
              assertThat(finding.currentSnapshot().warehouseId()).isEqualTo(currentWarehouseId);
              assertThat(finding.conflicts()).isEmpty();
            });
  }

  @Test
  void commandFailureRollsBackDomainWriteAndLeavesOnlyRetryableReservation() throws Exception {
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    Map<String, Object> request = Map.of("operationId", operationId);

    assertThatThrownBy(
            () ->
                idempotency.execute(
                    subjectId,
                    "session.resolve-number",
                    idempotencyKey,
                    request,
                    200,
                    NumberResolutionView.class,
                    () -> {
                      insertStartOperation(
                          operationId, subjectId, UUID.randomUUID(), warehouseId);
                      throw new IllegalStateException("fault after domain write");
                    }))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("fault after domain write");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_start_operation where operation_id=?",
                Integer.class,
                operationId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_idempotency_record where subject_id=? and command_scope=? and idempotency_key=?",
                String.class,
                subjectId,
                "session.resolve-number",
                idempotencyKey))
        .isEqualTo("IN_PROGRESS");

    Thread.sleep(300);
    NumberResolutionView retried =
        idempotency.execute(
            subjectId,
            "session.resolve-number",
            idempotencyKey,
            request,
            200,
            NumberResolutionView.class,
            () -> {
              insertStartOperation(operationId, subjectId, UUID.randomUUID(), warehouseId);
              return new NumberResolutionView("AA-01", "AA01", "MATCHED", null);
            });
    assertThat(retried.outcome()).isEqualTo("MATCHED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_start_operation where operation_id=?",
                Integer.class,
                operationId))
        .isOne();
  }

  @Test
  void commandLongerThanLeaseCannotBeStolenOrExecuteEffectTwice() throws Exception {
    UUID subjectId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    Map<String, Object> request = Map.of("expectedVersion", 3, "number", "BB-02");
    AtomicInteger commands = new AtomicInteger();
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    NumberResolutionView original =
        new NumberResolutionView("BB-02", "BB02", "EXCLUDED_STATUS_CONFLICT", null);

    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<NumberResolutionView> first =
          executor.submit(
              () ->
                  idempotency.execute(
                      subjectId,
                      "session.resolve-number",
                      idempotencyKey,
                      request,
                      200,
                      NumberResolutionView.class,
                      () -> {
                        commands.incrementAndGet();
                        entered.countDown();
                        try {
                          if (!release.await(5, TimeUnit.SECONDS)) {
                            throw new IllegalStateException("test command was not released");
                          }
                        } catch (InterruptedException exception) {
                          Thread.currentThread().interrupt();
                          throw new IllegalStateException(exception);
                        }
                        return original;
                      }));
      assertThat(entered.await(5, TimeUnit.SECONDS)).isTrue();
      Thread.sleep(300);
      Future<NumberResolutionView> concurrent =
          executor.submit(
              () ->
                  idempotency.execute(
                      subjectId,
                      "session.resolve-number",
                      idempotencyKey,
                      request,
                      200,
                      NumberResolutionView.class,
                      () -> {
                        commands.incrementAndGet();
                        return new NumberResolutionView("BB-02", "BB02", "MATCHED", null);
                      }));
      Thread.sleep(100);
      assertThat(concurrent.isDone()).isFalse();
      release.countDown();

      assertThat(first.get(5, TimeUnit.SECONDS)).isEqualTo(original);
      assertThat(concurrent.get(5, TimeUnit.SECONDS)).isEqualTo(original);
      assertThat(commands).hasValue(1);
    }
  }

  @Test
  void blockedMultiPageCopyCannotBeReleasedAndCommittedFailureIsRecovered() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    UUID captureId = UUID.randomUUID();
    InventoryDependencyGateway.CaptureMember firstMember = captureMember(0, warehouseId, "AA-01");
    InventoryDependencyGateway.CaptureMember secondMember = captureMember(1, warehouseId, "BB-02");
    List<InventoryDependencyGateway.CaptureMember> members = List.of(firstMember, secondMember);
    String digest = captureDigest(members);
    CountDownLatch secondPageEntered = new CountDownLatch(1);
    CountDownLatch continueCopy = new CountDownLatch(1);
    AtomicInteger releaseCalls = new AtomicInteger();
    AtomicInteger committedSessionsVisibleAtRelease = new AtomicInteger();

    when(events.initialize(
            anyString(),
            any(UUID.class),
            anyString(),
            anyString(),
            any(),
            nullable(UUID.class),
            nullable(UUID.class),
            any()))
        .thenAnswer(
            invocation ->
                new InventoryEventStore.AppendResult(UUID.randomUUID(), 0, "e".repeat(64)));

    when(dependencies.beginWarehouseOperation(
            eq(warehouseId),
            any(UUID.class),
            any(OffsetDateTime.class),
            eq(InventoryDependencyGateway.WarehouseOperationDirection.INCOMING)))
        .thenReturn(
            new InventoryDependencyGateway.WarehouseOperation(
                warehouseId,
                4,
                "ACTIVE",
                InventoryDependencyGateway.WarehouseOperationDirection.INCOMING,
                "Europe/Moscow",
                OffsetDateTime.parse("2026-01-01T00:00:00Z")));
    when(dependencies.createCapture(any(UUID.class), any(InventoryDependencyGateway.CaptureRequest.class)))
        .thenAnswer(
            invocation -> {
              InventoryDependencyGateway.CaptureRequest request = invocation.getArgument(1);
              OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
              return new InventoryDependencyGateway.Capture(
                  captureId,
                  request.operationId(),
                  request.technicalAttempt(),
                  warehouseId,
                  members.size(),
                  digest,
                  now,
                  now.plusMinutes(5));
            });
    when(dependencies.readCapture(eq(captureId), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              String cursor = invocation.getArgument(1);
              // The operation identity is validated by the application against the capture; read it
              // from the persisted capture result rather than manufacturing another contract value.
              Map<String, Object> captured =
                  jdbc.queryForMap(
                      """
                      select operation_id,technical_attempt from inventory_start_capture_result
                       where capture_id=?
                      """,
                      captureId);
              UUID operationId = (UUID) captured.get("operation_id");
              long attempt = ((Number) captured.get("technical_attempt")).longValue();
              if (cursor == null) {
                return new InventoryDependencyGateway.CapturePage(
                    captureId,
                    operationId,
                    attempt,
                    warehouseId,
                    members.size(),
                    digest,
                    "page-2",
                    List.of(firstMember));
              }
              secondPageEntered.countDown();
              if (!continueCopy.await(5, TimeUnit.SECONDS)) {
                throw new IllegalStateException("copy was not released");
              }
              return new InventoryDependencyGateway.CapturePage(
                  captureId,
                  operationId,
                  attempt,
                  warehouseId,
                  members.size(),
                  digest,
                  null,
                  List.of(secondMember));
            });
    doAnswer(
            invocation -> {
              committedSessionsVisibleAtRelease.set(
                  jdbc.queryForObject(
                      "select count(*) from inventory_session where warehouse_id=?",
                      Integer.class,
                      warehouseId));
              if (releaseCalls.incrementAndGet() == 1) {
                throw InventoryException.dependency("release outage");
              }
              return null;
            })
        .when(dependencies)
        .releaseCapture(captureId);

    UUID idempotencyKey = UUID.randomUUID();
    try (var executor = Executors.newVirtualThreadPerTaskExecutor()) {
      Future<?> start =
          executor.submit(
              () -> application.start(jwt(), idempotencyKey, new StartSessionRequest(warehouseId)));
      assertThat(secondPageEntered.await(5, TimeUnit.SECONDS)).isTrue();

      application.recoverCaptureReleases();
      verify(dependencies, never()).releaseCapture(captureId);
      continueCopy.countDown();
      start.get(10, TimeUnit.SECONDS);
    }

    assertThat(releaseCalls).hasValue(1);
    assertThat(committedSessionsVisibleAtRelease).hasValue(1);
    jdbc.update(
        "update inventory_capture_release set next_attempt_at=current_timestamp - interval '1 second' where capture_id=?",
        captureId);
    application.recoverCaptureReleases();
    verify(dependencies, times(2)).releaseCapture(captureId);
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_capture_release where capture_id=?",
                String.class,
                captureId))
        .isEqualTo("RELEASED");
  }

  @Test
  void captureCannotBeClaimedDuringCopyAndFailedPrecommitCaptureCanBeRetried() {
    UUID subjectId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    String requestHash = "a".repeat(64);
    InventoryStartPersistencePort.StartOperation operation =
        idempotency.reserve(subjectId, idempotencyKey, requestHash, warehouseId);
    long firstAttempt = idempotency.beginCaptureAttempt(operation.operationId(), requestHash);
    UUID firstCapture = UUID.randomUUID();
    idempotency.recordCaptured(
        operation.operationId(),
        firstAttempt,
        firstCapture,
        "b".repeat(64),
        500,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));

    assertThat(idempotency.claimPendingReleases("recovery", 20, Duration.ofSeconds(30))).isEmpty();

    idempotency.releaseFailed(operation.operationId(), "COPY_FAILED");
    jdbc.update(
        "update inventory_capture_release set next_attempt_at=current_timestamp - interval '1 second' where operation_id=?",
        operation.operationId());
    InventoryStartPersistencePort.CaptureReleaseClaim claim =
        idempotency.claimPendingReleases("recovery", 20, Duration.ofSeconds(30)).getFirst();
    assertThat(claim.captureId()).isEqualTo(firstCapture);
    idempotency.recordClaimSucceeded(
        claim.operationId(), claim.captureId(), claim.leaseToken());

    long secondAttempt = idempotency.beginCaptureAttempt(operation.operationId(), requestHash);
    assertThat(secondAttempt).isEqualTo(2);
    UUID secondCapture = UUID.randomUUID();
    idempotency.recordCaptured(
        operation.operationId(),
        secondAttempt,
        secondCapture,
        "c".repeat(64),
        500,
        OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
    assertThat(idempotency.latestCapture(operation.operationId()).captureId())
        .isEqualTo(secondCapture);
    assertThat(idempotency.claimPendingReleases("recovery", 20, Duration.ofSeconds(30))).isEmpty();
  }

  private void insertStartOperation(
      UUID operationId, UUID subjectId, UUID startKey, UUID warehouseId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_start_operation(
          operation_id,subject_id,idempotency_key,request_sha256,warehouse_id,state,
          created_at,updated_at,expires_at)
        values (?,?,?,'aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa',
          ?,'REQUESTED',?,?,? + interval '7 days')
        """,
        operationId,
        subjectId,
        startKey,
        warehouseId,
        now,
        now,
        now);
  }

  private void seedSession(UUID inventoryId, UUID warehouseId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,1,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',?::jsonb,?,?,?)
        """,
        inventoryId,
        warehouseId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        "a".repeat(64),
        "b".repeat(64),
        UUID.fromString(jwt().getSubject()),
        actorJson(),
        now,
        now,
        now);
  }

  private void seedFinding(UUID inventoryId, UUID findingId, UUID assetId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,
          asset_id,asset_version_snapshot,display_canonical_number,identity_match_key,
          passport_observation_state,
          equipment_observation_state,mutation_state,actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','MATCHED',?,1,
          'AA-01','AA01','ABSENT','ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        assetId,
        actorJson(),
        now,
        now);
  }

  private long confirmEmptyFurnitureReview(
      UUID inventoryId, UUID warehouseId, List<RevisionExpectation> findingRevisions) {
    String snapshotSha256 = "f".repeat(64);
    when(dependencies.furnitureSnapshot(eq(warehouseId), any()))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                warehouseId, snapshotSha256, List.of()));
    long sessionRevision = application.session(jwt(), inventoryId).sessionRevision();
    FurnitureReviewView started =
        application.startFurnitureReview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new StartFurnitureReviewRequest(sessionRevision, findingRevisions, true));
    FurnitureReviewView confirmed =
        application.saveFurnitureReview(
            jwt(),
            inventoryId,
            new SaveFurnitureReviewRequest(
                started.sessionRevision(), snapshotSha256, List.of()));
    return confirmed.sessionRevision();
  }

  private String actorJson() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
        + "\"principalType\":\"USER\",\"profileRevision\":null}";
  }

  private InventoryDependencyGateway.CaptureMember captureMember(
      long sequence, UUID warehouseId, String number) {
    return new InventoryDependencyGateway.CaptureMember(
        sequence,
        UUID.randomUUID(),
        2,
        warehouseId,
        "WAREHOUSE",
        number,
        number.replace("-", ""),
        mapper.createObjectNode().put("number", number),
        mapper.createArrayNode());
  }

  private String captureDigest(List<InventoryDependencyGateway.CaptureMember> members) {
    List<Map<String, Object>> digestMembers = new ArrayList<>();
    for (InventoryDependencyGateway.CaptureMember member : members) {
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("sequence", member.sequence());
      value.put("assetId", member.assetId());
      value.put("version", member.version());
      value.put("warehouseId", member.warehouseId());
      value.put("status", member.status());
      value.put("displayCanonicalNumber", member.displayCanonicalNumber());
      value.put("identityMatchKey", member.identityMatchKey());
      value.put("passportSnapshot", Map.of("number", member.displayCanonicalNumber()));
      value.put("contentsSnapshot", member.contentsSnapshot());
      digestMembers.add(value);
    }
    return canonicalJson.sha256(digestMembers);
  }

  private Jwt jwt() {
    Instant now = Instant.now();
    return Jwt.withTokenValue("inventory-idempotency-test")
        .header("alg", "none")
        .subject("00000000-0000-0000-0000-000000000701")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("principal_type", "USER")
        .claim("scope", "rwms.write rwms.read")
        .claim("global_role", "SYSTEM_ADMIN")
        .claim("warehouse_access", List.of())
        .build();
  }
}
