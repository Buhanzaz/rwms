package dev.buhanzaz.rwms.analytics.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class AnalyticsTimeConfiguration {
  @Bean
  Clock analyticsClock() {
    return Clock.systemUTC();
  }
}
