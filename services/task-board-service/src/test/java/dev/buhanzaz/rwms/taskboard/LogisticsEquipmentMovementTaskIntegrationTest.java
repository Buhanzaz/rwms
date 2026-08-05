package dev.buhanzaz.rwms.taskboard;
import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelLogisticsEquipmentMovementTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CreateBoardTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.EquipmentMovementOperation;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MoveEntryRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterLogisticsEquipmentMovementTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TakeEntryRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.VersionCommand;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.BoardEntryDto;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueDto;
import dev.buhanzaz.rwms.taskboard.domain.EquipmentMovementDirection;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskStatus;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.repository.BoardTaskRepository;
import dev.buhanzaz.rwms.taskboard.repository.QueueEntryRepository;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LogisticsEquipmentMovementTaskIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000603");
  private static final UUID OFFICE_TABLE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000604");
  private static final UUID BENCH_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000605");
  private static final String BASE_PATH =
      "/api/internal/task-board/v1/logistics/equipment-movement-tasks";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;
  @Autowired TaskBoardService board;
  @Autowired BoardTaskRepository tasks;
  @Autowired QueueEntryRepository entries;
  @Autowired TestWarehouseLifecycleGateway warehouseLifecycle;
  private WorkQueueDto furnitureQueue;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    warehouseLifecycle.reset();
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(
                0L, "Перемещение мебели", null, QueueType.FURNITURE_MOVEMENT));
    furnitureQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            WAREHOUSE,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                null,
                List.of()));
  }

  @Test
  void registersTypedMovementWithGeneratedBoardDetailsAndFencedLifecycle() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.now().plusDays(1);
    RegisterLogisticsEquipmentMovementTaskRequest request =
        movementRequest(externalTaskId, deadline, 2L);

    JsonNode first =
        response(
            mvc.perform(
                    post(BASE_PATH)
                        .with(logisticsJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.route").doesNotExist()));

    UUID taskId = UUID.fromString(first.required("taskId").textValue());
    var task = tasks.findById(taskId).orElseThrow();
    assertThat(task.isCompletionDeadlineEnforced()).isTrue();
    assertThat(task.getTitle()).isEqualTo("Перемещение мебели — бытовка БЫТ-011");
    assertThat(task.getDescription())
        .contains("Занести в бытовку: Стол офисный — 2 шт.")
        .contains("Вынести из бытовки: Лавка — 1 шт.");
    var route = entries.findAllByTaskIdOrderByRouteIndexAsc(taskId);
    assertThat(route).hasSize(1);
    assertThat(
            jdbc.queryForObject(
                "select queue_id from queue_entry where id=?", UUID.class, route.getFirst().getId()))
        .isEqualTo(furnitureQueue.id());
        assertThat(
            jdbc.queryForObject(
                """
                select definition.name
                  from queue_entry entry
                  join work_queue queue on queue.id=entry.queue_id
                  join queue_definition definition on definition.id=queue.definition_id
                 where entry.id=?
                """,
                String.class,
                route.getFirst().getId()))
        .isEqualTo("Перемещение мебели");
    assertThat(route.getFirst().getTaskText()).isEqualTo(task.getDescription());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.BOARD_TASK_CREATED))
        .isOne();
    assertThat(warehouseLifecycle.admissionTransactionStates()).containsOnly(false);

    JsonNode replay =
        response(
            mvc.perform(
                    post(BASE_PATH)
                        .with(logisticsJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()));
    assertThat(replay.required("taskId").textValue()).isEqualTo(first.required("taskId").textValue());

    mvc.perform(
            post(BASE_PATH)
                .with(logisticsJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(movementRequest(externalTaskId, deadline, 3L))))
        .andExpect(status().isConflict());
    mvc.perform(
            post(BASE_PATH)
                .with(logisticsJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        movementRequest(externalTaskId, deadline.plusMinutes(1), 2L))))
        .andExpect(status().isConflict());
    mvc.perform(get(BASE_PATH + "/{externalTaskId}", externalTaskId).with(logisticsJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskId").value(taskId.toString()));
    CancelLogisticsEquipmentMovementTaskRequest cancel =
        new CancelLogisticsEquipmentMovementTaskRequest(first.required("taskVersion").longValue());
    mvc.perform(
            post(BASE_PATH + "/{externalTaskId}/cancel", externalTaskId)
                .with(logisticsJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cancel)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mvc.perform(
            post(BASE_PATH + "/{externalTaskId}/cancel", externalTaskId)
                .with(logisticsJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cancel)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.BOARD_TASK_CANCELLED))
        .isOne();
  }

  @Test
  void validatesRequiredTypedDeadlineAndOperations() throws Exception {
    String body =
        """
        {
          "warehouseId": "%s",
          "externalTaskId": "%s",
          "operations": []
        }
        """
            .formatted(WAREHOUSE, UUID.randomUUID());

    mvc.perform(
            post(BASE_PATH)
                .with(logisticsJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isBadRequest())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_VALIDATION_FAILED"));
  }

  @Test
  void drainingWarehousePermitsOnlyPureFurnitureDrainAndInactiveRejectsNewWork() {
    warehouseLifecycle.lifecycleState(TestWarehouseLifecycleGateway.LifecycleState.DRAINING);

    assertThat(
            board.registerLogisticsEquipmentMovementTask(
                new RegisterLogisticsEquipmentMovementTaskRequest(
                    WAREHOUSE,
                    UUID.randomUUID(),
                    "БЫТ-011",
                    15,
                    OffsetDateTime.now().plusDays(1),
                    List.of(
                        new EquipmentMovementOperation(
                            EquipmentMovementDirection.TAKE_FROM_CABIN,
                            OFFICE_TABLE_ID,
                            "Стол офисный",
                            1L)))))
        .isNotNull();
    assertThat(warehouseLifecycle.admissions())
        .containsExactly(
            new TestWarehouseLifecycleGateway.Admission(
                WAREHOUSE,
                dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection.OUTGOING));

    assertThatThrownBy(
            () ->
                board.registerLogisticsEquipmentMovementTask(
                    movementRequest(UUID.randomUUID(), OffsetDateTime.now().plusDays(1), 1L)))
        .isInstanceOf(ConflictException.class);
    assertThat(warehouseLifecycle.admissions().getLast())
        .isEqualTo(
            new TestWarehouseLifecycleGateway.Admission(
                WAREHOUSE,
                dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection.INCOMING));

    warehouseLifecycle.reset();
    warehouseLifecycle.lifecycleState(TestWarehouseLifecycleGateway.LifecycleState.INACTIVE);
    assertThatThrownBy(
            () ->
                board.createTask(
                    WAREHOUSE,
                    new CreateBoardTaskRequest(
                        null,
                        "New work",
                        null,
                        null,
                        null,
                        null,
                        List.of(new RouteStepRequest(furnitureQueue.definitionId(), null, null)))))
        .isInstanceOf(ConflictException.class);
    assertThat(warehouseLifecycle.admissions())
        .containsExactly(
            new TestWarehouseLifecycleGateway.Admission(
                WAREHOUSE,
                dev.buhanzaz.rwms.taskboard.service.WarehouseLifecycleGateway.OperationDirection.INCOMING));
  }

  @Test
  void onlyEquipmentMovementTaskCompletionIsFencedAtDeadline() {
    var definition =
        registry.createQueueDefinition(
            QueueRegistryTestFixtures.globalDefinition(0L, "Movement", null, QueueType.MOVEMENT));
    var queue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            WAREHOUSE,
            new QueueFixtureRequest(
                0L,
                definition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                null,
                List.of()));
    var worker =
        workforce.createWorker(
            WAREHOUSE,
            new WorkerRequest(
                0L,
                "Deadline worker",
                null,
                null,
                null,
                true,
                null,
                null,
                null,
                List.of()));

    UUID genericExternalTaskId = UUID.randomUUID();
    board.createTask(
        WAREHOUSE,
        new CreateBoardTaskRequest(
            genericExternalTaskId,
            "Generic past deadline",
            null,
            null,
            null,
            OffsetDateTime.now().minusDays(1),
            List.of(new RouteStepRequest(queue.definitionId(), "Generic", null))));
    BoardEntryDto generic = entry(genericExternalTaskId);
    generic =
        board.take(
            WAREHOUSE,
            generic.id(),
            new TakeEntryRequest(generic.version(), null, worker.id()),
            null);
    board.complete(WAREHOUSE, generic.id(), new VersionCommand(generic.version()), null);
    assertThat(tasks.findByExternalTaskId(genericExternalTaskId).orElseThrow().getStatus())
        .isEqualTo(TaskStatus.DONE);
    assertThat(
            tasks.findByExternalTaskId(genericExternalTaskId).orElseThrow().isCompletionDeadlineEnforced())
        .isFalse();

    var movement =
        board.registerLogisticsEquipmentMovementTask(
            movementRequest(UUID.randomUUID(), OffsetDateTime.now().plusDays(1), 2L));
    var movementEntry = entries.findAllByTaskIdOrderByRouteIndexAsc(movement.taskId()).getFirst();
    var movementTask = tasks.findById(movement.taskId()).orElseThrow();
    board.move(
        WAREHOUSE,
        movementEntry.getId(),
        new MoveEntryRequest(
            movementEntry.getVersion(),
            movementTask.getVersion(),
            queue.id(),
            0,
            movementTask.getScheduledDate()));
    movementEntry = entries.findById(movementEntry.getId()).orElseThrow();
    BoardEntryDto started =
        board.take(
            WAREHOUSE,
            movementEntry.getId(),
            new TakeEntryRequest(movementEntry.getVersion(), null, worker.id()),
            null);
    jdbc.update("update board_task set deadline_at=clock_timestamp() where id=?", movement.taskId());

    assertThatThrownBy(
            () ->
                board.complete(
                    WAREHOUSE, started.id(), new VersionCommand(started.version()), null))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("Срок резерва мебели истек");
    assertThat(tasks.findById(movement.taskId()).orElseThrow().getStatus()).isEqualTo(TaskStatus.ACTIVE);
  }

  private RegisterLogisticsEquipmentMovementTaskRequest movementRequest(
      UUID externalTaskId, OffsetDateTime deadline, long officeTableQuantity) {
    return new RegisterLogisticsEquipmentMovementTaskRequest(
        WAREHOUSE,
        externalTaskId,
        "БЫТ-011",
        15,
        deadline,
        List.of(
            new EquipmentMovementOperation(
                EquipmentMovementDirection.BRING_TO_CABIN,
                OFFICE_TABLE_ID,
                "Стол офисный",
                officeTableQuantity),
            new EquipmentMovementOperation(
                EquipmentMovementDirection.TAKE_FROM_CABIN, BENCH_ID, "Лавка", 1L)));
  }

  private BoardEntryDto entry(UUID externalTaskId) {
    return board.snapshot(WAREHOUSE, true).columns().stream()
        .flatMap(column -> column.entries().stream())
        .filter(entry -> externalTaskId.equals(entry.externalTaskId()))
        .findFirst()
        .orElseThrow();
  }

  private JsonNode response(org.springframework.test.web.servlet.ResultActions result)
      throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private JwtRequestPostProcessor logisticsJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("logistics-service")
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", "logistics-service")
                    .claim("scope", List.of("task-board.logistics")));
  }
}
