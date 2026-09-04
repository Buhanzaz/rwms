package dev.buhanzaz.rwms.inventory;

import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CancelSessionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ClosePublicationRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompleteSessionRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionPreview;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionPreviewRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConfirmInventoryReturnsRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConfirmInventoryShipmentsRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryReturnInput;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CompletionRisk;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConflictView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.CreateFindingAssetRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FindingView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FrozenStatistics;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FurnitureReviewCabinInput;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FurnitureReviewItemInput;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FurnitureReviewView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FinalPlanEntryUpdate;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FinalPlanReconciliationDecision;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FinalPlanUpdateRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.MediaReference;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PageResponse;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.Observation;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanLineInput;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanSelection;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PlanStageSelection;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PrepareFinalPlanRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.PublishFindingsRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.OutcomeRecalculation;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RecalculateInventoryOutcomeRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ResolveConflictRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ResolveNumberRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RegistryReviewRequest;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RegistryReviewView;
import static dev.buhanzaz.rwms.inventory.api.InventoryApiModels.RefreshSessionRequest;
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
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.domain.ConflictResolutionStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanReconciliationStrategy;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanScheduleMode;
import dev.buhanzaz.rwms.inventory.domain.FinalPlanTargetKind;
import dev.buhanzaz.rwms.inventory.domain.FindingMediaReference;
import dev.buhanzaz.rwms.inventory.domain.FindingOrigin;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanLine;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanSnapshot;
import dev.buhanzaz.rwms.inventory.domain.FindingPlanStage;
import dev.buhanzaz.rwms.inventory.domain.InspectionState;
import dev.buhanzaz.rwms.inventory.domain.InventoryExpectedItem;
import dev.buhanzaz.rwms.inventory.domain.InventoryAssetOutcomeStatus;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinalPlanEntry;
import dev.buhanzaz.rwms.inventory.domain.InventoryFurnitureReconciliationIntent;
import dev.buhanzaz.rwms.inventory.domain.LogisticsPlanningMode;
import dev.buhanzaz.rwms.inventory.domain.InventoryFinding;
import dev.buhanzaz.rwms.inventory.domain.InventoryMembershipMovementType;
import dev.buhanzaz.rwms.inventory.domain.InventoryMediaFactProjection;
import dev.buhanzaz.rwms.inventory.domain.InventoryPublicationIntent;
import dev.buhanzaz.rwms.inventory.domain.MaintenancePublicationOutcome;
import dev.buhanzaz.rwms.inventory.domain.InventoryReviewStage;
import dev.buhanzaz.rwms.inventory.domain.ObservationPresence;
import dev.buhanzaz.rwms.inventory.domain.ReconciliationState;
import dev.buhanzaz.rwms.inventory.domain.SessionLifecycle;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanLineRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanSnapshotRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingPlanStageRepository;
import dev.buhanzaz.rwms.inventory.repository.FindingMediaReferenceRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryExpectedItemRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFinalPlanEntryRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFindingRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryMediaFactProjectionRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPublicationIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryFurnitureReconciliationIntentRepository;
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
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
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
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false",
      "rwms.inventory.cabin-write-off-recovery-delay-ms=600000",
      "rwms.inventory.plan-logistics-recovery-delay-ms=600000"
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
  @Autowired InventoryMediaFactProjectionRepository mediaFacts;
  @Autowired InventoryExpectedItemRepository expectedItems;
  @Autowired InventoryFinalPlanEntryRepository finalPlanEntries;
  @Autowired FindingPlanSnapshotRepository planSnapshots;
  @Autowired FindingPlanLineRepository planLines;
  @Autowired FindingPlanStageRepository planStages;
  @Autowired FindingMediaReferenceRepository mediaReferences;
  @Autowired JdbcTemplate jdbc;
  @Autowired PlatformTransactionManager transactionManager;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired ObjectMapper mapper;
  @Autowired InventoryCanonicalJsonPort canonicalJson;
  @Autowired InventoryFrozenPlanFingerprint frozenPlanFingerprint;
  @Autowired InventoryEventStore events;
  @Autowired InventoryPublicationIntentRepository publications;
  @Autowired InventoryFurnitureReconciliationIntentRepository furnitureReconciliations;
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
    when(dependencies.workCalendarSnapshot(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              UUID warehouseId = invocation.getArgument(0, UUID.class);
              LocalDate from = invocation.getArgument(1, LocalDate.class);
              LocalDate through = invocation.getArgument(2, LocalDate.class);
              return new InventoryDependencyGateway.WorkCalendarSnapshot(
                  warehouseId,
                  from,
                  through,
                  "a".repeat(64),
                  from.datesUntil(through.plusDays(1))
                      .map(
                          date ->
                              new InventoryDependencyGateway.WorkCalendarDate(
                                  date,
                                  true,
                                  "UTC",
                                  OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                                  UUID.fromString("00000000-0000-0000-0000-000000000701"),
                                  1L,
                                  LocalDate.of(2026, 1, 1)))
                      .toList());
            });
    when(dependencies.applyInventoryOutcome(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              UUID inventoryId = invocation.getArgument(0);
              UUID findingId = invocation.getArgument(1);
              JsonNode request = invocation.getArgument(3);
              UUID assetId = UUID.fromString(request.path("assetId").asText());
              String desiredStatus = request.path("desiredStatus").asText();
              var response = mapper.createObjectNode();
              response.put("inventoryId", inventoryId.toString());
              response.put("findingId", findingId.toString());
              response.put("assetId", assetId.toString());
              response.put("assetVersion", 100);
              response.put("status", desiredStatus);
              response.putArray("releasedOrderUnitReservationIds");
              response.putArray("releasedOperationLeaseIds");
              response.putArray("releasedPresentationHoldIds");
              response.put("transferSuperseded", false);
              return new InventoryDependencyGateway.InventoryAssetOutcome(
                  inventoryId,
                  findingId,
                  assetId,
                  100,
                  desiredStatus,
                  List.of(),
                  List.of(),
                  List.of(),
                  false,
                  response);
            });
    when(dependencies.applyLogisticsOutcomes(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              UUID inventoryId = invocation.getArgument(0);
              JsonNode request = invocation.getArgument(2);
              var response = mapper.createObjectNode();
              response.put("inventoryId", inventoryId.toString());
              response.put("finalPlanVersion", request.path("finalPlanVersion").asLong());
              response.putArray("supersededDocumentIds");
              response.putArray("supersededRentalOrderIds");
              response.putArray("cancelledDriverTaskIds");
              response.put("supersededLineCount", 0);
              response.put("supersededRentalUnitCount", 0);
              response.put("replay", false);
              return response;
            });
    when(dependencies.applyNoWorkDisposition(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              UUID inventoryId = invocation.getArgument(0);
              UUID findingId = invocation.getArgument(1);
              JsonNode request = invocation.getArgument(3);
              var response = mapper.createObjectNode();
              response.put("inventoryId", inventoryId.toString());
              response.put("findingId", findingId.toString());
              response.put("assetId", request.path("assetId").asText());
              response.putArray("supersededEstimateIds");
              response.putArray("supersededRepairIds");
              response.putArray("cancelledExternalTaskIds");
              response.putArray("cancelledDriverTaskIds");
              response.putArray("releasedLeaseIds");
              response.put("replay", false);
              return response;
            });
  }

  @Test
  void historyRecoveryCreatesFreeOutcomeAndRequeuesEveryPriorPublication() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    String finalPlanSha256 = "9".repeat(64);
    seedSession(inventoryId, warehouseId);

    InventoryFinding capitalWork =
        stageRecoveryFinding(inventoryId, warehouseId, "REC-CAP", true);
    InventoryFinding succeededWork =
        stageRecoveryFinding(inventoryId, warehouseId, "REC-OK", true);
    InventoryFinding legacySucceededWork =
        stageRecoveryFinding(inventoryId, warehouseId, "REC-LEGACY", true);
    InventoryFinding noWork =
        stageRecoveryFinding(inventoryId, warehouseId, "REC-FREE", false);
    saveRecoveryPlanSnapshot(inventoryId, capitalWork, true);
    saveRecoveryPlanSnapshot(inventoryId, succeededWork, false);
    saveRecoveryPlanSnapshot(inventoryId, legacySucceededWork, false);
    setLifecycle(inventoryId, SessionLifecycle.COMPLETED);
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,'COMPLETED',1,0,?,'AUTO','AUTO',clock_timestamp(),clock_timestamp())
        """,
        inventoryId,
        finalPlanSha256);
    finalPlanEntries.saveAllAndFlush(
        List.of(
            recoveryFinalPlanEntry(inventoryId, capitalWork, 0, true, true),
            recoveryFinalPlanEntry(inventoryId, succeededWork, 1, true, false),
            recoveryFinalPlanEntry(inventoryId, legacySucceededWork, 2, true, false),
            recoveryFinalPlanEntry(inventoryId, noWork, 3, false, false)));

    InventoryPublicationIntent blocked =
        publications.saveAndFlush(
            InventoryPublicationIntent.readyForOutcome(
                inventoryId,
                capitalWork.getId(),
                Math.max(1, capitalWork.getRevision()),
                1,
                finalPlanSha256,
                FinalPlanTargetKind.REPAIR,
                InventoryAssetOutcomeStatus.CAPITAL_REPAIR));
    initializePublicationEvent(blocked, inventoryId);
    blocked.request("4".repeat(64), null);
    blocked = publications.saveAndFlush(blocked);
    blocked.block("SOURCE_PRECONDITION_CONFLICT");
    publications.saveAndFlush(blocked);

    UUID repairId = UUID.randomUUID();
    InventoryPublicationIntent succeeded =
        publications.saveAndFlush(
            InventoryPublicationIntent.readyForOutcome(
                inventoryId,
                succeededWork.getId(),
                Math.max(1, succeededWork.getRevision()),
                1,
                finalPlanSha256,
                FinalPlanTargetKind.REPAIR,
                InventoryAssetOutcomeStatus.REPAIR));
    initializePublicationEvent(succeeded, inventoryId);
    succeeded.request("5".repeat(64), null);
    succeeded.recordAssetOutcome(
        12,
        InventoryAssetOutcomeStatus.REPAIR,
        "{\"assetId\":\"" + succeededWork.getAssetId() + "\"}");
    succeeded.succeed(
        new InventoryPublicationIntent.PublicationTarget(
            MaintenancePublicationOutcome.CREATED,
            FinalPlanTargetKind.REPAIR,
            repairId,
            null,
            repairId,
            "{\"outcome\":\"CREATED\"}"));
    publications.saveAndFlush(succeeded);

    UUID legacyRepairId = UUID.randomUUID();
    InventoryPublicationIntent legacySucceeded =
        publications.saveAndFlush(
            InventoryPublicationIntent.readyForOutcome(
                inventoryId,
                legacySucceededWork.getId(),
                Math.max(1, legacySucceededWork.getRevision()),
                1,
                finalPlanSha256,
                FinalPlanTargetKind.REPAIR,
                InventoryAssetOutcomeStatus.REPAIR));
    initializePublicationEvent(legacySucceeded, inventoryId);
    legacySucceeded.request("a".repeat(64), null);
    legacySucceeded.succeed(legacyRepairId);
    publications.saveAndFlush(legacySucceeded);

    InventoryFurnitureReconciliationIntent furniture =
        InventoryFurnitureReconciliationIntent.pending(
            inventoryId,
            UUID.randomUUID(),
            "6".repeat(64),
            "7".repeat(64),
            "8".repeat(64),
            "{}");
    furniture = furnitureReconciliations.saveAndFlush(furniture);
    furniture.block("SOURCE_PRECONDITION_CONFLICT");
    furnitureReconciliations.saveAndFlush(furniture);

    UUID commandKey = UUID.randomUUID();
    OutcomeRecalculation result =
        service.recalculateOutcome(
            jwt(),
            inventoryId,
            commandKey,
            new RecalculateInventoryOutcomeRequest(1L, 1L, finalPlanSha256));

    assertThat(result.createdPublicationCount()).isEqualTo(1);
    assertThat(result.requeuedPublicationCount()).isEqualTo(3);
    assertThat(result.finalPlanVersion()).isEqualTo(2L);
    assertThat(result.finalPlanSha256()).isNotEqualTo(finalPlanSha256);
    assertThat(result.furnitureReconciliationState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.FurnitureReconciliationState.PENDING);
    List<InventoryPublicationIntent> recalculated =
        publications.findAllByInventoryIdOrderByFindingId(inventoryId);
    assertThat(recalculated).hasSize(4);
    assertThat(
            publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
                .map(InventoryPublicationIntent::getOutcomeReapplicationNo))
        .containsOnly(1L);
    assertThat(
            publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
                .map(InventoryPublicationIntent::getFinalPlanVersion))
        .containsOnly(2L);
    assertThat(
            finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
                inventoryId, 2L))
        .allSatisfy(
            entry -> {
              assertThat(entry.getDispositionKind())
                  .isEqualTo(
                      dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind.LOCAL);
              assertThat(mapper.readTree(entry.getDispositionDetails()))
                  .isEqualTo(mapper.readTree("{\"formerRental\":null}"));
            });
    assertThat(
            recalculated.stream()
                .filter(value -> value.getFindingId().equals(succeededWork.getId()))
                .findFirst()
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getState())
                  .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.READY);
              assertThat(value.getTargetId()).isNull();
              assertThat(value.getAssetOutcomeResult()).isNull();
            });
    assertThat(
            recalculated.stream()
                .filter(value -> value.getFindingId().equals(capitalWork.getId()))
                .findFirst()
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getState())
                  .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.READY);
              assertThat(value.getDesiredAssetStatus())
                  .isEqualTo(InventoryAssetOutcomeStatus.CAPITAL_REPAIR);
              assertThat(value.getTargetKind()).isEqualTo(FinalPlanTargetKind.REPAIR);
            });
    assertThat(
            recalculated.stream()
                .filter(value -> value.getFindingId().equals(legacySucceededWork.getId()))
                .findFirst()
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getState())
                  .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.READY);
              assertThat(value.getDesiredAssetStatus())
                  .isEqualTo(InventoryAssetOutcomeStatus.REPAIR);
              assertThat(value.getTargetId()).isNull();
              assertThat(value.getAssetOutcomeResult()).isNull();
            });
    assertThat(
            recalculated.stream()
                .filter(value -> value.getFindingId().equals(noWork.getId()))
                .findFirst()
                .orElseThrow())
        .satisfies(
            value -> {
              assertThat(value.getState())
                  .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.READY);
              assertThat(value.getDesiredAssetStatus()).isEqualTo(InventoryAssetOutcomeStatus.FREE);
              assertThat(value.getTargetKind()).isNull();
            });
    OutcomeRecalculation replay =
        service.recalculateOutcome(
            jwt(),
            inventoryId,
            commandKey,
            new RecalculateInventoryOutcomeRequest(1L, 1L, finalPlanSha256));
    JsonNode replayJson = mapper.valueToTree(replay);
    JsonNode initialJson = mapper.valueToTree(result);
    assertThat(replayJson).isEqualTo(initialJson);
    assertThat(
            jdbc.queryForObject(
                "select final_plan_version from inventory_final_plan where inventory_id=?",
                Long.class,
                inventoryId))
        .isEqualTo(2L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_plan_logistics_effect where inventory_id=? and final_plan_version=2",
                Integer.class,
                inventoryId))
        .isEqualTo(1);
    verify(dependencies, times(0)).applyInventoryOutcome(any(), any(), any(), any());
    verify(dependencies, times(0)).applyReconciliation(any(), any(), any(), any());
  }

  @Test
  void historyRecoveryRestoresOmittedRentedObservationIntoANewerCompletedPlan() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    String originalPlanSha256 = "8".repeat(64);
    seedSession(inventoryId, warehouseId);
    InventoryFinding retained =
        stageRecoveryFinding(inventoryId, warehouseId, "REC-RETAINED", false);
    InventoryFinding omitted = stageRentedFinalPlanFinding(inventoryId, warehouseId, "230847");
    events.initialize(
        "FINDING",
        omitted.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);
    omitted.changeMembership(false);
    omitted = findings.saveAndFlush(omitted);
    assertThat(omitted.isOwnerProofActive()).isFalse();
    seedCompletedFinalPlan(
        inventoryId,
        originalPlanSha256,
        List.of(recoveryFinalPlanEntry(inventoryId, retained, 0, false, false)));
    UUID commandKey = UUID.randomUUID();
    RecalculateInventoryOutcomeRequest request =
        new RecalculateInventoryOutcomeRequest(1L, 1L, originalPlanSha256);

    OutcomeRecalculation corrected =
        service.recalculateOutcome(jwt(), inventoryId, commandKey, request);

    assertThat(corrected.finalPlanVersion()).isEqualTo(2L);
    assertThat(corrected.finalPlanSha256()).isNotEqualTo(originalPlanSha256);
    assertThat(corrected.createdPublicationCount()).isEqualTo(2);
    assertThat(corrected.requeuedPublicationCount()).isZero();
    assertThat(publications.findAllByInventoryIdOrderByFindingId(inventoryId))
        .extracting(
            InventoryPublicationIntent::getFindingId,
            InventoryPublicationIntent::getDesiredAssetStatus)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple(retained.getId(), InventoryAssetOutcomeStatus.FREE),
            org.assertj.core.groups.Tuple.tuple(
                omitted.getId(), InventoryAssetOutcomeStatus.REPAIR));
    InventoryFinding restored = findings.findById(omitted.getId()).orElseThrow();
    assertThat(restored.isMembershipActive()).isTrue();
    assertThat(restored.isOwnerProofActive()).isFalse();
    assertThat(restored.getCurrentStatus()).isEqualTo("RENTED");
    assertThat(restored.getReconciliation()).isEqualTo(ReconciliationState.MATCHED);
    assertThat(
            finalPlanEntries.findByInventoryIdAndFinalPlanVersionOrderByOrderAscFindingIdAsc(
                inventoryId, 2L))
        .hasSize(2)
        .extracting(InventoryFinalPlanEntry::getFindingId)
        .containsExactly(retained.getId(), omitted.getId());
    assertThat(
            jdbc.queryForMap(
                "select inspected_count,ready_count,with_work_count,unexpected_existing_count "
                    + "from inventory_completion_statistics where inventory_id=?",
                inventoryId))
        .containsEntry("inspected_count", 2)
        .containsEntry("ready_count", 1)
        .containsEntry("with_work_count", 1)
        .containsEntry("unexpected_existing_count", 2);
    assertThat(
            jdbc.queryForList(
                "select event_type from domain_event where aggregate_type='FINDING' "
                    + "and aggregate_id=? order by aggregate_version",
                String.class,
                omitted.getId().toString()))
        .containsExactly("inventory.finding.added.v1", "inventory.finding.membership-restored.v1");

    OutcomeRecalculation replay =
        service.recalculateOutcome(jwt(), inventoryId, commandKey, request);
    JsonNode replayJson = mapper.valueToTree(replay);
    JsonNode correctedJson = mapper.valueToTree(corrected);
    assertThat(replayJson).isEqualTo(correctedJson);
    verify(dependencies, times(0)).preflightReconciliation(any(), any());
  }

  @Test
  void publishesCompletedNoWorkPhotosAndSkipsMediaForAnExactRevisionWithoutImages() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID coverMediaId = UUID.randomUUID();
    String finalPlanSha256 = "b".repeat(64);
    seedSession(inventoryId, warehouseId);
    InventoryFinding photographed =
        stagePublicationFinding(
            inventoryId, warehouseId, "PHOTO-FREE", false, coverMediaId, 3);
    InventoryFinding withoutImages =
        stagePublicationFinding(inventoryId, warehouseId, "NO-PHOTO-FREE", false, null, 0);
    seedCompletedFinalPlan(
        inventoryId,
        finalPlanSha256,
        List.of(
            recoveryFinalPlanEntry(inventoryId, photographed, 0, false, false),
            recoveryFinalPlanEntry(inventoryId, withoutImages, 1, false, false)));
    InventoryPublicationIntent photographedIntent =
        saveOutcomePublicationIntent(
            inventoryId,
            photographed,
            finalPlanSha256,
            null,
            InventoryAssetOutcomeStatus.FREE);
    InventoryPublicationIntent withoutImagesIntent =
        saveOutcomePublicationIntent(
            inventoryId,
            withoutImages,
            finalPlanSha256,
            null,
            InventoryAssetOutcomeStatus.FREE);
    AtomicReference<JsonNode> photoRequest = new AtomicReference<>();
    AtomicReference<UUID> photoKey = new AtomicReference<>();
    when(dependencies.publishInventoryCabinPhotos(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(
                      jdbc.queryForObject(
                          "select state from inventory_publication_intent where id=?",
                          String.class,
                          photographedIntent.getId()))
                  .as("publication cannot succeed before media acknowledges the folder")
                  .isEqualTo("PENDING");
              JsonNode request = invocation.getArgument(3);
              photoRequest.set(request);
              photoKey.set(invocation.getArgument(2));
              return new InventoryDependencyGateway.InventoryCabinPhotoOutcome(
                  inventoryId,
                  photographed.getId(),
                  photographed.getAssetId(),
                  UUID.randomUUID(),
                  coverMediaId,
                  request.path("mediaReferences").size(),
                  6,
                  false);
            });

    service.recoverPendingPublications();

    assertThat(photoRequest.get()).isNotNull();
    assertThat(photoRequest.get().size()).isEqualTo(8);
    assertThat(photoRequest.get().path("warehouseId").asText())
        .isEqualTo(warehouseId.toString());
    assertThat(photoRequest.get().path("cabinId").asText())
        .isEqualTo(photographed.getAssetId().toString());
    assertThat(photoRequest.get().path("sourceRevision").asLong())
        .isEqualTo(photographed.getRevision());
    assertThat(photoRequest.get().path("finalPlanVersion").asLong()).isEqualTo(1);
    assertThat(photoRequest.get().path("finalPlanSha256").asText())
        .isEqualTo(finalPlanSha256);
    assertThat(photoRequest.get().path("coverMediaId").asText())
        .isEqualTo(coverMediaId.toString());
    assertThat(photoRequest.get().path("mediaReferences"))
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.path("mediaId").asText())
                  .isEqualTo(coverMediaId.toString());
              assertThat(reference.path("generation").asLong()).isEqualTo(3);
            });
    assertThat(photoKey.get()).isNotNull();
    assertThat(publications.findById(photographedIntent.getId()).orElseThrow().getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.SUCCEEDED);
    assertThat(publications.findById(withoutImagesIntent.getId()).orElseThrow().getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.SUCCEEDED);
    verify(dependencies, times(2)).applyInventoryOutcome(any(), any(), any(), any());
    verify(dependencies, times(1))
        .publishInventoryCabinPhotos(
            eq(inventoryId), eq(photographed.getId()), any(), any());
    verify(dependencies, never())
        .publishInventoryCabinPhotos(
            eq(inventoryId), eq(withoutImages.getId()), any(), any());
    ArgumentCaptor<UUID> logisticsKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<JsonNode> logisticsRequests = ArgumentCaptor.forClass(JsonNode.class);
    verify(dependencies)
        .applyLogisticsOutcomes(
            eq(inventoryId), logisticsKeys.capture(), logisticsRequests.capture());
    JsonNode logisticsRequest = logisticsRequests.getValue();
    assertThat(logisticsRequest.path("warehouseId").asText())
        .isEqualTo(warehouseId.toString());
    assertThat(logisticsRequest.path("inventoryCompletedAt").asText()).isNotBlank();
    assertThat(logisticsRequest.path("finalPlanVersion").asLong()).isEqualTo(1);
    assertThat(logisticsRequest.path("finalPlanSha256").asText())
        .isEqualTo(finalPlanSha256);
    assertThat(logisticsRequest.path("outcomes")).hasSize(2);
    assertThat(logisticsRequest.path("outcomes"))
        .allSatisfy(
            outcome -> assertThat(outcome.path("desiredStatus").asText()).isEqualTo("FREE"));
    verify(dependencies, times(2)).applyNoWorkDisposition(eq(inventoryId), any(), any(), any());
    verify(dependencies, never()).applyReconciliation(any(), any(), any(), any());
    InOrder order = inOrder(dependencies);
    order.verify(dependencies)
        .applyInventoryOutcome(eq(inventoryId), eq(photographed.getId()), any(), any());
    order.verify(dependencies)
        .publishInventoryCabinPhotos(eq(inventoryId), eq(photographed.getId()), any(), any());
  }

  @Test
  void schedulerRetriesAssetThenPhotosThenMaintenanceWithStableOwnerKey() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID coverMediaId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    String finalPlanSha256 = "c".repeat(64);
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        stagePublicationFinding(
            inventoryId, warehouseId, "PHOTO-REPAIR", true, coverMediaId, 2);
    savePublicationPlanSnapshot(inventoryId, finding, coverMediaId, 2);
    seedCompletedFinalPlan(
        inventoryId,
        finalPlanSha256,
        List.of(recoveryFinalPlanEntry(inventoryId, finding, 0, true, false)));
    InventoryPublicationIntent intent =
        saveOutcomePublicationIntent(
            inventoryId,
            finding,
            finalPlanSha256,
            FinalPlanTargetKind.REPAIR,
            InventoryAssetOutcomeStatus.REPAIR);
    AtomicReference<Integer> mediaCall = new AtomicReference<>(0);
    when(dependencies.publishInventoryCabinPhotos(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              int call = mediaCall.updateAndGet(value -> value + 1);
              assertThat(
                      jdbc.queryForObject(
                          "select state from inventory_publication_intent where id=?",
                          String.class,
                          intent.getId()))
                  .as("media is a required step inside the pending publication attempt")
                  .isEqualTo("PENDING");
              if (call == 1) {
                throw InventoryException.dependency("simulated media transport failure");
              }
              JsonNode request = invocation.getArgument(3);
              return new InventoryDependencyGateway.InventoryCabinPhotoOutcome(
                  inventoryId,
                  finding.getId(),
                  finding.getAssetId(),
                  UUID.randomUUID(),
                  coverMediaId,
                  request.path("mediaReferences").size(),
                  8,
                  true);
            });
    when(dependencies.applyReconciliation(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              var response = mapper.createObjectNode();
              response.put("outcome", "CREATED");
              response.put("targetKind", "REPAIR");
              response.put("targetId", repairId.toString());
              response.putNull("estimateId");
              response.put("repairId", repairId.toString());
              response
                  .putObject("source")
                  .put("inventoryId", inventoryId.toString())
                  .put("finalPlanVersion", 1)
                  .put("findingId", finding.getId().toString());
              response.putNull("successor");
              response.putObject("delta").putArray("lines");
              return response;
            });

    service.recoverPendingPublications();

    InventoryPublicationIntent failed = publications.findById(intent.getId()).orElseThrow();
    assertThat(failed.getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.TRANSIENT_FAILED);
    assertThat(failed.getAssetOutcomeResult()).isNotBlank();
    assertThat(failed.getGenerationAttemptCount()).isEqualTo(1);
    assertThat(failed.getNextAttemptAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
    verify(dependencies, never()).applyReconciliation(any(), any(), any(), any());

    jdbc.update(
        "update inventory_publication_intent set next_attempt_at=clock_timestamp()-interval '1 second' where id=?",
        intent.getId());
    service.recoverPendingPublications();

    InventoryPublicationIntent succeeded = publications.findById(intent.getId()).orElseThrow();
    assertThat(succeeded.getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.SUCCEEDED);
    assertThat(succeeded.getMaintenanceRepairId()).isEqualTo(repairId);
    assertThat(succeeded.getGenerationAttemptCount()).isEqualTo(2);
    ArgumentCaptor<UUID> assetKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> mediaKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> maintenanceKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .applyInventoryOutcome(
            eq(inventoryId), eq(finding.getId()), assetKeys.capture(), any());
    verify(dependencies, times(2))
        .publishInventoryCabinPhotos(
            eq(inventoryId), eq(finding.getId()), mediaKeys.capture(), any());
    verify(dependencies)
        .applyReconciliation(
            eq(inventoryId), eq(finding.getId()), maintenanceKeys.capture(), any());
    verify(dependencies)
        .applyLogisticsOutcomes(eq(inventoryId), any(), any());
    assertThat(assetKeys.getAllValues()).containsOnly(assetKeys.getValue());
    assertThat(mediaKeys.getAllValues()).containsOnly(mediaKeys.getValue());
    assertThat(assetKeys.getValue()).isEqualTo(mediaKeys.getValue());
    assertThat(mediaKeys.getValue()).isEqualTo(maintenanceKeys.getValue());
    InOrder order = inOrder(dependencies);
    order.verify(dependencies)
        .applyInventoryOutcome(eq(inventoryId), eq(finding.getId()), any(), any());
    order.verify(dependencies)
        .publishInventoryCabinPhotos(eq(inventoryId), eq(finding.getId()), any(), any());
    order.verify(dependencies)
        .applyInventoryOutcome(eq(inventoryId), eq(finding.getId()), any(), any());
    order.verify(dependencies)
        .publishInventoryCabinPhotos(eq(inventoryId), eq(finding.getId()), any(), any());
    order.verify(dependencies)
        .applyLogisticsOutcomes(eq(inventoryId), any(), any());
    order.verify(dependencies)
        .applyReconciliation(eq(inventoryId), eq(finding.getId()), any(), any());
  }

  @Test
  void publicationRecoveryBacksOffRetryable4xxStopsAtEightAndRecalculationRenewsOnlyBudget() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    String finalPlanSha256 = "7".repeat(64);
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        stagePublicationFinding(inventoryId, warehouseId, "RETRY-BOUND", false, null, 0);
    seedCompletedFinalPlan(
        inventoryId,
        finalPlanSha256,
        List.of(recoveryFinalPlanEntry(inventoryId, finding, 0, false, false)));
    InventoryPublicationIntent intent =
        saveOutcomePublicationIntent(
            inventoryId, finding, finalPlanSha256, null, InventoryAssetOutcomeStatus.FREE);
    List<HttpStatus> retryableStatuses =
        List.of(HttpStatus.REQUEST_TIMEOUT, HttpStatus.TOO_EARLY, HttpStatus.TOO_MANY_REQUESTS);
    AtomicInteger attempts = new AtomicInteger();
    doAnswer(
            ignored -> {
              int attempt = attempts.getAndIncrement();
              HttpStatus status = retryableStatuses.get(Math.min(attempt, 2));
              throw new InventoryException(status, "OWNER_RETRY_LATER", "Owner asked to retry");
            })
        .when(dependencies)
        .applyInventoryOutcome(any(), any(), any(), any());

    for (int attempt = 1; attempt <= 8; attempt++) {
      service.recoverPendingPublications();
      InventoryPublicationIntent current = publications.findById(intent.getId()).orElseThrow();
      assertThat(current.getAttemptCount()).isEqualTo(attempt);
      assertThat(current.getGenerationAttemptCount()).isEqualTo(attempt);
      if (attempt < 8) {
        assertThat(current.getState())
            .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.TRANSIENT_FAILED);
        assertThat(current.getNextAttemptAt()).isAfter(OffsetDateTime.now(ZoneOffset.UTC));
        jdbc.update(
            "update inventory_publication_intent set next_attempt_at=clock_timestamp()-interval '1 second' where id=?",
            intent.getId());
      }
    }

    InventoryPublicationIntent exhausted = publications.findById(intent.getId()).orElseThrow();
    assertThat(exhausted.getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.BLOCKED);
    assertThat(exhausted.getBlockedFailureCode()).isEqualTo("PUBLICATION_RETRY_EXHAUSTED");
    service.recoverPendingPublications();
    verify(dependencies, times(8)).applyInventoryOutcome(any(), any(), any(), any());

    UUID recalculationKey = UUID.randomUUID();
    RecalculateInventoryOutcomeRequest request =
        new RecalculateInventoryOutcomeRequest(1L, 1L, finalPlanSha256);
    OutcomeRecalculation recalculated =
        service.recalculateOutcome(jwt(), inventoryId, recalculationKey, request);
    OutcomeRecalculation replayed =
        service.recalculateOutcome(jwt(), inventoryId, recalculationKey, request);
    JsonNode replayedJson = mapper.valueToTree(replayed);
    JsonNode recalculatedJson = mapper.valueToTree(recalculated);
    assertThat(replayedJson).isEqualTo(recalculatedJson);
    assertThat(recalculated.finalPlanVersion()).isEqualTo(2L);
    InventoryPublicationIntent requeued = publications.findById(intent.getId()).orElseThrow();
    assertThat(requeued.getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.READY);
    assertThat(requeued.getAttemptCount()).isEqualTo(8);
    assertThat(requeued.getGenerationAttemptCount()).isZero();
    assertThat(requeued.getOutcomeReapplicationNo()).isEqualTo(1L);
    assertThat(requeued.getFinalPlanVersion()).isEqualTo(2L);
  }

  @Test
  void lostResponseRetryReusesOwnerKeysAndHistoryRecalculationAdvancesOneSharedGeneration() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID coverMediaId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    String finalPlanSha256 = "d".repeat(64);
    seedSession(inventoryId, warehouseId);
    InventoryFinding work =
        stagePublicationFinding(
            inventoryId, warehouseId, "EPOCH-WORK", true, coverMediaId, 4);
    InventoryFinding noWork =
        stagePublicationFinding(inventoryId, warehouseId, "EPOCH-FREE", false, null, 0);
    savePublicationPlanSnapshot(inventoryId, work, coverMediaId, 4);
    seedCompletedFinalPlan(
        inventoryId,
        finalPlanSha256,
        List.of(
            recoveryFinalPlanEntry(inventoryId, work, 0, true, false),
            recoveryFinalPlanEntry(inventoryId, noWork, 1, false, false)));
    InventoryPublicationIntent workIntent =
        saveOutcomePublicationIntent(
            inventoryId,
            work,
            finalPlanSha256,
            FinalPlanTargetKind.REPAIR,
            InventoryAssetOutcomeStatus.REPAIR);
    InventoryPublicationIntent noWorkIntent =
        saveOutcomePublicationIntent(
            inventoryId,
            noWork,
            finalPlanSha256,
            null,
            InventoryAssetOutcomeStatus.FREE);
    jdbc.update(
        "update inventory_publication_intent set updated_at=updated_at + interval '1 minute' where id=?",
        noWorkIntent.getId());

    when(dependencies.publishInventoryCabinPhotos(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              JsonNode request = invocation.getArgument(3);
              return new InventoryDependencyGateway.InventoryCabinPhotoOutcome(
                  inventoryId,
                  work.getId(),
                  work.getAssetId(),
                  UUID.randomUUID(),
                  coverMediaId,
                  request.path("mediaReferences").size(),
                  10,
                  false);
            });
    AtomicBoolean loseFirstMaintenanceResponse = new AtomicBoolean(true);
    when(dependencies.applyReconciliation(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              if (loseFirstMaintenanceResponse.getAndSet(false)) {
                throw new AssertionError(
                    "simulated process loss after maintenance accepted the owner key");
              }
              var response = mapper.createObjectNode();
              response.put("outcome", "CREATED");
              response.put("targetKind", "REPAIR");
              response.put("targetId", repairId.toString());
              response.putNull("estimateId");
              response.put("repairId", repairId.toString());
              response
                  .putObject("source")
                  .put("inventoryId", inventoryId.toString())
                  .put("finalPlanVersion", 1)
                  .put("findingId", work.getId().toString());
              response.putNull("successor");
              response.putObject("delta").putArray("lines");
              return response;
            });

    assertThatThrownBy(service::recoverPendingPublications)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("maintenance accepted the owner key");
    assertThat(publications.findById(workIntent.getId()).orElseThrow().getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.PENDING);
    jdbc.update(
        """
        update inventory_publication_intent
           set created_at=created_at - interval '31 seconds',
               updated_at=updated_at - interval '31 seconds'
         where id=?
        """,
        workIntent.getId());

    service.recoverPendingPublications();

    assertThat(publications.findById(workIntent.getId()).orElseThrow().getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.SUCCEEDED);
    assertThat(publications.findById(noWorkIntent.getId()).orElseThrow().getState())
        .isEqualTo(dev.buhanzaz.rwms.inventory.domain.PublicationState.SUCCEEDED);
    OutcomeRecalculation recalculated =
        service.recalculateOutcome(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new RecalculateInventoryOutcomeRequest(1L, 1L, finalPlanSha256));
    assertThat(recalculated.createdPublicationCount()).isZero();
    assertThat(recalculated.requeuedPublicationCount()).isEqualTo(2);
    assertThat(
            publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
                .map(InventoryPublicationIntent::getOutcomeReapplicationNo))
        .containsOnly(1L);

    service.recoverPendingPublications();

    ArgumentCaptor<UUID> assetKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> mediaKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> logisticsKeys = ArgumentCaptor.forClass(UUID.class);
    ArgumentCaptor<UUID> maintenanceKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(3))
        .applyInventoryOutcome(eq(inventoryId), eq(work.getId()), assetKeys.capture(), any());
    verify(dependencies, times(3))
        .publishInventoryCabinPhotos(
            eq(inventoryId), eq(work.getId()), mediaKeys.capture(), any());
    verify(dependencies, times(2))
        .applyLogisticsOutcomes(eq(inventoryId), logisticsKeys.capture(), any());
    verify(dependencies, times(3))
        .applyReconciliation(
            eq(inventoryId), eq(work.getId()), maintenanceKeys.capture(), any());
    verify(dependencies, times(2))
        .applyNoWorkDisposition(eq(inventoryId), eq(noWork.getId()), any(), any());

    UUID initialOwnerKey = assetKeys.getAllValues().get(0);
    UUID reappliedOwnerKey = assetKeys.getAllValues().get(2);
    assertThat(assetKeys.getAllValues().subList(0, 2)).containsOnly(initialOwnerKey);
    assertThat(mediaKeys.getAllValues().subList(0, 2)).containsOnly(initialOwnerKey);
    assertThat(maintenanceKeys.getAllValues().subList(0, 2)).containsOnly(initialOwnerKey);
    assertThat(mediaKeys.getAllValues().get(2)).isEqualTo(reappliedOwnerKey);
    assertThat(maintenanceKeys.getAllValues().get(2)).isEqualTo(reappliedOwnerKey);
    assertThat(reappliedOwnerKey).isNotEqualTo(initialOwnerKey);

    UUID initialLogisticsKey = logisticsKeys.getAllValues().get(0);
    UUID reappliedLogisticsKey = logisticsKeys.getAllValues().get(1);
    assertThat(reappliedLogisticsKey).isNotEqualTo(initialLogisticsKey);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_plan_logistics_effect where inventory_id=?",
                Long.class,
                inventoryId))
        .isEqualTo(2L);
    assertThat(
            publications.findAllByInventoryIdOrderByFindingId(inventoryId).stream()
                .map(InventoryPublicationIntent::getOutcomeReapplicationNo))
        .containsOnly(1L);
  }

  @Test
  void frozenPlanFingerprintUsesTheV12PostgresJsonbUtf8Rule() {
    String serialized =
        """
        {"z":"late","a":{"b":true,"a":null},"items":[2,1]}
        """;
    JsonNode snapshot = mapper.readTree(serialized);
    String expected =
        jdbc.queryForObject(
            """
            select encode(sha256(convert_to((?::jsonb)::text, 'UTF8')), 'hex')
            """,
            String.class,
            serialized);

    assertThat(frozenPlanFingerprint.sha256(snapshot)).isEqualTo(expected);
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

    UUID boundedHistoryWarehouseId = UUID.randomUUID();
    for (int index = 0; index < 12; index++) {
      UUID boundedInventoryId = UUID.randomUUID();
      seedSession(boundedInventoryId, boundedHistoryWarehouseId);
      markCancelled(boundedInventoryId);
    }
    Statistics historyStatistics =
        entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    historyStatistics.clear();
    PageResponse<SessionSummary> boundedHistory =
        service.sessions(
            jwt(),
            boundedHistoryWarehouseId,
            null,
            null,
            null,
            null,
            null,
            null,
            null,
            0,
            20,
            "startedAt,asc");
    assertThat(boundedHistory.content()).hasSize(12);
    assertThat(historyStatistics.getPrepareStatementCount()).isLessThanOrEqualTo(6);

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
    assertThat(expected.frozenPlan().lines().getFirst().mediaReferences())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.mediaId())
                  .isEqualTo(UUID.fromString("00000000-0000-0000-0000-000000000801"));
              assertThat(reference.generation()).isEqualTo(4);
            });
    assertThat(expected.frozenPlan().lines().get(1).mediaReferences()).isEmpty();
    assertThat(expected.media()).isEmpty();
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
    assertThat(
            exact
                .required("frozenPlan")
                .required("lines")
                .get(0)
                .required("mediaReferences")
                .get(0)
                .required("generation")
                .asLong())
        .isEqualTo(4);
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
    snapshot.putNull("logisticsPlanningMode");
    snapshot.putNull("logisticsScheduledDate");
    var sourceLine = snapshot.putArray("lines").addObject();
    sourceLine.put("aggregationKind", "CATALOG");
    sourceLine.put("catalogVersionId", catalogVersionId.toString());
    sourceLine.put("catalogNodeId", materialNodeId.toString());
    sourceLine.put("catalogNodeName", "ДВП");
    sourceLine.put("type", "MATERIAL");
    sourceLine.put("description", "ДВП");
    sourceLine.putNull("normalizedDescription");
    sourceLine.put("unit", "PCS");
    sourceLine.put("quantity", "2");
    sourceLine.put("unitPriceMinor", 125);
    sourceLine.put("normativeMinutes", "0");
    sourceLine
        .putObject("routing")
        .put("queueId", queueId.toString())
        .put("queueName", "Материалы")
        .put("queueType", "MAINTENANCE");
    sourceLine.putNull("groupComment");
    sourceLine.putArray("mediaReferences");
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
                    null,
                    null,
                    List.of(
                        new PlanLineInput(
                            "CATALOG",
                            materialNodeId,
                            null,
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
    assertThat(freezeRequest.get().required("logisticsPlanningMode").isNull()).isTrue();
    assertThat(freezeRequest.get().required("logisticsScheduledDate").isNull())
        .isTrue();
    assertThat(freezeRequest.get().required("lines")).hasSize(1);
    assertThat(freezeRequest.get().required("lines").get(0)
        .required("routingCatalogNodeId").isNull()).isTrue();
    assertThat(saved.inspectionSource()).isEqualTo("INVENTORY");
    assertThat(saved.frozenPlan().priority()).isEqualTo(4);
    assertThat(saved.frozenPlan().movementToRepair()).isFalse();
    assertThat(saved.frozenPlan().logisticsPlanningMode()).isNull();
    assertThat(saved.frozenPlan().logisticsScheduledDate()).isNull();
    assertThat(saved.frozenPlan().lines())
        .singleElement()
        .satisfies(
            line -> {
              assertThat(line.lineType()).isEqualTo("MATERIAL");
              assertThat(line.description()).isEqualTo("ДВП");
              assertThat(line.quantity()).isEqualTo("2");
              assertThat(line.routingQueueId()).isEqualTo(queueId);
              assertThat(line.routingQueueName()).isEqualTo("Материалы");
              assertThat(line.routingQueueType()).isEqualTo("MAINTENANCE");
            });

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), saved.findingRevision())));
    PreparedFinalPlan finalPlan = prepareFinalPlan(inventoryId, furnitureReview);
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(furnitureReview, finalPlan));
    assertThat(preview.statistics().workLineCount()).isZero();
    assertThat(preview.statistics().materialLineCount()).isOne();
    assertThat(preview.statistics().materialTotalMinor()).isEqualTo(250);
    assertThat(preview.statistics().normativeMinutes()).isEqualTo("0");

    UUID repairId = UUID.randomUUID();
    List<UUID> publicationKeys = new ArrayList<>();
    AtomicReference<JsonNode> maintenanceApplyRequest = new AtomicReference<>();
    when(dependencies.applyReconciliation(any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              publicationKeys.add(invocation.getArgument(2));
              maintenanceApplyRequest.set(invocation.getArgument(3));
              var response = mapper.createObjectNode();
              response.put("outcome", "CREATED");
              response.put("targetKind", "REPAIR");
              response.put("targetId", repairId.toString());
              response.putNull("estimateId");
              response.put("repairId", repairId.toString());
              response
                  .putObject("source")
                  .put("inventoryId", inventoryId.toString())
                  .put("finalPlanVersion", finalPlan.version())
                  .put("findingId", finding.getId().toString());
              response.putNull("successor");
              response.putObject("delta").putArray("lines");
              return response;
            });
    SessionView completed =
        service.complete(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompleteSessionRequest(
                preview.sessionRevision(),
                preview.finalPlanVersion(),
                preview.finalPlanSha256(),
                preview.findingRevisions(),
                preview.acknowledgementSha256(),
                preview.validationSha256()));
    assertThat(completed.lifecycle()).isEqualTo(SessionLifecycle.COMPLETED);
    assertThat(publicationKeys).isEmpty();
    assertThat(
            jdbc.queryForMap(
                "select state,attempt_count from inventory_plan_logistics_effect where inventory_id=?",
                inventoryId))
        .containsEntry("state", "READY")
        .containsEntry("attempt_count", 0);

    service.recoverPendingPublications();

    assertThat(publicationKeys).hasSize(1);
    assertThat(maintenanceApplyRequest.get().has("targetKind")).isFalse();
    assertThat(maintenanceApplyRequest.get().required("finalPlanVersion").asLong())
        .isEqualTo(finalPlan.version());
    assertThat(maintenanceApplyRequest.get().required("inventoryCompletedAt").asText())
        .isNotBlank();
    assertThat(maintenanceApplyRequest.get().required("authoritativeAssetVersion").asLong())
        .isEqualTo(100);
    assertThat(
            jdbc.queryForMap(
                "select state,attempt_count from inventory_plan_logistics_effect where inventory_id=?",
                inventoryId))
        .containsEntry("state", "SUCCEEDED")
        .containsEntry("attempt_count", 1);

    UUID recoveryAssetId = UUID.randomUUID();
    InventoryFinding recoveryFinding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            recoveryAssetId,
            2L,
            warehouseId,
            "WAREHOUSE",
            null,
            "БЫТ-RECOVERY",
            "БЫТRECOVERY",
            ReconciliationState.MATCHED,
            ACTOR);
    recoveryFinding.saveInspection(
        InspectionState.WORK_STAGED,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        fingerprint,
        "Восстановление публикации",
        ACTOR);
    recoveryFinding = findings.saveAndFlush(recoveryFinding);
    UUID recoveryFindingId = recoveryFinding.getId();
    long recoverySourceRevision = Math.max(1, recoveryFinding.getRevision());
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            recoveryFinding.getId(),
            recoverySourceRevision,
            inventoryId,
            "AUTO",
            false,
            null,
            null,
            catalogVersionId,
            fingerprint,
            snapshot.toString(),
            2));
    finalPlanEntries.saveAndFlush(
        new InventoryFinalPlanEntry(
            inventoryId,
            finalPlan.version(),
            recoveryFinding.getId(),
            recoverySourceRevision,
            recoveryAssetId,
            2L,
            fingerprint,
            true,
            FinalPlanTargetKind.REPAIR,
            1,
            4,
            false,
            null,
            LocalDate.of(2026, 8, 4),
            "[]",
            null));
    InventoryPublicationIntent recoveryIntent =
        publications.saveAndFlush(
            InventoryPublicationIntent.ready(
                inventoryId,
                recoveryFinding.getId(),
                recoverySourceRevision,
                finalPlan.version(),
                finalPlan.sha256(),
                FinalPlanTargetKind.REPAIR));
    events.initialize(
        "PUBLICATION",
        recoveryIntent.getId(),
        "inventory.publication.ready.v1",
        "rwms.inventory.publication.v1",
        mapper
            .createObjectNode()
            .put("inventoryId", inventoryId.toString())
            .put("findingId", recoveryFinding.getId().toString())
            .put("publicationIntentId", recoveryIntent.getId().toString()),
        UUID.randomUUID(),
        null,
        null);

    List<UUID> recoveryKeys = new ArrayList<>();
    AtomicBoolean simulateProcessLoss = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              recoveryKeys.add(invocation.getArgument(2));
              if (simulateProcessLoss.getAndSet(false)) {
                throw new AssertionError("simulated process loss after durable publication attempt");
              }
              var response = mapper.createObjectNode();
              response.put("outcome", "MATCHED");
              response.putNull("targetKind");
              response.putNull("targetId");
              response.putNull("estimateId");
              response.putNull("repairId");
              response
                  .putObject("source")
                  .put("inventoryId", inventoryId.toString())
                  .put("finalPlanVersion", finalPlan.version())
                  .put("findingId", recoveryFindingId.toString());
              response.putNull("successor");
              response.putObject("delta").putArray("lines");
              return response;
            })
        .when(dependencies)
        .applyReconciliation(any(), any(), any(), any());

    assertThatThrownBy(service::recoverPendingPublications)
        .isInstanceOf(AssertionError.class)
        .hasMessageContaining("simulated process loss");
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_publication_intent where id=?",
                String.class,
                recoveryIntent.getId()))
        .isEqualTo("PENDING");
    jdbc.update(
        """
        update inventory_publication_intent
           set created_at=created_at - interval '31 seconds',
               updated_at=updated_at - interval '31 seconds'
         where id=?
        """,
        recoveryIntent.getId());

    service.recoverPendingPublications();

    assertThat(recoveryKeys).hasSize(2);
    assertThat(recoveryKeys).allSatisfy(key -> assertThat(key).isEqualTo(recoveryKeys.getFirst()));
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_publication_intent where id=?",
                String.class,
                recoveryIntent.getId()))
        .isEqualTo("SUCCEEDED");
    assertThat(
            jdbc.queryForMap(
                """
                select target_kind,target_id,maintenance_estimate_id,maintenance_repair_id,
                       maintenance_outcome,maintenance_result::text as maintenance_result
                from inventory_publication_intent
                where id=?
                """,
                recoveryIntent.getId()))
        .containsEntry("target_kind", null)
        .containsEntry("target_id", null)
        .containsEntry("maintenance_estimate_id", null)
        .containsEntry("maintenance_repair_id", null)
        .containsEntry("maintenance_outcome", "MATCHED")
        .satisfies(
            row -> {
              JsonNode result = mapper.readTree((String) row.get("maintenance_result"));
              assertThat(result.path("outcome").asText()).isEqualTo("MATCHED");
              assertThat(result.path("delta").path("lines").isArray()).isTrue();
              assertThat(result.path("successor").isNull()).isTrue();
            });
    assertThat(
            jdbc.queryForMap(
                """
                select result.outcome,result.repair_id,result.maintenance_outcome,
                       result.maintenance_result::text as maintenance_result
                from inventory_publication_attempt attempt
                join inventory_publication_attempt_result result
                  on result.publication_attempt_id=attempt.id
                where attempt.publication_intent_id=?
                """,
                recoveryIntent.getId()))
        .containsEntry("outcome", "SUCCEEDED")
        .containsEntry("repair_id", null)
        .containsEntry("maintenance_outcome", "MATCHED")
        .satisfies(
            row ->
                assertThat(
                        mapper
                            .readTree((String) row.get("maintenance_result"))
                            .path("outcome")
                            .asText())
                    .isEqualTo("MATCHED"));
  }

  @Test
  void supplementsWithCurrentGenerationForRetainedWorkMediaWithoutRewritingHistory() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    UUID catalogVersionId = UUID.randomUUID();
    UUID workNodeId = UUID.randomUUID();
    UUID queueId = UUID.randomUUID();
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
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                assetId,
                1L,
                warehouseId,
                "WAREHOUSE",
                null,
                "БЫТ-MEDIA-REVISION",
                "БЫТMEDIAREVISION",
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

    InventoryDependencyGateway.ValidationItem currentAsset =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            1L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-MEDIA-REVISION",
            "БЫТMEDIAREVISION",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(List.of(currentAsset)),
                List.of(currentAsset)));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(assetId, List.of()))));

    mediaFacts.saveAndFlush(
        InventoryMediaFactProjection.create(
            mediaId, 1, 3, finding.getId(), warehouseId, "IMAGE", "READY", 0));
    mediaFacts.saveAndFlush(
        InventoryMediaFactProjection.create(
            mediaId, 3, 7, finding.getId(), warehouseId, "IMAGE", "READY", 180));

    ObjectNode historicalSnapshot = mapper.createObjectNode();
    historicalSnapshot.put("catalogVersionId", catalogVersionId.toString());
    historicalSnapshot.put("mode", "AUTO");
    historicalSnapshot.put("priority", 3);
    historicalSnapshot.putNull("coverMediaId");
    historicalSnapshot.put("movementToRepair", false);
    historicalSnapshot.putNull("logisticsPlanningMode");
    historicalSnapshot.putNull("logisticsScheduledDate");
    ObjectNode historicalLine = historicalSnapshot.putArray("lines").addObject();
    historicalLine.put("aggregationKind", "CATALOG");
    historicalLine.put("catalogVersionId", catalogVersionId.toString());
    historicalLine.put("catalogNodeId", workNodeId.toString());
    historicalLine.put("catalogNodeName", "Ремонт окна");
    historicalLine.put("type", "WORK");
    historicalLine.put("description", "Ремонт окна");
    historicalLine.putNull("normalizedDescription");
    historicalLine.put("unit", "PCS");
    historicalLine.put("quantity", "1");
    historicalLine.put("unitPriceMinor", 1000);
    historicalLine.put("normativeMinutes", "30");
    historicalLine.putNull("groupComment");
    historicalLine
        .putArray("mediaReferences")
        .addObject()
        .put("mediaId", mediaId.toString())
        .put("generation", 1);
    ObjectNode historicalStage = historicalSnapshot.putArray("stages").addObject();
    historicalStage.put("id", UUID.randomUUID().toString());
    historicalStage.put("catalogNodeId", workNodeId.toString());
    historicalStage.put("catalogNodeName", "Ремонт окна");
    historicalStage.put("kind", "REPAIR_WORK");
    historicalStage.put("order", 0);
    historicalStage
        .putObject("routing")
        .put("queueId", queueId.toString())
        .put("queueName", "Окна")
        .put("queueType", "REPAIR");
    historicalStage.put("normativeDurationMinutes", 30);
    historicalSnapshot.putArray("mediaReferences");
    String historicalFingerprint = frozenPlanFingerprint.sha256(historicalSnapshot);

    finding.saveInspection(
        InspectionState.WORK_STAGED,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        historicalFingerprint,
        "Первый осмотр",
        ACTOR);
    finding = findings.saveAndFlush(finding);
    assertThat(finding.getRevision()).isOne();
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            finding.getId(),
            1,
            inventoryId,
            "AUTO",
            false,
            null,
            null,
            catalogVersionId,
            historicalFingerprint,
            historicalSnapshot.toString(),
            2));

    ObjectNode currentSnapshot = historicalSnapshot.deepCopy();
    ((ObjectNode)
            currentSnapshot
                .required("lines")
                .get(0)
                .required("mediaReferences")
                .get(0))
        .put("generation", 3);
    String currentFingerprint = frozenPlanFingerprint.sha256(currentSnapshot);
    AtomicReference<JsonNode> freezeRequest = new AtomicReference<>();
    UUID findingId = finding.getId();
    when(dependencies.freezePlan(any(), any()))
        .thenAnswer(
            invocation -> {
              freezeRequest.set(invocation.getArgument(1));
              return new InventoryDependencyGateway.FrozenPlan(
                  warehouseId,
                  inventoryId,
                  findingId,
                  2,
                  currentSnapshot,
                  currentFingerprint);
            });

    FindingView supplemented =
        service.saveInspection(
            jwt(),
            inventoryId,
            findingId,
            new SaveInspectionRequest(
                0,
                1,
                InspectionState.WORK_STAGED,
                "Дополненный осмотр",
                new Observation(ObservationPresence.ABSENT, null),
                new Observation(ObservationPresence.ABSENT, null),
                List.of(),
                null,
                new PlanSelection(
                    "AUTO",
                    3,
                    null,
                    false,
                    null,
                    null,
                    List.of(
                        new PlanLineInput(
                            "CATALOG",
                            workNodeId,
                            null,
                            null,
                            null,
                            null,
                            "1",
                            null,
                            null,
                            null,
                            List.of(new MediaReference(mediaId, 1)))),
                    List.of(new PlanStageSelection(workNodeId, "REPAIR_WORK", 0)))));

    assertThat(supplemented.findingRevision()).isEqualTo(2);
    assertThat(
            freezeRequest
                .get()
                .required("lines")
                .get(0)
                .required("mediaReferences")
                .get(0)
                .required("generation")
                .asLong())
        .isEqualTo(3);
    assertThat(freezeRequest.get().required("sourceRevision").asLong()).isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                "select source_snapshot #>> '{lines,0,mediaReferences,0,generation}' from finding_plan_snapshot where finding_id=? and finding_revision=1",
                String.class,
                findingId))
        .isEqualTo("1");
    assertThat(
            jdbc.queryForObject(
                "select source_snapshot #>> '{lines,0,mediaReferences,0,generation}' from finding_plan_snapshot where finding_id=? and finding_revision=2",
                String.class,
                findingId))
        .isEqualTo("3");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finding_plan_snapshot where finding_id=?",
                Integer.class,
                findingId))
        .isEqualTo(2);
  }

  @Test
  void finalPlanRequiresAnActiveUniqueCandidateAndMergeForStartedWork() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding = stageFinalPlanFinding(inventoryId, warehouseId);
    confirmEmptyCabinDisposition(inventoryId);

    AtomicReference<List<JsonNode>> candidates = new AtomicReference<>(List.of());
    doAnswer(
            invocation -> {
              JsonNode request = invocation.getArgument(1);
              ObjectNode response = mapper.createObjectNode();
              response.put("inventoryId", request.required("inventoryId").asText());
              response.put("finalPlanVersion", request.required("finalPlanVersion").asLong());
              response.put("finalPlanSha256", request.required("finalPlanSha256").asText());
              ArrayNode responseFindings = response.putArray("findings");
              for (JsonNode requestedFinding : request.required("findings")) {
                ArrayNode responseCandidates =
                    responseFindings
                        .addObject()
                        .put("findingId", requestedFinding.required("findingId").asText())
                        .putArray("candidates");
                candidates.get().forEach(candidate -> responseCandidates.add(candidate.deepCopy()));
              }
              return response;
            })
        .when(dependencies)
        .preflightReconciliation(any(), any());

    var prepared =
        service.prepareFinalPlan(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new PrepareFinalPlanRequest(
                service.session(jwt(), inventoryId).sessionRevision(),
                0,
                FinalPlanScheduleMode.AUTO,
                FinalPlanScheduleMode.AUTO));
    var preparedEntry = prepared.entries().getFirst();

    UUID historicalTargetId = UUID.randomUUID();
    candidates.set(List.of(finalPlanCandidate(historicalTargetId, false, false)));
    assertThatThrownBy(
            () ->
                service.updateFinalPlan(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    finalPlanUpdateRequest(
                        prepared,
                        preparedEntry,
                        new FinalPlanReconciliationDecision(
                            FinalPlanReconciliationStrategy.REPLACE,
                            FinalPlanTargetKind.REPAIR,
                            historicalTargetId))))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.getMessage()).contains("active maintenance candidate");
            });

    UUID startedTargetId = UUID.randomUUID();
    candidates.set(List.of(finalPlanCandidate(startedTargetId, true, true)));
    assertThatThrownBy(
            () ->
                service.updateFinalPlan(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    finalPlanUpdateRequest(
                        prepared,
                        preparedEntry,
                        new FinalPlanReconciliationDecision(
                            FinalPlanReconciliationStrategy.REPLACE,
                            FinalPlanTargetKind.REPAIR,
                            startedTargetId))))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.getMessage()).contains("requires MERGE");
            });

    var merged =
        service.updateFinalPlan(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            finalPlanUpdateRequest(
                prepared,
                preparedEntry,
                new FinalPlanReconciliationDecision(
                    FinalPlanReconciliationStrategy.MERGE,
                    FinalPlanTargetKind.REPAIR,
                    startedTargetId)));
    assertThat(merged.entries().getFirst().reconciliationDecision().strategy())
        .isEqualTo(FinalPlanReconciliationStrategy.MERGE);

    List<JsonNode> ambiguousCandidates =
        List.of(
            finalPlanCandidate(UUID.randomUUID(), false, true),
            finalPlanCandidate(UUID.randomUUID(), false, true));
    candidates.set(ambiguousCandidates);
    var ambiguous =
        service.prepareFinalPlan(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new PrepareFinalPlanRequest(
                service.session(jwt(), inventoryId).sessionRevision(),
                0,
                FinalPlanScheduleMode.AUTO,
                FinalPlanScheduleMode.AUTO));

    assertThatThrownBy(
            () ->
                service.preview(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new CompletionPreviewRequest(
                        0,
                        ambiguous.finalPlanVersion(),
                        ambiguous.finalPlanSha256(),
                        List.of(new RevisionExpectation(finding.getId(), finding.getRevision())))))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status())
                  .withFailMessage(exception.getMessage())
                  .isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
              assertThat(exception.code())
                  .isEqualTo("INVENTORY_FINAL_PLAN_AMBIGUOUS_ACTIVE_CANDIDATES");
            });
  }

  @Test
  void finalPlanUsesCurrentWarehouseDateAndRejectsPastManualAndStaleDraftDates() {
    ZoneId warehouseZone = ZoneId.of("Europe/Moscow");
    LocalDate planningDate = LocalDate.now(warehouseZone);
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    setBusinessDate(inventoryId, planningDate.minusDays(1));
    InventoryFinding finding = stageFinalPlanFinding(inventoryId, warehouseId, true);
    confirmEmptyCabinDisposition(inventoryId);

    prepareFinalPlan(
        inventoryId,
        new FurnitureReviewState(
            service.session(jwt(), inventoryId).sessionRevision(),
            List.of(new RevisionExpectation(finding.getId(), finding.getRevision()))));
    var automatic = service.finalPlan(jwt(), inventoryId);
    var automaticEntry = automatic.entries().getFirst();
    assertThat(automaticEntry.movementScheduledDate()).isNotNull();
    assertThat(automaticEntry.movementScheduledDate().isBefore(planningDate)).isFalse();
    assertThat(automaticEntry.repairScheduledDate()).isNotNull();
    assertThat(automaticEntry.repairScheduledDate().isBefore(planningDate)).isFalse();

    assertThatThrownBy(
            () ->
                service.updateFinalPlan(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new FinalPlanUpdateRequest(
                        automatic.sessionRevision(),
                        automatic.finalPlanVersion(),
                        FinalPlanScheduleMode.MANUAL,
                        FinalPlanScheduleMode.MANUAL,
                        List.of(
                            new FinalPlanEntryUpdate(
                                automaticEntry.findingId(),
                                automaticEntry.findingRevision(),
                                automaticEntry.order(),
                                automaticEntry.priority(),
                                true,
                                planningDate.minusDays(1),
                                planningDate,
                                null)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Manual movement date cannot precede the current warehouse planning date");

    assertThatThrownBy(
            () ->
                service.updateFinalPlan(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new FinalPlanUpdateRequest(
                        automatic.sessionRevision(),
                        automatic.finalPlanVersion(),
                        FinalPlanScheduleMode.MANUAL,
                        FinalPlanScheduleMode.MANUAL,
                        List.of(
                            new FinalPlanEntryUpdate(
                                automaticEntry.findingId(),
                                automaticEntry.findingRevision(),
                                automaticEntry.order(),
                                automaticEntry.priority(),
                                false,
                                null,
                                planningDate.minusDays(1),
                                null)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Manual repair date cannot precede the current warehouse planning date");

    jdbc.update(
        """
        update inventory_final_plan_entry
           set movement_scheduled_date=?,repair_scheduled_date=?
         where inventory_id=? and final_plan_version=?
        """,
        planningDate.minusDays(1),
        planningDate.minusDays(1),
        inventoryId,
        automatic.finalPlanVersion());
    CompletionPreviewRequest previewRequest =
        new CompletionPreviewRequest(
            automatic.sessionRevision(),
            automatic.finalPlanVersion(),
            automatic.finalPlanSha256(),
            List.of(new RevisionExpectation(finding.getId(), finding.getRevision())));

    assertThatThrownBy(
            () -> service.preview(jwt(), inventoryId, UUID.randomUUID(), previewRequest))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.getMessage())
                  .contains("operational dates")
                  .contains("prepare it again");
            });
    assertThatThrownBy(
            () ->
                service.complete(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new CompleteSessionRequest(
                        automatic.sessionRevision(),
                        automatic.finalPlanVersion(),
                        automatic.finalPlanSha256(),
                        List.of(new RevisionExpectation(finding.getId(), finding.getRevision())),
                        "b".repeat(64),
                        "c".repeat(64))))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.getMessage())
                  .contains("operational dates")
                  .contains("prepare it again");
            });
  }

  @Test
  void preliminaryStatisticsUsesPersistedActiveFindingsAndPlanLinesWithoutMutating() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID cancelledInventoryId = UUID.randomUUID();
    seedSession(cancelledInventoryId, warehouseId);
    markCancelled(cancelledInventoryId);
    seedSession(inventoryId, warehouseId);
    new TransactionTemplate(transactionManager)
        .execute(status -> seedFindings(inventoryId, cancelledInventoryId, warehouseId));

    long sessionRevisionBefore =
        jdbc.queryForObject(
            "select session_revision from inventory_session where id=?", Long.class, inventoryId);
    List<Long> findingRevisionsBefore =
        jdbc.query(
            """
            select finding_revision from inventory_finding
             where inventory_id=? and membership_active=true
             order by id
            """,
            (resultSet, rowNum) -> resultSet.getLong(1),
            inventoryId);

    FrozenStatistics statistics = service.preliminaryStatistics(jwt(), inventoryId);

    assertThat(statistics.expectedCount()).isZero();
    assertThat(statistics.inspectedCount()).isEqualTo(2);
    assertThat(statistics.missingCount()).isZero();
    assertThat(statistics.readyCount()).isOne();
    assertThat(statistics.withWorkCount()).isOne();
    assertThat(statistics.addedCount()).isZero();
    assertThat(statistics.unexpectedExistingCount()).isEqualTo(2);
    assertThat(statistics.conflictCount()).isZero();
    assertThat(statistics.workLineCount()).isOne();
    assertThat(statistics.materialLineCount()).isOne();
    assertThat(statistics.workTotalMinor()).isEqualTo(1543);
    assertThat(statistics.materialTotalMinor()).isEqualTo(135);
    assertThat(statistics.grandTotalMinor()).isEqualTo(1678);
    assertThat(statistics.roundingAdjustmentMinor()).isZero();
    assertThat(statistics.normativeMinutes()).isEqualTo("3.125");
    assertThat(statistics.durationSeconds()).isGreaterThanOrEqualTo(0);
    assertThat(statistics.aggregateLines()).hasSize(2);
    assertThat(statistics.aggregateLines().get(0))
        .satisfies(
            line -> {
              assertThat(line.aggregationKind()).isEqualTo("MANUAL");
              assertThat(line.type()).isEqualTo("MATERIAL");
              assertThat(line.normalizedDescription()).isEqualTo("second material");
              assertThat(line.unit()).isEqualTo("PCS");
              assertThat(line.unitPriceMinor()).isEqualTo(45);
              assertThat(line.quantity()).isEqualTo("3");
              assertThat(line.rowTotalMinor()).isEqualTo(135);
            });
    assertThat(statistics.aggregateLines().get(1))
        .satisfies(
            line -> {
              assertThat(line.aggregationKind()).isEqualTo("CATALOG");
              assertThat(line.type()).isEqualTo("WORK");
              assertThat(line.catalogVersionId()).isNotNull();
              assertThat(line.catalogNodeId()).isNotNull();
              assertThat(line.unit()).isEqualTo("HOUR");
              assertThat(line.unitPriceMinor()).isEqualTo(1234);
              assertThat(line.quantity()).isEqualTo("1.25");
              assertThat(line.rowTotalMinor()).isEqualTo(1543);
            });
    verifyNoInteractions(dependencies);

    assertThat(
            jdbc.queryForObject(
                "select session_revision from inventory_session where id=?",
                Long.class,
                inventoryId))
        .isEqualTo(sessionRevisionBefore);
    assertThat(
            jdbc.query(
                """
                select finding_revision from inventory_finding
                 where inventory_id=? and membership_active=true
                 order by id
                """,
                (resultSet, rowNum) -> resultSet.getLong(1),
                inventoryId))
        .isEqualTo(findingRevisionsBefore);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_validation_snapshot where inventory_id=?",
                Integer.class,
                inventoryId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_completion_statistics where inventory_id=?",
                Integer.class,
                inventoryId))
        .isZero();
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
        .isEmpty();
    assertThat(service.session(jwtForWarehouse(firstWarehouseId), firstInventoryId).expectedCount())
        .isZero();
    assertThat(service.session(jwtForWarehouse(firstWarehouseId), firstInventoryId).findingCount())
        .isZero();
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
        .isEmpty();
    SessionView afterDeparture =
        service.session(jwtForWarehouse(secondWarehouseId), secondInventoryId);
    assertThat(afterDeparture.expectedCount()).isZero();
    assertThat(afterDeparture.findingCount()).isZero();
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
        .isZero();

    when(dependencies.currentAsset(assetId))
        .thenReturn(
            Optional.of(
                new InventoryDependencyGateway.LiveAssetSnapshot(
                    assetId,
                    10,
                    secondWarehouseId,
                    "WAREHOUSE",
                    "БЫТ-101",
                    "БЫТ101",
                    null,
                    mapper.createObjectNode(),
                    mapper.createArrayNode())));
    service.reconcileAssetMembership(
        assetId, actor, UUID.randomUUID(), UUID.randomUUID(), departedAt.plusMinutes(10));
    FindingView returned =
        service
            .findings(jwtForWarehouse(secondWarehouseId), secondInventoryId, 0, 20, "createdAt,asc")
            .content()
            .getFirst();
    assertThat(returned.inspection()).isEqualTo(InspectionState.NOT_INSPECTED);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_finding where asset_id=?", Long.class, assetId))
        .isEqualTo(3L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_finding where asset_id=? and membership_active",
                Long.class,
                assetId))
        .isEqualTo(1L);
  }

  @Test
  void liveInventoryExcludesWrittenOffAndLostCabins() {
    for (String terminalStatus : List.of("WRITTEN_OFF", "LOST")) {
      UUID warehouseId = UUID.randomUUID();
      UUID inventoryId = UUID.randomUUID();
      UUID assetId = UUID.randomUUID();
      OffsetDateTime arrivedAt = OffsetDateTime.parse("2026-08-05T09:00:00Z");
      seedSession(inventoryId, warehouseId);

      when(dependencies.currentAsset(assetId))
          .thenReturn(
              Optional.of(
                  new InventoryDependencyGateway.LiveAssetSnapshot(
                      assetId,
                      1,
                      warehouseId,
                      "WAREHOUSE",
                      "БЫТ-201",
                      "БЫТ201",
                      null,
                      mapper.createObjectNode(),
                      mapper.createArrayNode())));
      service.reconcileAssetMembership(
          assetId,
          new OpaqueActorReference(jwt().getSubject(), "USER", null),
          UUID.randomUUID(),
          UUID.randomUUID(),
          arrivedAt);

      when(dependencies.currentAsset(assetId))
          .thenReturn(
              Optional.of(
                  new InventoryDependencyGateway.LiveAssetSnapshot(
                      assetId,
                      2,
                      warehouseId,
                      terminalStatus,
                      "БЫТ-201",
                      "БЫТ201",
                      null,
                      mapper.createObjectNode(),
                      mapper.createArrayNode())));
      service.reconcileAssetMembership(
          assetId,
          new OpaqueActorReference(jwt().getSubject(), "USER", null),
          UUID.randomUUID(),
          UUID.randomUUID(),
          arrivedAt.plusMinutes(1));

      SessionView session = service.session(jwtForWarehouse(warehouseId), inventoryId);
      assertThat(
              service
                  .findings(
                      jwtForWarehouse(warehouseId), inventoryId, 0, 20, "createdAt,asc")
                  .content())
          .as(terminalStatus)
          .isEmpty();
      assertThat(session.expectedCount()).as(terminalStatus).isZero();
      assertThat(session.findingCount()).as(terminalStatus).isZero();
      assertThat(session.membershipMovements())
          .as(terminalStatus)
          .extracting(movement -> movement.type())
          .containsExactly(
              InventoryMembershipMovementType.ARRIVED,
              InventoryMembershipMovementType.DEPARTED);
    }
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
    assertThat(session.expectedCount()).isZero();
    assertThat(session.findingCount()).isZero();
    assertThat(
            service
                .findings(jwtForWarehouse(warehouseId), inventoryId, 0, 20, "createdAt,asc")
                .content())
        .isEmpty();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_finding where inventory_id=? and membership_active=false",
                Long.class,
                inventoryId))
        .isEqualTo(1L);
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
  void readyAfterRentCabinRequiresAtLeastOneReadyPhotoWithoutCreatingAcceptance() {
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
    PreparedFinalPlan finalPlan = prepareFinalPlan(inventoryId, furnitureReview);
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(furnitureReview, finalPlan));

    assertThat(preview.validatedFindings()).singleElement();
    assertThat(preview.validationSha256()).isNotEqualTo(providerDigest);
  }

  @Test
  void savedInspectionFurnitureQuantitySeedsTheFurnitureReview() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    UUID legacyEquipmentId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);

    ArrayNode equipment = mapper.createArrayNode();
    equipment
        .addObject()
        .put("equipmentId", equipmentId.toString())
        .put("equipmentName", "Стул")
        .put("equipmentCategory", "FURNITURE")
        .put("catalogVersion", 3)
        .put("quantity", 4);
    equipment
        .addObject()
        .put("equipmentId", legacyEquipmentId.toString())
        .put("catalogVersion", 2)
        .put("observedQuantity", 6);
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            assetId,
            1L,
            warehouseId,
            "WAREHOUSE",
            null,
            "БЫТ-МЕБЕЛЬ",
            "БЫТМЕБЕЛЬ",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.PRESENT,
        equipment.toString(),
        null,
        "Мебель сохранена в приложении",
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
    mediaFacts.saveAndFlush(
        InventoryMediaFactProjection.create(
            mediaId, 1, 1, finding.getId(), warehouseId, "IMAGE", "READY", 0));
    jdbc.update(
        """
        insert into finding_media_reference(
          finding_id, finding_revision, media_id, generation,
          media_kind, media_status, attached_at)
        values (?, ?, ?, 1, 'IMAGE', 'READY', now())
        """,
        finding.getId(),
        finding.getRevision(),
        mediaId);
    long mediaSourceRevision = finding.getRevision();

    InventoryDependencyGateway.ValidationItem current =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            1L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-МЕБЕЛЬ",
            "БЫТМЕБЕЛЬ",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> validationItems = List.of(current);
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
                    new InventoryDependencyGateway.RepairAssetSnapshot(assetId, List.of()))));
    when(dependencies.furnitureSnapshot(warehouseId, List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                warehouseId,
                "4".repeat(64),
                List.of(
                    new InventoryDependencyGateway.FurnitureSnapshotItem(
                        equipmentId,
                        3,
                        "Стул",
                        0,
                        null,
                        List.of(
                            new InventoryDependencyGateway.FurnitureSnapshotCabin(
                                assetId, 1, "БЫТ-МЕБЕЛЬ", "WAREHOUSE", 0))),
                    new InventoryDependencyGateway.FurnitureSnapshotItem(
                        legacyEquipmentId,
                        2,
                        "Стол",
                        0,
                        null,
                        List.of(
                            new InventoryDependencyGateway.FurnitureSnapshotCabin(
                                assetId, 1, "БЫТ-МЕБЕЛЬ", "WAREHOUSE", 0))))));

    confirmEmptyCabinDisposition(inventoryId);
    FurnitureReviewView review =
        service.startFurnitureReview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new StartFurnitureReviewRequest(
                0,
                List.of(new RevisionExpectation(finding.getId(), finding.getRevision()))));

    assertThat(review.items())
        .anySatisfy(
            item -> {
              assertThat(item.equipmentId()).isEqualTo(equipmentId);
              assertThat(item.cabins()).singleElement().satisfies(
                  cabin -> assertThat(cabin.observedQuantity()).isEqualTo(4));
            })
        .anySatisfy(
            item -> {
              assertThat(item.equipmentId()).isEqualTo(legacyEquipmentId);
              assertThat(item.cabins()).singleElement().satisfies(
                  cabin -> assertThat(cabin.observedQuantity()).isEqualTo(6));
            });

    service.saveFurnitureReview(
        jwt(),
        inventoryId,
        new SaveFurnitureReviewRequest(
            review.sessionRevision(),
            "4".repeat(64),
            List.of(
                new FurnitureReviewItemInput(
                    equipmentId,
                    3,
                    0,
                    List.of(
                        new FurnitureReviewCabinInput(
                            finding.getId(), mediaSourceRevision, 5))),
                new FurnitureReviewItemInput(
                    legacyEquipmentId,
                    2,
                    0,
                    List.of(
                        new FurnitureReviewCabinInput(
                            finding.getId(), mediaSourceRevision, 6))))));

    InventoryFinding reviewedFinding = findings.findById(finding.getId()).orElseThrow();
    assertThat(reviewedFinding.getRevision()).isEqualTo(mediaSourceRevision + 1);
    FindingView reviewedView =
        find(
            service.findings(jwt(), inventoryId, 0, 20, "createdAt,asc").content(),
            finding.getId());
    assertThat(reviewedView.findingRevision()).isEqualTo(reviewedFinding.getRevision());
    assertThat(reviewedView.media())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.mediaId()).isEqualTo(mediaId);
              assertThat(reference.generation()).isEqualTo(1);
            });
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=? and media_id=?
                """,
                Integer.class,
                finding.getId(),
                reviewedFinding.getRevision(),
                mediaId))
        .isOne();
  }

  @Test
  void refreshReconcilesCapturedMembershipRetainsEvidenceAndRestartsDerivedReview() {
    UUID warehouseId = UUID.randomUUID();
    UUID otherWarehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID otherInventoryId = UUID.randomUUID();
    UUID departedAssetId = UUID.randomUUID();
    UUID arrivedAssetId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    seedSession(otherInventoryId, otherWarehouseId);
    jdbc.update(
        "update inventory_session set expected_population_count=1 where id=?",
        inventoryId);

    UUID expectedItemId = UUID.randomUUID();
    InventoryFinding historical =
        InventoryFinding.expected(
            inventoryId,
            expectedItemId,
            departedAssetId,
            5,
            warehouseId,
            "WAREHOUSE",
            null,
            "БЫТ-СТАРАЯ",
            "БЫТСТАРАЯ",
            ACTOR);
    historical.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.PRESENT,
        "{\"serial\":\"KEEP-IN-DB\"}",
        ObservationPresence.ABSENT,
        null,
        null,
        "Сохранённый осмотр",
        ACTOR);
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              findings.save(historical);
              expectedItems.save(
                  new InventoryExpectedItem(
                      expectedItemId,
                      inventoryId,
                      historical.getId(),
                      0,
                      departedAssetId,
                      5,
                      "WAREHOUSE",
                      "БЫТ-СТАРАЯ",
                      "БЫТСТАРАЯ",
                      "{}",
                      "[]"));
            });
    events.initialize(
        "FINDING",
        historical.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "EXPECTED"),
        UUID.randomUUID(),
        null,
        null);
    InventoryFinding otherWarehouseFinding =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                otherInventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                departedAssetId,
                5L,
                otherWarehouseId,
                "WAREHOUSE",
                null,
                "БЫТ-ДРУГАЯ-СЕССИЯ",
                "БЫТДРУГАЯСЕССИЯ",
                ReconciliationState.MATCHED,
                ACTOR));
    events.initialize(
        "FINDING",
        otherWarehouseFinding.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);

    InventoryDependencyGateway.ValidationItem current =
        new InventoryDependencyGateway.ValidationItem(
            departedAssetId,
            true,
            5L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-СТАРАЯ",
            "БЫТСТАРАЯ",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> validationItems = List.of(current);
    when(dependencies.validateAssets(List.of(departedAssetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(validationItems),
                validationItems));
    when(dependencies.repairSnapshots(List.of(departedAssetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(
                        departedAssetId, List.of()))));
    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(
                new RevisionExpectation(
                    historical.getId(), historical.getRevision())));
    prepareFinalPlan(inventoryId, furnitureReview);
    InventoryFinding currentHistorical = findings.findById(historical.getId()).orElseThrow();
    UUID retainedMediaId = UUID.randomUUID();
    mediaFacts.saveAndFlush(
        InventoryMediaFactProjection.create(
            retainedMediaId,
            1,
            1,
            historical.getId(),
            warehouseId,
            "IMAGE",
            "READY",
            0));
    jdbc.update(
        """
        insert into finding_media_reference(
          finding_id, finding_revision, media_id, generation,
          media_kind, media_status, attached_at)
        values (?, ?, ?, 1, 'IMAGE', 'READY', now())
        """,
        historical.getId(),
        currentHistorical.getRevision(),
        retainedMediaId);
    long mediaSourceRevision = currentHistorical.getRevision();

    InventoryDependencyGateway.CaptureMember arrived =
        new InventoryDependencyGateway.CaptureMember(
            0,
            arrivedAssetId,
            8,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-НОВАЯ",
            "БЫТНОВАЯ",
            mapper.createObjectNode().put("tenant", "Новый арендатор"),
            mapper.createArrayNode());
    UUID captureId = stubRefreshCapture(warehouseId, List.of(arrived), null);
    long expectedRevision = service.session(jwt(), inventoryId).sessionRevision();
    UUID idempotencyKey = UUID.randomUUID();

    SessionView refreshed =
        service.refresh(
            jwt(),
            inventoryId,
            idempotencyKey,
            new RefreshSessionRequest(expectedRevision));

    assertThat(refreshed.reviewStage()).isEqualTo(InventoryReviewStage.CABINS);
    assertThat(refreshed.expectedCount()).isOne();
    assertThat(refreshed.findingCount()).isOne();
    assertThat(refreshed.membershipMovements())
        .extracting(movement -> movement.type())
        .containsExactlyInAnyOrder(
            InventoryMembershipMovementType.DEPARTED,
            InventoryMembershipMovementType.ARRIVED);
    List<InventoryFinding> retained = findings.findAllByInventoryIdOrderById(inventoryId);
    assertThat(retained).hasSize(2);
    InventoryFinding retainedHistorical =
        retained.stream()
            .filter(value -> value.getId().equals(historical.getId()))
            .findFirst()
            .orElseThrow();
    assertThat(retainedHistorical.isMembershipActive()).isFalse();
    assertThat(retainedHistorical.getInspection()).isEqualTo(InspectionState.READY);
    assertThat(retainedHistorical.getPassportObservation()).contains("KEEP-IN-DB");
    assertThat(retainedHistorical.getInspectionComment()).isEqualTo("Сохранённый осмотр");
    assertThat(retainedHistorical.getRevision()).isEqualTo(mediaSourceRevision + 1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=? and media_id=?
                """,
                Integer.class,
                historical.getId(),
                retainedHistorical.getRevision(),
                retainedMediaId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finding_media_reference where finding_id=? and media_id=?",
                Integer.class,
                historical.getId(),
                retainedMediaId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                """
                select event_type from domain_event
                 where aggregate_type='FINDING' and aggregate_id=?
                """,
                String.class,
                historical.getId().toString()))
        .contains("inventory.finding.added.v1", "inventory.finding.membership-departed.v1");
    assertThat(
            expectedItems.findAllByFindingIdInOrderByFindingId(
                retained.stream()
                    .map(InventoryFinding::getId)
                    .collect(java.util.stream.Collectors.toSet())))
        .hasSize(2);
    InventoryFinding isolated = findings.findById(otherWarehouseFinding.getId()).orElseThrow();
    assertThat(isolated.isMembershipActive()).isTrue();
    assertThat(isolated.getRevision()).isEqualTo(otherWarehouseFinding.getRevision());
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_final_plan where inventory_id=?",
                String.class,
                inventoryId))
        .isEqualTo("STALE");

    SessionView replayed =
        service.refresh(
            jwt(),
            inventoryId,
            idempotencyKey,
            new RefreshSessionRequest(expectedRevision));
    assertThat(replayed.sessionRevision()).isEqualTo(refreshed.sessionRevision());
    assertThat(replayed.membershipMovements()).hasSize(2);
    verify(dependencies, times(1)).createCapture(any(), any());
    verify(dependencies, times(1)).releaseCapture(captureId);
    verifyNoInteractionsOnCurrentAsset();
  }

  @Test
  void refreshCarriesMediaIntoTheCurrentFindingApiProjection() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
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
            "БЫТ-ФОТО-REFRESH",
            "БЫТФОТОREFRESH",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.refreshCurrentAsset(
        1L,
        warehouseId,
        "WAREHOUSE",
        null,
        "БЫТ-ФОТО-REFRESH",
        "{\"snapshot\":\"before\"}",
        "[]",
        "[]",
        ReconciliationState.MATCHED);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        "Фото осмотра",
        mediaId,
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
    mediaFacts.saveAndFlush(
        InventoryMediaFactProjection.create(
            mediaId, 1, 1, finding.getId(), warehouseId, "IMAGE", "READY", 0));
    jdbc.update(
        """
        insert into finding_media_reference(
          finding_id, finding_revision, media_id, generation,
          media_kind, media_status, attached_at)
        values (?, ?, ?, 1, 'IMAGE', 'READY', now())
        """,
        finding.getId(),
        finding.getRevision(),
        mediaId);
    long mediaSourceRevision = finding.getRevision();

    InventoryDependencyGateway.CaptureMember refreshedMember =
        new InventoryDependencyGateway.CaptureMember(
            0,
            assetId,
            2,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-ФОТО-REFRESH",
            "БЫТФОТОREFRESH",
            mapper.createObjectNode().put("snapshot", "after"),
            mapper.createArrayNode());
    stubRefreshCapture(warehouseId, List.of(refreshedMember), null);

    service.refresh(
        jwt(),
        inventoryId,
        UUID.randomUUID(),
        new RefreshSessionRequest(service.session(jwt(), inventoryId).sessionRevision()));

    InventoryFinding refreshedFinding = findings.findById(finding.getId()).orElseThrow();
    assertThat(refreshedFinding.getRevision()).isEqualTo(mediaSourceRevision + 1);
    FindingView refreshedView =
        find(
            service.findings(jwt(), inventoryId, 0, 20, "createdAt,asc").content(),
            finding.getId());
    assertThat(refreshedView.findingRevision()).isEqualTo(refreshedFinding.getRevision());
    assertThat(refreshedView.coverMediaId()).isEqualTo(mediaId);
    assertThat(refreshedView.media())
        .singleElement()
        .satisfies(
            reference -> {
              assertThat(reference.mediaId()).isEqualTo(mediaId);
              assertThat(reference.generation()).isEqualTo(1);
            });
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=? and media_id=?
                """,
                Integer.class,
                finding.getId(),
                refreshedFinding.getRevision(),
                mediaId))
        .isOne();
  }

  @ParameterizedTest
  @EnumSource(
      value = FindingOrigin.class,
      names = {"UNEXPECTED_EXISTING", "ADDED_USED"})
  void refreshKeepsExplicitObservationsThatAreAbsentFromAutomaticCapture(FindingOrigin origin) {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            origin,
            UUID.randomUUID(),
            1L,
            warehouseId,
            "RENTED",
            "Арендатор до инвентаризации",
            "230847",
            "230847",
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
        mapper.createObjectNode().put("origin", origin.name()),
        UUID.randomUUID(),
        null,
        null);
    stubRefreshCapture(warehouseId, List.of(), null);

    SessionView refreshed =
        service.refresh(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new RefreshSessionRequest(service.session(jwt(), inventoryId).sessionRevision()));

    InventoryFinding retained = findings.findById(finding.getId()).orElseThrow();
    assertThat(refreshed.findingCount()).isOne();
    assertThat(refreshed.membershipMovements()).isEmpty();
    assertThat(retained.isMembershipActive()).isTrue();
    assertThat(retained.getCurrentStatus()).isEqualTo("RENTED");
    assertThat(retained.getInspection()).isEqualTo(InspectionState.READY);
    assertThat(
            jdbc.queryForList(
                "select event_type from domain_event where aggregate_type='FINDING' "
                    + "and aggregate_id=? order by aggregate_version",
                String.class,
                finding.getId().toString()))
        .containsExactly("inventory.finding.added.v1");
  }

  @Test
  void nonMediaRevisionBumpRollsBackInsteadOfMixingDifferentTargetMedia() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID sourceMediaId = UUID.randomUUID();
    UUID conflictingTargetMediaId = UUID.randomUUID();
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
            "БЫТ-ФОТО-CONFLICT",
            "БЫТФОТОCONFLICT",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.refreshCurrentAsset(
        1L,
        warehouseId,
        "WAREHOUSE",
        null,
        "БЫТ-ФОТО-CONFLICT",
        "{}",
        "[]",
        "[]",
        ReconciliationState.MATCHED);
    finding.saveInspection(
        InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        null,
        "Проверка конфликта ревизий",
        sourceMediaId,
        ACTOR);
    finding = findings.saveAndFlush(finding);
    UUID findingId = finding.getId();
    jdbc.update(
        """
        insert into finding_media_reference(
          finding_id, finding_revision, media_id, generation,
          media_kind, media_status, attached_at)
        values (?, 0, ?, 1, 'IMAGE', 'READY', now()),
               (?, 1, ?, 1, 'VIDEO', 'READY', now())
        """,
        findingId,
        sourceMediaId,
        findingId,
        conflictingTargetMediaId);
    InventoryDependencyGateway.CaptureMember refreshedMember =
        new InventoryDependencyGateway.CaptureMember(
            0,
            assetId,
            2,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-ФОТО-CONFLICT",
            "БЫТФОТОCONFLICT",
            mapper.createObjectNode().put("snapshot", "after"),
            mapper.createArrayNode());
    stubRefreshCapture(warehouseId, List.of(refreshedMember), null);
    long sessionRevision = service.session(jwt(), inventoryId).sessionRevision();

    assertThatThrownBy(
            () ->
                service.refresh(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new RefreshSessionRequest(sessionRevision)))
        .isInstanceOf(IllegalStateException.class)
        .hasMessage("Target finding revision already contains different media evidence");

    InventoryFinding rolledBack = findings.findById(findingId).orElseThrow();
    assertThat(rolledBack.getRevision()).isZero();
    assertThat(rolledBack.getAssetVersion()).isEqualTo(1L);
    assertThat(rolledBack.getCurrentPassportSnapshot()).isEqualTo("{}");
    assertThat(service.session(jwt(), inventoryId).sessionRevision()).isEqualTo(sessionRevision);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from finding_media_reference where finding_id=?",
                Integer.class,
                findingId))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                """
                select finding_revision::text || ':' || media_id::text || ':' || media_kind
                  from finding_media_reference
                 where finding_id=? order by finding_revision
                """,
                String.class,
                findingId))
        .containsExactly(
            "0:" + sourceMediaId + ":IMAGE",
            "1:" + conflictingTargetMediaId + ":VIDEO");
  }

  @Test
  void refreshRechecksTheRevisionAfterCaptureAndAppliesNoPartialMembership() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    UUID captureId =
        stubRefreshCapture(
            warehouseId,
            List.of(),
            () ->
                jdbc.update(
                    "update inventory_session set session_revision=session_revision+1 where id=?",
                    inventoryId));

    assertThatThrownBy(
            () ->
                service.refresh(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new RefreshSessionRequest(0L)))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> {
              assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT);
              assertThat(exception.getMessage()).isEqualTo("Inventory revision is stale");
            });

    assertThat(service.session(jwt(), inventoryId).sessionRevision()).isOne();
    assertThat(findings.findAllByInventoryIdOrderById(inventoryId)).isEmpty();
    assertThat(service.session(jwt(), inventoryId).membershipMovements()).isEmpty();
    verify(dependencies).releaseCapture(captureId);
    verifyNoInteractionsOnCurrentAsset();
  }

  @Test
  void refreshRequiresManageAndRejectsAnInitiallyStaleRevisionBeforeCapture() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);

    assertThatThrownBy(
            () ->
                service.refresh(
                    jwtForWarehouseLevel(warehouseId, "EDIT"),
                    inventoryId,
                    UUID.randomUUID(),
                    new RefreshSessionRequest(0L)))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.NOT_FOUND));

    jdbc.update(
        "update inventory_session set session_revision=1 where id=?",
        inventoryId);
    assertThatThrownBy(
            () ->
                service.refresh(
                    jwtForWarehouse(warehouseId),
                    inventoryId,
                    UUID.randomUUID(),
                    new RefreshSessionRequest(0L)))
        .isInstanceOfSatisfying(
            InventoryException.class,
            exception -> assertThat(exception.status()).isEqualTo(HttpStatus.CONFLICT));
    verify(dependencies, times(0)).createCapture(any(), any());
    verifyNoInteractionsOnCurrentAsset();
  }

  @Test
  void registryReviewIgnoresJsonObjectFieldOrderAndAllowsFurnitureTransition() {
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
            "БЫТ-ORDER",
            "БЫТORDER",
            ReconciliationState.MATCHED,
            ACTOR);
    finding.refreshCurrentAsset(
        1L,
        warehouseId,
        "WAREHOUSE",
        null,
        "БЫТ-ORDER",
        "{\"category\":\"Новая\",\"dimensions\":\"2.4x6\",\"rentalType\":\"БК-1\"}",
        "[]",
        "[]",
        ReconciliationState.MATCHED);
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

    ObjectNode samePassportInAnotherOrder = mapper.createObjectNode();
    samePassportInAnotherOrder.put("rentalType", "БК-1");
    samePassportInAnotherOrder.put("category", "Новая");
    samePassportInAnotherOrder.put("dimensions", "2.4x6");
    InventoryDependencyGateway.ValidationItem current =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            1L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-ORDER",
            "БЫТORDER",
            null,
            samePassportInAnotherOrder,
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> currentItems = List.of(current);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(currentItems),
                currentItems));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(assetId, List.of()))));
    when(dependencies.furnitureSnapshot(warehouseId, List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                warehouseId, "f".repeat(64), List.of()));
    List<RevisionExpectation> revisions =
        List.of(new RevisionExpectation(finding.getId(), finding.getRevision()));

    RegistryReviewView review =
        service.registryReview(jwt(), inventoryId, new RegistryReviewRequest(0, revisions));
    confirmEmptyCabinDisposition(inventoryId);
    FurnitureReviewView furniture =
        service.startFurnitureReview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new StartFurnitureReviewRequest(0, revisions));

    assertThat(review.validatedFindings()).singleElement().satisfies(
        validated -> assertThat(validated.conflicts()).isEmpty());
    assertThat(furniture.stage()).isEqualTo(InventoryReviewStage.FURNITURE);
  }

  @Test
  void conflictsStartAfterInspectionAndIgnoreTechnicalAssetVersion() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID assetId = UUID.randomUUID();
    UUID mediaId = UUID.randomUUID();
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
    mediaFacts.saveAndFlush(
        InventoryMediaFactProjection.create(
            mediaId, 1, 1, finding.getId(), warehouseId, "IMAGE", "READY", 0));
    jdbc.update(
        """
        insert into finding_media_reference(
          finding_id, finding_revision, media_id, generation,
          media_kind, media_status, attached_at)
        values (?, ?, ?, 1, 'IMAGE', 'READY', now())
        """,
        finding.getId(),
        finding.getRevision(),
        mediaId);

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
    PreparedFinalPlan finalPlan = prepareFinalPlan(inventoryId, furnitureReview);
    CompletionPreview versionPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(furnitureReview, finalPlan));

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
            completionPreviewRequest(furnitureReview, finalPlan));

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
            completionPreviewRequest(furnitureReview, finalPlan));

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
    assertThat(keptInspection.media())
        .singleElement()
        .satisfies(reference -> assertThat(reference.mediaId()).isEqualTo(mediaId));
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from finding_media_reference
                 where finding_id=? and finding_revision=? and media_id=?
                """,
                Integer.class,
                finding.getId(),
                keptInspection.findingRevision(),
                mediaId))
        .isOne();

    FurnitureReviewState resumedFurnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(finding.getId(), keptInspection.findingRevision())));
    PreparedFinalPlan resumedFinalPlan = prepareFinalPlan(inventoryId, resumedFurnitureReview);
    CompletionPreview resolvedPreview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(resumedFurnitureReview, resumedFinalPlan));
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
            completionPreviewRequest(resumedFurnitureReview, resumedFinalPlan));
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
    PreparedFinalPlan finalPlan = prepareFinalPlan(inventoryId, furnitureReview);
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(furnitureReview, finalPlan));

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
                preview.finalPlanVersion(),
                preview.finalPlanSha256(),
                preview.findingRevisions(),
                preview.acknowledgementSha256(),
                preview.validationSha256()));

    assertThat(completed.lifecycle()).isEqualTo(SessionLifecycle.COMPLETED);
  }

  @Test
  void completionPersistsPlanLogisticsBeforeAutomaticWriteOffIntentInOneTransaction() {
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
    InventoryFinding missing =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                assetId,
                1L,
                warehouseId,
                "WAREHOUSE",
                null,
                "БЫТ-WRITE-OFF",
                "БЫТWRITEOFF",
                ReconciliationState.MATCHED,
                ACTOR));
    events.initialize(
        "FINDING",
        missing.getId(),
        "inventory.finding.added.v1",
        "rwms.inventory.session.v1",
        mapper.createObjectNode().put("origin", "UNEXPECTED_EXISTING"),
        UUID.randomUUID(),
        null,
        null);
    InventoryDependencyGateway.ValidationItem current =
        new InventoryDependencyGateway.ValidationItem(
            assetId,
            true,
            1L,
            warehouseId,
            "WAREHOUSE",
            "БЫТ-WRITE-OFF",
            "БЫТWRITEOFF",
            null,
            mapper.createObjectNode(),
            mapper.createArrayNode());
    List<InventoryDependencyGateway.ValidationItem> currentItems = List.of(current);
    when(dependencies.validateAssets(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.Validation(
                OffsetDateTime.now(ZoneOffset.UTC),
                canonicalJson.sha256(currentItems),
                currentItems));
    when(dependencies.repairSnapshots(List.of(assetId)))
        .thenReturn(
            new InventoryDependencyGateway.RepairSnapshots(
                List.of(
                    new InventoryDependencyGateway.RepairAssetSnapshot(assetId, List.of()))));

    FurnitureReviewState furnitureReview =
        confirmEmptyFurnitureReview(
            inventoryId,
            warehouseId,
            List.of(new RevisionExpectation(missing.getId(), missing.getRevision())));
    PreparedFinalPlan finalPlan = prepareFinalPlan(inventoryId, furnitureReview);
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(furnitureReview, finalPlan));

    SessionView completed =
        service.complete(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new CompleteSessionRequest(
                preview.sessionRevision(),
                preview.finalPlanVersion(),
                preview.finalPlanSha256(),
                preview.findingRevisions(),
                preview.acknowledgementSha256(),
                preview.validationSha256()));

    assertThat(completed.lifecycle()).isEqualTo(SessionLifecycle.COMPLETED);
    assertThat(
            jdbc.queryForMap(
                """
                select write_off.state,write_off.final_plan_version,
                       write_off.outcome_reapplication_no,
                       logistics.state as logistics_state
                  from inventory_cabin_write_off_intent write_off
                  join inventory_plan_logistics_effect logistics
                    on logistics.inventory_id=write_off.inventory_id
                   and logistics.final_plan_version=write_off.final_plan_version
                   and logistics.outcome_reapplication_no=write_off.outcome_reapplication_no
                 where write_off.finding_id=?
                """,
                missing.getId()))
        .containsEntry("state", "PENDING")
        .containsEntry("final_plan_version", finalPlan.version())
        .containsEntry("outcome_reapplication_no", 0L)
        .containsEntry("logistics_state", "READY");
    assertThat(publications.findAllByInventoryIdOrderByFindingId(inventoryId)).isEmpty();
    verify(dependencies, times(0)).createInventoryCabinWriteOff(any(), any());
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
    PreparedFinalPlan finalPlan = prepareFinalPlan(inventoryId, furnitureReview);
    CompletionPreview preview =
        service.preview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            completionPreviewRequest(furnitureReview, finalPlan));

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
            service.recalculateOutcome(
                foreign,
                inventoryId,
                UUID.randomUUID(),
                new RecalculateInventoryOutcomeRequest(0L, 1L, "a".repeat(64))));
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
            LogisticsPlanningMode.FIXED_DATE,
            LocalDate.of(2026, 8, 12),
            catalogVersionId,
            FINGERPRINT,
            "{\"priority\":3,\"movementToRepair\":true,"
                + "\"logisticsPlanningMode\":\"FIXED_DATE\","
                + "\"logisticsScheduledDate\":\"2026-08-12\",\"lines\":["
                + "{\"groupComment\":null,\"mediaReferences\":[{\"mediaId\":"
                + "\"00000000-0000-0000-0000-000000000801\",\"generation\":4}]},"
                + "{\"groupComment\":\"material note\",\"mediaReferences\":[]}],\"stages\":["
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

  private InventoryFinding stageRecoveryFinding(
      UUID inventoryId, UUID warehouseId, String number, boolean hasWork) {
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            UUID.randomUUID(),
            3L,
            warehouseId,
            "RENTED",
            "Tenant history",
            number,
            number.replace("-", ""),
            ReconciliationState.MATCHED,
            ACTOR);
    finding = findings.saveAndFlush(finding);
    finding.saveInspection(
        hasWork ? InspectionState.WORK_STAGED : InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        hasWork ? recoveryPlanFingerprint() : null,
        ACTOR);
    return findings.saveAndFlush(finding);
  }

  private void saveRecoveryPlanSnapshot(
      UUID inventoryId, InventoryFinding finding, boolean forceCapitalRepair) {
    ObjectNode source = recoveryPlanSource();
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            finding.getId(),
            finding.getRevision(),
            inventoryId,
            "AUTO",
            false,
            null,
            null,
            UUID.randomUUID(),
            finding.getMaintenancePlanFingerprintSha256(),
            source.toString(),
            forceCapitalRepair,
            2));
  }

  private String recoveryPlanFingerprint() {
    return frozenPlanFingerprint.sha256(recoveryPlanSource());
  }

  private ObjectNode recoveryPlanSource() {
    ObjectNode source = mapper.createObjectNode();
    source.put("priority", 3);
    source.putArray("lines");
    source.putArray("mediaReferences");
    return source;
  }

  private InventoryFinding stagePublicationFinding(
      UUID inventoryId,
      UUID warehouseId,
      String number,
      boolean hasWork,
      UUID coverMediaId,
      long mediaGeneration) {
    InventoryFinding finding =
        findings.saveAndFlush(
            InventoryFinding.unexpected(
                inventoryId,
                FindingOrigin.UNEXPECTED_EXISTING,
                UUID.randomUUID(),
                3L,
                warehouseId,
                "RENTED",
                "Tenant history",
                number,
                number.replace("-", ""),
                ReconciliationState.MATCHED,
                ACTOR));
    finding.saveInspection(
        hasWork ? InspectionState.WORK_STAGED : InspectionState.READY,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        hasWork
            ? frozenPlanFingerprint.sha256(publicationPlanSource(coverMediaId, mediaGeneration))
            : null,
        "",
        coverMediaId,
        ACTOR);
    finding = findings.saveAndFlush(finding);
    if (coverMediaId != null) {
      mediaReferences.saveAndFlush(
          new FindingMediaReference(
              finding.getId(),
              finding.getRevision(),
              coverMediaId,
              mediaGeneration,
              "IMAGE"));
    }
    return finding;
  }

  private void savePublicationPlanSnapshot(
      UUID inventoryId, InventoryFinding finding, UUID mediaId, long generation) {
    ObjectNode snapshot = publicationPlanSource(mediaId, generation);
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            finding.getId(),
            finding.getRevision(),
            inventoryId,
            "AUTO",
            false,
            null,
            null,
            UUID.randomUUID(),
            finding.getMaintenancePlanFingerprintSha256(),
            snapshot.toString(),
            2));
  }

  private ObjectNode publicationPlanSource(UUID mediaId, long generation) {
    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.putArray("lines");
    snapshot
        .putArray("mediaReferences")
        .addObject()
        .put("mediaId", mediaId.toString())
        .put("generation", generation);
    return snapshot;
  }

  private void seedCompletedFinalPlan(
      UUID inventoryId, String finalPlanSha256, List<InventoryFinalPlanEntry> entries) {
    setLifecycle(inventoryId, SessionLifecycle.COMPLETED);
    jdbc.update(
        """
        insert into inventory_final_plan(
          inventory_id,row_revision,final_plan_version,state,basis_session_revision,
          planning_settings_revision,final_plan_sha256,movement_schedule_mode,
          repair_schedule_mode,created_at,updated_at)
        values (?,0,1,'COMPLETED',1,0,?,'AUTO','AUTO',clock_timestamp(),clock_timestamp())
        """,
        inventoryId,
        finalPlanSha256);
    finalPlanEntries.saveAllAndFlush(entries);
  }

  private InventoryPublicationIntent saveOutcomePublicationIntent(
      UUID inventoryId,
      InventoryFinding finding,
      String finalPlanSha256,
      FinalPlanTargetKind targetKind,
      InventoryAssetOutcomeStatus desiredStatus) {
    InventoryPublicationIntent intent =
        publications.saveAndFlush(
            InventoryPublicationIntent.readyForOutcome(
                inventoryId,
                finding.getId(),
                finding.getRevision(),
                1,
                finalPlanSha256,
                targetKind,
                desiredStatus));
    initializePublicationEvent(intent, inventoryId);
    return intent;
  }

  private InventoryFinalPlanEntry recoveryFinalPlanEntry(
      UUID inventoryId,
      InventoryFinding finding,
      int order,
      boolean hasWork,
      boolean forceCapitalRepair) {
    return new InventoryFinalPlanEntry(
        inventoryId,
        1,
        finding.getId(),
        Math.max(1, finding.getRevision()),
        finding.getAssetId(),
        finding.getAssetVersion(),
        hasWork ? finding.getMaintenancePlanFingerprintSha256() : null,
        hasWork,
        hasWork ? FinalPlanTargetKind.REPAIR : null,
        order,
        hasWork ? 3 : null,
        false,
        forceCapitalRepair,
        null,
        hasWork ? LocalDate.of(2026, 8, 20) : null,
        "[]",
        null);
  }

  private void initializePublicationEvent(
      InventoryPublicationIntent intent, UUID inventoryId) {
    events.initialize(
        "PUBLICATION",
        intent.getId(),
        "inventory.publication.ready.v1",
        "rwms.inventory.publication.v1",
        mapper
            .createObjectNode()
            .put("inventoryId", inventoryId.toString())
            .put("findingId", intent.getFindingId().toString())
            .put("publicationIntentId", intent.getId().toString()),
        UUID.randomUUID(),
        null,
        null);
  }

  private FurnitureReviewState confirmEmptyFurnitureReview(
      UUID inventoryId, UUID warehouseId, List<RevisionExpectation> findingRevisions) {
    String snapshotSha256 = "f".repeat(64);
    when(dependencies.furnitureSnapshot(eq(warehouseId), any()))
        .thenReturn(
            new InventoryDependencyGateway.FurnitureSnapshot(
                warehouseId, snapshotSha256, List.of()));
    confirmEmptyCabinDisposition(inventoryId);
    long sessionRevision = service.session(jwt(), inventoryId).sessionRevision();
    FurnitureReviewView started =
        service.startFurnitureReview(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new StartFurnitureReviewRequest(sessionRevision, findingRevisions));
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

  private void confirmEmptyCabinDisposition(UUID inventoryId) {
    var returns = service.cabinDispositionReview(jwt(), inventoryId);
    var shipments =
        service.confirmInventoryReturns(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new ConfirmInventoryReturnsRequest(
                returns.sessionRevision(),
                returns.reviewRevision(),
                returns.returnCandidates().stream()
                    .map(
                        candidate ->
                            new InventoryReturnInput(
                                candidate.findingId(),
                                candidate.findingRevision(),
                                LocalDate.now(ZoneId.of("Europe/Moscow")),
                                UUID.randomUUID(),
                                "Клиент"))
                    .toList()));
    service.confirmInventoryShipments(
        jwt(),
        inventoryId,
        UUID.randomUUID(),
        new ConfirmInventoryShipmentsRequest(
            shipments.sessionRevision(), shipments.reviewRevision(), List.of()));
  }

  private PreparedFinalPlan prepareFinalPlan(
      UUID inventoryId, FurnitureReviewState furnitureReview) {
    doAnswer(
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
            })
        .when(dependencies)
        .preflightReconciliation(any(), any());
    var plan =
        service.prepareFinalPlan(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new PrepareFinalPlanRequest(
                furnitureReview.sessionRevision(),
                0,
                FinalPlanScheduleMode.AUTO,
                FinalPlanScheduleMode.AUTO));
    return new PreparedFinalPlan(plan.finalPlanVersion(), plan.finalPlanSha256());
  }

  private InventoryFinding stageFinalPlanFinding(UUID inventoryId, UUID warehouseId) {
    return stageFinalPlanFinding(inventoryId, warehouseId, false);
  }

  private InventoryFinding stageFinalPlanFinding(
      UUID inventoryId, UUID warehouseId, boolean movementToRepair) {
    return stageFinalPlanFinding(
        inventoryId, warehouseId, movementToRepair, "WAREHOUSE", "БЫТ-FINAL-PLAN");
  }

  private InventoryFinding stageRentedFinalPlanFinding(
      UUID inventoryId, UUID warehouseId, String number) {
    return stageFinalPlanFinding(inventoryId, warehouseId, false, "RENTED", number);
  }

  private InventoryFinding stageFinalPlanFinding(
      UUID inventoryId, UUID warehouseId, boolean movementToRepair, String status, String number) {
    UUID assetId = UUID.randomUUID();
    InventoryFinding finding =
        InventoryFinding.unexpected(
            inventoryId,
            FindingOrigin.UNEXPECTED_EXISTING,
            assetId,
            1L,
            warehouseId,
            status,
            null,
            number,
            number.replace("-", ""),
            ReconciliationState.MATCHED,
            ACTOR);
    ObjectNode snapshot = mapper.createObjectNode();
    snapshot.put("priority", 3);
    snapshot.put("movementToRepair", movementToRepair);
    if (movementToRepair) {
      snapshot.put("logisticsPlanningMode", "AUTO");
      snapshot.putNull("logisticsScheduledDate");
    } else {
      snapshot.putNull("logisticsPlanningMode");
      snapshot.putNull("logisticsScheduledDate");
    }
    snapshot.putArray("lines");
    String fingerprint = frozenPlanFingerprint.sha256(snapshot);
    finding.saveInspection(
        InspectionState.WORK_STAGED,
        ReconciliationState.MATCHED,
        ObservationPresence.ABSENT,
        null,
        ObservationPresence.ABSENT,
        null,
        fingerprint,
        "Final-plan reconciliation validation",
        ACTOR);
    finding = findings.saveAndFlush(finding);
    planSnapshots.saveAndFlush(
        new FindingPlanSnapshot(
            finding.getId(),
            finding.getRevision(),
            inventoryId,
            "AUTO",
            movementToRepair,
            movementToRepair ? LogisticsPlanningMode.AUTO : null,
            null,
            UUID.randomUUID(),
            fingerprint,
            snapshot.toString(),
            2));
    return finding;
  }

  private FinalPlanUpdateRequest finalPlanUpdateRequest(
      dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FinalPlanView plan,
      dev.buhanzaz.rwms.inventory.api.InventoryApiModels.FinalPlanEntryView entry,
      FinalPlanReconciliationDecision decision) {
    return new FinalPlanUpdateRequest(
        plan.sessionRevision(),
        plan.finalPlanVersion(),
        FinalPlanScheduleMode.AUTO,
        FinalPlanScheduleMode.AUTO,
        List.of(
            new FinalPlanEntryUpdate(
                entry.findingId(),
                entry.findingRevision(),
                entry.order(),
                entry.priority(),
                entry.movementToRepair(),
                null,
                null,
                decision)));
  }

  private ObjectNode finalPlanCandidate(UUID targetId, boolean started, boolean active) {
    ObjectNode candidate = mapper.createObjectNode();
    candidate.put("targetKind", "REPAIR");
    candidate.put("targetId", targetId.toString());
    candidate.putNull("estimateId");
    candidate.put("repairId", targetId.toString());
    candidate.put("version", 1);
    candidate.put("state", started ? "IN_PROGRESS" : "DRAFT");
    candidate.put("started", started);
    candidate.put("active", active);
    candidate.put("forceCapitalRepair", false);
    candidate.put("priority", 3);
    candidate.put("sourceParty", "OWNER");
    candidate.putNull("planFingerprintSha256");
    candidate
        .putObject("planSummary")
        .put("workLineCount", 1)
        .put("materialLineCount", 1)
        .put("grandTotalMinor", 100);
    return candidate;
  }

  private CompletionPreviewRequest completionPreviewRequest(
      FurnitureReviewState furnitureReview, PreparedFinalPlan finalPlan) {
    return new CompletionPreviewRequest(
        furnitureReview.sessionRevision(),
        finalPlan.version(),
        finalPlan.sha256(),
        furnitureReview.findingRevisions());
  }

  private UUID stubRefreshCapture(
      UUID warehouseId,
      List<InventoryDependencyGateway.CaptureMember> members,
      Runnable afterCaptureRead) {
    UUID captureId = UUID.randomUUID();
    String membershipDigest = captureDigest(members);
    AtomicReference<InventoryDependencyGateway.CaptureRequest> request =
        new AtomicReference<>();
    when(dependencies.createCapture(any(), any()))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                  .as("refresh capture must start outside the idempotent local transaction")
                  .isFalse();
              InventoryDependencyGateway.CaptureRequest value = invocation.getArgument(1);
              request.set(value);
              return new InventoryDependencyGateway.Capture(
                  captureId,
                  value.operationId(),
                  value.technicalAttempt(),
                  value.warehouseId(),
                  members.size(),
                  membershipDigest,
                  OffsetDateTime.now(ZoneOffset.UTC),
                  OffsetDateTime.now(ZoneOffset.UTC).plusMinutes(5));
            });
    when(dependencies.readCapture(eq(captureId), any(), eq(200)))
        .thenAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                  .as("refresh capture must be copied outside the idempotent local transaction")
                  .isFalse();
              assertThat(invocation.<String>getArgument(1)).isNull();
              if (afterCaptureRead != null) {
                afterCaptureRead.run();
              }
              InventoryDependencyGateway.CaptureRequest value = request.get();
              return new InventoryDependencyGateway.CapturePage(
                  captureId,
                  value.operationId(),
                  value.technicalAttempt(),
                  warehouseId,
                  members.size(),
                  membershipDigest,
                  null,
                  members);
            });
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive())
                  .as("refresh capture must release after the local transaction")
                  .isFalse();
              return null;
            })
        .when(dependencies)
        .releaseCapture(captureId);
    return captureId;
  }

  private String captureDigest(List<InventoryDependencyGateway.CaptureMember> members) {
    List<Map<String, Object>> digestMembers = new ArrayList<>();
    for (InventoryDependencyGateway.CaptureMember member : members) {
      Map<String, Object> digestMember = new LinkedHashMap<>();
      digestMember.put("sequence", member.sequence());
      digestMember.put("assetId", member.assetId());
      digestMember.put("version", member.version());
      digestMember.put("warehouseId", member.warehouseId());
      digestMember.put("status", member.status());
      digestMember.put("displayCanonicalNumber", member.displayCanonicalNumber());
      digestMember.put("identityMatchKey", member.identityMatchKey());
      digestMember.put("passportSnapshot", mapper.convertValue(member.passportSnapshot(), Map.class));
      digestMember.put("contentsSnapshot", member.contentsSnapshot());
      digestMembers.add(digestMember);
    }
    return canonicalJson.sha256(digestMembers);
  }

  private void verifyNoInteractionsOnCurrentAsset() {
    verify(dependencies, times(0)).currentAsset(any());
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

  private void setBusinessDate(UUID inventoryId, LocalDate businessDate) {
    jdbc.update("update inventory_session set business_date=? where id=?", businessDate, inventoryId);
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
    return jwtForWarehouseLevel(warehouseId, "MANAGE");
  }

  private Jwt jwtForWarehouseLevel(UUID warehouseId, String level) {
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
            List.of(Map.of("warehouseId", warehouseId.toString(), "level", level)))
        .build();
  }

  private record Fixture(
      UUID expectedFindingId,
      UUID expectedAssetId,
      UUID readyFindingId,
      UUID readyAssetId,
      UUID untouchedFindingId,
      UUID untouchedAssetId) {}

  private record PreparedFinalPlan(long version, String sha256) {}

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
