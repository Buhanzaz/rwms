package dev.buhanzaz.rwms.logistics.service;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import org.springframework.stereotype.Component;

/**
 * Emits only fixed-cardinality recovery metrics. Owner and state tags are closed enums so attempt
 * IDs, operation IDs, remote payloads and exception text can never enter metric labels.
 */
@Component
class LogisticsExternalAttemptClaimMetrics {
  private static final String CLAIM_COUNTER = "rwms.logistics.external_attempt.claim";
  private static final String CLAIM_LATENCY = "rwms.logistics.external_attempt.claim.latency";

  private final Map<LogisticsExternalAttemptClaimService.Owner, Map<ClaimState, Counter>> counters;
  private final Map<LogisticsExternalAttemptClaimService.Owner, Timer> latencyTimers;

  /**
   * Eagerly registers every closed owner/state counter and owner latency timer.
   *
   * <p>Eager registration keeps the available label values auditable even before the first claim
   * and prevents runtime input from creating a new series.
   */
  LogisticsExternalAttemptClaimMetrics(MeterRegistry meterRegistry) {
    counters = new EnumMap<>(LogisticsExternalAttemptClaimService.Owner.class);
    latencyTimers = new EnumMap<>(LogisticsExternalAttemptClaimService.Owner.class);
    for (LogisticsExternalAttemptClaimService.Owner owner :
        LogisticsExternalAttemptClaimService.Owner.values()) {
      Map<ClaimState, Counter> ownerCounters = new EnumMap<>(ClaimState.class);
      for (ClaimState state : ClaimState.values()) {
        ownerCounters.put(
            state,
            Counter.builder(CLAIM_COUNTER)
                .tag("owner", owner.metricTag())
                .tag("state", state.metricTag)
                .register(meterRegistry));
      }
      counters.put(owner, ownerCounters);
      latencyTimers.put(
          owner,
          Timer.builder(CLAIM_LATENCY)
              .tag("owner", owner.metricTag())
              .description("Database lease-claim latency for one closed logistics workflow owner")
              .register(meterRegistry));
    }
  }

  /** Records that one or more attempts entered an owner-specific lease. */
  void claimed(LogisticsExternalAttemptClaimService.Owner owner, int count) {
    record(owner, ClaimState.CLAIMED, count);
  }

  /** Records a local prerequisite that was deferred without a remote request. */
  void deferred(LogisticsExternalAttemptClaimService.Owner owner) {
    record(owner, ClaimState.DEFERRED, 1);
  }

  /** Records a completion rejected because a newer lease or row version won the fence. */
  void stale(LogisticsExternalAttemptClaimService.Owner owner) {
    record(owner, ClaimState.STALE, 1);
  }

  /** Records one database claim operation without adding an exception or attempt-identity label. */
  void claimLatency(LogisticsExternalAttemptClaimService.Owner owner, long elapsedNanoseconds) {
    Timer timer = latencyTimers.get(owner);
    if (timer == null) {
      throw new IllegalArgumentException("Known external-attempt owner is required");
    }
    timer.record(Math.max(0, elapsedNanoseconds), TimeUnit.NANOSECONDS);
  }

  private void record(
      LogisticsExternalAttemptClaimService.Owner owner, ClaimState state, int count) {
    if (count <= 0) return;
    Map<ClaimState, Counter> ownerCounters = counters.get(owner);
    if (ownerCounters == null) {
      throw new IllegalArgumentException("Known external-attempt owner is required");
    }
    ownerCounters.get(state).increment(count);
  }

  /** Closed counter states that cannot carry error text or durable work identity. */
  private enum ClaimState {
    CLAIMED("claimed"),
    DEFERRED("deferred"),
    STALE("stale");

    private final String metricTag;

    ClaimState(String metricTag) {
      this.metricTag = metricTag;
    }
  }
}
