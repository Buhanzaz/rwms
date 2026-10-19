package dev.buhanzaz.rwms.assistant.eventing;

import static dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus.STARTED;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.assistant.repository.AssistantToolCallRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

/** Verifies lazy, fixed-name assistant recovery gauges without dynamic labels or mutations. */
class AssistantRecoveryMetricsTest {
  private static final OffsetDateTime NOW =
      OffsetDateTime.of(2026, 8, 9, 12, 0, 0, 0, ZoneOffset.UTC);
  private static final String STARTED_COUNT =
      "rwms.assistant.recovery.tool-calls.started";
  private static final String OLDEST_STARTED_AGE =
      "rwms.assistant.recovery.tool-calls.started.oldest.age.seconds";
  private static final List<String> METRIC_NAMES = List.of(STARTED_COUNT, OLDEST_STARTED_AGE);

  @Test
  void exposesFixedUntaggedRecoveryMetersLazilyForStartedCalls() {
    AssistantToolCallRepository toolCalls = mock(AssistantToolCallRepository.class);
    when(toolCalls.countByStatus(STARTED)).thenReturn(3L);
    when(toolCalls.findOldestCreatedAtByStatus(STARTED))
        .thenReturn(Optional.of(NOW.minusSeconds(120)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AssistantRecoveryMetrics(registry, toolCalls, fixedClock());

    verifyNoInteractions(toolCalls);
    List<Gauge> gauges = METRIC_NAMES.stream().map(name -> registry.get(name).gauge()).toList();
    assertThat(gauges).extracting(Gauge::value).containsExactly(3.0, 120.0);
    assertThat(registry.getMeters())
        .extracting(meter -> meter.getId().getName())
        .containsExactlyInAnyOrderElementsOf(METRIC_NAMES);
    assertThat(gauges).allSatisfy(gauge -> assertThat(gauge.getId().getTags()).isEmpty());
  }

  @Test
  void emptyHealthyStartedSetProducesZeroGauges() {
    AssistantToolCallRepository toolCalls = mock(AssistantToolCallRepository.class);
    when(toolCalls.countByStatus(STARTED)).thenReturn(0L);
    when(toolCalls.findOldestCreatedAtByStatus(STARTED)).thenReturn(Optional.empty());
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AssistantRecoveryMetrics(registry, toolCalls, fixedClock());

    assertThat(METRIC_NAMES.stream().map(name -> registry.get(name).gauge().value()).toList())
        .containsOnly(0.0);
  }

  @Test
  void databaseFailureProducesUnknownRatherThanHealthyZero() {
    AssistantToolCallRepository toolCalls = mock(AssistantToolCallRepository.class);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("database unavailable");
    when(toolCalls.countByStatus(STARTED)).thenThrow(failure);
    when(toolCalls.findOldestCreatedAtByStatus(STARTED)).thenThrow(failure);
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AssistantRecoveryMetrics(registry, toolCalls, fixedClock());

    assertThat(METRIC_NAMES.stream().map(name -> registry.get(name).gauge().value()).toList())
        .allSatisfy(value -> assertThat(value).isNaN());
  }

  @Test
  void futureStartedTimestampProducesZeroAgeInsteadOfNegativeGaugeValue() {
    AssistantToolCallRepository toolCalls = mock(AssistantToolCallRepository.class);
    when(toolCalls.findOldestCreatedAtByStatus(STARTED))
        .thenReturn(Optional.of(NOW.plusSeconds(1)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();

    new AssistantRecoveryMetrics(registry, toolCalls, fixedClock());

    assertThat(registry.get(OLDEST_STARTED_AGE).gauge().value()).isZero();
  }

  /** Provides deterministic scrape time without changing a production tool-call transition. */
  private static Clock fixedClock() {
    return Clock.fixed(NOW.toInstant(), ZoneOffset.UTC);
  }
}
