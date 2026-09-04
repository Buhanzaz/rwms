package dev.buhanzaz.rwms.taskboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest(properties = "rwms.security.dev-auth-bypass=true")
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class TaskBoardDevSecurityIntegrationTest extends PostgresIntegrationTestSupport {
  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;

  @Test
  void permitsContentApiWithoutBearerTokenOnlyInDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/worker-classes")).andExpect(status().isOk());
  }

  @Test
  void servesGlobalKpiConfigurationOnCanonicalServicePaths() throws Exception {
    mockMvc.perform(get("/api/kpi-palette")).andExpect(status().isOk());
    mockMvc.perform(get("/api/kpi-settings")).andExpect(status().isOk());
    mockMvc.perform(get("/api/task-board/kpi-palette")).andExpect(status().isNotFound());
    mockMvc.perform(get("/api/task-board/kpi-settings")).andExpect(status().isNotFound());
  }

  @Test
  void keepsInternalApiAuthenticatedDuringDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/internal/work-queues")).andExpect(status().isUnauthorized());
  }
}
