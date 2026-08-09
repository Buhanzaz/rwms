package dev.buhanzaz.rwms.analytics.eventing;

import dev.buhanzaz.rwms.analytics.repository.AnalyticsAggregateCheckpointRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSanitizedDeadLetterRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.IntSupplier;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Exposes low-cardinality, read-only analytics recovery gauges.
 *
 * <p>Each gauge evaluates a service-owned Spring Data JPA query lazily at scrape time and does not
 * register labels, identifiers, topics, payloads, or error text. A pre-existing global Micrometer
 * {@code application} common tag may still be attached outside this component. These observations
 * never make a KPI period {@code COMPLETE}, request producer replay, or mutate gap and sanitized
 * DLT state. The injected clock calculates displayed ages only; it is not a recovery fence or
 * source of state-transition time.
 */
@Component
public final class AnalyticsRecoveryMetrics {
  private static final String ACTIVE_OPEN_GAPS = "rwms.analytics.recovery.gaps.active";
  private static final String TERMINAL_OPEN_GAPS = "rwms.analytics.recovery.gaps.terminal";
  private static final String OLDEST_OPEN_GAP_AGE_SECONDS =
      "rwms.analytics.recovery.gaps.oldest.age.seconds";
  private static final String MAXIMUM_OPEN_GAP_ATTEMPT =
      "rwms.analytics.recovery.gaps.maximum.attempt";
  private static final String DLT_BACKLOG = "rwms.analytics.recovery.dlt.backlog";
  private static final String OLDEST_DLT_BACKLOG_AGE_SECONDS =
      "rwms.analytics.recovery.dlt.backlog.oldest.age.seconds";
  private static final String TERMINAL_DLT = "rwms.analytics.recovery.dlt.terminal";

  private final Clock clock;

  /**
   * Registers the seven lazy recovery gauges in the service meter registry.
   *
   * <p>A repository access failure yields {@link Double#NaN}, not a fabricated healthy zero.
   *
   * @param registry service meter registry receiving the gauges
   * @param checkpoints service-owned checkpoint read boundary
   * @param deadLetters service-owned sanitized DLT read boundary
   * @param clock observation-only source for displayed ages
   */
  public AnalyticsRecoveryMetrics(
      MeterRegistry registry,
      AnalyticsAggregateCheckpointRepository checkpoints,
      AnalyticsSanitizedDeadLetterRepository deadLetters,
      Clock clock) {
    this.clock = clock;

    Gauge.builder(ACTIVE_OPEN_GAPS, this, ignored -> count(checkpoints::countActiveOpenGaps))
        .description("Open analytics gaps eligible for bounded local retry")
        .register(registry);
    Gauge.builder(
            TERMINAL_OPEN_GAPS,
            this,
            ignored -> count(checkpoints::countTerminallyBlockedGaps))
        .description("Open analytics gaps requiring reviewed owner-authoritative recovery")
        .register(registry);
    Gauge.builder(
            OLDEST_OPEN_GAP_AGE_SECONDS,
            this,
            ignored -> oldestAgeSeconds(checkpoints::findOldestOpenGapFirstSeenAt))
        .description("Age of the oldest retained analytics open gap")
        .register(registry);
    Gauge.builder(
            MAXIMUM_OPEN_GAP_ATTEMPT,
            this,
            ignored -> count(checkpoints::findMaximumOpenGapAttemptCount))
        .description("Maximum local retry attempt among retained analytics open gaps")
        .register(registry);
    Gauge.builder(DLT_BACKLOG, this, ignored -> count(deadLetters::countRecoverableBacklog))
        .description("Sanitized analytics DLT records pending publication or retry")
        .register(registry);
    Gauge.builder(
            OLDEST_DLT_BACKLOG_AGE_SECONDS,
            this,
            ignored -> oldestAgeSeconds(deadLetters::findOldestRecoverableBacklogAt))
        .description(
            "Age of the oldest sanitized analytics DLT record pending publication or retry")
        .register(registry);
    Gauge.builder(TERMINAL_DLT, this, ignored -> count(deadLetters::countTerminallyDeadLettered))
        .description("Sanitized analytics DLT records requiring reviewed recovery")
        .register(registry);
  }

  private double count(LongSupplier query) {
    try {
      return query.getAsLong();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  private double count(IntSupplier query) {
    try {
      return query.getAsInt();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  /**
   * Converts the oldest observed record timestamp to a non-negative displayed age.
   *
   * <p>The clock is an observation aid only. A future timestamp is shown as zero rather than a
   * negative age, and a database error remains unknown rather than healthy.
   */
  private double oldestAgeSeconds(Supplier<Optional<OffsetDateTime>> query) {
    try {
      Optional<OffsetDateTime> oldest = query.get();
      if (oldest.isEmpty()) {
        return 0;
      }
      return Math.max(
          0, Duration.between(oldest.orElseThrow(), OffsetDateTime.now(clock)).toSeconds());
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }
}
