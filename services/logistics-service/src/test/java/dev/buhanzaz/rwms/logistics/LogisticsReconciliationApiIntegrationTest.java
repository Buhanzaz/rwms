package dev.buhanzaz.rwms.logistics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.CreateReturnRequest;
import dev.buhanzaz.rwms.logistics.api.LogisticsApiModels.ReturnLineRequest;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.domain.LogisticsDocumentType;
import dev.buhanzaz.rwms.logistics.repository.LogisticsDocumentRepository;
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
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "AUTH_ISSUER=http://auth.test",
      "PANEL_ORIGIN=http://panel.test"
    })
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Testcontainers
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsReconciliationApiIntegrationTest {
  private static final UUID WAREHOUSE = UUID.fromString("00000000-0000-0000-0000-000000000821");
  private static final UUID SUBJECT = UUID.fromString("00000000-0000-0000-0000-000000000822");

  @Container
  @ServiceConnection
  static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @Autowired MockMvc mvc;
  @Autowired LogisticsDocumentService documents;
  @Autowired LogisticsDocumentRepository documentRepository;
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
  void recordsAnIdempotentManageScopedReconciliationRequestWithoutClaimingAReverseEffect()
      throws Exception {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(new ReturnLineRequest(UUID.randomUUID(), 0, "Tenant snapshot"))));
    LogisticsDocument document =
        documentRepository
            .findByIdAndDocumentType(created.response().id(), LogisticsDocumentType.RETURN)
            .orElseThrow();
    document.requireReconciliation();
    document = documentRepository.saveAndFlush(document);
    UUID key = UUID.randomUUID();

    mvc.perform(
            post("/api/logistics/v1/returns/{documentId}/reconcile", document.getId())
                .param("expectedVersion", Long.toString(document.getVersion()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Need canonical source audit\"}")
                .with(manageJwt()))
        .andExpect(status().isAccepted())
        .andExpect(header().string("ETag", "\"1\""))
        .andExpect(jsonPath("$.state").value("RECONCILIATION_REQUIRED"));

    mvc.perform(
            post("/api/logistics/v1/returns/{documentId}/reconcile", document.getId())
                .param("expectedVersion", Long.toString(document.getVersion()))
                .header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Need canonical source audit\"}")
                .with(manageJwt()))
        .andExpect(status().isAccepted())
        .andExpect(header().string("Idempotency-Replayed", "true"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_reconciliation_request where document_id=?",
                Long.class,
                document.getId()))
        .isOne();
    assertThat(
            jdbc.queryForObject(
                "select state from logistics_document where id=?", String.class, document.getId()))
        .isEqualTo("RECONCILIATION_REQUIRED");
  }

  @Test
  void rejectsAnEditOnlyReconciliationRequestWithACanonicalForbiddenProblem() throws Exception {
    LogisticsDocumentService.CreateResult created =
        documents.createReturn(
            SUBJECT,
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateReturnRequest(
                WAREHOUSE,
                List.of(new ReturnLineRequest(UUID.randomUUID(), 0, "Tenant snapshot"))));
    LogisticsDocument document =
        documentRepository
            .findByIdAndDocumentType(created.response().id(), LogisticsDocumentType.RETURN)
            .orElseThrow();
    document.requireReconciliation();
    document = documentRepository.saveAndFlush(document);

    mvc.perform(
            post("/api/logistics/v1/returns/{documentId}/reconcile", document.getId())
                .param("expectedVersion", Long.toString(document.getVersion()))
                .header("Idempotency-Key", UUID.randomUUID())
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"reason\":\"Need canonical source audit\"}")
                .with(editJwt()))
        .andExpect(status().isForbidden())
        .andExpect(jsonPath("$.code").value("LOGISTICS_FORBIDDEN"));

    assertThat(
            jdbc.queryForObject(
                "select count(*) from logistics_reconciliation_request where document_id=?",
                Long.class,
                document.getId()))
        .isZero();
  }

  private static RequestPostProcessor manageJwt() {
    return warehouseJwt("MANAGE");
  }

  private static RequestPostProcessor editJwt() {
    return warehouseJwt("EDIT");
  }

  private static RequestPostProcessor warehouseJwt(String level) {
    return jwt()
        .jwt(
            value ->
                value
                    .subject(SUBJECT.toString())
                    .claim("principal_type", "USER")
                    .claim("scope", "rwms.write")
                    .claim(
                        "warehouse_access",
                        List.of(Map.of("warehouseId", WAREHOUSE.toString(), "level", level))));
  }
}
