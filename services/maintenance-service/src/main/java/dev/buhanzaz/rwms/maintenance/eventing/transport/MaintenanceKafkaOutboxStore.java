package dev.buhanzaz.rwms.maintenance.eventing.transport;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Repository
public class MaintenanceKafkaOutboxStore {
  private final JdbcTemplate jdbc;

  public MaintenanceKafkaOutboxStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration) {
    UUID leaseToken = UUID.randomUUID();
    return jdbc.query(
            """
            with candidate as (
              select candidate.event_id
                from outbox_event candidate
               where ((candidate.status='PENDING' and candidate.next_attempt_at<=clock_timestamp())
                   or (candidate.status='IN_FLIGHT' and candidate.lease_until<clock_timestamp()))
                 and not exists (
                   select 1 from outbox_event predecessor
                    where predecessor.aggregate_type=candidate.aggregate_type
                      and predecessor.aggregate_id=candidate.aggregate_id
                      and predecessor.aggregate_version<candidate.aggregate_version
                      and predecessor.status<>'PUBLISHED')
               order by candidate.created_at,candidate.event_id
               for update skip locked limit 1
            )
            update outbox_event event
               set status='IN_FLIGHT',lease_owner=?,lease_token=?,
                   lease_until=clock_timestamp()+(? * interval '1 millisecond')
              from candidate where event.event_id=candidate.event_id
            returning event.event_id,event.aggregate_type,event.aggregate_id,event.aggregate_version,
                      event.event_type,event.topic,event.envelope_body::text,event.envelope_sha256,
                      event.attempt_count
            """,
            (result, row) ->
                new Claim(
                    result.getObject("event_id", UUID.class),
                    result.getString("aggregate_type"),
                    result.getString("aggregate_id"),
                    result.getLong("aggregate_version"),
                    result.getString("event_type"),
                    result.getString("topic"),
                    result.getString("envelope_body"),
                    result.getString("envelope_sha256").trim(),
                    result.getInt("attempt_count"),
                    leaseToken),
            owner,
            leaseToken,
            leaseDuration.toMillis())
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markPublished(UUID eventId, UUID leaseToken) {
    return jdbc.update(
            """
            update outbox_event set status='PUBLISHED',published_at=clock_timestamp(),
              lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
             where event_id=? and status='IN_FLIGHT' and lease_token=?
            """,
            eventId,
            leaseToken)
        == 1;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void transientFailure(Claim claim) {
    int nextAttempt = claim.attemptCount() + 1;
    if (nextAttempt >= 4) {
      terminalFailure(claim, "PUBLISH_FAILED");
      return;
    }
    jdbc.update(
        """
        update outbox_event set status='PENDING',attempt_count=?,
          next_attempt_at=clock_timestamp()+(? * interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED'
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        nextAttempt,
        1L << (nextAttempt - 1),
        claim.eventId(),
        claim.leaseToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailure(Claim claim, String failureCode) {
    jdbc.update(
        """
        update outbox_event set status='QUARANTINED',attempt_count=attempt_count+1,
          lease_owner=null,lease_token=null,lease_until=null,last_error_code=?
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        failureCode,
        claim.eventId(),
        claim.leaseToken());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean resumeAfterReview(
      UUID eventId,
      long expectedReviewVersion,
      UUID reviewerSubjectId,
      String reviewReason) {
    if (eventId == null
        || expectedReviewVersion < 0
        || reviewerSubjectId == null
        || reviewReason == null
        || reviewReason.isBlank()
        || reviewReason.trim().length() > 2000) {
      throw new IllegalArgumentException("Reviewed outbox resume metadata is invalid");
    }
    return jdbc.update(
            """
            update outbox_event
               set status='PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
                   lease_owner=null,lease_token=null,lease_until=null,published_at=null,dlt_at=null,
                   last_error_code=null,review_version=review_version+1,review_subject_id=?,
                   review_reason=?,reviewed_at=clock_timestamp()
             where event_id=? and review_version=? and status in ('DLT','QUARANTINED')
            """,
            reviewerSubjectId,
            reviewReason.trim(),
            eventId,
            expectedReviewVersion)
        == 1;
  }

  private void terminalFailure(Claim claim, String failureCode) {
    jdbc.update(
        """
        update outbox_event set status='DLT',attempt_count=attempt_count+1,dlt_at=clock_timestamp(),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code=?
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        failureCode,
        claim.eventId(),
        claim.leaseToken());
  }

  public record Claim(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String topic,
      String envelopeBody,
      String envelopeSha256,
      int attemptCount,
      UUID leaseToken) {}
}
