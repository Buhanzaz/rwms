package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.asset.warehouse-registry.enabled=false",
      "spring.cloud.function.definition=",
      "spring.task.scheduling.enabled=false"
    })
@ActiveProfiles("test")
@AutoConfigureMockMvc
class MaintenanceFurnitureCatalogIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri",
        () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void createsFurnitureOnceAndReplaysTheSameUuidIdentity() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    String equipmentName = "Стол автосвязь " + UUID.randomUUID();
    JsonNode created = create(idempotencyKey, equipmentName, status().isCreated());
    UUID equipmentId = UUID.fromString(created.get("equipmentId").asText());
    JsonNode repeated = create(idempotencyKey, equipmentName, status().isCreated());

    assertThat(Set.copyOf(created.propertyNames()))
        .containsExactlyInAnyOrder("externalReferenceId", "equipmentId", "equipmentName");
    assertThat(created.get("externalReferenceId").asText()).isEqualTo(idempotencyKey.toString());
    assertThat(created.get("equipmentName").asText()).isEqualTo(equipmentName);
    assertThat(repeated).isEqualTo(created);
    assertThat(catalogCount(equipmentId)).isOne();
    assertThat(eventCount(equipmentId)).isOne();
    assertThat(eventCount(equipmentId, AssetEventType.EQUIPMENT_CATALOG_CREATED)).isOne();
    assertThat(outboxCount(equipmentId)).isOne();
  }

  @Test
  void rejectsADifferentNameForTheSameIdempotencyKeyWithoutMutation() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    JsonNode created =
        create(idempotencyKey, "Стул автосвязь " + UUID.randomUUID(), status().isCreated());
    UUID equipmentId = UUID.fromString(created.get("equipmentId").asText());

    create(idempotencyKey, "Другой стул", status().isConflict());

    assertThat(catalogCount(equipmentId)).isOne();
    assertThat(eventCount(equipmentId)).isOne();
    assertThat(outboxCount(equipmentId)).isOne();
  }

  @Test
  void replayKeepsCreatedStatusAndMarksTheResponseHeader() throws Exception {
    UUID idempotencyKey = UUID.randomUUID();
    String equipmentName = "Стол с заголовком " + UUID.randomUUID();
    create(idempotencyKey, equipmentName, status().isCreated());

    MvcResult replay =
        mvc.perform(
                post("/api/internal/asset/v1/maintenance/equipment-catalog")
                    .header("Idempotency-Key", idempotencyKey)
                    .with(serviceJwt(
                        "maintenance-service", "maintenance-service", "asset.maintenance"))
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(requestBody(idempotencyKey, equipmentName)))
            .andExpect(status().isCreated())
            .andReturn();

    assertThat(replay.getResponse().getHeader("Idempotency-Replayed")).isEqualTo("true");
  }

  @Test
  void requiresExactlyTheMaintenanceServiceCredentialAndScope() throws Exception {
    String path = "/api/internal/asset/v1/maintenance/equipment-catalog";
    String request = requestBody(UUID.randomUUID(), "Автосвязь");

    mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID()).with(userJwt("asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID())
            .with(serviceJwt("inventory-service", "inventory-service", "asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID())
            .with(serviceJwt("inventory-service", "maintenance-service", "asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
    mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID())
            .with(serviceJwt(
                "maintenance-service",
                "maintenance-service",
                "asset.maintenance asset.internal"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());

    mvc.perform(post(path).header("Idempotency-Key", UUID.randomUUID())
            .with(serviceJwt("maintenance-service", "maintenance-service", "asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.equipmentName").value("Автосвязь"));

    assertThat(jdbc.queryForObject(
        "select count(*) from equipment_catalog_item where name='Автосвязь'", Integer.class)).isOne();
  }

  private JsonNode create(
      UUID idempotencyKey,
      String equipmentName,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus)
      throws Exception {
    MvcResult result = mvc.perform(
            post("/api/internal/asset/v1/maintenance/equipment-catalog")
                .header("Idempotency-Key", idempotencyKey)
                .with(serviceJwt(
                    "maintenance-service", "maintenance-service", "asset.maintenance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(idempotencyKey, equipmentName)))
        .andExpect(expectedStatus)
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String requestBody(UUID externalReferenceId, String equipmentName) throws Exception {
    return objectMapper.writeValueAsString(
        Map.of(
            "externalReferenceId", externalReferenceId,
            "equipmentName", equipmentName));
  }

  private int catalogCount(UUID equipmentId) {
    return jdbc.queryForObject(
        "select count(*) from equipment_catalog_item where id=?", Integer.class, equipmentId);
  }

  private int eventCount(UUID equipmentId) {
    return jdbc.queryForObject(
        "select count(*) from domain_event where aggregate_type='EQUIPMENT_CATALOG' and aggregate_id=?",
        Integer.class,
        equipmentId.toString());
  }

  private int eventCount(UUID equipmentId, AssetEventType eventType) {
    return jdbc.queryForObject(
        """
        select count(*) from domain_event
        where aggregate_type='EQUIPMENT_CATALOG' and aggregate_id=? and event_type=?
        """,
        Integer.class,
        equipmentId.toString(),
        eventType.value());
  }

  private int outboxCount(UUID equipmentId) {
    return jdbc.queryForObject(
        "select count(*) from outbox_event where aggregate_type='EQUIPMENT_CATALOG' and aggregate_id=?",
        Integer.class,
        equipmentId.toString());
  }

  private static JwtRequestPostProcessor serviceJwt(
      String subject, String clientId, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subject)
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", clientId)
                    .claim("scope", scope));
  }

  private static JwtRequestPostProcessor userJwt(String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("scope", scope));
  }
}
