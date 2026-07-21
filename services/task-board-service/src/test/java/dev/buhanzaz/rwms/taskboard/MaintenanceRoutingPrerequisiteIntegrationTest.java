package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingPreflightRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.MaintenanceRoutingQueueRequirement;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueReferenceRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkQueueRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.eventing.TaskBoardEventTypes;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.ReviewedTaskBoardBootstrapService;
import dev.buhanzaz.rwms.taskboard.service.StaleVersionException;
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
class MaintenanceRoutingPrerequisiteIntegrationTest extends PostgresIntegrationTestSupport {
  private static final String PATH =
      "/api/internal/task-board/v1/maintenance/routing-preflight";
  private static final UUID SPB =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID EXTERNAL_WORKS =
      UUID.fromString("019f21e8-4526-7462-95e8-3309ce19fc9c");
  private static final UUID INTERNAL_WORKS =
      UUID.fromString("019f21e8-cd97-7a20-a876-8306e77f94bd");
  private static final UUID ELECTRICS =
      UUID.fromString("019f21e9-6057-736c-8999-6808191a1362");
  private static final UUID PLUMBING =
      UUID.fromString("019f21e9-f54f-7184-954e-29f237b9c424");
  private static final UUID WELDING =
      UUID.fromString("019f21ea-6015-753f-ad3a-be58947aa252");
  private static final UUID SANITARY_DISINFECTION =
      UUID.fromString("019f21ed-eb53-782d-a73f-a24357e262b2");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired ReviewedTaskBoardBootstrapService bootstrap;
  @Autowired RegistryService registry;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void exactReviewedMoscowRoutingIsReadyAndReadOnly() throws Exception {
    bootstrap.bootstrap(MSK);
    String before = persistedState();

    mvc.perform(
            post(PATH)
                .with(
                    maintenanceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        "SERVICE",
                        List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(routingRequest())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.warehouseId").value(MSK.toString()))
        .andExpect(jsonPath("$.ready").value(true))
        .andExpect(jsonPath("$.missingQueueIds").isEmpty())
        .andExpect(jsonPath("$.mismatches").isEmpty());

    assertThat(persistedState()).isEqualTo(before);
  }

  @Test
  void wrongWarehouseIsReportedWithoutMutation() throws Exception {
    bootstrap.bootstrap(MSK);
    jdbc.update("update work_queue set warehouse_id=? where id=?", SPB, EXTERNAL_WORKS);
    String before = persistedState();

    mvc.perform(
            post(PATH)
                .with(exactMaintenanceJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(routingRequest())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(false))
        .andExpect(jsonPath("$.missingQueueIds").isEmpty())
        .andExpect(jsonPath("$.mismatches.length()").value(1))
        .andExpect(jsonPath("$.mismatches[0].queueId").value(EXTERNAL_WORKS.toString()))
        .andExpect(jsonPath("$.mismatches[0].fields[0]").value("WAREHOUSE_ID"));

    assertThat(persistedState()).isEqualTo(before);
  }

  @Test
  void missingAndEveryQueueStateMismatchAreReportedWithoutMutation() throws Exception {
    bootstrap.bootstrap(MSK);
    jdbc.update(
        "update work_queue set code='INTERNAL_CHANGED',queue_type='HOLDING',active=false,hidden=true where id=?",
        INTERNAL_WORKS);
    jdbc.update("delete from work_queue_class_binding where queue_id=?", WELDING);
    jdbc.update("delete from work_queue where id=?", WELDING);
    String before = persistedState();

    mvc.perform(
            post(PATH)
                .with(exactMaintenanceJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(routingRequest())))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(false))
        .andExpect(jsonPath("$.missingQueueIds[0]").value(WELDING.toString()))
        .andExpect(jsonPath("$.missingQueueIds.length()").value(1))
        .andExpect(jsonPath("$.mismatches.length()").value(1))
        .andExpect(jsonPath("$.mismatches[0].queueId").value(INTERNAL_WORKS.toString()))
        .andExpect(jsonPath("$.mismatches[0].fields[0]").value("CODE"))
        .andExpect(jsonPath("$.mismatches[0].fields[1]").value("TYPE"))
        .andExpect(jsonPath("$.mismatches[0].fields[2]").value("ACTIVE"))
        .andExpect(jsonPath("$.mismatches[0].fields[3]").value("HIDDEN"))
        .andExpect(jsonPath("$.mismatches[0].fields.length()").value(4));

    assertThat(persistedState()).isEqualTo(before);
  }

  @Test
  void exactMaintenanceServiceIdentityAndScopeAreRequired() throws Exception {
    String body = objectMapper.writeValueAsString(routingRequest());

    mvc.perform(post(PATH).contentType(MediaType.APPLICATION_JSON).content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(PATH)
                .with(
                    maintenanceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        "USER",
                        List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PATH)
                .with(
                    maintenanceJwt(
                        "other-service",
                        "other-service",
                        "SERVICE",
                        List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PATH)
                .with(
                    maintenanceJwt(
                        "maintenance-service",
                        "different-subject",
                        "SERVICE",
                        List.of("task-board.task-sync")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PATH)
                .with(
                    maintenanceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        "SERVICE",
                        List.of("task-board.task-sync", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());

    assertThat(jdbc.queryForObject("select count(*) from work_queue", Integer.class)).isZero();
  }

  @Test
  void catalogPositionReferenceReplayIsStableAndBlocksQueueDeletionUntilVersionedRemoval() {
    var queue =
        registry.createQueue(
            MSK,
            new WorkQueueRequest(
                0L,
                "CATALOG_ROUTE",
                "Catalog route",
                null,
                QueueType.REPAIR,
                true,
                false,
                false,
                null,
                null,
                false,
                List.<QueueBindingRequest>of()));
    var request =
        new QueueReferenceRequest(QueueReferenceType.CATALOG_POSITION, "catalog-position-1");

    var first = registry.registerReference(queue.id(), request);
    var replay = registry.registerReference(queue.id(), request);

    assertThat(replay.id()).isEqualTo(first.id());
    assertThat(replay.version()).isEqualTo(first.version());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from queue_usage_reference where reference_type='CATALOG_POSITION'",
                Integer.class))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.QUEUE_REFERENCE_CREATED))
        .isOne();
    assertThatThrownBy(
            () ->
                registry.deleteReference(
                    first.type(), first.externalReferenceId(), first.version() + 1))
        .isInstanceOf(StaleVersionException.class);
    assertThatThrownBy(() -> registry.deleteQueue(MSK, queue.id(), queue.version()))
        .isInstanceOf(ConflictException.class);

    registry.deleteReference(first.type(), first.externalReferenceId(), first.version());

    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where event_type=?",
                Integer.class,
                TaskBoardEventTypes.QUEUE_REFERENCE_DELETED))
        .isOne();
    registry.deleteQueue(MSK, queue.id(), queue.version());
    assertThat(registry.listQueues(MSK)).isEmpty();
  }

