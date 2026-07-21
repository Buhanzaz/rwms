package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateEquipmentRequest;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.service.AssetService;
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

  @Autowired AssetService service;
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
  void createsFurnitureOnceAndReturnsTheSameSanitizedIdentityOnRepeat() throws Exception {
    String code = code("ESTIMATE-TABLE");

    JsonNode created = ensure(code.toLowerCase(), "  Стол  ", status().isOk());
    UUID equipmentId = UUID.fromString(created.get("equipmentId").asText());
    JsonNode repeated = ensure(code, "Стол", status().isOk());

    assertThat(Set.copyOf(created.propertyNames()))
        .containsExactlyInAnyOrder("equipmentId", "equipmentCode", "equipmentName");
    assertThat(created.get("equipmentCode").asText()).isEqualTo(code);
    assertThat(created.get("equipmentName").asText()).isEqualTo("Стол");
    assertThat(repeated).isEqualTo(created);
    assertThat(catalogCount(code)).isOne();
    assertThat(eventCount(equipmentId)).isOne();
    assertThat(eventCount(equipmentId, AssetEventType.EQUIPMENT_CATALOG_CREATED)).isOne();
    assertThat(outboxCount(equipmentId)).isOne();
  }

  @Test
  void rejectsNameOrCategoryReuseWithoutMutation() throws Exception {
    EquipmentResponse furniture = createEquipment(code("ESTIMATE-CHAIR"), "Стул", EquipmentCategory.FURNITURE);
    EquipmentResponse electrical =
        createEquipment(code("ESTIMATE-LAMP"), "Лампа", EquipmentCategory.ELECTRICAL);
    int furnitureEvents = eventCount(furniture.id());
    int furnitureOutbox = outboxCount(furniture.id());
    int electricalEvents = eventCount(electrical.id());
    int electricalOutbox = outboxCount(electrical.id());

    ensure(furniture.code(), "Другой стул", status().isConflict());
    ensure(electrical.code(), electrical.name(), status().isConflict());

    assertEquipmentUnchanged(furniture);
    assertEquipmentUnchanged(electrical);
    assertThat(eventCount(furniture.id())).isEqualTo(furnitureEvents);
    assertThat(outboxCount(furniture.id())).isEqualTo(furnitureOutbox);
    assertThat(eventCount(electrical.id())).isEqualTo(electricalEvents);
    assertThat(outboxCount(electrical.id())).isEqualTo(electricalOutbox);
  }

  @Test
  void reactivatesExactInactiveFurnitureOnceAndWritesChangedEventAndOutbox() throws Exception {
    EquipmentResponse created =
        createEquipment(code("ESTIMATE-DESK"), "Письменный стол", EquipmentCategory.FURNITURE);
    EquipmentResponse inactive = service.updateEquipment(
        created.id(),
        new UpdateEquipmentRequest(
            created.version(),
            created.code(),
            created.name(),
            created.category(),
            false,
            created.comment()));
    int eventsBeforeEnsure = eventCount(created.id());
    int outboxBeforeEnsure = outboxCount(created.id());

    JsonNode reactivated = ensure(inactive.code(), inactive.name(), status().isOk());
    EquipmentResponse active = service.equipment(created.id());

    assertThat(reactivated.get("equipmentId").asText()).isEqualTo(created.id().toString());
    assertThat(active.active()).isTrue();
    assertThat(active.version()).isEqualTo(inactive.version() + 1);
    assertThat(eventCount(created.id())).isEqualTo(eventsBeforeEnsure + 1);
    assertThat(eventCount(created.id(), AssetEventType.EQUIPMENT_CATALOG_CHANGED)).isEqualTo(2);
    assertThat(outboxCount(created.id())).isEqualTo(outboxBeforeEnsure + 1);

    ensure(inactive.code(), inactive.name(), status().isOk());

    assertThat(service.equipment(created.id()).version()).isEqualTo(active.version());
    assertThat(eventCount(created.id())).isEqualTo(eventsBeforeEnsure + 1);
    assertThat(outboxCount(created.id())).isEqualTo(outboxBeforeEnsure + 1);
  }

  @Test
  void requiresExactlyTheMaintenanceServiceCredentialAndScope() throws Exception {
    String path = "/api/internal/asset/v1/maintenance/equipment-catalog/{equipmentCode}";
    String request = requestBody("Автосвязь");

    mvc.perform(put(path, code("USER")).with(userJwt("asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
    mvc.perform(put(path, code("WRONG-CLIENT"))
            .with(serviceJwt("inventory-service", "inventory-service", "asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
    mvc.perform(put(path, code("WRONG-SUBJECT"))
            .with(serviceJwt("inventory-service", "maintenance-service", "asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());
    mvc.perform(put(path, code("EXTRA-SCOPE"))
            .with(serviceJwt(
                "maintenance-service",
                "maintenance-service",
                "asset.maintenance asset.internal"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isForbidden());

    String acceptedCode = code("EXACT-SCOPE");
    mvc.perform(put(path, acceptedCode)
            .with(serviceJwt("maintenance-service", "maintenance-service", "asset.maintenance"))
            .contentType(MediaType.APPLICATION_JSON).content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.equipmentCode").value(acceptedCode));

    assertThat(catalogCount(acceptedCode)).isOne();
  }

  private JsonNode ensure(
      String equipmentCode,
      String equipmentName,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus)
      throws Exception {
    MvcResult result = mvc.perform(
            put(
                    "/api/internal/asset/v1/maintenance/equipment-catalog/{equipmentCode}",
                    equipmentCode)
                .with(serviceJwt(
                    "maintenance-service", "maintenance-service", "asset.maintenance"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(requestBody(equipmentName)))
        .andExpect(expectedStatus)
        .andReturn();
    return objectMapper.readTree(result.getResponse().getContentAsString());
  }

  private String requestBody(String equipmentName) throws Exception {
    return objectMapper.writeValueAsString(Map.of("equipmentName", equipmentName));
  }

  private EquipmentResponse createEquipment(
      String code, String name, EquipmentCategory category) {
    return service.createEquipment(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new CreateEquipmentRequest(code, name, category, null))
        .response();
  }

  private void assertEquipmentUnchanged(EquipmentResponse before) {
    EquipmentResponse after = service.equipment(before.id());
    assertThat(after.id()).isEqualTo(before.id());
    assertThat(after.version()).isEqualTo(before.version());
    assertThat(after.code()).isEqualTo(before.code());
    assertThat(after.name()).isEqualTo(before.name());
    assertThat(after.category()).isEqualTo(before.category());
    assertThat(after.active()).isEqualTo(before.active());
    assertThat(after.comment()).isEqualTo(before.comment());
    assertThat(catalogCount(before.code())).isOne();
  }

  private int catalogCount(String code) {
    return jdbc.queryForObject(
        "select count(*) from equipment_catalog_item where code=?", Integer.class, code);
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

  private static String code(String prefix) {
    return prefix + "-" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
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
