package dev.buhanzaz.rwms.logistics.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/**
 * Verifies that recovery permits constrain each owner independently while the shared remote worker
 * pool remains capable of advancing another owner and of stopping new submissions during shutdown.
 */
class LogisticsExternalAttemptRelayExecutorTest {
  private ThreadPoolTaskExecutor worker;

  @AfterEach
  void shutDownWorker() {
    if (worker != null) worker.shutdown();
  }

  @Test
  void slowReturnRegistrationDoesNotBlockShipmentAndShutdownReleasesItsPermit() throws Exception {
    LogisticsExternalAttemptRelayExecutor relayExecutor = relayExecutor();
    CountDownLatch returnStarted = new CountDownLatch(1);
    CountDownLatch releaseReturn = new CountDownLatch(1);
    CountDownLatch returnFinished = new CountDownLatch(1);
    CountDownLatch shipmentFinished = new CountDownLatch(1);

    assertThat(
            relayExecutor.submit(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                () -> {
                  returnStarted.countDown();
                  try {
                    if (!releaseReturn.await(5, TimeUnit.SECONDS)) {
                      throw new AssertionError("Test did not release the slow return operation");
                    }
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new AssertionError(exception);
                  } finally {
                    returnFinished.countDown();
                  }
                }))
        .isTrue();
    assertThat(returnStarted.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(
            relayExecutor.submit(
                LogisticsExternalAttemptClaimService.Owner.SHIPMENT, shipmentFinished::countDown))
        .isTrue();

    assertThat(shipmentFinished.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(
            relayExecutor.availablePermits(
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION))
        .isZero();

    relayExecutor.stopAccepting();
    assertThat(
            relayExecutor.submit(
                LogisticsExternalAttemptClaimService.Owner.MEDIA_OWNER_PROOF,
                () -> {
                  throw new AssertionError("Shutdown must reject new recovery work");
                }))
        .isFalse();
    releaseReturn.countDown();
    assertThat(returnFinished.await(1, TimeUnit.SECONDS)).isTrue();
    assertThat(
            awaitAvailablePermits(
                relayExecutor,
                LogisticsExternalAttemptClaimService.Owner.RETURN_REGISTRATION,
                Duration.ofSeconds(1)))
        .isEqualTo(1);
  }

  @Test
  void rejectsSubSecondShutdownTimeoutInsteadOfSilentlyConfiguringZeroSeconds() {
    LogisticsExternalAttemptClaimProperties properties = new LogisticsExternalAttemptClaimProperties();
    properties.setWorkerShutdownTimeout(Duration.ofMillis(999));

    assertThatThrownBy(properties::validateTemporalLimits)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("workerShutdownTimeout must be at least one second");
  }

  @Test
  void rejectsAnOwnerBudgetThatCouldConsumeEveryRemoteWorker() {
    LogisticsExternalAttemptClaimProperties properties = new LogisticsExternalAttemptClaimProperties();
    properties.setWorkerPoolSize(2);
    properties.setReturnRegistrationBudget(2);

    assertThatThrownBy(properties::validateTemporalLimits)
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("returnRegistrationBudget must be lower than workerPoolSize");
  }

  /**
   * Waits for the executor wrapper to release the owner permit after the operation has returned.
   * The operation's completion latch fires from inside the wrapped runnable, immediately before
   * the wrapper's {@code finally} block performs that release.
   */
  private static int awaitAvailablePermits(
      LogisticsExternalAttemptRelayExecutor relayExecutor,
      LogisticsExternalAttemptClaimService.Owner owner,
      Duration timeout)
      throws InterruptedException {
    long deadline = System.nanoTime() + timeout.toNanos();
    int availablePermits = relayExecutor.availablePermits(owner);
    while (availablePermits == 0 && System.nanoTime() < deadline) {
      TimeUnit.MILLISECONDS.sleep(10);
      availablePermits = relayExecutor.availablePermits(owner);
    }
    return availablePermits;
  }

  private LogisticsExternalAttemptRelayExecutor relayExecutor() {
    LogisticsExternalAttemptClaimProperties properties = new LogisticsExternalAttemptClaimProperties();
    properties.setWorkerPoolSize(2);
    properties.setWorkerQueueCapacity(2);
    properties.setReturnRegistrationBudget(1);
    properties.setReturnCompletionBudget(1);
    properties.setShipmentBudget(1);
    properties.setTransferBudget(1);
    properties.setMediaOwnerProofBudget(1);
    properties.validateTemporalLimits();

    worker = new ThreadPoolTaskExecutor();
    worker.setCorePoolSize(2);
    worker.setMaxPoolSize(2);
    worker.setQueueCapacity(2);
    worker.initialize();
    return new LogisticsExternalAttemptRelayExecutor(worker, properties);
  }
}
