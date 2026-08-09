package dev.buhanzaz.rwms.inventory.eventing;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lease-based persistence boundary for ordered inventory transactional-outbox delivery.
 */
@Repository
public class InventoryOutboxStore {
  private final JdbcTemplate jdbc;

  public InventoryOutboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /**
   * Claims one ordered inventory aggregate head in an independent transaction. The issued lease
   * token is required by later state changes so duplicate relay workers cannot finalize a lost claim.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID leaseToken = UUID.randomUUID();
    return jdbc
        .query(
            """
            with candidate as (
              select event_id from outbox_event candidate
               where ((status='PENDING' and next_attempt_at<=clock_timestamp())
                   or (status='IN_FLIGHT' and lease_until<clock_timestamp()))
                 and not exists (
                   select 1 from outbox_event earlier
                    where earlier.aggregate_type=candidate.aggregate_type
                      and earlier.aggregate_id=candidate.aggregate_id
                      and earlier.aggregate_version<candidate.aggregate_version
                      and earlier.status<>'PUBLISHED')
               order by created_at,event_id for update skip locked limit 1
            )
            update outbox_event event
               set status='IN_FLIGHT',lease_owner=?,lease_token=?,
                   lease_until=clock_timestamp()+(?*interval '1 millisecond')
              from candidate where event.event_id=candidate.event_id
            returning event.event_id,event.aggregate_id,event.topic,event.envelope_body::text,
                      event.envelope_sha256,event.attempt_count
            """,
            (resultSet, rowNumber) ->
                new Claim(
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getString("aggregate_id"),
                    resultSet.getString("topic"),
                    resultSet.getString("envelope_body"),
                    resultSet.getString("envelope_sha256").trim(),
                    resultSet.getInt("attempt_count"),
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
            update outbox_event set status='PUBLISHED',published_at=clock_timestamp(),
              lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
             where event_id=? and status='IN_FLIGHT' and lease_token=?
            """,
            claim.eventId(),
            claim.leaseToken())
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void transientFailure(Claim claim) {
    int attempts = Math.addExact(claim.attemptCount(), 1);
    long delaySeconds = 1L << Math.min(attempts - 1, 6);
    jdbc.update(
        """
        update outbox_event set status='PENDING',attempt_count=?,
          next_attempt_at=clock_timestamp()+(?*interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED'
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        attempts,
        delaySeconds,
        claim.eventId(),
        claim.leaseToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailure(Claim claim, String code) {
    jdbc.update(
        """
        update outbox_event set status='DLT',attempt_count=attempt_count+1,
          dlt_at=clock_timestamp(),lease_owner=null,lease_token=null,lease_until=null,
          last_error_code=? where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        code,
        claim.eventId(),
        claim.leaseToken());
  }

  public record Claim(
      UUID eventId,
      String aggregateId,
      String topic,
      String envelopeBody,
      String envelopeSha256,
      int attemptCount,
      UUID leaseToken) {}
}
