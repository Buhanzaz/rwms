package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.equipment.service.EquipmentMovementTaskProcessor;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
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
      "rwms.logistics.owner-proof.relay-enabled=false",
      "rwms.logistics.equipment-movement.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class EquipmentMovementTaskIntegrationTest {
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000007101");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000007201");
  private static final UUID TARGET_WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000007202");
  private static final UUID EQUIPMENT = UUID.fromString("00000000-0000-0000-0000-000000007301");
  private static final UUID CABIN = UUID.fromString("00000000-0000-0000-0000-000000007401");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired EquipmentMovementTaskProcessor processor;
  @MockitoBean LogisticsDependencyGateway dependencies;

  private final AtomicBoolean workerDone = new AtomicBoolean(false);
  private final AtomicReference<OffsetDateTime> workerDoneAt = new AtomicReference<>();
  private UUID reservationId;
  private UUID boardTaskId;
  private UUID sourceBalanceId;

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_outbox,
          equipment_movement_task_line,
          equipment_movement_task
        cascade
        """);
    reset(dependencies);
    workerDone.set(false);
    workerDoneAt.set(null);
    reservationId = UUID.randomUUID();
    boardTaskId = UUID.randomUUID();
    sourceBalanceId = UUID.randomUUID();

    when(dependencies.productionReady()).thenReturn(true);
    when(
            dependencies.warehouseAdmission(
                WAREHOUSE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                WAREHOUSE,
                19,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING,
                true));
    when(
            dependencies.warehouseAdmission(
                TARGET_WAREHOUSE,
                LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                TARGET_WAREHOUSE,
                23,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    WAREHOUSE, "UTC", invocation.getArgument(1)));
    when(dependencies.warehouseTimeZoneAt(eq(TARGET_WAREHOUSE), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    TARGET_WAREHOUSE, "UTC", invocation.getArgument(1)));
    when(
            dependencies.acquireEquipmentMovementReservation(
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                any(),
                anyLong(),
                anyLong(),
                any(),
                any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementReservation(
                    reservationId,
                    0,
                    "LOGISTICS_EQUIPMENT_MOVEMENT",
                    invocation.getArgument(1),
                    invocation.getArgument(2),
                    invocation.getArgument(3),
                    "Стол",
                    sourceBalanceId,
                    invocation.getArgument(4),
                    invocation.getArgument(5),
                    invocation.getArgument(6),
                    invocation.getArgument(8),
                    "ACTIVE",
                    invocation.getArgument(9),
                    null));
    when(
            dependencies.registerEquipmentMovementTask(
                any(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementBoardTask(
                    boardTaskId,
                    0,
                    invocation.getArgument(0),
                    invocation.getArgument(1),
                    "ACTIVE",
                    null));
    when(dependencies.readEquipmentMovementTask(any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementBoardTask(
                    boardTaskId,
                    1,
                    WAREHOUSE,
                    invocation.getArgument(0),
                    workerDone.get() ? "DONE" : "ACTIVE",
                    workerDone.get() ? workerDoneAt.get() : null));
    when(dependencies.executeEquipmentMovement(any(), any(), any()))
        .thenAnswer(
            invocation -> {
              UUID movementId = invocation.getArgument(1);
              @SuppressWarnings("unchecked")
              List<LogisticsDependencyGateway.EquipmentMovementExecutionRequestLine> requested =
                  invocation.getArgument(2);
              return new LogisticsDependencyGateway.EquipmentMovementExecution(
                  movementId,
                  requested.stream()
                      .map(
                          line ->
                              new LogisticsDependencyGateway.EquipmentMovementExecutionLine(
                                  line.reservationId(),
                                  line.expectedReservationVersion() + 1,
                                  line.lineId(),
                                  new LogisticsDependencyGateway.EquipmentMovementEvent(
                                      UUID.randomUUID(),
                                      0,
                                      EQUIPMENT,
                                      UUID.randomUUID(),
                                      UUID.randomUUID(),
                                      2,
                                      "STOCK_TO_CABIN",
                                      OffsetDateTime.now(ZoneOffset.UTC))))
                      .toList());
            });
    when(dependencies.cancelEquipmentMovementTask(any(), anyLong()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementBoardTask(
                    boardTaskId,
                    1,
                    WAREHOUSE,
                    invocation.getArgument(0),
                    "CANCELLED",
                    null));
    when(
            dependencies.releaseEquipmentMovementReservation(
                any(), any(), anyLong(), any(), any()))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.EquipmentMovementReservation(
                    invocation.getArgument(1),
                    ((Long) invocation.getArgument(2)) + 1,
                    "LOGISTICS_EQUIPMENT_MOVEMENT",
                    invocation.getArgument(3),
                    invocation.getArgument(4),
                    EQUIPMENT,
                    "Стол",
                    UUID.randomUUID(),
                    WAREHOUSE,
                    null,
                    "STOCK",
                    2,
                    "RELEASED",
                    OffsetDateTime.now(ZoneOffset.UTC),
                    null));
  }

  @Test
  void createsReservesAndRegistersButExecutesOnlyAfterWorkerCompletion() throws Exception {
    MvcResult created = createTask();
    UUID taskId = UUID.fromString(json(created).get("id").stringValue());

    assertThat(json(created).get("state").stringValue()).isEqualTo("AWAITING_WORKER");
    verify(dependencies, never()).executeEquipmentMovement(any(), any(), any());

    workerDoneAt.set(OffsetDateTime.now(ZoneOffset.UTC));
    workerDone.set(true);
    jdbc.update(
        "update equipment_movement_task set next_attempt_at=clock_timestamp() where id=?", taskId);
    processor.processUntilIdle(taskId);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/logistics/v1/equipment-movement-tasks/{taskId}", taskId)
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("COMPLETED"))
        .andExpect(jsonPath("$.lines[0].state").value("EXECUTED"));
    verify(dependencies).executeEquipmentMovement(any(), any(), any());
  }

  @Test
  void exactCreateReplaySucceedsWithoutAdmissionDependencyAndAlteredPayloadConflicts()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
    String request = taskBody(deadline, 15);
    MvcResult created =
        mvc.perform(
                post("/api/logistics/v1/equipment-movement-tasks")
                    .header("Idempotency-Key", idempotencyKey)
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(request)
                    .with(actor()))
            .andExpect(status().isCreated())
            .andReturn();
    UUID taskId = UUID.fromString(json(created).get("id").stringValue());

    when(dependencies.productionReady()).thenReturn(false);

    mvc.perform(
            post("/api/logistics/v1/equipment-movement-tasks")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(taskId.toString()));
    mvc.perform(
            post("/api/logistics/v1/equipment-movement-tasks")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(taskBody(deadline, 16))
                .with(actor()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));

    assertThat(jdbc.queryForObject("select count(*) from equipment_movement_task", Long.class))
        .isOne();
    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where operation_id=? and warehouse_id=?
                """,
                taskId,
                WAREHOUSE))
        .containsEntry("admission_direction", "OUTGOING")
        .containsEntry("admission_warehouse_version", 19L);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark_outbox where operation_id=?",
                Long.class,
                taskId))
        .isOne();
    verify(dependencies)
        .warehouseAdmission(
            WAREHOUSE,
            LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING);
    verify(dependencies).warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class));
    verify(dependencies)
        .acquireEquipmentMovementReservation(
            any(), any(), any(), any(), any(), any(), any(), anyLong(), anyLong(), any(), any());
    verify(dependencies).registerEquipmentMovementTask(any(), any(), any(), any(), any(), any());
  }

  @Test
  void rejectsAnExecutableMovementWithoutAPositiveDuration() throws Exception {
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);

    mvc.perform(
            post("/api/logistics/v1/equipment-movement-tasks")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"warehouseId":"%s","unitNumber":"CAB-701",
                    "deadlineAt":"%s","lines":[{"equipmentId":"%s","sourceRentalItemId":null,
                    "sourceLocationKind":"STOCK","expectedSourceBalanceVersion":4,
                    "targetRentalItemId":"%s","targetLocationKind":"CABIN_NON_RENTED","quantity":2}]}
                    """.formatted(WAREHOUSE, deadline, EQUIPMENT, CABIN))
                .with(actor()))
        .andExpect(status().isBadRequest());
  }


  @Test
  void cancellationCancelsBoardTaskAndReleasesReservationWithoutPhysicalMove() throws Exception {
    MvcResult created = createTask();
    UUID taskId = UUID.fromString(json(created).get("id").stringValue());
    long version = json(created).get("version").longValue();

    mvc.perform(
            post("/api/logistics/v1/equipment-movement-tasks/{taskId}/cancel", taskId)
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":%d}".formatted(version))
                .with(actor()))
        .andExpect(status().isAccepted())
        .andExpect(jsonPath("$.state").value("CANCELLED"))
        .andExpect(jsonPath("$.lines[0].state").value("RELEASED"));

    verify(dependencies).cancelEquipmentMovementTask(any(), anyLong());
    verify(dependencies).releaseEquipmentMovementReservation(any(), any(), anyLong(), any(), any());
    verify(dependencies, never()).executeEquipmentMovement(any(), any(), any());
  }

  @Test
  void expiredDeadlineCancelsTheWorkerTaskAndReleasesReservationWithoutPhysicalMove()
      throws Exception {
    MvcResult created = createTask();
    UUID taskId = UUID.fromString(json(created).get("id").stringValue());

    jdbc.update(
        "update equipment_movement_task set deadline_at=clock_timestamp(), next_attempt_at=clock_timestamp() where id=?",
        taskId);
    processor.processUntilIdle(taskId);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/logistics/v1/equipment-movement-tasks/{taskId}", taskId)
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("EXPIRED"))
        .andExpect(jsonPath("$.terminalState").value("EXPIRED"))
        .andExpect(jsonPath("$.lines[0].state").value("RELEASED"));

    verify(dependencies).cancelEquipmentMovementTask(any(), anyLong());
    verify(dependencies).releaseEquipmentMovementReservation(any(), any(), anyLong(), any(), any());
    verify(dependencies, never()).executeEquipmentMovement(any(), any(), any());
  }

  @Test
  void completedBeforeDeadlineStillExecutesWhenThePollRunsJustAfterTheDeadline()
      throws Exception {
    MvcResult created = createTask();
    UUID taskId = UUID.fromString(json(created).get("id").stringValue());

    workerDoneAt.set(OffsetDateTime.now(ZoneOffset.UTC));
    workerDone.set(true);
    jdbc.update(
        "update equipment_movement_task set deadline_at=clock_timestamp(), next_attempt_at=clock_timestamp() where id=?",
        taskId);
    processor.processUntilIdle(taskId);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/logistics/v1/equipment-movement-tasks/{taskId}", taskId)
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("COMPLETED"))
        .andExpect(jsonPath("$.lines[0].state").value("EXECUTED"));

    verify(dependencies).executeEquipmentMovement(any(), any(), any());
    verify(dependencies, never()).cancelEquipmentMovementTask(any(), anyLong());
  }

  @Test
  void completedAfterDeadlineClosesTheBoardTaskButRequiresEquipmentReconciliation()
      throws Exception {
    MvcResult created = createTask();
    UUID taskId = UUID.fromString(json(created).get("id").stringValue());

    OffsetDateTime deadline =
        jdbc.queryForObject(
            "select deadline_at from equipment_movement_task where id=?",
            OffsetDateTime.class,
            taskId);
    workerDoneAt.set(deadline.plusMinutes(1));
    workerDone.set(true);
    jdbc.update(
        "update equipment_movement_task set next_attempt_at=clock_timestamp() where id=?",
        taskId);
    processor.processUntilIdle(taskId);

    mvc.perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/logistics/v1/equipment-movement-tasks/{taskId}", taskId)
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.state").value("RECONCILIATION_REQUIRED"))
        .andExpect(
            jsonPath("$.failureCode").value("TASK_BOARD_COMPLETED_AFTER_RESERVATION_EXPIRY"));

    verify(dependencies, never()).executeEquipmentMovement(any(), any(), any());
    verify(dependencies, never()).cancelEquipmentMovementTask(any(), anyLong());
    verify(dependencies, never())
        .releaseEquipmentMovementReservation(any(), any(), anyLong(), any(), any());
  }

  private MvcResult createTask() throws Exception {
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
    return mvc.perform(
            post("/api/logistics/v1/equipment-movement-tasks")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {"warehouseId":"%s","unitNumber":"CAB-701","plannedDurationMinutes":15,
                    "deadlineAt":"%s","lines":[{"equipmentId":"%s","sourceRentalItemId":null,
                    "sourceLocationKind":"STOCK","expectedSourceBalanceVersion":4,
                    "targetRentalItemId":"%s","targetLocationKind":"CABIN_NON_RENTED","quantity":2}]}
                    """.formatted(WAREHOUSE, deadline, EQUIPMENT, CABIN))
                .with(actor()))
        .andExpect(status().isCreated())
        .andExpect(header().exists("ETag"))
        .andExpect(jsonPath("$.lines[0].quantity").value(2))
        .andReturn();
  }

  private MvcResult createTransferTask() throws Exception {
    OffsetDateTime deadline = OffsetDateTime.now(ZoneOffset.UTC).plusHours(2);
    return mvc.perform(
            post("/api/logistics/v1/equipment-movement-tasks")
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(transferTaskBody(deadline, 15))
                .with(actor()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.lines[0].targetWarehouseId").value(TARGET_WAREHOUSE.toString()))
        .andReturn();
  }

  private static String taskBody(OffsetDateTime deadline, int plannedDurationMinutes) {
    return """
        {"warehouseId":"%s","unitNumber":"CAB-701","plannedDurationMinutes":%d,
        "deadlineAt":"%s","lines":[{"equipmentId":"%s","sourceRentalItemId":null,
        "sourceLocationKind":"STOCK","expectedSourceBalanceVersion":4,
        "targetRentalItemId":"%s","targetLocationKind":"CABIN_NON_RENTED","quantity":2}]}
        """
        .formatted(
            WAREHOUSE, plannedDurationMinutes, deadline, EQUIPMENT, CABIN);
  }

  private static String transferTaskBody(
      OffsetDateTime deadline, int plannedDurationMinutes) {
    return """
        {"warehouseId":"%s","targetWarehouseId":"%s","unitNumber":"CAB-701",
        "plannedDurationMinutes":%d,"deadlineAt":"%s",
        "lines":[{"equipmentId":"%s","sourceRentalItemId":null,
        "sourceLocationKind":"STOCK","expectedSourceBalanceVersion":4,
        "targetRentalItemId":"%s","targetLocationKind":"CABIN_NON_RENTED","quantity":2}]}
        """
        .formatted(
            WAREHOUSE,
            TARGET_WAREHOUSE,
            plannedDurationMinutes,
            deadline,
            EQUIPMENT,
            CABIN);
  }

  private static JwtRequestPostProcessor actor() {
    return jwt()
        .jwt(
            value ->
                value
                    .subject(ACTOR.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read rwms.write")
                    .claim(
                        "warehouse_access",
                        List.of(
                            Map.of("warehouseId", WAREHOUSE.toString(), "level", "MANAGE"),
                            Map.of(
                                "warehouseId",
                                TARGET_WAREHOUSE.toString(),
                                "level",
                                "MANAGE"))));
  }

  private JsonNode json(MvcResult result) throws Exception {
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }
}
