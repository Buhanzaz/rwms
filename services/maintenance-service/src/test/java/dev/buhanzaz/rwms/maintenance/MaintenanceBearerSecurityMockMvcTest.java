package dev.buhanzaz.rwms.maintenance;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.request;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.maintenance.api.MaintenanceCatalogController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceEstimateController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceInventoryController;
import dev.buhanzaz.rwms.maintenance.api.MaintenanceRepairController;
import dev.buhanzaz.rwms.maintenance.config.MaintenanceSecurityProblemWriter;
import dev.buhanzaz.rwms.maintenance.config.SecurityConfiguration;
import dev.buhanzaz.rwms.maintenance.security.MaintenanceAuthorizer;
import dev.buhanzaz.rwms.maintenance.service.InventoryMaintenanceService;
import dev.buhanzaz.rwms.maintenance.service.MaintenanceApplicationService;
import dev.buhanzaz.rwms.platform.web.CorrelationIdFilter;
import dev.buhanzaz.rwms.platform.web.RwmsProblemDetailFactory;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

@WebMvcTest(
    controllers = {
      MaintenanceCatalogController.class,
      MaintenanceEstimateController.class,
      MaintenanceInventoryController.class,
      MaintenanceRepairController.class
    },
    properties = {
      "rwms.cors.allowed-origins=http://localhost:5173",
      "rwms.maintenance.security.dev-auth-bypass=false",
      "spring.security.oauth2.resourceserver.jwt.issuer-uri=https://issuer.example.test",
      "spring.security.oauth2.resourceserver.jwt.audiences=rwms-services"
    })
@Import({
  SecurityConfiguration.class,
  MaintenanceSecurityProblemWriter.class,
  MaintenanceBearerSecurityMockMvcTest.TechnicalWebBeans.class
})
class MaintenanceBearerSecurityMockMvcTest {
  private static final UUID ID = UUID.fromString("10000000-0000-0000-0000-000000000006");

  @Autowired MockMvc mvc;

  @MockitoBean MaintenanceApplicationService service;
  @MockitoBean InventoryMaintenanceService inventoryService;
  @MockitoBean JwtDecoder jwtDecoder;

  @Test
  void everyCanonicalOperationFailsClosedWithoutABearerToken() throws Exception {
    for (MaintenanceOpenApiParityTest.OperationSpec operation
        : MaintenanceOpenApiParityTest.canonicalOperations()) {
      mvc.perform(request(
              HttpMethod.valueOf(operation.httpMethod()),
              operation.path()
                  .replace("{id}", ID.toString())
                  .replace("{inventoryId}", ID.toString())
                  .replace("{findingId}", ID.toString()))
              .header(CorrelationIdFilter.HEADER_NAME, ID.toString()))
          .andExpect(status().isUnauthorized())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(header().string(CorrelationIdFilter.HEADER_NAME, ID.toString()))
          .andExpect(jsonPath("$.type").value(
              "urn:rwms:problem:maintenance:maintenance-unauthorized"))
          .andExpect(jsonPath("$.title").value("Unauthorized"))
          .andExpect(jsonPath("$.status").value(401))
          .andExpect(jsonPath("$.code").value("MAINTENANCE_UNAUTHORIZED"))
          .andExpect(jsonPath("$.violations").isArray())
          .andExpect(jsonPath("$.correlation.correlationId").value(ID.toString()));
    }
  }

  @Test
  void privateInventoryBoundaryRejectsWrongSubjectAndCombinedScopeAtHttpLevel()
      throws Exception {
    String body = """
        {
          "warehouseId":"10000000-0000-0000-0000-000000000006",
          "inventoryId":"10000000-0000-0000-0000-000000000006",
          "findingId":"10000000-0000-0000-0000-000000000006",
          "sourceRevision":1,
          "mode":"AUTO",
          "lines":[{
            "aggregationKind":"CATALOG",
            "catalogNodeId":"10000000-0000-0000-0000-000000000006",
            "description":null,
            "type":null,
            "unit":null,
            "quantity":"1",
            "unitPriceMinor":null,
            "normativeMinutes":null,
            "groupComment":null,
            "mediaReferences":[]
          }],
          "plan":[],
          "mediaReferences":[]
        }
        """;

    for (var rejected : java.util.List.of(
        jwt().jwt(token -> token
            .subject("other-service")
            .audience(java.util.List.of("rwms-services"))
            .claim("client_id", "inventory-service")
            .claim("principal_type", "SERVICE")
            .claim("scope", "maintenance.inventory")),
        jwt().jwt(token -> token
            .subject("inventory-service")
            .audience(java.util.List.of("rwms-services"))
            .claim("client_id", "inventory-service")
            .claim("principal_type", "SERVICE")
            .claim("scope", "maintenance.inventory asset.inventory")))) {
      mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders
              .post("/api/internal/maintenance/v1/inventory/plans")
              .with(rejected)
              .contentType(MediaType.APPLICATION_JSON)
              .content(body))
          .andExpect(status().isForbidden())
          .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
          .andExpect(jsonPath("$.code").value("MAINTENANCE_FORBIDDEN"));
    }
  }

  @TestConfiguration(proxyBeanMethods = false)
  @EnableWebSecurity
  static class TechnicalWebBeans {
    @Bean
    CorrelationIdFilter correlationIdFilter() {
      return new CorrelationIdFilter();
    }

    @Bean
    RwmsProblemDetailFactory problemDetailFactory() {
      return new RwmsProblemDetailFactory();
    }

    @Bean
    MaintenanceAuthorizer maintenanceAuthorizer(Environment environment) {
      return new MaintenanceAuthorizer(environment, false);
    }
  }
}
