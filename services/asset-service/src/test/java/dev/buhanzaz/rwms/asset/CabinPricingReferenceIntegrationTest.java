package dev.buhanzaz.rwms.asset;

import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.DIMENSION_24_X_6;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.FINISHING_DVP;
import static dev.buhanzaz.rwms.asset.CabinCompositionTestIds.TYPE_BK_1;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateCabinCatalogItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.CreateRentalItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.RentalItemResponse;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateCabinCatalogItemRequest;
import dev.buhanzaz.rwms.asset.api.AssetApiModels.UpdateEquipmentRequest;
import dev.buhanzaz.rwms.asset.api.CabinPricingCatalogResponse;
import dev.buhanzaz.rwms.asset.api.EquipmentPricingCatalogResponse;
import dev.buhanzaz.rwms.asset.api.PresentationHoldApiModels.CabinAvailabilityRequest;
import dev.buhanzaz.rwms.asset.domain.CabinCatalogKind;
import dev.buhanzaz.rwms.asset.domain.EquipmentCategory;
import dev.buhanzaz.rwms.asset.repository.RentalItemRepository;
import dev.buhanzaz.rwms.asset.service.AssetService;
import dev.buhanzaz.rwms.asset.service.CabinCompositionService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.IntStream;
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
import tools.jackson.databind.ObjectMapper;

/** Proves live catalog identities, complete warehouse-scoped references and service-only access. */
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
class CabinPricingReferenceIntegrationTest {
  private static final String BASE = "/api/internal/asset/v1/logistics";
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper objectMapper;
  @Autowired AssetService assets;
  @Autowired CabinCompositionService composition;
  @Autowired RentalItemRepository rentalItems;

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
  void catalogIncludesUnusedTypesAndCategoriesAndTracksRenameDisableAndDeletion() throws Exception {
    UUID actor = UUID.randomUUID();
    var type =
        composition
            .createCatalogItem(
                actor,
                UUID.randomUUID(),
                new CreateCabinCatalogItemRequest(CabinCatalogKind.TYPE, "Тарифный тип " + actor))
            .response();
    var category =
        composition
            .createCatalogItem(
                actor,
                UUID.randomUUID(),
                new CreateCabinCatalogItemRequest(
                    CabinCatalogKind.CATEGORY, "Тарифный подтип " + actor))
            .response();
    var initial = readCatalog();
    assertThat(initial.types())
        .extracting(CabinPricingCatalogResponse.Value::id)
        .contains(type.id());
    assertThat(initial.categories())
        .extracting(CabinPricingCatalogResponse.Value::id)
        .contains(category.id());
    var changed =
        composition.updateCatalogItem(
            type.id(),
            new UpdateCabinCatalogItemRequest(
                type.version(), "Переименованный тип " + actor, false, null));
    assertThat(readCatalog().types())
        .filteredOn(value -> value.id().equals(type.id()))
        .singleElement()
        .satisfies(
            value -> {
              assertThat(value.name()).isEqualTo(changed.name());
              assertThat(value.active()).isFalse();
            });
    composition.deleteCatalogItem(type.id(), changed.version());
    composition.deleteCatalogItem(category.id(), category.version());
    var after = readCatalog();
    assertThat(after.types())
        .extracting(CabinPricingCatalogResponse.Value::id)
        .doesNotContain(type.id());
    assertThat(after.categories())
        .extracting(CabinPricingCatalogResponse.Value::id)
        .doesNotContain(category.id());
  }

