package dev.buhanzaz.rwms.taskboard;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
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
  private static final String PATH = "/api/warehouses/" + WAREHOUSE + "/logistics-drivers";

  @Autowired MockMvc mvc;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void setUp() {
    cleanTaskBoardFixtures(jdbc);
  }

  @Test
  void editorCreatesContractorAndViewerReadsOnlyItsAvailabilityWindow() throws Exception {
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
                      "availableFrom":"2030-05-12T08:00:00+03:00",
                      "availableUntil":"2030-05-12T20:00:00+03:00",
                      "comment":"Рейс Великого Новгорода"
                    }
                    """
                        .formatted(CONTRACTOR)))
        .andExpect(status().isCreated())
        .andExpect(jsonPath("$.workerId").value(CONTRACTOR.toString()))
        .andExpect(jsonPath("$.homeWarehouseId").value(WAREHOUSE.toString()))
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
        .andExpect(jsonPath("$[0]").doesNotExist());
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
                      "availableFrom":"2030-05-12T08:00:00+03:00",
                      "availableUntil":"2030-05-12T20:00:00+03:00",
                      "comment":null
                    }
                    """
                        .formatted(CONTRACTOR)))
        .andExpect(status().isForbidden());
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
