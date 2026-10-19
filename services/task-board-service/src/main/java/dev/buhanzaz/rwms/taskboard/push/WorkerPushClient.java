package dev.buhanzaz.rwms.taskboard.push;

import java.util.UUID;

/**
 * Sends one data-only task invalidation to one registered native installation.
 *
 * <p>The transport carries no task snapshot. Android refreshes its authorized feed and deduplicates
 * at-least-once delivery by {@link Message#eventId()}.
 */
public interface WorkerPushClient {

  /** Sends one message or throws a classified failure for bounded outbox recovery. */
  void send(Message message, Target target) throws DeliveryException;

  /**
   * Immutable notification data shared with the WorkerApp FCM receiver.
   *
   * @param eventId stable deduplication identity
   * @param revision task-feed revision after the driver action
   * @param type invalidation type
   * @param entryId affected task-board entry
   */
  record Message(UUID eventId, long revision, String type, UUID entryId) {}

  /**
   * One server-owned Firebase target.
   *
   * @param installationId local installation identity used for revocation
   * @param targetKind {@code FID} or legacy {@code TOKEN}
   * @param value Firebase Installation ID or registration token
   */
  record Target(String installationId, String targetKind, String value) {}

  /**
   * Classified provider failure used to distinguish invalid installations from retryable outages.
   */
  final class DeliveryException extends Exception {
    private final boolean invalidTarget;
    private final boolean retryable;

    /** Creates a provider failure without retaining credential or payload secrets. */
    public DeliveryException(
        String message, boolean invalidTarget, boolean retryable, Throwable cause) {
      super(message, cause);
      this.invalidTarget = invalidTarget;
      this.retryable = retryable;
    }

    /** Returns whether this exact installation must be revoked. */
    public boolean invalidTarget() {
      return invalidTarget;
    }

    /** Returns whether the outbox may retry after a bounded delay. */
    public boolean retryable() {
      return retryable;
    }
  }
}
