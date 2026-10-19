package dev.buhanzaz.rwms.logistics.inquiry.eventing;

import dev.buhanzaz.rwms.logistics.eventing.LogisticsEventStore;
import dev.buhanzaz.rwms.logistics.service.LogisticsConflictException;
import dev.buhanzaz.rwms.logistics.service.LogisticsNotFoundException;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/** Stores, leases, and recovers immutable rental-inquiry booking event envelopes. */
@Repository
public class RentalInquiryBookedOutboxStore {
  private static final String EVENT_TYPE = "logistics.rental-inquiry.booked.v1";
  private static final Set<String> ENVELOPE_FIELDS =
      Set.of(
          "envelopeVersion",
          "eventId",
          "eventType",
          "eventVersion",
          "occurredAt",
          "recordedAt",
          "producer",
          "aggregateType",
          "aggregateId",
          "aggregateVersion",
          "correlation",
          "actorRef",
          "payload");
  private static final Set<String> PAYLOAD_FIELDS = Set.of("conversationId", "orderId");

  private final JdbcTemplate jdbc;
  private final ObjectMapper json;
  private final ObjectMapper strictJson;

  public RentalInquiryBookedOutboxStore(JdbcTemplate jdbc, ObjectMapper json) {
    this.jdbc = jdbc;
    this.json = json;
    this.strictJson =
        json.rebuild()
            .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .build();
  }

