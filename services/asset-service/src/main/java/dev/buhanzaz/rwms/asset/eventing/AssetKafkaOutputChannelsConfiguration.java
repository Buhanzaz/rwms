package dev.buhanzaz.rwms.asset.eventing;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.cloud.stream.messaging.DirectWithAttributesChannel;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.messaging.MessageChannel;

/**
 * Declares the asset Kafka output channels used by the transactional-outbox relay.
 */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "rwms.platform.kafka", name = "enabled", havingValue = "true")
class AssetKafkaOutputChannelsConfiguration {
  @Bean(name = "rwms.asset.rental-item.v1") MessageChannel rentalItem() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.equipment-catalog.v1") MessageChannel equipmentCatalog() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.equipment-balance.v1") MessageChannel equipmentBalance() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.equipment-movement.v1") MessageChannel equipmentMovement() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.equipment-allocation-hold.v1") MessageChannel equipmentAllocationHold() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.operation-lease.v1") MessageChannel operationLease() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.classifier.v1") MessageChannel classifier() { return new DirectWithAttributesChannel(); }
  @Bean(name = "rwms.asset.dlt.v1") MessageChannel sanitizedDlt() { return new DirectWithAttributesChannel(); }
}
