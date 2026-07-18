package dev.buhanzaz.rwms.logistics.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class LogisticsKafkaOutputChannelsConfiguration {
  @Bean(name = "rwms.logistics.return.v1")
  MessageChannel returnEvents() {
    return channel();
  }

  @Bean(name = "rwms.logistics.shipment.v1")
  MessageChannel shipmentEvents() {
    return channel();
  }

  @Bean(name = "rwms.logistics.transfer.v1")
  MessageChannel transferEvents() {
    return channel();
  }

  @Bean(name = "rwms.logistics.return.v1.logistics-service.dlt")
  MessageChannel returnDlt() {
    return channel();
  }

  @Bean(name = "rwms.logistics.shipment.v1.logistics-service.dlt")
  MessageChannel shipmentDlt() {
    return channel();
  }

  @Bean(name = "rwms.logistics.transfer.v1.logistics-service.dlt")
  MessageChannel transferDlt() {
    return channel();
  }

  @Bean(name = "rwms.logistics.inbound.v1.dlt")
  MessageChannel inboundDlt() {
    return channel();
  }

  private MessageChannel channel() {
    return new DirectWithAttributesChannel();
  }
}
