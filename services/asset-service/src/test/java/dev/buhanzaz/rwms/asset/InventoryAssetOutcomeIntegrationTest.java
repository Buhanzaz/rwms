package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_NEW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_ORDINARY;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CHARACTERISTIC_ELECTRICS_KK;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CHARACTERISTIC_PLASTIC_WINDOW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.plasticWindow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireLogisticsOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeShipmentContent;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeStatus;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureReconciliationCabin;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureReconciliationItem;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureReconciliationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureSnapshotRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventorySourceAssetRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventorySourceAssetResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventorySourceOutcomeCandidate;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsFencedEffectRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsLeaseOwnerType;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.LogisticsRentalItemAction;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.InventoryAssetService;
import dev.buhanzaz.rwms.asset.service.InventoryOperationalStateChangedException;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.SerializationFeature;
import tools.jackson.databind.node.ObjectNode;

/** Verifies completed-inventory ordering, replay, guard release and exact HTTP response identity. */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class InventoryAssetOutcomeIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final UUID TYPE_BK_2 =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000002");
  private static final UUID FINISHING_LDSP =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000202");
  private static final UUID CATEGORY_ORDINARY_ID =
      UUID.fromString("af57f2b0-3a71-4b7f-8d2f-000000000403");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired InventoryAssetService inventory;
  @Autowired OrderUnitReservationRepository orderReservations;
  @Autowired PresentationUnitHoldRepository presentationHolds;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AssetEventStore events;
  @Autowired PlatformTransactionManager transactionManager;

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

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void sourceProposalMaterializesAtItsExactUuidOnlyInsideFurnitureReconciliation() {
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    var proposal =
        inventory.createSourceAsset(
            new InventorySourceAssetRequest(
                inventoryId,
                findingId,
                warehouseId,
                "SOURCE-ATOMIC-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                CATEGORY_NEW,
                plasticWindow(),
                true,
                Map.of("origin", "inventory"),
                List.of("SOURCE")));
    UUID assetId = proposal.response().asset().assetId();
    InventoryOutcomeRequest outcome =
        request(warehouseId, assetId, now(), InventoryOutcomeStatus.REPAIR);

    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item where id=?", Integer.class, assetId))
        .isZero();
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(), inventoryId, findingId, UUID.randomUUID(), outcome))
        .isInstanceOf(dev.buhanzaz.rwms.asset.service.AssetNotFoundException.class);

    var snapshot =
        inventory.furnitureSnapshot(
            new InventoryFurnitureSnapshotRequest(warehouseId, List.of(assetId)));
    List<InventoryFurnitureReconciliationItem> items =
        snapshot.items().stream()
            .map(
                item ->
                    new InventoryFurnitureReconciliationItem(
                        item.equipmentId(),
                        item.catalogVersion(),
                        item.currentStockQuantity(),
                        item.cabins().stream()
                            .map(
                                cabin ->
                                    new InventoryFurnitureReconciliationCabin(
                                        cabin.assetId(), cabin.currentQuantity()))
                            .toList()))
            .toList();
    inventory.reconcileFurniture(
        inventoryId,
        UUID.randomUUID(),
        new InventoryFurnitureReconciliationRequest(
            warehouseId,
            snapshot.snapshotSha256(),
            "b".repeat(64),
            items,
            List.of(new InventorySourceOutcomeCandidate(findingId, outcome))));

    assertThat(jdbc.queryForObject("select id from rental_item where id=?", UUID.class, assetId))
        .isEqualTo(assetId);
    assertThat(currentStatus(assetId)).isEqualTo(RentalItemStatus.REPAIR);
    assertThat(jdbc.queryForObject(
            "select count(*) from inventory_asset_source where inventory_id=? and finding_id=?",
            Integer.class,
            inventoryId,
            findingId))
        .isOne();
    assertThat(jdbc.queryForObject(
            "select count(*) from domain_event where aggregate_id=? and event_type='asset.rental-item.created.v1'",
            Integer.class,
            assetId.toString()))
        .isOne();
    assertThat(
            inventory
                .createSourceAsset(
                    new InventorySourceAssetRequest(
                        inventoryId,
                        findingId,
                        warehouseId,
                        proposal.response().asset().displayCanonicalNumber(),
                        TYPE_BK_1,
                        DIMENSION_24_X_6,
                        FINISHING_DVP,
                        CATEGORY_NEW,
                        plasticWindow(),
                        true,
                        Map.of("origin", "inventory"),
                        List.of("SOURCE")))
                .response())
        .isEqualTo(proposal.response());
  }

  @Test
  void sourceOutcomeBatchRejectsMixedFinalPlanAndCrossWarehouseExistingAsset() {
    UUID warehouseId = UUID.randomUUID();
    UUID inventoryId = UUID.randomUUID();
    UUID firstFindingId = UUID.randomUUID();
    UUID secondFindingId = UUID.randomUUID();
    UUID firstAssetId =
        sourceProposal(inventoryId, firstFindingId, warehouseId).response().asset().assetId();
    UUID secondAssetId =
        sourceProposal(inventoryId, secondFindingId, warehouseId).response().asset().assetId();
    OffsetDateTime completedAt = now();
    InventoryOutcomeRequest first =
        requestWithPlan(
            warehouseId,
            firstAssetId,
            completedAt,
            2L,
            "a".repeat(64),
            InventoryOutcomeStatus.FREE);
    InventoryOutcomeRequest mixed =
        requestWithPlan(
            warehouseId,
            secondAssetId,
            completedAt,
            3L,
            "a".repeat(64),
            InventoryOutcomeStatus.REPAIR);

    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    inventoryId,
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        warehouseId,
                        "c".repeat(64),
                        "d".repeat(64),
                        List.of(),
                        List.of(
                            new InventorySourceOutcomeCandidate(firstFindingId, first),
                            new InventorySourceOutcomeCandidate(secondFindingId, mixed)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("one completed final-plan fence");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item where id in (?, ?)",
                Integer.class,
                firstAssetId,
                secondAssetId))
        .isZero();

    UUID otherWarehouseAsset = rentalItem(UUID.randomUUID(), "SOURCE-WRONG-WAREHOUSE-").id();
    InventoryOutcomeRequest crossWarehouse =
        request(warehouseId, otherWarehouseAsset, completedAt, InventoryOutcomeStatus.FREE);
    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        warehouseId,
                        "e".repeat(64),
                        "f".repeat(64),
                        List.of(),
                        List.of(
                            new InventorySourceOutcomeCandidate(
                                UUID.randomUUID(), crossWarehouse)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another warehouse");
  }

  @Test
  void sourceNumberCollisionRollsBackMaterializationButRetainsIsolatedProposal() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse existing = rentalItem(warehouseId, "SOURCE-COLLISION-");
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    var proposal =
        inventory.createSourceAsset(
            new InventorySourceAssetRequest(
                inventoryId,
                findingId,
                warehouseId,
                existing.number(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                CATEGORY_NEW,
                plasticWindow(),
                true,
                Map.of(),
                List.of()));
    UUID proposedAssetId = proposal.response().asset().assetId();
    InventoryOutcomeRequest outcome =
        request(warehouseId, proposedAssetId, now(), InventoryOutcomeStatus.FREE);

    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    inventoryId,
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        warehouseId,
                        "1".repeat(64),
                        "2".repeat(64),
                        List.of(),
                        List.of(new InventorySourceOutcomeCandidate(findingId, outcome)))))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("number or reserved identity");

    assertThat(jdbc.queryForObject(
            "select count(*) from inventory_asset_source_operation where inventory_id=? and finding_id=?",
            Integer.class,
            inventoryId,
            findingId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_item where id=?", Integer.class, proposedAssetId))
        .isZero();
    assertThat(jdbc.queryForObject(
            "select count(*) from inventory_asset_source where inventory_id=? and finding_id=?",
            Integer.class,
            inventoryId,
            findingId))
        .isZero();
    assertThat(jdbc.queryForObject(
            "select count(*) from inventory_asset_outcome_receipt where asset_id=?",
            Integer.class,
            proposedAssetId))
        .isZero();
    assertThat(jdbc.queryForObject(
            "select count(*) from domain_event where aggregate_id=?",
            Integer.class,
            proposedAssetId.toString()))
        .isZero();
  }

  @Test
  void releasesBindingsPersistsExactReplayAndAllowsLatestSourceRecovery() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-GUARDS-");
    setStatus(cabin.id(), RentalItemStatus.RENTED, null);
    var lease =
        assets
            .acquireLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    cabin.id(), "OUTCOME_TEST", "repair", cabin.version()))
            .response();
    OrderUnitReservation reservation =
        orderReservations.saveAndFlush(
            OrderUnitReservation.create(
                UUID.randomUUID(),
                cabin.id(),
                warehouseId,
                UUID.randomUUID(),
                "WMS_ADMIN"));
    OffsetDateTime now = now();
    PresentationUnitHold hold =
        presentationHolds.saveAndFlush(
            PresentationUnitHold.create(
                UUID.randomUUID(),
                cabin.id(),
                warehouseId,
                now.plusMinutes(30),
                UUID.randomUUID(),
                "WMS_ADMIN",
                now));

    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    InventoryOutcomeRequest request =
        request(warehouseId, cabin.id(), now.minusMinutes(1), InventoryOutcomeStatus.FREE);
    InventoryAssetService.OutcomeResult applied =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, request);

    assertThat(applied.replayed()).isFalse();
    assertThat(applied.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(applied.response().releasedOperationLeaseIds()).containsExactly(lease.id());
    assertThat(applied.response().releasedOrderUnitReservationIds())
        .containsExactly(reservation.getId());
    assertThat(applied.response().releasedPresentationHoldIds()).containsExactly(hold.getId());
    assertThat(applied.response().transferSuperseded()).isFalse();
    assertThat(state("operation_lease", lease.id())).isEqualTo("RELEASED");
    assertThat(state("order_unit_reservation", reservation.getId())).isEqualTo("RELEASED");
    assertThat(state("presentation_unit_hold", hold.getId())).isEqualTo("RELEASED");

    InventoryAssetService.OutcomeResult replay =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, request);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(applied.response());
    InventoryOutcomeRequest explicitFalse =
        new InventoryOutcomeRequest(
            request.warehouseId(),
            request.assetId(),
            request.inventoryCompletedAt(),
            request.finalPlanVersion(),
            request.finalPlanSha256(),
            request.findingRevision(),
            request.desiredStatus(),
            request.passportObservation(),
            request.passportObservationSha256(),
            request.shipmentContents(),
            false);
    InventoryAssetService.OutcomeResult explicitFalseReplay =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, explicitFalse);
    assertThat(explicitFalseReplay.replayed()).isTrue();
    assertThat(explicitFalseReplay.response()).isEqualTo(applied.response());
    InventoryOutcomeRequest changedReplay =
        request(
            warehouseId,
            cabin.id(),
            request.inventoryCompletedAt(),
            InventoryOutcomeStatus.REPAIR);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(), inventoryId, findingId, idempotencyKey, changedReplay))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("bound to another request");

    mvc.perform(
            put(
                    "/api/internal/asset/v1/inventory/outcomes/{inventoryId}/findings/{findingId}",
                    inventoryId,
                    findingId)
                .with(inventoryJwt())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.inventoryId").value(inventoryId.toString()))
        .andExpect(jsonPath("$.findingId").value(findingId.toString()))
        .andExpect(jsonPath("$.assetId").value(cabin.id().toString()))
        .andExpect(jsonPath("$.assetVersion").value(applied.response().assetVersion()))
        .andExpect(jsonPath("$.status").value("FREE"))
        .andExpect(jsonPath("$.releasedOrderUnitReservationIds[0]").value(reservation.getId().toString()))
        .andExpect(jsonPath("$.releasedOperationLeaseIds[0]").value(lease.id().toString()))
        .andExpect(jsonPath("$.releasedPresentationHoldIds[0]").value(hold.getId().toString()))
        .andExpect(jsonPath("$.transferSuperseded").value(false));

    setStatus(cabin.id(), RentalItemStatus.BOOKED, null);
    var currentInventoryRepairLease =
        assets
            .acquireLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    cabin.id(),
                    "MAINTENANCE_REPAIR",
                    UUID.randomUUID().toString(),
                    applied.response().assetVersion()))
            .response();
    OrderUnitReservation laterReservation =
        orderReservations.saveAndFlush(
            OrderUnitReservation.create(
                UUID.randomUUID(),
                cabin.id(),
                warehouseId,
                UUID.randomUUID(),
                "WMS_ADMIN"));
    InventoryAssetService.OutcomeResult recovered =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, UUID.randomUUID(), request);
    assertThat(recovered.replayed()).isFalse();
    assertThat(recovered.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(recovered.response().releasedOrderUnitReservationIds())
        .containsExactly(laterReservation.getId());
    assertThat(recovered.response().releasedOperationLeaseIds())
        .containsExactly(currentInventoryRepairLease.id());
    assertThat(state("operation_lease", currentInventoryRepairLease.id())).isEqualTo("RELEASED");

    InventoryOutcomeRequest older =
        request(
            warehouseId,
            cabin.id(),
            request.inventoryCompletedAt().minusSeconds(1),
            InventoryOutcomeStatus.REPAIR);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    older))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("older completed inventory");
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Equal-time inventory outcome conflicts");
    assertThat(currentStatus(cabin.id())).isEqualTo(RentalItemStatus.FREE);
  }

  @Test
  void acceptsHigherPlanCorrectionAndRetainsOnlyItsMaintenanceRepairLease() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-PLAN-CORRECTION-");
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    OffsetDateTime completedAt = now();
    InventoryOutcomeRequest initial =
        requestWithPlan(
            warehouseId, cabin.id(), completedAt, 2L, "a".repeat(64), InventoryOutcomeStatus.FREE);
    InventoryAssetService.OutcomeResult initiallyApplied =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, UUID.randomUUID(), initial);

    setStatus(cabin.id(), RentalItemStatus.RENTED, null);
    var staleLogisticsLease =
        assets
            .acquireLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    cabin.id(),
                    "LOGISTICS_SHIPMENT",
                    UUID.randomUUID().toString(),
                    initiallyApplied.response().assetVersion()))
            .response();
    InventoryOutcomeRequest corrected =
        requestWithPlan(
            warehouseId,
            cabin.id(),
            completedAt,
            3L,
            "b".repeat(64),
            InventoryOutcomeStatus.CAPITAL_REPAIR);
    UUID correctedKey = UUID.randomUUID();
    InventoryAssetService.OutcomeResult correction =
        inventory.applyOutcome(UUID.randomUUID(), inventoryId, findingId, correctedKey, corrected);

    assertThat(correction.replayed()).isFalse();
    assertThat(correction.response().status()).isEqualTo(RentalItemStatus.CAPITAL_REPAIR);
    assertThat(correction.response().releasedOperationLeaseIds())
        .containsExactly(staleLogisticsLease.id());
    assertThat(state("operation_lease", staleLogisticsLease.id())).isEqualTo("RELEASED");
    assertThat(watermarkPlanVersion(cabin.id())).isEqualTo(3L);
    assertThat(watermarkPlanHash(cabin.id())).isEqualTo("b".repeat(64));

    InventoryAssetService.OutcomeResult replay =
        inventory.applyOutcome(UUID.randomUUID(), inventoryId, findingId, correctedKey, corrected);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(correction.response());

    var repairLease =
        assets
            .acquireLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    cabin.id(),
                    "MAINTENANCE_REPAIR",
                    UUID.randomUUID().toString(),
                    correction.response().assetVersion()))
            .response();
    InventoryAssetService.OutcomeResult reasserted =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, UUID.randomUUID(), corrected);
    assertThat(reasserted.replayed()).isFalse();
    assertThat(reasserted.response().releasedOperationLeaseIds()).isEmpty();
    assertThat(state("operation_lease", repairLease.id())).isEqualTo("ACTIVE");

    InventoryOutcomeRequest lowerPlan =
        requestWithPlan(
            warehouseId, cabin.id(), completedAt, 2L, "c".repeat(64), InventoryOutcomeStatus.FREE);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(), inventoryId, findingId, UUID.randomUUID(), lowerPlan))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Equal-time inventory outcome conflicts");

    InventoryOutcomeRequest sameVersionHashDrift =
        requestWithPlan(
            warehouseId,
            cabin.id(),
            completedAt,
            3L,
            "d".repeat(64),
            InventoryOutcomeStatus.CAPITAL_REPAIR);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    inventoryId,
                    findingId,
                    UUID.randomUUID(),
                    sameVersionHashDrift))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Equal-time inventory outcome conflicts");
    assertThat(currentStatus(cabin.id())).isEqualTo(RentalItemStatus.CAPITAL_REPAIR);
    assertThat(watermarkPlanVersion(cabin.id())).isEqualTo(3L);
  }

  @Test
  void overridesOperationalStatusesButRollsBackWrongWarehouseAndTerminalCabins() {
    RentalItemStatus[] sources = {
      RentalItemStatus.BOOKED,
      RentalItemStatus.RENTED,
      RentalItemStatus.IN_TRANSFER,
      RentalItemStatus.REPAIR,
      RentalItemStatus.CAPITAL_REPAIR
    };
    InventoryOutcomeStatus[] targets = InventoryOutcomeStatus.values();
    UUID warehouseId = UUID.randomUUID();
    int sequence = 0;
    for (RentalItemStatus source : sources) {
      for (InventoryOutcomeStatus target : targets) {
        RentalItemResponse cabin =
            rentalItem(warehouseId, "OUTCOME-STATUS-" + sequence + "-");
        setStatus(
            cabin.id(),
            source,
            source == RentalItemStatus.IN_TRANSFER ? RentalItemStatus.FREE : null);
        InventoryAssetService.OutcomeResult result =
            inventory.applyOutcome(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                request(warehouseId, cabin.id(), now().plusSeconds(sequence), target));
        assertThat(result.response().status().name()).isEqualTo(target.name());
        assertThat(result.response().transferSuperseded())
            .isEqualTo(source == RentalItemStatus.IN_TRANSFER);
        assertThat(
                jdbc.queryForObject(
                    "select transfer_origin_status from rental_item where id=?",
                    String.class,
                    cabin.id()))
            .isNull();
        sequence++;
      }
    }

    RentalItemResponse wrongWarehouse = rentalItem(warehouseId, "OUTCOME-WRONG-WAREHOUSE-");
    setStatus(wrongWarehouse.id(), RentalItemStatus.BOOKED, null);
    UUID wrongKey = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    wrongKey,
                    request(
                        UUID.randomUUID(),
                        wrongWarehouse.id(),
                        now(),
                        InventoryOutcomeStatus.FREE)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("another warehouse");
    assertThat(currentStatus(wrongWarehouse.id())).isEqualTo(RentalItemStatus.BOOKED);
    assertThat(receiptCount(wrongKey)).isZero();

    for (RentalItemStatus terminalStatus :
        List.of(RentalItemStatus.LOST, RentalItemStatus.WRITTEN_OFF)) {
      RentalItemResponse terminal =
          rentalItem(
              warehouseId,
              terminalStatus == RentalItemStatus.LOST ? "OUTCOME-TERM-L-" : "OUTCOME-TERM-W-");
      setStatus(terminal.id(), terminalStatus, null);
      UUID terminalKey = UUID.randomUUID();
      assertThatThrownBy(
              () ->
                  inventory.applyOutcome(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      terminalKey,
                      request(
                          warehouseId,
                          terminal.id(),
                          now(),
                          InventoryOutcomeStatus.FREE)))
          .isInstanceOf(AssetConflictException.class)
          .hasMessageContaining("Lost or written-off");
      assertThat(currentStatus(terminal.id())).isEqualTo(terminalStatus);
      assertThat(receiptCount(terminalKey)).isZero();
    }
  }

  @Test
  void rentedOutcomeExactlyReplacesCabinContentsWithoutReadingOrChangingStock() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-RENTED-CONTENTS-");
    EquipmentResponse first = equipment("Inventory rented first ");
    EquipmentResponse second = equipment("Inventory rented second ");
    EquipmentResponse omitted = equipment("Inventory rented omitted ");
    UUID firstStock =
        seedBalance(first.id(), warehouseId, null, BalanceLocationKind.STOCK, 0L);
    UUID secondStock =
        seedBalance(second.id(), warehouseId, null, BalanceLocationKind.STOCK, 12L);
    UUID omittedStock =
        seedBalance(omitted.id(), warehouseId, null, BalanceLocationKind.STOCK, 13L);
    seedBalance(
        first.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED, 4L);
    seedBalance(
        second.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_RENTED, 7L);
    seedBalance(
        omitted.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED, 5L);
    seedBalance(
        omitted.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_RENTED, 6L);
    Map<UUID, Long> initialStockVersions =
        Map.of(
            firstStock, balanceVersion(firstStock),
            secondStock, balanceVersion(secondStock),
            omittedStock, balanceVersion(omittedStock));
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    InventoryOutcomeRequest request =
        rentedRequest(
            warehouseId,
            cabin.id(),
            now(),
            List.of(
                new InventoryOutcomeShipmentContent(second.id(), second.version(), 3L),
                new InventoryOutcomeShipmentContent(first.id(), first.version(), 2L)));

    InventoryAssetService.OutcomeResult applied =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, request);

    assertThat(applied.replayed()).isFalse();
    assertThat(applied.response().status()).isEqualTo(RentalItemStatus.RENTED);
    assertThat(applied.response().assetVersion()).isEqualTo(cabin.version() + 1);
    assertThat(cabinQuantity(first.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_RENTED))
        .isEqualTo(2L);
    assertThat(
            cabinQuantity(
                first.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED))
        .isZero();
    assertThat(cabinQuantity(second.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_RENTED))
        .isEqualTo(3L);
    assertThat(
            cabinQuantity(
                omitted.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED))
        .isZero();
    assertThat(
            cabinQuantity(
                omitted.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_RENTED))
        .isZero();
    assertThat(balanceQuantity(firstStock)).isZero();
    assertThat(balanceQuantity(secondStock)).isEqualTo(12L);
    assertThat(balanceQuantity(omittedStock)).isEqualTo(13L);
    assertThat(balanceVersion(firstStock)).isEqualTo(initialStockVersions.get(firstStock));
    assertThat(balanceVersion(secondStock)).isEqualTo(initialStockVersions.get(secondStock));
    assertThat(balanceVersion(omittedStock)).isEqualTo(initialStockVersions.get(omittedStock));
    assertThat(
            jdbc.queryForObject(
                "select shipment_contents_sha256 from inventory_asset_outcome_watermark where asset_id=?",
                String.class,
                cabin.id()))
        .matches("[0-9a-f]{64}");
    long cabinBalanceEvents = cabinBalanceEventCount(cabin.id());

    InventoryAssetService.OutcomeResult replay =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, request);
    InventoryAssetService.OutcomeResult reasserted =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, UUID.randomUUID(), request);

    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(applied.response());
    assertThat(reasserted.replayed()).isFalse();
    assertThat(reasserted.response().assetVersion()).isEqualTo(applied.response().assetVersion());
    assertThat(cabinBalanceEventCount(cabin.id())).isEqualTo(cabinBalanceEvents);
    assertThat(balanceQuantity(firstStock)).isZero();
    assertThat(balanceQuantity(secondStock)).isEqualTo(12L);
    assertThat(balanceQuantity(omittedStock)).isEqualTo(13L);

    InventoryOutcomeRequest changedContents =
        rentedRequest(
            warehouseId,
            cabin.id(),
            request.inventoryCompletedAt(),
            List.of(new InventoryOutcomeShipmentContent(first.id(), first.version(), 9L)));
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    inventoryId,
                    findingId,
                    UUID.randomUUID(),
                    changedContents))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Equal-time inventory outcome conflicts");
    assertThat(cabinQuantity(first.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_RENTED))
        .isEqualTo(2L);
    assertThat(balanceQuantity(firstStock)).isZero();
  }

  @Test
  void rejectsMissingForbiddenOrStaleShipmentContentsBeforePersistingAnyOutcome() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-RENTED-INVALID-");
    EquipmentResponse equipment = equipment("Inventory rented stale ");
    UUID stock = seedBalance(equipment.id(), warehouseId, null, BalanceLocationKind.STOCK, 8L);
    UUID rentedMissingKey = UUID.randomUUID();
    InventoryOutcomeRequest rentedMissing =
        request(warehouseId, cabin.id(), now(), InventoryOutcomeStatus.FREE);
    rentedMissing =
        new InventoryOutcomeRequest(
            rentedMissing.warehouseId(),
            rentedMissing.assetId(),
            rentedMissing.inventoryCompletedAt(),
            rentedMissing.finalPlanVersion(),
            rentedMissing.finalPlanSha256(),
            rentedMissing.findingRevision(),
            InventoryOutcomeStatus.RENTED,
            rentedMissing.passportObservation(),
            rentedMissing.passportObservationSha256(),
            null);
    InventoryOutcomeRequest finalRentedMissing = rentedMissing;

    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    rentedMissingKey,
                    finalRentedMissing))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("requires shipmentContents");
    assertThat(receiptCount(rentedMissingKey)).isZero();

    UUID localContentsKey = UUID.randomUUID();
    InventoryOutcomeRequest localWithContents =
        new InventoryOutcomeRequest(
            finalRentedMissing.warehouseId(),
            finalRentedMissing.assetId(),
            finalRentedMissing.inventoryCompletedAt(),
            finalRentedMissing.finalPlanVersion(),
            finalRentedMissing.finalPlanSha256(),
            finalRentedMissing.findingRevision(),
            InventoryOutcomeStatus.FREE,
            finalRentedMissing.passportObservation(),
            finalRentedMissing.passportObservationSha256(),
            List.of(new InventoryOutcomeShipmentContent(equipment.id(), equipment.version(), 1L)));
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    localContentsKey,
                    localWithContents))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("must be null");
    assertThat(receiptCount(localContentsKey)).isZero();

    UUID staleKey = UUID.randomUUID();
    InventoryOutcomeRequest stale =
        rentedRequest(
            warehouseId,
            cabin.id(),
            now().plusSeconds(1),
            List.of(
                new InventoryOutcomeShipmentContent(
                    equipment.id(), equipment.version() + 1, 2L)));
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), staleKey, stale))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("catalog identity or version is stale");
    assertThat(receiptCount(staleKey)).isZero();
    assertThat(watermarkCount(cabin.id())).isZero();
    assertThat(currentStatus(cabin.id())).isEqualTo(RentalItemStatus.FREE);
    assertThat(currentVersion(cabin.id())).isEqualTo(cabin.version());
    assertThat(balanceQuantity(stock)).isEqualTo(8L);
    assertThat(balanceVersion(stock)).isZero();
  }

  @Test
  void overwritesPresentPassportPreservesAbsentAndAdoptsLegacyWatermarkOnce() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-PASSPORT-");
    OffsetDateTime completedAt = now();

    ObjectNode observed = objectMapper.createObjectNode();
    observed.put("presence", "PRESENT");
    ObjectNode value = observed.putObject("value");
    value.put("rentalType", "БК-2");
    value.put("dimensions", "2.4x6");
    value.put("finishing", "ЛДСП");
    value.put("category", CATEGORY_ORDINARY);
    value.putArray("characteristics")
        .add("Электрика КК, Пластиковое окно, Электрика КК");
    value.putNull("linoleum");
    InventoryOutcomeRequest present =
        request(warehouseId, cabin.id(), completedAt, InventoryOutcomeStatus.FREE, observed);

    UUID presentInventoryId = UUID.randomUUID();
    UUID presentFindingId = UUID.randomUUID();
    UUID presentKey = UUID.randomUUID();
    InventoryAssetService.OutcomeResult applied =
        inventory.applyOutcome(
            UUID.randomUUID(), presentInventoryId, presentFindingId, presentKey, present);

    assertThat(applied.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(applied.response().assetVersion()).isEqualTo(cabin.version() + 1);
    Map<String, Object> composition = composition(cabin.id());
    assertThat(composition.get("cabin_type_id")).isEqualTo(TYPE_BK_2);
    assertThat(composition.get("cabin_dimension_id")).isEqualTo(DIMENSION_24_X_6);
    assertThat(composition.get("cabin_finishing_id")).isEqualTo(FINISHING_LDSP);
    assertThat(composition.get("cabin_category_id")).isEqualTo(CATEGORY_ORDINARY_ID);
    assertThat(composition.get("category")).isEqualTo(CATEGORY_ORDINARY);
    assertThat(composition.get("linoleum")).isNull();
    assertThat(characteristics(cabin.id()))
        .containsExactly(CHARACTERISTIC_ELECTRICS_KK, CHARACTERISTIC_PLASTIC_WINDOW);
    assertThat(passportEventCount(cabin.id())).isEqualTo(1);
    assertThat(watermarkPassportHash(cabin.id())).isEqualTo(present.passportObservationSha256());

    ObjectNode changedSameKeyObservation = observed.deepCopy();
    ((ObjectNode) changedSameKeyObservation.get("value")).put("linoleum", true);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    presentInventoryId,
                    presentFindingId,
                    presentKey,
                    request(
                        warehouseId,
                        cabin.id(),
                        present.inventoryCompletedAt(),
                        InventoryOutcomeStatus.FREE,
                        changedSameKeyObservation)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("bound to another request");

    ObjectNode clearCharacteristics = observed.deepCopy();
    ObjectNode clearValue = (ObjectNode) clearCharacteristics.get("value");
    clearValue.remove("characteristics");
    clearValue.put("linoleum", false);
    InventoryOutcomeRequest cleared =
        request(
            warehouseId,
            cabin.id(),
            completedAt.plusSeconds(1),
            InventoryOutcomeStatus.FREE,
            clearCharacteristics);
    InventoryAssetService.OutcomeResult clearedResult =
        inventory.applyOutcome(
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), cleared);
    assertThat(clearedResult.response().assetVersion()).isEqualTo(applied.response().assetVersion() + 1);
    assertThat(characteristics(cabin.id())).isEmpty();
    assertThat(composition(cabin.id()).get("linoleum")).isEqualTo(false);
    Map<String, Object> beforeAbsent = composition(cabin.id());

    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    UUID absentInventoryId = UUID.randomUUID();
    UUID absentFindingId = UUID.randomUUID();
    InventoryOutcomeRequest preserve =
        request(
            warehouseId,
            cabin.id(),
            completedAt.plusSeconds(2),
            InventoryOutcomeStatus.FREE,
            absent);
    InventoryAssetService.OutcomeResult preserved =
        inventory.applyOutcome(
            UUID.randomUUID(),
            absentInventoryId,
            absentFindingId,
            UUID.randomUUID(),
            preserve);
    assertThat(preserved.response().assetVersion()).isEqualTo(clearedResult.response().assetVersion());
    assertThat(composition(cabin.id())).isEqualTo(beforeAbsent);
    assertThat(passportEventCount(cabin.id())).isEqualTo(2);

    jdbc.update(
        "update inventory_asset_outcome_watermark set passport_observation_sha256=null where asset_id=?",
        cabin.id());
    InventoryAssetService.OutcomeResult adopted =
        inventory.applyOutcome(
            UUID.randomUUID(),
            absentInventoryId,
            absentFindingId,
            UUID.randomUUID(),
            preserve);
    assertThat(adopted.response().assetVersion()).isEqualTo(preserved.response().assetVersion());
    assertThat(watermarkPassportHash(cabin.id())).isEqualTo(preserve.passportObservationSha256());

    UUID driftKey = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    absentInventoryId,
                    absentFindingId,
                    driftKey,
                    request(
                        warehouseId,
                        cabin.id(),
                        preserve.inventoryCompletedAt(),
                        InventoryOutcomeStatus.FREE,
                        observed)))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("Equal-time inventory outcome conflicts");
    assertThat(receiptCount(driftKey)).isZero();

    ObjectNode unexpected = observed.deepCopy();
    unexpected.put("unexpected", true);
    UUID unexpectedKey = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    unexpectedKey,
                    request(
                        warehouseId,
                        cabin.id(),
                        completedAt.plusSeconds(3),
                        InventoryOutcomeStatus.FREE,
                        unexpected)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("passport observation is invalid");
    assertThat(receiptCount(unexpectedKey)).isZero();
  }

  @Test
  void unknownPassportCatalogRollsBackBindingsAssetWatermarkAndReceipt() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-PASSPORT-ROLLBACK-");
    setStatus(cabin.id(), RentalItemStatus.BOOKED, null);
    OrderUnitReservation reservation =
        orderReservations.saveAndFlush(
            OrderUnitReservation.create(
                UUID.randomUUID(),
                cabin.id(),
                warehouseId,
                UUID.randomUUID(),
                "WMS_ADMIN"));
    ObjectNode observed = objectMapper.createObjectNode();
    observed.put("presence", "PRESENT");
    ObjectNode value = observed.putObject("value");
    value.put("rentalType", "Неизвестный тип");
    value.put("dimensions", "2.4x6");
    value.put("finishing", "ДВП");
    value.put("category", CATEGORY_NEW);
    UUID key = UUID.randomUUID();

    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    key,
                    request(
                        warehouseId,
                        cabin.id(),
                        now(),
                        InventoryOutcomeStatus.REPAIR,
                        observed)))
        .hasMessageContaining("Cabin setting was not found");

    assertThat(currentStatus(cabin.id())).isEqualTo(RentalItemStatus.BOOKED);
    assertThat(currentVersion(cabin.id())).isEqualTo(cabin.version());
    assertThat(state("order_unit_reservation", reservation.getId())).isEqualTo("ACTIVE");
    assertThat(receiptCount(key)).isZero();
    assertThat(watermarkCount(cabin.id())).isZero();
    assertThat(passportEventCount(cabin.id())).isZero();
  }

  @Test
  void passportOnlyOutcomePreservesRentedStateBindingsContentsAndWarehouse() {
    UUID assetWarehouseId = UUID.randomUUID();
    UUID historicalWarehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(assetWarehouseId, "OUTCOME-PASSPORT-ONLY-");
    setStatus(cabin.id(), RentalItemStatus.RENTED, null);
    var lease =
        assets
            .acquireLease(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new AcquireOperationLeaseRequest(
                    cabin.id(), "LOGISTICS_RENTAL", UUID.randomUUID().toString(), cabin.version()))
            .response();
    OrderUnitReservation reservation =
        orderReservations.saveAndFlush(
            OrderUnitReservation.create(
                UUID.randomUUID(),
                cabin.id(),
                assetWarehouseId,
                UUID.randomUUID(),
                "WMS_ADMIN"));
    OffsetDateTime currentTime = now();
    PresentationUnitHold hold =
        presentationHolds.saveAndFlush(
            PresentationUnitHold.create(
                UUID.randomUUID(),
                cabin.id(),
                assetWarehouseId,
                currentTime.plusMinutes(30),
                UUID.randomUUID(),
                "WMS_ADMIN",
                currentTime));
    EquipmentResponse equipment = equipment("Passport-only contents ");
    seedBalance(
        equipment.id(),
        assetWarehouseId,
        cabin.id(),
        BalanceLocationKind.CABIN_RENTED,
        7L);

    ObjectNode observed = objectMapper.createObjectNode();
    observed.put("presence", "PRESENT");
    ObjectNode value = observed.putObject("value");
    value.put("rentalType", "БК-2");
    value.put("dimensions", "2.4x6");
    value.put("finishing", "ЛДСП");
    value.put("category", CATEGORY_ORDINARY);
    value.putArray("characteristics").add("Электрика КК");
    value.put("linoleum", false);
    UUID inventoryId = UUID.randomUUID();
    UUID findingId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    InventoryOutcomeRequest request =
        preserveRequest(historicalWarehouseId, cabin.id(), currentTime, observed);

    InventoryAssetService.OutcomeResult applied =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, request);

    assertThat(applied.replayed()).isFalse();
    assertThat(applied.response().status()).isEqualTo(RentalItemStatus.RENTED);
    assertThat(applied.response().assetVersion()).isEqualTo(cabin.version() + 1);
    assertThat(applied.response().releasedOperationLeaseIds()).isEmpty();
    assertThat(applied.response().releasedOrderUnitReservationIds()).isEmpty();
    assertThat(applied.response().releasedPresentationHoldIds()).isEmpty();
    assertThat(applied.response().transferSuperseded()).isFalse();
    assertThat(currentStatus(cabin.id())).isEqualTo(RentalItemStatus.RENTED);
    assertThat(currentWarehouse(cabin.id())).isEqualTo(assetWarehouseId);
    assertThat(state("operation_lease", lease.id())).isEqualTo("ACTIVE");
    assertThat(state("order_unit_reservation", reservation.getId())).isEqualTo("ACTIVE");
    assertThat(state("presentation_unit_hold", hold.getId())).isEqualTo("ACTIVE");
    assertThat(
            cabinQuantity(
                equipment.id(),
                assetWarehouseId,
                cabin.id(),
                BalanceLocationKind.CABIN_RENTED))
        .isEqualTo(7L);
    assertThat(composition(cabin.id()).get("cabin_type_id")).isEqualTo(TYPE_BK_2);
    assertThat(composition(cabin.id()).get("cabin_finishing_id")).isEqualTo(FINISHING_LDSP);
    assertThat(characteristics(cabin.id())).containsExactly(CHARACTERISTIC_ELECTRICS_KK);
    assertThat(
            jdbc.queryForObject(
                "select preserve_operational_state from inventory_asset_outcome_receipt where idempotency_key=?",
                Boolean.class,
                idempotencyKey))
        .isTrue();
    assertThat(
            jdbc.queryForObject(
                "select desired_status from inventory_asset_outcome_receipt where idempotency_key=?",
                String.class,
                idempotencyKey))
        .isNull();

    InventoryAssetService.OutcomeResult replay =
        inventory.applyOutcome(
            UUID.randomUUID(), inventoryId, findingId, idempotencyKey, request);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(applied.response());
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_asset_outcome_receipt set preserve_operational_state=false where idempotency_key=?",
                    idempotencyKey))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_asset_outcome_watermark set preserve_operational_state=false where asset_id=?",
                    cabin.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update inventory_asset_outcome_watermark set preserve_operational_state=false,desired_status='RENTED' where asset_id=?",
                    cabin.id()))
        .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);

    setStatus(cabin.id(), RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION, null);
    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    InventoryAssetService.OutcomeResult waiting =
        inventory.applyOutcome(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            preserveRequest(
                historicalWarehouseId, cabin.id(), currentTime.plusSeconds(1), absent));
    assertThat(waiting.response().status())
        .isEqualTo(RentalItemStatus.WAITING_ESTIMATE_CONFIRMATION);
    assertThat(state("operation_lease", lease.id())).isEqualTo("ACTIVE");
    assertThat(state("order_unit_reservation", reservation.getId())).isEqualTo("ACTIVE");
    assertThat(state("presentation_unit_hold", hold.getId())).isEqualTo("ACTIVE");
  }

  @Test
  void preservationModeRequiresNullStatusAndContentsAndCannotMaterializeSources() {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-PRESERVE-VALIDATION-");
    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    OffsetDateTime completedAt = now();

    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryOutcomeRequest(
                        warehouseId,
                        cabin.id(),
                        completedAt,
                        2L,
                        "a".repeat(64),
                        3L,
                        InventoryOutcomeStatus.FREE,
                        absent,
                        passportObservationHash(absent),
                        null,
                        true)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("desiredStatus must be null only");
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryOutcomeRequest(
                        warehouseId,
                        cabin.id(),
                        completedAt,
                        2L,
                        "a".repeat(64),
                        3L,
                        null,
                        absent,
                        passportObservationHash(absent),
                        null,
                        false)))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("desiredStatus must be null only");
    InventoryOutcomeRequest preserveWithContents =
        new InventoryOutcomeRequest(
            warehouseId,
            cabin.id(),
            completedAt,
            2L,
            "a".repeat(64),
            3L,
            null,
            absent,
            passportObservationHash(absent),
            List.of(new InventoryOutcomeShipmentContent(UUID.randomUUID(), 0L, 1L)),
            true);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    preserveWithContents))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("shipmentContents must be null");
    InventoryOutcomeRequest preserveWithVersion =
        new InventoryOutcomeRequest(
            warehouseId,
            cabin.id(),
            completedAt,
            2L,
            "a".repeat(64),
            3L,
            null,
            absent,
            passportObservationHash(absent),
            null,
            0L,
            true);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    preserveWithVersion))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("expectedAssetVersion must be omitted");

    InventoryOutcomeRequest sourcePreserve =
        preserveRequest(warehouseId, UUID.randomUUID(), completedAt, absent);
    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        warehouseId,
                        "b".repeat(64),
                        "c".repeat(64),
                        List.of(),
                        List.of(
                            new InventorySourceOutcomeCandidate(
                                UUID.randomUUID(), sourcePreserve)))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot preserve operational state");

    for (RentalItemStatus terminalStatus :
        List.of(RentalItemStatus.LOST, RentalItemStatus.WRITTEN_OFF)) {
      setStatus(cabin.id(), terminalStatus, null);
      assertThatThrownBy(
              () ->
                  inventory.applyOutcome(
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      UUID.randomUUID(),
                      preserveRequest(
                          UUID.randomUUID(), cabin.id(), completedAt.plusSeconds(1), absent)))
          .isInstanceOf(AssetConflictException.class)
          .hasMessageContaining("Lost or written-off");
    }
  }

  @Test
  void expectedAssetVersionFencesNewRentalButAllowsObservedHistoricalRental() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OUTCOME-OPERATIONAL-FENCE-");
    long observedVersion = cabin.version();
    UUID logisticsSubject = UUID.randomUUID();
    UUID documentId = UUID.randomUUID();
    UUID lineId = UUID.randomUUID();
    var lease =
        assets
            .acquireLogisticsLease(
                logisticsSubject,
                UUID.randomUUID(),
                new AcquireLogisticsOperationLeaseRequest(
                    cabin.id(),
                    LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT,
                    documentId,
                    lineId,
                    observedVersion))
            .response();
    var shipped =
        assets
            .applyLogisticsEffect(
                logisticsSubject,
                UUID.randomUUID(),
                cabin.id(),
                new LogisticsFencedEffectRequest(
                    observedVersion,
                    LogisticsRentalItemAction.SHIPMENT_CONFIRM,
                    lease.leaseId(),
                    lease.fencingToken(),
                    LogisticsLeaseOwnerType.LOGISTICS_SHIPMENT,
                    documentId,
                    lineId,
                    null,
                    null))
            .response();
    assertThat(shipped.status()).isEqualTo(RentalItemStatus.RENTED);
    assertThat(shipped.version()).isGreaterThan(observedVersion);

    UUID freeInventoryId = UUID.randomUUID();
    UUID freeFindingId = UUID.randomUUID();
    UUID freeKey = UUID.randomUUID();
    InventoryOutcomeRequest staleFree =
        guardedRequest(
            warehouseId,
            cabin.id(),
            now(),
            InventoryOutcomeStatus.FREE,
            observedVersion);
    mvc.perform(
            put(
                    "/api/internal/asset/v1/inventory/outcomes/{inventoryId}/findings/{findingId}",
                    freeInventoryId,
                    freeFindingId)
                .with(inventoryJwt())
                .header("Idempotency-Key", freeKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(staleFree)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("INVENTORY_OPERATIONAL_STATE_CHANGED"));

    UUID repairKey = UUID.randomUUID();
    InventoryOutcomeRequest staleRepair =
        guardedRequest(
            warehouseId,
            cabin.id(),
            staleFree.inventoryCompletedAt(),
            InventoryOutcomeStatus.REPAIR,
            observedVersion);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    repairKey,
                    staleRepair))
        .isInstanceOf(InventoryOperationalStateChangedException.class)
        .extracting(
            error -> ((InventoryOperationalStateChangedException) error).code())
        .isEqualTo("INVENTORY_OPERATIONAL_STATE_CHANGED");
    assertThat(currentStatus(cabin.id())).isEqualTo(RentalItemStatus.RENTED);
    assertThat(currentVersion(cabin.id())).isEqualTo(shipped.version());
    assertThat(state("operation_lease", lease.leaseId())).isEqualTo("ACTIVE");
    assertThat(receiptCount(freeKey)).isZero();
    assertThat(receiptCount(repairKey)).isZero();
    assertThat(watermarkCount(cabin.id())).isZero();

    UUID acceptedInventoryId = UUID.randomUUID();
    UUID acceptedFindingId = UUID.randomUUID();
    UUID acceptedKey = UUID.randomUUID();
    InventoryOutcomeRequest observedHistoricalReturn =
        guardedRequest(
            warehouseId,
            cabin.id(),
            staleFree.inventoryCompletedAt().plusSeconds(1),
            InventoryOutcomeStatus.FREE,
            shipped.version());
    InventoryAssetService.OutcomeResult accepted =
        inventory.applyOutcome(
            UUID.randomUUID(),
            acceptedInventoryId,
            acceptedFindingId,
            acceptedKey,
            observedHistoricalReturn);
    assertThat(accepted.response().status()).isEqualTo(RentalItemStatus.FREE);
    assertThat(state("operation_lease", lease.leaseId())).isEqualTo("RELEASED");
    InventoryAssetService.OutcomeResult replay =
        inventory.applyOutcome(
            UUID.randomUUID(),
            acceptedInventoryId,
            acceptedFindingId,
            acceptedKey,
            observedHistoricalReturn);
    assertThat(replay.replayed()).isTrue();
    assertThat(replay.response()).isEqualTo(accepted.response());

    InventoryOutcomeRequest omittedFence =
        request(
            warehouseId,
            cabin.id(),
            observedHistoricalReturn.inventoryCompletedAt(),
            InventoryOutcomeStatus.FREE);
    assertThatThrownBy(
            () ->
                inventory.applyOutcome(
                    UUID.randomUUID(),
                    acceptedInventoryId,
                    acceptedFindingId,
                    acceptedKey,
                    omittedFence))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("bound to another request");
  }

  private RentalItemResponse rentalItem(UUID warehouseId, String prefix) {
    return assets
        .createRentalItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                warehouseId,
                prefix + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                CATEGORY_NEW,
                plasticWindow(),
                true,
                Map.of(),
                List.of()))
        .response();
  }

  private InventoryAssetService.CreateResult<InventorySourceAssetResponse> sourceProposal(
      UUID inventoryId, UUID findingId, UUID warehouseId) {
    return inventory.createSourceAsset(
        new InventorySourceAssetRequest(
            inventoryId,
            findingId,
            warehouseId,
            "SOURCE-BATCH-" + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            CATEGORY_NEW,
            plasticWindow(),
            true,
            Map.of(),
            List.of()));
  }

  private EquipmentResponse equipment(String prefix) {
    return assets
        .createEquipment(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateEquipmentRequest(
                prefix + UUID.randomUUID(), EquipmentCategory.FURNITURE, null))
        .response();
  }

  private InventoryOutcomeRequest rentedRequest(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      List<InventoryOutcomeShipmentContent> shipmentContents) {
    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    return new InventoryOutcomeRequest(
        warehouseId,
        assetId,
        completedAt,
        2L,
        "a".repeat(64),
        3L,
        InventoryOutcomeStatus.RENTED,
        absent,
        passportObservationHash(absent),
        shipmentContents);
  }

  private UUID seedBalance(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {
    UUID balanceId = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  """
                  insert into equipment_balance(
                    id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
                    created_at,updated_at)
                  values (?,0,?,?,?,?,?,clock_timestamp(),clock_timestamp())
                  """,
                  balanceId,
                  equipmentId,
                  warehouseId,
                  rentalItemId,
                  locationKind.name(),
                  quantity);
              events.initialize(
                  AssetAggregateType.EQUIPMENT_BALANCE,
                  balanceId,
                  0L,
                  AssetEventType.EQUIPMENT_BALANCE_CHANGED,
                  balanceFact(
                      balanceId,
                      equipmentId,
                      warehouseId,
                      rentalItemId,
                      locationKind,
                      quantity),
                  balanceFact(
                      balanceId,
                      equipmentId,
                      warehouseId,
                      rentalItemId,
                      locationKind,
                      quantity));
            });
    return balanceId;
  }

  private static Map<String, Object> balanceFact(
      UUID balanceId,
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind,
      long quantity) {
    Map<String, Object> fact = new LinkedHashMap<>();
    fact.put("balanceId", balanceId.toString());
    fact.put("equipmentId", equipmentId.toString());
    fact.put("warehouseId", warehouseId.toString());
    fact.put("rentalItemId", rentalItemId == null ? null : rentalItemId.toString());
    fact.put("locationKind", locationKind.name());
    fact.put("quantity", quantity);
    return fact;
  }

  private long cabinQuantity(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind) {
    Long value =
        jdbc.queryForObject(
            """
            select quantity
            from equipment_balance
            where equipment_id=? and warehouse_id=? and rental_item_id=? and location_kind=?
            """,
            Long.class,
            equipmentId,
            warehouseId,
            rentalItemId,
            locationKind.name());
    return value == null ? 0 : value;
  }

  private long balanceQuantity(UUID balanceId) {
    return jdbc.queryForObject(
        "select quantity from equipment_balance where id=?", Long.class, balanceId);
  }

  private long balanceVersion(UUID balanceId) {
    return jdbc.queryForObject(
        "select version from equipment_balance where id=?", Long.class, balanceId);
  }

  private long cabinBalanceEventCount(UUID rentalItemId) {
    return jdbc.queryForObject(
        """
        select count(*)
        from domain_event event
        join equipment_balance balance on balance.id::text=event.aggregate_id
        where event.aggregate_type='EQUIPMENT_BALANCE' and balance.rental_item_id=?
        """,
        Long.class,
        rentalItemId);
  }

  private void setStatus(
      UUID assetId, RentalItemStatus status, RentalItemStatus transferOriginStatus) {
    jdbc.update(
        "update rental_item set status=?,transfer_origin_status=?,updated_at=clock_timestamp() where id=?",
        status.name(),
        transferOriginStatus == null ? null : transferOriginStatus.name(),
        assetId);
  }

  private RentalItemStatus currentStatus(UUID assetId) {
    return RentalItemStatus.valueOf(
        jdbc.queryForObject("select status from rental_item where id=?", String.class, assetId));
  }

  private UUID currentWarehouse(UUID assetId) {
    return jdbc.queryForObject(
        "select warehouse_id from rental_item where id=?", UUID.class, assetId);
  }

  private String state(String table, UUID id) {
    if (!List.of("operation_lease", "order_unit_reservation", "presentation_unit_hold")
        .contains(table)) {
      throw new IllegalArgumentException("Unsupported test table");
    }
    return jdbc.queryForObject("select state from " + table + " where id=?", String.class, id);
  }

  private int receiptCount(UUID key) {
    return jdbc.queryForObject(
        "select count(*) from inventory_asset_outcome_receipt where idempotency_key=?",
        Integer.class,
        key);
  }

  private InventoryOutcomeRequest request(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      InventoryOutcomeStatus desiredStatus) {
    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    return request(warehouseId, assetId, completedAt, desiredStatus, absent);
  }

  private InventoryOutcomeRequest preserveRequest(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      JsonNode passportObservation) {
    return new InventoryOutcomeRequest(
        warehouseId,
        assetId,
        completedAt,
        2L,
        "a".repeat(64),
        3L,
        null,
        passportObservation,
        passportObservationHash(passportObservation),
        null,
        true);
  }

  private InventoryOutcomeRequest guardedRequest(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      InventoryOutcomeStatus desiredStatus,
      long expectedAssetVersion) {
    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    return new InventoryOutcomeRequest(
        warehouseId,
        assetId,
        completedAt,
        2L,
        "a".repeat(64),
        3L,
        desiredStatus,
        absent,
        passportObservationHash(absent),
        desiredStatus == InventoryOutcomeStatus.RENTED ? List.of() : null,
        expectedAssetVersion,
        false);
  }

  private InventoryOutcomeRequest request(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      InventoryOutcomeStatus desiredStatus,
      JsonNode passportObservation) {
    return requestWithPlan(
        warehouseId, assetId, completedAt, 2L, "a".repeat(64), desiredStatus, passportObservation);
  }

  private InventoryOutcomeRequest requestWithPlan(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      InventoryOutcomeStatus desiredStatus) {
    ObjectNode absent = objectMapper.createObjectNode();
    absent.put("presence", "ABSENT");
    absent.putNull("value");
    return requestWithPlan(
        warehouseId,
        assetId,
        completedAt,
        finalPlanVersion,
        finalPlanSha256,
        desiredStatus,
        absent);
  }

  private InventoryOutcomeRequest requestWithPlan(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      long finalPlanVersion,
      String finalPlanSha256,
      InventoryOutcomeStatus desiredStatus,
      JsonNode passportObservation) {
    return new InventoryOutcomeRequest(
        warehouseId,
        assetId,
        completedAt,
        finalPlanVersion,
        finalPlanSha256,
        3L,
        desiredStatus,
        passportObservation,
        passportObservationHash(passportObservation),
        desiredStatus == InventoryOutcomeStatus.RENTED ? List.of() : null);
  }

  private String passportObservationHash(JsonNode observation) {
    try {
      Object canonical = objectMapper.treeToValue(observation, Object.class);
      String value =
          objectMapper
              .writer()
              .with(SerializationFeature.ORDER_MAP_ENTRIES_BY_KEYS)
              .writeValueAsString(canonical);
      return AssetChecksum.sha256(value.getBytes(StandardCharsets.UTF_8));
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalArgumentException("Test passport observation cannot be hashed", exception);
    }
  }

  private Map<String, Object> composition(UUID assetId) {
    return jdbc.queryForMap(
        """
        select cabin_type_id,cabin_dimension_id,cabin_finishing_id,cabin_category_id,
               category,linoleum
        from rental_item
        where id=?
        """,
        assetId);
  }

  private List<UUID> characteristics(UUID assetId) {
    return jdbc.query(
        """
        select characteristic_id
        from rental_item_characteristic
        where rental_item_id=?
        order by sort_order,id
        """,
        (result, row) -> result.getObject(1, UUID.class),
        assetId);
  }

  private long currentVersion(UUID assetId) {
    return jdbc.queryForObject(
        "select version from rental_item where id=?", Long.class, assetId);
  }

  private int watermarkCount(UUID assetId) {
    return jdbc.queryForObject(
        "select count(*) from inventory_asset_outcome_watermark where asset_id=?",
        Integer.class,
        assetId);
  }

  private String watermarkPassportHash(UUID assetId) {
    return jdbc.queryForObject(
        "select passport_observation_sha256 from inventory_asset_outcome_watermark where asset_id=?",
        String.class,
        assetId);
  }

  private long watermarkPlanVersion(UUID assetId) {
    return jdbc.queryForObject(
        "select final_plan_version from inventory_asset_outcome_watermark where asset_id=?",
        Long.class,
        assetId);
  }

  private String watermarkPlanHash(UUID assetId) {
    return jdbc.queryForObject(
        "select final_plan_sha256 from inventory_asset_outcome_watermark where asset_id=?",
        String.class,
        assetId);
  }

  private int passportEventCount(UUID assetId) {
    return jdbc.queryForObject(
        """
        select count(*)
        from domain_event
        where aggregate_type='RENTAL_ITEM'
          and aggregate_id=?
          and event_type='asset.rental-item.passport-changed.v1'
        """,
        Integer.class,
        assetId.toString());
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  private static JwtRequestPostProcessor inventoryJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("inventory-service")
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", "inventory-service")
                    .claim("scope", "asset.inventory"));
  }
}
