package dev.buhanzaz.rwms.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseSupportLinksRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseSupportLinkInput;
import dev.buhanzaz.rwms.warehouse.api.WarehouseSupportLinksResponse;
import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import dev.buhanzaz.rwms.warehouse.service.WarehouseSupportLinkService;
import java.math.BigDecimal;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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

/** Focused vertical tests for owner-held coordinates and directed warehouse support links. */
@SpringBootTest
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class WarehouseSupportLinkIntegrationTest {
  private static final UUID SPB = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired WarehouseService warehouses;
  @Autowired WarehouseSupportLinkService supportLinks;
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
    jdbc.update("delete from warehouse_support_link");
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from outbox_event");
    jdbc.update("delete from warehouse where id not in (?, ?)", SPB, MSK);
  }

  @Test
  void coordinatesRoundTripThroughPublicAndLogisticsContracts() {
    WarehouseResponse created =
        create(
            "Coordinate depot",
            true,
            new BigDecimal("58.521475"),
            new BigDecimal("31.275475"));

    assertThat(created.address()).isNull();
    assertThat(created.latitude()).isEqualByComparingTo("58.521475");
    assertThat(created.longitude()).isEqualByComparingTo("31.275475");
    assertThat(warehouses.get(created.id()).latitude()).isEqualByComparingTo("58.521475");
    assertThat(warehouses.logisticsIdentity(created.id()).latitude())
        .isEqualByComparingTo("58.521475");

    WarehouseResponse replaced =
        warehouses.replace(
            created.id(),
            new ReplaceWarehouseRequest(
                created.version(),
                created.name(),
                created.city(),
                created.address(),
                new BigDecimal("58.600000"),
                new BigDecimal("31.400000"),
                created.timeZone(),
                created.sortOrder(),
                true));

    assertThat(replaced.latitude()).isEqualByComparingTo("58.600000");
    assertThat(replaced.longitude()).isEqualByComparingTo("31.400000");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where aggregate_id=?",
                Integer.class,
                created.id().toString()))
        .isEqualTo(2);
  }

  @Test
  void replacesOneAndTwoLinksAndKeepsAnIdenticalRetryIdempotent() {
    WarehouseResponse served = create("Served regional", true, null, null);
    WarehouseResponse supportOne = create("Support one", false, null, null);
    WarehouseResponse supportTwo = create("Support two", false, null, null);
    WarehouseSupportLinkInput first = input(supportOne.id(), true, 1, Set.of(DayOfWeek.TUESDAY));

    WarehouseSupportLinksResponse one =
        supportLinks.replace(
            served.id(), new ReplaceWarehouseSupportLinksRequest(served.version(), List.of(first)));
    assertThat(one.links()).singleElement().extracting(link -> link.supportWarehouseId())
        .isEqualTo(supportOne.id());

    WarehouseSupportLinkInput second = input(supportTwo.id(), true, 2, Set.of());
    WarehouseSupportLinksResponse two =
        supportLinks.replace(
            served.id(),
            new ReplaceWarehouseSupportLinksRequest(one.warehouseVersion(), List.of(first, second)));
    assertThat(two.links()).hasSize(2);
    assertThat(two.links()).extracting(link -> link.supportWarehouseId())
        .containsExactly(supportOne.id(), supportTwo.id());

    WarehouseSupportLinksResponse replay =
        supportLinks.replace(
            served.id(),
            new ReplaceWarehouseSupportLinksRequest(two.warehouseVersion(), List.of(first, second)));
    assertThat(replay).isEqualTo(two);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from outbox_event where aggregate_id=?",
                Integer.class,
                served.id().toString()))
        .isEqualTo(3);
  }

  @Test
  void rejectsSelfDuplicateAndNonRepresentativeTargetLinks() {
    WarehouseResponse served = create("Served rejects", true, null, null);
    WarehouseResponse support = create("Support rejects", false, null, null);

    assertThatThrownBy(
            () ->
                supportLinks.replace(
                    served.id(),
                    new ReplaceWarehouseSupportLinksRequest(
                        served.version(), List.of(input(served.id(), true, 1, Set.of())))))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("cannot support itself");
    assertThatThrownBy(
            () ->
                supportLinks.replace(
                    served.id(),
                    new ReplaceWarehouseSupportLinksRequest(
                        served.version(),
                        List.of(
                            input(support.id(), true, 1, Set.of()),
                            input(support.id(), true, 2, Set.of())))))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("only once");

    WarehouseResponse ordinary = create("Ordinary served", false, null, null);
    assertThatThrownBy(
            () ->
                supportLinks.replace(
                    ordinary.id(),
                    new ReplaceWarehouseSupportLinksRequest(
                        ordinary.version(), List.of(input(support.id(), true, 1, Set.of())))))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("representative");
  }

  @Test
  void filtersInactiveWeekdayDateExclusionAndDailyWindowForLogistics() throws Exception {
    WarehouseResponse served =
        create(
            "Calendar served",
            true,
            new BigDecimal("58.500000"),
            new BigDecimal("31.200000"));
    WarehouseResponse support =
        create(
            "Calendar support",
            false,
            new BigDecimal("59.900000"),
            new BigDecimal("30.300000"));
    WarehouseSupportLinkInput calendar =
        new WarehouseSupportLinkInput(
            support.id(),
            true,
            1,
            true,
            true,
            true,
            true,
            true,
            true,
            Set.of(DayOfWeek.TUESDAY),
            Set.of(LocalDate.parse("2026-09-02")),
            Set.of(LocalDate.parse("2026-09-08")),
            LocalTime.parse("08:00"),
            LocalTime.parse("18:00"));
    WarehouseSupportLinksResponse configured =
        supportLinks.replace(
            served.id(),
            new ReplaceWarehouseSupportLinksRequest(served.version(), List.of(calendar)));

    assertThat(eligible(served.id(), "2026-09-01T09:00:00+03:00"))
        .singleElement()
        .satisfies(
            link -> {
              assertThat(link.supportWarehouse().id()).isEqualTo(support.id());
              assertThat(link.supportWarehouse().latitude()).isEqualByComparingTo("59.900000");
              assertThat(link.servedWarehouse().longitude()).isEqualByComparingTo("31.200000");
            });
    assertThat(eligible(served.id(), "2026-09-01T18:00:00+03:00")).isEmpty();
    assertThat(eligible(served.id(), "2026-09-02T09:00:00+03:00")).hasSize(1);
    assertThat(eligible(served.id(), "2026-09-03T09:00:00+03:00")).isEmpty();
    assertThat(eligible(served.id(), "2026-09-08T09:00:00+03:00")).isEmpty();
    JsonNode internal =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get(
                            "/api/internal/warehouse/v1/warehouses/logistics/{servedWarehouseId}/support-links",
                            served.id())
                        .param("at", "2026-09-01T09:00:00+03:00")
                        .with(logisticsServiceJwt()))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(internal.size()).isOne();
    assertThat(internal.get(0).get("supportWarehouse").get("id").stringValue())
        .isEqualTo(support.id().toString());

    WarehouseSupportLinkInput inactive =
        new WarehouseSupportLinkInput(
            support.id(),
            false,
            calendar.priority(),
            calendar.allowDrivers(),
            calendar.allowVehicles(),
            calendar.allowInventory(),
            calendar.allowDirectFulfillment(),
            calendar.allowInterwarehouseTransfer(),
            calendar.allowContractorFallback(),
            calendar.allowedWeekdays(),
            calendar.allowedDates(),
            calendar.excludedDates(),
            calendar.serviceStart(),
            calendar.serviceEnd());
    supportLinks.replace(
        served.id(),
        new ReplaceWarehouseSupportLinksRequest(configured.warehouseVersion(), List.of(inactive)));
    assertThat(eligible(served.id(), "2026-09-01T09:00:00+03:00")).isEmpty();
  }

  @Test
  void managerApiRequiresManageOnBothEndpointsAndReturnsTheSavedCollection() throws Exception {
    WarehouseResponse served = create("Manager served", true, null, null);
    WarehouseResponse support = create("Manager support", false, null, null);
    ReplaceWarehouseSupportLinksRequest request =
        new ReplaceWarehouseSupportLinksRequest(
            served.version(), List.of(input(support.id(), true, 1, Set.of(DayOfWeek.MONDAY))));

    mockMvc
        .perform(
            put("/api/warehouse/v1/warehouses/{servedWarehouseId}/support-links", served.id())
                .with(warehouseManagerJwt(served.id(), support.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isOk());
    JsonNode response =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get(
                            "/api/warehouse/v1/warehouses/{servedWarehouseId}/support-links",
                            served.id())
                        .with(warehouseManagerJwt(served.id(), support.id())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(response.get("links")).hasSize(1);
    assertThat(response.get("links").get(0).get("supportWarehouseId").stringValue())
        .isEqualTo(support.id().toString());

    mockMvc
        .perform(
            put("/api/warehouse/v1/warehouses/{servedWarehouseId}/support-links", served.id())
                .with(warehouseManagerJwt(served.id()))
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsBytes(request)))
        .andExpect(status().isForbidden());
  }

  @Test
  void clearingRepresentativeRequiresRemovingItsSupportLinksFirst() {
    WarehouseResponse served = create("Protected representative", true, null, null);
    WarehouseResponse support = create("Protected support", false, null, null);
    WarehouseSupportLinksResponse configured =
        supportLinks.replace(
            served.id(),
            new ReplaceWarehouseSupportLinksRequest(
                served.version(), List.of(input(support.id(), true, 1, Set.of()))));

    assertThatThrownBy(
            () ->
                warehouses.replace(
                    served.id(),
                    new ReplaceWarehouseRequest(
                        configured.warehouseVersion(),
                        served.name(),
                        served.city(),
                        served.address(),
                        served.latitude(),
                        served.longitude(),
                        served.timeZone(),
                        served.sortOrder(),
                        false)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("Remove warehouse support links");
  }

  private WarehouseResponse create(
      String name, boolean representative, BigDecimal latitude, BigDecimal longitude) {
    return warehouses
        .create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateWarehouseRequest(
                name,
                "Регион",
                null,
                latitude,
                longitude,
                "Europe/Moscow",
                null,
                representative))
        .response();
  }

  private static WarehouseSupportLinkInput input(
      UUID supportWarehouseId, boolean active, int priority, Set<DayOfWeek> weekdays) {
    return new WarehouseSupportLinkInput(
        supportWarehouseId,
        active,
        priority,
        true,
        true,
        true,
        true,
        true,
        true,
        weekdays,
        Set.of(),
        Set.of(),
        null,
        null);
  }

  private List<dev.buhanzaz.rwms.warehouse.api.LogisticsWarehouseSupportLinkResponse> eligible(
      UUID servedWarehouseId, String at) {
    return supportLinks.logisticsLinks(servedWarehouseId, OffsetDateTime.parse(at));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      warehouseManagerJwt(UUID... warehouseIds) {
    List<Map<String, String>> access =
        java.util.Arrays.stream(warehouseIds)
            .map(id -> Map.of("warehouseId", id.toString(), "level", "MANAGE"))
            .toList();
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", "WAREHOUSE_MANAGER")
                    .claim("scope", "warehouse.read rwms.write")
                    .claim("warehouse_access", access));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      logisticsServiceJwt() {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("logistics-service")
                    .audience(List.of("rwms-services"))
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", "logistics-service")
                    .claim("scope", "warehouse.logistics"));
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }
}
