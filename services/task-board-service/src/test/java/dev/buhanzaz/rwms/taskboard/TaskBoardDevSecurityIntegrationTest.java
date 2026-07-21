package dev.buhanzaz.rwms.taskboard;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

@SpringBootTest
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class TaskBoardDevSecurityIntegrationTest extends PostgresIntegrationTestSupport {
  @org.springframework.beans.factory.annotation.Autowired MockMvc mockMvc;

  @Test
  void permitsContentApiWithoutBearerTokenOnlyInDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/worker-classes")).andExpect(status().isOk());
  }

  @Test
  void keepsInternalApiAuthenticatedDuringDevelopmentBypass() throws Exception {
    mockMvc.perform(get("/api/internal/work-queues")).andExpect(status().isUnauthorized());
  }
}