  @Test
  void referencesExposeOnlyActualCatalogIdentitiesAndDoNotMutateTheCabin() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse cabin = cabin(warehouseId);
    UUID categoryId = rentalItems.findById(cabin.id()).orElseThrow().getCategoryId();
    String result =
        mvc.perform(
                post(BASE + "/cabin-pricing-references")
                    .with(logisticsJwt())
                    .contentType(MediaType.APPLICATION_JSON)
                    .content(
                        objectMapper.writeValueAsString(
                            new CabinAvailabilityRequest(warehouseId, List.of(cabin.id())))))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.warehouseId").value(warehouseId.toString()))
            .andExpect(jsonPath("$.cabins.length()").value(1))
            .andExpect(jsonPath("$.cabins[0].rentalItemId").value(cabin.id().toString()))
            .andExpect(jsonPath("$.cabins[0].rentalItemVersion").value(cabin.version()))
            .andExpect(jsonPath("$.cabins[0].rentalTypeId").value(TYPE_BK_1.toString()))
            .andExpect(jsonPath("$.cabins[0].categoryId").value(categoryId.toString()))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(result).doesNotContain("private-price-test", "passport", "tenant", "price");
    assertThat(objectMapper.readTree(result).get("cabins").get(0).size()).isEqualTo(4);
    assertThat(rentalItems.findById(cabin.id()).orElseThrow().getVersion())
        .isEqualTo(cabin.version());
  }

  @Test
  void missingOrForeignWarehouseCabinRejectsTheWholeSet() throws Exception {
    UUID warehouseId = UUID.randomUUID();
    RentalItemResponse own = cabin(warehouseId);
    RentalItemResponse foreign = cabin(UUID.randomUUID());
    for (UUID missing : List.of(UUID.randomUUID(), foreign.id())) {
      mvc.perform(
              post(BASE + "/cabin-pricing-references")
                  .with(logisticsJwt())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          new CabinAvailabilityRequest(warehouseId, List.of(own.id(), missing)))))
          .andExpect(status().isNotFound())
          .andExpect(jsonPath("$.cabins").doesNotExist());
    }
  }

  @Test
  void rejectsEmptyDuplicateAndOversizedReferenceRequests() throws Exception {
    UUID id = UUID.randomUUID();
    for (List<UUID> ids :
        List.of(
            List.<UUID>of(),
            List.of(id, id),
            IntStream.range(0, 101).mapToObj(ignored -> UUID.randomUUID()).toList())) {
      mvc.perform(
              post(BASE + "/cabin-pricing-references")
                  .with(logisticsJwt())
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(
                      objectMapper.writeValueAsString(
                          new CabinAvailabilityRequest(UUID.randomUUID(), ids))))
          .andExpect(status().isBadRequest());
    }
  }

  @Test
  void equipmentPricingIncludesOnlyFurnitureAndTracksLabelsWithoutNeedingStock() throws Exception {
    String suffix = UUID.randomUUID().toString();
    var furniture =
        assets
            .createEquipment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Тарифная кровать " + suffix, EquipmentCategory.FURNITURE, null, null))
            .response();
    var electrical =
        assets
            .createEquipment(
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateEquipmentRequest(
                    "Тарифная электрика " + suffix, EquipmentCategory.ELECTRICAL, null, null))
            .response();
    assets.updateEquipment(
        furniture.id(),
        new UpdateEquipmentRequest(
            furniture.version(),
            "Переименованная кровать " + suffix,
            EquipmentCategory.FURNITURE,
            false,
            null,
            null));

    String body =
        mvc.perform(get(BASE + "/equipment-pricing-catalog").with(logisticsJwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    var catalog = objectMapper.readValue(body, EquipmentPricingCatalogResponse.class);
    assertThat(catalog.items())
        .filteredOn(value -> value.id().equals(furniture.id()))
        .containsExactly(
            new EquipmentPricingCatalogResponse.Value(
                furniture.id(), "Переименованная кровать " + suffix, false));
    assertThat(catalog.items())
        .extracting(EquipmentPricingCatalogResponse.Value::id)
        .doesNotContain(electrical.id());
    assertThat(objectMapper.readTree(body).get("items").get(0).propertyNames())
        .containsExactlyInAnyOrder("id", "name", "active");
  }

  @Test
  void pricingFactsRequireTheExactLogisticsServiceCredential() throws Exception {
    mvc.perform(get(BASE + "/cabin-pricing-catalog")).andExpect(status().isUnauthorized());
    mvc.perform(get(BASE + "/equipment-pricing-catalog")).andExpect(status().isUnauthorized());
    String body =
        objectMapper.writeValueAsString(
            new CabinAvailabilityRequest(UUID.randomUUID(), List.of(UUID.randomUUID())));
    mvc.perform(
            post(BASE + "/cabin-pricing-references")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body))
        .andExpect(status().isUnauthorized());
    for (JwtRequestPostProcessor token :
        List.of(
            jwt()
                .jwt(
                    value ->
                        value
                            .subject(UUID.randomUUID().toString())
                            .claim("principal_type", "USER")
                            .claim("scope", "asset.logistics")),
            serviceJwt("maintenance-service", "asset.logistics"),
            serviceJwt("logistics-service", "rwms.read"),
            serviceJwt("logistics-service", "asset.logistics rwms.read"))) {
      mvc.perform(get(BASE + "/cabin-pricing-catalog").with(token))
          .andExpect(status().isForbidden());
      mvc.perform(get(BASE + "/equipment-pricing-catalog").with(token))
          .andExpect(status().isForbidden());
      mvc.perform(
              post(BASE + "/cabin-pricing-references")
                  .with(token)
                  .contentType(MediaType.APPLICATION_JSON)
                  .content(body))
          .andExpect(status().isForbidden());
    }
  }

  private CabinPricingCatalogResponse readCatalog() throws Exception {
    String json =
        mvc.perform(get(BASE + "/cabin-pricing-catalog").with(logisticsJwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    return objectMapper.readValue(json, CabinPricingCatalogResponse.class);
  }

  private RentalItemResponse cabin(UUID warehouseId) {
    return assets
        .createRentalItem(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateRentalItemRequest(
                warehouseId,
                "PRICING-" + UUID.randomUUID(),
                TYPE_BK_1,
                DIMENSION_24_X_6,
                FINISHING_DVP,
                "Новая",
                List.of(),
                false,
                Map.of("private-price-test", "not-public"),
                List.of()))
        .response();
  }

  private static JwtRequestPostProcessor logisticsJwt() {
    return serviceJwt("logistics-service", "asset.logistics");
  }

  private static JwtRequestPostProcessor serviceJwt(String client, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(client)
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", client)
                    .claim("scope", scope));
  }
}
