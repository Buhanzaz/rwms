package dev.buhanzaz.rwms.taskboard;

import static dev.buhanzaz.rwms.taskboard.api.ContractorCompanyApiModels.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import dev.buhanzaz.rwms.taskboard.service.ContractorCompanyService;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Exercises the city-owned contact catalog, driver membership and public version/auth fences. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class ContractorCompanyIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID CITY = UUID.fromString("00000000-0000-0000-0000-000000000851");
  private static final UUID OTHER_CITY = UUID.fromString("00000000-0000-0000-0000-000000000852");
  private static final String INN = "7801000001";
  @Autowired MockMvc mvc;
  @Autowired ObjectMapper json;
  @Autowired JdbcTemplate jdbc;
  @Autowired ContractorCompanyService companies;

  @BeforeEach
  void reset() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void createsNormalizedContactsAndReplaysOnlyTheIdenticalStableIdentity() throws Exception {
    UUID id = UUID.randomUUID();
    var request = company(id, INN);
    body(post(path(CITY)), request)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.companyId").value(id.toString()))
        .andExpect(jsonPath("$.name").value("Балтика"))
        .andExpect(jsonPath("$.contactName").value("Иван"))
        .andExpect(jsonPath("$.homeWarehouseId").value(CITY.toString()));
    body(post(path(CITY)), request)
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.version").value(0));
    body(post(path(CITY)), company(id, "7801000002")).andExpect(status().isConflict());
    body(post(path(OTHER_CITY)), request).andExpect(status().isConflict());
    assertThat(jdbc.queryForObject("select count(*) from contractor_company", Integer.class))
        .isOne();
  }

  @Test
  void innIsUniqueWithinOneCityAndIndependentBetweenCities() throws Exception {
    body(post(path(CITY)), company(UUID.randomUUID(), INN)).andExpect(status().isCreated());
    body(post(path(CITY)), company(UUID.randomUUID(), INN)).andExpect(status().isConflict());
    body(post(path(OTHER_CITY)), company(UUID.randomUUID(), INN)).andExpect(status().isCreated());
    mvc.perform(get(path(CITY)).with(user(CITY, "VIEW", "rwms.read")))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(1));
  }

  @Test
  void contactEditsAndEmptyDeletionRequireTheObservedVersion() throws Exception {
    var created = companies.create(CITY, company(UUID.randomUUID(), INN));
    var edited =
        response(
            body(
                    patch(path(CITY) + "/" + created.companyId()),
                    new UpdateContractorCompanyRequest(
                        created.version(),
                        "Север",
                        INN,
                        null,
                        "+7 900 222-22-22",
                        null,
                        null,
                        null))
                .andExpect(status().isOk()));
    long version = edited.required("version").longValue();
    assertThat(version).isGreaterThan(created.version());
    assertThat(edited.required("email").isNull()).isTrue();
    body(
            patch(path(CITY) + "/" + created.companyId()),
            new UpdateContractorCompanyRequest(
                created.version(), "Устаревшее имя", INN, null, "123", null, null, null))
        .andExpect(status().isConflict());
    mvc.perform(
            delete(path(CITY) + "/" + created.companyId())
                .with(user(CITY, "EDIT", "rwms.write"))
                .queryParam("expectedVersion", "0"))
        .andExpect(status().isConflict());
    mvc.perform(
            delete(path(CITY) + "/" + created.companyId())
                .with(user(CITY, "EDIT", "rwms.write"))
                .queryParam("expectedVersion", Long.toString(version)))
        .andExpect(status().isNoContent());
    assertThat(companies.list(CITY)).isEmpty();
  }

  @Test
  void duplicateInnUpdateDoesNotPartiallyOverwriteContactDetails() throws Exception {
    var first = companies.create(CITY, company(UUID.randomUUID(), INN));
    companies.create(CITY, company(UUID.randomUUID(), "7801000002"));
    body(
            patch(path(CITY) + "/" + first.companyId()),
            new UpdateContractorCompanyRequest(
                first.version(), "Не сохранять", "7801000002", null, "123", null, null, null))
        .andExpect(status().isConflict());
    assertThat(
            jdbc.queryForObject(
                "select name from contractor_company where id=?", String.class, first.companyId()))
        .isEqualTo("Балтика");
  }

  @Test
  void driversBelongToTheirHomeCityCompanyAndPreventCompanyDeletion() throws Exception {
    var company = companies.create(CITY, company(UUID.randomUUID(), INN));
    UUID driverId = UUID.randomUUID();
    var created =
        response(
            body(post(drivers(CITY)), driver(driverId, company.companyId()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.companyId").value(company.companyId().toString())));
    long version = created.required("version").longValue();
    mvc.perform(
            delete(path(CITY) + "/" + company.companyId())
                .with(user(CITY, "EDIT", "rwms.write"))
                .queryParam("expectedVersion", "0"))
        .andExpect(status().isConflict());
    body(patch(drivers(CITY) + "/" + driverId), driverUpdate(version, null))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.companyId").isEmpty());
    body(patch(drivers(CITY) + "/" + driverId), driverUpdate(version, company.companyId()))
        .andExpect(status().isConflict());
    mvc.perform(
            delete(path(CITY) + "/" + company.companyId())
                .with(user(CITY, "EDIT", "rwms.write"))
                .queryParam("expectedVersion", "0"))
        .andExpect(status().isNoContent());
    assertThat(
            jdbc.queryForObject("select count(*) from worker where id=?", Integer.class, driverId))
        .isOne();
    assertThat(
            jdbc.queryForObject("select warehouse_id from worker where id=?", UUID.class, driverId))
        .isEqualTo(CITY);
  }

  @Test
  void foreignCityMembershipIsRejectedByBothApiAndDatabase() throws Exception {
    var foreign = companies.create(OTHER_CITY, company(UUID.randomUUID(), INN));
    UUID driverId = UUID.randomUUID();
    body(post(drivers(CITY)), driver(UUID.randomUUID(), foreign.companyId()))
        .andExpect(status().isNotFound());
    var created =
        response(body(post(drivers(CITY)), driver(driverId, null)).andExpect(status().isCreated()));
    body(
            patch(drivers(CITY) + "/" + driverId),
            driverUpdate(created.required("version").longValue(), foreign.companyId()))
        .andExpect(status().isNotFound());
    assertThat(
            jdbc.queryForObject(
                "select contractor_company_id from worker where id=?", UUID.class, driverId))
        .isNull();
    assertThat(
            jdbc.queryForObject(
                "select display_name from worker where id=?", String.class, driverId))
        .isEqualTo("Петров");
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update worker set contractor_company_id=? where id=?",
                    foreign.companyId(),
                    driverId))
        .isInstanceOf(DataIntegrityViolationException.class);
  }

  @Test
  void onlyAuthorizedCityOperatorsCanReadAndEditTheCatalog() throws Exception {
    var request = company(UUID.randomUUID(), INN);
    mvc.perform(get(path(CITY))).andExpect(status().isUnauthorized());
    mvc.perform(get(path(CITY)).with(user(OTHER_CITY, "VIEW", "rwms.read")))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path(CITY))
                .with(user(CITY, "VIEW", "rwms.read rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(path(CITY))
                .with(user(CITY, "EDIT", "rwms.read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(request)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(path(CITY))
                .with(
                    jwt()
                        .jwt(
                            token ->
                                token
                                    .claim("principal_type", "SERVICE")
                                    .claim("scope", "rwms.read"))))
        .andExpect(status().isForbidden());
    assertThat(companies.list(CITY)).isEmpty();
  }

  @Test
  void invalidContactsAndMissingMembershipDecisionAreRejected() throws Exception {
    body(post(path(CITY)), company(UUID.randomUUID(), "12345")).andExpect(status().isBadRequest());
    body(
            post(path(CITY)),
            new CreateContractorCompanyRequest(
                UUID.randomUUID(), "Компания", INN, null, "123", "invalid", null, null))
        .andExpect(status().isBadRequest());
    var company = companies.create(CITY, company(UUID.randomUUID(), INN));
    body(
            patch(path(CITY) + "/" + company.companyId()),
            new UpdateContractorCompanyRequest(
                null, "Компания", INN, null, "123", null, null, null))
        .andExpect(status().isBadRequest());
    UUID driverId = UUID.randomUUID();
    body(post(drivers(CITY)), driver(driverId, company.companyId()))
        .andExpect(status().isCreated());
    var missing = driverUpdate(0, null);
    missing.remove("companyId");
    body(patch(drivers(CITY) + "/" + driverId), missing).andExpect(status().isBadRequest());
    assertThat(
            jdbc.queryForObject(
                "select contractor_company_id from worker where id=?", UUID.class, driverId))
        .isEqualTo(company.companyId());
  }

  @Test
  void simultaneousIdenticalCreatesProduceOneCompany() throws Exception {
    var request = company(UUID.randomUUID(), INN);
    var start = new CountDownLatch(1);
    try (var executor = Executors.newFixedThreadPool(2)) {
      var first =
          executor.submit(
              () -> {
                start.await();
                return companies.create(CITY, request);
              });
      var second =
          executor.submit(
              () -> {
                start.await();
                return companies.create(CITY, request);
              });
      start.countDown();
      assertThat(first.get(10, TimeUnit.SECONDS).companyId()).isEqualTo(request.companyId());
      assertThat(second.get(10, TimeUnit.SECONDS).companyId()).isEqualTo(request.companyId());
    }
    assertThat(companies.list(CITY)).hasSize(1);
  }

  private CreateContractorCompanyRequest company(UUID id, String inn) {
    return new CreateContractorCompanyRequest(
        id,
        " Балтика ",
        inn,
        " Иван ",
        "+7 900 111-11-11",
        "office@example.com",
        "Санкт-Петербург",
        "Манипулятор по звонку");
  }

  private Map<String, Object> driver(UUID id, UUID companyId) {
    var body = new LinkedHashMap<String, Object>();
    body.put("contractorId", id);
    body.put("displayName", "Петров");
    body.put("phone", "+7 900 123-45-67");
    body.put("companyId", companyId);
    return body;
  }

  private Map<String, Object> driverUpdate(long version, UUID companyId) {
    var body = driver(UUID.randomUUID(), companyId);
    body.remove("contractorId");
    body.put("expectedVersion", version);
    body.put("displayName", "Обновлённое имя");
    body.put("active", true);
    return body;
  }

  private ResultActions body(MockHttpServletRequestBuilder request, Object body) throws Exception {
    return mvc.perform(
        request
            .with(
                jwt()
                    .jwt(
                        token ->
                            token
                                .claim("principal_type", "USER")
                                .claim("global_role", "WMS_ADMIN")
                                .claim("scope", "rwms.read rwms.write")))
            .contentType(MediaType.APPLICATION_JSON)
            .content(json.writeValueAsString(body)));
  }

  private JsonNode response(ResultActions result) throws Exception {
    return json.readTree(result.andReturn().getResponse().getContentAsString());
  }

  private static String path(UUID city) {
    return "/api/warehouses/" + city + "/logistics-drivers/companies";
  }

  private static String drivers(UUID city) {
    return "/api/warehouses/" + city + "/logistics-drivers/contractors";
  }

  private static JwtRequestPostProcessor user(UUID city, String level, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .claim("principal_type", "USER")
                    .claim("scope", scope)
                    .claim(
                        "warehouse_access",
                        List.of(Map.of("warehouseId", city.toString(), "level", level))));
  }
}
