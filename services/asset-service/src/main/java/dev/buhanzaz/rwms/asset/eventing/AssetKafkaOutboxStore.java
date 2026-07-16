package dev.buhanzaz.rwms.asset.eventing;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class AssetKafkaOutboxStore {
  private final JdbcTemplate jdbc;
  public AssetKafkaOutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration lease) {
    UUID token = UUID.randomUUID();
    return jdbc.query("""
        with candidate as (
          select event_id from outbox_event candidate
          where ((status='PENDING' and next_attempt_at <= clock_timestamp())
              or (status='IN_FLIGHT' and lease_until < clock_timestamp()))
            and not exists (select 1 from outbox_event earlier
              where earlier.aggregate_type=candidate.aggregate_type and earlier.aggregate_id=candidate.aggregate_id
                and earlier.aggregate_version<candidate.aggregate_version and earlier.status<>'PUBLISHED')
          order by created_at,event_id for update skip locked limit 1
        ) update outbox_event event set status='IN_FLIGHT',lease_owner=?,lease_token=?,
          lease_until=clock_timestamp() + (? * interval '1 millisecond')
          from candidate where event.event_id=candidate.event_id
        returning event.event_id,event.aggregate_type,event.aggregate_id,event.aggregate_version,event.event_type,event.topic,
          event.envelope_body::text,event.envelope_sha256,event.attempt_count
        """, (rs, row) -> new Claim(rs.getObject("event_id", UUID.class), rs.getString("aggregate_type"),
            rs.getString("aggregate_id"), rs.getLong("aggregate_version"), rs.getString("event_type"), rs.getString("topic"),
            rs.getString("envelope_body"), rs.getString("envelope_sha256").trim(), rs.getInt("attempt_count"), token),
        owner, token, lease.toMillis()).stream().findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean published(UUID eventId, UUID leaseToken) {
    return jdbc.update("update outbox_event set status='PUBLISHED',published_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null,last_error_code=null where event_id=? and status='IN_FLIGHT' and lease_token=?", eventId, leaseToken) == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void transientFailure(Claim claim) {
    int next = claim.attemptCount() + 1;
    if (next >= 4) { dlt(claim, "PUBLISH_FAILED"); return; }
    jdbc.update("""
        update outbox_event set status='PENDING',attempt_count=?,next_attempt_at=clock_timestamp() + (? * interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED'
          where event_id=? and status='IN_FLIGHT' and lease_token=?
        """, next, 1L << (next - 1), claim.eventId(), claim.leaseToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailure(Claim claim) { dlt(claim, "VALIDATION_REJECTED"); }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void quarantine(Claim claim, String code) {
    jdbc.update("update outbox_event set status='QUARANTINED',attempt_count=attempt_count+1,lease_owner=null,lease_token=null,lease_until=null,last_error_code=? where event_id=? and status='IN_FLIGHT' and lease_token=?", code, claim.eventId(), claim.leaseToken());
  }

  private void dlt(Claim claim, String code) {
    jdbc.update("update outbox_event set status='DLT',attempt_count=attempt_count+1,dlt_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null,last_error_code=? where event_id=? and status='IN_FLIGHT' and lease_token=?", code, claim.eventId(), claim.leaseToken());
  }

  public record Claim(UUID eventId, String aggregateType, String aggregateId, long aggregateVersion,
      String eventType, String topic, String envelopeBody, String envelopeSha256, int attemptCount, UUID leaseToken) {}
}
