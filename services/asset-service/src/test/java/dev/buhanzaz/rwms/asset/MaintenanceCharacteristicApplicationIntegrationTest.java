package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetService;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
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
class MaintenanceCharacteristicApplicationIntegrationTest {
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
  void listsOnlyActiveCanonicalCharacteristicValuesThroughTheExactCredential()
      throws Exception {
    UUID inactiveId = UUID.randomUUID();
    jdbc.update(
        """
        insert into cabin_catalog_item(
          id,version,kind,name,name_normalized,active,sort_order,created_at,updated_at)
        values (?,0,'CHARACTERISTIC','Неактивная тестовая характеристика',
          'неактивная тестовая характеристика',false,10000,clock_timestamp(),clock_timestamp())
        """,
        inactiveId);

    MvcResult result =
        mvc.perform(
                get("/api/internal/asset/v1/maintenance/cabin-characteristics")
                    .with(maintenanceJwt()))
            .andExpect(status().isOk())
            .andReturn();
    JsonNode response =
        objectMapper.readTree(result.getResponse().getContentAsString());

    int expected =
        jdbc.queryForObject(
            """
            select count(*) from cabin_catalog_item
            where kind='CHARACTERISTIC' and active
            """,
            Integer.class);
    assertThat(response.size()).isEqualTo(expected);
    response.forEach(
        value ->
            assertThat(Set.copyOf(value.propertyNames()))
                .containsExactlyInAnyOrder("id", "name"));
    assertThat(response.toString()).doesNotContain(inactiveId.toString());

    mvc.perform(
            get("/api/internal/asset/v1/maintenance/cabin-characteristics")
                .with(
                    serviceJwt(
                        "inventory-service",
                        "inventory-service",
                        "asset.maintenance")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get("/api/internal/asset/v1/maintenance/cabin-characteristics")
                .with(
                    serviceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        "asset.maintenance asset.internal")))
        .andExpect(status().isForbidden());
  }

