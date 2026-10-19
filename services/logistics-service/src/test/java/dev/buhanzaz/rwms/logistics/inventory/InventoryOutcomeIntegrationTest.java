package dev.buhanzaz.rwms.logistics.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentLine;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuard;
import dev.buhanzaz.rwms.logistics.domain.LogisticsGuardState;
import dev.buhanzaz.rwms.logistics.domain.LogisticsLineState;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverLogisticsTask;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskKind;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskPlanningMode;
import dev.buhanzaz.rwms.logistics.driver.domain.DriverTaskSourceType;
import dev.buhanzaz.rwms.logistics.driver.repository.DriverLogisticsTaskRepository;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.LogisticsOwnerType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.OperationLease;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeRequest;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.ApplyInventoryOutcomeResponse;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryAssetOutcome;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryDispositionKind;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryDesiredStatus;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryFormerRental;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryShipment;
import dev.buhanzaz.rwms.logistics.inventory.api.InventoryOutcomeApiModels.InventoryShipmentFurniture;
import dev.buhanzaz.rwms.logistics.inventory.service.InventoryOutcomeService;
import dev.buhanzaz.rwms.logistics.order.domain.ClientType;
import dev.buhanzaz.rwms.logistics.order.domain.DesiredDeliveryWindow;
import dev.buhanzaz.rwms.logistics.order.domain.OrderClient;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrder;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderPaymentSource;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderStatus;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderUnitTerm;
import dev.buhanzaz.rwms.logistics.order.domain.RentalOrderQuotedPrice;
import dev.buhanzaz.rwms.logistics.order.repository.OrderClientRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderRepository;
import dev.buhanzaz.rwms.logistics.order.repository.RentalOrderUnitTermRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentLineRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
import dev.buhanzaz.rwms.logistics.repository.LogisticsGuardRepository;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import jakarta.persistence.EntityManagerFactory;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.hibernate.SessionFactory;
import org.mockito.ArgumentCaptor;
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
 * Database-backed proof that a completed inventory atomically supersedes logistics projections and
 * resumes source-owned task cancellation without deleting historical rows.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.jpa.properties.hibernate.generate_statistics=true",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "rwms.logistics.equipment-movement.relay-enabled=false",
      "rwms.logistics.driver-queue.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class InventoryOutcomeIntegrationTest {
  private static final UUID WAREHOUSE =
      UUID.fromString("10000000-0000-0000-0000-000000000001");
  private static final UUID OTHER_WAREHOUSE =
      UUID.fromString("10000000-0000-0000-0000-000000000002");
  private static final UUID SUBJECT =
      UUID.fromString("10000000-0000-0000-0000-000000000003");
  private static final UUID INVENTORY =
      UUID.fromString("10000000-0000-0000-0000-000000000004");
  private static final OffsetDateTime COMPLETED_AT =
      OffsetDateTime.of(2026, 8, 19, 10, 30, 0, 123_456_000, ZoneOffset.UTC);
  private static final String PLAN_SHA256 = "a".repeat(64);
  private static final Comparator<UUID> UUID_ORDER = Comparator.comparing(UUID::toString);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired InventoryOutcomeService outcomes;
  @Autowired JdbcTemplate jdbc;
  @Autowired LogisticsDocumentRepository documents;
  @Autowired LogisticsDocumentLineRepository documentLines;
  @Autowired LogisticsGuardRepository guards;
  @Autowired DriverLogisticsTaskRepository driverTasks;
  @Autowired OrderClientRepository clients;
  @Autowired RentalOrderRepository rentalOrders;
  @Autowired RentalOrderUnitTermRepository rentalTerms;
  @Autowired LogisticsDocumentService documentService;
  @Autowired EntityManagerFactory entityManagerFactory;

  @MockitoBean LogisticsDependencyGateway dependencies;

  @BeforeEach
  void clearDatabase() {
    jdbc.execute(
        """
        truncate table
          inventory_outcome_receipt,
          inventory_asset_outcome_watermark,
          driver_logistics_task,
          logistics_document,
          rental_order,
          order_client,
          warehouse_operation_mark_outbox
        cascade
        """);
    reset(dependencies);
  }

  @Test
  void supersedesTheLiveShaped216AssetIntersectionAndRetainsEveryHistoricalRow() {
    List<UUID> assets = new ArrayList<>();
    for (int index = 0; index < 216; index++) assets.add(UUID.randomUUID());

    List<LogisticsDocumentLine> lines =
        List.of(
            createReturnLine(assets.get(0), LogisticsDocumentState.INSPECTION_REQUIRED, WAREHOUSE),
            createShipmentLine(
                assets.get(1), LogisticsDocumentState.AWAITING_CONFIRMATION, WAREHOUSE),
            createShipmentLine(
                assets.get(2), LogisticsDocumentState.AWAITING_CONFIRMATION, WAREHOUSE),
            createShipmentLine(
                assets.get(3), LogisticsDocumentState.AWAITING_CONFIRMATION, WAREHOUSE),
            createShipmentLine(assets.get(4), LogisticsDocumentState.DRAFT, WAREHOUSE),
            createShipmentLine(assets.get(5), LogisticsDocumentState.DRAFT, WAREHOUSE),
            createShipmentLine(assets.get(6), LogisticsDocumentState.SHIPPED, WAREHOUSE),
            createTransferLine(assets.get(7), WAREHOUSE));

    OrderClient client = createClient();
    RentalOrder cancelled =
        createOrder(client, RentalOrderStatus.CANCELLED, assets.subList(10, 14), 1);
    RentalOrder saved = createOrder(client, RentalOrderStatus.SAVED, assets.subList(14, 20), 2);
    RentalOrder fulfilled =
        createOrder(client, RentalOrderStatus.FULFILLED, assets.subList(20, 21), 3);

    DriverLogisticsTask repairTask =
        createScheduledTask(
            assets.get(8), DriverTaskSourceType.REPAIR, UUID.randomUUID(), DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask estimateTask =
        createScheduledTask(
            assets.get(9),
            DriverTaskSourceType.ESTIMATE,
            UUID.randomUUID(),
            DriverTaskKind.DELIVER_TO_REPAIR);
    DriverLogisticsTask documentTask =
        createScheduledTask(
            assets.get(1),
            DriverTaskSourceType.LOGISTICS_DOCUMENT_LINE,
            lines.get(1).getId(),
            DriverTaskKind.SHIPMENT);
    List<DriverLogisticsTask> tasks = List.of(repairTask, estimateTask, documentTask);
    tasks.forEach(task -> stubCancellation(task, "SCHEDULED", "WAITING"));
    LogisticsGuard guard = createGuard(lines.getFirst());
    stubLeaseRelease(guard, "RELEASED");

    ApplyInventoryOutcomeResponse result =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request(INVENTORY, COMPLETED_AT, 7, assets));

    assertThat(result.inventoryId()).isEqualTo(INVENTORY);
    assertThat(result.finalPlanVersion()).isEqualTo(7);
    assertThat(result.replay()).isFalse();
    assertThat(result.supersededLineCount()).isEqualTo(8);
    assertThat(result.supersededRentalUnitCount()).isEqualTo(11);
    assertThat(result.supersededDocumentIds())
        .containsExactlyInAnyOrderElementsOf(
            lines.stream().map(line -> line.getDocument().getId()).toList())
        .isSortedAccordingTo(UUID_ORDER);
    assertThat(result.supersededRentalOrderIds())
        .containsExactlyInAnyOrder(cancelled.getId(), saved.getId(), fulfilled.getId())
        .isSortedAccordingTo(UUID_ORDER);
    assertThat(result.cancelledDriverTaskIds())
        .containsExactlyInAnyOrderElementsOf(tasks.stream().map(DriverLogisticsTask::getId).toList())
        .isSortedAccordingTo(UUID_ORDER);

    assertThat(count("logistics_document")).isEqualTo(8);
    assertThat(count("logistics_document_line")).isEqualTo(8);
    assertThat(count("rental_order_unit_term")).isEqualTo(11);
    assertThat(count("driver_logistics_task")).isEqualTo(3);
    assertThat(count("inventory_asset_outcome_watermark")).isEqualTo(216);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where state='CANCELLED'", Long.class))
        .isEqualTo(7);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where state='SHIPPED'", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document_line where inventory_superseded_by=?",
                Long.class,
                INVENTORY))
        .isEqualTo(8);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_unit_term where inventory_superseded_by=?",
                Long.class,
                INVENTORY))
        .isEqualTo(11);
    assertThat(orderStatus(cancelled)).isEqualTo(RentalOrderStatus.CANCELLED.name());
    assertThat(orderStatus(saved)).isEqualTo(RentalOrderStatus.CANCELLED.name());
    assertThat(orderStatus(fulfilled)).isEqualTo(RentalOrderStatus.CLOSED.name());
    assertThat(rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(cancelled.getId())).isEmpty();
    assertThat(rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(saved.getId())).isEmpty();
    assertThat(rentalTerms.findAllByOrder_IdOrderByRentalItemIdAsc(fulfilled.getId())).isEmpty();
    for (DriverLogisticsTask task : tasks) {
      assertThat(taskState(task.getId())).isEqualTo("CANCELLED");
      assertThat(
              jdbc.queryForObject(
                  "select inventory_cancelled_by from driver_logistics_task where id=?",
                  UUID.class,
                  task.getId()))
          .isEqualTo(INVENTORY);
      verify(dependencies)
          .cancelDriverTask(
              eq(task.getExternalTaskId()), eq(1L), eq("Задание заменено итогами завершённой инвентаризации"));
    }
    assertThat(guardState(guard.getId())).isEqualTo(LogisticsGuardState.RELEASED.name());
    assertThat(
            jdbc.queryForObject(
                "select inventory_superseded_by from logistics_guard where id=?",
                UUID.class,
                guard.getId()))
        .isEqualTo(INVENTORY);
    verify(dependencies)
        .releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_RETURN),
            eq(lines.getFirst().getDocument().getId()),
            eq(lines.getFirst().getId()));
  }

  @Test
  void createsTwoHundredThirtyDispositionFactsWithABoundedNumberOfFlushes() {
    List<InventoryAssetOutcome> batch = new ArrayList<>();
    for (int index = 0; index < 230; index++) {
      batch.add(
          new InventoryAssetOutcome(
              UUID.randomUUID(),
              UUID.randomUUID(),
              InventoryDispositionKind.SHIPMENT,
              InventoryDesiredStatus.RENTED,
              null,
              new InventoryShipment(
                  LocalDate.of(2026, 8, 16),
                  UUID.randomUUID(),
                  "Арендатор " + index,
                  List.of())));
    }
    ApplyInventoryOutcomeRequest request =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE, COMPLETED_AT, 35, PLAN_SHA256, List.copyOf(batch));
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    ApplyInventoryOutcomeResponse applied =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request);

    assertThat(applied.dispositions()).hasSize(230);
    assertThat(applied.dispositions())
        .allSatisfy(
            disposition -> {
              assertThat(disposition.markerId()).isNotNull();
              assertThat(disposition.documentId()).isNotNull();
              assertThat(disposition.lineId()).isNotNull();
            });
    assertThat(count("logistics_document")).isEqualTo(230);
    assertThat(count("logistics_document_line")).isEqualTo(230);
    assertThat(statistics.getFlushCount()).isLessThanOrEqualTo(8L);
  }

  @Test
  void closesFortyRentalOrdersWithoutPerOrderActiveTermQueries() {
    OrderClient client = createClient();
    List<UUID> assets = new ArrayList<>();
    List<RentalOrder> orders = new ArrayList<>();
    for (int index = 0; index < 40; index++) {
      UUID assetId = UUID.randomUUID();
      assets.add(assetId);
      orders.add(
          createOrder(client, RentalOrderStatus.SAVED, List.of(assetId), 100 + index));
    }
    var statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
    statistics.clear();

    ApplyInventoryOutcomeResponse applied =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request(INVENTORY, COMPLETED_AT, 36, assets));

    assertThat(applied.supersededRentalOrderIds())
        .containsExactlyInAnyOrderElementsOf(orders.stream().map(RentalOrder::getId).toList());
    assertThat(statistics.getQueryExecutionCount())
        .as("active rental terms must be checked once for the whole selected order set")
        .isLessThan(30L);
  }

  @Test
  void retriesAReplyLostAfterRemoteCancellationWithoutRepeatingTheRemoteEffect() {
    UUID asset = UUID.randomUUID();
    DriverLogisticsTask task =
        createScheduledTask(
            asset, DriverTaskSourceType.REPAIR, UUID.randomUUID(), DriverTaskKind.DELIVER_TO_REPAIR);
    LogisticsDependencyGateway.DriverBoardTask active = board(task, 1, "ACTIVE", "SCHEDULED", "WAITING");
    LogisticsDependencyGateway.DriverBoardTask cancelled =
        board(task, 2, "CANCELLED", "SCHEDULED", "CANCELLED");
    when(dependencies.readDriverTask(task.getExternalTaskId())).thenReturn(active, cancelled);
    when(dependencies.cancelDriverTask(eq(task.getExternalTaskId()), eq(1L), anyString()))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT, "response lost"));
    UUID key = UUID.randomUUID();
    ApplyInventoryOutcomeRequest request = request(INVENTORY, COMPLETED_AT, 8, List.of(asset));

    assertThatThrownBy(() -> outcomes.apply(INVENTORY, key, request))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("response lost");
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_outcome_receipt where idempotency_key=?",
                String.class,
                key))
        .isEqualTo("PREPARED");

    ApplyInventoryOutcomeResponse replayed = outcomes.apply(INVENTORY, key, request);

    assertThat(replayed.replay()).isTrue();
    assertThat(replayed.cancelledDriverTaskIds()).containsExactly(task.getId());
    assertThat(taskState(task.getId())).isEqualTo("CANCELLED");
    verify(dependencies, times(2)).readDriverTask(task.getExternalTaskId());
    verify(dependencies, times(1))
        .cancelDriverTask(eq(task.getExternalTaskId()), eq(1L), anyString());
  }

  @Test
  void rejectsAChangedRequestForAnExistingIdempotencyKey() {
    UUID asset = UUID.randomUUID();
    UUID finding = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    ApplyInventoryOutcomeRequest first =
        request(COMPLETED_AT, 9, PLAN_SHA256, finding, asset, InventoryDesiredStatus.FREE);
    ApplyInventoryOutcomeRequest changed =
        request(COMPLETED_AT, 9, PLAN_SHA256, finding, asset, InventoryDesiredStatus.REPAIR);
    outcomes.apply(INVENTORY, key, first);

    assertThatThrownBy(() -> outcomes.apply(INVENTORY, key, changed))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("Idempotency-Key");
    assertThat(count("inventory_outcome_receipt")).isOne();
  }

  @Test
  void rejectsDuplicateFindingOrAssetBeforeWritingAnyReceiptOrWatermark() {
    UUID finding = UUID.randomUUID();
    UUID asset = UUID.randomUUID();
    ApplyInventoryOutcomeRequest duplicate =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE,
            COMPLETED_AT,
            10,
            PLAN_SHA256,
            List.of(
                localOutcome(finding, asset, InventoryDesiredStatus.FREE),
                localOutcome(finding, UUID.randomUUID(), InventoryDesiredStatus.REPAIR)));

    assertThatThrownBy(() -> outcomes.apply(INVENTORY, UUID.randomUUID(), duplicate))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("unique findingId and assetId");
    assertThat(count("inventory_outcome_receipt")).isZero();
    assertThat(count("inventory_asset_outcome_watermark")).isZero();
  }

  @Test
  void rejectsStaleAndAmbiguousEqualWatermarksButAllowsSameSourceReassertionAndNewerTruth() {
    UUID asset = UUID.randomUUID();
    ApplyInventoryOutcomeRequest latest = request(INVENTORY, COMPLETED_AT, 11, List.of(asset));
    outcomes.apply(INVENTORY, UUID.randomUUID(), latest);

    UUID staleInventory = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                outcomes.apply(
                    staleInventory,
                    UUID.randomUUID(),
                    request(staleInventory, COMPLETED_AT.minusSeconds(1), 12, List.of(asset))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("newer completed inventory");

    UUID equalInventory = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                outcomes.apply(
                    equalInventory,
                    UUID.randomUUID(),
                    request(equalInventory, COMPLETED_AT, 12, List.of(asset))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("same completion time");

    ApplyInventoryOutcomeResponse reasserted =
        outcomes.apply(INVENTORY, UUID.randomUUID(), latest);
    UUID newerInventory = UUID.randomUUID();
    ApplyInventoryOutcomeResponse newer =
        outcomes.apply(
            newerInventory,
            UUID.randomUUID(),
            request(newerInventory, COMPLETED_AT.plusSeconds(1), 1, List.of(asset)));

    assertThat(reasserted.replay()).isFalse();
    assertThat(newer.inventoryId()).isEqualTo(newerInventory);
    assertThat(count("inventory_outcome_receipt")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select inventory_id from inventory_asset_outcome_watermark where asset_id=?",
                UUID.class,
                asset))
        .isEqualTo(newerInventory);
  }

  @Test
  void acceptsOnlyAForwardPlanCorrectionForTheSameCompletedInventorySource() {
    UUID asset = UUID.randomUUID();
    UUID finding = UUID.randomUUID();
    String correctedPlanSha256 = "e".repeat(64);
    ApplyInventoryOutcomeRequest original =
        request(COMPLETED_AT, 20, PLAN_SHA256, finding, asset, InventoryDesiredStatus.FREE);
    ApplyInventoryOutcomeRequest corrected =
        request(
            COMPLETED_AT, 21, correctedPlanSha256, finding, asset, InventoryDesiredStatus.REPAIR);
    outcomes.apply(INVENTORY, UUID.randomUUID(), original);

    ApplyInventoryOutcomeResponse advanced =
        outcomes.apply(INVENTORY, UUID.randomUUID(), corrected);
    ApplyInventoryOutcomeResponse exactReassertion =
        outcomes.apply(INVENTORY, UUID.randomUUID(), corrected);

    assertThat(advanced.finalPlanVersion()).isEqualTo(21);
    assertThat(advanced.replay()).isFalse();
    assertThat(exactReassertion.replay()).isFalse();

    assertThatThrownBy(() -> outcomes.apply(INVENTORY, UUID.randomUUID(), original))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("same completion time");
    assertThatThrownBy(
            () ->
                outcomes.apply(
                    INVENTORY,
                    UUID.randomUUID(),
                    request(
                        COMPLETED_AT,
                        21,
                        "f".repeat(64),
                        finding,
                        asset,
                        InventoryDesiredStatus.REPAIR)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("same completion time");
    UUID anotherInventory = UUID.randomUUID();
    assertThatThrownBy(
            () ->
                outcomes.apply(
                    anotherInventory,
                    UUID.randomUUID(),
                    request(
                        COMPLETED_AT,
                        22,
                        "1".repeat(64),
                        finding,
                        asset,
                        InventoryDesiredStatus.REPAIR)))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("same completion time");
    assertThatThrownBy(
            () ->
                outcomes.apply(
                    INVENTORY,
                    UUID.randomUUID(),
                    new ApplyInventoryOutcomeRequest(
                        OTHER_WAREHOUSE,
                        COMPLETED_AT,
                        22,
                        "2".repeat(64),
                        List.of(
                            localOutcome(finding, asset, InventoryDesiredStatus.REPAIR)))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("same completion time");

    assertThat(count("inventory_outcome_receipt")).isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select final_plan_version from inventory_asset_outcome_watermark where asset_id=?",
                Long.class,
                asset))
        .isEqualTo(21);
    assertThat(
            jdbc.queryForObject(
                "select final_plan_sha256 from inventory_asset_outcome_watermark where asset_id=?",
                String.class,
                asset))
        .isEqualTo(correctedPlanSha256);
  }

  @Test
  void correctedPlanSupersedesNewlyIncludedRentalAndShipmentStateForEveryOutcomeStatus() {
    UUID carriedAsset = UUID.randomUUID();
    UUID carriedFinding = UUID.randomUUID();
    List<UUID> restoredAssets = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    List<UUID> restoredFindings = List.of(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
    OrderClient client = createClient();
    RentalOrder order = createOrder(client, RentalOrderStatus.SAVED, restoredAssets, 91);
    List<LogisticsDocumentLine> shipmentLines =
        restoredAssets.stream()
            .map(asset -> createShipmentLine(asset, LogisticsDocumentState.DRAFT, WAREHOUSE))
            .toList();
    outcomes.apply(
        INVENTORY,
        UUID.randomUUID(),
        request(
            COMPLETED_AT,
            30,
            PLAN_SHA256,
            carriedFinding,
            carriedAsset,
            InventoryDesiredStatus.FREE));
    String correctedPlanSha256 = "3".repeat(64);
    ApplyInventoryOutcomeRequest corrected =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE,
            COMPLETED_AT,
            31,
            correctedPlanSha256,
            List.of(
                localOutcome(carriedFinding, carriedAsset, InventoryDesiredStatus.FREE),
                localOutcome(
                    restoredFindings.get(0), restoredAssets.get(0), InventoryDesiredStatus.FREE),
                localOutcome(
                    restoredFindings.get(1), restoredAssets.get(1), InventoryDesiredStatus.REPAIR),
                localOutcome(
                    restoredFindings.get(2),
                    restoredAssets.get(2),
                    InventoryDesiredStatus.CAPITAL_REPAIR)));
    UUID correctionKey = UUID.randomUUID();

    ApplyInventoryOutcomeResponse applied = outcomes.apply(INVENTORY, correctionKey, corrected);
    ApplyInventoryOutcomeResponse replayed = outcomes.apply(INVENTORY, correctionKey, corrected);

    assertThat(applied.finalPlanVersion()).isEqualTo(31);
    assertThat(applied.supersededLineCount()).isEqualTo(3);
    assertThat(applied.supersededRentalUnitCount()).isEqualTo(3);
    assertThat(applied.supersededDocumentIds())
        .containsExactlyInAnyOrderElementsOf(
            shipmentLines.stream().map(line -> line.getDocument().getId()).toList());
    assertThat(applied.supersededRentalOrderIds()).containsExactly(order.getId());
    assertThat(applied.replay()).isFalse();
    assertThat(replayed.replay()).isTrue();
    assertThat(orderStatus(order)).isEqualTo(RentalOrderStatus.CANCELLED.name());
    assertThat(
            jdbc.queryForList(
                "select distinct state from logistics_document where inventory_superseded_by=?",
                String.class,
                INVENTORY))
        .containsExactly(LogisticsDocumentState.CANCELLED.name());
    assertThat(
            jdbc.queryForList(
                "select inventory_desired_status from rental_order_unit_term where order_id=? order"
                    + " by inventory_desired_status",
                String.class,
                order.getId()))
        .containsExactly("CAPITAL_REPAIR", "FREE", "REPAIR");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_unit_term where order_id=? and"
                    + " inventory_superseded_by=? and inventory_final_plan_version=? and"
                    + " inventory_final_plan_sha256=?",
                Long.class,
                order.getId(),
                INVENTORY,
                31L,
                correctedPlanSha256))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document_line where inventory_superseded_by=? and"
                    + " inventory_final_plan_version=? and inventory_final_plan_sha256=?",
                Long.class,
                INVENTORY,
                31L,
                correctedPlanSha256))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_asset_outcome_watermark where inventory_id=? and"
                    + " final_plan_version=? and final_plan_sha256=?",
                Long.class,
                INVENTORY,
                31L,
                correctedPlanSha256))
        .isEqualTo(4);
    assertThat(count("inventory_outcome_receipt")).isEqualTo(2);
  }

  @Test
  void createsAndPubliclyReadsACompletedFormerRentalReturnAndReusesItOnEveryRetryPath() {
    UUID asset = UUID.randomUUID();
    UUID finding = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    LocalDate returnedOn = LocalDate.of(2026, 8, 18);
    ApplyInventoryOutcomeRequest request =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE,
            COMPLETED_AT,
            32,
            PLAN_SHA256,
            List.of(
                new InventoryAssetOutcome(
                    finding,
                    asset,
                    InventoryDispositionKind.LOCAL,
                    InventoryDesiredStatus.FREE,
                    new InventoryFormerRental(returnedOn, clientId, "Бывший арендатор"),
                    null)));
    UUID idempotencyKey = UUID.randomUUID();

    ApplyInventoryOutcomeResponse applied = outcomes.apply(INVENTORY, idempotencyKey, request);
    ApplyInventoryOutcomeResponse replayed = outcomes.apply(INVENTORY, idempotencyKey, request);
    ApplyInventoryOutcomeResponse reasserted =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request);

    assertThat(applied.dispositions()).singleElement().satisfies(
        disposition -> {
          assertThat(disposition.findingId()).isEqualTo(finding);
          assertThat(disposition.assetId()).isEqualTo(asset);
          assertThat(disposition.dispositionKind()).isEqualTo(InventoryDispositionKind.LOCAL);
          assertThat(disposition.markerId()).isNotNull();
          assertThat(disposition.documentId()).isNotNull();
          assertThat(disposition.lineId()).isNotNull();
        });
    assertThat(replayed.replay()).isTrue();
    assertThat(replayed.dispositions()).isEqualTo(applied.dispositions());
    assertThat(reasserted.replay()).isFalse();
    assertThat(reasserted.dispositions().getFirst().markerId())
        .isNotEqualTo(applied.dispositions().getFirst().markerId());
    assertThat(reasserted.dispositions().getFirst().documentId())
        .isEqualTo(applied.dispositions().getFirst().documentId());
    assertThat(reasserted.dispositions().getFirst().lineId())
        .isEqualTo(applied.dispositions().getFirst().lineId());

    var view =
        documentService.get(
            applied.dispositions().getFirst().documentId(), LogisticsDocumentType.RETURN);
    assertThat(view.state()).isEqualTo(LogisticsDocumentState.ACCEPTED);
    assertThat(view.warehouseId()).isEqualTo(WAREHOUSE);
    assertThat(view.clientId()).isEqualTo(clientId);
    assertThat(view.partySnapshot()).isEqualTo("Бывший арендатор");
    assertThat(view.scheduledDate()).isEqualTo(returnedOn);
    assertThat(view.driverSnapshot()).isNull();
    assertThat(view.driverWorkerId()).isNull();
    assertThat(view.inventorySourceId()).isEqualTo(INVENTORY);
    assertThat(view.inventorySourceFindingId()).isEqualTo(finding);
    assertThat(view.inventorySourceDispositionKind()).isEqualTo("LOCAL");
    assertThat(view.lines()).singleElement().satisfies(
        line -> {
          assertThat(line.id()).isEqualTo(applied.dispositions().getFirst().lineId());
          assertThat(line.assetId()).isEqualTo(asset);
          assertThat(line.state()).isEqualTo(LogisticsLineState.ARRIVED);
          assertThat(line.inventoryShipmentFurniture()).isNull();
        });
    assertThat(documentService.list(LogisticsDocumentType.RETURN, WAREHOUSE))
        .extracting(value -> value.id())
        .containsExactly(view.id());
    assertThat(count("logistics_document")).isOne();
    assertThat(count("logistics_document_line")).isOne();
    assertThat(count("driver_logistics_task")).isZero();
    assertThat(count("logistics_guard")).isZero();
  }

  @Test
  void createsAndPubliclyReadsACompletedShipmentWithFurnitureButNoOperationalWorkflow() {
    UUID asset = UUID.randomUUID();
    UUID finding = UUID.randomUUID();
    UUID clientId = UUID.randomUUID();
    UUID firstEquipment = UUID.fromString("20000000-0000-0000-0000-000000000001");
    UUID secondEquipment = UUID.fromString("20000000-0000-0000-0000-000000000002");
    LocalDate departedOn = LocalDate.of(2026, 8, 17);
    ApplyInventoryOutcomeRequest request =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE,
            COMPLETED_AT,
            33,
            PLAN_SHA256,
            List.of(
                new InventoryAssetOutcome(
                    finding,
                    asset,
                    InventoryDispositionKind.SHIPMENT,
                    InventoryDesiredStatus.RENTED,
                    null,
                    new InventoryShipment(
                        departedOn,
                        clientId,
                        "Найденный арендатор",
                        List.of(
                            new InventoryShipmentFurniture(secondEquipment, 8L, 3L),
                            new InventoryShipmentFurniture(firstEquipment, 5L, 2L))))));

    ApplyInventoryOutcomeResponse applied =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request);
    var disposition = applied.dispositions().getFirst();
    var view = documentService.get(disposition.documentId(), LogisticsDocumentType.SHIPMENT);

    assertThat(applied.dispositions()).hasSize(1);
    assertThat(disposition.dispositionKind()).isEqualTo(InventoryDispositionKind.SHIPMENT);
    assertThat(disposition.markerId()).isNotNull();
    assertThat(disposition.documentId()).isNotNull();
    assertThat(disposition.lineId()).isNotNull();
    assertThat(view.state()).isEqualTo(LogisticsDocumentState.SHIPPED);
    assertThat(view.clientId()).isEqualTo(clientId);
    assertThat(view.partySnapshot()).isEqualTo("Найденный арендатор");
    assertThat(view.scheduledDate()).isEqualTo(departedOn);
    assertThat(view.driverSnapshot()).isNull();
    assertThat(view.driverWorkerId()).isNull();
    assertThat(view.inventorySourceId()).isEqualTo(INVENTORY);
    assertThat(view.inventorySourceFindingId()).isEqualTo(finding);
    assertThat(view.inventorySourceDispositionKind()).isEqualTo("SHIPMENT");
    assertThat(view.lines()).singleElement().satisfies(
        line -> {
          assertThat(line.id()).isEqualTo(disposition.lineId());
          assertThat(line.assetId()).isEqualTo(asset);
          assertThat(line.state()).isEqualTo(LogisticsLineState.DEPARTED);
          assertThat(line.inventoryShipmentFurniture()).isNotNull();
          assertThat(line.inventoryShipmentFurniture().isArray()).isTrue();
          assertThat(line.inventoryShipmentFurniture()).hasSize(2);
          assertThat(line.inventoryShipmentFurniture().get(0).get("equipmentId").asText())
              .isEqualTo(firstEquipment.toString());
          assertThat(line.inventoryShipmentFurniture().get(0).get("quantity").asLong())
              .isEqualTo(2L);
          assertThat(line.inventoryShipmentFurniture().get(1).get("equipmentId").asText())
              .isEqualTo(secondEquipment.toString());
        });
    assertThat(documentService.list(LogisticsDocumentType.SHIPMENT, WAREHOUSE))
        .extracting(value -> value.id())
        .containsExactly(view.id());
    assertThat(count("driver_logistics_task")).isZero();
    assertThat(count("logistics_guard")).isZero();
    assertThat(count("logistics_task_reference")).isZero();
    assertThat(count("shipment_furniture_movement_task")).isZero();
    assertThat(count("equipment_movement_task")).isZero();

    ApplyInventoryOutcomeRequest drifted =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE,
            COMPLETED_AT,
            33,
            PLAN_SHA256,
            List.of(
                new InventoryAssetOutcome(
                    finding,
                    asset,
                    InventoryDispositionKind.SHIPMENT,
                    InventoryDesiredStatus.RENTED,
                    null,
                    new InventoryShipment(
                        departedOn,
                        clientId,
                        "Другой арендатор",
                        request.outcomes().getFirst().shipment().furniture()))));
    assertThatThrownBy(() -> outcomes.apply(INVENTORY, UUID.randomUUID(), drifted))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("document conflicts");
    assertThat(count("logistics_document")).isOne();
  }

  @Test
  void writeOffIsOnlyADurableMarkerAndSupersessionWithoutShipmentOrFreeState() {
    UUID asset = UUID.randomUUID();
    UUID finding = UUID.randomUUID();
    LogisticsDocumentLine previous =
        createShipmentLine(asset, LogisticsDocumentState.DRAFT, WAREHOUSE);
    LogisticsGuard guard = createGuard(previous);
    when(dependencies.releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(previous.getDocument().getId()),
            eq(previous.getId())))
        .thenReturn(terminalLease(guard, "RELEASED"));
    ApplyInventoryOutcomeRequest request =
        new ApplyInventoryOutcomeRequest(
            WAREHOUSE,
            COMPLETED_AT,
            34,
            PLAN_SHA256,
            List.of(
                new InventoryAssetOutcome(
                    finding,
                    asset,
                    InventoryDispositionKind.WRITE_OFF,
                    InventoryDesiredStatus.WRITE_OFF_PENDING,
                    null,
                    null)));

    ApplyInventoryOutcomeResponse applied =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request);
    var disposition = applied.dispositions().getFirst();

    assertThat(disposition.findingId()).isEqualTo(finding);
    assertThat(disposition.assetId()).isEqualTo(asset);
    assertThat(disposition.dispositionKind()).isEqualTo(InventoryDispositionKind.WRITE_OFF);
    assertThat(disposition.markerId()).isNotNull();
    assertThat(disposition.documentId()).isNull();
    assertThat(disposition.lineId()).isNull();
    assertThat(applied.supersededDocumentIds())
        .containsExactly(previous.getDocument().getId());
    assertThat(documentState(previous.getDocument().getId()))
        .isEqualTo(LogisticsDocumentState.CANCELLED.name());
    assertThat(count("logistics_document")).isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where inventory_source_id=?",
                Long.class,
                INVENTORY))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where state='SHIPPED'", Long.class))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select desired_status from inventory_outcome_receipt_asset where id=?",
                String.class,
                disposition.markerId()))
        .isEqualTo("WRITE_OFF_PENDING");
    assertThat(
            jdbc.queryForObject(
                "select inventory_desired_status from logistics_document_line where id=?",
                String.class,
                previous.getId()))
        .isEqualTo("WRITE_OFF_PENDING");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from inventory_outcome_receipt_asset where desired_status='FREE'",
                Long.class))
        .isZero();
    assertThat(guardState(guard.getId())).isEqualTo(LogisticsGuardState.RELEASED.name());
    verify(dependencies)
        .releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(previous.getDocument().getId()),
            eq(previous.getId()));
    assertThat(count("driver_logistics_task")).isZero();
    assertThat(count("logistics_guard")).isOne();
  }

  @Test
  void sameSourceReassertionDoesNotCancelTheMovementCreatedByThatInventoryFinding() {
    UUID asset = UUID.randomUUID();
    UUID finding = UUID.randomUUID();
    ApplyInventoryOutcomeRequest request =
        request(
            COMPLETED_AT,
            12,
            PLAN_SHA256,
            finding,
            asset,
            InventoryDesiredStatus.CAPITAL_REPAIR);
    outcomes.apply(INVENTORY, UUID.randomUUID(), request);
    DriverLogisticsTask currentInventoryMovement =
        createScheduledTask(
            asset,
            DriverTaskSourceType.INVENTORY,
            finding,
            DriverTaskKind.DELIVER_TO_REPAIR);

    ApplyInventoryOutcomeResponse reasserted =
        outcomes.apply(INVENTORY, UUID.randomUUID(), request);

    assertThat(reasserted.replay()).isFalse();
    assertThat(reasserted.cancelledDriverTaskIds()).isEmpty();
    assertThat(taskState(currentInventoryMovement.getId())).isEqualTo("SCHEDULED");
    assertThat(
            jdbc.queryForObject(
                "select inventory_cancelled_by from driver_logistics_task where id=?",
                UUID.class,
                currentInventoryMovement.getId()))
        .isNull();
    verify(dependencies, never()).readDriverTask(currentInventoryMovement.getExternalTaskId());
    verify(dependencies, never())
        .cancelDriverTask(eq(currentInventoryMovement.getExternalTaskId()), anyLong(), anyString());
  }

  @Test
  void retriesALostLeaseReleaseResponseWithTheSameStableDependencyKey() {
    UUID asset = UUID.randomUUID();
    LogisticsDocumentLine line =
        createShipmentLine(asset, LogisticsDocumentState.DRAFT, WAREHOUSE);
    LogisticsGuard guard = createGuard(line);
    OperationLease released = terminalLease(guard, "RELEASED");
    when(dependencies.releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(line.getDocument().getId()),
            eq(line.getId())))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT,
                "asset lease release response lost"))
        .thenReturn(released);
    UUID key = UUID.randomUUID();
    ApplyInventoryOutcomeRequest request =
        request(INVENTORY, COMPLETED_AT, 13, List.of(asset));

    assertThatThrownBy(() -> outcomes.apply(INVENTORY, key, request))
        .isInstanceOf(LogisticsDependencyException.class)
        .hasMessageContaining("response lost");
    assertThat(guardState(guard.getId()))
        .isEqualTo(LogisticsGuardState.RECONCILIATION_REQUIRED.name());

    ApplyInventoryOutcomeResponse replayed = outcomes.apply(INVENTORY, key, request);

    assertThat(replayed.replay()).isTrue();
    assertThat(guardState(guard.getId())).isEqualTo(LogisticsGuardState.RELEASED.name());
    ArgumentCaptor<UUID> releaseKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .releaseOperationLease(
            releaseKeys.capture(),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(line.getDocument().getId()),
            eq(line.getId()));
    assertThat(releaseKeys.getAllValues()).hasSize(2).allMatch(releaseKeys.getValue()::equals);
  }

  @Test
  void acceptsAnExactAlreadyExpiredLeaseAsTerminalRecovery() {
    UUID asset = UUID.randomUUID();
    LogisticsDocumentLine line =
        createShipmentLine(asset, LogisticsDocumentState.DRAFT, WAREHOUSE);
    LogisticsGuard guard = createGuard(line);
    when(dependencies.releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(line.getDocument().getId()),
            eq(line.getId())))
        .thenReturn(terminalLease(guard, "EXPIRED"));

    ApplyInventoryOutcomeResponse result =
        outcomes.apply(
            INVENTORY,
            UUID.randomUUID(),
            request(INVENTORY, COMPLETED_AT, 14, List.of(asset)));

    assertThat(result.replay()).isFalse();
    assertThat(guardState(guard.getId())).isEqualTo(LogisticsGuardState.RELEASED.name());
  }

  @Test
  void rejectsAMismatchedLeaseTerminalWithoutClosingTheLocalGuard() {
    UUID asset = UUID.randomUUID();
    LogisticsDocumentLine line =
        createShipmentLine(asset, LogisticsDocumentState.DRAFT, WAREHOUSE);
    LogisticsGuard guard = createGuard(line);
    when(dependencies.releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(line.getDocument().getId()),
            eq(line.getId())))
        .thenReturn(
            new OperationLease(
                guard.getLeaseId(),
                guard.getLeaseVersion() + 1,
                asset,
                guard.getFenceToken() + 1,
                "RELEASED",
                COMPLETED_AT.plusDays(1)));

    assertThatThrownBy(
            () ->
                outcomes.apply(
                    INVENTORY,
                    UUID.randomUUID(),
                    request(INVENTORY, COMPLETED_AT, 15, List.of(asset))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("mismatched terminal logistics lease");

    assertThat(guardState(guard.getId()))
        .isEqualTo(LogisticsGuardState.RECONCILIATION_REQUIRED.name());
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_outcome_task_action where target_id=?",
                String.class,
                guard.getId()))
        .isEqualTo("PENDING");
  }

  @Test
  void rejectsAPartialActiveDocumentAtomicallyBeforeAnyMarkerOrRemoteEffect() {
    UUID selected = UUID.randomUUID();
    UUID unrelated = UUID.randomUUID();
    LogisticsDocument document =
        documents.saveAndFlush(
            LogisticsDocument.createShipment(
                WAREHOUSE, "Клиент", "Водитель", SUBJECT, UUID.randomUUID()));
    documentLines.saveAndFlush(LogisticsDocumentLine.create(document, 1, selected, 0, null));
    documentLines.saveAndFlush(LogisticsDocumentLine.create(document, 2, unrelated, 0, null));

    assertThatThrownBy(
            () ->
                outcomes.apply(
                    INVENTORY,
                    UUID.randomUUID(),
                    request(INVENTORY, COMPLETED_AT, 13, List.of(selected))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("does not contain every active line");

    assertThat(count("inventory_outcome_receipt")).isZero();
    assertThat(count("inventory_asset_outcome_watermark")).isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document_line where inventory_superseded_by is not null",
                Long.class))
        .isZero();
    assertThat(documentState(document.getId())).isEqualTo("DRAFT");
    verify(dependencies, never()).readDriverTask(any(UUID.class));
    verify(dependencies, never())
        .cancelDriverTask(any(UUID.class), anyLong(), anyString());
    verify(dependencies, never())
        .releaseOperationLease(
            any(UUID.class),
            any(UUID.class),
            anyLong(),
            anyLong(),
            any(LogisticsOwnerType.class),
            any(UUID.class),
            any(UUID.class));
  }

  @Test
  void preservesTaskBoardCompletedWorkInsteadOfCancellingIt() {
    UUID asset = UUID.randomUUID();
    DriverLogisticsTask task =
        createScheduledTask(
            asset, DriverTaskSourceType.ESTIMATE, UUID.randomUUID(), DriverTaskKind.DELIVER_TO_REPAIR);
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(board(task, 2, "DONE", "CURRENT", "DONE"));

    ApplyInventoryOutcomeResponse result =
        outcomes.apply(
            INVENTORY,
            UUID.randomUUID(),
            request(INVENTORY, COMPLETED_AT, 14, List.of(asset)));

    assertThat(result.cancelledDriverTaskIds()).isEmpty();
    assertThat(taskState(task.getId())).isEqualTo("FINALIZING");
    assertThat(
            jdbc.queryForObject(
                "select inventory_cancelled_by from driver_logistics_task where id=?",
                UUID.class,
                task.getId()))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select state from inventory_outcome_task_action where target_id=?",
                String.class,
                task.getId()))
        .isEqualTo("PRESERVED");
    verify(dependencies, never())
        .cancelDriverTask(eq(task.getExternalTaskId()), eq(2L), anyString());
  }

  @Test
  void authoritativelyCancelsAlreadyStartedTaskBoardWork() {
    UUID asset = UUID.randomUUID();
    DriverLogisticsTask task =
        createStartedTask(
            asset, DriverTaskSourceType.REPAIR, UUID.randomUUID(), DriverTaskKind.DELIVER_TO_REPAIR);
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(board(task, 1, "ACTIVE", "CURRENT", "IN_PROGRESS"));
    when(dependencies.cancelDriverTask(eq(task.getExternalTaskId()), eq(1L), anyString()))
        .thenReturn(board(task, 2, "CANCELLED", "CURRENT", "CANCELLED"));

    ApplyInventoryOutcomeResponse result =
        outcomes.apply(
            INVENTORY,
            UUID.randomUUID(),
            request(INVENTORY, COMPLETED_AT, 15, List.of(asset)));

    assertThat(result.cancelledDriverTaskIds()).containsExactly(task.getId());
    assertThat(taskState(task.getId())).isEqualTo("CANCELLED");
    verify(dependencies)
        .cancelDriverTask(eq(task.getExternalTaskId()), eq(1L), anyString());
  }

  @Test
  void rejectsAnActiveDocumentOwnedByAnotherWarehouseBeforeMutation() {
    UUID asset = UUID.randomUUID();
    LogisticsDocumentLine line =
        createShipmentLine(asset, LogisticsDocumentState.DRAFT, OTHER_WAREHOUSE);

    assertThatThrownBy(
            () ->
                outcomes.apply(
                    INVENTORY,
                    UUID.randomUUID(),
                    request(INVENTORY, COMPLETED_AT, 16, List.of(asset))))
        .isInstanceOf(LogisticsConflictException.class)
        .hasMessageContaining("belongs to another warehouse");

    assertThat(count("inventory_outcome_receipt")).isZero();
    assertThat(count("inventory_asset_outcome_watermark")).isZero();
    assertThat(documentState(line.getDocument().getId())).isEqualTo("DRAFT");
    assertThat(
            jdbc.queryForObject(
                "select inventory_superseded_by from logistics_document_line where id=?",
                UUID.class,
                line.getId()))
        .isNull();
  }

  private LogisticsDocumentLine createReturnLine(
      UUID assetId, LogisticsDocumentState state, UUID warehouseId) {
    LogisticsDocument document =
        LogisticsDocument.createReturn(warehouseId, SUBJECT, UUID.randomUUID());
    if (state == LogisticsDocumentState.INSPECTION_REQUIRED) {
      document.scheduleReturn("Водитель", LocalDate.of(2026, 8, 20));
      document.beginReturnRegistration();
      document.requireReturnInspection();
    } else {
      throw new IllegalArgumentException("Unsupported return fixture state " + state);
    }
    return persistLine(document, assetId);
  }

  private LogisticsDocumentLine createShipmentLine(
      UUID assetId, LogisticsDocumentState state, UUID warehouseId) {
    LogisticsDocument document =
        LogisticsDocument.createShipment(
            warehouseId, "Клиент", "Водитель", SUBJECT, UUID.randomUUID());
    if (state != LogisticsDocumentState.DRAFT) {
      document.scheduleShipment("Водитель", LocalDate.of(2026, 8, 20));
      document.beginShipmentPreparation();
      document.awaitShipmentConfirmation();
      if (state == LogisticsDocumentState.SHIPPED) {
        document.beginShipmentConfirmation();
        document.ship();
      } else if (state != LogisticsDocumentState.AWAITING_CONFIRMATION) {
        throw new IllegalArgumentException("Unsupported shipment fixture state " + state);
      }
    }
    return persistLine(document, assetId);
  }

  private LogisticsDocumentLine createTransferLine(UUID assetId, UUID warehouseId) {
    LogisticsDocument document =
        LogisticsDocument.createTransfer(
            warehouseId,
            OTHER_WAREHOUSE.equals(warehouseId) ? WAREHOUSE : OTHER_WAREHOUSE,
            LocalDate.of(2026, 8, 20),
            SUBJECT,
            UUID.randomUUID());
    return persistLine(document, assetId);
  }

  private LogisticsDocumentLine persistLine(LogisticsDocument document, UUID assetId) {
    LogisticsDocument persisted = documents.saveAndFlush(document);
    return documentLines.saveAndFlush(
        LogisticsDocumentLine.create(persisted, 1, assetId, 0, null));
  }

  private LogisticsGuard createGuard(LogisticsDocumentLine line) {
    return guards.saveAndFlush(
        LogisticsGuard.active(
            line.getDocument(),
            line,
            UUID.randomUUID(),
            4,
            9,
            3,
            COMPLETED_AT.minusDays(1)));
  }

  private void stubLeaseRelease(LogisticsGuard guard, String terminalState) {
    LogisticsDocumentLine line = guard.getLine();
    when(dependencies.releaseOperationLease(
            any(UUID.class),
            eq(guard.getLeaseId()),
            eq(guard.getLeaseVersion()),
            eq(guard.getFenceToken()),
            eq(
                switch (line.getDocument().getDocumentType()) {
                  case RETURN -> LogisticsOwnerType.LOGISTICS_RETURN;
                  case SHIPMENT -> LogisticsOwnerType.LOGISTICS_SHIPMENT;
                  case TRANSFER -> LogisticsOwnerType.LOGISTICS_TRANSFER;
                }),
            eq(line.getDocument().getId()),
            eq(line.getId())))
        .thenReturn(terminalLease(guard, terminalState));
  }

  private static OperationLease terminalLease(LogisticsGuard guard, String terminalState) {
    return new OperationLease(
        guard.getLeaseId(),
        guard.getLeaseVersion() + 1,
        guard.getAssetId(),
        guard.getFenceToken(),
        terminalState,
        COMPLETED_AT.plusDays(1));
  }

  private OrderClient createClient() {
    return clients.saveAndFlush(
        OrderClient.create(
            ClientType.LEGAL_ENTITY,
            "Клиент",
            "клиент",
            "+79990000000",
            "+79990000000",
            null,
            null,
            "Представитель",
            SUBJECT,
            "Управляющий",
            null,
            null,
            List.of(),
            SUBJECT,
            UUID.randomUUID(),
            "b".repeat(64)));
  }

  private RentalOrder createOrder(
      OrderClient client, RentalOrderStatus status, List<UUID> assetIds, int ordinal) {
    RentalOrder order =
        RentalOrder.create(
            "ORD-99000" + ordinal,
            client,
            SUBJECT,
            "Управляющий",
            SUBJECT,
            "Управляющий",
            "WAREHOUSE_MANAGER",
            "+79990000000",
            null,
            UUID.randomUUID(),
            "c".repeat(64));
    order.selectWarehouse(WAREHOUSE);
    if (status == RentalOrderStatus.CANCELLED) {
      order.cancel();
    } else {
      order.replaceClientDeliveryDetails(
          "Москва, склад " + ordinal,
          new java.math.BigDecimal("55.750000"),
          new java.math.BigDecimal("37.620000"),
          List.of());
      order.replaceClientDesiredDeliveryWindows(
          List.of(
              DesiredDeliveryWindow.create(
                  LocalDate.of(2026, 8, 21), LocalDate.of(2026, 8, 21))));
      order.saveForFulfillment();
      OffsetDateTime paymentStartedAt = OffsetDateTime.now();
      order.startPaymentReservation(paymentStartedAt);
      order.confirmPayment(
          RentalOrderPaymentSource.MANAGER_CONFIRMATION, SUBJECT, paymentStartedAt.plusSeconds(1));
      if (status == RentalOrderStatus.FULFILLED) order.fulfill();
    }
    RentalOrder persisted = rentalOrders.saveAndFlush(order);
    rentalTerms.saveAllAndFlush(
        assetIds.stream()
            .map(
                assetId ->
                    RentalOrderUnitTerm.create(
                        persisted, assetId, 1, new RentalOrderQuotedPrice(null, null)))
            .toList());
    return persisted;
  }

  private DriverLogisticsTask createScheduledTask(
      UUID assetId, DriverTaskSourceType sourceType, UUID sourceId, DriverTaskKind kind) {
    return createTask(assetId, sourceType, sourceId, kind, "SCHEDULED", "WAITING");
  }

  private DriverLogisticsTask createStartedTask(
      UUID assetId, DriverTaskSourceType sourceType, UUID sourceId, DriverTaskKind kind) {
    return createTask(assetId, sourceType, sourceId, kind, "CURRENT", "IN_PROGRESS");
  }

  private DriverLogisticsTask createTask(
      UUID assetId,
      DriverTaskSourceType sourceType,
      UUID sourceId,
      DriverTaskKind kind,
      String lane,
      String entryStatus) {
    UUID repairId = kind == DriverTaskKind.DELIVER_TO_REPAIR ? sourceId : null;
    DriverLogisticsTask task =
        DriverLogisticsTask.create(
            WAREHOUSE,
            assetId,
            repairId,
            sourceType,
            sourceId,
            kind,
            DriverTaskPlanningMode.AUTO,
            LocalDate.of(2026, 8, 20),
            3,
            null,
            "БЫТ-ИНВ",
            UUID.randomUUID(),
            SUBJECT,
            UUID.randomUUID(),
            "d".repeat(64));
    task.registerBoardTask(
        UUID.randomUUID(), 1, UUID.randomUUID(), entryStatus, lane, null);
    return driverTasks.saveAndFlush(task);
  }

  private void stubCancellation(DriverLogisticsTask task, String lane, String entryStatus) {
    when(dependencies.readDriverTask(task.getExternalTaskId()))
        .thenReturn(board(task, 1, "ACTIVE", lane, entryStatus));
    when(dependencies.cancelDriverTask(eq(task.getExternalTaskId()), eq(1L), anyString()))
        .thenReturn(board(task, 2, "CANCELLED", lane, "CANCELLED"));
  }

  private static LogisticsDependencyGateway.DriverBoardTask board(
      DriverLogisticsTask task,
      long taskVersion,
      String status,
      String lane,
      String entryStatus) {
    return new LogisticsDependencyGateway.DriverBoardTask(
        task.getTaskBoardTaskId(),
        taskVersion,
        task.getWarehouseId(),
        task.getExternalTaskId(),
        "Задание",
        task.getUnitNumber(),
        "Перемещение",
        status,
        task.getScheduledDate(),
        lane,
        task.getPriority(),
        false,
        "DONE".equals(entryStatus) ? COMPLETED_AT : null,
        task.getTaskBoardEntryId(),
        taskVersion,
        entryStatus,
        0);
  }

  private static ApplyInventoryOutcomeRequest request(
      UUID inventoryId,
      OffsetDateTime completedAt,
      long planVersion,
      List<UUID> assetIds) {
    AtomicInteger index = new AtomicInteger();
    List<InventoryAssetOutcome> values =
        assetIds.stream()
            .map(
                assetId ->
                    localOutcome(
                        UUID.nameUUIDFromBytes(
                            (inventoryId + ":finding:" + assetId).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                        assetId,
                        InventoryDesiredStatus.values()[index.getAndIncrement() % 3]))
            .toList();
    return new ApplyInventoryOutcomeRequest(
        WAREHOUSE, completedAt, planVersion, PLAN_SHA256, values);
  }

  private static ApplyInventoryOutcomeRequest request(
      OffsetDateTime completedAt,
      long planVersion,
      String planSha256,
      UUID findingId,
      UUID assetId,
      InventoryDesiredStatus status) {
    return new ApplyInventoryOutcomeRequest(
        WAREHOUSE,
        completedAt,
        planVersion,
        planSha256,
        List.of(localOutcome(findingId, assetId, status)));
  }

  private static InventoryAssetOutcome localOutcome(
      UUID findingId, UUID assetId, InventoryDesiredStatus status) {
    return new InventoryAssetOutcome(
        findingId, assetId, InventoryDispositionKind.LOCAL, status, null, null);
  }

  private long count(String table) {
    return jdbc.queryForObject("select count(*) from " + table, Long.class);
  }

  private String documentState(UUID id) {
    return jdbc.queryForObject(
        "select state from logistics_document where id=?", String.class, id);
  }

  private String orderStatus(RentalOrder order) {
    return jdbc.queryForObject(
        "select status from rental_order where id=?", String.class, order.getId());
  }

  private String taskState(UUID id) {
    return jdbc.queryForObject(
        "select state from driver_logistics_task where id=?", String.class, id);
  }

  private String guardState(UUID id) {
    return jdbc.queryForObject(
        "select guard_state from logistics_guard where id=?", String.class, id);
  }
}
