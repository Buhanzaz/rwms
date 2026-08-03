package dev.buhanzaz.rwms.taskboard;
import static dev.buhanzaz.rwms.taskboard.QueueFixtureModels.*;

import static dev.buhanzaz.rwms.taskboard.api.ApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.taskboard.domain.QueueReferenceType;
import dev.buhanzaz.rwms.taskboard.domain.QueueType;
import dev.buhanzaz.rwms.taskboard.service.ConflictException;
import dev.buhanzaz.rwms.taskboard.service.RegistryService;
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
  private static final String WAREHOUSE_PREFLIGHT =
      "/api/internal/task-board/v1/maintenance/routing-preflight";
  private static final String CATALOG_PREFLIGHT =
      "/api/internal/task-board/v1/maintenance/catalog-routing-preflight";
  private static final UUID SPB =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK =
      UUID.fromString("00000000-0000-0000-0000-000000000002");

  @Autowired RegistryService registry;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @BeforeEach
  void clean() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void globalDefinitionPreflightDoesNotRequireWarehouseBinding() throws Exception {
    var definition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(
                0L, "Внешние работы", null, QueueType.REPAIR));

    mvc.perform(
            post(CATALOG_PREFLIGHT)
                .with(exactMaintenanceJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new CatalogRoutingPreflightRequest(
                            List.of(
                                new MaintenanceRoutingQueueRequirement(
                                    definition.id(), QueueType.REPAIR))))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(true))
        .andExpect(jsonPath("$.resolvedDefinitions[0].queueDefinitionId")
            .value(definition.id().toString()));

    assertThat(registry.listQueues(SPB)).isEmpty();
    assertThat(registry.listQueues(MSK)).isEmpty();
  }

  @Test
  void warehousePreflightResolvesDefinitionToItsActiveVisibleBinding() throws Exception {
    var definition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(
                0L, "Внутренние работы", null, QueueType.REPAIR));
    var binding = QueueRegistryTestFixtures.create(registry, jdbc, MSK, binding(definition.id(), true, false));

    mvc.perform(
            post(WAREHOUSE_PREFLIGHT)
                .with(exactMaintenanceJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        warehouseRequest(MSK, definition.id(), QueueType.REPAIR))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(true))
        .andExpect(jsonPath("$.missingQueueDefinitionIds").isEmpty())
        .andExpect(jsonPath("$.missingWarehouseBindingDefinitionIds").isEmpty())
        .andExpect(jsonPath("$.resolvedQueues[0].queueDefinitionId")
            .value(definition.id().toString()))
        .andExpect(jsonPath("$.resolvedQueues[0].workQueueId")
            .value(binding.id().toString()));
  }

  @Test
  void missingBindingAndDisabledBindingFailClosedWithoutNameFallback() throws Exception {
    var definition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(0L, "Электрики", null, QueueType.REPAIR));

    mvc.perform(
            post(WAREHOUSE_PREFLIGHT)
                .with(exactMaintenanceJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        warehouseRequest(MSK, definition.id(), QueueType.REPAIR))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(false))
        .andExpect(jsonPath("$.missingWarehouseBindingDefinitionIds[0]")
            .value(definition.id().toString()));

    var binding = QueueRegistryTestFixtures.create(registry, jdbc, MSK, binding(definition.id(), false, true));
    mvc.perform(
            post(WAREHOUSE_PREFLIGHT)
                .with(exactMaintenanceJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        warehouseRequest(MSK, definition.id(), QueueType.REPAIR))))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.ready").value(false))
        .andExpect(jsonPath("$.mismatches[0].fields[0]").value("ACTIVE"))
        .andExpect(jsonPath("$.mismatches[0].fields[1]").value("HIDDEN"));

    assertThat(registry.listQueues(MSK)).extracting(WorkQueueDto::id).containsExactly(binding.id());
  }

  @Test
  void catalogReferenceBlocksGlobalDefinitionDeleteAndKeepsWarehouseProjection() {
    var definition =
        registry.createQueueDefinition(
            new QueueDefinitionRequest(0L, "Сварка", null, QueueType.REPAIR));
    var binding = QueueRegistryTestFixtures.create(registry, jdbc, SPB, binding(definition.id(), true, false));
    var reference =
        registry.registerReference(
            definition.id(),
            new QueueReferenceRequest(
                QueueReferenceType.CATALOG_POSITION, "catalog-position-1"));

    assertThatThrownBy(
            () ->
                registry.deleteQueueDefinition(
                    definition.id(), definition.version()))
        .isInstanceOf(ConflictException.class)
        .hasMessageContaining("каталог");
    assertThat(registry.listQueues(SPB))
        .extracting(WorkQueueDto::id)
        .containsExactly(binding.id());

    registry.deleteReference(
        reference.type(), reference.externalReferenceId(), reference.version());
    registry.deleteQueue(SPB, binding.id(), binding.version());
    registry.deleteQueueDefinition(definition.id(), definition.version());
    assertThat(registry.listQueueDefinitions()).isEmpty();
  }

  @Test
  void exactMaintenanceServiceIdentityAndScopeAreRequired() throws Exception {
    String body =
        objectMapper.writeValueAsString(
            new CatalogRoutingPreflightRequest(
                List.of(
                    new MaintenanceRoutingQueueRequirement(
                        UUID.randomUUID(), QueueType.REPAIR))));

    mvc.perform(
            post(CATALOG_PREFLIGHT)
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    mvc.perform(
            post(CATALOG_PREFLIGHT)
                .with(
                    maintenanceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        "SERVICE",
                        List.of("task-board.task-sync", "rwms.write")))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isForbidden());
  }

  private QueueFixtureRequest binding(UUID definitionId, boolean active, boolean hidden) {
    return new QueueFixtureRequest(
        0L,
        definitionId,
        active,
        hidden,
        false,
        null,
        null,
        false,
        null,
        List.of());
  }

  private MaintenanceRoutingPreflightRequest warehouseRequest(
      UUID warehouseId, UUID definitionId, QueueType type) {
    return new MaintenanceRoutingPreflightRequest(
        warehouseId,
        List.of(new MaintenanceRoutingQueueRequirement(definitionId, type)));
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
}
