package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.CATEGORY_NEW;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.plasticWindow;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.EquipmentResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.TransferEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateGeneralCommentRequest;
import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.domain.BalanceLocationKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.domain.OrderUnitReservation;
import dev.buhanzaz.rwms.asset.eventing.AssetEventStore;
import dev.buhanzaz.rwms.asset.repository.OrderUnitReservationRepository;
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
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
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
class InventoryAssetCurrentSnapshotIntegrationTest {
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService assets;
  @Autowired AssetEventStore events;
  @Autowired JdbcTemplate jdbc;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired OrderUnitReservationRepository orderReservations;
  @Autowired PlatformTransactionManager transactionManager;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @Test
  void returnsTheCurrentSafeInventoryProjectionWithTenantAndCabinContents() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    String tenant = "Арендатор А";
    String privateComment = "This local operator note must not cross the inventory boundary";
    RentalItemResponse rental =
        assets
            .createRentalItem(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateRentalItemRequest(
                    warehouseId,
                    "INV-CURRENT-" + UUID.randomUUID(),
                    TYPE_BK_1,
                    DIMENSION_24_X_6,
                    FINISHING_DVP,
                    CATEGORY_NEW,
                    plasticWindow(),
                    true,
                    Map.of("serialNumber", "SAFE-SERIAL"),
                    List.of("inventory")))
            .response();
    RentalItemResponse commented =
        assets.updateGeneralComment(
            rental.id(), new UpdateGeneralCommentRequest(rental.version(), privateComment));
    orderReservations.saveAndFlush(
        OrderUnitReservation.create(
            UUID.randomUUID(),
            rental.id(),
            warehouseId,
            UUID.randomUUID(),
            tenant,
            null,
            UUID.randomUUID(),
            "WMS_ADMIN"));
    EquipmentResponse equipment =
        assets
            .createEquipment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Инвентарный стул",
                    EquipmentCategory.FURNITURE,
                    null))
            .response();
    seedStockBalance(equipment.id(), warehouseId, 1);
    assets.transfer(
        UUID.randomUUID(),
        UUID.randomUUID(),
        new TransferEquipmentRequest(
            equipment.id(),
            warehouseId,
            null,
            BalanceLocationKind.STOCK,
            0L,
            warehouseId,
            rental.id(),
            BalanceLocationKind.CABIN_NON_RENTED,
            0L,
            1L));

    String response =
        mvc.perform(
                get("/api/internal/asset/v1/inventory/assets/{assetId}", rental.id())
                    .with(inventoryJwt("inventory-service", "inventory-service", "asset.inventory")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.assetId").value(rental.id().toString()))
            .andExpect(jsonPath("$.version").value(commented.version()))
            .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
            .andExpect(jsonPath("$.status").value(commented.status().name()))
            .andExpect(jsonPath("$.displayCanonicalNumber").value(rental.number()))
            .andExpect(jsonPath("$.identityMatchKey").isNotEmpty())
            .andExpect(jsonPath("$.tenantSnapshot").value(tenant))
            .andExpect(jsonPath("$.passportSnapshot").isMap())
            .andExpect(jsonPath("$.passportSnapshot.passport.serialNumber").value("SAFE-SERIAL"))
            .andExpect(jsonPath("$.contentsSnapshot").isArray())
            .andExpect(jsonPath("$.contentsSnapshot[0].equipmentId").value(equipment.id().toString()))
            .andExpect(jsonPath("$.contentsSnapshot[0].equipmentName").value(equipment.name()))
            .andExpect(jsonPath("$.contentsSnapshot[0].quantity").value(1))
            .andExpect(
                jsonPath("$.contentsSnapshot[0].locationKind").value("CABIN_NON_RENTED"))
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode projection = objectMapper.readTree(response);
    assertThat(Set.copyOf(projection.propertyNames()))
        .containsExactlyInAnyOrder(
            "assetId",
            "version",
            "warehouseId",
            "status",
            "displayCanonicalNumber",
            "identityMatchKey",
            "tenantSnapshot",
            "passportSnapshot",
            "contentsSnapshot");
    assertThat(projection.path("passportSnapshot").isObject()).isTrue();
    assertThat(projection.path("contentsSnapshot").isArray()).isTrue();
    assertThat(response).doesNotContain(privateComment, "generalComment", "manualNotes");
  }

  @Test
  void returnsNotFoundForAMissingAsset() throws Exception {
    mvc.perform(
            get("/api/internal/asset/v1/inventory/assets/{assetId}", UUID.randomUUID())
                .with(inventoryJwt("inventory-service", "inventory-service", "asset.inventory")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
  }

  @Test
  void requiresTheExactInventoryServiceCredentialAndScope() throws Exception {
    UUID assetId = UUID.randomUUID();
    String path = "/api/internal/asset/v1/inventory/assets/{assetId}";

    mvc.perform(get(path, assetId).with(userJwt("asset.inventory")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, assetId)
                .with(inventoryJwt("inventory-service", "maintenance-service", "asset.inventory")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, assetId)
                .with(
                    inventoryJwt(
                        "inventory-service",
                        "inventory-service",
                        "asset.inventory asset.internal")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, assetId)
                .with(inventoryJwt("inventory-service", "inventory-service", "asset.internal")))
        .andExpect(status().isForbidden());
  }

  private void seedStockBalance(UUID equipmentId, UUID warehouseId, long quantity) {
    UUID balanceId = UUID.randomUUID();
    new TransactionTemplate(transactionManager)
        .executeWithoutResult(
            ignored -> {
              jdbc.update(
                  """
                  insert into equipment_balance(
                    id,version,equipment_id,warehouse_id,rental_item_id,location_kind,quantity,
                    created_at,updated_at)
                  values (?,0,?,?,null,'STOCK',?,clock_timestamp(),clock_timestamp())
                  """,
                  balanceId,
                  equipmentId,
                  warehouseId,
                  quantity);
              events.initialize(
                  AssetAggregateType.EQUIPMENT_BALANCE,
                  balanceId,
                  0,
                  AssetEventType.EQUIPMENT_BALANCE_CHANGED,
                  Map.of(
                      "balanceId", balanceId.toString(),
                      "equipmentId", equipmentId.toString(),
                      "warehouseId", warehouseId.toString(),
                      "locationKind", BalanceLocationKind.STOCK.name(),
                      "quantity", quantity),
                  Map.of(
                      "balanceId", balanceId.toString(),
                      "version", 0,
                      "locationKind", BalanceLocationKind.STOCK.name(),
                      "quantity", quantity));
            });
  }

  private static JwtRequestPostProcessor inventoryJwt(
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
