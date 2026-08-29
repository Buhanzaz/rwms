package dev.buhanzaz.rwms.logistics.service;

import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;

/** Timings that fence transfer cargo holds and post-arrival resource availability. */
@ConfigurationProperties(prefix = "rwms.logistics.transfer.plan-workflow")
public record TransferPlanWorkflowProperties(
    Duration reservationGrace, Duration resourceArrivalBuffer) {

  /** Applies safe operational defaults when an environment omits the new optional settings. */
  public TransferPlanWorkflowProperties {
    reservationGrace = reservationGrace == null ? Duration.ofHours(24) : reservationGrace;
    resourceArrivalBuffer =
        resourceArrivalBuffer == null ? Duration.ofMinutes(40) : resourceArrivalBuffer;
    if (reservationGrace.isNegative()
        || reservationGrace.isZero()
        || resourceArrivalBuffer.isNegative()) {
      throw new IllegalArgumentException("Transfer plan workflow durations are invalid");
    }
  }
}
