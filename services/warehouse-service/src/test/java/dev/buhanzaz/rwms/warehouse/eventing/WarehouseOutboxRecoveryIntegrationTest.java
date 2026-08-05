package dev.buhanzaz.rwms.warehouse.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import dev.buhanzaz.rwms.warehouse.WarehouseServiceApplication;
import dev.buhanzaz.rwms.warehouse.api.CreateWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.ReplaceWarehouseRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseOutboxRecoveryApiModels.WarehouseOutboxRecoveryRequest;
import dev.buhanzaz.rwms.warehouse.api.WarehouseResponse;
import dev.buhanzaz.rwms.warehouse.service.WarehouseChecksum;
import dev.buhanzaz.rwms.warehouse.service.WarehouseConflictException;
import dev.buhanzaz.rwms.warehouse.service.WarehouseService;
import java.time.OffsetDateTime;
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
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(classes = WarehouseServiceApplication.class)
@ActiveProfiles({"dev", "test"})
@AutoConfigureMockMvc
class WarehouseOutboxRecoveryIntegrationTest {
  private static final String RECOVERY_PATH = "/api/warehouse/v1/admin/outbox-events/{eventId}/recovery";
  private static final UUID SPB = UUID.fromString("00000000-0000-0000-0000-000000000001");
  private static final UUID MSK = UUID.fromString("00000000-0000-0000-0000-000000000002");
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  static {
    POSTGRES.start();
  }

