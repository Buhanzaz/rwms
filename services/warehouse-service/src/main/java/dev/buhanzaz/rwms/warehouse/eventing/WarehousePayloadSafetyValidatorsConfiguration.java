package dev.buhanzaz.rwms.warehouse.eventing;

import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/** Registers strict payload validators for every warehouse event type published through Kafka. */
@Configuration(proxyBeanMethods = false)
public class WarehousePayloadSafetyValidatorsConfiguration {
  @Bean
  RwmsKafkaPayloadSafetyValidator warehouseCreatedPayloadSafetyValidator(
      WarehouseEventPayloadPolicy policy) {
    return new WarehousePayloadSafetyValidator(WarehouseEventType.CREATED, policy);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator warehouseChangedPayloadSafetyValidator(
      WarehouseEventPayloadPolicy policy) {
    return new WarehousePayloadSafetyValidator(WarehouseEventType.CHANGED, policy);
  }

  @Bean
  RwmsKafkaPayloadSafetyValidator warehouseDeactivatedPayloadSafetyValidator(
      WarehouseEventPayloadPolicy policy) {
    return new WarehousePayloadSafetyValidator(WarehouseEventType.DEACTIVATED, policy);
  }
}