  private MaintenanceRoutingPreflightRequest routingRequest() {
    return new MaintenanceRoutingPreflightRequest(
        MSK,
        List.of(
            new MaintenanceRoutingQueueRequirement(
                EXTERNAL_WORKS, "EXTERNAL_WORKS", QueueType.REPAIR),
            new MaintenanceRoutingQueueRequirement(
                INTERNAL_WORKS, "INTERNAL_WORKS", QueueType.REPAIR),
            new MaintenanceRoutingQueueRequirement(ELECTRICS, "ELECTRICS", QueueType.REPAIR),
            new MaintenanceRoutingQueueRequirement(PLUMBING, "PLUMBING", QueueType.REPAIR),
            new MaintenanceRoutingQueueRequirement(WELDING, "WELDING", QueueType.REPAIR),
            new MaintenanceRoutingQueueRequirement(
                SANITARY_DISINFECTION, "SANITARY_DISINFECTION", QueueType.HOLDING)));
  }

  private JwtRequestPostProcessor exactMaintenanceJwt() {
    return maintenanceJwt(
        "maintenance-service",
        "maintenance-service",
        "SERVICE",
        List.of("task-board.task-sync"));
  }

  private JwtRequestPostProcessor maintenanceJwt(
      String clientId, String subject, String principalType, List<String> scopes) {
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

  private String persistedState() {
    return jdbc.queryForObject(
        """
        select jsonb_build_object(
          'queues', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                       from work_queue item),
          'bindings', (select coalesce(jsonb_agg(to_jsonb(item) order by item.id), '[]'::jsonb)
                         from work_queue_class_binding item),
          'events', (select coalesce(jsonb_agg(to_jsonb(item) order by item.event_id), '[]'::jsonb)
                       from domain_event item),
          'outbox', (select coalesce(jsonb_agg(to_jsonb(item) order by item.event_id), '[]'::jsonb)
                       from outbox_event item)
        )::text
        """,
        String.class);
  }
}
