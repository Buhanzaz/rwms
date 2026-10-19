package dev.buhanzaz.rwms.logistics.service;

import org.springframework.boot.task.ThreadPoolTaskSchedulerBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.TaskScheduler;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

/**
 * Separates lightweight recovery triggers from bounded remote-call workers. Scheduled relays only
 * claim and submit; the worker pool is the sole executor that can spend time in a dependency call.
 */
@Configuration
class LogisticsExternalAttemptSchedulerConfiguration {
  /**
   * Restores Spring Boot's ordinary scheduler under its conventional name. Defining the dedicated
   * trigger scheduler otherwise makes Boot back off its default scheduler and would accidentally
   * route unrelated scheduled work through the recovery trigger's single thread.
   */
  @Bean(name = "taskScheduler")
  ThreadPoolTaskScheduler taskScheduler(ThreadPoolTaskSchedulerBuilder builder) {
    return builder.build();
  }

  /** Creates the single-thread scheduler used only by the five external-attempt relay triggers. */
  @Bean(name = "logisticsExternalAttemptTriggerScheduler")
  TaskScheduler logisticsExternalAttemptTriggerScheduler(
      LogisticsExternalAttemptClaimProperties properties) {
    ThreadPoolTaskScheduler scheduler = new ThreadPoolTaskScheduler();
    scheduler.setPoolSize(1);
    scheduler.setThreadNamePrefix("logistics-attempt-trigger-");
    scheduler.setWaitForTasksToCompleteOnShutdown(true);
    scheduler.setAwaitTerminationSeconds(shutdownSeconds(properties));
    return scheduler;
  }

  /**
   * Creates the finite remote-call executor. Its bounded queue and separate thread group stop one
   * slow owner from occupying the scheduler that must continue offering permits to other owners.
   */
  @Bean(name = "logisticsExternalAttemptWorkerExecutor")
  ThreadPoolTaskExecutor logisticsExternalAttemptWorkerExecutor(
      LogisticsExternalAttemptClaimProperties properties) {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(properties.workerPoolSize());
    executor.setMaxPoolSize(properties.workerPoolSize());
    executor.setQueueCapacity(properties.workerQueueCapacity());
    executor.setThreadNamePrefix("logistics-attempt-worker-");
    executor.setWaitForTasksToCompleteOnShutdown(true);
    executor.setAwaitTerminationSeconds(shutdownSeconds(properties));
    return executor;
  }

  private static int shutdownSeconds(LogisticsExternalAttemptClaimProperties properties) {
    return Math.toIntExact(properties.workerShutdownTimeout().toSeconds());
  }
}
