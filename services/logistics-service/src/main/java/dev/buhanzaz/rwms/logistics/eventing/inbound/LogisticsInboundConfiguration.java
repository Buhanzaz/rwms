package dev.buhanzaz.rwms.logistics.eventing.inbound;

import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class LogisticsInboundConfiguration {
  @Bean
  @ConditionalOnMissingBean(LogisticsRetryDelayer.class)
  LogisticsRetryDelayer logisticsRetryDelayer() {
    return duration -> {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Logistics Kafka retry was interrupted", exception);
      }
    };
  }
}
