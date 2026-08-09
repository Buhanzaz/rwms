package dev.buhanzaz.rwms.auth.eventing;

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

/**
 * Durable store for safe dead-letter metadata produced by auth Kafka consumers.
 *
 * <p>Records are idempotent by destination, message checksum, and failure code. Claims use
 * expiring leases and tokens so multiple instances can retry delivery without storing or
 * forwarding the rejected source payload.
 */
@Repository
@RequiredArgsConstructor
public class AuthSanitizedDltStore {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /**
     * Idempotently queues a canonical, safe DLT body for later broker delivery.
     *
     * @param destination fixed sanitized-DLT destination
     * @param messageSha256 checksum of the rejected source message
     * @param failureCode stable non-sensitive rejection classification
     * @param safeBody allow-listed DLT metadata; never the original message
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void enqueue(
            String destination,
            String messageSha256,
            String failureCode,
            Map<String, String> safeBody) {
        String canonicalBody = canonicalJson(write(safeBody));
        UUID dltId = deterministicId(destination, messageSha256, failureCode);
        jdbc.update(
                """
                insert into sanitized_dead_letter(
                    dlt_id, destination, message_sha256, failure_code,
                    safe_body, body_sha256, status, attempt_count,
                    next_attempt_at, created_at)
                values (?, ?, ?, ?, ?::jsonb, ?, 'PENDING', 0,
                        clock_timestamp(), clock_timestamp())
                on conflict (dlt_id) do nothing
                """,
                dltId,
                destination,
                messageSha256,
                failureCode,
                canonicalBody,
                AuthEventStore.sha256(canonicalBody.getBytes(StandardCharsets.UTF_8)));
    }

    private static UUID deterministicId(
            String destination, String messageSha256, String failureCode) {
        String identity = destination + '\u001f' + messageSha256 + '\u001f' + failureCode;
        return UUID.nameUUIDFromBytes(identity.getBytes(StandardCharsets.UTF_8));
    }

    /**
     * Atomically leases one ready or expired sanitized-DLT record.
     *
     * @param owner relay instance acquiring the lease
     * @param leaseDuration maximum time the lease remains valid
     * @return the fenced claim, or empty when no record is ready
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Optional<Claim> claim(String owner, Duration leaseDuration) {
        UUID leaseToken = UUID.randomUUID();
        return jdbc.query(
                        """
                        with candidate as (
                            select dlt_id from sanitized_dead_letter
                             where (status = 'PENDING' and next_attempt_at <= clock_timestamp())
                                or (status = 'IN_FLIGHT' and lease_until < clock_timestamp())
                             order by created_at, dlt_id for update skip locked limit 1
                        )
                        update sanitized_dead_letter dlt
                           set status = 'IN_FLIGHT', lease_owner = ?, lease_token = ?,
                               lease_until = clock_timestamp() + (? * interval '1 millisecond')
                          from candidate where dlt.dlt_id = candidate.dlt_id
                        returning dlt.dlt_id, dlt.destination, dlt.safe_body::text,
                                  dlt.body_sha256, dlt.attempt_count
                        """,
                        (result, row) -> new Claim(
                                result.getObject("dlt_id", UUID.class),
                                result.getString("destination"),
                                result.getString("safe_body"),
                                result.getString("body_sha256").trim(),
                                result.getInt("attempt_count"),
                                leaseToken),
                        owner,
                        leaseToken,
                        leaseDuration.toMillis())
                .stream()
                .findFirst();
    }

    /**
     * Marks a claimed DLT record published after broker acknowledgement.
     *
     * @param claim record and lease token to settle
     * @return {@code true} when the claim still owned the record
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean published(Claim claim) {
        return jdbc.update(
                        """
                        update sanitized_dead_letter
                           set status = 'PUBLISHED', published_at = clock_timestamp(),
                               lease_owner = null, lease_token = null, lease_until = null
                         where dlt_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                        """,
                        claim.id(),
                        claim.leaseToken())
                == 1;
    }

    /**
     * Records a failed DLT delivery and retries it with bounded exponential backoff.
     *
     * <p>After the fourth failed attempt the durable record remains in {@code FAILED} for explicit
     * operator action instead of retrying indefinitely.
     *
     * @param claim record whose owned lease failed to publish
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void failed(Claim claim) {
        int attempts = claim.attemptCount() + 1;
        if (attempts >= 4) {
            jdbc.update(
                    """
                    update sanitized_dead_letter
                       set status = 'FAILED', attempt_count = ?,
                           lease_owner = null, lease_token = null, lease_until = null
                     where dlt_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                    """,
                    attempts,
                    claim.id(),
                    claim.leaseToken());
            return;
        }
        jdbc.update(
                """
                update sanitized_dead_letter
                   set status = 'PENDING', attempt_count = ?,
                       next_attempt_at = clock_timestamp() + (? * interval '1 second'),
                       lease_owner = null, lease_token = null, lease_until = null
                 where dlt_id = ? and status = 'IN_FLIGHT' and lease_token = ?
                """,
                attempts,
                1L << (attempts - 1),
                claim.id(),
                claim.leaseToken());
    }

    /**
     * Requeues a failed DLT record using its observed attempt count as an optimistic fence.
     *
     * @param id DLT record identifier
     * @param expectedAttemptCount attempt count observed by the operator
     * @return {@code true} when the record was still eligible for requeueing
     */
    @Transactional
    public boolean requeue(UUID id, int expectedAttemptCount) {
        return jdbc.update(
                        """
                        update sanitized_dead_letter
                           set status = 'PENDING', attempt_count = 0,
                               next_attempt_at = clock_timestamp()
                         where dlt_id = ? and status = 'FAILED' and attempt_count = ?
                           and published_at is null and lease_owner is null
                           and lease_token is null and lease_until is null
                        """,
                        id,
                        expectedAttemptCount)
                == 1;
    }

    private String canonicalJson(String value) {
        String canonical = jdbc.queryForObject("select (?::jsonb)::text", String.class, value);
        if (canonical == null) {
            throw new IllegalStateException("PostgreSQL did not canonicalize sanitized DLT JSON");
        }
        return canonical;
    }

    private String write(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (tools.jackson.core.JacksonException exception) {
            throw new IllegalStateException("Sanitized DLT metadata cannot be serialized");
        }
    }

    /**
     * Immutable lease of a sanitized DLT record.
     *
     * @param id durable DLT identifier
     * @param destination Kafka destination for safe metadata
     * @param safeBody canonical safe metadata body
     * @param bodySha256 checksum used before publication
     * @param attemptCount failures recorded before this claim
     * @param leaseToken token that fences settlement operations
     */
    public record Claim(
            UUID id,
            String destination,
            String safeBody,
            String bodySha256,
            int attemptCount,
            UUID leaseToken) {}
}
