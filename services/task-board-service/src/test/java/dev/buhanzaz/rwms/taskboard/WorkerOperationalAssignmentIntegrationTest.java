package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateContractorDriverRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.CreateWorkerOperationalAssignmentRequest;
import static dev.buhanzaz.rwms.taskboard.api.LogisticsDriverAssignmentApiModels.TransitionWorkerOperationalAssignmentRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QualificationRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.QueueBindingRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerClassDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerClassRequest;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerDto;
import static dev.buhanzaz.rwms.taskboard.api.ApiModels.WorkerRequest;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentMode;
import dev.buhanzaz.rwms.taskboard.domain.WorkerOperationalAssignmentStatus;
import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
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
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Verifies on-demand contractors, transfer assignment lifecycle, home-warehouse immutability and
 * overlap fencing through the exact internal logistics API.
 */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class WorkerOperationalAssignmentIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID SOURCE =
      UUID.fromString("00000000-0000-0000-0000-000000000741");
  private static final UUID DESTINATION =
      UUID.fromString("00000000-0000-0000-0000-000000000742");
  private static final String BASE =
      "/api/internal/task-board/v1/logistics/operational-assignments";
  private static final String DIRECTORY =
      "/api/internal/task-board/v1/logistics/warehouses/%s/drivers";

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired JdbcTemplate jdbc;
  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;

  private OffsetDateTime now;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
    now = OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.SECONDS);
  }

  @Test
  void contractorAndTemporaryAssignmentRespectLocationAndIdempotentLifecycle()
      throws Exception {
    UUID contractorId = UUID.randomUUID();
    UUID transferId = UUID.randomUUID();
    CreateContractorDriverRequest contractor =
        new CreateContractorDriverRequest(
            contractorId,
            "Петров",
            "+7 900 000-00-00",
            "Смена по договору");

    postJson("/api/internal/task-board/v1/logistics/warehouses/" + SOURCE + "/contractors", contractor)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.workerId").value(contractorId.toString()))
        .andExpect(jsonPath("$.employmentType").value("CONTRACTOR"));
    postJson("/api/internal/task-board/v1/logistics/warehouses/" + SOURCE + "/contractors", contractor)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(0));
    assertThat(
            jdbc.queryForObject(
                "select count(*) from domain_event where aggregate_type='WORKER' and aggregate_id=?",
                Integer.class,
                contractorId.toString()))
        .isOne();

    directory(SOURCE, now.minusHours(5), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()));
    directory(SOURCE, now.minusHours(2), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("HOME"));
    directory(SOURCE, now.plusHours(9), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()));

    var create =
        new CreateWorkerOperationalAssignmentRequest(
            transferId,
            contractorId,
            SOURCE,
            DESTINATION,
            WorkerOperationalAssignmentMode.TEMPORARY,
            now.minusHours(2),
            now.minusHours(1),
            now.plusHours(4));
    JsonNode planned = response(postJson(BASE, create).andExpect(status().isCreated()));
    UUID assignmentId = UUID.fromString(planned.required("id").textValue());
    assertThat(planned.required("status").textValue()).isEqualTo("PLANNED");
    assertThat(jdbc.queryForObject("select warehouse_id from worker where id=?", UUID.class, contractorId))
        .isEqualTo(SOURCE);

    postJson(BASE, create)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.id").value(assignmentId.toString()));
    directory(SOURCE, now.minusHours(3), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()));
    directory(SOURCE, now.minusMinutes(90), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(DESTINATION, now.minusMinutes(90), true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(DESTINATION, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(DESTINATION, now, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("INCOMING"));

    JsonNode inTransit =
        response(
            transition(assignmentId, 0L, WorkerOperationalAssignmentStatus.IN_TRANSIT)
                .andExpect(status().isOk()));
    long inTransitVersion = inTransit.required("version").longValue();
    directory(DESTINATION, now, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());

    JsonNode active =
        response(
            transition(
                    assignmentId,
                    inTransitVersion,
                    WorkerOperationalAssignmentStatus.ACTIVE)
                .andExpect(status().isOk()));
    long activeVersion = active.required("version").longValue();
    directory(DESTINATION, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("ACTIVE_ASSIGNMENT"));
    directory(DESTINATION, now.plusHours(5), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(SOURCE, now.plusHours(5), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("HOME"));

    transition(assignmentId, activeVersion, WorkerOperationalAssignmentStatus.COMPLETED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    transition(assignmentId, activeVersion, WorkerOperationalAssignmentStatus.COMPLETED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("HOME"));
    assertThat(jdbc.queryForObject("select warehouse_id from worker where id=?", UUID.class, contractorId))
        .isEqualTo(SOURCE);
  }

  @Test
  void rejectsDifferentContractorReplayAndOverlappingNonterminalAssignments() throws Exception {
    UUID contractorId = UUID.randomUUID();
    var contractor =
        new CreateContractorDriverRequest(
            contractorId,
            "Сидоров",
            "+7 901 000-00-00",
            null);
    postJson("/api/internal/task-board/v1/logistics/warehouses/" + SOURCE + "/contractors", contractor)
        .andExpect(status().isCreated());
    postJson(
            "/api/internal/task-board/v1/logistics/warehouses/" + SOURCE + "/contractors",
            new CreateContractorDriverRequest(
                contractorId,
                "Сидоров",
                "+7 999 999-99-99",
                null))
        .andExpect(status().isConflict());

    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                contractorId,
                SOURCE,
                DESTINATION,
                WorkerOperationalAssignmentMode.TEMPORARY,
                now.plusMinutes(30),
                now.plusHours(1),
                now.plusHours(4)))
        .andExpect(status().isCreated());
    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                contractorId,
                SOURCE,
                DESTINATION,
                WorkerOperationalAssignmentMode.TEMPORARY,
                now.plusHours(2),
                now.plusHours(3),
                now.plusHours(6)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));
  }

  @Test
  void completedPermanentAssignmentBecomesTheDerivedOperationalBase() throws Exception {
    WorkerDto driver = createStaffDriver("Орлов");
    assertThat(
            jdbc.queryForObject(
                "select employment_type from worker where id=?", String.class, driver.id()))
        .isEqualTo("STAFF");
    JsonNode planned =
        response(
            postJson(
                    BASE,
                    new CreateWorkerOperationalAssignmentRequest(
                        UUID.randomUUID(),
                        driver.id(),
                        SOURCE,
                        DESTINATION,
                        WorkerOperationalAssignmentMode.PERMANENT,
                        now.minusHours(2),
                        now.minusHours(1),
                        null))
                .andExpect(status().isCreated()));
    UUID assignmentId = UUID.fromString(planned.required("id").textValue());
    JsonNode inTransit =
        response(
            transition(assignmentId, 0L, WorkerOperationalAssignmentStatus.IN_TRANSIT)
                .andExpect(status().isOk()));
    JsonNode active =
        response(
            transition(
                    assignmentId,
                    inTransit.required("version").longValue(),
                    WorkerOperationalAssignmentStatus.ACTIVE)
                .andExpect(status().isOk()));
    transition(
            assignmentId,
            active.required("version").longValue(),
            WorkerOperationalAssignmentStatus.COMPLETED)
        .andExpect(status().isOk());

    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(DESTINATION, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(driver.id().toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("ACTIVE_ASSIGNMENT"));
    assertThat(jdbc.queryForObject("select warehouse_id from worker where id=?", UUID.class, driver.id()))
        .isEqualTo(SOURCE);

    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                driver.id(),
                DESTINATION,
                SOURCE,
                WorkerOperationalAssignmentMode.PERMANENT,
                now.plusMinutes(30),
                now.plusHours(1),
                null))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.sourceWarehouseId").value(DESTINATION.toString()));
  }

  @Test
  void cancellingPlannedAssignmentRestoresHomeAvailabilityWithoutMovingHome() throws Exception {
    UUID contractorId = UUID.randomUUID();
    postJson(
            "/api/internal/task-board/v1/logistics/warehouses/" + SOURCE + "/contractors",
            new CreateContractorDriverRequest(
                contractorId,
                "Кузнецов",
                "+7 902 000-00-00",
                null))
        .andExpect(status().isCreated());
    JsonNode planned =
        response(
            postJson(
                    BASE,
                    new CreateWorkerOperationalAssignmentRequest(
                        UUID.randomUUID(),
                        contractorId,
                        SOURCE,
                        DESTINATION,
                        WorkerOperationalAssignmentMode.TEMPORARY,
                        now.minusHours(1),
                        now.minusMinutes(30),
                        now.plusHours(2)))
                .andExpect(status().isCreated()));
    UUID assignmentId = UUID.fromString(planned.required("id").textValue());

    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    transition(assignmentId, 0L, WorkerOperationalAssignmentStatus.CANCELLED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(contractorId.toString()));
    assertThat(jdbc.queryForObject("select warehouse_id from worker where id=?", UUID.class, contractorId))
        .isEqualTo(SOURCE);
  }

  @Test
  void tripOnlyBlocksTravelWithoutAdvertisingDestinationAndReturnsToSourceAfterCompletion()
      throws Exception {
    WorkerDto driver = createStaffDriver("Петров");
    OffsetDateTime travelStartsAt = now.minusHours(2);
    OffsetDateTime tripEndsAt = now.minusHours(1);
    JsonNode planned =
        response(
            postJson(
                    BASE,
                    new CreateWorkerOperationalAssignmentRequest(
                        UUID.randomUUID(),
                        driver.id(),
                        SOURCE,
                        DESTINATION,
                        WorkerOperationalAssignmentMode.TRIP_ONLY,
                        travelStartsAt,
                        tripEndsAt,
                        tripEndsAt))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.mode").value("TRIP_ONLY")));
    UUID assignmentId = UUID.fromString(planned.required("id").textValue());

    directory(SOURCE, travelStartsAt.minusMinutes(1), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(driver.id().toString()));
    directory(SOURCE, travelStartsAt.plusMinutes(30), false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(DESTINATION, tripEndsAt, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());

    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                driver.id(),
                SOURCE,
                DESTINATION,
                WorkerOperationalAssignmentMode.TRIP_ONLY,
                travelStartsAt.plusMinutes(30),
                tripEndsAt.plusMinutes(30),
                tripEndsAt.plusMinutes(30)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));

    JsonNode inTransit =
        response(
            transition(assignmentId, 0L, WorkerOperationalAssignmentStatus.IN_TRANSIT)
                .andExpect(status().isOk()));
    long inTransitVersion = inTransit.required("version").longValue();
    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    directory(DESTINATION, now, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    transition(assignmentId, inTransitVersion, WorkerOperationalAssignmentStatus.ACTIVE)
        .andExpect(status().isConflict());

    transition(assignmentId, inTransitVersion, WorkerOperationalAssignmentStatus.COMPLETED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("COMPLETED"));
    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(driver.id().toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("HOME"));
    directory(DESTINATION, now, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
    assertThat(
            jdbc.queryForObject(
                "select warehouse_id from worker where id=?", UUID.class, driver.id()))
        .isEqualTo(SOURCE);
  }

  @Test
  void tripOnlyCanBeCancelledInTransitWithoutChangingOperationalBase() throws Exception {
    WorkerDto driver = createStaffDriver("Смирнов");
    OffsetDateTime tripEndsAt = now.plusHours(1);
    JsonNode planned =
        response(
            postJson(
                    BASE,
                    new CreateWorkerOperationalAssignmentRequest(
                        UUID.randomUUID(),
                        driver.id(),
                        SOURCE,
                        DESTINATION,
                        WorkerOperationalAssignmentMode.TRIP_ONLY,
                        now.minusHours(1),
                        tripEndsAt,
                        tripEndsAt))
                .andExpect(status().isCreated()));
    UUID assignmentId = UUID.fromString(planned.required("id").textValue());
    JsonNode inTransit =
        response(
            transition(assignmentId, 0L, WorkerOperationalAssignmentStatus.IN_TRANSIT)
                .andExpect(status().isOk()));

    transition(
            assignmentId,
            inTransit.required("version").longValue(),
            WorkerOperationalAssignmentStatus.CANCELLED)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("CANCELLED"));
    directory(SOURCE, now, false)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(driver.id().toString()));
    directory(DESTINATION, now, true)
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
  }

  @Test
  void contractorTripOnlyMustHaveOneEndInstantWithoutProfileDateLimits() throws Exception {
    UUID contractorId = UUID.randomUUID();
    postJson(
            "/api/internal/task-board/v1/logistics/warehouses/" + SOURCE + "/contractors",
            new CreateContractorDriverRequest(
                contractorId,
                "Волков",
                "+7 903 000-00-00",
                null))
        .andExpect(status().isCreated());

    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                contractorId,
                SOURCE,
                DESTINATION,
                WorkerOperationalAssignmentMode.TRIP_ONLY,
                now.plusMinutes(5),
                now.plusHours(2),
                now.plusHours(2).plusMinutes(1)))
        .andExpect(status().isBadRequest());

    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                contractorId,
                SOURCE,
                DESTINATION,
                WorkerOperationalAssignmentMode.TRIP_ONLY,
                now.plusMinutes(5),
                now.plusHours(2),
                now.plusHours(2)))
        .andExpect(status().isCreated());

    postJson(
            BASE,
            new CreateWorkerOperationalAssignmentRequest(
                UUID.randomUUID(),
                contractorId,
                SOURCE,
                DESTINATION,
                WorkerOperationalAssignmentMode.TRIP_ONLY,
                now.plusMinutes(150),
                now.plusHours(4),
                now.plusHours(4)))
        .andExpect(status().isCreated());
  }

  private WorkerDto createStaffDriver(String displayName) {
    WorkerClassDto primary =
        registry.createClass(new WorkerClassRequest(0L, "Водитель", null, null, 0, true));
    var definition =
        QueueRegistryTestFixtures.ensureDriverDefinition(
            registry, jdbc, "Водители", QueueType.MOVEMENT);
    QueueRegistryTestFixtures.create(
        registry,
        jdbc,
        SOURCE,
        new QueueFixtureModels.QueueFixtureRequest(
            0,
            definition.id(),
            true,
            false,
            false,
            null,
            null,
            false,
            null,
            List.of(
                new QueueBindingRequest(
                    primary.id(), 0, false, ParticipationPolicy.PRIMARY, false))));
    return workforce.createWorker(
        SOURCE,
        new WorkerRequest(
            0L,
            displayName,
            null,
            null,
            null,
            true,
            null,
            null,
            null,
            List.of(new QualificationRequest(primary.id(), true, null))));
  }

  private ResultActions directory(UUID warehouseId, OffsetDateTime at, boolean includeIncoming)
      throws Exception {
    return mvc.perform(
        get(DIRECTORY.formatted(warehouseId))
            .queryParam("at", at.toString())
            .queryParam("includeIncoming", Boolean.toString(includeIncoming))
            .with(logisticsJwt()));
  }

  private ResultActions transition(
      UUID assignmentId, long expectedVersion, WorkerOperationalAssignmentStatus status)
      throws Exception {
    return postJson(
        BASE + "/" + assignmentId + "/transition",
        new TransitionWorkerOperationalAssignmentRequest(expectedVersion, status));
  }

  private ResultActions postJson(String path, Object body) throws Exception {
    return mvc.perform(
        post(path)
            .with(logisticsJwt())
            .contentType(MediaType.APPLICATION_JSON)
            .content(objectMapper.writeValueAsString(body)));
  }

  private JsonNode response(ResultActions result) throws Exception {
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
