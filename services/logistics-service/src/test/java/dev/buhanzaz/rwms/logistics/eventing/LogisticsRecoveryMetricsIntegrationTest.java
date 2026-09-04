package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;

import dev.buhanzaz.rwms.logistics.LogisticsServiceApplication;
import dev.buhanzaz.rwms.logistics.service.LogisticsExternalAttemptClaimService;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.Timer;
import jakarta.persistence.EntityManagerFactory;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * Proves logistics recovery gauges and claim meters against a clean Flyway/JPA PostgreSQL context.
 *
 * <p>Technical rows are inserted with every schema constraint intact. Metric reads remain lazy and
 * read-only; the sole state transition is an explicit claim-service call used to prove the closed
 * owner latency/counter series.
 */
@SpringBootTest(
    classes = {
      LogisticsServiceApplication.class,
      LogisticsRecoveryMetricsIntegrationTest.FixedClockConfiguration.class
    },
    properties = {
      "spring.jpa.hibernate.ddl-auto=validate",
      "spring.task.scheduling.enabled=false",
      "rwms.platform.kafka.enabled=false",
      "rwms.logistics.rental-inquiry.booking-retry-initial-delay=1h",
      "rwms.logistics.customer.booking-reconcile-initial-delay=1h",
      "rwms.logistics.warehouse-operation-marks.initial-delay=1h",
      "AUTH_ISSUER=http://issuer.invalid",
      "PANEL_ORIGIN=http://localhost:5173"
    })
