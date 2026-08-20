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
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeStatus;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.InventoryAssetService;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
    assertThat(recovered.response().releasedOperationLeaseIds()).isEmpty();
    assertThat(state("operation_lease", currentInventoryRepairLease.id())).isEqualTo("ACTIVE");

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

  private InventoryOutcomeRequest request(
      UUID warehouseId,
      UUID assetId,
      OffsetDateTime completedAt,
      InventoryOutcomeStatus desiredStatus,
      JsonNode passportObservation) {
    return new InventoryOutcomeRequest(
        warehouseId,
        assetId,
        completedAt,
        2L,
        "a".repeat(64),
        3L,
        desiredStatus,
        passportObservation,
        passportObservationHash(passportObservation));
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
