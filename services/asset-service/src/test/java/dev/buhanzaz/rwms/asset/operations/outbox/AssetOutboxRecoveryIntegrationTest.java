package dev.buhanzaz.rwms.asset.operations.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import dev.buhanzaz.rwms.asset.service.AssetConflictException;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(properties = {
    "spring.jpa.hibernate.ddl-auto=validate",
    "rwms.platform.kafka.enabled=false",
    "rwms.asset.warehouse-registry.enabled=false",
    "spring.cloud.function.definition=",
    "spring.task.scheduling.enabled=false"
})
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
class AssetOutboxRecoveryIntegrationTest {
  @Container static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add("spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @Autowired AssetOutboxRecoveryService recovery;
  @Autowired JdbcTemplate jdbc;
  @Autowired ObjectMapper mapper;

  @Test
  void atomicallyRequeuesVerifiedDltPersistsImmutableAuditAndRetriesExactlyOnce() throws Exception {
    UUID aggregateId = UUID.randomUUID();
    UUID eventId = UUID.randomUUID();
    UUID reviewer = UUID.randomUUID();
    insertStream(aggregateId, 0, eventId);
    insertOutbox(eventId, aggregateId, 0, "DLT");

    AssetOutboxRequeueResponse first = recovery.requeue(eventId, 0L, reviewer, "  manual verification  ");
    AssetOutboxRequeueResponse retry = recovery.requeue(eventId, 0L, reviewer, "manual verification");

    assertThat(first.eventId()).isEqualTo(eventId);
    assertThat(first.reviewVersion()).isEqualTo(1);
    assertThat(first.status()).isEqualTo("PENDING");
    assertThat(first.attemptCount()).isZero();
    assertThat(first.lastErrorCode()).isNull();
    assertThat(first.reviewedAt()).isNotNull();
    assertThat(retry).isEqualTo(first);
    assertThat(jdbc.queryForMap("""
        select status,attempt_count,dlt_at,last_error_code,review_version,reviewed_at
        from outbox_event where event_id=?
        """, eventId))
        .containsEntry("status", "PENDING")
        .containsEntry("attempt_count", 0)
        .containsEntry("dlt_at", null)
        .containsEntry("last_error_code", null)
        .containsEntry("review_version", 1L);
    assertThat(jdbc.queryForMap("""
        select expected_review_version,review_version,prior_status,prior_last_error_code,
          prior_attempt_count,reviewer_subject_id,review_reason
        from asset_outbox_recovery_review where event_id=?
        """, eventId))
        .containsEntry("expected_review_version", 0L)
        .containsEntry("review_version", 1L)
        .containsEntry("prior_status", "DLT")
        .containsEntry("prior_last_error_code", "PUBLISH_FAILED")
        .containsEntry("prior_attempt_count", 4)
        .containsEntry("reviewer_subject_id", reviewer)
        .containsEntry("review_reason", "manual verification");
    assertThat(jdbc.queryForObject(
        "select count(*) from asset_outbox_recovery_review where event_id=?", Integer.class, eventId)).isEqualTo(1);
    assertThatThrownBy(() -> jdbc.update(
        "update asset_outbox_recovery_review set review_reason='changed' where event_id=?", eventId))
        .isInstanceOf(DataAccessException.class);
  }

  @Test
  void preservesAggregateOrderingWhenAnEarlierFactIsNotPublished() throws Exception {
    UUID aggregateId = UUID.randomUUID();
    UUID predecessor = UUID.randomUUID();
    UUID terminal = UUID.randomUUID();
    insertStream(aggregateId, 1, terminal);
    insertOutbox(predecessor, aggregateId, 0, "PENDING");
    insertOutbox(terminal, aggregateId, 1, "QUARANTINED");

    assertThatThrownBy(() -> recovery.requeue(terminal, 0L, UUID.randomUUID(), "wait for predecessor"))
        .isInstanceOf(AssetConflictException.class)
        .hasMessageContaining("first unpublished");
    assertThat(jdbc.queryForObject("select status from outbox_event where event_id=?", String.class, terminal))
        .isEqualTo("QUARANTINED");
    assertThat(jdbc.queryForObject(
        "select count(*) from asset_outbox_recovery_review where event_id=?", Integer.class, terminal)).isZero();
  }

  private void insertStream(UUID aggregateId, long currentVersion, UUID lastEventId) {
    jdbc.update("""
        insert into event_stream_head(aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('RENTAL_ITEM',?,?,?,clock_timestamp())
        """, aggregateId.toString(), currentVersion, lastEventId);
  }

  private void insertOutbox(UUID eventId, UUID aggregateId, long aggregateVersion, String status) throws Exception {
    String payload = mapper.writeValueAsString(Map.of(
        "rentalItemId", aggregateId.toString(),
        "warehouseId", UUID.randomUUID().toString(),
        "status", "FREE",
        "numberSha256", "a".repeat(64)));
    jdbc.update("""
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,occurred_at,
          recorded_at,correlation_id,causation_id,actor_ref,payload,payload_sha256,baseline)
        values (?,'RENTAL_ITEM',?,?, 'asset.rental-item.created.v1',1,clock_timestamp(),clock_timestamp(),
          ?,null,null,?::jsonb,?,false)
        """, eventId, aggregateId.toString(), aggregateVersion, UUID.randomUUID(), payload,
        AssetChecksum.sha256(payload.getBytes(StandardCharsets.UTF_8)));
    String body = canonicalEnvelope(eventId, aggregateId, aggregateVersion);
    boolean dlt = "DLT".equals(status);
    jdbc.update("""
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,envelope_sha256,
          status,attempt_count,next_attempt_at,dlt_at,last_error_code,created_at)
        values (?,'RENTAL_ITEM',?,?, 'asset.rental-item.created.v1',?,?::jsonb,?, ?,4,clock_timestamp(),
          case when ? then clock_timestamp() else null end,
          case when ? then 'PUBLISH_FAILED' else 'VALIDATION_REJECTED' end,clock_timestamp())
        """, eventId, aggregateId.toString(), aggregateVersion, AssetAggregateType.RENTAL_ITEM.topic(), body,
        AssetChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)), status, dlt, dlt);
  }

  private String canonicalEnvelope(UUID eventId, UUID aggregateId, long aggregateVersion) throws Exception {
    Instant recordedAt = Instant.parse("2026-08-05T10:00:00Z");
    DomainEventEnvelopeV2<Map<String, Object>> envelope = new DomainEventEnvelopeV2<>(
        2,
        eventId,
        "asset.rental-item.created.v1",
        1,
        recordedAt,
        recordedAt,
        "asset-service",
        AssetAggregateType.RENTAL_ITEM.name(),
        aggregateId.toString(),
        aggregateVersion,
        new CorrelationContext(UUID.randomUUID(), null),
        null,
        Map.of(
            "rentalItemId", aggregateId.toString(),
            "warehouseId", UUID.randomUUID().toString(),
            "status", "FREE",
            "numberSha256", "a".repeat(64)));
    ObjectNode root = (ObjectNode) mapper.valueToTree(envelope);
    root.putNull("actorRef");
    ((ObjectNode) root.get("correlation")).putNull("causationId");
    String encoded = mapper.writeValueAsString(root);
    return jdbc.queryForObject("select (?::jsonb)::text", String.class, encoded);
  }
}
