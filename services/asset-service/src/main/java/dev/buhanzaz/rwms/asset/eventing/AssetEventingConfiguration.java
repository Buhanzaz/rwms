package dev.buhanzaz.rwms.asset.eventing;

import dev.buhanzaz.rwms.asset.domain.AssetAggregateType;
import dev.buhanzaz.rwms.asset.domain.AssetEventType;
import dev.buhanzaz.rwms.asset.service.AssetIdempotencyProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaProperties;
import dev.buhanzaz.rwms.platform.kafka.RwmsKafkaPayloadSafetyValidator;
import java.util.Map;
import org.springframework.beans.factory.support.BeanDefinitionBuilder;
import org.springframework.beans.factory.support.BeanDefinitionRegistry;
import org.springframework.beans.factory.support.BeanDefinitionRegistryPostProcessor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import tools.jackson.databind.JsonNode;

/**
 * Registers payload validators that bind every asset event type to its permitted aggregate type
 * before an envelope can be accepted for transport.
 */
@Configuration
@EnableScheduling
@EnableConfigurationProperties({AssetOutboxProperties.class, AssetIdempotencyProperties.class, RwmsKafkaProperties.class})
public class AssetEventingConfiguration {
  private static final Map<AssetEventType, AssetAggregateType> AGGREGATES = Map.ofEntries(
      Map.entry(AssetEventType.RENTAL_ITEM_CREATED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_PASSPORT_CHANGED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_STATUS_CHANGED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_WAREHOUSE_CHANGED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_LOGISTICS_EFFECT_APPLIED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_INVENTORY_VISIBILITY_CHANGED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_GENERAL_COMMENT_CHANGED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.RENTAL_ITEM_MANUAL_NOTE_ADDED, AssetAggregateType.RENTAL_ITEM),
      Map.entry(AssetEventType.EQUIPMENT_CATALOG_CREATED, AssetAggregateType.EQUIPMENT_CATALOG),
      Map.entry(AssetEventType.EQUIPMENT_CATALOG_CHANGED, AssetAggregateType.EQUIPMENT_CATALOG),
      Map.entry(AssetEventType.EQUIPMENT_BALANCE_CHANGED, AssetAggregateType.EQUIPMENT_BALANCE),
      Map.entry(AssetEventType.EQUIPMENT_TRANSFERRED, AssetAggregateType.EQUIPMENT_MOVEMENT),
      Map.entry(AssetEventType.EQUIPMENT_WRITTEN_OFF, AssetAggregateType.EQUIPMENT_MOVEMENT),
      Map.entry(AssetEventType.EQUIPMENT_LOST, AssetAggregateType.EQUIPMENT_MOVEMENT),
      Map.entry(AssetEventType.EQUIPMENT_HOLD_ACQUIRED, AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD),
      Map.entry(AssetEventType.EQUIPMENT_HOLD_RENEWED, AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD),
      Map.entry(AssetEventType.EQUIPMENT_HOLD_COMMITTED, AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD),
      Map.entry(AssetEventType.EQUIPMENT_HOLD_RELEASED, AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD),
      Map.entry(AssetEventType.EQUIPMENT_HOLD_EXPIRED, AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD),
      Map.entry(AssetEventType.EQUIPMENT_HOLD_EXECUTED, AssetAggregateType.EQUIPMENT_ALLOCATION_HOLD),
      Map.entry(AssetEventType.OPERATION_LEASE_ACQUIRED, AssetAggregateType.OPERATION_LEASE),
      Map.entry(AssetEventType.OPERATION_LEASE_RENEWED, AssetAggregateType.OPERATION_LEASE),
      Map.entry(AssetEventType.OPERATION_LEASE_RELEASED, AssetAggregateType.OPERATION_LEASE),
      Map.entry(AssetEventType.OPERATION_LEASE_EXPIRED, AssetAggregateType.OPERATION_LEASE),
      Map.entry(AssetEventType.CLASSIFIER_CREATED, AssetAggregateType.CLASSIFIER),
      Map.entry(AssetEventType.CLASSIFIER_CHANGED, AssetAggregateType.CLASSIFIER));

  @Bean
  static BeanDefinitionRegistryPostProcessor assetPayloadValidators() {
    return registry -> AGGREGATES.forEach((type, aggregate) -> register(registry, type, aggregate));
  }

  private static void register(BeanDefinitionRegistry registry, AssetEventType type, AssetAggregateType aggregate) {
    registry.registerBeanDefinition("assetPayloadValidator_" + type.value().replace('.', '_').replace('-', '_'),
        BeanDefinitionBuilder.genericBeanDefinition(AssetPayloadSafetyValidator.class)
            .addConstructorArgValue(type.value()).addConstructorArgValue(aggregate)
            .addConstructorArgReference("assetEventPayloadPolicy").getBeanDefinition());
  }

  static final class AssetPayloadSafetyValidator implements RwmsKafkaPayloadSafetyValidator {
    private final String eventType;
    private final AssetAggregateType aggregateType;
    private final AssetEventPayloadPolicy policy;
    AssetPayloadSafetyValidator(String eventType, AssetAggregateType aggregateType, AssetEventPayloadPolicy policy) {
      this.eventType = eventType; this.aggregateType = aggregateType; this.policy = policy;
    }
    @Override public String eventType() { return eventType; }
    @Override public void validate(JsonNode payload) {
      String field = switch (aggregateType) {
        case RENTAL_ITEM -> "rentalItemId";
        case EQUIPMENT_CATALOG -> "equipmentId";
        case EQUIPMENT_BALANCE -> "balanceId";
        case EQUIPMENT_MOVEMENT -> "movementId";
        case EQUIPMENT_ALLOCATION_HOLD -> "holdId";
        case OPERATION_LEASE -> "leaseId";
        case CLASSIFIER -> "classifierId";
      };
      JsonNode identity = payload.get(field);
      if (identity == null || !identity.isTextual()) throw new IllegalArgumentException("Asset event identity is missing");
      policy.validateNode(eventType, aggregateType, java.util.UUID.fromString(identity.stringValue()), payload);
    }
  }
}
