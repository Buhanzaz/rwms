package dev.buhanzaz.rwms.maintenance.disposition.application;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Short-lived processing claims for an external-effect saga.
 *
 * <p>Claims are intentionally operational, mutable coordination state. Every claim result is also
 * inserted into the immutable attempts ledger, while business lifecycle remains on the disposition
 * aggregate and its event stream.
 */
@Repository
public class PropertyDispositionProcessingStore {
  private static final String PENDING = "PENDING";
  private static final String IN_FLIGHT = "IN_FLIGHT";
  private static final String COMPLETED = "COMPLETED";
  private static final String QUARANTINED = "QUARANTINED";

  private final JdbcTemplate jdbc;

  public PropertyDispositionProcessingStore(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public Optional<Claim> claimOne(String owner, Duration leaseDuration) {
    if (owner == null || owner.isBlank() || owner.length() > 128
        || leaseDuration == null || leaseDuration.isNegative() || leaseDuration.isZero()) {
      throw new IllegalArgumentException("Property disposition processing claim is invalid");
    }
    List<UUID> candidates = jdbc.query(
        """
        select decision.id
          from property_disposition_decision decision
          left join property_disposition_processing_claim claim
            on claim.decision_id = decision.id
         where decision.state in ('APPROVED', 'MOVEMENT_PENDING', 'EFFECT_PENDING', 'EFFECTIVE')
           and (
             claim.decision_id is null
             or (claim.status = 'PENDING' and claim.next_attempt_at <= clock_timestamp())
             or (claim.status = 'IN_FLIGHT' and claim.lease_until <= clock_timestamp())
           )
         order by decision.created_at, decision.id
         for update of decision skip locked
         limit 1
        """,
        (result, row) -> result.getObject("id", UUID.class));
    if (candidates.isEmpty()) {
      return Optional.empty();
    }
    UUID decisionId = candidates.getFirst();
    UUID token = UUID.randomUUID();
    OffsetDateTime now = now();
    OffsetDateTime leaseUntil = now.plus(leaseDuration);
    List<UUID> claimed = jdbc.query(
        """
        insert into property_disposition_processing_claim(
          decision_id, claim_token, lease_owner, lease_until, status, attempt_count,
          next_attempt_at, last_error_code, last_error_detail, updated_at)
        values (?, ?, ?, ?, 'IN_FLIGHT', 1, ?, null, null, ?)
        on conflict (decision_id) do update
          set claim_token = excluded.claim_token,
              lease_owner = excluded.lease_owner,
              lease_until = excluded.lease_until,
              status = 'IN_FLIGHT',
              attempt_count = property_disposition_processing_claim.attempt_count + 1,
              next_attempt_at = excluded.next_attempt_at,
              updated_at = excluded.updated_at
          where (property_disposition_processing_claim.status = 'PENDING'
                   and property_disposition_processing_claim.next_attempt_at <= ?)
             or (property_disposition_processing_claim.status = 'IN_FLIGHT'
                   and property_disposition_processing_claim.lease_until <= ?)
        returning decision_id
        """,
        (result, row) -> result.getObject("decision_id", UUID.class),
        decisionId,
        token,
        owner.trim(),
        leaseUntil,
        now,
        now,
        now,
        now,
        now);
    if (claimed.isEmpty()) {
      return Optional.empty();
    }
    record(decisionId, token, "CLAIM", "CLAIMED", null, null);
    return Optional.of(new Claim(decisionId, token));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retrySoon(Claim claim, String phase) {
    finish(claim, phase, PENDING, now(), "SUCCEEDED", null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void await(Claim claim, String phase, Duration delay) {
    if (delay == null || delay.isNegative()) {
      throw new IllegalArgumentException("Property disposition wait delay is invalid");
    }
    finish(claim, phase, PENDING, now().plus(delay), "WAITING", null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void retryableFailure(
      Claim claim, String phase, String code, String detail, Duration delay) {
    if (delay == null || delay.isNegative()) {
      throw new IllegalArgumentException("Property disposition retry delay is invalid");
    }
    finish(
        claim,
        phase,
        PENDING,
        now().plus(delay),
        "RETRYABLE_FAILURE",
        safe(code, 128),
        safe(detail, 2000));
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void completed(Claim claim, String phase) {
    finish(claim, phase, COMPLETED, null, "COMPLETED", null, null);
  }

  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void quarantined(Claim claim, String phase, String code, String detail) {
    finish(
        claim,
        phase,
        QUARANTINED,
        null,
        "QUARANTINED",
        safe(code, 128),
        safe(detail, 2000));
  }

  /** Reopens a human-recovered decision in the caller's transaction. */
  @Transactional(propagation = Propagation.MANDATORY)
  public void requeue(UUID decisionId) {
    if (decisionId == null) {
      throw new IllegalArgumentException("Property disposition decision ID is required");
    }
    OffsetDateTime now = now();
    jdbc.update(
        """
        insert into property_disposition_processing_claim(
          decision_id, claim_token, lease_owner, lease_until, status, attempt_count,
          next_attempt_at, last_error_code, last_error_detail, updated_at)
        values (?, null, null, null, 'PENDING', 0, ?, null, null, ?)
        on conflict (decision_id) do update
          set claim_token = null,
              lease_owner = null,
              lease_until = null,
              status = 'PENDING',
              next_attempt_at = excluded.next_attempt_at,
              last_error_code = null,
              last_error_detail = null,
              updated_at = excluded.updated_at
        """,
        decisionId,
        now,
        now);
  }

  private void finish(
      Claim claim,
      String phase,
      String targetStatus,
      OffsetDateTime nextAttemptAt,
      String outcome,
      String code,
      String detail) {
    if (claim == null || phase == null || phase.isBlank() || phase.length() > 64) {
      throw new IllegalArgumentException("Property disposition processing completion is invalid");
    }
    OffsetDateTime now = now();
    int changed = jdbc.update(
        """
        update property_disposition_processing_claim
           set claim_token = null,
               lease_owner = null,
               lease_until = null,
               status = ?,
               next_attempt_at = coalesce(?, next_attempt_at),
               last_error_code = ?,
               last_error_detail = ?,
               updated_at = ?
         where decision_id = ?
           and claim_token = ?
           and status = 'IN_FLIGHT'
        """,
        targetStatus,
        nextAttemptAt,
        code,
        detail,
        now,
        claim.decisionId(),
        claim.token());
    if (changed == 1) {
      record(claim.decisionId(), claim.token(), phase.trim(), outcome, code, detail);
    }
  }

  private void record(
      UUID decisionId,
      UUID token,
      String phase,
      String outcome,
      String code,
      String detail) {
    jdbc.update(
        """
        insert into property_disposition_processing_attempt(
          id, decision_id, claim_token, phase, outcome, failure_code, failure_detail, created_at)
        values (?, ?, ?, ?, ?, ?, ?, ?)
        """,
        UUID.randomUUID(),
        decisionId,
        token,
        phase,
        outcome,
        code,
        detail,
        now());
  }

  private static String safe(String value, int maximum) {
    String normalized = value == null ? null : value.trim();
    if (normalized == null || normalized.isEmpty()) {
      return null;
    }
    return normalized.length() <= maximum ? normalized : normalized.substring(0, maximum);
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.UTC);
  }

  public record Claim(UUID decisionId, UUID token) {
    public Claim {
      if (decisionId == null || token == null) {
        throw new IllegalArgumentException("Property disposition processing claim identity is required");
      }
    }
  }
}
