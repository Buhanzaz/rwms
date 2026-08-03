package dev.buhanzaz.rwms.taskboard;
import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.PreStartUpdateTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueDefinitionRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.TaskSourceReferenceDto;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.domain.TaskLane;
import dev.buhanzaz.rwms.taskboard.domain.TaskSourceType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.service.NotFoundException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.TaskBoardService;
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
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class TaskSyncPrerequisiteIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000601");

  @Autowired RegistryService registry;
  @Autowired TaskBoardService board;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  private UUID queueDefinitionId;
  private UUID verificationQueueDefinitionId;
  private UUID workQueueId;
  private UUID verificationWorkQueueId;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    var definition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(0L, "Maintenance", null, QueueType.REPAIR));
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
                List.<QueueBindingRequest>of()));
    queueDefinitionId = definition.id();
    workQueueId = queue.id();
    var verificationDefinition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(
                0L, "Maintenance check", null, QueueType.REPAIR));
    var verificationQueue =
        QueueRegistryTestFixtures.create(registry, jdbc,
            WAREHOUSE,
            new QueueFixtureRequest(
                0L,
                verificationDefinition.id(),
                true,
                false,
                false,
                null,
                null,
                false,
                null,
                List.<QueueBindingRequest>of()));
    verificationQueueDefinitionId = verificationDefinition.id();
    verificationWorkQueueId = verificationQueue.id();
  }

  @Test
  void sourceOwnedRegistrationReplayUpdateReconciliationAndCancelStayFenced() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    UUID repairId = UUID.randomUUID();
    RegisterExternalTaskRequest registration = registration(externalTaskId, repairId);

    String first =
        mvc.perform(
                post("/api/internal/task-board/v1/tasks")
                    .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(registration)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    String replay =
        mvc.perform(
                post("/api/internal/task-board/v1/tasks")
                    .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(registration)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(replay).required("taskId").textValue())
        .isEqualTo(objectMapper.readTree(first).required("taskId").textValue());
    mvc.perform(
            post("/api/internal/task-board/v1/tasks")
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        registration(externalTaskId, UUID.randomUUID()))))
        .andExpect(status().isConflict());
    mvc.perform(
            post("/api/internal/task-board/v1/tasks")
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registration(externalTaskId))))
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.BOARD_TASK_CREATED))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from task_sync_source", Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select source_type from task_sync_source", String.class))
        .isEqualTo("MAINTENANCE_REPAIR");
    assertThat(
            jdbc.queryForObject(
                "select source_id from task_sync_source", UUID.class))
        .isEqualTo(repairId);

    UUID oldEntryId =
        jdbc.queryForObject(
            "select id from queue_entry where task_id=(select board_task_id from task_sync_source)",
            UUID.class);
    PreStartUpdateTaskRequest update =
        new PreStartUpdateTaskRequest(
            0L,
            "Updated maintenance task",
            "CABIN-1",
            "Updated before work starts",
            25,
            null,
            List.of(
                new RouteStepRequest(queueDefinitionId, "Repair", 15),
                new RouteStepRequest(verificationQueueDefinitionId, "Check", 10)));
    mvc.perform(
            put("/api/internal/task-board/v1/tasks/{externalTaskId}", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskVersion").value(1))
        .andExpect(jsonPath("$.route.length()").value(2));
    assertThat(
            jdbc.queryForObject(
                "select payload->>'deleted' from domain_event where aggregate_type='QUEUE_ENTRY' "
                    + "and aggregate_id=? order by aggregate_version desc limit 1",
                String.class,
                oldEntryId.toString()))
        .isEqualTo("true");
    assertThat(jdbc.queryForObject("select count(*) from queue_entry", Integer.class)).isEqualTo(2);
    assertThat(
            jdbc.queryForList(
                """
                select board_fact.envelope_body->'payload'->>'externalTaskId' external_task_id,
                       (queue_fact.envelope_body->'payload'->>'routeIndex')::integer route_index,
                       jsonb_exists(
                         queue_fact.envelope_body->'payload', 'externalTaskId') queue_has_external_task_id
                  from queue_entry entry
                  join outbox_event queue_fact
                    on queue_fact.aggregate_type='QUEUE_ENTRY'
                   and queue_fact.aggregate_id=entry.id::text
                   and queue_fact.event_type=?
                  join outbox_event board_fact
                    on board_fact.aggregate_type='BOARD_TASK'
                   and board_fact.aggregate_id=entry.task_id::text
                   and board_fact.event_type=?
                 order by route_index
                """,
                TaskBoardEventTypes.QUEUE_ENTRY_CREATED,
                TaskBoardEventTypes.BOARD_TASK_CREATED))
        .extracting(
            row -> row.get("external_task_id"),
            row -> row.get("route_index"),
            row -> row.get("queue_has_external_task_id"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(externalTaskId.toString(), 0, false),
            org.assertj.core.groups.Tuple.tuple(externalTaskId.toString(), 1, false));

    assertThatThrownBy(() -> board.externalTask("other-service", externalTaskId))
        .isInstanceOf(NotFoundException.class);
    assertThatThrownBy(
            () ->
                board.cancelExternalTask(
                    "other-service", externalTaskId, new CancelTaskRequest(1L, "not owner")))
        .isInstanceOf(NotFoundException.class);

    mvc.perform(
            get("/api/internal/task-board/v1/tasks/{externalTaskId}", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.title").value("Updated maintenance task"));
    mvc.perform(
            put("/api/internal/task-board/v1/tasks/{externalTaskId}", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("select count(*) from queue_entry", Integer.class)).isEqualTo(2);

    CancelTaskRequest cancel = new CancelTaskRequest(1L, "amended");
    mvc.perform(
            post("/api/internal/task-board/v1/tasks/{externalTaskId}/cancel", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cancel)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    mvc.perform(
            post("/api/internal/task-board/v1/tasks/{externalTaskId}/cancel", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
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
    mvc.perform(
            get("/api/internal/task-board/v1/tasks/{externalTaskId}", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
  }

  @Test
  void registrationCannotBackfillAPreviouslyMissingSourceReference() {
    UUID externalTaskId = UUID.randomUUID();
    board.registerExternalTask("maintenance-service", registration(externalTaskId));

    assertThatThrownBy(
            () ->
                board.registerExternalTask(
                    "maintenance-service",
                    registration(externalTaskId, UUID.randomUUID())))
        .isInstanceOf(dev.buhanzaz.rwms.taskboard.service.ConflictException.class);
    assertThat(
            jdbc.queryForMap(
                "select source_type,source_id from task_sync_source where external_task_id=?",
                externalTaskId))
        .containsEntry("source_type", null)
        .containsEntry("source_id", null);
  }

  @Test
  void internalRegistrationPreservesRepeatedQueueStagesAndFencesReplay() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    RegisterExternalTaskRequest registration =
        repeatedQueueRegistration(externalTaskId, "Перемещение");

    String first =
        mvc.perform(
                post("/api/internal/task-board/v1/tasks")
                    .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(registration)))
            .andExpect(status().isCreated())
            .andExpect(jsonPath("$.route.length()").value(3))
            .andExpect(
                jsonPath("$.route[0].queueDefinitionId")
                    .value(queueDefinitionId.toString()))
            .andExpect(jsonPath("$.route[0].workQueueId").value(workQueueId.toString()))
            .andExpect(jsonPath("$.route[0].routeIndex").value(0))
            .andExpect(jsonPath("$.route[0].taskText").value("Перемещение"))
            .andExpect(
                jsonPath("$.route[1].queueDefinitionId")
                    .value(verificationQueueDefinitionId.toString()))
            .andExpect(
                jsonPath("$.route[1].workQueueId")
                    .value(verificationWorkQueueId.toString()))
            .andExpect(jsonPath("$.route[1].routeIndex").value(1))
            .andExpect(jsonPath("$.route[1].taskText").value("Внутренние работы"))
            .andExpect(
                jsonPath("$.route[2].queueDefinitionId")
                    .value(queueDefinitionId.toString()))
            .andExpect(jsonPath("$.route[2].workQueueId").value(workQueueId.toString()))
            .andExpect(jsonPath("$.route[2].routeIndex").value(2))
            .andExpect(jsonPath("$.route[2].taskText").value("Перемещение"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    var firstResponse = objectMapper.readTree(first);
    UUID taskId = UUID.fromString(firstResponse.required("taskId").textValue());
    assertThat(
            List.of(
                firstResponse.required("route").get(0).required("entryId").textValue(),
                firstResponse.required("route").get(1).required("entryId").textValue(),
                firstResponse.required("route").get(2).required("entryId").textValue()))
        .doesNotHaveDuplicates();
    assertThat(
            jdbc.queryForList(
                "select queue_id,route_index,task_text from queue_entry where task_id=? "
                    + "order by route_index",
                taskId))
        .extracting(
            row -> row.get("queue_id"),
            row -> row.get("route_index"),
            row -> row.get("task_text"))
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple(workQueueId, 0, "Перемещение"),
            org.assertj.core.groups.Tuple.tuple(
                verificationWorkQueueId, 1, "Внутренние работы"),
            org.assertj.core.groups.Tuple.tuple(workQueueId, 2, "Перемещение"));

    String replay =
        mvc.perform(
                post("/api/internal/task-board/v1/tasks")
                    .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(objectMapper.writeValueAsString(registration)))
            .andExpect(status().isCreated())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(replay)).isEqualTo(firstResponse);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from board_task where external_task_id=?",
                Integer.class,
                externalTaskId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from queue_entry where task_id=?", Integer.class, taskId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where aggregate_id=? and event_type=?",
                Integer.class,
                taskId.toString(),
                TaskBoardEventTypes.BOARD_TASK_CREATED))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.QUEUE_ENTRY_CREATED))
        .isEqualTo(3);

    mvc.perform(
            post("/api/internal/task-board/v1/tasks")
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        repeatedQueueRegistration(externalTaskId, "Изменённое перемещение"))))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from board_task where external_task_id=?",
                Integer.class,
                externalTaskId))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from queue_entry where task_id=?", Integer.class, taskId))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.QUEUE_ENTRY_CREATED))
        .isEqualTo(3);
  }

  @Test
  void preStartUpdateRejectsAStartedRoute() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    board.registerExternalTask("maintenance-service", registration(externalTaskId));
    jdbc.update(
        "update queue_entry set status='IN_PROGRESS',active_started_at=? where task_id="
            + "(select board_task_id from task_sync_source where external_task_id=?)",
        OffsetDateTime.now(),
        externalTaskId);

    PreStartUpdateTaskRequest update =
        new PreStartUpdateTaskRequest(
            0L,
            "Too late",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(queueDefinitionId, null, null)));
    mvc.perform(
            put("/api/internal/task-board/v1/tasks/{externalTaskId}", externalTaskId)
                .with(taskSyncJwt("maintenance-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(update)))
        .andExpect(status().isConflict());
  }

  @Test
  void exactServiceScopeAndApprovedClientAreRequired() throws Exception {
    mvc.perform(
            post("/api/internal/task-board/v1/tasks")
                .with(
                    taskSyncJwt(
                        "maintenance-service", List.of("task-board.task-sync", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registration(UUID.randomUUID()))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/internal/task-board/v1/tasks")
                .with(taskSyncJwt("other-service", List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registration(UUID.randomUUID()))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post("/api/internal/task-board/v1/tasks")
                .with(
                    taskSyncJwt(
                        "maintenance-service",
                        "different-subject",
                        List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(registration(UUID.randomUUID()))))
        .andExpect(status().isForbidden());
  }

  private RegisterExternalTaskRequest registration(UUID externalTaskId) {
    return new RegisterExternalTaskRequest(
        WAREHOUSE,
        externalTaskId,
        "Maintenance task",
        "CABIN-1",
        null,
        15,
        null,
        List.of(new RouteStepRequest(queueDefinitionId, "Repair", 15)));
  }

  private RegisterExternalTaskRequest registration(UUID externalTaskId, UUID repairId) {
    return new RegisterExternalTaskRequest(
        WAREHOUSE,
        externalTaskId,
        "Maintenance task",
        "CABIN-1",
        null,
        15,
        null,
        List.of(new RouteStepRequest(queueDefinitionId, "Repair", 15)),
        null,
        null,
        null,
        new TaskSourceReferenceDto(TaskSourceType.MAINTENANCE_REPAIR, repairId),
        TaskLane.SCHEDULED);
  }

  private RegisterExternalTaskRequest repeatedQueueRegistration(
      UUID externalTaskId, String returnStepText) {
    return new RegisterExternalTaskRequest(
        WAREHOUSE,
        externalTaskId,
        "Repair route",
        "CABIN-1",
        null,
        30,
        null,
        List.of(
            new RouteStepRequest(queueDefinitionId, "Перемещение", 5),
            new RouteStepRequest(
                verificationQueueDefinitionId, "Внутренние работы", 20),
            new RouteStepRequest(queueDefinitionId, returnStepText, 5)));
  }

  private JwtRequestPostProcessor taskSyncJwt(String clientId, List<String> scopes) {
    return taskSyncJwt(clientId, clientId, scopes);
  }

  private JwtRequestPostProcessor taskSyncJwt(
      String clientId, String subject, List<String> scopes) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subject)
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", clientId)
                    .claim("scope", scopes));
  }
}
