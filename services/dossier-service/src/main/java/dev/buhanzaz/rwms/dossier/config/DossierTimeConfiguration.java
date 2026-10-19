package dev.buhanzaz.rwms.dossier.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Provides the UTC Clock used by query, projection and recovery code for deterministic time semantics. */
@Configuration(proxyBeanMethods = false)
class DossierTimeConfiguration {
  @Bean
  Clock dossierClock() {
    return Clock.systemUTC();
  }
}
