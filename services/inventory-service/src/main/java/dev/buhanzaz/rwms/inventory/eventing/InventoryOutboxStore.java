package dev.buhanzaz.rwms.inventory.eventing;

import java.time.Duration;
import java.time.OffsetDateTime;
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
   * Claims one ordered inventory aggregate head in an independent transaction. Expired claims
   * consume the same finite attempt budget; an exhausted head stays terminal and blocks successors.
   * The issued token fences later state changes against a replacement claimant.
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration, int maxAttempts) {
    UUID leaseToken = UUID.randomUUID();
    ClaimedRow row =
        jdbc
            .query(
                """
                with candidate as (
                  select event_id,status='IN_FLIGHT' as expired,
                         (attempt_count+case when status='IN_FLIGHT' then 1 else 0 end)>=? as exhausted
                    from outbox_event candidate
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
                   set status=case
                         when candidate.exhausted then 'QUARANTINED'
                         else 'IN_FLIGHT'
                       end,
                       attempt_count=event.attempt_count+case when candidate.expired then 1 else 0 end,
                       lease_owner=case
                         when candidate.exhausted then null else ? end,
                       lease_token=case
                         when candidate.exhausted then null else ? end,
                       lease_until=case
                         when candidate.exhausted then null
                         else clock_timestamp()+(?*interval '1 millisecond') end,
                       last_error_code=case
                         when candidate.expired then 'LEASE_EXPIRED' else event.last_error_code end,
                       terminal_phase=case
                         when candidate.exhausted then
                           case when candidate.expired then 'LEASE' else 'PUBLISH' end
                         else null end,
                       terminal_reason=case
                         when candidate.exhausted then 'RETRY_BUDGET_EXHAUSTED'
                         else null end
                  from candidate where event.event_id=candidate.event_id
                returning event.status,event.event_id,event.aggregate_id,event.topic,
                          event.envelope_body::text,event.envelope_sha256,event.attempt_count
                """,
                (resultSet, rowNumber) ->
                    new ClaimedRow(
                        resultSet.getString("status"),
                        new Claim(
                            resultSet.getObject("event_id", UUID.class),
                            resultSet.getString("aggregate_id"),
                            resultSet.getString("topic"),
                            resultSet.getString("envelope_body"),
                            resultSet.getString("envelope_sha256").trim(),
                            resultSet.getInt("attempt_count"),
                            leaseToken)),
                maxAttempts,
                owner,
                leaseToken,
                leaseDuration.toMillis())
            .stream()
            .findFirst()
            .orElse(null);
    if (row == null || !"IN_FLIGHT".equals(row.status())) return Optional.empty();
    return Optional.of(row.claim());
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
  public void transientFailure(Claim claim, int maxAttempts) {
    int attempts = Math.addExact(claim.attemptCount(), 1);
    if (attempts >= maxAttempts) {
      jdbc.update(
          """
          update outbox_event set status='QUARANTINED',attempt_count=?,
            lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED',
            terminal_phase='PUBLISH',terminal_reason='RETRY_BUDGET_EXHAUSTED'
           where event_id=? and status='IN_FLIGHT' and lease_token=?
          """,
          attempts,
          claim.eventId(),
          claim.leaseToken());
      return;
    }
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
            last_error_code=?,terminal_phase='VALIDATION',terminal_reason=?
         where event_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        code,
        code,
        claim.eventId(),
        claim.leaseToken());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RecoveryCandidate> lockForRecovery(UUID eventId) {
    return jdbc
        .query(
            """
            select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,
              envelope_body::text,envelope_sha256,status,attempt_count,last_error_code,
              terminal_phase,terminal_reason,review_version,reviewed_at
             from outbox_event where event_id=? for update
            """,
            (resultSet, rowNumber) ->
                new RecoveryCandidate(
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getString("aggregate_type"),
                    resultSet.getString("aggregate_id"),
                    resultSet.getLong("aggregate_version"),
                    resultSet.getString("event_type"),
                    resultSet.getString("topic"),
                    resultSet.getString("envelope_body"),
                    resultSet.getString("envelope_sha256").trim(),
                    resultSet.getString("status"),
                    resultSet.getInt("attempt_count"),
                    resultSet.getString("last_error_code"),
                    resultSet.getString("terminal_phase"),
                    resultSet.getString("terminal_reason"),
                    resultSet.getLong("review_version"),
                    resultSet.getObject("reviewed_at", OffsetDateTime.class)),
            eventId)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isCurrentOrderedHead(RecoveryCandidate candidate) {
    Boolean result =
        jdbc.queryForObject(
            """
            select not exists (
              select 1 from outbox_event predecessor
               where predecessor.aggregate_type=? and predecessor.aggregate_id=?
                 and predecessor.aggregate_version<? and predecessor.status<>'PUBLISHED')
            """,
            Boolean.class,
            candidate.aggregateType(),
            candidate.aggregateId(),
            candidate.aggregateVersion());
    return Boolean.TRUE.equals(result);
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ReviewReceipt> recoveryReview(UUID eventId, long expectedReviewVersion) {
    return jdbc
        .query(
            """
            select request_fingerprint,review_version,reviewed_at
              from inventory_eventing_recovery_review
             where record_kind='OUTBOX' and record_id=? and expected_review_version=?
            """,
            (resultSet, rowNumber) ->
                new ReviewReceipt(
                    resultSet.getString("request_fingerprint").trim(),
                    resultSet.getLong("review_version"),
                    resultSet.getObject("reviewed_at", OffsetDateTime.class)),
            eventId,
            expectedReviewVersion)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RecoveryTruth> requeueAfterReview(
      RecoveryCandidate candidate,
      long expectedReviewVersion,
      UUID reviewerSubjectId,
      String reviewReason,
      String requestFingerprint) {
    return jdbc
        .query(
            """
            with updated as (
              update outbox_event
                 set status='PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
                     lease_owner=null,lease_token=null,lease_until=null,published_at=null,dlt_at=null,
                     last_error_code=null,terminal_phase=null,terminal_reason=null,
                     review_version=review_version+1,reviewed_at=clock_timestamp()
               where event_id=? and review_version=? and status in ('DLT','QUARANTINED')
              returning event_id,review_version,status,reviewed_at
            ), audit as (
              insert into inventory_eventing_recovery_review(
                record_kind,record_id,expected_review_version,review_version,prior_status,
                prior_last_error_code,prior_terminal_phase,prior_terminal_reason,
                prior_attempt_count,reviewer_subject_id,review_reason,request_fingerprint,reviewed_at)
              select 'OUTBOX',event_id,?,review_version,?,?,?,?,?,?,?,?,reviewed_at from updated
              returning record_id,review_version
            )
            select updated.event_id,updated.review_version,updated.status,updated.reviewed_at
              from updated join audit
                on audit.record_id=updated.event_id and audit.review_version=updated.review_version
            """,
            (resultSet, rowNumber) ->
                new RecoveryTruth(
                    resultSet.getObject("event_id", UUID.class),
                    resultSet.getLong("review_version"),
                    resultSet.getString("status"),
                    resultSet.getObject("reviewed_at", OffsetDateTime.class)),
            candidate.eventId(),
            expectedReviewVersion,
            expectedReviewVersion,
            candidate.status(),
            candidate.lastErrorCode(),
            candidate.terminalPhase(),
            candidate.terminalReason(),
            candidate.attemptCount(),
            reviewerSubjectId,
            reviewReason,
            requestFingerprint)
        .stream()
        .findFirst();
  }

  private record ClaimedRow(String status, Claim claim) {}

  public record Claim(
      UUID eventId,
      String aggregateId,
      String topic,
      String envelopeBody,
      String envelopeSha256,
      int attemptCount,
      UUID leaseToken) {}

  public record RecoveryCandidate(
      UUID eventId,
      String aggregateType,
      String aggregateId,
      long aggregateVersion,
      String eventType,
      String topic,
      String envelopeBody,
      String envelopeSha256,
      String status,
      int attemptCount,
      String lastErrorCode,
      String terminalPhase,
      String terminalReason,
      long reviewVersion,
      OffsetDateTime reviewedAt) {}

  public record RecoveryTruth(
      UUID eventId, long reviewVersion, String status, OffsetDateTime reviewedAt) {}

  public record ReviewReceipt(
      String requestFingerprint, long reviewVersion, OffsetDateTime reviewedAt) {}
}
