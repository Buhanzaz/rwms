package dev.buhanzaz.rwms.assistant.eventing;

import dev.buhanzaz.rwms.assistant.domain.AssistantToolCallStatus;
import dev.buhanzaz.rwms.assistant.repository.AssistantToolCallRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.Optional;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Component;

/**
 * Exposes fixed-name, read-only gauges for durable assistant tool calls left in {@code STARTED}.
 *
 * <p>The gauges execute payload-free Spring Data JPA queries lazily at scrape time. They add no
 * conversation, turn, tool, failure, or payload labels and never complete, fail, retry, or
 * otherwise mutate a tool call. Completed and failed tool calls are valid terminal history and are
 * deliberately excluded from the recovery backlog.
 */
@Component
public final class AssistantRecoveryMetrics {
  private static final String STARTED_TOOL_CALLS =
      "rwms.assistant.recovery.tool-calls.started";
  private static final String OLDEST_STARTED_TOOL_CALL_AGE_SECONDS =
      "rwms.assistant.recovery.tool-calls.started.oldest.age.seconds";

  private final AssistantToolCallRepository toolCalls;
  private final Clock clock;

  /**
   * Registers the two lazy assistant recovery gauges using UTC as production observation time.
   *
   * <p>An unavailable database is reported as {@link Double#NaN}, not as a healthy zero.
   *
   * @param registry service meter registry receiving the fixed-name gauges
   * @param toolCalls service-owned tool-call read boundary
   */
  @Autowired
  public AssistantRecoveryMetrics(MeterRegistry registry, AssistantToolCallRepository toolCalls) {
    this(registry, toolCalls, Clock.systemUTC());
  }

  /**
   * Registers the gauges with an explicit observation clock for deterministic verification.
   *
   * <p>The clock calculates displayed age only; it is not a transition timestamp or recovery
   * fence.
   *
   * @param registry service meter registry receiving the fixed-name gauges
   * @param toolCalls service-owned tool-call read boundary
   * @param clock observation-only source for displayed age
   */
  AssistantRecoveryMetrics(
      MeterRegistry registry, AssistantToolCallRepository toolCalls, Clock clock) {
    this.toolCalls = toolCalls;
    this.clock = clock;

    Gauge.builder(STARTED_TOOL_CALLS, this, ignored -> startedCount())
        .description("Durable assistant tool calls still awaiting a terminal outcome")
        .register(registry);
    Gauge.builder(
            OLDEST_STARTED_TOOL_CALL_AGE_SECONDS,
            this,
            ignored -> oldestStartedAgeSeconds())
        .description("Age of the oldest durable assistant tool call still started")
        .register(registry);
  }

  /** Reads the current STARTED count, preserving database failure as an unknown observation. */
  private double startedCount() {
    try {
      return toolCalls.countByStatus(AssistantToolCallStatus.STARTED);
    } catch (DataAccessException exception) {
      return Double.NaN;
    }
  }

  /**
   * Calculates a non-negative displayed age for the oldest durable STARTED tool call.
   *
   * <p>An empty healthy set is zero, a future timestamp is clamped to zero, and a database failure
   * remains unknown.
   */
  private double oldestStartedAgeSeconds() {
    try {
      Optional<OffsetDateTime> oldest =
          toolCalls.findOldestCreatedAtByStatus(AssistantToolCallStatus.STARTED);
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
