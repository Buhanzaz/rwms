package dev.buhanzaz.rwms.dossier.eventing;

import dev.buhanzaz.rwms.dossier.repository.DossierAggregateCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Exposes fixed-cardinality, read-only dossier recovery and relay gauges.
 *
 * <p>Every supplier executes a service-owned Spring Data JPA query lazily at scrape time. This
 * component registers no labels, identifiers, topics, payloads, or error text; a pre-existing
 * fixed application common tag may still be added outside it. These observations never resolve
 * visibility coverage, make a cabin {@code COMPLETE}, activate a generation, replay a source
 * record, publish an outbox row, or take ownership of a producer command. The injected clock only
 * computes displayed ages and is not a recovery fence or transition-time source.
 */
@Component
public final class DossierRecoveryMetrics {
  private static final String BLOCKED_CHECKPOINTS = "rwms.dossier.recovery.checkpoints.blocked";
  private static final String OLDEST_BLOCKED_CHECKPOINT_AGE_SECONDS =
      "rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds";
  private static final String UNRESOLVED_UNLINKED_FACTS =
      "rwms.dossier.recovery.unlinked-facts.unresolved";
  private static final String UNRESOLVED_DLT_COVERAGE =
      "rwms.dossier.recovery.dlt.coverage.unresolved";
  private static final String ACTIVITY_OUTBOX_BACKLOG = "rwms.dossier.activity-outbox.backlog";
  private static final String OLDEST_ACTIVITY_OUTBOX_BACKLOG_AGE_SECONDS =
      "rwms.dossier.activity-outbox.backlog.oldest.age.seconds";
  private static final String TERMINAL_ACTIVITY_OUTBOX = "rwms.dossier.activity-outbox.terminal";
  private static final String DLT_BACKLOG = "rwms.dossier.recovery.dlt.backlog";
  private static final String OLDEST_DLT_BACKLOG_AGE_SECONDS =
      "rwms.dossier.recovery.dlt.backlog.oldest.age.seconds";
  private static final String TERMINAL_DLT = "rwms.dossier.recovery.dlt.terminal";

  private final Clock clock;

  /**
   * Registers the ten lazy recovery and relay gauges in the service meter registry.
   *
   * <p>Repository access failures yield {@link Double#NaN} rather than a fabricated healthy zero.
   * Empty queries yield zero, and future timestamps yield a zero age.
   *
   * @param registry service meter registry
   * @param checkpoints local aggregate-checkpoint observations
   * @param unlinkedFacts local unresolved-evidence observations
   * @param deadLetters local sanitized-DLT observations
   * @param activityOutbox local sanitized activity-outbox observations
   * @param clock observation-only source of the current time
   */
  public DossierRecoveryMetrics(
      MeterRegistry registry,
      DossierAggregateCheckpointRepository checkpoints,
      DossierUnlinkedFactRepository unlinkedFacts,
      DossierSanitizedDeadLetterRepository deadLetters,
      DossierOutboxEventRepository activityOutbox,
      Clock clock) {
    this.clock = clock;

    Gauge.builder(BLOCKED_CHECKPOINTS, this, ignored -> count(checkpoints::countBlockedCheckpoints))
        .description(
            "Retained dossier aggregate checkpoints currently blocked from ordered projection")
        .register(registry);
    Gauge.builder(
            OLDEST_BLOCKED_CHECKPOINT_AGE_SECONDS,
            this,
            ignored -> oldestAgeSeconds(checkpoints::findOldestBlockedAt))
        .description("Age of the oldest retained blocked dossier aggregate checkpoint")
        .register(registry);
    Gauge.builder(
            UNRESOLVED_UNLINKED_FACTS,
            this,
            ignored -> count(unlinkedFacts::countByResolvedAtIsNull))
        .description("Retained unresolved dossier unlinked facts across projection generations")
        .register(registry);
    Gauge.builder(
            UNRESOLVED_DLT_COVERAGE,
            this,
            ignored -> count(deadLetters::countUnresolvedCoverage))
        .description(
            "Retained sanitized dossier DLT rows with unresolved exact visibility coverage")
        .register(registry);
    Gauge.builder(
            ACTIVITY_OUTBOX_BACKLOG,
            this,
            ignored -> count(activityOutbox::countRecoverableBacklog))
        .description("Dossier activity-outbox rows pending publication or retry")
        .register(registry);
    Gauge.builder(
            OLDEST_ACTIVITY_OUTBOX_BACKLOG_AGE_SECONDS,
            this,
            ignored -> oldestAgeSeconds(activityOutbox::findOldestRecoverableBacklogCreatedAt))
        .description("Age of the oldest dossier activity-outbox row pending publication or retry")
        .register(registry);
    Gauge.builder(
            TERMINAL_ACTIVITY_OUTBOX,
            this,
            ignored -> count(activityOutbox::countTerminallyDeadLettered))
        .description("Dossier activity-outbox rows requiring reviewed terminal recovery")
        .register(registry);
    Gauge.builder(DLT_BACKLOG, this, ignored -> count(deadLetters::countRecoverableBacklog))
        .description("Sanitized dossier DLT rows pending publication or retry")
        .register(registry);
    Gauge.builder(
            OLDEST_DLT_BACKLOG_AGE_SECONDS,
            this,
            ignored -> oldestAgeSeconds(deadLetters::findOldestRecoverableBacklogFailedAt))
        .description("Age of the oldest sanitized dossier DLT row pending publication or retry")
        .register(registry);
    Gauge.builder(TERMINAL_DLT, this, ignored -> count(deadLetters::countTerminallyDeadLettered))
        .description("Sanitized dossier DLT rows requiring reviewed terminal recovery")
        .register(registry);
  }

  /**
   * Reads one count at scrape time while preserving an unavailable database as an unknown value.
   *
   * @param query service-owned read-only count query
   * @return query result, or {@link Double#NaN} when the database is unavailable
   */
  private static double count(LongSupplier query) {
    try {
      return query.getAsLong();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  /**
   * Converts the oldest retained timestamp into a non-negative observational age at scrape time.
   *
   * <p>An empty query is a zero backlog age. A future value is clamped to zero, while database
   * access failure remains unknown rather than appearing healthy.
   *
   * @param query service-owned read-only oldest-time query
   * @return non-negative age in seconds, zero for no retained row or future time, or NaN on failure
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
