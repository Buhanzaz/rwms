package dev.buhanzaz.rwms.taskboard;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.api.ApiModels.CancelLogisticsPreparationTaskRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.CompleteLogisticsPreparationTaskRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterExternalTaskRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.RegisterLogisticsPreparationTaskRequest;
import dev.buhanzaz.rwms.taskboard.api.ApiModels.RouteStepRequest;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LogisticsTaskPrerequisiteIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000602");
  private static final String BASE_PATH =
      "/api/internal/task-board/v1/logistics/preparation-tasks";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired TaskBoardService board;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void registersAnUnassignedSourceOwnedTaskAndCancelsItIdempotently() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    OffsetDateTime deadline = OffsetDateTime.now().plusDays(1);
    RegisterLogisticsPreparationTaskRequest request =
        new RegisterLogisticsPreparationTaskRequest(WAREHOUSE, externalTaskId, 15, deadline);

    JsonNode first =
        response(
            mvc.perform(
                    post(BASE_PATH)
                        .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.externalTaskId").value(externalTaskId.toString()))
                .andExpect(jsonPath("$.status").value("ACTIVE"))
                .andExpect(jsonPath("$.title").doesNotExist())
                .andExpect(jsonPath("$.route").doesNotExist()));
    JsonNode replay =
        response(
            mvc.perform(
                    post(BASE_PATH)
                        .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isCreated()));
    assertThat(replay.required("taskId").textValue())
        .isEqualTo(first.required("taskId").textValue());
    assertThat(jdbc.queryForObject("select count(*) from board_task", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select count(*) from task_sync_source", Integer.class)).isOne();
    assertThat(jdbc.queryForObject("select source_client_id from task_sync_source", String.class))
        .isEqualTo("logistics-service");
    assertThat(jdbc.queryForObject("select queue_id is null from queue_entry", Boolean.class))
        .isTrue();
    assertThat(jdbc.queryForObject("select queue_code from queue_entry", String.class))
        .isEqualTo(TaskBoardService.UNASSIGNED_CODE);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.BOARD_TASK_CREATED))
        .isOne();

    mvc.perform(
            get(BASE_PATH + "/{externalTaskId}", externalTaskId)
                .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics"))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.taskId").value(first.required("taskId").textValue()))
        .andExpect(jsonPath("$.status").value("ACTIVE"));
    mvc.perform(
            post(BASE_PATH)
                .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new RegisterLogisticsPreparationTaskRequest(
                            WAREHOUSE, externalTaskId, 16, deadline))))
        .andExpect(status().isConflict());

    CancelLogisticsPreparationTaskRequest cancel =
        new CancelLogisticsPreparationTaskRequest(first.required("taskVersion").longValue());
    mvc.perform(
            post(BASE_PATH + "/{externalTaskId}/cancel", externalTaskId)
                .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(cancel)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"))
        .andExpect(jsonPath("$.title").doesNotExist());
    mvc.perform(
            post(BASE_PATH + "/{externalTaskId}/cancel", externalTaskId)
                .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics")))
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
  void completesTheSourceOwnedPreparationTaskWithTaskVersionCas() throws Exception {
    UUID externalTaskId = UUID.randomUUID();
    RegisterLogisticsPreparationTaskRequest register =
        new RegisterLogisticsPreparationTaskRequest(
            WAREHOUSE, externalTaskId, 0, null);
    JsonNode created =
        response(
            mvc.perform(
                    post(BASE_PATH)
                        .with(
                            logisticsJwt(
                                "logistics-service",
                                "logistics-service",
                                List.of("task-board.logistics")))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(register)))
                .andExpect(status().isCreated()));
    CompleteLogisticsPreparationTaskRequest complete =
        new CompleteLogisticsPreparationTaskRequest(
            created.required("taskVersion").longValue());

    for (int replay = 0; replay < 2; replay++) {
      mvc.perform(
              post(BASE_PATH + "/{externalTaskId}/complete", externalTaskId)
                  .with(
                      logisticsJwt(
                          "logistics-service",
                          "logistics-service",
                          List.of("task-board.logistics")))
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(complete)))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.status").value("DONE"))
          .andExpect(jsonPath("$.doneAt").isNotEmpty());
    }

    assertThat(jdbc.queryForObject("select status from board_task", String.class))
        .isEqualTo("DONE");
    assertThat(jdbc.queryForObject("select status from queue_entry", String.class))
        .isEqualTo("DONE");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.BOARD_TASK_COMPLETED))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.QUEUE_ENTRY_COMPLETED))
        .isOne();
  }

  @Test
  void rejectsWrongSourceScopeSubjectAndAnExistingOtherSourceTask() throws Exception {
    RegisterLogisticsPreparationTaskRequest request =
        new RegisterLogisticsPreparationTaskRequest(WAREHOUSE, UUID.randomUUID(), null, null);
    for (JwtRequestPostProcessor invalid :
        List.of(
            logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics", "rwms.write")),
            logisticsJwt("other-service", "other-service", List.of("task-board.logistics")),
            logisticsJwt("logistics-service", "another-subject", List.of("task-board.logistics")),
            serviceJwt("USER", "logistics-service", "logistics-service", List.of("task-board.logistics")),
            logisticsJwt("maintenance-service", "maintenance-service", List.of("task-board.task-sync")))) {
      mvc.perform(
              post(BASE_PATH)
                  .with(invalid)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(objectMapper.writeValueAsString(request)))
          .andExpect(status().isForbidden());
    }
    mvc.perform(
            post(BASE_PATH)
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request)))
        .andExpect(status().isUnauthorized());

    UUID externalTaskId = UUID.randomUUID();
    board.registerExternalTask(
        "maintenance-service",
        new RegisterExternalTaskRequest(
            WAREHOUSE,
            externalTaskId,
            "Maintenance-owned task",
            null,
            null,
            null,
            null,
            List.of(new RouteStepRequest(null, TaskBoardService.UNASSIGNED_CODE, null, null))));
    mvc.perform(
            post(BASE_PATH)
                .with(logisticsJwt("logistics-service", "logistics-service", List.of("task-board.logistics")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new RegisterLogisticsPreparationTaskRequest(
                            WAREHOUSE, externalTaskId, null, null))))
        .andExpect(status().isConflict());
  }

  private JsonNode response(org.springframework.test.web.servlet.ResultActions result)
      throws Exception {
    return objectMapper.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private JwtRequestPostProcessor logisticsJwt(
      String clientId, String subject, List<String> scopes) {
    return serviceJwt("SERVICE", clientId, subject, scopes);
  }

  private JwtRequestPostProcessor serviceJwt(
      String principalType, String clientId, String subject, List<String> scopes) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subject)
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", principalType)
                    .claim("client_id", clientId)
                    .claim("scope", scopes));
  }
}
