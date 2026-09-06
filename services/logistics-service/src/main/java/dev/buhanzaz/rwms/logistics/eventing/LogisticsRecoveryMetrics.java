package dev.buhanzaz.rwms.logistics.eventing;

import dev.buhanzaz.rwms.logistics.domain.LogisticsExternalAttemptResult;
import dev.buhanzaz.rwms.logistics.repository.LogisticsExternalAttemptRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DataAccessException;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Exposes fixed-name, read-only logistics recovery and relay observations.
 *
 * <p>Database gauges lazily call either the exact technical JDBC observation adapter or read-only
 * Spring Data JPA projections. Empty queues and future timestamps produce zero, while a database
 * access failure produces {@link Double#NaN} instead of a fabricated healthy value. The component
 * adds no identifiers, topics, payloads, or exception labels; the common application tag is owned
 * by runtime configuration outside this component. Scraping never claims, retries, publishes, or
 * resolves durable work.
 */
@Component
public final class LogisticsRecoveryMetrics {
  private static final List<LogisticsExternalAttemptResult> ACTIVE_ATTEMPT_RESULTS =
      List.of(LogisticsExternalAttemptResult.PENDING, LogisticsExternalAttemptResult.RETRY);

  private final Clock clock;

  /**
   * Registers fixed-cardinality gauges for every logistics-owned technical recovery queue.
   *
   * @param registry service meter registry
   * @param observations read-only technical queue observations
   * @param attempts read-only Spring Data observations of external attempts
   * @param workerExecutor bounded external-call executor
   * @param clock observation-only source used to calculate displayed ages
   */
  public LogisticsRecoveryMetrics(
      MeterRegistry registry,
      LogisticsRecoveryObservationStore observations,
      LogisticsExternalAttemptRepository attempts,
      @Qualifier("logisticsExternalAttemptWorkerExecutor") ThreadPoolTaskExecutor workerExecutor,
      Clock clock) {
    this.clock = clock;

    countGauge(
        registry,
        "rwms.logistics.outbox.backlog",
        observations::countMainOutboxBacklog,
        "Main logistics outbox rows awaiting broker acknowledgement");
    ageGauge(
        registry,
        "rwms.logistics.outbox.backlog.oldest.age.seconds",
        observations::findOldestMainOutboxBacklogCreatedAt,
        "Age of the oldest main logistics outbox row awaiting acknowledgement");
    countGauge(
        registry,
        "rwms.logistics.outbox.terminal",
        observations::countTerminalMainOutbox,
        "Main logistics outbox rows requiring reviewed terminal recovery");

    countGauge(
        registry,
        "rwms.logistics.sanitized_dlt.backlog",
        observations::countSanitizedDltBacklog,
        "Sanitized logistics DLT rows pending publication or in a relay lease");
    ageGauge(
        registry,
        "rwms.logistics.sanitized_dlt.backlog.oldest.age.seconds",
        observations::findOldestSanitizedDltBacklogCreatedAt,
        "Age of the oldest recoverable sanitized logistics DLT row");
    countGauge(
        registry,
        "rwms.logistics.sanitized_dlt.terminal",
        observations::countTerminalSanitizedDlt,
        "Sanitized logistics DLT rows whose bounded relay attempts failed terminally");

    countGauge(
        registry,
        "rwms.logistics.rental_inquiry.outbox.backlog",
        observations::countRentalInquiryOutboxBacklog,
        "Rental-inquiry booking events awaiting Kafka acknowledgement");
    ageGauge(
        registry,
        "rwms.logistics.rental_inquiry.outbox.backlog.oldest.age.seconds",
        observations::findOldestRentalInquiryOutboxBacklogCreatedAt,
        "Age of the oldest pending rental-inquiry booking event");
    countGauge(
        registry,
        "rwms.logistics.rental_inquiry.outbox.terminal",
        observations::countTerminalRentalInquiryOutbox,
        "Rental-inquiry booking events requiring reviewed recovery");

    countGauge(
        registry,
        "rwms.logistics.presentation_booking.recovery.backlog",
        observations::countPresentationBookingRecoveryBacklog,
        "Presentation bookings retained for automatic recovery");
    ageGauge(
        registry,
        "rwms.logistics.presentation_booking.recovery.backlog.oldest.age.seconds",
        observations::findOldestPresentationBookingRecoveryCreatedAt,
        "Age of the oldest presentation booking retained for automatic recovery");
    countGauge(
        registry,
        "rwms.logistics.presentation_booking.recovery.quarantined",
        observations::countQuarantinedPresentationBookings,
        "Presentation bookings requiring reviewed recovery");

    countGauge(
        registry,
        "rwms.logistics.customer_checkout.recovery.backlog",
        observations::countCustomerCheckoutRecoveryBacklog,
        "Customer checkout receipts retained for automatic recovery");
    ageGauge(
        registry,
        "rwms.logistics.customer_checkout.recovery.backlog.oldest.age.seconds",
        observations::findOldestCustomerCheckoutRecoveryCreatedAt,
        "Age of the oldest customer checkout retained for automatic recovery");
    countGauge(
        registry,
        "rwms.logistics.customer_checkout.recovery.quarantined",
        observations::countQuarantinedCustomerCheckouts,
        "Customer checkout receipts requiring reviewed recovery");

    countGauge(
        registry,
        "rwms.logistics.warehouse_mark.backlog",
        observations::countWarehouseMarkBacklog,
        "Warehouse-operation marks pending, retryable, or currently leased");
    ageGauge(
        registry,
        "rwms.logistics.warehouse_mark.backlog.oldest.age.seconds",
        observations::findOldestWarehouseMarkBacklogCreatedAt,
        "Age of the oldest unfinished warehouse-operation mark");
    countGauge(
        registry,
        "rwms.logistics.warehouse_mark.terminal",
        observations::countTerminalWarehouseMarks,
        "Warehouse-operation marks requiring reviewed terminal recovery");

    countGauge(
        registry,
        "rwms.logistics.inbound.gap.open",
        observations::countOpenInboundGaps,
        "Retained unresolved logistics inbound aggregate-version gaps");
    ageGauge(
        registry,
        "rwms.logistics.inbound.gap.open.oldest.age.seconds",
        observations::findOldestOpenInboundGapDetectedAt,
        "Age of the oldest retained open logistics inbound gap");
    countGauge(
        registry,
        "rwms.logistics.inbound.checkpoint.blocked",
        observations::countBlockedInboundCheckpoints,
        "Logistics inbound aggregate checkpoints blocked from ordered projection");

    countGauge(
        registry,
        "rwms.logistics.external_attempt.active",
        () -> attempts.countByResultIn(ACTIVE_ATTEMPT_RESULTS),
        "Durable external attempts still pending or retryable");
    ageGauge(
        registry,
        "rwms.logistics.external_attempt.active.oldest.age.seconds",
        () -> attempts.findOldestCreatedAtByResultIn(ACTIVE_ATTEMPT_RESULTS),
        "Age of the oldest durable external attempt still pending or retryable");
    countGauge(
        registry,
        "rwms.logistics.external_attempt.retry.max",
        () -> attempts.findMaximumRetryCountByResultIn(ACTIVE_ATTEMPT_RESULTS),
        "Maximum retry count among active durable external attempts");
    countGauge(
        registry,
        "rwms.logistics.external_attempt.reconciliation_required",
        () -> attempts.countByResult(LogisticsExternalAttemptResult.RECONCILIATION_REQUIRED),
        "Durable external attempts requiring explicit reconciliation");

    Gauge.builder(
            "rwms.logistics.external_attempt.executor.active",
            workerExecutor,
            ThreadPoolTaskExecutor::getActiveCount)
        .description("External-attempt worker threads currently executing remote calls")
        .register(registry);
    Gauge.builder(
            "rwms.logistics.external_attempt.executor.queue",
            workerExecutor,
            ThreadPoolTaskExecutor::getQueueSize)
        .description("External-attempt remote calls waiting in the bounded worker queue")
        .register(registry);
  }

  /** Registers one lazy count gauge with fail-closed database-error behavior. */
  private void countGauge(
      MeterRegistry registry, String name, LongSupplier query, String description) {
    Gauge.builder(name, this, ignored -> count(query)).description(description).register(registry);
  }

  /** Registers one lazy oldest-age gauge computed against the observation clock. */
  private void ageGauge(
      MeterRegistry registry,
      String name,
      Supplier<Optional<OffsetDateTime>> query,
      String description) {
    Gauge.builder(name, this, ignored -> oldestAgeSeconds(query))
        .description(description)
        .register(registry);
  }

  /** Returns a count query result, or NaN when the service database is unavailable. */
  private static double count(LongSupplier query) {
    try {
      return query.getAsLong();
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  /**
   * Converts the oldest retained timestamp to a non-negative displayed age.
   *
   * @return zero for an empty queue or future timestamp, or NaN on database access failure
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
