package dev.buhanzaz.rwms.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseIdempotencyStore;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class WarehouseServiceIntegrationTest {
  private static final UUID SPB = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired WarehouseService service;
  @Autowired WarehouseIdempotencyStore idempotency;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mockMvc;
  @Autowired ObjectMapper objectMapper;

  @DynamicPropertySource
  static void database(DynamicPropertyRegistry properties) {
    properties.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    properties.add("spring.datasource.username", POSTGRES::getUsername);
    properties.add("spring.datasource.password", POSTGRES::getPassword);
    properties.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    properties.add("rwms.platform.kafka.enabled", () -> "false");
    properties.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:65535/jwks");
  }

  @BeforeEach
  void cleanFixtures() {
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from outbox_event");
    jdbc.update("delete from warehouse where id not in (?, ?)", SPB, MSK);
  }

  @Test
  void createsReplaysExpiresAndProtectsTheIdempotencyBoundary() {
    UUID subject = UUID.randomUUID();
    UUID key = UUID.randomUUID();
    CreateWarehouseRequest first = request(" Test west ", 4);

    WarehouseService.CreateResult created = service.create(subject, key, first);
    WarehouseService.CreateResult replayed =
        service.create(subject, key, request("Test west", 4));

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(count("select count(*) from warehouse where name='Test west'")).isOne();
    assertThat(
            count(
                "select count(*) from outbox_event where aggregate_id=?",
                created.response().id().toString()))
        .isOne();
    assertThatThrownBy(() -> service.create(subject, key, request("Test east", 4)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("Idempotency-Key");

    jdbc.update(
        """
        update idempotency_record
           set created_at=clock_timestamp()-interval '8 days',
               expires_at=clock_timestamp()-interval '1 second'
         where subject_id=? and idempotency_key=?
        """,
        subject,
        key);
    assertThat(idempotency.cleanupExpired()).isOne();
    assertThat(service.create(subject, key, request("Test east", 4)).replayed())
        .isFalse();
  }

  @Test
  void enforcesOptimisticVersionNoOpAndDeactivationTransitions() {
    WarehouseResponse created =
        service
            .create(UUID.randomUUID(), UUID.randomUUID(), request("Operations", null))
            .response();
    WarehouseResponse noOp =
        service.replace(
            created.id(), replace(created, created.version(), "Operations", true, null));
    assertThat(noOp.version()).isEqualTo(created.version());
    assertThat(
            count(
                "select count(*) from outbox_event where aggregate_id=?", created.id().toString()))
        .isOne();

    WarehouseResponse changed =
        service.replace(
            created.id(),
            replace(created, created.version(), "Operations updated", true, 5));
    assertThat(changed.version()).isEqualTo(created.version() + 1);
    assertThat(eventTypes(created.id()))
        .containsExactly("warehouse.warehouse.created.v1", "warehouse.warehouse.changed.v1");
    assertThatThrownBy(
            () ->
                service.replace(
                    created.id(), replace(changed, created.version(), "Stale", true, 5)))
        .isInstanceOf(WarehouseConflictException.class);

    service.deactivate(created.id(), changed.version());
    assertThat(eventTypes(created.id()))
        .containsExactly(
            "warehouse.warehouse.created.v1",
            "warehouse.warehouse.changed.v1",
            "warehouse.warehouse.deactivated.v1");
    WarehouseResponse inactive = service.get(created.id());
    assertThatThrownBy(
            () ->
                service.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request(" operations\tUPDATED ", null)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("name");
    WarehouseResponse reactivated =
        service.replace(
            inactive.id(),
            replace(
                inactive,
                inactive.version(),
                inactive.name(),
                true,
                inactive.sortOrder()));
    assertThat(reactivated.active()).isTrue();
    assertThat(eventTypes(created.id()).getLast()).isEqualTo("warehouse.warehouse.changed.v1");

    WarehouseResponse anotherWarehouse =
        service
            .create(UUID.randomUUID(), UUID.randomUUID(), request("Field office", null))
            .response();
    assertThatThrownBy(
            () ->
                service.replace(
                    anotherWarehouse.id(),
                    replace(
                        anotherWarehouse,
                        anotherWarehouse.version(),
                        "OPERATIONS  updated",
                        true,
                        null)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("name");
  }

  @Test
  void publicWarehouseReadReturnsInactiveWarehouseByUuidAndListStaysActiveOnly() throws Exception {
    WarehouseResponse inactive =
        service
            .create(UUID.randomUUID(), UUID.randomUUID(), request("Historical warehouse", null))
            .response();
    service.deactivate(inactive.id(), inactive.version());

    String body =
        mockMvc
            .perform(
                get("/api/warehouse/v1/warehouses/{id}", inactive.id()).with(warehouseReadUserJwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(objectMapper.readTree(body).get("active").booleanValue()).isFalse();
    assertThat(service.list(false)).extracting(WarehouseResponse::id).doesNotContain(inactive.id());
  }

  @Test
  void duplicateWarehouseNamesReturnConflictForCreateAndReplace() throws Exception {
    WarehouseResponse existing =
        service
            .create(UUID.randomUUID(), UUID.randomUUID(), request("Registry depot", null))
            .response();
    WarehouseResponse other =
        service
            .create(UUID.randomUUID(), UUID.randomUUID(), request("Registry overflow", null))
            .response();

    mockMvc
        .perform(
            post("/api/warehouse/v1/warehouses")
                .with(systemAdminWriteJwt())
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request(" registry\tDEPOT ", null))))
        .andExpect(status().isConflict())
        .andExpect(
            result ->
                assertThat(
                        objectMapper
                            .readTree(result.getResponse().getContentAsString())
                            .get("code")
                            .stringValue())
                    .isEqualTo("WAREHOUSE_CONFLICT"));

    mockMvc
        .perform(
            put("/api/warehouse/v1/warehouses/{id}", other.id())
                .with(systemAdminWriteJwt())
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        replace(
                            other,
                            other.version(),
                            "REGISTRY  depot",
                            true,
                            other.sortOrder()))))
        .andExpect(status().isConflict())
        .andExpect(
            result ->
                assertThat(
                        objectMapper
                            .readTree(result.getResponse().getContentAsString())
                            .get("code")
                            .stringValue())
                    .isEqualTo("WAREHOUSE_CONFLICT"));

    assertThat(existing.id()).isNotEqualTo(other.id());
  }

  @Test
  void exposesTheExactInternalAuthExistenceResponseAndRejectsBroaderScope() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/internal/warehouse/v1/warehouses/{id}/existence", SPB)
                    .with(
                        jwt()
                            .jwt(
                                token ->
                                    token
                                        .subject(UUID.randomUUID().toString())
                                        .claim("principal_type", "SERVICE")
                                        .claim("client_id", "auth-service")
                                        .claim("scope", "warehouse.read"))))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    JsonNode response = objectMapper.readTree(body);
    assertThat(Set.copyOf(response.propertyNames()))
        .containsExactlyInAnyOrder("id", "version", "active");
    assertThat(response.get("id").stringValue()).isEqualTo(SPB.toString());
    assertThat(response.get("active").booleanValue()).isTrue();

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/{id}/existence", SPB)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject(UUID.randomUUID().toString())
                                    .claim("principal_type", "SERVICE")
                                    .claim("client_id", "auth-service")
                                    .claim("scope", "warehouse.read rwms.read"))))
        .andExpect(status().isForbidden());

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/asset/{id}/existence", SPB)
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .subject(UUID.randomUUID().toString())
                                    .claim("principal_type", "SERVICE")
                                    .claim("client_id", "asset-service")
                                    .claim("scope", "warehouse.read"))))
        .andExpect(status().isOk());
  }

  @Test
  void exposesOnlyActiveInventoryWarehouseMetadata() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/internal/warehouse/v1/warehouses/inventory/{id}/metadata", SPB)
                    .with(
                        inventoryServiceJwt(
                            "inventory-service", "inventory-service", "SERVICE", "warehouse.read")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode response = objectMapper.readTree(body);
    assertThat(Set.copyOf(response.propertyNames()))
        .containsExactlyInAnyOrder("id", "version", "active", "timeZone");
    assertThat(response.get("id").stringValue()).isEqualTo(SPB.toString());
    assertThat(response.get("version").longValue()).isZero();
    assertThat(response.get("active").booleanValue()).isTrue();
    assertThat(response.get("timeZone").stringValue()).isEqualTo("Europe/Moscow");

    WarehouseResponse inactive =
        service
            .create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Inventory hidden", null))
            .response();
    service.deactivate(inactive.id(), inactive.version());

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/inventory/{id}/metadata", inactive.id())
                .with(
                    inventoryServiceJwt(
                        "inventory-service", "inventory-service", "SERVICE", "warehouse.read")))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/inventory/{id}/metadata", UUID.randomUUID())
                .with(
                    inventoryServiceJwt(
                        "inventory-service", "inventory-service", "SERVICE", "warehouse.read")))
        .andExpect(status().isNotFound());
  }

  @Test
  void inventoryWarehouseMetadataRejectsEveryBroaderOrForeignAuthority() throws Exception {
    var invalidTokens =
        java.util.List.of(
            inventoryServiceJwt(
                "inventory-service", "inventory-service", "SERVICE", "warehouse.read rwms.read"),
            inventoryServiceJwt(
                "inventory-service", "inventory-service", "SERVICE", "asset.inventory"),
            inventoryServiceJwt("inventory-service", "inventory-service", "SERVICE", ""),
            inventoryServiceJwt("asset-service", "inventory-service", "SERVICE", "warehouse.read"),
            inventoryServiceJwt("inventory-service", "other-service", "SERVICE", "warehouse.read"),
            inventoryServiceJwt(
                "inventory-service", "inventory-service", "USER", "warehouse.read"));

    for (var invalid : invalidTokens) {
      mockMvc
          .perform(
              get("/api/internal/warehouse/v1/warehouses/inventory/{id}/metadata", SPB)
                  .with(invalid))
          .andExpect(status().isForbidden());
    }
  }

  @Test
  void exposesExactLogisticsWarehouseIdentityIncludingInactiveState() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/internal/warehouse/v1/warehouses/logistics/{id}/identity", SPB)
                    .with(
                        logisticsServiceJwt(
                            "logistics-service",
                            "logistics-service",
                            "SERVICE",
                            "warehouse.logistics")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode response = objectMapper.readTree(body);
    assertThat(Set.copyOf(response.propertyNames()))
        .containsExactlyInAnyOrder(
            "id", "version", "active", "name", "city", "timeZone");
    assertThat(response.get("id").stringValue()).isEqualTo(SPB.toString());
    assertThat(response.get("version").longValue()).isZero();
    assertThat(response.get("active").booleanValue()).isTrue();
    assertThat(response.get("name").stringValue()).isEqualTo("СПБ");
    assertThat(response.get("city").stringValue()).isEqualTo("Санкт-Петербург");
    assertThat(response.get("timeZone").stringValue()).isEqualTo("Europe/Moscow");

    WarehouseResponse inactive =
        service
            .create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Logistics inactive", null))
            .response();
    service.deactivate(inactive.id(), inactive.version());

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/logistics/{id}/identity", inactive.id())
                .with(
                    logisticsServiceJwt(
                        "logistics-service",
                        "logistics-service",
                        "SERVICE",
                        "warehouse.logistics")))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(
                        objectMapper
                            .readTree(result.getResponse().getContentAsString())
                            .get("active")
                            .booleanValue())
                    .isFalse());

    JsonNode active =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/internal/warehouse/v1/warehouses/logistics")
                        .with(
                            logisticsServiceJwt(
                                "logistics-service",
                                "logistics-service",
                                "SERVICE",
                                "warehouse.logistics")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(active.isArray()).isTrue();
    assertThat(
            java.util.stream.StreamSupport.stream(active.spliterator(), false)
                .map(value -> value.get("id").stringValue())
                .toList())
        .containsExactlyInAnyOrder(SPB.toString(), MSK.toString())
        .doesNotContain(inactive.id().toString());

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/logistics/{id}/identity", UUID.randomUUID())
                .with(
                    logisticsServiceJwt(
                        "logistics-service",
                        "logistics-service",
                        "SERVICE",
                        "warehouse.logistics")))
        .andExpect(status().isNotFound());
  }

  @Test
  void logisticsWarehouseIdentityRejectsEveryBroaderOrForeignAuthority() throws Exception {
    var invalidTokens =
        java.util.List.of(
            logisticsServiceJwt(
                "logistics-service",
                "logistics-service",
                "SERVICE",
                "warehouse.logistics warehouse.read"),
            logisticsServiceJwt(
                "logistics-service", "logistics-service", "SERVICE", "warehouse.read"),
            logisticsServiceJwt(
                "asset-service", "logistics-service", "SERVICE", "warehouse.logistics"),
            logisticsServiceJwt(
                "logistics-service", "other-service", "SERVICE", "warehouse.logistics"),
            logisticsServiceJwt(
                "logistics-service", "logistics-service", "USER", "warehouse.logistics"));

    for (var invalid : invalidTokens) {
      mockMvc
          .perform(
              get("/api/internal/warehouse/v1/warehouses/logistics/{id}/identity", SPB)
                  .with(invalid))
          .andExpect(status().isForbidden());
      mockMvc
          .perform(
              get("/api/internal/warehouse/v1/warehouses/logistics")
                  .with(invalid))
          .andExpect(status().isForbidden());
    }
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      inventoryServiceJwt(String clientId, String subject, String principalType, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subject)
                    .audience(java.util.List.of("rwms-services"))
                    .claim("principal_type", principalType)
                    .claim("client_id", clientId)
                    .claim("scope", scope));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      warehouseReadUserJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "warehouse.read"));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      systemAdminWriteJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", "SYSTEM_ADMIN")
                    .claim("scope", "rwms.write"));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      logisticsServiceJwt(String clientId, String subject, String principalType, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subject)
                    .audience(java.util.List.of("rwms-services"))
                    .claim("principal_type", principalType)
                    .claim("client_id", clientId)
                    .claim("scope", scope));
  }

  private CreateWarehouseRequest request(String name, Integer sortOrder) {
    return new CreateWarehouseRequest(name, "Москва", "", "Europe/Moscow", sortOrder);
  }

  private ReplaceWarehouseRequest replace(
      WarehouseResponse warehouse,
      long expectedVersion,
      String name,
      boolean active,
      Integer sortOrder) {
    return new ReplaceWarehouseRequest(
        expectedVersion,
        name,
        warehouse.city(),
        warehouse.address(),
        warehouse.timeZone(),
        active,
        sortOrder);
  }

  private long count(String query, Object... arguments) {
    Long result = jdbc.queryForObject(query, Long.class, arguments);
    return result == null ? 0 : result;
  }

  private java.util.List<String> eventTypes(UUID warehouseId) {
    return jdbc.queryForList(
        "select event_type from outbox_event where aggregate_id=? order by aggregate_version",
        String.class,
        warehouseId.toString());
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }
}
