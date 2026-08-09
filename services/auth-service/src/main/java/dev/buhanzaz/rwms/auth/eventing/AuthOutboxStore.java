package dev.buhanzaz.rwms.auth.eventing;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/**
 * Persists and coordinates claims for auth's transactional-outbox records.
 *
 * <p>Claims use row locks, expiring leases, and a lease token. Publication is allowed only in
 * aggregate-version order and only when the durable outbox envelope still exactly matches the
 * authoritative domain event. This keeps the PostgreSQL event store authoritative while Kafka
 * remains at-least-once transport.
 */
@Repository
@RequiredArgsConstructor
public class AuthOutboxStore {

    private final JdbcTemplate jdbc;

    /**
     * Atomically leases one ready record whose earlier aggregate versions have settled.
     *
     * @param owner relay instance acquiring the lease
     * @param leaseDuration maximum time the lease remains valid
     * @return the fenced claim, or empty when no eligible record exists
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(String owner, Duration leaseDuration) {
        UUID leaseToken = UUID.randomUUID();
        return jdbc.query(
                        """
                        with candidate as (
                            select event_id
                              from outbox_event candidate
                             where ((status = 'PENDING' and next_attempt_at <= clock_timestamp())
                                    or (status = 'IN_FLIGHT' and lease_until < clock_timestamp()))
                               and not exists (
                                   select 1 from outbox_event earlier
                                    where earlier.aggregate_type = candidate.aggregate_type
                                      and earlier.aggregate_id = candidate.aggregate_id
                                      and earlier.aggregate_version < candidate.aggregate_version
                                      and earlier.status <> 'PUBLISHED')
                             order by created_at, event_id
                             for update skip locked
                             limit 1
                        )
                        update outbox_event event
                           set status = 'IN_FLIGHT', lease_owner = ?, lease_token = ?,
                               lease_until = clock_timestamp() + (? * interval '1 millisecond')
                          from candidate
                         where event.event_id = candidate.event_id
                        returning event.event_id, event.aggregate_type, event.aggregate_id,
                                  event.aggregate_version, event.event_type, event.topic,
                                  event.envelope_body::text, event.envelope_sha256,
                                  event.attempt_count
                        """,
                        (result, row) -> new Claim(
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

    /**
     * Verifies that the immediately preceding aggregate version is authoritative and settled.
     *
     * @param claim currently leased outbox record
     * @return {@code true} for the first version or a published/baseline predecessor
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean hasValidPredecessor(Claim claim) {
        if (claim.aggregateVersion() == 0) {
            return true;
        }
        Integer count = jdbc.queryForObject(
                """
                select count(*)
                  from domain_event previous
                 where previous.aggregate_type = ? and previous.aggregate_id = ?
                   and previous.aggregate_version = ?
                   and (previous.baseline or exists (
                       select 1 from outbox_event published
                        where published.event_id = previous.event_id and published.status = 'PUBLISHED'))
                """,
                Integer.class,
                claim.aggregateType(),
                claim.aggregateId(),
                claim.aggregateVersion() - 1);
        return count != null && count == 1;
    }

    /**
     * Verifies that a claimed outbox envelope is a canonical representation of its domain event.
     *
     * @param claim currently leased outbox record
     * @return {@code true} only when all immutable event and envelope fields agree
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public boolean hasAuthoritativeEnvelope(Claim claim) {
        Integer count = jdbc.queryForObject(
                """
                    select count(*)
                      from outbox_event outbox
                      join domain_event event on event.event_id=outbox.event_id
                     where outbox.event_id=?
                       and outbox.aggregate_type=?
                       and outbox.aggregate_id=?
                       and outbox.aggregate_version=?
                       and outbox.event_type=?
                       and outbox.topic=?
                       and outbox.envelope_body=?::jsonb
                       and outbox.envelope_sha256=?
                       and outbox.envelope_sha256=encode(
                           sha256(convert_to(outbox.envelope_body::text, 'UTF8')), 'hex')
                       and event.aggregate_type=outbox.aggregate_type
                       and event.aggregate_id=outbox.aggregate_id
                       and event.aggregate_version=outbox.aggregate_version
                       and event.event_type=outbox.event_type
                       and event.payload_sha256=encode(
                           sha256(convert_to(event.payload::text, 'UTF8')), 'hex')
                       and case
                           when jsonb_exists_all(outbox.envelope_body, array[
                               'envelopeVersion', 'eventId', 'eventType', 'eventVersion',
                               'occurredAt', 'recordedAt', 'producer', 'aggregateType',
                               'aggregateId', 'aggregateVersion', 'correlation', 'actorRef', 'payload'])
                            and outbox.envelope_body - array[
                               'envelopeVersion', 'eventId', 'eventType', 'eventVersion',
                               'occurredAt', 'recordedAt', 'producer', 'aggregateType',
                               'aggregateId', 'aggregateVersion', 'correlation', 'actorRef', 'payload'] = '{}'::jsonb
                            and jsonb_typeof(outbox.envelope_body->'correlation')='object'
                           then outbox.envelope_body->>'envelopeVersion'='2'
                            and outbox.envelope_body->>'eventId'=event.event_id::text
                            and outbox.envelope_body->>'eventType'=event.event_type
                            and outbox.envelope_body->>'eventVersion'=event.event_version::text
                            and (outbox.envelope_body->>'occurredAt')::timestamptz
                                is not distinct from event.occurred_at
                            and (outbox.envelope_body->>'recordedAt')::timestamptz=event.recorded_at
                            and outbox.envelope_body->>'producer'='auth-service'
                            and outbox.envelope_body->>'aggregateType'=event.aggregate_type
                            and outbox.envelope_body->>'aggregateId'=event.aggregate_id
                            and outbox.envelope_body->>'aggregateVersion'=event.aggregate_version::text
                            and jsonb_exists_all(
                                outbox.envelope_body->'correlation',
                                array['correlationId', 'causationId'])
                            and (outbox.envelope_body->'correlation') - array['correlationId', 'causationId']='{}'::jsonb
                            and outbox.envelope_body->'correlation'->>'correlationId'=event.correlation_id::text
                            and outbox.envelope_body->'correlation'->>'causationId'
                                is not distinct from event.causation_id::text
                            and outbox.envelope_body->'actorRef'=coalesce(event.actor_ref, 'null'::jsonb)
                            and outbox.envelope_body->'payload'=event.payload
                           else false
                       end
                """,
                Integer.class,
                claim.eventId(),
                claim.aggregateType(),
                claim.aggregateId(),
                claim.aggregateVersion(),
                claim.eventType(),
                claim.topic(),
                claim.envelopeBody(),
                claim.envelopeSha256());
        return count != null && count == 1;
    }

    /**
     * Marks a broker-acknowledged record published if the caller still owns its lease.
     *
     * @param eventId event identifier
     * @param leaseToken token issued with the claim
     * @return {@code true} when this settlement won the lease fence
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean published(UUID eventId, UUID leaseToken) {
        return jdbc.update(
                        """
                        update outbox_event
                           set status = 'PUBLISHED', published_at = clock_timestamp(),
                               lease_owner = null, lease_token = null, lease_until = null,
                               last_error_code = null
                         where event_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                        """,
                        eventId,
                        leaseToken)
                == 1;
    }

    /**
     * Records a transient publication failure with bounded exponential retry.
     *
     * <p>After the fourth failed attempt the record transitions to {@code DLT}; before that it is
     * returned to {@code PENDING} with a lease-fenced retry time.
     *
     * @param claim record whose owned lease failed to publish
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void transientFailure(Claim claim) {
        int failedAttempts = claim.attemptCount() + 1;
        if (failedAttempts >= 4) {
            dlt(claim, "PUBLISH_FAILED");
            return;
        }
        long backoffSeconds = 1L << (failedAttempts - 1);
        jdbc.update(
                """
                update outbox_event
                   set status = 'PENDING', attempt_count = ?,
                       next_attempt_at = clock_timestamp() + (? * interval '1 second'),
                       lease_owner = null, lease_token = null, lease_until = null,
                       last_error_code = 'PUBLISH_FAILED'
                 where event_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                """,
                failedAttempts,
                backoffSeconds,
                claim.eventId(),
                claim.leaseToken());
    }

    /**
     * Moves a lease-owned record directly to {@code DLT} after validation rejects it.
     *
     * @param claim record whose claimed envelope failed validation
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void validationFailure(Claim claim) {
        dlt(claim, "VALIDATION_REJECTED");
    }

    /**
     * Fences and quarantines a record whose integrity or aggregate-order check failed.
     *
     * <p>Quarantine is distinct from retryable publication failure: it intentionally does not
     * publish or automatically requeue the record.
     *
     * @param claim record that failed an authoritative integrity check
     * @param code stable non-sensitive quarantine reason
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void quarantine(Claim claim, String code) {
        jdbc.update(
                """
                update outbox_event
                   set status = 'QUARANTINED', attempt_count = attempt_count + 1,
                       lease_owner = null, lease_token = null, lease_until = null,
                       dlt_at = null, last_error_code = ?
                 where event_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                """,
                code,
                claim.eventId(),
                claim.leaseToken());
    }

    /**
     * Requeues one DLT record after an operator uses its observed attempt count as a fence.
     *
     * @param eventId event to requeue
     * @param expectedAttemptCount attempt count observed by the operator
     * @return {@code true} only when the DLT record has not changed or been leased since observed
     */
    @Transactional
    public boolean requeue(UUID eventId, int expectedAttemptCount) {
        return jdbc.update(
                        """
                        update outbox_event
                           set status = 'PENDING', attempt_count = 0,
                               next_attempt_at = clock_timestamp(), dlt_at = null,
                               last_error_code = 'OPERATOR_REQUEUED'
                         where event_id = ? and status = 'DLT' and attempt_count = ?
                           and published_at is null and lease_owner is null
                           and lease_token is null and lease_until is null
                        """,
                        eventId,
                        expectedAttemptCount)
                == 1;
    }

    private void dlt(Claim claim, String code) {
        jdbc.update(
                """
                update outbox_event
                   set status = 'DLT', attempt_count = attempt_count + 1, dlt_at = clock_timestamp(),
                       lease_owner = null, lease_token = null, lease_until = null,
                       last_error_code = ?
                 where event_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                """,
                code,
                claim.eventId(),
                claim.leaseToken());
    }

    /**
     * Immutable leased outbox record and the token required to settle its state safely.
     *
     * @param eventId authoritative event identifier
     * @param aggregateType aggregate family name
     * @param aggregateId aggregate identifier
     * @param aggregateVersion per-aggregate stream version
     * @param eventType versioned event type
     * @param topic fixed producer destination
     * @param envelopeBody canonical serialized event envelope
     * @param envelopeSha256 checksum of the canonical envelope
     * @param attemptCount failures recorded before this claim
     * @param leaseToken token that fences settlement operations
     */
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
