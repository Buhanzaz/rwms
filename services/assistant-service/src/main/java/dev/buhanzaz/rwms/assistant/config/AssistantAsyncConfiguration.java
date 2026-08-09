package dev.buhanzaz.rwms.assistant.config;

import java.util.concurrent.ThreadPoolExecutor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

/** Configures the bounded executor that isolates long-running provider turns from request-handling threads. */
@Configuration
public class AssistantAsyncConfiguration {
  @Bean("assistantTurnExecutor")
  ThreadPoolTaskExecutor assistantTurnExecutor() {
    ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
    executor.setCorePoolSize(2);
    executor.setMaxPoolSize(8);
    executor.setQueueCapacity(64);
    executor.setThreadNamePrefix("assistant-turn-");
    executor.setRejectedExecutionHandler(new ThreadPoolExecutor.AbortPolicy());
    executor.initialize();
    return executor;
  }
}
