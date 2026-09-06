package dev.buhanzaz.rwms.logistics.eventing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataRetrievalFailureException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Tests recovery metric registration, cardinality, zero/future handling, and database failures. */
class LogisticsRecoveryMetricsTest {
  private static final Instant NOW = Instant.parse("2026-08-09T09:00:00Z");
  private static final List<String> DATABASE_METRICS =
      List.of(
          "rwms.logistics.outbox.backlog",
          "rwms.logistics.outbox.backlog.oldest.age.seconds",
          "rwms.logistics.outbox.terminal",
          "rwms.logistics.sanitized_dlt.backlog",
          "rwms.logistics.sanitized_dlt.backlog.oldest.age.seconds",
          "rwms.logistics.sanitized_dlt.terminal",
          "rwms.logistics.rental_inquiry.outbox.backlog",
          "rwms.logistics.rental_inquiry.outbox.backlog.oldest.age.seconds",
          "rwms.logistics.rental_inquiry.outbox.terminal",
          "rwms.logistics.presentation_booking.recovery.backlog",
          "rwms.logistics.presentation_booking.recovery.backlog.oldest.age.seconds",
          "rwms.logistics.presentation_booking.recovery.quarantined",
          "rwms.logistics.customer_checkout.recovery.backlog",
          "rwms.logistics.customer_checkout.recovery.backlog.oldest.age.seconds",
          "rwms.logistics.customer_checkout.recovery.quarantined",
          "rwms.logistics.warehouse_mark.backlog",
          "rwms.logistics.warehouse_mark.backlog.oldest.age.seconds",
          "rwms.logistics.warehouse_mark.terminal",
          "rwms.logistics.inbound.gap.open",
          "rwms.logistics.inbound.gap.open.oldest.age.seconds",
          "rwms.logistics.inbound.checkpoint.blocked",
          "rwms.logistics.external_attempt.active",
          "rwms.logistics.external_attempt.active.oldest.age.seconds",
          "rwms.logistics.external_attempt.retry.max",
          "rwms.logistics.external_attempt.reconciliation_required");
  private static final List<String> EXECUTOR_METRICS =
      List.of(
          "rwms.logistics.external_attempt.executor.active",
          "rwms.logistics.external_attempt.executor.queue");