  /** Persists one canonical envelope and its stable checksum in the booking transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      UUID inquiryId,
      long inquiryVersion,
      UUID conversationId,
      UUID bookingId,
      UUID orderId,
      UUID managerSubjectId,
      OffsetDateTime occurredAt) {
    if (inquiryVersion < 0) {
      throw new IllegalArgumentException("inquiryVersion must not be negative");
    }
    UUID eventId = UUID.randomUUID();
    OffsetDateTime recordedAt = now();
    DomainEventEnvelopeV2<BookedPayload> body =
        new DomainEventEnvelopeV2<>(
            2,
            eventId,
            EVENT_TYPE,
            1,
            occurredAt.toInstant(),
            recordedAt.toInstant(),
            "logistics-service",
            "RENTAL_INQUIRY",
            inquiryId.toString(),
            inquiryVersion,
            new CorrelationContext(conversationId, bookingId),
            new OpaqueActorReference(managerSubjectId.toString(), "USER", null),
            new BookedPayload(conversationId, orderId));
    try {
      String payload = canonicalJson(json.writeValueAsString(body));
      jdbc.update(
          """
          insert into rental_inquiry_outbox(
              event_id,event_type,inquiry_id,conversation_id,order_id,payload,payload_sha256,
              status,attempt_count,next_attempt_at,created_at)
          values (?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
          """,
          eventId,
          body.eventType(),
          inquiryId,
          conversationId,
          orderId,
          payload,
          sha256(payload),
          recordedAt,
          recordedAt);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Rental inquiry event cannot be serialized", exception);
    }
  }

  /** Atomically consumes one delivery attempt, including a recovered expired lease. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public ClaimOutcome claim(String owner, Duration leaseDuration, int maxAttempts) {
    UUID leaseToken = UUID.randomUUID();
    ClaimedRow row =
        jdbc
            .query(
                """
                with candidate as (
                  select event_id,attempt_count>=? as exhausted
                    from rental_inquiry_outbox
                   where (status='PENDING' and next_attempt_at<=clock_timestamp())
                      or (status='IN_FLIGHT' and lease_until<clock_timestamp())
                   order by created_at,event_id for update skip locked limit 1
                )
                update rental_inquiry_outbox event
                   set status=case when candidate.exhausted then 'QUARANTINED' else 'IN_FLIGHT' end,
                       attempt_count=case when candidate.exhausted then event.attempt_count
                                          else event.attempt_count+1 end,
                       lease_owner=case when candidate.exhausted then null else ? end,
                       lease_token=case when candidate.exhausted then null else ? end,
                       lease_until=case when candidate.exhausted then null
                         else clock_timestamp()+(?*interval '1 millisecond') end,
                       last_error_code=case when candidate.exhausted then 'LEASE_EXPIRED'
                                            else event.last_error_code end
                  from candidate where event.event_id=candidate.event_id
                returning event.status,event.event_id,event.event_type,event.inquiry_id,
                          event.conversation_id,event.order_id,event.payload::text,
                          event.payload_sha256,event.attempt_count
                """,
                (result, ignored) ->
                    new ClaimedRow(
                        result.getString("status"),
                        new Claim(
                            result.getObject("event_id", UUID.class),
                            result.getString("event_type"),
                            result.getObject("inquiry_id", UUID.class),
                            result.getObject("conversation_id", UUID.class),
                            result.getObject("order_id", UUID.class),
                            result.getString("payload"),
                            result.getString("payload_sha256").trim(),
                            result.getInt("attempt_count"),
                            leaseToken)),
                maxAttempts,
                owner,
                leaseToken,
                leaseDuration.toMillis())
            .stream()
            .findFirst()
            .orElse(null);
    if (row == null) return new ClaimOutcome(false, Optional.empty());
    if (!"IN_FLIGHT".equals(row.status())) return new ClaimOutcome(true, Optional.empty());
    return new ClaimOutcome(true, Optional.of(row.claim()));
  }

  /** Returns whether the retained envelope checksum, metadata, and business payload are intact. */
  public boolean hasValidEnvelope(Claim claim) {
    try {
      return sha256(claim.payload()).equals(claim.payloadSha256())
          && verifyEnvelope(
              claim.eventId(),
              claim.eventType(),
              claim.inquiryId(),
              claim.conversationId(),
              claim.orderId(),
              claim.payload());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markPublished(Claim claim) {
    return jdbc.update(
            """
            update rental_inquiry_outbox
               set status='PUBLISHED',published_at=clock_timestamp(),lease_owner=null,
                   lease_token=null,lease_until=null,last_error_code=null
             where event_id=? and status='IN_FLIGHT' and lease_token=?
            """,
            claim.eventId(),
            claim.leaseToken())
        == 1;
  }

  /** Persists one sanitized broker failure and terminalizes the final allowed attempt. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void deliveryFailed(Claim claim, int maxAttempts, String code) {
    if (claim.attemptCount() >= maxAttempts) {
      terminalize(claim, code);
      return;
    }
    long delaySeconds = 1L << Math.min(claim.attemptCount() - 1, 6);
    jdbc.update(
        """
        update rental_inquiry_outbox
           set status='PENDING',next_attempt_at=clock_timestamp()+(?*interval '1 second'),
               lease_owner=null,lease_token=null,lease_until=null,last_error_code=?
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        delaySeconds,
        code,
        claim.eventId(),
        claim.leaseToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailed(Claim claim) {
    terminalize(claim, "VALIDATION_REJECTED");
  }

  /** Applies or replays one administrator-reviewed recovery without replacing event bytes or ID. */
  @Transactional
  public RecoveryResult recover(
      UUID eventId, long expectedRecoveryVersion, UUID reviewedBySubjectId, String reason) {
    if (eventId == null || expectedRecoveryVersion < 0 || reviewedBySubjectId == null) {
      throw new IllegalArgumentException("Rental inquiry outbox recovery request is invalid");
    }
    String normalizedReason = normalizeReason(reason);
    String fingerprint =
        sha256(
            ("rental-inquiry-outbox-recovery-v1\n"
                    + eventId
                    + '\n'
                    + expectedRecoveryVersion
                    + '\n'
                    + reviewedBySubjectId
                    + '\n'
                    + normalizedReason)
                .getBytes(StandardCharsets.UTF_8));
    RecoveryRow row = lockRecoveryRow(eventId).orElseThrow(LogisticsNotFoundException::new);
    Optional<RecoveryReceipt> replay = recoveryReceipt(eventId, expectedRecoveryVersion);
    if (replay.isPresent()) {
      RecoveryReceipt receipt = replay.orElseThrow();
      if (!receipt.requestFingerprint().equals(fingerprint)) {
        throw new LogisticsConflictException(
            "Recovery version is already bound to another reviewed command");
      }
      return new RecoveryResult(
          eventId,
          "PENDING",
          0,
          receipt.recoveryVersion(),
          receipt.lastErrorCode(),
          receipt.reviewedBySubjectId(),
          receipt.reason(),
          receipt.reviewedAt(),
          true);
    }
    if (row.recoveryVersion() != expectedRecoveryVersion) {
      throw new LogisticsConflictException("Rental inquiry outbox recovery version is stale");
    }
    if (!"QUARANTINED".equals(row.status())) {
      throw new LogisticsConflictException(
          "Only a quarantined rental inquiry event can be recovered");
    }
    if (!sha256(row.payload()).equals(row.payloadSha256())
        || !verifyEnvelope(
            row.eventId(),
            row.eventType(),
            row.inquiryId(),
            row.conversationId(),
            row.orderId(),
            row.payload())) {
      throw new LogisticsConflictException("Stored rental inquiry event is not safe to recover");
    }
    OffsetDateTime reviewedAt = databaseNow();
    long recoveryVersion = Math.addExact(expectedRecoveryVersion, 1);
    int changed =
        jdbc.update(
            """
            update rental_inquiry_outbox
               set status='PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
                   lease_owner=null,lease_token=null,lease_until=null,last_error_code=null,
                   recovery_version=?,recovered_by_subject_id=?,recovery_reason=?,recovered_at=?
             where event_id=? and status='QUARANTINED' and recovery_version=?
            """,
            recoveryVersion,
            reviewedBySubjectId,
            normalizedReason,
            reviewedAt,
            eventId,
            expectedRecoveryVersion);
    if (changed != 1) {
      throw new LogisticsConflictException("Rental inquiry outbox recovery changed concurrently");
    }
    jdbc.update(
        """
        insert into rental_inquiry_outbox_recovery_audit(
          event_id,expected_recovery_version,recovery_version,prior_attempt_count,
          prior_last_error_code,reviewed_by_subject_id,reason,request_fingerprint,reviewed_at)
        values (?,?,?,?,?,?,?,?,?)
        """,
        eventId,
        expectedRecoveryVersion,
        recoveryVersion,
        row.attemptCount(),
        row.lastErrorCode(),
        reviewedBySubjectId,
        normalizedReason,
        fingerprint,
        reviewedAt);
    return new RecoveryResult(
        eventId,
        "PENDING",
        0,
        recoveryVersion,
        row.lastErrorCode(),
        reviewedBySubjectId,
        normalizedReason,
        reviewedAt,
        false);
  }

  private void terminalize(Claim claim, String code) {
    jdbc.update(
        """
        update rental_inquiry_outbox
           set status='QUARANTINED',lease_owner=null,lease_token=null,lease_until=null,
               last_error_code=?
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        code,
        claim.eventId(),
        claim.leaseToken());
  }

  private Optional<RecoveryRow> lockRecoveryRow(UUID eventId) {
    return jdbc
        .query(
            """
            select event_id,event_type,inquiry_id,conversation_id,order_id,payload::text,
                   payload_sha256,status,attempt_count,last_error_code,recovery_version
              from rental_inquiry_outbox where event_id=? for update
            """,
            (result, ignored) ->
                new RecoveryRow(
                    result.getObject("event_id", UUID.class),
                    result.getString("event_type"),
                    result.getObject("inquiry_id", UUID.class),
                    result.getObject("conversation_id", UUID.class),
                    result.getObject("order_id", UUID.class),
                    result.getString("payload"),
                    result.getString("payload_sha256").trim(),
                    result.getString("status"),
                    result.getInt("attempt_count"),
                    result.getString("last_error_code"),
                    result.getLong("recovery_version")),
            eventId)
        .stream()
        .findFirst();
  }

  private Optional<RecoveryReceipt> recoveryReceipt(UUID eventId, long expectedRecoveryVersion) {
    return jdbc
        .query(
            """
            select recovery_version,prior_last_error_code,reviewed_by_subject_id,reason,
                   request_fingerprint,reviewed_at
              from rental_inquiry_outbox_recovery_audit
             where event_id=? and expected_recovery_version=?
            """,
            (result, ignored) ->
                new RecoveryReceipt(
                    result.getLong("recovery_version"),
                    result.getString("prior_last_error_code"),
                    result.getObject("reviewed_by_subject_id", UUID.class),
                    result.getString("reason"),
                    result.getString("request_fingerprint").trim(),
                    result.getObject("reviewed_at", OffsetDateTime.class)),
            eventId,
            expectedRecoveryVersion)
        .stream()
        .findFirst();
  }

  private boolean verifyEnvelope(
      UUID eventId,
      String eventType,
      UUID inquiryId,
      UUID conversationId,
      UUID orderId,
      String payload) {
    try {
      JsonNode root = strictJson.readTree(payload);
      requireExactFields(root, ENVELOPE_FIELDS);
      requireExactFields(root.path("payload"), PAYLOAD_FIELDS);
      DomainEventEnvelopeV2<BookedPayload> envelope =
          strictJson
              .readerFor(new TypeReference<DomainEventEnvelopeV2<BookedPayload>>() {})
              .readValue(root);
      return eventId.equals(envelope.eventId())
          && EVENT_TYPE.equals(eventType)
          && eventType.equals(envelope.eventType())
          && envelope.eventVersion() == 1
          && "logistics-service".equals(envelope.producer())
          && "RENTAL_INQUIRY".equals(envelope.aggregateType())
          && inquiryId.toString().equals(envelope.aggregateId())
          && envelope.aggregateVersion() >= 0
          && conversationId.equals(envelope.correlation().correlationId())
          && envelope.correlation().causationId() != null
          && envelope.actorRef() != null
          && "USER".equals(envelope.actorRef().principalType())
          && envelope.actorRef().profileRevision() == null
          && envelope.payload() != null
          && conversationId.equals(envelope.payload().conversationId())
          && orderId.equals(envelope.payload().orderId());
    } catch (RuntimeException exception) {
      return false;
    }
  }

  private static void requireExactFields(JsonNode node, Set<String> fields) {
    if (node == null || !node.isObject()) {
      throw new IllegalArgumentException("Rental inquiry event field is not an object");
    }
    Set<String> actual = new LinkedHashSet<>();
    node.propertyNames().forEach(actual::add);
    if (!actual.equals(fields)) {
      throw new IllegalArgumentException("Rental inquiry event fields changed");
    }
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize JSON");
    return canonical;
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? now() : value;
  }

  private static String normalizeReason(String reason) {
    if (reason == null || reason.codePointCount(0, reason.length()) > 2000) {
      throw new IllegalArgumentException(
          "Recovery reason must contain 1 to 2000 trimmed characters");
    }
    String normalized = reason.strip();
    int length = normalized.codePointCount(0, normalized.length());
    if (length < 1 || length > 2000 || normalized.indexOf('\0') >= 0) {
      throw new IllegalArgumentException(
          "Recovery reason must contain 1 to 2000 trimmed characters");
    }
    return normalized;
  }

  private static String sha256(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String sha256(byte[] value) {
    return LogisticsEventStore.sha256(value);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC).truncatedTo(ChronoUnit.MICROS);
  }

  /** Business identifiers carried by the immutable booked-event envelope. */
  public record BookedPayload(UUID conversationId, UUID orderId) {}

  /** Fenced immutable delivery input retained from one atomic attempt claim. */
  public record Claim(
      UUID eventId,
      String eventType,
      UUID inquiryId,
      UUID conversationId,
      UUID orderId,
      String payload,
      String payloadSha256,
      int attemptCount,
      UUID leaseToken) {}

  /** One atomic relay step, which may terminalize an exhausted lease without publishing. */
  public record ClaimOutcome(boolean progressed, Optional<Claim> claim) {}

  /** Historical PENDING receipt for an accepted reviewed recovery or its exact replay. */
  public record RecoveryResult(
      UUID eventId,
      String status,
      int attemptCount,
      long recoveryVersion,
      String lastErrorCode,
      UUID reviewedBySubjectId,
      String reason,
      OffsetDateTime reviewedAt,
      boolean replayed) {}

  private record ClaimedRow(String status, Claim claim) {}

  private record RecoveryRow(
      UUID eventId,
      String eventType,
      UUID inquiryId,
      UUID conversationId,
      UUID orderId,
      String payload,
      String payloadSha256,
      String status,
      int attemptCount,
      String lastErrorCode,
      long recoveryVersion) {}

  private record RecoveryReceipt(
      long recoveryVersion,
      String lastErrorCode,
      UUID reviewedBySubjectId,
      String reason,
      String requestFingerprint,
      OffsetDateTime reviewedAt) {}
}
