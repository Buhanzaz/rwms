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

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateGeneralCommentRequest;
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
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
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
class MaintenanceAssetSnapshotIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired AssetService service;
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;

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
  void returnsCanonicalNumberAndLatestVersionWithoutPrivateAssetFields() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse rental = rental(warehouseId);
    String privateComment = "operator@example.test / maintenance must not receive this";
    RentalItemResponse commented =
        service.updateGeneralComment(
            rental.id(), new UpdateGeneralCommentRequest(rental.version(), privateComment));

    String response =
        mvc.perform(
                get(
                        "/api/internal/asset/v1/maintenance/rental-items/{id}/snapshot",
                        rental.id())
                    .with(
                        serviceJwt(
                            "maintenance-service", "maintenance-service", "asset.maintenance")))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.id").value(rental.id().toString()))
            .andExpect(jsonPath("$.version").value(commented.version()))
            .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
            .andExpect(jsonPath("$.number").value(rental.number()))
            .andExpect(jsonPath("$.status").value(rental.status().name()))
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(commented.version()).isGreaterThan(rental.version());
    assertThat(Set.copyOf(objectMapper.readTree(response).propertyNames()))
        .containsExactlyInAnyOrder("id", "version", "warehouseId", "number", "status");
    assertThat(response)
        .contains(rental.number())
        .doesNotContain(privateComment, "privatePassportValue", "private characteristics",
            "passport", "contents", "generalComment");
  }

  @Test
  void returnsNotFoundForAMissingRentalItem() throws Exception {
    mvc.perform(
            get(
                    "/api/internal/asset/v1/maintenance/rental-items/{id}/snapshot",
                    UUID.randomUUID())
                .with(
                    serviceJwt(
                        "maintenance-service", "maintenance-service", "asset.maintenance")))
        .andExpect(status().isNotFound())
        .andExpect(jsonPath("$.code").value("ASSET_NOT_FOUND"));
  }

  @Test
  void requiresTheExactMaintenanceServiceCredentialAndScope() throws Exception {
    UUID rentalItemId = rental(UUID.randomUUID()).id();
    String path = "/api/internal/asset/v1/maintenance/rental-items/{id}/snapshot";

    mvc.perform(get(path, rentalItemId).with(userJwt("asset.maintenance")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, rentalItemId)
                .with(serviceJwt("inventory-service", "inventory-service", "asset.maintenance")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, rentalItemId)
                .with(
                    serviceJwt(
                        "maintenance-service",
                        "maintenance-service",
                        "asset.maintenance asset.internal")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, rentalItemId)
                .with(serviceJwt("maintenance-service", "maintenance-service", "asset.internal")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path, rentalItemId)
                .with(
                    serviceJwt(
                        "maintenance-service", "maintenance-service", "asset.maintenance")))
        .andExpect(status().isOk());
  }

  private RentalItemResponse rental(UUID warehouseId) {
    return service
        .createRentalItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                warehouseId,
                "MAINT-SNAPSHOT-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                CATEGORY_NEW,
                plasticWindow(),
                true,
                Map.of("privatePassportValue", "local-only"),
                List.of("private-tag")))
        .response();
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