  @Test
  void registersOnlyFixedNameGaugesWithoutQueryingAtConstruction() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    registry.config().commonTags("application", "logistics-service");
    LogisticsRecoveryObservationStore observations = mock(LogisticsRecoveryObservationStore.class);
    LogisticsExternalAttemptRepository attempts = mock(LogisticsExternalAttemptRepository.class);
    ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);

    new LogisticsRecoveryMetrics(
        registry, observations, attempts, executor, Clock.fixed(NOW, ZoneOffset.UTC));

    assertThat(registry.getMeters()).hasSize(DATABASE_METRICS.size() + EXECUTOR_METRICS.size());
    for (String name : allMetricNames()) {
      Gauge gauge = registry.get(name).gauge();
      assertThat(gauge.getId().getTags())
          .extracting(tag -> tag.getKey() + "=" + tag.getValue())
          .containsExactly("application=logistics-service");
    }
    assertThat(registry.getMeters())
        .extracting(Meter::getId)
        .extracting(Meter.Id::getName)
        .doesNotHaveDuplicates();
    verifyNoInteractions(observations, attempts, executor);
  }

  @Test
  void emptyAndFutureStateProduceZero() {
    Fixture fixture = fixture();
    OffsetDateTime future = OffsetDateTime.ofInstant(NOW.plusSeconds(60), ZoneOffset.UTC);
    when(fixture.observations.findOldestMainOutboxBacklogCreatedAt())
        .thenReturn(Optional.of(future));
    when(fixture.observations.findOldestSanitizedDltBacklogCreatedAt())
        .thenReturn(Optional.of(future));
    when(fixture.observations.findOldestRentalInquiryOutboxBacklogCreatedAt())
        .thenReturn(Optional.of(future));
    when(fixture.observations.findOldestPresentationBookingRecoveryCreatedAt())
        .thenReturn(Optional.of(future));
    when(fixture.observations.findOldestCustomerCheckoutRecoveryCreatedAt())
        .thenReturn(Optional.of(future));
    when(fixture.observations.findOldestWarehouseMarkBacklogCreatedAt())
        .thenReturn(Optional.of(future));
    when(fixture.observations.findOldestOpenInboundGapDetectedAt())
        .thenReturn(Optional.of(future));
    when(fixture.attempts.findOldestCreatedAtByResultIn(anyList()))
        .thenReturn(Optional.of(future));

    for (String name : allMetricNames()) {
      assertThat(fixture.registry.get(name).gauge().value()).isZero();
    }
  }

  @Test
  void everyDatabaseFailureProducesNanWithoutAFreeFormErrorLabel() {
    Fixture fixture = fixture();
    DataRetrievalFailureException failure =
        new DataRetrievalFailureException("database unavailable");
    failEveryObservation(fixture.observations, failure);
    when(fixture.attempts.countByResultIn(anyList())).thenThrow(failure);
    when(fixture.attempts.findOldestCreatedAtByResultIn(anyList())).thenThrow(failure);
    when(fixture.attempts.findMaximumRetryCountByResultIn(anyList())).thenThrow(failure);
    when(fixture.attempts.countByResult(LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED))
        .thenThrow(failure);

    for (String name : DATABASE_METRICS) {
      assertThat(fixture.registry.get(name).gauge().value()).isNaN();
      assertThat(fixture.registry.get(name).gauge().getId().getTags()).isEmpty();
    }
    for (String name : EXECUTOR_METRICS) {
      assertThat(fixture.registry.get(name).gauge().value()).isZero();
    }
  }

  private static Fixture fixture() {
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    LogisticsRecoveryObservationStore observations = mock(LogisticsRecoveryObservationStore.class);
    LogisticsExternalAttemptRepository attempts = mock(LogisticsExternalAttemptRepository.class);
    ThreadPoolTaskExecutor executor = mock(ThreadPoolTaskExecutor.class);
    new LogisticsRecoveryMetrics(
        registry, observations, attempts, executor, Clock.fixed(NOW, ZoneOffset.UTC));
    return new Fixture(registry, observations, attempts);
  }

  private static void failEveryObservation(
      LogisticsRecoveryObservationStore observations, RuntimeException failure) {
    when(observations.countMainOutboxBacklog()).thenThrow(failure);
    when(observations.findOldestMainOutboxBacklogCreatedAt()).thenThrow(failure);
    when(observations.countTerminalMainOutbox()).thenThrow(failure);
    when(observations.countSanitizedDltBacklog()).thenThrow(failure);
    when(observations.findOldestSanitizedDltBacklogCreatedAt()).thenThrow(failure);
    when(observations.countTerminalSanitizedDlt()).thenThrow(failure);
    when(observations.countRentalInquiryOutboxBacklog()).thenThrow(failure);
    when(observations.findOldestRentalInquiryOutboxBacklogCreatedAt()).thenThrow(failure);
    when(observations.countTerminalRentalInquiryOutbox()).thenThrow(failure);
    when(observations.countPresentationBookingRecoveryBacklog()).thenThrow(failure);
    when(observations.findOldestPresentationBookingRecoveryCreatedAt()).thenThrow(failure);
    when(observations.countQuarantinedPresentationBookings()).thenThrow(failure);
    when(observations.countCustomerCheckoutRecoveryBacklog()).thenThrow(failure);
    when(observations.findOldestCustomerCheckoutRecoveryCreatedAt()).thenThrow(failure);
    when(observations.countQuarantinedCustomerCheckouts()).thenThrow(failure);
    when(observations.countWarehouseMarkBacklog()).thenThrow(failure);
    when(observations.findOldestWarehouseMarkBacklogCreatedAt()).thenThrow(failure);
    when(observations.countTerminalWarehouseMarks()).thenThrow(failure);
    when(observations.countOpenInboundGaps()).thenThrow(failure);
    when(observations.findOldestOpenInboundGapDetectedAt()).thenThrow(failure);
    when(observations.countBlockedInboundCheckpoints()).thenThrow(failure);
  }

  private static List<String> allMetricNames() {
    return java.util.stream.Stream.concat(DATABASE_METRICS.stream(), EXECUTOR_METRICS.stream())
        .toList();
  }

  /** Immutable unit-test fixture containing the registry and both mocked persistence boundaries. */
  private record Fixture(
      SimpleMeterRegistry registry,
      LogisticsRecoveryObservationStore observations,
      LogisticsExternalAttemptRepository attempts) {}
}
