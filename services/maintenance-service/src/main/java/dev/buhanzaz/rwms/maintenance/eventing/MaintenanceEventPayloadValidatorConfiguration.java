package dev.buhanzaz.rwms.maintenance.eventing;

import dev.buhanzaz.rwms.maintenance.domain.MaintenanceAggregateType;
import dev.buhanzaz.rwms.maintenance.domain.MaintenanceEventType;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import java.util.UUID;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import tools.jackson.databind.JsonNode;

/** Registers one payload safety validator for every exact maintenance event type. */
@Configuration(proxyBeanMethods = false)
public class MaintenanceEventPayloadValidatorConfiguration {
  @Bean
  static BeanDefinitionRegistryPostProcessor maintenancePayloadSafetyValidators() {
    return registry -> {
      for (MaintenanceEventType eventType : MaintenanceEventType.values()) {
        register(registry, eventType);
      }
    };
  }

  private static void register(
      BeanDefinitionRegistry registry, MaintenanceEventType eventType) {
    registry.registerBeanDefinition(
        "maintenancePayloadValidator_"
            + eventType.value().replace('.', '_').replace('-', '_'),
        BeanDefinitionBuilder.genericBeanDefinition(MaintenancePayloadSafetyValidator.class)
            .addConstructorArgValue(eventType)
            .addConstructorArgReference("maintenanceEventPayloadPolicy")
            .getBeanDefinition());
  }

  static final class MaintenancePayloadSafetyValidator
      implements RwmsKafkaPayloadSafetyValidator {
    private final MaintenanceEventType eventType;
    private final MaintenanceAggregateType aggregateType;
    private final MaintenanceEventPayloadPolicy policy;

    MaintenancePayloadSafetyValidator(
        MaintenanceEventType eventType, MaintenanceEventPayloadPolicy policy) {
      this.eventType = eventType;
      this.policy = policy;
      this.aggregateType = policy.aggregateFor(eventType);
    }

    @Override
    public String eventType() {
      return eventType.value();
    }

    @Override
    public void validate(JsonNode payload) {
      JsonNode identity = payload == null ? null : payload.get(identityField());
      if (identity == null || !identity.isTextual()) {
        throw new IllegalArgumentException("Maintenance event aggregate identity is missing");
      }
      UUID aggregateId;
      try {
        aggregateId = UUID.fromString(identity.stringValue());
      } catch (IllegalArgumentException exception) {
        throw new IllegalArgumentException("Maintenance event aggregate identity is invalid", exception);
      }
      policy.validateNode(eventType.value(), aggregateType, aggregateId, payload);
    }

    private String identityField() {
      return switch (aggregateType) {
        case CATALOG_VERSION -> "catalogVersionId";
        case ESTIMATE -> "estimateId";
        case REPAIR -> "repairId";
        case PROPERTY_DISPOSITION -> "decisionId";
      };
    }
  }
}
