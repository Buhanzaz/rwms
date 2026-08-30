package dev.buhanzaz.rwms.logistics.driver.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Exercises the public capital-repair promotion command through MVC, JPA and the actual driver
 * workflow. The task-board dependency deliberately exposes an empty board snapshot after
 * registration: the promotion must use the point task snapshot and still enter CURRENT.
 */
@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class DriverCapitalRepairPromotionIntegrationTest {
  private static final UUID ACTOR = UUID.fromString("00000000-0000-0000-0000-000000009101");
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000009201");
  private static final UUID REPAIR = UUID.fromString("00000000-0000-0000-0000-000000009301");
  private static final UUID CABIN = UUID.fromString("00000000-0000-0000-0000-000000009401");
  private static final UUID QUEUE_DEFINITION =
      UUID.fromString("00000000-0000-0000-0000-000000009501");
  private static final UUID WORK_QUEUE = UUID.fromString("00000000-0000-0000-0000-000000009601");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @MockitoBean LogisticsDependencyGateway dependencies;

  private final AtomicReference<LogisticsDependencyGateway.DriverBoardTask> boardTask =
      new AtomicReference<>();
  private final AtomicInteger registrations = new AtomicInteger();

  @BeforeEach
  void resetState() {
    jdbc.execute(
        """
        truncate table
          logistics_warehouse_admission_intent,
          warehouse_operation_mark_outbox,
          driver_logistics_task
        cascade
        """);
    reset(dependencies);
    boardTask.set(null);
    registrations.set(0);

    when(dependencies.productionReady()).thenReturn(true);
    when(
            dependencies.warehouseAdmission(
                WAREHOUSE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                WAREHOUSE,
                29,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING,
                true));
    when(dependencies.readWarehouseIdentity(WAREHOUSE))
        .thenReturn(new LogisticsDependencyGateway.WarehouseIdentity(WAREHOUSE, 0, true, "UTC"));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                new LogisticsDependencyGateway.WarehouseTimeZone(
                    WAREHOUSE, "UTC", invocation.getArgument(1)));
    when(dependencies.readWarehouseDriverQueue(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseDriverQueue(
                WAREHOUSE, QUEUE_DEFINITION, WORK_QUEUE));
    when(dependencies.readRentalItemSnapshot(CABIN))
        .thenReturn(
            new LogisticsDependencyGateway.RentalItemSnapshot(
                CABIN, 0, WAREHOUSE, "БЫТ-901", "CAPITAL_REPAIR", List.of()));
    when(dependencies.readCabinMediaSnapshots(WAREHOUSE, List.of(CABIN)))
        .thenReturn(
            List.of(
                new LogisticsDependencyGateway.CabinMediaSnapshot(CABIN, 0, List.of())));
    when(dependencies.readCapitalRepair(REPAIR))
        .thenReturn(
            new LogisticsDependencyGateway.CapitalRepair(
                REPAIR,
                CABIN,
                WAREHOUSE,
                2,
                new LogisticsDependencyGateway.RepairComplexitySnapshot(
                    "CAPITAL", "Капитальный ремонт", "#AA0000", "720", true),
                0));
    when(dependencies.readRepairPlaces(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.RepairPlaceProjection(
                WAREHOUSE, 6, 5, 0, 6, 0, 0, false, List.of()));

    // The board list may lag a just-created task. A point lookup is authoritative for explicit
    // promotion and is deliberately the only read path that contains the registered card here.
    when(dependencies.readDriverBoard(WAREHOUSE))
        .thenReturn(
            new LogisticsDependencyGateway.DriverBoardSnapshot(
                WAREHOUSE, WORK_QUEUE, 0, List.of(), List.of()));
    when(dependencies.registerDriverTask(
            eq(WAREHOUSE),
            any(),
            any(),
            any(),
            any(),
            any(),
            eq(QUEUE_DEFINITION),
            any(),
            anyInt(),
            any(),
            any()))
        .thenAnswer(
            invocation -> {
              UUID externalTaskId = invocation.getArgument(1);
              UUID localTaskId = invocation.getArgument(2);
              LocalDate scheduledDate = invocation.getArgument(7);
              int priority = invocation.getArgument(8);
              LogisticsDependencyGateway.DriverTaskAudience audience = invocation.getArgument(9);
              LogisticsDependencyGateway.DriverBoardTask registered =
                  new LogisticsDependencyGateway.DriverBoardTask(
                      UUID.nameUUIDFromBytes(("board:" + localTaskId).getBytes()),
                      0,
                      WAREHOUSE,
                      externalTaskId,
                      "Переместить бытовку на производство",
                      "БЫТ-901",
                      "Переместить бытовку на производство",
                      audience,
                      "ACTIVE",
                      scheduledDate,
                      "SCHEDULED",
                      priority,
                      false,
                      null,
                      UUID.nameUUIDFromBytes(("entry:" + localTaskId).getBytes()),
                      0,
                      "WAITING",
                      0);
              boardTask.set(registered);
              registrations.incrementAndGet();
              return registered;
            });
    when(dependencies.readDriverTask(any()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.DriverBoardTask value = boardTask.get();
              assertThat(value).isNotNull();
              assertThat(invocation.getArgument(0, UUID.class)).isEqualTo(value.externalTaskId());
              return value;
            });
    when(dependencies.setDriverTaskLane(any(), anyLong(), eq("CURRENT")))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.DriverBoardTask scheduled = boardTask.get();
              assertThat(invocation.getArgument(0, UUID.class))
                  .isEqualTo(scheduled.externalTaskId());
              assertThat(invocation.getArgument(1, Long.class))
                  .isEqualTo(scheduled.taskVersion());
              LogisticsDependencyGateway.DriverBoardTask current =
                  new LogisticsDependencyGateway.DriverBoardTask(
                      scheduled.taskId(),
                      scheduled.taskVersion() + 1,
                      scheduled.warehouseId(),
                      scheduled.externalTaskId(),
                      scheduled.title(),
                      scheduled.unitNumber(),
                      scheduled.taskText(),
                      scheduled.driverAudience(),
                      scheduled.status(),
                      scheduled.scheduledDate(),
                      "CURRENT",
                      scheduled.priority(),
                      scheduled.pinned(),
                      scheduled.doneAt(),
                      scheduled.entryId(),
                      scheduled.entryVersion() + 1,
                      scheduled.entryStatus(),
                      scheduled.queuePosition());
              boardTask.set(current);
              return current;
            });
    when(dependencies.moveDriverTask(
            any(), anyLong(), anyLong(), eq("SCHEDULED"), any(), anyInt(), isNull()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.DriverBoardTask scheduled = boardTask.get();
              LocalDate targetDate = invocation.getArgument(4, LocalDate.class);
              int targetIndex = invocation.getArgument(5, Integer.class);
              assertThat(invocation.getArgument(0, UUID.class))
                  .isEqualTo(scheduled.externalTaskId());
              assertThat(invocation.getArgument(1, Long.class))
                  .isEqualTo(scheduled.taskVersion());
              assertThat(invocation.getArgument(2, Long.class))
                  .isEqualTo(scheduled.entryVersion());
              LogisticsDependencyGateway.DriverBoardTask moved =
                  new LogisticsDependencyGateway.DriverBoardTask(
                      scheduled.taskId(),
                      scheduled.taskVersion(),
                      scheduled.warehouseId(),
                      scheduled.externalTaskId(),
                      scheduled.title(),
                      scheduled.unitNumber(),
                      scheduled.taskText(),
                      scheduled.driverAudience(),
                      scheduled.status(),
                      targetDate,
                      "SCHEDULED",
                      scheduled.priority(),
                      scheduled.pinned(),
                      scheduled.doneAt(),
                      scheduled.entryId(),
                      scheduled.entryVersion() + 1,
                      scheduled.entryStatus(),
                      targetIndex);
              boardTask.set(moved);
              return moved;
            });
    when(dependencies.cancelDriverTask(any(), anyLong()))
        .thenAnswer(
            invocation -> {
              LogisticsDependencyGateway.DriverBoardTask current = boardTask.get();
              assertThat(invocation.getArgument(0, UUID.class))
                  .isEqualTo(current.externalTaskId());
              assertThat(invocation.getArgument(1, Long.class))
                  .isEqualTo(current.taskVersion());
              LogisticsDependencyGateway.DriverBoardTask cancelled =
                  new LogisticsDependencyGateway.DriverBoardTask(
                      current.taskId(),
                      current.taskVersion() + 1,
                      current.warehouseId(),
                      current.externalTaskId(),
                      current.title(),
                      current.unitNumber(),
                      current.taskText(),
                      current.driverAudience(),
                      "CANCELLED",
                      current.scheduledDate(),
                      current.lane(),
                      current.priority(),
                      current.pinned(),
                      null,
                      current.entryId(),
                      current.entryVersion() + 1,
                      "CANCELLED",
                      current.queuePosition());
              boardTask.set(cancelled);
              return cancelled;
            });
  }

  @Test
  void promotesCapitalRepairToCurrentDespiteFullOrdinaryCapacityAndReplaysTheSameTask()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();

    mvc.perform(promote(idempotencyKey))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sourceType").value("CAPITAL_REPAIR"))
        .andExpect(jsonPath("$.kind").value("CAPITAL_TO_PRODUCTION"))
        .andExpect(jsonPath("$.state").value("CURRENT"));

    when(dependencies.productionReady()).thenReturn(false);

    mvc.perform(promote(idempotencyKey))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.state").value("CURRENT"));

    assertThat(registrations).hasValue(1);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from driver_logistics_task
                 where source_type = 'CAPITAL_REPAIR'
                   and source_id = ?
                   and task_kind = 'CAPITAL_TO_PRODUCTION'
                """,
                Long.class,
                REPAIR))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from driver_logistics_task
                 where repair_id = ?
                   and task_kind = 'DELIVER_TO_REPAIR'
                """,
                Long.class,
                REPAIR))
        .isZero();
    assertThat(
            jdbc.queryForObject(
                "select state from driver_logistics_task where source_id = ?",
                String.class,
                REPAIR))
        .isEqualTo("CURRENT");
    assertThat(
            jdbc.queryForMap(
                """
                select admission_direction,admission_warehouse_version
                  from warehouse_operation_mark_outbox
                 where operation_id=(select id from driver_logistics_task where source_id=?)
                   and warehouse_id=?
                """,
                REPAIR,
                WAREHOUSE))
        .containsEntry("admission_direction", "OUTGOING")
        .containsEntry("admission_warehouse_version", 29L);
    verify(dependencies, times(1))
        .warehouseAdmission(
            WAREHOUSE,
            LogisticsDependencyGateway.WarehouseOperationDirection.OUTGOING);
    verify(dependencies, times(1))
        .warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class));
    verify(dependencies, never())
        .transitionRepairPlace(any(), any(), any(), anyLong(), any());
    verify(dependencies, times(1)).setDriverTaskLane(any(), anyLong(), eq("CURRENT"));
  }

  @Test
  void exactNonUtcAutoTaskReplayUsesPersistedDateWithoutAdmissionDependency()
      throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    UUID sourceId = UUID.randomUUID();
    AtomicReference<LocalDate> admittedLocalDate = new AtomicReference<>();
    AtomicReference<LocalDate> admissionUtcDate = new AtomicReference<>();
    when(
            dependencies.warehouseAdmission(
                WAREHOUSE,
                LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING))
        .thenReturn(
            new LogisticsDependencyGateway.WarehouseOperationAdmission(
                WAREHOUSE,
                31,
                LogisticsDependencyGateway.WarehouseLifecycleState.ACTIVE,
                LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING,
                true));
    when(dependencies.warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation -> {
              OffsetDateTime at = invocation.getArgument(1);
              String zone = at.getHour() < 10 ? "Etc/GMT+12" : "Pacific/Kiritimati";
              admissionUtcDate.set(at.toLocalDate());
              admittedLocalDate.set(
                  at.toInstant().atZone(ZoneId.of(zone)).toLocalDate());
              return new LogisticsDependencyGateway.WarehouseTimeZone(
                  WAREHOUSE, zone, at);
            });
    String request = driverTaskBody(sourceId, 2);

    mvc.perform(
            post("/api/logistics/v1/driver-tasks")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .with(actor()))
        .andExpect(status().isCreated());
    UUID taskId =
        Objects.requireNonNull(
            jdbc.queryForObject(
                """
                select id from driver_logistics_task
                 where created_by_subject_id=? and idempotency_key=?
                """,
                UUID.class,
                ACTOR,
                idempotencyKey));
    assertThat(admittedLocalDate.get()).isNotEqualTo(admissionUtcDate.get());
    assertThat(
            jdbc.queryForObject(
                "select scheduled_date from driver_logistics_task where id=?",
                LocalDate.class,
                taskId))
        .isEqualTo(admittedLocalDate.get());

    when(dependencies.productionReady()).thenReturn(false);

    mvc.perform(
            post("/api/logistics/v1/driver-tasks")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(request)
                .with(actor()))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"))
        .andExpect(jsonPath("$.id").value(taskId.toString()))
        .andExpect(jsonPath("$.scheduledDate").value(admittedLocalDate.get().toString()));
    mvc.perform(
            post("/api/logistics/v1/driver-tasks")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(driverTaskBody(sourceId, 3))
                .with(actor()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("LOGISTICS_CONFLICT"));

    assertThat(jdbc.queryForObject("select count(*) from driver_logistics_task", Long.class))
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
        .containsEntry("admission_direction", "INCOMING")
        .containsEntry("admission_warehouse_version", 31L);
    assertThat(registrations).hasValue(1);
    verify(dependencies, times(1))
        .warehouseAdmission(
            WAREHOUSE,
            LogisticsDependencyGateway.WarehouseOperationDirection.INCOMING);
    verify(dependencies, times(1))
        .warehouseTimeZoneAt(eq(WAREHOUSE), any(OffsetDateTime.class));
    verify(dependencies, times(1)).readWarehouseDriverQueue(WAREHOUSE);
    verify(dependencies, times(1)).readRentalItemSnapshot(CABIN);
  }

  @Test
  void schedulesCapitalRepairDirectlyOnTheSelectedDateAndQueuePosition() throws Exception {
    LocalDate targetDate = LocalDate.now(ZoneOffset.UTC).plusDays(2);

    mvc.perform(schedule(UUID.randomUUID(), targetDate, 1))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.kind").value("CAPITAL_TO_PRODUCTION"))
        .andExpect(jsonPath("$.workflowState").value("SCHEDULED"))
        .andExpect(jsonPath("$.lane").value("SCHEDULED"))
        .andExpect(jsonPath("$.scheduledDate").value(targetDate.toString()))
        .andExpect(jsonPath("$.position").value(1));

    assertThat(
            jdbc.queryForObject(
                "select planning_mode from driver_logistics_task where source_id = ?",
                String.class,
                REPAIR))
        .isEqualTo("FIXED_DATE");
    assertThat(
            jdbc.queryForObject(
                "select fixed_date_lower_bound from driver_logistics_task where source_id = ?",
                LocalDate.class,
                REPAIR))
        .isEqualTo(targetDate);
    verify(dependencies, never()).setDriverTaskLane(any(), anyLong(), eq("CURRENT"));
    verify(dependencies)
        .moveDriverTask(
            any(), anyLong(), anyLong(), eq("SCHEDULED"), eq(targetDate), eq(1), isNull());
  }

  @Test
  void returnsCapitalMovementWithoutDeletingHistoryAndAllowsASecondPromotion()
      throws Exception {
    mvc.perform(promote(UUID.randomUUID()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("CURRENT"));

    LogisticsDependencyGateway.DriverBoardTask first = boardTask.get();
    mvc.perform(
            post(
                    "/api/logistics/v1/driver-board/tasks/{externalTaskId}/return-to-capital-repairs",
                    first.externalTaskId())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    "{\"warehouseId\":\"%s\",\"expectedTaskVersion\":%d}"
                        .formatted(WAREHOUSE, first.taskVersion()))
                .with(actor()))
        .andExpect(status().isNoContent());

    assertThat(
            jdbc.queryForObject(
                "select state from driver_logistics_task where external_task_id = ?",
                String.class,
                first.externalTaskId()))
        .isEqualTo("CANCELLED");

    mvc.perform(promote(UUID.randomUUID()))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.state").value("CURRENT"));

    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from driver_logistics_task
                 where source_type = 'CAPITAL_REPAIR'
                   and source_id = ?
                   and task_kind = 'CAPITAL_TO_PRODUCTION'
                """,
                Long.class,
                REPAIR))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*)
                  from driver_logistics_task
                 where source_type = 'CAPITAL_REPAIR'
                   and source_id = ?
                   and task_kind = 'CAPITAL_TO_PRODUCTION'
                   and state <> 'CANCELLED'
                """,
                Long.class,
                REPAIR))
        .isOne();
    assertThat(registrations).hasValue(2);
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder promote(
      UUID idempotencyKey) {
    return post("/api/logistics/v1/driver-board/capital-repairs/{repairId}/promote", REPAIR)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content("{\"warehouseId\":\"%s\"}".formatted(WAREHOUSE))
        .with(actor());
  }

  private static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder schedule(
      UUID idempotencyKey, LocalDate targetDate, int targetIndex) {
    return post("/api/logistics/v1/driver-board/capital-repairs/{repairId}/schedule", REPAIR)
        .header("Idempotency-Key", idempotencyKey)
        .contentType(MediaType.APPLICATION_JSON)
        .content(
            "{\"warehouseId\":\"%s\",\"targetDate\":\"%s\",\"targetIndex\":%d}"
                .formatted(WAREHOUSE, targetDate, targetIndex))
        .with(actor());
  }

  private static String driverTaskBody(UUID sourceId, int priority) {
    return """
        {
          "warehouseId":"%s",
          "cabinId":"%s",
          "sourceType":"MANUAL",
          "sourceId":"%s",
          "kind":"GENERAL_MOVEMENT",
          "planningMode":"AUTO",
          "priority":%d,
          "activateNow":false,
          "comment":"Проверка границы складской даты"
        }
        """
        .formatted(WAREHOUSE, CABIN, sourceId, priority);
  }

  private static JwtRequestPostProcessor actor() {
    return jwt()
        .jwt(
            value ->
                value
                    .subject(ACTOR.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.write")
                    .claim(
                        "warehouse_access",
                        List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "EDIT"))));
  }
}