  @Autowired WarehouseService warehouses;
  @Autowired WarehouseOutboxRecoveryService recovery;
  @Autowired WarehouseOutboxEnvelopeValidator envelopeValidator;
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
    jdbc.execute("truncate table warehouse_outbox_recovery_audit");
    jdbc.update("delete from idempotency_record");
    jdbc.update("delete from outbox_event");
    jdbc.update("delete from warehouse where id not in (?, ?)", SPB, MSK);
  }

  @Test
  void reviewedRecoveryRequeuesOnlyOnceAndWritesAppendOnlyAudit() {
    WarehouseResponse created = create("Reviewed recovery");
    UUID eventId = eventId(created.id(), created.version());
    terminal(eventId, "DLT", 4, "PUBLISH_FAILED");
    UUID reviewer = UUID.randomUUID();
    String reason = "Kafka broker restored";
    String immutableEnvelope = envelope(eventId).envelopeBody();

    WarehouseOutboxRecoveryStore.RecoveryResult recovered =
        recovery.recover(eventId, 0, reviewer, reason);

    assertThat(recovered.replayed()).isFalse();
    assertThat(recovered.status()).isEqualTo("PENDING");
    assertThat(recovered.attemptCount()).isZero();
    assertThat(recovered.reviewVersion()).isEqualTo(1);
    assertThat(recovered.lastErrorCode()).isEqualTo("PUBLISH_FAILED");
    assertThat(envelope(eventId).envelopeBody()).isEqualTo(immutableEnvelope);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_outbox_recovery_audit where event_id=?", Integer.class, eventId))
        .isEqualTo(1);
    assertThat(
            jdbc.queryForObject(
                "select prior_status || '|' || prior_attempt_count || '|' || prior_last_error_code"
                    + " from warehouse_outbox_recovery_audit where event_id=?",
                String.class,
                eventId))
        .isEqualTo("DLT|4|PUBLISH_FAILED");

    WarehouseOutboxRecoveryStore.RecoveryResult replayed =
        recovery.recover(eventId, 0, reviewer, "  Kafka broker restored  ");
    assertThat(replayed.replayed()).isTrue();
    assertThat(replayed.recoveredAt()).isEqualTo(recovered.recoveredAt());
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_outbox_recovery_audit where event_id=?", Integer.class, eventId))
        .isEqualTo(1);
    assertThatThrownBy(() -> recovery.recover(eventId, 0, reviewer, "Different reason"))
        .isInstanceOf(WarehouseConflictException.class);
    assertThatThrownBy(
            () ->
                jdbc.update(
                    "update warehouse_outbox_recovery_audit set reason='edited' where event_id=?", eventId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
    assertThatThrownBy(
            () -> jdbc.update("delete from warehouse_outbox_recovery_audit where event_id=?", eventId))
        .isInstanceOf(RuntimeException.class)
        .hasMessageContaining("append-only");
  }

  @Test
  void recoveryRequiresTheCurrentFirstUnpublishedAggregateHead() {
    WarehouseResponse created = create("Head fence");
    WarehouseResponse changed =
        warehouses.replace(
            created.id(),
            new ReplaceWarehouseRequest(
                created.version(),
                "Head fence changed",
                created.city(),
                created.address(),
                created.timeZone(),
                created.sortOrder()));
    UUID firstEventId = eventId(created.id(), created.version());
    UUID secondEventId = eventId(changed.id(), changed.version());
    terminal(firstEventId, "DLT", 4, "PUBLISH_FAILED");
    terminal(secondEventId, "QUARANTINED", 1, "ENVELOPE_MISMATCH");

    assertThatThrownBy(
            () -> recovery.recover(secondEventId, 0, UUID.randomUUID(), "Try to skip the head"))
        .isInstanceOf(WarehouseConflictException.class)
        .hasMessageContaining("first unpublished");
    assertThat(
            jdbc.queryForObject("select status from outbox_event where event_id=?", String.class, secondEventId))
        .isEqualTo("QUARANTINED");
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_outbox_recovery_audit where event_id=?",
                Integer.class,
                secondEventId))
        .isZero();
  }

  @Test
  void recoveryRevalidatesChecksumAndSchemaBeforeChangingTerminalState() throws Exception {
    WarehouseResponse created = create("Envelope validation");
    WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope valid =
        envelope(eventId(created.id(), created.version()));
    envelopeValidator.validateOrThrow(valid);

    ObjectNode malformed = (ObjectNode) objectMapper.readTree(valid.envelopeBody());
    malformed.put("unapproved", true);
    String malformedBody =
        jdbc.queryForObject(
            "select (?::jsonb)::text", String.class, objectMapper.writeValueAsString(malformed));
    WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope schemaMismatch =
        new WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope(
            valid.eventId(),
            valid.aggregateType(),
            valid.aggregateId(),
            valid.aggregateVersion(),
            valid.eventType(),
            valid.eventVersion(),
            valid.topic(),
            valid.occurredAt(),
            valid.recordedAt(),
            malformedBody,
            WarehouseChecksum.sha256(malformedBody.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
    WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope checksumMismatch =
        new WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope(
            valid.eventId(),
            valid.aggregateType(),
            valid.aggregateId(),
            valid.aggregateVersion(),
            valid.eventType(),
            valid.eventVersion(),
            valid.topic(),
            valid.occurredAt(),
            valid.recordedAt(),
            valid.envelopeBody(),
            "0".repeat(64));

    assertThatThrownBy(() -> envelopeValidator.validateOrThrow(schemaMismatch))
        .isInstanceOf(WarehouseConflictException.class);
    assertThatThrownBy(() -> envelopeValidator.validateOrThrow(checksumMismatch))
        .isInstanceOf(WarehouseConflictException.class);
  }

  @Test
  void publicBoundaryAcceptsBothGlobalAdministratorRolesAndFencesConflictingRetries()
      throws Exception {
    WarehouseResponse created = create("Controller recovery");
    UUID eventId = eventId(created.id(), created.version());
    terminal(eventId, "QUARANTINED", 1, "ENVELOPE_MISMATCH");
    UUID reviewer = UUID.randomUUID();
    String request =
        objectMapper.writeValueAsString(new WarehouseOutboxRecoveryRequest(0L, "Reviewed by WMS"));

    mockMvc
        .perform(
            post(RECOVERY_PATH, eventId)
                .with(recoveryJwt(reviewer, "WMS_ADMIN", "rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(jsonPath("$.status").value("PENDING"))
        .andExpect(jsonPath("$.reviewVersion").value(1))
        .andExpect(jsonPath("$.reviewedBySubjectId").value(reviewer.toString()));
    mockMvc
        .perform(
            post(RECOVERY_PATH, eventId)
                .with(recoveryJwt(reviewer, "WMS_ADMIN", "rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isOk())
        .andExpect(header().string("Idempotency-Replayed", "true"));
    mockMvc
        .perform(
            post(RECOVERY_PATH, eventId)
                .with(recoveryJwt(reviewer, "WMS_ADMIN", "rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(
                    objectMapper.writeValueAsString(
                        new WarehouseOutboxRecoveryRequest(0L, "Conflicting review"))))
        .andExpect(status().isConflict());
    mockMvc
        .perform(
            post(RECOVERY_PATH, eventId)
                .with(recoveryJwt(UUID.randomUUID(), "WAREHOUSE_MANAGER", "rwms.write"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isForbidden());
    mockMvc
        .perform(
            post(RECOVERY_PATH, eventId)
                .with(recoveryJwt(UUID.randomUUID(), "SYSTEM_ADMIN", "rwms.write warehouse.read"))
                .contentType(MediaType.APPLICATION_JSON)
                .content(request))
        .andExpect(status().isForbidden());
  }

  private WarehouseResponse create(String name) {
    return warehouses
        .create(
            UUID.randomUUID(),
            UUID.randomUUID(),
            new CreateWarehouseRequest(name, "Москва", null, "Europe/Moscow", null))
        .response();
  }

  private UUID eventId(UUID warehouseId, long aggregateVersion) {
    return jdbc.queryForObject(
        "select event_id from outbox_event where aggregate_id=? and aggregate_version=?",
        UUID.class,
        warehouseId.toString(),
        aggregateVersion);
  }

  private void terminal(UUID eventId, String status, int attempts, String errorCode) {
    if ("DLT".equals(status)) {
      jdbc.update(
          """
          update outbox_event
             set status='DLT',attempt_count=?,dlt_at=clock_timestamp(),last_error_code=?
           where event_id=?
          """,
          attempts,
          errorCode,
          eventId);
      return;
    }
    jdbc.update(
        """
        update outbox_event
           set status='QUARANTINED',attempt_count=?,dlt_at=null,last_error_code=?
         where event_id=?
        """,
        attempts,
        errorCode,
        eventId);
  }

  private WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope envelope(UUID eventId) {
    return jdbc.queryForObject(
        """
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,topic,
               occurred_at,recorded_at,envelope_body::text,envelope_sha256
          from outbox_event where event_id=?
        """,
        (rs, ignored) ->
            new WarehouseOutboxEnvelopeValidator.ImmutableOutboxEnvelope(
                rs.getObject("event_id", UUID.class),
                rs.getString("aggregate_type"),
                rs.getString("aggregate_id"),
                rs.getLong("aggregate_version"),
                rs.getString("event_type"),
                rs.getInt("event_version"),
                rs.getString("topic"),
                rs.getObject("occurred_at", OffsetDateTime.class),
                rs.getObject("recorded_at", OffsetDateTime.class),
                rs.getString("envelope_body"),
                rs.getString("envelope_sha256")),
        eventId);
  }

  private static org.springframework.security.test.web.servlet.request
          .SecurityMockMvcRequestPostProcessors.JwtRequestPostProcessor
      recoveryJwt(UUID subjectId, String role, String scope) {
    return jwt()
        .jwt(
            token ->
                token
                    .subject(subjectId.toString())
                    .claim("principal_type", "USER")
                    .claim("global_role", role)
                    .claim("scope", scope));
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }
}
