package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.service.AssetChecksum;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

/** Hash-only dead letters; original inbound records are intentionally never persisted here. */
@Repository
public class AssetSanitizedDltStore {
  private final JdbcTemplate jdbc;
  private final ObjectMapper mapper;

  public AssetSanitizedDltStore(JdbcTemplate jdbc, ObjectMapper mapper) {
    this.jdbc = jdbc;
    this.mapper = mapper;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void enqueue(String messageSha256, String failureCode, Map<String, String> safeBody) {
    String body = canonicalJson(write(safeBody));
    UUID id = UUID.nameUUIDFromBytes((AssetAggregateType.SANITIZED_DLT_TOPIC + '\u001f'
        + messageSha256 + '\u001f' + failureCode).getBytes(StandardCharsets.UTF_8));
    jdbc.update("""
        insert into sanitized_dead_letter(
          dlt_id,destination,message_sha256,failure_code,safe_body,body_sha256,status,attempt_count,next_attempt_at,created_at)
        values (?,?,?,?,?::jsonb,?,'PENDING',0,clock_timestamp(),clock_timestamp())
        on conflict (dlt_id) do nothing
        """, id, AssetAggregateType.SANITIZED_DLT_TOPIC, messageSha256, failureCode, body,
        AssetChecksum.sha256(body.getBytes(StandardCharsets.UTF_8)));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID token = UUID.randomUUID();
    return jdbc.query("""
        with candidate as (
          select dlt_id from sanitized_dead_letter
          where (status='PENDING' and next_attempt_at<=clock_timestamp())
             or (status='IN_FLIGHT' and lease_until<clock_timestamp())
          order by created_at,dlt_id for update skip locked limit 1)
        update sanitized_dead_letter dlt set status='IN_FLIGHT',lease_owner=?,lease_token=?,
          lease_until=clock_timestamp()+(? * interval '1 millisecond')
        from candidate where dlt.dlt_id=candidate.dlt_id
        returning dlt.dlt_id,dlt.destination,dlt.safe_body::text,dlt.body_sha256,dlt.attempt_count
        """, (rs, row) -> new Claim(rs.getObject("dlt_id", UUID.class), rs.getString("destination"),
            rs.getString("safe_body"), rs.getString("body_sha256").trim(), rs.getInt("attempt_count"), token),
        owner, token, leaseDuration.toMillis()).stream().findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean published(Claim claim) {
    return jdbc.update("""
        update sanitized_dead_letter set status='PUBLISHED',published_at=clock_timestamp(),
          lease_owner=null,lease_token=null,lease_until=null
        where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """, claim.id(), claim.leaseToken()) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void failed(Claim claim) {
    int attempts = claim.attemptCount() + 1;
    if (attempts >= 4) {
      jdbc.update("""
          update sanitized_dead_letter set status='FAILED',attempt_count=?,lease_owner=null,lease_token=null,lease_until=null
          where dlt_id=? and status='IN_FLIGHT' and lease_token=?
          """, attempts, claim.id(), claim.leaseToken());
      return;
    }
    jdbc.update("""
        update sanitized_dead_letter set status='PENDING',attempt_count=?,
          next_attempt_at=clock_timestamp()+(? * interval '1 second'),lease_owner=null,lease_token=null,lease_until=null
        where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """, attempts, 1L << (attempts - 1), claim.id(), claim.leaseToken());
  }

  private String canonicalJson(String json) {
    String value = jdbc.queryForObject("select (?::jsonb)::text", String.class, json);
    if (value == null) throw new IllegalStateException("PostgreSQL did not canonicalize asset sanitized DLT JSON");
    return value;
  }

  private String write(Map<String, String> body) {
    try {
      return mapper.writeValueAsString(body);
    } catch (JacksonException exception) {
      throw new IllegalStateException("Asset sanitized DLT metadata cannot be serialized", exception);
    }
  }

  public record Claim(UUID id, String destination, String safeBody, String bodySha256,
                      int attemptCount, UUID leaseToken) {}
}
