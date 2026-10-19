package dev.buhanzaz.rwms.inventory.eventing;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Persists finite, lease-fenced publication of sanitized inventory dead letters. */
@Repository
public class InventoryDeadLetterRelayStore {
  private final JdbcTemplate jdbc;

  public InventoryDeadLetterRelayStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claim(String owner, Duration leaseDuration, int maxAttempts) {
    UUID token = UUID.randomUUID();
    ClaimedRow row =
        jdbc
            .query(
                """
                with candidate as (
                  select dlt_id,status='IN_FLIGHT' as expired,
                         (attempt_count+case when status='IN_FLIGHT' then 1 else 0 end)>=? as exhausted
                    from sanitized_dead_letter
                   where (status='PENDING' and next_attempt_at<=clock_timestamp())
                      or (status='IN_FLIGHT' and lease_until<clock_timestamp())
                   order by created_at,dlt_id for update skip locked limit 1
                )
                update sanitized_dead_letter letter
                   set status=case
                         when candidate.exhausted then 'FAILED'
                         else 'IN_FLIGHT'
                       end,
                       attempt_count=letter.attempt_count+case when candidate.expired then 1 else 0 end,
                       lease_owner=case
                         when candidate.exhausted then null else ? end,
                       lease_token=case
                         when candidate.exhausted then null else ? end,
                       lease_until=case
                         when candidate.exhausted then null
                         else clock_timestamp()+(?*interval '1 millisecond') end,
                       last_error_code=case
                         when candidate.expired then 'LEASE_EXPIRED' else letter.last_error_code end,
                       terminal_phase=case
                         when candidate.exhausted then
                           case when candidate.expired then 'LEASE' else 'PUBLISH' end
                         else null end,
                       terminal_reason=case
                         when candidate.exhausted then 'RETRY_BUDGET_EXHAUSTED'
                         else null end
                  from candidate where letter.dlt_id=candidate.dlt_id
                returning letter.status,letter.dlt_id,letter.safe_body::text,letter.body_sha256,
                          letter.attempt_count
                """,
                (resultSet, rowNumber) ->
                    new ClaimedRow(
                        resultSet.getString("status"),
                        new Claim(
                            resultSet.getObject("dlt_id", UUID.class),
                            resultSet.getString("safe_body"),
                            resultSet.getString("body_sha256").trim(),
                            resultSet.getInt("attempt_count"),
                            token)),
                maxAttempts,
                owner,
                token,
                leaseDuration.toMillis())
            .stream()
            .findFirst()
            .orElse(null);
    if (row == null || !"IN_FLIGHT".equals(row.status())) return Optional.empty();
    return Optional.of(row.claim());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void published(Claim claim) {
    jdbc.update(
        """
        update sanitized_dead_letter set status='PUBLISHED',published_at=clock_timestamp(),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code=null
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        claim.id(),
        claim.token());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void validationFailure(Claim claim) {
    jdbc.update(
        """
        update sanitized_dead_letter set status='FAILED',attempt_count=attempt_count+1,
          lease_owner=null,lease_token=null,lease_until=null,last_error_code='CHECKSUM_MISMATCH',
          terminal_phase='VALIDATION',terminal_reason='CHECKSUM_MISMATCH'
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        claim.id(),
        claim.token());
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void transientFailure(Claim claim, int maxAttempts) {
    int attempts = Math.addExact(claim.attempts(), 1);
    if (attempts >= maxAttempts) {
      jdbc.update(
          """
          update sanitized_dead_letter set status='FAILED',attempt_count=?,
            lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED',
            terminal_phase='PUBLISH',terminal_reason='RETRY_BUDGET_EXHAUSTED'
           where dlt_id=? and status='IN_FLIGHT' and lease_token=?
          """,
          attempts,
          claim.id(),
          claim.token());
      return;
    }
    long delaySeconds = 1L << Math.min(attempts - 1, 6);
    jdbc.update(
        """
        update sanitized_dead_letter set status='PENDING',attempt_count=?,
          next_attempt_at=clock_timestamp()+(?*interval '1 second'),
          lease_owner=null,lease_token=null,lease_until=null,last_error_code='PUBLISH_FAILED'
         where dlt_id=? and status='IN_FLIGHT' and lease_token=?
        """,
        attempts,
        delaySeconds,
        claim.id(),
        claim.token());
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RecoveryCandidate> lockForRecovery(UUID dltId) {
    return jdbc
        .query(
            """
            select dlt_id,destination,source_topic,source_event_id,message_sha256,failure_code,
              safe_body::text,body_sha256,status,attempt_count,last_error_code,
              terminal_phase,terminal_reason,review_version,reviewed_at
             from sanitized_dead_letter where dlt_id=? for update
            """,
            (resultSet, rowNumber) ->
                new RecoveryCandidate(
                    resultSet.getObject("dlt_id", UUID.class),
                    resultSet.getString("destination"),
                    resultSet.getString("source_topic"),
                    resultSet.getObject("source_event_id", UUID.class),
                    resultSet.getString("message_sha256").trim(),
                    resultSet.getString("failure_code"),
                    resultSet.getString("safe_body"),
                    resultSet.getString("body_sha256").trim(),
                    resultSet.getString("status"),
                    resultSet.getInt("attempt_count"),
                    resultSet.getString("last_error_code"),
                    resultSet.getString("terminal_phase"),
                    resultSet.getString("terminal_reason"),
                    resultSet.getLong("review_version"),
                    resultSet.getObject("reviewed_at", OffsetDateTime.class)),
            dltId)
        .stream()
        .findFirst();
  }

  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<ReviewReceipt> recoveryReview(UUID dltId, long expectedReviewVersion) {
    return jdbc
        .query(
            """
            select request_fingerprint,review_version,reviewed_at
              from inventory_eventing_recovery_review
             where record_kind='DLT' and record_id=? and expected_review_version=?
            """,
            (resultSet, rowNumber) ->
                new ReviewReceipt(
                    resultSet.getString("request_fingerprint").trim(),
                    resultSet.getLong("review_version"),
                    resultSet.getObject("reviewed_at", OffsetDateTime.class)),
            dltId,
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
              update sanitized_dead_letter
                 set status='PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
                     lease_owner=null,lease_token=null,lease_until=null,published_at=null,
                     last_error_code=null,terminal_phase=null,terminal_reason=null,
                     review_version=review_version+1,reviewed_at=clock_timestamp()
               where dlt_id=? and review_version=? and status='FAILED'
              returning dlt_id,review_version,status,reviewed_at
            ), audit as (
              insert into inventory_eventing_recovery_review(
                record_kind,record_id,expected_review_version,review_version,prior_status,
                prior_last_error_code,prior_terminal_phase,prior_terminal_reason,
                prior_attempt_count,reviewer_subject_id,review_reason,request_fingerprint,reviewed_at)
              select 'DLT',dlt_id,?,review_version,?,?,?,?,?,?,?,?,reviewed_at from updated
              returning record_id,review_version
            )
            select updated.dlt_id,updated.review_version,updated.status,updated.reviewed_at
              from updated join audit
                on audit.record_id=updated.dlt_id and audit.review_version=updated.review_version
            """,
            (resultSet, rowNumber) ->
                new RecoveryTruth(
                    resultSet.getObject("dlt_id", UUID.class),
                    resultSet.getLong("review_version"),
                    resultSet.getString("status"),
                    resultSet.getObject("reviewed_at", OffsetDateTime.class)),
            candidate.id(),
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

  public record Claim(UUID id, String body, String hash, int attempts, UUID token) {}

  public record RecoveryCandidate(
      UUID id,
      String destination,
      String sourceTopic,
      UUID sourceEventId,
      String messageSha256,
      String failureCode,
      String body,
      String bodySha256,
      String status,
      int attemptCount,
      String lastErrorCode,
      String terminalPhase,
      String terminalReason,
      long reviewVersion,
      OffsetDateTime reviewedAt) {}

  public record RecoveryTruth(
      UUID id, long reviewVersion, String status, OffsetDateTime reviewedAt) {}

  public record ReviewReceipt(
      String requestFingerprint, long reviewVersion, OffsetDateTime reviewedAt) {}
}
