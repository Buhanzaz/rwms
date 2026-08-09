package dev.buhanzaz.rwms.taskboard.eventing;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.ObjectMapper;

/** Owns durable enqueue, lease, retry, and terminal state for task-board sanitized DLT records. */
@Repository
@RequiredArgsConstructor
public class TaskBoardSanitizedDltStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper objectMapper;

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void enqueue(String destination, String messageSha256, String failureCode,
      Map<String, String> safeBody) {
    String body = canonicalJson(write(safeBody));
    UUID id = UUID.nameUUIDFromBytes(
        (destination + '\u001f' + messageSha256 + '\u001f' + failureCode)
            .getBytes(StandardCharsets.UTF_8));
    jdbc.update(
        """
        insert into sanitized_dead_letter(
          dlt_id,destination,message_sha256,failure_code,safe_body,body_sha256,
          status,attempt_count,next_attempt_at,created_at)
        values (?,?,?,?,?::jsonb,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        on conflict (dlt_id) do nothing
        """,
        id, destination, messageSha256, failureCode, body,
        TaskBoardEventStore.sha256(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID token = UUID.randomUUID();
    return jdbc.query(
        """
        with candidate as (
          select dlt_id from sanitized_dead_letter
           where (status='PENDING' and next_attempt_at<=clock_timestamp())
              or (status='IN_FLIGHT' and lease_until<clock_timestamp())
           order by created_at,dlt_id for update skip locked limit 1)
        update sanitized_dead_letter dlt
           set status='IN_FLIGHT',lease_owner=?,lease_token=?,
               lease_until=clock_timestamp()+(? * interval '1 millisecond')
          from candidate where dlt.dlt_id=candidate.dlt_id
        returning dlt.dlt_id,dlt.destination,dlt.safe_body::text,dlt.body_sha256,dlt.attempt_count
        """,
        (rs, row) -> new Claim(rs.getObject("dlt_id", UUID.class), rs.getString("destination"),
            rs.getString("safe_body"), rs.getString("body_sha256").trim(),
            rs.getInt("attempt_count"), token),
        owner, token, leaseDuration.toMillis()).stream().findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean published(Claim claim) {
    return jdbc.update(
        """
        update sanitized_dead_letter set status='PUBLISHED',published_at=clock_timestamp(),
          lease_owner=null,lease_token=null,lease_until=null
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """, claim.id(), claim.leaseToken()) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(Claim claim) {
    int attempts = claim.attemptCount() + 1;
    if (attempts >= 4) {
      jdbc.update(
          """
          update sanitized_dead_letter set status='FAILED',attempt_count=?,
            lease_owner=null,lease_token=null,lease_until=null
           where dlt_id=? and status='IN_FLIGHT' and lease_token=?
          """, attempts, claim.id(), claim.leaseToken());
      return;
    }
    jdbc.update(
        """
        update sanitized_dead_letter set status='PENDING',attempt_count=?,
          next_attempt_at=clock_timestamp()+(? * interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """, attempts, 1L << (attempts - 1), claim.id(), claim.leaseToken());
  }

  @Transactional
  public boolean requeue(UUID dltId, int expectedAttemptCount) {
    return jdbc.update(
            """
            update sanitized_dead_letter
               set status='PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
                   published_at=null
             where dlt_id=? and status='FAILED' and attempt_count=?
               and lease_owner is null and lease_token is null and lease_until is null
            """,
            dltId,
            expectedAttemptCount)
        == 1;
  }

  private String canonicalJson(String value) {
    String result = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
    if (result == null) throw new IllegalStateException("PostgreSQL did not canonicalize sanitized DLT JSON");
    return result;
  }

  private String write(Object value) {
    try {
      return objectMapper.writeValueAsString(value);
    } catch (tools.jackson.core.JacksonException exception) {
      throw new IllegalStateException("Sanitized DLT metadata cannot be serialized", exception);
    }
  }

  public record Claim(UUID id, String destination, String safeBody, String bodySha256,
      int attemptCount, UUID leaseToken) {}
}
