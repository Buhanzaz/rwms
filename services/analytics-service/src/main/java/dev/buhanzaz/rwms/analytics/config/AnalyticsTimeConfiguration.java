package dev.buhanzaz.rwms.analytics.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the UTC time dependency used to make analytics as-of and recovery calculations testable. */
@Configuration(proxyBeanMethods = false)
class AnalyticsTimeConfiguration {
  @Bean
  Clock analyticsClock() {
    return Clock.systemUTC();
  }
}
