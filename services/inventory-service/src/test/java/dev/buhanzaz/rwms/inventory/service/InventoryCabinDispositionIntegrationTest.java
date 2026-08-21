package dev.buhanzaz.rwms.inventory.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConfirmInventoryReturnsRequest;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.ConfirmInventoryShipmentsRequest;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryCabinDispositionReviewView;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryReturnInput;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryShipmentFurnitureInput;
import dev.buhanzaz.rwms.inventory.api.InventoryApiModels.InventoryShipmentInput;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionKind;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinDispositionReviewPhase;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinWriteOffIntent;
import dev.buhanzaz.rwms.inventory.domain.InventoryCabinWriteOffIntentState;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanEffectState;
import dev.buhanzaz.rwms.inventory.domain.InventoryPlanLogisticsEffect;
import dev.buhanzaz.rwms.inventory.eventing.InventoryEventStore;
import dev.buhanzaz.rwms.inventory.integration.InventoryDependencyGateway;
import dev.buhanzaz.rwms.inventory.repository.InventoryCabinWriteOffIntentRepository;
import dev.buhanzaz.rwms.inventory.repository.InventoryPlanLogisticsEffectRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
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
      "rwms.inventory.capture-release-recovery-delay-ms=600000",
      "rwms.inventory.cabin-write-off-recovery-delay-ms=600000",
      "rwms.inventory.plan-logistics-recovery-delay-ms=600000"
    })
