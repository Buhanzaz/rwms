package dev.buhanzaz.rwms.maintenance.eventing.transport;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

/**
 * Creates all five maintenance Kafka output channels only when transport is explicitly enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class MaintenanceKafkaOutputChannelsConfiguration {
  @Bean(name = MaintenanceTransportTopics.CATALOG)
  MessageChannel catalog() {
    return new DirectWithAttributesChannel();
  }

  @Bean(name = MaintenanceTransportTopics.ESTIMATE)
  MessageChannel estimate() {
    return new DirectWithAttributesChannel();
  }

  @Bean(name = MaintenanceTransportTopics.REPAIR)
  MessageChannel repair() {
    return new DirectWithAttributesChannel();
  }

  /** Creates the canonical property-disposition owner channel used by the ordered output binder. */
  @Bean(name = MaintenanceTransportTopics.PROPERTY_DISPOSITION)
  MessageChannel propertyDisposition() {
    return new DirectWithAttributesChannel();
  }

  @Bean(name = MaintenanceTransportTopics.SANITIZED_DLT)
  MessageChannel sanitizedDlt() {
    return new DirectWithAttributesChannel();
  }
}
