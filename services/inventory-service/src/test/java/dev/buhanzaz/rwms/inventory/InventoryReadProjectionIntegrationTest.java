package dev.buhanzaz.rwms.inventory;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CancelSessionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ClosePublicationRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompleteSessionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionPreview;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionPreviewRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionRisk;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConflictView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CreateFindingAssetRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FindingView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FurnitureReviewView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PageResponse;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanLineInput;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanSelection;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PublishFindingsRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ResolveConflictRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ResolveNumberRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RevisionExpectation;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RetryPublicationRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SaveInspectionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SaveFurnitureReviewRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SessionSummary;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.SessionView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.StartFurnitureReviewRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovementType;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.service.InventoryApplicationService;
import dev.buhanzaz.rwms.inventory.service.InventoryCanonicalJsonPort;
import dev.buhanzaz.rwms.inventory.service.InventoryException;
import dev.buhanzaz.rwms.inventory.service.InventoryFrozenPlanFingerprint;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import jakarta.persistence.EntityManagerFactory;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
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
  @Autowired InventoryCanonicalJsonPort canonicalJson;
  @Autowired InventoryFrozenPlanFingerprint frozenPlanFingerprint;
  @Autowired InventoryEventStore events;
  @MockitoBean InventoryDependencyGateway dependencies;

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
    assertThat(expected.inspectionSource()).isEqualTo("INVENTORY");
    assertThat(expected.coverMediaId()).isNull();
    assertThat(expected.frozenPlan()).isNotNull();
    assertThat(expected.frozenPlan().mode()).isEqualTo("MANUAL");
    assertThat(expected.frozenPlan().fingerprintSha256()).isEqualTo(FINGERPRINT);
    assertThat(expected.frozenPlan().priority()).isEqualTo(3);
    assertThat(expected.frozenPlan().coverMediaId()).isNull();
    assertThat(expected.frozenPlan().movementToRepair()).isTrue();
    assertThat(expected.frozenPlan().movementToShipment()).isFalse();
    assertThat(expected.frozenPlan().logisticsPlanningMode())
        .isEqualTo(LogisticsPlanningMode.FIXED_DATE);
    assertThat(expected.frozenPlan().logisticsScheduledDate())
        .isEqualTo(LocalDate.of(2026, 8, 12));
    assertThat(expected.frozenPlan().lines())
        .extracting(line -> line.description())
        .containsExactly("First work", "Second material");
    assertThat(expected.frozenPlan().lines().getFirst().quantity()).isEqualTo("1.25");
    assertThat(expected.frozenPlan().lines().getFirst().unitPriceMinor()).isEqualTo(1234);
    assertThat(expected.frozenPlan().lines().getFirst().normativeMinutes()).isEqualTo("2.5");
    assertThat(expected.frozenPlan().stages())
        .extracting(stage -> stage.order())
        .containsExactly(0);

    FindingView ready = find(response.content(), fixture.readyFindingId());
    assertThat(ready.expectedSnapshot()).isNull();
    assertThat(ready.inspectionSource()).isEqualTo("INVENTORY");
    assertThat(ready.frozenPlan()).isNull();
    FindingView untouched = find(response.content(), fixture.untouchedFindingId());
    assertThat(untouched.expectedSnapshot()).isNull();
    assertThat(untouched.inspectionSource()).isNull();
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
    assertThat(completedFinding.reconciliation()).isEqualTo(ReconciliationState.CONFLICT);
    assertThat(completedFinding.conflicts())
        .extracting(ConflictView::code)
        .containsExactly("RENTED");

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
  void freezesIndependentMaterialWithPriorityAndExposesPerFindingPlanAndTotals() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID catalogVersionId = UUID.randomUUID();
    UUID materialNodeId = UUID.randomUUID();
    UUID stageNodeId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                assetId,
                1L,
                warehouseId,
                "WAREHOUSE",
                null,
                "БЫТ-MATERIAL",
                "БЫТMATERIAL",
                ReconciliationState.MATCHED,
                ACTOR));
    events.initialize(
        "FINDING",
        finding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);

    InventoryDependencyGateway.ValidationItem item =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            1L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-MATERIAL",
            "БЫТMATERIAL",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(List.of(item)),
                List.of(item)));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(assetId, List.of()))));

    var snapshot = mapper.createObjectNode();
    snapshot.put("catalogVersionId", catalogVersionId.toString());
    snapshot.put("mode", "AUTO");
    snapshot.put("priority", 4);
    snapshot.putNull("coverMediaId");
    snapshot.put("movementToRepair", false);
    snapshot.put("movementToShipment", false);
    snapshot.putNull("logisticsPlanningMode");
    snapshot.putNull("logisticsScheduledDate");
    snapshot
        .putArray("lines")
        .addObject()
        .put("aggregationKind", "CATALOG")
        .put("catalogVersionId", catalogVersionId.toString())
        .put("catalogNodeId", materialNodeId.toString())
        .put("catalogNodeName", "ДВП")
        .put("type", "MATERIAL")
        .put("description", "ДВП")
        .putNull("normalizedDescription")
        .put("unit", "PCS")
        .put("quantity", "2")
        .put("unitPriceMinor", 125)
        .put("normativeMinutes", "0")
        .putNull("groupComment")
        .putArray("mediaReferences");
    var stage = snapshot.putArray("stages").addObject();
    stage.put("id", UUID.randomUUID().toString());
    stage.put("catalogNodeId", stageNodeId.toString());
    stage.put("catalogNodeName", "Выдача материалов");
    stage.put("kind", "REPAIR_WORK");
    stage.put("order", 0);
    stage
        .putObject("routing")
        .put("queueId", queueId.toString())
        .put("queueName", "Материалы")
        .put("queueType", "MAINTENANCE");
    stage.put("normativeDurationMinutes", 0);
    snapshot.putArray("mediaReferences");
    String fingerprint = frozenPlanFingerprint.sha256(snapshot);
    AtomicReference<JsonNode> freezeRequest = new AtomicReference<>();
    when(dependencies.freezePlan(any(), any()))
        .thenAnswer(
            invocation -> {
              freezeRequest.set(invocation.getArgument(1));
              return new InventoryDependencyGateway.FrozenPlan(
                  warehouseId,
                  inventoryId,
                  finding.getId(),
                  1,
                  snapshot,
                  fingerprint);
            });

    FindingView saved =
        service.saveInspection(
            jwt(),
            inventoryId,
            finding.getId(),
            new SaveInspectionRequest(
                0,
                0,
                InspectionState.WORK_STAGED,
                "Требуется материал",
                new Observation(ObservationPresence.ABSENT, null),
                new Observation(ObservationPresence.ABSENT, null),
                List.of(),
                null,
                new PlanSelection(
                    "AUTO",
                    4,
                    null,
                    false,
                    false,
                    null,
                    null,
                    List.of(
                        new PlanLineInput(
                            "CATALOG",
                            materialNodeId,
                            null,
                            null,
                            null,
                            "2",
                            null,
                            null,
                            null,
                            List.of())),
                    List.of())));

    assertThat(freezeRequest.get().required("priority").asInt()).isEqualTo(4);
    assertThat(freezeRequest.get().required("coverMediaId").isNull()).isTrue();
    assertThat(freezeRequest.get().required("movementToRepair").booleanValue()).isFalse();
    assertThat(freezeRequest.get().required("movementToShipment").booleanValue()).isFalse();
    assertThat(freezeRequest.get().required("logisticsPlanningMode").isNull()).isTrue();
    assertThat(freezeRequest.get().required("logisticsScheduledDate").isNull())
        .isTrue();
    assertThat(freezeRequest.get().required("lines")).hasSize(1);
    assertThat(saved.inspectionSource()).isEqualTo("INVENTORY");
    assertThat(saved.frozenPlan().priority()).isEqualTo(4);
    assertThat(saved.frozenPlan().movementToRepair()).isFalse();
    assertThat(saved.frozenPlan().movementToShipment()).isFalse();
    assertThat(saved.frozenPlan().logisticsPlanningMode()).isNull();
    assertThat(saved.frozenPlan().logisticsScheduledDate()).isNull();
    assertThat(saved.frozenPlan().lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.lineType()).isEqualTo("MATERIAL");
              assertThat(line.description()).isEqualTo("ДВП");
              assertThat(line.quantity()).isEqualTo("2");
            });

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), saved.findingRevision())));
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));
    assertThat(preview.statistics().workLineCount()).isZero();
    assertThat(preview.statistics().materialLineCount()).isOne();
    assertThat(preview.statistics().materialTotalMinor()).isEqualTo(250);
    assertThat(preview.statistics().normativeMinutes()).isEqualTo("0");
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

  @Test
  void liveAssetMembershipRetainsCapturedFindingsAndMovementJournal() {
    UUID firstWarehouseId = UUID.randomUUID();
    UUID secondWarehouseId = UUID.randomUUID();
    UUID firstInventoryId = UUID.randomUUID();
    UUID secondInventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(firstInventoryId, firstWarehouseId);
    seedSession(secondInventoryId, secondWarehouseId);
    OpaqueActorReference actor =
        new OpaqueActorReference(jwt().getSubject(), "USER", null);
    var passport = mapper.createObjectNode().put("tenant", "Арендатор");
    var contents = mapper.createArrayNode();
    OffsetDateTime arrivedAt = OffsetDateTime.parse("2026-07-27T08:00:00Z");
    OffsetDateTime transferredAt = arrivedAt.plusMinutes(30);
    OffsetDateTime departedAt = arrivedAt.plusHours(1);

    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.LiveAssetSnapshot(
                    assetId,
                    7,
                    firstWarehouseId,
                    "AFTER_RENT",
                    "БЫТ-101",
                    "БЫТ101",
                    "Арендатор",
                    passport,
                    contents)));

    service.reconcileAssetMembership(
        assetId, actor, UUID.randomUUID(), UUID.randomUUID(), arrivedAt);

    SessionView first = service.session(jwtForWarehouse(firstWarehouseId), firstInventoryId);
    assertThat(first.sessionRevision()).isEqualTo(1);
    assertThat(first.expectedCount()).isOne();
    assertThat(first.findingCount()).isOne();
    assertThat(first.membershipMovements())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.type()).isEqualTo(InventoryMembershipMovementType.ARRIVED);
              assertThat(movement.assetId()).isEqualTo(assetId);
              assertThat(movement.displayCanonicalNumber()).isEqualTo("БЫТ-101");
              assertThat(movement.fromWarehouseId()).isNull();
              assertThat(movement.toWarehouseId()).isEqualTo(firstWarehouseId);
              assertThat(movement.occurredAt()).isEqualTo(arrivedAt);
            });
    FindingView arrived =
        service
            .findings(jwtForWarehouse(firstWarehouseId), firstInventoryId, 0, 20, "createdAt,asc")
            .content()
            .getFirst();
    assertThat(arrived.currentSnapshot().status()).isEqualTo("AFTER_RENT");
    assertThat(arrived.origin()).isEqualTo(FindingOrigin.EXPECTED);
    assertThat(arrived.expectedSnapshot()).isNotNull();
    assertThat(arrived.expectedSnapshot().warehouseId()).isEqualTo(firstWarehouseId);

    service.reconcileAssetMembership(
        assetId, actor, UUID.randomUUID(), UUID.randomUUID(), arrivedAt.plusMinutes(1));
    assertThat(service.session(jwtForWarehouse(firstWarehouseId), firstInventoryId).sessionRevision())
        .isEqualTo(1);
    assertThat(
            service
                .session(jwtForWarehouse(firstWarehouseId), firstInventoryId)
                .membershipMovements())
        .hasSize(1);

    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.LiveAssetSnapshot(
                    assetId,
                    8,
                    firstWarehouseId,
                    "WAREHOUSE",
                    "БЫТ-101",
                    "БЫТ101",
                    null,
                    mapper.createObjectNode(),
                    mapper.createArrayNode())));
    service.reconcileAssetMembership(
        assetId, actor, UUID.randomUUID(), UUID.randomUUID(), arrivedAt.plusMinutes(10));
    assertThat(service.session(jwtForWarehouse(firstWarehouseId), firstInventoryId).sessionRevision())
        .isEqualTo(2);
    assertThat(
            service
                .findings(
                    jwtForWarehouse(firstWarehouseId),
                    firstInventoryId,
                    0,
                    20,
                    "createdAt,asc")
                .content()
                .getFirst()
                .currentSnapshot()
                .status())
        .isEqualTo("WAREHOUSE");

    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.LiveAssetSnapshot(
                    assetId,
                    9,
                    secondWarehouseId,
                    "WAREHOUSE",
                    "БЫТ-101",
                    "БЫТ101",
                    null,
                    mapper.createObjectNode(),
                    mapper.createArrayNode())));
    service.reconcileAssetMembership(
        assetId, actor, UUID.randomUUID(), UUID.randomUUID(), transferredAt);

    assertThat(
            service
                .findings(jwtForWarehouse(firstWarehouseId), firstInventoryId, 0, 20, "createdAt,asc")
                .content())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.inspection()).isEqualTo(InspectionState.NOT_INSPECTED);
              assertThat(value.conflicts()).isEmpty();
              assertThat(value.currentSnapshot().warehouseId()).isEqualTo(secondWarehouseId);
            });
    assertThat(service.session(jwtForWarehouse(firstWarehouseId), firstInventoryId).expectedCount())
        .isOne();
    assertThat(
            service
                .session(jwtForWarehouse(firstWarehouseId), firstInventoryId)
                .membershipMovements())
        .extracting(movement -> movement.type())
        .containsExactly(
            InventoryMembershipMovementType.ARRIVED,
            InventoryMembershipMovementType.TRANSFERRED);
    assertThat(
            service
                .findings(
                    jwtForWarehouse(secondWarehouseId),
                    secondInventoryId,
                    0,
                    20,
                    "createdAt,asc")
                .content())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.assetId()).isEqualTo(assetId);
              assertThat(value.origin()).isEqualTo(FindingOrigin.EXPECTED);
              assertThat(value.expectedSnapshot()).isNotNull();
            });
    SessionView second = service.session(jwtForWarehouse(secondWarehouseId), secondInventoryId);
    assertThat(second.expectedCount()).isOne();
    assertThat(second.findingCount()).isOne();
    assertThat(second.membershipMovements())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.type()).isEqualTo(InventoryMembershipMovementType.ARRIVED);
              assertThat(movement.assetId()).isEqualTo(assetId);
              assertThat(movement.toWarehouseId()).isEqualTo(secondWarehouseId);
              assertThat(movement.occurredAt()).isEqualTo(transferredAt);
            });

    when(dependencies.currentAsset(assetId)).thenReturn(Optional.empty());
    service.reconcileAssetMembership(
        assetId, actor, UUID.randomUUID(), UUID.randomUUID(), departedAt);

    assertThat(
            service
                .findings(
                    jwtForWarehouse(secondWarehouseId),
                    secondInventoryId,
                    0,
                    20,
                    "createdAt,asc")
                .content())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.currentSnapshot()).isNull();
              assertThat(value.conflicts()).isEmpty();
            });
    SessionView afterDeparture =
        service.session(jwtForWarehouse(secondWarehouseId), secondInventoryId);
    assertThat(afterDeparture.expectedCount()).isOne();
    assertThat(afterDeparture.findingCount()).isOne();
    assertThat(afterDeparture.membershipMovements())
        .extracting(movement -> movement.type())
        .containsExactly(
            InventoryMembershipMovementType.ARRIVED,
            InventoryMembershipMovementType.DEPARTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_finding where asset_id=?",
                Long.class,
                assetId))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_finding where asset_id=? and membership_active",
                Long.class,
                assetId))
        .isEqualTo(2L);
  }

  @Test
  void expectedUninspectedCabinThatLeftWarehouseRemainsWithoutConflict() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID expectedItemId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        new TransactionTemplate(transactionManager)
            .execute(
                status -> {
                  InventoryFinding value =
                      findings.saveAndFlush(
                          InventoryFinding.expected(
                              inventoryId,
                              expectedItemId,
                              assetId,
                              5,
                              warehouseId,
                              "WAREHOUSE",
                              null,
                              "БЫТ-303",
                              "БЫТ303",
                              ACTOR));
                  expectedItems.saveAndFlush(
                      new InventoryExpectedItem(
                          expectedItemId,
                          inventoryId,
                          value.getId(),
                          0,
                          assetId,
                          5,
                          "WAREHOUSE",
                          "БЫТ-303",
                          "БЫТ303",
                          "{}",
                          "[]"));
                  return value;
                });
    jdbc.update(
        "update inventory_session set expected_population_count=1 where id=?", inventoryId);
    events.initialize(
        "FINDING",
        finding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "EXPECTED"),
        UUID.randomUUID(),
        null,
        null);
    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.LiveAssetSnapshot(
                    assetId,
                    6,
                    warehouseId,
                    "IN_TRANSFER",
                    "БЫТ-303",
                    "БЫТ303",
                    null,
                    mapper.createObjectNode(),
                    mapper.createArrayNode())));
    OffsetDateTime transferredAt = OffsetDateTime.parse("2026-07-27T09:00:00Z");
    service.reconcileAssetMembership(
        assetId,
        new OpaqueActorReference(jwt().getSubject(), "USER", null),
        UUID.randomUUID(),
        UUID.randomUUID(),
        transferredAt);

    SessionView session = service.session(jwtForWarehouse(warehouseId), inventoryId);
    assertThat(session.expectedCount()).isOne();
    assertThat(session.findingCount()).isOne();
    assertThat(
            service
                .findings(jwtForWarehouse(warehouseId), inventoryId, 0, 20, "createdAt,asc")
                .content())
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.inspection()).isEqualTo(InspectionState.NOT_INSPECTED);
              assertThat(value.currentSnapshot().status()).isEqualTo("IN_TRANSFER");
              assertThat(value.conflicts()).isEmpty();
            });
    assertThat(session.membershipMovements())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.type()).isEqualTo(InventoryMembershipMovementType.TRANSFERRED);
              assertThat(movement.displayCanonicalNumber()).isEqualTo("БЫТ-303");
              assertThat(movement.fromWarehouseId()).isEqualTo(warehouseId);
              assertThat(movement.toWarehouseId()).isNull();
              assertThat(movement.status()).isEqualTo("IN_TRANSFER");
              assertThat(movement.occurredAt()).isEqualTo(transferredAt);
            });
  }

  @Test
  void acceptingAfterRentCabinRequiresAtLeastOneReadyPhoto() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.LiveAssetSnapshot(
                    assetId,
                    4,
                    warehouseId,
                    "AFTER_RENT",
                    "БЫТ-202",
                    "БЫТ202",
                    null,
                    mapper.createObjectNode(),
                    mapper.createArrayNode())));
    List<InventoryDependencyGateway.ValidationItem> validationItems =
        List.of(
            new InventoryDependencyGateway.ValidationItem(
                assetId,
                true,
                4L,
                warehouseId,
                "AFTER_RENT",
                "БЫТ-202",
                "БЫТ202",
                null,
                mapper.createObjectNode(),
                mapper.createArrayNode()));
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(validationItems),
                validationItems));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(
                        assetId, List.of()))));
    service.reconcileAssetMembership(
        assetId,
        new OpaqueActorReference(jwt().getSubject(), "USER", null),
        UUID.randomUUID(),
        UUID.randomUUID(),
        OffsetDateTime.now(ZoneOffset.UTC));
    FindingView finding =
        service
            .findings(jwt(), inventoryId, 0, 20, "createdAt,asc")
            .content()
            .getFirst();

    assertThatThrownBy(
            () ->
                service.saveInspection(
                    jwt(),
                    inventoryId,
                    finding.id(),
                    new SaveInspectionRequest(
                        1,
                        finding.findingRevision(),
                        InspectionState.READY,
                        new Observation(ObservationPresence.ABSENT, null),
                        new Observation(ObservationPresence.ABSENT, null),
                        List.of(),
                        null)))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(exception.getMessage()).contains("загрузите хотя бы одну фотографию");
            });
  }

  @Test
  void completionPreviewUsesItsSemanticDigestWhenProviderSerializationDiffers() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            assetId,
            1L,
            warehouseId,
            "WAREHOUSE",
            null,
            "БЫТ-302",
            "БЫТ302",
            ReconciliationState.MATCHED,
            ACTOR);
    finding = findings.saveAndFlush(finding);
    events.initialize(
        "FINDING",
        finding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);

    InventoryDependencyGateway.ValidationItem item =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            1L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-302",
            "БЫТ302",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> items = List.of(item);
    String providerDigest = "f".repeat(64);
    assertThat(canonicalJson.sha256(items)).isNotEqualTo(providerDigest);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC), providerDigest, items));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(
                        assetId, List.of()))));

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), finding.getRevision())));
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));

    assertThat(preview.validatedFindings()).singleElement();
    assertThat(preview.validationSha256()).isNotEqualTo(providerDigest);
  }

  @Test
  void conflictsStartAfterInspectionAndIgnoreTechnicalAssetVersion() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            assetId,
            1L,
            warehouseId,
            "WAREHOUSE",
            null,
            "БЫТ-301",
            "БЫТ301",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        ACTOR);
    finding = findings.saveAndFlush(finding);
    events.initialize(
        "FINDING",
        finding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);

    InventoryDependencyGateway.ValidationItem versionOnly =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            8L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-301",
            "БЫТ301",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> versionOnlyItems = List.of(versionOnly);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(versionOnlyItems),
                versionOnlyItems));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(
                        assetId, List.of()))));

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), finding.getRevision())));
    CompletionPreview versionPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));

    assertThat(versionPreview.validatedFindings().getFirst().conflicts()).isEmpty();
    assertThat(versionPreview.risks()).isEmpty();

    InventoryDependencyGateway.ValidationItem laterTechnicalVersion =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            9L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-301",
            "БЫТ301",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> laterTechnicalVersionItems =
        List.of(laterTechnicalVersion);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(laterTechnicalVersionItems),
                laterTechnicalVersionItems));

    CompletionPreview laterTechnicalVersionPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));

    assertThat(laterTechnicalVersionPreview.validationSha256())
        .isEqualTo(versionPreview.validationSha256());

    InventoryDependencyGateway.ValidationItem changedStatus =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            10L,
            warehouseId,
            "REPAIR",
            "БЫТ-301",
            "БЫТ301",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> changedItems = List.of(changedStatus);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(changedItems),
                changedItems));

    CompletionPreview changedPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));

    assertThat(changedPreview.validatedFindings().getFirst().conflicts())
        .extracting(ConflictView::code)
        .containsExactly("STATUS_CHANGED");
    assertThat(changedPreview.risks())
        .extracting(CompletionRisk::code)
        .containsExactly("CONFLICT");

    FindingView keptInspection =
        service.resolveConflict(
            jwt(),
            inventoryId,
            finding.getId(),
            new ResolveConflictRequest(
                furnitureReview.sessionRevision(),
                furnitureReview.findingRevision(finding.getId()),
                ConflictResolutionStrategy.KEEP_INSPECTION,
                "Данные осмотра подтверждены кладовщиком"));
    assertThat(keptInspection.conflicts()).isEmpty();
    assertThat(keptInspection.inspectionBaseline().status()).isEqualTo("WAREHOUSE");
    assertThat(keptInspection.currentSnapshot().status()).isEqualTo("REPAIR");
    assertThat(keptInspection.conflictResolution().strategy())
        .isEqualTo(ConflictResolutionStrategy.KEEP_INSPECTION);

    FurnitureReviewState resumedFurnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), keptInspection.findingRevision())));
    CompletionPreview resolvedPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                resumedFurnitureReview.sessionRevision(),
                resumedFurnitureReview.findingRevisions()));
    assertThat(resolvedPreview.risks()).isEmpty();

    InventoryDependencyGateway.ValidationItem laterChange =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            11L,
            warehouseId,
            "OWN_NEEDS",
            "БЫТ-301",
            "БЫТ301",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> laterItems = List.of(laterChange);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(laterItems),
                laterItems));

    CompletionPreview staleResolutionPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                resumedFurnitureReview.sessionRevision(),
                resumedFurnitureReview.findingRevisions()));
    assertThat(staleResolutionPreview.validatedFindings().getFirst().conflicts())
        .extracting(ConflictView::code)
        .containsExactly("STATUS_CHANGED");
    assertThat(staleResolutionPreview.risks())
        .extracting(CompletionRisk::code)
        .containsExactly("CONFLICT");
  }

  @Test
  void completionRemainsFreshWhenOnlyTechnicalAssetVersionChangesAfterPreview() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    events.initialize(
        "SESSION",
        inventoryId,
        "inventory.session.started.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("inventoryId", inventoryId.toString()),
        UUID.randomUUID(),
        null,
        null);
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            assetId,
            6L,
            warehouseId,
            "WAREHOUSE",
            null,
            "БЫТ-001",
            "БЫТ001",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        ACTOR);
    finding = findings.saveAndFlush(finding);
    events.initialize(
        "FINDING",
        finding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);

    InventoryDependencyGateway.ValidationItem previewItem =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            8L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-001",
            "БЫТ001",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> previewItems = List.of(previewItem);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(previewItems),
                previewItems));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(
                        assetId, List.of()))));

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), finding.getRevision())));
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));

    InventoryDependencyGateway.ValidationItem completionItem =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            9L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-001",
            "БЫТ001",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> completionItems =
        List.of(completionItem);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(completionItems),
                completionItems));

    SessionView completed =
        service.complete(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompleteSessionRequest(
                preview.sessionRevision(),
                preview.findingRevisions(),
                preview.acknowledgementSha256(),
                preview.validationSha256()));

    assertThat(completed.lifecycle()).isEqualTo(SessionLifecycle.COMPLETED);
  }

  @Test
  void uninspectedFindingDoesNotConflictWhenRegistryStatusChanges() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                assetId,
                1L,
                warehouseId,
                "WAREHOUSE",
                null,
                "БЫТ-302",
                "БЫТ302",
                ReconciliationState.MATCHED,
                ACTOR));
    InventoryDependencyGateway.ValidationItem changed =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            8L,
            warehouseId,
            "REPAIR",
            "БЫТ-302",
            "БЫТ302",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> changedItems = List.of(changed);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(changedItems),
                changedItems));

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), finding.getRevision())));
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompletionPreviewRequest(
                furnitureReview.sessionRevision(), furnitureReview.findingRevisions()));

    assertThat(preview.validatedFindings().getFirst().conflicts()).isEmpty();
    assertThat(preview.validatedFindings().getFirst().currentSnapshot().status())
        .isEqualTo("REPAIR");
    assertThat(preview.risks())
        .extracting(CompletionRisk::code)
        .containsExactly("NOT_INSPECTED");
  }

  @ParameterizedTest
  @EnumSource(
      value = FindingOrigin.class,
      names = {"ADDED_NEW", "ADDED_USED"})
  void createsInventoryAssetWithOpaqueActorEventAndCurrentSessionRevision(FindingOrigin origin) {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    when(dependencies.createSourceAsset(any(UUID.class), any(JsonNode.class)))
        .thenAnswer(
            invocation -> {
              JsonNode request = invocation.getArgument(1);
              return new InventoryDependencyGateway.SourceAsset(
                  UUID.fromString(request.path("inventoryId").asText()),
                  UUID.fromString(request.path("findingId").asText()),
                  new InventoryDependencyGateway.AssetSnapshot(
                      assetId,
                      0,
                      warehouseId,
                      "FREE",
                      request.path("number").asText(),
                      "NEW901"));
            });

    var passport = mapper.createObjectNode();
    passport.put("rentalType", "БК-1");
    passport.put("dimensions", "2.4x6");
    passport.put("finishing", "ДВП");
    passport.put("category", "Новая");
    passport.put("characteristics", "Пластиковое окно");
    passport.put("linoleum", false);
    passport.set("passport", mapper.createObjectNode());
    passport.set("tags", mapper.createArrayNode());

    FindingView created =
        service.createAsset(
            jwt(),
            inventoryId,
            findingId,
            UUID.randomUUID(),
            new CreateFindingAssetRequest(
                0, 0, 1, origin, "NEW-901", passport));

    assertThat(created.id()).isEqualTo(findingId);
    assertThat(created.currentSnapshot().assetId()).isEqualTo(assetId);
    assertThat(created.currentSnapshot().status()).isEqualTo("FREE");
    assertThat(created.expectedSnapshot()).isNull();
    SessionView session = service.session(jwt(), inventoryId);
    assertThat(session.sessionRevision()).isEqualTo(1);
    assertThat(session.membershipMovements())
        .singleElement()
        .satisfies(
            movement -> {
              assertThat(movement.type()).isEqualTo(InventoryMembershipMovementType.ARRIVED);
              assertThat(movement.assetId()).isEqualTo(assetId);
              assertThat(movement.origin()).isEqualTo(origin);
            });
    assertThat(
            jdbc.queryForObject(
                "select actor_ref->>'subjectId' from domain_event "
                    + "where aggregate_type='FINDING' and aggregate_id=? order by aggregate_version limit 1",
                String.class,
                findingId.toString()))
        .isEqualTo(jwt().getSubject());
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
            true,
            false,
            LogisticsPlanningMode.FIXED_DATE,
            LocalDate.of(2026, 8, 12),
            catalogVersionId,
            FINGERPRINT,
            "{\"priority\":3,\"movementToRepair\":true,\"movementToShipment\":false,"
                + "\"logisticsPlanningMode\":\"FIXED_DATE\","
                + "\"logisticsScheduledDate\":\"2026-08-12\",\"stages\":["
                + "{\"id\":\"00000000-0000-0000-0000-000000000811\","
                + "\"kind\":\"REPAIR_WORK\",\"order\":0,"
                + "\"catalogNodeId\":\"00000000-0000-0000-0000-000000000812\","
                + "\"catalogNodeName\":\"Repair A\",\"normativeDurationMinutes\":150}]}"));
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
                0,
                "REPAIR_WORK",
                UUID.randomUUID(),
                "Repair work",
                UUID.randomUUID(),
                "Maintenance",
                "MAINTENANCE",
                true,
                "{}")));

    UUID readyAssetId = UUID.randomUUID();
    InventoryFinding ready =
        InventoryFinding.unexpected(
            activeInventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            readyAssetId,
            1L,
            warehouseId,
            "WAREHOUSE",
            null,
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
                warehouseId,
                "WAREHOUSE",
                null,
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
            warehouseId,
            "WAREHOUSE",
            null,
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

  private FurnitureReviewState confirmEmptyFurnitureReview(
      UUID inventoryId, UUID warehouseId, List<RevisionExpectation> findingRevisions) {
    String snapshotSha256 = "f".repeat(64);
    when(dependencies.furnitureSnapshot(eq(warehouseId), any()))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                warehouseId, snapshotSha256, List.of()));
    long sessionRevision = service.session(jwt(), inventoryId).sessionRevision();
    FurnitureReviewView started =
        service.startFurnitureReview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new StartFurnitureReviewRequest(sessionRevision, findingRevisions, true));
    FurnitureReviewView confirmed =
        service.saveFurnitureReview(
            jwt(),
            inventoryId,
            new SaveFurnitureReviewRequest(
                started.sessionRevision(), snapshotSha256, List.of()));
    List<RevisionExpectation> updatedFindingRevisions =
        findings.findAllByInventoryIdAndMembershipActiveTrueOrderById(inventoryId).stream()
            .map(value -> new RevisionExpectation(value.getId(), value.getRevision()))
            .toList();
    return new FurnitureReviewState(confirmed.sessionRevision(), updatedFindingRevisions);
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

  private record FurnitureReviewState(
      long sessionRevision, List<RevisionExpectation> findingRevisions) {
    long findingRevision(UUID findingId) {
      return findingRevisions.stream()
          .filter(value -> value.findingId().equals(findingId))
          .findFirst()
          .orElseThrow()
          .expectedFindingRevision();
    }
  }
}
