package dev.buhanzaz.rwms.inventory.observability;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.inventory.InventoryServiceApplication;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

@SpringBootTest(
    classes = InventoryServiceApplication.class,
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "rwms.platform.kafka.enabled=false",
      "rwms.inventory.dependencies.enabled=false"
    })
@ActiveProfiles("test")
class InventoryOperationalMetricsIntegrationTest {
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:17-alpine");
  private static final String HASH_A = "a".repeat(64);
  private static final String HASH_B = "b".repeat(64);
  private static final String HASH_C = "c".repeat(64);
  private static final String ACTOR =
      "{\"subjectId\":\"00000000-0000-0000-0000-000000000701\","
          + "\"principalType\":\"USER\",\"profileRevision\":null}";
  private static final List<String> METRICS =
      List.of(
          "rwms.inventory.outbox.backlog",
          "rwms.inventory.outbox.oldest.age.seconds",
          "rwms.inventory.inbox.retry.current",
          "rwms.inventory.inbox.quarantined.current",
          "rwms.inventory.inbox.dlt.current",
          "rwms.inventory.version_gap.open",
          "rwms.inventory.publication.pending.current",
          "rwms.inventory.publication.in_flight.current",
          "rwms.inventory.publication.failed.current",
          "rwms.inventory.publication.oldest.unresolved.age.seconds",
          "rwms.inventory.furniture.reconciliation.unresolved.current",
          "rwms.inventory.furniture.reconciliation.failed.current",
          "rwms.inventory.furniture.reconciliation.oldest.unresolved.age.seconds",
          "rwms.inventory.furniture.loss.unresolved.current",
          "rwms.inventory.furniture.loss.failed.current",
          "rwms.inventory.furniture.loss.oldest.unresolved.age.seconds",
          "rwms.inventory.dlt.backlog");

  static {
    POSTGRES.start();
  }