@ActiveProfiles("test")
class InventoryCabinDispositionIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final ZoneId WAREHOUSE_ZONE = ZoneId.of("Europe/Moscow");

  static {
    POSTGRES.start();
  }

  @Autowired InventoryApplicationService application;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;
  @Autowired InventoryCabinWriteOffService cabinWriteOffs;
  @Autowired InventoryPlanLogisticsReconciliationService planLogistics;
  @Autowired InventoryCabinWriteOffIntentRepository writeOffIntents;
  @Autowired InventoryPlanLogisticsEffectRepository logisticsEffects;
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
  void exactReturnAndSelectedShipmentLeaveEveryOmittedMissingCabinAsWriteOffAndReplayExactly() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID returnedFindingId = UUID.randomUUID();
    UUID shippedFindingId = UUID.randomUUID();
    UUID omittedFindingId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID equipmentId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    seedFinding(inventoryId, returnedFindingId, UUID.randomUUID(), "RET-01");
    seedFinding(inventoryId, shippedFindingId, UUID.randomUUID(), "MISS-SHIP");
    seedFinding(inventoryId, omittedFindingId, UUID.randomUUID(), "MISS-WRITE");
    markInspectedRented(returnedFindingId, warehouseId, "RET-01");

    InventoryCabinDispositionReviewView initial =
        application.cabinDispositionReview(jwt(), inventoryId);
    assertThat(initial.phase()).isEqualTo(InventoryCabinDispositionReviewPhase.RETURNS);
    assertThat(initial.returnCandidates())
        .extracting(value -> value.findingId())
        .containsExactly(returnedFindingId);
    assertThat(initial.missingCandidates())
        .extracting(value -> value.findingId())
        .containsExactlyInAnyOrder(shippedFindingId, omittedFindingId);
    assertThatThrownBy(
            () ->
                application.confirmInventoryReturns(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new ConfirmInventoryReturnsRequest(
                        initial.sessionRevision(), initial.reviewRevision(), List.of())))
        .isInstanceOf(InventoryException.class)
        .hasMessageContaining("every found rented cabin");

    LocalDate today = LocalDate.now(WAREHOUSE_ZONE);
    ConfirmInventoryReturnsRequest returnsRequest =
        new ConfirmInventoryReturnsRequest(
            initial.sessionRevision(),
            initial.reviewRevision(),
            List.of(
                new InventoryReturnInput(
                    returnedFindingId, 0, today, clientId, "Клиент возврата")));
    UUID returnKey = UUID.randomUUID();
    InventoryCabinDispositionReviewView shipments =
        application.confirmInventoryReturns(jwt(), inventoryId, returnKey, returnsRequest);
    JsonNode replayedReturns =
        mapper.valueToTree(
            application.confirmInventoryReturns(jwt(), inventoryId, returnKey, returnsRequest));
    assertThat(replayedReturns)
        .isEqualTo(mapper.valueToTree(shipments));
    assertThat(shipments.phase()).isEqualTo(InventoryCabinDispositionReviewPhase.SHIPMENTS);

    ConfirmInventoryShipmentsRequest shipmentRequest =
        new ConfirmInventoryShipmentsRequest(
            shipments.sessionRevision(),
            shipments.reviewRevision(),
            List.of(
                new InventoryShipmentInput(
                    shippedFindingId,
                    0,
                    today,
                    clientId,
                    "Клиент отгрузки",
                    List.of(new InventoryShipmentFurnitureInput(equipmentId, 17, 500)))));
    UUID shipmentKey = UUID.randomUUID();
    InventoryCabinDispositionReviewView completed =
        application.confirmInventoryShipments(jwt(), inventoryId, shipmentKey, shipmentRequest);
    assertThat(completed.phase()).isEqualTo(InventoryCabinDispositionReviewPhase.COMPLETED);
    JsonNode replayedShipments =
        mapper.valueToTree(
            application.confirmInventoryShipments(
                jwt(), inventoryId, shipmentKey, shipmentRequest));
    assertThat(replayedShipments)
        .isEqualTo(mapper.valueToTree(completed));

    Map<UUID, Map<String, Object>> decisions =
        jdbc.query(
            "select finding_id,disposition_kind,disposition_details::text from inventory_cabin_disposition_row where inventory_id=?",
            (result, row) ->
                Map.entry(
                    result.getObject("finding_id", UUID.class),
                    Map.<String, Object>of(
                        "kind",
                        result.getString("disposition_kind"),
                        "details",
                        result.getString("disposition_details"))),
            inventoryId).stream()
            .collect(java.util.stream.Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
    assertThat(decisions.get(returnedFindingId).get("kind"))
        .isEqualTo(InventoryCabinDispositionKind.LOCAL.name());
    assertThat(decisions.get(returnedFindingId).get("details").toString())
        .contains("formerRental", "Клиент возврата");
    assertThat(decisions.get(shippedFindingId).get("kind"))
        .isEqualTo(InventoryCabinDispositionKind.SHIPMENT.name());
    assertThat(decisions.get(shippedFindingId).get("details").toString())
        .contains(equipmentId.toString(), "500", "17");
    assertThat(decisions.get(omittedFindingId).get("kind"))
        .isEqualTo(InventoryCabinDispositionKind.WRITE_OFF.name());
  }

  @Test
  void emptyShipmentConfirmationWritesOffEveryMissingCabin() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID first = UUID.randomUUID();
    UUID second = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    seedFinding(inventoryId, first, UUID.randomUUID(), "MISS-01");
    seedFinding(inventoryId, second, UUID.randomUUID(), "MISS-02");

    InventoryCabinDispositionReviewView returns =
        application.cabinDispositionReview(jwt(), inventoryId);
    InventoryCabinDispositionReviewView shipments =
        application.confirmInventoryReturns(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new ConfirmInventoryReturnsRequest(
                returns.sessionRevision(), returns.reviewRevision(), List.of()));
    InventoryCabinDispositionReviewView completed =
        application.confirmInventoryShipments(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new ConfirmInventoryShipmentsRequest(
                shipments.sessionRevision(), shipments.reviewRevision(), List.of()));

    assertThat(completed.phase()).isEqualTo(InventoryCabinDispositionReviewPhase.COMPLETED);
    assertThat(
            jdbc.queryForList(
                "select disposition_kind from inventory_cabin_disposition_row where inventory_id=? order by finding_id",
                String.class,
                inventoryId))
        .containsExactly(
            InventoryCabinDispositionKind.WRITE_OFF.name(),
            InventoryCabinDispositionKind.WRITE_OFF.name());
  }

  @Test
  void shipmentFurnitureRejectsNullRowsBeforeCanonicalOrdering() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    seedFinding(inventoryId, findingId, UUID.randomUUID(), "MISS-NULL-FURNITURE");
    InventoryCabinDispositionReviewView returns =
        application.cabinDispositionReview(jwt(), inventoryId);
    InventoryCabinDispositionReviewView shipments =
        application.confirmInventoryReturns(
            jwt(),
            inventoryId,
            UUID.randomUUID(),
            new ConfirmInventoryReturnsRequest(
                returns.sessionRevision(), returns.reviewRevision(), List.of()));

    assertThatThrownBy(
            () ->
                application.confirmInventoryShipments(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new ConfirmInventoryShipmentsRequest(
                        shipments.sessionRevision(),
                        shipments.reviewRevision(),
                        List.of(
                            new InventoryShipmentInput(
                                findingId,
                                0,
                                LocalDate.now(WAREHOUSE_ZONE),
                                UUID.randomUUID(),
                                "Клиент",
                                java.util.Collections.singletonList(null))))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique positive rows");
  }

  @Test
  void sessionReviewFindingAndWarehouseDateFencesRejectStaleDispositionCommandsAndMutationRebuildsReview() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    seedFinding(inventoryId, findingId, UUID.randomUUID(), "RET-FENCE");
    markInspectedRented(findingId, warehouseId, "RET-FENCE");
    InventoryCabinDispositionReviewView review =
        application.cabinDispositionReview(jwt(), inventoryId);
    LocalDate today = LocalDate.now(WAREHOUSE_ZONE);

    assertReturnConflict(
        inventoryId,
        new ConfirmInventoryReturnsRequest(
            review.sessionRevision() + 1,
            review.reviewRevision(),
            List.of(new InventoryReturnInput(findingId, 0, today, clientId, "Клиент"))),
        "revision");
    assertReturnConflict(
        inventoryId,
        new ConfirmInventoryReturnsRequest(
            review.sessionRevision(),
            review.reviewRevision() + 1,
            List.of(new InventoryReturnInput(findingId, 0, today, clientId, "Клиент"))),
        "review revision");
    assertReturnConflict(
        inventoryId,
        new ConfirmInventoryReturnsRequest(
            review.sessionRevision(),
            review.reviewRevision(),
            List.of(new InventoryReturnInput(findingId, 1, today, clientId, "Клиент"))),
        "finding revision");
    assertThatThrownBy(
            () ->
                application.confirmInventoryReturns(
                    jwt(),
                    inventoryId,
                    UUID.randomUUID(),
                    new ConfirmInventoryReturnsRequest(
                        review.sessionRevision(),
                        review.reviewRevision(),
                        List.of(
                            new InventoryReturnInput(
                                findingId, 0, today.plusDays(1), clientId, "Клиент")))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("current warehouse date");

    jdbc.update("update inventory_finding set finding_revision=1 where id=?", findingId);
    InventoryCabinDispositionReviewView rebuilt =
        application.cabinDispositionReview(jwt(), inventoryId);
    assertThat(rebuilt.phase()).isEqualTo(InventoryCabinDispositionReviewPhase.RETURNS);
    assertThat(rebuilt.reviewRevision()).isGreaterThan(review.reviewRevision());
    assertThat(rebuilt.returnCandidates().getFirst().findingRevision()).isEqualTo(1L);
    assertThat(
            jdbc.queryForObject(
                "select inspection from inventory_finding where id=?", String.class, findingId))
        .isEqualTo("READY");
  }

  @Test
  void writeOffWaitsForExactLogisticsSuccessAndReusesOneStableMaintenanceCommand() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID cabinId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryPlanLogisticsEffect effect = saveLogisticsEffect(inventoryId, 3, 4);
    InventoryCabinWriteOffIntent intent =
        saveWriteOffIntent(inventoryId, warehouseId, findingId, cabinId, 3, 4);

    cabinWriteOffs.recoverCabinWriteOffs();
    cabinWriteOffs.recoverCabinWriteOffs();

    verifyNoInteractions(dependencies);
    assertThat(writeOffIntents.findById(findingId).orElseThrow().getAttemptCount()).isZero();
    effect.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));
    effect = logisticsEffects.saveAndFlush(effect);
    effect.succeed("{}");
    logisticsEffects.saveAndFlush(effect);
    UUID decisionId = UUID.randomUUID();
    when(dependencies.createInventoryCabinWriteOff(any(), any()))
        .thenReturn(
            new InventoryDependencyGateway.InventoryCabinWriteOffOutcome(
                decisionId,
                0,
                inventoryId,
                findingId,
                warehouseId,
                cabinId,
                "WRITE_OFF",
                "PENDING"));

    cabinWriteOffs.recoverCabinWriteOffs();
    cabinWriteOffs.recoverCabinWriteOffs();

    InventoryCabinWriteOffIntent succeeded = writeOffIntents.findById(findingId).orElseThrow();
    assertThat(succeeded.getState()).isEqualTo(InventoryCabinWriteOffIntentState.SUCCEEDED);
    assertThat(succeeded.getDecisionId()).isEqualTo(decisionId);
    assertThat(succeeded.getAttemptCount()).isEqualTo(1);
    assertThat(succeeded.getIdempotencyKey()).isEqualTo(intent.getIdempotencyKey());
    verify(dependencies, times(1)).createInventoryCabinWriteOff(intent.getIdempotencyKey(),
        new InventoryDependencyGateway.InventoryCabinWriteOffRequest(
            inventoryId, findingId, warehouseId, cabinId, 1, "Автосписание", null));
  }

  @Test
  void blockedLogisticsBlocksDependentWriteOffOnceWithoutHotPollingMaintenance() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryPlanLogisticsEffect effect = saveLogisticsEffect(inventoryId, 1, 0);
    effect.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));
    effect = logisticsEffects.saveAndFlush(effect);
    effect.block("LOGISTICS_PLAN_REJECTED");
    logisticsEffects.saveAndFlush(effect);
    saveWriteOffIntent(inventoryId, warehouseId, findingId, UUID.randomUUID(), 1, 0);

    cabinWriteOffs.recoverCabinWriteOffs();
    cabinWriteOffs.recoverCabinWriteOffs();

    InventoryCabinWriteOffIntent blocked = writeOffIntents.findById(findingId).orElseThrow();
    assertThat(blocked.getState()).isEqualTo(InventoryCabinWriteOffIntentState.BLOCKED);
    assertThat(blocked.getFailureCode())
        .isEqualTo("CABIN_WRITE_OFF_UPSTREAM_LOGISTICS_BLOCKED");
    assertThat(blocked.getAttemptCount()).isZero();
    verifyNoInteractions(dependencies);
  }

  @Test
  void planLogisticsAutomaticRecoveryStopsAfterEightTransientAttempts() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryPlanLogisticsEffect effect = saveLogisticsEffect(inventoryId, 1, 0);
    when(dependencies.applyLogisticsOutcomes(any(), any(), any()))
        .thenThrow(
            new InventoryException(
                HttpStatus.TOO_MANY_REQUESTS,
                "LOGISTICS_RATE_LIMITED",
                "logistics unavailable"));

    for (int attempt = 1; attempt <= 8; attempt++) {
      planLogistics.recoverPlanLogisticsEffects();
      jdbc.update(
          "update inventory_plan_logistics_effect set next_attempt_at=clock_timestamp()-interval '1 second' where id=?",
          effect.getId());
    }

    InventoryPlanLogisticsEffect blocked = logisticsEffects.findById(effect.getId()).orElseThrow();
    assertThat(blocked.getState()).isEqualTo(InventoryPlanEffectState.BLOCKED);
    assertThat(blocked.getAttemptCount()).isEqualTo(8);
    assertThat(blocked.getFailureCode()).isEqualTo("LOGISTICS_RETRY_EXHAUSTED");
    planLogistics.recoverPlanLogisticsEffects();
    verify(dependencies, times(8)).applyLogisticsOutcomes(any(), any(), any());
  }

  @Test
  void cabinWriteOffAutomaticRecoveryStopsAfterEightTransientAttempts() {
    UUID inventoryId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    seedSession(inventoryId, warehouseId);
    InventoryPlanLogisticsEffect effect = saveLogisticsEffect(inventoryId, 1, 0);
    effect.beginAttempt(OffsetDateTime.now(ZoneOffset.UTC).plusSeconds(30));
    effect = logisticsEffects.saveAndFlush(effect);
    effect.succeed("{}");
    logisticsEffects.saveAndFlush(effect);
    saveWriteOffIntent(inventoryId, warehouseId, findingId, UUID.randomUUID(), 1, 0);
    when(dependencies.createInventoryCabinWriteOff(any(), any()))
        .thenThrow(
            new InventoryException(
                HttpStatus.TOO_MANY_REQUESTS,
                "MAINTENANCE_RATE_LIMITED",
                "maintenance unavailable"));

    for (int attempt = 1; attempt <= 8; attempt++) {
      cabinWriteOffs.recoverCabinWriteOffs();
      jdbc.update(
          "update inventory_cabin_write_off_intent set next_attempt_at=clock_timestamp()-interval '1 second' where finding_id=?",
          findingId);
    }

    InventoryCabinWriteOffIntent blocked = writeOffIntents.findById(findingId).orElseThrow();
    assertThat(blocked.getState()).isEqualTo(InventoryCabinWriteOffIntentState.BLOCKED);
    assertThat(blocked.getAttemptCount()).isEqualTo(8);
    assertThat(blocked.getFailureCode()).isEqualTo("CABIN_WRITE_OFF_RETRY_EXHAUSTED");
    cabinWriteOffs.recoverCabinWriteOffs();
    verify(dependencies, times(8)).createInventoryCabinWriteOff(any(), any());
  }

  private void assertReturnConflict(
      UUID inventoryId, ConfirmInventoryReturnsRequest request, String message) {
    assertThatThrownBy(
            () ->
                application.confirmInventoryReturns(
                    jwt(), inventoryId, UUID.randomUUID(), request))
        .isInstanceOf(InventoryException.class)
        .hasMessageContaining(message);
  }

  private InventoryPlanLogisticsEffect saveLogisticsEffect(
      UUID inventoryId, long finalPlanVersion, long reapplicationNo) {
    return logisticsEffects.saveAndFlush(
        InventoryPlanLogisticsEffect.ready(
            inventoryId,
            finalPlanVersion,
            "c".repeat(64),
            reapplicationNo,
            UUID.randomUUID(),
            "d".repeat(64),
            "{\"inventoryId\":\""
                + inventoryId
                + "\",\"finalPlanVersion\":"
                + finalPlanVersion
                + "}"));
  }

  private InventoryCabinWriteOffIntent saveWriteOffIntent(
      UUID inventoryId,
      UUID warehouseId,
      UUID findingId,
      UUID cabinId,
      long finalPlanVersion,
      long reapplicationNo) {
    String request =
        "{\"inventorySessionId\":\""
            + inventoryId
            + "\",\"findingId\":\""
            + findingId
            + "\",\"warehouseId\":\""
            + warehouseId
            + "\",\"cabinId\":\""
            + cabinId
            + "\",\"expectedAssetVersion\":1,\"reason\":\"Автосписание\",\"evidenceLink\":null}";
    return writeOffIntents.saveAndFlush(
        InventoryCabinWriteOffIntent.pending(
            findingId,
            inventoryId,
            warehouseId,
            cabinId,
            finalPlanVersion,
            reapplicationNo,
            UUID.randomUUID(),
            "e".repeat(64),
            request));
  }

  private void seedSession(UUID inventoryId, UUID warehouseId) {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
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
        current,
        current,
        current);
  }

  private void seedFinding(UUID inventoryId, UUID findingId, UUID assetId, String number) {
    OffsetDateTime current = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,
          asset_id,asset_version_snapshot,display_canonical_number,identity_match_key,
          passport_observation_state,equipment_observation_state,mutation_state,
          actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','MATCHED',?,1,?,?,
          'ABSENT','ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        assetId,
        number,
        number.replace("-", ""),
        actorJson(),
        current,
        current);
  }

  private void markInspectedRented(UUID findingId, UUID warehouseId, String number) {
    jdbc.update(
        """
        update inventory_finding
           set inspection='READY',
               current_warehouse_id=?,current_status='RENTED',
               current_display_canonical_number=?,current_passport_snapshot='{}'::jsonb,
               current_contents_snapshot='[]'::jsonb,current_repairs_snapshot='[]'::jsonb,
               inspection_asset_version=asset_version_snapshot,inspection_warehouse_id=?,
               inspection_status='RENTED',inspection_display_canonical_number=?,
               inspection_passport_snapshot='{}'::jsonb,
               inspection_contents_snapshot='[]'::jsonb,inspection_repairs_snapshot='[]'::jsonb
         where id=?
        """,
        warehouseId,
        number,
        warehouseId,
        number,
        findingId);
  }

  private String actorJson() {
    return "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
        + "\"principalType\":\"USER\",\"profileRevision\":null}";
  }

  private Jwt jwt() {
    Instant current = Instant.now();
    return Jwt.withTokenValue("inventory-disposition-test")
        .header("alg", "none")
        .subject("00000000-0000-0000-0000-000000000701")
        .issuedAt(current)
        .expiresAt(current.plusSeconds(300))
        .claim("principal_type", "USER")
        .claim("scope", "rwms.write rwms.read")
        .claim("global_role", "SYSTEM_ADMIN")
        .claim("warehouse_access", List.of())
        .build();
  }
}
