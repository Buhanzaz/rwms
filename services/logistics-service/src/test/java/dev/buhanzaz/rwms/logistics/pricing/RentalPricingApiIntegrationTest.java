package dev.buhanzaz.rwms.logistics.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyException;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinPricingCatalog;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinPricingCatalogValue;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinPricingReference;
import dev.buhanzaz.rwms.logistics.integration.LogisticsDependencyGateway.CabinPricingReferences;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.outbox-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@ActiveProfiles("test")
@Testcontainers
@AutoConfigureMockMvc
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class RentalPricingApiIntegrationTest {
  private static final String SETTINGS = "/api/logistics/v1/settings/rental-prices";
  private static final String PRICES = "/api/logistics/v1/cabins/rental-prices";
  private static final UUID ACTOR = UUID.randomUUID();
  private static final UUID WAREHOUSE = UUID.randomUUID();
  private static final UUID TYPE = UUID.randomUUID();
  private static final UUID UNUSED_TYPE = UUID.randomUUID();
  private static final UUID CATEGORY = UUID.randomUUID();
  private static final UUID OTHER_CATEGORY = UUID.randomUUID();
  private static final UUID CABIN = UUID.randomUUID();
  private static final UUID SECOND_CABIN = UUID.randomUUID();
  private static final UUID ZERO_CABIN = UUID.randomUUID();

  @Container @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired dev.buhanzaz.rwms.logistics.pricing.service.RentalPricingService pricing;
  @Autowired org.springframework.transaction.PlatformTransactionManager transactionManager;
  @MockitoBean LogisticsDependencyGateway dependencies;
  private CabinPricingCatalog currentCatalog;

  @BeforeEach
  void resetPrices() {
    jdbc.update("delete from rental_pricing_rate");
    jdbc.update(
        "update rental_pricing_settings set version=0, updated_by_subject_id=null,"
            + " updated_at=current_timestamp");
    reset(dependencies);
    currentCatalog =
        new CabinPricingCatalog(
            List.of(
                new CabinPricingCatalogValue(TYPE, "БК-1", true),
                new CabinPricingCatalogValue(UNUSED_TYPE, "БК-2", false)),
            List.of(
                new CabinPricingCatalogValue(CATEGORY, "Обычная", true),
                new CabinPricingCatalogValue(OTHER_CATEGORY, "ИТР", false)));
    when(dependencies.readCabinPricingCatalog())
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return currentCatalog;
            });
  }

  @Test
  void returnsEveryRealTypeAndCategoryIncludingUnusedAndInactiveWithZeroDefaults()
      throws Exception {
    mvc.perform(get(SETTINGS).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(0))
        .andExpect(jsonPath("$.types.length()").value(2))
        .andExpect(jsonPath("$.types[0].rentalTypeId").value(TYPE.toString()))
        .andExpect(jsonPath("$.types[0].categories.length()").value(2))
        .andExpect(jsonPath("$.types[0].categories[0].monthlyPriceRubles").value("0"))
        .andExpect(jsonPath("$.types[1].active").value(false))
        .andExpect(jsonPath("$.types[1].categories[1].active").value(false))
        .andExpect(jsonPath("$.types[1].categories[1].monthlyPriceRubles").value("0"));
    assertThat(jdbc.queryForObject("select count(*) from rental_pricing_rate", Long.class))
        .isZero();
    assertThat(jdbc.queryForObject("select version from rental_pricing_settings", Long.class))
        .isZero();
  }

  @Test
  void adminCanSetExactPriceAndResetItToZeroWithoutChangingOtherPairs() throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "9223372036854775807")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(
            jsonPath("$.types[0].categories[0].monthlyPriceRubles").value("9223372036854775807"));
    mvc.perform(
            put(path(TYPE, OTHER_CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(1, "10000")))
        .andExpect(status().isOk());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(2, "0")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(3))
        .andExpect(jsonPath("$.types[0].categories[0].monthlyPriceRubles").value("0"))
        .andExpect(jsonPath("$.types[0].categories[1].monthlyPriceRubles").value("10000"));
  }

  @Test
  void liveRenamePreservesTheRateButRemovalAndNewIdentityNeverResurrectIt() throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isOk());
    currentCatalog =
        new CabinPricingCatalog(
            List.of(new CabinPricingCatalogValue(TYPE, "Новое имя типа", true)),
            List.of(new CabinPricingCatalogValue(CATEGORY, "Новое имя категории", true)));
    mvc.perform(get(SETTINGS).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.types.length()").value(1))
        .andExpect(jsonPath("$.types[0].name").value("Новое имя типа"))
        .andExpect(jsonPath("$.types[0].categories[0].name").value("Новое имя категории"))
        .andExpect(jsonPath("$.types[0].categories[0].monthlyPriceRubles").value("8000"));
    currentCatalog = new CabinPricingCatalog(currentCatalog.types(), List.of());
    mvc.perform(get(SETTINGS).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.types[0].categories").isEmpty());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(1, "9000")))
        .andExpect(status().isNotFound());
    currentCatalog =
        new CabinPricingCatalog(
            currentCatalog.types(),
            List.of(new CabinPricingCatalogValue(UUID.randomUUID(), "Обычная", true)));
    mvc.perform(get(SETTINGS).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.types[0].categories[0].monthlyPriceRubles").value("0"));
    currentCatalog = new CabinPricingCatalog(List.of(), currentCatalog.categories());
    mvc.perform(get(SETTINGS).with(admin()))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.types").isEmpty());
    assertThat(jdbc.queryForObject("select version from rental_pricing_settings", Long.class))
        .isEqualTo(1);
  }

  @Test
  void staleVersionCannotOverwriteEvenADifferentRow() throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isOk());
    mvc.perform(
            put(path(TYPE, OTHER_CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "12000")))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("RENTAL_PRICING_VERSION_CONFLICT"));
  }

  @Test
  void dedicatedAdministrationClientCanEditButRentalManagerCanOnlyRead() throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(actor("WMS_ADMIN", "admin.manage", "rwms-admin-web", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isOk());
    mvc.perform(get(SETTINGS).with(manager())).andExpect(status().isOk());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(1, "9000")))
        .andExpect(status().isForbidden());
  }

  @Test
  void rejectsMissingAuthenticationScopeRentalAccessAndWrongClient() throws Exception {
    mvc.perform(get(SETTINGS)).andExpect(status().isUnauthorized());
    mvc.perform(get(SETTINGS).with(actor("SYSTEM_ADMIN", "rwms.read", "rwms-web", false)))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(actor("SYSTEM_ADMIN", "rwms.read", "rwms-web", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(SETTINGS).with(actor("CUSTOMER", "customer.rental", "rwms-customer-android", true)))
        .andExpect(status().isForbidden());
    mvc.perform(get(SETTINGS).with(actor("RENTAL_MANAGER", "rental.manage", "rwms-web", true)))
        .andExpect(status().isForbidden());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(actor("WMS_ADMIN", "admin.manage", "rwms-web", true))
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isForbidden());
  }

  @ParameterizedTest
  @ValueSource(
      strings = {"-1", "1.25", "01", "", "9223372036854775808", "100000000000000000000", "1e3"})
  void rejectsInvalidOrOverflowingAmounts(String price) throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, price)))
        .andExpect(status().isBadRequest());
    assertThat(jdbc.queryForObject("select version from rental_pricing_settings", Long.class))
        .isZero();
  }

  @Test
  void rejectsMissingRequestFieldsAndUnknownClassification() throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"expectedVersion\":0}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"monthlyPriceRubles\":\"8000\"}"))
        .andExpect(status().isBadRequest());
    mvc.perform(
            put(path(UUID.randomUUID(), CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isNotFound());
  }

  @Test
  void pricesEveryCabinFromOneRevisionAndPreservesRequestOrder() throws Exception {
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isOk());
    List<UUID> ids = List.of(ZERO_CABIN, SECOND_CABIN, CABIN);
    when(dependencies.readCabinPricingReferences(WAREHOUSE, ids))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new CabinPricingReferences(
                  WAREHOUSE,
                  List.of(
                      new CabinPricingReference(CABIN, 3, TYPE, CATEGORY),
                      new CabinPricingReference(SECOND_CABIN, 8, TYPE, CATEGORY),
                      new CabinPricingReference(ZERO_CABIN, 2, UNUSED_TYPE, CATEGORY)));
            });
    mvc.perform(
            post(PRICES)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cabins(WAREHOUSE, ids)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.pricingVersion").value(1))
        .andExpect(jsonPath("$.cabins[0].rentalItemId").value(ZERO_CABIN.toString()))
        .andExpect(jsonPath("$.cabins[0].monthlyPriceRubles").value("0"))
        .andExpect(jsonPath("$.cabins[1].monthlyPriceRubles").value("8000"))
        .andExpect(jsonPath("$.cabins[2].monthlyPriceRubles").value("8000"))
        .andExpect(jsonPath("$.cabins[1].rentalItemVersion").value(8));
  }

  @Test
  void priceReadRejectsForeignWarehouseDuplicatesAndIncompleteDependencyFacts() throws Exception {
    mvc.perform(
            post(PRICES)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cabins(UUID.randomUUID(), List.of(CABIN))))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PRICES)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cabins(WAREHOUSE, List.of(CABIN, CABIN))))
        .andExpect(status().isBadRequest());
    when(dependencies.readCabinPricingReferences(WAREHOUSE, List.of(CABIN)))
        .thenReturn(new CabinPricingReferences(WAREHOUSE, List.of()));
    mvc.perform(
            post(PRICES)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cabins(WAREHOUSE, List.of(CABIN))))
        .andExpect(status().isServiceUnavailable())
        .andExpect(jsonPath("$.code").value("RENTAL_PRICING_UNAVAILABLE"));
  }

  @Test
  void priceQuoteSuspendsCallerTransactionForRemoteFactsAndRestoresItAfterSnapshotRead() {
    when(dependencies.readCabinPricingReferences(WAREHOUSE, List.of(CABIN)))
        .thenAnswer(
            ignored -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isFalse();
              return new CabinPricingReferences(
                  WAREHOUSE, List.of(new CabinPricingReference(CABIN, 3, TYPE, CATEGORY)));
            });
    new org.springframework.transaction.support.TransactionTemplate(transactionManager)
        .executeWithoutResult(
            status -> {
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
              assertThat(
                      pricing
                          .prices(WAREHOUSE, List.of(CABIN))
                          .cabins()
                          .getFirst()
                          .monthlyPriceRubles())
                  .isZero();
              assertThat(TransactionSynchronizationManager.isActualTransactionActive()).isTrue();
            });
  }

  @Test
  void missingCabinIsNotFoundAndDependencyFailureNeverInventsAFreeTariff() throws Exception {
    when(dependencies.readCabinPricingReferences(WAREHOUSE, List.of(CABIN)))
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.PERMANENT_REJECTION,
                "ASSET_NOT_FOUND",
                "missing",
                null));
    mvc.perform(
            post(PRICES)
                .with(manager())
                .contentType(MediaType.APPLICATION_JSON)
                .content(cabins(WAREHOUSE, List.of(CABIN))))
        .andExpect(status().isNotFound());
    when(dependencies.readCabinPricingCatalog())
        .thenThrow(
            new LogisticsDependencyException(
                LogisticsDependencyException.FailureKind.TRANSIENT, "unavailable"));
    mvc.perform(get(SETTINGS).with(admin())).andExpect(status().isServiceUnavailable());
    mvc.perform(
            put(path(TYPE, CATEGORY))
                .with(admin())
                .contentType(MediaType.APPLICATION_JSON)
                .content(body(0, "8000")))
        .andExpect(status().isServiceUnavailable());
    assertThat(jdbc.queryForObject("select version from rental_pricing_settings", Long.class))
        .isZero();
  }

  private String body(long version, String price) {
    return json.writeValueAsString(Map.of("expectedVersion", version, "monthlyPriceRubles", price));
  }

  private String cabins(UUID warehouseId, List<UUID> ids) {
    return json.writeValueAsString(Map.of("warehouseId", warehouseId, "rentalItemIds", ids));
  }

  private String path(UUID type, UUID category) {
    return SETTINGS + "/" + type + "/" + category;
  }

  private JwtRequestPostProcessor admin() {
    return actor("SYSTEM_ADMIN", "rwms.read rwms.write", "rwms-web", true);
  }

  private JwtRequestPostProcessor manager() {
    return actor("RENTAL_MANAGER", "rental.manage", "rwms-rental-manager-web", true);
  }

  private JwtRequestPostProcessor actor(
      String role, String scope, String client, boolean rentalAccess) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(ACTOR.toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", role)
                    .claim("scope", scope)
                    .claim("client_id", client)
                    .claim("rentalAccess", rentalAccess)
                    .claim(
                        "warehouse_access",
                        List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", "VIEW"))));
  }
}
