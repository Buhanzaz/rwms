package dev.buhanzaz.rwms.taskboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.patch;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/** Verifies the public panel resource boundary and its warehouse grant fences. */
@SpringBootTest
@ActiveProfiles("test")
@AutoConfigureMockMvc
class LogisticsDriverResourceControllerIntegrationTest extends PostgresIntegrationTestSupport {
  private static final UUID WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000781");
  private static final UUID OTHER_WAREHOUSE =
      UUID.fromString("00000000-0000-0000-0000-000000000782");
  private static final UUID CONTRACTOR =
      UUID.fromString("00000000-0000-0000-0000-000000000783");
  private static final UUID SECOND_CONTRACTOR =
      UUID.fromString("00000000-0000-0000-0000-000000000784");
  private static final UUID STAFF_WORKER =
      UUID.fromString("00000000-0000-0000-0000-000000000785");
  private static final String PATH = "/api/warehouses/" + WAREHOUSE + "/logistics-drivers";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void editorCreatesOnDemandContractorWithoutProfileDates() throws Exception {
    mvc.perform(
            post(PATH + "/contractors")
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "contractorId":"%s",
                      "displayName":"Наёмный водитель",
                      "phone":"+7 900 000-00-00",
                      "comment":"Рейс Великого Новгорода"
                    }
                    """
                        .formatted(CONTRACTOR)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.workerId").value(CONTRACTOR.toString()))
        .andExpect(jsonPath("$.homeWarehouseId").value(WAREHOUSE.toString()))
        .andExpect(jsonPath("$.availableFrom").doesNotExist())
        .andExpect(jsonPath("$.availableUntil").doesNotExist())
        .andExpect(jsonPath("$.employmentType").value("CONTRACTOR"));

    mvc.perform(
            get(PATH)
                .queryParam("at", "2030-05-12T12:00:00+03:00")
                .with(userJwt("rwms.read", "VIEW", WAREHOUSE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(CONTRACTOR.toString()))
        .andExpect(jsonPath("$[0].availabilityKind").value("HOME"))
        .andExpect(jsonPath("$[1]").doesNotExist());

    mvc.perform(
            get(PATH)
                .queryParam("at", "2030-05-13T12:00:00+03:00")
                .with(userJwt("rwms.read", "VIEW", WAREHOUSE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$[0].workerId").value(CONTRACTOR.toString()));
  }

  @Test
  void publicBoundaryRequiresTheMatchingScopeAndWarehouseGrant() throws Exception {
    mvc.perform(get(PATH).with(userJwt("rwms.write", "EDIT", WAREHOUSE)))
        .andExpect(status().isForbidden());
    mvc.perform(
            get(PATH)
                .with(userJwt("rwms.read", "VIEW", OTHER_WAREHOUSE)))
        .andExpect(status().isForbidden());
    mvc.perform(
            post(PATH + "/contractors")
                .with(userJwt("rwms.write", "VIEW", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "contractorId":"%s",
                      "displayName":"Недоступный подрядчик",
                      "phone":"+7 900 111-11-11",
                      "comment":null
                    }
                    """
                        .formatted(CONTRACTOR)))
        .andExpect(status().isForbidden());

    mvc.perform(get(PATH + "/contractors").with(userJwt("rwms.write", "EDIT", WAREHOUSE)))
        .andExpect(status().isForbidden());
    mvc.perform(
            patch(PATH + "/contractors/" + CONTRACTOR)
                .with(userJwt("rwms.write", "VIEW", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(0, "Недоступный подрядчик", true)))
        .andExpect(status().isForbidden());
  }

  @Test
  void contractorCatalogIncludesInactiveProfiles() throws Exception {
    createContractor(WAREHOUSE, CONTRACTOR, "A Активный подрядчик");
    createContractor(WAREHOUSE, SECOND_CONTRACTOR, "B Будущий подрядчик");
    mvc.perform(
            patch(PATH + "/contractors/" + SECOND_CONTRACTOR)
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(0, "B Неактивный подрядчик", false)))
        .andExpect(status().isOk());

    mvc.perform(get(PATH + "/contractors").with(userJwt("rwms.read", "VIEW", WAREHOUSE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(2))
        .andExpect(jsonPath("$[0].workerId").value(CONTRACTOR.toString()))
        .andExpect(jsonPath("$[0].active").value(true))
        .andExpect(jsonPath("$[1].workerId").value(SECOND_CONTRACTOR.toString()))
        .andExpect(jsonPath("$[1].active").value(false));
  }

  @Test
  void editorUpdatesContractorUnderExpectedVersionAndRejectsStaleReplay() throws Exception {
    createContractor(WAREHOUSE, CONTRACTOR, "Наёмный водитель");
    String update = updateBody(0, "Иван Петров", false);

    mvc.perform(
            patch(PATH + "/contractors/" + CONTRACTOR)
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE))
                .header("Origin", "http://localhost:8080")
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.displayName").value("Иван Петров"))
        .andExpect(jsonPath("$.phone").value("+7 900 999-00-00"))
        .andExpect(jsonPath("$.comment").value("Обновлён диспетчером"))
        .andExpect(jsonPath("$.active").value(false));

    mvc.perform(
            patch(PATH + "/contractors/" + CONTRACTOR)
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(update))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("TASK_BOARD_CONFLICT"));
  }

  @Test
  void updateDoesNotCrossWarehouseBoundaryOrReclassifyStaff() throws Exception {
    createContractor(OTHER_WAREHOUSE, SECOND_CONTRACTOR, "Другой склад");
    jdbc.update(
        """
        insert into worker(
          id,version,revision_marker,warehouse_id,display_name,active,credential_status)
        values (?,0,?,?,?,true,'NOT_CONFIGURED')
        """,
        STAFF_WORKER,
        UUID.randomUUID(),
        WAREHOUSE,
        "Штатный водитель");

    mvc.perform(
            patch(PATH + "/contractors/" + SECOND_CONTRACTOR)
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(0, "Другой склад", true)))
        .andExpect(status().isNotFound());
    mvc.perform(
            patch(PATH + "/contractors/" + STAFF_WORKER)
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE))
                .contentType(MediaType.APPLICATION_JSON)
                .content(updateBody(0, "Штатный водитель", true)))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.detail").value("Выбранный рабочий не является наёмным водителем"));
    mvc.perform(get(PATH + "/contractors").with(userJwt("rwms.read", "VIEW", WAREHOUSE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  @Test
  void editorDeletesUnusedContractorUnderExpectedVersion() throws Exception {
    createContractor(WAREHOUSE, CONTRACTOR, "Удаляемый подрядчик");

    mvc.perform(
            delete(PATH + "/contractors/" + CONTRACTOR)
                .queryParam("expectedVersion", "0")
                .with(userJwt("rwms.write", "EDIT", WAREHOUSE)))
        .andExpect(status().isNoContent());

    mvc.perform(get(PATH + "/contractors").with(userJwt("rwms.read", "VIEW", WAREHOUSE)))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.length()").value(0));
  }

  private void createContractor(UUID warehouseId, UUID workerId, String displayName)
      throws Exception {
    mvc.perform(
            post("/api/warehouses/" + warehouseId + "/logistics-drivers/contractors")
                .with(userJwt("rwms.write", "EDIT", warehouseId))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "contractorId":"%s",
                      "displayName":"%s",
                      "phone":"+7 900 000-00-00",
                      "comment":"Каталог наёмных водителей"
                    }
                    """
                        .formatted(workerId, displayName)))
        .andExpect(status().isCreated());
  }

  private static String updateBody(long expectedVersion, String displayName, boolean active) {
    return """
        {
          "expectedVersion":%d,
          "displayName":"%s",
          "phone":"+7 900 999-00-00",
          "comment":"Обновлён диспетчером",
          "active":%s
        }
        """
        .formatted(expectedVersion, displayName, active);
  }

  private static JwtRequestPostProcessor userJwt(
      String scope, String level, UUID grantedWarehouseId) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject("warehouse-logistics-user")
                    .claim("principal_type", "USER")
                    .claim("global_role", "WAREHOUSE_MANAGER")
                    .claim("scope", scope)
                    .claim(
                        "warehouse_access",
                        List.of(
                            Map.of(
                                "warehouseId", grantedWarehouseId.toString(), "level", level))));
  }
}
