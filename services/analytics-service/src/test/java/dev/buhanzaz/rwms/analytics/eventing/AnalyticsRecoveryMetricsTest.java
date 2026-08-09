package dev.buhanzaz.rwms.analytics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.analytics.repository.AnalyticsAggregateCheckpointRepository;
import dev.buhanzaz.rwms.analytics.repository.AnalyticsSanitizedDeadLetterRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Verifies lazy, read-only analytics recovery gauges without service-registered labels. */
class AnalyticsRecoveryMetricsTest {
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 8, 9, 9, 0, 0, 0, ZoneOffset.UTC);

  private static final List<String> METRIC_NAMES =
      List.of(
          "rwms.analytics.recovery.gaps.active",
          "rwms.analytics.recovery.gaps.terminal",
          "rwms.analytics.recovery.gaps.oldest.age.seconds",
          "rwms.analytics.recovery.gaps.maximum.attempt",
          "rwms.analytics.recovery.dlt.backlog",
          "rwms.analytics.recovery.dlt.backlog.oldest.age.seconds",
          "rwms.analytics.recovery.dlt.terminal");

  @Test
  void exposesFixedUntaggedRecoveryMetersLazilyForPopulatedState() {
    AnalyticsAggregateCheckpointRepository checkpoints =
        mock(AnalyticsAggregateCheckpointRepository.class);
    when(checkpoints.countActiveOpenGaps()).thenReturn(4L);
    when(checkpoints.countTerminallyBlockedGaps()).thenReturn(2L);
    when(checkpoints.findOldestOpenGapFirstSeenAt()).thenReturn(Optional.of(NOW.minusSeconds(90)));
    when(checkpoints.findMaximumOpenGapAttemptCount()).thenReturn(3);
    AnalyticsSanitizedDeadLetterRepository deadLetters =
        mock(AnalyticsSanitizedDeadLetterRepository.class);
    when(deadLetters.countRecoverableBacklog()).thenReturn(7L);
    when(deadLetters.findOldestRecoverableBacklogAt())
        .thenReturn(Optional.of(NOW.minusSeconds(45)));
    when(deadLetters.countTerminallyDeadLettered()).thenReturn(5L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AnalyticsRecoveryMetrics(registry, checkpoints, deadLetters, fixedClock());

    verifyNoInteractions(checkpoints, deadLetters);
    List<Gauge> gauges = METRIC_NAMES.stream().map(name -> registry.get(name).gauge()).toList();
    assertThat(gauges)
        .extracting(Gauge::value)
        .containsExactly(4.0, 2.0, 90.0, 3.0, 7.0, 45.0, 5.0);
    assertThat(registry.getMeters())
        .extracting(meter -> meter.getId().getName())
        .containsExactlyInAnyOrderElementsOf(METRIC_NAMES);
    assertThat(gauges).allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
  }

  @Test
  void emptyHealthyRecoverySetsProduceZeroGauges() {
    AnalyticsAggregateCheckpointRepository checkpoints =
        mock(AnalyticsAggregateCheckpointRepository.class);
    when(checkpoints.countActiveOpenGaps()).thenReturn(0L);
    when(checkpoints.countTerminallyBlockedGaps()).thenReturn(0L);
    when(checkpoints.findOldestOpenGapFirstSeenAt()).thenReturn(Optional.empty());
    when(checkpoints.findMaximumOpenGapAttemptCount()).thenReturn(0);
    AnalyticsSanitizedDeadLetterRepository deadLetters =
        mock(AnalyticsSanitizedDeadLetterRepository.class);
    when(deadLetters.countRecoverableBacklog()).thenReturn(0L);
    when(deadLetters.findOldestRecoverableBacklogAt()).thenReturn(Optional.empty());
    when(deadLetters.countTerminallyDeadLettered()).thenReturn(0L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AnalyticsRecoveryMetrics(registry, checkpoints, deadLetters, fixedClock());

    assertThat(METRIC_NAMES.stream().map(name -> registry.get(name).gauge().value()).toList())
        .containsOnly(0.0);
  }

  @Test
  void databaseFailureProducesUnknownRatherThanHealthyZero() {
    AnalyticsAggregateCheckpointRepository checkpoints =
        mock(AnalyticsAggregateCheckpointRepository.class);
    AnalyticsSanitizedDeadLetterRepository deadLetters =
        mock(AnalyticsSanitizedDeadLetterRepository.class);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("database unavailable");
    when(checkpoints.countActiveOpenGaps()).thenThrow(failure);
    when(checkpoints.countTerminallyBlockedGaps()).thenThrow(failure);
    when(checkpoints.findOldestOpenGapFirstSeenAt()).thenThrow(failure);
    when(checkpoints.findMaximumOpenGapAttemptCount()).thenThrow(failure);
    when(deadLetters.countRecoverableBacklog()).thenThrow(failure);
    when(deadLetters.findOldestRecoverableBacklogAt()).thenThrow(failure);
    when(deadLetters.countTerminallyDeadLettered()).thenThrow(failure);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AnalyticsRecoveryMetrics(registry, checkpoints, deadLetters, fixedClock());

    assertThat(METRIC_NAMES.stream().map(name -> registry.get(name).gauge().value()).toList())
        .allSatisfy(value -> assertThat(value).isNaN());
  }

  @Test
  void futureTimestampsProduceZeroAgeInsteadOfNegativeGaugeValues() {
    AnalyticsAggregateCheckpointRepository checkpoints =
        mock(AnalyticsAggregateCheckpointRepository.class);
    when(checkpoints.findOldestOpenGapFirstSeenAt()).thenReturn(Optional.of(NOW.plusSeconds(1)));
    AnalyticsSanitizedDeadLetterRepository deadLetters =
        mock(AnalyticsSanitizedDeadLetterRepository.class);
    when(deadLetters.findOldestRecoverableBacklogAt()).thenReturn(Optional.of(NOW.plusSeconds(1)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AnalyticsRecoveryMetrics(registry, checkpoints, deadLetters, fixedClock());

    assertThat(registry.get("rwms.analytics.recovery.gaps.oldest.age.seconds").gauge().value())
        .isZero();
    assertThat(
            registry
                .get("rwms.analytics.recovery.dlt.backlog.oldest.age.seconds")
                .gauge()
                .value())
        .isZero();
  }

  private static Clock fixedClock() {
    return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
  }
}