@ActiveProfiles("test")
@Testcontainers(disabledWithoutDocker = true)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class LogisticsRecoveryMetricsIntegrationTest {
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 8, 9, 9, 0, 0, 0, ZoneOffset.UTC);
  private static final String REQUEST_DIGEST = "a".repeat(64);
  private static final String RESPONSE_DIGEST = "b".repeat(64);
  private static final List<String> GAUGE_NAMES =
      List.of(
          "rwms.logistics.outbox.backlog",
          "rwms.logistics.outbox.backlog.oldest.age.seconds",
          "rwms.logistics.outbox.terminal",
          "rwms.logistics.sanitized_dlt.backlog",
          "rwms.logistics.sanitized_dlt.backlog.oldest.age.seconds",
          "rwms.logistics.sanitized_dlt.terminal",
          "rwms.logistics.rental_inquiry.outbox.backlog",
          "rwms.logistics.rental_inquiry.outbox.backlog.oldest.age.seconds",
          "rwms.logistics.presentation_booking.recovery.backlog",
          "rwms.logistics.presentation_booking.recovery.backlog.oldest.age.seconds",
          "rwms.logistics.presentation_booking.recovery.quarantined",
          "rwms.logistics.customer_checkout.recovery.backlog",
          "rwms.logistics.customer_checkout.recovery.backlog.oldest.age.seconds",
          "rwms.logistics.customer_checkout.recovery.quarantined",
          "rwms.logistics.warehouse_mark.backlog",
          "rwms.logistics.warehouse_mark.backlog.oldest.age.seconds",
          "rwms.logistics.warehouse_mark.terminal",
          "rwms.logistics.inbound.gap.open",
          "rwms.logistics.inbound.gap.open.oldest.age.seconds",
          "rwms.logistics.inbound.checkpoint.blocked",
          "rwms.logistics.external_attempt.active",
          "rwms.logistics.external_attempt.active.oldest.age.seconds",
          "rwms.logistics.external_attempt.retry.max",
          "rwms.logistics.external_attempt.reconciliation_required",
          "rwms.logistics.external_attempt.executor.active",
          "rwms.logistics.external_attempt.executor.queue");

  @Container
  static final PostgreSQLContainer POSTGRES =
      new PostgreSQLContainer("postgres:17-alpine")
          .withDatabaseName("logistics_recovery_metrics");

  @Autowired MeterRegistry registry;
  @Autowired Flyway flyway;
  @Autowired EntityManagerFactory entityManagerFactory;
  @Autowired JdbcTemplate jdbc;
  @Autowired LogisticsExternalAttemptClaimService claims;

  /** Supplies the isolated PostgreSQL connection and explicit test-only non-Kafka configuration. */
  @DynamicPropertySource
  static void properties(DynamicPropertyRegistry registry) {
    registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
    registry.add("spring.datasource.username", POSTGRES::getUsername);
    registry.add("spring.datasource.password", POSTGRES::getPassword);
    registry.add("spring.datasource.driver-class-name", () -> "org.postgresql.Driver");
    registry.add(
        "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
        () -> "http://127.0.0.1:65535/jwks");
  }

  @BeforeEach
  void resetTechnicalState() {
    jdbc.execute(
        """
        truncate table
          outbox_event,
          domain_event,
          event_stream_head,
          sanitized_dead_letter,
          rental_inquiry_outbox,
          warehouse_operation_mark_recovery_audit,
          warehouse_operation_mark_outbox,
          version_gap_quarantine,
          consumer_aggregate_checkpoint,
          logistics_external_attempt,
          presentation_booking,
          customer_rental_session,
          client_presentation,
          rental_inquiry,
          order_client,
          logistics_document
        cascade
        """);
  }

  @Test
  void cleanSchemaThenBoundedStatesAndClaimTransitionAreObservable() {
    assertThat(flyway.info().current()).isNotNull();
    assertThat(entityManagerFactory.isOpen()).isTrue();
    for (String metric : GAUGE_NAMES) {
      assertThat(gauge(metric)).isZero();
    }

    persistObservedState();

    assertThat(gauge("rwms.logistics.outbox.backlog")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.outbox.backlog.oldest.age.seconds")).isEqualTo(300.0);
    assertThat(gauge("rwms.logistics.outbox.terminal")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.sanitized_dlt.backlog")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.sanitized_dlt.backlog.oldest.age.seconds"))
        .isEqualTo(250.0);
    assertThat(gauge("rwms.logistics.sanitized_dlt.terminal")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.rental_inquiry.outbox.backlog")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.rental_inquiry.outbox.backlog.oldest.age.seconds"))
        .isEqualTo(200.0);
    assertThat(gauge("rwms.logistics.presentation_booking.recovery.backlog")).isEqualTo(1.0);
    assertThat(
            gauge(
                "rwms.logistics.presentation_booking.recovery.backlog.oldest.age.seconds"))
        .isEqualTo(175.0);
    assertThat(gauge("rwms.logistics.presentation_booking.recovery.quarantined"))
        .isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.customer_checkout.recovery.backlog")).isEqualTo(1.0);
    assertThat(
            gauge("rwms.logistics.customer_checkout.recovery.backlog.oldest.age.seconds"))
        .isEqualTo(140.0);
    assertThat(gauge("rwms.logistics.customer_checkout.recovery.quarantined"))
        .isEqualTo(1.0);
    assertThat(
            jdbc.queryForObject(
                "select count(*) from warehouse_operation_mark_outbox "
                    + "where state in ('PENDING','RETRY_PENDING','IN_FLIGHT')",
                Integer.class))
        .isEqualTo(1);
    assertThat(gauge("rwms.logistics.warehouse_mark.backlog")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.warehouse_mark.backlog.oldest.age.seconds"))
        .isEqualTo(150.0);
    assertThat(gauge("rwms.logistics.warehouse_mark.terminal")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.inbound.gap.open")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.inbound.gap.open.oldest.age.seconds"))
        .isEqualTo(100.0);
    assertThat(gauge("rwms.logistics.inbound.checkpoint.blocked")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.external_attempt.active")).isEqualTo(2.0);
    assertThat(gauge("rwms.logistics.external_attempt.active.oldest.age.seconds"))
        .isEqualTo(500.0);
    assertThat(gauge("rwms.logistics.external_attempt.retry.max")).isEqualTo(3.0);
    assertThat(gauge("rwms.logistics.external_attempt.reconciliation_required")).isEqualTo(1.0);
    assertThat(gauge("rwms.logistics.external_attempt.executor.active")).isZero();
    assertThat(gauge("rwms.logistics.external_attempt.executor.queue")).isZero();

    assertClaimMetersUseOnlyClosedTags();
    List<LogisticsExternalAttemptClaimService.Claim> claimed =
        claims.claimDue(
            LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
            List.of("METRICS_TEST"),
            1);
    assertThat(claimed).hasSize(1);
    Counter claimedCounter =
        registry
            .get("rwms.logistics.external_attempt.claim")
            .tag("owner", "return_registration")
            .tag("state", "claimed")
            .counter();
    Timer claimLatency =
        registry
            .get("rwms.logistics.external_attempt.claim.latency")
            .tag("owner", "return_registration")
            .timer();
    assertThat(claimedCounter.count()).isEqualTo(1.0);
    assertThat(claimLatency.count()).isEqualTo(1);
    assertThat(claimLatency.totalTime(java.util.concurrent.TimeUnit.NANOSECONDS)).isNotNegative();
  }

  private void persistObservedState() {
    insertMainOutbox("PENDING", NOW.minusSeconds(300));
    insertMainOutbox("DLT", NOW.minusSeconds(275));
    insertSanitizedDlt("PENDING", NOW.minusSeconds(250), "c".repeat(64));
    insertSanitizedDlt("FAILED", NOW.minusSeconds(225), "d".repeat(64));
    insertRentalInquiryOutbox(NOW.minusSeconds(200));
    insertBookingRecoveryStates();
    // Rental-inquiry inserts legitimately emit first-operation marks through the V37 trigger.
    // This fixture observes that queue independently with exact timestamps below.
    jdbc.update("delete from warehouse_operation_mark_outbox");
    insertWarehouseMark("PENDING", NOW.minusSeconds(150));
    insertWarehouseMark("QUARANTINED", NOW.minusSeconds(125));
    insertInboundGap(NOW.minusSeconds(100));

    UUID documentId = insertDocument();
    insertExternalAttempt(documentId, "PENDING", 0, NOW.minusSeconds(500), "METRICS_TEST");
    insertExternalAttempt(documentId, "RETRY", 3, NOW.minusSeconds(400), "METRICS_RETRY");
    insertExternalAttempt(
        documentId,
        "RECONCILIATION_REQUIRED",
        2,
        NOW.minusSeconds(300),
        "METRICS_RECONCILIATION");
  }

  private void insertMainOutbox(String status, OffsetDateTime createdAt) {
    UUID eventId = UUID.randomUUID();
    String aggregateId = UUID.randomUUID().toString();
    jdbc.update(
        """
        insert into event_stream_head(
          aggregate_type,aggregate_id,current_version,last_event_id,updated_at)
        values ('RETURN',?,0,?,?)
        """,
        aggregateId,
        eventId,
        createdAt);
    jdbc.update(
        """
        insert into domain_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,event_version,
          occurred_at,recorded_at,correlation_id,payload,payload_sha256,baseline)
        values (?,'RETURN',?,0,'logistics.return.created.v1',1,?,?,?,'{}'::jsonb,
          encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),false)
        """,
        eventId,
        aggregateId,
        createdAt,
        createdAt,
        UUID.randomUUID());
    jdbc.update(
        """
        insert into outbox_event(
          event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body,
          envelope_sha256,status,attempt_count,next_attempt_at,dlt_at,created_at)
        values (?,'RETURN',?,0,'logistics.return.created.v1',?,'{}'::jsonb,
          encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),?,0,?,
          case when ?='DLT' then ? else null end,?)
        """,
        eventId,
        aggregateId,
        LogisticsTransportTopics.RETURN,
        status,
        createdAt,
        status,
        createdAt,
        createdAt);
  }

  private void insertBookingRecoveryStates() {
    UUID clientId = UUID.randomUUID();
    UUID actorId = UUID.randomUUID();
    UUID warehouseId = UUID.randomUUID();
    jdbc.update(
        """
        insert into order_client(
          id,version,client_type,display_name,normalized_name,created_by_subject_id,
          creation_idempotency_key,creation_request_sha256,created_at,updated_at,phone,
          normalized_phone,responsible_manager_id)
        values (?,0,'INDIVIDUAL','Metrics customer','metrics customer',?,?,?, ?,?,
          '+79990000001','+79990000001',?)
        """,
        clientId,
        actorId,
        UUID.randomUUID(),
        REQUEST_DIGEST,
        NOW.minusSeconds(300),
        NOW.minusSeconds(300),
        actorId);
    insertBookingRecoveryState(
        clientId, actorId, warehouseId, NOW.minusSeconds(175), false);
    insertBookingRecoveryState(
        clientId, actorId, warehouseId, NOW.minusSeconds(160), true);
  }

  private void insertBookingRecoveryState(
      UUID clientId,
      UUID actorId,
      UUID warehouseId,
      OffsetDateTime createdAt,
      boolean quarantined) {
    UUID inquiryId = UUID.randomUUID();
    UUID presentationId = UUID.randomUUID();
    UUID bookingId = UUID.randomUUID();
    UUID commandKey = UUID.randomUUID();
    OffsetDateTime nextAttemptAt = quarantined ? null : NOW.minusSeconds(1);
    OffsetDateTime quarantinedAt = quarantined ? NOW.minusSeconds(1) : null;
    int attempts = quarantined ? 8 : 1;
    jdbc.update(
        """
        insert into rental_inquiry(
          id,version,conversation_id,client_id,manager_id,manager_display_name,manager_role,
          warehouse_id,state,creation_idempotency_key,created_at,updated_at)
        values (?,0,?,?,?,'Metrics actor','CUSTOMER',?,'ACTIVE',?,?,?)
        """,
        inquiryId,
        UUID.randomUUID(),
        clientId,
        actorId,
        warehouseId,
        UUID.randomUUID(),
        createdAt,
        createdAt);
    jdbc.update(
        """
        insert into client_presentation(
          id,version,inquiry_id,revision,warehouse_id,state,expires_at,view_until,
          last_publish_idempotency_key,last_publish_request_sha256,created_at,updated_at)
        values (?,0,?,1,?,'ACTIVE',?,?,?,?,?,?)
        """,
        presentationId,
        inquiryId,
        warehouseId,
        NOW.plusHours(1),
        NOW.plusHours(2),
        UUID.randomUUID(),
        RESPONSE_DIGEST,
        createdAt,
        createdAt);
    jdbc.update(
        """
        insert into presentation_booking(
          id,version,presentation_id,presentation_revision,idempotency_key,
          selected_item_ids_json,state,attempt_count,last_error_code,created_at,updated_at,
          recovery_next_attempt_at,recovery_quarantined_at)
        values (?,0,?,1,?,jsonb_build_array(?::text),'PENDING',?,'DEPENDENCY_TRANSIENT',
          ?,?,?,?)
        """,
        bookingId,
        presentationId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        attempts,
        createdAt,
        NOW.minusSeconds(1),
        nextAttemptAt,
        quarantinedAt);
    jdbc.update(
        """
        insert into customer_rental_session(
          id,version,inquiry_id,customer_subject_id,warehouse_id,state,pending_command_key,
          pending_command_sha256,booking_id,presentation_token,checkout_command_key,
          checkout_command_sha256,created_at,updated_at,recovery_attempt_count,
          recovery_next_attempt_at,recovery_quarantined_at,recovery_last_error_code)
        values (?,0,?,?,?,'CHECKOUT_PENDING',?,?,?,'metrics-presentation-token',?,?,
          ?,?,?,?,?,'CUSTOMER_CHECKOUT_DEPENDENCY_PENDING')
        """,
        UUID.randomUUID(),
        inquiryId,
        actorId,
        warehouseId,
        commandKey,
        REQUEST_DIGEST,
        bookingId,
        commandKey,
        REQUEST_DIGEST,
        quarantined ? NOW.minusSeconds(130) : NOW.minusSeconds(140),
        NOW.minusSeconds(1),
        attempts,
        nextAttemptAt,
        quarantinedAt);
  }

  private void insertSanitizedDlt(String status, OffsetDateTime createdAt, String messageDigest) {
    jdbc.update(
        """
        insert into sanitized_dead_letter(
          dlt_id,destination,message_sha256,failure_code,safe_body,body_sha256,status,
          attempt_count,next_attempt_at,created_at)
        values (?,?,?,'VALIDATION_REJECTED','{}'::jsonb,
          encode(sha256(convert_to('{}'::jsonb::text,'UTF8')),'hex'),?,4,?,?)
        """,
        UUID.randomUUID(),
        LogisticsTransportTopics.RETURN_DLT,
        messageDigest,
        status,
        createdAt,
        createdAt);
  }

  private void insertRentalInquiryOutbox(OffsetDateTime createdAt) {
    jdbc.update(
        """
        insert into rental_inquiry_outbox(
          event_id,event_type,inquiry_id,conversation_id,order_id,payload,status,attempt_count,
          next_attempt_at,created_at)
        values (?,'logistics.rental-inquiry.booked.v1',?,?,?,'{}'::jsonb,'PENDING',0,?,?)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        createdAt,
        createdAt);
  }

  private void insertWarehouseMark(String state, OffsetDateTime createdAt) {
    jdbc.update(
        """
        insert into warehouse_operation_mark_outbox(
          operation_id,warehouse_id,occurred_at,state,attempt_count,next_attempt_at,
          created_at,updated_at)
        values (?,?,?,?,0,?,?,?)
        """,
        UUID.randomUUID(),
        UUID.randomUUID(),
        createdAt,
        state,
        createdAt,
        createdAt,
        createdAt);
  }

  private void insertInboundGap(OffsetDateTime detectedAt) {
    UUID aggregateId = UUID.randomUUID();
    jdbc.update(
        """
        insert into consumer_aggregate_checkpoint(
          consumer_group,aggregate_type,aggregate_id,last_aggregate_version,blocked,
          quarantine_reason,updated_at)
        values ('logistics-service-inbox-v1','RENTAL_ITEM',?,-1,true,
          'AGGREGATE_VERSION_GAP',?)
        """,
        aggregateId.toString(),
        detectedAt);
    jdbc.update(
        """
        insert into version_gap_quarantine(
          quarantine_id,consumer_group,aggregate_type,aggregate_id,expected_version,
          received_version,received_event_id,payload_sha256,reason_code,status,detected_at)
        values (?,'logistics-service-inbox-v1','RENTAL_ITEM',?,0,2,?,?,'AGGREGATE_VERSION_GAP',
          'OPEN',?)
        """,
        UUID.randomUUID(),
        aggregateId.toString(),
        UUID.randomUUID(),
        "e".repeat(64),
        detectedAt);
  }

  private UUID insertDocument() {
    UUID documentId = UUID.randomUUID();
    jdbc.update(
        """
        insert into logistics_document(
          id,version,document_type,state,warehouse_id,requested_by_subject_id,correlation_id,
          created_at,updated_at)
        values (?,0,'RETURN','REGISTERING',?,?,?, ?,?)
        """,
        documentId,
        UUID.randomUUID(),
        UUID.randomUUID(),
        UUID.randomUUID(),
        NOW.minusSeconds(600),
        NOW.minusSeconds(600));
    return documentId;
  }

  private void insertExternalAttempt(
      UUID documentId,
      String result,
      int retryCount,
      OffsetDateTime createdAt,
      String operationType) {
    boolean terminal = result.equals("RECONCILIATION_REQUIRED");
    jdbc.update(
        """
        insert into logistics_external_attempt(
          id,document_id,operation_id,target_service,operation_type,request_sha256,response_sha256,
          result,retry_count,next_attempt_at,correlation_id,created_at,completed_at)
        values (?,?,?,'ASSET',?,?,?, ?,?,?,?, ?,?)
        """,
        UUID.randomUUID(),
        documentId,
        UUID.randomUUID(),
        operationType,
        REQUEST_DIGEST,
        terminal ? RESPONSE_DIGEST : null,
        result,
        retryCount,
        terminal ? null : NOW.minusDays(1),
        UUID.randomUUID(),
        createdAt,
        terminal ? createdAt.plusSeconds(1) : null);
  }

  private void assertClaimMetersUseOnlyClosedTags() {
    assertThat(registry.find("rwms.logistics.external_attempt.claim").counters()).hasSize(15);
    assertThat(registry.find("rwms.logistics.external_attempt.claim.latency").timers()).hasSize(5);
    for (Counter counter : registry.find("rwms.logistics.external_attempt.claim").counters()) {
      assertThat(counter.getId().getTags())
          .extracting(Tag::getKey)
          .containsExactlyInAnyOrder("application", "owner", "state");
    }
    for (Timer timer : registry.find("rwms.logistics.external_attempt.claim.latency").timers()) {
      assertThat(timer.getId().getTags())
          .extracting(Tag::getKey)
          .containsExactlyInAnyOrder("application", "owner");
    }
    for (String metric : GAUGE_NAMES) {
      Gauge gauge = registry.get(metric).gauge();
      assertThat(gauge.getId().getTags())
          .extracting(Tag::getKey)
          .containsExactly("application");
    }
  }

  private double gauge(String name) {
    return registry.get(name).gauge().value();
  }

  /** Replaces the application clock only for deterministic displayed-age assertions. */
  @TestConfiguration(proxyBeanMethods = false)
  static class FixedClockConfiguration {
    /** Supplies a fixed UTC observation clock; it is not used for database transitions. */
    @Bean
    @Primary
    Clock recoveryMetricsClock() {
      return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
    }
  }
}
