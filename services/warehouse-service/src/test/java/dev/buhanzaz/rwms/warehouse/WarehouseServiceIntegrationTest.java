package dev.buhanzaz.rwms.warehouse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleReadinessRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseLifecycleTransitionRequest;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ScheduleWarehouseTimeZoneRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseOperationMarkRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseTimeZoneAtResponse;
import dev.buhanzaz.rwms.warehouse.api.WarehouseTimeZoneChangeResponse;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseDefaults;
import dev.buhanzaz.rwms.warehouse.domain.WarehouseType;
import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseIdempotencyStore;
import dev.buhanzaz.rwms.warehouse.service.WarehouseLifecycleReadinessOwner;
import dev.buhanzaz.rwms.warehouse.service.WarehouseNotFoundException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseOperationDirection;
import dev.buhanzaz.rwms.warehouse.service.WarehouseOperationSource;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
  private static final UUID COMPANY = WarehouseDefaults.INITIAL_COMPANY_ID;
  private static final UUID OTHER_COMPANY =
      UUID.fromString("00000000-0000-0000-0000-0000000000c2");
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

    WarehouseService.CreateResult created = service.create(COMPANY, subject, key, first);
    WarehouseService.CreateResult replayed =
        service.create(COMPANY, subject, key, request("Test west", 4));

    assertThat(created.replayed()).isFalse();
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.response()).isEqualTo(created.response());
    assertThat(count("select count(*) from warehouse where name='Test west'")).isOne();
    assertThat(
            count(
                "select count(*) from outbox_event where aggregate_id=?",
                created.response().id().toString()))
        .isOne();
    assertThatThrownBy(
            () ->
                service.create(
                    COMPANY,
                    subject,
                    key,
                    new CreateWarehouseRequest(
                        "Test west",
                        "Москва",
                        "",
                        null,
                        null,
                        "Europe/Moscow",
                        4,
                        WarehouseType.REPRESENTATIVE,
                        SPB)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("Idempotency-Key");
    assertThatThrownBy(() -> service.create(COMPANY, subject, key, request("Test east", 4)))
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
    assertThat(service.create(COMPANY, subject, key, request("Test east", 4)).replayed())
        .isFalse();
  }

  @Test
  void createsOrdinaryAndRepresentativeWarehousesAndReplacesTheCharacteristic() {
    WarehouseResponse ordinary =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Ordinary depot", null))
            .response();
    WarehouseResponse representative =
        service
            .create(
                COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                new CreateWarehouseRequest(
                    "Representative depot",
                    "Великий Новгород",
                    "Большая Санкт-Петербургская улица, 1",
                    null,
                    null,
                    "Europe/Moscow",
                    null,
                    WarehouseType.REPRESENTATIVE,
                    SPB))
            .response();

    assertThat(ordinary.representative()).isFalse();
    assertThat(representative.representative()).isTrue();
    assertThat(
            jdbc.queryForObject(
                "select representative from warehouse where id=?",
                Boolean.class,
                representative.id()))
        .isTrue();

    WarehouseResponse replaced =
        service.replace(
            COMPANY,
            ordinary.id(),
            new ReplaceWarehouseRequest(
                ordinary.version(),
                ordinary.name(),
                ordinary.city(),
                ordinary.address(),
                ordinary.latitude(),
                ordinary.longitude(),
                ordinary.timeZone(),
                ordinary.sortOrder(),
                WarehouseType.REPRESENTATIVE,
                SPB));

    assertThat(replaced.representative()).isTrue();
    assertThat(service.get(COMPANY, ordinary.id()).representative()).isTrue();
    assertThat(service.logisticsIdentity(ordinary.id()).representative()).isTrue();
  }

  @Test
  void legacyJsonOmissionDefaultsRepresentativeToFalseForCreateAndFullReplace()
      throws Exception {
    JsonNode created =
        objectMapper.readTree(
            mockMvc
                .perform(
                    post("/api/warehouse/v1/warehouses")
                        .with(systemAdminWriteJwt())
                        .header("Idempotency-Key", UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "name": "Legacy JSON depot",
                              "city": "Москва",
                              "address": null,
                              "timeZone": "Europe/Moscow",
                              "sortOrder": null
                            }
                            """))
                .andExpect(status().isCreated())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(created.get("representative").booleanValue()).isFalse();
    UUID warehouseId = UUID.fromString(created.get("id").stringValue());
    JsonNode replaced =
        objectMapper.readTree(
            mockMvc
                .perform(
                    put("/api/warehouse/v1/warehouses/{id}", warehouseId)
                        .with(systemAdminWriteJwt())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                            """
                            {
                              "expectedVersion": %d,
                              "name": "Legacy JSON depot updated",
                              "city": "Москва",
                              "address": null,
                              "timeZone": "Europe/Moscow",
                              "sortOrder": null
                            }
                            """
                                .formatted(created.get("version").longValue())))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(replaced.get("representative").booleanValue()).isFalse();
  }

  @Test
  void enforcesFencedOneWayLifecycleAndRequiresEveryReadinessOwner() {
    WarehouseResponse created =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Operations", null))
            .response();
    WarehouseResponse noOp =
        service.replace(
            COMPANY, created.id(), replace(created, created.version(), "Operations", null));
    assertThat(noOp.version()).isEqualTo(created.version());
    assertThat(
            count(
                "select count(*) from outbox_event where aggregate_id=?", created.id().toString()))
        .isOne();

    WarehouseResponse changed =
        service.replace(
            COMPANY, created.id(), replace(created, created.version(), "Operations updated", 5));
    assertThat(changed.version()).isEqualTo(created.version() + 1);
    assertThat(eventTypes(created.id()))
        .containsExactly("warehouse.warehouse.created.v1", "warehouse.warehouse.changed.v1");
    assertThatThrownBy(
            () ->
                service.replace(
                    COMPANY, created.id(), replace(changed, created.version(), "Stale", 5)))
        .isInstanceOf(WarehouseConflictException.class);

    assertThatThrownBy(
            () ->
                service.completeInactivation(
                    COMPANY,
                    created.id(),
                    new WarehouseLifecycleTransitionRequest(changed.version())))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("DRAINING");

    WarehouseResponse draining =
        service.startDraining(
            COMPANY, created.id(), new WarehouseLifecycleTransitionRequest(changed.version()));
    assertThat(draining.lifecycleState()).isEqualTo("DRAINING");
    assertThat(draining.active()).isFalse();
    assertThat(service.admission(draining.id(), WarehouseOperationDirection.INCOMING).admitted())
        .isFalse();
    assertThat(service.admission(draining.id(), WarehouseOperationDirection.OUTGOING).admitted())
        .isTrue();
    assertThatThrownBy(
            () ->
                service.startDraining(
                    COMPANY,
                    created.id(),
                    new WarehouseLifecycleTransitionRequest(draining.version())))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("ACTIVE");
    assertThatThrownBy(
            () ->
                service.confirmLifecycleReadiness(
                    created.id(),
                    WarehouseLifecycleReadinessOwner.ASSET,
                    new WarehouseLifecycleReadinessRequest(changed.version())))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("changed by another request");
    assertThatThrownBy(
            () ->
                service.completeInactivation(
                    COMPANY,
                    created.id(),
                    new WarehouseLifecycleTransitionRequest(draining.version())))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("readiness");

    long readinessVersion =
        service
            .confirmLifecycleReadiness(
                created.id(),
                WarehouseLifecycleReadinessOwner.ASSET,
                new WarehouseLifecycleReadinessRequest(draining.version()))
            .warehouseVersion();
    assertThat(
            service
                .confirmLifecycleReadiness(
                    created.id(),
                    WarehouseLifecycleReadinessOwner.ASSET,
                    new WarehouseLifecycleReadinessRequest(draining.version()))
                .warehouseVersion())
        .isEqualTo(readinessVersion);
    for (WarehouseLifecycleReadinessOwner owner : WarehouseLifecycleReadinessOwner.values()) {
      if (owner == WarehouseLifecycleReadinessOwner.ASSET) continue;
      readinessVersion =
          service
              .confirmLifecycleReadiness(
                  created.id(), owner, new WarehouseLifecycleReadinessRequest(readinessVersion))
              .warehouseVersion();
    }
    WarehouseResponse inactive =
        service.completeInactivation(
            COMPANY, created.id(), new WarehouseLifecycleTransitionRequest(readinessVersion));
    assertThat(inactive.lifecycleState()).isEqualTo("INACTIVE");
    assertThat(inactive.active()).isFalse();
    assertThat(service.admission(inactive.id(), WarehouseOperationDirection.INCOMING).admitted())
        .isFalse();
    assertThat(service.admission(inactive.id(), WarehouseOperationDirection.OUTGOING).admitted())
        .isFalse();
    assertThat(eventTypes(created.id()).getLast()).isEqualTo("warehouse.warehouse.deactivated.v1");
    assertThat(
            count(
                "select count(*) from warehouse_lifecycle_readiness where warehouse_id=?",
                created.id()))
        .isEqualTo(WarehouseLifecycleReadinessOwner.values().length);
    assertThat(
            count(
                "select count(*) from warehouse_lifecycle_transition where warehouse_id=?",
                created.id()))
        .isEqualTo(2);
    assertThatThrownBy(
            () ->
                service.create(
                    COMPANY,
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    request(" operations\tUPDATED ", null)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("name");
    assertThatThrownBy(
            () ->
                service.startDraining(
                    COMPANY,
                    inactive.id(),
                    new WarehouseLifecycleTransitionRequest(inactive.version())))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("ACTIVE");

    WarehouseResponse anotherWarehouse =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Field office", null))
            .response();
    assertThatThrownBy(
            () ->
                service.replace(
                        COMPANY,
                        anotherWarehouse.id(),
                        replace(
                            anotherWarehouse,
                            anotherWarehouse.version(),
                            "OPERATIONS  updated",
                            null)))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("name");
  }

  @Test
  void reconcilesOnlyTheAuthenticatedOwnersOutstandingDrainingWorkWithAKeyset() {
    WarehouseResponse first =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Drain work first", null))
            .response();
    WarehouseResponse second =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Drain work second", null))
            .response();
    WarehouseResponse firstDraining =
        service.startDraining(
            COMPANY, first.id(), new WarehouseLifecycleTransitionRequest(first.version()));
    service.startDraining(
        COMPANY, second.id(), new WarehouseLifecycleTransitionRequest(second.version()));

    var firstPage =
        service.lifecycleReadinessWork(WarehouseLifecycleReadinessOwner.ASSET, null, 1);
    assertThat(firstPage.items()).hasSize(1);
    assertThat(firstPage.items().getFirst().lifecycleState()).isEqualTo("DRAINING");
    assertThat(firstPage.nextAfter()).isEqualTo(firstPage.items().getFirst().warehouseId());
    var secondPage =
        service.lifecycleReadinessWork(
            WarehouseLifecycleReadinessOwner.ASSET, firstPage.nextAfter(), 1);
    assertThat(secondPage.items()).hasSize(1);
    assertThat(secondPage.nextAfter()).isNull();
    assertThat(
            Set.of(firstPage.items().getFirst().warehouseId(), secondPage.items().getFirst().warehouseId()))
        .containsExactlyInAnyOrder(first.id(), second.id());

    service.confirmLifecycleReadiness(
        first.id(),
        WarehouseLifecycleReadinessOwner.ASSET,
        new WarehouseLifecycleReadinessRequest(firstDraining.version()));
    assertThat(
            service.lifecycleReadinessWork(WarehouseLifecycleReadinessOwner.ASSET, null, 10).items())
        .extracting(item -> item.warehouseId())
        .containsExactly(second.id());
    assertThat(
            service.lifecycleReadinessWork(WarehouseLifecycleReadinessOwner.INVENTORY, null, 10).items())
        .extracting(item -> item.warehouseId())
        .containsExactlyInAnyOrder(first.id(), second.id());
  }

  @Test
  void publicWarehouseReadReturnsInactiveWarehouseByUuidAndDefaultListHidesOnlyTerminalState()
      throws Exception {
    WarehouseResponse created =
        service
            .create(
                COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Historical warehouse", null))
            .response();
    WarehouseResponse inactive = drainAndInactivate(created);

    String body =
        mockMvc
            .perform(
                get("/api/warehouse/v1/warehouses/{id}", inactive.id()).with(warehouseReadUserJwt()))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    assertThat(objectMapper.readTree(body).get("active").booleanValue()).isFalse();
    assertThat(objectMapper.readTree(body).get("lifecycleState").stringValue()).isEqualTo("INACTIVE");
    assertThat(service.list(COMPANY, false))
        .extracting(WarehouseResponse::id)
        .doesNotContain(inactive.id());
    assertThat(service.list(COMPANY, true))
        .extracting(WarehouseResponse::id)
        .contains(inactive.id());
    mockMvc
        .perform(
            delete("/api/warehouse/v1/warehouses/{id}", inactive.id())
                .queryParam("expectedVersion", Long.toString(inactive.version()))
                .with(systemAdminWriteJwt()))
        .andExpect(status().isMethodNotAllowed());
  }

  @Test
  void duplicateWarehouseNamesReturnConflictForCreateAndReplace() throws Exception {
    WarehouseResponse existing =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Registry depot", null))
            .response();
    WarehouseResponse other =
        service
            .create(
                COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Registry overflow", null))
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
  void scopesNamesListsAndUuidReadsToTheAuthenticatedCompany() throws Exception {
    WarehouseResponse initial =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Shared name", null))
            .response();
    WarehouseResponse other =
        service
            .create(
                OTHER_COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request(" shared  NAME ", null))
            .response();

    assertThat(service.list(COMPANY, false))
        .extracting(WarehouseResponse::id)
        .contains(initial.id())
        .doesNotContain(other.id());
    assertThat(service.list(OTHER_COMPANY, false))
        .extracting(WarehouseResponse::id)
        .containsExactly(other.id());
    assertThatThrownBy(() -> service.get(COMPANY, other.id()))
        .isInstanceOf(WarehouseNotFoundException.class);

    mockMvc
        .perform(
            get("/api/warehouse/v1/warehouses/{id}", other.id())
                .with(warehouseReadUserJwt(COMPANY)))
        .andExpect(status().isNotFound());
    mockMvc
        .perform(
            get("/api/warehouse/v1/warehouses/{id}", other.id())
                .with(warehouseReadUserJwt(OTHER_COMPANY)))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(
                        objectMapper
                            .readTree(result.getResponse().getContentAsString())
                            .get("companyId")
                            .stringValue())
                    .isEqualTo(OTHER_COMPANY.toString()));
  }

  @Test
  void rentalManagerListsOnlySignedCompanyWarehousesAndCannotReadByIdOrMutate()
      throws Exception {
    WarehouseResponse own =
        service
            .create(COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Manager depot", null))
            .response();
    WarehouseResponse foreign =
        service
            .create(
                OTHER_COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Foreign manager depot", null))
            .response();

    JsonNode directory =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/warehouse/v1/warehouses")
                        .with(rentalManagerDirectoryJwt(COMPANY)))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());

    assertThat(
            java.util.stream.StreamSupport.stream(directory.spliterator(), false)
                .map(value -> value.get("id").stringValue())
                .toList())
        .contains(own.id().toString())
        .doesNotContain(foreign.id().toString());
    assertThat(
            java.util.stream.StreamSupport.stream(directory.spliterator(), false)
                .map(value -> value.get("companyId").stringValue())
                .toList())
        .containsOnly(COMPANY.toString());

    mockMvc
        .perform(
            get("/api/warehouse/v1/warehouses/{id}", own.id())
                .with(rentalManagerDirectoryJwt(COMPANY)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            get("/api/warehouse/v1/warehouses")
                .queryParam("includeInactive", "true")
                .with(rentalManagerDirectoryJwt(COMPANY)))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post("/api/warehouse/v1/warehouses")
                .with(rentalManagerDirectoryJwt(COMPANY))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content(objectMapper.writeValueAsString(request("Denied manager depot", null))))
        .andExpect(status().isForbidden());
  }

  @Test
  void preservesEffectiveDatedTimezoneHistoryAndPreventsPostOperationCorrections() throws Exception {
    WarehouseResponse created =
        service
            .create(
                COMPANY, UUID.randomUUID(), UUID.randomUUID(), request("Timezone history", null))
            .response();
    WarehouseResponse corrected =
        service.replace(
            COMPANY,
            created.id(),
            new ReplaceWarehouseRequest(
                created.version(),
                created.name(),
                created.city(),
                created.address(),
                "Europe/Samara",
                created.sortOrder()));
    assertThat(corrected.timeZone()).isEqualTo("Europe/Samara");

    OffsetDateTime operationAt = databaseNow();
    UUID operationId = UUID.randomUUID();
    assertThat(
            service.markOperation(
                corrected.id(),
                WarehouseOperationSource.ASSET,
                new WarehouseOperationMarkRequest(operationId, operationAt)))
        .isTrue();
    assertThat(
            service.markOperation(
                corrected.id(),
                WarehouseOperationSource.ASSET,
                new WarehouseOperationMarkRequest(operationId, operationAt)))
        .isFalse();

    OffsetDateTime effectiveFrom = OffsetDateTime.now(ZoneOffset.UTC).plusDays(1);
    WarehouseTimeZoneChangeResponse scheduled =
        service.scheduleTimeZone(
            COMPANY,
            corrected.id(),
            new ScheduleWarehouseTimeZoneRequest(
                corrected.version(), "Europe/Moscow", effectiveFrom));
    assertThat(scheduled.warehouseVersion()).isEqualTo(corrected.version() + 1);
    assertThat(scheduled.timeZone()).isEqualTo("Europe/Moscow");
    assertThat(service.get(COMPANY, created.id()).timeZone()).isEqualTo("Europe/Samara");
    WarehouseTimeZoneAtResponse historical = service.timeZoneAt(created.id(), operationAt);
    assertThat(historical.timeZone()).isEqualTo("Europe/Samara");
    WarehouseTimeZoneAtResponse future =
        service.timeZoneAt(created.id(), scheduled.effectiveFrom().plusSeconds(1));
    assertThat(future.timeZone()).isEqualTo("Europe/Moscow");

    assertThatThrownBy(
            () ->
                service.replace(
                    COMPANY,
                    created.id(),
                    new ReplaceWarehouseRequest(
                        scheduled.warehouseVersion(),
                        corrected.name(),
                        corrected.city(),
                        corrected.address(),
                        "Europe/Moscow",
                        corrected.sortOrder())))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("scheduled");
    assertThat(count("select count(*) from warehouse_time_zone_history where warehouse_id=?", created.id()))
        .isEqualTo(3);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark where warehouse_id=?", Integer.class, created.id()))
        .isOne();
    String scheduledEvent =
        jdbc.queryForObject(
            """
            select envelope_body::text
              from outbox_event
             where aggregate_id=? and aggregate_version=?
            """,
            String.class,
            created.id().toString(),
            scheduled.warehouseVersion());
    assertThat(objectMapper.readTree(scheduledEvent).at("/payload/timeZone").stringValue())
        .isEqualTo("Europe/Samara");
    assertThat(
            objectMapper
                .readTree(scheduledEvent)
                .at("/payload/timeZoneDecision/timeZone")
                .stringValue())
        .isEqualTo("Europe/Moscow");
    assertThat(
            OffsetDateTime.parse(
                objectMapper
                    .readTree(scheduledEvent)
                    .at("/payload/timeZoneDecision/effectiveFrom")
                    .stringValue()))
        .isEqualTo(scheduled.effectiveFrom());
  }

  @Test
  void exposesTimezoneAsOfAndOperationMarkOnlyToExactOperationOwnerScopes() throws Exception {
    WarehouseResponse created =
        service
            .create(
                COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Timezone private boundary", null))
            .response();
    OffsetDateTime now = databaseNow();
    String asOfBody =
        mockMvc
            .perform(
                get("/api/internal/warehouse/v1/warehouses/{id}/time-zone", created.id())
                    .queryParam("at", now.toString())
                    .with(
                        operationOwnerJwt(
                            "asset-service", "warehouse.timezone.read")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(objectMapper.readTree(asOfBody).get("timeZone").stringValue())
        .isEqualTo("Europe/Moscow");
    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/{id}/time-zone", created.id())
                .queryParam("at", now.toString())
                .with(
                    operationOwnerJwt(
                        "task-board-service", "warehouse.timezone.read")))
        .andExpect(status().isOk());

    mockMvc
        .perform(
            post("/api/internal/warehouse/v1/warehouses/{id}/operation-marks", created.id())
                .with(operationOwnerJwt("asset-service", "warehouse.operation.mark"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new WarehouseOperationMarkRequest(UUID.randomUUID(), databaseNow()))))
        .andExpect(status().isNoContent());
    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/{id}/time-zone", created.id())
                .queryParam("at", now.toString())
                .with(
                    operationOwnerJwt(
                        "asset-service", "warehouse.timezone.read warehouse.read")))
        .andExpect(status().isForbidden());
  }

  @Test
  void exposesFencedDirectionalAdmissionAndAuthenticatedReadinessOnlyToLifecycleOwners()
      throws Exception {
    WarehouseResponse created =
        service
            .create(
                COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Lifecycle admission", null))
            .response();
    WarehouseResponse draining =
        service.startDraining(
            COMPANY, created.id(), new WarehouseLifecycleTransitionRequest(created.version()));

    JsonNode incoming =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/internal/warehouse/v1/warehouses/{id}/admission", created.id())
                        .queryParam("direction", "INCOMING")
                        .with(lifecycleOwnerJwt("asset-service", "warehouse.lifecycle.read")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(Set.copyOf(incoming.propertyNames()))
        .containsExactlyInAnyOrder(
            "warehouseId", "warehouseVersion", "lifecycleState", "direction", "admitted");
    assertThat(incoming.get("lifecycleState").stringValue()).isEqualTo("DRAINING");
    assertThat(incoming.get("direction").stringValue()).isEqualTo("INCOMING");
    assertThat(incoming.get("admitted").booleanValue()).isFalse();

    JsonNode outgoing =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/internal/warehouse/v1/warehouses/{id}/admission", created.id())
                        .queryParam("direction", "OUTGOING")
                        .with(lifecycleOwnerJwt("asset-service", "warehouse.lifecycle.read")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(outgoing.get("admitted").booleanValue()).isTrue();

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/{id}/admission", created.id())
                .queryParam("direction", "OUTGOING")
                .with(
                    lifecycleOwnerJwt(
                        "asset-service", "warehouse.lifecycle.read warehouse.read")))
        .andExpect(status().isForbidden());

    JsonNode work =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/internal/warehouse/v1/lifecycle/readiness-work")
                        .queryParam("limit", "100")
                        .with(lifecycleOwnerJwt("asset-service", "warehouse.lifecycle.read")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(Set.copyOf(work.propertyNames())).containsExactlyInAnyOrder("items", "nextAfter");
    assertThat(work.get("items").get(0).get("warehouseId").stringValue())
        .isEqualTo(created.id().toString());
    assertThat(work.get("items").get(0).get("warehouseVersion").longValue())
        .isEqualTo(draining.version());
    assertThat(work.get("items").get(0).get("lifecycleState").stringValue()).isEqualTo("DRAINING");

    mockMvc
        .perform(
            post(
                    "/api/internal/warehouse/v1/warehouses/{id}/lifecycle-readiness",
                    created.id())
                .with(lifecycleOwnerJwt("asset-service", "warehouse.lifecycle.confirm"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new WarehouseLifecycleReadinessRequest(draining.version()))))
        .andExpect(status().isOk())
        .andExpect(
            result ->
                assertThat(
                        objectMapper
                            .readTree(result.getResponse().getContentAsString())
                            .get("readinessOwner")
                            .stringValue())
                    .isEqualTo("ASSET"));

    JsonNode acknowledged =
        objectMapper.readTree(
            mockMvc
                .perform(
                    get("/api/internal/warehouse/v1/lifecycle/readiness-work")
                        .with(lifecycleOwnerJwt("asset-service", "warehouse.lifecycle.read")))
                .andExpect(status().isOk())
                .andReturn()
                .getResponse()
                .getContentAsString());
    assertThat(acknowledged.get("items")).isEmpty();
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
        .containsExactlyInAnyOrder("id", "companyId", "version", "active");
    assertThat(response.get("id").stringValue()).isEqualTo(SPB.toString());
    assertThat(response.get("companyId").stringValue())
        .isEqualTo(COMPANY.toString());
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

    String assetBody =
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
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertThat(Set.copyOf(objectMapper.readTree(assetBody).propertyNames()))
        .containsExactlyInAnyOrder("id", "version", "active");
  }

  @Test
  void exposesAuthoritativeCompanyInTheTaskBoardWarehouseIdentity() throws Exception {
    String body =
        mockMvc
            .perform(
                get("/api/internal/warehouse/v1/warehouses/{id}/identity", SPB)
                    .with(operationOwnerJwt("task-board-service", "warehouse.identity.read")))
            .andExpect(status().isOk())
            .andReturn()
            .getResponse()
            .getContentAsString();

    JsonNode response = objectMapper.readTree(body);
    assertThat(Set.copyOf(response.propertyNames()))
        .containsExactlyInAnyOrder(
            "id",
            "companyId",
            "version",
            "active",
            "name",
            "city",
            "address",
            "latitude",
            "longitude",
            "timeZone");
    assertThat(response.get("id").stringValue()).isEqualTo(SPB.toString());
    assertThat(response.get("companyId").stringValue()).isEqualTo(COMPANY.toString());

    mockMvc
        .perform(
            get("/api/internal/warehouse/v1/warehouses/{id}/identity", SPB)
                .with(
                    operationOwnerJwt(
                        "task-board-service", "warehouse.identity.read warehouse.read")))
        .andExpect(status().isForbidden());
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

    WarehouseResponse inventoryCreated =
        service
            .create(
                COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Inventory hidden", null))
            .response();
    WarehouseResponse inactive = drainAndInactivate(inventoryCreated);

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
            "id",
            "companyId",
            "version",
            "active",
            "name",
            "city",
            "address",
            "latitude",
            "longitude",
            "timeZone",
            "representative");
    assertThat(response.get("id").stringValue()).isEqualTo(SPB.toString());
    assertThat(response.get("companyId").stringValue()).isEqualTo(COMPANY.toString());
    assertThat(response.get("version").longValue()).isZero();
    assertThat(response.get("active").booleanValue()).isTrue();
    assertThat(response.get("name").stringValue()).isEqualTo("СПБ");
    assertThat(response.get("city").stringValue()).isEqualTo("Санкт-Петербург");
    assertThat(response.get("address").isNull()).isTrue();
    assertThat(response.get("latitude").isNull()).isTrue();
    assertThat(response.get("longitude").isNull()).isTrue();
    assertThat(response.get("timeZone").stringValue()).isEqualTo("Europe/Moscow");
    assertThat(response.get("representative").booleanValue()).isFalse();

    WarehouseResponse logisticsCreated =
        service
            .create(
                COMPANY,
                UUID.randomUUID(),
                UUID.randomUUID(),
                request("Logistics inactive", null))
            .response();
    WarehouseResponse inactive = drainAndInactivate(logisticsCreated);

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
    assertThat(
            java.util.stream.StreamSupport.stream(active.spliterator(), false)
                .map(value -> value.get("companyId").stringValue())
                .toList())
        .containsOnly(COMPANY.toString());

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
    return warehouseReadUserJwt(COMPANY);
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      warehouseReadUserJwt(UUID companyId) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("company_id", companyId.toString())
                    .claim("scope", "warehouse.read"));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      rentalManagerDirectoryJwt(UUID companyId) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(UUID.randomUUID().toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", "RENTAL_MANAGER")
                    .claim("company_id", companyId.toString())
                    .claim("client_id", "rwms-rental-manager-web")
                    .claim("rentalAccess", true)
                    .claim("scope", "openid profile offline_access rental.manage"));
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
                    .claim("company_id", COMPANY.toString())
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

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      operationOwnerJwt(String clientId, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(clientId)
                    .audience(java.util.List.of("rwms-services"))
                    .claim("principal_type", "SERVICE")
                    .claim("client_id", clientId)
                    .claim("scope", scope));
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      lifecycleOwnerJwt(String clientId, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(clientId)
                    .audience(java.util.List.of("rwms-services"))
                    .claim("principal_type", "SERVICE")
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
      Integer sortOrder) {
    return new ReplaceWarehouseRequest(
        expectedVersion,
        name,
        warehouse.city(),
        warehouse.address(),
        warehouse.timeZone(),
        sortOrder);
  }

  private WarehouseResponse drainAndInactivate(WarehouseResponse warehouse) {
    WarehouseResponse draining =
        service.startDraining(
            COMPANY, warehouse.id(), new WarehouseLifecycleTransitionRequest(warehouse.version()));
    long expectedVersion = draining.version();
    for (WarehouseLifecycleReadinessOwner owner : WarehouseLifecycleReadinessOwner.values()) {
      expectedVersion =
          service
              .confirmLifecycleReadiness(
                  warehouse.id(), owner, new WarehouseLifecycleReadinessRequest(expectedVersion))
              .warehouseVersion();
    }
    return service.completeInactivation(
        COMPANY, warehouse.id(), new WarehouseLifecycleTransitionRequest(expectedVersion));
  }

  private long count(String query, Object... arguments) {
    Long result = jdbc.queryForObject(query, Long.class, arguments);
    return result == null ? 0 : result;
  }

  private OffsetDateTime databaseNow() {
    return jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
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