  @Autowired MeterRegistry registry;
  @Autowired JdbcTemplate jdbc;

  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.issuer-uri", () -> "http://issuer.invalid");
    registry.add("rwms.cors.allowed-origins", () -> "http://localhost:5173");
  }

  @AfterAll
  static void stopDatabase() {
    POSTGRES.stop();
  }

  @BeforeEach
  void clearRows() {
    jdbc.execute(
        """
        truncate table inventory_session, domain_event, inbox_message, version_gap_quarantine,
          sanitized_dead_letter restart identity cascade
        """);
  }

  @Test
  void exposesEveryOperationalGaugeAtZeroWithoutIdentifierTags() {
    for (String metricName : METRICS) {
      Gauge gauge = gauge(metricName);
      assertThat(gauge.value()).as(metricName).isZero();
      assertThat(gauge.getId().getTags())
          .extracting(Tag::getKey)
          .doesNotContain("warehouse", "warehouse_id", "inventory_id", "session_id", "finding_id");
    }
  }

  @Test
  void readsOperationalAlertValuesFromTheAuthoritativeDatabase() {
    insertOutbox("PENDING", 180);
    insertOutbox("IN_FLIGHT", 60);
    insertInbox("RETRY");
    insertInbox("QUARANTINED");
    insertInbox("DLT");
    insertOpenVersionGap();
    insertDeadLetter("PENDING");
    insertDeadLetter("IN_FLIGHT");
    insertDeadLetter("FAILED");

    UUID publicationInventoryId = insertSession();
    insertPublication(publicationInventoryId, "READY", 120);
    insertPublication(publicationInventoryId, "PENDING", 240);
    insertPublication(publicationInventoryId, "TRANSIENT_FAILED", 360);
    insertPublication(publicationInventoryId, "BLOCKED", 480);

    insertFurnitureReconciliation("PENDING", 180);
    insertFurnitureReconciliation("TRANSIENT_FAILED", 300);
    insertFurnitureReconciliation("BLOCKED", 420);
    insertFurnitureLoss("PENDING", 200);
    insertFurnitureLoss("TRANSIENT_FAILED", 320);
    insertFurnitureLoss("BLOCKED", 440);

    assertThat(metric("rwms.inventory.outbox.backlog")).isEqualTo(2.0);
    assertAgeAtLeast("rwms.inventory.outbox.oldest.age.seconds", 175.0);
    assertThat(metric("rwms.inventory.inbox.retry.current")).isEqualTo(1.0);
    assertThat(metric("rwms.inventory.inbox.quarantined.current")).isEqualTo(1.0);
    assertThat(metric("rwms.inventory.inbox.dlt.current")).isEqualTo(1.0);
    assertThat(metric("rwms.inventory.version_gap.open")).isEqualTo(1.0);
    assertThat(metric("rwms.inventory.publication.pending.current")).isEqualTo(3.0);
    assertThat(metric("rwms.inventory.publication.in_flight.current")).isEqualTo(1.0);
    assertThat(metric("rwms.inventory.publication.failed.current")).isEqualTo(2.0);
    assertAgeAtLeast("rwms.inventory.publication.oldest.unresolved.age.seconds", 475.0);
    assertThat(metric("rwms.inventory.furniture.reconciliation.unresolved.current")).isEqualTo(3.0);
    assertThat(metric("rwms.inventory.furniture.reconciliation.failed.current")).isEqualTo(2.0);
    assertAgeAtLeast(
        "rwms.inventory.furniture.reconciliation.oldest.unresolved.age.seconds", 415.0);
    assertThat(metric("rwms.inventory.furniture.loss.unresolved.current")).isEqualTo(3.0);
    assertThat(metric("rwms.inventory.furniture.loss.failed.current")).isEqualTo(2.0);
    assertAgeAtLeast("rwms.inventory.furniture.loss.oldest.unresolved.age.seconds", 435.0);
    assertThat(metric("rwms.inventory.dlt.backlog")).isEqualTo(3.0);
  }

  private void assertAgeAtLeast(String metricName, double seconds) {
    assertThat(metric(metricName)).as(metricName).isGreaterThanOrEqualTo(seconds);
  }

  private double metric(String name) {
    return gauge(name).value();
  }

  private Gauge gauge(String name) {
    Gauge gauge = registry.find(name).gauge();
    assertThat(gauge).as(name).isNotNull();
    return gauge;
  }

  private void insertOutbox(String status, int ageSeconds) {
    UUID eventId = UUID.randomUUID();
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          recorded_at,correlation_id,event_body,event_sha256)
        values (?,'SESSION',?,0,'inventory.session.started.v1',1,
          clock_timestamp()-(? * interval '1 second'),?,?::jsonb,?)
        """,
        eventId,
        aggregateId.toString(),
        ageSeconds,
        UUID.randomUUID(),
        "{}",
        HASH_A);
    if ("IN_FLIGHT".equals(status)) {
      jdbc.update(
          """
          insert into outbox_event(
            event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
            envelope_sha256,status,attempt_count,next_attempt_at,lease_owner,lease_token,lease_until,
            created_at)
          values (?,'SESSION',?,0,'inventory.session.started.v1','rwms.inventory.session.v1',?::jsonb,
            ?,'IN_FLIGHT',0,clock_timestamp(),'metrics-test',?,clock_timestamp()+interval '1 minute',
            clock_timestamp()-(? * interval '1 second'))
          """,
          eventId,
          aggregateId.toString(),
          "{}",
          HASH_A,
          UUID.randomUUID(),
          ageSeconds);
      return;
    }
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
          envelope_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'SESSION',?,0,'inventory.session.started.v1','rwms.inventory.session.v1',?::jsonb,
          ?,'PENDING',0,clock_timestamp(),clock_timestamp()-(? * interval '1 second'))
        """,
        eventId,
        aggregateId.toString(),
        "{}",
        HASH_A,
        ageSeconds);
  }

  private void insertInbox(String status) {
    UUID eventId = UUID.randomUUID();
    String aggregateId = UUID.randomUUID().toString();
    if ("RETRY".equals(status)) {
      jdbc.update(
          """
          insert into inbox_message(
            consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,
            aggregate_version,event_type,payload_sha256,envelope_body,status,attempt_count,
            received_at,next_attempt_at)
          values ('inventory-service-media-inbox-v1',?,'rwms.media.media.v1','MEDIA',?,?,0,
            'media.media.ready.v1',?,?::jsonb,'RETRY',1,clock_timestamp(),clock_timestamp())
          """,
          eventId,
          aggregateId,
          aggregateId,
          HASH_A,
          "{}");
      return;
    }
    if ("QUARANTINED".equals(status)) {
      jdbc.update(
          """
          insert into inbox_message(
            consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,
            aggregate_version,event_type,payload_sha256,envelope_body,status,attempt_count,
            received_at,quarantine_reason)
          values ('inventory-service-media-inbox-v1',?,'rwms.media.media.v1','MEDIA',?,?,0,
            'media.media.ready.v1',?,?::jsonb,'QUARANTINED',1,clock_timestamp(),
            'AGGREGATE_VERSION_GAP')
          """,
          eventId,
          aggregateId,
          aggregateId,
          HASH_A,
          "{}");
      return;
    }
    jdbc.update(
        """
        insert into inbox_message(
          consumer_group,event_id,source_topic,aggregate_type,aggregate_id,record_key,
          aggregate_version,event_type,payload_sha256,envelope_body,status,attempt_count,
          received_at,dlt_at)
        values ('inventory-service-media-inbox-v1',?,'rwms.media.media.v1','MEDIA',?,?,0,
          'media.media.ready.v1',?,?::jsonb,'DLT',3,clock_timestamp(),clock_timestamp())
        """,
        eventId,
        aggregateId,
        aggregateId,
        HASH_A,
        "{}");
  }

  private void insertOpenVersionGap() {
    jdbc.update(
        """
        insert into version_gap_quarantine(
          quarantine_id,consumer_group,aggregate_type,aggregate_id,expected_version,received_version,
          received_event_id,payload_sha256,reason_code,status,detected_at)
        values (?,'inventory-service-media-inbox-v1','MEDIA',?,1,3,?,?,'AGGREGATE_VERSION_GAP',
          'OPEN',clock_timestamp())
        """,
        UUID.randomUUID(),
        UUID.randomUUID().toString(),
        UUID.randomUUID(),
        HASH_A);
  }

  private void insertDeadLetter(String status) {
    String safeBody =
        "{\"failureCode\":\"PROCESSING_FAILED\",\"messageSha256\":\""
            + HASH_A
            + "\",\"recordedAt\":\"2026-01-01T00:00:00Z\"}";
    if ("IN_FLIGHT".equals(status)) {
      jdbc.update(
          """
          insert into sanitized_dead_letter(
            dlt_id,destination,source_topic,source_event_id,message_sha256,failure_code,safe_body,
            body_sha256,status,attempt_count,next_attempt_at,lease_owner,lease_token,lease_until,created_at)
          values (?,'rwms.inventory.dlt.v1','rwms.inventory.session.v1',?,?,?,?::jsonb,?,
            'IN_FLIGHT',1,clock_timestamp(),'metrics-test',?,clock_timestamp()+interval '1 minute',
            clock_timestamp())
          """,
          UUID.randomUUID(),
          UUID.randomUUID(),
          HASH_A,
          "PROCESSING_FAILED",
          safeBody,
          HASH_B,
          UUID.randomUUID());
      return;
    }
    jdbc.update(
        """
        insert into sanitized_dead_letter(
          dlt_id,destination,source_topic,source_event_id,message_sha256,failure_code,safe_body,
          body_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,'rwms.inventory.dlt.v1','rwms.inventory.session.v1',?,?,?,?::jsonb,?,
          ?,1,clock_timestamp(),clock_timestamp())
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        HASH_A,
        "PROCESSING_FAILED",
        safeBody,
        HASH_B,
        status);
  }

  private UUID insertSession() {
    UUID inventoryId = UUID.randomUUID();
    UUID operationId = UUID.randomUUID();
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    jdbc.update(
        """
        insert into inventory_session(
          id,session_revision,warehouse_id,warehouse_version_snapshot,warehouse_time_zone,
          business_date,lifecycle,start_operation_id,start_idempotency_key,start_request_sha256,
          expected_population_count,expected_population_sha256,started_by_subject_id,
          started_by_display_name,started_actor_ref,started_at,created_at,updated_at)
        values (?,0,?,0,'Europe/Moscow',current_date,'ACTIVE',?,?,?,0,?,?,'Inventory operator',
          ?::jsonb,?,?,?)
        """,
        inventoryId,
        UUID.randomUUID(),
        operationId,
        operationId,
        HASH_A,
        HASH_B,
        UUID.randomUUID(),
        ACTOR,
        now,
        now,
        now);
    return inventoryId;
  }

  private void insertPublication(UUID inventoryId, String state, int ageSeconds) {
    UUID findingId = UUID.randomUUID();
    insertFinding(inventoryId, findingId);
    String requestHash = "READY".equals(state) ? null : HASH_A;
    String failureCode = "BLOCKED".equals(state) ? "SOURCE_PRECONDITION_CONFLICT" : null;
    jdbc.update(
        """
        insert into inventory_publication_intent(
          id,inventory_id,finding_id,publication_revision,state,maintenance_source_key,source_revision,
          desired_asset_status,request_sha256,attempt_count,blocked_failure_code,created_at,updated_at)
        values (?,?,?,0,?,?,1,'REPAIR',?,1,?,clock_timestamp()-(? * interval '1 second'),
          clock_timestamp()-(? * interval '1 second'))
        """,
        UUID.randomUUID(),
        inventoryId,
        findingId,
        state,
        inventoryId + ":" + findingId,
        requestHash,
        failureCode,
        ageSeconds,
        ageSeconds);
  }

  private void insertFinding(UUID inventoryId, UUID findingId) {
    OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
    String matchKey = UUID.randomUUID().toString();
    jdbc.update(
        """
        insert into inventory_finding(
          id,inventory_id,finding_revision,origin,inspection,reconciliation,
          display_canonical_number,identity_match_key,passport_observation_state,
          equipment_observation_state,mutation_state,actor_ref,created_at,updated_at)
        values (?,?,0,'UNEXPECTED_EXISTING','NOT_INSPECTED','CONFLICT',?,?,'ABSENT',
          'ABSENT','IDLE',?::jsonb,?,?)
        """,
        findingId,
        inventoryId,
        matchKey,
        matchKey,
        ACTOR,
        now,
        now);
  }

  private void insertFurnitureReconciliation(String state, int ageSeconds) {
    UUID inventoryId = insertSession();
    String failureCode = "PENDING".equals(state) ? null : "ASSET_SERVICE_UNAVAILABLE";
    if ("BLOCKED".equals(state)) {
      jdbc.update(
          """
          insert into inventory_furniture_reconciliation_intent(
            inventory_id,intent_revision,state,idempotency_key,asset_snapshot_sha256,review_sha256,
            request_sha256,request_body,attempt_count,next_attempt_at,failure_code,created_at,
            updated_at,completed_at)
          values (?,0,?, ?,?,?,?,?::jsonb,1,clock_timestamp(),?,
            clock_timestamp()-(? * interval '1 second'),clock_timestamp(),clock_timestamp())
          """,
          inventoryId,
          state,
          UUID.randomUUID(),
          HASH_A,
          HASH_B,
          HASH_C,
          "{}",
          failureCode,
          ageSeconds);
      return;
    }
    jdbc.update(
        """
        insert into inventory_furniture_reconciliation_intent(
          inventory_id,intent_revision,state,idempotency_key,asset_snapshot_sha256,review_sha256,
          request_sha256,request_body,attempt_count,next_attempt_at,failure_code,created_at,updated_at)
        values (?,0,?, ?,?,?,?,?::jsonb,1,clock_timestamp(),?,
          clock_timestamp()-(? * interval '1 second'),clock_timestamp())
        """,
        inventoryId,
        state,
        UUID.randomUUID(),
        HASH_A,
        HASH_B,
        HASH_C,
        "{}",
        failureCode,
        ageSeconds);
  }

  private void insertFurnitureLoss(String state, int ageSeconds) {
    UUID inventoryId = insertSession();
    String failureCode = "PENDING".equals(state) ? null : "MAINTENANCE_SERVICE_UNAVAILABLE";
    OffsetDateTime completedAt =
        "BLOCKED".equals(state) ? OffsetDateTime.now(ZoneOffset.UTC) : null;
    jdbc.update(
        """
        insert into inventory_furniture_loss_intent(
          finding_id,intent_revision,inventory_id,warehouse_id,equipment_id,state,idempotency_key,
          request_sha256,request_body,attempt_count,next_attempt_at,failure_code,created_at,
          updated_at,completed_at)
        values (?,0,?,?,?,?,?,?,?::jsonb,1,clock_timestamp(),?,
          clock_timestamp()-(? * interval '1 second'),clock_timestamp(),?)
        """,
        UUID.randomUUID(),
        inventoryId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        state,
        UUID.randomUUID(),
        HASH_A,
        "{}",
        failureCode,
        ageSeconds,
        completedAt);
  }
}
