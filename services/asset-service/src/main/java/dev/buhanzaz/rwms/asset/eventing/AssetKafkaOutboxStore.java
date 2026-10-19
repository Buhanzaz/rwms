package dev.buhanzaz.rwms.asset.eventing;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Lease-based persistence boundary for ordered asset transactional-outbox delivery and recovery.
 */
@Repository
public class AssetKafkaOutboxStore {
  private final JdbcTemplate jdbc;
  public AssetKafkaOutboxStore(JdbcTemplate jdbc) { this.jdbc = jdbc; }

  /**
   * Claims one publishable aggregate head in an independent transaction. The lease token and
   * predecessor check prevent concurrent relays from publishing an aggregate out of order.
   */
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

  /**
   * Marks a claim published only when the relay still owns its lease token after broker
   * acknowledgement; a lost or expired claim is not silently finalized.
   */
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

  /**
   * Locks one terminal record while an administrator decides whether it can safely resume.
   * The recovery service performs envelope validation before calling {@link #requeueAfterReview}.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RecoveryCandidate> lockForRecovery(UUID eventId) {
    return jdbc.query("""
        select event_id,aggregate_type,aggregate_id,aggregate_version,event_type,topic,envelope_body::text,
          envelope_sha256,status,attempt_count,last_error_code,review_version,reviewed_at
        from outbox_event where event_id=? for update
        """, (rs, row) -> {
          OffsetDateTime reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
          return new RecoveryCandidate(
              rs.getObject("event_id", UUID.class),
              rs.getString("aggregate_type"),
              rs.getString("aggregate_id"),
              rs.getLong("aggregate_version"),
              rs.getString("event_type"),
              rs.getString("topic"),
              rs.getString("envelope_body"),
              rs.getString("envelope_sha256").trim(),
              rs.getString("status"),
              rs.getInt("attempt_count"),
              rs.getString("last_error_code"),
              rs.getLong("review_version"),
              reviewedAt == null ? null : reviewedAt.toInstant());
        }, eventId).stream().findFirst();
  }

  /** The record may resume only when all earlier stream facts have been published. */
  @Transactional(propagation = Propagation.MANDATORY)
  public boolean isCurrentOrderedHead(RecoveryCandidate candidate) {
    Boolean result = jdbc.queryForObject("""
        select not exists (
          select 1 from outbox_event predecessor
          where predecessor.aggregate_type=? and predecessor.aggregate_id=?
            and predecessor.aggregate_version<? and predecessor.status<>'PUBLISHED'
        )
        """, Boolean.class, candidate.aggregateType(), candidate.aggregateId(), candidate.aggregateVersion());
    return Boolean.TRUE.equals(result);
  }

  /**
   * Returns the immutable review fingerprint for an already-applied version, if present.
   * The target outbox row is locked by the caller first, so its returned truth is stable.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<String> recoveryFingerprint(UUID eventId, long expectedReviewVersion) {
    return jdbc.query("""
        select request_fingerprint from asset_outbox_recovery_review
        where event_id=? and expected_review_version=?
        """, (rs, row) -> rs.getString("request_fingerprint").trim(), eventId, expectedReviewVersion)
        .stream().findFirst();
  }

  /**
   * Applies the terminal-to-pending transition and records its immutable review in one transaction.
   * The compare-and-set is deliberately retained even though the candidate row is locked above.
   */
  @Transactional(propagation = Propagation.MANDATORY)
  public Optional<RecoveryTruth> requeueAfterReview(
      RecoveryCandidate candidate,
      long expectedReviewVersion,
      UUID reviewerSubjectId,
      String reviewReason,
      String requestFingerprint) {
    return jdbc.query("""
        with updated as (
          update outbox_event
             set status='PENDING',attempt_count=0,next_attempt_at=clock_timestamp(),
                 lease_owner=null,lease_token=null,lease_until=null,published_at=null,dlt_at=null,
                 last_error_code=null,review_version=review_version+1,reviewed_at=clock_timestamp()
           where event_id=? and review_version=? and status in ('DLT','QUARANTINED')
          returning event_id,review_version,status,attempt_count,last_error_code,reviewed_at
        ), audit as (
          insert into asset_outbox_recovery_review(
            event_id,expected_review_version,review_version,prior_status,prior_last_error_code,
            prior_attempt_count,reviewer_subject_id,review_reason,request_fingerprint,reviewed_at)
          select event_id,?,review_version,?,?,?, ?,?,?,reviewed_at from updated
          returning event_id,review_version
        )
        select updated.event_id,updated.review_version,updated.status,updated.attempt_count,
          updated.last_error_code,updated.reviewed_at
        from updated join audit using (event_id,review_version)
        """, (rs, row) -> {
          OffsetDateTime reviewedAt = rs.getObject("reviewed_at", OffsetDateTime.class);
          return new RecoveryTruth(
              rs.getObject("event_id", UUID.class),
              rs.getLong("review_version"),
              rs.getString("status"),
              rs.getInt("attempt_count"),
              rs.getString("last_error_code"),
              reviewedAt == null ? null : reviewedAt.toInstant());
        },
        candidate.eventId(),
        expectedReviewVersion,
        expectedReviewVersion,
        candidate.status(),
        candidate.lastErrorCode(),
        candidate.attemptCount(),
        reviewerSubjectId,
        reviewReason,
        requestFingerprint).stream().findFirst();
  }

  public record Claim(UUID eventId, String aggregateType, String aggregateId, long aggregateVersion,
      String eventType, String topic, String envelopeBody, String envelopeSha256, int attemptCount, UUID leaseToken) {}

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
      long reviewVersion,
      java.time.Instant reviewedAt) {}

  public record RecoveryTruth(
      UUID eventId,
      long reviewVersion,
      String status,
      int attemptCount,
      String lastErrorCode,
      java.time.Instant reviewedAt) {}
}
