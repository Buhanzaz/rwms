package dev.buhanzaz.rwms.maintenance.eventing.transport;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration(proxyBeanMethods = false)
@EnableScheduling
@EnableConfigurationProperties({MaintenanceOutboxProperties.class, RwmsKafkaProperties.class})
public class MaintenanceTransportConfiguration {
  @Bean
  @ConditionalOnMissingBean(MaintenanceInboundEffects.class)
  MaintenanceInboundEffects failClosedMaintenanceInboundEffects() {
    return (event, correlation) -> {
      throw new IllegalStateException(
          "Maintenance inbound domain effects are unavailable for " + event.sourceTopic());
    };
  }

  @Bean
  @ConditionalOnMissingBean(MaintenanceRetryDelayer.class)
  MaintenanceRetryDelayer maintenanceRetryDelayer() {
    return duration -> {
      try {
        Thread.sleep(duration);
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("Maintenance Kafka retry was interrupted", exception);
      }
    };
  }
}
