package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.PreStartUpdateTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.QueueType;
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

  private UUID queueId;
  private UUID verificationQueueId;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    queueId =
        registry
            .createQueue(
                WAREHOUSE,
                new WorkQueueRequest(
                    0L,
                    "MAINTENANCE",
                    "Maintenance",
                    null,
                    QueueType.REPAIR,
                    true,
                    false,
                    false,
                    null,
                    null,
                    false,
                    List.<QueueBindingRequest>of()))
            .id();
    verificationQueueId =
        registry
            .createQueue(
                WAREHOUSE,
                new WorkQueueRequest(
                    0L,
                    "MAINTENANCE_CHECK",
                    "Maintenance check",
                    null,
                    QueueType.REPAIR,
                    true,
                    false,
                    false,
                    null,
                    null,
                    false,
                    List.<QueueBindingRequest>of()))
            .id();
  }

  @Test
  void sourceOwnedRegistrationReplayUpdateReconciliationAndCancelStayFenced() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    RegisterExternalTaskRequest registration = registration(externalTaskId);

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
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.BOARD_TASK_CREATED))
        .isOne();
    assertThat(jdbc.queryForObject("select count(*) from task_sync_source", Integer.class))
        .isOne();

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
                new RouteStepRequest(queueId, null, "Repair", 15),
                new RouteStepRequest(verificationQueueId, null, "Check", 10)));
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
            List.of(new RouteStepRequest(queueId, null, null, null)));
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
        List.of(new RouteStepRequest(queueId, null, "Repair", 15)));
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
