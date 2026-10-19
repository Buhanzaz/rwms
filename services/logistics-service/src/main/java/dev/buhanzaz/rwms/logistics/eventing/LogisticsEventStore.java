package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.logistics.domain.LogisticsDocument;
import dev.buhanzaz.rwms.logistics.mapper.LogisticsEventPayloadMapper;
import dev.buhanzaz.rwms.platform.contracts.CorrelationContext;
import dev.buhanzaz.rwms.platform.contracts.DomainEventEnvelopeV2;
import dev.buhanzaz.rwms.platform.contracts.OpaqueActorReference;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Appends the creation fact, synchronous projection checkpoint, and
 * transactional outbox row in the caller's PostgreSQL transaction.
 */
@Service
@RequiredArgsConstructor
public class LogisticsEventStore {
  private static final String PRODUCER = "logistics-service";
  private static final String PROJECTION = "logistics-live-v1";

  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;
  private final LogisticsEventPayloadMapper payloadMapper;

  @Transactional(propagation = Propagation.MANDATORY)
  public void initialize(
      LogisticsDocument document, int lineCount, UUID correlationId, UUID actorSubjectId) {
    if (document == null || document.getId() == null) {
      throw new IllegalArgumentException("Persisted logistics document is required");
    }
    if (lineCount < 1 || lineCount > 100) {
      throw new IllegalArgumentException("lineCount must be between 1 and 100");
    }
    if (correlationId == null || actorSubjectId == null) {
      throw new IllegalArgumentException("Correlation and actor identifiers are required");
    }
    LogisticsAggregateType aggregateType = LogisticsAggregateType.from(document.getDocumentType());
    LogisticsEventType eventType = LogisticsEventType.createdFor(aggregateType);
    LogisticsEventPayload payload =
        payloadMapper.toPayload(new LogisticsEventSource(document, lineCount, null));
    requirePayloadIdentity(payload, document, lineCount, null);

    UUID eventId = UUID.randomUUID();
    OffsetDateTime recordedAt = databaseNow();
    try {
      jdbc.update(
          """
          insert into event_stream_head(
              aggregate_type, aggregate_id, current_version, last_event_id, updated_at)
          values (?, ?, ?, ?, ?)
          """,
          aggregateType.name(),
          document.getId().toString(),
          document.getVersion(),
          eventId,
          recordedAt);
    } catch (DuplicateKeyException exception) {
      throw new OptimisticLockingFailureException("Logistics aggregate stream already exists", exception);
    }

    persistFact(
        eventId,
        aggregateType,
        eventType,
        document,
        payload,
        recordedAt,
        new CorrelationContext(correlationId, null),
        new OpaqueActorReference(actorSubjectId.toString(), "USER", null));
  }

