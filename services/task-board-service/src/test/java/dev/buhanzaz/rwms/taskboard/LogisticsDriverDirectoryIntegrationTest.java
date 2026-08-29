package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.ParticipationPolicy;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
import dev.buhanzaz.rwms.taskboard.service.WorkforceService;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the qualification, active-state, ordering, and exact-service fences of the directory. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LogisticsDriverDirectoryIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000731");
  private static final String PATH =
      "/api/internal/task-board/v1/logistics/warehouses/" + WAREHOUSE + "/drivers";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;
  @Autowired RegistryService registry;
  @Autowired WorkforceService workforce;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void returnsOnlyActiveWorkersWithTheActivePrimaryDriverQualification() throws Exception {
    WorkerClassDto primary =
        registry.createClass(new WorkerClassRequest(0L, "Водитель", null, null, 0, true));
    WorkerClassDto secondary =
        registry.createClass(new WorkerClassRequest(0L, "Такелажник", null, null, 1, true));
    QueueDefinitionDto definition =
        QueueRegistryTestFixtures.ensureDriverDefinition(
            registry, jdbc, "Водители", QueueType.MOVEMENT);
    QueueRegistryTestFixtures.create(
        registry,
        jdbc,
        WAREHOUSE,
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
                    primary.id(), 0, false, ParticipationPolicy.PRIMARY, false),
                new QueueBindingRequest(
                    secondary.id(), 1, false, ParticipationPolicy.OPTIONAL, false))));

    WorkerDto zoya = createWorker("Зоя", true, primary.id(), true);
    WorkerDto anna = createWorker("Анна", true, primary.id(), true);
    createWorker("Неактивный", false, primary.id(), true);
    createWorker("Квалификация выключена", true, primary.id(), false);
    createWorker("Только поддержка", true, secondary.id(), true);
    workforce.createWorker(
        WAREHOUSE,
        new WorkerRequest(
            0L, "Без квалификации", null, null, null, true, null, null, null, List.of()));

    mvc.perform(get(PATH).with(logisticsJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(anna.id().toString()))
        .andExpect(jsonPath("$[0].displayName").value("Анна"))
        .andExpect(jsonPath("$[1].workerId").value(zoya.id().toString()))
        .andExpect(jsonPath("$[1].displayName").value("Зоя"))
        .andExpect(jsonPath("$[2]").doesNotExist());

    jdbc.update("update work_queue set active=false where warehouse_id=?", WAREHOUSE);

    mvc.perform(get(PATH).with(logisticsJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0]").doesNotExist());
  }

  @Test
  void rejectsAnyCredentialOtherThanTheExactLogisticsServiceIdentity() throws Exception {
    mvc.perform(
            get(PATH)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("other-service")
                                    .claim("client_id", "other-service")
                                    .claim("principal_type", "SERVICE")
                                    .claim("scope", "task-board.logistics"))))
        .andExpect(status().isForbidden());

    mvc.perform(
            get(PATH)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject("logistics-service")
                                    .claim("client_id", "logistics-service")
                                    .claim("principal_type", "SERVICE")
                                    .claim(
                                        "scope",
                                        "task-board.logistics task-board.task-sync"))))
        .andExpect(status().isForbidden());
  }

  private WorkerDto createWorker(
      String displayName, boolean active, UUID workerClassId, boolean qualificationActive) {
    return workforce.createWorker(
        WAREHOUSE,
        new WorkerRequest(
            0L,
            displayName,
            null,
            null,
            null,
            active,
            null,
            null,
            null,
            List.of(new QualificationRequest(workerClassId, qualificationActive, null))));
  }

  private JwtRequestPostProcessor logisticsJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("logistics-service")
                    .claim("client_id", "logistics-service")
                    .claim("principal_type", "SERVICE")
                    .claim("scope", "task-board.logistics"));
  }
}
