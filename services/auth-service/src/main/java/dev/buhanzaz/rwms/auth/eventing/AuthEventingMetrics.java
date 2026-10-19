package dev.buhanzaz.rwms.auth.eventing;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.annotation.PostConstruct;
import java.math.BigDecimal;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Publishes observability metrics for auth's event store, relays, inbox, and replay controls.
 *
 * <p>Counters describe completed attempts; gauges query durable local state so a process restart
 * does not erase operator-visible backlog, retry, or quarantine information. Database failures
 * produce {@link Double#NaN} instead of making metric collection affect application traffic.
 */
@Component
@RequiredArgsConstructor
public class AuthEventingMetrics {

    private final MeterRegistry registry;
    private final JdbcTemplate jdbc;

    private Counter replayAttempts;
    private Counter replayFailures;
    private Counter outboxPublished;
    private Counter outboxPublishFailures;
    private Counter inboxProcessed;
    private Counter inboxQuarantined;
    private Counter sanitizedDltEnqueued;
    private Counter sanitizedDltPublished;
    private Counter shadowReconciliations;

    /** Registers counters and database-backed gauges once the component is fully constructed. */
    @PostConstruct
    void register() {
        replayAttempts = counter("rwms.auth.replay.attempts", "Authoritative auth replay attempts");
        replayFailures = counter("rwms.auth.replay.failures", "Failed authoritative auth replays");
        outboxPublished = counter("rwms.auth.outbox.published", "Published auth outbox facts");
        outboxPublishFailures = counter(
                "rwms.auth.outbox.publish.failures", "Failed or quarantined auth outbox publications");
        inboxProcessed = counter("rwms.auth.inbox.processed", "Processed auth Kafka facts");
        inboxQuarantined = counter("rwms.auth.inbox.quarantined", "Quarantined auth Kafka facts");
        sanitizedDltEnqueued = counter("rwms.auth.dlt.enqueued", "Sanitized auth DLT records enqueued");
        sanitizedDltPublished = counter("rwms.auth.dlt.published", "Sanitized auth DLT records published");
        shadowReconciliations = counter(
                "rwms.auth.shadow.reconciliations", "Completed guarded auth shadow reconciliations");

        Gauge.builder("rwms.auth.outbox.backlog", this, ignored -> count(
                        "select count(*) from outbox_event where status in ('PENDING', 'IN_FLIGHT')"))
                .description("Auth outbox records awaiting broker acknowledgement")
                .register(registry);
        Gauge.builder("rwms.auth.outbox.oldest.age.seconds", this, ignored -> decimal(
                        """
                        select coalesce(extract(epoch from (clock_timestamp() - min(created_at))), 0)
                          from outbox_event where status in ('PENDING', 'IN_FLIGHT')
                        """))
                .description("Age in seconds of the oldest unpublished auth outbox record")
                .register(registry);
        Gauge.builder("rwms.auth.outbox.retry.count", this, ignored -> decimal(
                        "select coalesce(sum(attempt_count), 0) from outbox_event"))
                .description("Accumulated auth outbox retry attempts")
                .register(registry);
        Gauge.builder("rwms.auth.version.gap.open", this, ignored -> count(
                        "select count(*) from version_gap_quarantine where status = 'OPEN'"))
                .description("Open auth aggregate version gaps")
                .register(registry);
        Gauge.builder("rwms.auth.dlt.backlog", this, ignored -> count(
                        "select count(*) from sanitized_dead_letter where status in ('PENDING', 'IN_FLIGHT')"))
                .description("Sanitized auth DLT records awaiting broker acknowledgement")
                .register(registry);
    }

    /** Increments the count of authoritative-stream replay verifications attempted. */
    public void replayAttempted() {
        replayAttempts.increment();
    }

    /** Increments the count of replay verifications that ended in failure. */
    public void replayFailed() {
        replayFailures.increment();
    }

    /** Increments the count of outbox records durably marked published after broker acknowledgement. */
    public void outboxPublished() {
        outboxPublished.increment();
    }

    /** Increments the count of failed, quarantined, or lost-lease outbox publish attempts. */
    public void outboxPublishFailed() {
        outboxPublishFailures.increment();
    }

    /** Increments the count of inbox records accepted as processed, including safe duplicates. */
    public void inboxProcessed() {
        inboxProcessed.increment();
    }

    /** Increments the count of inbox records quarantined for a blocked aggregate or version gap. */
    public void inboxQuarantined() {
        inboxQuarantined.increment();
    }

    /** Increments the count of safe DLT metadata records durably queued for delivery. */
    public void sanitizedDltEnqueued() {
        sanitizedDltEnqueued.increment();
    }

    /** Increments the count of sanitized DLT records acknowledged and marked published. */
    public void sanitizedDltPublished() {
        sanitizedDltPublished.increment();
    }

    /** Increments the count of operator-approved aggregate shadow reconciliations completed. */
    public void shadowReconciled() {
        shadowReconciliations.increment();
    }

    private Counter counter(String name, String description) {
        return Counter.builder(name).description(description).register(registry);
    }

    private double count(String sql) {
        try {
            Long value = jdbc.queryForObject(sql, Long.class);
            return value == null ? 0 : value.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }

    private double decimal(String sql) {
        try {
            BigDecimal value = jdbc.queryForObject(sql, BigDecimal.class);
            return value == null ? 0 : value.doubleValue();
        } catch (DataAccessException exception) {
            return Double.NaN;
        }
    }
}
