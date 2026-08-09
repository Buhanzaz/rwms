package dev.buhanzaz.rwms.asset.domain;

import java.util.List;

/**
 * Enumerates permitted asset aggregate type values in the asset persistent workflow state.
 */
public enum AssetAggregateType {
  RENTAL_ITEM("rwms.asset.rental-item.v1"),
  EQUIPMENT_CATALOG("rwms.asset.equipment-catalog.v1"),
  EQUIPMENT_BALANCE("rwms.asset.equipment-balance.v1"),
  EQUIPMENT_MOVEMENT("rwms.asset.equipment-movement.v1"),
  EQUIPMENT_ALLOCATION_HOLD("rwms.asset.equipment-allocation-hold.v1"),
  OPERATION_LEASE("rwms.asset.operation-lease.v1"),
  CLASSIFIER("rwms.asset.classifier.v1");

  public static final String CONSUMER_GROUP = "asset-service-inbox-v1";
  public static final String SANITIZED_DLT_TOPIC = "rwms.asset.dlt.v1";

  private final String topic;

  AssetAggregateType(String topic) {
    this.topic = topic;
  }

  public String topic() {
    return topic;
  }

  public List<String> outputDestinations() {
    return List.of(topic, SANITIZED_DLT_TOPIC);
  }

  public static AssetAggregateType requireTopic(String value) {
    for (AssetAggregateType type : values()) {
      if (type.topic.equals(value)) return type;
    }
    throw new IllegalArgumentException("Unsupported asset aggregate-family topic");
  }
}
