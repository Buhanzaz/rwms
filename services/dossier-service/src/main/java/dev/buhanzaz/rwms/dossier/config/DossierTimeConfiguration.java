package dev.buhanzaz.rwms.dossier.config;

import java.time.Clock;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration(proxyBeanMethods = false)
class DossierTimeConfiguration {
  @Bean
  Clock dossierClock() {
    return Clock.systemUTC();
  }
}
