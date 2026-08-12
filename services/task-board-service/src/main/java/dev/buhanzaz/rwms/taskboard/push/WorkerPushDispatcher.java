package dev.buhanzaz.rwms.taskboard.push;

import java.time.Duration;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Delivers leased push-outbox events with bounded exponential retry and target revocation.
 *
 * <p>Retries may redeliver the same event to a target that already accepted it; this is deliberate
 * at-least-once behavior and Android deduplicates by event ID.
 */
@Component
@ConditionalOnProperty(
    prefix = "rwms.task-board.push.fcm",
    name = "enabled",
    havingValue = "true")
public class WorkerPushDispatcher {
  private static final Logger LOG = LoggerFactory.getLogger(WorkerPushDispatcher.class);
  private static final Duration MAX_RETRY_DELAY = Duration.ofMinutes(15);

  private final WorkerPushOutbox outbox;
  private final WorkerPushClient client;
  private final Duration leaseDuration;
  private final int batchSize;
  private final int maxAttempts;

  public WorkerPushDispatcher(
      WorkerPushOutbox outbox,
      WorkerPushClient client,
      @Value("${rwms.task-board.push.fcm.lease-duration:30s}") Duration leaseDuration,
      @Value("${rwms.task-board.push.fcm.batch-size:50}") int batchSize,
      @Value("${rwms.task-board.push.fcm.max-attempts:8}") int maxAttempts) {
    if (leaseDuration.isZero() || leaseDuration.isNegative()) {
      throw new IllegalArgumentException("FCM lease duration must be positive");
    }
    if (batchSize < 1 || batchSize > 500) {
      throw new IllegalArgumentException("FCM batch size must be between 1 and 500");
    }
    if (maxAttempts < 1 || maxAttempts > 20) {
      throw new IllegalArgumentException("FCM max attempts must be between 1 and 20");
    }
    this.outbox = outbox;
    this.client = client;
    this.leaseDuration = leaseDuration;
    this.batchSize = batchSize;
    this.maxAttempts = maxAttempts;
  }

  /** Claims and delivers one bounded batch without holding a transaction across FCM calls. */
  @Scheduled(fixedDelayString = "${rwms.task-board.push.fcm.dispatcher-delay:2s}")
  public void dispatch() {
    for (WorkerPushOutbox.PendingPush pending : outbox.claim(batchSize, leaseDuration)) {
      deliver(pending);
    }
  }

  private void deliver(WorkerPushOutbox.PendingPush pending) {
    List<WorkerPushClient.Target> targets =
        outbox.targets(pending.workerId(), pending.warehouseId());
    if (targets.isEmpty()) {
      outbox.markSent(pending.eventId());
      return;
    }
    WorkerPushClient.Message message =
        new WorkerPushClient.Message(
            pending.eventId(), pending.revision(), pending.eventType(), pending.entryId());
    for (WorkerPushClient.Target target : targets) {
      try {
        client.send(message, target);
      } catch (WorkerPushClient.DeliveryException exception) {
        if (exception.invalidTarget()) {
          outbox.revokeTarget(pending.workerId(), target);
          LOG.info(
              "Revoked invalid FCM installation {} for worker {}",
              target.installationId(),
              pending.workerId());
          continue;
        }
        if (exception.retryable() && pending.attemptCount() < maxAttempts) {
          Duration delay = retryDelay(pending.attemptCount());
          outbox.markRetry(pending.eventId(), delay, exception.getMessage());
          LOG.warn(
              "FCM event {} retry {} scheduled after {}",
              pending.eventId(),
              pending.attemptCount(),
              delay);
        } else {
          outbox.markDead(pending.eventId(), exception.getMessage());
          LOG.error(
              "FCM event {} exhausted or failed permanently after {} attempts",
              pending.eventId(),
              pending.attemptCount());
        }
        return;
      } catch (RuntimeException exception) {
        if (pending.attemptCount() < maxAttempts) {
          outbox.markRetry(
              pending.eventId(), retryDelay(pending.attemptCount()), "Unexpected FCM failure");
        } else {
          outbox.markDead(pending.eventId(), "Unexpected FCM failure");
        }
        LOG.warn("Unexpected FCM failure for event {}", pending.eventId(), exception);
        return;
      }
    }
    outbox.markSent(pending.eventId());
  }

  private Duration retryDelay(int attemptCount) {
    long seconds = Math.min(MAX_RETRY_DELAY.toSeconds(), 5L << Math.min(attemptCount - 1, 10));
    return Duration.ofSeconds(seconds);
  }
}
