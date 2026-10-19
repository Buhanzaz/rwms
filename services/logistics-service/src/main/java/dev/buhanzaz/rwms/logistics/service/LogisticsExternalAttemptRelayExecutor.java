package dev.buhanzaz.rwms.logistics.service;

import jakarta.annotation.PreDestroy;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.stereotype.Component;

/**
 * Applies an explicit finite permit budget per workflow owner before submitting remote work to the
 * bounded executor. It is technical scheduling infrastructure only: it does not inspect attempts,
 * documents or dependency payloads.
 */
@Component
class LogisticsExternalAttemptRelayExecutor {
  private final ThreadPoolTaskExecutor workerExecutor;
  private final Map<LogisticsExternalAttemptClaimService.Owner, Semaphore> ownerPermits;
  private final AtomicBoolean accepting = new AtomicBoolean(true);

  LogisticsExternalAttemptRelayExecutor(
      @Qualifier("logisticsExternalAttemptWorkerExecutor") ThreadPoolTaskExecutor workerExecutor,
      LogisticsExternalAttemptClaimProperties properties) {
    this.workerExecutor = workerExecutor;
    ownerPermits = new EnumMap<>(LogisticsExternalAttemptClaimService.Owner.class);
    ownerPermits.put(
        LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
        new Semaphore(properties.returnRegistrationBudget()));
    ownerPermits.put(
        LogisticsExternalAttemptClaimService.Owner.RETURN_COMPLETION,
        new Semaphore(properties.returnCompletionBudget()));
    ownerPermits.put(
        LogisticsExternalAttemptClaimService.Owner.SHIPMENT,
        new Semaphore(properties.shipmentBudget()));
    ownerPermits.put(
        LogisticsExternalAttemptClaimService.Owner.TRANSFER,
        new Semaphore(properties.transferBudget()));
    ownerPermits.put(
        LogisticsExternalAttemptClaimService.Owner.MEDIA_OWNER_PROOF,
        new Semaphore(properties.mediaOwnerProofBudget()));
  }

  /**
   * Returns the owner's currently free worker capacity. Relays use this snapshot only to bound
   * claim attempts; {@link #submit} atomically rechecks it before consuming a permit.
   */
  int availablePermits(LogisticsExternalAttemptClaimService.Owner owner) {
    return permits(owner).availablePermits();
  }

  /**
   * Attempts to submit one already-claimed operation. A queue rejection releases the permit so the
   * relay can immediately defer and clear the database lease instead of stranding it.
   */
  boolean submit(LogisticsExternalAttemptClaimService.Owner owner, Runnable operation) {
    if (operation == null || !accepting.get()) return false;
    Semaphore permits = permits(owner);
    if (!permits.tryAcquire()) return false;
    if (!accepting.get()) {
      permits.release();
      return false;
    }
    try {
      workerExecutor.execute(
          () -> {
            try {
              operation.run();
            } finally {
              permits.release();
            }
          });
      return true;
    } catch (RejectedExecutionException exception) {
      permits.release();
      return false;
    }
  }

  /**
   * Prevents new claims from entering the worker during graceful shutdown. Existing tasks retain
   * their permits until completion; if a process stops before one completes, its database lease is
   * intentionally recovered by expiry rather than being silently marked finished.
   */
  @PreDestroy
  public void stopAccepting() {
    accepting.set(false);
  }

  private Semaphore permits(LogisticsExternalAttemptClaimService.Owner owner) {
    if (owner == null || !ownerPermits.containsKey(owner)) {
      throw new IllegalArgumentException("Known external-attempt owner is required");
    }
    return ownerPermits.get(owner);
  }
}
