package dev.buhanzaz.rwms.logistics.eventing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import dev.buhanzaz.rwms.logistics.eventing.inbound.LogisticsInboundTransportTopics;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/**
 * Stores only a deterministic safe summary for a failed broker delivery. The
 * original envelope and its business payload are intentionally never copied
 * into the dead-letter table.
 */
@Repository
@RequiredArgsConstructor
public class LogisticsSanitizedDltStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void enqueue(LogisticsOutboxStore.Claim claim, String failureCode) {
    if (!"VALIDATION_REJECTED".equals(failureCode) && !"PROCESSING_FAILED".equals(failureCode)) {
      throw new IllegalArgumentException("Unsupported logistics DLT failure code");
    }

    LogisticsAggregateType aggregateType =
        LogisticsAggregateType.valueOf(requireAggregateType(claim.aggregateType()));
    String destination = aggregateType.sanitizedDltTopic();
    String safeBody = canonicalJson(write(safeBody(claim, failureCode)));
    UUID dltId =
        UUID.nameUUIDFromBytes(
            (destination + '\u001f' + claim.envelopeSha256() + '\u001f' + failureCode)
                .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into sanitized_dead_letter(
            dlt_id, destination, message_sha256, failure_code, safe_body,
            body_sha256, status, attempt_count, next_attempt_at, created_at)
        values (?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, clock_timestamp(), clock_timestamp())
        on conflict (dlt_id) do nothing
        """,
        dltId,
        destination,
        claim.envelopeSha256(),
        failureCode,
        safeBody,
        LogisticsEventStore.sha256(safeBody.getBytes(StandardCharsets.UTF_8)));
  }

  /** Persists only a hash-only summary for a rejected source-owned inbound fact. */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void enqueueInbound(
      String messageSha256, String failureCode, String sourceTopic, UUID sourceEventId) {
    if (messageSha256 == null || !messageSha256.matches("[0-9a-f]{64}")) {
      throw new IllegalArgumentException("Inbound DLT message hash is invalid");
    }
    if (!Set.of("VALIDATION_REJECTED", "PROCESSING_FAILED", "VERSION_GAP", "EVENT_ID_CONFLICT")
        .contains(failureCode)) {
      throw new IllegalArgumentException("Unsupported inbound logistics DLT failure code");
    }
    String destination = LogisticsInboundTransportTopics.SANITIZED_DLT;
    Map<String, Object> body = new LinkedHashMap<>();
    body.put("failureCode", failureCode);
    body.put("messageSha256", messageSha256);
    body.put("sourceTopic", sourceTopic);
    body.put("sourceEventId", sourceEventId == null ? null : sourceEventId.toString());
    body.put("recordedAt", Instant.now().toString());
    String safeBody = canonicalJson(write(body));
    UUID dltId =
        UUID.nameUUIDFromBytes(
            (destination
                    + '\u001f'
                    + (sourceTopic == null ? "" : sourceTopic)
                    + '\u001f'
                    + messageSha256
                    + '\u001f'
                    + failureCode)
                .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into sanitized_dead_letter(
            dlt_id, destination, message_sha256, failure_code, safe_body,
            body_sha256, status, attempt_count, next_attempt_at, created_at)
        values (?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0, clock_timestamp(), clock_timestamp())
        on conflict (dlt_id) do nothing
        """,
        dltId,
        destination,
        messageSha256,
        failureCode,
        safeBody,
        LogisticsEventStore.sha256(safeBody.getBytes(StandardCharsets.UTF_8)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID leaseToken = UUID.randomUUID();
    return jdbc
        .query(
            """
            with candidate as (
                select dlt_id
                  from sanitized_dead_letter
                 where (status = 'PENDING' and next_attempt_at <= clock_timestamp())
                    or (status = 'IN_FLIGHT' and lease_until < clock_timestamp())
                 order by created_at, dlt_id
                 for update skip locked
                 limit 1
            )
            update sanitized_dead_letter dlt
               set status = 'IN_FLIGHT', lease_owner = ?, lease_token = ?,
                   lease_until = clock_timestamp() + (? * interval '1 millisecond')
              from candidate
             where dlt.dlt_id = candidate.dlt_id
            returning dlt.dlt_id, dlt.destination, dlt.safe_body::text,
                      dlt.body_sha256, dlt.attempt_count
            """,
            (result, row) ->
                new Claim(
                    result.getObject("dlt_id", UUID.class),
                    result.getString("destination"),
                    result.getString("safe_body"),
                    result.getString("body_sha256").trim(),
                    result.getInt("attempt_count"),
                    leaseToken),
            owner,
            leaseToken,
            leaseDuration.toMillis())
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean published(Claim claim) {
    return jdbc.update(
            """
            update sanitized_dead_letter
               set status = 'PUBLISHED', published_at = clock_timestamp(),
                   lease_owner = null, lease_token = null, lease_until = null
             where dlt_id = ? and status = 'IN_FLIGHT' and lease_token = ?
            """,
            claim.id(),
            claim.leaseToken())
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(Claim claim) {
    int failedAttempts = claim.attemptCount() + 1;
    if (failedAttempts >= 4) {
      jdbc.update(
          """
          update sanitized_dead_letter
             set status = 'FAILED', attempt_count = ?, lease_owner = null,
                 lease_token = null, lease_until = null
           where dlt_id = ? and status = 'IN_FLIGHT' and lease_token = ?
          """,
          failedAttempts,
          claim.id(),
          claim.leaseToken());
      return;
    }

    long delaySeconds = 1L << (failedAttempts - 1);
    jdbc.update(
        """
        update sanitized_dead_letter
           set status = 'PENDING', attempt_count = ?,
               next_attempt_at = clock_timestamp() + (? * interval '1 second'),
               lease_owner = null, lease_token = null, lease_until = null
         where dlt_id = ? and status = 'IN_FLIGHT' and lease_token = ?
        """,
        failedAttempts,
        delaySeconds,
        claim.id(),
        claim.leaseToken());
  }

  private static String requireAggregateType(String value) {
    try {
      return LogisticsAggregateType.valueOf(value).name();
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Unsupported logistics aggregate type for DLT", exception);
    }
  }

  private static Map<String, String> safeBody(LogisticsOutboxStore.Claim claim, String failureCode) {
    Map<String, String> body = new LinkedHashMap<>();
    body.put("failureCode", failureCode);
    body.put("messageSha256", claim.envelopeSha256());
    body.put("eventId", claim.eventId().toString());
    body.put("eventType", claim.eventType());
    body.put("aggregateType", claim.aggregateType());
    body.put("aggregateId", claim.aggregateId());
    body.put("aggregateVersion", Long.toString(claim.aggregateVersion()));
    body.put("recordedAt", Instant.now().toString());
    return body;
  }

  private String canonicalJson(String json) {
    String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
    if (canonical == null) {
      throw new IllegalStateException("PostgreSQL did not canonicalize logistics DLT JSON");
    }
    return canonical;
  }

  private String write(Map<String, ?> value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (JacksonException exception) {
      throw new IllegalArgumentException("Logistics DLT metadata cannot be serialized", exception);
    }
  }

  public record Claim(
      UUID id,
      String destination,
      String safeBody,
      String bodySha256,
      int attemptCount,
      UUID leaseToken) {}
}
