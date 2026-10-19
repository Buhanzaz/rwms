package dev.buhanzaz.rwms.warehouse.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

/** Declares the service-local output channel named after the canonical warehouse topic. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class WarehouseKafkaOutputChannelsConfiguration {
  @Bean(name = "rwms.warehouse.warehouse.v1")
  MessageChannel warehouse() {
    return new DirectWithAttributesChannel();
  }
}
