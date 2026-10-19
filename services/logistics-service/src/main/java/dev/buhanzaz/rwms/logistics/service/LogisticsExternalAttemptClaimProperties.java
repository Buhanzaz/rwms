package dev.buhanzaz.rwms.logistics.service;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Min;
import java.time.Duration;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

/**
 * Binds the finite lease, page, worker and per-owner limits for recovery of durable external
 * attempts. These values bound local resource use; they never change the business meaning of an
 * attempt or its target-service idempotency key.
 */
@ConfigurationProperties("rwms.logistics.external-attempt-claim")
@Validated
public class LogisticsExternalAttemptClaimProperties {
  private Duration leaseDuration = Duration.ofSeconds(30);

  @Min(1)
  private int maximumPageSize = 64;

  private Duration blockedWorkDelay = Duration.ofSeconds(1);

  @Min(2)
  private int workerPoolSize = 8;

  @Min(0)
  private int workerQueueCapacity = 32;

  private Duration workerShutdownTimeout = Duration.ofSeconds(30);

  @Min(1)
  private int returnRegistrationBudget = 4;

  @Min(1)
  private int returnCompletionBudget = 4;

  @Min(1)
  private int shipmentBudget = 4;

  @Min(1)
  private int transferBudget = 4;

  @Min(1)
  private int mediaOwnerProofBudget = 2;

  public Duration leaseDuration() {
    return leaseDuration;
  }

  public void setLeaseDuration(Duration leaseDuration) {
    this.leaseDuration = leaseDuration;
  }

  public int maximumPageSize() {
    return maximumPageSize;
  }

  public void setMaximumPageSize(int maximumPageSize) {
    this.maximumPageSize = maximumPageSize;
  }

  public Duration blockedWorkDelay() {
    return blockedWorkDelay;
  }

  public void setBlockedWorkDelay(Duration blockedWorkDelay) {
    this.blockedWorkDelay = blockedWorkDelay;
  }

  public int workerPoolSize() {
    return workerPoolSize;
  }

  public void setWorkerPoolSize(int workerPoolSize) {
    this.workerPoolSize = workerPoolSize;
  }

  public int workerQueueCapacity() {
    return workerQueueCapacity;
  }

  public void setWorkerQueueCapacity(int workerQueueCapacity) {
    this.workerQueueCapacity = workerQueueCapacity;
  }

  public Duration workerShutdownTimeout() {
    return workerShutdownTimeout;
  }

  public void setWorkerShutdownTimeout(Duration workerShutdownTimeout) {
    this.workerShutdownTimeout = workerShutdownTimeout;
  }

  public int returnRegistrationBudget() {
    return returnRegistrationBudget;
  }

  public void setReturnRegistrationBudget(int returnRegistrationBudget) {
    this.returnRegistrationBudget = returnRegistrationBudget;
  }

  public int returnCompletionBudget() {
    return returnCompletionBudget;
  }

  public void setReturnCompletionBudget(int returnCompletionBudget) {
    this.returnCompletionBudget = returnCompletionBudget;
  }

  public int shipmentBudget() {
    return shipmentBudget;
  }

  public void setShipmentBudget(int shipmentBudget) {
    this.shipmentBudget = shipmentBudget;
  }

  public int transferBudget() {
    return transferBudget;
  }

  public void setTransferBudget(int transferBudget) {
    this.transferBudget = transferBudget;
  }

  public int mediaOwnerProofBudget() {
    return mediaOwnerProofBudget;
  }

  public void setMediaOwnerProofBudget(int mediaOwnerProofBudget) {
    this.mediaOwnerProofBudget = mediaOwnerProofBudget;
  }

  /**
   * Validates recovery capacity and timing limits before recovery infrastructure starts accepting
   * work.
   */
  @PostConstruct
  public void validateTemporalLimits() {
    requirePositive(leaseDuration, "leaseDuration");
    requirePositive(blockedWorkDelay, "blockedWorkDelay");
    requireAtLeastOneSecond(workerShutdownTimeout, "workerShutdownTimeout");
    requireOwnerBudgetBelowWorkerPool(returnRegistrationBudget, "returnRegistrationBudget");
    requireOwnerBudgetBelowWorkerPool(returnCompletionBudget, "returnCompletionBudget");
    requireOwnerBudgetBelowWorkerPool(shipmentBudget, "shipmentBudget");
    requireOwnerBudgetBelowWorkerPool(transferBudget, "transferBudget");
    requireOwnerBudgetBelowWorkerPool(mediaOwnerProofBudget, "mediaOwnerProofBudget");
  }

  private static void requirePositive(Duration value, String field) {
    if (value == null || value.isZero() || value.isNegative()) {
      throw new IllegalArgumentException(field + " must be positive");
    }
  }

  /**
   * Keeps the duration compatible with Spring's whole-second graceful-shutdown API. Rejecting a
   * sub-second value is safer than silently reducing an operator's requested grace period to zero.
   */
  private static void requireAtLeastOneSecond(Duration value, String field) {
    requirePositive(value, field);
    if (value.compareTo(Duration.ofSeconds(1)) < 0) {
      throw new IllegalArgumentException(field + " must be at least one second");
    }
  }

  /**
   * Reserves at least one worker thread for another owner, so one saturated dependency cannot
   * consume the complete remote-call pool merely through its own configured permit budget.
   */
  private void requireOwnerBudgetBelowWorkerPool(int budget, String field) {
    if (budget >= workerPoolSize) {
      throw new IllegalArgumentException(field + " must be lower than workerPoolSize");
    }
  }
}
