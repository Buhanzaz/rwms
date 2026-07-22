package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.service.LogisticsDocumentService;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.return-registration.relay-enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class ReturnRegistrationApiIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000601");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000602");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired LogisticsDocumentService documents;
  @Autowired JdbcTemplate jdbc;

  @BeforeEach
  void reset() {
    jdbc.execute(
        """
        truncate table
          logistics_document,
          event_stream_head,
          domain_event,
          aggregate_snapshot,
          projection_checkpoint,
          outbox_event
        cascade
        """);
  }

  @Test
  void acceptsAUserScopedVersionedReturnRegistrationCommand() throws Exception {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(new ReturnLineRequest(UUID.randomUUID(), 0, "Tenant snapshot"))));
    UUID idempotencyKey = UUID.randomUUID();

    mvc.perform(
            post("/api/logistics/v1/returns/{documentId}/register", created.response().id())
                .param("expectedVersion", "0")
                .header("Idempotency-Key", idempotencyKey)
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    """
                    {
                      "driverSnapshot": "Водитель возврата",
                      "scheduledAt": "2026-07-22T08:00:00Z"
                    }
                    """)
                .with(
                    jwt()
                        .jwt(
                            jwt ->
                                jwt.subject(SUBJECT.toString())
                                    .claim("principal_type", "USER")
                                    .claim("scope", "rwms.write")
                                    .claim(
                                        "warehouse_access",
                                        List.of(
                                            Map.of(
                                                "warehouseId",
                                                WAREHOUSE.toString(),
                                                "level",
                                                "EDIT"))))))
        .andExpect(status().isAccepted())
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.id").value(created.response().id().toString()))
        .andExpect(jsonPath("$.version").value(1))
        .andExpect(jsonPath("$.state").value("REGISTERING"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_external_attempt where operation_type='RETURN_WAREHOUSE_IDENTITY'",
                Long.class))
        .isOne();
  }

  @Test
  void rejectsUnauthenticatedReturnRegistration() throws Exception {
    mvc.perform(
            post("/api/logistics/v1/returns/{documentId}/register", UUID.randomUUID())
                .param("expectedVersion", "0")
                .header("Idempotency-Key", UUID.randomUUID()))
        .andExpect(status().isUnauthorized());
  }
}
