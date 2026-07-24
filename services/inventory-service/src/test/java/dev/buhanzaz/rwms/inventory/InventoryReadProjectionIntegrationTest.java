package dev.buhanzaz.rwms.inventory;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CancelSessionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ClosePublicationRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompleteSessionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionPreviewRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConflictView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CreateFindingAssetRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FindingView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PageResponse;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PublishFindingsRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ResolveNumberRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RetryPublicationRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SaveInspectionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SessionSummary;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SessionView;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.service.InventoryApplicationService;
import dev.buhanzaz.rwms.inventory.service.InventoryException;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryReadProjectionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final String ACTOR =
      "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
          + "\"principalType\":\"USER\",\"profileRevision\":null}";
  private static final String FINGERPRINT = "a".repeat(64);

  static {
    POSTGRES.start();
  }

  @Autowired InventoryApplicationService service;
  @Autowired InventoryFindingRepository findings;
  @Autowired InventoryExpectedItemRepository expectedItems;
  @Autowired FindingPlanSnapshotRepository planSnapshots;
  @Autowired FindingPlanLineRepository planLines;
  @Autowired FindingPlanStageRepository planStages;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired ObjectMapper mapper;

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
    jdbc.execute("truncate table inventory_session restart identity cascade");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void readsAuthoritativeCountsExpectedAndFrozenHistoryWithoutNPlusOne() {
    UUID warehouseId = UUID.randomUUID();
    UUID cancelledInventoryId = UUID.randomUUID();
    seedSession(cancelledInventoryId, warehouseId);
    markCancelled(cancelledInventoryId);
    UUID activeInventoryId = UUID.randomUUID();
    seedSession(activeInventoryId, warehouseId);

    Fixture fixture =
        new TransactionTemplate(transactionManager)
            .execute(
                status ->
                    seedFindings(activeInventoryId, cancelledInventoryId, warehouseId));

    PageResponse<SessionSummary> activeHistory =
        service.sessions(
            jwt(),
            warehouseId,
            SessionLifecycle.ACTIVE,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            20,
            "startedAt,asc");
    assertThat(activeHistory.content()).hasSize(1);
    assertThat(activeHistory.content().getFirst().findingCount()).isEqualTo(3);
    assertThat(activeHistory.content().getFirst().inspectedCount()).isEqualTo(2);

    PageResponse<SessionSummary> cancelledHistory =
        service.sessions(
            jwt(),
            warehouseId,
            SessionLifecycle.CANCELLED,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            20,
            "startedAt,asc");
    assertThat(cancelledHistory.content()).singleElement().satisfies(summary -> {
      assertThat(summary.id()).isEqualTo(cancelledInventoryId);
      assertThat(summary.findingCount()).isOne();
      assertThat(summary.inspectedCount()).isOne();
    });

    SessionView detail = service.session(jwt(), activeInventoryId);
    assertThat(detail.findingCount()).isEqualTo(3);
    assertThat(detail.inspectedCount()).isEqualTo(2);
    assertThat(detail.author().displayName()).isEqualTo("Inventory operator");

    Statistics statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();
    PageResponse<FindingView> response =
        service.findings(jwt(), activeInventoryId, 0, 20, "displayCanonicalNumber,asc");
    assertThat(statistics.getPrepareStatementCount()).isLessThanOrEqualTo(9);
    assertThat(response.content()).hasSize(3);

    FindingView expected = find(response.content(), fixture.expectedFindingId());
    assertThat(expected.expectedSnapshot()).isNotNull();
    assertThat(expected.expectedSnapshot().assetId()).isEqualTo(fixture.expectedAssetId());
    assertThat(expected.expectedSnapshot().assetVersion()).isEqualTo(7);
    assertThat(expected.expectedSnapshot().warehouseId()).isEqualTo(warehouseId);
    assertThat(expected.expectedSnapshot().status()).isEqualTo("WAREHOUSE");
    assertThat(expected.expectedSnapshot().displayCanonicalNumber()).isEqualTo("AA-01");
    assertThat(expected.expectedSnapshot().passportSnapshot().isObject()).isTrue();
    assertThat(expected.expectedSnapshot().contentsSnapshot().isArray()).isTrue();
    assertThat(expected.currentSnapshot()).isNotNull();
    assertThat(expected.currentSnapshot().warehouseId()).isEqualTo(warehouseId);
    assertThat(expected.comment()).isEmpty();
    assertThat(expected.conflicts()).isEmpty();
    assertThat(expected.frozenPlan()).isNotNull();
    assertThat(expected.frozenPlan().mode()).isEqualTo("MANUAL");
    assertThat(expected.frozenPlan().fingerprintSha256()).isEqualTo(FINGERPRINT);
    assertThat(expected.frozenPlan().lines())
        .extracting(line -> line.description())
        .containsExactly("First work", "Second material");
    assertThat(expected.frozenPlan().lines().getFirst().quantity()).isEqualTo("1.25");
    assertThat(expected.frozenPlan().lines().getFirst().unitPriceMinor()).isEqualTo(1234);
    assertThat(expected.frozenPlan().lines().getFirst().normativeMinutes()).isEqualTo("2.5");
    assertThat(expected.frozenPlan().stages())
        .extracting(stage -> stage.order())
        .containsExactly(0, 1);

    FindingView ready = find(response.content(), fixture.readyFindingId());
    assertThat(ready.expectedSnapshot()).isNull();
    assertThat(ready.frozenPlan()).isNull();
    FindingView untouched = find(response.content(), fixture.untouchedFindingId());
    assertThat(untouched.expectedSnapshot()).isNull();
    assertThat(untouched.frozenPlan()).isNull();

    JsonNode exact = mapper.valueToTree(expected);
    assertThat(exact.required("expectedSnapshot").required("passportSnapshot").isObject()).isTrue();
    assertThat(exact.required("frozenPlan").required("lines").get(0).required("quantity").asText())
        .isEqualTo("1.25");
    assertThat(exact.toString())
        .doesNotContain("sourceSnapshot", "safeSnapshot", "actor", "company");

    OffsetDateTime completedAt = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        update inventory_session
        set lifecycle='COMPLETED',completion_validation_sha256=?,
            completion_acknowledgement_sha256=?,validated_at=?,
            completed_by_actor_ref=?::jsonb,completed_at=?,updated_at=?
        where id=?
        """,
        "2".repeat(64),
        "3".repeat(64),
        completedAt,
        ACTOR,
        completedAt,
        completedAt,
        activeInventoryId);
    String completedSnapshot =
        """
        {"preview":{"validatedFindings":[{
          "findingId":"%s",
          "currentSnapshot":{
            "assetId":"%s","assetVersion":8,"warehouseId":"%s",
            "status":"RENTED","displayCanonicalNumber":"AA-01",
            "tenantSnapshot":"Арендатор А"
          },
          "conflicts":[{
            "code":"RENTED","message":"Бытовка числится в аренде",
            "expected":"WAREHOUSE","actual":"RENTED"
          }]
        }]}}
        """
            .formatted(fixture.expectedFindingId(), fixture.expectedAssetId(), warehouseId);
    jdbc.update(
        """
        insert into inventory_validation_snapshot(
          inventory_id,session_revision,validation_sha256,acknowledgement_sha256,
          validated_at,snapshot_body)
        values (?,0,?,?,?,?::jsonb)
        """,
        activeInventoryId,
        "2".repeat(64),
        "3".repeat(64),
        completedAt,
        completedSnapshot);
    FindingView completedFinding =
        find(
            service.findings(jwt(), activeInventoryId, 0, 20, "createdAt,asc")
                .content(),
            fixture.expectedFindingId());
    assertThat(completedFinding.currentSnapshot().status()).isEqualTo("RENTED");
    assertThat(completedFinding.currentSnapshot().tenantSnapshot()).isEqualTo("Арендатор А");
    assertThat(completedFinding.reconciliation()).isEqualTo(ReconciliationState.MATCHED);
    assertThat(completedFinding.conflicts())
        .extracting(ConflictView::code)
        .containsExactly("RENTED");

    String legacyCompletedSnapshot =
        """
        {"preview":{},"validation":{"assets":[{
          "assetId":"%s","found":true,"version":8,
          "warehouseId":"%s","status":"RENTED"
        },{
          "assetId":"%s","found":true,"version":1,
          "warehouseId":"%s","status":"WAREHOUSE"
        },{
          "assetId":"%s","found":true,"version":1,
          "warehouseId":"%s","status":"WAREHOUSE"
        }]}}
        """
            .formatted(
                fixture.expectedAssetId(),
                warehouseId,
                fixture.readyAssetId(),
                warehouseId,
                fixture.untouchedAssetId(),
                warehouseId);
    jdbc.update(
        "update inventory_validation_snapshot set snapshot_body=?::jsonb where inventory_id=?",
        legacyCompletedSnapshot,
        activeInventoryId);
    FindingView legacyCompletedFinding =
        find(
            service.findings(jwt(), activeInventoryId, 0, 20, "createdAt,asc")
                .content(),
            fixture.expectedFindingId());
    assertThat(legacyCompletedFinding.currentSnapshot().status()).isEqualTo("RENTED");
    assertThat(legacyCompletedFinding.currentSnapshot().displayCanonicalNumber())
        .isEqualTo("AA-01");
    assertThat(legacyCompletedFinding.conflicts())
        .extracting(ConflictView::code)
        .contains("ASSET_CHANGED", "RENTED", "STATUS_CHANGED")
        .doesNotContain("TENANT_CHANGED");

    String oversized = "{\"value\":\"" + "x".repeat(65_536) + "\"}";
    jdbc.update(
        "update inventory_expected_item set safe_passport_snapshot=?::jsonb where finding_id=?",
        oversized,
        fixture.expectedFindingId());
    assertThatThrownBy(
            () -> service.findings(jwt(), activeInventoryId, 0, 20, "createdAt,asc"))
        .isInstanceOf(IllegalStateException.class)
        .hasMessageContaining("safe bound");
  }

  @Test
  void foreignWarehouseScopedSessionIdIsIndistinguishableFromMissingId() {
    UUID ownerWarehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    seedSession(inventoryId, ownerWarehouseId);

    assertThatThrownBy(() -> service.session(jwtForWarehouse(UUID.randomUUID()), inventoryId))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(exception.code()).isEqualTo("INVENTORY_NOT_FOUND");
            });
  }

  @ParameterizedTest
  @EnumSource(SessionLifecycle.class)
  void everyMutationHidesForeignWarehouseBeforeLifecycleChecks(SessionLifecycle lifecycle) {
    UUID ownerWarehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    seedSession(inventoryId, ownerWarehouseId);
    setLifecycle(inventoryId, lifecycle);
    Jwt foreign = jwtForWarehouse(UUID.randomUUID());

    assertNotFound(
        () ->
            service.resolveNumber(
                foreign,
                inventoryId,
                UUID.randomUUID(),
                new ResolveNumberRequest(0, "AA-01")));
    assertNotFound(
        () ->
            service.createAsset(
                foreign,
                inventoryId,
                findingId,
                UUID.randomUUID(),
                new CreateFindingAssetRequest(
                    0,
                    0,
                    1,
                    FindingOrigin.ADDED_NEW,
                    "AA-01",
                    mapper.createObjectNode())));
    assertNotFound(
        () ->
            service.saveInspection(
                foreign,
                inventoryId,
                findingId,
                new SaveInspectionRequest(
                    0,
                    0,
                    InspectionState.READY,
                    new Observation(ObservationPresence.ABSENT, null),
                    new Observation(ObservationPresence.ABSENT, null),
                    List.of(),
                    null)));
    assertNotFound(
        () ->
            service.preview(
                foreign,
                inventoryId,
                UUID.randomUUID(),
                new CompletionPreviewRequest(0, List.of())));
    assertNotFound(
        () ->
            service.complete(
                foreign,
                inventoryId,
                UUID.randomUUID(),
                new CompleteSessionRequest(
                    0, List.of(), "a".repeat(64), "b".repeat(64))));
    assertNotFound(
        () ->
            service.cancel(
                foreign,
                inventoryId,
                UUID.randomUUID(),
                new CancelSessionRequest(0, "reviewed")));
    assertNotFound(
        () ->
            service.publish(
                foreign,
                inventoryId,
                UUID.randomUUID(),
                new PublishFindingsRequest(0, true, List.of())));
    assertNotFound(
        () ->
            service.retryPublication(
                foreign,
                inventoryId,
                findingId,
                UUID.randomUUID(),
                new RetryPublicationRequest(0, null, null)));
    assertNotFound(
        () ->
            service.closePublication(
                foreign,
                inventoryId,
                findingId,
                UUID.randomUUID(),
                new ClosePublicationRequest(0, "reviewed")));
  }

  private Fixture seedFindings(
      UUID activeInventoryId, UUID cancelledInventoryId, UUID warehouseId) {
    UUID expectedItemId = UUID.randomUUID();
    UUID expectedAssetId = UUID.randomUUID();
    InventoryFinding expected =
        findings.saveAndFlush(
            InventoryFinding.expected(
                activeInventoryId,
                expectedItemId,
                expectedAssetId,
                7,
                warehouseId,
                "WAREHOUSE",
                null,
                "AA-01",
                "AA01",
                ACTOR));
    expected.saveInspection(
        InspectionState.WORK_STAGED,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        FINGERPRINT,
        ACTOR);
    expected = findings.saveAndFlush(expected);
    expectedItems.saveAndFlush(
        new InventoryExpectedItem(
            expectedItemId,
            activeInventoryId,
            expected.getId(),
            0,
            expectedAssetId,
            7,
            "WAREHOUSE",
            "AA-01",
            "AA01",
            "{\"serial\":\"SAFE\"}",
            "[{\"name\":\"safe\"}]"));
    UUID catalogVersionId = UUID.randomUUID();
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            expected.getId(),
            expected.getRevision(),
            activeInventoryId,
            "MANUAL",
            catalogVersionId,
            FINGERPRINT,
            "{\"stages\":["
                + "{\"id\":\"00000000-0000-0000-0000-000000000811\","
                + "\"catalogNodeId\":\"00000000-0000-0000-0000-000000000812\","
                + "\"catalogNodeCode\":\"REPAIR_A\",\"normativeDurationMinutes\":150},"
                + "{\"id\":\"00000000-0000-0000-0000-000000000813\","
                + "\"catalogNodeId\":\"00000000-0000-0000-0000-000000000814\","
                + "\"catalogNodeCode\":\"MOVE_A\",\"normativeDurationMinutes\":30}]}"));
    planLines.saveAllAndFlush(
        List.of(
            new FindingPlanLine(
                expected.getId(),
                expected.getRevision(),
                1,
                "MANUAL",
                "MATERIAL",
                null,
                null,
                "Second material",
                "second material",
                "PCS",
                new BigDecimal("3.000"),
                45,
                new BigDecimal("0.000")),
            new FindingPlanLine(
                expected.getId(),
                expected.getRevision(),
                0,
                "CATALOG",
                "WORK",
                catalogVersionId,
                UUID.randomUUID(),
                "First work",
                null,
                "HOUR",
                new BigDecimal("1.250"),
                1234,
                new BigDecimal("2.500"))));
    planStages.saveAllAndFlush(
        List.of(
            new FindingPlanStage(
                expected.getId(),
                expected.getRevision(),
                1,
                "MOVE_TO_REPAIR",
                UUID.randomUUID(),
                "MOVE",
                "LOGISTICS",
                true,
                false,
                "{}"),
            new FindingPlanStage(
                expected.getId(),
                expected.getRevision(),
                0,
                "REPAIR_WORK",
                UUID.randomUUID(),
                "REPAIR",
                "MAINTENANCE",
                false,
                true,
                "{}")));

    UUID readyAssetId = UUID.randomUUID();
    InventoryFinding ready =
        InventoryFinding.unexpected(
            activeInventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            readyAssetId,
            1L,
            "BB-02",
            "BB02",
            ReconciliationState.MATCHED,
            ACTOR);
    ready.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        ACTOR);
    ready = findings.saveAndFlush(ready);
    UUID untouchedAssetId = UUID.randomUUID();
    InventoryFinding untouched =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                activeInventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                untouchedAssetId,
                1L,
                "CC-03",
                "CC03",
                ReconciliationState.MATCHED,
                ACTOR));

    InventoryFinding cancelledReady =
        InventoryFinding.unexpected(
            cancelledInventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            UUID.randomUUID(),
            1L,
            "DD-04",
            "DD04",
            ReconciliationState.MATCHED,
            ACTOR);
    cancelledReady.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        ACTOR);
    findings.saveAndFlush(cancelledReady);
    return new Fixture(
        expected.getId(),
        expectedAssetId,
        ready.getId(),
        readyAssetId,
        untouched.getId(),
        untouchedAssetId);
  }

  private FindingView find(List<FindingView> values, UUID findingId) {
    return values.stream().filter(value -> value.id().equals(findingId)).findFirst().orElseThrow();
  }

  private void seedSession(UUID inventoryId, UUID warehouseId) {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    UUID operationId = UUID.randomUUID();
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',?::jsonb,?,?,?)
        """,
        inventoryId,
        warehouseId,
        operationId,
        operationId,
        "0".repeat(64),
        "1".repeat(64),
        UUID.randomUUID(),
        ACTOR,
        current,
        current,
        current);
  }

  private void markCancelled(UUID inventoryId) {
    jdbc.update(
        """
        update inventory_session
           set lifecycle='CANCELLED',session_revision=1,cancelled_by_actor_ref=?::jsonb,
               cancellation_reason='reviewed',cancelled_at=started_at,updated_at=started_at
         where id=?
        """,
        ACTOR,
        inventoryId);
  }

  private void setLifecycle(UUID inventoryId, SessionLifecycle lifecycle) {
    if (lifecycle == SessionLifecycle.ACTIVE) return;
    if (lifecycle == SessionLifecycle.CANCELLED) {
      markCancelled(inventoryId);
      return;
    }
    jdbc.update(
        """
        update inventory_session
           set lifecycle='COMPLETED',session_revision=1,
               completion_validation_sha256=?,completion_acknowledgement_sha256=?,
               validated_at=started_at,completed_by_actor_ref=?::jsonb,
               completed_at=started_at,updated_at=started_at
         where id=?
        """,
        "2".repeat(64),
        "3".repeat(64),
        ACTOR,
        inventoryId);
  }

  private void assertNotFound(Runnable command) {
    assertThatThrownBy(command::run)
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND);
              assertThat(exception.code()).isEqualTo("INVENTORY_NOT_FOUND");
            });
  }

  private Jwt jwt() {
    Instant now = Instant.now();
    return Jwt.withTokenValue("inventory-read-test")
        .header("alg", "none")
        .subject("00000000-0000-0000-0000-000000000701")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("principal_type", "USER")
        .claim("scope", "rwms.read rwms.write")
        .claim("global_role", "SYSTEM_ADMIN")
        .claim("warehouse_access", List.of(Map.of()))
        .build();
  }

  private Jwt jwtForWarehouse(UUID warehouseId) {
    Instant now = Instant.now();
    return Jwt.withTokenValue("inventory-warehouse-scope-test")
        .header("alg", "none")
        .subject("00000000-0000-0000-0000-000000000701")
        .issuedAt(now)
        .expiresAt(now.plusSeconds(300))
        .claim("principal_type", "USER")
        .claim("scope", "rwms.read rwms.write")
        .claim(
            "warehouse_access",
            List.of(Map.of("warehouseId", warehouseId.toString(), "level", "MANAGE")))
        .build();
  }

  private record Fixture(
      UUID expectedFindingId,
      UUID expectedAssetId,
      UUID readyFindingId,
      UUID readyAssetId,
      UUID untouchedFindingId,
      UUID untouchedAssetId) {}
}
