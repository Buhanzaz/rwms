package dev.buhanzaz.rwms.asset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

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
class OldPanelStockPhotoCleanupApiIntegrationTest {
  private static final UUID SPB_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK_WAREHOUSE_ID =
      UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final UUID FIRST_CABIN_ID =
      UUID.fromString("51000000-0000-4000-8000-000000000001");
  private static final UUID SECOND_CABIN_ID =
      UUID.fromString("51000000-0000-4000-8000-000000000002");
  private static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired MockMvc mvc;

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
  void rentalItemApiKeepsCanonicalNumbersAndReturnsNoOldPanelStockPhotoMetadata()
      throws Exception {
    mvc.perform(
            get("/api/asset/v1/rental-items/{id}", FIRST_CABIN_ID).with(readJwt()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.id").value(FIRST_CABIN_ID.toString()))
        .andExpect(jsonPath("$.number").value("БЫТ-001"))
        .andExpect(jsonPath("$.passport.source").doesNotExist())
        .andExpect(jsonPath("$.passport.legacyId").doesNotExist())
        .andExpect(jsonPath("$.passport.legacyWarehouseId").doesNotExist())
        .andExpect(jsonPath("$.passport.legacyNumber").doesNotExist())
        .andExpect(jsonPath("$.passport.locationNodeId").doesNotExist())
        .andExpect(jsonPath("$.passport.hasPhotos").doesNotExist())
        .andExpect(jsonPath("$.passport.photoCount").doesNotExist())
        .andExpect(jsonPath("$.passport.legacyPhotos").doesNotExist())
        .andExpect(jsonPath("$.passport.previewPhotoUrls").doesNotExist())
        .andExpect(jsonPath("$.passport.mainPhotoUrl").doesNotExist());

    String second =
        mvc.perform(get("/api/asset/v1/rental-items/{id}", SECOND_CABIN_ID).with(readJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.number").value("БЫТ-002"))
            .andExpect(jsonPath("$.passport.legacyId").doesNotExist())
            .andExpect(jsonPath("$.passport.legacyWarehouseId").doesNotExist())
            .andExpect(jsonPath("$.passport.legacyNumber").doesNotExist())
            .andExpect(jsonPath("$.passport.locationNodeId").doesNotExist())
            .andExpect(jsonPath("$.passport.hasPhotos").doesNotExist())
            .andExpect(jsonPath("$.passport.photoCount").doesNotExist())
            .andExpect(jsonPath("$.passport.legacyPhotos").doesNotExist())
            .andExpect(jsonPath("$.passport.previewPhotoUrls").doesNotExist())
            .andExpect(jsonPath("$.passport.mainPhotoUrl").doesNotExist())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(second).doesNotContain("images.unsplash.com");

    String warehousePage =
        mvc.perform(
                get("/api/asset/v1/rental-items")
                    .queryParam("warehouseId", SPB_WAREHOUSE_ID.toString())
                    .queryParam("size", "200")
                    .with(readJwt()))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.totalElements").value(120))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(warehousePage)
        .doesNotContain(
            "images.unsplash.com",
            "old-panel-rental-items-v1",
            "legacy",
            "locationNodeId",
            "hasPhotos",
            "photoCount",
            "previewPhotoUrls",
            "mainPhotoUrl");
  }

  @Test
  void movingCabinToWarehouseWithSameLocalNumberReturnsConflict() throws Exception {
    mvc.perform(
            put("/api/asset/v1/rental-items/{id}/warehouse", SECOND_CABIN_ID)
                .contentType(MediaType.APPLICATION_JSON)
                .content("""
                    {"expectedVersion":1,"warehouseId":"%s"}
                    """.formatted(MSK_WAREHOUSE_ID))
                .with(writeJwt()))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("ASSET_CONFLICT"));
  }

  private static JwtRequestPostProcessor readJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.read")
                    .claim("global_role", "SYSTEM_ADMIN"));
  }

  private static JwtRequestPostProcessor writeJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.write")
                    .claim("global_role", "SYSTEM_ADMIN"));
  }
}
