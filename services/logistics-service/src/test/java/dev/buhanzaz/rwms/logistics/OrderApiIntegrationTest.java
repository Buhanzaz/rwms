package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.order.service.RentalOrderMutationRecoveryService;
import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import dev.buhanzaz.rwms.logistics.service.ShipmentProcessor;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "rwms.logistics.return-completion.relay-enabled=false",
      "rwms.logistics.shipment.relay-enabled=false",
      "rwms.logistics.transfer.relay-enabled=false",
      "rwms.logistics.order-mutation-reconcile-delay=1h",
      "rwms.logistics.order-mutation-reconcile-initial-delay=1h",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderApiIntegrationTest {
  private static final UUID MANAGER_1 = UUID.fromString("00000000-0000-0000-0000-000000009101");
  private static final UUID MANAGER_2 = UUID.fromString("00000000-0000-0000-0000-000000009102");
  private static final UUID ADMIN = UUID.fromString("00000000-0000-0000-0000-000000009103");
  private static final String RENTAL_MANAGER_WEB_CLIENT_ID = "rwms-rental-manager-web";
  private static final String RENTAL_MANAGER_ANDROID_CLIENT_ID = "rwms-rental-manager-android";
  private static final UUID WAREHOUSE_1 = UUID.fromString("00000000-0000-0000-0000-000000009201");
  private static final UUID WAREHOUSE_2 = UUID.fromString("00000000-0000-0000-0000-000000009202");
  private static final UUID UNIT_1 = UUID.fromString("00000000-0000-0000-0000-000000009301");
  private static final UUID UNIT_2 = UUID.fromString("00000000-0000-0000-0000-000000009302");
  private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000009401");
  private static final UUID DRIVER_QUEUE_DEFINITION =
      UUID.fromString("00000000-0000-0000-0000-000000009501");
  private static final UUID DRIVER_QUEUE_CATEGORY =
      UUID.fromString("00000000-0000-0000-0000-000000009502");
  private static final AtomicInteger NEXT_TEST_PHONE = new AtomicInteger(1_000_000);

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired LogisticsExternalAttemptClaimService externalAttemptClaims;
  @Autowired ShipmentProcessor shipmentProcessor;
  @Autowired RentalOrderMutationRecoveryService orderMutationRecovery;
  @MockitoBean LogisticsDependencyGateway dependencies;

  private final Map<UUID, LinkedHashMap<UUID, LogisticsDependencyGateway.OrderUnitReservation>>
      reservations = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> activeUnitOwners = new ConcurrentHashMap<>();
  private final Map<UUID, LogisticsDependencyGateway.OrderUnitReservation> releaseReceipts =
      new ConcurrentHashMap<>();
  private final Map<UUID, List<LogisticsDependencyGateway.OrderUnitReservation>>
      releaseAllReceipts = new ConcurrentHashMap<>();
  private final Map<UUID, LinkedHashMap<UUID, LogisticsDependencyGateway.OrderEquipmentReservation>>
      equipmentReservations = new ConcurrentHashMap<>();

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table
          shipment_task_settings,
          driver_logistics_task,
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_outbox,
          logistics_document,
          rental_order_command_receipt,
          rental_order_audit_event,
          rental_order,
          order_client
        cascade
        """);
    reservations.clear();
    activeUnitOwners.clear();
    releaseReceipts.clear();
    releaseAllReceipts.clear();
    equipmentReservations.clear();
    reset(dependencies);
    when(dependencies.readWarehouseDriverQueue(WAREHOUSE_1))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                WAREHOUSE_1, DRIVER_QUEUE_DEFINITION, DRIVER_QUEUE_CATEGORY));
    when(dependencies.readRentalItemSnapshot(any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.RentalItemSnapshot(
                    invocation.getArgument(0), 7, WAREHOUSE_1, "БТ-QA", "FREE", List.of()));
    when(dependencies.productionReady()).thenReturn(true);
    when(dependencies.warehouseAdmission(
            WAREHOUSE_1, LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                WAREHOUSE_1,
                23,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE_1), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    WAREHOUSE_1, "Europe/Moscow", invocation.getArgument(1)));
    when(dependencies.readWarehouseIdentity(any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseIdentity(
                    invocation.getArgument(0), 0, true, "Europe/Moscow"));
    when(dependencies.readOrderUnits(any()))
        .thenAnswer(
            invocation ->
                List.copyOf(
                    reservations
                        .computeIfAbsent(
                            invocation.getArgument(0), ignored -> new LinkedHashMap<>())
                        .values()));
    when(dependencies.readOrderUnitCandidates(any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(new LogisticsDependencyGateway.OrderUnitCandidatePage(List.of(), 0, 50, 0, 0));
    when(dependencies.reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString()))
        .thenAnswer(
            invocation ->
                reserveRemotely(
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    invocation.getArgument(7),
                    invocation.getArgument(8)));
    when(dependencies.releaseOrderUnit(any(), any(), any(), any(), anyString()))
        .thenAnswer(
            invocation ->
                releaseRemotely(
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    invocation.getArgument(2)));
    when(dependencies.releaseAllOrderUnits(any(), any(), any(), anyString()))
        .thenAnswer(
            invocation -> releaseAllRemotely(invocation.getArgument(0), invocation.getArgument(1)));
    when(dependencies.replaceOrderEquipmentReservations(
            any(), any(), any(), any(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              UUID orderId = invocation.getArgument(1);
              List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> requirements =
                  invocation.getArgument(5);
              return replaceEquipmentReservationsRemotely(orderId, requirements);
            });
  }

  @Test
  void logisticsDocumentListsReturnBoundedArrayPagesForEveryDocumentType() throws Exception {
    LocalDate scheduledDate = LocalDate.of(2026, 9, 2);
    for (LogisticsDocumentType type : LogisticsDocumentType.values()) {
      List<UUID> documentIds = seedPagedDocuments(type, 55, scheduledDate);
      String path = "/api/logistics/v1/" + type.name().toLowerCase() + "s";

      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("scheduledDate", scheduledDate.toString())
                  .with(admin()))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RWMS-Page", "0"))
          .andExpect(header().string("X-RWMS-Page-Size", "50"))
          .andExpect(header().string("X-RWMS-Total-Elements", "55"))
          .andExpect(header().string("X-RWMS-Total-Pages", "2"))
          .andExpect(header().string("X-RWMS-Has-Next", "true"))
          .andExpect(jsonPath("$.length()").value(50))
          .andExpect(jsonPath("$[0].id").value(documentIds.get(54).toString()))
          .andExpect(jsonPath("$[49].id").value(documentIds.get(5).toString()))
          .andExpect(jsonPath("$[0].lines.length()").value(1));

      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("scheduledDate", scheduledDate.toString())
                  .param("page", "1")
                  .param("size", "10")
                  .with(admin()))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RWMS-Page", "1"))
          .andExpect(header().string("X-RWMS-Page-Size", "10"))
          .andExpect(header().string("X-RWMS-Total-Elements", "55"))
          .andExpect(header().string("X-RWMS-Total-Pages", "6"))
          .andExpect(header().string("X-RWMS-Has-Next", "true"))
          .andExpect(jsonPath("$.length()").value(10))
          .andExpect(jsonPath("$[0].id").value(documentIds.get(44).toString()))
          .andExpect(jsonPath("$[9].id").value(documentIds.get(35).toString()));

      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("scheduledDate", scheduledDate.toString())
                  .param("page", "5")
                  .param("size", "10")
                  .with(admin()))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RWMS-Page", "5"))
          .andExpect(header().string("X-RWMS-Page-Size", "10"))
          .andExpect(header().string("X-RWMS-Has-Next", "false"))
          .andExpect(jsonPath("$.length()").value(5))
          .andExpect(jsonPath("$[0].id").value(documentIds.get(4).toString()))
          .andExpect(jsonPath("$[4].id").value(documentIds.get(0).toString()));
    }
  }

  @Test
  void logisticsDocumentListsRejectInvalidPageBounds() throws Exception {
    for (Map.Entry<String, String> invalid :
        Map.of("page", "-1", "zeroSize", "0", "oversize", "101").entrySet()) {
      var request =
          get("/api/logistics/v1/returns")
              .param("warehouseId", WAREHOUSE_1.toString())
              .param("scheduledDate", "2026-09-02")
              .with(admin());
      if ("page".equals(invalid.getKey())) {
        request.param("page", invalid.getValue());
      } else {
        request.param("size", invalid.getValue());
      }
      mvc.perform(request).andExpect(status().isBadRequest());
    }
  }

  @Test
  void documentHistoryKeepsSavedEvidenceAndPagesOnlyItsOwnOrderedJournal() throws Exception {
    for (LogisticsDocumentType type :
        List.of(LogisticsDocumentType.RETURN, LogisticsDocumentType.SHIPMENT)) {
      List<UUID> ids = seedPagedDocuments(type, 2, LocalDate.of(2026, 9, 5));
      UUID documentId = ids.getFirst();
      String path =
          "/api/logistics/v1/" + type.name().toLowerCase() + "s/" + documentId + "/history";
      jdbc.update("update logistics_document set version=3 where id=?", documentId);
      jdbc.update(
          """
          update logistics_document_line set expected_contents_snapshot=?::jsonb,
            factual_contents_snapshot='{"contents":[]}'::jsonb,
            return_additional_contents_snapshot=?::jsonb
          where document_id=?
          """,
          "{\"contents\":[{\"equipmentId\":\"" + EQUIPMENT + "\",\"quantity\":2}]}",
          type == LogisticsDocumentType.RETURN
              ? "{\"equipmentConfirmed\":true,\"additionalEquipment\":[]}"
              : null,
          documentId);
      jdbc.update(
          """
          insert into event_stream_head(aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
          values (?,?,3,?,now())
          """,
          type.name(),
          documentId.toString(),
          UUID.randomUUID());
      for (long version : List.of(0L, 2L, 3L)) {
        jdbc.update(
            """
            insert into domain_event(event_id,aggregate_type,aggregate_id,aggregate_version,event_type,
              event_version,occurred_at,recorded_at,correlation_id,actor_ref,payload,payload_sha256,baseline)
            select ?,?,?,?, ?,1,?,now(),?,?::jsonb,payload,
              encode(sha256(convert_to(payload::text,'UTF8')),'hex'),?
            from (select '{"state":"DRAFT","resultCode":null}'::jsonb payload) fixture
            """,
            UUID.randomUUID(),
            type.name(),
            documentId.toString(),
            version,
            "logistics." + type.name().toLowerCase() + ".created.v1",
            version == 0 ? null : OffsetDateTime.parse("2026-09-05T09:00:00Z"),
            UUID.randomUUID(),
            "{\"subjectId\":\"" + MANAGER_2 + "\",\"principalType\":\"USER\"}",
            version == 0);
      }
      mvc.perform(get(path).param("size", "1").with(readOnlyViewer(MANAGER_1, "viewer")))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.documentId").value(documentId.toString()))
          .andExpect(jsonPath("$.documentVersion").value(3))
          .andExpect(jsonPath("$.events.length()").value(1))
          .andExpect(jsonPath("$.events[0].aggregateVersion").value(0))
          .andExpect(jsonPath("$.events[0].baseline").value(true))
          .andExpect(jsonPath("$.events[0].occurredAt").isEmpty())
          .andExpect(jsonPath("$.nextAfterVersion").value(0))
          .andExpect(jsonPath("$.lines[0].contentsBeforeOperation.contents[0].quantity").value(2))
          .andExpect(jsonPath("$.lines[0].contentsAfterOperation.contents.length()").value(0));
      mvc.perform(get(path).param("size", "1").param("afterVersion", "0").with(admin()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.events[0].aggregateVersion").value(2))
          .andExpect(jsonPath("$.events[0].recordedActor.subjectId").value(MANAGER_2.toString()))
          .andExpect(jsonPath("$.nextAfterVersion").value(2));
      mvc.perform(get(path).param("size", "1").param("afterVersion", "2").with(admin()))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.events[0].aggregateVersion").value(3))
          .andExpect(jsonPath("$.nextAfterVersion").isEmpty());
      MvcResult empty =
          mvc.perform(
                  get("/api/logistics/v1/"
                          + type.name().toLowerCase()
                          + "s/"
                          + ids.get(1)
                          + "/history")
                      .with(admin()))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.events.length()").value(0))
              .andReturn();
      JsonNode line =
          objectMapper.readTree(empty.getResponse().getContentAsString()).get("lines").get(0);
      assertThat(line.propertyNames())
          .containsExactlyInAnyOrder(
              "lineId",
              "assetId",
              "contentsBeforeOperation",
              "contentsAfterOperation",
              "returnAcceptance",
              "inventoryShipmentFurniture");
      assertThat(line.get("contentsBeforeOperation").isNull()).isTrue();
      assertThat(line.get("contentsAfterOperation").isNull()).isTrue();
      assertThat(line.get("returnAcceptance").isNull()).isTrue();
      assertThat(line.get("inventoryShipmentFurniture").isNull()).isTrue();
    }
    verify(dependencies, never()).readRentalItemSnapshot(any());
  }

  @Test
  void documentHistoryRequiresWarehouseReadAccessAndValidCursor() throws Exception {
    for (LogisticsDocumentType type :
        List.of(LogisticsDocumentType.RETURN, LogisticsDocumentType.SHIPMENT)) {
      UUID id = seedPagedDocuments(type, 1, LocalDate.of(2026, 9, 5)).getFirst();
      String path = "/api/logistics/v1/" + type.name().toLowerCase() + "s/" + id + "/history";
      mvc.perform(get(path)).andExpect(status().isUnauthorized());
      mvc.perform(get(path).param("size", "0").with(admin())).andExpect(status().isBadRequest());
      mvc.perform(get(path).param("size", "101").with(admin())).andExpect(status().isBadRequest());
      mvc.perform(get(path).param("afterVersion", "-2").with(admin()))
          .andExpect(status().isBadRequest());
      mvc.perform(
              get(path)
                  .with(
                      applicationUser(
                          MANAGER_1,
                          "VIEWER",
                          "viewer",
                          null,
                          "rwms.write",
                          true,
                          List.of(Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT")))))
          .andExpect(status().isForbidden());
      jdbc.update("update logistics_document set warehouse_id=? where id=?", WAREHOUSE_2, id);
      mvc.perform(get(path).with(readOnlyViewer(MANAGER_1, "viewer")))
          .andExpect(status().isForbidden());
      String wrongType = type == LogisticsDocumentType.RETURN ? "shipments" : "returns";
      mvc.perform(get("/api/logistics/v1/" + wrongType + "/" + id + "/history").with(admin()))
          .andExpect(status().isNotFound());
    }
  }

  @Test
  void cabinDocumentHistoryFiltersBeforePagingAndKeepsCancelledFacts() throws Exception {
    LocalDate date = LocalDate.of(2026, 9, 2);
    for (LogisticsDocumentType type :
        List.of(LogisticsDocumentType.SHIPMENT, LogisticsDocumentType.RETURN)) {
      List<UUID> ids = seedPagedDocuments(type, 55, date);
      for (int index = 0; index < 3; index++) {
        jdbc.update(
            "update logistics_document_line set asset_id = ? where document_id = ?",
            UNIT_1,
            ids.get(index));
      }
      jdbc.update(
          "update logistics_document set warehouse_id = ? where id = ?", WAREHOUSE_2, ids.get(2));
      jdbc.update(
          "update logistics_document set state = 'CANCELLED', scheduled_date = ? where id = ?",
          date.plusDays(1),
          ids.get(0));
      String path = "/api/logistics/v1/" + type.name().toLowerCase() + "s";

      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("assetId", UNIT_1.toString())
                  .param("size", "1")
                  .with(readOnlyViewer(MANAGER_1, "viewer")))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RWMS-Total-Elements", "2"))
          .andExpect(header().string("X-RWMS-Has-Next", "true"))
          .andExpect(jsonPath("$.length()").value(1))
          .andExpect(jsonPath("$[0].id").value(ids.get(1).toString()));

      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("assetId", UNIT_1.toString())
                  .param("size", "1")
                  .param("page", "1")
                  .with(admin()))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RWMS-Has-Next", "false"))
          .andExpect(jsonPath("$[0].id").value(ids.get(0).toString()))
          .andExpect(jsonPath("$[0].state").value("CANCELLED"));

      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("assetId", UNIT_1.toString())
                  .param("scheduledDate", date.plusDays(1).toString())
                  .with(admin()))
          .andExpect(status().isOk())
          .andExpect(header().string("X-RWMS-Total-Elements", "1"))
          .andExpect(jsonPath("$[0].id").value(ids.get(0).toString()));
    }
  }

  @Test
  void cabinHistoryDoesNotBroadenWarehouseAccessAndRejectsMalformedIdentity() throws Exception {
    for (String type : List.of("shipments", "returns")) {
      String path = "/api/logistics/v1/" + type;
      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_2.toString())
                  .param("assetId", UNIT_1.toString())
                  .with(readOnlyViewer(MANAGER_1, "viewer")))
          .andExpect(status().isForbidden());
      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("assetId", "not-a-uuid")
                  .with(admin()))
          .andExpect(status().isBadRequest());
      mvc.perform(
              get(path)
                  .param("warehouseId", WAREHOUSE_1.toString())
                  .param("assetId", UNIT_1.toString()))
          .andExpect(status().isUnauthorized());
    }
  }

  @Test
  void transferListIncludesBothWarehouseDirectionsOnlyForTheSelectedDay() throws Exception {
    LocalDate scheduledDate = LocalDate.of(2026, 9, 3);
    OffsetDateTime createdAt = OffsetDateTime.parse("2026-09-01T10:00:00Z");
    UUID outgoing = UUID.fromString("18000000-0000-0000-0000-000000000101");
    UUID incoming = UUID.fromString("18000000-0000-0000-0000-000000000102");
    UUID anotherDay = UUID.fromString("18000000-0000-0000-0000-000000000103");
    seedListedDocument(
        outgoing, WAREHOUSE_1, WAREHOUSE_2, scheduledDate, createdAt);
    seedListedDocument(
        incoming, WAREHOUSE_2, WAREHOUSE_1, scheduledDate, createdAt.plusSeconds(1));
    seedListedDocument(
        anotherDay, WAREHOUSE_1, WAREHOUSE_2, scheduledDate.plusDays(1), createdAt.plusSeconds(2));

    mvc.perform(
            get("/api/logistics/v1/transfers")
                .param("warehouseId", WAREHOUSE_1.toString())
                .param("scheduledDate", scheduledDate.toString())
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("X-RWMS-Total-Elements", "2"))
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].id").value(incoming.toString()))
        .andExpect(jsonPath("$[1].id").value(outgoing.toString()));
  }

  @Test
  void managersSeeOnlyOwnOrdersAndCannotUseDirectReadOrMutationWhileAdminSeesBoth()
      throws Exception {
    UUID first = createOrder(MANAGER_1, "manager-one", "Клиент один");
    UUID second = createOrder(MANAGER_2, "manager-two", "Клиент два");

    mvc.perform(get("/api/logistics/v1/orders").with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(first.toString()))
        .andExpect(jsonPath("$.content[0].managerId").value(MANAGER_1.toString()));

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", first)
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", first)
                .param("expectedVersion", "0")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    verify(dependencies, never()).releaseAllOrderUnits(any(), any(), any(), anyString());

    mvc.perform(get("/api/logistics/v1/orders").with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(2))
        .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(first)).exists())
        .andExpect(jsonPath("$.content[?(@.id == '%s')]".formatted(second)).exists());
    mvc.perform(get("/api/logistics/v1/orders/{orderId}", first).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.managerDisplayName").value("manager-one"))
        .andExpect(jsonPath("$.createdBy").value(MANAGER_1.toString()));
  }

  @Test
  void dedicatedManagerWebAndAndroidTokensCanUseClientAndOrderApis()
      throws Exception {
    MvcResult clientResult =
        mvc.perform(
                post("/api/logistics/v1/clients")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientType":"LEGAL_ENTITY",
                          "displayName":"ООО Dedicated Manager",
                          "phone":"+79990006666",
                          "contactPerson":"Менеджер"
                        }
                        """)
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andReturn();
    UUID clientId = UUID.fromString(json(clientResult).get("id").stringValue());

    MvcResult orderResult =
        mvc.perform(
                post("/api/logistics/v1/orders")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content("{\"clientId\":\"%s\"}".formatted(clientId))
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andReturn();
    UUID orderId = UUID.fromString(json(orderResult).get("id").stringValue());

    mvc.perform(
            get("/api/logistics/v1/clients/{clientId}", clientId)
                .with(androidManager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(clientId.toString()));
    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(androidManager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()))
        .andExpect(jsonPath("$.managerId").value(MANAGER_1.toString()));

    mvc.perform(
            get("/api/logistics/v1/returns")
                .param("warehouseId", WAREHOUSE_1.toString())
                .param("scheduledDate", "2026-09-02")
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isForbidden());
  }

  @Test
  void orderApisRejectLegacyUnknownAndWrongRoleDedicatedManagerTokens() throws Exception {
    mvc.perform(
            get("/api/logistics/v1/clients")
                .with(
                    applicationUser(
                        MANAGER_1,
                        "RENTAL_MANAGER",
                        "manager-one",
                        "rwms-panel",
                        "rwms.read rwms.write",
                        true,
                        List.of())))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/logistics/v1/orders")
                .with(
                    applicationUser(
                        MANAGER_1,
                        "RENTAL_MANAGER",
                        "manager-one",
                        "unknown-manager-client",
                        "rental.manage",
                        true,
                        List.of())))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/logistics/v1/orders")
                .with(
                    applicationUser(
                        ADMIN,
                        "SYSTEM_ADMIN",
                        "admin",
                        RENTAL_MANAGER_WEB_CLIENT_ID,
                        "rental.manage",
                        true,
                        List.of())))
        .andExpect(status().isForbidden());
  }

  @Test
  void normalizedClientCreationReplaysByPhoneAndKeepsCanonicalClient() throws Exception {
    UUID key = UUID.randomUUID();
    String request =
        """
        {"clientType":"INDIVIDUAL","displayName":"  Петров   А.В.  ","phone":"+79990000001"}
        """;
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.displayName").value("Петров А.В."));
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientType":"INDIVIDUAL","displayName":"петров а.в.","phone":"+79990000001"}
                    """)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.displayName").value("Петров А.В."));
    mvc.perform(
            get("/api/logistics/v1/clients")
                .param("type", "INDIVIDUAL")
                .param("search", "  ПЕТРОВ   а.в. ")
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    assertThat(jdbc.queryForObject("select count(*) from order_client", Long.class)).isOne();
  }

  @Test
  void administratorCreatesHistoricalShipmentForPastDateWithoutDriverTask() throws Exception {
    MvcResult clientResult =
        mvc.perform(
                post("/api/logistics/v1/clients")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientType": "LEGAL_ENTITY",
                          "displayName": "АО АПАТИТ",
                          "phone": "%s",
                          "contactPerson": "Контактное лицо"
                        }
                        """
                            .formatted(nextTestPhone()))
                    .with(admin()))
            .andExpect(status().isCreated())
            .andReturn();
    UUID clientId = UUID.fromString(json(clientResult).get("id").stringValue());
    UUID idempotencyKey = UUID.randomUUID();
    String historicalShipmentBody =
        """
        {
          "warehouseId": "%s",
          "rentalItemId": "%s",
          "expectedRentalItemVersion": 7,
          "clientId": "%s",
          "kind": "SHIPMENT",
          "occurredOn": "2023-05-17"
        }
        """
            .formatted(WAREHOUSE_1, UNIT_1, clientId);

    var historicalShipment =
        mvc.perform(
            post("/api/logistics/v1/historical-rental-movements")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(historicalShipmentBody)
                .with(admin()));
    assertThat(historicalShipment.andReturn().getResolvedException()).isNull();
    historicalShipment
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("PREPARING"))
        .andExpect(jsonPath("$.scheduledDate").value("2023-05-17"))
        .andExpect(jsonPath("$.historicalRentalImport").value(true))
        .andExpect(jsonPath("$.driverSnapshot").doesNotExist())
        .andExpect(jsonPath("$.driverWorkerId").doesNotExist());
    UUID documentId =
        UUID.fromString(json(historicalShipment.andReturn()).get("id").stringValue());
    long creationVersion = json(historicalShipment.andReturn()).get("version").longValue();

    mvc.perform(
            post("/api/logistics/v1/historical-rental-movements")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(historicalShipmentBody)
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.state").value("PREPARING"))
        .andExpect(jsonPath("$.scheduledDate").value("2023-05-17"))
        .andExpect(jsonPath("$.historicalRentalImport").value(true));

    when(dependencies.closeHistoricalShipment(
            any(), eq(documentId), eq(WAREHOUSE_1), eq(UNIT_1)))
        .thenReturn(
            new LogisticsDependencyGateway.HistoricalShipmentRepairClosure(
                documentId,
                WAREHOUSE_1,
                UNIT_1,
                7,
                "RENTED",
                List.of(),
                "ALREADY_RENTED"));
    assertThat(
            LogisticsExternalAttemptTestClaims.drainShipment(
                externalAttemptClaims, shipmentProcessor))
        .isOne();

    MvcResult shipped =
        mvc.perform(get("/api/logistics/v1/shipments/{documentId}", documentId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(documentId.toString()))
            .andExpect(jsonPath("$.state").value("SHIPPED"))
            .andExpect(jsonPath("$.partySnapshot").value("АО АПАТИТ"))
            .andExpect(jsonPath("$.scheduledDate").value("2023-05-17"))
            .andReturn();
    long shippedVersion = json(shipped).get("version").longValue();

    mvc.perform(
            post("/api/logistics/v1/shipments/{documentId}/cancel", documentId)
                .param("expectedVersion", Long.toString(shippedVersion))
                .header("Idempotency-Key", UUID.randomUUID())
                .with(admin()))
        .andExpect(status().isConflict());

    mvc.perform(
            post("/api/logistics/v1/historical-rental-movements")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(historicalShipmentBody)
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(creationVersion))
        .andExpect(jsonPath("$.state").value("PREPARING"));
    verify(dependencies, never()).readRentalItemSnapshot(UNIT_1);
    verify(dependencies, never())
        .acquireOperationLease(any(), any(), any(), anyLong(), any(), any());
    verify(dependencies, never())
        .applyFencedEffect(
            any(),
            any(),
            any(),
            anyLong(),
            any(),
            anyLong(),
            any(),
            any(),
            any(),
            any());

    UUID replacementClientId =
        createClient(MANAGER_1, "manager-one", "ООО Исправленный клиент");
    UUID correctedDriverWorkerId = UUID.randomUUID();
    UUID updateKey = UUID.randomUUID();
    String updateBody =
        """
        {
          "expectedVersion": %d,
          "rentalItemId": "%s",
          "clientId": "%s",
          "driverSnapshot": "Иванов Иван",
          "driverWorkerId": "%s",
          "occurredOn": "2023-05-16"
        }
        """
            .formatted(shippedVersion, UNIT_1, replacementClientId, correctedDriverWorkerId);
    mvc.perform(
            put("/api/logistics/v1/historical-rental-movements/{documentId}", documentId)
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(documentId.toString()))
        .andExpect(jsonPath("$.state").value("SHIPPED"))
        .andExpect(jsonPath("$.clientId").value(replacementClientId.toString()))
        .andExpect(jsonPath("$.partySnapshot").value("ООО Исправленный клиент"))
        .andExpect(jsonPath("$.driverSnapshot").value("Иванов Иван"))
        .andExpect(jsonPath("$.driverWorkerId").value(correctedDriverWorkerId.toString()))
        .andExpect(jsonPath("$.scheduledDate").value("2023-05-16"));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE_1), any(OffsetDateTime.class)))
        .thenThrow(
            new dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException(
                dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException.FailureKind.TRANSIENT,
                "warehouse time is unavailable after the accepted update"));
    mvc.perform(
            put("/api/logistics/v1/historical-rental-movements/{documentId}", documentId)
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(documentId.toString()));

    assertThat(
            jdbc.queryForObject(
                "select tenant_snapshot from logistics_document_line where document_id=?",
                String.class,
                documentId))
        .isEqualTo("ООО Исправленный клиент");

    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from logistics_idempotency_record
                where operation_name='CREATE_HISTORICAL_RENTAL_MOVEMENT'
                  and idempotency_key=?
                """,
                Long.class,
                idempotencyKey))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from logistics_idempotency_record
                where operation_name='UPDATE_HISTORICAL_RENTAL_MOVEMENT'
                  and idempotency_key=?
                """,
                Long.class,
                updateKey))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from logistics_document d
                join logistics_document_line l on l.document_id=d.id
                where d.document_type='SHIPMENT'
                  and d.historical_rental_import=true
                  and l.asset_id=?
                """,
                Long.class,
                UNIT_1))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from logistics_external_attempt
                where operation_type='SHIPMENT_HISTORICAL_MAINTENANCE_CLOSE'
                """,
                Long.class))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from driver_logistics_task", Long.class))
        .isZero();
  }

  @Test
  void administratorCancelsFailedHistoricalShipmentAndResolvesItsAudit() throws Exception {
    UUID clientId = createClient(MANAGER_1, "manager-one", "ООО Ошибочная отгрузка");
    UUID driverWorkerId = UUID.randomUUID();
    MvcResult created =
        mvc.perform(
                post("/api/logistics/v1/historical-rental-movements")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "warehouseId": "%s",
                          "rentalItemId": "%s",
                          "expectedRentalItemVersion": 7,
                          "clientId": "%s",
                          "driverSnapshot": "Петров Пётр",
                          "driverWorkerId": "%s",
                          "kind": "SHIPMENT",
                          "occurredOn": "2023-05-17"
                        }
                        """
                            .formatted(WAREHOUSE_1, UNIT_1, clientId, driverWorkerId))
                    .with(admin()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PREPARING"))
            .andExpect(jsonPath("$.driverSnapshot").value("Петров Пётр"))
            .andExpect(jsonPath("$.driverWorkerId").value(driverWorkerId.toString()))
            .andReturn();
    UUID documentId = UUID.fromString(json(created).get("id").stringValue());

    when(dependencies.closeHistoricalShipment(
            any(), eq(documentId), eq(WAREHOUSE_1), eq(UNIT_1)))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.CONFIGURATION,
                "maintenance dependency is unavailable"));
    assertThat(
            LogisticsExternalAttemptTestClaims.drainShipment(
                externalAttemptClaims, shipmentProcessor))
        .isOne();

    MvcResult failed =
        mvc.perform(get("/api/logistics/v1/shipments/{documentId}", documentId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("RECONCILIATION_REQUIRED"))
            .andReturn();
    long failedVersion = json(failed).get("version").longValue();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_reconciliation where document_id=? and"
                    + " state='OPEN'",
                Long.class,
                documentId))
        .isOne();

    mvc.perform(
            post("/api/logistics/v1/shipments/{documentId}/cancel", documentId)
                .param("expectedVersion", Long.toString(failedVersion))
                .header("Idempotency-Key", UUID.randomUUID())
                .with(admin()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("CANCELLED"))
        .andExpect(jsonPath("$.historicalRentalImport").value(true));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_reconciliation where document_id=? and"
                    + " state='RESOLVED'",
                Long.class,
                documentId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select resolution_reason from logistics_reconciliation where document_id=?",
                String.class,
                documentId))
        .isEqualTo("HISTORICAL_SHIPMENT_CANCELLED_BY_OPERATOR");
    assertThat(jdbc.queryForObject("select count(*) from driver_logistics_task", Long.class))
        .isZero();
  }

  @Test
  void administratorCancelsHistoricalShipmentAfterRecoveringLostLeaseResponse() throws Exception {
    UUID clientId = createClient(MANAGER_1, "manager-one", "ООО Потерянный ответ аренды");
    MvcResult created =
        mvc.perform(
                post("/api/logistics/v1/historical-rental-movements")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "warehouseId": "%s",
                          "rentalItemId": "%s",
                          "expectedRentalItemVersion": 7,
                          "clientId": "%s",
                          "kind": "SHIPMENT",
                          "occurredOn": "2023-05-17"
                        }
                        """
                            .formatted(WAREHOUSE_1, UNIT_1, clientId))
                    .with(admin()))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("PREPARING"))
            .andReturn();
    UUID documentId = UUID.fromString(json(created).get("id").stringValue());
    UUID lineId = UUID.fromString(json(created).get("lines").get(0).get("id").stringValue());
    UUID leaseId = UUID.randomUUID();

    when(dependencies.closeHistoricalShipment(
            any(), eq(documentId), eq(WAREHOUSE_1), eq(UNIT_1)))
        .thenReturn(
            new LogisticsDependencyGateway.HistoricalShipmentRepairClosure(
                documentId,
                WAREHOUSE_1,
                UNIT_1,
                7,
                "FREE",
                List.of(),
                "NOT_REQUIRED"));
    when(dependencies.acquireOperationLease(
            any(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(UNIT_1),
            eq(7L),
            eq(documentId),
            eq(lineId)))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.CONFIGURATION,
                "lease response was lost"))
        .thenReturn(
            new LogisticsDependencyGateway.OperationLease(
                leaseId,
                0,
                UNIT_1,
                41,
                "ACTIVE",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)));
    when(dependencies.releaseOperationLease(
            any(),
            eq(leaseId),
            eq(0L),
            eq(41L),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(documentId),
            eq(lineId)))
        .thenReturn(
            new LogisticsDependencyGateway.OperationLease(
                leaseId,
                1,
                UNIT_1,
                41,
                "EXPIRED",
                OffsetDateTime.now(ZoneOffset.UTC).minusMinutes(1)));

    assertThat(
            LogisticsExternalAttemptTestClaims.drainShipment(
                externalAttemptClaims, shipmentProcessor))
        .isEqualTo(3);
    MvcResult failed =
        mvc.perform(get("/api/logistics/v1/shipments/{documentId}", documentId).with(admin()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.state").value("RECONCILIATION_REQUIRED"))
            .andReturn();

    mvc.perform(
            post("/api/logistics/v1/shipments/{documentId}/cancel", documentId)
                .param(
                    "expectedVersion",
                    Long.toString(json(failed).get("version").longValue()))
                .header("Idempotency-Key", UUID.randomUUID())
                .with(admin()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("CANCELLING"));

    assertThat(
            LogisticsExternalAttemptTestClaims.drainShipment(
                externalAttemptClaims, shipmentProcessor))
        .isEqualTo(2);
    mvc.perform(get("/api/logistics/v1/shipments/{documentId}", documentId).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("CANCELLED"));

    ArgumentCaptor<UUID> operationIds = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .acquireOperationLease(
            operationIds.capture(),
            eq(LogisticsDependencyGateway.LogisticsOwnerType.LOGISTICS_SHIPMENT),
            eq(UNIT_1),
            eq(7L),
            eq(documentId),
            eq(lineId));
    assertThat(operationIds.getAllValues()).hasSize(2);
    assertThat(operationIds.getAllValues().get(1)).isEqualTo(operationIds.getAllValues().get(0));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_guard where document_id=? and"
                    + " guard_state='RELEASED'",
                Long.class,
                documentId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_reconciliation where document_id=? and"
                    + " state='RESOLVED'",
                Long.class,
                documentId))
        .isOne();
  }

  @Test
  void emptyClientSearchReturnsTheMostRecentCounterpartiesFirst() throws Exception {
    List<UUID> clientIds =
        List.of(
            createClient(MANAGER_1, "manager-one", "Контрагент 1"),
            createClient(MANAGER_1, "manager-one", "Контрагент 2"),
            createClient(MANAGER_1, "manager-one", "Контрагент 3"),
            createClient(MANAGER_1, "manager-one", "Контрагент 4"),
            createClient(MANAGER_1, "manager-one", "Контрагент 5"),
            createClient(MANAGER_1, "manager-one", "Контрагент 6"));
    for (int index = 0; index < clientIds.size(); index++) {
      jdbc.update(
          "update order_client set created_at=? where id=?",
          OffsetDateTime.of(2026, 7, 22, 10, index, 0, 0, ZoneOffset.UTC),
          clientIds.get(index));
    }

    mvc.perform(
            get("/api/logistics/v1/clients")
                .param("search", "")
                .param("page", "0")
                .param("size", "5")
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(6))
        .andExpect(jsonPath("$.content.length()").value(5))
        .andExpect(jsonPath("$.content[0].displayName").value("Контрагент 6"))
        .andExpect(jsonPath("$.content[4].displayName").value("Контрагент 2"));
  }

  @Test
  void legalEntityRequiresContactAndManagerSnapshotComesFromTheWriteActor() throws Exception {
    String phone = nextTestPhone();
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientType":"LEGAL_ENTITY","displayName":"ООО Петров","phone":"%s"}
                    """
                        .formatted(phone))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());

    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientType":"LEGAL_ENTITY",
                      "displayName":"ООО Петров",
                      "phone":"%s",
                      "contactPerson":"Пётр Петров",
                      "email":"OWNER@EXAMPLE.TEST",
                      "comment":"Позвонить заранее",
                      "source":"Выставка"
                    }
                    """
                        .formatted(phone))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("LEGAL_ENTITY"))
        .andExpect(jsonPath("$.contactPerson").value("Пётр Петров"))
        .andExpect(jsonPath("$.email").value("owner@example.test"))
        .andExpect(jsonPath("$.responsibleManagerId").value(MANAGER_1.toString()))
        .andExpect(jsonPath("$.responsibleManagerDisplayName").value("manager-one"))
        .andExpect(jsonPath("$.comment").value("Позвонить заранее"))
        .andExpect(jsonPath("$.source").value("Выставка"));
  }

  @Test
  void clientPhoneAcceptsHumanFormattingAndReturnsCanonicalE164() throws Exception {
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientType":"INDIVIDUAL",
                      "displayName":"Покупатель с форматированным телефоном",
                      "phone":"8 (999) 555-44-33"
                    }
                    """)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.phone").value("+79995554433"));

    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientType":"INDIVIDUAL",
                      "displayName":"Покупатель с некорректным телефоном",
                      "phone":"+7 (999) CALL-ME"
                    }
                    """)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LOGISTICS_INVALID_REQUEST"));
  }

  @Test
  void globalClientDedupeNeverDisclosesAnotherManagersClient() throws Exception {
    String phone = nextTestPhone();
    MvcResult first =
        mvc.perform(
                post("/api/logistics/v1/clients")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientType":"INDIVIDUAL","displayName":"Скрытый клиент","phone":"%s"}
                        """
                            .formatted(phone))
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andReturn();
    UUID clientId = UUID.fromString(json(first).get("id").stringValue());

    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientType":"INDIVIDUAL","displayName":"Чужое имя","phone":"%s"}
                    """
                        .formatted(phone))
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("CLIENT_ALREADY_EXISTS"))
        .andExpect(jsonPath("$.id").doesNotExist());
    mvc.perform(
            get("/api/logistics/v1/clients/{clientId}", clientId)
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
    mvc.perform(
            get("/api/logistics/v1/clients")
                .param("search", phone)
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));
  }

  @Test
  void clientDetailAndOrderPageApplyTheSameNoDisclosureRules() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент с заказами");
    UUID clientId = orderClientId(orderId);

    mvc.perform(
            get("/api/logistics/v1/clients/{clientId}", clientId)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"0\""))
        .andExpect(jsonPath("$.id").value(clientId.toString()))
        .andExpect(jsonPath("$.responsibleManagerId").value(MANAGER_1.toString()));
    mvc.perform(
            get("/api/logistics/v1/clients/{clientId}/orders", clientId)
                .param("page", "0")
                .param("size", "10")
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(orderId.toString()));
    mvc.perform(
            get("/api/logistics/v1/clients/{clientId}/orders", clientId)
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));
  }

  @Test
  void scheduledShipmentStopsOrdinarySavedOrderEditingBeforeTheTripStarts() throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент сохранённого заказа");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 2L));
    UUID idempotencyKey = UUID.randomUUID();

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", idempotencyKey)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"3\""))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.status").value("SAVED"))
        .andExpect(jsonPath("$.unitCount").value(1));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", idempotencyKey)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(3));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"3\""))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.status").value("SAVED"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where rental_order_id=? and"
                    + " document_type='SHIPMENT'",
                Long.class,
                orderId))
        .isZero();

    createRentalShipment(orderId, MANAGER_1, 3, UNIT_1, shipmentDate, "Водитель");

    mvc.perform(
            get("/api/logistics/v1/shipments")
                .param("warehouseId", WAREHOUSE_1.toString())
                .param("scheduledDate", shipmentDate)
                .with(panelRentalManager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].state").value("DRAFT"))
        .andExpect(jsonPath("$[0].scheduledDate").value(shipmentDate))
        .andExpect(jsonPath("$[0].driverSnapshot").value("Водитель"))
        .andExpect(jsonPath("$[0].rentalOrderId").value(orderId.toString()))
        .andExpect(jsonPath("$[0].lines.length()").value(1))
        .andExpect(jsonPath("$[0].lines[0].assetId").value(UNIT_1.toString()))
        .andExpect(jsonPath("$[0].lines[0].tenantSnapshot").value("Клиент сохранённого заказа"));

    mvc.perform(
            get("/api/logistics/v1/orders")
                .param("status", "SAVED")
                .param("clientType", "LEGAL_ENTITY")
                .param("warehouseId", WAREHOUSE_1.toString())
                .param("createdFrom", "2020-01-01T00:00:00Z")
                .param("createdTo", "2030-01-01T00:00:00Z")
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            get("/api/logistics/v1/orders")
                .param("status", "DRAFT")
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(0));

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(3, orderClientId(orderId)))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_EDITABLE"));

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"))
        .andExpect(jsonPath("$.permissions.canEdit").value(false));

    assertAuditCount(orderId, "ORDER_SAVED", 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where rental_order_id=? and"
                    + " document_type='SHIPMENT'",
                Long.class,
                orderId))
        .isOne();
  }

  @Test
  void exactRentalShipmentReplaySucceedsWithoutAdmissionDependencyAndAlteredPayloadConflicts()
      throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент replay отгрузки");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 2L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk());

    UUID idempotencyKey = UUID.randomUUID();
    String request = rentalShipmentBody(3, "Водитель replay", shipmentDate, UNIT_1);
    MvcResult created =
        mvc.perform(
                post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andReturn();
    UUID shipmentId = UUID.fromString(json(created).get("id").stringValue());

    when(dependencies.productionReady()).thenReturn(false);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(shipmentId.toString()));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(rentalShipmentBody(3, "Другой водитель", shipmentDate, UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where rental_order_id=? and"
                    + " document_type='SHIPMENT'",
                Long.class,
                orderId))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where operation_id=? and warehouse_id=?
                """,
                shipmentId,
                WAREHOUSE_1))
        .containsEntry("admission_direction", "OUTGOING")
        .containsEntry("admission_warehouse_version", 23L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark_outbox where operation_id=?",
                Long.class,
                shipmentId))
        .isOne();
    verify(dependencies, times(1))
        .warehouseAdmission(
            WAREHOUSE_1, LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING);
    verify(dependencies, times(1)).warehouseTimeZoneAt(eq(WAREHOUSE_1), any(OffsetDateTime.class));
  }

  @Test
  void startingShipmentLocksTheSavedBookingAgainstFurtherEdits() throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент начатой отгрузки");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 1L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"));

    JsonNode shipment =
        createRentalShipment(orderId, MANAGER_1, 3, UNIT_1, shipmentDate, "Водитель");
    UUID shipmentId = UUID.fromString(shipment.get("id").stringValue());
    long shipmentVersion =
        jdbc.queryForObject(
            "select version from logistics_document where id=?", Long.class, shipmentId);
    mvc.perform(
            put("/api/logistics/v1/shipments/{documentId}/plan", shipmentId)
                .param("expectedVersion", Long.toString(shipmentVersion))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"driverSnapshot":"Водитель","scheduledDate":"%s"}
                    """
                        .formatted(shipmentDate))
                .with(panelRentalManager(MANAGER_1, "manager-one")))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("PREPARING"));

    long orderVersion =
        jdbc.queryForObject("select version from rental_order where id=?", Long.class, orderId);
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(orderVersion, orderClientId(orderId)))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_EDITABLE"));
  }

  @Test
  void warehouseManagerCanReplaceAnUnstartedCabinAfterAnotherTripHasDeparted() throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент замены по ходке");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    addUnit(orderId, MANAGER_1, UNIT_2, 2);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 1L, UNIT_2, 1L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk());
    JsonNode firstShipment =
        createRentalShipment(orderId, MANAGER_1, 4, UNIT_1, shipmentDate, "Водитель 1");
    long currentOrderVersion =
        jdbc.queryForObject("select version from rental_order where id=?", Long.class, orderId);
    JsonNode secondShipment =
        createRentalShipment(
            orderId, MANAGER_1, currentOrderVersion, UNIT_2, shipmentDate, "Водитель 2");
    UUID firstShipmentId = UUID.fromString(firstShipment.get("id").stringValue());
    UUID secondShipmentId = UUID.fromString(secondShipment.get("id").stringValue());
    JwtRequestPostProcessor warehouseManager =
        user(
            UUID.randomUUID(),
            "WAREHOUSE_MANAGER",
            "warehouse-manager",
            List.of(Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT")));

    mvc.perform(get("/api/logistics/v1/orders/{orderId}", orderId).with(warehouseManager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.canEdit").value(false))
        .andExpect(jsonPath("$.permissions.canReplaceUnits").value(true));

    jdbc.update("update logistics_document set state='DEPARTING' where id=?", firstShipmentId);
    jdbc.update(
        "update logistics_document_line set state='DEPARTING' where document_id=?",
        firstShipmentId);
    mvc.perform(get("/api/logistics/v1/orders/{orderId}", orderId).with(warehouseManager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.canEdit").value(false))
        .andExpect(jsonPath("$.permissions.canReplaceUnits").value(true));

    jdbc.update("update logistics_document set state='DEPARTING' where id=?", secondShipmentId);
    jdbc.update(
        "update logistics_document_line set state='DEPARTING' where document_id=?",
        secondShipmentId);
    mvc.perform(get("/api/logistics/v1/orders/{orderId}", orderId).with(warehouseManager))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.canEdit").value(false))
        .andExpect(jsonPath("$.permissions.canReplaceUnits").value(false));
  }

  @Test
  void createdShipmentFurnitureTaskLocksTheSavedBookingAgainstFurtherEdits() throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент задания на мебель");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 1L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"));

    JsonNode shipment =
        createRentalShipment(orderId, MANAGER_1, 3, UNIT_1, shipmentDate, "Водитель");
    UUID shipmentId = UUID.fromString(shipment.get("id").stringValue());
    linkFurnitureMovementTask(shipmentId, UNIT_1);

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.canEdit").value(false));

    long orderVersion =
        jdbc.queryForObject("select version from rental_order where id=?", Long.class, orderId);
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(orderVersion, orderClientId(orderId)))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_EDITABLE"));
  }

  @Test
  void savedAndFulfilledOrdersExposeServerAuthorizedRentalTermExtensionAffordance()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент продления аренды");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 1L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.canExtendRentalTerms").value(true));

    jdbc.update("update rental_order set status='FULFILLED' where id=?", orderId);

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("FULFILLED"))
        .andExpect(jsonPath("$.permissions.canEdit").value(false))
        .andExpect(jsonPath("$.permissions.canExtendRentalTerms").value(true));
    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(readOnlyViewer(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.permissions.canExtendRentalTerms").value(false));
    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(managerWithoutWarehouse(MANAGER_1, "manager-one")))
        .andExpect(status().isForbidden());
  }

  @Test
  void clientSelectedRentalTermsRequireACompleteDraftVectorAndKeepPartialShipmentDatesIndependent()
      throws Exception {
    LocalDate firstDate = LocalDate.parse(futureDate(1));
    LocalDate secondDate = firstDate.plusDays(7);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент частичных отгрузок");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    addUnit(orderId, MANAGER_1, UNIT_2, 2);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_RENTAL_TERMS_REQUIRED"));

    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 2L, UNIT_2, 3L));
    assertThat(
            jdbc.queryForObject(
                "select rental_months from rental_order_unit_term where order_id=? and"
                    + " rental_item_id=?",
                Long.class,
                orderId,
                UNIT_1))
        .isEqualTo(2L);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.status").value("SAVED"));

    JsonNode firstShipment =
        json(
            mvc.perform(
                    post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            rentalShipmentBody(
                                4, "Вне желаемого окна", firstDate.plusDays(40).toString(), UNIT_1))
                        .with(manager(MANAGER_1, "manager-one")))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.scheduledDate").value(firstDate.plusDays(40).toString()))
                .andReturn());
    JsonNode afterFirstShipment =
        json(
            mvc.perform(
                    get("/api/logistics/v1/orders/{orderId}", orderId)
                        .with(manager(MANAGER_1, "manager-one")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(rentalTerm(afterFirstShipment, UNIT_1).get("shipmentDate").stringValue())
        .isEqualTo(firstDate.plusDays(40).toString());
    assertThat(rentalTerm(afterFirstShipment, UNIT_1).get("returnDate").stringValue())
        .isEqualTo(firstDate.plusDays(40).plusMonths(2).toString());
    assertThat(rentalTerm(afterFirstShipment, UNIT_2).get("shipmentDate").isNull()).isTrue();
    assertThat(afterFirstShipment.get("movements")).hasSize(1);
    assertThat(afterFirstShipment.at("/movements/0/documentId").stringValue())
        .isEqualTo(firstShipment.get("id").stringValue());
    assertThat(afterFirstShipment.at("/movements/0/documentType").stringValue())
        .isEqualTo("SHIPMENT");
    assertThat(afterFirstShipment.at("/movements/0/scheduledDate").stringValue())
        .isEqualTo(firstDate.plusDays(40).toString());
    assertThat(afterFirstShipment.at("/movements/0/cabins/0/rentalItemId").stringValue())
        .isEqualTo(UNIT_1.toString());

    createRentalShipment(orderId, MANAGER_1, 4, UNIT_2, secondDate.toString(), "Водитель 2");
    JsonNode afterSecondShipment =
        json(
            mvc.perform(
                    get("/api/logistics/v1/orders/{orderId}", orderId)
                        .with(manager(MANAGER_1, "manager-one")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(rentalTerm(afterSecondShipment, UNIT_2).get("shipmentDate").stringValue())
        .isEqualTo(secondDate.toString());
    assertThat(rentalTerm(afterSecondShipment, UNIT_2).get("returnDate").stringValue())
        .isEqualTo(secondDate.plusMonths(3).toString());

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion": 4,
                      "driverSnapshot": "Повтор",
                      "scheduledDate": "%s",
                      "unitIds": ["%s"]
                    }
                    """
                        .formatted(secondDate.plusDays(1), UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict());

    jdbc.update(
        "update logistics_document set state='SHIPPED' where id=?",
        UUID.fromString(firstShipment.get("id").stringValue()));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/rental-terms/extend", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion": 4,
                      "terms": [{"unitId":"%s", "additionalMonths":1}]
                    }
                    """
                        .formatted(UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(5));
    assertThat(
            jdbc.queryForObject(
                "select rental_months from rental_order_unit_term where order_id=? and"
                    + " rental_item_id=?",
                Long.class,
                orderId,
                UNIT_1))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select return_date::text from rental_order_unit_term where order_id=? and"
                    + " rental_item_id=?",
                String.class,
                orderId,
                UNIT_1))
        .isEqualTo(firstDate.plusDays(40).plusMonths(3).toString());
  }

  @Test
  void savedOrderShipmentRejectsMoreCabinsThanTheWarehouseTaskCapBeforeCreatingTheDocument()
      throws Exception {
    LocalDate shipmentDate = LocalDate.parse(futureDate(1));
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент лимита отгрузки");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    addUnit(orderId, MANAGER_1, UNIT_2, 2);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 2L, UNIT_2, 3L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4));
    jdbc.update(
        """
        insert into shipment_task_settings(
          warehouse_id, version, max_cabins_per_shipment_task, updated_by_subject_id, updated_at)
        values (?, 0, 1, ?, clock_timestamp())
        """,
        WAREHOUSE_1,
        MANAGER_1);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion": 4,
                      "driverSnapshot": "Водитель",
                      "scheduledDate": "%s",
                      "unitIds": ["%s", "%s"]
                    }
                    """
                        .formatted(shipmentDate, UNIT_1, UNIT_2))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"))
        .andExpect(
            jsonPath("$.detail").value(org.hamcrest.Matchers.containsString("больше 1 бытовок")));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where rental_order_id=?",
                Long.class,
                orderId))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark_outbox where operation_id=?",
                Long.class,
                orderId))
        .isZero();
  }

  @Test
  void warehouseShipmentTaskSettingsUseReadManageScopesAndOptimisticVersioning() throws Exception {
    mvc.perform(
            get("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings", WAREHOUSE_1)
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value(WAREHOUSE_1.toString()))
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.maxCabinsPerShipmentTask").value(1))
        .andExpect(jsonPath("$.updatedBy").value(ADMIN.toString()));

    mvc.perform(
            put("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings", WAREHOUSE_1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"maxCabinsPerShipmentTask\":3}")
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value(WAREHOUSE_1.toString()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.maxCabinsPerShipmentTask").value(3));

    mvc.perform(
            get("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings", WAREHOUSE_1)
                .with(panelRentalManager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.maxCabinsPerShipmentTask").value(3));
    mvc.perform(
            put("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings", WAREHOUSE_1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"maxCabinsPerShipmentTask\":4}")
                .with(panelRentalManager(MANAGER_1, "manager-one")))
        .andExpect(status().isForbidden());
    mvc.perform(
            put("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings", WAREHOUSE_1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0,\"maxCabinsPerShipmentTask\":4}")
                .with(admin()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));
    mvc.perform(
            put("/api/logistics/v1/warehouses/{warehouseId}/shipment-task-settings", WAREHOUSE_1)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"maxCabinsPerShipmentTask\":101}")
                .with(admin()))
        .andExpect(status().isBadRequest());
  }

  @Test
  void soleProprietorClientTypeIsPersistedAndReturned() throws Exception {
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientType":"SOLE_PROPRIETOR",
                      "displayName":"ИП Петров",
                      "phone":"+79990000001",
                      "contactPerson":"Пётр Петров"
                    }
                    """)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.type").value("SOLE_PROPRIETOR"))
        .andExpect(jsonPath("$.displayName").value("ИП Петров"))
        .andExpect(jsonPath("$.phone").value("+79990000001"))
        .andExpect(jsonPath("$.contactPerson").value("Пётр Петров"));
    assertThat(
            jdbc.queryForMap(
                "select client_type,display_name,phone,contact_person from order_client"))
        .containsEntry("client_type", "SOLE_PROPRIETOR")
        .containsEntry("display_name", "ИП Петров")
        .containsEntry("phone", "+79990000001")
        .containsEntry("contact_person", "Пётр Петров");
  }

  @Test
  void existingClientOrderCreationReusesTheClientRow() throws Exception {
    MvcResult clientResult =
        mvc.perform(
                post("/api/logistics/v1/clients")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"clientType":"LEGAL_ENTITY","displayName":"ООО Существующий","phone":"+79990000001","contactPerson":"Иван Петров"}
                        """)
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andReturn();
    UUID clientId = UUID.fromString(json(clientResult).get("id").stringValue());

    mvc.perform(
            post("/api/logistics/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientId":"%s"}
                    """
                        .formatted(clientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.client.id").value(clientId.toString()));
    assertThat(jdbc.queryForObject("select count(*) from order_client", Long.class)).isOne();
  }

  @Test
  void orderCreateAndManagerEditRejectMovedClientDeliveryFieldsAndPreserveConfirmedFacts()
      throws Exception {
    UUID clientId = createClient(MANAGER_1, "manager-one", "Клиент доставки");
    UUID createKey = UUID.randomUUID();
    LocalDate firstDate = LocalDate.now(ZoneOffset.UTC).plusDays(2);
    String createBody =
        """
        {
          "clientId":"%s",
          "contactPhone":"+7 (999) 111-22-33",
          "comment":"Въезд со двора"
        }
        """
            .formatted(clientId);
    MvcResult created =
        mvc.perform(
                post("/api/logistics/v1/orders")
                    .header("Idempotency-Key", createKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(createBody)
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deliveryAddress").isEmpty())
            .andExpect(jsonPath("$.latitude").isEmpty())
            .andExpect(jsonPath("$.longitude").isEmpty())
            .andExpect(jsonPath("$.contactPhone").value("+79991112233"))
            .andExpect(jsonPath("$.comment").value("Въезд со двора"))
            .andExpect(jsonPath("$.desiredDeliveryWindows.length()").value(0))
            .andReturn();
    UUID orderId = UUID.fromString(json(created).get("id").stringValue());
    mvc.perform(
            post("/api/logistics/v1/orders")
                .header("Idempotency-Key", createKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(createBody)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(orderId.toString()));

    seedClientDeliveryDetails(orderId, "Москва, Первая улица, 10");
    seedClientDesiredDeliveryWindow(orderId, firstDate);

    UUID updateKey = UUID.randomUUID();
    String updateBody =
        """
        {
          "expectedVersion":0,
          "clientId":"%s",
          "contactPhone":"8 (999) 222-33-44"
        }
        """
            .formatted(clientId);
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.deliveryAddress").value("Москва, Первая улица, 10"))
        .andExpect(jsonPath("$.latitude").value(55.751234))
        .andExpect(jsonPath("$.longitude").value(37.621234))
        .andExpect(jsonPath("$.contactPhone").value("+79992223344"))
        .andExpect(
            jsonPath("$.desiredDeliveryWindows[0].endDate").value(firstDate.toString()))
        .andExpect(jsonPath("$.desiredDeliveryWindows[0].timeFrom").doesNotExist())
        .andExpect(jsonPath("$.additionalContacts[0].name").value("Иван Контакт"))
        .andExpect(jsonPath("$.comment").isEmpty());
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", updateKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"clientId":"%s","latitude":55.0}
                    """
                        .formatted(clientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/logistics/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientId":"%s","deliveryAddress":"Москва, запрещённое поле"}
                    """
                        .formatted(clientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            post("/api/logistics/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "clientId":"%s",
                      "contactPhone":"+7ABC9991112233"
                    }
                    """
                        .formatted(clientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LOGISTICS_INVALID_REQUEST"));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion":1,
                      "clientId":"%s",
                      "contactPhone":"+7ABC9992223344"
                    }
                    """
                        .formatted(clientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LOGISTICS_INVALID_REQUEST"));
    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.contactPhone").value("+79992223344"));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion":1,
                      "clientId":"%s",
                      "desiredDeliveryWindows":[
                        {"startDate":"%s","endDate":"%s"},
                        {"startDate":"%s","endDate":"%s"}
                      ]
                    }
                    """
                        .formatted(clientId, firstDate, firstDate, firstDate, firstDate))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("LOGISTICS_INVALID_REQUEST"));
    mvc.perform(
            post("/api/logistics/v1/orders")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientId":"%s","acceptableDeliveryDates":["%s"]}
                    """
                        .formatted(clientId, firstDate))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());
  }

  @Test
  void shipmentRequiresClientConfirmedAddressAndDateBeforeDateOnlyPlanning()
      throws Exception {
    UUID clientId = createClient(MANAGER_1, "manager-one", "Клиент черновика");
    String scheduledDate = futureDate(1);
    MvcResult draft =
        mvc.perform(
                post("/api/logistics/v1/orders")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        "{\"clientId\":\"%s\",\"contactPhone\":\"+79990000000\"}"
                            .formatted(clientId))
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.deliveryAddress").isEmpty())
            .andExpect(jsonPath("$.desiredDeliveryWindows.length()").value(0))
            .andReturn();
    UUID orderId = UUID.fromString(json(draft).get("id").stringValue());
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "1")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_DELIVERY_DETAILS_REQUIRED"));
    seedClientDeliveryDetails(orderId, "Москва, Тестовая улица, 1");
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    seedClientSelectedRentalTerms(orderId, Map.of(UNIT_1, 1L));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"))
        .andExpect(jsonPath("$.desiredDeliveryWindows.length()").value(0));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(rentalShipmentBody(3, "Водитель", scheduledDate, UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_DELIVERY_PREFERENCES_REQUIRED"));
    seedClientDesiredDeliveryWindow(orderId);
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion":3,
                      "driverSnapshot":"Водитель",
                      "scheduledDate":"%s",
                      "unitIds":["%s"]
                    }
                    """
                        .formatted(scheduledDate, UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.scheduledDate").value(scheduledDate))
        .andExpect(jsonPath("$.scheduledTime").doesNotExist());
  }

  @Test
  void updateOrderChangesClientAuditsAndReplaysExactlyOnce() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент до изменения");
    UUID nextClientId = createClient(MANAGER_1, "manager-one", "Клиент после изменения");
    UUID key = UUID.randomUUID();
    String body = updateOrderBody(0, nextClientId);

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.client.id").value(nextClientId.toString()));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.client.id").value(nextClientId.toString()));

    UUID anotherClientId = createClient(MANAGER_1, "manager-one", "Третий клиент");
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(1, anotherClientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

    assertThat(orderClientId(orderId)).isEqualTo(nextClientId);
    assertThat(
            jdbc.queryForObject("select version from rental_order where id=?", Long.class, orderId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                """
                select previous_values ->> 'displayName'
                from rental_order_audit_event
                where order_id=? and event_type='CLIENT_SELECTED' and previous_values is not null
                """,
                String.class,
                orderId))
        .isEqualTo("Клиент до изменения");
    assertThat(
            jdbc.queryForObject(
                """
                select new_values ->> 'displayName'
                from rental_order_audit_event
                where order_id=? and event_type='CLIENT_SELECTED' and previous_values is not null
                """,
                String.class,
                orderId))
        .isEqualTo("Клиент после изменения");
    assertAuditCount(orderId, "ORDER_CHANGED", 1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from rental_order_command_receipt
                where order_id=? and operation_name='UPDATE_ORDER'
                """,
                Long.class,
                orderId))
        .isOne();
  }

  @Test
  void updateOrderSameClientStoresReceiptWithoutVersionBumpOrAudit() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент без изменения");
    UUID clientId = orderClientId(orderId);
    UUID key = UUID.randomUUID();
    String body = updateOrderBody(0, clientId);

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.client.id").value(clientId.toString()));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(0));

    assertThat(
            jdbc.queryForObject("select version from rental_order where id=?", Long.class, orderId))
        .isZero();
    assertAuditCount(orderId, "CLIENT_SELECTED", 1);
    assertAuditCount(orderId, "ORDER_CHANGED", 0);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                from rental_order_command_receipt
                where order_id=? and operation_name='UPDATE_ORDER'
                """,
                Long.class,
                orderId))
        .isOne();
  }

  @Test
  void updateOrderRequiresVisibleEditableDraftExistingClientAndCurrentVersion() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент с проверками");
    UUID originalClientId = orderClientId(orderId);
    UUID replacementClientId = createClient(MANAGER_1, "manager-one", "Клиент замена");

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(0, replacementClientId))
                .with(manager(MANAGER_2, "manager-two")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"clientId\":\"%s\"}".formatted(replacementClientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(0, UUID.randomUUID()))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("CLIENT_NOT_FOUND"));

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(0, replacementClientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(0, originalClientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_VERSION_CONFLICT"));

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "1")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(2, originalClientId))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_EDITABLE"));
  }

  @Test
  void warehouseManagerSeesGrantedWarehouseOnly() throws Exception {
    UUID first = createOrder(MANAGER_1, "manager-one", "Клиент W1");
    UUID second = createOrder(MANAGER_2, "manager-two", "Клиент W2");
    selectWarehouseAs(first, ADMIN, "SYSTEM_ADMIN", WAREHOUSE_1, 0, List.of());
    selectWarehouseAs(second, ADMIN, "SYSTEM_ADMIN", WAREHOUSE_2, 0, List.of());
    JwtRequestPostProcessor localAdmin =
        user(
            UUID.fromString("00000000-0000-0000-0000-000000009104"),
            "WAREHOUSE_MANAGER",
            "local-admin",
            List.of(Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT")));

    mvc.perform(get("/api/logistics/v1/orders").with(localAdmin))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1))
        .andExpect(jsonPath("$.content[0].id").value(first.toString()));
    mvc.perform(get("/api/logistics/v1/orders/{orderId}", second).with(localAdmin))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
  }

  @Test
  void presentationSelectedUnitEquipmentReleaseAndCancelAreVersionedAndAudited() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент workflow");
    JsonNode warehouse = selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    assertThat(warehouse.get("version").longValue()).isEqualTo(1);

    JsonNode added = addUnit(orderId, MANAGER_1, UNIT_1, 1);
    assertThat(added.get("version").longValue()).isEqualTo(2);
    assertThat(added.at("/units/0/added").booleanValue()).isTrue();
    assertThat(added.at("/units/0/unit/id").stringValue()).isEqualTo(UNIT_1.toString());

    MvcResult desiredEquipment =
        mvc.perform(
                put(
                        "/api/logistics/v1/orders/{orderId}/units/{unitId}/desired-equipment",
                        orderId,
                        UNIT_1)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "expectedVersion": 2,
                          "requirements": [{"equipmentId":"%s","quantity":2}]
                        }
                        """
                            .formatted(EQUIPMENT))
                    .with(manager(MANAGER_1, "manager-one")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.version").value(3))
            .andExpect(jsonPath("$.units[0].desiredContents[0].quantity").value(2))
            .andReturn();
    assertThat(json(desiredEquipment).get("version").longValue()).isEqualTo(3);

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}/units/{unitId}", orderId, UNIT_1)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.unitCount").value(0));

    addUnit(orderId, MANAGER_1, UNIT_2, 4);
    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "5")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(6))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.unitCount").value(0));

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}/history", orderId)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[?(@.eventType == 'WAREHOUSE_SELECTED')]").exists())
        .andExpect(jsonPath("$[?(@.eventType == 'UNIT_ADDED')]").exists())
        .andExpect(jsonPath("$[?(@.eventType == 'EQUIPMENT_ADDED')]").exists())
        .andExpect(jsonPath("$[?(@.eventType == 'UNIT_REMOVED')]").exists())
        .andExpect(jsonPath("$[?(@.eventType == 'RESERVATION_RELEASED')]").exists())
        .andExpect(jsonPath("$[?(@.eventType == 'ORDER_CANCELLED')]").exists());
  }

  @Test
  void removeUnitRecoversAutonomouslyAfterUnknownOutcomeAndJoinsDuplicateApiRetry()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент remove recovery");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    reserveRemotely(orderId, WAREHOUSE_1, UNIT_1, MANAGER_1, "RENTAL_MANAGER");
    UUID key = UUID.randomUUID();
    AtomicBoolean failAfterEffect = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              LogisticsDependencyGateway.OrderUnitReservation released =
                  releaseRemotely(
                      invocation.getArgument(0),
                      invocation.getArgument(1),
                      invocation.getArgument(2));
              if (failAfterEffect.getAndSet(false)) throw transientDependencyFailure();
              return released;
            })
        .when(dependencies)
        .releaseOrderUnit(any(), any(), any(), any(), anyString());

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}/units/{unitId}", orderId, UNIT_1)
                .param("expectedVersion", "1")
                .header("Idempotency-Key", key)
                .with(admin()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ORDER_ASSET_SERVICE_UNAVAILABLE"));
    assertThat(activeUnitOwners).doesNotContainKey(UNIT_1);
    assertAuditCount(orderId, "UNIT_ADDED", 0);
    assertAuditCount(orderId, "UNIT_REMOVED", 0);

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}/units/{unitId}", orderId, UNIT_1)
                .param("expectedVersion", "1")
                .header("Idempotency-Key", key)
                .with(admin()))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ORDER_MUTATION_PENDING"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_mutation_command where order_id=?",
                Long.class,
                orderId))
        .isEqualTo(1L);
    verify(dependencies, times(1)).releaseOrderUnit(any(), any(), any(), any(), anyString());

    recoverOrderMutation(orderId);

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}/units/{unitId}", orderId, UNIT_1)
                .param("expectedVersion", "1")
                .header("Idempotency-Key", key)
                .with(admin()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.unitCount").value(0));
    verify(dependencies, times(2)).releaseOrderUnit(any(), any(), any(), any(), anyString());
    ArgumentCaptor<UUID> stepKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .releaseOrderUnit(stepKeys.capture(), any(), any(), any(), anyString());
    assertThat(stepKeys.getAllValues()).containsOnly(stepKeys.getAllValues().getFirst());
    assertThat(stepKeys.getAllValues().getFirst()).isNotEqualTo(key);
    assertAuditCount(orderId, "UNIT_ADDED", 1);
    assertAuditCount(orderId, "RESERVATION_CREATED", 1);
    assertAuditCount(orderId, "UNIT_REMOVED", 1);
    assertAuditCount(orderId, "RESERVATION_RELEASED", 1);
    assertThat(
            jdbc.queryForObject(
                "select actor_subject_id from rental_order_audit_event where order_id=? and"
                    + " event_type='UNIT_ADDED'",
                UUID.class,
                orderId))
        .isEqualTo(MANAGER_1);
    assertThat(
            jdbc.queryForObject(
                "select actor_role from rental_order_audit_event where order_id=? and"
                    + " event_type='UNIT_ADDED'",
                String.class,
                orderId))
        .isEqualTo("RENTAL_MANAGER");
    assertThat(
            jdbc.queryForObject(
                "select actor_subject_id from rental_order_audit_event where order_id=? and"
                    + " event_type='UNIT_REMOVED'",
                UUID.class,
                orderId))
        .isEqualTo(ADMIN);
    assertThat(
            jdbc.queryForObject(
                "select actor_role from rental_order_audit_event where order_id=? and"
                    + " event_type='UNIT_REMOVED'",
                String.class,
                orderId))
        .isEqualTo("SYSTEM_ADMIN");
  }

  @Test
  void cancelRecoversAutonomouslyAfterUnknownOutcomeAndFencesCompetingMutation()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент cancel recovery");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    UUID key = UUID.randomUUID();
    AtomicBoolean failAfterEffect = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              List<LogisticsDependencyGateway.OrderUnitReservation> released =
                  releaseAllRemotely(invocation.getArgument(0), invocation.getArgument(1));
              if (failAfterEffect.getAndSet(false)) throw transientDependencyFailure();
              return released;
            })
        .when(dependencies)
        .releaseAllOrderUnits(any(), any(), any(), anyString());

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", key)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ORDER_ASSET_SERVICE_UNAVAILABLE"));
    assertThat(activeUnitOwners).doesNotContainKey(UNIT_1);
    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, orderId))
        .isEqualTo("DRAFT");

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", key)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ORDER_MUTATION_PENDING"));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateOrderBody(2, orderClientId(orderId)))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_MUTATION_PENDING"));
    verify(dependencies, times(1)).releaseAllOrderUnits(any(), any(), any(), anyString());

    recoverOrderMutation(orderId);

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", key)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.unitCount").value(0));
    verify(dependencies, times(2)).releaseAllOrderUnits(any(), any(), any(), anyString());
    ArgumentCaptor<UUID> stepKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .releaseAllOrderUnits(stepKeys.capture(), any(), any(), anyString());
    assertThat(stepKeys.getAllValues()).containsOnly(stepKeys.getAllValues().getFirst());
    assertThat(stepKeys.getAllValues().getFirst()).isNotEqualTo(key);
    assertAuditCount(orderId, "UNIT_REMOVED", 1);
    assertAuditCount(orderId, "RESERVATION_RELEASED", 1);
    assertAuditCount(orderId, "ORDER_CANCELLED", 1);
  }

  @Test
  void cancelRecoveryResumesAtEquipmentAfterCommittedUnitReleaseReceipt() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент equipment recovery");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    UUID key = UUID.randomUUID();
    AtomicBoolean failEquipmentAfterEffect = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              List<LogisticsDependencyGateway.OrderEquipmentReservation> receipt =
                  replaceEquipmentReservationsRemotely(
                      invocation.getArgument(1), invocation.getArgument(5));
              if (failEquipmentAfterEffect.getAndSet(false)) throw transientDependencyFailure();
              return receipt;
            })
        .when(dependencies)
        .replaceOrderEquipmentReservations(any(), any(), any(), any(), anyString(), any());

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", key)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ORDER_ASSET_SERVICE_UNAVAILABLE"));
    Map<String, Object> deferred =
        jdbc.queryForMap(
            "select step, released_units_receipt_json, equipment_receipt_json, attempt_count"
                + " from rental_order_mutation_command where order_id=?",
            orderId);
    assertThat(deferred.get("step")).isEqualTo("RELEASE_EQUIPMENT");
    assertThat(deferred.get("released_units_receipt_json")).isNotNull();
    assertThat(deferred.get("equipment_receipt_json")).isNull();
    assertThat(deferred.get("attempt_count")).isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select status from rental_order where id=?", String.class, orderId))
        .isEqualTo("DRAFT");
    verify(dependencies, times(1)).releaseAllOrderUnits(any(), any(), any(), anyString());
    verify(dependencies, times(1))
        .replaceOrderEquipmentReservations(any(), any(), any(), any(), anyString(), any());

    recoverOrderMutation(orderId);

    verify(dependencies, times(1)).releaseAllOrderUnits(any(), any(), any(), anyString());
    verify(dependencies, times(2))
        .replaceOrderEquipmentReservations(any(), any(), any(), any(), anyString(), any());
    ArgumentCaptor<UUID> equipmentKeys = ArgumentCaptor.forClass(UUID.class);
    verify(dependencies, times(2))
        .replaceOrderEquipmentReservations(
            equipmentKeys.capture(), any(), any(), any(), anyString(), any());
    assertThat(equipmentKeys.getAllValues())
        .containsOnly(equipmentKeys.getAllValues().getFirst());
    assertThat(equipmentKeys.getAllValues().getFirst()).isNotEqualTo(key);

    mvc.perform(
            delete("/api/logistics/v1/orders/{orderId}", orderId)
                .param("expectedVersion", "2")
                .header("Idempotency-Key", key)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void managerReadsOwnAssignedOrderWithoutWarehouseGrant() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент grant");
    selectWarehouseAs(orderId, ADMIN, "SYSTEM_ADMIN", WAREHOUSE_1, 0, List.of());

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(managerWithoutWarehouse(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()));
    mvc.perform(
            get("/api/logistics/v1/orders").with(managerWithoutWarehouse(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
  }

  @Test
  void directWarehouseAndUnitSelectionOperationsAreNotExposed() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент без третьего пути");

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}/warehouse", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"warehouseId":"%s"}
                    """
                        .formatted(WAREHOUSE_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isNotFound());
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":1,\"unitId\":\"%s\"}".formatted(UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isNotFound());
    verify(dependencies, never()).readWarehouseIdentity(any());
    verify(dependencies, never())
        .reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString());
  }

  @Test
  void desiredEquipmentEndpointRejectsAUnitFromAnotherOrderBeforeCallingAssetMutation()
      throws Exception {
    UUID first = createOrder(MANAGER_1, "manager-one", "Клиент A");
    selectWarehouse(first, MANAGER_1, WAREHOUSE_1, 0);
    UUID second = createOrder(MANAGER_2, "manager-two", "Клиент B");
    selectWarehouse(second, MANAGER_2, WAREHOUSE_1, 0);
    addUnit(second, MANAGER_2, UNIT_2, 1);

    mvc.perform(
            put(
                    "/api/logistics/v1/orders/{orderId}/units/{unitId}/desired-equipment",
                    first,
                    UNIT_2)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion": 1,
                      "requirements": [{"equipmentId":"%s","quantity":1}]
                    }
                    """
                        .formatted(EQUIPMENT))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ORDER_UNIT_NOT_FOUND"));
    verify(dependencies, never())
        .replaceOrderEquipmentReservations(any(), any(), any(), any(), anyString(), any());
  }

  private UUID createOrder(UUID subjectId, String username, String clientName) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/logistics/v1/orders")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "newClient": {
                          "clientType": "LEGAL_ENTITY",
                          "displayName": "%s",
                          "phone": "%s",
                          "contactPerson": "Контактное лицо"
                        },
                        "contactPhone": "+79990000000"
                        }
                        """
                            .formatted(clientName, nextTestPhone()))
                    .with(manager(subjectId, username)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.managerId").value(subjectId.toString()))
            .andReturn();
    UUID orderId = UUID.fromString(json(result).get("id").stringValue());
    seedClientDeliveryDetails(orderId, "Москва, Тестовая улица, 1");
    seedClientDesiredDeliveryWindow(orderId);
    return orderId;
  }

  private UUID createClient(UUID subjectId, String username, String clientName) throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/logistics/v1/clients")
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "clientType": "LEGAL_ENTITY",
                          "displayName": "%s",
                          "phone": "%s",
                          "contactPerson": "Контактное лицо"
                        }
                        """
                            .formatted(clientName, nextTestPhone()))
                    .with(manager(subjectId, username)))
            .andExpect(status().isCreated())
            .andReturn();
    return UUID.fromString(json(result).get("id").stringValue());
  }

  private UUID orderClientId(UUID orderId) {
    return jdbc.queryForObject(
        "select client_id from rental_order where id=?", UUID.class, orderId);
  }

  private void recoverOrderMutation(UUID orderId) {
    assertThat(
            jdbc.update(
                "update rental_order_mutation_command set next_attempt_at=clock_timestamp()"
                    + " where order_id=? and state='PENDING' and lease_token is null",
                orderId))
        .isEqualTo(1);
    orderMutationRecovery.recoverPending();
    assertThat(
            jdbc.queryForObject(
                "select state from rental_order_mutation_command where order_id=?",
                String.class,
                orderId))
        .isEqualTo("COMPLETED");
  }

  private static String updateOrderBody(long expectedVersion, UUID clientId) {
    return """
    {
      "expectedVersion": %d,
      "clientId": "%s",
      "contactPhone": "+79990000000"
    }
    """
        .formatted(expectedVersion, clientId);
  }

  private void seedClientDesiredDeliveryWindow(UUID orderId) {
    LocalDate date = LocalDate.now(ZoneOffset.UTC).plusDays(1);
    seedClientDesiredDeliveryWindow(orderId, date);
  }

  private void seedClientDesiredDeliveryWindow(UUID orderId, LocalDate date) {
    jdbc.update(
        """
        insert into rental_order_desired_delivery_window(
          order_id,position,start_date,end_date)
        values (?,0,?,?)
        """,
        orderId,
        date,
        date);
  }

  private void seedClientDeliveryDetails(UUID orderId, String address) {
    jdbc.update(
        """
        update rental_order
        set delivery_address=?, latitude=55.751234, longitude=37.621234
        where id=?
        """,
        address,
        orderId);
    jdbc.update("delete from rental_order_additional_contact where order_id=?", orderId);
    jdbc.update(
        """
        insert into rental_order_additional_contact(order_id,position,contact_name,phone)
        values (?,0,'Иван Контакт','+79991112233')
        """,
        orderId);
  }

  private void seedClientSelectedRentalTerms(UUID orderId, Map<UUID, Long> terms) {
    terms.forEach(
        (unitId, rentalMonths) ->
            jdbc.update(
                """
                insert into rental_order_unit_term(
                  id,version,order_id,rental_item_id,rental_months,created_at,updated_at)
                values (?,0,?,?,?,clock_timestamp(),clock_timestamp())
                """,
                UUID.randomUUID(),
                orderId,
                unitId,
                rentalMonths));
  }

  private JsonNode createRentalShipment(
      UUID orderId,
      UUID subjectId,
      long expectedVersion,
      UUID unitId,
      String scheduledDate,
      String driver)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/logistics/v1/orders/{orderId}/shipments", orderId)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "expectedVersion": %d,
                          "driverSnapshot": "%s",
                          "scheduledDate": "%s",
                          "unitIds": ["%s"]
                        }
                        """
                            .formatted(expectedVersion, driver, scheduledDate, unitId))
                    .with(manager(subjectId, "manager-one")))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.state").value("DRAFT"))
            .andExpect(jsonPath("$.scheduledDate").value(scheduledDate))
            .andExpect(jsonPath("$.rentalOrderId").value(orderId.toString()))
            .andExpect(jsonPath("$.lines.length()").value(1))
            .andReturn();
    return json(result);
  }

  private static String rentalShipmentBody(
      long expectedVersion, String driver, String scheduledDate, UUID unitId) {
    return """
    {
      "expectedVersion": %d,
      "driverSnapshot": "%s",
      "scheduledDate": "%s",
      "unitIds": ["%s"]
    }
    """
        .formatted(expectedVersion, driver, scheduledDate, unitId);
  }

  private static JsonNode rentalTerm(JsonNode detail, UUID unitId) {
    for (JsonNode unit : detail.get("units")) {
      if (unitId.toString().equals(unit.at("/unit/id").stringValue())) {
        return unit.get("rentalTerm");
      }
    }
    throw new AssertionError("Order unit was not returned: " + unitId);
  }

  private static String nextTestPhone() {
    return "+7999%07d".formatted(NEXT_TEST_PHONE.getAndIncrement());
  }

  private static String futureDate(int days) {
    return LocalDate.now(ZoneOffset.UTC).plusDays(days).toString();
  }

  private void linkFurnitureMovementTask(UUID shipmentId, UUID unitId) {
    UUID taskId = UUID.randomUUID();
    OffsetDateTime createdAt = now();
    jdbc.update(
        """
        insert into equipment_movement_task(
          id,version,warehouse_id,external_task_id,unit_number,deadline_at,
          planned_duration_minutes,state,owner_type,
          created_by_subject_id,idempotency_key,request_sha256,retry_count,created_at,updated_at)
        values (?,0,?,?,?,?,?,?,'USER_REQUEST',?,?,?,0,?,?)
        """,
        taskId,
        WAREHOUSE_1,
        UUID.randomUUID(),
        "БЫТ-001",
        createdAt.plusDays(1),
        60,
        "COMPLETED",
        MANAGER_1,
        UUID.randomUUID(),
        "a".repeat(64),
        createdAt,
        createdAt);
    jdbc.update(
        """
        insert into shipment_furniture_movement_task(
          id,version,document_id,rental_item_id,unit_number,equipment_movement_task_id,line_count,created_at)
        values (?,0,?,?,?,?,1,?)
        """,
        UUID.randomUUID(),
        shipmentId,
        unitId,
        "БЫТ-001",
        taskId,
        createdAt);
  }

  private JsonNode selectWarehouse(
      UUID orderId, UUID subjectId, UUID warehouseId, long expectedVersion) throws Exception {
    return selectWarehouseAs(
        orderId,
        subjectId,
        "RENTAL_MANAGER",
        warehouseId,
        expectedVersion,
        List.of(Map.of("warehouseId", warehouseId.toString(), "level", "EDIT")));
  }

  private JsonNode selectWarehouseAs(
      UUID orderId,
      UUID subjectId,
      String role,
      UUID warehouseId,
      long expectedVersion,
      List<Map<String, String>> grants)
      throws Exception {
    assertThat(
            jdbc.update(
                """
                update rental_order
                set warehouse_id=?, version=version+1, updated_at=?
                where id=? and version=? and warehouse_id is null
                """,
                warehouseId,
                now(),
                orderId,
                expectedVersion))
        .isOne();
    appendFixtureAudit(
        orderId,
        "WAREHOUSE_SELECTED",
        subjectId,
        role,
        "WAREHOUSE",
        warehouseId,
        null,
        "{\"warehouseId\":\"%s\"}".formatted(warehouseId));
    appendFixtureAudit(
        orderId,
        "ORDER_CHANGED",
        subjectId,
        role,
        "ORDER",
        orderId,
        null,
        "{\"changedField\":\"warehouseId\",\"version\":%d}".formatted(expectedVersion + 1));
    JwtRequestPostProcessor actor =
        "RENTAL_MANAGER".equals(role)
            ? dedicatedManager(
                subjectId,
                subjectId.toString(),
                RENTAL_MANAGER_WEB_CLIENT_ID,
                grants)
            : user(subjectId, role, subjectId.toString(), grants);
    MvcResult result =
        mvc.perform(
                get("/api/logistics/v1/orders/{orderId}", orderId).with(actor))
            .andExpect(status().isOk())
            .andReturn();
    return json(result);
  }

  private JsonNode addUnit(UUID orderId, UUID subjectId, UUID unitId, long expectedVersion)
      throws Exception {
    LogisticsDependencyGateway.OrderUnitReservation reservation =
        reserveRemotely(orderId, WAREHOUSE_1, unitId, subjectId, "RENTAL_MANAGER");
    assertThat(
            jdbc.update(
                """
                update rental_order
                set version=version+1, updated_at=?
                where id=? and version=? and warehouse_id=?
                """,
                now(),
                orderId,
                expectedVersion,
                WAREHOUSE_1))
        .isOne();
    appendFixtureAudit(
        orderId,
        "UNIT_ADDED",
        subjectId,
        "RENTAL_MANAGER",
        "RENTAL_ITEM",
        unitId,
        null,
        "{\"unitNumber\":\"%s\"}".formatted(reservation.unit().number()));
    appendFixtureAudit(
        orderId,
        "RESERVATION_CREATED",
        subjectId,
        "RENTAL_MANAGER",
        "UNIT_RESERVATION",
        reservation.reservationId(),
        null,
        "{\"unitNumber\":\"%s\",\"state\":\"ACTIVE\"}".formatted(reservation.unit().number()));
    appendFixtureAudit(
        orderId,
        "ORDER_CHANGED",
        subjectId,
        "RENTAL_MANAGER",
        "ORDER",
        orderId,
        null,
        "{\"changedField\":\"units\",\"version\":%d}".formatted(expectedVersion + 1));
    MvcResult result =
        mvc.perform(
                get("/api/logistics/v1/orders/{orderId}", orderId)
                    .with(manager(subjectId, "manager")))
            .andExpect(status().isOk())
            .andReturn();
    return json(result);
  }

  private void appendFixtureAudit(
      UUID orderId,
      String eventType,
      UUID actorSubjectId,
      String actorRole,
      String subjectType,
      UUID subjectId,
      String previousValues,
      String newValues) {
    jdbc.update(
        """
        insert into rental_order_audit_event(
          id,order_id,event_type,actor_subject_id,actor_role,subject_type,subject_id,
          previous_values,new_values,occurred_at)
        values (?,?,?,?,?,?,?,cast(? as jsonb),cast(? as jsonb),?)
        """,
        UUID.randomUUID(),
        orderId,
        eventType,
        actorSubjectId,
        actorRole,
        subjectType,
        subjectId.toString(),
        previousValues,
        newValues,
        now());
  }

  private JsonNode json(MvcResult result) {
    return objectMapper.readTree(result.getResponse().getContentAsByteArray());
  }

  private void assertAuditCount(UUID orderId, String eventType, long expected) {
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and event_type=?",
                Long.class,
                orderId,
                eventType))
        .isEqualTo(expected);
  }

  private static LogisticsDependencyException transientDependencyFailure() {
    return new LogisticsDependencyException(
        LogisticsDependencyException.FailureKind.TRANSIENT,
        "Remote effect completed before the response was lost",
        null);
  }

  private static JwtRequestPostProcessor manager(UUID subjectId, String username) {
    return dedicatedManager(
        subjectId,
        username,
        RENTAL_MANAGER_WEB_CLIENT_ID,
        List.of(
            Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT"),
            Map.of("warehouseId", WAREHOUSE_2.toString(), "level", "EDIT")));
  }

  private static JwtRequestPostProcessor androidManager(UUID subjectId, String username) {
    return dedicatedManager(
        subjectId,
        username,
        RENTAL_MANAGER_ANDROID_CLIENT_ID,
        List.of(
            Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT"),
            Map.of("warehouseId", WAREHOUSE_2.toString(), "level", "EDIT")));
  }

  private static JwtRequestPostProcessor managerWithoutWarehouse(UUID subjectId, String username) {
    return dedicatedManager(
        subjectId, username, RENTAL_MANAGER_WEB_CLIENT_ID, List.of());
  }

  private static JwtRequestPostProcessor readOnlyViewer(UUID subjectId, String username) {
    return applicationUser(
        subjectId,
        "VIEWER",
        username,
        null,
        "rwms.read",
        true,
        List.of(Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT")));
  }

  private static JwtRequestPostProcessor panelRentalManager(UUID subjectId, String username) {
    return applicationUser(
        subjectId,
        "RENTAL_MANAGER",
        username,
        "rwms-panel",
        "rwms.read rwms.write",
        true,
        List.of(
            Map.of("warehouseId", WAREHOUSE_1.toString(), "level", "EDIT"),
            Map.of("warehouseId", WAREHOUSE_2.toString(), "level", "EDIT")));
  }

  private static JwtRequestPostProcessor admin() {
    return user(ADMIN, "SYSTEM_ADMIN", "admin", List.of());
  }

  private static JwtRequestPostProcessor user(
      UUID subjectId,
      String role,
      String username,
      List<Map<String, String>> warehouseAccess) {
    return applicationUser(
        subjectId,
        role,
        username,
        null,
        "rwms.read rwms.write",
        true,
        warehouseAccess);
  }

  private static JwtRequestPostProcessor dedicatedManager(
      UUID subjectId,
      String username,
      String clientId,
      List<Map<String, String>> warehouseAccess) {
    return applicationUser(
        subjectId,
        "RENTAL_MANAGER",
        username,
        clientId,
        "rental.manage",
        true,
        warehouseAccess);
  }

  private static JwtRequestPostProcessor applicationUser(
      UUID subjectId,
      String role,
      String username,
      String clientId,
      String scope,
      boolean rentalAccess,
      List<Map<String, String>> warehouseAccess) {
    return jwt()
        .jwt(
            token -> {
              token
                  .subject(subjectId.toString())
                  .claim("principal_type", "USER")
                  .claim("scope", scope)
                  .claim("global_role", role)
                  .claim("preferred_username", username)
                  .claim("rentalAccess", rentalAccess)
                  .claim("warehouse_access", warehouseAccess);
              if (clientId != null) token.claim("client_id", clientId);
            });
  }

  private LogisticsDependencyGateway.OrderUnitReservation reserveRemotely(
      UUID orderId, UUID warehouseId, UUID unitId, UUID actor, String role) {
    UUID owner = activeUnitOwners.putIfAbsent(unitId, orderId);
    if (owner != null && !owner.equals(orderId)) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
          "UNIT_ALREADY_RESERVED",
          "conflict",
          null);
    }
    LinkedHashMap<UUID, LogisticsDependencyGateway.OrderUnitReservation> orderUnits =
        reservations.computeIfAbsent(orderId, ignored -> new LinkedHashMap<>());
    LogisticsDependencyGateway.OrderUnitReservation current = orderUnits.get(unitId);
    if (current != null) return replayed(current);
    LogisticsDependencyGateway.OrderUnitReservation created =
        reservation(orderId, warehouseId, unitId, actor, role, "ACTIVE", false, List.of());
    orderUnits.put(unitId, created);
    return created;
  }

  private LogisticsDependencyGateway.OrderUnitReservation releaseRemotely(
      UUID idempotencyKey, UUID orderId, UUID unitId) {
    LogisticsDependencyGateway.OrderUnitReservation receipt = releaseReceipts.get(idempotencyKey);
    if (receipt != null) return replayed(receipt);
    LogisticsDependencyGateway.OrderUnitReservation current =
        reservations.getOrDefault(orderId, new LinkedHashMap<>()).remove(unitId);
    if (current == null) {
      throw new LogisticsDependencyException(
          LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
          "ASSET_NOT_FOUND",
          "missing",
          null);
    }
    activeUnitOwners.remove(unitId, orderId);
    LogisticsDependencyGateway.OrderUnitReservation released = released(current);
    releaseReceipts.put(idempotencyKey, released);
    return released;
  }

  private List<LogisticsDependencyGateway.OrderUnitReservation> releaseAllRemotely(
      UUID idempotencyKey, UUID orderId) {
    List<LogisticsDependencyGateway.OrderUnitReservation> receipt =
        releaseAllReceipts.get(idempotencyKey);
    if (receipt != null) return receipt.stream().map(OrderApiIntegrationTest::replayed).toList();
    LinkedHashMap<UUID, LogisticsDependencyGateway.OrderUnitReservation> current =
        reservations.computeIfAbsent(orderId, ignored -> new LinkedHashMap<>());
    List<LogisticsDependencyGateway.OrderUnitReservation> released =
        current.values().stream().map(OrderApiIntegrationTest::released).toList();
    current.keySet().forEach(unitId -> activeUnitOwners.remove(unitId, orderId));
    current.clear();
    releaseAllReceipts.put(idempotencyKey, List.copyOf(released));
    return released;
  }

  private List<LogisticsDependencyGateway.OrderEquipmentReservation>
      replaceEquipmentReservationsRemotely(
          UUID orderId,
          List<LogisticsDependencyGateway.OrderUnitEquipmentRequirements> requirements) {
    LinkedHashMap<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> current =
        equipmentReservations.computeIfAbsent(orderId, ignored -> new LinkedHashMap<>());
    current.clear();
    LinkedHashMap<UUID, Long> totals = new LinkedHashMap<>();
    requirements.stream()
        .flatMap(unit -> unit.requirements().stream())
        .forEach(
            requirement ->
                totals.merge(requirement.equipmentId(), requirement.quantity(), Long::sum));
    for (Map.Entry<UUID, Long> requirement : totals.entrySet()) {
      current.put(
          requirement.getKey(),
          new LogisticsDependencyGateway.OrderEquipmentReservation(
              requirement.getKey(), "Стул", requirement.getValue(), 10));
    }
    return List.copyOf(current.values());
  }

  private List<UUID> seedPagedDocuments(
      LogisticsDocumentType type, int count, LocalDate scheduledDate) {
    List<UUID> documentIds = new java.util.ArrayList<>();
    OffsetDateTime base = OffsetDateTime.parse("2026-08-01T00:00:00Z");
    for (int index = 0; index < count; index++) {
      UUID documentId = new UUID(0x1800000000000000L + type.ordinal(), index + 1L);
      UUID lineId = new UUID(0x1900000000000000L + type.ordinal(), index + 1L);
      UUID assetId = new UUID(0x1A00000000000000L + type.ordinal(), index + 1L);
      OffsetDateTime createdAt = base.plusSeconds(index);
      jdbc.update(
          """
          insert into logistics_document(
            id, version, document_type, state, warehouse_id, destination_warehouse_id,
            party_snapshot, driver_snapshot, scheduled_date, requested_by_subject_id,
            correlation_id, created_at, updated_at
          ) values (?, 0, ?, 'DRAFT', ?, ?, ?, ?, ?, ?, ?, ?, ?)
          """,
          documentId,
          type.name(),
          WAREHOUSE_1,
          type == LogisticsDocumentType.TRANSFER ? WAREHOUSE_2 : null,
          "Клиент " + index,
          type == LogisticsDocumentType.TRANSFER ? null : "Водитель " + index,
          scheduledDate,
          ADMIN,
          UUID.randomUUID(),
          createdAt,
          createdAt);
      jdbc.update(
          """
          insert into logistics_document_line(
            id, version, document_id, line_number, asset_id, asset_version, state,
            tenant_snapshot, created_at, updated_at
          ) values (?, 0, ?, 1, ?, 0, 'PENDING', ?, ?, ?)
          """,
          lineId,
          documentId,
          assetId,
          "Клиент " + index,
          createdAt,
          createdAt);
      documentIds.add(documentId);
    }
    return List.copyOf(documentIds);
  }

  private void seedListedDocument(
      UUID documentId,
      UUID warehouseId,
      UUID destinationWarehouseId,
      LocalDate scheduledDate,
      OffsetDateTime createdAt) {
    jdbc.update(
        """
        insert into logistics_document(
          id, version, document_type, state, warehouse_id, destination_warehouse_id,
          scheduled_date, requested_by_subject_id, correlation_id, created_at, updated_at
        ) values (?, 0, 'TRANSFER', 'DRAFT', ?, ?, ?, ?, ?, ?, ?)
        """,
        documentId,
        warehouseId,
        destinationWarehouseId,
        scheduledDate,
        ADMIN,
        UUID.randomUUID(),
        createdAt,
        createdAt);
  }

  private static LogisticsDependencyGateway.OrderUnitReservation reservation(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID actor,
      String role,
      String state,
      boolean replayed,
      List<LogisticsDependencyGateway.OrderEquipmentContent> contents) {
    return new LogisticsDependencyGateway.OrderUnitReservation(
        UUID.randomUUID(),
        0,
        orderId,
        unitId,
        warehouseId,
        state,
        actor,
        role,
        now(),
        "RELEASED".equals(state) ? now() : null,
        replayed,
        new LogisticsDependencyGateway.OrderRentalItem(
            unitId,
            0,
            warehouseId,
            "CAB-" + unitId.toString().substring(30),
            "FREE",
            "STANDARD",
            "6x2.4",
            "BASIC",
            "OFFICE",
            null,
            false,
            List.of(),
            contents,
            now(),
            now()));
  }

  private static LogisticsDependencyGateway.OrderUnitReservation replayed(
      LogisticsDependencyGateway.OrderUnitReservation current) {
    return new LogisticsDependencyGateway.OrderUnitReservation(
        current.reservationId(),
        current.reservationVersion(),
        current.orderId(),
        current.unitId(),
        current.warehouseId(),
        current.state(),
        current.addedBySubjectId(),
        current.addedByRole(),
        current.createdAt(),
        current.releasedAt(),
        true,
        current.unit());
  }

  private static LogisticsDependencyGateway.OrderUnitReservation released(
      LogisticsDependencyGateway.OrderUnitReservation current) {
    return new LogisticsDependencyGateway.OrderUnitReservation(
        current.reservationId(),
        current.reservationVersion() + 1,
        current.orderId(),
        current.unitId(),
        current.warehouseId(),
        "RELEASED",
        current.addedBySubjectId(),
        current.addedByRole(),
        current.createdAt(),
        now(),
        false,
        current.unit());
  }

  private static LogisticsDependencyGateway.OrderRentalItem withContents(
      LogisticsDependencyGateway.OrderRentalItem unit,
      List<LogisticsDependencyGateway.OrderEquipmentContent> contents) {
    return new LogisticsDependencyGateway.OrderRentalItem(
        unit.id(),
        unit.version() + 1,
        unit.warehouseId(),
        unit.number(),
        unit.status(),
        unit.rentalType(),
        unit.dimensions(),
        unit.finishing(),
        unit.category(),
        unit.characteristics(),
        unit.linoleum(),
        unit.tags(),
        contents,
        unit.createdAt(),
        now());
  }

  private static LogisticsDependencyGateway.OrderUnitReservation withUnit(
      LogisticsDependencyGateway.OrderUnitReservation reservation,
      LogisticsDependencyGateway.OrderRentalItem unit) {
    return new LogisticsDependencyGateway.OrderUnitReservation(
        reservation.reservationId(),
        reservation.reservationVersion(),
        reservation.orderId(),
        reservation.unitId(),
        reservation.warehouseId(),
        reservation.state(),
        reservation.addedBySubjectId(),
        reservation.addedByRole(),
        reservation.createdAt(),
        reservation.releasedAt(),
        reservation.replayed(),
        unit);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }
}