  @Test
  void appliesOncePreservesExistingValuesBumpsRevisionAndReplaysWithoutDuplicates()
      throws Exception {
    RentalItemResponse rental = rental();
    UUID existingCharacteristicId = activeCharacteristicId(0);
    UUID appliedCharacteristicId = activeCharacteristicId(1);
    service.updatePassport(
        rental.id(),
        new dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdatePassportRequest(
            rental.version(),
            TYPE_BK_1,
            DIMENSION_24_X_6,
            FINISHING_DVP,
            null,
            List.of(existingCharacteristicId),
            false,
            Map.of(),
            List.of()));
    RentalItemResponse before = service.rentalItem(rental.id());
    UUID idempotencyKey = UUID.randomUUID();

    JsonNode applied =
        apply(
            rental.id(),
            appliedCharacteristicId,
            idempotencyKey,
            status().isOk(),
            false);
    assertThat(applied.get("rentalItemId").asText())
        .isEqualTo(rental.id().toString());
    assertThat(applied.get("characteristicId").asText())
        .isEqualTo(appliedCharacteristicId.toString());
    assertThat(applied.get("added").asBoolean()).isTrue();
    assertThat(applied.get("rentalItemVersion").asLong())
        .isEqualTo(before.version() + 1);

    JsonNode replay =
        apply(
            rental.id(),
            appliedCharacteristicId,
            idempotencyKey,
            status().isOk(),
            true);
    assertThat(replay).isEqualTo(applied);

    JsonNode semanticReplay =
        apply(
            rental.id(),
            appliedCharacteristicId,
            UUID.randomUUID(),
            status().isOk(),
            false);
    assertThat(semanticReplay.get("added").asBoolean()).isFalse();
    assertThat(semanticReplay.get("rentalItemVersion").asLong())
        .isEqualTo(applied.get("rentalItemVersion").asLong());
    assertThat(
            jdbc.queryForList(
                """
                select characteristic_id from rental_item_characteristic
                where rental_item_id=? order by sort_order,id
                """,
                UUID.class,
                rental.id()))
        .containsExactly(existingCharacteristicId, appliedCharacteristicId);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from domain_event
                where aggregate_type='RENTAL_ITEM' and aggregate_id=?
                  and event_type=?
                """,
                Integer.class,
                rental.id().toString(),
                AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED.value()))
        .isEqualTo(2);
    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from outbox_event
                where aggregate_type='RENTAL_ITEM' and aggregate_id=?
                  and event_type=?
                """,
                Integer.class,
                rental.id().toString(),
                AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED.value()))
        .isEqualTo(2);
    String latestSnapshot =
        jdbc.queryForObject(
            """
            select state::text from aggregate_snapshot
            where aggregate_type='RENTAL_ITEM' and aggregate_id=?
            order by aggregate_version desc limit 1
            """,
            String.class,
            rental.id().toString());
    assertThat(latestSnapshot)
        .contains(
            existingCharacteristicId.toString(),
            appliedCharacteristicId.toString());
  }

  @Test
  void rejectsMissingRentalInactiveOrWrongKindAndNonMaintenanceCredentials()
      throws Exception {
    RentalItemResponse rental = rental();
    UUID activeCharacteristicId = activeCharacteristicId(0);
    UUID inactiveId = UUID.randomUUID();
    jdbc.update(
        """
        insert into cabin_catalog_item(
          id,version,kind,name,name_normalized,active,sort_order,created_at,updated_at)
        values (?,0,'CHARACTERISTIC','Неактивная применяемая характеристика',
          'неактивная применяемая характеристика',false,10001,clock_timestamp(),clock_timestamp())
        """,
        inactiveId);

    apply(
        UUID.randomUUID(),
        activeCharacteristicId,
        UUID.randomUUID(),
        status().isNotFound(),
        false);
    apply(
        rental.id(),
        inactiveId,
        UUID.randomUUID(),
        status().isConflict(),
        false);
    apply(
        rental.id(),
        TYPE_BK_1,
        UUID.randomUUID(),
        status().isBadRequest(),
        false);
    mvc.perform(
            put(
                    "/api/internal/asset/v1/maintenance/rental-items/{rentalItemId}/characteristics/{characteristicId}",
                    rental.id(),
                    activeCharacteristicId)
                .header("Idempotency-Key", UUID.randomUUID())
                .with(
                    serviceJwt(
                        "inventory-service",
                        "inventory-service",
                        "asset.maintenance")))
        .andExpect(status().isForbidden());

    assertThat(
            jdbc.queryForObject(
                """
                select count(*) from rental_item_characteristic
                where rental_item_id=?
                """,
                Integer.class,
                rental.id()))
        .isZero();
    assertThat(service.rentalItem(rental.id()).version()).isEqualTo(rental.version());
  }

  private JsonNode apply(
      UUID rentalItemId,
      UUID characteristicId,
      UUID idempotencyKey,
      org.springframework.test.web.servlet.ResultMatcher expectedStatus,
      boolean replayed)
      throws Exception {
    var result =
        mvc.perform(
                put(
                        "/api/internal/asset/v1/maintenance/rental-items/{rentalItemId}/characteristics/{characteristicId}",
                        rentalItemId,
                        characteristicId)
                    .header("Idempotency-Key", idempotencyKey)
                    .with(maintenanceJwt()))
            .andExpect(expectedStatus);
    if (replayed) {
      result.andExpect(header().string("Idempotency-Replayed", "true"));
    }
    return objectMapper.readTree(
        result.andReturn().getResponse().getContentAsString());
  }

  private RentalItemResponse rental() {
    return service
        .createRentalItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                UUID.randomUUID(),
                "MAINT-CHAR-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                null,
                List.of(),
                false,
                Map.of(),
                List.of()))
        .response();
  }

  private UUID activeCharacteristicId(int offset) {
    return jdbc.queryForObject(
        """
        select id from cabin_catalog_item
        where kind='CHARACTERISTIC' and active
        order by name,id offset ? limit 1
        """,
        UUID.class,
        offset);
  }

  private static JwtRequestPostProcessor maintenanceJwt() {
    return serviceJwt(
        "maintenance-service",
        "maintenance-service",
        "asset.maintenance");
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
}
