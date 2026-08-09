package dev.buhanzaz.rwms.logistics.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

/**
 * Creates every canonical primary and sanitized-DLT logistics output channel only when Kafka is
 * enabled.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class LogisticsKafkaOutputChannelsConfiguration {
  /** Creates the canonical return aggregate output channel. */
  @Bean(name = LogisticsTransportTopics.RETURN)
  MessageChannel returnEvents() {
    return channel();
  }

  /** Creates the canonical shipment aggregate output channel. */
  @Bean(name = LogisticsTransportTopics.SHIPMENT)
  MessageChannel shipmentEvents() {
    return channel();
  }

  /** Creates the canonical transfer aggregate output channel. */
  @Bean(name = LogisticsTransportTopics.TRANSFER)
  MessageChannel transferEvents() {
    return channel();
  }

  /** Creates the canonical rental-inquiry event-family output channel. */
  @Bean(name = LogisticsTransportTopics.RENTAL_INQUIRY)
  MessageChannel rentalInquiryEvents() {
    return channel();
  }

  /** Creates the sanitized return DLT output channel. */
  @Bean(name = LogisticsTransportTopics.RETURN_DLT)
  MessageChannel returnDlt() {
    return channel();
  }

  /** Creates the sanitized shipment DLT output channel. */
  @Bean(name = LogisticsTransportTopics.SHIPMENT_DLT)
  MessageChannel shipmentDlt() {
    return channel();
  }

  /** Creates the sanitized transfer DLT output channel. */
  @Bean(name = LogisticsTransportTopics.TRANSFER_DLT)
  MessageChannel transferDlt() {
    return channel();
  }

  /** Creates the sanitized inbound-consumer DLT output channel. */
  @Bean(name = LogisticsTransportTopics.INBOUND_DLT)
  MessageChannel inboundDlt() {
    return channel();
  }

  /** Creates an imperative producer channel whose bean name is its exact destination. */
  private MessageChannel channel() {
    return new DirectWithAttributesChannel();
  }
}
