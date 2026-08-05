package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class OrderApiIntegrationTest {
  private static final UUID MANAGER_1 =
      UUID.fromString("00000000-0000-0000-0000-000000009101");
  private static final UUID MANAGER_2 =
      UUID.fromString("00000000-0000-0000-0000-000000009102");
  private static final UUID ADMIN =
      UUID.fromString("00000000-0000-0000-0000-000000009103");
  private static final UUID WAREHOUSE_1 =
      UUID.fromString("00000000-0000-0000-0000-000000009201");
  private static final UUID WAREHOUSE_2 =
      UUID.fromString("00000000-0000-0000-0000-000000009202");
  private static final UUID UNIT_1 =
      UUID.fromString("00000000-0000-0000-0000-000000009301");
  private static final UUID UNIT_2 =
      UUID.fromString("00000000-0000-0000-0000-000000009302");
  private static final UUID EQUIPMENT =
      UUID.fromString("00000000-0000-0000-0000-000000009401");
  private static final AtomicInteger NEXT_TEST_PHONE = new AtomicInteger(1_000_000);

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean LogisticsDependencyGateway dependencies;

  private final Map<UUID, LinkedHashMap<UUID, LogisticsDependencyGateway.OrderUnitReservation>>
      reservations = new ConcurrentHashMap<>();
  private final Map<UUID, UUID> activeUnitOwners = new ConcurrentHashMap<>();
  private final Map<UUID, LogisticsDependencyGateway.OrderUnitReservation>
      releaseReceipts = new ConcurrentHashMap<>();
  private final Map<UUID, List<LogisticsDependencyGateway.OrderUnitReservation>>
      releaseAllReceipts = new ConcurrentHashMap<>();
  private final Map<UUID, LinkedHashMap<UUID, LogisticsDependencyGateway.OrderEquipmentReservation>>
      equipmentReservations = new ConcurrentHashMap<>();

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table
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
                        .computeIfAbsent(invocation.getArgument(0), ignored -> new LinkedHashMap<>())
                        .values()));
    when(dependencies.readOrderUnitCandidates(any(), any(), anyInt(), anyInt(), anyString()))
        .thenReturn(
            new LogisticsDependencyGateway.OrderUnitCandidatePage(
                List.of(), 0, 50, 0, 0));
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
            invocation ->
                releaseAllRemotely(
                    invocation.getArgument(0), invocation.getArgument(1)));
    when(dependencies.replaceOrderEquipmentReservations(
            any(), any(), any(), any(), anyString(), any()))
        .thenAnswer(
            invocation -> {
              UUID orderId = invocation.getArgument(1);
              List<LogisticsDependencyGateway.OrderEquipmentRequirement> requirements =
                  invocation.getArgument(5);
              return replaceEquipmentReservationsRemotely(orderId, requirements);
            });
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
    verify(dependencies, never())
        .releaseAllOrderUnits(any(), any(), any(), anyString());

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
  void normalizedClientCreationReplaysByPhoneAndKeepsCanonicalClient()
      throws Exception {
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
    assertThat(
            jdbc.queryForObject("select count(*) from order_client", Long.class))
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
  void savingOrderKeepsItEditableUntilTheDraftShipmentStartsOrGetsFurnitureTasks()
      throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент сохранённого заказа");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    setRentalTerms(orderId, MANAGER_1, 2, UNIT_1, 2);
    UUID idempotencyKey = UUID.randomUUID();

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", idempotencyKey)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"4\""))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.status").value("SAVED"))
        .andExpect(jsonPath("$.unitCount").value(1));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", idempotencyKey)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(4));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "4")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("ETag", "\"4\""))
        .andExpect(jsonPath("$.version").value(4))
        .andExpect(jsonPath("$.status").value("SAVED"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where rental_order_id=? and document_type='SHIPMENT'",
                Long.class,
                orderId))
        .isZero();

    createRentalShipment(orderId, MANAGER_1, 4, UNIT_1, shipmentDate, "Водитель");

    mvc.perform(
            get("/api/logistics/v1/shipments")
                .param("warehouseId", WAREHOUSE_1.toString())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1))
        .andExpect(jsonPath("$[0].state").value("DRAFT"))
        .andExpect(jsonPath("$[0].scheduledDate").value(shipmentDate))
        .andExpect(jsonPath("$[0].driverSnapshot").value("Водитель"))
        .andExpect(jsonPath("$[0].rentalOrderId").value(orderId.toString()))
        .andExpect(jsonPath("$[0].lines.length()").value(1))
        .andExpect(jsonPath("$[0].lines[0].assetId").value(UNIT_1.toString()))
        .andExpect(
            jsonPath("$[0].lines[0].tenantSnapshot")
                .value("Клиент сохранённого заказа"));

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
                .content(updateOrderBody(4, orderClientId(orderId)))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"))
        .andExpect(jsonPath("$.permissions.canEdit").value(true));

    assertAuditCount(orderId, "ORDER_SAVED", 1);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_document where rental_order_id=? and document_type='SHIPMENT'",
                Long.class,
                orderId))
        .isOne();
  }

  @Test
  void startingShipmentLocksTheSavedBookingAgainstFurtherEdits() throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент начатой отгрузки");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    setRentalTerms(orderId, MANAGER_1, 2, UNIT_1, 1);
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"));

    JsonNode shipment = createRentalShipment(orderId, MANAGER_1, 4, UNIT_1, shipmentDate, "Водитель");
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
                .with(manager(MANAGER_1, "manager-one")))
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
  void createdShipmentFurnitureTaskLocksTheSavedBookingAgainstFurtherEdits()
      throws Exception {
    String shipmentDate = futureDate(1);
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент задания на мебель");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    setRentalTerms(orderId, MANAGER_1, 2, UNIT_1, 1);
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "3")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("SAVED"));

    JsonNode shipment = createRentalShipment(orderId, MANAGER_1, 4, UNIT_1, shipmentDate, "Водитель");
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
  void rentalTermsRequireACompleteDraftVectorAndKeepPartialShipmentDatesIndependent()
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

    JsonNode terms =
        setRentalTerms(
            orderId,
            MANAGER_1,
            3,
            Map.of(UNIT_1, 2L, UNIT_2, 3L));
    assertThat(rentalTerm(terms, UNIT_1).get("rentalMonths").longValue()).isEqualTo(2);
    assertThat(rentalTerm(terms, UNIT_1).get("shipmentDate").isNull()).isTrue();
    assertThat(rentalTerm(terms, UNIT_1).get("returnDate").isNull()).isTrue();

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/save", orderId)
                .param("expectedVersion", "4")
                .header("Idempotency-Key", UUID.randomUUID())
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(5))
        .andExpect(jsonPath("$.status").value("SAVED"));

    JsonNode firstShipment =
        createRentalShipment(orderId, MANAGER_1, 5, UNIT_1, firstDate.toString(), "Водитель 1");
    JsonNode afterFirstShipment =
        json(
            mvc.perform(
                    get("/api/logistics/v1/orders/{orderId}", orderId)
                        .with(manager(MANAGER_1, "manager-one")))
                .andExpect(status().isOk())
                .andReturn());
    assertThat(rentalTerm(afterFirstShipment, UNIT_1).get("shipmentDate").stringValue())
        .isEqualTo(firstDate.toString());
    assertThat(rentalTerm(afterFirstShipment, UNIT_1).get("returnDate").stringValue())
        .isEqualTo(firstDate.plusMonths(2).toString());
    assertThat(rentalTerm(afterFirstShipment, UNIT_2).get("shipmentDate").isNull()).isTrue();

    createRentalShipment(orderId, MANAGER_1, 5, UNIT_2, secondDate.toString(), "Водитель 2");
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
                      "expectedVersion": 5,
                      "driverSnapshot": "Повтор",
                      "scheduledDate": "%s",
                      "unitIds": ["%s"]
                    }
                    """
                        .formatted(secondDate.plusDays(1), UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict());

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}/rental-terms", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "expectedVersion": 5,
                      "terms": [
                        {"unitId":"%s", "rentalMonths":4},
                        {"unitId":"%s", "rentalMonths":3}
                      ]
                    }
                    """
                        .formatted(UNIT_1, UNIT_2))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_RENTAL_TERM_ASSIGNED"));

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
                      "expectedVersion": 5,
                      "terms": [{"unitId":"%s", "additionalMonths":1}]
                    }
                    """
                        .formatted(UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(6));
    assertThat(
            jdbc.queryForObject(
                "select rental_months from rental_order_unit_term where order_id=? and rental_item_id=?",
                Long.class,
                orderId,
                UNIT_1))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select return_date::text from rental_order_unit_term where order_id=? and rental_item_id=?",
                String.class,
                orderId,
                UNIT_1))
        .isEqualTo(firstDate.plusMonths(3).toString());
  }

  @Test
  void unsupportedClientTypeIsRejectedBeforeMutation() throws Exception {
    mvc.perform(
            post("/api/logistics/v1/clients")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"clientType":"ENTREPRENEUR","displayName":"Петров А.В.","phone":"+79990000001"}
                    """)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());
    assertThat(
            jdbc.queryForObject("select count(*) from order_client", Long.class))
        .isZero();
  }

  @Test
  void missingRequiredExpectedVersionIsRejectedBeforeAssetCall() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент validation");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"unitId":"%s"}
                    """
                        .formatted(UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isBadRequest());
    verify(dependencies, never())
        .reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString());
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
                        {"clientType":"LEGAL_ENTITY","displayName":"ООО Существующий","phone":"+79990000001"}
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
    assertThat(jdbc.queryForObject("select count(*) from order_client", Long.class))
        .isOne();
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
            jdbc.queryForObject(
                "select version from rental_order where id=?", Long.class, orderId))
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
            jdbc.queryForObject(
                "select version from rental_order where id=?", Long.class, orderId))
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
  void updateOrderRequiresVisibleEditableDraftExistingClientAndCurrentVersion()
      throws Exception {
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
  void warehouseSelectionRetryReplaysWithoutASecondEffectOrVersionBump()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент retry");
    UUID key = UUID.randomUUID();
    String body =
        """
        {"expectedVersion":0,"warehouseId":"%s"}
        """
            .formatted(WAREHOUSE_1);
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}/warehouse", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1));
    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}/warehouse", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(1));
    verify(dependencies, times(1)).readWarehouseIdentity(WAREHOUSE_1);
    assertThat(
            jdbc.queryForObject(
                "select version from rental_order where id=?", Long.class, orderId))
        .isEqualTo(1);
  }

  @Test
  void warehouseReservationEquipmentReleaseAndCancelAreVersionedAndAudited()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент workflow");
    JsonNode warehouse = selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    assertThat(warehouse.get("version").longValue()).isEqualTo(1);

    JsonNode added = addUnit(orderId, MANAGER_1, UNIT_1, 1);
    assertThat(added.get("version").longValue()).isEqualTo(2);
    assertThat(added.at("/units/0/added").booleanValue()).isTrue();
    assertThat(added.at("/units/0/unit/id").stringValue()).isEqualTo(UNIT_1.toString());

    mvc.perform(
            put("/api/logistics/v1/orders/{orderId}/warehouse", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":2,"warehouseId":"%s"}
                    """
                        .formatted(WAREHOUSE_2))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ORDER_WAREHOUSE_LOCKED"));

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
  void addingTheSameActiveUnitWithAFreshKeyIsASemanticReplay() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент semantic replay");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":2,"unitId":"%s"}
                    """
                        .formatted(UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.unitCount").value(1));

    verify(dependencies, times(1))
        .reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and event_type='UNIT_ADDED'",
                Long.class,
                orderId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and event_type='RESERVATION_CREATED'",
                Long.class,
                orderId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select version from rental_order where id=?", Long.class, orderId))
        .isEqualTo(2);
  }

  @Test
  void addRetryAdoptsRemoteReservationAfterUnknownOutcome() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент add recovery");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    UUID key = UUID.randomUUID();
    AtomicBoolean failAfterEffect = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              LogisticsDependencyGateway.OrderUnitReservation reserved =
                  reserveRemotely(
                      invocation.getArgument(1),
                      invocation.getArgument(2),
                      invocation.getArgument(3),
                      invocation.getArgument(7),
                      invocation.getArgument(8));
              if (failAfterEffect.getAndSet(false)) throw transientDependencyFailure();
              return reserved;
            })
        .when(dependencies)
        .reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString());

    String body =
        """
        {"expectedVersion":1,"unitId":"%s"}
        """
            .formatted(UNIT_1);
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("ORDER_ASSET_SERVICE_UNAVAILABLE"));
    assertThat(activeUnitOwners.get(UNIT_1)).isEqualTo(orderId);
    assertThat(
            jdbc.queryForObject(
                "select version from rental_order where id=?", Long.class, orderId))
        .isEqualTo(1);

    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body)
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(2))
        .andExpect(jsonPath("$.unitCount").value(1));
    verify(dependencies, times(1))
        .reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString());
    assertAuditCount(orderId, "UNIT_ADDED", 1);
    assertAuditCount(orderId, "RESERVATION_CREATED", 1);
  }

  @Test
  void removeRetryRecoversMissingAddAndReleaseEvidenceAfterUnknownOutcome()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент remove recovery");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    reserveRemotely(
        orderId,
        WAREHOUSE_1,
        UNIT_1,
        MANAGER_1,
        "RENTAL_MANAGER");
    UUID key = UUID.randomUUID();
    AtomicBoolean failAfterEffect = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
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
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.unitCount").value(0));
    verify(dependencies, times(2))
        .releaseOrderUnit(any(), any(), any(), any(), anyString());
    assertAuditCount(orderId, "UNIT_ADDED", 1);
    assertAuditCount(orderId, "RESERVATION_CREATED", 1);
    assertAuditCount(orderId, "UNIT_REMOVED", 1);
    assertAuditCount(orderId, "RESERVATION_RELEASED", 1);
    assertThat(
            jdbc.queryForObject(
                "select actor_subject_id from rental_order_audit_event where order_id=? and event_type='UNIT_ADDED'",
                UUID.class,
                orderId))
        .isEqualTo(MANAGER_1);
    assertThat(
            jdbc.queryForObject(
                "select actor_role from rental_order_audit_event where order_id=? and event_type='UNIT_ADDED'",
                String.class,
                orderId))
        .isEqualTo("RENTAL_MANAGER");
    assertThat(
            jdbc.queryForObject(
                "select actor_subject_id from rental_order_audit_event where order_id=? and event_type='UNIT_REMOVED'",
                UUID.class,
                orderId))
        .isEqualTo(ADMIN);
    assertThat(
            jdbc.queryForObject(
                "select actor_role from rental_order_audit_event where order_id=? and event_type='UNIT_REMOVED'",
                String.class,
                orderId))
        .isEqualTo("SYSTEM_ADMIN");
  }

  @Test
  void cancelRetryUsesReleaseAllReplayAfterUnknownOutcome() throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент cancel recovery");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    addUnit(orderId, MANAGER_1, UNIT_1, 1);
    UUID key = UUID.randomUUID();
    AtomicBoolean failAfterEffect = new AtomicBoolean(true);
    doAnswer(
            invocation -> {
              List<LogisticsDependencyGateway.OrderUnitReservation> released =
                  releaseAllRemotely(
                      invocation.getArgument(0), invocation.getArgument(1));
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
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.unitCount").value(0));
    verify(dependencies, times(2))
        .releaseAllOrderUnits(any(), any(), any(), anyString());
    assertAuditCount(orderId, "UNIT_REMOVED", 1);
    assertAuditCount(orderId, "RESERVATION_RELEASED", 1);
    assertAuditCount(orderId, "ORDER_CANCELLED", 1);
  }

  @Test
  void reservationConflictReturnsQuicklyAndPersistsAuditAfterOrderLockEnds()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент conflict");
    selectWarehouse(orderId, MANAGER_1, WAREHOUSE_1, 0);
    doThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
                "UNIT_ALREADY_RESERVED",
                "conflict",
                null))
        .when(dependencies)
        .reserveOrderUnit(
            any(), any(), any(), any(), any(), anyString(), any(), any(), anyString());

    long started = System.nanoTime();
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"unitId":"%s"}
                    """
                        .formatted(UNIT_1))
                .with(manager(MANAGER_1, "manager-one")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("UNIT_ALREADY_RESERVED"));
    assertThat((System.nanoTime() - started) / 1_000_000_000.0).isLessThan(5.0);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from rental_order_audit_event where order_id=? and event_type='UNIT_ADD_CONFLICT'",
                Long.class,
                orderId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select version from rental_order where id=?", Long.class, orderId))
        .isEqualTo(1);
  }

  @Test
  void managerReadsOwnAssignedOrderButWarehouseCommandsNeedTheirOwnGrant()
      throws Exception {
    UUID orderId = createOrder(MANAGER_1, "manager-one", "Клиент grant");
    selectWarehouseAs(orderId, ADMIN, "SYSTEM_ADMIN", WAREHOUSE_1, 0, List.of());

    mvc.perform(
            get("/api/logistics/v1/orders/{orderId}", orderId)
                .with(managerWithoutWarehouse(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(orderId.toString()));
    mvc.perform(
            get("/api/logistics/v1/orders")
                .with(managerWithoutWarehouse(MANAGER_1, "manager-one")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.totalElements").value(1));
    mvc.perform(
            post("/api/logistics/v1/orders/{orderId}/units", orderId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"expectedVersion":1,"unitId":"%s"}
                    """
                        .formatted(UNIT_1))
                .with(managerWithoutWarehouse(MANAGER_1, "manager-one")))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("LOGISTICS_FORBIDDEN"));
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

  private UUID createOrder(UUID subjectId, String username, String clientName)
      throws Exception {
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
                          "phone": "%s"
                        }
                        }
                        """
                            .formatted(clientName, nextTestPhone()))
                    .with(manager(subjectId, username)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.managerId").value(subjectId.toString()))
            .andReturn();
    return UUID.fromString(json(result).get("id").stringValue());
  }

  private UUID createClient(UUID subjectId, String username, String clientName)
      throws Exception {
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
                          "phone": "%s"
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

  private static String updateOrderBody(long expectedVersion, UUID clientId) {
    return "{\"expectedVersion\":%d,\"clientId\":\"%s\"}"
        .formatted(expectedVersion, clientId);
  }

  private JsonNode setRentalTerms(
      UUID orderId, UUID subjectId, long expectedVersion, UUID unitId, long rentalMonths)
      throws Exception {
    return setRentalTerms(orderId, subjectId, expectedVersion, Map.of(unitId, rentalMonths));
  }

  private JsonNode setRentalTerms(
      UUID orderId, UUID subjectId, long expectedVersion, Map<UUID, Long> terms) throws Exception {
    String termValues =
        terms.entrySet().stream()
            .map(
                entry ->
                    "{\"unitId\":\"%s\",\"rentalMonths\":%d}"
                        .formatted(entry.getKey(), entry.getValue()))
            .collect(java.util.stream.Collectors.joining(","));
    MvcResult result =
        mvc.perform(
                put("/api/logistics/v1/orders/{orderId}/rental-terms", orderId)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {
                          "expectedVersion": %d,
                          "terms": [%s]
                        }
                        """
                            .formatted(expectedVersion, termValues))
                    .with(manager(subjectId, "manager-one")))
            .andExpect(status().isOk())
            .andReturn();
    return json(result);
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
      UUID orderId, UUID subjectId, UUID warehouseId, long expectedVersion)
      throws Exception {
    return selectWarehouseAs(
        orderId,
        subjectId,
        "RENTAL_MANAGER",
        warehouseId,
        expectedVersion,
        List.of(
            Map.of(
                "warehouseId", warehouseId.toString(),
                "level", "EDIT")));
  }

  private JsonNode selectWarehouseAs(
      UUID orderId,
      UUID subjectId,
      String role,
      UUID warehouseId,
      long expectedVersion,
      List<Map<String, String>> grants)
      throws Exception {
    MvcResult result =
        mvc.perform(
                put("/api/logistics/v1/orders/{orderId}/warehouse", orderId)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"expectedVersion":%d,"warehouseId":"%s"}
                        """
                            .formatted(expectedVersion, warehouseId))
                    .with(user(subjectId, role, subjectId.toString(), grants)))
            .andExpect(status().isOk())
            .andReturn();
    return json(result);
  }

  private JsonNode addUnit(
      UUID orderId, UUID subjectId, UUID unitId, long expectedVersion)
      throws Exception {
    MvcResult result =
        mvc.perform(
                post("/api/logistics/v1/orders/{orderId}/units", orderId)
                    .header("Idempotency-Key", UUID.randomUUID())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        """
                        {"expectedVersion":%d,"unitId":"%s"}
                        """
                            .formatted(expectedVersion, unitId))
                    .with(manager(subjectId, "manager")))
            .andExpect(status().isCreated())
            .andReturn();
    return json(result);
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
    return user(
        subjectId,
        "RENTAL_MANAGER",
        username,
        List.of(
            Map.of(
                "warehouseId", WAREHOUSE_1.toString(),
                "level", "EDIT"),
            Map.of(
                "warehouseId", WAREHOUSE_2.toString(),
                "level", "EDIT")));
  }

  private static JwtRequestPostProcessor managerWithoutWarehouse(
      UUID subjectId, String username) {
    return user(subjectId, "RENTAL_MANAGER", username, List.of());
  }

  private static JwtRequestPostProcessor admin() {
    return user(ADMIN, "SYSTEM_ADMIN", "admin", List.of());
  }

  private static JwtRequestPostProcessor user(
      UUID subjectId,
      String role,
      String username,
      List<Map<String, String>> warehouseAccess) {
    return jwt()
        .jwt(
            token ->
                token.subject(subjectId.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read rwms.write")
                    .claim("global_role", role)
                    .claim("preferred_username", username)
                    .claim("rentalAccess", true)
                    .claim("warehouse_access", warehouseAccess));
  }

  private LogisticsDependencyGateway.OrderUnitReservation reserveRemotely(
      UUID orderId,
      UUID warehouseId,
      UUID unitId,
      UUID actor,
      String role) {
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
    LogisticsDependencyGateway.OrderUnitReservation receipt =
        releaseReceipts.get(idempotencyKey);
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
          UUID orderId, List<LogisticsDependencyGateway.OrderEquipmentRequirement> requirements) {
    LinkedHashMap<UUID, LogisticsDependencyGateway.OrderEquipmentReservation> current =
        equipmentReservations.computeIfAbsent(orderId, ignored -> new LinkedHashMap<>());
    current.clear();
    for (LogisticsDependencyGateway.OrderEquipmentRequirement requirement : requirements) {
      current.put(
          requirement.equipmentId(),
          new LogisticsDependencyGateway.OrderEquipmentReservation(
              requirement.equipmentId(),
              "Стул",
              requirement.quantity(),
              10));
    }
    return List.copyOf(current.values());
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
