package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_NEW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.plasticWindow;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireEquipmentHoldRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.AcquireOperationLeaseRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureReconciliationCabin;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureReconciliationItem;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureReconciliationRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureSnapshot;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureSnapshotCabin;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureSnapshotItem;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryFurnitureSnapshotRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.InventoryOutcomeStatus;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OrderEquipmentReservation;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.domain.PresentationUnitHold;
import dev.buhanzaz.rwms.asset.domain.RentalItemStatus;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.OrderEquipmentReservationRepository;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
import dev.buhanzaz.rwms.asset.repository.PresentationUnitHoldRepository;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.InventoryAssetService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

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
class InventoryFurnitureReconciliationIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired InventoryAssetService inventory;
  @Autowired AssetEventStore events;
  @Autowired OrderUnitReservationRepository orderReservations;
  @Autowired OrderEquipmentReservationRepository orderEquipmentReservations;
  @Autowired PresentationUnitHoldRepository presentationHolds;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
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
  void snapshotsCanonicallyAndReconcilesExactlyOnceWithoutTouchingAnotherWarehouse()
      throws Exception {
    FurnitureFixture fixture = furnitureFixture("RECONCILE");
    OtherWarehouseFixture other = otherWarehouseFixture(fixture.equipment());
    InventoryFurnitureSnapshot forward = snapshot(
        fixture.warehouseId(), List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));
    InventoryFurnitureSnapshot reverse = snapshot(
        fixture.warehouseId(), List.of(fixture.secondCabin().id(), fixture.firstCabin().id()));
    InventoryFurnitureSnapshot otherBefore = snapshot(other.warehouseId(), List.of(other.cabin().id()));

    assertThat(reverse).isEqualTo(forward);
    assertThat(forward.items())
        .isSortedAccordingTo(
            Comparator.comparing(InventoryFurnitureSnapshotItem::equipmentName)
                .thenComparing(InventoryFurnitureSnapshotItem::equipmentId));
    assertThat(item(forward, fixture.equipment().id()).cabins())
        .extracting(cabin -> cabin.displayCanonicalNumber())
        .containsExactly(
            fixture.firstCabin().number(), fixture.secondCabin().number());
    assertThat(item(forward, fixture.equipment().id()).stockBalanceVersion()).isEqualTo(
        version(fixture.equipment().id(), fixture.warehouseId(), null, BalanceLocationKind.STOCK));

    mvc.perform(
            post("/api/internal/asset/v1/inventory/furniture-snapshots")
                .with(inventoryJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(new InventoryFurnitureSnapshotRequest(
                    fixture.warehouseId(),
                    List.of(fixture.secondCabin().id(), fixture.firstCabin().id())))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value(fixture.warehouseId().toString()))
        .andExpect(jsonPath("$.snapshotSha256").value(forward.snapshotSha256()))
        .andExpect(jsonPath("$.items[0].cabins[0].assetId").value(fixture.firstCabin().id().toString()));

    InventoryFurnitureReconciliationRequest request = reconciliationRequest(
        forward,
        fixture.equipment().id(),
        6L,
        Map.of(fixture.firstCabin().id(), 3L, fixture.secondCabin().id(), 2L),
        "a".repeat(64));
    UUID inventoryId = UUID.randomUUID();
    UUID idempotencyKey = UUID.randomUUID();
    long eventsBefore = balanceEventCount(fixture.equipment().id(), fixture.warehouseId());

    mvc.perform(
            put("/api/internal/asset/v1/inventory/furniture-reconciliations/{inventoryId}", inventoryId)
                .with(inventoryJwt())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent());

    InventoryFurnitureSnapshot reconciled = snapshot(
        fixture.warehouseId(), List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));
    InventoryFurnitureSnapshotItem reconciledItem = item(reconciled, fixture.equipment().id());
    assertThat(reconciledItem.currentStockQuantity()).isEqualTo(6L);
    assertThat(cabinQuantity(reconciledItem, fixture.firstCabin().id())).isEqualTo(3L);
    assertThat(cabinQuantity(reconciledItem, fixture.secondCabin().id())).isEqualTo(2L);
    assertThat(quantity(
        fixture.equipment().id(),
        fixture.warehouseId(),
        fixture.firstCabin().id(),
        BalanceLocationKind.CABIN_NON_RENTED)).isEqualTo(3L);
    assertThat(quantity(
        fixture.equipment().id(),
        fixture.warehouseId(),
        fixture.firstCabin().id(),
        BalanceLocationKind.CABIN_RENTED)).isZero();
    assertThat(quantity(
        fixture.equipment().id(),
        fixture.warehouseId(),
        fixture.secondCabin().id(),
        BalanceLocationKind.CABIN_NON_RENTED)).isEqualTo(2L);
    assertThat(balanceEventCount(fixture.equipment().id(), fixture.warehouseId()))
        .isEqualTo(eventsBefore + 4L);
    assertThat(snapshot(other.warehouseId(), List.of(other.cabin().id()))).isEqualTo(otherBefore);

    mvc.perform(
            put("/api/internal/asset/v1/inventory/furniture-reconciliations/{inventoryId}", inventoryId)
                .with(inventoryJwt())
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isNoContent())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    assertThat(balanceEventCount(fixture.equipment().id(), fixture.warehouseId()))
        .isEqualTo(eventsBefore + 4L);

    InventoryFurnitureReconciliationRequest changedPayload =
        new InventoryFurnitureReconciliationRequest(
            request.warehouseId(),
            request.expectedSnapshotSha256(),
            "b".repeat(64),
            request.items());
    assertThatThrownBy(
            () -> inventory.reconcileFurniture(inventoryId, UUID.randomUUID(), changedPayload))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("bound to another request");
  }

  @Test
  void supportsAStockOnlyFurnitureReviewWhenNoCabinsAreSelected() {
    FurnitureFixture fixture = furnitureFixture("STOCK-ONLY");

    InventoryFurnitureSnapshot stockOnly = snapshot(fixture.warehouseId(), List.of());
    assertThat(stockOnly.items()).isNotEmpty();
    assertThat(stockOnly.items()).allSatisfy(item -> assertThat(item.cabins()).isEmpty());
    assertThat(item(stockOnly, fixture.equipment().id()).currentStockQuantity()).isEqualTo(10L);

    InventoryFurnitureReconciliationRequest request = reconciliationRequest(
        stockOnly, fixture.equipment().id(), 7L, Map.of(), "2".repeat(64));
    assertThat(
            inventory.reconcileFurniture(UUID.randomUUID(), UUID.randomUUID(), request).replayed())
        .isFalse();
    assertThat(quantity(
        fixture.equipment().id(),
        fixture.warehouseId(),
        null,
        BalanceLocationKind.STOCK)).isEqualTo(7L);
    assertThat(quantity(
        fixture.equipment().id(),
        fixture.warehouseId(),
        fixture.firstCabin().id(),
        BalanceLocationKind.CABIN_NON_RENTED)).isEqualTo(2L);
    assertThat(quantity(
        fixture.equipment().id(),
        fixture.warehouseId(),
        fixture.firstCabin().id(),
        BalanceLocationKind.CABIN_RENTED)).isEqualTo(4L);
  }

  @Test
  void exposesNullStockBalanceVersionOnlyWhenThePhysicalStockBalanceIsAbsent()
      throws Exception {
    UUID warehouseId = UUID.randomUUID();
    EquipmentResponse equipment = assets.createEquipment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Uninitialized inventory furniture " + UUID.randomUUID(),
            EquipmentCategory.FURNITURE,
            null)).response();

    InventoryFurnitureSnapshot absent = snapshot(warehouseId, List.of());
    InventoryFurnitureSnapshotItem absentItem = item(absent, equipment.id());
    assertThat(absentItem.currentStockQuantity()).isZero();
    assertThat(absentItem.stockBalanceVersion()).isNull();
    JsonNode absentJson = objectMapper.readTree(objectMapper.writeValueAsString(absent));
    JsonNode absentJsonItem = null;
    for (JsonNode candidate : absentJson.path("items")) {
      if (equipment.id().toString().equals(candidate.path("equipmentId").asText())) {
        absentJsonItem = candidate;
        break;
      }
    }
    assertThat(absentJsonItem).isNotNull();
    assertThat(absentJsonItem.path("stockBalanceVersion").isNull()).isTrue();

    seedBalance(equipment.id(), warehouseId, null, BalanceLocationKind.STOCK, 0L);

    InventoryFurnitureSnapshot initialized = snapshot(warehouseId, List.of());
    InventoryFurnitureSnapshotItem initializedItem = item(initialized, equipment.id());
    assertThat(initializedItem.currentStockQuantity()).isZero();
    assertThat(initializedItem.stockBalanceVersion()).isZero();
    assertThat(initialized.snapshotSha256()).isNotEqualTo(absent.snapshotSha256());
  }

  @Test
  void appliesAuthoritativeCountsAfterNonTerminalCabinAndBalanceDrift() {
    FurnitureFixture fixture = furnitureFixture("AUTHORITATIVE-DRIFT");
    InventoryFurnitureSnapshot captured = snapshot(
        fixture.warehouseId(), List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));
    InventoryFurnitureReconciliationRequest request = reconciliationRequest(
        captured,
        fixture.equipment().id(),
        4L,
        Map.of(fixture.firstCabin().id(), 3L, fixture.secondCabin().id(), 1L),
        "c".repeat(64));

    assets.transfer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            fixture.equipment().id(),
            fixture.warehouseId(),
            null,
            BalanceLocationKind.STOCK,
            version(
                fixture.equipment().id(),
                fixture.warehouseId(),
                null,
                BalanceLocationKind.STOCK),
            fixture.warehouseId(),
            fixture.firstCabin().id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            version(
                fixture.equipment().id(),
                fixture.warehouseId(),
                fixture.firstCabin().id(),
                BalanceLocationKind.CABIN_NON_RENTED),
            1L));
    var repair =
        applyInventoryOutcome(
            fixture.warehouseId(),
            fixture.firstCabin().id(),
            InventoryOutcomeStatus.REPAIR,
            "d".repeat(64));
    var capitalRepair =
        applyInventoryOutcome(
            fixture.warehouseId(),
            fixture.secondCabin().id(),
            InventoryOutcomeStatus.CAPITAL_REPAIR,
            "e".repeat(64));
    assertThat(repair.assetVersion())
        .isGreaterThan(
            cabin(captured, fixture.equipment().id(), repair.assetId()).assetVersion());
    assertThat(capitalRepair.assetVersion())
        .isGreaterThan(
            cabin(captured, fixture.equipment().id(), capitalRepair.assetId()).assetVersion());
    assertThat(
            snapshot(
                    fixture.warehouseId(),
                    List.of(fixture.firstCabin().id(), fixture.secondCabin().id()))
                .snapshotSha256())
        .isNotEqualTo(captured.snapshotSha256());
    OrderUnitReservation guard =
        orderReservations.saveAndFlush(
            OrderUnitReservation.create(
                UUID.randomUUID(),
                fixture.secondCabin().id(),
                fixture.warehouseId(),
                UUID.randomUUID(),
                "WMS_ADMIN"));

    assertThat(
            inventory.reconcileFurniture(UUID.randomUUID(), UUID.randomUUID(), request).replayed())
        .isFalse();
    InventoryFurnitureSnapshot reconciled =
        snapshot(
            fixture.warehouseId(),
            List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));
    InventoryFurnitureSnapshotItem reconciledItem = item(reconciled, fixture.equipment().id());
    assertThat(reconciledItem.currentStockQuantity()).isEqualTo(4L);
    assertThat(cabinQuantity(reconciledItem, fixture.firstCabin().id())).isEqualTo(3L);
    assertThat(cabinQuantity(reconciledItem, fixture.secondCabin().id())).isEqualTo(1L);
    assertThat(assets.rentalItem(fixture.firstCabin().id()).status())
        .isEqualTo(RentalItemStatus.REPAIR);
    assertThat(assets.rentalItem(fixture.secondCabin().id()).status())
        .isEqualTo(RentalItemStatus.CAPITAL_REPAIR);
    assertThat(orderReservations.findById(guard.getId()).orElseThrow().isActive()).isFalse();
  }

  @Test
  void rejectsTerminalCabinsBeforeSupersedingGuardsOrCounts() {
    for (RentalItemStatus terminalStatus :
        List.of(RentalItemStatus.LOST, RentalItemStatus.WRITTEN_OFF)) {
      FurnitureFixture fixture =
          furnitureFixture(
              terminalStatus == RentalItemStatus.LOST ? "TERMINAL-L" : "TERMINAL-W");
      InventoryFurnitureSnapshot captured =
          snapshot(
              fixture.warehouseId(),
              List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));
      InventoryFurnitureReconciliationRequest request =
          reconciliationRequest(
              captured,
              fixture.equipment().id(),
              1L,
              Map.of(fixture.firstCabin().id(), 1L, fixture.secondCabin().id(), 1L),
              terminalStatus == RentalItemStatus.LOST ? "9".repeat(64) : "0".repeat(64));
      // Terminal transitions are disposition-owned; this narrow fixture creates their persisted
      // post-snapshot state without invoking a second workflow under test.
      assertThat(
              jdbc.update(
                  """
                  update rental_item
                  set status=?,version=version+1,transfer_origin_status=null,
                      updated_at=clock_timestamp()
                  where id=?
                  """,
                  terminalStatus.name(),
                  fixture.firstCabin().id()))
          .isOne();
      OrderUnitReservation guard =
          orderReservations.saveAndFlush(
              OrderUnitReservation.create(
                  UUID.randomUUID(),
                  fixture.secondCabin().id(),
                  fixture.warehouseId(),
                  UUID.randomUUID(),
                  "WMS_ADMIN"));
      InventoryFurnitureSnapshot terminalState =
          snapshot(
              fixture.warehouseId(),
              List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));

      assertThatThrownBy(
              () -> inventory.reconcileFurniture(UUID.randomUUID(), UUID.randomUUID(), request))
          .isInstanceOf(AssetConflictException.class)
          .hasMessageContaining("Lost or written-off cabin");
      assertThat(
              snapshot(
                  fixture.warehouseId(),
                  List.of(fixture.firstCabin().id(), fixture.secondCabin().id())))
          .isEqualTo(terminalState);
      assertThat(orderReservations.findById(guard.getId()).orElseThrow().isActive()).isTrue();
      assertThat(assets.rentalItem(fixture.firstCabin().id()).status()).isEqualTo(terminalStatus);
    }
  }

  @Test
  void supersedesLiveOperationLeasesOrderReservationsAndEquipmentHolds() {
    FurnitureFixture leased = furnitureFixture("LEASE");
    InventoryFurnitureSnapshot leasedSnapshot = snapshot(
        leased.warehouseId(), List.of(leased.firstCabin().id(), leased.secondCabin().id()));
    assets.acquireLease(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new AcquireOperationLeaseRequest(
            leased.firstCabin().id(), "INVENTORY_TEST", "lease", leased.firstCabin().version()));
    inventory.reconcileFurniture(
        UUID.randomUUID(),
        UUID.randomUUID(),
        reconciliationRequest(
            leasedSnapshot,
            leased.equipment().id(),
            item(leasedSnapshot, leased.equipment().id()).currentStockQuantity(),
            Map.of(),
            "d".repeat(64)));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from operation_lease where rental_item_id=? and state='ACTIVE'",
                Integer.class,
                leased.firstCabin().id()))
        .isZero();

    FurnitureFixture reserved = furnitureFixture("RESERVATION");
    InventoryFurnitureSnapshot reservedSnapshot = snapshot(
        reserved.warehouseId(), List.of(reserved.firstCabin().id(), reserved.secondCabin().id()));
    orderReservations.saveAndFlush(OrderUnitReservation.create(
        UUID.randomUUID(),
        reserved.secondCabin().id(),
        reserved.warehouseId(),
        UUID.randomUUID(),
        "WMS_ADMIN"));
    inventory.reconcileFurniture(
        UUID.randomUUID(),
        UUID.randomUUID(),
        reconciliationRequest(
            reservedSnapshot,
            reserved.equipment().id(),
            item(reservedSnapshot, reserved.equipment().id()).currentStockQuantity(),
            Map.of(),
            "e".repeat(64)));
    InventoryFurnitureSnapshot reservedAfter =
        snapshot(
            reserved.warehouseId(),
            List.of(reserved.firstCabin().id(), reserved.secondCabin().id()));
    assertThat(item(reservedAfter, reserved.equipment().id()).currentStockQuantity())
        .isEqualTo(item(reservedSnapshot, reserved.equipment().id()).currentStockQuantity());
    assertThat(item(reservedAfter, reserved.equipment().id()).cabins())
        .extracting(InventoryFurnitureReconciliationIntegrationTest::quantityView)
        .containsExactlyElementsOf(
            item(reservedSnapshot, reserved.equipment().id()).cabins().stream()
                .map(InventoryFurnitureReconciliationIntegrationTest::quantityView)
                .toList());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_unit_reservation where rental_item_id=? and state='ACTIVE'",
                Integer.class,
                reserved.secondCabin().id()))
        .isZero();

    FurnitureFixture held = furnitureFixture("HOLD");
    InventoryFurnitureSnapshot heldSnapshot = snapshot(
        held.warehouseId(), List.of(held.firstCabin().id(), held.secondCabin().id()));
    assets.acquireHold(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new AcquireEquipmentHoldRequest(
            held.equipment().id(),
            held.warehouseId(),
            "INVENTORY_TEST",
            "hold",
            1L,
            version(held.equipment().id(), held.warehouseId(), null, BalanceLocationKind.STOCK)));
    // A hold does not alter the snapshot hash; the reviewed inventory supersedes it after the
    // stable scope/catalog checks and keeps its released row as audit evidence.
    inventory.reconcileFurniture(
        UUID.randomUUID(),
        UUID.randomUUID(),
        reconciliationRequest(
            heldSnapshot,
            held.equipment().id(),
            item(heldSnapshot, held.equipment().id()).currentStockQuantity(),
            Map.of(),
            "f".repeat(64)));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from equipment_allocation_hold where warehouse_id=? and state in ('ACTIVE','COMMITTED')",
                Integer.class,
                held.warehouseId()))
        .isZero();

    FurnitureFixture presented = furnitureFixture("PRESENTATION");
    InventoryFurnitureSnapshot presentedSnapshot =
        snapshot(
            presented.warehouseId(),
            List.of(presented.firstCabin().id(), presented.secondCabin().id()));
    OffsetDateTime createdAt = OffsetDateTime.now(ZoneOffset.UTC);
    presentationHolds.saveAndFlush(
        PresentationUnitHold.create(
            UUID.randomUUID(),
            presented.firstCabin().id(),
            presented.warehouseId(),
            createdAt.plusMinutes(30),
            UUID.randomUUID(),
            "WMS_ADMIN",
            createdAt));
    inventory.reconcileFurniture(
        UUID.randomUUID(),
        UUID.randomUUID(),
        reconciliationRequest(
            presentedSnapshot,
            presented.equipment().id(),
            item(presentedSnapshot, presented.equipment().id()).currentStockQuantity(),
            Map.of(),
            "7".repeat(64)));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from presentation_unit_hold where rental_item_id=? and state='ACTIVE'",
                Integer.class,
                presented.firstCabin().id()))
        .isZero();

    FurnitureFixture equipmentReserved = furnitureFixture("EQUIPMENT-RESERVATION");
    InventoryFurnitureSnapshot equipmentReservedSnapshot =
        snapshot(
            equipmentReserved.warehouseId(),
            List.of(
                equipmentReserved.firstCabin().id(), equipmentReserved.secondCabin().id()));
    orderEquipmentReservations.saveAndFlush(
        OrderEquipmentReservation.create(
            UUID.randomUUID(),
            equipmentReserved.equipment().id(),
            equipmentReserved.warehouseId(),
            1L));
    inventory.reconcileFurniture(
        UUID.randomUUID(),
        UUID.randomUUID(),
        reconciliationRequest(
            equipmentReservedSnapshot,
            equipmentReserved.equipment().id(),
            item(equipmentReservedSnapshot, equipmentReserved.equipment().id())
                .currentStockQuantity(),
            Map.of(),
            "8".repeat(64)));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from order_equipment_reservation where warehouse_id=? and state='ACTIVE'",
                Integer.class,
                equipmentReserved.warehouseId()))
        .isZero();
  }

  @Test
  void rejectsNegativeAndDuplicateAbsoluteReconciliationRows() {
    FurnitureFixture fixture = furnitureFixture("VALIDATION");
    InventoryFurnitureSnapshot current = snapshot(
        fixture.warehouseId(), List.of(fixture.firstCabin().id(), fixture.secondCabin().id()));
    InventoryFurnitureReconciliationRequest valid = reconciliationRequest(
        current,
        fixture.equipment().id(),
        item(current, fixture.equipment().id()).currentStockQuantity(),
        Map.of(),
        "1".repeat(64));
    InventoryFurnitureReconciliationItem first = valid.items().getFirst();

    List<InventoryFurnitureReconciliationItem> negativeItems = new ArrayList<>(valid.items());
    negativeItems.set(
        0,
        new InventoryFurnitureReconciliationItem(
            first.equipmentId(), first.catalogVersion(), -1L, first.cabins()));
    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        valid.warehouseId(),
                        valid.expectedSnapshotSha256(),
                        valid.reviewSha256(),
                        negativeItems)))
        .isInstanceOf(IllegalArgumentException.class);

    List<InventoryFurnitureReconciliationItem> duplicateEquipment = new ArrayList<>(valid.items());
    duplicateEquipment.add(first);
    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        valid.warehouseId(),
                        valid.expectedSnapshotSha256(),
                        valid.reviewSha256(),
                        duplicateEquipment)))
        .isInstanceOf(IllegalArgumentException.class);

    List<InventoryFurnitureReconciliationCabin> duplicateCabins = new ArrayList<>(first.cabins());
    duplicateCabins.add(first.cabins().getFirst());
    List<InventoryFurnitureReconciliationItem> duplicateCabinItems = new ArrayList<>(valid.items());
    duplicateCabinItems.set(
        0,
        new InventoryFurnitureReconciliationItem(
            first.equipmentId(),
            first.catalogVersion(),
            first.stockQuantity(),
            duplicateCabins));
    assertThatThrownBy(
            () ->
                inventory.reconcileFurniture(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    new InventoryFurnitureReconciliationRequest(
                        valid.warehouseId(),
                        valid.expectedSnapshotSha256(),
                        valid.reviewSha256(),
                        duplicateCabinItems)))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private FurnitureFixture furnitureFixture(String prefix) {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse firstCabin = rentalItem(warehouseId, prefix + "-A-");
    RentalItemResponse secondCabin = rentalItem(warehouseId, prefix + "-B-");
    EquipmentResponse equipment = assets.createEquipment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEquipmentRequest(
            "Inventory furniture " + prefix + " " + UUID.randomUUID(),
            EquipmentCategory.FURNITURE,
            null)).response();
    seedBalance(equipment.id(), warehouseId, null, BalanceLocationKind.STOCK, 12L);
    assets.transfer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipment.id(),
            warehouseId,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouseId,
            firstCabin.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            2L));
    // A legacy opposite bucket is deliberately present: reconciliation must zero it, not leave it
    // contributing to the logical cabin quantity.
    seedBalance(
        equipment.id(),
        warehouseId,
        firstCabin.id(),
        BalanceLocationKind.CABIN_RENTED,
        4L);
    return new FurnitureFixture(warehouseId, equipment, firstCabin, secondCabin);
  }

  private OtherWarehouseFixture otherWarehouseFixture(EquipmentResponse equipment) {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = rentalItem(warehouseId, "OTHER-");
    seedBalance(equipment.id(), warehouseId, null, BalanceLocationKind.STOCK, 11L);
    seedBalance(
        equipment.id(), warehouseId, cabin.id(), BalanceLocationKind.CABIN_NON_RENTED, 5L);
    return new OtherWarehouseFixture(warehouseId, cabin);
  }

  private RentalItemResponse rentalItem(UUID warehouseId, String numberPrefix) {
    return assets.createRentalItem(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateRentalItemRequest(
            warehouseId,
            numberPrefix + UUID.randomUUID(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            CATEGORY_NEW,
            plasticWindow(),
            true,
            Map.of(),
            List.of())).response();
  }

  private InventoryFurnitureSnapshot snapshot(UUID warehouseId, List<UUID> assetIds) {
    return inventory.furnitureSnapshot(new InventoryFurnitureSnapshotRequest(warehouseId, assetIds));
  }

  private InventoryFurnitureReconciliationRequest reconciliationRequest(
      InventoryFurnitureSnapshot snapshot,
      UUID changedEquipmentId,
      long changedStockQuantity,
      Map<UUID, Long> changedCabinQuantities,
      String reviewSha256) {
    return new InventoryFurnitureReconciliationRequest(
        snapshot.warehouseId(),
        snapshot.snapshotSha256(),
        reviewSha256,
        snapshot.items().stream()
            .map(
                item ->
                    new InventoryFurnitureReconciliationItem(
                        item.equipmentId(),
                        item.catalogVersion(),
                        item.equipmentId().equals(changedEquipmentId)
                            ? changedStockQuantity
                            : item.currentStockQuantity(),
                        item.cabins().stream()
                            .map(
                                cabin ->
                                    new InventoryFurnitureReconciliationCabin(
                                        cabin.assetId(),
                                        item.equipmentId().equals(changedEquipmentId)
                                            ? changedCabinQuantities.getOrDefault(
                                                cabin.assetId(), cabin.currentQuantity())
                                            : cabin.currentQuantity()))
                            .toList()))
            .toList());
  }

  private static InventoryFurnitureSnapshotItem item(
      InventoryFurnitureSnapshot snapshot, UUID equipmentId) {
    return snapshot.items().stream()
        .filter(item -> item.equipmentId().equals(equipmentId))
        .findFirst()
        .orElseThrow();
  }

  private static long cabinQuantity(InventoryFurnitureSnapshotItem item, UUID assetId) {
    return item.cabins().stream()
        .filter(cabin -> cabin.assetId().equals(assetId))
        .findFirst()
        .orElseThrow()
        .currentQuantity();
  }

  private static InventoryFurnitureSnapshotCabin cabin(
      InventoryFurnitureSnapshot snapshot, UUID equipmentId, UUID assetId) {
    return item(snapshot, equipmentId).cabins().stream()
        .filter(cabin -> cabin.assetId().equals(assetId))
        .findFirst()
        .orElseThrow();
  }

  private InventoryOutcomeResponse applyInventoryOutcome(
      UUID warehouseId,
      UUID assetId,
      InventoryOutcomeStatus status,
      String finalPlanSha256) {
    return inventory
        .applyOutcome(
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            UUID.randomUUID(),
            new InventoryOutcomeRequest(
                warehouseId,
                assetId,
                OffsetDateTime.now(ZoneOffset.UTC),
                1L,
                finalPlanSha256,
                1L,
                status,
                objectMapper
                    .createObjectNode()
                    .put("presence", "ABSENT")
                    .putNull("value"),
                "61a7ce0bfd5097c09f79252071264127229f73b876984eae80271c474e7126a7",
                null))
        .response();
  }

  private static String quantityView(InventoryFurnitureSnapshotCabin cabin) {
    return cabin.assetId() + ":" + cabin.currentQuantity();
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

  private long quantity(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind) {
    Long value = jdbc.queryForObject(
        """
        select quantity
        from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ?
          and location_kind=?
        """,
        Long.class,
        equipmentId,
        warehouseId,
        rentalItemId,
        locationKind.name());
    return value == null ? 0L : value;
  }

  private long version(
      UUID equipmentId,
      UUID warehouseId,
      UUID rentalItemId,
      BalanceLocationKind locationKind) {
    Long value = jdbc.queryForObject(
        """
        select version
        from equipment_balance
        where equipment_id=? and warehouse_id=? and rental_item_id is not distinct from ?
          and location_kind=?
        """,
        Long.class,
        equipmentId,
        warehouseId,
        rentalItemId,
        locationKind.name());
    return value == null ? 0L : value;
  }

  private long balanceEventCount(UUID equipmentId, UUID warehouseId) {
    Long value = jdbc.queryForObject(
        """
        select count(*)
        from domain_event
        where aggregate_type='EQUIPMENT_BALANCE'
          and payload->>'equipmentId'=?
          and payload->>'warehouseId'=?
        """,
        Long.class,
        equipmentId.toString(),
        warehouseId.toString());
    return value == null ? 0L : value;
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

  private record FurnitureFixture(
      UUID warehouseId,
      EquipmentResponse equipment,
      RentalItemResponse firstCabin,
      RentalItemResponse secondCabin) {}

  private record OtherWarehouseFixture(UUID warehouseId, RentalItemResponse cabin) {}
}
