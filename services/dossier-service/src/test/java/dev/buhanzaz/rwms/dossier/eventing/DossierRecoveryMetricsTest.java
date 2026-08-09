package dev.buhanzaz.rwms.dossier.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.dossier.repository.DossierAggregateCheckpointRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierOutboxEventRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierSanitizedDeadLetterRepository;
import dev.buhanzaz.rwms.dossier.repository.DossierUnlinkedFactRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Verifies lazy, fixed-cardinality dossier recovery and relay gauges without service labels. */
class DossierRecoveryMetricsTest {
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 8, 9, 9, 0, 0, 0, ZoneOffset.UTC);

  private static final List<String> METRIC_NAMES =
      List.of(
          "rwms.dossier.recovery.checkpoints.blocked",
          "rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds",
          "rwms.dossier.recovery.unlinked-facts.unresolved",
          "rwms.dossier.recovery.dlt.coverage.unresolved",
          "rwms.dossier.activity-outbox.backlog",
          "rwms.dossier.activity-outbox.backlog.oldest.age.seconds",
          "rwms.dossier.activity-outbox.terminal",
          "rwms.dossier.recovery.dlt.backlog",
          "rwms.dossier.recovery.dlt.backlog.oldest.age.seconds",
          "rwms.dossier.recovery.dlt.terminal");

  @Test
  void exposesFixedUntaggedMetricsLazilyForPopulatedState() {
    DossierAggregateCheckpointRepository checkpoints =
        mock(DossierAggregateCheckpointRepository.class);
    when(checkpoints.countBlockedCheckpoints()).thenReturn(4L);
    when(checkpoints.findOldestBlockedAt()).thenReturn(Optional.of(NOW.minusSeconds(90)));
    DossierUnlinkedFactRepository unlinkedFacts = mock(DossierUnlinkedFactRepository.class);
    when(unlinkedFacts.countByResolvedAtIsNull()).thenReturn(3L);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    when(deadLetters.countUnresolvedCoverage()).thenReturn(2L);
    when(deadLetters.countRecoverableBacklog()).thenReturn(7L);
    when(deadLetters.findOldestRecoverableBacklogFailedAt())
        .thenReturn(Optional.of(NOW.minusSeconds(45)));
    when(deadLetters.countTerminallyDeadLettered()).thenReturn(5L);
    DossierOutboxEventRepository activityOutbox = mock(DossierOutboxEventRepository.class);
    when(activityOutbox.countRecoverableBacklog()).thenReturn(6L);
    when(activityOutbox.findOldestRecoverableBacklogCreatedAt())
        .thenReturn(Optional.of(NOW.minusSeconds(60)));
    when(activityOutbox.countTerminallyDeadLettered()).thenReturn(1L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new DossierRecoveryMetrics(
        registry, checkpoints, unlinkedFacts, deadLetters, activityOutbox, fixedClock());

    verifyNoInteractions(checkpoints, unlinkedFacts, deadLetters, activityOutbox);
    List<Gauge> gauges = METRIC_NAMES.stream().map(name -> registry.get(name).gauge()).toList();
    assertThat(gauges)
        .extracting(Gauge::value)
        .containsExactly(4.0, 90.0, 3.0, 2.0, 6.0, 60.0, 1.0, 7.0, 45.0, 5.0);
    assertThat(registry.getMeters())
        .extracting(meter -> meter.getId().getName())
        .containsExactlyInAnyOrderElementsOf(METRIC_NAMES);
    assertThat(gauges).allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
  }

  @Test
  void emptyRecoveryAndRelaySetsProduceZeroGauges() {
    DossierAggregateCheckpointRepository checkpoints =
        mock(DossierAggregateCheckpointRepository.class);
    when(checkpoints.countBlockedCheckpoints()).thenReturn(0L);
    when(checkpoints.findOldestBlockedAt()).thenReturn(Optional.empty());
    DossierUnlinkedFactRepository unlinkedFacts = mock(DossierUnlinkedFactRepository.class);
    when(unlinkedFacts.countByResolvedAtIsNull()).thenReturn(0L);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    when(deadLetters.countUnresolvedCoverage()).thenReturn(0L);
    when(deadLetters.countRecoverableBacklog()).thenReturn(0L);
    when(deadLetters.findOldestRecoverableBacklogFailedAt()).thenReturn(Optional.empty());
    when(deadLetters.countTerminallyDeadLettered()).thenReturn(0L);
    DossierOutboxEventRepository activityOutbox = mock(DossierOutboxEventRepository.class);
    when(activityOutbox.countRecoverableBacklog()).thenReturn(0L);
    when(activityOutbox.findOldestRecoverableBacklogCreatedAt()).thenReturn(Optional.empty());
    when(activityOutbox.countTerminallyDeadLettered()).thenReturn(0L);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new DossierRecoveryMetrics(
        registry, checkpoints, unlinkedFacts, deadLetters, activityOutbox, fixedClock());

    assertThat(METRIC_NAMES.stream().map(name -> registry.get(name).gauge().value()).toList())
        .containsOnly(0.0);
  }

  @Test
  void databaseFailureProducesUnknownRatherThanHealthyZero() {
    DossierAggregateCheckpointRepository checkpoints =
        mock(DossierAggregateCheckpointRepository.class);
    DossierUnlinkedFactRepository unlinkedFacts = mock(DossierUnlinkedFactRepository.class);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    DossierOutboxEventRepository activityOutbox = mock(DossierOutboxEventRepository.class);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("database unavailable");
    when(checkpoints.countBlockedCheckpoints()).thenThrow(failure);
    when(checkpoints.findOldestBlockedAt()).thenThrow(failure);
    when(unlinkedFacts.countByResolvedAtIsNull()).thenThrow(failure);
    when(deadLetters.countUnresolvedCoverage()).thenThrow(failure);
    when(deadLetters.countRecoverableBacklog()).thenThrow(failure);
    when(deadLetters.findOldestRecoverableBacklogFailedAt()).thenThrow(failure);
    when(deadLetters.countTerminallyDeadLettered()).thenThrow(failure);
    when(activityOutbox.countRecoverableBacklog()).thenThrow(failure);
    when(activityOutbox.findOldestRecoverableBacklogCreatedAt()).thenThrow(failure);
    when(activityOutbox.countTerminallyDeadLettered()).thenThrow(failure);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new DossierRecoveryMetrics(
        registry, checkpoints, unlinkedFacts, deadLetters, activityOutbox, fixedClock());

    assertThat(METRIC_NAMES.stream().map(name -> registry.get(name).gauge().value()).toList())
        .allSatisfy(value -> assertThat(value).isNaN());
  }

  @Test
  void futureTimestampsProduceZeroAgeInsteadOfNegativeGaugeValues() {
    DossierAggregateCheckpointRepository checkpoints =
        mock(DossierAggregateCheckpointRepository.class);
    when(checkpoints.findOldestBlockedAt()).thenReturn(Optional.of(NOW.plusSeconds(1)));
    DossierUnlinkedFactRepository unlinkedFacts = mock(DossierUnlinkedFactRepository.class);
    DossierSanitizedDeadLetterRepository deadLetters =
        mock(DossierSanitizedDeadLetterRepository.class);
    when(deadLetters.findOldestRecoverableBacklogFailedAt())
        .thenReturn(Optional.of(NOW.plusSeconds(1)));
    DossierOutboxEventRepository activityOutbox = mock(DossierOutboxEventRepository.class);
    when(activityOutbox.findOldestRecoverableBacklogCreatedAt())
        .thenReturn(Optional.of(NOW.plusSeconds(1)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new DossierRecoveryMetrics(
        registry, checkpoints, unlinkedFacts, deadLetters, activityOutbox, fixedClock());

    assertThat(
            registry
                .get("rwms.dossier.recovery.checkpoints.blocked.oldest.age.seconds")
                .gauge()
                .value())
        .isZero();
    assertThat(
            registry
                .get("rwms.dossier.activity-outbox.backlog.oldest.age.seconds")
                .gauge()
                .value())
        .isZero();
    assertThat(
            registry
                .get("rwms.dossier.recovery.dlt.backlog.oldest.age.seconds")
                .gauge()
                .value())
        .isZero();
  }

  /** Provides deterministic observation time without changing a production recovery transition. */
  private static Clock fixedClock() {
    return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
  }
}