  /**
   * Appends one post-creation aggregate fact only after the JPA optimistic
   * version has been flushed. The SQL compare-and-set keeps the event stream,
   * projection checkpoint and outbox aligned with that same aggregate version.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public void append(
      LogisticsDocument document,
      int lineCount,
      UUID correlationId,
      UUID actorSubjectId,
      LogisticsEventType eventType,
      String resultCode) {
    if (document == null || document.getId() == null) {
      throw new IllegalArgumentException("Persisted logistics document is required");
    }
    if (lineCount < 1 || lineCount > 100) {
      throw new IllegalArgumentException("lineCount must be between 1 and 100");
    }
    if (correlationId == null || actorSubjectId == null || eventType == null) {
      throw new IllegalArgumentException("Correlation, actor and event type are required");
    }
    LogisticsAggregateType aggregateType = LogisticsAggregateType.from(document.getDocumentType());
    if (eventType.aggregateType() != aggregateType || document.getVersion() < 1) {
      throw new IllegalArgumentException("Event type or aggregate version is invalid");
    }
    LogisticsEventPayload payload =
        payloadMapper.toPayload(new LogisticsEventSource(document, lineCount, resultCode));
    requirePayloadIdentity(payload, document, lineCount, resultCode);

    UUID eventId = UUID.randomUUID();
    OffsetDateTime recordedAt = databaseNow();
    int updated =
        jdbc.update(
            """
            update event_stream_head
            set current_version=?, last_event_id=?, updated_at=?
            where aggregate_type=? and aggregate_id=? and current_version=?
            """,
            document.getVersion(),
            eventId,
            recordedAt,
            aggregateType.name(),
            document.getId().toString(),
            document.getVersion() - 1);
    if (updated != 1) {
      throw new OptimisticLockingFailureException("Logistics aggregate event stream changed concurrently");
    }

    persistFact(
        eventId,
        aggregateType,
        eventType,
        document,
        payload,
        recordedAt,
        new CorrelationContext(correlationId, null),
        new OpaqueActorReference(actorSubjectId.toString(), "USER", null));
  }

  private void persistFact(
      UUID eventId,
      LogisticsAggregateType aggregateType,
      LogisticsEventType eventType,
      LogisticsDocument document,
      LogisticsEventPayload payload,
      OffsetDateTime recordedAt,
      CorrelationContext correlation,
      OpaqueActorReference actorRef) {
    String payloadJson = canonicalJson(write(payload));
    String payloadHash = sha256(payloadJson.getBytes(StandardCharsets.UTF_8));
    DomainEventEnvelopeV2<LogisticsEventPayload> envelope =
        new DomainEventEnvelopeV2<>(
            2,
            eventId,
            eventType.value(),
            1,
            recordedAt.toInstant(),
            recordedAt.toInstant(),
            PRODUCER,
            aggregateType.name(),
            document.getId().toString(),
            document.getVersion(),
            correlation,
            actorRef,
            payload);
    String envelopeJson = canonicalJson(write(envelope));
    String envelopeHash = sha256(envelopeJson.getBytes(StandardCharsets.UTF_8));

    jdbc.update(
        """
        insert into domain_event(
            event_id, aggregate_type, aggregate_id, aggregate_version,
            event_type, event_version, occurred_at, recorded_at,
            correlation_id, causation_id, actor_ref, payload, payload_sha256, baseline)
        values (?, ?, ?, ?, ?, 1, ?, ?, ?, ?, ?::jsonb, ?::jsonb, ?, false)
        """,
        eventId,
        aggregateType.name(),
        document.getId().toString(),
        document.getVersion(),
        eventType.value(),
        recordedAt,
        recordedAt,
        correlation.correlationId(),
        correlation.causationId(),
        write(actorRef),
        payloadJson,
        payloadHash);
    jdbc.update(
        """
        insert into outbox_event(
            event_id, aggregate_type, aggregate_id, aggregate_version,
            event_type, topic, envelope_body, envelope_sha256,
            status, attempt_count, next_attempt_at, created_at)
        values (?, ?, ?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, ?, ?)
        """,
        eventId,
        aggregateType.name(),
        document.getId().toString(),
        document.getVersion(),
        eventType.value(),
        aggregateType.topic(),
        envelopeJson,
        envelopeHash,
        recordedAt,
        recordedAt);
    jdbc.update(
        """
        insert into projection_checkpoint(
            projection_name, aggregate_type, aggregate_id,
            aggregate_version, projection_sha256, updated_at)
        values (?, ?, ?, ?, ?, ?)
        on conflict (projection_name, aggregate_type, aggregate_id)
        do update set aggregate_version = excluded.aggregate_version,
                      projection_sha256 = excluded.projection_sha256,
                      updated_at = excluded.updated_at
        """,
        PROJECTION,
        aggregateType.name(),
        document.getId().toString(),
        document.getVersion(),
        payloadHash,
        recordedAt);
    jdbc.update(
        """
        insert into aggregate_snapshot(
            aggregate_type, aggregate_id, aggregate_version,
            state, state_sha256, recorded_at)
        values (?, ?, ?, ?::jsonb, ?, ?)
        on conflict do nothing
        """,
        aggregateType.name(),
        document.getId().toString(),
        document.getVersion(),
        payloadJson,
        payloadHash,
        recordedAt);
  }

  private OffsetDateTime databaseNow() {
    OffsetDateTime value = jdbc.queryForObject("select clock_timestamp()", OffsetDateTime.class);
    return value == null ? OffsetDateTime.now(ZoneOffset.UTC) : value;
  }

  private String canonicalJson(String value) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (canonical == null) throw new IllegalStateException("PostgreSQL did not canonicalize logistics JSON");
    return canonical;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Logistics event value cannot be serialized", exception);
    }
  }

  private static void requirePayloadIdentity(
      LogisticsEventPayload payload,
      LogisticsDocument document,
      int lineCount,
      String resultCode) {
    if (!document.getId().equals(payload.documentId())
        || payload.documentType() != document.getDocumentType()
        || payload.state() != document.getState()
        || !document.getWarehouseId().equals(payload.warehouseId())
        || payload.lineCount() != lineCount
        || !java.util.Objects.equals(payload.resultCode(), resultCode)) {
      throw new IllegalStateException("Logistics event payload is not a safe projection of its document");
    }
  }

  public static String sha256(byte[] value) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is required by the Java runtime", exception);
    }
  }
}
